#!/usr/bin/env python3
"""Derive each season's exclusive icons and gear from existing artwork.

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
ITEM_TEXTURES = MGX / "textures" / "item"
TEXTURES = ITEM_TEXTURES / "cosmetic"
WORN_WINGS = MGX / "textures" / "entity" / "equipment" / "wings"
WORN_ARMOUR = MGX / "textures" / "entity" / "equipment" / "humanoid"
EQUIPMENT = MGX / "equipment"
GEAR_MODELS = MGX / "models" / "item"
GEAR_ITEMS = MGX / "items"
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


def is_blade(hue: float, saturation: float) -> bool:
    return 0.40 < hue < 0.62 and saturation > 0.25


def is_not_handle(hue: float, saturation: float) -> bool:
    return not (0.05 < hue < 0.14 and saturation > 0.5)


def everything(_hue: float, _saturation: float) -> bool:
    return True


#: piece: (source texture, which pixels are "material", java item, model parent source, display)
GEAR = {
    "scythe": ("pvp_scythe_1", is_blade, "netherite_sword", "pvp_scythe_1", "Scythe", (0.22, 1.0)),
    "pickaxe": ("amethyst_pickaxe", is_not_handle, "netherite_pickaxe", "amethyst_pickaxe", "Pickaxe", None),
    "axe": ("amethyst_axe", is_not_handle, "netherite_axe", "amethyst_axe", "Axe", None),
    "wings": ("amethyst_elytra", everything, "elytra", "amethyst_elytra", "Wings", None),
    "boots": ("amethyst_boots", everything, "netherite_boots", "amethyst_boots", "Boots", None),
    "hoe": ("amethyst_hoe", is_not_handle, "netherite_hoe", "amethyst_hoe", "Hoe", None),
    "helmet": ("amethyst_helmet", everything, "netherite_helmet", "amethyst_helmet", "Helmet", None),
    "bow": ("amethyst_bow", is_not_handle, "bow", "amethyst_bow", "Bow", None),
}


def rgb(value: int) -> tuple[float, float, float]:
    return ((value >> 16) & 255) / 255, ((value >> 8) & 255) / 255, (value & 255) / 255


def mix(a, b, t):
    return tuple(x + (y - x) * t for x, y in zip(a, b))


def recolour(
    image: Image.Image, shadow: int, primary: int, highlight: int, select=everything, lift=None
) -> Image.Image:
    """Moves the hue of every selected pixel into the palette.

    Brightness never changes unless ``lift`` gives a (darkest, brightest) range to
    stretch the selection into: the leaderboard scythe blade is nearly black, and a
    season colour cannot read at that value. The order of every shade is kept either way.
    """
    image = image.convert("RGBA")

    def selected(pixel) -> bool:
        hue, saturation, _ = colorsys.rgb_to_hsv(*(channel / 255 for channel in pixel[:3]))
        return select(hue, saturation)

    values = [max(p[:3]) / 255 for p in image.getdata() if p[3] and selected(p)] or [0.0, 1.0]
    low, high = min(values), max(values)
    span = max(1e-6, high - low)
    stops = (rgb(shadow), rgb(primary), rgb(highlight))
    cache: dict[tuple[int, int, int, int], tuple[int, int, int, int]] = {}
    out = []
    for pixel in image.getdata():
        if not pixel[3] or not selected(pixel):
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
            if lift is not None:
                value = lift[0] + t * (lift[1] - lift[0])
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
    items = [item for item in items if not item["model"].startswith("mgx:season_")]
    for season, (theme, shadow, primary, highlight, _materials) in THEMES.items():
        for piece, (source, select, java_item, model_source, label, lift) in GEAR.items():
            name = f"season_{season}_{piece}"
            with Image.open(ITEM_TEXTURES / f"{source}.png") as original:
                recolour(original, shadow, primary, highlight, select, lift).save(
                    ITEM_TEXTURES / f"{name}.png", optimize=True)
            model = json.loads((GEAR_MODELS / f"{model_source}.json").read_text(encoding="utf-8"))
            model["textures"] = {"layer0": f"mgx:item/{name}"}
            write_json(GEAR_MODELS / f"{name}.json", model)
            write_json(GEAR_ITEMS / f"{name}.json",
                       {"model": {"type": "minecraft:model", "model": f"mgx:item/{name}"}})
            if piece == "helmet":
                # The Helmet and Boots share one worn look, as vanilla armour sets do.
                armour = f"season_{season}_armor"
                with Image.open(WORN_ARMOUR / "amethyst_armor.png") as worn:
                    recolour(worn, shadow, primary, highlight).save(WORN_ARMOUR / f"{armour}.png", optimize=True)
                write_json(EQUIPMENT / f"{armour}.json",
                           {"layers": {"humanoid": [{"texture": f"mgx:{armour}"}]}})
            if piece == "wings":
                with Image.open(WORN_WINGS / "amethyst_elytra.png") as worn:
                    recolour(worn, shadow, primary, highlight).save(WORN_WINGS / f"{name}.png", optimize=True)
                write_json(EQUIPMENT / f"{name}.json",
                           {"layers": {"wings": [{"texture": f"mgx:{name}"}]}})
            items.append({
                "java_item": f"minecraft:{java_item}",
                "model": f"mgx:{name}",
                "bedrock_identifier": f"mgx:{name}",
                "display_name": f"{theme} {label}",
            })
            print(f"  {name}")
    catalog["items"] = items
    CATALOG.write_text(catalog_text(items), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
