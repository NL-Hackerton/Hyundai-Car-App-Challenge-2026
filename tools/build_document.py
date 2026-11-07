#!/usr/bin/env python3
"""Fill the official blank DOCX; convert/render only on explicit execution.

Input contract: .harness/work-orders/document-interface.md.
This command does not perform visual review, sign, upload, or submit documents.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import math
import re
import shutil
import subprocess
import tempfile

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs/form/contest-application-consent.docx"
SOURCE_SHA256 = "07be893d58b0e96ab7788f0d189d6829f0e59a2cee4bc18cb90ec37eeda2f5fd"
TITLES = (
    "앱 한 줄 소개", "기획 배경", "앱 주요 기능 및 차량 특화 요소",
    "주요 사용 시나리오", "개발 계획", "기대효과 및 발전 가능성",
    "참고자료", "숏폼 링크",
)
HEADINGS = tuple(f"## {i}. {title}" for i, title in enumerate(TITLES, 1))
INLINE = re.compile(r"\[([^\]\n]+)\]\((https?://[^\s)]+)\)|https?://[^\s<>]+|\*\*([^*\n]+)\*\*|\*([^*\n]+)\*|`([^`\n]+)`")
CAPTION = re.compile(r'<p(?: align="center")?>((표|그림)\s+\d+\..+)</p>')
IMAGE = re.compile(r"!\[([^\]\n]*)\]\(([A-Za-z0-9_./-]+\.png)\)")
LIST = re.compile(r"^( *)([-*]|\d+\.) (.+)$")
SEPARATOR = re.compile(r"^:?-{3,}:?$")
# Tables estimated up to this height stay on one page with their caption (cm),
# unless they have more than KEEP_TABLE_ROWS rows: long row lists may flow.
KEEP_TABLE_CM = 14.0
KEEP_TABLE_ROWS = 15
# Table rows estimated taller than this may break across pages (cm).
SPLIT_ROW_CM = 5.0
TABLE_FONT_PT = 9.5
# Readability layout (Windows-safe defaults).
BODY_FONT_PT = 10.5
HEADING_FONT_PT = 12
CAPTION_FONT_PT = 9
BODY_LINE_SPACING = 1.5
TABLE_LINE_SPACING = 1.15
HEADING_COLOR = "1F3864"
CAPTION_COLOR = "595959"
TABLE_HEADER_FILL = "DCE6F2"
# Cell padding in twips (1/20 pt): top/bottom, left/right.
CELL_PAD_TB = 60
CELL_PAD_LR = 100


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def validate_links(text: str):
    """Reject unsupported URL syntax before changing any existing output."""
    links = list(INLINE.finditer(text))
    for start in re.finditer(r"(?<!!)\[[^\n]*?\]\(", text):
        match = INLINE.match(text, start.start())
        if not match or not match.group(1):
            raise ValueError("지원하지 않는 Markdown 링크다. http(s) 단순 링크를 사용한다.")
        if start.start() and text[start.start() - 1] == "\\":
            raise ValueError("이스케이프된 Markdown 링크는 지원하지 않는다.")
        if any(char in match.group(1) for char in "\\[]"):
            raise ValueError("링크 표시문의 이스케이프·중첩 괄호는 지원하지 않는다.")
        if any(char in match.group(2) for char in "()\\"):
            raise ValueError("URL 괄호·이스케이프는 지원하지 않는다. %28/%29 등 URL 인코딩을 사용한다.")
    for match in re.finditer(r"https?://[^\s<>]+", text):
        url = match.group()
        # A Markdown link's final delimiter is not part of its destination.
        markdown = next(
            (m for m in links if m.group(1) and m.start(2) == match.start()),
            None,
        )
        if markdown:
            url = markdown.group(2)
        if any(char in url for char in "()\\"):
            raise ValueError("URL 괄호·이스케이프는 지원하지 않는다. URL 인코딩을 사용한다.")


def internal_input(path: Path) -> Path:
    resolved = path.resolve(strict=True)
    if not resolved.is_relative_to(ROOT) or not resolved.is_file():
        raise ValueError("입력은 저장소 내부의 실제 파일이어야 한다. 외부 경로·외부 링크는 거부한다.")
    return resolved


def safe_output(path: Path, protected):
    """Reject all output symlinks and protected-file aliases before deletion."""
    if not path.is_relative_to(ROOT):
        raise ValueError("출력 경로가 저장소 밖이다.")
    for component in (*reversed(path.parents), path):
        if component == ROOT or component.is_relative_to(ROOT):
            if component.is_symlink():
                raise ValueError(f"출력 또는 상위 경로의 심볼릭 링크를 거부한다: {component}")
    if not path.resolve().is_relative_to(ROOT):
        raise ValueError("출력의 실제 경로가 저장소 밖이다.")
    if path.exists():
        for source in protected:
            if path.samefile(source) or source.resolve().is_relative_to(path.resolve()):
                raise ValueError("출력이 원본·원고와 동일하거나 보호 파일을 포함하는 경로다.")


def input_document(path: Path):
    text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    validate_links(text)
    lines = text.splitlines()
    if not lines or lines[0] != "---":
        raise ValueError("원고 첫 줄에는 메타데이터 구분선 ---가 필요하다.")
    try:
        end = lines.index("---", 1)
    except ValueError as error:
        raise ValueError("메타데이터 닫는 ---가 없다.") from error
    metadata = {}
    for line in lines[1:end]:
        key, sep, value = line.partition(":")
        if not sep or key in metadata:
            raise ValueError("메타데이터 키 누락 또는 중복이다.")
        metadata[key] = value.strip()
    if set(metadata) - {"project_name", "participant_unit", "participant_category"}:
        raise ValueError("허용되지 않은 메타데이터다. 개인정보 입력은 지원하지 않는다.")
    project = metadata.get("project_name", "")
    if not project or len(project) > 80 or any(c in project for c in '<>\"@'):
        raise ValueError("project_name은 80자 이내의 비식별 일반 문자열이어야 한다.")
    for key, allowed in (("participant_unit", "individual"), ("participant_category", "student")):
        if key in metadata and metadata[key] != allowed:
            raise ValueError(f"{key}의 허용값은 {allowed}뿐이다.")
    sections = []
    current = None
    for line in lines[end + 1:]:
        if line.startswith("## "):
            expected = HEADINGS[len(sections)] if len(sections) < 8 else None
            if line != expected:
                raise ValueError(f"공식 항목 제목·순서 불일치: {line}")
            current = []
            sections.append(current)
        elif current is None:
            if line.strip():
                raise ValueError("첫 공식 항목 앞에는 본문을 둘 수 없다.")
        else:
            current.append(line)
    if len(sections) != 8 or any(not "\n".join(s).strip() for s in sections):
        raise ValueError("비어 있지 않은 공식 1~8 항목이 모두 필요하다.")
    return metadata, [parse_blocks(s) for s in sections]


def cells(line: str):
    return [part.strip().replace("\\|", "|") for part in re.split(r"(?<!\\)\|", line.strip().strip("|"))]


def parse_blocks(lines):
    blocks = []
    i = 0
    while i < len(lines):
        line = lines[i]
        if not line.strip():
            i += 1
            continue
        caption = CAPTION.fullmatch(line)
        if caption:
            expected = "table" if caption.group(2) == "표" else "image"
            if not blocks or blocks[-1][0] != expected:
                raise ValueError("표·그림 캡션은 바로 앞 Markdown 표 또는 그림에 붙여야 한다.")
            blocks.append(("caption", caption.group(1)))
            i += 1
            continue
        image = IMAGE.fullmatch(line.strip())
        if image:
            blocks.append(("image", internal_input(ROOT / image.group(2))))
            i += 1
            continue
        if line.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                rows.append(cells(lines[i]))
                i += 1
            if len(rows) < 2 or not all(SEPARATOR.fullmatch(v) for v in rows[1]):
                raise ValueError("Markdown 표 구분행이 필요하다.")
            if any(len(row) != len(rows[0]) for row in rows):
                raise ValueError("Markdown 표의 열 수가 일치하지 않는다.")
            blocks.append(("table", [rows[0], *rows[2:]]))
            continue
        heading = re.fullmatch(r"(#{3,6}) (.+)", line)
        if heading:
            blocks.append(("heading", heading.group(2)))
            i += 1
            continue
        item = LIST.fullmatch(line)
        if item:
            spaces = len(item.group(1))
            if spaces % 2 or spaces > 4:
                raise ValueError("목록 들여쓰기는 0/2/4칸이어야 한다.")
            marker = "•" if item.group(2) in ("-", "*") else item.group(2)
            blocks.append(("list", (spaces // 2, marker, item.group(3))))
            i += 1
            continue
        if line.startswith(("#", "```", "~~~", "!", ">")) or "<" in line or "$$" in line:
            raise ValueError(f"지원하지 않는 Markdown 구문: {line[:80]}")
        paragraph = [line.strip()]
        i += 1
        while i < len(lines) and lines[i].strip():
            next_line = lines[i]
            if next_line.startswith(("#", "|", "<", "```", "~~~", "!", ">")) or LIST.fullmatch(next_line):
                break
            paragraph.append(next_line.strip())
            i += 1
        blocks.append(("paragraph", " ".join(paragraph)))
    return blocks


def set_font(run, font, size=BODY_FONT_PT):
    run.font.name = font
    run.font.size = Pt(size)
    run._element.get_or_add_rPr().rFonts.set(qn("w:eastAsia"), font)


def hyperlink(paragraph, label, url, font):
    if not url.startswith(("https://", "http://")):
        raise ValueError("http(s) 링크만 지원한다.")
    relation = paragraph.part.relate_to(
        url, "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink", is_external=True
    )
    link = OxmlElement("w:hyperlink")
    link.set(qn("r:id"), relation)
    run = OxmlElement("w:r")
    properties = OxmlElement("w:rPr")
    fonts = OxmlElement("w:rFonts")
    for name in ("ascii", "hAnsi", "eastAsia"):
        fonts.set(qn(f"w:{name}"), font)
    properties.append(fonts)
    size = OxmlElement("w:sz")
    size.set(qn("w:val"), "21")
    properties.append(size)
    run.append(properties)
    text = OxmlElement("w:t")
    text.text = label if label == url else f"{label} ({url})"
    run.append(text)
    link.append(run)
    paragraph._p.append(link)


def inline(paragraph, text, font):
    cursor = 0
    for match in INLINE.finditer(text):
        if match.start() > cursor:
            set_font(paragraph.add_run(text[cursor:match.start()]), font)
        if match.group(1):
            hyperlink(paragraph, match.group(1), match.group(2), font)
        elif match.group(0).startswith(("http://", "https://")):
            hyperlink(paragraph, match.group(0), match.group(0), font)
        else:
            value = match.group(3) or match.group(4) or match.group(5)
            run = paragraph.add_run(value)
            set_font(run, font)
            run.bold = bool(match.group(3))
            run.italic = bool(match.group(4))
        cursor = match.end()
    if cursor < len(text):
        set_font(paragraph.add_run(text[cursor:]), font)


def paragraph_format(paragraph, *, heading=False):
    fmt = paragraph.paragraph_format
    fmt.space_before = Pt(12 if heading else 0)
    fmt.space_after = Pt(4 if heading else 6)
    fmt.line_spacing = 1.15 if heading else BODY_LINE_SPACING
    fmt.keep_with_next = heading
    fmt.keep_together = False
    fmt.widow_control = True


def shade(cell, fill):
    properties = cell._tc.get_or_add_tcPr()
    element = OxmlElement("w:shd")
    element.set(qn("w:val"), "clear")
    element.set(qn("w:color"), "auto")
    element.set(qn("w:fill"), fill)
    properties.append(element)


def cell_margins(table):
    properties = table._tbl.tblPr
    margins = OxmlElement("w:tblCellMar")
    for edge, value in (("top", CELL_PAD_TB), ("bottom", CELL_PAD_TB), ("left", CELL_PAD_LR), ("right", CELL_PAD_LR)):
        element = OxmlElement(f"w:{edge}")
        element.set(qn("w:w"), str(value))
        element.set(qn("w:type"), "dxa")
        margins.append(element)
    properties.append(margins)


def allow_split(row):
    properties = row._tr.get_or_add_trPr()
    for tag in ("w:cantSplit", "w:trHeight"):
        for element in list(properties.findall(qn(tag))):
            properties.remove(element)


def text_width(text: str) -> float:
    """Approximate rendered width in cm of table text at TABLE_FONT_PT."""
    return sum(0.34 if ord(char) > 0x2E7F else 0.19 for char in text)


def column_widths(rows, available: float):
    """Give each column room for its longest unbroken token, then share the rest by text volume."""
    columns = list(zip(*([re.sub(r"[`*]", "", value) for value in row] for row in rows)))
    floors = [
        min(max(1.6, max(text_width(token) for value in column for token in (value.split() or [""])) + 0.5),
            available * 0.4)
        for column in columns
    ]
    if sum(floors) > available:
        floors = [floor * available / sum(floors) for floor in floors]
    volume = [sum(text_width(value) for value in column) or 1.0 for column in columns]
    rest = available - sum(floors)
    return [floor + rest * share / sum(volume) for floor, share in zip(floors, volume)]


def row_height(row, widths) -> float:
    """Estimate one rendered row height in cm from wrapped line counts (calibrated on LibreOffice output)."""
    lines = max(
        max(1, math.ceil(text_width(re.sub(r"[`*]", "", value)) / max(width - 0.4, 0.5)))
        for value, width in zip(row, widths)
    )
    return 0.53 * lines + 0.32


def table_height(rows, widths) -> float:
    return sum(row_height(row, widths) for row in rows)


def render_blocks(outer, blocks, font):
    # Expand only the free-text box into borderless continuation rows.
    cell = outer.cell(0, 0)
    borders = outer._tbl.tblPr.find(qn("w:tblBorders"))
    if borders is not None:
        for edge in ("insideH", "insideV"):
            border = OxmlElement(f"w:{edge}")
            border.set(qn("w:val"), "nil")
            borders.append(border)
    for child in list(cell._tc):
        if child.tag != qn("w:tcPr"):
            cell._tc.remove(child)
    for index, (kind, value) in enumerate(blocks):
        if index and kind != "caption":
            row = outer.add_row()
            allow_split(row)
            cell = row.cells[0]
            for child in list(cell._tc):
                if child.tag != qn("w:tcPr"):
                    cell._tc.remove(child)
        if kind == "image":
            # The picture and its caption share one unsplittable row. No keep-with-next:
            # LibreOffice would chain this row to the following heading and table.
            outer.rows[-1]._tr.get_or_add_trPr().append(OxmlElement("w:cantSplit"))
            paragraph = cell.add_paragraph()
            paragraph_format(paragraph)
            paragraph.alignment = WD_ALIGN_PARAGRAPH.CENTER
            paragraph.add_run().add_picture(str(value), width=Cm(14))
            # A separate empty paragraph receives the caption below the picture.
            cell.add_paragraph()
            continue
        if kind == "table":
            widths = column_widths(value, 17.2)
            # LibreOffice neither honors header keep-with-next nor repeats the header
            # inside a split nested table. Keep short tables that fit on a page together
            # with their caption; let tall or long tables flow so they leave no blank page end.
            if table_height(value, widths) <= KEEP_TABLE_CM and len(value) <= KEEP_TABLE_ROWS:
                outer.rows[-1]._tr.get_or_add_trPr().append(OxmlElement("w:cantSplit"))
            table = cell.add_table(rows=len(value), cols=len(value[0]))
            table.alignment = WD_TABLE_ALIGNMENT.CENTER
            table.autofit = False
            cell_margins(table)
            # Exactly one nesting level is needed to preserve the official input box.
            # LibreOffice lays out from the grid, Word from the cells; set both.
            for column, width in zip(table.columns, widths):
                column.width = Cm(width)
            for row, data in zip(table.rows, value):
                allow_split(row)
                # Keep a data row intact across nested-table pagination, except rows too
                # tall to move without leaving a large blank page end.
                if row_height(data, widths) <= SPLIT_ROW_CM:
                    row._tr.get_or_add_trPr().append(OxmlElement("w:cantSplit"))
                for target, width in zip(row.cells, widths):
                    target.width = Cm(width)
            header = OxmlElement("w:tblHeader")
            table.rows[0]._tr.get_or_add_trPr().append(header)
            for r, data in enumerate(value):
                for c, content in enumerate(data):
                    paragraph = table.cell(r, c).paragraphs[0]
                    paragraph_format(paragraph)
                    paragraph.paragraph_format.space_after = Pt(0)
                    paragraph.paragraph_format.line_spacing = TABLE_LINE_SPACING
                    inline(paragraph, content, font)
                    for run in paragraph.runs:
                        run.font.size = Pt(TABLE_FONT_PT)
                    if r == 0:
                        paragraph.paragraph_format.keep_with_next = True
                        paragraph.alignment = WD_ALIGN_PARAGRAPH.CENTER
                        shade(table.cell(r, c), TABLE_HEADER_FILL)
                        for run in paragraph.runs:
                            run.bold = True
            # Keep the final row attached to the following caption, not the entire table.
            if index + 1 < len(blocks) and blocks[index + 1][0] == "caption":
                for target in table.rows[-1].cells:
                    target.paragraphs[-1].paragraph_format.keep_with_next = True
            continue
        # Reuse Word's trailing paragraph to keep captions adjacent to tables.
        if cell._tc[-1].tag == qn("w:p") and not cell.paragraphs[-1].text:
            paragraph = cell.paragraphs[-1]
        else:
            paragraph = cell.add_paragraph()
        paragraph_format(paragraph, heading=kind == "heading")
        if kind == "list":
            depth, marker, content = value
            paragraph.paragraph_format.left_indent = Cm(0.35 + depth * 0.4)
            paragraph.paragraph_format.first_line_indent = Cm(-0.3)
            paragraph.paragraph_format.space_after = Pt(3)
            paragraph.paragraph_format.line_spacing = 1.35
            inline(paragraph, f"{marker} {content}", font)
        else:
            inline(paragraph, value, font)
            if kind == "heading":
                for run in paragraph.runs:
                    run.bold = True
                    run.font.size = Pt(HEADING_FONT_PT)
                    run.font.color.rgb = RGBColor.from_string(HEADING_COLOR)
            if kind == "caption":
                paragraph.alignment = WD_ALIGN_PARAGRAPH.CENTER
                paragraph.paragraph_format.keep_together = True
                paragraph.paragraph_format.space_before = Pt(4)
                paragraph.paragraph_format.space_after = Pt(10)
                paragraph.paragraph_format.line_spacing = 1.15
                for run in paragraph.runs:
                    run.font.size = Pt(CAPTION_FONT_PT)
                    run.font.color.rgb = RGBColor.from_string(CAPTION_COLOR)
    # Word requires a terminating paragraph after a nested table.
    if len(cell._tc) == 1 or cell._tc[-1].tag != qn("w:p"):
        cell.add_paragraph()


def replace_run_text(paragraph, old, new):
    for run in paragraph.runs:
        if old in run.text:
            run.text = run.text.replace(old, new, 1)
            return
    # Runs may split a checkbox or underscore label. Preserve the first run's formatting.
    text = paragraph.text
    if old not in text:
        raise ValueError(f"원본 메타데이터 위치를 찾을 수 없다: {old}")
    updated = text.replace(old, new, 1)
    for run in paragraph.runs:
        run.text = ""
    paragraph.add_run(updated)


def build(source, metadata, sections, target, font):
    document = Document(source)
    if len(document.tables) != 10 or any(len(t.rows) != 1 or len(t.columns) != 1 for t in document.tables[1:9]):
        raise ValueError("공식 DOCX 10개 표 및 8개 입력표 구조가 변경되었다.")
    # Consent and personal-entry tables are untouched.
    for table, blocks in zip(document.tables[1:9], sections):
        allow_split(table.rows[0])
        render_blocks(table, blocks, font)
    project_count = 0
    for paragraph in document.paragraphs:
        text = paragraph.text
        if text.lstrip().startswith("앱명 / 프로젝트명:"):
            blank = re.search(r"_+", text)
            if not blank:
                raise ValueError("프로젝트명 원본 공란을 찾을 수 없다.")
            replace_run_text(paragraph, blank.group(), metadata["project_name"])
            project_count += 1
        elif text.startswith("참가 단위:") and metadata.get("participant_unit"):
            replace_run_text(paragraph, "□ 개인", "☑ 개인")
        elif text.startswith("참가 부문:") and metadata.get("participant_category"):
            replace_run_text(paragraph, "□ 대학(원)생", "☑ 대학(원)생")
        elif re.match(r"^[1-8]\s*\.", text.replace(" ", "")):
            paragraph.paragraph_format.keep_with_next = True
    if project_count != 3:
        raise ValueError("공식 프로젝트명 3개 위치가 일치하지 않는다.")
    for section in document.sections:
        section.page_width = Cm(21)
        section.page_height = Cm(29.7)
        # Reserve a clear band above the unchanged official footer.
        section.bottom_margin = Cm(2.4)
    core = document.core_properties
    core.author = ""
    core.last_modified_by = ""
    core.title = metadata["project_name"]
    core.subject = "개인정보·동의·서명 공란인 승인용 기획서"
    core.keywords = ""
    core.comments = ""
    target.parent.mkdir(parents=True, exist_ok=True)
    safe_output(target, (source,))
    # Save to a fresh sibling, then replace the directory entry, never a link target.
    with tempfile.TemporaryDirectory(prefix=".document-save-", dir=target.parent) as temporary:
        staged = Path(temporary) / "application.docx"
        document.save(staged)
        staged.replace(target)


def run_command(command, commands):
    result = subprocess.run(command, check=True, capture_output=True, text=True)
    commands.append({"argv": command, "stdout": result.stdout, "stderr": result.stderr})
    return result.stdout + result.stderr


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=ROOT / "docs/draft/proposal.md")
    parser.add_argument("--output", type=Path, default=ROOT / "submission/application-review.docx",
                        help="생성할 DOCX 경로. 기본값은 기존 제출 파일이다.")
    parser.add_argument("--review-dir", type=Path, default=ROOT / ".private/visual-review",
                        help="렌더 PNG·manifest 저장 디렉터리.")
    parser.add_argument("--font", default="맑은 고딕")
    parser.add_argument("--replace", action="store_true")
    args = parser.parse_args()
    args.input = internal_input(args.input)
    internal_input(SOURCE)
    if digest(SOURCE) != SOURCE_SHA256:
        raise ValueError("공식 원본 SHA-256이 변경되었다. 원본 확인 없이 진행하지 않는다.")
    metadata, sections = input_document(args.input)
    binaries = {name: shutil.which(name) for name in ("soffice", "pdfinfo", "pdftoppm")}
    if not binaries["soffice"]:
        mac_binary = Path("/Applications/LibreOffice.app/Contents/MacOS/soffice")
        if mac_binary.is_file():
            binaries["soffice"] = str(mac_binary)
    missing = [name for name, path in binaries.items() if not path]
    if missing:
        raise RuntimeError("필요한 로컬 변환 도구가 없다: " + ", ".join(missing))
    target = args.output if args.output.is_absolute() else (ROOT / args.output)
    target = target.resolve()
    if not target.is_relative_to(ROOT) or target.suffix.lower() != ".docx":
        raise ValueError("출력 DOCX는 저장소 내부 .docx 경로여야 한다.")
    target.parent.mkdir(parents=True, exist_ok=True)
    pdf = target.with_suffix(".pdf")
    review = args.review_dir if args.review_dir.is_absolute() else (ROOT / args.review_dir)
    review = review.resolve()
    if not review.is_relative_to(ROOT):
        raise ValueError("review-dir은 저장소 내부여야 한다.")
    images = review / "final"
    manifest = review / "manifest.json"
    for output_path in (target, pdf, review, images, manifest):
        safe_output(output_path, (SOURCE, args.input))
    existing = [p for p in (target, pdf, images, manifest) if p.exists()]
    if existing and not args.replace:
        raise FileExistsError("기존 산출물이 있다. 교체 지시 후 --replace를 사용한다.")
    # Invalidate previous evidence before producing changed outputs.
    if manifest.exists():
        manifest.unlink()
    if images.exists():
        shutil.rmtree(images)
    if pdf.exists():
        pdf.unlink()
    images.mkdir(parents=True, exist_ok=True)
    commands = []
    versions = {
        name: run_command([path, "--version" if name == "soffice" else "-v"], commands).strip()
        for name, path in binaries.items()
    }
    build(SOURCE, metadata, sections, target, args.font)
    with tempfile.TemporaryDirectory(prefix="hmg-document-") as temporary:
        profile = (Path(temporary) / "lo-profile").as_uri()
        run_command([
            binaries["soffice"], f"-env:UserInstallation={profile}", "--headless",
            "--convert-to", "pdf:writer_pdf_Export", "--outdir", str(target.parent), str(target),
        ], commands)
    if not pdf.is_file() or not pdf.stat().st_size:
        raise RuntimeError("LibreOffice가 대응 PDF를 생성하지 못했다.")
    info = run_command([binaries["pdfinfo"], str(pdf)], commands)
    count = re.search(r"^Pages:\s+(\d+)\s*$", info, re.MULTILINE)
    if not count or int(count.group(1)) < 1:
        raise RuntimeError("PDF 전체 페이지 수를 확인하지 못했다.")
    pages = int(count.group(1))
    run_command([binaries["pdftoppm"], "-png", "-r", "160", str(pdf), str(images / "page")], commands)
    rendered = sorted(images.glob("page-*.png"), key=lambda p: int(p.stem.split("-")[-1]))
    if len(rendered) != pages:
        raise RuntimeError("PDF 페이지 수와 렌더 이미지 수가 다르다.")
    image_records = []
    for number, path in enumerate(rendered, 1):
        renamed = images / f"page-{number:02d}.png"
        if path != renamed:
            path.rename(renamed)
        image_records.append({"page": number, "path": str(renamed.relative_to(ROOT)), "sha256": digest(renamed)})
    def record(path):
        return {"path": str(path.resolve().relative_to(ROOT)), "sha256": digest(path)}
    payload = {
        "created_at": datetime.now(timezone.utc).isoformat(),
        "status": "rendered_not_visually_reviewed",
        "privacy": "personal_information_consent_and_signatures_left_blank",
        "source_docx": record(SOURCE), "manuscript": record(args.input),
        "output_docx": record(target), "output_pdf": record(pdf),
        "font_requested": args.font, "render_dpi": 160, "total_pages": pages,
        "visually_reviewed_pages": 0, "tools": versions, "commands": commands,
        "pages": image_records,
    }
    manifest.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"생성 완료: {target.relative_to(ROOT)}, {pdf.relative_to(ROOT)}, {pages}개 PNG")
    print(f"전 페이지 시각 검수 미실행. manifest: {manifest.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
