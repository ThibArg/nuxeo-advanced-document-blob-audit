# nuxeo-advanced-document-blob-audit

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

### Binary Changes That Cannot Be Diffed

A binary that changed but cannot be compared — larger than `maxBlobSize`, or in a format no extractor handles — is **still audited**. For an audit tool, staying silent would be the worst possible behaviour: the absence of an entry would be indistinguishable from the absence of a change.

In that case the plugin writes the `blobContentModified` entry **only**, and creates no BlobDiff document:

| Case | Audit comment | `skipReason` extended info | BlobDiff |
|------|---------------|----------------------------|----------|
| Blob over `maxBlobSize` | `file:content : binary changed (too large)` | `skippedTooLarge` | none |
| No extractor for the mime type | `file:content : binary changed (unsupported format)` | `skippedUnsupportedType` | none |
| Blobs not both managed by a provider | `file:content : binary changed (blob not managed by a provider)` | `skippedNotManaged` | none |
| Diffable change | `file:content : binary content modified between versions` | absent | created |

The third case only happens with an unusual blob provider setup: the asynchronous work re-reads both binaries from their provider after commit, which requires a provider id and a key. It also comes with a `WARN` in the logs. Note that such blobs usually carry no digest, and the "did it really change?" guard answers "different" when it cannot tell, so every new version of such a pair is audited.

`skipReason` and `diffCorrelationId` are mutually exclusive: a skipped entry carries no correlation id, because no BlobDiff will ever exist to point at.

Nothing at all is written when the property is simply out of scope — feature disabled, document type or xpath not covered — or when the binary did not actually change. The digest comparison always runs first, so a new version of an over-sized document whose binary never moved stays silent.

> [!NOTE]
> Both skip reasons are also reachable as a `bdiff:status`, on a different path. The check-in described above never schedules a work for them, but `BlobDiffWork` re-evaluates the eligibility of the rehydrated blobs before extracting, so a `BlobDiff.Retry`, or a work outliving a `maxBlobSize` change, can be filed as `skippedTooLarge` or `skippedUnsupportedType`. `skippedUnsupportedType` is also what the work records when the two blobs no longer share a mime type an extractor handles. `skippedNotManaged`, on the other hand, is a skip reason only: no `BlobDiff` is ever created in that case.

### How the Version Pair Is Established

The listener hooks the two events of a check-in, **both fired on the live document**:

| Event | What the plugin does |
|-------|----------------------|
| `aboutToCheckIn` | Records which version is currently the last one, *before* the new one exists |
| `documentCheckedIn` | Reads the new version from the platform's `checkedInVersionRef` property and compares it with the recorded one |

Capturing the predecessor before the fact makes the pair exact. Nothing is inferred from version numbering, so renumbering versions out of band — a bulk update straight in the database, a migration — no longer affects which pair is compared.

Two core call sites notify a check-in without forwarding the event options, so the recorded reference cannot always reach the second event. The plugin then falls back to a query ordered by `ecm:versionCreated`, which is a record of *when* a version was created rather than an assumption about how its label was numbered.

> [!NOTE]
> Earlier versions of this plugin hooked `documentCreated`, filtered on `isVersion()`, walked back up to the live document and re-derived the previous version from `ORDER BY uid:major_version DESC, uid:minor_version DESC`. That assumed monotonically increasing version numbers and had to skip the diff with a `WARN` whenever the assumption could not be verified. Both the assumption and the bail-out are gone.

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

### Image Inventory Extractors

Image inventories have a point of their own, `imageExtractors`, because an image extractor and a
text extractor both run on the same blob: they cannot compete for the same MIME type in a single,
order-based selection. The descriptor, the MIME type matching and the `enabled` switch are
identical to `extractors`.

```xml
<extension target="org.nuxeo.audit.advanced.blob.BlobDiffComponent" point="imageExtractors">
  <extractor name="imagePdf" enabled="false"
             class="org.nuxeo.audit.advanced.blob.image.ImageInventoryExtractor">
    <mimeTypes>
      <mimeType>application/pdf</mimeType>
    </mimeTypes>
  </extractor>
</extension>
```

`ImageInventoryExtractor` is contributed once per format (`imageWord`, `imageSpreadsheet`,
`imagePresentation`, `imagePdf`), so a single format can be turned off — the above disables the PDF
inventory and leaves the others untouched. These extractors only run when `imageAnalysisLevel > 0`,
and they are bounded by the same `maxLines` as the text.

### Reading the Binaries

