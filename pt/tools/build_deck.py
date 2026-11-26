"""Build the Stay-or-Go final-round PT deck as native, editable PowerPoint slides.

The Figma export (pt/slides/*.png) is the visual reference; this script rebuilds every slide with
real text in Pretendard (so PowerPoint can embed the font), the app screenshots in pt/assets and
icons cropped from the Figma export. Speaker notes come from pt/script_ko.md.

Step 1 (this script):  pt/build/base.pptx
Step 2 (finalize.ps1): PowerPoint inserts the demo clip on slide 13, embeds fonts, exports previews.

Coordinates are in 1280x720 "design px" (1 px = 9525 EMU on a 13.333 x 7.5 in slide).
Usage: <python> pt/tools/build_deck.py
"""
import copy
import re
from pathlib import Path

from lxml import etree
from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.oxml.ns import qn
from pptx.util import Emu, Pt

PT = Path(__file__).resolve().parents[1]
A = PT / "assets"
IC = A / "icons"
OUT = PT / "build" / "base.pptx"
FONT = "Pretendard"
TOTAL = 14
TRACK = 0.025  # -2.5% letter spacing by default
CUR = [0]
FIT = []  # (slide, box, paragraphs, size, bold, line, w, h, spacing) for the overflow check

BLUE = "2563EB"
BLUE_D = "1D4ED8"
INK = "111827"
BODY = "4B5563"
MUTED = "6B7280"
TINT = "EEF3FF"
WHITE = "FFFFFF"
PURPLE = "7C6CF5"
GREEN = "15803D"
GREEN_T = "DCF5E7"

NSA = "http://schemas.openxmlformats.org/drawingml/2006/main"


_FONTS = {}


def textw(t, size, bold=False, spacing=None):
    """Rendered width in design px of a single line in Pretendard (installed OTF)."""
    import os
    from PIL import ImageFont
    key = (size, bold)
    if key not in _FONTS:
        fdir = Path(os.path.expandvars(r"%LOCALAPPDATA%/Microsoft/Windows/Fonts"))
        _FONTS[key] = ImageFont.truetype(str(fdir / f"Pretendard-{'Bold' if bold else 'Regular'}.otf"),
                                         size=max(1, round(size / 0.75 * 4)))
    sp = -TRACK * size if spacing is None else spacing
    return _FONTS[key].getlength(t) / 4 + len(t) * sp / 0.75


def E(px):
    return Emu(int(round(px * 9525)))


# ---------------------------------------------------------------- primitives

def _rpr_font(rPr, size, bold, color, grad=None, spacing=None):
    rPr.set("lang", "ko-KR")
    rPr.set("altLang", "en-US")
    rPr.set("sz", str(int(round(size * 100))))
    rPr.set("b", "1" if bold else "0")
    if spacing is None:
        spacing = -TRACK * size  # Figma-like tight Korean tracking
    rPr.set("spc", str(int(round(spacing * 100))))
    for child in list(rPr):
        rPr.remove(child)
    if grad:
        gf = etree.SubElement(rPr, qn("a:gradFill"))
        gl = etree.SubElement(gf, qn("a:gsLst"))
        for pos, c in grad:
            gs = etree.SubElement(gl, qn("a:gs"), pos=str(pos))
            etree.SubElement(gs, qn("a:srgbClr"), val=c)
        etree.SubElement(gf, qn("a:lin"), ang="0", scaled="0")
    else:
        sf = etree.SubElement(rPr, qn("a:solidFill"))
        etree.SubElement(sf, qn("a:srgbClr"), val=color)
    for tag in ("a:latin", "a:ea", "a:cs"):
        etree.SubElement(rPr, qn(tag), typeface=FONT)


GRAD = [(0, "3B9BFF"), (50000, "2563EB"), (100000, "6A5CF6")]


def text(slide, x, y, w, h, paras, size=14, color=BODY, bold=False, align="l", anchor="t",
         line=1.2, after=0, name=None, spacing=None):
    """paras: str | list of paragraphs; a paragraph is str or list of (text, opts) runs."""
    tb = slide.shapes.add_textbox(E(x), E(y), E(w), E(h))
    if name:
        tb.name = name
    tf = tb.text_frame
    tf.word_wrap = True
    tf.auto_size = None
    for side in ("left", "right", "top", "bottom"):
        setattr(tf, f"margin_{side}", 0)
    tf.vertical_anchor = {"t": MSO_ANCHOR.TOP, "m": MSO_ANCHOR.MIDDLE, "b": MSO_ANCHOR.BOTTOM}[anchor]
    if isinstance(paras, str):
        paras = [paras]
    FIT.append((CUR[0], name or "", paras, size, bold,
                line, w, h, spacing))
    for i, para in enumerate(paras):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = {"l": PP_ALIGN.LEFT, "c": PP_ALIGN.CENTER, "r": PP_ALIGN.RIGHT}[align]
        p.line_spacing = line
        if after and i < len(paras) - 1:
            p.space_after = Pt(after)
        runs = [(para, {})] if isinstance(para, str) else para
        for t, o in runs:
            r = p.add_run()
            r.text = t
            _rpr_font(r._r.get_or_add_rPr(), o.get("size", size), o.get("bold", bold),
                      o.get("color", color), o.get("grad"), o.get("spacing", spacing))
    # endParaRPr so empty-line metrics also use Pretendard
    return tb


