"""Build the PT deck from 2x slide PNGs exported from Figma.

Outputs: PPTX (full-bleed images + speaker notes from script_ko.md), PDF backup, 1280px PNG previews.
Usage: python pt/tools/build_deck.py
"""
import re
from pathlib import Path
from PIL import Image
from pptx import Presentation
from pptx.util import Emu

PT = Path(__file__).resolve().parents[1]
SLIDES = sorted((PT / "slides").glob("slide_*.png"))
NAME = "Stay-or-Go_현대차본선PT_김&양"

def notes_from_script():
    text = (PT / "script_ko.md").read_text(encoding="utf-8")
    parts = re.split(r"^## (\d+)\. ", text, flags=re.M)
    notes = {}
    for i in range(1, len(parts), 2):
        n = int(parts[i]); body = parts[i + 1].split("\n---")[0]
        title, _, rest = body.partition("\n")
        notes[n] = (f"{n}. {title.strip()}\n\n" + rest.strip()).replace("**", "")
    return notes

def main():
    assert len(SLIDES) == 14, len(SLIDES)
    notes = notes_from_script()
    prs = Presentation()
    prs.slide_width, prs.slide_height = Emu(12192000), Emu(6858000)  # 13.333 x 7.5 in, 16:9
    blank = prs.slide_layouts[6]
    for i, png in enumerate(SLIDES, 1):
        s = prs.slides.add_slide(blank)
        pic = s.shapes.add_picture(str(png), 0, 0, prs.slide_width, prs.slide_height)
        pic.name = f"Slide {i:02d}"
        s.notes_slide.notes_text_frame.text = notes.get(i, "")
    prs.save(PT / f"{NAME}.pptx")

    imgs = [Image.open(p).convert("RGB") for p in SLIDES]
    imgs[0].save(PT / f"{NAME}.pdf", save_all=True, append_images=imgs[1:], quality=90, resolution=288)

    prev = PT / "previews"; prev.mkdir(exist_ok=True)
    for i, im in enumerate(imgs, 1):
        im.resize((1280, 720), Image.LANCZOS).save(prev / f"slide_{i:02d}.png", optimize=True)
    sheet = Image.new("RGB", (4 * 480 + 5 * 16, 4 * 270 + 5 * 16), "white")
    for i, im in enumerate(imgs):
        sheet.paste(im.resize((480, 270), Image.LANCZOS), (16 + (i % 4) * 496, 16 + (i // 4) * 286))
    sheet.save(prev / "contact_sheet.png", optimize=True)
    print("ok", len(imgs), sorted(notes))

if __name__ == "__main__":
    main()
