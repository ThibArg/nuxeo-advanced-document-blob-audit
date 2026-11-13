# AGENTS.md — nuxeo-advanced-document-blob-audit

Working notes for AI agents picking this repository up. Read this before touching the code.
Documentation homes: `README.md` for users and administrators, `docs/DESIGN.md` for the *why*,
this file for the rules to keep — each pointing to the section of `docs/DESIGN.md` that explains it.

## What this plugin does

Audits **what actually changed inside a binary** between two successive Nuxeo versions of a
document: Excel cell by cell, PowerPoint slide by slide, Word paragraph by paragraph, PDF line by line
as rendered, text formats line by line.

Pipeline:

1. `BlobModificationListener` (synchronous, on `aboutToCheckIn` + `documentCheckedIn`, both fired
   on the live document) establishes the version pair, and `VersionBlobPairs` lists the blobs to
   compare.
2. For each pair, `BlobDiffTrigger#scheduleIfNeeded` checks the eligibility and the digests. A real
   change fires a `blobContentModified` audit entry (**no business data**: xpath, filenames, mime
   types, and either a `diffCorrelationId` or a `skipReason`). A diffable one also schedules a
   `BlobDiffWork` carrying a `DiffRequest` (one `FrozenBlob` per side).
3. `BlobDiffWork` (queue `blobDiff`, after commit) re-reads both binaries by storage key, extracts
   and compares them with no transaction open (`BlobDiffService#diff`, `TextDiffer`), and
   `BlobDiffStore` writes the result as a `BlobDiff` document under the ACL-restricted
   `/change-diff/YYYY/MM/DD/` (UTC) container.

A binary that changed but cannot be diffed (over `maxBlobSize`, no extractor for the mime type, read
by another extractor contribution than the previous version, not managed, in a reshaped list) gets
the audit entry only, with a `skipReason` and no `diffCorrelationId`. Statuses: README, "Statuses";
`DiffStatus` holds each value and its audit comment.

Formats are supported by contributing `BlobContentExtractor` implementations to the `extractors`
extension point; image inventories go to `imageExtractors`. The diff engine is format-agnostic.

## Layout

Maven multi-module, parent `org.nuxeo:nuxeo-parent:2025.24`, version `2025.1.0-SNAPSHOT`.

- `nuxeo-advanced-document-blob-audit-core/` — all Java, all contributions, and the Web UI.
  - `src/main/java/org/nuxeo/audit/advanced/blob/`:
    - `BlobDiffComponent`, the `BlobDiffService` implementation: configuration and extractor
      registries, pair eligibility, `diff`. Its `start` installs `BlobDiffRepositoryInit` and adds the
      `blobContentModified` row to the `eventTypes` vocabulary.
    - `BlobModificationListener` (version pairing, disabling switches), `VersionBlobPairs` (which
      blobs of two versions to compare), `BlobDiffTrigger` (eligibility, digests, skip or schedule).
    - `DiffRequest` + `FrozenBlob` (what a diff compares: built at check-in, carried by the work,
      written on the `BlobDiff`, read back by the retry), `VersionContext` (the version pair).
    - `BlobDiffStore` (the `/change-diff` container and the `BlobDiff` documents; static, privileged
      session), `BlobDiffRepositoryInit`, `BlobDiffLocationListener`, `BlobDiffAccess` (auditor check,
      auditors group).
    - `DiffStatus`, `DiffEligibility`, `DiffResult`, `BlobAuditConstants`, `BlobDiffConfigDescriptor`,
      `ExtractorDescriptor`.
    - `BlobContentExtractor` (the extension interface), `ContentLine`, `DiffableContent`, `TextDiffer`.
    - `extractor/`: `SpreadsheetExtractor`, `PresentationExtractor` + `SlideHandler`,
      `PlainTextExtractor`, `ConverterTextExtractor`, `BoundedText` (the text a SAX handler reads).
    - `image/`: `AbstractImageInventory` and its four subclasses `WordImageInventory`,
      `SpreadsheetImageInventory`, `PresentationImageInventory`, `PdfImageInventory`.
    - `io/`: `MaterializedBlob`, `OoxmlPackages`, `BlobCharsets`, `BlobDiffSourceEnricher`.
    - `operations/` (the six `BlobDiff.*` operations), `bulk/BlobDiffPurgeAction`, `work/BlobDiffWork`.
  - `src/main/resources/OSGI-INF/` — 11 components, all listed in `META-INF/MANIFEST.MF`, plus
    `deployment-fragment.xml` and `l10n/`.
  - `src/main/resources/schemas/` — `blobdiff.xsd` (`bdiff:`) and `blobdiffsearch.xsd` (`bdsearch:`,
    the search document of `BLOB_DIFFS_ADMIN`, never persisted).
  - `src/main/resources/web/nuxeo.war/ui/` — Polymer elements under
    `nuxeo-advanced-document-blob-audit/elements/`, the two halves of the `blobdiffs` named search
    under `search/blobdiffs/`, layouts under `document/blobdiff/`, client i18n under `i18n/`.
