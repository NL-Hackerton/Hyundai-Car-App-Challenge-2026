#!/usr/bin/env python3
"""Check that every text/background color pair StayGo uses is >= 4.5:1 (Pleos guide) in Light and Dark.

    python3 apps/staygo/tools/check_contrast.py      # exit 1 if any pair fails
"""
import pathlib
import re
import sys

RES = pathlib.Path(__file__).resolve().parents[1] / "app/src/main/res"
# (foreground, background) as used by the layouts and MainActivity
PAIRS = [
    ("sg_text", "sg_card"), ("sg_text_2", "sg_card"), ("sg_text_3", "sg_card"), ("sg_text_3", "sg_bg"),
    ("sg_text", "sg_bg"), ("sg_text", "sg_card_high"), ("sg_text_2", "sg_card_high"),
    ("sg_on_go", "sg_go"), ("sg_on_stay", "sg_stay"), ("sg_on_warn", "sg_warn"),
    ("sg_go_text", "sg_card"), ("sg_on_toast", "sg_toast"),
    ("color_static_light_100", "color_overlay_driving"),
]


def load(path, base=None):
    colors = dict(base or {})
    for name, value in re.findall(r'<color name="([^"]+)">([^<]+)</color>', path.read_text()):
        colors[name] = value.strip()
    return colors


def resolve(colors, name, depth=0):
    v = colors[name]
    return resolve(colors, v[len("@color/"):], depth + 1) if v.startswith("@color/") and depth < 10 else v


def rgba(h):
    h = h.lstrip("#")
    if len(h) == 6:
        h = "FF" + h
    return int(h[0:2], 16) / 255, [int(h[i:i + 2], 16) for i in (2, 4, 6)]


def over(fg, bg):
    a, f = rgba(fg)
    _, b = rgba(bg)
    return [f[i] * a + b[i] * (1 - a) for i in range(3)]


def lum(rgb):
    def ch(c):
        c /= 255
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = map(ch, rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def main():
    light = load(RES / "values/colors.xml")
    dark = load(RES / "values-night/colors.xml", light)
    page = {"light": "sg_bg", "dark": "sg_bg"}
    failed = False
    for mode, colors in (("light", light), ("dark", dark)):
        for fg, bg in PAIRS:
            # a translucent background (e.g. basic button) sits on the card
            bg_rgb = over(resolve(colors, bg), resolve(colors, "sg_card" if bg != "sg_card" else page[mode]))
            bg_hex = "#%02X%02X%02X" % tuple(round(c) for c in bg_rgb)
            fg_rgb = over(resolve(colors, fg), bg_hex)
            l1, l2 = sorted([lum(fg_rgb), lum(bg_rgb)], reverse=True)
            ratio = (l1 + 0.05) / (l2 + 0.05)
            ok = ratio >= 4.5
            failed |= not ok
            print(f"{mode:5s} {fg:24s} on {bg:22s} {ratio:5.2f} {'ok' if ok else 'FAIL'}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
