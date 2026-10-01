# AGENTS.md — nuxeo-advanced-document-blob-audit

Working notes for AI agents picking this repository up. Read this before touching the code.
Functional documentation lives in `README.md`.

## What this plugin does

Audits **what actually changed inside a binary** between two successive Nuxeo versions of a
document: Excel cell by cell, PowerPoint slide by slide, Word/PDF paragraph by paragraph, text
formats line by line.

Pipeline:

1. `BlobModificationListener` (synchronous, on `aboutToCheckIn` + `documentCheckedIn`, both fired
   on the live document) compares the digests of the blob on the previous and the new version.
2. If they differ, it fires a `blobContentModified` audit entry (**no business data**: only xpath,
   filenames and a `diffCorrelationId`) and schedules a `BlobDiffWork`.
3. `BlobDiffWork` (asynchronous, queue `blobDiff`) extracts and compares the two binaries, then
   stores the unified diff as a **blob** on a dedicated `BlobDiff` document under the
   ACL-restricted `/change-diff/YYYY/MM/DD/` container.

A binary that changed but cannot be diffed (over `maxBlobSize`, or no extractor for the mime type)
produces the **audit entry only**, with a `skipReason` extended info and no `diffCorrelationId`.
See the "Skip reporting" invariant below.

Formats are supported by contributing `BlobTextExtractor` implementations to the `extractors`
extension point. The diff engine (`TextDiffer`) is format-agnostic.

## Layout

Maven multi-module, parent `org.nuxeo:nuxeo-parent:2025.24`, version `2025.1.0-SNAPSHOT`.

- `nuxeo-advanced-document-blob-audit-core/` — all Java, all contributions, and the Web UI.
  - `src/main/java/org/nuxeo/audit/advanced/blob/` — service (`BlobDiffComponent`),
    `BlobModificationListener`, `BlobDiffTrigger`, `TextDiffer`; subpackages `bulk/`, `extractor/`,
    `image/`, `io/`, `operations/`, `work/`.
  - `src/main/resources/OSGI-INF/` — 12 components + `deployment-fragment.xml` + `l10n/`.
  - `src/main/resources/OSGI-INF/l10n/messages_en_US.properties` and `messages_fr_FR.properties` —
    **server-side** i18n, appended by `deployment-fragment.xml` to
    `nuxeo.war/WEB-INF/classes/messages*.properties`. This is the bundle
    `SuggestDirectoryEntries` reads to localize vocabulary labels, so the `eventTypes` label of
    `blobContentModified` must live here. Nothing reads a `nuxeo.war/server-side-i18n/` folder.
  - `src/main/resources/web/nuxeo.war/` — Polymer elements under
    `ui/nuxeo-advanced-document-blob-audit/elements/`, layouts under `ui/document/blobdiff/`,
    **client** i18n in `ui/i18n/messages*.json`, read by Web UI only.
    The two i18n sets have different roles and different consumers: they are **not** copies of one
    another. A key may exist in both (`label.blobaudit.event.blobContentModified` does), but adding
    a key to one does not imply adding it to the other.
- `nuxeo-advanced-document-blob-audit-package/` — the Nuxeo marketplace package.

## Invariants to keep in mind

**Version pairing is captured, not inferred.** `ABOUT_TO_CHECKIN` fires inline on the live document
just before the new version exists; the listener records `getLastDocumentVersionRef` in the event
properties. `AbstractSession` passes that very same option map to `notifyCheckedInVersion`, which
copies it into the `DOCUMENT_CHECKEDIN` properties, where the listener reads it back alongside the
platform's `checkedInVersionRef`. There is no assumption about version numbering left, and no
bail-out.

