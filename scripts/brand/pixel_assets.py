"""Generate FlowMap Mobile brand assets from the 16x16 pixel logo.

Outputs (relative to the repository root):
  app/src/main/res/mipmap-*/ic_launcher*.png      legacy launcher icons
  app/src/main/res/drawable/ic_launcher_fg_*.png  adaptive-icon foreground layer (xxxhdpi, nearest-neighbour)
  docs/media/icon-512.png                         Play Store icon
  docs/media/*.png                                English README artwork (needs --pdf with the brand sheet)

Usage:  python scripts/brand/pixel_assets.py [--pdf "Pixel Bookmap Logo.pdf"]
The logo grid is the single source of truth; it is mirrored in
app/src/main/java/.../ui/brand/PixelLogo.kt.
"""
from __future__ import annotations

import argparse
import io
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]

PALETTE = {
    "0": None,  # transparent
    "1": (0x10, 0x17, 0x31),  # frame
    "2": (0x3D, 0xFF, 0x9F),  # green
    "3": (0x17, 0x3E, 0x67),  # navy
    "4": (0x1F, 0x8F, 0xA5),  # teal
    "5": (0xF2, 0xD0, 0x4B),  # yellow
    "6": (0xFF, 0x7A, 0x2F),  # orange
    "7": (0xFF, 0xFF, 0xFF),  # white price line
    "8": (0x9D, 0x10, 0x30),  # dark red
    "9": (0xFE, 0x3B, 0x5B),  # red
    "a": (0xFF, 0x90, 0xA3),  # pink core
}

LOGO = [
    "0111111111111110",
    "1111111111111211",
    "1333344333332221",
    "1455666554445251",
    "1344555443377731",
    "1113331111171331",
    "1111111111171111",
    "1133117777371111",
    "1111117117171111",
    "1337777338981331",
    "1447544459a95441",
    "1347666558985531",
    "1777443333333331",
    "1111111111111111",
    "1111111111111111",
    "0111111111111110",
]

BG = (0x07, 0x0A, 0x13)

# 5x7 pixel font used for the wordmark and README captions.
FONT = {
    "A": [".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"],
    "B": ["####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."],
    "C": [".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."],
    "D": ["####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."],
    "E": ["#####", "#....", "#....", "####.", "#....", "#....", "#####"],
    "F": ["#####", "#....", "#....", "####.", "#....", "#....", "#...."],
    "G": [".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".####"],
    "H": ["#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"],
    "I": ["#####", "..#..", "..#..", "..#..", "..#..", "..#..", "#####"],
    "J": ["..###", "...#.", "...#.", "...#.", "#..#.", "#..#.", ".##.."],
    "K": ["#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"],
    "L": ["#....", "#....", "#....", "#....", "#....", "#....", "#####"],
    "M": ["#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"],
    "N": ["#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"],
    "O": [".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."],
    "P": ["####.", "#...#", "#...#", "####.", "#....", "#....", "#...."],
    "Q": [".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"],
    "R": ["####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"],
    "S": [".####", "#....", "#....", ".###.", "....#", "....#", "####."],
    "T": ["#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."],
    "U": ["#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."],
    "V": ["#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."],
    "W": ["#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#"],
    "X": ["#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"],
    "Y": ["#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."],
    "Z": ["#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"],
    "0": [".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."],
    "1": ["..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."],
    "2": [".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"],
    "3": ["####.", "....#", "....#", ".###.", "....#", "....#", "####."],
    "4": ["...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."],
    "5": ["#####", "#....", "####.", "....#", "....#", "#...#", ".###."],
    "6": [".###.", "#....", "#....", "####.", "#...#", "#...#", ".###."],
    "7": ["#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."],
    "8": [".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."],
    "9": [".###.", "#...#", "#...#", ".####", "....#", "....#", ".###."],
    ".": [".....", ".....", ".....", ".....", ".....", ".##..", ".##.."],
    ",": [".....", ".....", ".....", ".....", ".##..", "..#..", ".#..."],
    ":": [".....", ".##..", ".##..", ".....", ".##..", ".##..", "....."],
    "+": [".....", "..#..", "..#..", "#####", "..#..", "..#..", "....."],
    "-": [".....", ".....", ".....", "#####", ".....", ".....", "....."],
    "/": ["....#", "....#", "...#.", "..#..", ".#...", "#....", "#...."],
    "x": [".....", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "....."],
    "'": ["..#..", "..#..", ".#...", ".....", ".....", ".....", "....."],
    "·": [".....", ".....", ".....", "..#..", ".....", ".....", "....."],
    " ": [".....", ".....", ".....", ".....", ".....", ".....", "....."],
}


def logo_image(cell: int, pad_cells: float = 0.0, bg=None) -> Image.Image:
    n = len(LOGO)
    pad = int(round(pad_cells * cell))
    size = n * cell + 2 * pad
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0) if bg is None else (*bg, 255))
    d = ImageDraw.Draw(img)
    for y, row in enumerate(LOGO):
        for x, ch in enumerate(row):
            c = PALETTE[ch]
            if c is None:
                continue
            d.rectangle([pad + x * cell, pad + y * cell, pad + (x + 1) * cell - 1, pad + (y + 1) * cell - 1], fill=(*c, 255))
    return img


