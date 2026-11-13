# Blob audit — manual test runbook

Manual end-user test of `nuxeo-advanced-document-blob-audit` on a local Docker Compose deployment:
Nuxeo LTS 2025 + MongoDB + OpenSearch, Web UI.

Everything lives in `test-campaign/`, at the root of the repository:

| Path | What |
|---|---|
| `S01-…` to `S28-…` | the test files, one folder per scenario, `xxx-00` is the first upload, `-01`, `-02`, `-03` the next ones |
| `nuxeo-config/blobaudit-runbook-config.xml` | the configuration to deploy (feature on, `File` only, every blob xpath, image inventory, `auditors` group) |
| `MANIFEST.tsv` | every file with its size, SHA-256 and the mime type a browser sends |
| `_generator/` | the Python scripts that produced the files (to regenerate or tweak them), and `make-large-files.sh` |
| `runbook-by-agents.md` | the same campaign, scripted over the REST API for an agent |
| `agent/blobaudit-helpers.sh` | the bash functions (curl, jq) the agent runbook uses |
| `results/` | what an agent run writes (log, raw responses, report); ignored by git |

The two S16 files over 10 MB (`too-large-00.txt`, `too-large-01.txt`) are not in git. Before S16, run
once, from the root of the repository:

```bash
bash test-campaign/_generator/make-large-files.sh
```

It writes them and checks their SHA-256 against `MANIFEST.tsv` (about a second). The agent helpers run
it on their own.

Allow about 2 h for everything; the core formats (part A) take 30 min.

---

## 1. Setup (once)

### 1.1 Deploy the plugin and its configuration

1. Build the package: `mvn clean install` at the root of the repository. The package
   is `nuxeo-advanced-document-blob-audit-package/target/nuxeo-advanced-document-blob-audit-2025.1.0-SNAPSHOT.zip`.
2. Install it in the Nuxeo container the way your compose file installs local packages (for example
   `nuxeoctl mp-install /path/in/container/nuxeo-advanced-document-blob-audit-2025.1.0-SNAPSHOT.zip`).
3. Mount the configuration so that it ends in `nxserver/config` (the file name must end with
   `-config.xml`):

   ```yaml
   services:
     nuxeo:
       volumes:
         - /path/to/the/repository/test-campaign/nuxeo-config/blobaudit-runbook-config.xml:/opt/nuxeo/server/nxserver/config/blobaudit-runbook-config.xml:ro
   ```

4. Restart Nuxeo. In `server.log`, expect no `ERROR` from `org.nuxeo.audit.advanced.blob`. A `WARN`
   saying the ACL of `/change-diff` was reset is normal on the first start with the `auditors` group.
   As `Administrator`, open `/nuxeo/ui/config.jsp`: it must contain
   `"blobaudit":{"auditorsGroup":"auditors"}`. If it shows `administrators`, or no `blobaudit` at all,
   the configuration is not in effect (file not in `nxserver/config`, or no restart since), and
   carol will see nothing. The configuration needs no other `<require>` than `BlobDiffComponent`
   from plugin item 105 on; never add one on `org.nuxeo.audit.advanced.blob.properties`, a component
   that no longer exists: the configuration would stay pending, the feature off.
5. Check that the search client is OpenSearch (the facets of the audit page need it).

### 1.2 Users and groups

Create them as `Administrator` in **Administration > Users & Groups**. Use any local test password.

| User | Groups | Role in the test | What they must see |
|---|---|---|---|
| `alice` | `members` | author: creates documents, replaces files, creates versions | the documents, the **History** tab entries "Binary content modified"; **no** "Content changes audit" drawer entry, **no** "Content changes" tab, no access to any diff |
| `bob` | `members` | second author: S24 (automatic version), second value of the "User" facet | same as alice |
| `carol` | `members`, **`auditors`** | auditor: reads the diffs, retries, deletes, purges | the drawer entry, the tab, every diff; **not** the documents of the `Restricted` folder |
| `Administrator` | `administrators` | setup only; always an auditor | everything |