Two core call sites (`saveDocument`'s snapshot branch and the publishing path) call
`notifyCheckedInVersion` with `null` options, so the captured ref can be missing.
`previousVersionRefFallback` covers them, ordering by `ecm:versionCreated` — a fact, not a
convention. Do not "simplify" it away, and do not reintroduce `ORDER BY uid:major_version`.
Coverage: `TestBlobDiffVersionPairing`.

**The listener can be muted.** `BlobModificationListener` honours three switches (see README,
"Disabling the Listener"):

- `DISABLE_BLOB_DIFF_LISTENER` context data / event property — works through
  `session.saveDocument` (its options reach both check-in events), **not** through
  `session.checkIn` (which builds a fresh, empty option map);
- `BlobModificationListener.runDisabled(Runnable|Supplier)`, thread-scoped — the answer for explicit
  check-ins, migrations and importers;
- `EventServiceAdmin.setListenerEnabledFlag("blobModificationListener", false)`, instance-wide.

Any change to `handleEvent` must keep these checks first, before any I/O.
Coverage: `TestBlobDiffListenerDisabling`.

**Skip reporting: the order of the checks in `BlobDiffTrigger#scheduleIfNeeded` is significant.**
Eligibility (`DiffEligibility`) is evaluated, then `sameContent`, then the skip is reported. Moving
`sameContent` back after the eligibility branch would audit every new version of an over-sized
document as a binary change even when the binary never moved. `NOT_APPLICABLE` short-circuits
before `sameContent` and stays fully silent; `TOO_LARGE` and `UNSUPPORTED_TYPE` only produce an
entry once the digests are known to differ. Coverage: `TestBlobDiffSkipReporting`.

Both skip statuses are reachable as a `bdiff:status`. `skippedUnsupportedType` is set by
`BlobDiffWork` when `service.diff()` returns `null` at work time; `skippedTooLarge` **became**
reachable with SEC-01, because the work now re-evaluates `getEligibility` on the rehydrated blobs
(`BlobDiffWork#ineligibleStatus`) — a retry, or a work outliving a `maxBlobSize` change, can fail
it. The purge dialog offers both. This reverses an earlier note claiming `skippedTooLarge` could
never be persisted; that was true only of the check-in path.

**`BlobDiff.Retry` trusts nothing on its input but the path.** The operation replays the provider
id and the storage key persisted on the document, and `BlobDiffWork` re-reads them in a *system
session* through `provider.readBlob(info)` — a keyed store lookup, subject to **no document ACL**.
The doctype and `bdiff:status` checks are both on attacker-controlled properties, and
`AbstractSession.createDocument` does not enforce allowed subtypes (that is `TypeManager`, i.e. UI).
`BlobDiffRetryOp#checkProvenance` therefore rejects anything outside `/change-diff/` **before a
single property is read**, and it must stay the first thing after `checkAuditor`.
The eligibility recheck in the work is *not* a substitute: the length it compares comes from
`BlobInfo`, which `resolveBlob` fills from `bdiff:oldLength` / `bdiff:newLength` — forgeable.
Coverage: `TestBlobDiffManagement#testRetryRefusesADiffCreatedOutsideTheContainer`,
`#testRetryDoesNotExtractABlobThatIsNoLongerEligible`.

**The `blobDiffPurge` bulk action must stay off the HTTP surface.** `httpEnabled` is left to its
default `false`, which is what keeps it out of `Bulk.RunAction` and of the
`/search/bulk/{action}` REST endpoint for anyone but an administrator. The only supported entry
point is `BlobDiff.Purge`, which enforces `BlobDiffAccess.checkAuditor` and builds the NXQL itself.

The two alternatives were both considered and rejected: `httpEnabled="true"` publishes a
delete-by-NXQL API to every authenticated user, and routing the UI through `Bulk.RunAction` with
`httpEnabled="false"` reserves the purge to administrators, taking the feature away from the
auditors it exists for. The computation also refuses to remove anything that is not a `BlobDiff`,
so even an administrator submitting the action by hand with an arbitrary query cannot turn it into
a generic delete. Coverage: `TestBlobDiffPurgeAction`.

**`BlobDiff.Purge` uses `submit()`, not `submitTransactional()`.** The latter defers the real
submission — and with it the exclusivity check and the parameter validation — to
`beforeCompletion`, so a second concurrent purge surfaces as a commit failure instead of a readable
error, and the returned id is not queryable until commit. The operation writes nothing to the
repository, so there is nothing to undo if it does not commit. Do not "fix" this back.

**Never put a `<dataFile>` on a vocabulary the plugin does not own.** A
`<directory name="eventTypes" extends="template-vocabulary"><dataFile>…</dataFile></directory>` does
not add rows, it **replaces** the platform's: `dataFileName` is single-valued,
`BaseDirectoryDescriptor#merge` overwrites it rather than concatenating, and `DirectoryRegistry`
recomputes the effective descriptor from a `clone()` of the template for every contribution carrying
`extends`, so the last one wins outright. `dataLoadingPolicy=skip_duplicate` does **not** save it: it
only protects rows that are already in storage, which is true of an instance that predates the
plugin and false of a greenfield one. Measured on empty storage: `eventTypes` held 1 row and the
platform's 51 were gone.

`blobContentModified` is therefore created at runtime, by
`BlobDiffInitComponent#registerAuditEventType` — guarded by `hasEntry`, wrapped in
`TransactionHelper.runInTransaction` + `Framework.doPrivileged`, and with any `RuntimeException`
logged rather than propagated, because a vocabulary row must never break startup. The same rule
applies to `eventCategories`, `nature`, `subject` and every other platform vocabulary.
Coverage: `TestAuditEventTypeRegistration`.

**The `eventTypes` label is resolved server side, not by Web UI.** `nuxeo-audit-search` asks for
`localize=true` without `dbl10n`, so `SuggestDirectoryEntries` translates the label against the
server `messages` bundle and `I18NUtils` falls back to returning the key. That is why
`OSGI-INF/l10n/messages_*.properties` is appended to `nuxeo.war/WEB-INF/classes/messages*.properties`
by `deployment-fragment.xml`. Adding the key to `web/nuxeo.war/ui/i18n/messages*.json` alone makes
the filter display `label.blobaudit.event.blobContentModified`.

**Know where each query lands, it is not uniform.** The plugin has four query sites and they do
not share a backend:

| Site | Mechanism | Destination |
|---|---|---|
| `BlobDiffWork#existingDiffId:208` | `session.query` | repository, always |
| `BlobModificationListener#previousVersionRefFallback:283` | `session.query` | repository, always |
| `BLOB_DIFFS_FOR_DOCUMENT` | `<coreQueryPageProvider>` | repository, always |
| `BLOB_DIFFS_ADMIN` | `SearchServicePageProvider` → `SearchService` | **the configured search client** |

In LTS 2025 the default client is `repository` (`common-base/nuxeo.defaults:150`,
`nuxeo.search.client.default.name`), and the OpenSearch search-client package flips it to
`opensearch` through its own `nuxeo.defaults`. So `BLOB_DIFFS_ADMIN` only reaches the engine on an
instance that has one — which is the real-world case, but not the test stack and not a bare server.

Two consequences. First, `BLOB_DIFFS_FOR_DOCUMENT` is the **synchronous, read-your-writes** page
provider: the tests depend on that and must not be moved to `SearchService`. Second,
`RepositorySearchClient#hasCapability` returns `false` for everything including `AGGREGATE`
(`:73-77`), and the mismatch is reported as a `SearchLimitation` rather than raised
(`AbstractSearchResponseTransformer:54-66`) — which `SearchServicePageProvider` surfaces nowhere.
On a bare instance the four facets of the audit page come back **silently empty**. Documented in
the README as a prerequisite rather than worked around.

**The diff body is bounded by construction, and the counters are not.** `maxDiffChars` caps the
whole rendered body, `maxValueLength` a single unit, and both are checked **after** the entry is
built — testing `sb.length()` beforehand lets the result overshoot by one whole entry. The cut is
monotone, and `added` / `removed` / `changed` keep counting past the budget so they stay exact.
Any change to `diffKeyed` or `diffPositional` must preserve all three properties.
Coverage: `TestTextDifferBounding`.

## Conventions

Standard Nuxeo LTS 2025 plugin conventions apply (`jakarta.*`, Log4j2 `LogManager.getLogger()`,
JUnit 4 + `FeaturesRunner`, `Framework.getService()`, 4-space indent, ~120 char lines, no wildcard
imports).

Repository-specific:

- The feature is **disabled by default** (`<config enabled="false">`). Tests enable it through
  `src/test/resources/blobaudit-test-config.xml`.
- Every new `OSGI-INF/*.xml` component **must** be added to the `Nuxeo-Component` header of
  `src/main/resources/META-INF/MANIFEST.MF`, one entry per line, single leading space on
  continuation lines, trailing newline at EOF. A component missing from that header is silently
  never loaded — this has already happened once in this repo.
- `BlobDiff` documents are **never** trashed, only permanently deleted.
- Anything written to a `BlobDiff` is business content extracted from the source. Treat
  confidentiality as a first-class concern in every change.
- `AGENTS.md` is currently **not** in `.gitignore` for this repo, so it is pushed. Keep it free of
  any sensitive information.

## Build and test

```bash
mvn -o install                                     # full build, 212 tests (23 test classes)
mvn -o test -pl nuxeo-advanced-document-blob-audit-core -Dtest=TestBlobDiffHardening
mvn -o test -pl nuxeo-advanced-document-blob-audit-core -Dtest='TestTextDiffer#someMethod'
```

`nuxeo.skip.enforcer` is `true` in the parent pom, so the Nuxeo enforcer rules do not run; do not
rely on them to catch a bad dependency. The build resolves against
`https://packages.nuxeo.com/repository/maven-public/` — `-o` only works once the local repo is warm.

Tests needing no Nuxeo runtime (fast, pure JUnit):

```bash
mvn -o test -pl nuxeo-advanced-document-blob-audit-core \
  -Dtest='TestTextDiffer,TestTextDifferScaling,TestTextDifferBounding,TestExcelDiff,TestPresentationDiff,TestImageInventoryExtractor,TestExtractorFileBacking,TestPlainTextExtractor,TestExtractorSelection'
```

All runtime tests go through `BlobAuditFeature` (in-memory audit backend + `CoreFeature`, deploys
this bundle plus `blobaudit-test-config.xml` and `blobaudit-test-pageprovider-contrib.xml`).
`blobaudit-test-smallblob-config.xml` is deployed per-test to exercise `maxBlobSize`,
`blobaudit-test-imagelevel-config.xml` to turn `imageAnalysisLevel` on,
`blobaudit-test-noimagepdf-config.xml` to disable a single image extractor,
`blobaudit-test-failingimage-contrib.xml` to make the Word image inventory throw
(`FailingImageExtractor`, a test class contributed to `imageExtractors` with a lower `order` than
`imageWord`), and
`blobaudit-test-allxpaths-config.xml` (used by `TestBlobDiffVersionPairing`) to widen the watched
xpaths.
`blobaudit-test-vocabulary-template-contrib.xml` (used by `TestAuditEventTypeRegistration`) supplies
`template-vocabulary`, which the platform ships in `org.nuxeo.ecm.default.config` — a bundle nothing
in this test stack deploys, and without which the `eventTypes` directory never registers.
`TestMaterializedBlob` needs the runtime but not the repository (`RuntimeFeature` only).
`TestBlobDiffPurgeAction` adds `CoreBulkFeature` (from the `nuxeo-core-bulk` test-jar) on top of
`BlobAuditFeature`, and waits on `bulkService.await(commandId, timeout)`.

Do not run the build when a commit only touches `README.md`, `AGENTS.md` or `.gitignore`.

## Deployment target

This plugin is heading for a **real instance**, not a demo. Consequence: anything that only matters
at small volume can no longer be waved away. This is what made backlog item 11 (purge on the Bulk
Action Framework) mandatory rather than optional.

---

## Backlog

A full architecture review was run on the plugin. The architecture was judged sound; fourteen
improvements were identified. A later front-end audit added its own findings, numbered `WEB-nn`,
a later correctness audit its own, numbered `COR-nn`, a security audit its own, numbered
`SEC-nn` / `BLD-nn`, and a scalability audit its own, numbered `RES-nn`; those that turn into work
get the next free number in this same list.
**Items 1 to 11, 13, 15 to 20 and 21 to 23 are done** (regression coverage:
`TestBlobDiffHardening`, `TestBlobDiffSkipReporting`, `TestTextDifferScaling`,
`TestBlobDiffVersionPairing`, `TestBlobDiffImageExtraction`, `TestMaterializedBlob`,
`TestBlobDiffPurgeAction`, `TestBlobDiffManagement`, `TestImageInventoryExtractor`,
`TestTextDifferBounding`, `TestExtractorFileBacking`):

1. `NotFulltextIndexable` facet on `BlobDiff` — extracted business content was leaking into the
   full-text index and Elasticsearch. **Scope correction, see item 21**: the facet is a
   *repository-side* switch (`FulltextConfigurationFactory`), so it stops `ecm:binarytext` being
   computed on the `bdiff:diff` blob — which is the leak that mattered. It does **not** cover the
   `copy_to: all_field` of the search-engine dynamic template, through which every *string*
   property of the schema is reachable by `ecm:fulltext`. In this schema those are metadata
   (`oldFilename`, `newFilename`, `summary`, version labels), not extracted content, so the
   invariant holds — but for a narrower reason than originally written.
2. Dedicated `blobDiff` WorkManager queue (`maxThreads=2`) + `getRetryCount()` = 2.
3. Version lookup bounded to 2 rows, run with a privileged session.
4. Deterministic work id derived from the version pair, not the random correlation id.
5. Idempotent work: an existing `BlobDiff` with the same correlation id short-circuits the run.
6. Common prefix/suffix trimming + interned line ids in `TextDiffer`. One modified paragraph at
   20 000 lines: 1.45 s → 2.4 ms, and flat as the document grows.
7. Cleanups: dead page provider removed, UTC dated containers, bounded root-container retry,
   charset/BOM handling in `PlainTextExtractor`, wildcard import, duplicated `<vendor>`.
8. Blob xpaths derived from the document type through `SchemaManager` and cached, instead of
   walking every property of both versions and calling `getValue()` on each. Blobs inside lists are
   cached as templates (`files:files/*/file`) and resolved against the document.
9. Listener moved to `aboutToCheckIn` + `documentCheckedIn`. Removes the `ORDER BY
   uid:major_version` heuristic, its bail-out, and the five `getSourceDocument` hops.
10. Binaries materialised once per diff (`io/MaterializedBlob`), so every extraction pass reads a
    local copy: four downloads for two files became two. `ImageInventoryExtractor` is now a plain
    `BlobTextExtractor` contributed to the new `imageExtractors` point (one contribution per
    format, so one can be disabled alone), bounded by `maxLines`, and reads the PDF from the file
    instead of `readAllBytes()`.
13. Non-diffable binary changes are audited instead of being silent (`DiffEligibility`,
    `BlobDiffTrigger.Outcome`, `skipReason` extended info).
15. Non-`ManagedBlob` pairs get the third skip reason, `skippedNotManaged`.
11. Purge moved to the Bulk Action Framework (`bulk/BlobDiffPurgeAction`, action `blobDiffPurge`,
    `exclusive`). `BlobDiff.Purge` submits and returns a command id; `BlobDiff.PurgeStatus`,
    `BlobDiff.PurgeAbort` and `BlobDiff.CleanEmptyFolders` complete the sequence, and the purge
    dialog polls its way through it. The unbounded `while (true) { …; nextTransaction(); }` inside
    a transaction-bound Automation operation is gone.

    The backlog also flagged `removeEmptyFolders` for loading all children without pagination.
    **That part of the diagnosis was overstated**: the fan-out is bounded by the calendar — a
    handful of years, twelve months, thirty-one days — so the longest list ever loaded is about
    thirty entries whatever the number of diffs. It moved to `getChildrenIterator` anyway, and to
    its own operation, because it can only run once the asynchronous purge has emptied the folders.

16. *(front-end audit, finding `WEB-01`)* "Content changes audit" never issued a single query.
    `nuxeo-blobdiff-search-page` bound its table with `nx-provider="provider"` — a **string id** —
    while `#provider` sits in the element's own template and `nuxeo-data-table` sits inside the
    `_isAuditor` `dom-if`. `PageProviderDisplayBehavior._nxProviderChanged` resolves a string
    through `this.__dataHost.$[id]`, which for a stamped node is the *template instance*, whose `$`
    only covers ids internal to that `dom-if`. The lookup yields `undefined`, the `querySelector`
    fallback is guarded by `=== null` so it never runs, `nxProvider` stays the string `'provider'`,
    `_hasPageProvider()` is false and `fetch()` returns `Promise.resolve()` — silently. The page
    showed `label.blobaudit.empty` forever.

    Fixed by passing the **node**, not the id: a `_nxProvider` property set to `this.$.provider` in
    `ready()`, bound as `nx-provider="[[_nxProvider]]"`. The binding crosses the `dom-if` through
    the templatizer. This is verbatim what Web UI does in `nuxeo-results-view`
    (`_nxProvider: HTMLElement`, initialised in `ready()`, consumed inside a `dom-if`). Legacy
    `Polymer({…})` is safe here: `ready` is a lifecycle key, so `GenerateClassFromInfo` calls
    `super.ready()` *before* it and `this.$` is populated.

    Three things not to "fix" by symmetry: the `this.$$('#table')` calls in `_refresh` and
    `_deleteSelection` are correct and must stay — `this.$.table` does not exist; and
    `nuxeo-blobdiff-document-history` keeps `nx-provider="provider"` because its table is *not*
    inside a `dom-if`. Rule of thumb: **a `nuxeo-data-table` stamped by a `dom-if` must be handed
    the provider element, never its id.**

    No automated coverage — this repository has no JS test infrastructure (no runner, no
    `*.test.js`). Verified by reading the Web UI and Polymer sources; behavioural check is manual
    (open the page as an auditor, confirm a search request leaves in the network tab).

17. *(correctness audit, finding `COR-01`)* `BlobDiffService#extract` returned `null` for two
    unrelated situations: "no extractor is registered for this mime type" and "the extractor was
    found and blew up". `diffLocal` propagated the `null` and `BlobDiffWork` mapped it to the
    single status `skippedUnsupportedType`, so a genuine failure was persisted as a format
    limitation. `BlobDiff.Retry` only accepts `error`, so that diff could **never** be replayed:
    a silent, permanent hole in the audit trail, under a reason that was not the real one.

    Fixed by narrowing the contract rather than widening the return type: `extract` now wraps any
    extractor failure in a `NuxeoException` and keeps `null` for the absence of an extractor alone.
    Nothing else had to change — `BlobDiffWork` already had the `catch (RuntimeException)` that
    records `error` right below the `result == null` branch. The two rejected alternatives were an
    `ExtractionOutcome{OK, UNSUPPORTED, FAILED}` enum (a public API signature change for no gain
    here) and keeping `null` while exposing the last error elsewhere (implicit, non-thread-safe
    state).

    **The image inventory stays swallowed, and that asymmetry is deliberate.** `diffLocal`'s own
    `catch (Exception)` around `extractImages` degrades to the text result with a WARN: a failing
    inventory costs one section of the report, a failing text extraction leaves nothing to record.
    Do not "harmonize" the two.

    `skippedUnsupportedType` remains reachable, and its meaning is now exact: `service.diff()`
    returned `null` because one side had no extractor at work time — typically a version pair whose
    two blobs do not share a mime type. `DiffEligibility.UNSUPPORTED_TYPE` (the pre-work skip
    reason, decided at check-in) is untouched, and so is the purge dialog offering that status.

    Coverage: `TestBlobDiffService#testCorruptedSpreadsheetRaisesInsteadOfLookingUnsupported`
    (which replaces a test whose javadoc asserted the bug, "extraction failures must be swallowed" —
    its premise was wrong, `extract` never runs on the synchronous listener path),
    `TestBlobDiffManagement#testAnExtractionFailureIsRetryable` (end to end: a corrupted `.xlsx`
    versioned twice yields a `BlobDiff` in `error` that `BlobDiff.Retry` accepts) and
    `TestBlobDiffImageExtraction#testAFailingImageInventoryStillYieldsTheTextDiff` (which injects
    the failure through `FailingImageExtractor` + `blobaudit-test-failingimage-contrib.xml`,
    there being no dependable binary that the text converter reads and the inventory chokes on).

18. *(security audit, finding `SEC-01`)* `BlobDiff.Retry` decrypted caller-supplied storage keys.
    The operation validated the doctype and `bdiff:status` — two properties of the input — then
    handed the persisted provider id and key to `BlobDiffWork`, which opens a **system session** and
    calls `provider.readBlob(info)`. A blob provider is a store keyed by an opaque string: that call
    applies **no document ACL**. The extracted content then landed in `bdiff:diff` under
    `/change-diff`, readable by the caller. Reachable because
    `AbstractSession.createDocument` calls `parent.addChild(name, type)` without consulting the
    allowed subtypes — a `TypeManager` concern, hence a UI one — so an auditor who is explicitly
    *not* an administrator could create a `BlobDiff` in their own space, set `bdiff:status=error`
    plus the keys of their choice, and have the platform extract any binary of the repository for
    them.

    Two changes, and only the first is a security boundary:

    - `BlobDiffRetryOp#checkProvenance` rejects anything whose path is not under `/change-diff/`,
      **before a single property of the input is read**. It sits immediately after `checkAuditor`
      and must stay there.
    - `BlobDiffWork#ineligibleStatus` re-evaluates `getEligibility` on the rehydrated blobs, before
      `service.diff`. The retry path bypassed `maxBlobSize` and the mime type whitelist entirely;
      so does any queued work that outlives a configuration change. **This one is not a boundary**:
      the length it compares comes from `BlobInfo`, which `resolveBlob` fills from
      `bdiff:oldLength` / `bdiff:newLength` — document properties, hence forgeable. It covers
      configuration drift and nothing more. Do not present it as the fix for the forged input.

    Two design points were settled rather than inferred. `NOT_APPLICABLE` at work time (feature
    switched off, xpath out of scope) is **not** turned into a skip status: it says nothing about
    the binary, and `getSkipReason()` already returns `null` for it, so the work simply carries on.
    And a retry that is no longer eligible **replaces** the `error` diff with a skipped one, like
    the nominal path: the audit entry carries a `diffCorrelationId`, so a `BlobDiff` has to exist
    for it either way. The cost is accepted — `BlobDiff.Retry` only accepts `error`, so that diff
    is no longer replayable once it is filed as skipped.

    Consequence to remember: `skippedTooLarge` **became** a reachable `bdiff:status`, and the purge
    dialog now offers it. See the invariant above.

    Coverage: `TestBlobDiffManagement#testRetryRefusesADiffCreatedOutsideTheContainer` and
    `#testRetryDoesNotExtractABlobThatIsNoLongerEligible` (which deploys
    `blobaudit-test-smallblob-config.xml` on the method). Both were checked to fail against the
    unpatched code — the first accepted the forged document, the second recorded `ok`.

19. *(security audit, finding `BLD-04`)* Five of the six operations had a refusal test for a
    non-auditor; `BlobDiff.Retry` had none, only type and status precondition tests. It was the one
    operation where the missing guard leads to a privileged read (see 18).
    `TestBlobDiffManagement#testNonAuditorCannotDeleteOrPurge` became
    `#testNonAuditorCannotDeletePurgeOrRetry`.

20. *(security audit, findings `SEC-02` and `SEC-04`)* Zip bomb and heap blow-up in
    `ImageInventoryExtractor`, both fixed by the same streaming digest.

    `SEC-02`, the DOCX path: `zip.readAllBytes()` on a `word/media/` entry allocates an array of the
    **inflated** size, unbounded. `java.util.zip.ZipInputStream` has none of POI's `ZipSecureFile`
    protections, and the only upstream guard, `getEligibility`, compares the **deflated** size of
    the container to `maxBlobSize`. Ten megabytes of deflate expand to gigabytes — times two
    concurrent works on the `blobDiff` queue, times the two extra runs of `getRetryCount()`.
    Reachable by anyone able to version a `.docx` as soon as `imageAnalysisLevel > 0`.

    `SEC-04`, the PDF path: `image.createInputStream()` returns the stream PDFBox has already
    **decoded**, and `transferTo` into a `ByteArrayOutputStream` plus `toByteArray()` held two full
    copies of it. Treated here rather than in its own pass because it is the same file and reuses
    the same helper.

    `digestStream` computes SHA-256 in 8 KB chunks and gives up past `maxImageBytes()` (32 MB),
    returning `null`; the entry is dropped and the inventory is flagged `truncated`. It does **not**
    close its argument — the DOCX loop iterates over a shared `ZipInputStream`. Two cheap
    pre-checks sit in front of it: `isInflationBomb` on the declared zip sizes (POI's ratio of 100,
    with POI's grace size of 100 KB below which the ratio means nothing) and `exceedsPixelBudget` on
    `width × height × bitsPerComponent`, which rejects a PDF image before a byte is decoded.

    Both pre-checks are **opportunistic, by construction**. Read through a `ZipInputStream`, an
    entry whose sizes live in a trailing data descriptor reports `-1` for both, and that is what POI
    produces — measured: on the test fixture it is `digestStream` that fires, not the ratio.
    `exceedsPixelBudget` ignores the component count, so it under-estimates a RGB image threefold;
    that is the safe direction for a pre-check. `digestStream` is the guard that always applies.

    On the PDF path the ordinal advances even for a dropped image: keys there are positional, so
    renumbering one side would make every following image look changed. `collectPdfResources` grew
    a `boolean[] dropped` out-parameter because its return value already means "the `maxLines`
    budget is exhausted, stop", and an over-sized image must be skipped **without** ending the walk.

    Coverage: `TestImageInventoryExtractor#testAnOverSizedWordImageIsDroppedInsteadOfBuffered` and
    `#testAnOverSizedPdfImageIsDroppedInsteadOfBuffered`. Both go through a `CappedExtractor`
    subclass overriding `maxImageBytes()` — which is why that method exists rather than the
    constant being read directly — so the test asserts the real behaviour without the JVM having to
    inflate a real bomb.

21. *(scalability audit, finding `RES-01`)* No index on `bdiff:correlationId` nor on
    `bdiff:sourceId`. The only usable index being the one on `ecm:primaryType`, the repository read
    and filtered **every `BlobDiff` of the instance** on each of two queries — `BlobDiffWork#
    existingDiffId`, the idempotency guard, run once per work; and the `BLOB_DIFFS_FOR_DOCUMENT`
    core query page provider, public API for integrators. The `LIMIT 2` does not help: the nominal
    outcome is *zero* match, which is exactly the case that forces the full scan.

    Fixed with two `<property indexOrder="ascending">` inside the **`schema`** extension point of
    `blobdiff-doctype-contrib.xml` — not `configuration`, see `PropertyDescriptor.java:62`.

    **Three facts settled during the review, worth not re-deriving.**

    - *`indexOrder` is a MongoDB mechanism and nothing else.* Its only consumer in the whole
      platform is `MongoDBIndexCreator` (`:87-89`), called from `MongoDBConnection:314-322` at
      repository init, idempotently (`existingIndexes.containsKey`, `:105`), over existing data. It
      has **zero** usage in `nuxeo-core-storage-sql`: on VCS the equivalent indexes must be created
      by hand. And zero effect on the search engine — there is no per-field opt-in there at all.
    - *Nothing has to be deployed on OpenSearch/Elasticsearch.* `DefaultIndexingJsonWriter#
      writeSchemas` serialises every schema with no allow-list, and the default mapping's bare
      `match_mapping_type: "string"` dynamic template maps every string to `keyword` + doc_values +
      `copy_to: all_field`. Term equality and `terms` aggregations on `bdiff:*` work out of the box.
      **Do not ship a mapping fragment**: `OpenSearchComponent:101-143` only pushes a mapping when
      the index has none (`mappingExists` is "does `GET /_mapping` answer 200", true of any existing
      index), so a fragment would need a drop plus a full reindex, where a dynamic template needs
      nothing — and the target component name differs between os1 and os2.
    - *Be exact about the benefit.* The `sourceId` index serves **no UI path**: both Web UI elements
      go through `BLOB_DIFFS_ADMIN`. It protects `BLOB_DIFFS_FOR_DOCUMENT` and the tests.

    No automated coverage is possible — index creation is MongoDB behaviour, not observable from
    `BlobAuditFeature`. Verified by `db.getCollection("default").getIndexes()` on the sandbox.

22. *(scalability audit, finding `RES-02`)* Four POI call sites threw away the guarantee that
    `MaterializedBlob` exists to provide. POI cannot reposition an `InputStream`, so
    `WorkbookFactory.create(InputStream)` and `new XMLSlideShow(InputStream)` buffer the **whole**
    OOXML package in heap before building the XMLBeans model, while the `File` variants open the
    zip in random access, read only, and read the parts on demand.

    `SpreadsheetExtractor:64`, `PresentationExtractor:74`, `ImageInventoryExtractor:197` and `:345`
    now branch on `blob.getFile()`. **Two explicit branches, never a ternary inside the
    try-with-resources**: `file != null ? create(file…) : create(blob.getStream())` leaves the
    stream outside the resource list and leaks it if the factory throws. The `OPCPackage` is closed
    explicitly — `XMLSlideShow.close()` does not close a package it was handed.

    The stream fallback stays and is not dead code: `blob.getFile()` is null for a blob that is not
    file-backed, and every other extractor fixture is built in memory. `extractDocx` is untouched —
    it reads a `ZipInputStream` whose memory is already bounded by the streaming digest of item 20.

    Coverage: `TestExtractorFileBacking`, which asserts **equivalence** of the two branches on the
    three extractors plus the `maxLines` cut, and starts by asserting that both branches are
    actually reachable so the comparisons cannot pass vacuously. The memory saving itself is not
    dependably unit-testable; it is a manual check.

23. *(scalability audit, finding `RES-03`)* `maxDiffEntries` bounded how **many** differences were
    written, never how long they were, and nothing bounded a single extracted unit. An Excel cell
    accepts 32 767 characters and a Word paragraph is unbounded. Measured against the unpatched
    code: 5 000 modified worst-case cells render to **327 MB**, and 2 000 rewritten 5 000-character
    paragraphs to **20 MB** — in one `StringBuilder`, copied by `toString()`, copied again into an
    in-memory `StringBlob`, then encoded to bytes by the blob provider.

    Two new options, `maxDiffChars` (4 MB) and `maxValueLength` (4 096), a three-argument
    `TextDiffer` constructor, and `elide()` applied to **values and keys** — a keyed extractor is
    free to use JSON pointers, so leaving the key unbounded would leave the per-entry size unbounded.

    **The budget is checked against the rendered entry, not before building it.** The obvious form,
    `if (sb.length() >= maxChars)`, lets the result overshoot by one whole entry — up to
    `2 × maxValueLength` plus the key — so it cannot honour a `length() <= maxDiffChars` contract.
    The cut is also **monotone** (`if (cut || emitted >= maxEntries)`): letting a later, shorter
    entry through after a long one was rejected would produce a non-contiguous diff body.
    Counters are incremented **before** the guard and stay exact past the budget; that was already
    the behaviour and it must not change.

    `merge()` moved from `BlobDiffComponent` to `TextDiffer`, which already owns `maxChars`. Each
    side is bounded on its own, so the concatenation could otherwise reach twice the cap and the
    image inventory would quietly undo the bound. The cut falls back to the last complete line.
    The move had no cost: one caller, no test, and it makes the method testable in plain JUnit.

    Not done, deliberately: streaming the diff to a temporary file. `DiffResult.unified` is a
    `String` consumed by a dozen call sites and some thirty assertions; once the string is bounded
    by construction, holding it in memory is safe and the change would buy nothing.

    Known limitation, documented rather than worked around: when both sides of a modification are
    longer than `maxValueLength` and differ only past it, the entry renders as two identical
    prefixes (`~ key : X… -> X…`). The counters stay exact and `bdiff:changed` still reports it.

    Coverage: `TestTextDifferBounding`. Four of its seven cases were checked to fail against the
    unpatched code, with the measurements above.

> **Numbers are stable identifiers, not priorities.** They are referenced in commit messages; never
> reused or renumbered. Follow the recommended order below, not the numbering.

| Session | Items | Effort | Notes |
|---|---|---|---|
| next | 24 | S | Optional. The index of item 21 already solves the scalability problem |
| — | 14 | — | **Closed**, see below |
| — | 12 | — | **Dropped**, see below |


### 24. Replace the idempotency query with a path lookup — OPTIONAL

`BlobDiffWork#existingDiffId` runs an NXQL query whose answer is derivable without one: the
`BlobDiff` path is fully deterministic, `getOrCreateContainer` giving `/change-diff/YYYY/MM/DD`
(`BlobDiffComponent:303`) and `diffDocumentName` giving `sourceId-date.getTime()-correlationId`
(`:513-515`). `BlobDiffWork` carries all three inputs, serialised. A `session.exists(PathRef)` would
be O(1) and consistent within the transaction, and `bdiff:correlationId` — which has **exactly one
reader in the whole plugin**, that query — would lose its only consumer.

**Deliberately not done in the item 21-23 batch**, and the reasons should be weighed again before
reopening:

- the index already solves the scalability problem; this is an elegance gain, not a capacity one;
- it needs new public API (`BlobDiffService#diffDocumentPath`, side-effect free —
  `getOrCreateContainer` *creates*, so it cannot serve an existence check);
- it touches the guard that implements item 5, whose failure mode is duplicated audit records;
- **the naming invariant it relies on is not contractual.** `AbstractSession.createDocument:722`
  re-reads `DESTINATION_NAME` from the options **after** firing `ABOUT_TO_CREATE`, so any listener
  can rename the document. A correct implementation must therefore validate the doctype and
  `bdiff:correlationId` of whatever sits at the computed path, and keep the query as a cold
  fallback — at which point the index is still wanted.

**Do not use the search engine for this.** Indexing is asynchronous by construction
(`IndexingDomainEventProducer` → stream `source/indexing` → `ongoingIndexing`, `batchThreshold`
250 ms, `maxRetries=4 delay=3s`; the platform's own comment says "almost real time"). The window
between the commit of run 1 and its visibility in the index is exactly the window in which a retry
runs, so a search-backed guard would be read-your-writes unsafe and would reintroduce item 5's bug.


### 12. Myers diff instead of Hirschberg — DROPPED

Was conditional on the measurements after item 6. Those measurements settled it: the dominant real
case is now flat (2.4 ms whatever the document size) and the pathological case — a document
rewritten from end to end — costs 339 ms at 20 000 lines, well inside the asynchronous budget.
Myers' O(ND) would buy nothing worth the risk of re-deriving an optimal alignment.

Reopen only if a real corpus shows documents where prefix/suffix trimming finds nothing *and* the
line count goes far past 20 000. `TestTextDiffer` and `TestTextDifferScaling` assert alignment
**optimality** — that is the contract any replacement must satisfy.

### 14. `DiffResult#summary()` stays English — CLOSED

`bdiff:summary` holds a hardcoded English sentence ("2 modifications, 1 addition", or "No textual
change detected"), built by `DiffResult#summary()` and persisted as is.

**Decision (2026-09, product owner): leave it in English.** Do not "fix" this by composing the
sentence in the UI: it would split the display between already-stored English strings and newly
composed translated ones, and realigning them would mean rewriting every existing `BlobDiff`.

`bdiff:added`, `bdiff:removed`, `bdiff:changed` and `bdiff:truncated` are stored as separate
fields, so a UI that wants a translated summary can build one from those without touching
`bdiff:summary`. `nuxeo-blobdiff-status` already does exactly that for its counters.

---

## Other observations (not scheduled)

- **Do not add `<require>ElasticSearchComponent</require>` to `blobdiff-pageproviders-contrib.xml`.**
  The item 7 review claimed it was missing; it is not needed and would be harmful.
  `SearchServicePageProvider` lives in `nuxeo-platform-query-api`, not in the Elasticsearch bundle,
  so the component always registers. A `<require>` would keep the whole component —
  `BLOB_DIFFS_FOR_DOCUMENT` included — pending on any instance without a search engine, and in the
  tests. What does need a search engine is the *execution*: `SearchService` is resolved at query
  time and the provider throws if none is configured. The XML says so.
- **`LogEntry.getExtendedInfos()` throws `UnsupportedOperationException` in LTS 2025.** Use
  `getExtendedValue(key)`. Relevant to any test asserting on audit extended info.

- **Repository growth.** One `BlobDiff` per version per xpath in `/change-diff/yyyy/MM/dd`; a single
  day folder can reach hundreds of thousands of children. Consider an hourly level or hash bucketing.
- **`coalesce` reports misleading counts.** 3 removals followed by 3 additions yields "2 deletions,
  1 modification, 2 additions" instead of "3 modifications" — coalescing is pair by pair, should be
  by blocks.
- **Front-end.** `nuxeo-blobdiff-viewer` uses a raw `fetch(url, {credentials:'include'})` rather than
  the Nuxeo client: works with cookie sessions, breaks with JWT/OAuth, ignores CORS config. It also
  downloads the whole diff before paginating the rendering.
- **Audit/diff reconciliation.** The audit entry is written synchronously while the work runs after
  commit. If the work is lost, the entry points to a `BlobDiff` that never existed. A reconciliation
  job, or a "pending" state in the UI, would close the gap.