Both binaries are materialised on the local filesystem **once per diff**, and every extraction pass
reads the local copy. On an S3-backed blob provider this is one download per version, whatever the
number of passes. A blob already backed by a file is used as is and never copied.

## Algorithm: Hirschberg + Prefix/Suffix Trimming

Positional alignment relies on **Hirschberg's algorithm** instead of the traditional LCS table.

A classic `int[n+1][m+1]` matrix costs approximately `4 × n × m` bytes, or about **92 MB** for 4,900 lines, **per concurrent worker**. Hirschberg produces the **same optimal alignment** while requiring only O(min(n,m)) memory.

Hirschberg fixes memory, not time, which stays O(n×m) **on the sequences it is given**. So before aligning anything, the diff strips the longest common prefix and the longest common suffix: only the part that actually differs reaches the alignment. Identical lines produce no diff entry, so the result is unchanged — and since a shared first line always belongs to some optimal alignment, optimality is preserved too.

The alignment then runs over interned line identifiers, so the `n×m` inner loop compares integers rather than calling `String.equals` on paragraphs that usually share a long common prefix.

Measured results (JDK 17, one modified paragraph in a document of N paragraphs):

| Lines | Before | After |
|--------|--------|--------|
| 5,000 | 139 ms | **4.7 ms** |
| 10,000 | 336 ms | **2.4 ms** |
| 20,000 | 1.45 s | **2.4 ms** |

The cost stops growing with the size of the document: what drives it is the size of the change, not the size of the file.

The worst case — a document rewritten from end to end — shares nothing, trims nothing and stays quadratic, though the integer comparison still helps:

| Lines | Before | After |
|--------|--------|--------|
| 10,000 | 229 ms | 138 ms |
| 20,000 | 891 ms | 339 ms |

This is why `maxLines` still matters, but it now bounds only the pathological case.

## Data Model

Document type: `BlobDiff`

Facets: `HiddenInNavigation`, `NotCollectionMember`, `NotFulltextIndexable`

| Field | Purpose |
|--------|--------|
| bdiff:sourceId, bdiff:sourceRepository | Source document |
| bdiff:xpath | Which blob changed |
| bdiff:correlationId | Link to the audit entry |
| bdiff:user, bdiff:date | Who and when |
| bdiff:oldDigest, bdiff:newDigest | Fast path and proof |
| bdiff:summary, added/removed/changed, truncated | Short, indexable summary. `bdiff:summary` is a plain **English** sentence by design (see note below); the counters next to it are numbers and translate freely. |
| bdiff:status | ok, skippedUnsupportedType, skippedTooLarge, error (see the note above on how the two skip statuses are reached) |
| bdiff:diff | **Blob containing the full diff** |
| bdiff:oldBlobProvider, oldBlobKey, oldMimeType, oldLength, newBlobProvider, newBlobKey, newLength | Storage identity of both binaries (since 1.2), used to **retry** a diff in error |

> [!NOTE]
> `bdiff:summary` is deliberately **not** translated. It is written once, when the diff is computed, and persisted as is — so translating it later would mean rewriting every existing `BlobDiff`, and composing it in the UI instead would mix already-stored English sentences with newly translated ones. A UI that wants a localised summary should build it from `bdiff:added`, `bdiff:removed`, `bdiff:changed` and `bdiff:truncated`, which are stored separately for exactly that reason. The `nuxeo-blobdiff-status` element already does this for its counters.

The diff is stored as a blob, not a string: it stays outside the SQL/Mongo record. The `NotFulltextIndexable` facet is what keeps it outside the full-text index too — without it the platform would run its binary text extraction on the diff and copy the extracted business content into the full-text index and into Elasticsearch, defeating the whole point of the restricted container.

### Container and Security

`/change-diff/YYYY/MM/DD/`, partitioned by date (server time zone). Diff documents are named `<sourceId>-<eventTime>-<correlationId>`, so a single version containing changes on several blob xpaths yields distinct documents.

