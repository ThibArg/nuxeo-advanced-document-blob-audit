# Design notes — nuxeo-advanced-document-blob-audit

Why the plugin is built the way it is, for whoever forks or maintains it. The [README](../README.md)
says what it does and how to configure it; [`AGENTS.md`](../AGENTS.md) lists the rules to keep, each
pointing here for its reason. Code is cited by class and method name, never by line number.

Contents: [1. Pipeline](#1-pipeline) · [2. Version pairing](#2-version-pairing) ·
[3. Which blobs are compared](#3-which-blobs-are-compared) ·
[4. Eligibility and reporting](#4-eligibility-and-reporting) · [5. Diff engine](#5-diff-engine) ·
[6. Extractors](#6-extractors) · [7. Security model](#7-security-model) ·
[8. Search and Web UI](#8-search-and-web-ui) · [9. Platform facts relied on](#9-platform-facts-relied-on) ·
[10. Decisions and rejected ideas](#10-decisions-and-rejected-ideas)

## 1. Pipeline

### Synchronous detection

`BlobModificationListener` is a synchronous listener on `aboutToCheckIn` and `documentCheckedIn`,
both fired on the **live** document. It first honours its three disabling switches, before any
I/O: a muted listener must cost nothing and read nothing. It then establishes the version pair
(§2), lists the blob pairs to compare (`VersionBlobPairs`, §3), and hands each pair to
`BlobDiffTrigger#scheduleIfNeeded` (§4). Every auditable outcome fires a `blobContentModified`
event in the user transaction, which the route of `blobaudit-audit-contrib.xml` turns into an audit
entry.

Everything synchronous is cheap: two version references, two document loads, digests and mime
types. No binary is ever read in the user transaction. Right after the switches and the scope checks
(`enabled`, `docTypes`), `VersionBlobPairs#holdsBlob` reads the properties of the live document the
pairs would read: a check-in versions the live document as it stands, so with no blob there no pair
can have a new blob to compare, and both events return before any version lookup. Without it, every
check-in of an accepted type — `collaborative-save` snapshots, publishing, notes, folders — paid a
privileged query and the load of the pair for nothing. A listener running after this one on
`aboutToCheckIn` and adding a blob is still covered: the capture is skipped, and `documentCheckedIn`
falls back on the query of §2. Coverage: `TestBlobDiffVersionPairing` (the "no blob" cases). The work is scheduled with
`afterCommit=true`: a rollback creates neither the audit entry nor a `BlobDiff`.

### What travels with the work

`BlobDiffTrigger` builds a `DiffRequest`: repository, source id and title, xpath, one `FrozenBlob`
per side (provider id, storage key, filename, mime type, digest, length), the `VersionContext` of the
pair — mandatory, the record refuses `null` — the user, the event time and a random correlation id,
the one the audit entry carries as `diffCorrelationId`. The work re-reads both binaries through
`FrozenBlob#resolve` (`BlobProvider#readBlob`), never from the live document: a later save could
otherwise make a queued v1 → v2 work compare v1 → v3.

`DiffRequest#writeTo` and `DiffRequest#readFrom` are the only mapping between a request and the
properties of a `BlobDiff`, side by side and kept symmetric by
`TestBlobDiffService#testARequestSurvivesTheRoundTripThroughABlobDiff`. Two consequences: an
`error` diff records the filenames and digests even when no blob could be read, and a retry rebuilds
its work from the stored diff alone. A side read back without provider id or key is `null`: the work
files such a pair in `error`, `BlobDiff.Retry` refuses it.

### The asynchronous work

`BlobDiffWork` runs on a queue of its own, `blobDiff` (`maxThreads=2`). On the shared `default`
queue, a burst of check-ins on large Office or PDF files would starve every other asynchronous work
of the instance; each thread can hold two extracted documents, hence the low concurrency.

`BlobDiffWork#work` reads, commits, extracts with **no transaction open**, then writes in a new one:

1. open a system session; `alreadyRecorded()`;
2. if the source no longer exists, file an `error` diff whose summary says so, so that the audit
   entry is never orphaned;
3. read the type and the title of the source — the diff records the title at work time — and resolve
   both frozen sides;
4. `commitOrRollbackTransaction()`;
5. re-check the eligibility of the pair (§4), then `BlobDiffService#diff`;
6. `startTransaction()`; `alreadyRecorded()` again; `BlobDiffStore#createDiffDocument`;
   remove the diff this work replaces, if any.

Materialising and parsing two binaries can outlast the transaction timeout. Inside the work
transaction the commit would then fail: nothing recorded, not even an `error` diff, and each retry
running the whole extraction again. Between steps 4 and 6 nothing touches the repository, and no
`DocumentModel` read before the commit is used after it: what is needed is copied into locals first.
Coverage: `TestBlobDiffHardening#testTheExtractionRunsOutsideAnyTransaction`.

The status is decided in step 5: an unreadable side is `error`; an ineligible pair takes the skip
status of its eligibility; a `null` result (no extractor) is `skippedUnsupportedType`; a result
flagged `replaced()` is `skippedContentReplaced`; an extraction that raises is `error`; anything
else is `ok`.

### Idempotency and retries

The WorkManager is at-least-once: a node crash, a redeployment or a retry can run a work twice.
`BlobDiffWork#alreadyRecorded` looks for a `BlobDiff` carrying the same `bdiff:correlationId`, under
`/change-diff`, neither proxy nor version, the diff being replaced excepted, and ends the run if it
finds one. Without it, the second run would create a second document — the core renaming it, since
the computed name is taken — and the audit trail would hold indistinguishable duplicates. It runs
twice, because another run of the same work may record the diff while this one extracts
(`TestBlobDiffHardening#testADiffRecordedDuringTheExtractionIsNotDuplicated`). The path filter is
there because the correlation id is published in the audit entry: a `BlobDiff` forged elsewhere with
that id could otherwise suppress the genuine one.

The work id, `DiffRequest#workId` (`<repository>:<source>:<xpath>:<previous>><new>:blobDiff`), is a
stable, readable name, **not** a deduplication key: LTS 2025 ignores `IF_NOT_RUNNING_OR_SCHEDULED`
(§9), so the work is scheduled plainly and `alreadyRecorded()` is the duplicate guard.

`getRetryCount()` is 2: transient failures are the common case (an S3 read timing out, a converter
momentarily unavailable, a concurrent update on a dated container), and retrying is safe because of
the guard above.

### Replays

`BlobDiff.Retry` schedules the work rebuilt from an `error` diff under an id of its own,
`<work id>:retry:<diff id>` (`BlobDiffWork#withReplaceDiffId`). Under the nominal id, the stream
WorkManager would take it for a duplicate of the run that recorded the error and drop it in silence
(§9).

A second retry is refused with 409 while `WorkManager#getWorkState` reports that replay `SCHEDULED`
or `RUNNING`: on the in-memory WorkManager, with two threads, two replays of one diff could both pass
`alreadyRecorded()` before either commits. A `null` state lets the retry through on purpose: it is
what a replay lost with a restarted node looks like, and all the stream WorkManager reports when it
does not store work states — two replays are harmless there, since they share the partition of the
source document, run one after the other, and the second finds the diff of the first.

The diff stays `error` while its replay runs. A replay that fails for good leaves it in place, still
retryable; a successful one creates the new diff — same user, date and correlation id, so the audit
entry still points to it — then removes the old one. Only `error` is retryable: a skip status is no
incident, and replaying it under the same configuration would file it the same way.

## 2. Version pairing

`aboutToCheckIn` is fired inline on the live document just before the new version exists, so
`getLastDocumentVersionRef` returns exactly the version the new one will succeed.
`BlobModificationListener#capturePreviousVersion` stores it in the event properties
(`blobDiffPreviousVersionRef`). `AbstractSession` passes that very same option map to
`notifyCheckedInVersion`, which copies it into the properties of `documentCheckedIn`, next to the
platform's `checkedInVersionRef` (no constant exists for it; the platform's own listeners use the
string). `BlobModificationListener#scheduleDiffs` reads both: the pair is captured, not inferred,
and nothing is assumed about how version labels are numbered — renumbering versions out of band
changes nothing.

Two core call sites (the snapshot branch of `saveDocument` and the publishing path) call
`notifyCheckedInVersion` with `null` options, so the captured reference can be missing.
`BlobModificationListener#previousVersionRefFallback` then queries the two most recent versions of
the series, ordered by `ecm:versionCreated` — a fact about when a version was created, not a
convention about its label — and keeps the one that is not the new version. Never order by
`uid:major_version`. Coverage: `TestBlobDiffVersionPairing`.

All three reads (the capture, the fallback query, the loading of the pair) are privileged: a version
the caller cannot read must not look like "no previous version", which would turn a real
modification into an unaudited first version. Nothing leaks — only ids, labels and blobs are used,
and the result lands in the restricted container.

The three disabling switches are the context flag `DISABLE_BLOB_DIFF_LISTENER` (platform convention,
it only reaches the listener through the option map `saveDocument` builds for the version it creates
itself), the thread-scoped `BlobModificationListener#runDisabled` (the only one that covers explicit
check-ins, automatic policies, restores, publishing, importers) and
`EventServiceAdmin#setListenerEnabledFlag("blobModificationListener", false)`. Their exact reach is
in the README; `runDisabled` restores the previous state in a `finally`, because a switch left on a
pooled request thread would silently stop auditing the instance. Coverage:
`TestBlobDiffListenerDisabling`.

## 3. Which blobs are compared

`VersionBlobPairs#of` returns the pairs. Configured xpaths are compared verbatim: a path holding no
blob yields `null` and is skipped downstream. A blob added or removed is never diffed: the feature
audits the replacement of an existing blob.

"No blob" means exactly a `PropertyNotFoundException` — a schema the document does not carry, a list
entry past its end — or a value that is not a blob (`VersionBlobPairs#safeGetBlob`, and
`#expandTemplate` for a list). Any other failure to read a property propagates: answered `null`, a
binary that changed would leave no audit entry at all, which reads as "the binary did not change",
with nothing above `DEBUG` to tell otherwise. Raised from the synchronous listener, the platform's
`EventServiceImpl#fireEvent` logs it at `ERROR` and goes on with the check-in, unless the transaction
is already marked for rollback. Coverage: `TestBlobDiffVersionPairing` (the "no blob, or no answer"
cases).

With `<xpath>*</xpath>`, the xpaths come from the schemas of the **new version**
(`DocumentModel#getSchemas()`, which includes the schemas of facets added to the document;
`DocumentType#getSchemas()` would miss them). They are computed per schema, as templates
(`files:files/*/file`), and cached by schema name — a document type plus its instance facets reduce
to a set of schemas — in a static map that `BlobDiffComponent#stop` clears, since a hot reload can
redefine schemas. Walking every property of both versions instead would call `getValue()` on each,
loading every complex and list property inside the user transaction. `MAX_SCHEMA_DEPTH` guards
against a complex type that refers to itself.

Blobs inside a list are paired by content, never by index alone (`VersionBlobPairs#pairListEntries`):
once an entry is removed, inserted or moved, the same index holds another file on each side, and
comparing them would report a change between two unrelated files — and schedule a work for each
shifted index. Each list template is expanded on both versions: a digest present on both sides is
unchanged wherever it sits; a new blob is compared with the one at its xpath only when the list kept
its shape and that one is gone; a new blob in a list that also lost an entry is
`skippedListChanged`, audited without a diff; a new blob with nothing gone is an addition. A blob
with no digest never matches the other side, which degrades to index pairing when the shape is kept.
A configured indexed xpath (`files:files/0/file`) is an explicit choice and stays compared by index.

## 4. Eligibility and reporting

### Eligibility

`DiffEligibility` says whether a pair can be diffed, and if not why: `NOT_APPLICABLE` (feature off,
type or xpath not watched: fully silent), `TOO_LARGE`, `UNSUPPORTED_TYPE`, `FORMAT_CHANGED`,
`ELIGIBLE`. The distinction matters beyond yes or no: a binary that really changed but cannot be
diffed must still leave an entry, otherwise the absence of an entry could not be told from the
absence of a change.

`BlobDiffService#getEligibility(docType, xpath, oldBlob, newBlob)` combines the verdict of each
side — precedence `NOT_APPLICABLE` > `TOO_LARGE` > `UNSUPPORTED_TYPE` — then returns
`FORMAT_CHANGED` when both are eligible but resolve to different extractor contributions. It is the
only eligibility call of `BlobDiffTrigger` and of `BlobDiffWork#work`: do not inline
`DiffEligibility.combine` into either, they would drift apart.

### The order of the checks at check-in

`BlobDiffTrigger#scheduleIfNeeded`: a missing side → nothing; eligibility; `NOT_APPLICABLE` →
nothing; `sameContent` → nothing; any other skip → reported; blobs not both `ManagedBlob` →
`skippedNotManaged`; otherwise the request is built and the work scheduled.

The digest comparison must run before a skip is reported, or every new version of an over-sized
document would be audited as a binary change even when the binary never moved, and identical bytes
re-uploaded under another mime type would be audited as a format change. `sameContent` answers
"different" when a digest is missing: when it cannot tell, it audits. Coverage:
`TestBlobDiffSkipReporting`.

The `ManagedBlob` check comes last because the work re-reads both binaries from their provider,
which needs a provider id and a key; a pair without them is a binary change all the same, reported
rather than dropped.

### One extractor contribution, one comparability class

Two extractors do not cut a document the same way — cells, paragraphs, rendered lines — so diffing
across them reports everything as removed and added, copies both documents into `/change-diff`,
and gives counters that mean nothing. `BlobDiffComponent#getEligibility` therefore compares the
extractor *instances* resolved for both mime types — one per contribution — by identity, on one
snapshot of the list: a concurrent `start` replacing the list must not make the same contribution
look like two.

The contributions *are* the rules. `ConverterTextExtractor` is contributed three times: `converter`
(docx, doc, odt, rtf, all cut into paragraphs, so `.doc` → `.docx` is diffed), `pdf` (one line per
rendered line) and `legacyPresentation` (flat slide text), so that a Word document replaced by its
PDF rendition or by a deck is not diffed. Every text format is in `plainText` on purpose:
`PlainTextExtractor` reads raw lines whatever the mime type, so `.txt` → `.json` is a genuine diff.
Splitting text formats by mime type was rejected: text mime types are unreliable (aliases, a `.txt`
holding JSON renamed `.json`), and a genuine edit would lose its diff. Coverage:
`TestBlobDiffService#testThePairEligibilityComparesTheExtractorContributions`.

### A text file replaced by another one

Within `plainText`, a `.json` replaced by an unrelated `.xml` is still a diffable pair whose diff
lists both files whole. `BlobDiffComponent#isReplaced` flags the result `replaced()` when the mime
types differ (`ExtractorDescriptor#normalizeMimeType`: no case, no parameters), both sides hold at
least `minSimilarityLines` units, and `DiffResult#similarity()` is under `minSimilarity`. Both are
properties of the contribution reading the pair — only one contribution can reach this point, two
being `FORMAT_CHANGED` — parsed once by `BlobDiffComponent#resolve` into `ResolvedExtractor`, read
by the service and ignored by the extractor. Only `plainText` sets them; `0` disables the rule. An
invalid value is logged and disables the rule, never the extractor: a failure in `resolve` would make
every text format unsupported.

- **Characters, not lines.** Two unrelated JSON files share their `{` and `},` lines, often half of
  them; in characters those weigh next to nothing.
- **The smaller side.** Over the larger side, a big append or a mass deletion would look like a
  replacement; over the smaller one it keeps everything and scores 100 %.
- **The mime-change condition is the product decision**, not an optimisation: a rewrite in the same
  format is an author's edit, and is always diffed. Do not drop it to "catch more noise".
- **Decided at work time.** Deciding at check-in would mean extracting in the user transaction, so
  the check-in entry is the nominal one, with its `diffCorrelationId`.
- **The service keeps the body.** `BlobDiffService#diff` returns the whole `unified` and only flags
  the result; `BlobDiffStore#createDiffDocument` stores a body for `ok` only. Integrators recompute a
  replaced pair with `diff` on the two version blobs. `BlobDiff.Retry` refuses the status: it is no
  incident.

The score is stored on every measured diff, so the threshold can be tuned on real data. Coverage:
`TestTextDifferSimilarity`, `TestBlobDiffService`, `TestBlobDiffSkipReporting`.

### Two reporting channels

A skip decided at check-in is the `skipReason` of the audit entry, and no `BlobDiff` exists; a status
decided by the work is a `bdiff:status`, and the entry is the nominal one. One channel — a `BlobDiff`
for every skip — was rejected: it would put a work and a document on every change of a format no
extractor reads (images, videos, archives in a watched xpath), and `skippedNotManaged` and
`skippedListChanged` are decided at check-in anyway.

The work re-checks the eligibility of the rehydrated pair before extracting, for a retry or a queued
work that outlives a configuration change (a lower `maxBlobSize`, a disabled extractor, a mime type
moved to another contribution): that is how `skippedTooLarge`, `skippedUnsupportedType` and
`skippedFormatChanged` become reachable as a `bdiff:status`. Such a pair replaces its `error` diff
with a skipped one, which is then no longer retryable: the audit entry carries a correlation id, so a
`BlobDiff` has to exist either way. `NOT_APPLICABLE` at work time is no skip — it says nothing about
the binary — and the diff goes on. This re-check is **not** a security boundary: the lengths it
compares come from the `BlobDiff` (§7).

### Failures

`BlobDiffComponent#extract` returns `null` when no extractor is registered for the mime type, and
for nothing else; a failing extractor raises a `NuxeoException`, which the work files as `error`.
Reported as `null`, a genuine failure would become `skippedUnsupportedType` — a status
`BlobDiff.Retry` refuses — so a permanent, mislabelled hole in the audit trail
(`TestBlobDiffManagement#testAnExtractionFailureIsRetryable`).

The image inventory is the deliberate exception: a failure is logged at `WARN` and the text result
is kept. A failing inventory costs one section of the report; a failing text extraction leaves
nothing to record. Do not harmonise the two.

`BlobDiffComponent#stop` keeps the extractor lists: both WorkManagers drain their works for a while
before the components stop, and a work still running past that would otherwise find no extractor and
file `skippedUnsupportedType`. For the same reason a restart may replace the lists while works read
them: they are `volatile` fields holding lists built whole and never modified (`List.copyOf`), so a
work sees either list complete, and its extractors initialised
(`TestBlobDiffService#testTheExtractorListsArePublishedSafely`).

## 5. Diff engine

`TextDiffer` is the single engine, format-agnostic. An extractor produces a `DiffableContent`, a
list of `ContentLine(key, value)`, either keyed or positional:

- **keyed** (spreadsheet cells, image inventories): the two sides are compared as maps, O(n+m) in
  time and memory, exact `added` / `removed` / `changed`, insensitive to the extraction order;
- **positional** (lines, paragraphs, slides): the sequences are aligned (LCS), and a removal
  immediately followed by an addition is reported as a modification (`TextDiffer#coalesce`, pair by
  pair).

The alignment uses **Hirschberg's algorithm** rather than the textbook table: the same optimal
alignment in O(min(n,m)) memory, where an `int[n+1][m+1]` table costs about `4·n·m` bytes per
concurrent work. Hirschberg fixes memory, not time, so `TextDiffer#positionalEdits` first strips the
common prefix and suffix: only the part that differs reaches the alignment, and a shared first line
always belongs to some optimal alignment, so optimality is kept. The alignment then runs over
interned line ids (`Alignment#intern`), comparing integers instead of paragraphs that often share a
long prefix. The measured figures live in one place, the class Javadoc of `TextDiffer`: in short, a
few edited paragraphs cost milliseconds whatever the size of the document, and only a document
rewritten from end to end stays quadratic, which `maxLines` bounds. `TestTextDiffer` and
`TestTextDifferScaling` assert the optimality of the alignment: that is the contract any
replacement must satisfy.

### Truncation

Each side is cut at `maxLines` on its own, so the two windows do not end at the same place in the
document. Past the last unit both windows share, a unit missing from a cut side may simply lie beyond
its cut: it is unknown, neither reported nor counted. Keyed: `TextDiffer#pastLastShared` drops, on
each side, the keys after the last one both windows hold when the other side is cut. Positional:
`Alignment#tailStart` marks the first edit after the last aligned pair, and the removals past it are
dropped when the new side is cut, the additions when the old side is. Before that point both windows
cover the same part of the document and every reported change is a fact. Both rules rely on the
extraction following document order on both versions, which every built-in extractor does.

### Bounds

`maxDiffEntries` bounds how many entries are written, never how long they are; an Excel cell holds up
to 32 767 characters and a Word paragraph is unbounded. `TextDiffer.Body` owns the budget of both
strategies, which only count and offer each entry:

- the budget (`maxEntries`, `maxChars`) is checked on the **rendered** entry: checking the body
  length beforehand lets the result overshoot by one whole entry;
- the cut is monotone: once an entry is refused, every later one is, so the body is a contiguous
  prefix of the full diff;
- entries past the budget are not even rendered (they are offered as a `Supplier`);
- the counters are incremented before the budget is consulted, so they stay exact past it.

`TextDiffer#elide` bounds keys as well as values, a keyed extractor being free to use long keys.
`TextDiffer#merge` caps the concatenation of the text and image results at `maxChars`, cutting at
the last complete line: each side is bounded on its own, so their sum could reach twice the cap.
Coverage: `TestTextDifferBounding`.

### One entry per line

The body is a line-oriented record: the viewer classifies each line by its `+ `, `- `, `~ ` or `# `
prefix. An extracted value may hold line breaks - an Excel cell typed with Alt+Enter, a PowerPoint
soft line break, a table cell joined with `\n` - and, written raw, one entry would spread over
several lines: the next ones unprefixed, or, typed on purpose, prefixed like entries that never
happened. `TextDiffer#renderValue` writes every break `\R` matches (`\r\n` counting as one) as a
literal `\n`, in keys and values, after `elide`. It does so at rendering only: the extracted values,
their comparison, the counters and the similarity are those of the document. Coverage:
`TestTextDiffer` (the "line breaks inside a value" cases).

### Values bounded at extraction

`maxValueLength` is handed to every extractor (`BlobContentExtractor.MAX_VALUE_LENGTH`), and the
built-in ones build each value through `ContentLine.of(key, value, maxValueLength)`. Past the limit,
`ContentLine#compact` keeps the beginning, the full length and the first 16 hex characters of the
SHA-256 of the whole value, within `maxValueLength`: two values still compare equal exactly when they
are, `elide` leaves the result alone, and what each side holds is bounded by
`maxLines × maxValueLength`. A new extractor must do the same — and that includes what it holds
*before* a value is final, like the XLSX shared strings and the lines of a slide waiting for its
title (§6). Coverage: `TestExtractedValueBounding`.

That bound applies to finished values, and a SAX handler only knows how long a text is once its
element ends. POI's `ZipSecureFile` lets a part inflate up to 100 times its compressed size, so one
text node of a file `maxBlobSize` accepts can reach a billion characters, which a `StringBuilder`
would hold whole before any budget is consulted. Every OOXML handler therefore reads its character
data into a `BoundedText`: the XLSX shared strings, cell values, inline strings and formulas, the
PPTX paragraphs, table cells and rows (handed cell by cell, never as a list). Up to
`BoundedText.HELD_LENGTH` characters — 32,767, the most an Excel cell holds — the text is held whole,
so whatever Excel or PowerPoint writes is read, decoded, trimmed and parsed exactly as before. Past
that, it keeps its beginning, its length and a running SHA-256 of its UTF-8 encoding, and gives
exactly what `ContentLine#compact` makes of the whole: such a text is compared on its raw content,
before decoding or trimming, and a formula that long is compared as text. Coverage:
`TestExtractedValueBounding` (the "piece by piece" cases), `TestSpreadsheetStreaming` and
`TestPresentationStreaming` (the inflated cases).

### Similarity

`TextDiffer` measures, for free, the characters of the units kept unchanged: positional, every old
line minus the raw removals, computed *before* the truncation drop above, since every old line is
either kept or removed; keyed, the entries with equal key and value. `DiffResult#similarity()` is
that count over the characters of the smaller side, `null` when a side has none. §4 says why
characters and why the smaller side.

## 6. Extractors

### The contract

`BlobContentExtractor#init(Map)` receives the properties of its contribution, plus
`maxValueLength` unless the contribution sets it; `extract(blob, maxLines)` declares
`throws IOException` and nothing wider. A `throws Exception` would force `catch (Exception)` on the
call sites, which also catches `InterruptedException`: the blob re-read runs on a WorkManager thread
that *is* interrupted at shutdown, and would file a bogus `error` diff instead of letting the cancel
through. An extractor reporting a non-I/O failure throws a `NuxeoException`; a malformed file is an
I/O failure (the built-in OOXML readers turn a malformed index into the same `IOException` as a
malformed part). `BlobDiffComponent#extract` hands the caller a `NuxeoException` whatever the
extractor raised, the one `BlobDiffService#diff` declares: POI and PDFBox answer some malformed files
with unchecked exceptions of their own (`TestBlobDiffService#testAnUncheckedExtractionFailureReachesTheCallerAsANuxeoException`).
`BlobDiffComponent#resolve` catches `ReflectiveOperationException | RuntimeException` around the
instantiation and `init`: a typo in a contributed property costs that extractor, not the start of the
component. The lowest `order` whose mime types accept the blob wins; `*` and `type/*` are accepted,
and mime types are compared normalised.

### One local file per binary

`BlobDiffComponent#diff` materialises both sides once with `MaterializedBlob`, and every extractor
needing random access (POI, PDFBox) reads the local file through `MaterializedBlob#of`, a
pass-through for a blob already backed by a file. Read from a stream, POI and PDFBox buffer the whole
package in heap; and on an S3-backed provider, each extraction pass would be a download — four for
two files as soon as the image inventory is on.

`MaterializedBlob` makes its own temporary copy with `Framework#createTempFile` rather than
`Blob#getCloseableFile`, which tracks its file with `Framework#trackFile`, a call that needs a running
runtime (§9): the pure-JUnit extractor tests could not materialise. A pass-through never deletes
anything — a file owned by the blob store must never be deleted — and the copy it owns is deleted on
`close`. A failed copy raises a `NuxeoException`, so the diff is filed `error` and can be retried;
there is no in-heap fallback, which would bring back a second, unbounded read path into every reader.
Coverage: `TestMaterializedBlob`.

### XLSX, streamed

POI's usermodel parses every sheet into an XMLBeans tree when it opens a workbook (measured:
410 MB allocated to extract the first 100 of 300,000 numeric cells), so `maxLines` would bound what
is kept, never what is built. `SpreadsheetExtractor` streams OOXML workbooks: `XSSFReader`, the
styles table, and a SAX `SheetHandler` per sheet that stops at `maxLines`.

POI's own `XSSFSheetXMLHandler` is not usable: asked for formulas, it cannot render shared ones
(Excel writes one for every fill-down), and comparing formulas is the default (`useFormulas`).
`SharedFormulas` rebuilds them as `XSSFCell#getCellFormula` does — the master formula parsed
against a workbook holding only the sheet and the defined names, built on first use — then shifts
the references in place, which also avoids POI's `SharedFormula` turning `Other!A1` into `A1`. A
formula that cannot be rebuilt (external link, table reference) falls back to its cached value.
With `useFormulas=false`, cached values are compared on both formats (`DataFormatter` would render
the formula text of a cell without an evaluator). The values must stay identical to what the
usermodel path produces — kept for binary `.xls`, which has no XML tree to blow up and is bounded by
`maxBlobSize` — and `TestSpreadsheetStreaming` compares the two on a hand-written sheet.

The shared strings are read by `SharedStringTable`, not by POI's `ReadOnlySharedStringsTable`, which
holds the whole part before the first cell: each string is kept decoded, trimmed and compacted,
charged its length plus 32, within `maxLines × maxValueLength`. A cell whose string lies past that
budget ends the extraction, flagged truncated, the way `maxLines` does — never a placeholder value,
which would make two different strings compare equal.

Every SAX parser comes from `XMLHelper.newXMLReader()` (no DTD, no external entity), through
`OoxmlPackages#parse`, and a parse is ended by throwing `OoxmlPackages.Stop`. Packages are opened
read only from the local file by `OoxmlPackages#open` and released with `revert()`; `close()` on a
read-only package logs a warning before doing the same.

### PPTX, streamed the way POI reads it

`XMLSlideShow` parses every slide when it opens a deck (measured: 319 MB allocated to extract 100
lines of a 200,000-paragraph slide). `SlideHandler` walks the shape tree of a slide with SAX the way
`XSLFSheet#buildShapes` builds it, quirks included, on purpose: of an `mc:AlternateContent` only the
first element of the first `mc:Choice` is read, in place of the block, never the `mc:Fallback`; only
top-level shapes count for the title and for the notes body. The mimicry is what makes the
reference test possible: `TestPresentationStreaming` keeps the former usermodel code and compares
the two on a hand-written slide, and it has already caught a misreading of the alternate content.

Each slide is parsed once. The `## Slide: <title>` marker comes first and carries the title, so the
lines of a slide are held, compacted, at most the room left under `maxLines`, until the title is
known. The title is never held whole either: `TitleText` keeps its first `maxValueLength`
characters, its length and a running SHA-256, plus a copy of the digest taken before trailing
characters up to U+0020, so that its marker is exactly what `ContentLine#compact` makes of the
trimmed whole — `ContentLine#compacted` is the one format both use. Once a slide overflows,
`SlideText` reads the rest of it only as the title needs, and stops at the title. Slide numbers are
never part of the text: a slide inserted would otherwise shift, and "modify", every following one.

### Converted formats

`ConverterTextExtractor` hands the blob to the `ConversionService` (`any2text`) and reads its
output with a `BufferedReader`, stopping at `maxLines`. `readLine` breaks only on CR and LF, so each
line is split again on `OTHER_LINE_BREAKS` (VT, FF, NEL, U+2028, U+2029): the segmentation stays that
of a `split("\\R")` without ever holding the whole output — dropping that second split would shift the
diff of every converted PDF. The conversion itself still processes the whole document, and a
converter building its output in memory still holds it. Every decode goes through `BlobCharsets`,
never `Blob#getString()`: a blank or unknown declared encoding must degrade to a default, not turn a
readable diff into an `error`.

### Image inventories

One class per format — `WordImageInventory`, `SpreadsheetImageInventory`,
`PresentationImageInventory`, `PdfImageInventory` — extends `AbstractImageInventory`, which owns the
budgets, the digests and the `Inventory` accumulator. Each safeguard answers a blow-up a few
kilobytes of input can cause:

- **Per image**: `maxImageBytes()`, 32 MB, enforced by `digestStream` on all four formats, which
  reads at most the cap plus one byte; a larger image is dropped and the inventory flagged truncated.
- **Per file**: one `ImageBudget` per extraction. `digestOnce` memoises digests — by part name
  (OOXML), by picture index (`.xls`), by stream identity (PDF) — so an image placed on 300 pages is
  read once, and `meter` charges every byte read against `maxTotalImageBytes()`, eight times the
  per-image cap (256 MB), after which the inventory stops, flagged truncated.
- **OOXML pictures** are resolved from their relationship and streamed from the part
  (`digestOoxmlPicture`), never through `PictureData#getData()`, which inflates the whole image (POI
  caps it at 100 MB). Binary `.xls` has no part to stream, and `HSSFPictureData#getData()` returns a
  fresh copy on every call, so it is only called inside the `digestOnce` supplier, memoised on the
  picture index.
- **Word** is read as a plain zip stream, each `word/media/` entry digested as it is inflated, behind
  an inflation-ratio pre-check (ratio 100, ignored under 100 KB). The pre-check is opportunistic — an
  entry whose sizes live in a trailing data descriptor reports `-1` — and the streaming digest is the
  guard that always applies.
- **PDF images are never decoded.** `PdfImageInventory#digestPdfImage` hashes `Filter`,
  `DecodeParms` (sorted keys, indirect references followed to a bounded depth), `Width`, `Height`,
  `BitsPerComponent` and the raw stream. PDFBox decodes a whole stream into heap before returning its
  first byte, and `getWidth()` decodes a JPX image: a 1×1 image over a Flate bomb would otherwise be
  an `OutOfMemoryError`. The visible cost: a producer re-compressing an unchanged image reports it as
  replaced.
- **PDF form XObjects are walked once per document** (`PdfWalk`): their placements are recorded and
  replayed on every later reference, a form already on the current path is skipped, and nesting stops
  at `MAX_FORM_DEPTH` (16). `maxLines` counts every placement, dropped ones included, so replays stay
  bounded. A form naming itself would otherwise recurse until `StackOverflowError`, an `Error` that
  nothing catches.
- **A dropped image never renumbers.** The XLSX ordinal, the `#2` occurrence suffix of XLSX and PPTX
  keys and the PDF positional ordinal advance before the drop check: renumbering one side would make
  every following image look changed.

Coverage: `TestImageInventoryExtractor`, `TestBlobDiffImageExtraction`.

## 7. Security model

A `BlobDiff` holds business content extracted from its source, readable by people who may not be
allowed to read that source. Everything below keeps the copy contained.

### The container

`/change-diff` is created at every repository initialisation (`BlobDiffRepositoryInit`, installed by
`BlobDiffComponent#start`), even when the feature is off, so that its ACL exists before the first
diff. `BlobDiffStore#repairSecurity` resets its local ACL — the auditors group granted `Read`,
`Remove` and `RemoveChildren`, then inheritance blocked — whenever it differs, including after the
auditors group was reconfigured, and logs a `WARN` when it repairs a tampered one. Dated folders have
no local ACL and inherit it. Diffs are written with a system session: the users who modify a file
have no permission on the container. `BlobDiff` documents are never trashed, only permanently
deleted.

`NotFulltextIndexable` keeps the platform from extracting the binary text of `bdiff:diff` into the
full-text index — and from there into the search engine. It is a repository-side switch: the string
properties of the schema (filenames, summary, version labels) still reach the search engine's
full-text field, which is acceptable because they are metadata, not extracted content.

### Nothing leaves the container, and only the container is trusted

`AbstractSession#createDocument` does not enforce allowed subtypes — that is `TypeManager`, a UI
concern — so any user able to add children somewhere could create a `BlobDiff` with fabricated
content and have it listed next to the genuine ones. And the auditors group holds `Remove` on the
container, which is all `CoreSession#move` checks on the source side. Two layers, keep both:

- `BlobDiffLocationListener` (synchronous, `aboutToCreate`, `aboutToMove`, `aboutToCopy`) refuses,
  for every principal, system included, a `BlobDiff` destined anywhere else, and any move or copy of
  something under the container to outside it (`DocumentSecurityException`, bubbled as a 403);
- every reader of `BlobDiff` documents filters on `ecm:path STARTSWITH '/change-diff'` and excludes
  proxies — both page providers, `BlobDiffWork#existingDiffId`, and the query of `BlobDiff.Purge`,
  which runs under the caller, who may hold `Remove` where a forged diff sits. The listener cannot
  reach a document forged before it existed; the filters can. A new reader needs the same filter.

`createProxy` fires no "about to" event, so a proxy published elsewhere escapes the listener: the
`ecm:isProxy = 0` filters keep it out of the audit lists, and publishing needs Read on the diff, which
only auditors hold. Coverage: `TestBlobDiffLocationAndSecurity`.

### `BlobDiff.Retry` trusts nothing but the stored path

The work re-reads a binary with `provider.readBlob(info)` in a system session: a lookup in a store
keyed by an opaque string, subject to **no document ACL**, whose extracted content then lands in a
`BlobDiff` the caller can read. A retry must therefore only replay keys the plugin wrote itself.
`BlobDiffRetryOp#run` keeps this order:

1. `BlobDiffAccess.checkAuditor`;
2. `reloadStored`: only the **id** of the input is used, and the document is reloaded with
   `session.getDocument` under the caller's session, so Read is still enforced; a model never stored
   (`SimpleDocumentModel` included) is refused with 400. Over REST, a JSON document entity is loaded
   by uid, then `DocumentModelJsonReader#readEntity` applies the properties of the request to it in
   memory, under `doPrivileged`: Read on any diff would be enough to hand over a genuine path with the
   caller's status and keys;
3. `checkProvenance`: the path must be under `/change-diff/`, before any other property is read. The
   document type and `bdiff:status` are no guard on their own: on a forged document, both are the
   attacker's;
4. type `BlobDiff`, status `error`;
5. `DiffRequest#readFrom`, which trusts every property it reads, so only now;
6. both sides present, no replay pending, then schedule.

The eligibility re-check of the work is no substitute: the length it compares comes from
`bdiff:oldLength` / `bdiff:newLength`, which `readFrom` read — forgeable. Coverage:
`TestBlobDiffManagement` (the `testRetry*` cases).

### Operations, purge and enricher

Every operation calls `BlobDiffAccess.checkAuditor`: an administrator, a member of `administrators`
or of the auditors group, the latter read from the configuration property
`org.nuxeo.web.ui.blobaudit.auditorsGroup` only — its prefix makes Web UI expose it as
`Nuxeo.UI.config.blobaudit.auditorsGroup`, so server and UI cannot disagree. The check is in
addition to the ACL, for what does more than the ACL enforces (scheduling a work, a bulk purge).

The default group, `administrators`, lives in the code (`BlobAuditConstants.DEFAULT_AUDITORS_GROUP`,
and the same literal in `nuxeo-blobdiff-common.html`), never in a contribution of the bundle.
`ConfigurationService` keeps the last contribution registered, and the components of
`nxserver/config` are deployed before the bundles: an integrator's component requiring only
`BlobDiffComponent`, as the README shows, was registered before a default the bundle contributed in
a component of its own, and lost to it. The configured group was then locked out of the diffs, of
the operations and of Web UI, with nothing logged. With no default contributed, there is nothing to
order against. Coverage: `TestBlobDiffLocationAndSecurity#testTheBundleContributesNoAuditorsGroup`.

The purge runs on the Bulk Action Framework, as the `blobDiffPurge` action:

- **not `httpEnabled`** (the default): it stays out of `Bulk.RunAction` and of
  `/search/bulk/{action}` for anyone but an administrator. `BlobDiff.Purge` is the only entry point:
  it checks the auditor and builds the query itself. `httpEnabled="true"` would publish a
  delete-by-NXQL API to every authenticated user; routing the UI through `Bulk.RunAction` with it off
  would reserve the purge to administrators, taking it away from the auditors it is built for;
- the computation removes nothing but `BlobDiff` documents, whatever the query of the command, so not
  even an administrator submitting the action by hand can turn it into a generic delete; it removes
  them with `session.removeDocument` (events, ACLs, blob dereferencing);
- `exclusive`: one purge per repository, under a lock with a TTL, so a command lost mid-flight does
  not freeze the feature;
- `BulkService#submit`, not `submitTransactional`: the latter defers the submission — and with it the
  exclusivity check — to `beforeCompletion`, so a concurrent purge would surface as a commit failure
  instead of a 409, and the returned id would not be queryable until commit. The operation writes
  nothing to the repository, so there is nothing to undo;
- `BlobDiff.PurgeStatus` and `BlobDiff.PurgeAbort` go through `BlobDiffPurgeAction#purgeStatus`:
  `BulkService#getStatus` and `#abort` check neither the action nor the owner of a command id, so any
  other command looks `UNKNOWN` — no oracle — and is never aborted. Any auditor may stop any purge, by
  design;
- `BlobDiffPurgeComputation#compute` checks `isAbortedCommand` first: the framework only skips a
  bucket of a command it no longer knows, and it caches commands, so without it a stopped purge would
  delete everything already scrolled. An abort takes effect at the next batch of 50.

Coverage: `TestBlobDiffPurgeAction`, which pins `httpEnabled` through `BulkAdminService#isHttpEnabled`
and holds the first purge with `PurgeGateListener` while the second is refused.

The `blobDiffSource` enricher computes the existence and the trash state of the source with a
privileged session — an auditor may know that a source was deleted — and writes its title, path and
type only when the caller can read it: the one place where privileged data is narrowed before it
reaches a caller (`TestBlobDiffManagement#testEnricherHidesASourceTheAuditorCannotRead`). It costs
two repository operations per diff, one privileged load and the caller's `Read` check, since the
search results enrich a whole page of diffs, each on a source of its own: the title, path and type are
read in that load, and only written when the check passes. No `documentResolver` on
`bdiff:sourceId`: its validation would forbid recording an `error` diff for a deleted source.

### Logs, names, dates and concurrency

- Logs name a side by its filename and provider, never by its key nor its digest: the default key
  strategy makes the key the digest, and either one identifies a binary to whoever reads the logs.
- Nor does a log hold content of a document: the image inventory names a dropped picture by its
  media part (`/xl/media/image3.png`), or for `.xls` by the number of its sheet
  (`excel:image:#2:B4:F18`), never by its inventory key, which carries the sheet name. Coverage:
  `TestBlobDiffImageExtraction#testADroppedSpreadsheetPictureIsLoggedWithoutItsSheetName`.
- A diff is named `<sourceId>-<eventTime>-<correlationId>`: one version touching two xpaths makes two
  diffs with the same source and time.
- Dated paths and the purge boundary are formatted in UTC and `Locale.ROOT`: in the JVM zone, two
  nodes of one cluster would file the same instant in two folders and a "before" date would select two
  different sets; under a locale with another numbering system, non-ASCII digits would land in a
  repository path and in an NXQL `DATE` literal.
- Dated folders are created in the caller's session and transaction, under an in-JVM lock
  (`BlobDiffStore#getOrCreateContainer`): created in another transaction, they would be invisible to
  the caller's session. A residual cross-node race can only produce a renamed dated sibling
  (`14.1727…`), still under the restricted root. The root is created at repository initialisation;
  if a creation race renames it, the duplicate is removed and the creation retried at most three
  times — bounded, since a repository renaming every creation would otherwise retry for ever inside
  the work.

## 8. Search and Web UI

### Where each query lands

| Site | Mechanism | Destination |
|---|---|---|
| `BlobDiffWork#existingDiffId` | `session.query` | the repository, always |
| `BlobModificationListener#previousVersionRefFallback` | `session.query` | the repository, always |
| `BLOB_DIFFS_FOR_DOCUMENT` | `coreQueryPageProvider` | the repository, always |
| `BLOB_DIFFS_ADMIN` | `SearchServicePageProvider` → `SearchService` | **the configured search client** |

`BLOB_DIFFS_FOR_DOCUMENT` is the synchronous, read-your-writes provider, public API for integrators;
the tests depend on it, so it must not move to `SearchService`. `BLOB_DIFFS_ADMIN` backs every Web UI
screen. The default search client of LTS 2025 is `repository` (`nuxeo.search.client.default.name`),
which the OpenSearch search-client package flips to `opensearch`. `RepositorySearchClient` declares no
capability, `AGGREGATE` included, and the mismatch is reported as a `SearchLimitation` rather than
raised, which `SearchServicePageProvider` surfaces nowhere: on a bare instance the four facets come
back silently empty. Documented in the README as a prerequisite rather than worked around.
`TestBlobDiffAdminSearch` runs `BLOB_DIFFS_ADMIN` on that client and checks that each filter narrows
the result.

Nothing has to be deployed on the search engine: every schema is indexed, and the default mapping
maps every string to a `keyword` through a dynamic template, so `bdiff:*` is filterable and
aggregatable out of the box. A mapping fragment would only be pushed to an index that has no mapping
yet, so it would need a drop and a full reindex. Do not use the search engine for the idempotency
guard either: indexing is asynchronous, so it would not see the diff a previous run just committed.

`bdiff:correlationId` and `bdiff:sourceId` carry `indexOrder="ascending"`: each is the sole
predicate of a repository query — the idempotency guard, whose nominal outcome is *zero* match, the
case that forces a full scan; and `BLOB_DIFFS_FOR_DOCUMENT`. `indexOrder` is read by
`MongoDBIndexCreator` only, at repository initialisation, idempotently, over existing data: on VCS
the indexes are created by hand, and the search engine ignores it.

### The search document

`BLOB_DIFFS_ADMIN` declares its search document type with `<searchDocumentType>BlobDiffSearch`.
`WhereClauseDescriptor` only declares `@escaper`, `predicate` and `fixedPart`, so a
`whereClause@docType` attribute is dropped by XMap in silence. Without the real element,
`PageProviderHelper` falls back to `SimpleDocumentModel.empty()`; since the predicates carry a
schema, `NXQLQueryBuilder#getRawValue` then reads `null` from it **without throwing**, the
named-parameter fallback never runs, and every filter is dropped quietly. The UI keeps sending
prefixed keys (`bdsearch:sourceId`): a prefix resolves through the schema registry, while a bare key
is matched by iterating the schemas of the search document, where a generic name such as `user` can
collide.

Each aggregate has three names, not to be conflated: `@id`, the key of its buckets in the response,
read by the form as `aggregations.bdiff_<x>_agg`; `@parameter`, the indexed field aggregated on; and
`field/@name`, where the selection is read back from the search document, hence the key the client
sends, `bdsearch:<x>_agg` — an `nxs:stringList`, sent as a JSON array. An `@id` different from the
field name is harmless on the `/execute` path the UI uses, but would break a `/search/pp/{name}/bulk`
submission, which keys selections on `@id`.

A boolean filter sends `true` or nothing, never `false`: `nuxeo-page-provider` only drops `null` and
`undefined` named parameters, and `NXQLQueryBuilder` turns `false` into `= 0`, so an unticked
"truncated only" box would mean "exclude truncated". The form sets `true` or `null`, and replaces a
`false` read back from a saved search by `null`.

### The named search

No Web UI element renders "form on the left, results on the right": the application shell produces
that illusion. The form is a `nuxeo-search-form` contributed to `DRAWER_PAGES`; the results are the
single, hardcoded `<nuxeo-search-page name="search">` of `nuxeo-app`, whose `_updateSearch()`
matches the two with `this.$$("[search-name='" + this.searchName + "']")` in its own shadow root.
Four couplings follow, each a silent failure when broken:

- **`search-name` must be a literal attribute.** `searchName` is not `reflectToAttribute` on
  `nuxeo-search-form`: a binding sets the property, leaves no attribute, and the results stay empty
  for ever, with no error.
- **`name` goes on the inner element, never on the `nuxeo-filter`.** `nuxeo-filter` stamps its
  template into its own parent, as a sibling of itself, so the stamped `nuxeo-search-form` is the
  child of `iron-pages#drawer-pages` that its `attr-for-selected="name"` reads.
- **The layout files are found by name.** `nuxeo-search-form-layout` loads
  `<hrefBase><searchName>/nuxeo-<searchName>-search-form.html`, lowercased (`hrefBase` is only set by
  the development `index.html`, so `/ui/search/` on a server), and `nuxeo-layout` derives the tag
  name from the file name: folder, file names, `dom-module` ids and `search-name` stay in lockstep.
  The results layout must contain a literal `<nuxeo-results>`, which `_grabResults` looks for and
  drives through `fetch()` and `reset()`.
- **Never set a height on the data table.** `nuxeo-results` sizes its views through
  `--nuxeo-results-view-height`.

The drawer icon carries `route="search:blobdiffs"`, which the platform searches do not: their icon
only opens the drawer, leaving the main area on the previous page until a filter changes — one click
too many for an audit entry point. A user who is not an auditor gets a blank pane on
`#!/search/blobdiffs`: the form is filtered out, so nothing is stamped; the server checks are the
guard. Saved searches are kept in full: `only-queue` would hide their dropdown while `nuxeo-app` still
shows "Save search as…".

`Nuxeo.BlobAudit.openSearch(params, replace)` crosses a shadow root on purpose: the form is a sibling
of the results, not an ancestor, so the "show only the diffs of this document" row action and the
"Open in the content changes audit" button of the document tab have no binding to write to.
`nuxeo-app` keeps the form in a property it resolves with the same selector; there is no public API.
The tab replaces the whole `params` (so the earlier filters are dropped and the count matches the
tab); the row action adds to them.

A `nuxeo-data-table` stamped by a `dom-if` must be handed the provider **element**, never its id:
`PageProviderDisplayBehavior` resolves a string id through the `$` of the template instance, which
does not know it, and then fetches nothing, silently. The results layout binds the `nxProvider` that
`nuxeo-search-results-layout` injects.

### The `BlobDiff` view

Web UI requests the `blobDiffSource` enricher with every document it loads:
`org.nuxeo.web.ui.enrichers.document` is a list property, `ConfigurationPropertyDescriptor#merge`
appends a later value to a list, and `nuxeo-app` builds the document enrichers from it. The view
therefore needs no second fetch of the document. The component requires
`org.nuxeo.web.ui.properties.contrib`, and stays pending, harmlessly, where Web UI is not deployed —
in the tests.

The Edit action is hidden by contributing `editDocumentAction` again, same slot and order, with the
same button behind a filter excluding `BlobDiff`: `nuxeo-slots` replaces a content of the same name
registered with the same or a higher priority, and the plugin bundle is appended to the `web-ui`
bundle, so it registers after the platform. Re-check this override when upgrading Web UI. An
`Immutable` facet would not do: the core derives immutability from versions and proxies.
The filter is `!this.hasType(document, 'BlobDiff')`: `FiltersBehavior` and `document` only, like
the platform's expressions. With `org.nuxeo.web.ui.expressions.eval=false` (the Content-Security-
Policy mode), `nuxeo-filter` runs its expression in a sandbox holding `this` (`FiltersBehavior`),
`document` and `user`, nothing else; an expression calling `Nuxeo.BlobAudit` raises there, the
filter stays closed, and since this content replaces the platform's, Edit would vanish on every
document type. The three visibility filters (drawer entry, drawer form, document tab) do call
`Nuxeo.BlobAudit.isAuditor`, which reads the configured auditors group: in that mode they hide the
feature from everyone, a limitation the README states. They are cosmetic, the server enforces the
access.
`nuxeo-blobdiff-metadata-layout.html` is empty on purpose: Web UI loads it, and would get a 404.

`nuxeo-blobdiff-viewer` aborts the request in flight when it is handed another diff and ignores any
response that is not the current one. It aborts it too when it leaves the page (`detached`), and
restarts it if it comes back with the same diff (`attached`): the `url` observer would not fire again. The purge dialog is a small state machine because the purge is
asynchronous: it polls with a doubling delay, and after five failures in a row or an `UNKNOWN` state
it closes saying the purge keeps running on the server; it reports failed and skipped diffs and a
reached query limit; an abort branches on the state it returns (a command that completed meanwhile
goes through the normal completion, folder cleanup included); Esc and a click outside are ignored
while a purge runs, and reopening shows its progress.

### Translations and the audit event

Two sets of translations with different consumers, not copies of one another.
`web/nuxeo.war/ui/i18n/messages*.json` is read by Web UI. `OSGI-INF/l10n/messages_*.properties` is
appended by `deployment-fragment.xml` to `nuxeo.war/WEB-INF/classes/messages*.properties`, the server
bundle against which `SuggestDirectoryEntries` localises vocabulary labels: the audit filter asks for
`localize=true` without `dbl10n`, and `I18NUtils` returns the key when it misses. The label of
`blobContentModified` must therefore live there.

The `eventTypes` row of `blobContentModified` is created at runtime by
`BlobDiffComponent#registerAuditEventType`, guarded by `hasEntry`, in a transaction, privileged, with
any `RuntimeException` logged rather than propagated: a vocabulary row must never break startup. A
`<dataFile>` contribution would *replace* the platform's rows (§9). A runtime with no `eventTypes`
directory is skipped at `DEBUG`: `DirectoryServiceImpl` starts before this component, so a `null`
directory is genuinely absent, not late. Coverage: `TestAuditEventTypeRegistration`.

`deployment-fragment.xml` only ever *merges* into `nuxeo.war`: a file removed from the bundle lingers
in a deployment that keeps its `ui/` folder (a bind mount, typically) until that folder is cleared.

## 9. Platform facts relied on

Verified in the LTS 2025 sources (and POI 5.5.1, PDFBox 3.0.7, as shipped with it). Re-check them when
upgrading.

**Work manager**

- `WorkManagerImpl#schedule` disables `IF_NOT_SCHEDULED` and `IF_NOT_RUNNING_OR_SCHEDULED` (a "disabled
  for now" TODO), and `StreamWorkManager#schedule` ignores the flag: no scheduling option deduplicates
  a work.
- The stream `WorkComputation` skips an idempotent work whose id is among the last 50 its thread
  processed (`IDS_SIZE`): hence the own id of a replay.
- `WorkManager#getWorkState` only knows the states of the stream WorkManager when
  `nuxeo.stream.work.storestate.enabled` is set.
- Both WorkManagers drain their works for a while before the components stop.

**Runtime, repository and events**

- `Framework#trackFile` sends a `FileEvent`, which needs a running runtime; `Framework#createTempFile`
  does not.
- `RepositoryService` runs the repository-initialisation handlers from an `afterStart` listener, once
  every component has started; `DirectoryServiceImpl` starts at order 97, before the default 1000.
- The default `Descriptor#merge` returns the later contribution alone, and XMap cannot tell an empty
  list element from an absent one.
- A `<dataFile>` on a directory extending a template **replaces** its data: `dataFileName` is
  single-valued, `BaseDirectoryDescriptor#merge` overwrites it, and `DirectoryRegistry` recomputes the
  descriptor from a clone of the template for every contribution carrying `extends`.
  `dataLoadingPolicy=skip_duplicate` only protects rows already stored: on empty storage, `eventTypes`
  was measured with 1 row instead of the platform's 51. `template-vocabulary` comes from
  `org.nuxeo.ecm.default.config`.
- `AbstractSession` passes the option map of `aboutToCheckIn` on to `notifyCheckedInVersion`, which
  copies it into the `documentCheckedIn` properties next to `checkedInVersionRef`; two call sites pass
  `null`. `CoreSession#checkIn(ref, option, comment)` builds a fresh, empty map; `restoreToVersion` and
  `publishDocument` pass none; automatic policies (`collaborative-save`, `note-as-wiki`) go through
  `DocumentModel#checkIn`.
- `AbstractSession#createDocument` does not check allowed subtypes, and re-reads the destination name
  after `aboutToCreate`, so a listener may rename a document: its name is not contractual.
  `CoreSession#move` only checks `Remove` on the source side. `createProxy` fires no "about to" event.
- `DocumentModelJsonReader#readEntity` applies the properties of a JSON document entity loaded by uid
  to it in memory, under `doPrivileged`.
- `BlobProvider#readBlob` applies no document ACL, and the default key strategy makes the key the
  digest.
- `LogEntry#getExtendedInfos()` throws `UnsupportedOperationException`: use `getExtendedValue(key)`.

**Bulk Action Framework**

- `BulkService#getStatus` and `#abort` check neither the action nor the owner of a command id; the
  `/bulk/{commandId}` REST endpoint is restricted to administrators and the submitter
  (`BulkActionFrameworkObject#checkStatus`), and `BulkStatusJsonWriter` does not write
  `queryLimitReached`.
- `AbstractBulkComputation` only skips a bucket of a command it no longer knows, and caches commands.
- `BulkService#submitTransactional` defers the submission, exclusivity check included, to
  `beforeCompletion`.

**Search**

- The default search client is `repository` (`nuxeo.search.client.default.name`); the OpenSearch
  search-client package sets its own. `RepositorySearchClient` declares no capability, and a missing
  one is reported as a `SearchLimitation`, not raised.
- `SearchServicePageProvider` lives in `nuxeo-platform-query-api`, not in the search engine bundles.
- `WhereClauseDescriptor` declares `@escaper`, `predicate` and `fixedPart` only;
  `NXQLQueryBuilder#getRawValue` reads `null` from `SimpleDocumentModel.empty()` without throwing.
- `indexOrder` has a single consumer, `MongoDBIndexCreator`. The default index mapping maps every
  string to a `keyword` through a dynamic template, and `OpenSearchComponent` only pushes a mapping to
  an index that has none.

**Web UI**

- `ConfigurationPropertyDescriptor#merge` appends the values of a list property; properties prefixed
  `org.nuxeo.web.ui.` are exposed to Web UI as `Nuxeo.UI.config`.
- `nuxeo-slots` replaces a slot content of the same name registered with the same or a higher
  priority.
- The named-search behaviours of §8: `searchName` not reflected to an attribute, `nuxeo-filter`
  stamping into its parent, the file-name convention of `nuxeo-search-form-layout`, `_grabResults`.

**POI and PDFBox**

- `XSSFWorkbook` and `XMLSlideShow` parse every sheet or slide when they open a document;
  `ReadOnlySharedStringsTable` holds the whole shared-strings part.
- `XSSFSheetXMLHandler` cannot render shared formulas, and `SharedFormula` turns a reference to another
  sheet into a plain one.
- `PictureData#getData()` inflates the whole picture (capped at 100 MB by POI); `HSSFPictureData#getData()`
  returns a new copy on every call. `ZipInputStream` has none of POI's `ZipSecureFile` protections.
- PDFBox decodes a whole stream into heap before `createInputStream()` returns its first byte, and
  `PDImageXObject#getWidth()` decodes a JPX image.

## 10. Decisions and rejected ideas

Settled; reopen only with new facts.

- `bdiff:summary` stays **English**: it is stored once, and composing it in the UI would mix stored
  English sentences with translated ones. A translated summary is built from the counters.
- `imageInventory` is a boolean: levels can be introduced the day a second analysis mode exists.
- The auditors group has one setting, the configuration property, shared by the server and Web UI.
- The diff records the source title at work time, not at check-in.
- No `retrying` status: a diff stays `error` while its replay runs, and the 409 asks the WorkManager.
- A pair no longer eligible at work time replaces its `error` diff with a skipped one, which is then
  no longer retryable; `NOT_APPLICABLE` at work time is no skip.
- `skippedContentReplaced` is not retryable, and no operation recomputes a replaced pair:
  `BlobDiffService#diff` does.
- The scope of `DISABLE_BLOB_DIFF_LISTENER` is documented, not widened: `runDisabled` covers the rest.
- **Myers instead of Hirschberg**: dropped on measurements. Reopen only if a real corpus shows
  documents where trimming finds nothing *and* that go far past 20,000 lines.
- **A single post-commit listener inferring the previous version**: ambiguous on equal timestamps
  where the capture is exact, out of reach of `runDisabled`, and it would move the audit entry out of
  the user transaction, for some 40 lines saved.
- **A diff library**: none ships with the server, and truncation, similarity and bounds are custom
  anyway.
- **Dropping the work-time eligibility re-check**: one line, covering retries and configuration drift.
- **One reporting channel** (a `BlobDiff` for every skip): see §4.
- **The platform `/bulk/{commandId}` endpoints instead of `BlobDiff.PurgeStatus` / `PurgeAbort`**: an
  auditor could no longer follow or stop a colleague's purge, and the dialog would lose
  `queryLimitReached`.
- **Removing empty folders inside the purge computation**: the purge would then delete folders too,
  weakening "it removes nothing but `BlobDiff`". `BlobDiff.CleanEmptyFolders` stays; its fan-out is
  bounded by the calendar.
- **Not mimicking POI in the PPTX walk, dropping its usermodel reference, or an HSSF event-API path
  for `.xls`**: the mimicry is what makes the reference test possible, and `.xls` has no XML tree to
  blow up.
- **Merging the `extractors` and `imageExtractors` points**: a text and an image extractor must both
  apply to the same blob, which one ordered selection per mime type cannot express.
- **Word images through `OoxmlPackages`**: they keep their zip-stream reading and its ratio pre-check.
- **No `<require>` on `ElasticSearchComponent`** in the page providers: it would keep the whole
  component, `BLOB_DIFFS_FOR_DOCUMENT` included, pending on any instance without a search engine, and
  in the tests.
- **`LogEntry#getExtendedInfos()`** throws in LTS 2025: code and tests read `getExtendedValue(key)`.
- **A path lookup instead of the idempotency query**: the name of a diff is not contractual (§9), so
  the query would stay as a fallback, and its index already solves the cost.
- **Streaming the diff body to a file**: bounded by construction, the string costs nothing more.
- **A cap on the works of one check-in**: removals and moves schedule nothing, so a check-in only
  schedules one work per blob that really changed.
- **`only-queue` on the search form**: saved searches are kept in full.
