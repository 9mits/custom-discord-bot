#!/usr/bin/env python3
"""Compose a titled banner out of the server's own item sprites.

`make_item_sheet.py` puts a row of icons on the page, which is right when the text
beside it already says what they are. A banner is for the beats that carry a section on
their own: a headline, a line of context, and the things themselves in labelled cards.

Every sprite comes from the resource pack this server ships, scaled with
nearest-neighbour so a 16x16 texture stays crisp. Nothing here draws or invents pixels;
it lays out artwork that already exists and sets type around it.

    python devblog/make_banner.py update-8 solstice-gear \
        --title "SEASON 1 GEAR" --subtitle "EARNED ONLY ON THE SEASON PASS" \
        --card season_1_scythe:SCYTHE --card season_1_wings:WINGS

Writes devblog/media/<post-slug>/<name>.png.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # pragma: no cover - the message is the whole handling
    sys.exit("Pillow is required: pip install -r devblog/requirements.txt")

REPO = Path(__file__).resolve().parent.parent
PACK_ITEMS = REPO / "assets" / "resourcepack" / "src" / "assets" / "mgx" / "textures" / "item"
MEDIA = Path(__file__).resolve().parent / "media"
INLINE_ICONS = Path(__file__).resolve().parent / "static" / "minecraft-items"

#: Bold grotesque, to match the update artwork already on the site.
FONTS = [
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    "/System/Library/Fonts/Supplemental/Arial.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]
#: The house purple, top to bottom.
TOP = (58, 26, 94)
BOTTOM = (18, 10, 32)
CARD = (22, 18, 31)
MUTED = (186, 170, 214)
ACCENTS = {
    "gold": (255, 196, 84),
    "violet": (181, 108, 255),
    "cyan": (83, 229, 255),
    "silver": (206, 212, 224),
    "bronze": (205, 133, 84),
    "green": (98, 224, 106),
}


def font(size: int) -> ImageFont.FreeTypeFont:
    for path in FONTS:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def sprite(name: str) -> Image.Image:
    """The pack's sprite, or the blog's own icon set for a vanilla item it names."""
    for path in (PACK_ITEMS / f"{name}.png",
                 PACK_ITEMS / "cosmetic" / f"{name}.png",
                 INLINE_ICONS / f"{name}.png"):
        if path.exists():
            break
    else:
        sys.exit(f"No sprite named {name} in the resource pack or the blog's icons.")
    with Image.open(path) as image:
        return image.convert("RGBA")


def background(width: int, height: int) -> Image.Image:
    canvas = Image.new("RGB", (width, height))
    draw = ImageDraw.Draw(canvas)
    for y in range(height):
        blend = y / max(1, height - 1)
        draw.line(
            [(0, y), (width, y)],
            fill=tuple(round(TOP[c] + (BOTTOM[c] - TOP[c]) * blend) for c in range(3)),
        )
    return canvas


def centred(draw: ImageDraw.ImageDraw, y: int, text: str, face, fill, width: int) -> int:
    left, top, right, bottom = draw.textbbox((0, 0), text, font=face)
    draw.text(((width - (right - left)) / 2 - left, y - top), text, font=face, fill=fill)
    return bottom - top


def fitted(image: Image.Image, box: int) -> Image.Image:
    scale = max(1, box // max(image.width, image.height))
    return image.resize((image.width * scale, image.height * scale), Image.NEAREST)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("slug", help="post slug, e.g. update-8")
    parser.add_argument("name", help="output file name without .png")
    parser.add_argument("--title", required=True)
    parser.add_argument("--subtitle", default="")
    parser.add_argument("--footnote", default="")
    parser.add_argument(
        "--card",
        action="append",
        default=[],
        metavar="SPRITE:LABEL[:SUBLABEL[:ACCENT]]",
        help="one labelled card; repeat for each",
    )
    parser.add_argument("--width", type=int, default=1600)
    args = parser.parse_args()

    cards = []
    for raw in args.card:
        parts = raw.split(":")
        cards.append((
            parts[0],
            parts[1] if len(parts) > 1 else "",
            parts[2] if len(parts) > 2 else "",
            ACCENTS.get(parts[3] if len(parts) > 3 else "violet", ACCENTS["violet"]),
        ))

    width = args.width
    title_face, subtitle_face = font(76), font(30)
    label_face, sub_face, note_face = font(30), font(23), font(24)

    top = 46
    height = top + 96 + (46 if args.subtitle else 0)
    card_top = height + 18
    cell = 0
    if cards:
        gap = 34
        cell = min(300, (width - 120 - gap * (len(cards) - 1)) // len(cards))
        card_height = cell + (58 if any(c[1] for c in cards) else 0) + (34 if any(c[2] for c in cards) else 0)
        height = card_top + card_height + 54
    else:
        height += 40
    if args.footnote:
        height += 48

    canvas = background(width, height)
    draw = ImageDraw.Draw(canvas)
    centred(draw, top, args.title, title_face, (255, 255, 255), width)
    if args.subtitle:
        centred(draw, top + 100, args.subtitle, subtitle_face, MUTED, width)

    if cards:
        total = cell * len(cards) + gap * (len(cards) - 1)
        x = (width - total) / 2
        for name, label, sub, accent in cards:
            draw.rounded_rectangle(
                [x, card_top, x + cell, card_top + cell], radius=22, fill=CARD, outline=accent, width=4
            )
            art = fitted(sprite(name), int(cell * 0.62))
            canvas.paste(
                art,
                (int(x + (cell - art.width) / 2), int(card_top + (cell - art.height) / 2)),
                art,
            )
            text_y = card_top + cell + 16
            if label:
                left, top_, right, bottom = draw.textbbox((0, 0), label, font=label_face)
                draw.text((x + (cell - (right - left)) / 2 - left, text_y - top_), label,
                          font=label_face, fill=accent)
                text_y += bottom - top_ + 10
            if sub:
                left, top_, right, _ = draw.textbbox((0, 0), sub, font=sub_face)
                draw.text((x + (cell - (right - left)) / 2 - left, text_y - top_), sub,
                          font=sub_face, fill=MUTED)
            x += cell + gap

    if args.footnote:
        centred(draw, height - 62, args.footnote, note_face, MUTED, width)

    target = MEDIA / args.slug / f"{args.name}.png"
    target.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(target, optimize=True)
    print(f"{target.relative_to(REPO)}  {canvas.width}x{canvas.height}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
