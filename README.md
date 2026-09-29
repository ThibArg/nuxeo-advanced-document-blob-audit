# nuxeo-advanced-document-blob-audit

> [!IMPORTANT]
> This is **Work in Progress**. For now, using GitHub and this repo as backup.
> 
> **Do not use it as is**, it's not working (yet)
> 
> Once ready it will forked to Nuxeo Presales Github Sandboxes.

Audit **what actually changed inside a file**: Excel spreadsheets cell by cell, PowerPoint decks slide by slide, Word documents and PDFs paragraph by paragraph, and text formats line by line.

A **standalone plugin** with no dependency on `nuxeo-advanced-document-audit`, which handles scalar fields. Both plugins can coexist.

## Principle

| Step | Where | Cost |
|------|------|------|
| Binary change detection | BlobModificationListener, synchronous | Digest comparison |
| blobContentModified audit entry | synchronous | Negligible, **no business data** |
| Extraction + diff | BlobDiffWork, asynchronous | Outside the user transaction |
| Result storage | Dedicated BlobDiff document | Blob stored outside the source document |

The audit log never contains business content. It stores only the xpath, file names, and a `diffCorrelationId` pointing to the BlobDiff document.

## One Engine, Pluggable Extractors

```text
Blob ──► BlobTextExtractor ──► DiffableContent ──► TextDiffer ──► DiffResult
          (per format)          (common model)      (single engine)
```

`DiffableContent` is a list of `ContentLine(key, value)` objects:

- **non-null key** → *identified content*. Compared as maps, providing exact semantic detection of additions, removals, and modifications, independent of extraction order. Spreadsheet mode (`Sheet1!B12`).
- **null key** → *positional content*. Aligned using Hirschberg/LCS, with consecutive delete+add operations merged into a modification. Used for text, Word, and PDF documents.

No external diff library is required.

### Included Extractors

| Extractor | Formats | Mode | Quality |
|-----------|----------|------|---------|
| SpreadsheetExtractor | xlsx, xlsm, xls | keyed, cell-based | Excellent — Sheet1!B12: 100 → 120 |
| PlainTextExtractor | txt, json, xml, csv, yaml, source files | positional, line-based | Excellent |
| PresentationExtractor | pptx, pptm, ppsx, potx | positional, paragraph-based, slide-aware | Good — text boxes, tables, notes |
| ConverterTextExtractor | docx, doc, odt, rtf, **pdf**, ppt (legacy) | positional, paragraph-based | Good (Word), indicative (PDF) |

`ConverterTextExtractor` delegates to the platform `any2text` converter (Apache Tika). Therefore **Word and PDF are supported without any format-specific code**. Supporting a new Tika-recognized format simply requires adding a new `<mimeType>`.

### Adding a Format

```xml
<extension target="org.nuxeo.audit.advanced.blob.BlobDiffComponent" point="extractors">
  <extractor name="myFormat" order="15" class="com.acme.MyExtractor">
    <mimeTypes>
      <mimeType>application/vnd.acme</mimeType>
    </mimeTypes>
  </extractor>
</extension>
```

The extractor with the lowest `order` wins. A specialized extractor overrides a generic one.

## Algorithm: Hirschberg

Positional alignment relies on **Hirschberg's algorithm** instead of the traditional LCS table.

A classic `int[n+1][m+1]` matrix costs approximately `4 × n × m` bytes, or about **92 MB** for 4,900 lines, **per concurrent worker**.

Hirschberg produces the **same optimal alignment** while requiring only O(min(n,m)) memory.

Measured results (JDK 17, single modification):

| Lines | Time | Allocated Memory |
|--------|--------|--------|
| 1,000 | 13 ms | Negligible |
| 4,900 | ~400 ms | **1 MB** |
| 10,000 | ~1.1 s | Negligible |
| 20,000 | ~4.6 s *(extrapolated)* | Negligible |

**Execution time remains quadratic**. Only memory consumption becomes linear.

This is why `maxLines` still matters: the default limit of 10,000 lines caps a diff operation at roughly 1.1 seconds.

## Data Model

