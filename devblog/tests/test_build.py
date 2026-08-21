"""Tests for the dev blog generator.

Run from the repo root:
    python devblog/tests/test_build.py
or:
    python -m unittest discover -s devblog/tests -t devblog
"""

from __future__ import annotations

import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import build  # noqa: E402
import theme  # noqa: E402


VALID = """---
title: Fiesta Forever
tagline: The maze never ends.
date: 2026-08-21
hero: hero.png
tags: event, update
---

## Fiesta Forever

The **Fiesta** is back.
And it is **INFINITE**.

![](maze.png)
"""


class FrontMatterTests(unittest.TestCase):
    def parse(self, text: str, name: str = "2026-08-21-fiesta.md"):
        return build.parse_front_matter(text, Path(name))

    def test_reads_keys_and_body(self):
        meta, body = self.parse(VALID)
        self.assertEqual(meta["title"], "Fiesta Forever")
        self.assertEqual(meta["date"], "2026-08-21")
        self.assertTrue(body.startswith("## Fiesta Forever"))

    def test_tags_become_a_list(self):
        meta, _ = self.parse(VALID)
        self.assertEqual(meta["tags"], ["event", "update"])

    def test_missing_opening_fence_is_an_error(self):
        with self.assertRaises(build.PostError):
            self.parse("title: nope\n\nbody")

    def test_unclosed_front_matter_is_an_error(self):
        with self.assertRaises(build.PostError):
            self.parse("---\ntitle: nope\n\nbody")

    def test_malformed_line_is_an_error(self):
        with self.assertRaises(build.PostError):
            self.parse("---\ntitle Fiesta\n---\n\nbody")

    def test_quotes_and_comments_are_stripped(self):
        meta, _ = self.parse('---\n# a note\ntitle: "Quoted"\n---\n\nbody')
        self.assertEqual(meta["title"], "Quoted")


class PostTests(unittest.TestCase):
    def make(self, text: str = VALID, name: str = "2026-08-21-fiesta-forever.md"):
        path = Path(name)
        meta, body = build.parse_front_matter(text, path)
        return build.Post(path, meta, body)

    def test_slug_drops_the_date_prefix(self):
        self.assertEqual(self.make().slug, "fiesta-forever")

    def test_slug_falls_back_to_the_stem(self):
        post = self.make(VALID, "about.md")
        self.assertEqual(post.slug, "about")

    def test_explicit_slug_wins(self):
        text = VALID.replace("title: Fiesta Forever", "title: Fiesta Forever\nslug: custom")
        self.assertEqual(self.make(text).slug, "custom")

    def test_title_is_required(self):
        with self.assertRaises(build.PostError):
            self.make("---\ndate: 2026-08-21\n---\n\nbody")

    def test_date_can_come_from_the_filename(self):
        post = self.make("---\ntitle: T\n---\n\nbody", "2026-01-02-thing.md")
        self.assertEqual(post.date.strftime("%Y-%m-%d"), "2026-01-02")

    def test_undated_post_is_an_error(self):
        with self.assertRaises(build.PostError):
            self.make("---\ntitle: T\n---\n\nbody", "thing.md")

    def test_bad_date_is_an_error(self):
        with self.assertRaises(build.PostError):
            self.make("---\ntitle: T\ndate: last tuesday\n---\n\nbody", "thing.md")

    def test_display_date_has_no_leading_zero(self):
        post = self.make("---\ntitle: T\ndate: 2026-01-02\n---\n\nbody", "x.md")
        self.assertEqual(post.display_date(), "January 2, 2026")

    def test_url_points_at_the_slug_folder(self):
        self.assertEqual(self.make().url, "posts/fiesta-forever/")


class RenderTests(unittest.TestCase):
    def setUp(self):
        path = Path("2026-08-21-fiesta-forever.md")
        meta, body = build.parse_front_matter(VALID, path)
        self.post = build.Post(path, meta, body)

    def test_relative_images_get_the_media_prefix(self):
        html = build.render_body(self.post, "../../")
        self.assertIn('src="../../media/fiesta-forever/maze.png"', html)

    def test_absolute_urls_are_left_alone(self):
        html = build.rewrite_media_urls(
            '<img src="https://cdn.example.com/a.png">', self.post, "../../"
        )
        self.assertIn('src="https://cdn.example.com/a.png"', html)

    def test_root_relative_urls_are_left_alone(self):
        html = build.rewrite_media_urls('<a href="/about/">x</a>', self.post, "../../")
        self.assertIn('href="/about/"', html)

    def test_anchors_are_left_alone(self):
        html = build.rewrite_media_urls('<a href="#top">x</a>', self.post, "")
        self.assertIn('href="#top"', html)

    def test_standalone_image_becomes_a_figure(self):
        html = build.render_body(self.post, "")
        self.assertIn('<figure class="shot">', html)

    def test_single_newlines_become_line_breaks(self):
        html = build.render_body(self.post, "")
        self.assertIn("<br", html)

    def test_excerpt_prefers_the_tagline(self):
        self.assertEqual(build.excerpt(self.post), "The maze never ends.")

    def test_excerpt_falls_back_to_the_body(self):
        self.post.tagline = ""
        text = build.excerpt(self.post)
        self.assertIn("Fiesta", text)
        self.assertNotIn("#", text)
        self.assertNotIn("![", text)

    def test_excerpt_is_truncated(self):
        self.post.tagline = "word " * 200
        self.assertLessEqual(len(build.excerpt(self.post)), 191)


class BuildTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)

        self._saved = (build.POSTS_DIR, build.MEDIA_DIR, build.STATIC_DIR, build.DIST_DIR)
        build.POSTS_DIR = self.tmp / "posts"
        build.MEDIA_DIR = self.tmp / "media"
        build.STATIC_DIR = self.tmp / "static"
        build.DIST_DIR = self.tmp / "dist"
        for folder in (build.POSTS_DIR, build.MEDIA_DIR, build.STATIC_DIR):
            folder.mkdir(parents=True)
        self.addCleanup(self._restore)

    def _restore(self):
        build.POSTS_DIR, build.MEDIA_DIR, build.STATIC_DIR, build.DIST_DIR = self._saved

    def write(self, name: str, text: str):
        (build.POSTS_DIR / name).write_text(text, encoding="utf-8")

    def test_builds_every_expected_file(self):
        self.write("2026-08-21-fiesta-forever.md", VALID)
        posts = build.build("https://example.com/blog")
        self.assertEqual(len(posts), 1)

        dist = build.DIST_DIR
        for expected in (
            "index.html",
            "404.html",
            "feed.xml",
            ".nojekyll",
            "assets/style.css",
            "posts/fiesta-forever/index.html",
        ):
            self.assertTrue((dist / expected).exists(), "missing %s" % expected)

    def test_posts_are_newest_first(self):
        self.write("2026-01-01-old.md", "---\ntitle: Old\n---\n\nbody")
        self.write("2026-09-09-new.md", "---\ntitle: New\n---\n\nbody")
        posts = build.build("https://example.com")
        self.assertEqual([p.title for p in posts], ["New", "Old"])

    def test_drafts_are_skipped_by_default(self):
        self.write("2026-08-21-wip.md", "---\ntitle: WIP\ndraft: true\n---\n\nbody")
        self.assertEqual(build.build("https://example.com"), [])
        self.assertEqual(len(build.build("https://example.com", include_drafts=True)), 1)

    def test_duplicate_slugs_are_rejected(self):
        self.write("2026-01-01-same.md", "---\ntitle: A\nslug: same\n---\n\nbody")
        self.write("2026-02-02-other.md", "---\ntitle: B\nslug: same\n---\n\nbody")
        with self.assertRaises(build.PostError):
            build.build("https://example.com")

    def test_media_is_copied_next_to_the_post(self):
        self.write("2026-08-21-fiesta-forever.md", VALID)
        shot_dir = build.MEDIA_DIR / "fiesta-forever"
        shot_dir.mkdir(parents=True)
        (shot_dir / "maze.png").write_bytes(b"stub")
        build.build("https://example.com")
        self.assertTrue((build.DIST_DIR / "media/fiesta-forever/maze.png").exists())

    def test_empty_index_still_renders(self):
        build.build("https://example.com")
        page = (build.DIST_DIR / "index.html").read_text(encoding="utf-8")
        self.assertIn("No updates posted yet", page)

    def test_feed_lists_the_post(self):
        self.write("2026-08-21-fiesta-forever.md", VALID)
        build.build("https://example.com/blog")
        feed = (build.DIST_DIR / "feed.xml").read_text(encoding="utf-8")
        self.assertIn("<title>Fiesta Forever</title>", feed)
        self.assertIn("https://example.com/blog/posts/fiesta-forever/", feed)

    def test_index_links_and_titles_the_post(self):
        self.write("2026-08-21-fiesta-forever.md", VALID)
        build.build("https://example.com")
        page = (build.DIST_DIR / "index.html").read_text(encoding="utf-8")
        self.assertIn('href="posts/fiesta-forever/"', page)
        self.assertIn("Fiesta Forever", page)
        self.assertIn("August 21, 2026", page)


class ThemeTests(unittest.TestCase):
    def test_optional_links_are_omitted_when_unset(self):
        saved = theme.DISCORD_URL, theme.SERVER_ADDRESS
        theme.DISCORD_URL, theme.SERVER_ADDRESS = "", ""
        try:
            page = theme.render_404(prefix="/")
            self.assertNotIn("discord.gg", page)
            self.assertNotIn('class="addr"', page)
        finally:
            theme.DISCORD_URL, theme.SERVER_ADDRESS = saved

    def test_optional_links_render_when_set(self):
        saved = theme.DISCORD_URL, theme.SERVER_ADDRESS
        theme.DISCORD_URL = "https://discord.gg/example"
        theme.SERVER_ADDRESS = "play.example.com"
        try:
            page = theme.render_404(prefix="/")
            self.assertIn("https://discord.gg/example", page)
            self.assertIn("play.example.com", page)
        finally:
            theme.DISCORD_URL, theme.SERVER_ADDRESS = saved

    def test_titles_are_escaped(self):
        page = theme._page("<script>x</script>", "d", "", "<main></main>")
        self.assertNotIn("<script>x</script>", page)

    def test_stylesheet_defines_light_and_dark(self):
        self.assertIn("prefers-color-scheme: dark", theme.STYLESHEET)
        self.assertIn('[data-theme="dark"]', theme.STYLESHEET)


if __name__ == "__main__":
    unittest.main(verbosity=2)
