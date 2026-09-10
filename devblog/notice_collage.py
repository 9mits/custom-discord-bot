#!/usr/bin/env python3
"""Compose one update-notice collage from a post's own screenshots.

The Discord notice gets a single image instead of a column of them. Discord scales
an embed image to roughly 550px wide, which is the whole design constraint: menu
screenshots and anything carrying small text turn to mush at that size, so a
collage is built from the shots that read as *pictures* — a hero and three
supporting tiles, nothing smaller.

Every tile comes from `devblog/media/<slug>/`. Nothing is drawn or invented; this
only crops and arranges artwork a human already supplied.

    python devblog/notice_collage.py update-7
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont

REPO_ROOT = Path(__file__).resolve().parent.parent
MEDIA = REPO_ROOT / "devblog" / "media"

WIDTH = 1600
MARGIN, GUTTER = 14, 12
HERO_HEIGHT = 600
RADIUS = 18
BACKDROP = (14, 10, 22)
EDGE = (150, 92, 235)
CAPTION_INK = (255, 255, 255)
CAPTION_SHADE = (0, 0, 0)
FONT_PATH = "/System/Library/Fonts/Supplemental/Arial Black.ttf"
#: How near-black a border pixel has to be before it counts as dead padding.
TRIM_TOLERANCE = 26

#: Which shots make the collage, and how each one meets its tile. "cover" fills
#: and crops, for a photographed world; "contain" letterboxes, for a designed
#: panel whose composition is destroyed by cropping.
DEFAULT_TILES = {
    "update-7": [
        # The hero, then a row of the world and a row of the spoils. Weighted
        # towards places rather than item grids: a wall of icons says "inventory
        # screen", and what pulls somebody back is seeing somewhere to go.
        ("dragon-victory.png", "cover", ""),
        ("mysterious-portal.png", "cover", ""),
        ("dragon-battle.png", "cover", ""),
        ("humongous-amethyst.png", "cover", ""),
        ("pvp-rank-progression.png", "contain", ""),
        ("eternal-gear.png", "contain", ""),
    ],
}
#: How many tiles sit in each row under the hero. Their widths come from the
#: pictures themselves, so nothing has to be told what shape it is.
DEFAULT_ROWS = (3, 2)


def _trim(image: Image.Image) -> Image.Image:
    """Crop the dead near-black margin off a designed panel.

    Letterboxing a panel that already carries its own wide black border stacks one
    band of nothing on another — which is exactly how a 2.7:1 panel ended up as
    40% padding in a 1.6:1 tile. Trimming first means the artwork, not its margin,
    is what gets fitted.
    """
    grey = image.convert("L")
    floor = Image.new("L", grey.size, TRIM_TOLERANCE)
    box = ImageChops.subtract(grey, floor).getbbox()
    return image.crop(box) if box else image


def _fit(image: Image.Image, box: tuple[int, int], mode: str) -> Image.Image:
    """One tile's worth of picture, either filled and cropped or letterboxed."""
    width, height = box
    # Letterboxed panels are artwork drawn on black, so the bars have to be black
    # too or the tile shows a seam where the padding meets the picture.
    canvas = Image.new("RGB", box, (0, 0, 0) if mode == "contain" else BACKDROP)
    if mode == "contain":
        image = _trim(image)
    if mode == "cover":
        scale = max(width / image.width, height / image.height)
        resized = image.resize(
            (max(1, round(image.width * scale)), max(1, round(image.height * scale))),
            Image.LANCZOS,
        )
        left = (resized.width - width) // 2
        top = (resized.height - height) // 2
        canvas.paste(resized.crop((left, top, left + width, top + height)), (0, 0))
        return canvas
    shrunk = image.copy()
    shrunk.thumbnail((width, height), Image.LANCZOS)
    canvas.paste(shrunk, ((width - shrunk.width) // 2, (height - shrunk.height) // 2))
    return canvas


def _rounded(tile: Image.Image, radius: int) -> Image.Image:
    """A tile with soft corners and a thin amethyst edge, so the grid reads as design."""
    mask = Image.new("L", tile.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, tile.width - 1, tile.height - 1),
                                           radius=radius, fill=255)
    framed = Image.new("RGB", tile.size, BACKDROP)
    framed.paste(tile, (0, 0), mask)
    ImageDraw.Draw(framed).rounded_rectangle(
        (0, 0, framed.width - 1, framed.height - 1), radius=radius, outline=EDGE, width=3
    )
    return Image.composite(framed, Image.new("RGB", tile.size, BACKDROP), mask)


def _caption(tile: Image.Image, text: str) -> None:
    """Name the feature across the foot of its tile.

    Off by default. Burnt-in labels fight the artwork and duplicate whatever the
    embed already says, so the collage stays wordless unless a caption is asked
    for explicitly.
    """
    if not text:
        return
    size = max(20, round(tile.height * 0.115))
    try:
        font = ImageFont.truetype(FONT_PATH, size)
    except OSError:
        font = ImageFont.load_default()
    draw = ImageDraw.Draw(tile, "RGBA")
    left, top, right, bottom = draw.textbbox((0, 0), text, font=font)
    band = bottom - top + round(size * 1.1)
    draw.rectangle((0, tile.height - band, tile.width, tile.height), fill=(0, 0, 0, 165))
    x = (tile.width - (right - left)) // 2
    y = tile.height - band + (band - (bottom - top)) // 2 - top
    draw.text((x + 2, y + 2), text, font=font, fill=CAPTION_SHADE)
    draw.text((x, y), text, font=font, fill=CAPTION_INK)


def _load(source: Path, name: str, mode: str) -> Image.Image:
    """The picture a tile will hold, with any dead margin already gone."""
    path = source / name
    if not path.exists():
        raise SystemExit(f"{path} is missing; a collage never invents a screenshot.")
    with Image.open(path) as raw:
        image = raw.convert("RGB")
    return _trim(image) if mode == "contain" else image


def build(
    slug: str,
    tiles: list[tuple[str, str, str]],
    out: Path,
    rows: tuple[int, ...] = DEFAULT_ROWS,
) -> Path:
    """Write the collage for one update and return where it landed.

    Rows are justified: every tile in a row shares one height, and each is as wide
    as its own picture wants to be at that height. Fixed cells were the mistake —
    a 3.6:1 rank ladder in a 2.45:1 cell is a third padding, and the eye reads the
    inconsistent bands as broken spacing rather than as design.
    """
    source = MEDIA / slug
    if len(tiles) < 2:
        raise SystemExit("A collage is a hero plus at least one tile.")
    if len(tiles) != 1 + sum(rows):
        raise SystemExit(
            f"{len(tiles)} tiles will not fill 1 hero plus {sum(rows)} places."
        )

    usable = WIDTH - 2 * MARGIN
    hero = _load(source, tiles[0][0], tiles[0][1])
    placed: list[tuple[Image.Image, str, tuple[int, int, int, int]]] = [
        (hero, tiles[0][2], (MARGIN, MARGIN, usable, HERO_HEIGHT))
    ]

    y = MARGIN + HERO_HEIGHT
    index = 1
    for count in rows:
        row = [_load(source, name, mode) for name, mode, _ in tiles[index:index + count]]
        aspects = [image.width / image.height for image in row]
        span = usable - (count - 1) * GUTTER
        height = round(span / sum(aspects))
        widths = [round(aspect * height) for aspect in aspects]
        # Rounding each tile independently leaves the row a pixel or two short of
        # the margin; the last tile absorbs it so both edges line up exactly.
        widths[-1] += span - sum(widths)
        y += GUTTER
        x = MARGIN
        for image, width, (_, _, label) in zip(row, widths, tiles[index:index + count]):
            placed.append((image, label, (x, y, width, height)))
            x += width + GUTTER
        y += height
        index += count

    sheet = Image.new("RGB", (WIDTH, y + MARGIN), BACKDROP)
    for image, label, (x, top, width, height) in placed:
        tile = _fit(image, (width, height), "cover")
        _caption(tile, label)
        sheet.paste(_rounded(tile, RADIUS), (x, top))

    out.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out, optimize=True)
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("slug", help="the update's media folder, e.g. update-7")
    parser.add_argument("--out", type=Path, default=None)
    parser.add_argument(
        "--tiles",
        nargs="+",
        metavar="FILE:MODE:LABEL",
        help="<name.png>:<cover|contain>:<caption> tiles, hero first",
    )
    parser.add_argument(
        "--rows",
        nargs="+",
        metavar="COUNT",
        help="tiles per row under the hero, e.g. 3 2",
    )
    args = parser.parse_args()

    rows = tuple(int(entry) for entry in args.rows) if args.rows else DEFAULT_ROWS

    if args.tiles:
        tiles = []
        for entry in args.tiles:
            name, _, rest = entry.partition(":")
            mode, _, label = rest.partition(":")
            tiles.append((name, mode or "cover", label))
    elif args.slug in DEFAULT_TILES:
        tiles = DEFAULT_TILES[args.slug]
    else:
        raise SystemExit(f"No default tiles for {args.slug}; pass --tiles.")

    out = args.out or (MEDIA / args.slug / "notice-collage.png")
    written = build(args.slug, tiles, out, rows)
    print(f"{written}  {written.stat().st_size // 1024} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
