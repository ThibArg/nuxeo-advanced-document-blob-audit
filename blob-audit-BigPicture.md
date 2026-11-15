# nuxeo-advanced-document-blob-audit

The big picture — in one page. For details, see [blob-audit-summary.html](blob-audit-summary.html).

> When a document gets a **new version** and its file has **really changed**, the plugin records **that** it
> changed (an audit entry, right away) and **what** changed inside it (a content diff, computed in the
> background and kept in a folder only auditors can read).

## How it flows

Legend: **blue** — during the check-in · **orange** — in the background · **purple** — audit log ·
**green** — stored diff.

![How it flows: during the check-in, a new version (v1.0 → v1.1) leads to "File changed?", which compares the two fingerprints (digests); if not, nothing happens. If yes, in the background: an audit entry "file X changed", visible in History, and the diff is computed (both files read by format, their contents compared) and stored as a BlobDiff in /change-diff, auditors only. The audit entry and the BlobDiff share the same correlation id.](README-Pictures/blob-audit-BigPicture-flow.png)

## What it compares

![What it compares: Excel cell by cell (~ B12 : 100 → 120); PowerPoint slide by slide (paragraphs, tables, notes); Word, ODT, RTF and PDF paragraph by paragraph (PDF: line by line, as rendered); Text, CSV, JSON, XML… line by line (any text/*). The MIME type picks the reader; one engine does all comparisons. Optional: images inside documents, compared by fingerprint. Other formats (images, zip, video…) are still audited as "changed, not diffable".](README-Pictures/blob-audit-BigPicture-formats.png)

## Four things to remember

- **Versions, not saves** — Only a check-in that replaces a file with different bytes counts.
- **Never silent** — "No entry" always means "no change", never "could not tell".
- **Confidential** — Diffs hold business content; only the auditors group can read them.
- **Off by default** — Enable it and pick doc types and limits in one XML `config`.

---

*Big-picture view of the nuxeo-advanced-document-blob-audit plugin (Nuxeo LTS 2025).*