- **Created at startup, always.** `BlobDiffRepositoryInit` creates `/change-diff` at every repository initialisation, even when the feature is disabled, so the restricted ACL exists before the first diff is written.
- **Self-healing ACL.** At each startup the local ACL of `/change-diff` is compared with the expected one and reset (with a WARN) if it differs: the auditors group gets `Read`, `Remove` and `RemoveChildren`, then inheritance is blocked. Dated sub-folders have no local ACL and inherit it.
- **Auditors group** is configurable, default `administrators`. Recommended: the configuration property `org.nuxeo.web.ui.blobaudit.auditorsGroup`, which is also exposed to Web UI (`Nuxeo.UI.config.blobaudit.auditorsGroup`) so the server and the UI share one setting. `<auditorsGroup>` in the `config` extension point still wins when set. Members of the `administrators` group always have access.
- **Concurrency.** Dated folders are created in the caller's session under an in-JVM lock. A residual cross-node race can only produce a renamed dated sibling (e.g. `14.1727…`), which still lives under the restricted root and inherits its ACL.
- **Deleted source.** If the source document no longer exists when `BlobDiffWork` runs, a `BlobDiff` with status `error` and summary `Source document no longer exists` is recorded, so the audit entry is never orphaned.

Writes are performed with a **system session**: users modifying the file have no permission on this container.

### Asynchronous Pipeline

The synchronous side must stay cheap, and the asynchronous side must be safe to run more than once. Four guarantees back this up.

**Dedicated work queue.** `BlobDiffWork` runs in the `blobDiff` category, bound to its own WorkManager queue (`blobdiff-workmanager-contrib.xml`, `maxThreads=2`). Without it the category would fall back to the shared `default` queue, where a burst of check-ins on large Office or PDF files would starve every other asynchronous work of the instance. Raise `maxThreads` only after measuring: each thread can hold a whole extracted document in memory.

**Deterministic work id.** The work id is derived from the business identity of the comparison — the version pair, or the digest pair when no version context is available — never from the random correlation id. This is what `Scheduling.IF_NOT_RUNNING_OR_SCHEDULED` deduplicates on, so redundant works on the same pair collapse into one.

**Idempotent work.** The WorkManager is *at-least-once*: a node crash, a redeployment or a retry can run the same work twice. Before creating anything, `BlobDiffWork` looks for an existing `BlobDiff` carrying the same `bdiff:correlationId` and returns early if it finds one. The diff being superseded by `BlobDiff.Retry` is excluded from that lookup, since it carries the same correlation id by design.

**Retries.** `getRetryCount()` returns 2. Transient failures are the common case here (an S3 read timing out, a converter momentarily unavailable, a concurrent update on the dated container), and retrying is only safe because of the idempotence guard above.

**Bounded version lookup.** On each version creation the listener fetches only the **two** most recent versions of the series, with a **privileged** session. Fetching them all made the cost grow quadratically over the life of a document; using the user session silently dropped versions the caller cannot read, so the "previous version" could be the wrong one. Nothing leaks: only ids, labels and blobs of the two versions are used, and the result lands in the restricted container.

### Page Providers

| Name | Backend | Use |
|---|---|---|
| `BLOB_DIFFS_ADMIN` | `SearchService` → **the configured search client** | Web UI search and document tab: filters (`bdsearch:*` named parameters of the `BlobDiffSearch` search document) and aggregates on status, format, field and user |
| `BLOB_DIFFS_FOR_DOCUMENT` | Core (NXQL), always the repository | Diffs of one document, synchronous and read-your-writes |

> [!IMPORTANT]
> The filter keys sent by the UI keep their **`bdsearch:` prefix** — `bdsearch:sourceId`, not `sourceId`. A prefixed key is resolved deterministically through the schema prefix registry; a bare key is resolved by iterating the schemas of the search document, where a generic name such as `user` can collide with another schema. Note also that the search document type is declared with `<searchDocumentType>`, a **sibling** of `<whereClause>` — `whereClause@docType` is not part of the descriptor and XMap drops it silently.

> [!NOTE]
> `BLOB_DIFFS_ADMIN` relies on `SearchServicePageProvider`, which lives in `nuxeo-platform-query-api`, **not** in the Elasticsearch bundle — so its component always registers. Adding a `<require>` on `org.nuxeo.elasticsearch.ElasticSearchComponent` would be harmful rather than missing: it would keep the whole contribution — `BLOB_DIFFS_FOR_DOCUMENT` included — pending on any instance without a search engine, and in the tests. What does need a search engine is the *execution*: `SearchService` is resolved at query time. See "Search Engine Prerequisite" below for what happens without one.

## Web UI

Available to members of `administrators` and of the auditors group only.