Document type: `BlobDiff`

Facets: `HiddenInNavigation`, `NotCollectionMember`

| Field | Purpose |
|--------|--------|
| bdiff:sourceId, bdiff:sourceRepository | Source document |
| bdiff:xpath | Which blob changed |
| bdiff:correlationId | Link to the audit entry |
| bdiff:user, bdiff:date | Who and when |
| bdiff:oldDigest, bdiff:newDigest | Fast path and proof |
| bdiff:summary, added/removed/changed, truncated | Short, indexable summary |
| bdiff:status | ok, skippedTooLarge, skippedUnsupportedType, error |
| bdiff:diff | **Blob containing the full diff** |
| bdiff:oldBlobProvider, oldBlobKey, oldMimeType, oldLength, newBlobProvider, newBlobKey, newLength | Storage identity of both binaries (since 1.2), used to **retry** a diff in error |

The diff is stored as a blob, not a string: outside SQL/Mongo records and outside full-text indexing.

### Container and Security

`/change-diff/YYYY/MM/DD/`, partitioned by date (server time zone). Diff documents are named `<sourceId>-<eventTime>-<correlationId>`, so a single version containing changes on several blob xpaths yields distinct documents.

- **Created at startup, always.** `BlobDiffRepositoryInit` creates `/change-diff` at every repository initialisation, even when the feature is disabled, so the restricted ACL exists before the first diff is written.
- **Self-healing ACL.** At each startup the local ACL of `/change-diff` is compared with the expected one and reset (with a WARN) if it differs: the auditors group gets `Read`, `Remove` and `RemoveChildren`, then inheritance is blocked. Dated sub-folders have no local ACL and inherit it.
- **Auditors group** is configurable, default `administrators`. Recommended: the configuration property `org.nuxeo.web.ui.blobaudit.auditorsGroup`, which is also exposed to Web UI (`Nuxeo.UI.config.blobaudit.auditorsGroup`) so the server and the UI share one setting. `<auditorsGroup>` in the `config` extension point still wins when set. Members of the `administrators` group always have access.
- **Concurrency.** Dated folders are created in the caller's session under an in-JVM lock. A residual cross-node race can only produce a renamed dated sibling (e.g. `14.1727…`), which still lives under the restricted root and inherits its ACL.
- **Deleted source.** If the source document no longer exists when `BlobDiffWork` runs, a `BlobDiff` with status `error` and summary `Source document no longer exists` is recorded, so the audit entry is never orphaned.

Writes are performed with a **system session**: users modifying the file have no permission on this container.

### Page Providers

| Name | Backend | Use |
|---|---|---|
| `BLOB_DIFFS_ADMIN` | **Elasticsearch** | Web UI page and document tab: filters (`bdsearch:*` named parameters of the `BlobDiffSearch` search document) and aggregates on status, format, field and user |
| `BLOB_DIFFS_FOR_DOCUMENT` | Core (NXQL) | Diffs of one document, without Elasticsearch |
| `BLOB_DIFFS_OLDER_THAN` | Core (NXQL) | Retention scripts |

`BLOB_DIFFS_ADMIN` lives in its own component requiring `org.nuxeo.elasticsearch.ElasticSearchComponent`: without Elasticsearch it stays pending instead of failing.

## Web UI

Available to members of `administrators` and of the auditors group only.

- **Main drawer entry "Content changes audit"**: full-width listing of every `BlobDiff`, including the exact version transition, with filters (date range, source document via `nuxeo-document-suggestion`, user, truncated only) and facets (status, format, field, user). Actions: open, go to the source, filter on the source, download the diff, retry (errors only), **permanent deletion** of the selection, and **purge** before a date (optionally for one status).
- **"Content changes" tab** on documents holding files (`file` or `files` schema), with one row per version transition rather than per ordinary save.
- **`BlobDiff` document view** (`document/blobdiff/nuxeo-blobdiff-view-layout.html`): source, field, user, date, status and counters, files and digests, colored rendering of the diff (`+` / `-` / `~`, `# Images` section, progressive display by 1 000 lines), and the same actions. `nuxeo-blobdiff-metadata-layout.html` is empty on purpose: Web UI loads it and would otherwise get a 404.