Create the group **`auditors`** (label "Blob diff auditors") with `carol` as its only member. It is the
group named in the configuration (`org.nuxeo.web.ui.blobaudit.auditorsGroup`).

### 1.3 Containers and permissions

The default domain grants `Read` to `members`: block the inheritance so that the permissions below
are the only ones.

| Container | Path | Inheritance | Permissions |
|---|---|---|---|
| Workspace **Blob Audit Tests** | `/default-domain/workspaces/blob-audit-tests` | blocked | `alice` **ReadWrite**, `bob` **ReadWrite**, `carol` **Read** |
| Folder **Restricted** (inside the workspace) | `…/blob-audit-tests/restricted` | blocked | `alice` **ReadWrite** only (carol has nothing) |

Why these rights: `ReadWrite` includes `WriteVersion` (needed to create a version) and `Remove`
(trash, S27). `carol` needs no write right anywhere: the diffs live under `/change-diff`, whose ACL the
plugin manages (auditors group: `Read`, `Remove`, `RemoveChildren`). `Read` on the workspace lets her
follow the "source document" links; nothing in `Restricted` lets S26 check the "Source not accessible
to you" badge.

---

## 2. The basic loop

Every scenario repeats the same loop, as **alice** unless stated otherwise, in the workspace:

1. **Create** > **File**, title = the scenario name (for example `S01 Contract`), upload the `-00`
   file, **Create**.
2. Under the title, click **CREATE VERSION** > **Minor version** > **Create Version**: version
   `0.1`. Nothing is audited for a first version.
3. On the main file, use the **Replace** action (upload icon of the file) and pick the `-01` file. The
   version tag shows `0.1+`.
4. Click the version tag (`VERSION 0.1+`) > **Create Version** > **Minor version**: version `0.2`.
5. Check the result (§ 2.1). Repeat 3–5 with `-02`, `-03`.

The audit entry is written in the same transaction; the diff is computed **asynchronously** after
the commit. Wait 5–10 s and refresh before concluding that something is missing.

### 2.1 Where to look

| As | Where | What |
|---|---|---|
| alice | the document > **History** tab | one "Binary content modified" entry per changed file, comment `file:content : <outcome>`; no file content in it |
| carol | the document > **Content changes** tab | one row per version transition, with status and counters |
| carol | left drawer > **Content changes audit** | every diff; facets Status / Format / Field / User; filters date range, source document, user, truncated only |
| carol | a `BlobDiff` (click a row) | source, field, user, date, status, counters, similarity, files and digests, the colored diff (`+` / `-` / `~`, `# Images`), 1 000 lines at a time |

Audit comment outcomes:

| Outcome in the comment | Meaning | `BlobDiff` |
|---|---|---|
| `binary content modified between versions` | a diff was scheduled | yes, after a few seconds |
| `binary changed (too large)` | `skippedTooLarge` (over 10 MB) | no |
| `binary changed (unsupported format)` | `skippedUnsupportedType` | no |
| `binary changed (format changed)` | `skippedFormatChanged` (two extractor contributions) | no |
| `binary changed (list entries also removed or moved, previous file unknown)` | `skippedListChanged` | no |

How a diff body reads: a positional change (Word, PDF, PowerPoint, text) is a `- old` line followed
by a `+ new` line and counts as **one modification**; a keyed change (Excel, images) is one line
`~ Sheet!B2 : old -> new`. A line break inside a value is shown as a literal `\n`.

---

## 3. Scenarios

"ok, 1 modification, 1 addition" means: audit outcome `binary content modified between versions`,
`BlobDiff` status **OK**, summary "1 modification, 1 addition". The image lines need the image
inventory, which the runbook configuration turns on.

### Part A — core formats