def _shadow(shape, blur=22, dist=6, alpha=9):
    spPr = shape._element.spPr
    eff = etree.SubElement(spPr, qn("a:effectLst"))
    sh = etree.SubElement(eff, qn("a:outerShdw"), blurRad=str(int(E(blur))), dist=str(int(E(dist))),
                          dir="5400000", algn="t", rotWithShape="0")
    c = etree.SubElement(sh, qn("a:srgbClr"), val="1E3A8A")
    etree.SubElement(c, qn("a:alpha"), val=str(alpha * 1000))


def box(slide, x, y, w, h, fill=WHITE, radius=18, shadow=True, line=None, name=None):
    shp = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE if radius else MSO_SHAPE.RECTANGLE,
                                 E(x), E(y), E(w), E(h))
    if radius:
        shp.adjustments[0] = min(0.5, radius / min(w, h))
    shp.fill.solid()
    shp.fill.fore_color.rgb = RGBColor.from_string(fill)
    if line:
        shp.line.color.rgb = RGBColor.from_string(line)
        shp.line.width = Pt(0.75)
    else:
        shp.line.fill.background()
    if shadow:
        _shadow(shp)
    else:
        etree.SubElement(shp._element.spPr, qn("a:effectLst"))
    if name:
        shp.name = name
    shp.text_frame.text = ""
    return shp


def pic(slide, path, x, y, w, h, radius=None, name=None, shadow=False):
    p = slide.shapes.add_picture(str(path), E(x), E(y), E(w), E(h))
    if name:
        p.name = name
    if radius:
        geom = p._element.spPr.find(qn("a:prstGeom"))
        geom.set("prst", "roundRect")
        av = geom.find(qn("a:avLst"))
        if av is None:
            av = etree.SubElement(geom, qn("a:avLst"))
        etree.SubElement(av, qn("a:gd"), name="adj", fmla=f"val {int(radius / min(w, h) * 100000)}")
    if shadow:
        _shadow(p, blur=28, dist=8, alpha=14)
    return p


def badge(slide, x, y, label, w=40, h=40):
    box(slide, x, y, w, h, fill=BLUE, radius=10, shadow=False)
    text(slide, x, y, w, h, label, size=12, color=WHITE, bold=True, align="c", anchor="m")


def chip(slide, x, y, label, h=34, size=13, fill=TINT, color=INK):
    w = 28 + textw(label, size, True)
    box(slide, x, y, w, h, fill=fill, radius=9, shadow=False)
    text(slide, x, y, w, h, label, size=size, color=color, bold=True, align="c", anchor="m")
    return w


def hline(slide, x, y, w, color="E5E7EB"):
    box(slide, x, y, w, 1, fill=color, radius=0, shadow=False)


def chevron(slide, x, y, size=20):
    text(slide, x, y, 20, 30, "›", size=size, color=BLUE, bold=True, align="c", anchor="m")


# ---------------------------------------------------------------- slide frame

def background(slide):
    pic(slide, A / "bg.png", 0, 0, 1280, 720, name="Background")


def brand(slide):
    pic(slide, IC / "logo.png", 59, 28, 38, 38, name="Logo")
    bw = textw("Stay-or-Go", 18, True)
    text(slide, 106, 33, bw + 6, 28, "Stay-or-Go", size=18, color=INK, bold=True, anchor="m")
    bx = 106 + bw + 10
    box(slide, bx, 38, 43, 19, fill=PURPLE, radius=6, shadow=False)
    text(slide, bx, 38, 43, 19, "Pleos", size=9, color=WHITE, bold=True, align="c", anchor="m")


MOVED = {}  # slide -> lines moved off the slide into the speaker notes


def moved(n, *lines):
    MOVED.setdefault(n, []).extend(lines)


def frame(slide, n, section, label, title, subtitle):
    background(slide)
    brand(slide)
    text(slide, 59, 102, 1000, 22, label, size=12, color=BLUE_D, bold=True, spacing=1)
    text(slide, 59, 130, 1170, 60, title, size=36, color=INK, bold=True, anchor="m", name="Title")
    text(slide, 59, 200, 1170, 28, subtitle, size=15, color=BODY, anchor="m")
    box(slide, 1145, 689, 18, 4, fill=BLUE, radius=2, shadow=False)
    text(slide, 1165, 682, 56, 18, f"{n:02d} / {TOTAL}", size=10, color=MUTED, align="r", anchor="m")


def source_line(slide, txt):
    """At most one source line per slide, bottom-left."""
    text(slide, 59, 662, 1060, 18, txt, size=10, color=MUTED, anchor="m")


def bottom_bar(slide, y, tag, msg, w=1162, h=66):
    box(slide, 59, y, w, h)
    badge(slide, 80, y + (h - 40) / 2, tag)
    text(slide, 136, y, w - 160, h, msg, size=16, color=INK, bold=True, anchor="m")


