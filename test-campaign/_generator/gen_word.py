"""Word scenarios: S01 (paragraph edits), S02 (images), S11 (Word family conversions), S13 base."""
import tempfile
from pathlib import Path

from docx import Document
from docx.shared import Inches

from common import convert, image_stream, neutral_properties, scenario_dir

# ---------------------------------------------------------------------------------------------
# S01 - contract, paragraph by paragraph
# ---------------------------------------------------------------------------------------------

CLAUSES = [
    ("1. Scope", "The provider delivers monthly maintenance of the document management platform."),
    ("2. Payment", "Invoices are payable within 30 days of receipt."),
    ("3. Confidentiality", "Both parties keep confidential any information exchanged under this agreement."),
    ("4. Liability", "The liability of the provider is limited to the amount paid over the last twelve months."),
    ("5. Termination", "Either party may terminate this agreement with a notice of three months."),
]

PARTIES = [
    ("Party", "Role", "Signature date"),
    ("Acme Corporation", "Client", "2026-01-15"),
    ("Globex Services", "Provider", "2026-01-16"),
]


def contract(path, clauses, parties, extra_after_termination=None, bold_payment=False, header=None):
    doc = Document()
    if header:
        doc.sections[0].header.paragraphs[0].text = header
    doc.add_heading("Service Agreement", level=0)
    doc.add_paragraph("This agreement is made between Acme Corporation and Globex Services.")
    for title, body in clauses:
        doc.add_heading(title, level=1)
        p = doc.add_paragraph()
        if bold_payment and title.startswith("2."):
            # Same text, split over two runs, the second one bold: formatting only.
            cut = body.index("within")
            p.add_run(body[:cut])
            p.add_run(body[cut:]).bold = True
        else:
            p.add_run(body)
        if title == "5. Termination" and extra_after_termination:
            doc.add_paragraph(extra_after_termination)
    table = doc.add_table(rows=len(parties), cols=3)
    for r, row in enumerate(parties):
        for c, value in enumerate(row):
            table.cell(r, c).text = value
    doc.add_paragraph("Signed in two copies.")
    neutral_properties(doc.core_properties)
    doc.save(path)


def s01():
    d = scenario_dir("S01-word-contract")
    contract(d / "contract-00.docx", CLAUSES, PARTIES)

    clauses_01 = [(t, b.replace("30 days", "45 days")) for t, b in CLAUSES]
    notice = "The notice must be sent by registered letter."
    contract(d / "contract-01.docx", clauses_01, PARTIES, extra_after_termination=notice)

    clauses_02 = [c for c in clauses_01 if not c[0].startswith("4.")]
    parties_02 = [PARTIES[0], PARTIES[1], ("Globex Services", "Provider", "2026-02-01")]
    contract(d / "contract-02.docx", clauses_02, parties_02, extra_after_termination=notice)

    # Formatting and page header only: the text of the body is the one of -02.
    contract(d / "contract-03.docx", clauses_02, parties_02, extra_after_termination=notice,
             bold_payment=True, header="CONFIDENTIAL - Draft")


# ---------------------------------------------------------------------------------------------
# S02 - Word with images
# ---------------------------------------------------------------------------------------------

def product_sheet(path, first_image, second_image=None, caption_2=False):
    doc = Document()
    doc.add_heading("Product sheet", level=0)
    doc.add_paragraph("The product is shown below.")
    if first_image:
        doc.add_picture(image_stream(*first_image), width=Inches(3))
    doc.add_paragraph("Specifications follow.")
    if second_image:
        doc.add_picture(image_stream(*second_image), width=Inches(3))
    if caption_2:
        doc.add_paragraph("Figure 2: detail view.")
    doc.add_paragraph("Weight: 1.2 kg")
    neutral_properties(doc.core_properties)
    doc.save(path)


def s02():
    d = scenario_dir("S02-word-images")
    product_sheet(d / "word-images-00.docx", ("A", "PNG"))
    product_sheet(d / "word-images-01.docx", ("B", "PNG"))
    product_sheet(d / "word-images-02.docx", ("B", "PNG"), ("C", "JPEG"), caption_2=True)
    product_sheet(d / "word-images-03.docx", None, ("C", "JPEG"), caption_2=True)


# ---------------------------------------------------------------------------------------------
# S11 - one letter, four formats of the "converter" contribution
# ---------------------------------------------------------------------------------------------

LETTER = [
    "Dear Mr. Smith,",
    "Thank you for your order number 4512 placed on October 2.",
    "We confirm that the goods will be delivered on November 15.",
    "The invoice will be sent separately by email.",
    "Please contact us for any question.",
    "Best regards,",
    "Jane Doe, Customer Service",
]


def letter_docx(path, lines):
    doc = Document()
    for line in lines:
        doc.add_paragraph(line)
    neutral_properties(doc.core_properties)
    doc.save(path)


def s11():
    d = scenario_dir("S11-word-family")
    letter_docx(d / "letter-00.docx", LETTER)

    v01 = [line.replace("November 15", "November 22") for line in LETTER]
    v02 = v01[:4] + ["A tracking number will follow."] + v01[4:]
    v03 = [line.replace("Jane Doe", "John Roe") for line in v02]
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        for name, lines, ext in (("letter-01", v01, "odt"), ("letter-02", v02, "doc"), ("letter-03", v03, "rtf")):
            source = tmp / f"{name}.docx"
            letter_docx(source, lines)
            convert(source, ext, d / f"{name}.{ext}")


# ---------------------------------------------------------------------------------------------
# S13 - the Word document that starts the format change series
# ---------------------------------------------------------------------------------------------

MEMO = [
    "Internal memo",
    "Subject: office move",
    "The team moves to the third floor on December 1.",
    "Please pack your belongings by November 28.",
    "Boxes are available at the reception.",
]


def memo_docx(path):
    doc = Document()
    for line in MEMO:
        doc.add_paragraph(line)
    neutral_properties(doc.core_properties)
    doc.save(path)


def run():
    print("Word...")
    s01()
    s02()
    s11()
    memo_docx(scenario_dir("S13-format-change") / "format-change-00.docx")