- `nuxeo-advanced-document-blob-audit-package/` — the marketplace package (the bundle only).
- `docs/DESIGN.md` — design notes. `README-Summary/` — two HTML overviews, maintained by hand: mirror
  any user-visible change there.

**Two i18n sets, two consumers, not copies of one another.**
`OSGI-INF/l10n/messages_en_US.properties` and `messages_fr_FR.properties` are **server-side**:
`deployment-fragment.xml` appends them to `nuxeo.war/WEB-INF/classes/messages*.properties`, the
bundle `SuggestDirectoryEntries` localises vocabulary labels with — so the `eventTypes` label of
`blobContentModified` must live there. `web/nuxeo.war/ui/i18n/messages*.json` is **client-side**,
read by Web UI only. A key may exist in both (`label.blobaudit.event.blobContentModified` does);
adding a key to one does not imply adding it to the other.

## Invariants to keep in mind

Each rule names where it lives and what guards it. Do not weaken a guarding test to make a change
pass: a failing one means a guarded behaviour broke.

### Detection

**Version pairing is captured, not inferred.** `BlobModificationListener#capturePreviousVersion`
records `getLastDocumentVersionRef` on `aboutToCheckIn`; `AbstractSession` forwards that option map
to `documentCheckedIn`, read with the platform's `checkedInVersionRef`. Two core call sites pass
`null` options: `previousVersionRefFallback` covers them, ordering by `ecm:versionCreated`. Do not
"simplify" it away, never reintroduce `ORDER BY uid:major_version`. The three reads are privileged.
Coverage: `TestBlobDiffVersionPairing`. Why: `docs/DESIGN.md` §2.

**The listener can be muted, and the checks come first.** Three switches: the
`DISABLE_BLOB_DIFF_LISTENER` context data / event property, which only reaches the listener for the
version `saveDocument` creates itself from a `VERSIONING_OPTION` (not automatic policies such as
`collaborative-save`, not `checkIn`, `restoreToVersion` nor `publishDocument`);
`BlobModificationListener.runDisabled(Runnable|Supplier)`, thread-scoped, restored in a `finally`;
`EventServiceAdmin.setListenerEnabledFlag("blobModificationListener", false)`. Any change to
`handleEvent` keeps these checks first, before any I/O. Then, before any version lookup,
`VersionBlobPairs#holdsBlob` returns early when the live document holds no blob at the xpaths the
pairs would read: keep it ahead of `capturePreviousVersion` and `scheduleDiffs`. Coverage:
`TestBlobDiffListenerDisabling`, `TestBlobDiffVersionPairing` (the "no blob" cases). Why:
`docs/DESIGN.md` §1, §2; the exact reach is in the README.

**Blobs inside a list are paired by content, never by index alone.**
`VersionBlobPairs#pairListEntries` expands a list template on each version: a digest on both sides
is unchanged wherever it sits; a new blob is compared with the one at its xpath only when the list
kept its shape and that one is gone; a new blob in a list that also lost an entry is
`skippedListChanged`. Configured indexed xpaths (`files:files/0/file`) stay compared by index. With
`<xpath>*</xpath>` the templates come from `DocumentModel#getSchemas()` of the new version (instance
facets included), cached per schema and cleared by `BlobDiffComponent#stop`. Coverage:
`TestBlobDiffVersionPairing`. Why: `docs/DESIGN.md` §3.

**The order of the checks in `BlobDiffTrigger#scheduleIfNeeded` is significant.** Eligibility, then
`NOT_APPLICABLE` returns silently, then `sameContent`, then a skip is reported, then the
`ManagedBlob` check, then the work is scheduled with `afterCommit=true`. Moving `sameContent` after
the skip would audit every new version of an over-sized document, and identical bytes re-uploaded
under another mime type. Coverage: `TestBlobDiffSkipReporting`. Why: `docs/DESIGN.md` §4.

**One extractor contribution is one comparability class.** `BlobDiffService#getEligibility(docType,
xpath, oldBlob, newBlob)` combines the two per-blob verdicts, then returns `FORMAT_CHANGED` when both
sides resolve to different extractor instances, compared by identity on one snapshot of the list.
Precedence: `NOT_APPLICABLE` > `TOO_LARGE` > `UNSUPPORTED_TYPE` > `FORMAT_CHANGED` > `ELIGIBLE`. It is
the **only** eligibility call of `BlobDiffTrigger` and of `BlobDiffWork#work`: do not inline
`DiffEligibility.combine` into either. The work-time call is what makes `skippedTooLarge`,
`skippedUnsupportedType` and `skippedFormatChanged` reachable as a `bdiff:status` (a retry, or a work
outliving a configuration change); the purge dialog offers them. The contributions are the rules:
`ConverterTextExtractor` is contributed three times (`converter`, `pdf`, `legacyPresentation`) — do
not fold `pdf` or `legacyPresentation` back into `converter`; every text format stays in
`plainText`. Coverage:
`TestBlobDiffService#testThePairEligibilityComparesTheExtractorContributions`, the format cases of
`TestBlobDiffSkipReporting`, `TestBlobDiffManagement#testARetryOnAPairThatChangedFormatIsFiledAsSuch`.
Why: `docs/DESIGN.md` §4.

