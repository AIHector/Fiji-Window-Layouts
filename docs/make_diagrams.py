#!/usr/bin/env python3
"""Draw the six built-in layouts as PNG diagrams for README.md.

The zones are the same numbers as in WindowLayouts_.java (percent of the full usable screen of
display 1, the top 9% being the ImageJ toolbar band). Run with any Python that has Pillow:
    python3 docs/make_diagrams.py
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent
FONT = "/System/Library/Fonts/HelveticaNeue.ttc"

# group -> (label, fill colour)
STYLE = {
    "images": ("Images", "#cfe3f7"),
    "bc": ("B&C / Threshold / Channels", "#f9e0b8"),
    "roi": ("ROI Manager", "#d6efd3"),
    "snt": ("SNT", "#e8d9f5"),
    "results": ("Results / tables", "#fbd3d3"),
    "log": ("Log / Console", "#e4e4e4"),
}

# (group, x, y, w, h) in percent of the work area (below the toolbar); converted to screen percent below
WORK = {
    1: [("images", 0, 0, 58, 100), ("roi", 58, 0, 18, 33, "ROI Manager / SNT"), ("bc", 76, 0, 11, 33, "B&C"),
        ("bc", 87, 0, 13, 33, "Threshold / Channels"), ("results", 58, 33, 42, 37), ("log", 58, 70, 42, 30)],
    2: [("images", 0, 0, 72, 100), ("bc", 72, 0, 11, 40), ("roi", 83, 0, 17, 40, "ROI Manager / SNT"),
        ("results", 72, 40, 28, 34), ("log", 72, 74, 28, 26)],
    3: [("images", 0, 0, 100, 60), ("results", 0, 60, 45, 40), ("roi", 45, 60, 19, 40, "ROI Manager / SNT"),
        ("bc", 64, 60, 16, 40), ("log", 80, 60, 20, 40)],
    4: [("images", 0, 0, 82, 78), ("results", 0, 78, 82, 22), ("bc", 82, 0, 18, 34),
        ("roi", 82, 34, 18, 34, "ROI Manager / SNT"), ("log", 82, 68, 18, 32)],
    5: [("images", 0, 0, 60, 100), ("snt", 60, 0, 40, 33, "SNT (main, Path/Fill Manager)"), ("roi", 60, 33, 20, 34),
        ("bc", 80, 33, 20, 34), ("results", 60, 67, 40, 17), ("log", 60, 84, 40, 16)],
    6: [("images", 0, 0, 100, 100)],
}
BAND = 9  # the toolbar band, in percent of the screen height (as drawn)
LAYOUTS = {n: [(z[0], z[1], BAND + z[2] * (100 - BAND) / 100, z[3], z[4] * (100 - BAND) / 100) + tuple(z[5:]) for z in zs]
           for n, zs in WORK.items()}
SECOND = [("bc", 0, 0, 20, 40), ("roi", 20, 0, 30, 40), ("snt", 50, 0, 50, 40),
          ("results", 0, 40, 100, 35), ("log", 0, 75, 100, 25)]

W, H = 760, 494  # 1512 x 982 screen, scaled


def font(size, bold=False):
    return ImageFont.truetype(FONT, size, index=1 if bold else 0)


def screen(draw, ox, oy, w, h, zones, title, toolbar=True):
    draw.rounded_rectangle([ox, oy, ox + w, oy + h], 10, fill="#ffffff", outline="#333333", width=3)
    if toolbar:
        draw.rectangle([ox + 3, oy + 3, ox + w - 3, oy + 0.035 * h], fill="#555555")  # macOS menu bar
        draw.rectangle([ox + 3, oy + 0.035 * h, ox + w - 3, oy + 0.09 * h], fill="#9aa5b1")
        draw.text((ox + 10, oy + 0.045 * h), "ImageJ toolbar", fill="white", font=font(13, True))
    for z in zones:
        g, x, y, zw, zh = z[:5]
        label = z[5] if len(z) > 5 else STYLE[g][0]
        x0, y0 = ox + x / 100 * w, oy + y / 100 * h
        x1, y1 = ox + (x + zw) / 100 * w, oy + (y + zh) / 100 * h
        draw.rectangle([x0, y0, x1, y1], fill=STYLE[g][1], outline="#555555", width=1)
        size = 15 if (x1 - x0) > 150 else 12
        words, lines, cur = label.split(" "), [], ""
        for word in words:  # wrap to the zone width
            t = (cur + " " + word).strip()
            if draw.textlength(t, font=font(size)) > (x1 - x0) - 10 and cur:
                lines.append(cur)
                cur = word
            else:
                cur = t
        lines.append(cur)
        for i, line in enumerate(lines):
            draw.text((x0 + 6, y0 + 5 + i * (size + 3)), line, fill="#222222", font=font(size))
    draw.text((ox, oy + h + 8), title, fill="#222222", font=font(16, True))


for n, zones in LAYOUTS.items():
    if n == 6:
        img = Image.new("RGB", (W * 2 + 60, H + 40), "white")
        d = ImageDraw.Draw(img)
        screen(d, 10, 10, W, H, zones, "Display 1 (toolbar)")
        screen(d, W + 50, 10, W, H, SECOND, "Display 2 (tools)", toolbar=False)
    else:
        img = Image.new("RGB", (W + 20, H + 40), "white")
        d = ImageDraw.Draw(img)
        screen(d, 10, 10, W, H, zones, f"Layout {n}")
    img.save(OUT / f"layout{n}.png")
    print("wrote", OUT / f"layout{n}.png")
