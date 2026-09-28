# nuxeo-advanced-document-blob-audit

> [!IMPORTANT]
> This is **Work in Progress**. For now, using GitHub and this repo as backup.
> 
> **Do not use it as is**, it's not working (yet)
> 
> Once ready it will forked to Nuxeo Presales Github Sandboxes.

Audit **what actually changed inside a file**: Excel spreadsheets cell by cell, Word documents and PDFs paragraph by paragraph, and text formats line by line.

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
| ConverterTextExtractor | docx, doc, odt, rtf, **pdf** | positional, paragraph-based | Good (Word), indicative (PDF) |

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

The diff is stored as a blob, not a string: outside SQL/Mongo records and outside full-text indexing.

### Container and Security

`/change-diff/YYYY/MM/DD/` — partitioned by date.

Created by a `RepositoryInitializationHandler`, with inheritance blocked and read access granted only to the `administrators` group (see `BlobAuditConstants.AUDITORS_GROUP` to use a dedicated auditors group).

Writes are performed using a **system session**: users modifying the file have no permissions on this container.

### Diff History View

Page provider `BLOB_DIFFS_FOR_DOCUMENT`.

`BLOB_DIFFS_OLDER_THAN` is used for cleanup and retention.

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

In production, restrict the feature to specific document types. Computing diffs for every binary in a repository is rarely worth the cost.

#### Image Analysis Level

`imageAnalysisLevel` is optional and defaults to `0` when omitted.

- `0`: no image analysis. This preserves the current text/cell-only behavior and adds no image-processing cost.
- `1`: reserved for digest-based image inventory, allowing extractors to report image additions, removals, and replacements. The configuration contract is available now; image extraction itself is implemented per format in subsequent changes.

This version supports values from `0` to `1`. At component startup, an invalid value is clamped to the nearest supported bound and a `WARN` is logged. For example, `-1` becomes `0`, while `2` becomes `1`. Future versions may raise the maximum when richer image-analysis modes are implemented.

At level 1, keys use `word:image:<media-part>`, `excel:image:<sheet>:<start-cell>:<end-cell>`, and `pdf:page:<page>:image:<ordinal>`. Values are SHA-256 digests. This level does not perform OCR or pixel-level visual comparison.

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

The new blob is explicitly written to its `BlobProvider` before the work is queued. The work therefore never reloads the current blob property from the source document. This guarantees that a queued comparison always uses the exact pair that triggered the event, even if the source document is modified again before the asynchronous work starts.

Both storage keys are resolved through `BlobManager` during work execution. If either frozen blob can no longer be resolved, the persistent `BlobDiff` is created with an `error` status instead of storing a misleading diff.

### Confidentiality

Business data is copied into a document protected by ACLs different from those of the source document.

For sensitive deployments (SecNumCloud, personal data, regulated environments), this design should be explicitly reviewed and validated.

### Scanned PDFs

Without OCR, text extraction returns nothing. The status remains `ok` but the diff is empty.

### Excel Row Insertions

Because keys are absolute cell references (`Sheet1!B12`), inserting a row shifts all cells below it and artificially inflates the diff.

This behavior is covered by `TestExcelDiff#testInsertingARowShiftsCellsAndInflatesTheDiff`.

Fixing it would require an additional row-alignment phase, making it a reasonable v2 enhancement.

### Unrouted Events = Lost Entries

`blobContentModified` must be declared both in the AuditRouter route and in the `eventTypes` vocabulary.

Without the route, the audit entry is silently ignored.

Without the vocabulary entry, it does not appear in Web UI filters.

## Tests

Run all tests:

```bash
mvn -pl nuxeo-advanced-document-blob-audit-core test
```

Run only the fast tests (without a Nuxeo runtime):

```bash
mvn -pl nuxeo-advanced-document-blob-audit-core test     -Dtest='TestTextDiffer,TestTextDifferScaling,TestExcelDiff,TestPlainTextExtractor,TestExtractorSelection'
```

| Class | Runtime? | Purpose |
|--------|--------|--------|
| TestTextDiffer | No | Diff engine: keyed, positional, coalescing, truncation |
| TestTextDifferScaling | No | Alignment optimality and memory budget |
| TestExcelDiff | No | Complete Excel processing chain |
| TestPlainTextExtractor | No | txt / json / xml / csv |
| TestExtractorSelection | No | MIME type matching |
| TestConverterTextExtractor | Yes | Word / PDF extraction, using Assume |
| TestBlobDiffService | Yes | Guardrails, container, ACLs, persistence |
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
