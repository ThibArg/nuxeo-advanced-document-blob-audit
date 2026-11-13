#!/usr/bin/env python3
"""Generates every test file of the blob audit runbooks.

Usage (from this folder):
    python3 -m venv .venv
    .venv/bin/pip install python-docx openpyxl python-pptx reportlab Pillow xlwt
    .venv/bin/python generate.py

Output: the parent folder (override with BLOBAUDIT_OUT). LibreOffice is needed for the .odt, .doc,
.rtf and .ppt files only. Also writes MANIFEST.tsv: path, size, sha256, mime type to upload with.

The files are committed: regenerate only to change the test material, then review the diff. The
LibreOffice conversions come out with new bytes at every run, so git shows them changed. The two
S16 files over 10 MB are not committed (make-large-files.sh restores them).
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import gen_excel  # noqa: E402
import gen_pdf  # noqa: E402
import gen_ppt  # noqa: E402
import gen_text  # noqa: E402
import gen_word  # noqa: E402
from common import ROOT, sha256  # noqa: E402

MIME_TYPES = {
    ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ".doc": "application/msword",
    ".odt": "application/vnd.oasis.opendocument.text",
    # What the Nuxeo mimetype registry assigns to a .rtf, and what a browser on macOS sends.
    # The plugin README maps rtf to the converter contribution with application/rtf: see S11.
    ".rtf": "text/rtf",
    ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    ".xls": "application/vnd.ms-excel",
    ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    ".ppt": "application/vnd.ms-powerpoint",
    ".pdf": "application/pdf",
    ".txt": "text/plain",
    ".csv": "text/csv",
    ".md": "text/markdown",
    ".json": "application/json",
    ".xml": "text/xml",
    ".yaml": "application/x-yaml",
    ".png": "image/png",
}


def manifest():
    rows = ["path\tsize\tsha256\tmime-type"]
    for path in sorted(ROOT.glob("S*/*")):
        if path.is_file():
            rows.append(f"{path.relative_to(ROOT)}\t{path.stat().st_size}\t{sha256(path)}\t"
                        f"{MIME_TYPES.get(path.suffix, 'application/octet-stream')}")
    (ROOT / "MANIFEST.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")
    print(f"MANIFEST.tsv: {len(rows) - 1} files")


def main():
    ROOT.mkdir(parents=True, exist_ok=True)
    gen_word.run()
    gen_excel.run()
    gen_ppt.run()
    gen_pdf.run()
    gen_text.run()
    manifest()


if __name__ == "__main__":
    main()