**S01 — Word, paragraph by paragraph** (`S01-word-contract/contract-*.docx`, document `S01 Contract`)

| Upload | Version | Expected |
|---|---|---|
| `contract-00.docx` | 0.1 | nothing |
| `contract-01.docx` | 0.2 | ok, **1 modification, 1 addition**: `30 days` → `45 days`; `+ The notice must be sent by registered letter.` |
| `contract-02.docx` | 0.3 | ok, **1 modification, 2 deletions**: `- 4. Liability`, `- The liability of the provider…`; table cell `2026-01-16` → `2026-02-01` |
| `contract-03.docx` | 0.4 | ok, **"No textual change detected"**, no diff body: only a word put in bold and a page header added. Formatting and headers are not compared, but the audit entry is there (the bytes changed) |

**S02 — Word with images** (`S02-word-images/word-images-*.docx`)

| Upload | Version | Expected |
|---|---|---|
| `-00` (blue image A) | 0.1 | nothing |
| `-01` (image A replaced by red image B) | 0.2 | ok, **1 modification**, body only under `# Images`: `~ word:image:image1.png : sha256:… -> sha256:…` |
| `-02` (green JPEG C added, caption added) | 0.3 | ok, **2 additions**: `+ Figure 2: detail view.` and `+ word:image:image2.jpg = sha256:…` |
| `-03` (image B removed) | 0.4 | ok, **1 addition, 2 deletions**, all under `# Images`: `- …image1.png`, `- …image2.jpg`, `+ …image1.jpg`. The remaining picture was renamed when the file was rewritten (Word does the same): check that `image1.jpg` carries the digest the old `image2.jpg` had |

**S03 — Excel, cell by cell** (`S03-excel-budget/budget-*.xlsx`)

| Upload | Version | Expected |
|---|---|---|
| `-00` | 0.1 | nothing |
| `-01` | 0.2 | ok, **3 modifications**: `~ Budget!C3 : 1200 -> 1500`, `~ Budget!E5 : 300 -> 350`, `~ Notes!B2 : Figures approved\nby the steering committee -> Figures approved\nby the board` (a multi-line cell stays **one** line, `\n` literal) |
| `-02` (row 7 "Travel" added, totals extended) | 0.3 | ok, **5 modifications, 6 additions**: `+ Budget!A7 = Travel` … `+ Budget!F7 = =SUM(B7:E7)`; `~ Budget!B8 : =SUM(B2:B6) -> =SUM(B2:B7)` … `F8`. Formulas are compared as formulas, so the totals' new values are not reported |
| `-03` (sheet `Notes` renamed `Remarks`) | 0.4 | ok, **6 additions, 6 deletions**: every cell of the sheet removed and added (documented limitation) |

**S04 — Excel with images** (`S04-excel-images/excel-images-*.xlsx`)

| Upload | Version | Expected |
|---|---|---|
| `-00` (image A on `B2`) | 0.1 | nothing |
| `-01` (same image moved to `E10`) | 0.2 | ok, **1 addition, 1 deletion**: `- excel:image:Pictures:B2`, `+ excel:image:Pictures:E10`, same sha256 |
| `-02` (image replaced by B) | 0.3 | ok, **1 modification**: `~ excel:image:Pictures:E10` |
| `-03` (image C added on `H2`, title edited) | 0.4 | ok, **1 modification, 1 addition**: `~ Pictures!A1 : Product photo -> Product photos`, `+ excel:image:Pictures:H2` |

**S05 — PowerPoint, slide by slide** (`S05-powerpoint-deck/deck-*.pptx`)

