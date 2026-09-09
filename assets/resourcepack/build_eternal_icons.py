#!/usr/bin/env python3
"""Derive the Eternal icons from the Amethyst ones.

The Eternal items are the permanent versions of the 24-hour Amethyst gear, and they
have to be tellable apart at a glance in a hotbar. Minecraft's enchantment glint is a
single global texture, so a per-item shimmer is not available: a distinct texture is
the only way one item can look different from another.

Nothing here draws geometry, which the icon art direction forbids. It is the same
move the two potion reskins already use — a palette applied to existing pixels. Every
opaque pixel keeps its position, its alpha and its brightness; only its hue moves,
sweeping along the item so the sprite reads as prismatic rather than recoloured.

    python assets/resourcepack/build_eternal_icons.py
"""

from __future__ import annotations

import colorsys
import json
from pathlib import Path

from PIL import Image

PACK = Path(__file__).resolve().parent / "src" / "assets" / "mgx"
TEXTURES = PACK / "textures" / "item"
MODELS = PACK / "models" / "item"
ITEMS = PACK / "items"

#: The timed gear that gets a permanent twin, and the vanilla item each is built on.
ETERNAL_ITEMS = {
    "amethyst_sword": ("minecraft:diamond_sword", "minecraft:item/handheld"),
    "amethyst_pickaxe": ("minecraft:diamond_pickaxe", "minecraft:item/handheld"),
    "amethyst_shovel": ("minecraft:diamond_shovel", "minecraft:item/handheld"),
    "amethyst_axe": ("minecraft:diamond_axe", "minecraft:item/handheld"),
    "amethyst_hoe": ("minecraft:diamond_hoe", "minecraft:item/handheld"),
    "amethyst_bow": ("minecraft:bow", "minecraft:item/bow"),
    "amethyst_elytra": ("minecraft:elytra", "minecraft:item/generated"),
}

#: A full turn of the wheel, started in the violet so the item still opens on the
#: amethyst it is a version of and travels the whole spectrum from there. A partial
#: sweep just tints the sprite one colour, which reads as a recolour rather than as
#: something rarer. Full saturation reads as a toy; this keeps metal looking like metal.
SWEEP = 1.0
HUE_START = 0.78
SATURATION = 0.65


def prismatic(image: Image.Image) -> Image.Image:
    """Re-hue along the item's diagonal. Geometry and alpha are untouched."""
    image = image.convert("RGBA")
    width, height = image.size
    source = image.load()
    box = image.getbbox()
    if box is None:
        return image
    left, top, right, bottom = box
    span = max(1, (right - left) + (bottom - top))
    out = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    target = out.load()
    for y in range(height):
        for x in range(width):
            red, green, blue, alpha = source[x, y]
            if alpha == 0:
                continue
            _, _, value = colorsys.rgb_to_hsv(red / 255, green / 255, blue / 255)
            position = (((x - left) + (y - top)) / span) % 1.0
            new_r, new_g, new_b = colorsys.hsv_to_rgb(
                (HUE_START + position * SWEEP) % 1.0, SATURATION, value
            )
            target[x, y] = (
                int(new_r * 255), int(new_g * 255), int(new_b * 255), alpha
            )
    return out


def write_json(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, separators=(",", ":")) + "\n", encoding="utf-8")


def main() -> int:
    for base, (_vanilla, parent) in ETERNAL_ITEMS.items():
        name = f"eternal_{base}"
        source = Image.open(TEXTURES / f"{base}.png")
        prismatic(source).save(TEXTURES / f"{name}.png", optimize=True)
        write_json(MODELS / f"{name}.json",
                   {"parent": parent, "textures": {"layer0": f"mgx:item/{name}"}})
        write_json(ITEMS / f"{name}.json",
                   {"model": {"type": "minecraft:model", "model": f"mgx:item/{name}"}})
        print(f"  {name}")
    print(f"{len(ETERNAL_ITEMS)} Eternal icons, models and item definitions written")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
