"""PowerPoint scenarios: S05 (slides, table, notes, soft line break, image) and S14 (legacy .ppt)."""
import tempfile
from pathlib import Path

from pptx import Presentation
from pptx.util import Inches

from common import convert, image_stream, neutral_properties, scenario_dir

HIGHLIGHTS = ["Revenue up 12 percent", "Two new customers signed", "Churn stable at 3 percent"]
NOTES = "Mention the new customer in Lyon."
TABLE = [["Region", "Q2", "Q3"], ["North", "120", "135"], ["South", "95", "110"]]
NEXT_STEPS = ["Hire two account managers", "Open the Madrid office\vin January", "Review pricing"]


def bullets(slide, title, items, notes=None):
    slide.shapes.title.text = title
    body = slide.placeholders[1].text_frame
    body.text = items[0]
    for item in items[1:]:
        body.add_paragraph().text = item
    if notes:
        slide.notes_slide.notes_text_frame.text = notes


def deck(path, highlights, notes, table, next_steps, risks=None, image=None):
    prs = Presentation()
    title_layout, content_layout, title_only = prs.slide_layouts[0], prs.slide_layouts[1], prs.slide_layouts[5]

    s1 = prs.slides.add_slide(title_layout)
    s1.shapes.title.text = "Quarterly Review"
    s1.placeholders[1].text = "Q3 2026 - Sales team"

    bullets(prs.slides.add_slide(content_layout), "Highlights", highlights, notes)

    s3 = prs.slides.add_slide(title_only)
    s3.shapes.title.text = "Figures"
    shape = s3.shapes.add_table(len(table), 3, Inches(1), Inches(2), Inches(6), Inches(1.5))
    for r, row in enumerate(table):
        for c, value in enumerate(row):
            shape.table.cell(r, c).text = value
    if image:
        s3.shapes.add_picture(image_stream(image, "PNG"), Inches(6.5), Inches(4.5), width=Inches(2.5))

    bullets(prs.slides.add_slide(content_layout), "Next Steps", next_steps)

    if risks:
        bullets(prs.slides.add_slide(content_layout), "Risks", risks)
    neutral_properties(prs.core_properties)
    prs.save(path)


def s05():
    d = scenario_dir("S05-powerpoint-deck")
    deck(d / "deck-00.pptx", HIGHLIGHTS, NOTES, TABLE, NEXT_STEPS)

    highlights_01 = ["Revenue up 14 percent"] + HIGHLIGHTS[1:]
    notes_01 = "Mention the new customers in Lyon and Nantes."
    table_01 = [TABLE[0], TABLE[1], ["South", "95", "118"]]
    deck(d / "deck-01.pptx", highlights_01, notes_01, table_01, NEXT_STEPS)

    highlights_02 = highlights_01[:2]
    risks = ["Currency exposure", "Supplier delays"]
    deck(d / "deck-02.pptx", highlights_02, notes_01, table_01, NEXT_STEPS, risks=risks)

    next_steps_03 = [NEXT_STEPS[0], "Open the Madrid office\vin February", NEXT_STEPS[2]]
    deck(d / "deck-03.pptx", highlights_02, notes_01, table_01, next_steps_03, risks=risks, image="A")


# ---------------------------------------------------------------------------------------------
# S14 - legacy .ppt (legacyPresentation contribution), then a .pptx (presentation contribution)
# ---------------------------------------------------------------------------------------------

def training(path, items):
    prs = Presentation()
    s1 = prs.slides.add_slide(prs.slide_layouts[0])
    s1.shapes.title.text = "Training plan"
    s1.placeholders[1].text = "Onboarding of new hires"
    bullets(prs.slides.add_slide(prs.slide_layouts[1]), "Sessions", items)
    neutral_properties(prs.core_properties)
    prs.save(path)


TRAINING = ["Week 1: product overview", "Week 2: customer cases", "Week 3: certification"]


def s14():
    d = scenario_dir("S14-legacy-ppt")
    v01 = [TRAINING[0], "Week 2: customer cases and demos", TRAINING[2]]
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        for name, items in (("legacy-deck-00", TRAINING), ("legacy-deck-01", v01)):
            source = tmp / f"{name}.pptx"
            training(source, items)
            convert(source, "ppt", d / f"{name}.ppt")
    training(d / "legacy-deck-02.pptx", v01 + ["Week 4: shadowing"])


def run():
    print("PowerPoint...")
    s05()
    s14()