**A text file replaced by another one is filed without its body, and only then.**
`BlobDiffComponent#isReplaced` flags a result `replaced()` when the normalised mime types differ,
both sides hold at least `minSimilarityLines` units and the similarity is under `minSimilarity` —
properties of the contribution reading the pair, parsed once into `ResolvedExtractor`, read by the
service, ignored by the extractor; an invalid value disables the rule, never the extractor. Keep the
mime-change condition (a rewrite in the same format is always diffed), keep the body in
`BlobDiffService#diff` (integrators recompute with it; `BlobDiffWork` drops it), keep the score in
characters over the smaller side, and keep the decision at work time. Coverage:
`TestTextDifferSimilarity`, the `replaced` / `minSimilarity` cases of `TestBlobDiffService` and
`TestBlobDiffSkipReporting`, `TestExtractorSelection#testAnInvalidSimilarityRuleIsDisabledRatherThanFatal`,
`TestBlobDiffManagement#testRetryRefusesAReplacedContent`. Why: `docs/DESIGN.md` §4.

**Contributions merge, they do not replace.** `BlobDiffConfigDescriptor` and `ExtractorDescriptor`
override `Descriptor#merge`, whose default returns the later contribution alone. Scalar fields are
boxed so that "not set" can be told from a value, getters apply the defaults, a list replaces the
earlier one only when not empty, extractor properties are overlaid. XMap cannot tell an empty list
from an absent one: "every blob xpath" is `<xpath>*</xpath>`, "every type" an empty `docTypes` or
`*`, an empty `xpaths` means `file:content`. A new field must be boxed and added to `merge`, or a
partial override resets it. Coverage: `TestBlobDiffConfigDescriptor`, `TestExtractorSelection`,
`TestBlobDiffService#testAPartialContributionOnlyChangesWhatItSets`.

### The work and the retry

**What a diff compares is frozen at check-in.** `BlobDiffTrigger` builds a `DiffRequest` with one
`FrozenBlob` per side and a mandatory `VersionContext`; the work re-reads both binaries through
`FrozenBlob#resolve`, never from the live document. `DiffRequest#writeTo` and `#readFrom` are the
only mapping between a request and the `BlobDiff` properties: keep them symmetric. An `error` diff
keeps the filenames and digests; a `null` side is filed `error`, never dereferenced. Logs name a side
by its filename, never by its key or digest. Coverage:
`TestBlobDiffService#testARequestSurvivesTheRoundTripThroughABlobDiff`,
`TestBlobDiffManagement#testAnErrorDiffKeepsTheFilenamesAndDigests`,
`#testAWorkWithAMissingSideIsFiledInError`, `#testAnUnreadablePairIsLoggedWithoutItsStorageKeys`.
Why: `docs/DESIGN.md` §1.

**The work id only names the work; `alreadyRecorded()` is the duplicate guard.**
`IF_NOT_RUNNING_OR_SCHEDULED` is ignored by LTS 2025 (`WorkManagerImpl#schedule` hard-codes it
disabled, `StreamWorkManager#schedule` ignores it), so `BlobDiffTrigger` schedules plainly and
`DiffRequest#workId` is a readable name. `BlobDiffWork#alreadyRecorded` looks the correlation id up
under `/change-diff`, proxies and versions excluded, the replaced diff excepted. Never move this guard
to the search engine: indexing is asynchronous. Coverage:
`TestBlobDiffHardening#testRunningTheSameWorkTwiceCreatesASingleDiff`. Why: `docs/DESIGN.md` §1, §9.

**`BlobDiffWork` extracts with no transaction open, and checks for a duplicate twice.** `work()`
reads, calls `commitOrRollbackTransaction()`, resolves the eligibility and runs `service.diff`, then
`startTransaction()` to write. Nothing in between touches the repository, and no `DocumentModel` read
before the commit is used after it. `alreadyRecorded()` runs again once the new transaction is open:
do not drop that second check. Coverage: `TestBlobDiffHardening#testTheExtractionRunsOutsideAnyTransaction`,
`#testADiffRecordedDuringTheExtractionIsNotDuplicated`. Why: `docs/DESIGN.md` §1.

**`BlobDiff.Retry` trusts nothing but the stored path.** `BlobDiffRetryOp#run` keeps this order:
`checkAuditor`, `reloadStored` (the id of the input only, reloaded with the caller's session; a model
never stored is refused), `checkProvenance` (under `/change-diff/`, before any other property is
read), type, status `error`, then `DiffRequest.readFrom` — which trusts every property it reads, so
only after `checkProvenance` — then both sides present, the pending check, the schedule. The work
re-reads the keys in a system session through `provider.readBlob`, which applies no ACL; its
eligibility re-check is no substitute (the lengths come from the diff). Coverage:
`TestBlobDiffManagement#testRetryRefusesADiffCreatedOutsideTheContainer`,
`#testRetryReadsTheStoredDiffNotTheModelItIsHanded`, `#testRetryRefusesADocumentThatIsNotStored`,
`#testRetryDoesNotExtractABlobThatIsNoLongerEligible`. Why: `docs/DESIGN.md` §7.