Deletion is **permanent** (no trash): both dialogs show a warning and require an explicit acknowledgment.

Hiding the UI is cosmetic. The protection is the ACL of `/change-diff` and the server checks of the operations.

### Server Side

| Item | Purpose |
|---|---|
| `BlobDiff.Delete` | Permanently deletes the input BlobDiff documents (other types refused) |
| `BlobDiff.Purge(before, status?)` | Permanently deletes diffs dated before a day, by batches of 100 with a commit between batches, then removes emptied dated folders. Returns `{"deleted": n, "folders": m}` |
| `BlobDiff.Retry` | Replays a diff in `error` from its stored binary keys. The new diff keeps user, date and correlation id, and replaces the failed one once created |
| `blobDiffSource` enricher | `{uid, exists, trashed, readable, title, path, type}` of the source; title/path/type only when the current user can read it |

All three operations are refused (`DocumentSecurityException`) to anyone who is not an administrator, a member of `administrators` or of the auditors group.

### Deployment

`OSGI-INF/deployment-fragment.xml` unzips `web/nuxeo.war/**` and appends `ui/i18n/messages.json` and `messages-fr.json` (also to `messages-fr-FR.json`) to the Web UI translations. The bundle `ui/nuxeo-advanced-document-blob-audit/nuxeo-advanced-document-blob-audit.html` is registered through `WebResources` (`OSGI-INF/blobaudit-webui-contrib.xml`).

```
web/nuxeo.war/ui/
├── document/blobdiff/
│   ├── nuxeo-blobdiff-view-layout.html
│   └── nuxeo-blobdiff-metadata-layout.html
├── i18n/
│   ├── messages.json
│   └── messages-fr.json
└── nuxeo-advanced-document-blob-audit/
    ├── nuxeo-advanced-document-blob-audit.html   (slots: DRAWER_ITEMS/PAGES, PAGES, DOCUMENT_VIEWS_*)
    └── elements/                                 (search page, drawer, tab, viewer, dialogs, badges)
```

## Configuration

**Disabled by default.**

```xml
<extension target="org.nuxeo.audit.advanced.blob.BlobDiffComponent" point="config">
  <config enabled="true">
    <maxBlobSize>10485760</maxBlobSize>
    <maxLines>10000</maxLines>
    <maxDiffEntries>5000</maxDiffEntries>
    <imageAnalysisLevel>0</imageAnalysisLevel>
    <docTypes>
      <docType>Contract</docType>
    </docTypes>
    <xpaths>
      <xpath>file:content</xpath>
    </xpaths>
  </config>
</extension>
```

Auditors group (server and Web UI):

```xml
<extension target="org.nuxeo.runtime.ConfigurationService" point="configuration">
  <property name="org.nuxeo.web.ui.blobaudit.auditorsGroup">auditors</property>
</extension>
```

In production, restrict the feature to specific document types. Computing diffs for every binary in a repository is rarely worth the cost.

#### Image Analysis Level

`imageAnalysisLevel` is optional and defaults to `0` when omitted.

- `0`: no image analysis. This preserves the current text/cell-only behavior and adds no image-processing cost.
- `1`: reserved for digest-based image inventory, allowing extractors to report image additions, removals, and replacements. The configuration contract is available now; image extraction itself is implemented per format in subsequent changes.

This version supports values from `0` to `1`. At component startup, an invalid value is clamped to the nearest supported bound and a `WARN` is logged. For example, `-1` becomes `0`, while `2` becomes `1`. Future versions may raise the maximum when richer image-analysis modes are implemented.

At level 1, keys use `word:image:<media-part>`, `excel:image:<sheet>:<start-cell>:<end-cell>`, and `pdf:page:<page>:image:<ordinal>` and `powerpoint:slide:<slide>:image:<media-part>` (suffixed `#2`, `#3`… when the same image is placed several times on one slide). For PowerPoint, only pictures placed on slides (including inside groups) are inventoried; layouts/masters and linked pictures are ignored. Legacy `.ppt` gets text diff only. Values are SHA-256 digests. This level does not perform OCR or pixel-level visual comparison.