| Upload | Version | Expected |
|---|---|---|
| `-00` (4 slides: title, bullets + notes, table, bullets) | 0.1 | nothing |
| `-01` | 0.2 | ok, **3 modifications**: `Revenue up 12 percent` → `14 percent`; `[notes] Mention the new customer in Lyon.` → `…customers in Lyon and Nantes.`; `[table] South \| 95 \| 110` → `118` |
| `-02` (a bullet removed, slide 5 added) | 0.3 | ok, **3 additions, 1 modification**: `- Churn stable at 3 percent`; `+ ## Slide: Risks`, `+ Risks`, `+ Currency exposure`, `+ Supplier delays`. The removal, immediately followed by the heading of the new slide, is counted as a modification (§ 5) |
| `-03` (soft line break edited, image added on slide 3) | 0.4 | ok, **1 modification, 1 addition**: `Open the Madrid office\nin January` → `…\nin February`; under `# Images`: `+ powerpoint:slide:3:image:…image1.png` |

**S06 — PDF, line by line as rendered** (`S06-pdf-report/report-*.pdf`, two pages, one image per page)

| Upload | Version | Expected |
|---|---|---|
| `-00` | 0.1 | nothing |
| `-01` | 0.2 | ok, **1 modification, 1 addition**: `Incidents closed: 12` → `13`; `+ Repaint the emergency exits.`; no image line (same images) |
| `-02` (page 1 image replaced) | 0.3 | ok, **1 modification**: `~ pdf:page:1:image:1` |
| `-03` (a page inserted in position 2) | 0.4 | ok, **4 additions, 1 deletion**: `+ Section 1b - Budget`, `+ Budget allocated: 25000 EUR`, `+ Budget spent: 21300 EUR`; images `- pdf:page:2:image:1`, `+ pdf:page:3:image:1`, same sha256 (inserting a page shifts the image keys: documented limitation) |

### Part B — text family

**S07 — plain text** (`S07-text-notes/notes-*.txt`)

| Upload | Version | Expected |
|---|---|---|
| `-00` | 0.1 | nothing |
| `-01` | 0.2 | ok, **2 modifications, 1 addition** (attendees, engineering action; `+ Action: sales lists the pilot customers`) |
| `-02` (same text: CRLF, indentation, trailing spaces, blank lines) | 0.3 | ok, **"No textual change detected"**, audit entry present (`trimLines` and `skipBlankLines`) |
| `-03` (UTF-8 BOM added, one line removed) | 0.4 | ok, **1 deletion**: `- Action: support writes the FAQ`. The first line must **not** be reported: the BOM is ignored |

Optional, version pairing: open the version tag, select `0.1`, **Restore**; then change the
description, save and **Create Version**. Expected: the diff compares the **last version created**
(`0.4`, previous version label in the view) with the new one, whatever label the new version gets
(labels may repeat after a restore). Its body is S07 backwards: the attendees and engineering lines
back to their `-00` text, the FAQ line back, the sales line gone (those two adjacent lines may be
counted as one modification).

**S08 — JSON replaced by its XML equivalent** (`S08-json-replaced-by-xml/`)

| Upload | Version | Expected |
|---|---|---|
| `config-00.json` | 0.1 | nothing |
| `config-01.json` | 0.2 | ok, **2 modifications** (`"pool": 10,` → `20,`; `"logLevel": "INFO"` → `"DEBUG"`) |
| `config-02.xml` | 0.3 | audit outcome nominal (with a correlation id), `BlobDiff` status **Content replaced** (`skippedContentReplaced`): counters and similarity (≈ 0 %) stored, **no body**, the view explains the file was replaced; badge **Format: json → xml** |
| `config-03.xml` | 0.4 | ok, **1 modification**: `<port>8080</port>` → `<port>9090</port>` |

**S09 — a .txt saved as .csv** (`S09-txt-to-csv/`)

| Upload | Version | Expected |
|---|---|---|
| `data-00.txt` | 0.1 | nothing |
| `data-01.csv` | 0.2 | ok, **1 modification** (`Madrid,600` → `Madrid,650`), similarity ≈ **88 %**, Format badge (text/plain → text/csv). Not "Content replaced": the similarity is above 10 % |

