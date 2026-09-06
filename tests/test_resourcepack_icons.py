import hashlib
import json
import unittest
import zipfile
from pathlib import Path

from PIL import Image


REPO = Path(__file__).resolve().parents[1]
RESOURCE_PACK = REPO / "assets" / "resourcepack"
ITEM_TEXTURES = RESOURCE_PACK / "src" / "assets" / "mgx" / "textures" / "item"
NATIVE_POTION_ICONS = {"crate_luck_potion", "fortune_potion"}
LINKED_ICON_SIZES = {
    "amethyst_apple": (32, 32),
    "amethyst_arrow": (16, 16),
    "amethyst_boots": (16, 16),
    "amethyst_bow": (16, 16),
    "amethyst_chestplate": (16, 16),
    "amethyst_dragon_egg": (16, 16),
    "amethyst_elytra": (16, 16),
    "amethyst_helmet": (16, 16),
    "amethyst_hoe": (16, 16),
    "amethyst_leggings": (16, 16),
    "amethyst_pickaxe": (16, 16),
    "amethyst_shovel": (16, 16),
    "amethyst_axe": (16, 16),
    "amethyst_shield": (64, 64),
    "amethyst_shield_icon": (590, 876),
    "amethyst_totem": (360, 360),
    "amethyst_sword": (16, 16),
}
IMPORTED_MOD_HASHES = {
    "amethyst_pickaxe": "65630e43cdb2634ae0fa77d9ac1d9bc2a2b657a59fb4ea32932d057f5afdb2d9",
    "amethyst_shovel": "32b5016af36735c55d1614cf065d2b906eb58bb55fa4703fc7ddc9d799d78547",
    "amethyst_axe": "5c43672b2716bcb2fd4f5e0c06c66080eb1cf00b60d8c72cc4d8f581bbbec030",
    "amethyst_shield": "79c0eaaf8939888df6b0e28e1a080db648cd56a5a9622d74f51596a1e385ee01",
    "amethyst_sword": "4e1b69e98d1bed76f5f40ecc26fea99afe77a1da82eca352301299aa5aef9488",
}
# Every generated icon is designed on an 18x18 logical grid and exported at 72x72 as
# exact 4x4 blocks. The 4x export rather than 2x is about the item atlas: 36 divides
# only by 4 and would cap the atlas mipmap chain lower than the pack's existing
# 360x360 totem already does, while 72 divides by 8 and matches it. The Shard is the
# one exception still on the older 16x16 grid at 32x32: its artwork came from a
# supplied transparent source that is no longer on hand to re-import, so it keeps the
# sprite it shipped with rather than being upscaled into a blurrier version of itself.
GENERATED_ICON = (72, 4)
LEGACY_GRID_ICONS = {"shard": (32, 2)}
# The masked placeholder is a flat black silhouette on purpose: it is what a crate shows
# in place of a secret nobody owns yet, and giving it form would give the secret away.
DELIBERATELY_FLAT_ICONS = {"secret_silhouette"}
# Comfortably under the current minimum (0.55) so ordinary artwork variation passes,
# and far above the 0.13-0.27 the flat batch was shipping at.
MINIMUM_VALUE_SPREAD = 0.45
POTION_REFERENCE = RESOURCE_PACK / "icon-sources" / "potion_of_healing_reference.png"
EVENT_SONG_SHA256 = "768d3d503ac3e8ba39f6db1213a8296abcde9260944212fd5fe00d0f81ecc448"
DRAGON_SONG_SHA256 = "5cf005148259f8ed415215077245e3fccaa4ca351174034109bafe4a447ade2d"


def is_potion_liquid(colour, y):
    red, green, blue, alpha = colour
    return (
        alpha == 255
        and y >= 70
        and red >= 90
        and red > green * 1.25
        and red > blue * 1.18
    )