def statement_bar(slide, y, msg, chips):
    box(slide, 59, y, 1162, 80)
    mw = textw(msg, 19, True)
    text(slide, 86, y, mw + 10, 80, msg, size=19, color=INK, bold=True, anchor="m")
    x = 86 + mw + 36
    for lab in chips:
        x += chip(slide, x, y + 23, lab) + 10


def step_cards(slide, items, y=254, h=300):
    """Four cards: number label, icon tile, heading, one short key line."""
    xs = [59, 361, 663, 965]
    for i, (num, icon, head, body) in enumerate(items):
        x = xs[i]
        box(slide, x, y, 256, h)
        text(slide, x + 22, y + 26, 200, 22, num, size=14, color=BLUE_D, bold=True)
        pic(slide, IC / f"{icon}.png", x + 22, y + 60, 72, 72)
        text(slide, x + 22, y + 150, 220, 30, head, size=20, color=INK, bold=True, anchor="m")
        text(slide, x + 22, y + 194, 216, h - 205, body, size=15.5, color=BODY, line=1.3)
        if i < 3:
            chevron(slide, x + 266, y + h / 2 - 15)


def three_cards(slide, items, y=249, h=280, big=False):
    """items: (label, heading, body, source|None). big=True renders heading as a big number + unit."""
    xs = [59, 452, 845]
    for i, (label, head, body, src) in enumerate(items):
        x = xs[i]
        box(slide, x, y, 376, h)
        text(slide, x + 27, y + 24, 330, 22, label, size=13, color=BLUE_D, bold=True, anchor="m")
        if big:
            num, unit = head
            text(slide, x + 27, y + 54, 330, 86,
                 [[(num, {"size": 60, "bold": True, "color": BLUE}), ("  " + unit, {"size": 24, "bold": True, "color": BLUE})]],
                 anchor="m", line=1.0)
            by = y + 154
        else:
            text(slide, x + 27, y + 56, 330, 36, head, size=22, color=INK, bold=True, anchor="m")
            by = y + 106
        text(slide, x + 27, by, 324, y + h - by - (48 if src else 20), body, size=16, color=BODY, line=1.35)
        if src:
            text(slide, x + 27, y + h - 42, 324, 20, src, size=11, color=MUTED, anchor="m")


# ---------------------------------------------------------------- slides

def s01(s):
    background(s)
    brand(s)
    pw = textw("현대자동차그룹 차량용 앱 개발 공모전 · 본선", 11, True) + 28
    box(s, 1221 - pw, 36, pw, 26, fill="E4ECFF", radius=13, shadow=False)
    text(s, 1221 - pw, 36, pw, 26, "현대자동차그룹 차량용 앱 개발 공모전 · 본선", size=11, color=BLUE_D, bold=True,
         align="c", anchor="m")
    text(s, 59, 245, 640, 90, "Stay-or-Go", size=60, color=INK, bold=True, anchor="m", name="Title")
    text(s, 59, 338, 640, 50, "더 채울까, 지금 떠날까", size=28, bold=True, color=BLUE, anchor="m")
    text(s, 59, 400, 640, 30, "급속충전 중, 차량 신호와 순정 내비로 출발 시점을 권하는 Pleos 앱", size=16, color=BODY,
         anchor="m")
    feats = [("f_chart", "차량 신호", "SOC · 충전전력", 59), ("f_leaf", "순정 내비", "도착 예상 SOC", 237),
             ("f_refresh", "정차 · 충전 중", "에만 조작", 406)]
    for icon, a, b, x in feats:
        pic(s, IC / f"{icon}.png", x, 462, 44, 44)
        text(s, x + 54, 462, 130, 44, [a, b], size=13.5, color=INK, bold=True, anchor="m", line=1.15)
    pic(s, A / "app_go86_v2.jpg", 734, 249, 481, 271, radius=10, shadow=True, name="App screenshot")
    for (x, y, w, h, small, big) in [(728, 119, 226, 88, "결정하는 순간", "Stay ↔ Go"),
                                     (994, 83, 227, 88, "추가 장치", "0개"),
                                     (741, 483, 227, 88, "동작 확인", "에뮬레이터")]:
        box(s, x, y, w, h, radius=14)
        text(s, x + 16, y + 14, w - 30, 20, small, size=12, color=MUTED, anchor="m")
        text(s, x + 16, y + 38, w - 30, 38, big, size=24, color=BLUE, bold=True, anchor="m")
    text(s, 59, 678, 700, 24, "팀 김&양 · 김유빈 · 양하정   |   접수번호 U-057", size=12, color=MUTED, anchor="m")
    moved(1, "표지 카드 「결정하는 순간」의 보조 문구: 충전 속도가 떨어질 때",
          "표지 하단: 2026.12 본선 PT · 현대자동차그룹 UX 스튜디오")