**S10 — Markdown and YAML** (`S10-markdown-and-yaml/`, two documents `S10 Readme` and `S10 Pipeline`)

| Upload | Version | Expected |
|---|---|---|
| `readme-00.md` then `readme-01.md` | 0.1, 0.2 | ok, **1 modification, 1 addition** (`## Version 3.2` → `3.3`, `+ - Dark mode`) |
| `pipeline-00.yaml` then `pipeline-01.yaml` | 0.1, 0.2 | ok, **1 modification, 1 addition** (`environment: staging` → `production`, `+ - security-scan`) |

The plugin reads `.yaml` as text whether the browser sends `application/yaml` (the type registered by
RFC 9512), `application/x-yaml` or a `text/…` type (plugin item 107). If the YAML step ends as
`binary changed (unsupported format)`, look at `newMimeType` in the audit entry: that is what your
browser sent; note it as a finding.

### Part C — formats replacing one another

**S11 — the Word family** (`S11-word-family/`, one letter in four formats)

| Upload | Version | Expected |
|---|---|---|
| `letter-00.docx` | 0.1 | nothing |
| `letter-01.odt` | 0.2 | ok, **1 modification** (`November 15` → `November 22`), badge docx → odt |
| `letter-02.doc` | 0.3 | ok, **1 addition** (`A tracking number will follow.`), badge odt → doc |
| `letter-03.rtf` | 0.4 | ok, **1 modification** (`Jane Doe` → `John Roe`), badge doc → rtf |

The Nuxeo mimetype registry types `.rtf` as `text/rtf` (some browsers send `application/rtf`): the
`converter` contribution lists both (plugin item 106). A `binary changed (format changed)` with
`newMimeType` `text/rtf` means a plugin older than item 106, where `plainText` (`text/*`) took it:
a finding for the plugin, not for the runbook.

**S12 — .xls replaced by .xlsx** (`S12-xls-to-xlsx/`)

| Upload | Version | Expected |
|---|---|---|
| `legacy-sales-00.xls` | 0.1 | nothing |
| `legacy-sales-01.xlsx` | 0.2 | ok, **1 modification**: `~ Sales!B3 : 95 -> 110`, badge xls → xlsx (one contribution reads both) |

**S13 — format changes** (`S13-format-change/`)

| Upload | Version | Expected |
|---|---|---|
| `format-change-00.docx` | 0.1 | nothing |
| `format-change-01.pdf` (same memo as PDF) | 0.2 | `binary changed (format changed)`, `skipReason=skippedFormatChanged`, **no** `BlobDiff` |
| `format-change-02.xlsx` | 0.3 | same: format changed, no `BlobDiff` |
| `format-change-03.xlsx` | 0.4 | ok, **1 modification**: `~ Memo!A3 : …December 1. -> …December 8.` |

**S14 — legacy PowerPoint** (`S14-legacy-ppt/`)

| Upload | Version | Expected |
|---|---|---|
| `legacy-deck-00.ppt` | 0.1 | nothing |
| `legacy-deck-01.ppt` | 0.2 | ok, at least **1 modification**: `Week 2: customer cases` → `Week 2: customer cases and demos` (flat text through `any2text`; the exact lines depend on the converter) |
| `legacy-deck-02.pptx` | 0.3 | `binary changed (format changed)`: `legacyPresentation` → `presentation`, no `BlobDiff` |

### Part D — limits, skips and failures

**S15 — an Excel cell at the maximum size** (`S15-excel-big-cell/`, cell `Big!B1` holds 32 767 characters)

| Upload | Version | Expected |
|---|---|---|
| `big-cell-00.xlsx` | 0.1 | nothing |
| `big-cell-01.xlsx` (only the end of the cell differs) | 0.2 | ok, **1 modification** `~ Big!B1 : This cell holds… [32767 chars, sha256:…] -> This cell holds… [32767 chars, sha256:…]`: each side is cut to its beginning (about 4 000 characters, `maxValueLength`) plus its length and a short digest; the two digests **differ** although the beginnings are equal |

