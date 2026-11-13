# nuxeo-advanced-document-blob-audit

Audit **what actually changed inside a file** between two successive versions of a document: Excel spreadsheets cell by cell, PowerPoint decks slide by slide, Word documents paragraph by paragraph, PDFs line by line as rendered, and text formats line by line.

A **standalone plugin** with no dependency on `nuxeo-advanced-document-audit`, which handles scalar fields. Both plugins can coexist.

> [!IMPORTANT]
> By essence, this plugin is complex. With listeners, asynchronous work, different code for extractors
> (depending on the mime type), etc.
>
> It is a **Nuxeo Presales Sandbox plugin**, showing art of the possible but not included as part of the
> Nuxeo Platform and, so, **not supported**. The need for this feature is very rare and mostly limited to
> strict regulation constraints. So, if you need it **we strongly recommend a Nuxeo Partner forks the code
> and takes ownership** (tuning, maintenance, support)

| Step | Where | When |
|---|---|---|
| Detect a binary change | `BlobModificationListener`, on each check-in | synchronous: a digest comparison |
| Write the `blobContentModified` audit entry | the audit log | same transaction, **no business content** |
| Extract and compare both binaries | `BlobDiffWork`, queue `blobDiff` | asynchronous, after commit, outside any transaction |
| Store the result | a `BlobDiff` document under `/change-diff` | readable by the auditors only |

Only the creation of a version is audited, never an ordinary save. The design notes, for whoever forks or maintains the plugin, are in [`docs/DESIGN.md`](docs/DESIGN.md).

## Quick Start

1. Install the package (it requires `nuxeo-web-ui`): `nuxeoctl mp-install nuxeo-advanced-document-blob-audit-<version>.zip`.
2. Enable the feature, **disabled by default**, and scope it:

```xml
<component name="com.example.blobaudit.config">
  <require>org.nuxeo.audit.advanced.blob.BlobDiffComponent</require>
  <extension target="org.nuxeo.audit.advanced.blob.BlobDiffComponent" point="config">
    <config enabled="true">
      <docTypes>
        <docType>Contract</docType>
      </docTypes>
      <xpaths>
        <xpath>file:content</xpath>
      </xpaths>
      <!-- Optional: also report added, removed and replaced images -->
      <imageInventory>true</imageInventory>
    </config>
  </extension>
</component>
```

3. Choose the auditors group. It is the single setting of the group, read by the server checks, by the ACL of `/change-diff` and by Web UI. Default: `administrators`, whose members always have access. The plugin contributes no value of its own, so this contribution needs no other `<require>` than the one above and is never overridden, whatever the order Nuxeo loads the components in. The ACL of `/change-diff` follows the new group at the next start.

```xml
<extension target="org.nuxeo.runtime.ConfigurationService" point="configuration">
  <property name="org.nuxeo.web.ui.blobaudit.auditorsGroup">auditors</property>
</extension>
```

Restrict the feature to the document types that need it: once enabled, the shipped configuration watches every type, and every diff copies business content into `/change-diff`.

## What Gets Recorded

### The Audit Entry

Each binary change between two versions writes one `blobContentModified` entry on the source document, in the default document category, so it shows in the Web UI history and audit screens under the "Document" filter. The event is added to the `eventTypes` vocabulary at startup, labelled *Binary content modified*. Its comment is `<xpath> : <outcome>`, for instance `file:content : binary content modified between versions`, and its extended infos are:

| Key | Value |
|---|---|
| `xpath` | the blob property that changed |
| `oldFilename`, `newFilename` | the file names on the previous and on the new version |
| `oldMimeType`, `newMimeType` | their mime types |
| `diffCorrelationId` | the `bdiff:correlationId` of the `BlobDiff` the work creates |
| `skipReason` | why the change is audited without a diff, see [Statuses](#statuses) |

`diffCorrelationId` and `skipReason` are mutually exclusive. The entry never holds extracted content: that only goes to the `BlobDiff`, which is created once the work has run.

### The `BlobDiff` Document

Stored under `/change-diff/YYYY/MM/DD/`, dated in **UTC**, titled `<source title> - <xpath>`, with the facets `HiddenInNavigation`, `NotCollectionMember` and `NotFulltextIndexable` — the binary text of the diff never reaches the full-text index.

| Field | Content |
|---|---|
| `bdiff:sourceId`, `bdiff:sourceRepository` | the live source document |
| `bdiff:xpath` | the blob compared |
| `bdiff:previousVersionId`, `previousVersionLabel`, `newVersionId`, `newVersionLabel`, `versionSeriesId` | the two versions compared |
| `bdiff:correlationId` | the link to the audit entry |
| `bdiff:user`, `bdiff:date` | who created the new version, and when |
| `bdiff:oldFilename`, `newFilename`, `oldDigest`, `newDigest` | the two files |
| `bdiff:oldBlobProvider`, `oldBlobKey`, `oldMimeType`, `oldLength`, and the same four `new*` fields | the storage identity of both binaries, used by `BlobDiff.Retry` |
| `bdiff:status` | see [Statuses](#statuses) |
| `bdiff:summary`, `added`, `removed`, `changed`, `truncated` | a short summary and exact counters |
| `bdiff:similarity` | share of the smaller version kept unchanged in the other one, in characters, `0` to `1`; absent when not measured |
| `bdiff:diff` | the diff, a `text/plain` blob, present on an `ok` diff that found changes |

The body lists `+ added`, `- removed` and, for keyed content, `~ Sheet1!B2 : 1 -> 2` lines; the image inventory follows under a `# Images` line. Each line is one entry: a line break inside a value (a cell typed with Alt+Enter, a PowerPoint soft line break) is written as a literal `\n`. `bdiff:summary` is a plain **English** sentence ("2 modifications, 1 addition", "No textual change detected"), written once and never translated: a localised summary is built from the counters, as Web UI does.

### Statuses

| Value | Set when | Audit entry | `BlobDiff` | Body | Retry |
|---|---|---|---|---|---|
| `ok` | work: diff computed | nominal, with `diffCorrelationId` | yes | yes, unless nothing changed | no |
| `skippedTooLarge` | check-in: a side over `maxBlobSize`; or work: same re-check on the pair (retry, configuration change) | check-in: `skipReason`; work: the nominal entry | work case only | no | no |
| `skippedUnsupportedType` | check-in: no extractor; or work: no extractor when the diff runs | same pattern | work case only | no | no |
| `skippedFormatChanged` | check-in: the two sides read by two extractor contributions; or work: same | same pattern | work case only | no | no |
| `skippedNotManaged` | check-in: blobs not both managed by a provider | `skipReason` | never | — | — |
| `skippedListChanged` | check-in (`*` mode): a list also lost an entry | `skipReason` | never | — | — |
| `skippedContentReplaced` | work: mime type changed and similarity under `minSimilarity` (plain text only) | nominal | yes, counters and similarity | no | no |
| `error` | work: extraction failed, a blob unreadable, source deleted, local copy failed | nominal | yes | no | yes — `BlobDiff.Retry`, 409 while a replay is pending |

A skipped check-in entry reads `file:content : binary changed (too large)`, `(unsupported format)`, `(format changed)`, `(blob not managed by a provider)` or `(list entries also removed or moved, previous file unknown)`. A binary that changed is always audited, diffable or not: the absence of an entry must mean the absence of a change.

Precedence when several skips apply: out of scope (silent) > `skippedTooLarge` > `skippedUnsupportedType` > `skippedFormatChanged`. The digest comparison runs before any skip is reported, so identical bytes are never audited, not even uploaded again under another name or mime type. Out of scope (feature off, type or xpath not watched): nothing at all. `skippedNotManaged` only happens with an unusual blob provider setup, logs a `WARN`, and since such blobs usually carry no digest, every new version of the pair is audited.

### Format Changes

Two extractors do not cut a document into units the same way: comparing the cells of an `.xlsx` with the paragraphs of the `.docx` that replaced it would report everything as removed and added. A new version is therefore only diffed when **the same extractor contribution** reads both blobs; otherwise the change is `skippedFormatChanged`. Each contribution is a comparability class:

| Contribution | Mime types | Diffed with each other |
|---|---|---|
| `spreadsheet` | xlsx, xlsm, xls | yes: an `.xls` saved as `.xlsx` is diffed cell by cell |
| `presentation` | pptx, pptm, ppsx, potx | yes |
| `plainText` | `text/*`, json, xml, yaml, javascript, sql | yes: lines are read raw, whatever the mime type — but see the next section |
| `converter` | docx, doc, odt, rtf | yes: the converter cuts all of them into paragraphs |
| `pdf` | pdf | — |
| `legacyPresentation` | ppt | — |

`pdf` and `legacyPresentation` use the same class as `converter`, and are separate for that reason alone: a Word document replaced by its PDF rendition is not diffed. To make two formats comparable, move their mime types into one contribution; to stop comparing two, split it.

### A Text File Replaced by Another One

A `.json` replaced by an unrelated `.xml` is read by `plainText` on both sides, so it is no format change, and its diff would list both files whole. Such a pair is filed **`skippedContentReplaced`**: the `BlobDiff` keeps its counters and its similarity, not the body. All three conditions are required:

1. the mime type changed, compared without case nor parameters (`text/plain; charset=UTF-8` is `text/plain`);
2. both sides hold at least `minSimilarityLines` lines (5);
3. the similarity is under `minSimilarity` (`0.1`).

```text
similarity = characters of the lines kept unchanged / characters of the smaller version
```

Unrelated files score 0 to 5 %, the same data converted from JSON to XML close to 0 % too, an ordinary edit over 90 %. A rewrite **in the same format** is always diffed: the rule is about a file replaced by another one, not about how much an author changed. The score is stored on every diff where it was measured, so the threshold can be tuned on real data. To keep every full body, set `minSimilarity` to `0`:

```xml
<extension target="org.nuxeo.audit.advanced.blob.BlobDiffComponent" point="extractors">
  <extractor name="plainText">
    <property name="minSimilarity">0</property>
  </extractor>
</extension>
```

Nothing is lost by not storing the body: both versions are immutable and the `BlobDiff` keeps their ids and both blob keys. `BlobDiffService#diff(oldBlob, newBlob)` on the blobs of the two versions returns the full body at any time — the result is only flagged `replaced()`. `BlobDiff.Retry` does not replay this status: it is no incident.

### Blobs in Lists

With `<xpath>*</xpath>`, blobs inside lists such as `files:files` are watched, and paired **by content**, never by index: a file present on both versions, wherever it sits, is unchanged; a new file is compared with the one at its position only when the list kept its shape and that file is gone. Adding, removing or reordering attachments is therefore not audited, and a file that changed in a list that also lost an entry is `skippedListChanged`, since which file it replaces cannot be told. An explicitly configured indexed xpath (`files:files/0/file`) is compared by index.

## Supported Formats

| Format | Contribution | Extractor | Mode | Notes |
|---|---|---|---|---|
| xlsx, xlsm, xls | `spreadsheet` | `SpreadsheetExtractor` | keyed, one unit per cell (`Sheet1!B12`) | formulas compared as formulas (`useFormulas`); OOXML read sheet by sheet |
| pptx, pptm, ppsx, potx | `presentation` | `PresentationExtractor` | positional, one unit per paragraph | `## Slide: <title>` markers, `[table] a \| b` rows, `[notes]` (`includeNotes`) |
| text, json, xml, csv, yaml, source files | `plainText` | `PlainTextExtractor` | positional, one unit per line | charset and BOM handled; `trimLines`, `skipBlankLines` |
| docx, doc, odt, rtf | `converter` | `ConverterTextExtractor` | positional, one unit per paragraph | through the platform `any2text` converter |
| pdf | `pdf` | `ConverterTextExtractor` | positional, one unit per rendered line | indicative: depends on the layout reconstruction |
| ppt | `legacyPresentation` | `ConverterTextExtractor` | positional | flat slide text, no image inventory |

Keyed content is compared as two maps, so the diff is exact and independent of the extraction order. Positional content is aligned with Hirschberg's algorithm, and a removal immediately followed by an addition is reported as a modification. No external diff library is needed.

**Adding a format.** Implement `BlobContentExtractor` and contribute it; the lowest `order` wins for a mime type, so a specialised extractor overrides a generic one. Any format the `any2text` converter reads only needs a `<mimeType>` on a `ConverterTextExtractor` contribution.

```xml
<extension target="org.nuxeo.audit.advanced.blob.BlobDiffComponent" point="extractors">
  <extractor name="myFormat" order="15" class="com.example.MyExtractor">
    <mimeTypes>
      <mimeType>application/vnd.example</mimeType>
    </mimeTypes>
  </extractor>
</extension>
```

### Image Inventory

With `<imageInventory>true</imageInventory>`, the images of Word, Excel, PowerPoint OOXML and PDF files are listed next to the text and compared on their SHA-256: additions, removals and replacements appear under `# Images`. No OCR, no visual comparison. Each format is a contribution of its own to the `imageExtractors` point — `imageWord`, `imageSpreadsheet`, `imagePresentation`, `imagePdf` — so one can be turned off alone:

```xml
<extension target="org.nuxeo.audit.advanced.blob.BlobDiffComponent" point="imageExtractors">
  <extractor name="imagePdf" enabled="false" />
</extension>
```

| Format | Key |
|---|---|
| Word | `word:image:<media-part>` |
| Excel | `excel:image:<sheet>:<start-cell>:<end-cell>`, `<cell>` alone for a picture on one cell, `image:<ordinal>` for an absolute position |
| PowerPoint | `powerpoint:slide:<slide>:image:<media-part>` |
| PDF | `pdf:page:<page>:image:<ordinal>` |

Excel and PowerPoint keys get a `#2`, `#3`… suffix when several pictures share one. Only pictures placed on slides are listed (groups included), not those of layouts and masters; linked (external) pictures are ignored.

## Configuration

Every option of the `config` extension point of `org.nuxeo.audit.advanced.blob.BlobDiffComponent`:

| Option | Default | Meaning |
|---|---|---|
| `enabled` (attribute) | `false` | global switch |
| `docTypes` | empty: every type | document types watched; `*` also means every type |
| `xpaths` | `file:content` | blob xpaths watched; `*` means every blob xpath of the schemas the document carries, those of facets added to the document included |
| `maxBlobSize` | `10485760` (10 MB) | a larger binary is never extracted: `skippedTooLarge` |
| `maxLines` | `10000` | units extracted from each side; past it the diff is `truncated` |
| `maxDiffEntries` | `5000` | differences written to the body |
| `maxDiffChars` | `4194304` | length of the whole body, in characters |
| `maxValueLength` | `4096` | length of a single unit: a longer one keeps its beginning, its length and a digest of the whole |
| `imageInventory` | `false` | the [image inventory](#image-inventory) |

Extractor properties, set on a contribution of the `extractors` point: `useFormulas` (`spreadsheet`, `true`: with `false`, formulas are compared on the value cached in the file), `includeNotes` (`presentation`, `true`), `trimLines` and `skipBlankLines` (`plainText`, both `true`), `converter` and `minLineLength` (the three `ConverterTextExtractor` contributions, `any2text` and `1`), `minSimilarity` and `minSimilarityLines` (`plainText` only, `0.1` and `5`, see [above](#a-text-file-replaced-by-another-one)). `maxValueLength` is handed to every extractor from the configuration unless its contribution sets one.

### Merging Contributions

Contributions **merge**: a later one only overrides what it sets, the rest keeps the earlier values. A contribution setting nothing but `enabled="true"` and `docTypes`, like the one of the [Quick Start](#quick-start) without its `xpaths`, keeps the shipped bounds and xpaths.

- A list (`docTypes`, `xpaths`) replaces the earlier one only when it is **not empty**: an empty element cannot widen a list back, list `*` instead.
- A value that is set always wins: `<config enabled="false">` still switches the feature off.
- The `extractors` and `imageExtractors` points merge the same way, by extractor name: an override names the extractor and sets what it changes; its properties are added to the earlier ones, and its mime types replace them only when it lists some. An override of `converter` does not reach `pdf` nor `legacyPresentation`: override them by name too.
- An invalid `minSimilarity` is logged at `ERROR` and disables the rule, never the extractor.

### Bounding the Size of a Diff

`maxBlobSize` bounds what may be opened at all, `maxLines` and `maxValueLength` what is extracted — at most `maxLines × maxValueLength` characters held per side — and `maxDiffEntries`, `maxDiffChars` and `maxValueLength` what is written. An Excel cell holds up to 32 767 characters and a Word paragraph is unbounded, so the count alone would not bound the body. Inside an `.xlsx` or a `.pptx`, a single text longer than an Excel cell is never held whole while it is read, however much its compressed part inflates.

When a cap is reached, `bdiff:truncated` is set and the counters `added`, `removed` and `changed` stay **exact**: only the rendering is shortened. `maxLines` cuts each side on its own, so past the last unit both windows share, a unit missing from one side may simply lie beyond its cut: it is neither reported nor counted. Before that point every reported change is a fact. A value longer than `maxValueLength` is shown as its beginning, its length and a short SHA-256 of the whole (`X… [32767 chars, sha256:…]`), so two values differing only past the beginning still render differently.

`maxLines` also bounds the cost of the alignment: a few edited paragraphs cost milliseconds whatever the size of the document, a document rewritten from end to end costs time quadratic in its length.

### Disabling the Listener

The `enabled` switch is global and static. For targeted, runtime control, the plugin follows the platform convention (`DublinCoreListener.DISABLE_DUBLINCORE_LISTENER`, `CoreSession.DISABLE_AUDIT_LOGGER`, `VersioningService.DISABLE_AUTO_CHECKOUT`) and offers three mechanisms. When the listener is disabled, **nothing** happens — no audit entry, no work, no `BlobDiff` — and the version itself is created normally.

**1. Per operation, through the document context data**

```java
import static org.nuxeo.audit.advanced.blob.BlobModificationListener.DISABLE_BLOB_DIFF_LISTENER;

doc.putContextData(DISABLE_BLOB_DIFF_LISTENER, Boolean.TRUE);
doc.putContextData(VersioningService.VERSIONING_OPTION, VersioningOption.MINOR);
session.saveDocument(doc);   // version created, no audit entry and no BlobDiff
```

`CoreSession#saveDocument` copies the document context data into its option map, which it passes to `aboutToCheckIn` and `documentCheckedIn` — the two events the listener reads — for the version **it creates itself**, from the `VERSIONING_OPTION` set on the document. The same key also works as an event property when you fire the event yourself.

> [!IMPORTANT]
> The flag only covers **manual** versioning through `saveDocument`. Every other path that creates a version builds its own option map, so the flag never reaches the listener and the version is audited and diffed regardless:
>
> - an **automatic versioning policy** applied during the same save. The platform ships `collaborative-save`, active by default, which creates a minor version *before* the update whenever the user saving is not the last contributor, and `note-as-wiki`, which versions every note. Both go through `DocumentModel#checkIn`, the next path;
> - `session.checkIn(docRef, option, comment)`, which builds a *fresh, empty* option map;
> - `session.restoreToVersion(...)`, which snapshots the live document before restoring, and `session.publishDocument(...)`, both of which pass no option map at all.
>
> An importer or a migration that re-saves documents last edited by someone else is the typical trap: with the flag set and no `VERSIONING_OPTION`, `collaborative-save` still snapshots each document, and each snapshot yields an audit entry and a `BlobDiff`. Use the thread-scoped switch below for anything that checks in explicitly, restores, publishes, or saves documents that a policy may version.

**2. Thread-scoped, for migrations, importers, explicit check-ins, restores and publishing**

```java
BlobModificationListener.runDisabled(() -> {
    for (DocumentModel doc : batch) {
        session.checkIn(doc.getRef(), VersioningOption.MINOR, "bulk migration");
    }
});

// value-returning variant
DocumentRef ref = BlobModificationListener.runDisabled(
        () -> session.checkIn(doc.getRef(), VersioningOption.MINOR, null));
```

The previous state is restored in a `finally` block, so nesting is safe and an exception can never leave the listener disabled for the rest of the thread — which, on a pooled request thread, would silently stop auditing the whole instance. `BlobModificationListener.isDisabledForThread()` exposes the current state.

**3. Instance-wide, at runtime**

```java
Framework.getService(EventServiceAdmin.class).setListenerEnabledFlag("blobModificationListener", false);
```

Global, until it is set back or the node restarts. Prefer 1 and 2 for anything scoped.

## Web UI

Shown to members of `administrators` and of the auditors group only. Hiding it is cosmetic: the protection is the ACL of `/change-diff` and the server checks of the operations. A user who is not an auditor and opens `#!/search/blobdiffs` by hand gets an empty pane.

- **"Content changes audit"**, a drawer entry built like the platform searches: the form in the drawer, the results in the main area, one click opening both. Filters: date range, source document, user, truncated diffs only; facets: status, format, field, user. The results are a standard `nuxeo-results`: sort, selection, CSV export, saved searches, and per-user column settings kept in local storage. Row actions: open, go to the source, show only the diffs of this document, download the diff, retry (an `error` diff with both blob keys). Selection action: permanent deletion. Toolbar action: purge.
- **"Content changes" tab** on documents holding files (`file` or `files` schema): one row per version transition. "Open in the content changes audit" opens the search on that document alone, dropping the filters of an earlier search.
- **`BlobDiff` view**: source (a link when readable, badged *In trash* when it is; *Source deleted*, or *Source not accessible to you*), field, user, date, status and counters, similarity, files and digests, and the diff rendered in color (`+` / `-` / `~`, `# Images`), shown 1 000 lines at a time. Actions: download, retry, back to the list, delete. There is **no Edit action**: a diff is a record.

Wherever a status is shown, a **"Format: json → xml"** badge follows it when the two versions do not hold the same mime type, with both mime types in its tooltip.

Deletion and purge are **permanent** (no trash): both dialogs show a warning and require an explicit acknowledgment.

## Operations and Purge

| Operation | Purpose |
|---|---|
| `BlobDiff.Delete` | Permanently deletes the input `BlobDiff` documents; any other type is refused (400) |
| `BlobDiff.Purge(before, status?)` | Schedules the permanent deletion of the diffs dated before a day (UTC), optionally of one status, on the Bulk Action Framework. Returns `{"commandId": "...", "state": "SCHEDULED"}`; refused with 409 while a purge runs |
| `BlobDiff.PurgeStatus(commandId)` | `{state, deleted, processed, total, errorCount, skipCount, queryLimitReached}`. Any id that is not a purge answers `UNKNOWN` |
| `BlobDiff.PurgeAbort(commandId)` | Stops a purge at its next batch of 50 diffs. Deleted diffs are not restored; running the purge again finishes the job |
| `BlobDiff.CleanEmptyFolders` | Removes the dated folders left empty under `/change-diff`. Returns `{"folders": n}` |
| `BlobDiff.Retry` | Replays a diff in `error` from its stored blob keys. The new diff keeps user, date and correlation id and replaces it; the diff stays `error` meanwhile. Refused with 409 while a replay of the same diff is pending |

Every operation is refused to anyone but an administrator or a member of `administrators` or of the auditors group. The `blobDiffSource` enricher gives the state of the source of a `BlobDiff` — `{uid, exists, trashed, readable, title, path, type}`, the last three only when the current user can read it.

A purge is asynchronous; the Web UI runs this sequence for you, and a script scheduling a retention purge runs it too:

1. `BlobDiff.Purge(before, status?)` → `commandId`;
2. poll `BlobDiff.PurgeStatus(commandId)` until `state` is `COMPLETED` or `ABORTED`;
3. `BlobDiff.CleanEmptyFolders`, once the command has emptied the folders.

`deleted` is the exact count of removed diffs; `processed` counts the ids handed to the purge. A completed purge with failed or skipped diffs, or with `queryLimitReached`, needs another run. One purge runs at a time per repository, under the calling user, so the deletion stays subject to the ACL of `/change-diff`; it only ever removes `BlobDiff` documents under `/change-diff`.

## Deployment and Prerequisites

- **Package.** The marketplace package ships the plugin bundle only: Apache POI and PDFBox come with the LTS 2025 server. The bundle unzips its Web UI files into `nuxeo.war` and appends its translations to those of Web UI and of the server.
- **Search engine, for the facets.** The audit search and the document tab use `BLOB_DIFFS_ADMIN`, which runs on the configured search client. With the OpenSearch (or Elasticsearch) search-client package, the query and its four facets run on the engine; no mapping has to be deployed. On a bare instance the default `repository` client still lists the diffs, but the facets come back **silently empty**.
- **Repository indexes.** `bdiff:correlationId` and `bdiff:sourceId` are declared with `indexOrder`, which MongoDB applies at startup. On **VCS (SQL)**, create these two indexes by hand.
- **Work queue.** The `blobDiff` queue runs 2 works at a time. Raise `maxThreads` only after measuring: each work can hold two extracted documents in memory.
- **The container.** `/change-diff` is created at each repository initialisation, even with the feature disabled, partitioned by day in **UTC**. Its local ACL — the auditors group granted `Read`, `Remove` and `RemoveChildren`, inheritance blocked — is reset with a `WARN` at each startup if it was changed; dated folders inherit it.
- **Security**, in short: the diffs are written with a system session; a `BlobDiff` cannot be created, moved or copied outside `/change-diff`, by anyone, administrators included; every reader of diffs only trusts what sits under `/change-diff`; `BlobDiff.Retry` only replays a stored diff from that container. The model, its reasons and its limits: [`docs/DESIGN.md`](docs/DESIGN.md#7-security-model).

## Limitations

- **Business content is copied** into `BlobDiff` documents, protected by other ACLs than the source. Review this design for regulated environments.
- **Textual diff only.** Formatting (bold, colors, fonts, layout, styles) is never compared.
- **Scanned PDFs and image-only content** extract nothing without OCR: the status is `ok` and the diff is empty.
- **PDF quality is indicative.** Units are the lines rendered by the converter; multi-column or complex layouts give noisy diffs.
- **Converted formats** (Word, OpenDocument, RTF, PDF, `.ppt`): the conversion processes the whole document; only the reading of its output stops at `maxLines`.
- **Excel**: keys are absolute cell references, so inserting a row or a column shifts every cell after it and inflates the diff, and renaming a sheet reports all its cells as removed and added. Legacy `.xls` is loaded whole.
- **PowerPoint**: only OOXML is read in depth. SmartArt, charts, OLE objects, text inherited from layouts or masters, comments, alt-text and hidden-slide status are not extracted. A moved slide is a removal plus an addition; a slide without title placeholder gets a bare `## Slide` marker.
- **Positional counters are not an exact edit script.** A removal followed by an addition is coalesced into a modification, so a rewritten block reports a mix of removals, additions and modifications; the totals stay consistent.
- **Images**: digest-based. A re-encoded image, or a PDF image re-compressed by its producer, is reported as replaced; resizing or moving an image without changing its bytes is not detected (except an Excel picture changing cells); PowerPoint and PDF keys carry the slide or page number, so inserting a slide or a page reports the images after it as removed and added; a Word image used twice appears once. An image is read once per file however often it is placed. An image over 32 MB is skipped, a PDF form nested deeper than 16 levels or containing itself too, and the inventory stops past 256 MB of images or past `maxLines` images: in each case the diff is `truncated`. A failing inventory falls back to the text diff, with a `WARN`.
- **No scheduled retention.** The purge is manual, or scheduled by your own script following the sequence above.
- **Retry** needs the binaries to be still in the blob store.
- **Diff-to-diff navigation in Web UI.** Opening a `BlobDiff` from another one — from the drawer queue or from a link — updates the URL, the title and the header, but the page keeps showing the previous diff; a reload shows the right one. Under investigation: the document never reaches the plugin's view layout.
- **Web UI with `org.nuxeo.web.ui.expressions.eval=false`** (the setting that enables the Content-Security-Policy of Web UI): the drawer entry, the drawer search form and the "Content changes" tab are hidden from everyone, administrators included, because their visibility filter calls a helper of the plugin that the sandboxed expressions cannot reach, and `#!/search/blobdiffs` shows an empty pane. Nothing else is affected: the diffs are recorded, a `BlobDiff` opened from its URL is displayed with its actions, and the operations and the server checks work as usual. Hiding Edit on `BlobDiff` documents works in both modes.

## Build and Tests

```bash
mvn clean install
```

The marketplace package is built under `nuxeo-advanced-document-blob-audit-package/target/`. The tests that need no Nuxeo runtime run in about 20 seconds:

```bash
mvn -pl nuxeo-advanced-document-blob-audit-core test \
  -Dtest='TestTextDiffer,TestTextDifferScaling,TestTextDifferBounding,TestTextDifferSimilarity,TestExcelDiff,TestPresentationDiff,TestImageInventoryExtractor,TestPlainTextExtractor,TestExtractorSelection,TestSpreadsheetStreaming,TestPresentationStreaming,TestExtractedValueBounding,TestBlobDiffConfigDescriptor'
```

What each test class covers, and the test resources, are listed in [`AGENTS.md`](AGENTS.md#build-and-test).

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
