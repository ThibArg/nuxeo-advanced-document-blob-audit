# AGENTS.md — nuxeo-advanced-document-blob-audit

Working notes for AI agents and developers picking this repository up. Read this before touching
the code.

## What this plugin does

Audits **what actually changed inside a binary** between two successive Nuxeo versions of a
document: Excel cell by cell, PowerPoint slide by slide, Word/PDF paragraph by paragraph, text
formats line by line.

Pipeline:

1. `BlobModificationListener` (synchronous, on `documentCreated` of a version) compares the digests
   of the blob on the two most recent versions.
2. If they differ, it fires a `blobContentModified` audit entry (**no business data**, only xpath,
   filenames and a `diffCorrelationId`) and schedules a `BlobDiffWork`.
3. `BlobDiffWork` (asynchronous, queue `blobDiff`) extracts and compares the two binaries, then
   stores the unified diff as a **blob** on a dedicated `BlobDiff` document under the
   ACL-restricted `/change-diff/YYYY/MM/DD/` container.

Formats are supported by contributing `BlobTextExtractor` implementations to the `extractors`
extension point. The diff engine (`TextDiffer`) is format-agnostic.

Full functional documentation: `README.md`.

## Two invariants to keep in mind

**Version ordering.** The previous version is found with
`ORDER BY uid:major_version DESC, uid:minor_version DESC`, which assumes the usual Nuxeo behaviour:
versions are created one after the other, each greater than the previous one. If version numbers are
rewritten out of band (bulk update straight in the database), the listener detects that the newest
ordered version is not the one that just triggered the event, logs a WARN and **skips** the diff
rather than comparing an arbitrary pair. Do not "fix" that bail-out by guessing — backlog item 9
proposes removing the need for the heuristic altogether.

**The listener can be muted.** `BlobModificationListener` honours three switches, documented in
`README.md` ("Disabling the Listener"):

- `DISABLE_BLOB_DIFF_LISTENER` context data / event property, per operation — works through
  `session.saveDocument`, **not** through `session.checkIn` (which builds a fresh, empty option map);
- `BlobModificationListener.runDisabled(Runnable|Supplier)`, thread-scoped, which is the answer for
  explicit check-ins, migrations and importers;
- `EventServiceAdmin.setListenerEnabledFlag("blobModificationListener", false)`, instance-wide.

Any change to `handleEvent` must keep these checks first, before any I/O.
Coverage: `TestBlobDiffListenerDisabling`.

## Conventions

Standard Nuxeo LTS 2025 plugin conventions apply (`jakarta.*`, Log4j2 `LogManager.getLogger()`,
JUnit 4 + `FeaturesRunner`, `Framework.getService()`, 4-space indent, ~120 char lines, no wildcard
imports).

Repository-specific points:

- The feature is **disabled by default** (`<config enabled="false">`). Tests enable it through
  `src/test/resources/blobaudit-test-config.xml`.
- Every new `OSGI-INF/*.xml` component **must** be added to the `Nuxeo-Component` header of
  `src/main/resources/META-INF/MANIFEST.MF`, one entry per line, single leading space on
  continuation lines, trailing newline at EOF. A component missing from that header is silently
  never loaded — this has already happened once in this repo (see backlog item 7).
- `BlobDiff` documents are **never** trashed, only permanently deleted.
- Anything written to a `BlobDiff` is business content extracted from the source. Treat
  confidentiality as a first-class concern in every change.

## Build and test

```bash
mvn -o install                                     # full build, 131 tests
mvn -o test -pl nuxeo-advanced-document-blob-audit-core -Dtest=TestBlobDiffHardening
```

Fast tests needing no Nuxeo runtime:

```bash
mvn -o test -pl nuxeo-advanced-document-blob-audit-core \
  -Dtest='TestTextDiffer,TestTextDifferScaling,TestExcelDiff,TestPresentationDiff,TestImageInventoryExtractor,TestPlainTextExtractor,TestExtractorSelection'
```

Do not run the build when a commit only touches `README.md`, `AGENTS.md` or `.gitignore`.

## Deployment target

This plugin is heading for a **real instance**, not a demo. Two consequences on the backlog:
item 11 (purge on the Bulk Action Framework) is needed rather than optional, and anything that only
matters at small volume can no longer be waved away.

## State of the code

A full architecture review was run on the whole plugin. The architecture was judged sound; fourteen
improvements were identified. **Items 1 to 5 are done.** The rest are open and described below.

> **Numbers are stable identifiers, not priorities.** They are referenced in commit messages and in
> earlier discussions, so they are never reused or renumbered. Item 13 was found last and must be
> done first. Follow the "Recommended order" table, not the numbering.

### Done