**S16 — over `maxBlobSize`** (`S16-too-large/`, `-00` and `-01` are 11 MB, written by
`_generator/make-large-files.sh`: see the top of this runbook)

| Upload | Version | Expected |
|---|---|---|
| `too-large-00.txt` | 0.1 | nothing |
| `too-large-01.txt` | 0.2 | `binary changed (too large)`, `skipReason=skippedTooLarge`, no `BlobDiff` |
| `too-large-02.txt` (6 KB) | 0.3 | **still** `binary changed (too large)`: the previous version is over the limit |
| `too-large-03.txt` | 0.4 | ok, **1 modification** (`Record 0000010 … status nominal` → `status ALERT`) |

**S17 — unsupported format** (`S17-unsupported-format/`, PNG files in a **File** document)

| Upload | Version | Expected |
|---|---|---|
| `picture-00.png` | 0.1 | nothing |
| `picture-01.png` | 0.2 | `binary changed (unsupported format)`, no `BlobDiff` |
| `picture-02.txt` | 0.3 | `binary changed (unsupported format)`, not "format changed": an unsupported side wins |
| `picture-03.txt` | 0.4 | ok, **1 modification** (`Usage: catalog only` → `catalog and website`) |

**S18 — silent cases: nothing must be audited**

| Step | Expected |
|---|---|
| `S18-same-bytes-renamed/same-bytes-00.txt` → version 0.1, then **Replace** with `same-bytes-01.md` (identical bytes, other name and extension) → version 0.2 | **no** "Binary content modified" entry, no `BlobDiff` |
| then `same-bytes-02.md` → version 0.3 | ok, **1 addition** (`+ - sign the artifacts`) |
| on any File of the series: change only the title, save, **Create Version** | no new entry |
| **Create** > **File** with **no** file, version it, change the description, version it again | nothing |

**S19 — a corrupted file, error and retry** (`S19-broken-file/`, `broken-01.xlsx` is a truncated zip)

| Upload | Version | Expected |
|---|---|---|
| `broken-00.xlsx` | 0.1 | nothing |
| `broken-01.xlsx` | 0.2 | audit nominal; `BlobDiff` status **Error**, no body, both file names and digests shown, **Retry** available |
| (carol) **Retry** on that diff | — | "Retry scheduled"; a second Retry right away may answer that a replay is pending (409). After a few seconds the diff is **replaced** by a new `error` diff (new document, same correlation id): the file is still corrupted |
| `broken-02.xlsx` | 0.3 | `error` again: the previous version is the corrupted one |
| `broken-03.xlsx` | 0.4 | ok, **1 modification**: `~ Stock!B2 : 14 -> 16` |

**S20 — over `maxLines`** (`S20-truncated-lines/`, 12 000 lines)

| Upload | Version | Expected |
|---|---|---|
| `long-log-00.txt` then `long-log-01.txt` | 0.1, 0.2 | ok, **1 modification** (line 100, `INFO` → `WARN`), **Truncated**. The change on line 11 000 is **not** reported (past the 10 000 lines read) |

**S21 — over `maxDiffEntries`** (`S21-excel-mass-update/`, 6 000 cells all changed)

| Upload | Version | Expected |
|---|---|---|
| `mass-update-00.xlsx` then `mass-update-01.xlsx` | 0.1, 0.2 | ok, **6000 modifications** (exact counter), **Truncated**; the body holds 5 000 `~ Data!A… : n -> n+1` lines, shown 1 000 at a time ("Show more") |

**S22 — a text rewritten in the same format** (`S22-rewrite-same-format/`)

| Upload | Version | Expected |
|---|---|---|
| `rewrite-00.txt` then `rewrite-01.txt` | 0.1, 0.2 | ok, full body (both texts), similarity **0 %**, **not** "Content replaced": the mime type did not change. The counters mix modifications, additions and deletions (known coalescing behaviour) |

