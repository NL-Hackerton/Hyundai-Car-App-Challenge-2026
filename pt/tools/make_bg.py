"""Generate the deck background (light blue gradient + two soft shapes) used behind native slides.

Matches the Figma template's Blue mode. Usage: <python> pt/tools/make_bg.py
"""
from pathlib import Path
from PIL import Image, ImageDraw

PT = Path(__file__).resolve().parents[1]
S = 3  # supersample
W, H = 1280 * S, 720 * S


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def hexrgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def gradient(w, h, c0, c1):
    """Diagonal gradient top-left c0 -> bottom-right c1."""
    g = Image.new("RGB", (256, 1))
    for x in range(256):
        g.putpixel((x, 0), lerp(c0, c1, x / 255))
    g = g.resize((w + h, 1))
    out = Image.new("RGB", (w, h))
    # build by shearing: pixel (x, y) takes g[x + y]
    rows = []
    for y in range(0, h, S):
        row = g.crop((y, 0, y + w, 1)).resize((w, S))
        out.paste(row, (0, y))
    return out


def shape(base, box, c0, c1):
    w, h = base.size
    fill = gradient(w, h, c0, c1)
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).ellipse(box, fill=255)
    base.paste(fill, (0, 0), mask)


def main(out=PT / "assets" / "bg.png"):
    bg = gradient(W, H, hexrgb("FFFFFF"), hexrgb("DFE8FF"))
    shape(bg, (1052 * S, -330 * S, 1700 * S, 215 * S), hexrgb("E3ECFF"), hexrgb("D3DFFF"))
    shape(bg, (795 * S, 568 * S, 1430 * S, 1000 * S), hexrgb("C3DAFF"), hexrgb("BCC6FF"))
    bg = bg.resize((1920, 1080), Image.LANCZOS)
    bg.save(out, optimize=True)
    print("ok", out, out.stat().st_size)


if __name__ == "__main__":
    main()