- **"Content changes audit" search**, reached from its own drawer entry. It is a *named search*, built exactly like the platform ones: the **search form sits in the drawer on the left**, the **results fill the main area on the right**. Filters: date range, source document (`nuxeo-document-suggestion`), user, truncated only. Facets: status, format, field, user. Clicking the drawer icon opens the form and brings the results to the front in one go.

  The results table is a standard `nuxeo-results`, so it comes with the platform sort selector, the selection toolbar, CSV export, and **per-user column preferences** (show/hide, resize, reorder) persisted in local storage. Row actions: open, go to the source, filter on the source, download the diff, retry (errors only). Selection action: **permanent deletion**. Toolbar action: **purge** before a date, optionally for one status.

  Saved searches work as they do everywhere else: an auditor can store a set of filters from the drawer header and share it.
- **"Content changes" tab** on documents holding files (`file` or `files` schema), with one row per version transition rather than per ordinary save. "Open in the content changes audit" switches to the search with the source pre-filtered.
- **`BlobDiff` document view** (`document/blobdiff/nuxeo-blobdiff-view-layout.html`): source, field, user, date, status and counters, files and digests, colored rendering of the diff (`+` / `-` / `~`, `# Images` section, progressive display by 1 000 lines), and the same actions. `nuxeo-blobdiff-metadata-layout.html` is empty on purpose: Web UI loads it and would otherwise get a 404.

Deletion is **permanent** (no trash): both dialogs show a warning and require an explicit acknowledgment.

Hiding the UI is cosmetic. The protection is the ACL of `/change-diff` and the server checks of the operations.

> [!NOTE]
> A user who is **not** an auditor and navigates to `#!/search/blobdiffs` by hand gets an empty results pane rather than an explicit refusal: the drawer form is filtered out, so Web UI has no search to bind and stamps nothing. The server-side checks are what actually deny access.

### Server Side

| Item | Purpose |
|---|---|
| `BlobDiff.Delete` | Permanently deletes the input BlobDiff documents (other types refused) |
| `BlobDiff.Purge(before, status?)` | **Asynchronous.** Schedules the permanent deletion of diffs dated before a day on the Bulk Action Framework. Returns `{"commandId": "...", "state": "SCHEDULED"}` |
| `BlobDiff.PurgeStatus(commandId)` | Progress of a purge: `{state, deleted, processed, total, errorCount, skipCount, queryLimitReached}` |
| `BlobDiff.PurgeAbort(commandId)` | Stops a running purge. Already deleted diffs are not restored |
| `BlobDiff.CleanEmptyFolders` | Removes the dated folders left empty under `/change-diff`. Returns `{"folders": n}` |
| `BlobDiff.Retry` | Replays a diff in `error` from its stored binary keys. The new diff keeps user, date and correlation id, and replaces the failed one once created |
| `blobDiffSource` enricher | `{uid, exists, trashed, readable, title, path, type}` of the source; title/path/type only when the current user can read it |

Every operation is refused (`DocumentSecurityException`) to anyone who is not an administrator, a member of `administrators` or of the auditors group.

### The Purge Is Asynchronous

`BlobDiff.Purge` used to delete by batches of 100 in an unbounded loop, committing between batches, **inside the HTTP transaction** — an HTTP timeout was guaranteed at volume. It now submits a `blobDiffPurge` bulk command and returns immediately.

The full sequence, which the Web UI performs for you:

1. `BlobDiff.Purge(before, status?)` → `commandId`;
2. poll `BlobDiff.PurgeStatus(commandId)` until `state` is `COMPLETED` (or `ABORTED`);
3. `BlobDiff.CleanEmptyFolders` — the dated folders can only be removed once the command has emptied them.

Notes:

- **Breaking change.** The previous synchronous payload was `{"deleted": n, "folders": m}`. A script that purges on a schedule must be adapted to the three-step sequence above.
- `deleted` is the exact number of removed documents, published by the action; `processed` counts the ids handed to the computation, which differs as soon as the query matched something the purge refuses to touch.
- **One purge at a time per repository.** The action is `exclusive`: a second submission is refused with *"A BlobDiff purge is already running on this repository"*. The underlying lock has a TTL, so a command lost mid-flight does not freeze the feature.
- The command is submitted **under the calling user**, so the deletion stays subject to the ACL of `/change-diff`.
- **The `blobDiffPurge` action is deliberately not `httpEnabled`.** It is unreachable through `Bulk.RunAction` and through the `/search/bulk/{action}` REST endpoint for anyone but an administrator. `BlobDiff.Purge` is the only supported entry point: it enforces the auditors check and builds the query itself. Turning `httpEnabled` on would publish a delete-by-NXQL API to every authenticated user.
- The action only ever removes documents of type `BlobDiff`, whatever the query it is given.

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
├── search/blobdiffs/                             (resolved at runtime by filename, see below)
│   ├── nuxeo-blobdiffs-search-form.html          (left pane, stamped in the drawer)
│   └── nuxeo-blobdiffs-search-results.html       (right pane, stamped in the main area)
└── nuxeo-advanced-document-blob-audit/
    ├── nuxeo-advanced-document-blob-audit.html   (slots: DRAWER_ITEMS, DRAWER_PAGES, DOCUMENT_VIEWS_*)
    └── elements/                                 (tab, viewer, dialogs, badges, source link)
