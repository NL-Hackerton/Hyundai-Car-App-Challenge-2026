"""Make 1280px previews + contact sheet from PowerPoint's own slide export (pt/build/png, from finalize.ps1).

Usage: <python> pt/tools/previews.py
"""
from pathlib import Path
from PIL import Image

PT = Path(__file__).resolve().parents[1]
SRC = sorted((PT / "build" / "png").glob("slide_*.png"))
OUT = PT / "previews"


def main():
    assert SRC, "run finalize.ps1 first"
    OUT.mkdir(exist_ok=True)
    for old in OUT.glob("slide_*.png"):
        old.unlink()
    imgs = [Image.open(p).convert("RGB") for p in SRC]
    for p, im in zip(SRC, imgs):
        im.resize((1280, 720), Image.LANCZOS).save(OUT / p.name, optimize=True)
    cols, tw, th, gap = 4, 480, 270, 16
    rows = (len(imgs) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * tw + (cols + 1) * gap, rows * th + (rows + 1) * gap), "white")
    for i, im in enumerate(imgs):
        sheet.paste(im.resize((tw, th), Image.LANCZOS), (gap + (i % cols) * (tw + gap), gap + (i // cols) * (th + gap)))
    sheet.save(OUT / "contact_sheet.png", optimize=True)
    print("ok", len(imgs))


if __name__ == "__main__":
    main()
