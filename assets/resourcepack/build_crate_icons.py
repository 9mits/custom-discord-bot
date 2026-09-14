#!/usr/bin/env python3
"""Derive the Relic and key-free crate cosmetic icons from existing artwork.

Same rule as ``build_season_icons.py``, whose recolour it reuses: nothing is drawn, every
opaque pixel keeps its position, alpha and brightness, and only hue and saturation move
into the new palette.

- Relics come from the imported Amethyst gear, with wooden handles and bow wood kept.
  The Lantern Helm and Cloudstrider Boots also get a worn texture from the Amethyst armour.
- Dawnbreak (Daily Crate) and Dreamdrift (AFK Crate) cosmetics come from Amethyst Orbit,
  Frost Trail and Blood Burst, so they never read as a Season exclusive.

The palettes must match RelicCatalog and CrateCosmetics in the plugin; a test compares them.

    python assets/resourcepack/build_crate_icons.py
"""

from __future__ import annotations

import json
from pathlib import Path

from PIL import Image

import build_season_icons as season
import import_generated_icons as importer

PACK = Path(__file__).resolve().parent
MGX = PACK / "src" / "assets" / "mgx"
ITEM_TEXTURES = MGX / "textures" / "item"
WORN = MGX / "textures" / "entity" / "equipment" / "humanoid"
EQUIPMENT = MGX / "equipment"
MODELS = MGX / "models" / "item"
ITEMS = MGX / "items"
CATALOG = PACK / "bedrock" / "catalog.json"

#: relic: (source, select, (shadow, primary, highlight), java item, display name, worn)
RELICS = {
    "veinseeker_pickaxe": ("amethyst_pickaxe", season.is_not_handle, (0x1C7F74, 0x3FD8C6, 0xC9FFF6),
                           "diamond_pickaxe", "Veinseeker Pickaxe", False),
    "magnetite_shovel": ("amethyst_shovel", season.is_not_handle, (0x3B4250, 0xC9CED6, 0xF4F7FB),
                         "diamond_shovel", "Magnetite Shovel", False),
    "bloodthirst_blade": ("amethyst_sword", season.is_not_handle, (0x4A0710, 0xC81E2C, 0xFF6B5E),
                          "diamond_sword", "Bloodthirst Blade", False),
    "frostbite_bow": ("amethyst_bow", season.is_not_handle, (0x2A5DA8, 0x8FD8FF, 0xF2FBFF),
                      "bow", "Frostbite Bow", False),
    "verdant_sickle": ("amethyst_hoe", season.is_not_handle, (0x1E6B2A, 0x62E06A, 0xE4FFB8),
                       "diamond_hoe", "Verdant Sickle", False),
    "lantern_helm": ("amethyst_helmet", season.everything, (0x9A5B12, 0xFFC247, 0xFFF6D6),
                     "diamond_helmet", "Lantern Helm", True),
    "cloudstrider_boots": ("amethyst_boots", season.everything, (0x5F7FB3, 0xBFD9FF, 0xFFFFFF),
                           "diamond_boots", "Cloudstrider Boots", True),
}