## Build

```bash
mvn clean install
```

The Marketplace package is generated under:

```text
nuxeo-advanced-document-blob-audit-package/target/
```

## Points of Attention

### Retrieving the Exact Binary Pair

`BlobDiffWork` does not transport `Blob` instances directly. When the work is scheduled, both sides of the comparison are frozen using their blob provider identifiers, storage keys, filenames, MIME types, digests, and lengths.

Both blobs already belong to persisted Nuxeo versions and are `ManagedBlob` instances. Their existing provider IDs and storage keys are captured directly; the listener does not write either blob again. The work never reloads the current blob property from the live document. This guarantees that a queued comparison always uses the exact version pair that triggered the event, even if the live document is modified again before the asynchronous work starts.

Both storage keys are resolved through `BlobManager` during work execution. If either frozen blob can no longer be resolved, the persistent `BlobDiff` is created with an `error` status instead of storing a misleading diff.

### Confidentiality

Business data is copied into a document protected by ACLs different from those of the source document.

For sensitive deployments (SecNumCloud, personal data, regulated environments), this design should be explicitly reviewed and validated.

### Scanned PDFs

Without OCR, text extraction returns nothing. The status remains `ok` but the diff is empty.

### PowerPoint Diff

`PresentationExtractor` (Apache POI XSLF, already provided by the platform) emits one `## Slide: <title>` marker per slide, then each text paragraph in drawing order (group shapes included), table rows as `[table] a | b | c`, and speaker notes as `[notes] ...` (disable with the `includeNotes=false` extractor property). Slide numbers are deliberately **not** part of the text: inserting or deleting a slide yields a compact diff instead of shifting every following slide. Image keys, on the other hand, do carry the slide number (see Known Limitations).

### Excel Row Insertions

Because keys are absolute cell references (`Sheet1!B12`), inserting a row shifts all cells below it and artificially inflates the diff.

This behavior is covered by `TestExcelDiff#testInsertingARowShiftsCellsAndInflatesTheDiff`.

Fixing it would require an additional row-alignment phase, making it a reasonable v2 enhancement.

### Unrouted Events = Lost Entries

`blobContentModified` must be declared both in the AuditRouter route and in the `eventTypes` vocabulary.

Without the route, the audit entry is silently ignored.

Without the vocabulary entry, it does not appear in Web UI filters.

## Known Limitations

### Text Extraction
- **Textual diff only.** Formatting (bold, colors, fonts, layout, styles) is never compared: restyling a Word paragraph or a PowerPoint text box produces no diff.
- **Scanned PDFs / image-only content.** Without OCR, nothing is extracted: status stays `ok` with an empty diff.
- **PDF quality is indicative.** Paragraph boundaries depend on Tika's layout reconstruction; multi-column or complex layouts may produce noisy diffs.
- **Positional counters are not an exact edit script.** A removal immediately followed by an addition is coalesced into a modification, so fully rewritten blocks report a mix of removals, additions and modifications (totals remain consistent).
- **Quadratic running time.** Hirschberg keeps memory linear, but time stays O(n × m): `maxLines` must remain bounded.
- **Truncation.** Beyond `maxLines` (extraction) or `maxDiffEntries` (reporting), the diff is partial and flagged `truncated`.

### Excel
- **Row/column insertions inflate the diff.** Keys are absolute cell references, so inserting a row shifts every cell below it (see `TestExcelDiff#testInsertingARowShiftsCellsAndInflatesTheDiff`).
- **Renaming a sheet** invalidates all its keys: every cell is reported as removed then added.
- Formulas are compared as formulas (default), not on their computed value.

### PowerPoint
- **Only OOXML is analyzed in depth** (`.pptx`, `.pptm`, `.ppsx`, `.potx`). Legacy binary `.ppt` falls back to `any2text`: flat text, no slide structure, no image inventory.
- **Not extracted:** SmartArt, charts, embedded OLE objects, text inherited from layouts/masters, comments, alt-text, hidden-slide status.
- **Slide moves** are reported as a removal plus an addition of the same content.
- **Slide titles** come from the title placeholder; slides without one get a bare `## Slide` marker, which may lower alignment quality on decks with many untitled slides.

