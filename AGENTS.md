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
    `BlobModificationListener`, `BlobDiffTrigger`, `TextDiffer`; subpackages `extractor/`,
    `image/`, `io/`, `operations/`, `work/`.
  - `src/main/resources/OSGI-INF/` — 12 components + `deployment-fragment.xml`.
  - `src/main/resources/web/nuxeo.war/` — Polymer elements under
    `ui/nuxeo-advanced-document-blob-audit/elements/`, layouts under `ui/document/blobdiff/`,
    client i18n in `ui/i18n/messages*.json`, server i18n in `server-side-i18n/messages*.properties`.
    **Both i18n sets must be kept in sync** (fr + en exist for each).
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
mvn -o install                                     # full build, 184 tests (21 test classes)
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
`blobaudit-test-smallblob-config.xml` is deployed per-test to exercise `maxBlobSize`,
`blobaudit-test-imagelevel-config.xml` to turn `imageAnalysisLevel` on, and
`blobaudit-test-noimagepdf-config.xml` to disable a single image extractor.
`TestMaterializedBlob` needs the runtime but not the repository (`RuntimeFeature` only).

Do not run the build when a commit only touches `README.md`, `AGENTS.md` or `.gitignore`.

## Deployment target

This plugin is heading for a **real instance**, not a demo. Consequence: backlog item 11 (purge on
the Bulk Action Framework) is needed rather than optional, and anything that only matters at small
volume can no longer be waved away.

---

## Backlog

A full architecture review was run on the plugin. The architecture was judged sound; fourteen
improvements were identified. **Items 1 to 10, 13 and 15 are done** (regression coverage:
`TestBlobDiffHardening`, `TestBlobDiffSkipReporting`, `TestTextDifferScaling`,
`TestBlobDiffVersionPairing`, `TestBlobDiffImageExtraction`, `TestMaterializedBlob`):

1. `NotFulltextIndexable` facet on `BlobDiff` — extracted business content was leaking into the
   full-text index and Elasticsearch.
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

> **Numbers are stable identifiers, not priorities.** They are referenced in commit messages; never
> reused or renumbered. Follow the recommended order below, not the numbering.

| Session | Items | Effort | Notes |
|---|---|---|---|
| **F** | 11 | high | Needed, see "Deployment target" |
| — | 14 | — | **Closed**, see below |
| — | 12 | — | **Dropped**, see below |


### 11. Move the purge to the Bulk Action Framework

`BlobDiffPurgeOp` calls `nextTransaction()` **inside an Automation operation** while the
`@Context CoreSession` is transaction-bound, in an unbounded loop — HTTP timeout guaranteed at
volume. Use a `BulkCommand` over the NXQL (scalable, resumable, status tracking); the UI would call
`Bulk.RunAction`. Also: `removeEmptyFolders` loads all children via `session.getChildren` without
pagination.

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