**A replay has a work id of its own, and one replay at a time.** `BlobDiffWork#withReplaceDiffId`
appends `:retry:<diffId>`: under the nominal id, the stream `WorkComputation` skips an idempotent
work among the last 50 ids of its thread. The diff stays `error` while its replay runs; a second
retry is refused with 409 while `WorkManager#getWorkState` reports the replay `SCHEDULED` or
`RUNNING`, and a `null` state is let through on purpose (a lost replay, or a stream WorkManager not
storing states). A replay that fails for good leaves the `error` diff in place. Coverage:
`TestBlobDiffManagement#testARetryRunsUnderAWorkIdOfItsOwn`,
`#testASecondRetryIsRefusedWhileTheFirstIsRunning`, `#testRetryReplaysAnErrorDiffAndReplacesIt`.
Why: `docs/DESIGN.md` §1.

**A failing extraction raises; a failing image inventory does not.** `BlobDiffComponent#extract`
returns `null` only when no extractor is registered (filed `skippedUnsupportedType`); a failing
extractor raises, filed `error`, retryable. The image inventory logs a `WARN` and keeps the text
result. Do not harmonise the two. `BlobDiffComponent#stop` keeps the extractor lists for the works
still draining, and `start` publishes them as `volatile` fields holding immutable lists. Coverage:
`TestBlobDiffManagement#testAnExtractionFailureIsRetryable`,
`TestBlobDiffService#testCorruptedSpreadsheetRaisesInsteadOfLookingUnsupported`,
`#testAStoppingComponentKeepsItsExtractors`, `#testTheExtractorListsArePublishedSafely`,
`TestBlobDiffImageExtraction`. Why: `docs/DESIGN.md` §4.

### Container, security, purge

**A `BlobDiff` lives under `/change-diff`, and nothing leaves it.** Two layers, keep both:
`BlobDiffLocationListener` refuses, for every principal including system, a `BlobDiff` created
elsewhere and any move or copy out of the container; every reader of `BlobDiff` documents filters on
`ecm:path STARTSWITH '/change-diff'` and `ecm:isProxy = 0` — both page providers,
`BlobDiffWork#existingDiffId`, the query of `BlobDiff.Purge`. A new reader needs the same filter.
Forged fixtures go through `BlobAuditTestHelper.createUnguarded`. Coverage:
`TestBlobDiffLocationAndSecurity`, `TestBlobDiffPurgeAction#testPurgeLeavesABlobDiffOutsideTheContainerAlone`,
`TestBlobDiffManagement#testAForgedDiffOutsideTheContainerDoesNotSuppressTheRetry`. Why:
`docs/DESIGN.md` §7.

**The container stays restricted in a cluster.** `BlobDiffStore`: the root is created at every
repository initialisation, even when the feature is off, with bounded retries
(`ROOT_CREATION_ATTEMPTS`); `repairSecurity` resets its ACL at each start; dated folders are created
in the caller's session under `CONTAINER_LOCK`; paths and the purge boundary use UTC and
`Locale.ROOT`; a diff is named `<sourceId>-<eventTime>-<correlationId>`. Coverage:
`TestBlobDiffLocationAndSecurity`, `TestBlobDiffService` (the container cases). Why:
`docs/DESIGN.md` §7.

**The purge stays off the HTTP surface and removes nothing but diffs.** The `blobDiffPurge` action
keeps `httpEnabled` at its default `false`; `BlobDiff.Purge` is the only entry point (auditor check,
query built server side, `submit()` and not `submitTransactional()`); the computation removes only
`BlobDiff` documents and checks `isAbortedCommand` before each batch; `BlobDiff.PurgeStatus` and
`BlobDiff.PurgeAbort` go through `BlobDiffPurgeAction#purgeStatus`, which answers any other command
as `UNKNOWN`. Coverage: `TestBlobDiffPurgeAction` (`httpEnabled` pinned by
`#testThePurgeActionIsNotExposedOverHttp`). Why: `docs/DESIGN.md` §7.

**Never put a `<dataFile>` on a vocabulary the plugin does not own.** It would replace the
platform's rows. `blobContentModified` is added at runtime by
`BlobDiffComponent#registerAuditEventType`: `hasEntry`, in a transaction, privileged, any
`RuntimeException` logged, never propagated; a runtime without `eventTypes` is skipped at `DEBUG`.
Its label is resolved server side: it lives in `OSGI-INF/l10n/messages_*.properties`. Coverage:
`TestAuditEventTypeRegistration`. Why: `docs/DESIGN.md` §8, §9.

### Diff engine

**The diff body is bounded by construction, and the counters are not.** Both strategies of
`TextDiffer` write their body through `TextDiffer.Body`, which checks `maxEntries` and `maxChars` on
the **rendered** entry, never renders an entry past the budget, and cuts monotonically (the body is
a contiguous prefix of the full diff). `added` / `removed` / `changed` are counted before the budget
is consulted, so they stay exact. `TextDiffer#merge` caps text plus images at `maxChars`. Coverage:
`TestTextDifferBounding`. Why: `docs/DESIGN.md` §5.

