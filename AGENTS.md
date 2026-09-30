# AGENTS.md — nuxeo-advanced-document-blob-audit

Working notes for AI agents picking this repository up. Read this before touching the code.
Functional documentation lives in `README.md`.

## What this plugin does

Audits **what actually changed inside a binary** between two successive Nuxeo versions of a
document: Excel cell by cell, PowerPoint slide by slide, Word/PDF paragraph by paragraph, text
formats line by line.

Pipeline:

1. `BlobModificationListener` (synchronous, on `documentCreated` of a version) compares the digests
   of the blob on the two most recent versions.
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
    `BlobModificationListener`, `BlobDiffTrigger`, `TextDiffer`; subpackages `extractor/`,
    `image/`, `io/`, `operations/`, `work/`.
  - `src/main/resources/OSGI-INF/` — 12 components + `deployment-fragment.xml`.
  - `src/main/resources/web/nuxeo.war/` — Polymer elements under
    `ui/nuxeo-advanced-document-blob-audit/elements/`, layouts under `ui/document/blobdiff/`,
    client i18n in `ui/i18n/messages*.json`, server i18n in `server-side-i18n/messages*.properties`.
    **Both i18n sets must be kept in sync** (fr + en exist for each).
- `nuxeo-advanced-document-blob-audit-package/` — the Nuxeo marketplace package.

## Invariants to keep in mind

**Version ordering.** The previous version is found with
`ORDER BY uid:major_version DESC, uid:minor_version DESC`, which assumes the usual Nuxeo behaviour.
If version numbers are rewritten out of band, the listener detects that the newest ordered version
is not the one that just triggered the event, logs a WARN and **skips** the diff rather than
comparing an arbitrary pair. Do not "fix" that bail-out by guessing — backlog item 9 removes the
need for the heuristic altogether.

**The listener can be muted.** `BlobModificationListener` honours three switches (see README,
"Disabling the Listener"):

- `DISABLE_BLOB_DIFF_LISTENER` context data / event property — works through
  `session.saveDocument`, **not** through `session.checkIn` (which builds a fresh, empty option map);
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

`skippedTooLarge` is **not** a reachable `bdiff:status` (an over-sized blob never reaches the work,
so no `BlobDiff` is created); `skippedUnsupportedType` still is, set by `BlobDiffWork` when
`service.diff()` returns `null` at work time. The purge dialog offers only the latter.

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
mvn -o install                                     # full build, 140 tests (18 test classes)
mvn -o test -pl nuxeo-advanced-document-blob-audit-core -Dtest=TestBlobDiffHardening
mvn -o test -pl nuxeo-advanced-document-blob-audit-core -Dtest='TestTextDiffer#someMethod'
```

`nuxeo.skip.enforcer` is `true` in the parent pom, so the Nuxeo enforcer rules do not run; do not
rely on them to catch a bad dependency. The build resolves against
`https://packages.nuxeo.com/repository/maven-public/` — `-o` only works once the local repo is warm.

Tests needing no Nuxeo runtime (fast, pure JUnit):

```bash
mvn -o test -pl nuxeo-advanced-document-blob-audit-core \
  -Dtest='TestTextDiffer,TestTextDifferScaling,TestExcelDiff,TestPresentationDiff,TestImageInventoryExtractor,TestPlainTextExtractor,TestExtractorSelection'
```

All runtime tests go through `BlobAuditFeature` (in-memory audit backend + `CoreFeature`, deploys
this bundle plus `blobaudit-test-config.xml` and `blobaudit-test-pageprovider-contrib.xml`).
`blobaudit-test-smallblob-config.xml` is deployed per-test to exercise `maxBlobSize`.

Do not run the build when a commit only touches `README.md`, `AGENTS.md` or `.gitignore`.

## Deployment target

This plugin is heading for a **real instance**, not a demo. Consequence: backlog item 11 (purge on
the Bulk Action Framework) is needed rather than optional, and anything that only matters at small
volume can no longer be waved away.

