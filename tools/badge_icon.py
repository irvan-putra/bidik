#!/usr/bin/env python3
"""Generate mata launcher icons with the version badged on the icon.

Release builds show the version on the launcher icon, readable inside
the icon safe area. This script regenerates the mipmap
PNGs deterministically from the version string - the release workflow runs
it at stamp time and commits the result, so the tagged source always
reproduces the icon of its binary.

Usage: tools/badge_icon.py 0.1.0
Requires: Pillow (CI: pip install pillow).
"""

import sys
import os

from PIL import Image, ImageDraw, ImageFont

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

BG = (24, 24, 32, 255)
LENS = (240, 240, 245, 255)
PUPIL = (24, 24, 32, 255)
STRIP = (24, 24, 32, 200)
TEXT = (240, 240, 245, 255)


def render(size, version):
    img = Image.new("RGBA", (size, size), BG)
    d = ImageDraw.Draw(img)
    # lens: outer ring + pupil, centered in the upper 2/3
    cx, cy = size / 2, size * 0.42
    r = size * 0.20
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=LENS)
    pr = r * 0.5
    d.ellipse([cx - pr, cy - pr, cx + pr, cy + pr], fill=PUPIL)
    # version strip across the lower third, kept inside the icon safe area
    strip_h = int(size * 0.18)
    y0 = size - strip_h - int(size * 0.06)
    d.rectangle([0, y0, size, y0 + strip_h], fill=STRIP)
    label = "v" + version
    font = ImageFont.load_default(size=max(10, int(strip_h * 0.72)))
    bbox = d.textbbox((0, 0), label, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    d.text(((size - tw) / 2, y0 + (strip_h - th) / 2 - bbox[1]), label,
           font=font, fill=TEXT)
    return img


def main():
    version = sys.argv[1]
    res = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
    for folder, size in DENSITIES.items():
        out_dir = os.path.abspath(os.path.join(res, folder))
        os.makedirs(out_dir, exist_ok=True)
        out = os.path.join(out_dir, "ic_launcher.png")
        render(size, version).save(out)
        print("wrote", out)


if __name__ == "__main__":
    main()