| # | Item | Where |
|---|---|---|
| 1 | `NotFulltextIndexable` facet on `BlobDiff` — the extracted business content was being copied into the full-text index and into Elasticsearch | `blobdiff-doctype-contrib.xml` |
| 2 | Dedicated `blobDiff` WorkManager queue (`maxThreads=2`) + `getRetryCount()` returning 2 | `blobdiff-workmanager-contrib.xml`, `BlobDiffWork` |
| 3 | Version lookup bounded to 2 rows and run with a privileged session | `BlobModificationListener#orderedVersions` |
| 4 | Deterministic work id, derived from the version pair (or digest pair), not from the random correlation id | `BlobDiffWork#workId` |
| 5 | Idempotent work: an existing `BlobDiff` with the same correlation id short-circuits the run | `BlobDiffWork#existingDiffId` |

Regression coverage for all five: `TestBlobDiffHardening`.

### Recommended order

One session per row. The grouping is by blast radius, so each row is one reviewable commit that can
be rolled back on its own.

| Session | Items | Effort | Why grouped / notes |
|---|---|---|---|
| **A** | 13 | medium | Functional hole, decision already taken (see item 13). Do first. |
| **B** | 6 | low | Self-contained, large measurable gain, tests already in place |
| **C** | 7 | low | Purely mechanical, no design decision |
| **D** | 8 + 9 | medium | **Must go together**: 9 changes which document the listener sees, hence 8's cache key |
| **E** | 10 | medium | Extraction I/O and the extractor extension model |
| **F** | 11 | high | Needed, see "Deployment target" |
| — | 14 | — | Needs a product decision, see item 14 |
| — | 12 | — | **Conditional**: only if measurements after item 6 justify it |

Before starting session D: item 9 **removes the need** for the `ORDER BY uid:major_version`
heuristic, so the `[!WARNING]` block in `README.md` and the "Version ordering" invariant at the top
of this file must be rewritten, not merely adjusted.

---

## Backlog

### 6. Common prefix/suffix trimming in `TextDiffer` (high value, low effort)

`TextDiffer` uses Hirschberg's algorithm: memory is O(min(n,m)) but **time stays O(n*m)**
(~1.1 s at 10 000 lines, ~4.6 s at 20 000). That quadratic time is the only reason `maxLines` is
capped at 10 000.

In the dominant real case — one paragraph modified in a 5 000-paragraph document — the sequences
share a huge common prefix and suffix. Strip them before running the alignment and the problem
collapses from 5000×5000 to roughly 1×1.

- Implement in `diffPositional`, before `lcsEdits`.
- The stripped prefix/suffix contribute no edits, so counters and unified output are unaffected.
- `TestTextDifferScaling` already measures optimality and budget: extend it with a case asserting
  the near-linear behaviour on a single-line change in a large document.
- Consider also hashing lines to `int` before alignment to avoid millions of `String.equals` on
  long paragraphs.

### 7. Cleanups (low effort, purely mechanical)

No design decision in this list. Anything that needed one has been moved to items 13 and 14.

- `OSGI-INF/blobdiff-es-pageprovider-contrib.xml` has been **deleted** (it was dead code, absent
  from the `Nuxeo-Component` header, and duplicated `BLOB_DIFFS_ADMIN`). What remains to do: the
  surviving declaration in `blobdiff-pageproviders-contrib.xml` has **no**
  `<require>org.nuxeo.elasticsearch.ElasticSearchComponent</require>`, so on an instance without a
  search engine the component fails instead of staying pending. Either add the `<require>` or
  confirm that `SearchServicePageProvider` degrades gracefully. `README.md` carries a `[!NOTE]`.
- `BLOB_DIFFS_OLDER_THAN` page provider is referenced nowhere. Dead.
- `BlobAuditConstants` lines ~39-43: five constants are stuck to the left margin. Fix indentation.
- `BlobDiffRetryOp` uses `import static ...BlobAuditConstants.*` — wildcard import, against
  convention.
- `BlobDiffComponent#getOrCreateContainer` builds three `SimpleDateFormat` per call, in the
  **default time zone**: two nodes in different time zones write into different dated folders.
  Replace with a static `DateTimeFormatter` in UTC.
- `BlobDiffComponent#ensureRootContainer` recurses after `removeDocument` with no bound. Add a
  depth counter.
- `BlobDiffComponent#diff` calls `getConfig()` four times (registry lookup each time). Capture once.
- `BlobDiffTrigger#sameContent`: when one side has a null `digestAlgorithm`, digests possibly
  produced by different algorithms are compared and may wrongly conclude "identical". Do not
  short-circuit when algorithms cannot be proven equal.
- `PlainTextExtractor` passes the blob's encoding straight to `InputStreamReader`, which throws on a
  bogus encoding name; no BOM handling either.
