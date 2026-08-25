#!/usr/bin/env python3
"""Prepare AI-generated icon sources for the Minecraft resource packs.

This importer never draws or invents icon geometry. It only removes the flat
generation backdrop, trims model artifacts, and scales the selected generated
artwork onto a 16x16 logical pixel grid, then enlarges that grid exactly 2x for
clean 32x32 inventory rendering.
"""

from __future__ import annotations

import argparse
import json
import math
from collections import deque
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


PACK_ROOT = Path(__file__).resolve().parent
ITEM_ROOT = PACK_ROOT / "src/assets/mgx/textures/item"
CANVAS_SIZE = 32
LOGICAL_SIZE = 16
CONTENT_SIZE = 15
PALETTE_SIZE = 32
BACKGROUND_DISTANCE = 72
FOCUS_ONLY = {
    "bronze_cataclysm",
    "conquerors_march",
    "golden_finality",
    "kingmakers_wake",
    "silver_reckoning",
}


def catalog_targets() -> dict[str, Path]:
    roots = (ITEM_ROOT, ITEM_ROOT / "cosmetic")
    return {
        path.stem: path.relative_to(ITEM_ROOT)
        for root in roots
        for path in sorted(root.glob("*.png"))
    }


def border_median(image: Image.Image) -> tuple[int, int, int]:
    width, height = image.size
    samples: list[tuple[int, int, int]] = []
    step = max(1, min(width, height) // 64)
    for x in range(0, width, step):
        samples.extend((image.getpixel((x, 0)), image.getpixel((x, height - 1))))
    for y in range(0, height, step):
        samples.extend((image.getpixel((0, y)), image.getpixel((width - 1, y))))
    midpoint = len(samples) // 2
    return tuple(sorted(sample[channel] for sample in samples)[midpoint] for channel in range(3))


def close_to_background(
        colour: tuple[int, int, int],
        background: tuple[int, int, int],
) -> bool:
    distance_squared = sum((colour[index] - background[index]) ** 2 for index in range(3))
    return distance_squared <= BACKGROUND_DISTANCE ** 2


def connected_background(image: Image.Image) -> bytearray:
    width, height = image.size
    pixels = image.load()
    background = border_median(image)
    mask = bytearray(width * height)
    pending: deque[tuple[int, int]] = deque()

    def seed(x: int, y: int) -> None:
        index = y * width + x
        if not mask[index] and close_to_background(pixels[x, y], background):
            mask[index] = 1
            pending.append((x, y))

    for x in range(width):
        seed(x, 0)
        seed(x, height - 1)
    for y in range(height):
        seed(0, y)
        seed(width - 1, y)

    while pending:
        x, y = pending.popleft()
        for next_x, next_y in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if not (0 <= next_x < width and 0 <= next_y < height):
                continue
            index = next_y * width + next_x
            if not mask[index] and close_to_background(pixels[next_x, next_y], background):
                mask[index] = 1
                pending.append((next_x, next_y))
    return mask


def meaningful_foreground(
        image: Image.Image,
        background: bytearray,
        focus_only: bool,
) -> Image.Image:
    width, height = image.size
    seen = bytearray(width * height)
    components: list[list[tuple[int, int]]] = []
    neighbours = (
        (-1, -1), (0, -1), (1, -1),
        (-1, 0), (1, 0),
        (-1, 1), (0, 1), (1, 1),
    )

    for y in range(height):
        for x in range(width):
            index = y * width + x
            if background[index] or seen[index]:
                continue
            seen[index] = 1
            pending = [(x, y)]
            component: list[tuple[int, int]] = []
            while pending:
                current_x, current_y = pending.pop()
                component.append((current_x, current_y))
                for offset_x, offset_y in neighbours:
                    next_x = current_x + offset_x
                    next_y = current_y + offset_y
                    if not (0 <= next_x < width and 0 <= next_y < height):
                        continue
                    next_index = next_y * width + next_x
                    if not background[next_index] and not seen[next_index]:
                        seen[next_index] = 1
                        pending.append((next_x, next_y))
            components.append(component)

    if not components:
        raise ValueError("generated source contains no foreground")
    largest = max(len(component) for component in components)
    minimum = largest if focus_only else max(4, math.floor(largest * 0.003))
    alpha = Image.new("L", image.size, 0)
    alpha_pixels = alpha.load()
    for component in components:
        if len(component) < minimum:
            continue
        for x, y in component:
            alpha_pixels[x, y] = 255
    return alpha


def prepare(source: Path, focus_only: bool = False) -> Image.Image:
    image = Image.open(source).convert("RGB")
    image.thumbnail((256, 256), Image.Resampling.BOX)
    alpha = meaningful_foreground(image, connected_background(image), focus_only)
    bounds = alpha.getbbox()
    if bounds is None:
        raise ValueError(f"generated source {source} contains no usable artwork")

    isolated = Image.new("RGBA", image.size, (0, 0, 0, 0))
    isolated.paste(image, (0, 0), alpha)
    cropped = isolated.crop(bounds)
    width, height = cropped.size
    square_size = max(width, height)
    padding = max(1, square_size // 28)
    square = Image.new(
        "RGBA",
        (square_size + 2 * padding, square_size + 2 * padding),
        (0, 0, 0, 0),
    )
    square.alpha_composite(
        cropped,
        (padding + (square_size - width) // 2, padding + (square_size - height) // 2),
    )
    # BOX retains the model's lighting and material separation while reducing
    # the high-resolution generation. The deliberately coarse logical grid keeps
    # tiny one-pixel highlights from turning the inventory icon into dotted noise.
    scaled = square.resize((CONTENT_SIZE, CONTENT_SIZE), Image.Resampling.BOX)
    scaled.putdata([
        (red, green, blue, 255 if alpha_value >= 58 else 0)
        for red, green, blue, alpha_value in scaled.getdata()
    ])
    scaled = scaled.quantize(
        colors=PALETTE_SIZE,
        method=Image.Quantize.FASTOCTREE,
        dither=Image.Dither.NONE,
    ).convert("RGBA")
    logical = Image.new("RGBA", (LOGICAL_SIZE, LOGICAL_SIZE), (0, 0, 0, 0))
    offset = (LOGICAL_SIZE - CONTENT_SIZE + 1) // 2
    logical.alpha_composite(scaled, (offset, offset))
    return logical.resize((CANVAS_SIZE, CANVAS_SIZE), Image.Resampling.NEAREST)


def write_contact_sheet(output_root: Path, targets: dict[str, Path], destination: Path) -> None:
    names = sorted(targets, key=lambda name: (len(targets[name].parts), str(targets[name])))
    columns, cell_width, cell_height = 7, 180, 190
    scale = max(1, 128 // CANVAS_SIZE)
    rows = math.ceil(len(names) / columns)
    sheet = Image.new("RGB", (columns * cell_width, rows * cell_height), "#303540")
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default(size=13)

    for index, name in enumerate(names):
        x = (index % columns) * cell_width
        y = (index // columns) * cell_height
        for pixel_y in range(CANVAS_SIZE):
            for pixel_x in range(CANVAS_SIZE):
                colour = "#657080" if (pixel_x + pixel_y) % 2 == 0 else "#56606f"
                draw.rectangle(
                    (
                        x + 26 + pixel_x * scale,
                        y + 8 + pixel_y * scale,
                        x + 25 + (pixel_x + 1) * scale,
                        y + 7 + (pixel_y + 1) * scale,
                    ),
                    fill=colour,
                )
        icon = Image.open(output_root / targets[name]).convert("RGBA")
        icon = icon.resize((CANVAS_SIZE * scale,) * 2, Image.Resampling.NEAREST)
        sheet.paste(icon, (x + 26, y + 8), icon)
        draw.text((x + 8, y + 146), name.replace("_", " "), fill="white", font=font)
        draw.rectangle((x, y, x + cell_width - 1, y + cell_height - 1), outline="#444b58")

    destination.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(destination, optimize=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True, help="JSON map of icon ID to generated PNG")
    parser.add_argument("--output", type=Path, required=True, help="Destination item-texture directory")
    parser.add_argument("--contact-sheet", type=Path, help="Optional labeled review sheet")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    sources = json.loads(args.manifest.read_text(encoding="utf-8"))
    targets = catalog_targets()
    missing = sorted(set(targets) - set(sources))
    unexpected = sorted(set(sources) - set(targets))
    if missing or unexpected:
        raise SystemExit(f"icon manifest mismatch: missing={missing}, unexpected={unexpected}")

    for name, relative_target in targets.items():
        source = Path(sources[name]).expanduser()
        if not source.is_file():
            raise SystemExit(f"missing generated source for {name}: {source}")
        target = args.output / relative_target
        target.parent.mkdir(parents=True, exist_ok=True)
        prepare(source, focus_only=name in FOCUS_ONLY).save(target, optimize=True)

    if args.contact_sheet:
        write_contact_sheet(args.output, targets, args.contact_sheet)
    print(f"Prepared {len(targets)} generated icons in {args.output}")


if __name__ == "__main__":
    main()
