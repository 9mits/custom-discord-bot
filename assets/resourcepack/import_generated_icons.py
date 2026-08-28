#!/usr/bin/env python3
"""Prepare AI-generated icon sources for the Minecraft resource packs.

This importer never draws or invents icon geometry. It only removes the flat
generation backdrop, trims model artifacts, and scales the selected generated
artwork onto a 16x16 logical pixel grid, then enlarges that grid exactly 2x for
clean 32x32 inventory rendering. Potion reskins are the deliberate exception:
the supplied official bottle is copied exactly and only its existing liquid
pixels receive a colour ramp sampled from the corresponding generated edit.
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
POTION_REFERENCE = PACK_ROOT / "icon-sources/potion_of_healing_reference.png"
CANVAS_SIZE = 32
LOGICAL_SIZE = 16
CONTENT_SIZE = 14
PALETTE_SIZE = 24
BACKGROUND_DISTANCE = 72
FOCUS_ONLY = {
    "bronze_cataclysm",
    "conquerors_march",
    "golden_finality",
    "kingmakers_wake",
    "silver_reckoning",
}
POTION_PROFILES = {
    "crate_luck_potion": "violet",
    "fortune_potion": "emerald",
}


def catalog_targets() -> dict[str, Path]:
    roots = (ITEM_ROOT, ITEM_ROOT / "cosmetic")
    targets = {
        path.stem: path.relative_to(ITEM_ROOT)
        for root in roots
        for path in sorted(root.glob("*.png"))
    }
    # A newly generated asset has a committed item model before it has its first
    # texture. Include those model IDs so --partial can bootstrap the PNG itself.
    model_root = PACK_ROOT / "src/assets/mgx/items"
    for root, prefix in ((model_root, Path()), (model_root / "cosmetic", Path("cosmetic"))):
        for path in sorted(root.glob("*.json")):
            targets.setdefault(path.stem, prefix / f"{path.stem}.png")
    return targets


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


def potion_liquid(colour: tuple[int, int, int, int], y: int) -> bool:
    """Identify only the seven red liquid shades in the supplied vanilla icon."""
    red, green, blue, alpha = colour
    return (
        alpha == 255
        and y >= 70
        and red >= 90
        and red > green * 1.25
        and red > blue * 1.18
    )


def generated_liquid_palette(source: Path, profile: str, shades: int) -> list[tuple[int, int, int]]:
    """Sample a restrained liquid ramp from an AI-generated potion edit."""
    image = Image.open(source).convert("RGB")
    image.thumbnail((256, 256), Image.Resampling.BOX)
    samples: list[tuple[int, int, int]] = []
    for red, green, blue in image.getdata():
        spread = max(red, green, blue) - min(red, green, blue)
        if spread < 35:
            continue
        if profile == "emerald":
            selected = green > red * 1.08 and green > blue * 1.05
        else:
            selected = red > green * 1.18 and blue > green * 1.18
        if selected:
            samples.append((red, green, blue))
    if len(samples) < shades:
        raise ValueError(f"generated source {source} has no usable {profile} liquid palette")
    samples.sort(key=lambda colour: 299 * colour[0] + 587 * colour[1] + 114 * colour[2])
    quantiles = (0.05, 0.18, 0.32, 0.48, 0.64, 0.80, 0.94)
    return [samples[round((len(samples) - 1) * quantiles[index])] for index in range(shades)]


def prepare_potion(source: Path, profile: str) -> Image.Image:
    """Recolour liquid without changing one bottle, padding, or alpha pixel."""
    bottle = Image.open(POTION_REFERENCE).convert("RGBA")
    liquid_colours = sorted(
        {
            bottle.getpixel((x, y))[:3]
            for y in range(bottle.height)
            for x in range(bottle.width)
            if potion_liquid(bottle.getpixel((x, y)), y)
        },
        key=lambda colour: 299 * colour[0] + 587 * colour[1] + 114 * colour[2],
    )
    palette = generated_liquid_palette(source, profile, len(liquid_colours))
    replacements = dict(zip(liquid_colours, palette))
    output = bottle.copy()
    pixels = output.load()
    for y in range(output.height):
        for x in range(output.width):
            colour = pixels[x, y]
            if potion_liquid(colour, y):
                pixels[x, y] = (*replacements[colour[:3]], colour[3])
    return output


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
    parser.add_argument(
        "--partial",
        action="store_true",
        help="Import only manifest entries; every entry must still name a catalog target",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    sources = json.loads(args.manifest.read_text(encoding="utf-8"))
    targets = catalog_targets()
    missing = [] if args.partial else sorted(set(targets) - set(sources))
    unexpected = sorted(set(sources) - set(targets))
    if missing or unexpected:
        raise SystemExit(f"icon manifest mismatch: missing={missing}, unexpected={unexpected}")

    selected_targets = {
        name: targets[name]
        for name in (sources if args.partial else targets)
    }
    for name, relative_target in selected_targets.items():
        source = Path(sources[name]).expanduser()
        if not source.is_file():
            raise SystemExit(f"missing generated source for {name}: {source}")
        target = args.output / relative_target
        target.parent.mkdir(parents=True, exist_ok=True)
        prepared = (
            prepare_potion(source, POTION_PROFILES[name])
            if name in POTION_PROFILES
            else prepare(source, focus_only=name in FOCUS_ONLY)
        )
        prepared.save(target, optimize=True)

    if args.contact_sheet:
        write_contact_sheet(args.output, selected_targets, args.contact_sheet)
    print(f"Prepared {len(selected_targets)} generated icons in {args.output}")


if __name__ == "__main__":
    main()