**One entry per line in the body.** `TextDiffer#renderValue` writes every line break of a key or a
value (`\R`, `\r\n` counting as one) as a literal `\n`, after `elide`. Every entry renderer goes
through it; a raw break would let a multi-line cell or paragraph spread over several lines, or forge
prefixed ones. Rendering only: never escape in the extractors or in `ContentLine`, the values, their
comparison and the similarity are the document's. Coverage: `TestTextDiffer` (the "line breaks
inside a value" cases). Why: `docs/DESIGN.md` §5.

**A unit missing from a cut side is unknown past the last shared unit.** Keyed:
`TextDiffer#pastLastShared`; positional: `Alignment#tailStart`, the removals past it dropped when the
new side is cut, the additions when the old side is. Both rely on extraction following document
order. The similarity counts kept characters *before* that drop. Coverage: `TestTextDiffer` (the
"before the cut" cases), `TestTextDifferSimilarity`. Why: `docs/DESIGN.md` §5.

**Hirschberg stays, and so do trimming and interning.** `TestTextDiffer` and `TestTextDifferScaling`
assert alignment optimality and the memory bound: the contract any replacement must satisfy. The
measured figures have one home, the class Javadoc of `TextDiffer`. Why: `docs/DESIGN.md` §5, §10.

### Extractors

**Extracted values are bounded when they are created.** `BlobDiffComponent#resolve` hands
`maxValueLength` to every extractor (`BlobContentExtractor.MAX_VALUE_LENGTH`) unless its contribution
sets one, and the built-in extractors build every value through `ContentLine.of(key, value,
maxValueLength)`. A new extractor must do the same, including for what it holds before a value is
final (XLSX shared strings, the lines and the title of a slide). The character data a SAX handler
accumulates goes through `BoundedText` (shared strings, cell values, inline strings, formulas,
paragraphs, table cells and rows): whole up to `BoundedText.HELD_LENGTH`, the most an Excel cell
holds, exactly `ContentLine#compact` of the whole past it, never a raw `StringBuilder` — every budget
applies to a finished value, and one inflated text node would be held whole before any is consulted.
Coverage: `TestExtractedValueBounding`, `TestSpreadsheetStreaming#testAnInflatedTextNodeIsNeverHeldWhole`,
`TestPresentationStreaming#testAnInflatedParagraphIsNeverHeldWhole`,
`TestConverterTextExtractor#testAConvertedLineIsBoundedByMaxValueLength`,
`TestBlobDiffService#testTheConfiguredMaxValueLengthReachesTheExtractors`. Why: `docs/DESIGN.md` §5.

**`BlobContentExtractor#extract` declares `throws IOException`, never wider.** A `throws Exception`
forces `catch (Exception)`, which swallows the interruption of a WorkManager thread at shutdown. A
non-I/O failure is a `NuxeoException`. `BlobDiffComponent#resolve` catches
`ReflectiveOperationException | RuntimeException`: a bad contribution costs one extractor. Why:
`docs/DESIGN.md` §6.

**One local file per binary, owned by `MaterializedBlob`.** `BlobDiffComponent#diff` materialises
both sides once; extractors needing random access go through `MaterializedBlob#of`, a pass-through
for a blob already backed by a file — which is never deleted. Otherwise `MaterializedBlob` makes its
own copy with `Framework#createTempFile` (not `Blob#getCloseableFile`, whose `Framework#trackFile`
needs a runtime) and deletes it on `close`. A failed copy raises, so the diff is filed `error` and is
retryable: no in-heap fallback. Coverage: `TestMaterializedBlob`. Why: `docs/DESIGN.md` §6.

**OOXML is streamed, never read through POI's usermodel.** `SpreadsheetExtractor` parses each sheet
with SAX and stops at `maxLines`; its values stay identical to the usermodel path kept for `.xls`
(`TestSpreadsheetStreaming`, `TestExcelDiff`). Shared formulas are rebuilt by `SharedFormulas`;
shared strings are read by `SharedStringTable` within `maxLines × maxValueLength`, a cell past that
budget ending the extraction as truncated, never a placeholder. `SlideHandler` mimics
`XSLFSheet#buildShapes`, quirks included, so that `TestPresentationStreaming` can compare it with the
usermodel: keep both. Each slide is parsed once; its title is held compacted (`TitleText`, same format
as `ContentLine#compacted`). Every SAX parser comes from `XMLHelper.newXMLReader()` through
`OoxmlPackages#parse`, a parse is stopped with `OoxmlPackages.Stop`, packages are opened read only
from a local file by `OoxmlPackages#open` and released with `revert()`. Why: `docs/DESIGN.md` §6.

**The converter output is read line by line.** `ConverterTextExtractor` stops at `maxLines` and
splits each line again on `OTHER_LINE_BREAKS`; dropping that split would shift every converted PDF.
Decodes go through `BlobCharsets`. Coverage:
`TestConverterTextExtractor#testTheConverterOutputIsSplitOnEveryLineBreak`,
`#testTheConverterOutputIsReadOnlyUpToMaxLines`. Why: `docs/DESIGN.md` §6.