def s02(s):
    background(s)
    text(s, 140, 240, 1000, 170, [[("Stay or Go?", {"grad": GRAD})]], size=110, bold=True, align="c",
         anchor="m", name="Title", line=1.0)
    text(s, 140, 424, 1000, 36, "휴게소 급속충전 중, 충전 속도가 떨어지는 그 순간", size=20, color=BODY, align="c",
         anchor="m")
    moved(2, "출발 전 경로 계획이 아니라, 이미 꽂혀 있는 충전 세션 안의 결정")


def s03(s):
    frame(s, 3, "01 · 문제", "01 / PROBLEM · 왜 어려운가", "판단에 필요한 정보가 세 곳에 흩어져 있다",
          "목적지에 필요한 SOC · 지금 충전 속도 · 다음 충전기 조건을 운전자가 머릿속에서 조합한다")
    for y, q in [(249, "“20→80%에 1시간 46분… 옮기자 21분”"), (455, "“앱 4개와 OBD 동글을 오갔다”")]:
        box(s, 59, y, 633, 188)
        text(s, 90, y, 580, 188, q, size=26, color=INK, bold=True, anchor="m")
    box(s, 715, 249, 506, 345)
    text(s, 742, 300, 470, 140, [[("29.0", {"size": 110, "bold": True, "color": BLUE}),
                                  (" %", {"size": 44, "bold": True, "color": BLUE})]], anchor="m", line=1.0)
    text(s, 742, 456, 450, 40, "전기차 이용 불편 1위 = 충전", size=24, color=BLUE, bold=True, anchor="m")
    x = 715
    for i, lab in enumerate(["차량 화면 · SOC", "내비 · 도착 SOC", "충전 앱 · 충전기"]):
        box(s, x, 607, 141, 36, radius=9, shadow=False)
        text(s, x, 607, 141, 36, lab, size=13, color=INK, bold=True, align="c", anchor="m")
        if i < 2:
            chevron(s, x + 151, 610)
        x += 182.5
    source_line(s, "출처: 이용자 블로그(2025) [3] · 해외 기아 EV 동호회(2023) [6] · 환경부·한국환경공단 이용자 설문 1,000명 [7] (기획서 표 1)")
    moved(3, "사례 1 [3]: 100 kW 충전기에서 멈추고 다음 휴게소 124 kW로 이동 — 현재 전력과 다음 충전기를 운전자가 직접 비교했다.",
          "사례 2 [6]: 경로 앱 · 충전소 앱 · 충전사 앱 · 순정 내비를 오가며 정류장별 충전 목표를 정했다.",
          "설문 [7]: 응답자 1,000명이 꼽은 전기차 이용 불편 사항 가운데 충전이 가장 많았다(29.0%). 글쓴이 식별 정보는 옮기지 않았다.")


def s04(s):
    frame(s, 4, "02 · 해법", "02 / SOLUTION · 한 화면 해법", "정차 · 충전 중, 한 화면에 Stay와 Go",
          "운전자가 앱을 열면 SOC · 충전전력 · 도착 예상 SOC · 권고를 한 번에 보여 준다")
    pic(s, A / "app_go86_v2.jpg", 59, 253, 741, 417, radius=12, shadow=True, name="App screenshot")
    box(s, 822, 254, 399, 416)
    text(s, 849, 282, 340, 34, "화면이 답하는 질문", size=20, color=INK, bold=True, anchor="m")
    rows = [("지금 얼마나 찼나", "현재 SOC"), ("아직 빨리 차나", "충전전력"), ("지금 가면 몇 %?", "도착 예상 SOC"),
            ("그래서?", "Stay / Go 권고")]
    y = 340
    for i, (q, a) in enumerate(rows):
        last = i == len(rows) - 1
        if last:
            box(s, 849, y + 6, 345, 56, fill=BLUE, radius=8, shadow=False)
        elif i:
            hline(s, 849, y, 345)
        text(s, 864, y + 6, 180, 56, q, size=17, color=WHITE if last else INK, anchor="m")
        text(s, 1000, y + 6, 180, 56, a, size=17, color=WHITE if last else BLUE, bold=True, align="r", anchor="m")
        y += 72
    moved(4, "화면: Pleos Connect 에뮬레이터 실제 화면(SOC 86%, 2026-11-25).",
          "충전 전력 옆 「추정」은 배터리 잔량 변화로 계산했다는 뜻이다. 정차 · 충전 중에만 조작할 수 있다.")


def s05(s):
    frame(s, 5, "02 · 해법", "02 / HOW · 판단 방법", "네 가지 신호를 Stay · Go 하나로",
          "도착 예상 SOC는 앱이 따로 추정하지 않고 순정 내비의 경로 계산값을 그대로 쓴다")
    step_cards(s, [
        ("01", "chart", "현재 SOC", ["SOC가 바뀔 때마다", "다시 판정"]),
        ("02", "grid", "충전전력 곡선", ["P < 0.5 × Pmax", "→ 충전 후반"]),
        ("03", "clock", "도착 예상 SOC", ["순정 내비 값을", "예비 15%p와 비교"]),
        ("04", "check", "다음 충전기", ["여기서 채우기 vs", "경유지에서 채우기"]),
    ])
    bottom_bar(s, 580, "규칙", "A < m → Stay   ·   A ≥ m 이고 P < 0.5·Pmax → Go   ·   전력을 모르면 SOC 80% 기준")
    moved(5, "01 현재 SOC: Vehicle SDK SOC 구독, SOC가 바뀔 때마다 다시 판정.",
          "02 충전전력 곡선: P = 지금 충전전력, Pmax = 이 세션 최대. P < 0.5 × Pmax 이면 충전 후반.",
          "03 도착 예상 SOC: NaviHelper requestTrip으로 지금 출발 시 도착 A를 받고 예비 m(15%p)과 비교.",
          "04 다음 충전기: 부족분 ΔE를 여기서 채우는 시간 vs 경유지에서 ΔE ÷ Pc + 5분.",
          "규칙 근거: 기획서 3항.")