- `package.xml` declares `<vendor>Hyland</vendor>` twice.
- `blobdiff.xsd` uses `xs:date` for `bdiff:date`. Verified harmless — Nuxeo maps `xs:date` and
  `xs:dateTime` onto the same `DateType`, so no precision is lost — but `xs:dateTime` would be
  more honest about the Elasticsearch mapping. Cosmetic only.

### 8. Rework `collectBlobXPaths` (medium effort, high runtime value)

`BlobModificationListener#collectBlobXPaths` walks **every schema and every property** of both
document models, and `isBlobProperty` calls `property.getValue()`, which forces the load of all
complex and list properties from the database — inside the user transaction.

Replace with a **static computation per document type**, derived from the `SchemaManager`
(`DocumentType` → `Schema` → `Field` → blob-typed fields, recursing into complex and list types),
cached in a `Map<String, List<String>>`. No value is read, nothing is loaded.

Note this only matters when the `<xpaths>` config is empty; the shipped default restricts to
`file:content`, which short-circuits the walk. Production deployments that widen the scope pay the
full cost.

### 9. Move the listener to `documentCheckedIn` (medium effort, correctness + simplicity)

Currently the listener hooks `documentCreated` and filters on `isVersion()`, then walks back up to
the live document with up to five `getSourceDocument` calls.

`AbstractSession#notifyCheckedInVersion` fires `DOCUMENT_CHECKEDIN` **on the live document**, with
a `checkedInVersionRef` property. That is strictly richer: no `findLiveDocument`, no ambiguity about
which document is which.

Even better: capture the previous version in `aboutToCheckIn`, where it is trivially
`getLastDocumentVersionRef`, and schedule in `documentCheckedIn`. This removes the
`ORDER BY uid:major_version` heuristic entirely, along with its noisy
"version numbers may not be monotonically increasing" bail-out. Alternatively, keep the query but
order by `ecm:versionCreated DESC` — it answers "the version before" directly and cannot bail out.

### 10. Single materialisation of the binaries, and `ImageInventoryExtractor` as a real extractor

Two coupled problems.

**Repeated downloads.** With `imageAnalysisLevel > 0`, `BlobDiffComponent#diff` calls
`blob.getStream()` **four times** for two files (text extractor + image extractor, on each side). On
an S3 blob provider that is four downloads. Materialise each side once (`blob.getFile()` when local,
otherwise a `CloseableFile` / temp file) and hand that to both extractors.

**Broken extension model.** `ImageInventoryExtractor` is the only extraction class that is
hardcoded (`new ImageInventoryExtractor()` in `BlobDiffComponent#diff`) and the only one not
implementing `BlobTextExtractor`. Consequences: not contributable, not disableable per MIME type,
not configurable, **not bounded by `maxLines`** (a PDF with 5 000 images produces 5 000
`ContentLine`), and `extractPdf` does `in.readAllBytes()` — the whole PDF in heap, times the number
of concurrent works.

Either fold it into the existing `extractors` extension point with an explicit composition, or
introduce a second `imageExtractors` point. Not a `new` in the middle of the service.

### 11. Move the purge to the Bulk Action Framework (medium effort)

`BlobDiffPurgeOp` commits and restarts the transaction **inside an Automation operation**
(`nextTransaction()`). The `@Context CoreSession` is transaction-bound, and the loop is unbounded —
an HTTP timeout is guaranteed on a large volume.

This is exactly what the LTS 2025 **Bulk Action Framework** is for: a `BulkCommand` over the NXQL,
scalable, resumable, with status tracking. The UI would call `Bulk.RunAction` and display progress.

Also: `removeEmptyFolders` loads all children via `session.getChildren` without pagination.

### 12. Myers diff instead of Hirschberg (higher effort, do after item 6)

If item 6 is not enough, replace the quadratic LCS with **Myers' O(ND)** algorithm, where `D` is the
size of the edit script — small in practice. This is what `git diff` and `java-diff-utils` use. With
Myers plus prefix/suffix trimming, `maxLines` could go to 100 000 and the cap stops being a design
constraint.

`TestTextDiffer` and `TestTextDifferScaling` assert alignment **optimality**, so they are the
contract any replacement must satisfy.

### 13. Non-diffable binary changes are completely silent (do first)

**The functional hole of the plugin as it stands.** A user replaces a 40 MB PowerPoint, a `.zip` or
any unsupported format: the plugin leaves **no trace at all**. For an audit tool, that is the worst
possible failure mode — the absence of an entry is indistinguishable from the absence of a change.

Mechanics. `BlobDiffComponent#isDiffable` returns `false` in two distinct situations:

```java
if (blob.getLength() > config.getMaxBlobSize()) return false;   // too large
return findExtractor(blob.getMimeType()) != null;               // unsupported type
```