---

## Backlog

A full architecture review was run on the plugin. The architecture was judged sound; fourteen
improvements were identified. **Items 1 to 5 and 13 are done** (regression coverage:
`TestBlobDiffHardening`, `TestBlobDiffSkipReporting`):

1. `NotFulltextIndexable` facet on `BlobDiff` — extracted business content was leaking into the
   full-text index and Elasticsearch.
2. Dedicated `blobDiff` WorkManager queue (`maxThreads=2`) + `getRetryCount()` = 2.
3. Version lookup bounded to 2 rows, run with a privileged session.
4. Deterministic work id derived from the version pair, not the random correlation id.
5. Idempotent work: an existing `BlobDiff` with the same correlation id short-circuits the run.
13. Non-diffable binary changes are audited instead of being silent (`DiffEligibility`,
    `BlobDiffTrigger.Outcome`, `skipReason` extended info).

> **Numbers are stable identifiers, not priorities.** They are referenced in commit messages; never
> reused or renumbered. Follow the recommended order below, not the numbering.

| Session | Items | Effort | Notes |
|---|---|---|---|
| **B** | 6 | low | Self-contained, large measurable gain, tests in place. Do first. |
| **C** | 7 + 15 | low | Purely mechanical; 15 is a small hole in the same area |
| **D** | 8 + 9 | medium | **Must go together**: 9 changes which document the listener sees, hence 8's cache key |
| **E** | 10 | medium | Extraction I/O and the extractor extension model |
| **F** | 11 | high | Needed, see "Deployment target" |
| — | 14 | — | Needs a product decision |
| — | 12 | — | **Conditional**: only if measurements after item 6 justify it |

Before starting session D: item 9 **removes the need** for the `ORDER BY uid:major_version`
heuristic, so the `[!WARNING]` block in `README.md` and the "Version ordering" invariant above must
be rewritten, not merely adjusted.

### 6. Common prefix/suffix trimming in `TextDiffer`

`TextDiffer` uses Hirschberg: memory O(min(n,m)) but **time stays O(n*m)** (~1.1 s at 10 000 lines,
~4.6 s at 20 000). That is the only reason `maxLines` is capped at 10 000. In the dominant real case
(one paragraph changed in a 5 000-paragraph document) the sequences share a huge common prefix and
suffix; strip them before the alignment and 5000×5000 collapses to ~1×1.

Implement in `diffPositional`, before `lcsEdits`; counters and unified output are unaffected. Extend
`TestTextDifferScaling` with a near-linear assertion. Consider hashing lines to `int` before
alignment.

### 7. Cleanups (purely mechanical)

- `blobdiff-pageproviders-contrib.xml` has **no** `<require>org.nuxeo.elasticsearch.ElasticSearchComponent</require>`,
  so on an instance without a search engine the component fails instead of staying pending. Add the
  `<require>` or confirm `SearchServicePageProvider` degrades gracefully. (`blobdiff-es-pageprovider-contrib.xml`
  was already deleted as dead code.)
- `BLOB_DIFFS_OLDER_THAN` page provider is referenced nowhere. Dead.
- `BlobAuditConstants` ~lines 39-43: five constants stuck to the left margin.
- `BlobDiffRetryOp` uses a wildcard static import of `BlobAuditConstants`.
- `BlobDiffComponent#getOrCreateContainer` builds three `SimpleDateFormat` per call in the **default
  time zone**: two nodes in different zones write into different dated folders. Use a static
  `DateTimeFormatter` in UTC.
- `BlobDiffComponent#ensureRootContainer` recurses after `removeDocument` with no bound.
- `BlobDiffComponent#diff` calls `getConfig()` four times. Capture once.
- `PlainTextExtractor` passes the blob encoding straight to `InputStreamReader` (throws on a bogus
  name) and does no BOM handling.
- `package.xml` declares `<vendor>Hyland</vendor>` twice.
- `blobdiff.xsd` uses `xs:date` for `bdiff:date`. Harmless (Nuxeo maps both onto `DateType`) but
  `xs:dateTime` is more honest. Cosmetic.