**One image inventory class per format, one budget for all.** `WordImageInventory`,
`SpreadsheetImageInventory`, `PresentationImageInventory` and `PdfImageInventory` extend
`AbstractImageInventory`, which owns `maxImageBytes()` (32 MB, enforced by `digestStream`), one
`ImageBudget` per file (`digestOnce` memoises, `meter` charges every byte against 256 MB) and the
`Inventory` accumulator. Image bytes are read only through `digestOnce` and a metered stream; OOXML
pictures are streamed from their part (`digestOoxmlPicture`), `.xls` pictures copied once inside the
`digestOnce` supplier; the XLSX inventory never parses a sheet, it reaches the drawings through the
relationships of the sheet part; Word keeps its zip-stream reading and ratio pre-check. A PDF image
is never decoded (`digestPdfImage`: no `createInputStream()`, `getImage()` nor `getWidth()`); a PDF
form is walked once per document (`PdfWalk`, cycle and `MAX_FORM_DEPTH` guards; never recurse
outside `collectPdfForm`). Ordinals and `#n` suffixes advance before the drop check, so a dropped
image never renumbers. A dropped image is logged by its part name, or for `.xls` by its sheet number,
never by its key: a sheet name is document content. Coverage: `TestImageInventoryExtractor`,
`TestBlobDiffImageExtraction`. Why: `docs/DESIGN.md` §6, §7.

### Search and Web UI

**Know where each query lands.** `BlobDiffWork#existingDiffId`,
`BlobModificationListener#previousVersionRefFallback` and `BLOB_DIFFS_FOR_DOCUMENT` always run on the
repository — keep `BLOB_DIFFS_FOR_DOCUMENT` there, it is synchronous and the tests rely on it.
`BLOB_DIFFS_ADMIN` runs on the configured search client; on the default `repository` client its
facets come back silently empty. No `<require>` on `ElasticSearchComponent`. `bdiff:correlationId`
and `bdiff:sourceId` keep `indexOrder`. Coverage: `TestBlobDiffAdminSearch`. Why: `docs/DESIGN.md` §8.

**`BLOB_DIFFS_ADMIN` declares `<searchDocumentType>`, and the UI keeps the `bdsearch:` prefix.**
`whereClause@docType` is dropped by XMap and every filter is then silently ignored; a bare key can
collide across schemas. A boolean filter sends `true` or nothing, never `false`. Coverage:
`TestBlobDiffAdminSearch`. Why: `docs/DESIGN.md` §8.

**The named search is wired by string.** `search-name="blobdiffs"` stays a literal attribute,
`name` stays on the inner `nuxeo-search-form` (never on the `nuxeo-filter`), the folder, file names
and `dom-module` ids under `ui/search/blobdiffs/` stay in lockstep with the search name, the results
layout keeps a literal `<nuxeo-results>`, and the data table gets no height. Each is a silent failure
when broken; there is no test. Why: `docs/DESIGN.md` §8.

**The Web UI overrides are deliberate.** `blobDiffSource` is added to the list property
`org.nuxeo.web.ui.enrichers.document` (the `BlobDiff` view fetches the document once); the
`editDocumentAction` slot content is re-contributed to hide Edit on `BlobDiff` — re-check both when
upgrading Web UI. Its filter expression uses `FiltersBehavior` and `document` only
(`!this.hasType(document, 'BlobDiff')`), never a `Nuxeo.*` global: with
`org.nuxeo.web.ui.expressions.eval=false` the sandbox cannot reach one, and a failing filter there
hides Edit on every type. The three visibility filters keep `Nuxeo.BlobAudit.isAuditor` on purpose
(the README states the CSP-mode limitation). `nuxeo-blobdiff-metadata-layout.html` is empty on
purpose. Why: `docs/DESIGN.md` §8.

## Conventions

Standard Nuxeo LTS 2025 plugin conventions apply (`jakarta.*`, Log4j2 `LogManager.getLogger()`,
JUnit 4 + `FeaturesRunner`, `Framework.getService()`, 4-space indent, ~120 char lines, no wildcard
imports, `@since 2025.1` on new public API).

Repository-specific:

- The feature is **disabled by default** (`<config enabled="false">`). Tests enable it through
  `src/test/resources/blobaudit-test-config.xml`.
- Every new `OSGI-INF/*.xml` component **must** be added to the `Nuxeo-Component` header of
  `src/main/resources/META-INF/MANIFEST.MF`, one entry per line, single leading space on
  continuation lines, trailing newline at EOF. A component missing from that header is silently
  never loaded.
- `BlobDiff` documents are **never** trashed, only permanently deleted.
- Anything written to a `BlobDiff` is business content extracted from the source. Treat
  confidentiality as a first-class concern in every change.
- Comments keep the *why*, never the history ("used to", item numbers, review ids): the commit
  message is the record.
- Documentation homes: `README.md` for users and administrators (statuses, configuration, Web UI,
  operations, limitations), `docs/DESIGN.md` for the reasons, this file for the rules. A rule here
  points to its section of `docs/DESIGN.md`; a user-visible change updates the README and
  `README-Summary/`.