def s06(s):
    frame(s, 6, "02 · 해법", "02 / EXAMPLE · 판단 예시 = 곧 보실 시연", "충전 곡선 위에서 Stay가 Go로 바뀐다",
          "시연과 같은 조건: 부산시청 375 km 경로")
    items = [("SOC 78%", "users", "Stay · 더 충전", "도착 8% < 예비 15%"),
             ("SOC 83%", "leaf", "Stay 유지", "95 kW로 하락, 도착 12%"),
             ("SOC 86%", "refresh", "Go · 지금 출발", "도착 15% · 내비로 경로 전달")]
    xs = [59, 462, 865]
    for i, (lab, icon, head, body) in enumerate(items):
        x = xs[i]
        go = i == 2
        box(s, x, 254, 356, 280, line=BLUE if go else None)
        text(s, x + 24, 280, 300, 24, lab, size=16, color=BLUE_D, bold=True, anchor="m")
        pic(s, IC / f"{icon}.png", x + 24, 318, 72, 72)
        text(s, x + 24, 408, 310, 36, head, size=22, color=BLUE if go else INK, bold=True, anchor="m")
        text(s, x + 24, 456, 316, 40, body, size=17, color=BODY, anchor="m")
        if i < 2:
            chevron(s, x + 368, 380)
    bottom_bar(s, 566, "원칙", "시간 단축을 약속하지 않는다 — “속도가 세션 최대의 41%, 지금 가도 15%로 도착”만 말한다", w=975)
    text(s, 1046, 566, 175, 66, [[("Stay → Go", {"grad": GRAD})]], size=24, bold=True, align="r", anchor="m")
    moved(6, "충전 곡선(가상 콘솔 입력): SOC 78%까지 230 kW, 82% 이후 95 kW.",
          "78%: 230 kW = 세션 최대, 지금 가면 도착 8% < 예비 15% → Stay.",
          "83%: 전력 95 kW로 하락(충전 후반)이지만 도착 12% < 15% → Stay 유지.",
          "86%: 도착 15% ≥ 15%, 95 kW < 115 kW(판정선) → Go 강조, 내비로 경로 전달.")


def s07(s):
    frame(s, 7, "03 · 차량 특화", "03 / VEHICLE · Pleos SDK 실측", "일반 앱으로 차량 · 내비 신호를 받았다",
          "Pleos Connect v2.1.0 에뮬레이터 · 시스템 앱이 아닌 일반 앱으로 실측")
    box(s, 59, 249, 633, 393)
    text(s, 88, 278, 400, 24, "SOC 구독 · Vehicle SDK", size=14, color=BLUE_D, bold=True, anchor="m")
    box(s, 618, 278, 48, 26, fill=GREEN_T, radius=13, shadow=False)
    text(s, 618, 278, 48, 26, "사실", size=12, color=GREEN, bold=True, align="c", anchor="m")
    text(s, 88, 330, 590, 150, [[("11/11", {"size": 120, "bold": True, "color": BLUE}),
                                 ("  회 수신", {"size": 26, "bold": True, "color": BLUE})]], anchor="m", line=1.0)
    text(s, 88, 510, 580, 34, "SOC 변화 11번을 모두 받았다", size=19, color=INK, bold=True, anchor="m")
    box(s, 715, 249, 506, 393)
    text(s, 742, 276, 450, 34, "실측한 것 · 못 한 것", size=20, color=INK, bold=True, anchor="m")
    rows = [("잔량 변화 → 충전전력", "150 → 148.6 kW"), ("도착 예상 SOC", "72% → 67%"),
            ("충전 경유지 삽입", "직행 10% 미만일 때"), ("주행 화면 제한", "1초 안에 전환"),
            ("충전 상태 · 순간전력", "측정 못 함 → 추정")]
    y = 322
    for i, (a, b) in enumerate(rows):
        last = i == len(rows) - 1
        if last:
            box(s, 742, y + 6, 452, 50, fill=BLUE, radius=8, shadow=False)
        elif i:
            hline(s, 742, y, 452)
        text(s, 758, y + 6, 230, 50, a, size=16, color=WHITE if last else INK, anchor="m")
        text(s, 980, y + 6, 200, 50, b, size=16, color=WHITE if last else BLUE, bold=True, align="r", anchor="m")
        y += 60
    moved(7, "실측 조건: 비시스템 앱 · 가상 콘솔 입력, 2026-11-06 4~7차 실행.",
          "SOC를 2초마다 72→83%로 바꾸자 모두 한 번씩 수신(간격 2,007~2,019 ms). 출처: 기획서 표 6 · 표 10 · probe-log-excerpt.txt.",
          "도착 예상 SOC 72% → 67%는 26.1 km 경로 기준.",
          "충전 상태 · 순간 충전전력은 가상 콘솔에 설정 항목이 없어 측정하지 못했다 → 배터리 잔량 변화로 추정(화면에 「추정」 표시). 실차 · Pleos Devbox에서 확인할 항목.")