```

> [!IMPORTANT]
> The two files under `search/blobdiffs/` are **not** imported by the bundle. Web UI resolves them at runtime from a hardcoded convention — `<hrefBase><searchName>/nuxeo-<searchName>-search-form.html`, lowercased, `hrefBase` falling back to `/ui/search/` on a server — and `nuxeo-layout` derives the custom element name from the file name. The folder name, the two file names and the two `dom-module` ids must therefore stay in lockstep with `search-name="blobdiffs"`. Get one wrong and the pane renders *"Failed to find search layout for blobdiffs"* (or silently nothing), with no other diagnostic.

## Configuration

**Disabled by default.**

```xml
<extension target="org.nuxeo.audit.advanced.blob.BlobDiffComponent" point="config">
  <config enabled="true">
    <maxBlobSize>10485760</maxBlobSize>
    <maxLines>10000</maxLines>
    <maxDiffEntries>5000</maxDiffEntries>
    <maxDiffChars>4194304</maxDiffChars>
    <maxValueLength>4096</maxValueLength>
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

### Bounding the Size of a Diff

Four options bound the work, at two different stages, and they are not interchangeable:

| Option | Stage | Bounds |
|---|---|---|
| `maxBlobSize` | before extraction | the binary that may be opened at all |
| `maxLines` | extraction | how many units are extracted from each side |
| `maxDiffEntries` | reporting | how **many** differences are written to the diff body |
| `maxDiffChars` | reporting | the total **length** of the diff body, in characters |
| `maxValueLength` | reporting | the length of a **single** rendered unit, beyond which it is elided |

`maxDiffEntries` has never bounded anything but the count. An Excel cell accepts 32 767 characters and a Word paragraph is unbounded, so 5 000 entries of worst-case cells render to roughly 320 MB — accumulated in a single `StringBuilder`, copied by `toString()`, copied again into an in-memory blob, then encoded to bytes by the blob provider. `maxDiffChars` and `maxValueLength` are what make the stored diff proportional to something known in advance rather than to the content.

When either cap is reached, the counters `bdiff:added`, `bdiff:removed` and `bdiff:changed` stay **exact** — counting continues past the budget — and `bdiff:truncated` is set. Only the rendering is shortened, never the accounting.

> [!NOTE]
> When both sides of a modification are longer than `maxValueLength` and only differ past it, the rendered entry shows two identical prefixes (`~ key : X… -> X…`). The modification is still counted and flagged; only that one line of the report is uninformative.

### Search Engine Prerequisite

The **Content changes audit** search and the document history tab are backed by `BLOB_DIFFS_ADMIN`, which goes through the LTS 2025 `SearchService`. Where the query lands depends on the deployed search client:

- with the OpenSearch (or Elasticsearch) search-client package installed, the query **and its four aggregates** — status, MIME type, xpath, user — run on the engine;
- on a bare instance, the default client is `repository` (`nuxeo.search.client.default.name`), which does not support the `AGGREGATE` capability. The listing still works, but the facet panel comes back **silently empty**: the platform reports a `SearchLimitation` on the response rather than raising, and the page provider does not surface it.

A search engine is therefore a practical prerequisite for the facet panel of the search form. No mapping or configuration template has to be deployed for it: the default index mapping maps every string property to a `keyword` through a dynamic template, so `bdiff:*` is queryable and aggregatable as soon as the documents are indexed.

### Repository Indexes

`bdiff:correlationId` and `bdiff:sourceId` are declared with `indexOrder="ascending"`. Both are the sole predicate of a query that would otherwise scan every `BlobDiff` of the instance — the idempotency guard of `BlobDiffWork`, and the `BLOB_DIFFS_FOR_DOCUMENT` core query page provider.

> [!IMPORTANT]
> `indexOrder` is a **MongoDB** mechanism. The platform's only consumer of it is `MongoDBIndexCreator`, which builds the indexes at repository initialisation, idempotently, over existing data. On **VCS (SQL)** it has no effect at all: the equivalent indexes would have to be created by hand. It has no effect on Elasticsearch/OpenSearch either — there is no per-field opt-in there, the index mapping decides.