### 15. Non-`ManagedBlob` pairs are still silent

The same functional hole item 13 closed, in its last corner. When the two version blobs are not both
`ManagedBlob`, `BlobDiffTrigger#scheduleIfNeeded` logs a WARN and returns `Outcome.none()`: the
binary changed, and nothing is audited. It was deliberately left out of item 13 because the product
decision only covered `TOO_LARGE` and `UNSUPPORTED_TYPE`.

Needs a third skip reason (`skippedNotManaged`?) plus its i18n keys, or a decision that this case is
an instance misconfiguration worth failing loudly instead. In practice it only happens with an
unusual blob provider setup.

### 8. Rework `collectBlobXPaths`

It walks **every schema and every property** of both document models, and `isBlobProperty` calls
`property.getValue()`, forcing the load of all complex and list properties **inside the user
transaction**. Replace with a static per-document-type computation from the `SchemaManager`
(`DocumentType` → `Schema` → `Field`, recursing into complex/list), cached in a
`Map<String, List<String>>`. Only matters when `<xpaths>` is empty; the shipped default
(`file:content`) short-circuits the walk.

### 9. Move the listener to `documentCheckedIn`

Currently hooks `documentCreated`, filters on `isVersion()`, then walks back up to the live document
with up to five `getSourceDocument` calls. `AbstractSession#notifyCheckedInVersion` fires
`DOCUMENT_CHECKEDIN` **on the live document** with a `checkedInVersionRef` property — strictly
richer. Better still: capture the previous version in `aboutToCheckIn`
(`getLastDocumentVersionRef`) and schedule in `documentCheckedIn`. Removes the
`ORDER BY uid:major_version` heuristic and its bail-out. Alternative: keep the query but order by
`ecm:versionCreated DESC`.

### 10. Single materialisation of the binaries; `ImageInventoryExtractor` as a real extractor

With `imageAnalysisLevel > 0`, `BlobDiffComponent#diff` calls `blob.getStream()` **four times** for
two files — four S3 downloads. Materialise each side once (`blob.getFile()` or a `CloseableFile`)
and hand it to both extractors.

`ImageInventoryExtractor` is hardcoded (`new ImageInventoryExtractor()` inside the service) and is
the only extraction class not implementing `BlobTextExtractor`: not contributable, not disableable
per MIME type, **not bounded by `maxLines`**, and `extractPdf` does `in.readAllBytes()` (whole PDF
in heap, times the concurrency). Fold it into the `extractors` point or add an `imageExtractors`
point.

### 11. Move the purge to the Bulk Action Framework

`BlobDiffPurgeOp` calls `nextTransaction()` **inside an Automation operation** while the
`@Context CoreSession` is transaction-bound, in an unbounded loop — HTTP timeout guaranteed at
volume. Use a `BulkCommand` over the NXQL (scalable, resumable, status tracking); the UI would call
`Bulk.RunAction`. Also: `removeEmptyFolders` loads all children via `session.getChildren` without
pagination.

### 12. Myers diff instead of Hirschberg (after item 6)

If item 6 is not enough, replace the quadratic LCS with Myers' O(ND). With Myers plus prefix/suffix
trimming, `maxLines` could go to 100 000. `TestTextDiffer` and `TestTextDifferScaling` assert
alignment **optimality** — that is the contract any replacement must satisfy.

### 14. `DiffResult#summary()` is a persisted, untranslatable English string (needs a decision)

It builds a hardcoded English sentence ("2 modifications, 1 addition"), **persisted** in
`bdiff:summary` and displayed as-is, while `added`/`removed`/`changed` are already stored as
separate fields. Composing in the UI is the clean fix but every stored `BlobDiff` keeps its English
string. Decision needed: leave as is / compose in UI and accept mixed display / compose and migrate
(likely a Bulk Action, see item 11).

---

## Other observations (not scheduled)

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
