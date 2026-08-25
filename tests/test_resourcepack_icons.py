import hashlib
import json
import unittest
import zipfile
from pathlib import Path

from PIL import Image


REPO = Path(__file__).resolve().parents[1]
RESOURCE_PACK = REPO / "assets" / "resourcepack"
ITEM_TEXTURES = RESOURCE_PACK / "src" / "assets" / "mgx" / "textures" / "item"


class ResourcePackIconTests(unittest.TestCase):
    def icon_paths(self):
        return sorted(ITEM_TEXTURES.glob("*.png")) + sorted((ITEM_TEXTURES / "cosmetic").glob("*.png"))

    def test_custom_icons_are_valid_distinct_minecraft_sprites(self):
        icons = self.icon_paths()
        self.assertEqual(43, len(icons))

        digests = set()
        for path in icons:
            with self.subTest(icon=path.stem):
                with Image.open(path) as image:
                    self.assertEqual((32, 32), image.size)
                    self.assertEqual("RGBA", image.mode)
                    colours = image.getcolors(maxcolors=257)
                    self.assertIsNotNone(colours)
                    self.assertLessEqual(len(colours), 24)
                    alpha = image.getchannel("A")
                    self.assertEqual({0, 255}, set(alpha.getdata()))
                    pixels = image.load()
                    for y in range(0, 32, 2):
                        for x in range(0, 32, 2):
                            block = {pixels[x + dx, y + dy] for dx in range(2) for dy in range(2)}
                            self.assertEqual(1, len(block), "every logical pixel must be a crisp 2x2 block")
                    bounds = alpha.getbbox()
                    self.assertIsNotNone(bounds)
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
                    namespace, name = item["model"].split(":", 1)
                    item_model = json.loads((
                        RESOURCE_PACK / "src" / "assets" / namespace / "items" / f"{name}.json"
                    ).read_text(encoding="utf-8"))
                    model_namespace, model_name = item_model["model"]["model"].split(":", 1)
                    model = json.loads((
                        RESOURCE_PACK / "src" / "assets" / model_namespace / "models" / f"{model_name}.json"
                    ).read_text(encoding="utf-8"))
                    texture_namespace, texture_name = model["textures"]["layer0"].split(":", 1)
                    java_texture = (
                        RESOURCE_PACK / "src" / "assets" / texture_namespace / "textures" / f"{texture_name}.png"
                    )
                    bedrock_name = item["bedrock_identifier"].split(":", 1)[1]
                    self.assertEqual(
                        java_texture.read_bytes(),
                        archive.read(f"textures/items/{bedrock_name}.png"),
                    )


if __name__ == "__main__":
    unittest.main()