### Images (`imageAnalysisLevel=1`)
- **Digest-based only.** Detects additions, removals and byte-level replacements; no OCR, no visual/pixel comparison. Re-encoding an identical-looking image is reported as a change.
- **Resizing, moving or cropping** an image without changing its bytes is not detected (except Excel, where the anchor is part of the key).
- **Position-sensitive keys.** PowerPoint keys include the slide number and PDF keys the page number and ordinal: inserting a slide/page before an image reports it as removed + added.
- **Word** keys are media part names: the same image used in several places appears once.
- **Linked (external) images** and images in PowerPoint layouts/masters are ignored.
- Image extraction failure silently falls back to the text-only result (logged as WARN).

### Security and Operations
- **Business content is copied** into `BlobDiff` documents protected by different ACLs than the source; review this for regulated environments.
- **No scheduled retention.** Purge is manual (Web UI or `BlobDiff.Purge`); schedule the operation yourself for automatic retention.
- **Elasticsearch required** for the Web UI listing and tab (`BLOB_DIFFS_ADMIN`).
- **Retry** only works for diffs created from 1.2 on (binary keys stored), and as long as the binaries are still in the blob store (the binaries garbage collector may have removed an unreferenced old version).
- **Web UI access** is based on the auditors group name exposed in `Nuxeo.UI.config`: overriding `<auditorsGroup>` without the configuration property desynchronises the UI (the server stays correct).
- `blobContentModified` must be declared both in the audit route and in the `eventTypes` vocabulary, or entries are lost / hidden.

## Tests

Run all tests:

```bash
mvn -pl nuxeo-advanced-document-blob-audit-core test
```

Run only the fast tests (without a Nuxeo runtime):

```bash
mvn -pl nuxeo-advanced-document-blob-audit-core test     -Dtest='TestTextDiffer,TestTextDifferScaling,TestExcelDiff,TestPresentationDiff,TestImageInventoryExtractor,TestPlainTextExtractor,TestExtractorSelection'
```

| Class | Runtime? | Purpose |
|--------|--------|--------|
| TestTextDiffer | No | Diff engine: keyed, positional, coalescing, truncation |
| TestTextDifferScaling | No | Alignment optimality and memory budget |
| TestExcelDiff | No | Complete Excel processing chain |
| TestPlainTextExtractor | No | txt / json / xml / csv |
| TestExtractorSelection | No | MIME type matching |
| TestPresentationDiff | No | PowerPoint text (slides, tables, notes) and image diff |
| TestConverterTextExtractor | Yes | Word / PDF extraction, using Assume |
| TestBlobDiffService | Yes | Guardrails, container, ACLs, persistence |
| TestBlobDiffLocationAndSecurity | Yes | Location under /change-diff, non-admin isolation, auditors access, ACL repair, concurrency, deleted source |
| TestBlobDiffManagement | Yes | Binary keys, Delete / Purge / Retry operations and their access control, blobDiffSource enricher |
| TestBlobAuditIntegration | Yes | End-to-end validation |


## Support
**These features are not part of the Nuxeo Production platform.**

These solutions are provided for inspiration and we encourage customers to use them as code samples and learning
resources.

This is a moving project (no API maintenance, no deprecation process, etc.) If any of these solutions are found to be
useful for the Nuxeo Platform in general, they will be integrated directly into platform, not maintained here.

## Licence

[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0)

## About Nuxeo

Nuxeo Platform is an open source highly scalable, cloud-native, enterprise content management product with rich multimedia support, written in Java. Data can be stored in both SQL & NoSQL databases.

The development of the Nuxeo Platform is mostly done by Nuxeo employees with an open development model.

The source code, documentation, roadmap, issue tracker, testing, benchmarks are all public.

More information is available at [Hyland/Nuxeo](https://www.hyland.com/en/solutions/products/nuxeo-platform).