def s08(s):
    frame(s, 8, "04 · 안전", "04 / SAFETY · 안전 · 예외 처리", "정차 · 충전 중에만. 모르면 모른다고",
          "주의 분산을 만들지 않고, 확인할 수 없는 상태를 확인된 것처럼 보이지 않는다")
    cards = [(59, 249, "주행 중", "조작 화면 숨김 · 요약 한 줄"),
             (649, 249, "실행 방식", "사용자가 직접 연다"),
             (59, 459, "목적지 · 전력을 모를 때", "Go 잠금 · SOC 80% 기준"),
             (649, 459, "충전소 · 점유 시간", "고장 · 대기는 단정 안 함")]
    for x, y, lab, key in cards:
        box(s, x, y, 572, 190)
        text(s, x + 28, y + 22, 500, 28, lab, size=15, color=BLUE_D, bold=True, anchor="m")
        box(s, x + 28, y + 64, 516, 98, fill="F1F5FF", radius=12, shadow=False)
        text(s, x + 28, y + 64, 516, 98, key, size=24, color=INK, bold=True, align="c", anchor="m")
    moved(8, "주행 중: CarUxRestrictionsManager가 주의 분산 최적화(DO)를 요구하면 즉시 전환 — 실측 1초 안. 근거: Pleos 주행 제한 지침 [23].",
          "실행 방식: 충전 시작 · 도어 · 도착 때 자동 실행 없음 · 차 밖 푸시 없음 · 케이블 분리는 운전자가. 근거: 기획서 3항.",
          "목적지 · 전력을 모를 때: 목적지가 없으면 설정을 요청하고, 전력을 모르면 규칙 6(SOC 80%)으로 대체해 그 사실을 표시. 근거: 기획서 표 5.",
          "충전소 · 점유 시간: 충전 확인 50분 뒤 1시간 점유 제한을 보조 문구로 알림 · 법률 자문 용도 아님. 근거: 시행령 제18조의8 [13].")


def s09(s):
    frame(s, 9, "05 · 차별점", "05 / DIFFERENT · 기존 기능과의 차이", "“찼으면 출발”은 새롭지 않다. 차이는 어디서, 무엇으로",
          "공개 문서 기준 비교 — 「확인 못 함」은 기능이 없다는 단정이 아니다")
    three_cards(s, [
        ("순정 연동 · 해외", "Tesla 내비", ["충전 중 남은 시간 표시", "Tesla 차량 전용"], None),
        ("앱 · 동글", "ABRP", ["실시간 계획 갱신", "동글 또는 연동 차종 필요"], None),
        ("순정 · 현대 · 기아", "EV 경로 플래너", ["출발 전 · 주행 중 경유 안내", "충전 중 출발 권고는 확인 못 함"], None),
    ], h=240)
    statement_bar(s, 520, "현대차그룹 차 안에서, 장치 없이",
                  ["추가 장치 0", "순정 내비 재경로", "1시간 점유 고지", "80% 자동 종료 인식", "다음 충전기 비교"])
    source_line(s, "출처: Tesla [17] · ABRP [18][19] · 현대 · 기아 EV 경로 플래너 [20]–[22] (기획서 표 3)")
    moved(9, "Tesla 내비 [17]: 수퍼차저 충전 중 다음 정차까지 필요한 남은 충전 시간을 표시한다. Tesla 차량 전용.",
          "ABRP [18][19]: 실시간 SOC로 정차 계획을 갱신한다. OBD 동글 또는 연동 차종과 휴대전화가 필요하다.",
          "현대 · 기아 EV 경로 플래너 [20]–[22]: 출발 전 · 주행 중 충전소 경유와 도착 SOC를 안내한다. 충전 중 출발 시점 권고는 공개 문서에서 확인 못 함.")


def s10(s):
    frame(s, 10, "06 · 사업성", "06 / BUSINESS · 사업 모델", "운전자는 무료, 회전율이 오르는 충전사업자가 낸다",
          "효과는 아직 가설이다 — 고객 수요 · 매출은 검증하지 않았고 파일럿에서 측정한다")
    three_cards(s, [
        ("대기 차가 있을 때 1분당 매출 차이", ("917", "원/분"), "매출 차이이며 이익이 아니다", None),
        ("2단계 손익분기", ("9", "곳"), "사이트당 월 30만 원 연동료 가정", None),
        ("파일럿 진입 조건", ("55", "회/월"), "후보 사이트의 월 Go 선택 횟수", None),
    ], h=280, big=True)
    bottom_bar(s, 566, "대안", "독립 앱 경로가 안 되면, 판단 로직을 순정 EV 경로 플래너에 제안한다")
    moved(10, "917원/분: 200 kW 대 60 kW 충전 · 393.1원/kWh 기준. 매출이며 이익이 아니다. [계산] 기획서 6항 · 요금 [44].",
          "9곳: 월 운영비 약 259만 원 ÷ 사이트당 연동 이용료 월 30만 원. [가정] 기획서 6항.",
          "55회/월: 후보 사이트의 월 Go 선택 횟수(1회 6분 단축 가정 → 월 327분). [가정] 기획서 표 8.",
          "대안: 라이선스 · 기능 이관 형태(기획서 표 8).")


