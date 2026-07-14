import io
import unittest

from cogs.automod import (
    IMAGE_FILTER_MAX_ENTRIES,
    IMAGE_HASH_DISTANCE_THRESHOLD,
    hash_distance,
    hash_image_bytes,
    match_banned_image,
    normalize_image_filter_settings,
)


def _entry(hash_hex: str, label: str = "test") -> dict:
    return {"hash": hash_hex, "label": label, "added_by": 1, "added_at": ""}


def _png_bytes(color, size=(64, 64), stripe=None):
    from PIL import Image, ImageDraw

    img = Image.new("RGB", size, color)
    draw = ImageDraw.Draw(img)
    # A gradient bar gives dHash real structure; solid colors hash to zero.
    # Scale-relative so the same picture renders identically at any size.
    for x in range(size[0]):
        draw.line([(x, 0), (x, size[1] // 4)], fill=(int(x / size[0] * 255), 0, 0))
    if stripe:
        draw.rectangle([0, size[1] - 8, size[0], size[1]], fill=stripe)
    buffer = io.BytesIO()
    img.save(buffer, format="PNG")
    return buffer.getvalue()


class ImageFilterSettingsTests(unittest.TestCase):
    def test_normalize_defaults(self):
        settings = normalize_image_filter_settings({})
        self.assertFalse(settings["enabled"])
        self.assertTrue(settings["delete_message"])
        self.assertTrue(settings["log_detections"])
        self.assertFalse(settings["punish"])
        self.assertEqual(settings["punishment_type"], "warn")
        self.assertEqual(settings["entries"], [])

    def test_normalize_drops_invalid_entries_and_types(self):
        settings = normalize_image_filter_settings({
            "punishment_type": "explode",
            "duration_minutes": -5,
            "entries": [
                {"hash": "not-hex"},
                {"hash": "00ff00ff00ff00ff", "label": "x" * 200},
                "garbage",
            ],
        })
        self.assertEqual(settings["punishment_type"], "warn")
        self.assertEqual(settings["duration_minutes"], 1)  # clamped to the floor
        self.assertEqual(len(settings["entries"]), 1)
        self.assertLessEqual(len(settings["entries"][0]["label"]), 80)

    def test_normalize_caps_entries(self):
        entries = [{"hash": f"{i:016x}"} for i in range(IMAGE_FILTER_MAX_ENTRIES + 10)]
        settings = normalize_image_filter_settings({"entries": entries})
        self.assertEqual(len(settings["entries"]), IMAGE_FILTER_MAX_ENTRIES)


class ImageHashTests(unittest.TestCase):
    def test_hash_distance(self):
        self.assertEqual(hash_distance("0" * 16, "0" * 16), 0)
        self.assertEqual(hash_distance("0" * 16, "f" + "0" * 15), 4)

    def test_match_exact_and_near(self):
        entries = [_entry("00ff00ff00ff00ff", "meme")]
        entry, distance = match_banned_image("00ff00ff00ff00ff", entries)
        self.assertIsNotNone(entry)
        self.assertEqual(distance, 0)
        # Flip a few bits, still within threshold
        entry, distance = match_banned_image("00ff00ff00ff00f0", entries)
        self.assertIsNotNone(entry)
        self.assertLessEqual(distance, IMAGE_HASH_DISTANCE_THRESHOLD)

    def test_no_match_beyond_threshold(self):
        entries = [_entry("0" * 16)]
        entry, _ = match_banned_image("f" * 16, entries)
        self.assertIsNone(entry)

    def test_hash_image_bytes_stable_and_discriminating(self):
        original = hash_image_bytes(_png_bytes((30, 60, 90)))
        self.assertIsNotNone(original)
        self.assertEqual(len(original), 16)

        # Same image re-encoded at a different size stays within threshold.
        resized = hash_image_bytes(_png_bytes((30, 60, 90), size=(128, 128)))
        self.assertLessEqual(hash_distance(original, resized), IMAGE_HASH_DISTANCE_THRESHOLD)

        # A structurally different image lands beyond it.
        different = hash_image_bytes(_png_bytes((200, 200, 200), stripe=(0, 0, 0)))
        self.assertGreater(hash_distance(original, different), 0)

    def test_hash_image_bytes_rejects_garbage(self):
        self.assertIsNone(hash_image_bytes(b"this is not an image"))


if __name__ == "__main__":
    unittest.main()