### Part E — attachments, users, permissions, lifecycle

**S23 — attachments (`files:files`)** (`S23-attachments/`, needs `<xpath>*</xpath>`, set by the runbook configuration)

| Step | Version | Expected |
|---|---|---|
| Create `S23 Attachments` with `main-00.txt` as main file; in **Attachments**, upload `attach-alpha-00.txt` then `attach-beta-00.txt` | 0.1 | nothing |
| On the **alpha** attachment, **Replace** with `attach-alpha-01.txt` | 0.2 | ok on field **`files:files/0/file`**, **1 modification** (`Amount: 12000 EUR` → `12500 EUR`) |
| Upload `attach-gamma-00.txt` as a third attachment | 0.3 | nothing: an added file is not audited |
| **Delete** the beta attachment **and** replace alpha with `attach-alpha-02.txt`, then version | 0.4 | `binary changed (list entries also removed or moved, previous file unknown)` (`skippedListChanged`), no `BlobDiff` |

**S24 — two authors, automatic version** (`S24-collaborative-save/`)

| Step | Expected |
|---|---|
| alice: create `S24 Collab` with `collab-00.txt`, version 0.1 | nothing |
| alice: **Replace** with `collab-01.txt`, **no** version | nothing (a save is never audited) |
| bob: **Replace** with `collab-02.txt` | the platform `collaborative-save` policy creates version **0.2** holding alice's file just before bob's save: ok, **1 modification** (`Step 2: …within one day`), "Version created by" **bob** (the check-in runs in his save) |
| bob: **Create Version** 0.3 | ok, **1 modification** (`Step 3: …and notify`), by bob |

**S25 — a document type out of scope** (`S25-out-of-scope-type/`)

| Step | Expected |
|---|---|
| **Create** > **Picture** `S25 Picture` with `scope-00.png`, version; **Replace** with `scope-01.png`, version | **nothing at all**: the configuration watches `File` only. Compare with S17, where a PNG in a File is audited as unsupported |

**S26 — a source the auditor cannot read** (`S26-restricted-source/`, in the `Restricted` folder)

| Step | Expected |
|---|---|
| alice: `S26 Board minutes` with `secret-00.txt`, version; `secret-01.txt`, version | ok, **1 modification** |
| carol: open the diff from **Content changes audit** | the diff and its body are readable (auditors read every diff, by design); the source shows **Source not accessible to you**; "Go to the source" does not open it |

**S27 — source trashed, then deleted** (`S27-source-lifecycle/`)

| Step | Expected |
|---|---|
| alice: `S27 Policy` with `lifecycle-00.txt`, version; `lifecycle-01.txt`, version | ok, **1 modification** |
| alice: move the document to the trash; carol: reload the diff | source badged **In trash** |
| alice: delete it permanently from the trash; carol: reload the diff | **Source deleted**; the diff is still there |

**S28 — another charset** (`S28-charset/`, `-00` UTF-8, `-01` ISO-8859-1, same text)

| Upload | Version | Expected |
|---|---|---|
| `charset-00.txt` then `charset-01.txt` | 0.1, 0.2 | ok, **2 modifications**: the two lines with accents come back with `�` (no charset detection, the blob is read as UTF-8: documented behaviour, worth knowing) |

### Part F — auditor screens and operations

Run them once the scenarios above have produced diffs.