- `AGENTS.md` is **not** in `.gitignore` for this repository, so it is pushed. Keep it free of any
  sensitive information: no login, password, key, e-mail address, personal name or local path.

## Build and test

```bash
mvn -o install                                     # full build: 327 tests, 27 test classes
mvn -o test -pl nuxeo-advanced-document-blob-audit-core -Dtest=TestBlobDiffHardening
mvn -o test -pl nuxeo-advanced-document-blob-audit-core -Dtest='TestTextDiffer#testChangeOnTheFirstLine'
mvn -o dependency:analyze -pl nuxeo-advanced-document-blob-audit-core
```

`-o` only works once the local Maven repository is warm (the build resolves against the Nuxeo
public repository). `nuxeo.skip.enforcer` is `true` in the parent pom, so the Nuxeo enforcer rules do
not run: rely on `dependency:analyze` instead, whose "Used undeclared dependencies" must stay empty.
Do not run the build when a commit only touches `README.md`, `AGENTS.md`, `docs/`, `README-Summary/`
or `.gitignore`.

Tests needing no Nuxeo runtime (pure JUnit, about 20 seconds):

```bash
mvn -o test -pl nuxeo-advanced-document-blob-audit-core \
  -Dtest='TestTextDiffer,TestTextDifferScaling,TestTextDifferBounding,TestTextDifferSimilarity,TestExcelDiff,TestPresentationDiff,TestImageInventoryExtractor,TestPlainTextExtractor,TestExtractorSelection,TestSpreadsheetStreaming,TestPresentationStreaming,TestExtractedValueBounding,TestBlobDiffConfigDescriptor'
```

`TestMaterializedBlob` needs the runtime but not the repository (`RuntimeFeature` only). Every other
runtime test goes through `BlobAuditFeature`: in-memory audit backend and `CoreFeature`, deploying
this bundle plus `blobaudit-test-config.xml` (feature enabled), `blobaudit-test-pageprovider-contrib.xml`
and `blobaudit-test-vocabulary-template-contrib.xml` (supplies `template-vocabulary`, which the
platform ships in `org.nuxeo.ecm.default.config`, a bundle this test stack does not deploy; without
it `eventTypes` never registers). `TestBlobDiffAdminSearch` adds `CoreSearchFeature`;
`TestBlobDiffPurgeAction` adds `CoreBulkFeature` and waits on `BulkService#await`.

Test resources deployed per class or per test (`src/test/resources/`):

| Resource | Purpose |
|---|---|
| `blobaudit-test-smallblob-config.xml` | a low `maxBlobSize` |
| `blobaudit-test-smallvalue-config.xml` | `maxValueLength` set to 200 |
| `blobaudit-test-imageinventory-config.xml` | `imageInventory` on |
| `blobaudit-test-noimagepdf-config.xml` | one image extractor disabled |
| `blobaudit-test-failingimage-contrib.xml` | `FailingImageExtractor`, ahead of `imageWord`, throws |
| `blobaudit-test-txprobe-contrib.xml` | `TransactionProbeExtractor`, ahead of `plainText`, records whether a transaction is open |
| `blobaudit-test-passthrough-contrib.xml` | `PassThroughConverter` (`blobauditPassThrough`): the converter output is the input |
| `blobaudit-test-allxpaths-config.xml` | `<xpath>*</xpath>` (`TestBlobDiffVersionPairing`) |
| `blobaudit-test-dynamicfacet-contrib.xml` + `schemas/blobaudit-test-extra.xsd` | a facet whose schema holds a blob (`TestBlobDiffVersionPairing`) |
| `blobaudit-test-partial-config.xml` | overrides nothing but `docTypes` and one `converter` property (`TestBlobDiffService`) |
| `blobaudit-test-keepalldiffs-config.xml` | `minSimilarity=0` on `plainText` |
| `blobaudit-test-purgegate-contrib.xml` | `PurgeGateListener` holds the first `BlobDiff` removal until released (`TestBlobDiffPurgeAction` abort and exclusivity cases) |

Test helpers: `BlobAuditTestHelper` (`DiffRequest` and `BlobDiff` fixtures, `createUnguarded` for a
diff forged outside the container, `onDisk` for file-backed blobs, `allocatedBytes` for every memory
bound: it skips the test where the JVM cannot measure allocations, rather than let the bound pass
unmeasured), `BlobAuditFeature`.

What each test class guards is named by the invariants above. When a change adds or removes tests,
update the count on the first line of this section.

## Deployment target

This plugin is heading for a **real instance**, not a demo. Consequence: anything that only matters
at small volume can no longer be waved away — this is what made the purge on the Bulk Action
Framework mandatory rather than optional.

## Open items

### 26. A `BlobDiff` page does not refresh when navigating to another `BlobDiff` — OPEN

Navigate from one `BlobDiff` to another — from the drawer queue of `nuxeo-search-form`, or from any
link — and the **URL, the browser title and the Web UI header all update, but the whole
`nuxeo-blobdiff-view-layout` card stays on the previous diff**. A reload fixes it. The drawer queue
merely makes it easy to hit. The view layout no longer fetches the document itself (it reads the
`blobDiffSource` enricher Web UI now requests): check first that the bug still reproduces.