### Disabling the Listener

The `<config enabled="...">` switch above is the global, static one: it requires a redeployment. For targeted, runtime control, the plugin follows the platform convention (`DublinCoreListener.DISABLE_DUBLINCORE_LISTENER`, `CoreSession.DISABLE_AUDIT_LOGGER`, `VersioningService.DISABLE_AUTO_CHECKOUT`) and offers two complementary mechanisms.

**1. Per-operation, through the document context data**

```java
import static org.nuxeo.audit.advanced.blob.BlobModificationListener.DISABLE_BLOB_DIFF_LISTENER;

doc.putContextData(DISABLE_BLOB_DIFF_LISTENER, Boolean.TRUE);
doc.putContextData(VersioningService.VERSIONING_OPTION, VersioningOption.MINOR);
session.saveDocument(doc);   // version created, no audit entry and no BlobDiff
```

`CoreSession#saveDocument` copies the document context data into the event options, which are then passed to both `aboutToCheckIn` and `documentCheckedIn` — the two events the listener reads. The same key also works when you fire the event yourself and set it as an event property.

> [!IMPORTANT]
> This does **not** work with `session.checkIn(docRef, option, comment)`. That method builds a *fresh, empty* option map, so context data set on the document is never propagated on that path. Use the thread-scoped switch below when you check in explicitly.

**2. Thread-scoped, for migrations, importers and explicit check-ins**

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

The listener is named `blobModificationListener`, so the standard platform administration API applies:

```java
Framework.getService(EventServiceAdmin.class).setListenerEnabledFlag("blobModificationListener", false);
```

This is global and survives until it is set back or the node restarts. Prefer mechanisms 1 and 2 for anything scoped.

When the listener is disabled by any of these means, **nothing** happens: no `blobContentModified` audit entry and no `BlobDiffWork`. The version itself is created normally.

#### Image Analysis Level

`imageAnalysisLevel` is optional and defaults to `0` when omitted.

- `0`: no image analysis. This preserves the text/cell-only behavior and adds no image-processing cost.
- `1`: digest-based image inventory, reporting image additions, removals, and replacements. It is produced by the extractors contributed to the `imageExtractors` point (see above), which are selectable and disableable per MIME type.

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
- **Truncation.** Beyond `maxLines` (extraction), or `maxDiffEntries` / `maxDiffChars` / `maxValueLength` (reporting), the diff is partial and flagged `truncated`. The counters stay exact in every case.

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
- **Bounded by `maxLines`**, like the text: past that many images the inventory stops and the diff is flagged `truncated`.
- Image extraction failure silently falls back to the text-only result (logged as WARN). A format with no contributed image extractor simply gets no inventory.

### Web UI
- **Navigating directly from one `BlobDiff` to another leaves the page showing the previous diff.** The URL, the browser title and the Web UI header all update, but the content card does not; a page reload shows the right one. Reachable from the drawer queue and from any diff-to-diff link. Under investigation — the document never reaches the plugin's view layout, so the cause is upstream of this plugin. Browsing to a diff from anywhere else is unaffected.
- **Opening a `BlobDiff` logs a 404** for `nuxeo-blobdiff-edit-layout.html`: Web UI offers an Edit action and a diff deliberately has no edit layout. Cosmetic.

### Security and Operations
- **Business content is copied** into `BlobDiff` documents protected by different ACLs than the source; review this for regulated environments.
- **No scheduled retention.** Purge is manual (Web UI or `BlobDiff.Purge`); schedule it yourself for automatic retention, following the three-step asynchronous sequence described above.
- **A search engine is a practical prerequisite** for the Web UI search and tab (`BLOB_DIFFS_ADMIN`). Without one the results still come back, but the facet panel of the search form is silently empty — see "Search Engine Prerequisite" above.
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
| TestBlobDiffManagement | Yes | Binary keys, Delete / Retry operations and their access control, blobDiffSource enricher |
| TestBlobDiffPurgeAction | Yes | Asynchronous purge on the Bulk Action Framework: date and status filtering, exact counts, several buckets, type safety, exclusivity, abort, empty folder cleanup, access control |
| TestBlobDiffHardening | Yes | Full-text exclusion, dedicated work queue, deterministic work id, work idempotence, bounded version lookup |
| TestBlobDiffListenerDisabling | Yes | Per-operation context data flag, thread-scoped `runDisabled`, no leak between operations |
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