def text_width(text: str, px: int, tracking: int = 1) -> int:
    return len(text) * (5 + tracking) * px - tracking * px


def draw_text(img: Image.Image, text: str, x: int, y: int, px: int, color, tracking: int = 1, shadow=None) -> None:
    d = ImageDraw.Draw(img)
    cx = x
    for ch in text:
        glyph = FONT.get(ch, FONT[" "])
        for gy, row in enumerate(glyph):
            for gx, bit in enumerate(row):
                if bit == "#":
                    if shadow is not None:
                        d.rectangle([cx + (gx + 1) * px, y + (gy + 1) * px, cx + (gx + 2) * px - 1, y + (gy + 2) * px - 1], fill=shadow)
                    d.rectangle([cx + gx * px, y + gy * px, cx + (gx + 1) * px - 1, y + (gy + 1) * px - 1], fill=color)
        cx += (5 + tracking) * px


def draw_centered(img, text, center_x, y, px, color, tracking=1):
    draw_text(img, text, int(center_x - text_width(text, px, tracking) / 2), y, px, color, tracking)


def launcher_icons() -> None:
    res = ROOT / "app/src/main/res"
    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for name, size in densities.items():
        out = res / f"mipmap-{name}"
        out.mkdir(parents=True, exist_ok=True)
        # Legacy icon: logo scaled with integer cells on the brand background, rounded by the launcher.
        cell = max(1, size // 18)
        logo = logo_image(cell)
        canvas = Image.new("RGBA", (size, size), (*BG, 255))
        off = (size - logo.width) // 2
        canvas.alpha_composite(logo, (off, off))
        canvas.save(out / "ic_launcher.png")
        canvas.save(out / "ic_launcher_round.png")
    # Adaptive foreground: 108dp canvas, the visible safe zone is the central 66dp.
    fg_dir = res / "drawable-nodpi"
    fg_dir.mkdir(parents=True, exist_ok=True)
    size = 432
    cell = 16  # 16 cells * 16 px = 256 px, inside the 264 px safe zone at xxxhdpi
    logo = logo_image(cell)
    fg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    off = (size - logo.width) // 2
    fg.alpha_composite(logo, (off, off))
    fg.save(fg_dir / "ic_launcher_fg.png")
    media = ROOT / "docs/media"
    media.mkdir(parents=True, exist_ok=True)
    store = Image.new("RGBA", (512, 512), (*BG, 255))
    big = logo_image(28)
    store.alpha_composite(big, ((512 - big.width) // 2, (512 - big.height) // 2))
    store.convert("RGB").save(media / "icon-512.png")


def feature_graphic() -> None:
    """1024x500 Play Store feature graphic drawn entirely from the pixel system."""
    w, h = 1024, 500
    img = Image.new("RGBA", (w, h), (*BG, 255))
    d = ImageDraw.Draw(img)
    import random

    rnd = random.Random(7)
    ramp = [(0x10, 0x14, 0x2A), (0x1B, 0x3D, 0x66), (0x1F, 0x8F, 0xA5), (0x5A, 0xF5, 0xE3), (0xF2, 0xD0, 0x4B), (0xFF, 0x7A, 0x2F), (0xFE, 0x3B, 0x5B)]
    cell = 16
    bands = {6: 5, 7: 4, 22: 6, 23: 5, 27: 3}
    for y in range(h // cell + 1):
        for x in range(w // cell + 1):
            base = bands.get(y, 0)
            v = max(0, min(6, base - rnd.randint(0, 3))) if base else (1 if rnd.random() < 0.25 else 0)
            d.rectangle([x * cell, y * cell, x * cell + cell - 1, y * cell + cell - 1], fill=ramp[v])
    panel = (64, 120, 660, 380)
    d.rectangle([panel[0] + 10, panel[1] + 10, panel[2] + 10, panel[3] + 10], fill=(0, 0, 0))
    d.rectangle(panel, fill=(0x08, 0x0B, 0x16), outline=(0x5A, 0xF5, 0xE3), width=4)
    logo = logo_image(6)
    img.alpha_composite(logo, (96, 150))
    draw_text(img, "FLOWMAP", 216, 162, 9, (255, 255, 255), shadow=(0x1B, 0x3D, 0x66))
    draw_text(img, "MOBILE", 96, 270, 6, (0xF2, 0xD0, 0x4B))
    draw_text(img, "LIQUIDITY IN YOUR POCKET", 96, 330, 3, (0xB8, 0xC4, 0xE0))
    img.convert("RGB").save(ROOT / "docs/media/feature-graphic.png")


def translate_sheet(pdf: Path) -> None:
    """Re-letter the Turkish pages of the brand sheet in English (the text is baked into the artwork)."""
    import pymupdf

    doc = pymupdf.open(str(pdf))
    pages = []
    for page in doc:
        info = page.get_images(full=True)[0]
        raw = doc.extract_image(info[0])["image"]
        pages.append(Image.open(io.BytesIO(raw)).convert("RGBA"))
    media = ROOT / "docs/media"
    media.mkdir(parents=True, exist_ok=True)
    k = 4  # page images are rendered at 4 px per PDF point

    # Page 1: logo lockups (already English).
    pages[0].convert("RGB").resize((1800, 1140), Image.LANCZOS).save(media / "brand-logo.png")

    # Page 2: icon sizes — the caption paragraph is Turkish.
    p = pages[1]
    cream = p.getpixel((int(880 * k), int(400 * k)))
    ImageDraw.Draw(p).rectangle([455 * k, 300 * k, 890 * k, 400 * k], fill=cream)
    navy = (0x1B, 0x2C, 0x52, 255)
    lines = ["EVERY PIXEL IS A WHOLE NUMBER:", "A 16x16 GRID THAT SCALES TO", "16 / 32 / 64 / 128 / 256 PX", "WITHOUT ANY BLUR."]
    for i, line in enumerate(lines):
        draw_text(p, line, 460 * k, int((318 + i * 18) * k), 7, navy)
    p.convert("RGB").resize((1800, 840), Image.LANCZOS).save(media / "brand-icon-sizes.png")

    # Page 3: the three marks — titles and descriptions are Turkish.
    p = pages[2]
    card_bg = p.getpixel((int(170 * k), int(398 * k)))
    d = ImageDraw.Draw(p)
    white, grey = (255, 255, 255, 255), (0xC3, 0xCC, 0xE2, 255)
    cards = [
        (170, ["HEAT LINE"], ["LIQUIDITY BANDS + PRICE LINE", "+ TRADE BUBBLE. PRIMARY MARK."]),
        (450, ["DEPTH LADDER"], ["DOM VIEW: SELL WALLS ON TOP,", "BUY WALLS BELOW, PRICE IN", "THE MIDDLE."]),
        (730, ["HEAT BUBBLE"], ["A LARGE TRADE BUBBLE WITH", "HEAT RINGS RADIATING OUT."]),
    ]
    for cx, titles, desc in cards:
        d.rectangle([(cx - 118) * k, 290 * k, (cx + 118) * k, 395 * k], fill=card_bg)
        y = 296
        for t in titles:
            draw_centered(p, t, cx * k, int(y * k), 11, white)
            y += 22
        y += 6
        for t in desc:
            draw_centered(p, t, cx * k, int(y * k), 5, grey)
            y += 13
    p.convert("RGB").resize((1800, 840), Image.LANCZOS).save(media / "brand-marks.png")

    # Page 4: banner (already English).
    pages[3].convert("RGB").resize((1920, 1080), Image.LANCZOS).save(media / "banner.png")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pdf", type=Path, help="brand sheet PDF (Pixel Bookmap Logo.pdf)")
    args = ap.parse_args()
    launcher_icons()
    feature_graphic()
    if args.pdf:
        translate_sheet(args.pdf)
    print("assets written under", ROOT)


if __name__ == "__main__":
    main()
