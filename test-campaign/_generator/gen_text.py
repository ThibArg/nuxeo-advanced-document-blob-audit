"""Text family scenarios (plainText contribution) and the edge cases built on text files."""
from common import make_image, scenario_dir, write_bytes, write_text

# ---------------------------------------------------------------------------------------------
# S07 - meeting notes: edits, whitespace and line endings only, BOM
# ---------------------------------------------------------------------------------------------

NOTES_00 = [
    "Project kickoff - meeting notes",
    "Date: 2026-11-02",
    "Attendees: product, engineering, support",
    "Decision: the beta starts on December 1",
    "Decision: the pricing page is updated before the beta",
    "Action: engineering prepares the staging environment",
    "Action: support writes the FAQ",
    "Next meeting: 2026-11-09",
]


def s07():
    d = scenario_dir("S07-text-notes")
    write_text(d / "notes-00.txt", "\n".join(NOTES_00) + "\n")

    # Edits kept apart by unchanged lines, so that each one is reported on its own.
    v01 = list(NOTES_00)
    v01[2] = "Attendees: product, engineering, support, sales"
    v01[5] = "Action: engineering prepares the staging and demo environments"
    v01.insert(7, "Action: sales lists the pilot customers")
    write_text(d / "notes-01.txt", "\n".join(v01) + "\n")

    # Same text: CRLF, indentation, trailing spaces and blank lines only.
    v02 = []
    for i, line in enumerate(v01):
        v02.append(("    " if i % 2 else "\t") + line + "   ")
        if i % 3 == 2:
            v02.append("")
    write_text(d / "notes-02.txt", "\n".join(v02) + "\n", newline="\r\n")

    v03 = [line for line in v01 if line != "Action: support writes the FAQ"]
    write_text(d / "notes-03.txt", "\n".join(v03) + "\n", bom=True)


# ---------------------------------------------------------------------------------------------
# S08 - JSON edited, then replaced by its XML equivalent, then the XML edited
# ---------------------------------------------------------------------------------------------

JSON_TEMPLATE = """{{
  "service": "invoice-api",
  "version": "2.4.1",
  "port": 8080,
  "database": {{
    "host": "db.internal",
    "pool": {pool},
    "timeoutSeconds": 30
  }},
  "features": {{
    "pdfExport": true,
    "emailNotifications": false
  }},
  "logLevel": "{level}"
}}
"""

XML_TEMPLATE = """<?xml version="1.0" encoding="UTF-8"?>
<config>
  <service>invoice-api</service>
  <version>2.4.1</version>
  <port>{port}</port>
  <database>
    <host>db.internal</host>
    <pool>20</pool>
    <timeoutSeconds>30</timeoutSeconds>
  </database>
  <features>
    <pdfExport>true</pdfExport>
    <emailNotifications>false</emailNotifications>
  </features>
  <logLevel>DEBUG</logLevel>
</config>
"""


def s08():
    d = scenario_dir("S08-json-replaced-by-xml")
    write_text(d / "config-00.json", JSON_TEMPLATE.format(pool=10, level="INFO"))
    write_text(d / "config-01.json", JSON_TEMPLATE.format(pool=20, level="DEBUG"))
    write_text(d / "config-02.xml", XML_TEMPLATE.format(port=8080))
    write_text(d / "config-03.xml", XML_TEMPLATE.format(port=9090))


# ---------------------------------------------------------------------------------------------
# S09 - a .txt holding CSV data, saved as a real .csv with one edit
# ---------------------------------------------------------------------------------------------

DATA = [
    "id,name,city,amount",
    "1,Alpha Corp,Paris,1200",
    "2,Beta Ltd,London,800",
    "3,Gamma Inc,Berlin,1500",
    "4,Delta SA,Madrid,600",
    "5,Epsilon AG,Zurich,2100",
    "6,Zeta BV,Amsterdam,900",
    "7,Eta SpA,Rome,1100",
]


def s09():
    d = scenario_dir("S09-txt-to-csv")
    write_text(d / "data-00.txt", "\n".join(DATA) + "\n")
    edited = [line.replace("Madrid,600", "Madrid,650") for line in DATA]
    write_text(d / "data-01.csv", "\n".join(edited) + "\n")


# ---------------------------------------------------------------------------------------------
# S10 - Markdown and YAML (two documents)
# ---------------------------------------------------------------------------------------------

