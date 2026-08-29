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
    "amethyst_pickaxe": (16, 16),
    "amethyst_shovel": (16, 16),
    "amethyst_axe": (16, 16),
    "amethyst_shield": (64, 64),
    "amethyst_shield_icon": (590, 876),
    "amethyst_totem": (360, 360),
}
IMPORTED_MOD_HASHES = {
    "amethyst_pickaxe": "65630e43cdb2634ae0fa77d9ac1d9bc2a2b657a59fb4ea32932d057f5afdb2d9",
    "amethyst_shovel": "32b5016af36735c55d1614cf065d2b906eb58bb55fa4703fc7ddc9d799d78547",
    "amethyst_axe": "5c43672b2716bcb2fd4f5e0c06c66080eb1cf00b60d8c72cc4d8f581bbbec030",
    "amethyst_shield": "79c0eaaf8939888df6b0e28e1a080db648cd56a5a9622d74f51596a1e385ee01",
}
POTION_REFERENCE = RESOURCE_PACK / "icon-sources" / "potion_of_healing_reference.png"
EVENT_SONG_SHA256 = "768d3d503ac3e8ba39f6db1213a8296abcde9260944212fd5fe00d0f81ecc448"


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

    def test_custom_icons_are_valid_distinct_minecraft_sprites(self):
        icons = self.icon_paths()
        self.assertEqual(64, len(icons))

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
                        self.assertEqual((32, 32), image.size)
                        colours = image.getcolors(maxcolors=257)
                        self.assertIsNotNone(colours)
                        self.assertLessEqual(len(colours), 24)
                        pixels = image.load()
                        for y in range(0, 32, 2):
                            for x in range(0, 32, 2):
                                block = {
                                    pixels[x + dx, y + dy]
                                    for dx in range(2) for dy in range(2)
                                }
                                self.assertEqual(
                                    1, len(block),
                                    "every logical pixel must be a crisp 2x2 block",
                                )
                        self.assertGreaterEqual(bounds[2] - bounds[0], 14)
                        self.assertGreaterEqual(bounds[3] - bounds[1], 20)
                digests.add(hashlib.sha256(path.read_bytes()).digest())

        self.assertEqual(len(icons), len(digests), "custom icons must not be duplicate recolour assets")

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


if __name__ == "__main__":
    unittest.main()
