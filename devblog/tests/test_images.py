"""Tests for the responsive image pass (images.py)."""

from __future__ import annotations

import random
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import images  # noqa: E402

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    Image = None


def noisy_png(path: Path, width: int, height: int) -> None:
    """A photo-like PNG: noise does not compress, so it is well over the small-file cut."""
    rng = random.Random(7)
    image = Image.frombytes("RGB", (width, height), rng.randbytes(width * height * 3))
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, "PNG")


@unittest.skipIf(Image is None, "Pillow is not installed")
class ResponsiveImageTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.dist = self.tmp / "dist"
        noisy_png(self.dist / "media" / "update-9" / "arena.png", 2000, 1000)
        noisy_png(self.dist / "media" / "update-9" / "cover.png", 1400, 700)
        noisy_png(self.dist / "media" / "update-9" / "narrow.png", 600, 400)
        (self.dist / "media" / "update-9" / "stub.png").write_bytes(b"not an image")
        tiny = Image.new("RGBA", (16, 16), (200, 100, 50, 255))
        tiny.save(self.dist / "media" / "update-9" / "pixel.png")
        (self.dist / "update-9").mkdir(parents=True)
        self.page = self.dist / "update-9" / "index.html"
        self.page.write_text(
            '<meta property="og:image" content="https://site.test/media/update-9/cover.png">'
            '<img src="../media/update-9/arena.png" alt="Arena">'
            '<img src="../media/update-9/narrow.png" alt="">'
            '<img src="../media/update-9/pixel.png" alt="">'
            '<img src="../media/update-9/stub.png" alt="">'
            '<img src="https://cdn.example.com/a.png">',
            encoding="utf-8",
        )

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def run_pass(self):
        return images.optimise(self.dist, site_url="https://site.test",
                               cache_dir=self.tmp / "cache", workers=1)

    def test_a_large_screenshot_gets_webp_widths_and_a_responsive_tag(self):
        report = self.run_pass()
        html = self.page.read_text(encoding="utf-8")
        folder = self.dist / "media" / "update-9"
        for width in (480, 960, 1600):
            self.assertTrue((folder / ("arena.w%d.webp" % width)).is_file(), width)
        self.assertIn('src="../media/update-9/arena.w1600.webp"', html)
        self.assertIn("arena.w480.webp 480w, ../media/update-9/arena.w960.webp 960w", html)
        self.assertIn('width="2000" height="1000"', html)
        self.assertIn('fetchpriority="high"', html.split("narrow")[0], "the first picture loads early")
        self.assertTrue((folder / "arena.png").is_file(), "originals stay at their old URLs")
        self.assertGreater(report.images, 0)
        self.assertLess(report.bytes_after, report.bytes_before)

    def test_a_picture_is_never_told_to_draw_wider_than_it_is(self):
        self.run_pass()
        html = self.page.read_text(encoding="utf-8")
        narrow = html.split('narrow.w600.webp"', 1)[1].split(">", 1)[0]
        self.assertIn('sizes="(max-width: 600px) 100vw, 600px"', narrow)
        self.assertIn('loading="lazy"', narrow)

    def test_small_art_stubs_and_remote_images_are_left_alone(self):
        self.run_pass()
        html = self.page.read_text(encoding="utf-8")
        self.assertIn('src="../media/update-9/pixel.png"', html)
        self.assertNotIn("pixel.w", html)
        self.assertIn('src="../media/update-9/stub.png"', html)
        self.assertIn('<img src="https://cdn.example.com/a.png">', html)

    def test_share_previews_point_at_a_small_jpeg(self):
        self.run_pass()
        html = self.page.read_text(encoding="utf-8")
        self.assertIn('content="https://site.test/media/update-9/cover.share.jpg"', html)
        with Image.open(self.dist / "media" / "update-9" / "cover.share.jpg") as share:
            self.assertEqual(1200, share.width)

    def test_a_second_build_reuses_the_encoded_copies(self):
        self.run_pass()
        cached = sorted(path.name for path in (self.tmp / "cache").iterdir())
        self.assertTrue(cached)
        self.page.write_text('<img src="../media/update-9/arena.png">', encoding="utf-8")
        for path in (self.dist / "media" / "update-9").glob("*.webp"):
            path.unlink()
        self.run_pass()
        self.assertTrue((self.dist / "media" / "update-9" / "arena.w960.webp").is_file())
        self.assertEqual(cached, sorted(path.name for path in (self.tmp / "cache").iterdir()))


if __name__ == "__main__":
    unittest.main()