README_00 = """# Release notes

## Version 3.2

- Faster search
- New export to CSV

## Known issues

- The preview fails on very large PDFs
"""

PIPELINE_00 = """stages:
  - build
  - test
  - deploy
build:
  image: maven:3.9
  script: mvn -B package
deploy:
  environment: staging
  when: manual
"""


def s10():
    d = scenario_dir("S10-markdown-and-yaml")
    write_text(d / "readme-00.md", README_00)
    readme_01 = README_00.replace("## Version 3.2", "## Version 3.3").replace(
        "- New export to CSV\n", "- New export to CSV\n- Dark mode\n")
    write_text(d / "readme-01.md", readme_01)
    write_text(d / "pipeline-00.yaml", PIPELINE_00)
    pipeline_01 = PIPELINE_00.replace("environment: staging", "environment: production").replace(
        "  - test\n", "  - test\n  - security-scan\n")
    write_text(d / "pipeline-01.yaml", pipeline_01)


# ---------------------------------------------------------------------------------------------
# S16 - over maxBlobSize (10 MB): too large, then one side too large, then both small.
# too-large-00 and -01 (11 MB each) are not in git: make-large-files.sh writes the same bytes and
# checks them against MANIFEST.tsv. Change big_lines and that script together.
# ---------------------------------------------------------------------------------------------

def big_lines(changed_index=None):
    lines = []
    size = 0
    i = 0
    while size < 11_500_000:
        i += 1
        status = "ALERT" if i == changed_index else "nominal"
        line = f"Record {i:07d}: sensor reading {i % 997:03d} at station {i % 53:02d}, status {status}\n"
        lines.append(line)
        size += len(line)
    return lines


def s16():
    d = scenario_dir("S16-too-large")
    write_text(d / "too-large-00.txt", "".join(big_lines()))
    v01 = big_lines(changed_index=50_000)
    write_text(d / "too-large-01.txt", "".join(v01))
    v02 = v01[:100]
    write_text(d / "too-large-02.txt", "".join(v02))
    v03 = list(v02)
    v03[9] = v03[9].replace("status nominal", "status ALERT")
    write_text(d / "too-large-03.txt", "".join(v03))


# ---------------------------------------------------------------------------------------------
# S17 - unsupported format (PNG in a File document), then text
# ---------------------------------------------------------------------------------------------

def s17():
    d = scenario_dir("S17-unsupported-format")
    write_bytes(d / "picture-00.png", make_image("A", "PNG"))
    write_bytes(d / "picture-01.png", make_image("B", "PNG"))
    text = ["Picture description", "Subject: product packshot", "Background: plain",
            "Photographer: studio team", "Usage: catalog only"]
    write_text(d / "picture-02.txt", "\n".join(text) + "\n")
    text[4] = "Usage: catalog and website"
    write_text(d / "picture-03.txt", "\n".join(text) + "\n")


# ---------------------------------------------------------------------------------------------
# S18 - identical bytes under another name and extension
# ---------------------------------------------------------------------------------------------

CHECKLIST = ["Release checklist", "- run the tests", "- update the changelog", "- tag the release",
             "- announce on the forum"]


def s18():
    d = scenario_dir("S18-same-bytes-renamed")
    write_text(d / "same-bytes-00.txt", "\n".join(CHECKLIST) + "\n")
    write_text(d / "same-bytes-01.md", "\n".join(CHECKLIST) + "\n")
    edited = CHECKLIST[:3] + ["- sign the artifacts"] + CHECKLIST[3:]
    write_text(d / "same-bytes-02.md", "\n".join(edited) + "\n")


# ---------------------------------------------------------------------------------------------
# S20 - 12 000 lines: over maxLines (10 000)
# ---------------------------------------------------------------------------------------------

def log_line(i, level="INFO "):
    return f"2026-11-01 10:{(i // 60) % 60:02d}:{i % 60:02d} {level} [worker-{i % 4 + 1}] event {i:05d} processed"


def s20():
    d = scenario_dir("S20-truncated-lines")
    lines = [log_line(i) for i in range(1, 12_001)]
    write_text(d / "long-log-00.txt", "\n".join(lines) + "\n")
    lines[99] = log_line(100, "WARN ")       # line 100: inside the window
    lines[10_999] = log_line(11_000, "ERROR")  # line 11 000: past maxLines, never seen
    write_text(d / "long-log-01.txt", "\n".join(lines) + "\n")