| # | As | Action | Expected |
|---|---|---|---|
| F1 | alice | look at the left drawer, at a document's tabs, then type `#!/search/blobdiffs` in the URL | no "Content changes audit" entry, no "Content changes" tab, an empty pane |
| F2 | alice | open the URL of a `BlobDiff` (copied from carol's session) | no access |
| F3 | alice | **History** tab of `S01 Contract` | "Binary content modified" entries, comment `file:content : …`; file names and mime types only, never a word of the content |
| F4 | carol | **Content changes audit** | every diff; facets Status, Format, Field, User filled (OpenSearch); filter on a source document, on a user (`bob`), on a date range, "Truncated diffs only" (S20, S21) |
| F5 | carol | results table | sort, CSV export, column settings kept after a reload; row actions: open, go to the source, show only this document, download, retry (S19 only) |
| F6 | carol | open a `BlobDiff` | no **Edit** action; download gives a `text/plain` file; the "Format: x → y" badge on S08, S09, S11, S12 |
| F7 | carol | on `S01 Contract`, **Content changes** tab > **Open in the content changes audit** | the audit page filtered on that document only, earlier filters dropped |
| F8 | carol | select the S22 diff > **Delete permanently** | the dialog requires the acknowledgment; the diff is gone (no trash) |
| F9 | carol | toolbar **Purge**: before = **tomorrow**, status = **Error** | progress, then "n content diff(s) purged": the S19 error diffs only; the other diffs stay |
| F10 | carol | **Content changes** tab of `S23 Attachments`; **Field** facet of the audit page | the S23 row shows field `files:files/0/file`; the facet lists `file:content` and `files:files/0/file` |

---

## 4. Results checklist

| Scenario | Result | Notes |
|---|---|---|
| S01 Word | ☐ pass ☐ fail | |
| S02 Word images | ☐ pass ☐ fail | |
| S03 Excel | ☐ pass ☐ fail | |
| S04 Excel images | ☐ pass ☐ fail | |
| S05 PowerPoint | ☐ pass ☐ fail | |
| S06 PDF | ☐ pass ☐ fail | |
| S07 Text | ☐ pass ☐ fail | |
| S08 JSON → XML | ☐ pass ☐ fail | |
| S09 TXT → CSV | ☐ pass ☐ fail | |
| S10 Markdown, YAML | ☐ pass ☐ fail | mime type sent for `.yaml`: |
| S11 Word family | ☐ pass ☐ fail | mime type sent for `.rtf`: |
| S12 XLS → XLSX | ☐ pass ☐ fail | |
| S13 Format change | ☐ pass ☐ fail | |
| S14 Legacy PPT | ☐ pass ☐ fail | |
| S15 Big cell | ☐ pass ☐ fail | |
| S16 Too large | ☐ pass ☐ fail | |
| S17 Unsupported | ☐ pass ☐ fail | |
| S18 Silent cases | ☐ pass ☐ fail | |
| S19 Error and retry | ☐ pass ☐ fail | |
| S20 maxLines | ☐ pass ☐ fail | |
| S21 maxDiffEntries | ☐ pass ☐ fail | |
| S22 Rewrite | ☐ pass ☐ fail | |
| S23 Attachments | ☐ pass ☐ fail | |
| S24 Two authors | ☐ pass ☐ fail | |
| S25 Out of scope | ☐ pass ☐ fail | |
| S26 Restricted source | ☐ pass ☐ fail | |
| S27 Trash, delete | ☐ pass ☐ fail | |
| S28 Charset | ☐ pass ☐ fail | |
| F1–F10 | ☐ pass ☐ fail | |

## 5. Known behaviours, not to be reported as new bugs

- Opening a `BlobDiff` from another `BlobDiff` (drawer queue, link) updates the URL and the header but
  keeps the previous diff on screen; a reload fixes it (open item 26).
- Positional counters coalesce a removal followed by an addition into a modification, pair by pair:
  a rewritten block reports a mix (S22), and a removed bullet followed by a new slide reports a
  "modification" (S05 `-02`).
- Formatting, page headers and footers are not compared (S01 `-03`).
- With `org.nuxeo.web.ui.expressions.eval=false`, the drawer entry and the tab are hidden from
  everyone.
- To regenerate the files: see the header of `_generator/generate.py`.
