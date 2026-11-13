"""Excel scenarios: S03 (cells, formulas, multi-line cell, sheet rename), S04 (images),
S12 (.xls replaced by .xlsx), S13 (format change, xlsx part), S15 (big cell), S19 (corrupted file),
S21 (mass update past maxDiffEntries)."""
import io

import xlwt
from openpyxl import Workbook
from openpyxl.drawing.image import Image as XlImage
from openpyxl.styles import Alignment

from common import image_stream, scenario_dir, write_bytes

# ---------------------------------------------------------------------------------------------
# S03 - budget
# ---------------------------------------------------------------------------------------------

ROWS = [
    ["Licenses", 1000, 1000, 1000, 1000],
    ["Hosting", 1200, 1200, 1200, 1200],
    ["Support", 800, 800, 900, 900],
    ["Training", 0, 500, 0, 300],
    ["Consulting", 2000, 1500, 1500, 1000],
]

NOTES = [
    ("Owner", "Finance team"),
    ("Comment", "Figures approved\nby the steering committee"),
    ("Status", "Draft"),
]


def budget(path, rows, notes, notes_title="Notes", last_row=6):
    wb = Workbook()
    ws = wb.active
    ws.title = "Budget"
    ws.append(["Item", "Q1", "Q2", "Q3", "Q4", "Total"])
    for i, row in enumerate(rows, start=2):
        if row is None:
            continue
        for c, value in enumerate(row, start=1):
            ws.cell(row=i, column=c, value=value)
        ws.cell(row=i, column=6, value=f"=SUM(B{i}:E{i})")
    ws.cell(row=8, column=1, value="Total")
    for col in "BCDEF":
        ws[f"{col}8"] = f"=SUM({col}2:{col}{last_row})"
    notes_ws = wb.create_sheet(notes_title)
    for r, (label, value) in enumerate(notes, start=1):
        notes_ws.cell(row=r, column=1, value=label)
        cell = notes_ws.cell(row=r, column=2, value=value)
        cell.alignment = Alignment(wrap_text=True)
    wb.save(path)


def s03():
    d = scenario_dir("S03-excel-budget")
    budget(d / "budget-00.xlsx", ROWS + [None], NOTES)

    rows_01 = [list(r) for r in ROWS]
    rows_01[1][2] = 1500   # Budget!C3
    rows_01[3][4] = 350    # Budget!E5
    notes_01 = [NOTES[0], ("Comment", "Figures approved\nby the board"), NOTES[2]]
    budget(d / "budget-01.xlsx", rows_01 + [None], notes_01)

    rows_02 = rows_01 + [["Travel", 300, 300, 300, 300]]
    budget(d / "budget-02.xlsx", rows_02, notes_01, last_row=7)

    budget(d / "budget-03.xlsx", rows_02, notes_01, notes_title="Remarks", last_row=7)


# ---------------------------------------------------------------------------------------------
# S04 - Excel with images
# ---------------------------------------------------------------------------------------------

def pictures(path, title, images):
    wb = Workbook()
    ws = wb.active
    ws.title = "Pictures"
    ws["A1"] = title
    for letter, anchor in images:
        img = XlImage(image_stream(letter, "PNG"))
        img.width, img.height = 160, 100
        ws.add_image(img, anchor)
    wb.save(path)


def s04():
    d = scenario_dir("S04-excel-images")
    pictures(d / "excel-images-00.xlsx", "Product photo", [("A", "B2")])
    pictures(d / "excel-images-01.xlsx", "Product photo", [("A", "E10")])
    pictures(d / "excel-images-02.xlsx", "Product photo", [("B", "E10")])
    pictures(d / "excel-images-03.xlsx", "Product photos", [("B", "E10"), ("C", "H2")])


# ---------------------------------------------------------------------------------------------
# S12 - legacy .xls replaced by .xlsx
# ---------------------------------------------------------------------------------------------

LEGACY = [
    ["Region", "Units", "Revenue"],
    ["North", 120, 24000],
    ["South", 95, 19000],
    ["West", 60, 12000],
]