def s11(s):
    frame(s, 11, "07 · 로드맵", "07 / ROADMAP · 단계와 중단 기준", "검증 → 출시 → 파일럿 → 순정 제안",
          "단계마다 진행 · 중단 기준을 미리 정했다")
    step_cards(s, [
        ("0단계", "chart", "검증", ["사용성 시험 12명", "앱 마켓 심사"]),
        ("1단계", "grid", "출시", ["무료 · 재사용률", "20% 미만이면 축소"]),
        ("2단계", "clock", "충전사업자 파일럿", ["월 Go 55회 이상", "사이트 1곳 · 무상"]),
        ("3단계", "check", "순정 기능 제안", ["순정에 같은 기능이", "생기면 로직 제안으로"]),
    ])
    bottom_bar(s, 580, "다음", "본선 직후: 실차 또는 Pleos Devbox에서 충전 상태 · 순간 충전전력 확인")
    moved(11, "0단계 검증: 2026.12~2027 상반기, 사용성 시험 12명 · 앱 마켓 심사 · 휴게소 운영사 2곳 제안.",
          "1단계 출시: 국내 Pleos 전기차 출시 이후 무료, 3개월 재사용률 20% 미만이면 축소.",
          "2단계 파일럿: 월 Go 55회 이상 사이트, E-pit 포함 1곳 · 무상 → 사이트 연동 이용료.",
          "3단계 순정 기능 제안: 2단계와 병행, 순정에 같은 기능이 생기면 로직 제안으로 전환.",
          "근거: 기획서 표 7.")


def s12(s):
    frame(s, 12, "팀", "TEAM · 김&양", "둘이서 기획하고, 만들고, 실측했다",
          "AI 도구를 조사 · 코드 작성 · 검증에 활용했다. 실제 사용자 조사와 실차 검증은 아직이다")
    three_cards(s, [
        ("팀 김&양", "김유빈 · 양하정", "기획 · 앱 개발 · 에뮬레이터 검증. 본선 발표 김유빈, 시연 양하정.", None),
        ("만든 것", "Stay-or-Go 앱", "규칙 1~6 · 전력 추정 · 50분 점유 고지 · Go 경로 전달 · 주행 중 숨김 · 단위 테스트 24건 · release APK",
         None),
        ("검증한 방법", "프로브 앱 실측", "Pleos Connect v2.1.0 에뮬레이터에서 SOC · 잔량 · 내비 · 화면 제한을 로그로 확인 (2~7차 실행)",
         None),
    ], h=290)
    statement_bar(s, 566, "아직: 실사용자 조사 · 실차 검증",
                  ["사용성 시험 12명", "실차 · Devbox", "앱 마켓 심사", "운영사 제안"])
    moved(12, "저장소 위치: 앱 apps/staygo · apk/, 프로브 apps/pleos-stayorgo-probe.")


def s13(s):
    frame(s, 13, "시연", "LIVE DEMO · 이어서 시연 5분 · 양하정", "이제 실제 Pleos 에뮬레이터에서 보여 드립니다",
          "라이브가 안 되면 아래 녹화 영상(1배속 · 65초)을 클릭해 재생한다")
    box(s, 59, 249, 741, 417, fill="0B1530", radius=12, name="VIDEO_SLOT")
    text(s, 59, 249, 741, 417, "시연 영상", size=20, color="9CA3AF", align="c", anchor="m", name="VIDEO_LABEL")
    box(s, 822, 249, 399, 417)
    text(s, 850, 278, 350, 34, "시연에서 보실 장면", size=20, color=INK, bold=True, anchor="m")
    steps = ["Stay · 도착 SOC 부족", "Go 전환 · SOC 86%", "Go 탭 → 순정 내비", "D단 → 주행 잠금"]
    y = 340
    for i, a in enumerate(steps):
        box(s, 850, y + 7, 36, 36, fill=BLUE if i == 1 else TINT, radius=18, shadow=False)
        text(s, 850, y + 7, 36, 36, str(i + 1), size=15, color=WHITE if i == 1 else BLUE_D, bold=True, align="c",
             anchor="m")
        text(s, 900, y, 300, 50, a, size=18, color=INK, bold=True, anchor="m")
        y += 74
    moved(13, "장면 1: SOC 78~85%, 도착 예비 15%p 미달 → Stay.",
          "장면 2: SOC 86%, 도착 15% · 충전 후반 → Go.",
          "장면 3: Go 탭 → 목적지 경로를 순정 내비에 전달.",
          "장면 4: D단 → 조작 화면 숨김 · 요약 한 줄.",
          "영상: 2026-11-25 Pleos Connect v2.1.0 에뮬레이터, 1배속 실시간, 무음. SOC 83~85% 구간 1분 15초를 잘라 냈다(영상에 표시).")