What is established, so it does not get re-derived:

- **Not the platform.** The identical flow on the platform's *default* and *assets* searches —
  queue view, click one document then another — refreshes correctly. So the
  `navigateTo` → `page()` → `app.load` → `currentDocument` chain is sound.
- **Not a stale deployment.** `deployment-fragment.xml` `<unzip>` only ever *merges* into
  `nuxeo.war`, so files removed from the bundle linger in a bind-mounted `ui/` folder until it is
  cleared; that caused a broader navigation breakage once, and was fixed separately.
- **Not `route="search:blobdiffs"`.** Suspected first because no platform `DRAWER_ITEMS` icon
  carries a `route`, and page.js runs with `click: false`; ruled out by testing.
- **Our element is never touched.** Temporary instrumentation (`attached`, `detached` and the
  `document` observer of the layout; `attached`, `detached`, `_load` on `nuxeo-blobdiff-viewer`)
  logged a full, correct sequence on the *first* open and then **absolutely nothing** on the
  second.

That last point is the whole finding: `document` never reaches our layout, so the break is
**upstream of this plugin**, somewhere in

```
app.currentDocument -> nuxeo-browser.document -> viewsContext (computed: _viewsContext(document))
  -> nuxeo-slot DOCUMENT_VIEWS_PAGES -> nuxeo-document-view.document
  -> nuxeo-document-layout.document -> _loadLayout -> nuxeo-layout -> our element
```

Every link reads as sound — `viewsContext` is a computed object so it is new each time, and
`nuxeo-slot._forwardHostProp` does `instance.set('document', value)` — yet one of them fails for
this doctype and not for `File`.

**Leading suspicion, untested:** several `DOCUMENT_VIEWS_PAGES` contributions share `name="view"`
(`nuxeo-document-view`, plus the picture/video/collection variants, each behind a `nuxeo-filter` on
a facet). If more than one is stamped for a `BlobDiff`, `iron-pages` would display one instance
while the model updates another. Counting the stamped `nuxeo-document-view` elements is the first
thing to check.

**Resume here:** reproduce, then run the probe below in the console and find the first `*_doc` that
still reports the *old* uid — that is the broken link.

```js
(() => {
  const deep = (root, sel, acc = []) => {
    if (!root || !root.querySelectorAll) return acc;
    root.querySelectorAll(sel).forEach((e) => acc.push(e));
    root.querySelectorAll('*').forEach((e) => e.shadowRoot && deep(e.shadowRoot, sel, acc));
    return acc;
  };
  const app = document.querySelector('nuxeo-app');
  const S = app.shadowRoot, u = (d) => (d && d.uid) || null;
  const br = deep(S, '#browser')[0], dv = deep(S, 'nuxeo-document-view'),
        dl = deep(S, 'nuxeo-document-layout'), vl = deep(S, 'nuxeo-blobdiff-view-layout');
  console.log({
    app_currentDocument: u(app.currentDocument),
    browser_document: u(br && br.document),
    documentView_n: dv.length, documentView_doc: u(dv[0] && dv[0].document),
    documentLayout_n: dl.length, documentLayout_doc: u(dl[0] && dl[0].document),
    documentLayout_href: dl[0] && dl[0]._href,
    viewLayout_n: vl.length, viewLayout_doc: u(vl[0] && vl[0].document),
  });
})();
```

### 24. Replace the idempotency query with a path lookup — OPTIONAL, NOT RECOMMENDED

`BlobDiffWork#existingDiffId` could check the deterministic path
(`/change-diff/YYYY/MM/DD/<sourceId>-<eventTime>-<correlationId>`) instead of querying. Not done: the
index on `bdiff:correlationId` already solves the cost, and `AbstractSession#createDocument` lets a
listener rename a document, so the name is not contractual — the query would have to stay as a
fallback anyway. Never use the search engine for this guard: indexing is asynchronous.

### Other observations (not scheduled)

- **Repository growth.** One `BlobDiff` per version per xpath in `/change-diff/yyyy/MM/dd`; a single
  day folder can reach hundreds of thousands of children. Consider an hourly level or hash bucketing.
- **`coalesce` reports misleading counts.** 3 removals followed by 3 additions yields "2 deletions,
  1 modification, 2 additions" instead of "3 modifications": coalescing is pair by pair, it could be
  by blocks.
- **Front-end.** `nuxeo-blobdiff-viewer` uses a raw `fetch(url, {credentials: 'include'})` rather than
  the Nuxeo client: it works with cookie sessions, breaks with JWT/OAuth and ignores the CORS
  configuration. It also downloads the whole diff before paginating the rendering.
- **Audit/diff reconciliation.** The audit entry is written synchronously while the work runs after
  commit. If the work is lost, the entry points to a `BlobDiff` that never existed. A reconciliation
  job, or a "pending" state in the UI, would close the gap.

## Numbering

Work is numbered and cited in commit messages (`Item NN: ...`); history:
`git log --grep '^Item '`. Next free number: 102.