`BlobDiffTrigger#scheduleIfNeeded` collapses both into a single `return null`. Back in
`BlobModificationListener#handleEvent`, `correlationId == null` means `fireEvent` is never called:
no work, no `BlobDiff`, **and no audit entry**.

Consequence on the status values:

| Status | Reachable today? |
|---|---|
| `skippedTooLarge` | **Never** |
| `skippedUnsupportedType` | **Practically never** — only if an extractor disappears between the scheduling and the execution of the work (redeployment, configuration change) |

The only test touching `STATUS_SKIPPED_TYPE` (`TestBlobDiffService`) builds the `BlobDiff` directly
through the service; it never exercises the listener/trigger path.

**Decision taken (2026-09, product owner): option (b).**

- Write the **audit entry only**. No `BlobDiff` document is created for a non-diffable change —
  there is nothing to diff, and creating one per changed image, video or archive would flood both
  the repository and the dated containers.
- The entry must carry a **very compact** reason. Keep the comment short, and put the machine
  readable value in an extended info so the UI and NXQL can filter on it. Suggested shape:
  comment `file:content : binary changed (too large)` / `(unsupported format)`, plus an extended
  info `skipReason` = `skippedTooLarge` / `skippedUnsupportedType` (reuse `BlobAuditConstants`).
- Distinguishing the two reasons costs **nothing**: both checks are already executed inside
  `isDiffable` today, their result is merely collapsed into a boolean and discarded.
  `blob.getLength()` is already-loaded metadata on a `ManagedBlob` (no I/O) and `findExtractor` is an
  in-memory loop over a handful of descriptors. Turn `isDiffable` into a method returning an enum
  (`ELIGIBLE`, `TOO_LARGE`, `UNSUPPORTED_TYPE`, `NOT_APPLICABLE`) and keep `isDiffable` as a thin
  boolean façade for existing callers.

Implementation notes:

- `NOT_APPLICABLE` (feature disabled, doc type or xpath out of scope) must stay **fully silent** —
  it is a deliberate configuration choice, not a skipped change.
- Only report a skip when the binary actually changed. The digest comparison
  (`BlobDiffTrigger#sameContent`) must still run first, otherwise every check-in of an unchanged
  large file would produce an entry.
- The two extra `AuditRouter` / `eventTypes` requirements do not change: the same
  `blobContentModified` event is reused, only its extended infos differ.
- The purge dialog of the Web UI already offers both statuses as filters; once the audit entry
  carries `skipReason`, decide whether those filters should target the audit or stay on `BlobDiff`
  (they currently filter `bdiff:status`, which will never hold those values under option (b) —
  either remove them from the dialog or repoint them).
- Add end-to-end coverage: a blob over `maxBlobSize` and a blob with an unsupported MIME type must
  each produce exactly one audit entry with the right `skipReason`, and **zero** `BlobDiff`.

### 14. `DiffResult#summary()` is a persisted, untranslatable English string (needs a decision)

`DiffResult#summary()` builds a hardcoded English sentence ("2 modifications, 1 addition") that is
**persisted** in `bdiff:summary` and displayed as-is by the Web UI listing and document view. It is
neither translatable nor redundant-free: `added`, `removed` and `changed` are already stored as
separate fields next to it.

Letting the UI compose the sentence from the counters is the clean fix, but it is **not** a cleanup:
every `BlobDiff` already stored keeps its English string, so the listing would mix generated and
stored wording unless a migration rewrites them. Hence the product decision needed:

- leave it as is and accept English-only summaries; or
- compose in the UI and accept a mixed display on historical data; or
- compose in the UI **and** migrate existing documents (likely a Bulk Action, see item 11).

Not scheduled until that decision is taken.

---

## Other observations (not scheduled)

- **Repository growth.** One `BlobDiff` per version per xpath, in `/change-diff/yyyy/MM/dd`. On a
  busy instance a single day folder can reach hundreds of thousands of children, well beyond Nuxeo
  recommendations. Consider an hourly level or hash bucketing.
- **`coalesce` reports misleading counts.** A block of 3 removals followed by 3 additions yields
  "2 deletions, 1 modification, 2 additions" instead of "3 modifications", because coalescing is
  done pair by pair. Coalesce by blocks instead.
- **Front-end.** `nuxeo-blobdiff-viewer` uses a raw `fetch(url, {credentials:'include'})` rather
  than the Nuxeo client: works with cookie sessions, breaks with JWT/OAuth and ignores CORS config.
  It also downloads the whole diff before paginating the rendering.
- **Audit/diff reconciliation.** The audit entry is written synchronously while the work runs after
  commit. If the work is lost, the entry points to a `BlobDiff` that never existed. A reconciliation
  job, or a "pending" state surfaced in the UI, would close the gap.