# ---------------------------------------------------------------------------------------------
# S22 - rewritten from end to end, same format
# ---------------------------------------------------------------------------------------------

def s22():
    d = scenario_dir("S22-rewrite-same-format")
    v00 = ["Cafeteria menu - week 45", "Monday: vegetable soup", "Tuesday: fish and rice",
           "Wednesday: lasagna", "Thursday: chicken curry", "Friday: pizza"]
    v01 = ["Parking rules", "Badges are mandatory at the gate", "Visitors park on level -1",
           "Electric chargers are limited to 2 hours", "Bicycles go to the inner yard",
           "Report any damage to the facility team"]
    write_text(d / "rewrite-00.txt", "\n".join(v00) + "\n")
    write_text(d / "rewrite-01.txt", "\n".join(v01) + "\n")


# ---------------------------------------------------------------------------------------------
# S23 - attachments (files:files), needs <xpath>*</xpath>
# ---------------------------------------------------------------------------------------------

def s23():
    d = scenario_dir("S23-attachments")
    write_text(d / "main-00.txt", "Supplier file\nMain document of the record\n")
    alpha = ["Attachment alpha", "Supplier: Acme", "Contract: 2026-118", "Amount: 12000 EUR",
             "Payment: 30 days"]
    write_text(d / "attach-alpha-00.txt", "\n".join(alpha) + "\n")
    alpha[3] = "Amount: 12500 EUR"
    write_text(d / "attach-alpha-01.txt", "\n".join(alpha) + "\n")
    alpha[4] = "Payment: 45 days"
    write_text(d / "attach-alpha-02.txt", "\n".join(alpha) + "\n")
    write_text(d / "attach-beta-00.txt", "Attachment beta\nInsurance certificate\nValid until 2027-06-30\n")
    write_text(d / "attach-gamma-00.txt", "Attachment gamma\nBank details\nIBAN: on file\n")


# ---------------------------------------------------------------------------------------------
# S24 / S26 / S27 - small text series for the multi-user, restricted and lifecycle scenarios
# ---------------------------------------------------------------------------------------------

def series(folder, prefix, base, edits):
    d = scenario_dir(folder)
    lines = list(base)
    write_text(d / f"{prefix}-00.txt", "\n".join(lines) + "\n")
    for n, (index, value) in enumerate(edits, start=1):
        lines[index] = value
        write_text(d / f"{prefix}-{n:02d}.txt", "\n".join(lines) + "\n")


def s24_s26_s27():
    series("S24-collaborative-save", "collab",
           ["Shared procedure", "Step 1: open the ticket", "Step 2: assign an owner", "Step 3: close the ticket"],
           [(2, "Step 2: assign an owner within one day"), (3, "Step 3: close the ticket and notify")])
    series("S26-restricted-source", "secret",
           ["Board minutes - restricted", "Item 1: acquisition of a competitor", "Item 2: budget 2027"],
           [(1, "Item 1: acquisition of a competitor postponed")])
    series("S27-source-lifecycle", "lifecycle",
           ["Draft policy", "Rule 1: lock your screen", "Rule 2: badge in and out"],
           [(2, "Rule 2: badge in and out, every time")])


# ---------------------------------------------------------------------------------------------
# S25 - a document type the configuration does not watch (Picture)
# ---------------------------------------------------------------------------------------------

def s25():
    d = scenario_dir("S25-out-of-scope-type")
    write_bytes(d / "scope-00.png", make_image("C", "PNG"))
    write_bytes(d / "scope-01.png", make_image("D", "PNG"))


# ---------------------------------------------------------------------------------------------
# S28 - same text, UTF-8 then ISO-8859-1: no charset detection
# ---------------------------------------------------------------------------------------------

def s28():
    d = scenario_dir("S28-charset")
    text = "Compte rendu\nLe comité a validé le budget\nAucun changement ici\nProchaine réunion : jeudi\n"
    write_text(d / "charset-00.txt", text, encoding="utf-8")
    write_text(d / "charset-01.txt", text, encoding="iso-8859-1")


def run():
    print("Text...")
    s07()
    s08()
    s09()
    s10()
    s16()
    s17()
    s18()
    s20()
    s22()
    s23()
    s24_s26_s27()
    s25()
    s28()
