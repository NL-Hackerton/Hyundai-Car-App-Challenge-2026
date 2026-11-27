"""Render pt/script_ko.md to a print-friendly DOCX (python-docx). PDF is exported by Word (see script_pdf.ps1).

Usage: <python> pt/tools/script_docx.py <out.docx>
"""
import re
import sys
from pathlib import Path

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

PT = Path(__file__).resolve().parents[1]
FONT = "맑은 고딕"


def set_font(run, size=None, bold=None, color=None):
    run.font.name = FONT
    run._element.get_or_add_rPr().get_or_add_rFonts().set(qn("w:eastAsia"), FONT)
    if size:
        run.font.size = Pt(size)
    if bold is not None:
        run.bold = bold
    if color:
        run.font.color.rgb = RGBColor.from_string(color)


def add_inline(par, text, size=10.5, color=None):
    for tok in re.split(r"(\*\*.+?\*\*|`.+?`)", text):
        if not tok:
            continue
        if tok.startswith("**"):
            set_font(par.add_run(tok[2:-2]), size, True, color)
        elif tok.startswith("`"):
            r = par.add_run(tok[1:-1])
            set_font(r, size - 0.5, None, "374151")
        else:
            set_font(par.add_run(tok), size, None, color)


def main(out):
    lines = (PT / "script_ko.md").read_text(encoding="utf-8").splitlines()
    doc = Document()
    sec = doc.sections[0]
    sec.page_width, sec.page_height = Cm(21), Cm(29.7)
    sec.left_margin = sec.right_margin = Cm(2)
    sec.top_margin = sec.bottom_margin = Cm(1.8)
    st = doc.styles["Normal"]
    st.font.name = FONT
    st.element.rPr.rFonts.set(qn("w:eastAsia"), FONT)
    st.font.size = Pt(10.5)
    st.paragraph_format.space_after = Pt(4)
    st.paragraph_format.line_spacing = 1.35
    i = 0
    first_part = True
    while i < len(lines):
        ln = lines[i]
        if ln.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                cells = [c.strip() for c in lines[i].strip().strip("|").split("|")]
                if not all(re.fullmatch(r":?-+:?", c) for c in cells):
                    rows.append(cells)
                i += 1
            t = doc.add_table(rows=len(rows), cols=len(rows[0]))
            t.style = "Table Grid"
            t.alignment = WD_TABLE_ALIGNMENT.CENTER
            t.autofit = False
            head = rows[0]
            if "답변자" in head:
                widths = [1.1, 4.4, 1.6, 9.9]
            elif "판단 기준" in head:
                widths = [3.6, 4.0, 9.4]
            else:
                widths = [17.0 / len(head)] * len(head)
            for r in range(len(rows)):
                for c, wd in enumerate(widths):
                    t.cell(r, c).width = Cm(wd)
            for r, row in enumerate(rows):
                for c, val in enumerate(row):
                    cell = t.cell(r, c)
                    cell.text = ""
                    add_inline(cell.paragraphs[0], val, 9.5 if len(rows[0]) > 3 else 10)
                    if r == 0:
                        for run in cell.paragraphs[0].runs:
                            run.bold = True
            doc.add_paragraph()
            continue
        if ln.startswith("# "):
            if not first_part and ("부." in ln):
                doc.add_page_break()
            first_part = False
            p = doc.add_paragraph()
            add_inline(p, ln[2:], 16 if "대본 (" in ln and "부" not in ln else 14, "1D4ED8")
            for r in p.runs:
                r.bold = True
        elif ln.startswith("## "):
            p = doc.add_paragraph()
            p.paragraph_format.space_before = Pt(10)
            p.paragraph_format.keep_with_next = True
            add_inline(p, ln[3:], 12.5, "111827")
            for r in p.runs:
                r.bold = True
        elif ln.startswith("### "):
            p = doc.add_paragraph()
            p.paragraph_format.space_before = Pt(8)
            p.paragraph_format.keep_with_next = True
            add_inline(p, ln[4:], 11.5, "1D4ED8")
            for r in p.runs:
                r.bold = True
        elif ln.startswith("- "):
            p = doc.add_paragraph(style="List Bullet")
            add_inline(p, ln[2:])
        elif ln.strip() == "---" or not ln.strip():
            pass
        elif ln.startswith("▶") or (ln.startswith("(") and ln.endswith(")")):
            p = doc.add_paragraph()
            add_inline(p, ln, 10, "6B7280")
        else:
            p = doc.add_paragraph()
            add_inline(p, ln, 11.5 if not ln.startswith("준비") else 10.5)
        i += 1
    doc.save(out)
    print("ok", out)


if __name__ == "__main__":
    main(sys.argv[1])