class ResourcePackIconTests(unittest.TestCase):
    def icon_paths(self):
        return sorted(ITEM_TEXTURES.glob("*.png")) + sorted((ITEM_TEXTURES / "cosmetic").glob("*.png"))

    def test_java_and_bedrock_packs_share_the_current_description(self):
        expected = "Mysterious SMP X — custom items, cosmetics, sounds, and UI"
        java_source = json.loads(
            (RESOURCE_PACK / "src" / "pack.mcmeta").read_text(encoding="utf-8")
        )
        self.assertEqual(expected, java_source["pack"]["description"])

        with zipfile.ZipFile(RESOURCE_PACK / "MysteriousSMPX.zip") as java_pack:
            java_built = json.loads(java_pack.read("pack.mcmeta"))
        with zipfile.ZipFile(
            RESOURCE_PACK / "bedrock" / "MysteriousSMPX-Bedrock.mcpack"
        ) as bedrock_pack:
            bedrock_built = json.loads(bedrock_pack.read("manifest.json"))
        self.assertEqual(expected, java_built["pack"]["description"])
        self.assertEqual(expected, bedrock_built["header"]["description"])

    def test_built_java_pack_matches_every_source_file(self):
        """The shipped zip must be the sources, not a stale build of them.

        Editing a texture without re-running build_pack.py leaves the client loading
        the previous artwork while the repo looks correct, which is invisible in every
        other check here — the Bedrock pack has this guard, the Java pack did not, and
        eight icons shipped stale because of it.
        """
        source_root = RESOURCE_PACK / "src"
        with zipfile.ZipFile(RESOURCE_PACK / "MysteriousSMPX.zip") as pack:
            packed = set(pack.namelist())
            stale = []
            for path in sorted(source_root.rglob("*")):
                if not path.is_file():
                    continue
                name = path.relative_to(source_root).as_posix()
                if name not in packed:
                    stale.append(f"{name} (missing from the zip)")
                elif pack.read(name) != path.read_bytes():
                    stale.append(f"{name} (stale in the zip)")
        self.assertEqual(
            [], stale,
            "rebuild with assets/resourcepack/build_pack.py: " + ", ".join(stale[:8]),
        )

    def test_custom_icons_are_valid_distinct_minecraft_sprites(self):
        icons = self.icon_paths()
        self.assertEqual(91, len(icons))

        digests = set()
        for path in icons:
            with self.subTest(icon=path.stem):
                with Image.open(path) as image:
                    self.assertEqual("RGBA", image.mode)
                    if path.stem in LINKED_ICON_SIZES:
                        self.assertEqual(LINKED_ICON_SIZES[path.stem], image.size)
                        expected_hash = IMPORTED_MOD_HASHES.get(path.stem)
                        if expected_hash:
                            self.assertEqual(expected_hash, hashlib.sha256(path.read_bytes()).hexdigest())
                        digests.add(hashlib.sha256(path.read_bytes()).digest())
                        continue
                    alpha = image.getchannel("A")
                    self.assertEqual({0, 255}, set(alpha.getdata()))
                    bounds = alpha.getbbox()
                    self.assertIsNotNone(bounds)
                    if path.stem in NATIVE_POTION_ICONS:
                        self.assertEqual((160, 160), image.size)
                        with Image.open(POTION_REFERENCE) as reference:
                            reference = reference.convert("RGBA")
                            self.assertEqual(reference.getchannel("A").tobytes(), alpha.tobytes())
                            changed_liquid = 0
                            for y in range(160):
                                for x in range(160):
                                    expected = reference.getpixel((x, y))
                                    actual = image.getpixel((x, y))
                                    if is_potion_liquid(expected, y):
                                        changed_liquid += actual != expected
                                    else:
                                        self.assertEqual(expected, actual, f"bottle changed at {x},{y}")
                            self.assertGreater(changed_liquid, 0)
                            liquid = [
                                image.getpixel((x, y))[:3]
                                for y in range(160)
                                for x in range(160)
                                if is_potion_liquid(reference.getpixel((x, y)), y)
                            ]
                            average = tuple(
                                sum(colour[channel] for colour in liquid) / len(liquid)
                                for channel in range(3)
                            )
                            if path.stem == "fortune_potion":
                                self.assertGreater(average[1], max(average[0], average[2]) * 2)
                            else:
                                self.assertGreater(average[2], average[1] * 2)
                                self.assertGreater(average[0], average[1] * 2)
                    else:
                        canvas, step = LEGACY_GRID_ICONS.get(path.stem, GENERATED_ICON)
                        self.assertEqual((canvas, canvas), image.size)
                        colours = image.getcolors(maxcolors=257)
                        self.assertIsNotNone(colours)
                        self.assertLessEqual(len(colours), 32)
                        pixels = image.load()
                        for y in range(0, canvas, step):
                            for x in range(0, canvas, step):
                                block = {
                                    pixels[x + dx, y + dy]
                                    for dx in range(step) for dy in range(step)
                                }
                                self.assertEqual(
                                    1, len(block),
                                    f"every logical pixel must be a crisp {step}x{step} block",
                                )
                        # Framing, not just presence. The importer measures what actually
                        # survives its alpha cut and rescales until the long axis fills
                        # the content box, so an icon that drifts small again — the way
                        # several used to — fails here rather than shipping undersized.
                        width = bounds[2] - bounds[0]
                        height = bounds[3] - bounds[1]
                        self.assertGreaterEqual(
                            max(width, height), round(canvas * 0.80),
                            "an icon must fill its content box on its long axis",
                        )
                        self.assertGreaterEqual(
                            min(width, height), round(canvas * 0.45),
                            "an icon must not collapse to a sliver on its short axis",
                        )
                digests.add(hashlib.sha256(path.read_bytes()).digest())

        self.assertEqual(len(icons), len(digests), "custom icons must not be duplicate recolour assets")

    def test_generated_icons_have_enough_light_to_read_as_solid(self):
        """A sprite with no light-to-dark range looks flat and lifeless in the slot.

        Several generated batches arrived tonally compressed — one violet at one
        brightness, no shadow, no highlight. The importer's shading pass widens each
        sprite's own value range around its own midpoint and shades its silhouette
        edge, which is the lighting the art direction already specifies. This is the
        floor that keeps a future flat batch from shipping unnoticed.
        """
        flat = []
        for path in self.icon_paths():
            if path.stem in LINKED_ICON_SIZES or path.stem in NATIVE_POTION_ICONS:
                continue
            if path.stem in DELIBERATELY_FLAT_ICONS:
                continue
            with Image.open(path) as image:
                image = image.convert("RGBA")
                if image.size != (GENERATED_ICON[0],) * 2:
                    continue
                values = sorted(
                    max(pixel[:3]) / 255
                    for pixel in image.getdata()
                    if pixel[3] == 255
                )
            spread = values[int(len(values) * 0.90)] - values[int(len(values) * 0.10)]
            if spread < MINIMUM_VALUE_SPREAD:
                flat.append(f"{path.stem} ({spread:.2f})")
        self.assertEqual([], flat, "these icons have no shading: " + ", ".join(flat))

    def test_dragon_icons_use_generated_artwork_workflow(self):
        self.assertFalse(
            (RESOURCE_PACK / "build_dragon_cosmetic_icons.py").exists(),
            "Dragon icon geometry must come from image generation, not a drawing script",
        )
        direction = (RESOURCE_PACK / "ICON_ART_DIRECTION.md").read_text(encoding="utf-8")
        expansion = (RESOURCE_PACK / "AMETHYST_EXPANSION_ASSETS.md").read_text(encoding="utf-8")
        for icon in (
            "dragonheart_rupture", "crystal_wingfall", "endscale_cataclysm",
            "amethyst_dragon_crown", "violet_wyrm_orbit", "geode_sovereignty",
            "dragonflight_wake", "shardwing_procession", "crystalfire_trail",
            "amethyst_dragon_ascendant", "dragon_podium_1", "dragon_podium_2",
            "dragon_podium_3", "dragon_clan_1", "dragon_clan_2", "dragon_clan_3",
        ):
            self.assertIn(f"`{icon}`", direction)
        self.assertIn("built-in image-generation tool", expansion)
        self.assertIn("No script draws their", expansion)

    def test_bedrock_pack_contains_the_canonical_java_icon_bytes(self):
        catalog = json.loads((RESOURCE_PACK / "bedrock" / "catalog.json").read_text(encoding="utf-8"))
        pack = RESOURCE_PACK / "bedrock" / "MysteriousSMPX-Bedrock.mcpack"

        with zipfile.ZipFile(pack) as archive:
            for item in catalog["items"]:
                with self.subTest(item=item["bedrock_identifier"]):
                    explicit_icon = item.get("icon_texture")
                    if explicit_icon:
                        texture_namespace, texture_name = explicit_icon.split(":", 1)
                    else:
                        namespace, name = item["model"].split(":", 1)
                        item_model = json.loads((
                            RESOURCE_PACK / "src" / "assets" / namespace / "items" / f"{name}.json"
                        ).read_text(encoding="utf-8"))["model"]
                        while item_model["type"] == "minecraft:condition":
                            item_model = item_model["on_false"]
                        model_namespace, model_name = item_model["model"].split(":", 1)
                        model = json.loads((
                            RESOURCE_PACK / "src" / "assets" / model_namespace / "models" / f"{model_name}.json"
                        ).read_text(encoding="utf-8"))
                        texture = model["textures"].get("layer0", model["textures"].get("0"))
                        texture_namespace, texture_name = texture.split(":", 1)
                    java_texture = (
                        RESOURCE_PACK / "src" / "assets" / texture_namespace / "textures" / f"{texture_name}.png"
                    )
                    bedrock_name = item["bedrock_identifier"].split(":", 1)[1]
                    self.assertEqual(
                        java_texture.read_bytes(),
                        archive.read(f"textures/items/{bedrock_name}.png"),
                    )

    def test_exact_event_song_ships_in_both_edition_packs(self):
        java_sound = (
            RESOURCE_PACK / "src" / "assets" / "mgx" / "sounds" / "music"
            / "iridescent_imperium.ogg"
        )
        self.assertEqual(EVENT_SONG_SHA256, hashlib.sha256(java_sound.read_bytes()).hexdigest())
        with zipfile.ZipFile(RESOURCE_PACK / "MysteriousSMPX.zip") as java_pack:
            self.assertEqual(
                java_sound.read_bytes(),
                java_pack.read("assets/mgx/sounds/music/iridescent_imperium.ogg"),
            )
        with zipfile.ZipFile(RESOURCE_PACK / "bedrock" / "MysteriousSMPX-Bedrock.mcpack") as bedrock_pack:
            self.assertEqual(
                java_sound.read_bytes(),
                bedrock_pack.read("sounds/music/iridescent_imperium.ogg"),
            )
            definitions = json.loads(bedrock_pack.read("sounds/sound_definitions.json"))
            self.assertIn(
                "mgx:iridescent_imperium",
                definitions["sound_definitions"],
            )

    def test_exact_dragon_song_ships_in_both_edition_packs(self):
        dragon_song = (
            RESOURCE_PACK / "src" / "assets" / "mgx" / "sounds" / "music"
            / "amethyst_dragon_ascendant.ogg"
        )
        self.assertEqual(DRAGON_SONG_SHA256, hashlib.sha256(dragon_song.read_bytes()).hexdigest())
        with zipfile.ZipFile(RESOURCE_PACK / "MysteriousSMPX.zip") as java_pack:
            self.assertEqual(
                dragon_song.read_bytes(),
                java_pack.read("assets/mgx/sounds/music/amethyst_dragon_ascendant.ogg"),
            )
        with zipfile.ZipFile(RESOURCE_PACK / "bedrock" / "MysteriousSMPX-Bedrock.mcpack") as bedrock_pack:
            self.assertEqual(
                dragon_song.read_bytes(),
                bedrock_pack.read("sounds/music/amethyst_dragon_ascendant.ogg"),
            )
            definitions = json.loads(bedrock_pack.read("sounds/sound_definitions.json"))
            self.assertIn("mgx:amethyst_dragon_ascendant", definitions["sound_definitions"])


if __name__ == "__main__":
    unittest.main()