def s14(s):
    background(s)
    text(s, 140, 250, 1000, 180, [[("Q&A", {"grad": GRAD})]], size=130, bold=True, align="c", anchor="m",
         name="Title", line=1.0)
    text(s, 140, 446, 1000, 36, "충전 후반, 떠날 때를 차가 먼저 알려 준다 — Stay-or-Go", size=20, color=BODY,
         align="c", anchor="m")


def fit_check():
    """Measure every text box with the installed Pretendard OTF and report wraps/overflow."""
    import os
    from PIL import ImageFont
    fdir = Path(os.path.expandvars(r"%LOCALAPPDATA%\Microsoft\Windows\Fonts"))
    cache = {}

    def font(size, bold):
        key = (size, bold)
        if key not in cache:
            cache[key] = ImageFont.truetype(str(fdir / f"Pretendard-{'Bold' if bold else 'Regular'}.otf"),
                                            size=max(1, round(size / 0.75 * 4)))  # 4x for precision
        return cache[key]

    def width(runs, default_size, default_bold, spacing):
        tot = 0.0
        for t, o in runs:
            sz = o.get("size", default_size)
            b = o.get("bold", default_bold)
            sp = o.get("spacing", spacing)
            sp = -TRACK * sz if sp is None else sp
            tot += font(sz, b).getlength(t) / 4 + len(t) * sp / 0.75
        return tot

    problems = 0
    for sl, name, paras, size, bold, line, w, h, spacing in FIT:
        lines = 0
        hpx = 0.0
        for para in paras:
            runs = [(para, {})] if isinstance(para, str) else para
            wid = width(runs, size, bold, spacing)
            n = 1
            if wid > w + 0.5:
                # greedy word wrap on the concatenated text with the first run's style
                words = "".join(t for t, _ in runs).split(" ")
                cur, n = "", 1
                for wd in words:
                    trial = (cur + " " + wd).strip()
                    if width([(trial, runs[0][1])], size, bold, spacing) > w:
                        n += 1
                        cur = wd
                    else:
                        cur = trial
            psize = max(o.get("size", size) for _, o in runs)
            lines += n
            hpx += n * psize / 0.75 * 1.2 * line
        if lines == len(paras):
            continue
        txt = " / ".join("".join(t for t, _ in ([(p, {})] if isinstance(p, str) else p)) for p in paras)
        if hpx * 0.85 > h:
            problems += 1
            print(f"  slide {sl:2d}: overflow  h={h} need={hpx:.0f} lines={lines}  {txt[:70]}")
        elif lines > len(paras):
            print(f"  slide {sl:2d}: wraps {lines - len(paras)}x (fits)  {txt[:70]}")
    print("fit problems:", problems)
    return problems


SLIDES = [s01, s02, s03, s04, s05, s06, s07, s08, s09, s10, s11, s12, s13, s14]


def notes_from_script():
    text_ = (PT / "script_ko.md").read_text(encoding="utf-8")
    parts = re.split(r"^## (\d+)\. ", text_, flags=re.M)
    notes = {}
    for i in range(1, len(parts), 2):
        n = int(parts[i])
        body = parts[i + 1].split("\n---")[0]
        title, _, rest = body.partition("\n")
        notes[n] = (f"{n}. {title.strip()}\n\n" + rest.strip()).replace("**", "").replace("`", "")
    return notes


def main():
    assert len(SLIDES) == TOTAL
    notes = notes_from_script()
    prs = Presentation()
    prs.slide_width, prs.slide_height = Emu(12192000), Emu(6858000)
    # theme fonts -> Pretendard so notes and any new text default to the embedded font
    theme_part = [p for p in prs.part.package.iter_parts() if p.partname.endswith("theme1.xml")]
    blank = prs.slide_layouts[6]
    for i, fn in enumerate(SLIDES, 1):
        s = prs.slides.add_slide(blank)
        CUR[0] = i
        fn(s)
        tf = s.notes_slide.notes_text_frame
        note = notes.get(i, "")
        if MOVED.get(i):
            note += "\n\n[슬라이드에서 옮긴 세부 · 출처]\n" + "\n".join("- " + m for m in MOVED[i])
        tf.text = note
        for p in tf.paragraphs:
            for r in p.runs:
                r.font.name = FONT
                r._r.get_or_add_rPr().append(etree.Element(qn("a:ea"), typeface=FONT))
    core = prs.core_properties
    core.title = "Stay-or-Go — 현대자동차그룹 차량용 앱 개발 공모전 본선 PT"
    core.author = "팀 김&양"
    core.subject = "접수번호 U-057"
    OUT.parent.mkdir(exist_ok=True)
    prs.save(OUT)
    print("ok", OUT, sorted(notes))
    fit_check()


if __name__ == "__main__":
    main()