#: theme: (name, (shadow, primary, highlight), {slot: java item})
THEMES = {
    "dawnbreak": ("Dawnbreak", (0xC2185B, 0xFF8A3D, 0xFFE39A),
                  {"aura": "orange_tulip", "trail": "honeycomb", "kill": "copper_ingot"}),
    "dreamdrift": ("Dreamdrift", (0x3B2A8C, 0x9D8CFF, 0xB8FFF4),
                   {"aura": "phantom_membrane", "trail": "feather", "kill": "ender_pearl"}),
}
def vivid(image: Image.Image, select=season.everything) -> Image.Image:
    """Give a recolour the depth a straight hue swap loses.

    A recolour keeps brightness exactly, but a gradient palette on low-contrast source
    art reads as one flat colour. This widens the selected pixels' own value range around
    their midpoint, saturates the body, lifts the brightest few towards a specular white
    and deepens the darkest into a real shadow. It depends on colour alone, so a sprite
    never gains colours, and it only moves pixels that already exist.
    """
    import colorsys

    image = image.convert("RGBA")
    pixels = list(image.getdata())

    def chosen(pixel):
        if not pixel[3]:
            return False
        hue, saturation, _ = colorsys.rgb_to_hsv(*(c / 255 for c in pixel[:3]))
        return select(hue, saturation)

    values = sorted(max(p[:3]) / 255 for p in pixels if chosen(p))
    if len(values) < 4:
        return image
    low, high = values[int(len(values) * 0.12)], values[int(len(values) * 0.92)]
    middle = values[len(values) // 2]
    # A specular glint is an accent. When the brightest band is a whole face (a blade),
    # desaturating it would turn the item pastel, so the face keeps its colour instead.
    bright_share = sum(1 for value in values if value >= high) / len(values)
    specular = bright_share <= 0.12
    cache = {}
    out = []
    for pixel in pixels:
        if not chosen(pixel):
            out.append(pixel)
            continue
        if pixel not in cache:
            hue, saturation, value = colorsys.rgb_to_hsv(*(c / 255 for c in pixel[:3]))
            value = max(0.0, min(1.0, middle + (value - middle) * 1.3))
            saturation = min(1.0, saturation * 1.2)
            if value >= high and specular:
                saturation *= 0.6
                value = min(1.0, value * 1.1)
            elif value >= high:
                saturation = min(1.0, saturation * 1.25)
                value *= 0.9
            elif value <= low:
                value *= 0.72
                saturation = min(1.0, saturation * 1.15)
            r, g, b = colorsys.hsv_to_rgb(hue, saturation, value)
            cache[pixel] = (round(r * 255), round(g * 255), round(b * 255), pixel[3])
        out.append(cache[pixel])
    result = Image.new("RGBA", image.size)
    result.putdata(out)
    return result


def lit_cosmetic(image: Image.Image) -> Image.Image:
    """The house shading pass on the 18x18 logical grid, then back to crisp 4x4 blocks."""
    size = image.width
    step = size // 18
    logical = image.resize((18, 18), Image.NEAREST)
    shaded = vivid(importer.shade(logical, "crate_cosmetic"))
    quantized = shaded.convert("RGBA")
    colours = quantized.getcolors(maxcolors=4096) or []
    if len(colours) > 32:
        alpha = quantized.getchannel("A")
        quantized = quantized.convert("RGB").quantize(colors=31, method=Image.Quantize.MEDIANCUT).convert("RGBA")
        quantized.putalpha(alpha)
    return quantized.resize((18 * step, 18 * step), Image.NEAREST)


COSMETIC_SOURCES = {"aura": "amethyst_orbit", "trail": "frost_trail", "kill": "blood_burst"}
COSMETIC_NAMES = {"aura": "Orbit", "trail": "Helix", "kill": "Shockwave"}


def main() -> int:
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    ours = {f"mgx:{relic}" for relic in RELICS} | {
        f"mgx:cosmetic/{theme}_{slot}" for theme in THEMES for slot in COSMETIC_SOURCES
    }
    items = [item for item in catalog["items"] if item["model"] not in ours]
    for relic, (source, select, palette, java_item, name, worn) in RELICS.items():
        with Image.open(ITEM_TEXTURES / f"{source}.png") as original:
            vivid(season.recolour(original, *palette, select), select).save(
                ITEM_TEXTURES / f"{relic}.png", optimize=True)
        model = json.loads((MODELS / f"{source}.json").read_text(encoding="utf-8"))
        model["textures"] = {"layer0": f"mgx:item/{relic}"}
        season.write_json(MODELS / f"{relic}.json", model)
        season.write_json(ITEMS / f"{relic}.json",
                          {"model": {"type": "minecraft:model", "model": f"mgx:item/{relic}"}})
        if worn:
            with Image.open(WORN / "amethyst_armor.png") as armour:
                vivid(season.recolour(armour, *palette)).save(WORN / f"{relic}.png", optimize=True)
            season.write_json(EQUIPMENT / f"{relic}.json",
                              {"layers": {"humanoid": [{"texture": f"mgx:{relic}"}]}})
        items.append({"java_item": f"minecraft:{java_item}", "model": f"mgx:{relic}",
                      "bedrock_identifier": f"mgx:{relic}", "display_name": name})
        print(f"  {relic}")
    for theme, (display, palette, materials) in THEMES.items():
        for slot, source in COSMETIC_SOURCES.items():
            name = f"{theme}_{slot}"
            with Image.open(ITEM_TEXTURES / "cosmetic" / f"{source}.png") as original:
                lit_cosmetic(season.recolour(original, *palette)).save(
                    ITEM_TEXTURES / "cosmetic" / f"{name}.png", optimize=True)
            season.write_json(MODELS / "cosmetic" / f"{name}.json",
                              {"parent": "minecraft:item/generated",
                               "textures": {"layer0": f"mgx:item/cosmetic/{name}"}})
            season.write_json(ITEMS / "cosmetic" / f"{name}.json",
                              {"model": {"type": "minecraft:model", "model": f"mgx:item/cosmetic/{name}"}})
            items.append({"java_item": f"minecraft:{materials[slot]}", "model": f"mgx:cosmetic/{name}",
                          "bedrock_identifier": f"mgx:cosmetic_{name}",
                          "display_name": f"{display} {COSMETIC_NAMES[slot]}"})
            print(f"  {name}")
    CATALOG.write_text(season.catalog_text(items), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
