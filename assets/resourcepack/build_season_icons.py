#!/usr/bin/env python3
"""Derive each season's exclusive icons from existing cosmetic artwork.

Season exclusives share their shapes and differ by palette, and their icons follow the
same rule. Nothing here draws geometry, which the icon art direction forbids: like the
Eternal twins and the potion reskins, it re-colours pixels that already exist. Every
opaque pixel keeps its position, its alpha and its exact brightness, so the shading the
art direction requires survives untouched; only hue and saturation move, taken from
the season palette by how bright the pixel was.

The mapping depends on colour alone, never on position, so a sprite cannot gain colours
and stays inside the palette budget the icon tests enforce.

The themes must match SeasonCosmetics.THEMES in the plugin; a Python test checks that.

    python assets/resourcepack/build_season_icons.py
"""

from __future__ import annotations

import colorsys
import json
from pathlib import Path

from PIL import Image

PACK = Path(__file__).resolve().parent
MGX = PACK / "src" / "assets" / "mgx"
TEXTURES = MGX / "textures" / "item" / "cosmetic"
MODELS = MGX / "models" / "item" / "cosmetic"
ITEMS = MGX / "items" / "cosmetic"
CATALOG = PACK / "bedrock" / "catalog.json"

#: Which existing icon each exclusive is re-coloured from.
SOURCES = {"aura": "celestial_crown", "trail": "ender_trail", "kill": "shining_light"}

#: season: (theme, shadow, primary, highlight, {slot: (display name, java item)})
THEMES = {
    1: ("Solstice", 0xD9480F, 0xFFB22E, 0xFFF1B8,
        {"aura": "sunflower", "trail": "blaze_powder", "kill": "golden_sword"}),
    2: ("Frostbound", 0x1F4FB8, 0x5CC4FF, 0xDDF4FF,
        {"aura": "blue_ice", "trail": "snowball", "kill": "diamond_sword"}),
    3: ("Verdant", 0x167A47, 0x4FE08A, 0xE4FFB8,
        {"aura": "emerald", "trail": "slime_ball", "kill": "iron_sword"}),
    4: ("Eclipse", 0x4A1640, 0xF03A5F, 0xFFB3C6,
        {"aura": "crimson_fungus", "trail": "redstone", "kill": "netherite_sword"}),
}
SLOT_NAMES = {"aura": "Crown", "trail": "Wake", "kill": "Verdict"}


def rgb(value: int) -> tuple[float, float, float]:
    return ((value >> 16) & 255) / 255, ((value >> 8) & 255) / 255, (value & 255) / 255


def mix(a, b, t):
    return tuple(x + (y - x) * t for x, y in zip(a, b))


def recolour(image: Image.Image, shadow: int, primary: int, highlight: int) -> Image.Image:
    image = image.convert("RGBA")
    values = [max(p[:3]) / 255 for p in image.getdata() if p[3]]
    low, high = min(values), max(values)
    span = max(1e-6, high - low)
    stops = (rgb(shadow), rgb(primary), rgb(highlight))
    cache: dict[tuple[int, int, int, int], tuple[int, int, int, int]] = {}
    out = []
    for pixel in image.getdata():
        if not pixel[3]:
            out.append(pixel)
            continue
        if pixel not in cache:
            value = max(pixel[:3]) / 255
            t = (value - low) / span
            tint = mix(stops[0], stops[1], t * 2) if t < 0.5 else mix(stops[1], stops[2], (t - 0.5) * 2)
            hue, saturation, _ = colorsys.rgb_to_hsv(*tint)
            # Dark pixels of a light tint read as grey-brown; a firmer saturation keeps
            # the whole sprite in the season colour rather than only its highlights.
            saturation = min(1.0, saturation * 1.3)
            r, g, b = colorsys.hsv_to_rgb(hue, saturation, value)
            cache[pixel] = (round(r * 255), round(g * 255), round(b * 255), pixel[3])
        out.append(cache[pixel])
    result = Image.new("RGBA", image.size)
    result.putdata(out)
    return result


def write_json(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, separators=(",", ":")) + "\n", encoding="utf-8")


def catalog_text(items: list[dict]) -> str:
    """The catalog's own layout: one compact item per line, so diffs stay readable."""
    rows = ",\n".join("    " + json.dumps(item, ensure_ascii=False) for item in items)
    return "{\n  \"items\": [\n" + rows + "\n  ]\n}\n"


def main() -> int:
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    items = [item for item in catalog["items"] if not item["model"].startswith("mgx:cosmetic/season_")]
    for season, (theme, shadow, primary, highlight, materials) in THEMES.items():
        for slot, source in SOURCES.items():
            name = f"season_{season}_{slot}"
            with Image.open(TEXTURES / f"{source}.png") as original:
                recolour(original, shadow, primary, highlight).save(TEXTURES / f"{name}.png", optimize=True)
            write_json(MODELS / f"{name}.json",
                       {"parent": "minecraft:item/generated", "textures": {"layer0": f"mgx:item/cosmetic/{name}"}})
            write_json(ITEMS / f"{name}.json",
                       {"model": {"type": "minecraft:model", "model": f"mgx:item/cosmetic/{name}"}})
            items.append({
                "java_item": f"minecraft:{materials[slot]}",
                "model": f"mgx:cosmetic/{name}",
                "bedrock_identifier": f"mgx:cosmetic_{name}",
                "display_name": f"{theme} {SLOT_NAMES[slot]}",
            })
            print(f"  {name}")
    catalog["items"] = items
    CATALOG.write_text(catalog_text(items), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
