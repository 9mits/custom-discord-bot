#!/usr/bin/env python3
"""Compose several item sprites into one image for a post.

A row of 16x16 icons dropped inline beside the text is fine for naming an item in
passing, but the things an update is actually about deserve to be *looked* at. This
lays a small group of them out as a single picture, big enough to read.

Every sprite comes from the resource pack this server actually ships, so the page
shows the same art as the game. Nothing here draws or invents pixels: it scales with
nearest-neighbour so a 16x16 texture stays crisp instead of turning to mush, keeps
each sprite's aspect ratio, and leaves the background transparent so the sheet sits
on the page's own colour in either theme.

    python devblog/make_item_sheet.py update-7 dragon-gear \
        amethyst_sword amethyst_hoe amethyst_bow amethyst_elytra amethyst_apple

Writes devblog/media/<post-slug>/<name>.png.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:  # pragma: no cover - the message is the whole handling
    sys.exit("Pillow is required: pip install -r devblog/requirements.txt")

REPO = Path(__file__).resolve().parent.parent
PACK_ITEMS = REPO / "assets" / "resourcepack" / "src" / "assets" / "mgx" / "textures" / "item"
MEDIA = Path(__file__).resolve().parent / "media"

#: Height of one sprite's cell. Large enough that a 16x16 texture still reads on a
#: retina display without the file becoming a screenshot-sized download.
CELL = 256
#: Breathing room between sprites, as a share of the cell.
GAP = 0.18

#: Sprites whose flat texture is a 3D model's UV map rather than an icon. Using the
#: map renders as a garbled sheet on the page, so the rendered icon is used instead.
ICON_OVERRIDES = {"amethyst_shield": "amethyst_shield_icon"}


def find_texture(name: str) -> Path:
    """The file for one sprite name, searching items then the cosmetic subfolder."""
    name = ICON_OVERRIDES.get(name, name)
    for candidate in (PACK_ITEMS / f"{name}.png", PACK_ITEMS / "cosmetic" / f"{name}.png"):
        if candidate.exists():
            return candidate
    raise SystemExit(f"no texture named {name!r} under {PACK_ITEMS}")


def scaled(path: Path, cell: int) -> Image.Image:
    """One sprite fitted into a square cell, aspect kept, pixels kept sharp."""
    image = Image.open(path).convert("RGBA")
    width, height = image.size
    factor = min(cell / width, cell / height)
    # Whole-number scaling wherever it fits, so a pixel stays a square block.
    if factor >= 1:
        factor = int(factor)
    return image.resize((max(1, int(width * factor)), max(1, int(height * factor))), Image.NEAREST)


def sheet(names: list[str], columns: int, cell: int = CELL) -> Image.Image:
    sprites = [scaled(find_texture(name), cell) for name in names]
    rows = -(-len(sprites) // columns)
    gap = int(cell * GAP)
    width = columns * cell + (columns + 1) * gap
    height = rows * cell + (rows + 1) * gap
    canvas = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    for index, sprite in enumerate(sprites):
        row, column = divmod(index, columns)
        # Centred in its cell so a tall sprite and a wide one still line up.
        x = gap + column * (cell + gap) + (cell - sprite.width) // 2
        y = gap + row * (cell + gap) + (cell - sprite.height) // 2
        canvas.alpha_composite(sprite, (x, y))
    return canvas


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("slug", help="post slug, e.g. update-7")
    parser.add_argument("name", help="output filename without .png")
    parser.add_argument("items", nargs="+", help="sprite names, in order")
    parser.add_argument("--columns", type=int, default=0,
                        help="grid columns; defaults to one row")
    parser.add_argument("--cell", type=int, default=CELL)
    args = parser.parse_args()

    columns = args.columns or len(args.items)
    image = sheet(args.items, columns, args.cell)
    out = MEDIA / args.slug / f"{args.name}.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    image.save(out, optimize=True)
    print(f"{out.relative_to(REPO)}  {image.width}x{image.height}  {out.stat().st_size:,} bytes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
