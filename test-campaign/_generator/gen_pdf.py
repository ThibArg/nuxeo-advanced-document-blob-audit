"""PDF scenarios: S06 (text and images, page insertion) and S13 (format change, PDF part)."""
from reportlab.lib.pagesizes import A4
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen import canvas

from common import image_stream, scenario_dir

WIDTH, HEIGHT = A4


def pdf(path, pages):
    """pages: list of (title, lines, image letter or None). invariant=1: no date nor random id."""
    c = canvas.Canvas(str(path), pagesize=A4, invariant=1)
    c.setTitle("Blob audit test document")
    for title, lines, image in pages:
        y = HEIGHT - 80
        c.setFont("Helvetica-Bold", 18)
        c.drawString(60, y, title)
        c.setFont("Helvetica", 12)
        for line in lines:
            y -= 24
            c.drawString(60, y, line)
        if image:
            c.drawImage(ImageReader(image_stream(image, "JPEG")), 60, y - 260, width=320, height=200)
        c.showPage()
    c.save()


PAGE_1 = ("Annual Safety Report 2026", [
    "Prepared by the Quality department.",
    "Incidents reported: 14",
    "Incidents closed: 12",
    "Average closing time: 9 days",
])
PAGE_2 = ("Section 2 - Actions", [
    "Replace the fire extinguishers in building B.",
    "Train 40 employees on first aid.",
    "Audit the chemical storage room.",
])
PAGE_1B = ("Section 1b - Budget", [
    "Budget allocated: 25000 EUR",
    "Budget spent: 21300 EUR",
])


def s06():
    d = scenario_dir("S06-pdf-report")
    pdf(d / "report-00.pdf", [(*PAGE_1, "A"), (*PAGE_2, "C")])

    p1 = (PAGE_1[0], [line.replace("closed: 12", "closed: 13") for line in PAGE_1[1]])
    p2 = (PAGE_2[0], PAGE_2[1] + ["Repaint the emergency exits."])
    pdf(d / "report-01.pdf", [(*p1, "A"), (*p2, "C")])
    pdf(d / "report-02.pdf", [(*p1, "B"), (*p2, "C")])
    pdf(d / "report-03.pdf", [(*p1, "B"), (*PAGE_1B, None), (*p2, "C")])


MEMO = [
    "Subject: office move",
    "The team moves to the third floor on December 1.",
    "Please pack your belongings by November 28.",
    "Boxes are available at the reception.",
]


def run():
    print("PDF...")
    s06()
    pdf(scenario_dir("S13-format-change") / "format-change-01.pdf", [("Internal memo", MEMO, None)])