def s12():
    d = scenario_dir("S12-xls-to-xlsx")
    book = xlwt.Workbook()
    sheet = book.add_sheet("Sales")
    for r, row in enumerate(LEGACY):
        for c, value in enumerate(row):
            sheet.write(r, c, value)
    buf = io.BytesIO()
    book.save(buf)
    write_bytes(d / "legacy-sales-00.xls", buf.getvalue())

    wb = Workbook()
    ws = wb.active
    ws.title = "Sales"
    for row in LEGACY:
        ws.append(row)
    ws["B3"] = 110  # South units: 95 -> 110
    wb.save(d / "legacy-sales-01.xlsx")


# ---------------------------------------------------------------------------------------------
# S13 - format change: the spreadsheet part
# ---------------------------------------------------------------------------------------------

MEMO = [
    "Internal memo",
    "Subject: office move",
    "The team moves to the third floor on December 1.",
    "Please pack your belongings by November 28.",
    "Boxes are available at the reception.",
]


def memo_xlsx(path, lines):
    wb = Workbook()
    ws = wb.active
    ws.title = "Memo"
    for i, line in enumerate(lines, start=1):
        ws.cell(row=i, column=1, value=line)
    wb.save(path)


def s13():
    d = scenario_dir("S13-format-change")
    memo_xlsx(d / "format-change-02.xlsx", MEMO)
    edited = list(MEMO)
    edited[2] = "The team moves to the third floor on December 8."
    memo_xlsx(d / "format-change-03.xlsx", edited)


# ---------------------------------------------------------------------------------------------
# S15 - a cell at the Excel maximum, 32 767 characters, changed at its very end
# ---------------------------------------------------------------------------------------------

SENTENCE = "This cell holds a very long legal clause that keeps repeating itself. "


def big_text(ending):
    text = (SENTENCE * (32767 // len(SENTENCE) + 1))[: 32767 - len(ending)] + ending
    assert len(text) == 32767
    return text


def big_cell(path, ending):
    wb = Workbook()
    ws = wb.active
    ws.title = "Big"
    ws["A1"] = "Short cell"
    ws["B1"] = big_text(ending)
    ws["A2"] = "Another short cell"
    wb.save(path)


def s15():
    d = scenario_dir("S15-excel-big-cell")
    big_cell(d / "big-cell-00.xlsx", "[END OF VERSION 00]")
    big_cell(d / "big-cell-01.xlsx", "[END OF VERSION 01]")


# ---------------------------------------------------------------------------------------------
# S19 - a corrupted spreadsheet in the middle of a series
# ---------------------------------------------------------------------------------------------

def inventory(path, apples):
    wb = Workbook()
    ws = wb.active
    ws.title = "Stock"
    for row in (["Fruit", "Quantity"], ["Apples", apples], ["Pears", 40], ["Plums", 25]):
        ws.append(row)
    wb.save(path)


def s19():
    d = scenario_dir("S19-broken-file")
    inventory(d / "broken-00.xlsx", 10)
    buf = io.BytesIO()
    inventory(buf, 12)
    data = buf.getvalue()
    write_bytes(d / "broken-01.xlsx", data[: len(data) // 2])  # truncated: not a readable zip
    inventory(d / "broken-02.xlsx", 14)
    inventory(d / "broken-03.xlsx", 16)


# ---------------------------------------------------------------------------------------------
# S21 - 6 000 cells changed: over maxDiffEntries (5 000)
# ---------------------------------------------------------------------------------------------

def mass(path, offset):
    wb = Workbook(write_only=True)
    ws = wb.create_sheet("Data")
    for i in range(1, 6001):
        ws.append([i * 10 + offset])
    wb.save(path)


def s21():
    d = scenario_dir("S21-excel-mass-update")
    mass(d / "mass-update-00.xlsx", 0)
    mass(d / "mass-update-01.xlsx", 1)


def run():
    print("Excel...")
    s03()
    s04()
    s12()
    s13()
    s15()
    s19()
    s21()
