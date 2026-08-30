"""Tests for the dev blog generator.

Run from the repo root:
    python devblog/tests/test_build.py
or:
    python -m unittest discover -s devblog/tests -t devblog
"""

from __future__ import annotations

import json
import re
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
        self.assertEqual(self.make().url, "fiesta-forever/")


class RenderTests(unittest.TestCase):
    def setUp(self):
        path = Path("2026-08-21-fiesta-forever.md")
        meta, body = build.parse_front_matter(VALID, path)
        self.post = build.Post(path, meta, body)

    def test_relative_images_get_the_media_prefix(self):
        html = build.render_body(self.post, "../")
        self.assertIn('src="../media/fiesta-forever/maze.png"', html)

    def test_absolute_urls_are_left_alone(self):
        html = build.rewrite_media_urls(
            '<img src="https://cdn.example.com/a.png">', self.post, "../"
        )
        self.assertIn('src="https://cdn.example.com/a.png"', html)

    def test_root_relative_urls_are_left_alone(self):
        html = build.rewrite_media_urls('<a href="/about/">x</a>', self.post, "../")
        self.assertIn('href="/about/"', html)

    def test_anchors_are_left_alone(self):
        html = build.rewrite_media_urls('<a href="#top">x</a>', self.post, "")
        self.assertIn('href="#top"', html)

    def test_standalone_image_becomes_a_figure(self):
        html = build.render_body(self.post, "")
        self.assertIn('<figure class="shot">', html)

    def test_hero_figure_is_marked_as_the_hero(self):
        html = theme.render_post(self.post, "<p>x</p>", "hero.png", "", "https://e.com")
        self.assertIn('<figure class="shot hero">', html)

    def test_cover_is_shared_without_becoming_an_article_hero(self):
        meta, body = build.parse_front_matter(
            VALID.replace("hero: hero.png", "cover: cover.png"),
            Path("2026-08-21-fiesta-forever.md"),
        )
        post = build.Post(Path("2026-08-21-fiesta-forever.md"), meta, body)
        html = theme.render_post(post, "<p>x</p>", None, "../", "https://e.com")
        self.assertNotIn('<figure class="shot hero">', html)
        self.assertIn(
            '<meta property="og:image" content="https://e.com/media/fiesta-forever/cover.png">',
            html,
        )

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
            ".nojekyll",
            "assets/style.css",
            "fiesta-forever/index.html",
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

    def test_index_links_and_titles_the_post(self):
        self.write("2026-08-21-fiesta-forever.md", VALID)
        build.build("https://example.com")
        page = (build.DIST_DIR / "index.html").read_text(encoding="utf-8")
        self.assertIn('href="fiesta-forever/"', page)
        self.assertIn("Fiesta Forever", page)
        self.assertIn("August 21, 2026", page)


class ThemeTests(unittest.TestCase):
    def test_optional_links_are_omitted_when_unset(self):
        saved = theme.DISCORD_URL, theme.SERVER_ADDRESS
        theme.DISCORD_URL, theme.SERVER_ADDRESS = "", ""
        try:
            page = theme.render_404(prefix="/")
            self.assertNotIn("discord.gg", page)
            self.assertNotIn('class="footer-addr"', page)
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
            self.assertIn('class="footer-addr"', page)
        finally:
            theme.DISCORD_URL, theme.SERVER_ADDRESS = saved

    def test_urabe_breaks_out_of_the_community_card(self):
        band = theme._community("")
        self.assertIn('class="community-mascot"', band)
        self.assertIn('src="assets/urabe.png"', band)
        # Decorative: the heading beside her already says what the band is for.
        self.assertIn('alt=""', band)
        self.assertIn('aria-hidden="true"', band)
        # Popping out only works while the card neither clips her nor loses its
        # positioning context.
        self.assertIn(".community-card {\n  position: relative;", theme.STYLESHEET)
        self.assertNotIn("overflow: hidden", theme.STYLESHEET.split("community band")[1]
                         .split("/* =====")[0])

    def test_the_mascot_is_cut_off_by_the_frame_line_itself(self):
        """Her crop has to land on the card's bottom edge, not near it.

        Hanging her below the line leaves the crop floating in open space with
        nothing to land against, and fading it out does not make that deliberate.
        Sitting her above it leaves a gap. The line does the cutting.
        """
        css = theme.STYLESHEET
        mascot = css.split(".community-mascot")[-1]
        self.assertIn("bottom: 0", mascot)
        self.assertNotIn("mask-image", css)

    def test_the_mascot_asset_is_trimmed_to_the_figure(self):
        """Transparent padding in the file reads as bad spacing on the page.

        She is positioned by her bottom edge, so a band of invisible pixels under
        her feet lifts her off the card and there is no CSS fix for it.
        """
        from struct import unpack

        png = (Path(__file__).resolve().parents[1] / "static" / "urabe.png").read_bytes()
        width, height = unpack(">II", png[16:24])
        markup = theme._community("")
        self.assertIn('width="%d" height="%d"' % (width, height), markup)

    def test_the_mascot_is_addressed_relative_to_the_page_that_shows_her(self):
        self.assertIn('src="../assets/urabe.png"', theme._community("../"))

    def test_titles_are_escaped(self):
        page = theme._page("<script>x</script>", "d", "", "<main></main>")
        self.assertNotIn("<script>x</script>", page)

    def test_stylesheet_defines_light_and_dark(self):
        self.assertIn("prefers-color-scheme: dark", theme.STYLESHEET)
        self.assertIn('[data-theme="dark"]', theme.STYLESHEET)

    def test_shot_images_are_height_capped(self):
        # The bug this guards: a square 512x512 logo used as art was upscaled to
        # the full 730px column and swallowed the page.
        css = theme.STYLESHEET
        block = css.split(".post-body figure.shot img {")[1].split("}")[0]
        decls = [d.strip() for d in block.split(";") if d.strip()]
        self.assertIn("width: auto", decls)
        self.assertIn("max-width: 100%", decls)
        self.assertNotIn("width: 100%", decls)
        self.assertTrue(any(d.startswith("max-height:") for d in decls), decls)

    def test_hero_gets_its_own_height_cap(self):
        self.assertIn(".post-body figure.shot.hero img", theme.STYLESHEET)

    def test_hero_wordmark_keeps_its_aspect(self):
        # It is a 3:1 wordmark; forcing it into a square squashed it once before.
        block = theme.STYLESHEET.split(".hero-copy .wordmark {")[1].split("}")[0]
        self.assertIn("width: auto", block)
        self.assertIn("height: auto", block)
        self.assertIn("max-height", block)

    def test_homepage_feature_art_is_not_artificially_shrunk(self):
        block = theme.STYLESHEET.split(".hero-feature .frame img {")[1].split("}")[0]
        self.assertIn("object-fit: contain", block)
        self.assertNotIn("padding:", block)

    def test_post_related_cards_do_not_use_three_narrow_columns(self):
        self.assertIn(".post-main .more .card-grid { grid-template-columns: 1fr;", theme.STYLESHEET)
        self.assertIn("repeat(2, minmax(0, 1fr))", theme.STYLESHEET)

    def test_brand_ramp_is_defined_and_used(self):
        self.assertIn("--brand-ramp:", theme.STYLESHEET)
        self.assertIn(".brandbar", theme.STYLESHEET)
        self.assertIn('<div class="brandbar"></div>', theme.render_404(prefix="/"))

    def test_home_links_never_spell_out_index_html(self):
        home = theme.render_404(prefix="")
        nested = theme.render_404(prefix="../")
        self.assertIn('href="./"', home)
        self.assertIn('href="../"', nested)
        self.assertNotIn("index.html", home)
        self.assertNotIn("index.html", nested)

    def test_footer_uses_the_wordmark(self):
        self.assertIn('class="mark" src="/assets/logo.png"', theme.render_404(prefix="/"))


class PostFieldTests(unittest.TestCase):
    def make(self, text: str, name: str = "2026-08-21-x.md"):
        path = Path(name)
        meta, body = build.parse_front_matter(text, path)
        return build.Post(path, meta, body)

    def test_category_defaults_to_the_config_value(self):
        post = self.make("---\ntitle: T\n---\n\nbody")
        self.assertEqual(post.category, build.config.DEFAULT_CATEGORY)

    def test_category_can_be_overridden(self):
        post = self.make("---\ntitle: T\ncategory: Event\n---\n\nbody")
        self.assertEqual(post.category, "Event")

    def test_art_and_signoff_default_to_empty(self):
        post = self.make("---\ntitle: T\n---\n\nbody")
        self.assertEqual(post.cover, "")
        self.assertEqual(post.hero, "")
        self.assertEqual(post.icon, "")
        self.assertEqual(post.signoff, "")

    def test_media_url_uses_the_post_slug(self):
        post = self.make("---\ntitle: T\nicon: i.png\n---\n\nbody", "2026-01-01-fiesta.md")
        self.assertEqual(build.media_url(post, post.icon, "../"),
                         "../media/fiesta/i.png")

    def test_media_url_passes_absolute_through(self):
        post = self.make("---\ntitle: T\n---\n\nbody")
        self.assertEqual(build.media_url(post, "https://x/y.png", "../"),
                         "https://x/y.png")

    def test_media_url_is_none_when_unset(self):
        post = self.make("---\ntitle: T\n---\n\nbody")
        self.assertIsNone(build.media_url(post, "", "../"))


class CardTests(unittest.TestCase):
    def make_card(self, prefix: str):
        path = Path("2026-08-21-fiesta-forever.md")
        meta, body = build.parse_front_matter(VALID, path)
        return build.card_for(build.Post(path, meta, body), prefix)

    def test_index_card_url_is_relative_to_root(self):
        self.assertEqual(self.make_card("")["url"], "fiesta-forever/")

    def test_related_card_url_climbs_out_of_the_post_folder(self):
        self.assertEqual(self.make_card("../")["url"], "../fiesta-forever/")

    def test_card_falls_back_to_the_hero_for_its_icon(self):
        card = self.make_card("")
        self.assertIsNone(card["icon"])
        html = theme.render_card(card, "")
        # No explicit icon, so both layers use the hero art.
        self.assertEqual(html.count("media/fiesta-forever/hero.png"), 2)

    def test_card_uses_an_explicit_icon_when_given(self):
        path = Path("2026-08-21-fiesta-forever.md")
        meta, body = build.parse_front_matter(
            VALID.replace("hero: hero.png", "hero: hero.png\nicon: icon.png"), path
        )
        html = theme.render_card(build.card_for(build.Post(path, meta, body), ""), "")
        self.assertIn("media/fiesta-forever/icon.png", html)
        self.assertIn("media/fiesta-forever/hero.png", html)

    def test_card_uses_cover_as_the_backdrop_without_showing_a_gameplay_hero(self):
        path = Path("2026-08-21-fiesta-forever.md")
        meta, body = build.parse_front_matter(
            VALID.replace(
                "hero: hero.png",
                "cover: cover.png\nicon: icon.png",
            ),
            path,
        )
        card = build.card_for(build.Post(path, meta, body), "")
        html = theme.render_card(card, "")
        self.assertIn('class="blur" src="media/fiesta-forever/cover.png"', html)
        self.assertIn('class="icon" src="media/fiesta-forever/icon.png"', html)
        self.assertNotIn("hero.png", html)

    def test_card_renders_the_blurred_backdrop_and_square(self):
        html = theme.render_card(self.make_card(""), "")
        self.assertIn('class="blur"', html)
        self.assertIn('class="icon"', html)


class RelatedTests(unittest.TestCase):
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

    def write(self, name: str, title: str):
        (build.POSTS_DIR / name).write_text(
            "---\ntitle: %s\n---\n\nbody" % title, encoding="utf-8"
        )

    def test_a_post_never_lists_itself_as_related(self):
        for n in range(1, 4):
            self.write("2026-0%d-01-p%d.md" % (n, n), "Post %d" % n)
        build.build("https://example.com")
        page = (build.DIST_DIR / "p2/index.html").read_text(encoding="utf-8")
        self.assertIn("More Updates", page)
        self.assertNotIn('href="../p2/"', page)
        self.assertIn('href="../p3/"', page)

    def test_a_lone_post_has_no_more_updates_strip(self):
        self.write("2026-01-01-only.md", "Only")
        build.build("https://example.com")
        page = (build.DIST_DIR / "only/index.html").read_text(encoding="utf-8")
        self.assertNotIn("More Updates", page)

    def test_related_is_capped_at_three(self):
        for n in range(1, 7):
            self.write("2026-0%d-01-p%d.md" % (n, n), "Post %d" % n)
        build.build("https://example.com")
        page = (build.DIST_DIR / "p1/index.html").read_text(encoding="utf-8")
        self.assertEqual(len(re.findall(r'class="card"[^>]*href="\.\./', page)), 3)



class SocialLinkTests(unittest.TestCase):
    """The one table that feeds both the sidebar grid and the footer column."""

    FIELDS = ("DISCORD_URL", "APPLY_URL", "REDDIT_URL", "TWITTER_URL", "YOUTUBE_URL")

    def setUp(self):
        self._saved = {f: getattr(theme, f) for f in self.FIELDS}
        for f in self.FIELDS:
            setattr(theme, f, "")
        self.addCleanup(self._restore)

    def _restore(self):
        for f, v in self._saved.items():
            setattr(theme, f, v)

    def test_unset_links_are_dropped(self):
        self.assertEqual(theme._social_links(), [])

    def test_only_set_links_come_back(self):
        theme.REDDIT_URL = "https://reddit.com/r/example"
        self.assertEqual(
            theme._social_links(), [("https://reddit.com/r/example", "Reddit", "reddit")]
        )

    def test_order_is_stable(self):
        theme.DISCORD_URL = "d"
        theme.REDDIT_URL = "r"
        theme.YOUTUBE_URL = "y"
        self.assertEqual([label for _, label, _ in theme._social_links()],
                         ["Discord", "Reddit", "YouTube"])

    def test_reddit_reaches_the_community_band_and_the_footer(self):
        theme.REDDIT_URL = "https://reddit.com/r/example"
        page = _home()
        self.assertIn('class="social-btn reddit"', page)
        self.assertEqual(page.count("https://reddit.com/r/example"), 2)

    def test_the_band_is_dropped_when_nothing_is_configured(self):
        saved = theme.SERVER_ADDRESS
        theme.SERVER_ADDRESS = ""
        try:
            self.assertNotIn("Join our community", _home())
        finally:
            theme.SERVER_ADDRESS = saved

    def test_a_server_address_alone_still_renders_the_band(self):
        saved = theme.SERVER_ADDRESS
        theme.SERVER_ADDRESS = "play.example.com"
        try:
            page = _home()
            self.assertIn("Join our community", page)
            self.assertIn('data-copy="play.example.com"', page)
        finally:
            theme.SERVER_ADDRESS = saved

    def test_posts_no_longer_carry_a_rail(self):
        theme.DISCORD_URL = "d"
        page = theme.render_post(_stub_post(), "<p>x</p>", None, "", "https://e.com")
        self.assertNotIn("sidebar", page)
        self.assertNotIn("side-btn", page)


def _stub_post():
    path = Path("2026-08-21-fiesta-forever.md")
    meta, body = build.parse_front_matter(VALID, path)
    return build.Post(path, meta, body)


def _home():
    return theme.render_index(
        featured=build.card_for(_stub_post(), ""), cards=[], prefix="", site_url="https://e.com"
    )



class SlugSafetyTests(unittest.TestCase):
    """Posts sit at the site root now, so slugs share a namespace with assets."""

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

    def write(self, name: str, body: str):
        (build.POSTS_DIR / name).write_text(body, encoding="utf-8")

    def test_a_slug_may_not_shadow_the_assets_folder(self):
        self.write("2026-01-01-x.md", "---\ntitle: T\nslug: assets\n---\n\nbody")
        with self.assertRaises(build.PostError):
            build.build("https://example.com")

    def test_a_slug_may_not_shadow_the_media_folder(self):
        self.write("2026-01-01-x.md", "---\ntitle: T\nslug: media\n---\n\nbody")
        with self.assertRaises(build.PostError):
            build.build("https://example.com")

    def test_a_slug_must_be_url_safe(self):
        self.write("2026-01-01-x.md", "---\ntitle: T\nslug: Update One\n---\n\nbody")
        with self.assertRaises(build.PostError):
            build.build("https://example.com")

    def test_a_codename_slug_lands_at_the_site_root(self):
        self.write("2026-01-01-x.md", "---\ntitle: Fiesta\nslug: update-1\n---\n\nbody")
        posts = build.build("https://example.com")
        self.assertEqual(posts[0].url, "update-1/")
        self.assertTrue((build.DIST_DIR / "update-1" / "index.html").exists())


class HomePageTests(unittest.TestCase):
    def test_the_newest_post_leads_the_hero(self):
        page = _home()
        self.assertIn('class="hero-band"', page)
        self.assertIn("Read Latest Update", page)
        self.assertIn('href="fiesta-forever/"', page)

    def test_the_featured_strip_carries_a_date(self):
        self.assertIn("August 21, 2026", _home())

    def test_the_hero_preview_puts_the_post_date_below_its_title(self):
        frame = _home().split('<div class="hero-feature">', 1)[1].split("</a>", 1)[0]
        self.assertIn(
            '<p>Fiesta Forever</p><time datetime="2026-08-21">August 21, 2026</time>',
            frame,
        )

    def test_a_designed_cover_wins_over_gameplay_art_on_the_homepage(self):
        card = build.card_for(_stub_post(), "")
        card["cover"] = "media/fiesta-forever/cover.png"
        card["hero"] = "media/fiesta-forever/gameplay.jpg"
        page = theme.render_index(card, [], "", "https://e.com")
        frame = page.split('<div class="hero-feature">', 1)[1].split("</a>", 1)[0]
        self.assertIn("cover.png", frame)
        self.assertNotIn("gameplay.jpg", frame)

    def test_the_archive_is_hidden_when_there_is_only_one_post(self):
        self.assertNotIn("All Updates", _home())

    def test_the_archive_appears_once_there_is_a_second_post(self):
        card = build.card_for(_stub_post(), "")
        page = theme.render_index(
            featured=card, cards=[card], prefix="", site_url="https://e.com"
        )
        self.assertIn("All Updates", page)

    def test_an_empty_site_says_so(self):
        page = theme.render_index(featured=None, cards=[], prefix="", site_url="https://e.com")
        self.assertIn("No updates posted yet", page)
        self.assertNotIn("Read Latest Update", page)



class PageTests(unittest.TestCase):
    """Standing pages: guide, rules, how to apply."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self._saved = (build.POSTS_DIR, build.PAGES_DIR, build.MEDIA_DIR,
                       build.STATIC_DIR, build.DIST_DIR)
        build.POSTS_DIR = self.tmp / "posts"
        build.PAGES_DIR = self.tmp / "pages"
        build.MEDIA_DIR = self.tmp / "media"
        build.STATIC_DIR = self.tmp / "static"
        build.DIST_DIR = self.tmp / "dist"
        for folder in (build.POSTS_DIR, build.PAGES_DIR, build.MEDIA_DIR, build.STATIC_DIR):
            folder.mkdir(parents=True)
        self.addCleanup(self._restore)

    def _restore(self):
        (build.POSTS_DIR, build.PAGES_DIR, build.MEDIA_DIR,
         build.STATIC_DIR, build.DIST_DIR) = self._saved

    def page(self, name, body="---\ntitle: T\n---\n\nbody"):
        (build.PAGES_DIR / name).write_text(body, encoding="utf-8")

    def post(self, name, body="---\ntitle: P\n---\n\nbody"):
        (build.POSTS_DIR / name).write_text(body, encoding="utf-8")

    def test_a_page_lands_at_its_own_root_url(self):
        self.page("guide.md", "---\ntitle: Server Guide\nnav: Guide\n---\n\nHello")
        build.build("https://example.com")
        out = build.DIST_DIR / "guide" / "index.html"
        self.assertTrue(out.exists())
        self.assertIn("Server Guide", out.read_text(encoding="utf-8"))

    def test_pages_sort_by_order_then_slug(self):
        self.page("apply.md", "---\ntitle: A\nnav: Apply\norder: 3\n---\n\nx")
        self.page("guide.md", "---\ntitle: G\nnav: Guide\norder: 1\n---\n\nx")
        self.page("rules.md", "---\ntitle: R\nnav: Rules\norder: 2\n---\n\nx")
        self.assertEqual([p.nav for p in build.load_pages()], ["Guide", "Rules", "Apply"])

    def test_a_bad_order_is_an_error(self):
        self.page("guide.md", "---\ntitle: G\norder: soon\n---\n\nx")
        with self.assertRaises(build.PostError):
            build.load_pages()

    def test_dashboard_layout_uses_the_wide_site_shell_and_live_asset(self):
        self.page(
            "leaderboards.md",
            "---\ntitle: Leaderboards\nlayout: dashboard\n---\n\n<div id=\"leaderboard-root\"></div>",
        )
        (build.STATIC_DIR / "server-dashboard.js").write_text("// live")
        build.build("https://example.com")
        page = (build.DIST_DIR / "leaderboards" / "index.html").read_text(encoding="utf-8")
        self.assertIn('class="doc live-doc"', page)
        self.assertIn('src="../assets/server-dashboard.js"', page)
        self.assertTrue((build.DIST_DIR / "assets" / "server-dashboard.js").is_file())

    def test_unknown_page_layout_is_an_error(self):
        self.page("guide.md", "---\ntitle: G\nlayout: mystery\n---\n\nx")
        with self.assertRaises(build.PostError):
            build.load_pages()

    def test_nav_appears_on_every_rendered_page(self):
        self.page("guide.md", "---\ntitle: G\nnav: Guide\n---\n\nx")
        self.post("2026-01-01-p1.md")
        build.build("https://example.com")
        for rel in ("index.html", "404.html", "p1/index.html", "guide/index.html"):
            page = (build.DIST_DIR / rel).read_text(encoding="utf-8")
            self.assertIn(">Guide</a>", page, rel)

    def test_the_current_page_is_marked(self):
        self.page("guide.md", "---\ntitle: G\nnav: Guide\n---\n\nx")
        build.build("https://example.com")
        page = (build.DIST_DIR / "guide" / "index.html").read_text(encoding="utf-8")
        self.assertIn('href="../guide/" aria-current="page"', page)

    def test_a_page_may_not_collide_with_a_post(self):
        self.page("guide.md", "---\ntitle: G\n---\n\nx")
        self.post("2026-01-01-guide.md")
        with self.assertRaises(build.PostError):
            build.build("https://example.com")

    def test_a_page_slug_may_not_be_reserved(self):
        self.page("assets.md", "---\ntitle: A\n---\n\nx")
        with self.assertRaises(build.PostError):
            build.build("https://example.com")

    def test_no_feed_is_written(self):
        self.post("2026-01-01-p1.md")
        build.build("https://example.com")
        self.assertFalse((build.DIST_DIR / "feed.xml").exists())

    def test_nothing_links_to_a_feed(self):
        self.post("2026-01-01-p1.md")
        build.build("https://example.com")
        for rel in ("index.html", "404.html", "p1/index.html"):
            page = (build.DIST_DIR / rel).read_text(encoding="utf-8")
            self.assertNotIn("feed.xml", page, rel)
            self.assertNotIn("rss", page.lower(), rel)


class MobileLayoutTests(unittest.TestCase):
    """Guards for the overflow class of bug, which has no browser here to catch it."""

    def test_no_image_rule_sets_a_bare_rem_max_width(self):
        # `max-width: 26rem` on an img beats the global `img { max-width: 100% }`,
        # so on a 360px phone the wordmark pushed the page wider than the viewport.
        offenders = []
        for rule in re.findall(r"([^{}]+)\{([^{}]*)\}", theme.STYLESHEET):
            selector, block = rule[0].strip(), rule[1]
            if "img" not in selector and "wordmark" not in selector and "mark" not in selector:
                continue
            for decl in block.split(";"):
                decl = decl.strip()
                if decl.startswith("max-width:") and "rem" in decl and "min(" not in decl:
                    offenders.append("%s { %s }" % (selector, decl))
        self.assertEqual(offenders, [], "image widths must be clamped with min(...)")

    def test_wide_tables_scroll_instead_of_widening_the_page(self):
        for selector in (".post-body table", ".doc-body table"):
            block = theme.STYLESHEET.split(selector + " {")[1].split("}")[0]
            self.assertIn("overflow-x: auto", block, selector)
            self.assertIn("max-width: 100%", block, selector)

    def test_the_nav_underline_tracks_its_padding(self):
        # Mobile shrinks the padding; a hard-coded 1.5rem left the rule misaligned.
        self.assertIn("--nav-pad", theme.STYLESHEET)
        self.assertNotIn("width: calc(100% - 1.5rem)", theme.STYLESHEET)

    def test_narrow_screens_get_their_own_rules(self):
        self.assertIn("@media (max-width: 900px)", theme.STYLESHEET)
        self.assertIn("@media (max-width: 640px)", theme.STYLESHEET)



class PrivateAddressTests(unittest.TestCase):
    """The connect address is private — applying is how a player gets it."""

    PAGES = sorted((Path(__file__).resolve().parents[1] / "pages").glob("*.md"))

    def test_there_are_pages_to_check(self):
        self.assertTrue(self.PAGES, "no pages found to scan")

    def test_no_page_publishes_an_ip_literal(self):
        for path in self.PAGES:
            found = re.findall(r"\b\d{1,3}(?:\.\d{1,3}){3}\b", path.read_text(encoding="utf-8"))
            self.assertEqual(found, [], "%s leaks %s" % (path.name, found))

    def test_the_web_rewrite_actually_ran(self):
        # If the bot's fallback wording reaches the page, the rewrite silently
        # stopped matching and the guide is telling players the wrong thing.
        import sync_from_bot

        for before, _after in sync_from_bot.WEB_REWRITES:
            for path in self.PAGES:
                self.assertNotIn(before, path.read_text(encoding="utf-8"), path.name)

    def test_the_shipped_config_keeps_the_address_private(self):
        import config

        self.assertEqual(
            config.SERVER_ADDRESS, "",
            "SERVER_ADDRESS publishes the connect address in the footer of every "
            "page and as a Copy IP button; the address is private",
        )



class LongTokenTests(unittest.TestCase):
    """A single unbreakable token wider than a phone widens the whole page.

    The guide shipped a bare 68-character Discord channel URL, which let phones
    zoom out of the layout — on that page only, because that is the only page
    the URL appeared on.
    """

    PAGES = sorted((Path(__file__).resolve().parents[1] / "pages").glob("*.md"))

    def test_no_generated_page_carries_a_bare_url(self):
        for path in self.PAGES:
            text = path.read_text(encoding="utf-8")
            # A URL not preceded by "(" is not inside a markdown link.
            bare = re.findall(r"(?<!\()https?://[^\s)\]]+", text)
            self.assertEqual(bare, [], "%s has a bare URL: %s" % (path.name, bare))

    def test_bare_urls_get_link_text(self):
        import sync_from_bot

        out = sync_from_bot._link_bare_urls("See https://example.com/a/b now")
        self.assertEqual(out, "See [example.com](https://example.com/a/b) now")

    def test_a_discord_channel_url_reads_as_discord(self):
        import sync_from_bot

        out = sync_from_bot._link_bare_urls("Here: https://discord.com/channels/1/2")
        self.assertIn("[read it in Discord](https://discord.com/channels/1/2)", out)

    def test_an_existing_markdown_link_is_left_alone(self):
        import sync_from_bot

        text = "[Paper](https://papermc.io) is the server"
        self.assertEqual(sync_from_bot._link_bare_urls(text), text)

    def test_prose_wraps_long_tokens_as_a_backstop(self):
        for selector in (".post-body {", ".doc-body {"):
            block = theme.STYLESHEET.split(selector)[1].split("}")[0]
            self.assertIn("overflow-wrap: anywhere", block, selector)


class MotionTests(unittest.TestCase):
    def test_one_motion_vocabulary_is_defined_and_used(self):
        for token in ("--ease:", "--ease-out:", "--dur:", "--dur-slow:"):
            self.assertIn(token, theme.STYLESHEET, token)
        self.assertGreater(theme.STYLESHEET.count("var(--ease"), 10)

    def test_elevation_tokens_are_defined(self):
        for token in ("--lift-1:", "--lift-2:", "--lift-3:"):
            self.assertIn(token, theme.STYLESHEET, token)

    def test_buttons_lift_rather_than_jump_at_the_cursor(self):
        # scale(1.05) on hover is the toy feel; buttons should translate instead.
        for selector in (".btn:hover", ".cta-primary:hover", ".social-btn:hover"):
            block = theme.STYLESHEET.split(selector + " {")[1].split("}")[0]
            self.assertNotIn("scale(1.", block, selector)
            self.assertIn("translateY(-", block, selector)

    def test_every_animation_is_disabled_for_reduced_motion(self):
        block = theme.STYLESHEET.split("@media (prefers-reduced-motion: reduce) {")[1]
        self.assertIn("animation: none !important", block)
        self.assertIn("transition: none !important", block)

    def test_the_entrance_is_opt_in(self):
        # Guarded by no-preference, so it never runs for someone who opted out.
        self.assertIn("@media (prefers-reduced-motion: no-preference)", theme.STYLESHEET)
        head, _sep, tail = theme.STYLESHEET.partition("@keyframes rise")
        self.assertIn("no-preference", head.rsplit("@media", 1)[-1])



class ServerStatsTests(unittest.TestCase):
    """The address is private; only counts may reach the page."""

    STATUS = {
        "players": {"online": 11, "max": 50},
        "version": {"name": "Paper 1.21.11", "protocol": 767},
        "description": {"text": "Mysterious Server X"},
        "favicon": "data:image/png;base64,AAAA",
    }

    def test_it_extracts_the_numbers(self):
        import server_status

        stats = server_status.stats_from(self.STATUS)
        self.assertEqual(stats["online"], 11)
        self.assertEqual(stats["max"], 50)
        self.assertEqual(stats["version"], "1.21.11")
        self.assertTrue(stats["checked_at"].endswith("Z"))

    def test_it_keeps_only_the_numbers(self):
        import server_status

        # No MOTD, no favicon, no player list, and above all no address.
        self.assertEqual(
            set(server_status.stats_from(self.STATUS)),
            {"online", "max", "version", "checked_at"},
        )

    def test_a_missing_player_count_is_an_error_not_a_zero(self):
        import server_status

        with self.assertRaises(server_status.QueryError):
            server_status.stats_from({"version": {"name": "Paper 1.21.11"}})

    def test_the_version_drops_the_server_software(self):
        import server_status

        self.assertEqual(server_status._version_number("Paper 1.21.11"), "1.21.11")
        self.assertEqual(server_status._version_number("1.21.1"), "1.21.1")

    def test_the_band_is_omitted_without_stats(self):
        self.assertEqual(theme._stats_band(None), "")
        self.assertEqual(theme._stats_band({}), "")

    def test_the_band_shows_the_figures_and_when_they_were_checked(self):
        band = theme._stats_band(
            {"online": 11, "max": 50, "version": "1.21.11",
             "checked_at": "2026-08-21T10:45:04Z"}
        )
        self.assertIn(">11<", band)
        self.assertIn(">50<", band)
        self.assertIn("1.21.11", band)
        self.assertIn("Players online", band)
        self.assertIn("Checked", band)
        self.assertIn('data-ago="2026-08-21T10:45:04Z"', band)

    def test_the_band_never_carries_an_address(self):
        band = theme._stats_band(
            {"online": 1, "max": 2, "version": "1.21.11",
             "checked_at": "2026-08-21T10:45:04Z",
             "host": "10.0.0.1", "port": 25565}
        )
        self.assertNotIn("10.0.0.1", band)
        self.assertNotIn("25565", band)

    def test_a_missing_stats_file_simply_means_no_panel(self):
        saved = build.DATA_DIR
        build.DATA_DIR = Path(tempfile.mkdtemp())
        try:
            self.assertIsNone(build.load_stats())
        finally:
            build.DATA_DIR = saved

    def test_a_corrupt_stats_file_is_ignored_rather_than_fatal(self):
        saved = build.DATA_DIR
        tmp = Path(tempfile.mkdtemp())
        (tmp / "stats.json").write_text("{not json", encoding="utf-8")
        build.DATA_DIR = tmp
        try:
            self.assertIsNone(build.load_stats())
        finally:
            build.DATA_DIR = saved

    def test_stats_without_counts_are_ignored(self):
        saved = build.DATA_DIR
        tmp = Path(tempfile.mkdtemp())
        (tmp / "stats.json").write_text('{"version": "1.21.11"}', encoding="utf-8")
        build.DATA_DIR = tmp
        try:
            self.assertIsNone(build.load_stats())
        finally:
            build.DATA_DIR = saved

    def test_the_band_tells_the_browser_where_to_refetch(self):
        band = theme._stats_band(
            {"online": 11, "max": 50, "version": "1.21.11",
             "checked_at": "2026-08-21T10:45:04Z"}
        )
        self.assertIn('data-stats-src="assets/stats.json"', band)
        self.assertIn('data-stat="online"', band)
        self.assertIn('data-stat="max"', band)
        self.assertIn('data-stat="version"', band)

    def test_the_refresh_script_polls_and_survives_a_failed_poll(self):
        self.assertIn("data-stats-src", theme.STATS_SCRIPT)
        self.assertIn("visibilitychange", theme.STATS_SCRIPT)
        self.assertIn("setInterval", theme.STATS_SCRIPT)
        # A poll that fails must leave the numbers the build put there.
        self.assertIn(".catch(", theme.STATS_SCRIPT)

    def test_the_schedule_refreshes_the_figures_every_five_minutes(self):
        workflow = (Path(__file__).resolve().parents[1] / "deploy.yml").read_text(encoding="utf-8")
        self.assertIn('cron: "*/5 * * * *"', workflow)

    def test_the_stats_file_is_git_ignored(self):
        ignore = (Path(__file__).resolve().parents[1] / ".gitignore").read_text(encoding="utf-8")
        self.assertIn("data/", ignore)



class ArtworkTests(unittest.TestCase):
    """Post artwork is often a transparent PNG with light ink."""

    def test_art_sits_on_a_plate_so_light_ink_stays_visible(self):
        # A white wordmark on a transparent background vanished against the
        # light theme's near-white page until these gained a backing colour.
        self.assertIn("--art-plate:", theme.STYLESHEET)
        for selector in ("\n.post-body figure.shot img {", "\n.card-thumb {"):
            block = theme.STYLESHEET.split(selector)[1].split("}")[0]
            self.assertIn("var(--art-plate)", block, selector.strip())

    def test_the_hero_frame_contains_rather_than_crops(self):
        # cover cropped a wordmark to fill the 16:9 frame. A correctly-shaped
        # screenshot fills the frame under contain anyway.
        block = theme.STYLESHEET.split(".hero-feature .frame img {")[1].split("}")[0]
        self.assertIn("object-fit: contain", block)
        self.assertNotIn("object-fit: cover", block)

    def test_the_card_icon_is_contained_not_cropped(self):
        # cover on a 1.6:1 wordmark cut the ends off the words.
        block = theme.STYLESHEET.split("\n.card-thumb .icon {")[1].split("}")[0]
        self.assertIn("object-fit: contain", block)
        self.assertNotIn("object-fit: cover", block)
        self.assertNotIn("aspect-ratio: 1 / 1", block)

    def test_the_post_references_artwork_that_exists(self):
        posts = build.load_posts(include_drafts=True)
        self.assertTrue(posts)
        for post in posts:
            for name in (post.cover, post.hero, post.icon):
                if not name or name.startswith(("http", "/")):
                    continue
                path = build.MEDIA_DIR / post.slug / name
                self.assertTrue(path.exists(), "%s references missing %s" % (post.path.name, path))



class BlogArchiveTests(unittest.TestCase):
    """The /blog page: every update, with a category filter."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self._saved = (build.POSTS_DIR, build.PAGES_DIR, build.MEDIA_DIR,
                       build.STATIC_DIR, build.DIST_DIR)
        build.POSTS_DIR = self.tmp / "posts"
        build.PAGES_DIR = self.tmp / "pages"
        build.MEDIA_DIR = self.tmp / "media"
        build.STATIC_DIR = self.tmp / "static"
        build.DIST_DIR = self.tmp / "dist"
        for folder in (build.POSTS_DIR, build.PAGES_DIR, build.MEDIA_DIR, build.STATIC_DIR):
            folder.mkdir(parents=True)
        self.addCleanup(self._restore)

    def _restore(self):
        (build.POSTS_DIR, build.PAGES_DIR, build.MEDIA_DIR,
         build.STATIC_DIR, build.DIST_DIR) = self._saved

    def post(self, month, slug, category="Announcement"):
        (build.POSTS_DIR / ("2026-%02d-01-%s.md" % (month, slug))).write_text(
            "---\ntitle: %s\ncategory: %s\n---\n\nbody\n" % (slug, category),
            encoding="utf-8",
        )

    def archive(self):
        return (build.DIST_DIR / "blog" / "index.html").read_text(encoding="utf-8")

    def test_the_archive_is_written(self):
        self.post(1, "update-1")
        build.build("https://example.com")
        self.assertTrue((build.DIST_DIR / "blog" / "index.html").exists())

    def test_it_lists_every_post(self):
        for n in range(1, 6):
            self.post(n, "update-%d" % n)
        build.build("https://example.com")
        self.assertEqual(self.archive().count('class="card"'), 5)

    def test_the_newest_post_is_called_out_and_not_duplicated(self):
        self.post(1, "old")
        self.post(9, "newest")
        build.build("https://example.com")
        page = self.archive()
        self.assertIn('id="featured"', page)
        # Its card is in the grid for filtering, but starts hidden.
        self.assertEqual(page.count('data-featured="1" hidden'), 1)

    def test_a_tab_per_category_with_counts(self):
        self.post(1, "a", "Event")
        self.post(2, "b", "Event")
        self.post(3, "c", "Patch")
        build.build("https://example.com")
        bar = re.search(r'<div class="cat-tabs".*?</div>', self.archive(), re.S).group(0)
        self.assertIn('data-cat="*">All Posts<span class="count">3', bar)
        self.assertIn('data-cat="Event">Event<span class="count">2', bar)
        self.assertIn('data-cat="Patch">Patch<span class="count">1', bar)

    def test_the_filter_is_hidden_when_there_is_only_one_category(self):
        self.post(1, "a", "Event")
        self.post(2, "b", "Event")
        build.build("https://example.com")
        self.assertNotIn('<div class="cat-tabs"', self.archive())

    def test_every_card_carries_its_category(self):
        self.post(1, "a", "Event")
        self.post(2, "b", "Patch")
        build.build("https://example.com")
        self.assertEqual(
            sorted(set(re.findall(r'data-category="([^"]+)"', self.archive()))),
            ["Event", "Patch"],
        )

    def test_an_empty_archive_says_so_rather_than_breaking(self):
        build.build("https://example.com")
        page = self.archive()
        self.assertIn("No updates posted yet", page)
        self.assertNotIn('id="featured"', page)


    def test_blog_is_reserved_so_a_post_cannot_take_it(self):
        (build.POSTS_DIR / "2026-01-01-x.md").write_text(
            "---\ntitle: T\nslug: blog\n---\n\nbody", encoding="utf-8")
        with self.assertRaises(build.PostError):
            build.build("https://example.com")

    def test_the_nav_carries_home_and_blog_on_every_page(self):
        self.post(1, "update-1")
        (build.PAGES_DIR / "guide.md").write_text(
            "---\ntitle: G\nnav: Guide\n---\n\nx", encoding="utf-8")
        build.build("https://example.com")
        for rel in ("index.html", "blog/index.html", "update-1/index.html",
                    "guide/index.html", "404.html"):
            page = (build.DIST_DIR / rel).read_text(encoding="utf-8")
            nav = re.search(r'aria-label="Primary">(.*?)</nav>', page, re.S).group(1)
            self.assertIn(">Home</a>", nav, rel)
            self.assertIn(">Blog</a>", nav, rel)
            self.assertIn(">Guide</a>", nav, rel)

    def test_reading_a_post_keeps_the_blog_tab_lit(self):
        self.post(1, "update-1")
        build.build("https://example.com")
        page = (build.DIST_DIR / "update-1" / "index.html").read_text(encoding="utf-8")
        self.assertIn('href="../blog/" aria-current="page"', page)

    def test_the_figures_are_published_for_the_page_to_refetch(self):
        self.post(1, "update-1")
        saved = build.DATA_DIR
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp, True)
        (tmp / "stats.json").write_text(
            json.dumps({"online": 7, "max": 50, "version": "1.21.11",
                        "checked_at": "2026-08-21T10:45:04Z"}),
            encoding="utf-8",
        )
        build.DATA_DIR = tmp
        try:
            build.build("https://example.com")
        finally:
            build.DATA_DIR = saved
        published = build.DIST_DIR / "assets" / "stats.json"
        self.assertTrue(published.exists())
        self.assertEqual(json.loads(published.read_text(encoding="utf-8"))["online"], 7)

    def test_no_figures_means_no_published_file_to_mislead_anyone(self):
        self.post(1, "update-1")
        saved = build.DATA_DIR
        build.DATA_DIR = Path(tempfile.mkdtemp())
        try:
            build.build("https://example.com")
        finally:
            build.DATA_DIR = saved
        self.assertFalse((build.DIST_DIR / "assets" / "stats.json").exists())


class EventArchiveTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self._saved = (
            build.POSTS_DIR, build.EVENTS_DIR, build.PAGES_DIR, build.MEDIA_DIR,
            build.STATIC_DIR, build.DIST_DIR,
        )
        build.POSTS_DIR = self.tmp / "posts"
        build.EVENTS_DIR = self.tmp / "events"
        build.PAGES_DIR = self.tmp / "pages"
        build.MEDIA_DIR = self.tmp / "media"
        build.STATIC_DIR = self.tmp / "static"
        build.DIST_DIR = self.tmp / "dist"
        for folder in (
            build.POSTS_DIR, build.EVENTS_DIR, build.PAGES_DIR,
            build.MEDIA_DIR, build.STATIC_DIR,
        ):
            folder.mkdir(parents=True)
        self.addCleanup(self._restore)

    def _restore(self):
        (
            build.POSTS_DIR, build.EVENTS_DIR, build.PAGES_DIR, build.MEDIA_DIR,
            build.STATIC_DIR, build.DIST_DIR,
        ) = self._saved

    def event(self, slug="void-weekend", draft=False):
        (build.EVENTS_DIR / ("2026-01-01-%s.md" % slug)).write_text(
            "---\ntitle: Void Weekend\ndate: 2026-09-05 18:00\ncategory: Event\n%s---\n\nSoon\n"
            % ("draft: true\n" if draft else ""),
            encoding="utf-8",
        )

    def test_events_have_an_archive_and_nested_post_url(self):
        self.event()
        build.build("https://example.com")
        archive = build.DIST_DIR / "events" / "index.html"
        post = build.DIST_DIR / "events" / "void-weekend" / "index.html"
        self.assertTrue(archive.exists())
        self.assertTrue(post.exists())
        self.assertIn("Void Weekend", archive.read_text(encoding="utf-8"))
        self.assertIn('href="../../events/" aria-current="page"',
                      post.read_text(encoding="utf-8"))

    def test_event_drafts_only_appear_in_draft_builds(self):
        self.event(draft=True)
        build.build("https://example.com")
        self.assertFalse((build.DIST_DIR / "events" / "void-weekend").exists())
        build.build("https://example.com", include_drafts=True)
        self.assertTrue((build.DIST_DIR / "events" / "void-weekend" / "index.html").exists())

    def test_empty_events_page_explains_that_nothing_is_announced(self):
        build.build("https://example.com")
        page = (build.DIST_DIR / "events" / "index.html").read_text(encoding="utf-8")
        self.assertIn("No upcoming events announced yet", page)

    def test_events_is_reserved_from_root_posts(self):
        (build.POSTS_DIR / "2026-01-01-events.md").write_text(
            "---\ntitle: Bad\n---\n\nbody\n", encoding="utf-8"
        )
        with self.assertRaises(build.PostError):
            build.build("https://example.com")



class WarmThemeTests(unittest.TestCase):
    """The palette should read warm, not neutral dark."""

    def tokens(self, selector):
        block = theme.STYLESHEET.split(selector)[1].split("}")[0]
        return dict(re.findall(r"(--[a-z-]+):\s*(#[0-9a-f]{6})", block))

    NEUTRALS = ("--page-bg", "--canvas", "--surface-raised", "--ink", "--text-muted", "--grey")

    def test_the_dark_neutrals_lean_warm(self):
        tokens = self.tokens(':root[data-theme="dark"] {')
        for name in self.NEUTRALS:
            value = tokens[name]
            red, blue = int(value[1:3], 16), int(value[5:7], 16)
            self.assertGreater(red, blue, "%s (%s) is not warm" % (name, value))

    def test_the_light_neutrals_lean_warm_too(self):
        tokens = self.tokens("\n:root {")
        for name in self.NEUTRALS:
            value = tokens[name]
            red, blue = int(value[1:3], 16), int(value[5:7], 16)
            self.assertGreater(red, blue, "%s (%s) is not warm" % (name, value))

    def test_surfaces_stay_near_neutral_so_they_read_orange_not_brown(self):
        # Brown is desaturated orange. Tinting the surfaces themselves made mud;
        # the colour has to come from saturated light over near-neutral dark.
        import colorsys

        tokens = self.tokens(':root[data-theme="dark"] {')
        for name in ("--page-bg", "--canvas", "--surface", "--surface-raised"):
            value = tokens[name]
            rgb = [int(value[i:i + 2], 16) / 255 for i in (1, 3, 5)]
            saturation = colorsys.rgb_to_hls(*rgb)[2]
            self.assertLess(saturation, 0.20, "%s (%s) is muddy" % (name, value))

    def test_the_bloom_carries_real_colour(self):
        for selector in (':root[data-theme="dark"] {', "\n:root {"):
            block = theme.STYLESHEET.split(selector)[1].split("}")[0]
            red, green, blue = re.search(
                r"--bloom:\s*rgba\((\d+),\s*(\d+),\s*(\d+)", block
            ).groups()
            self.assertGreaterEqual(int(red), 240, selector)
            self.assertLessEqual(int(blue), 40, selector)

    def test_a_brand_bloom_sits_behind_every_page(self):
        self.assertIn("--bloom:", theme.STYLESHEET)
        self.assertIn("--bloom-2:", theme.STYLESHEET)
        block = theme.STYLESHEET.split(".page::before {")[1].split("}")[0]
        self.assertIn("var(--bloom)", block)
        self.assertIn("radial-gradient", block)

    def test_dark_bloom_is_stronger_than_light(self):
        # A tint that reads on white is invisible on near-black.
        def alpha(selector):
            block = theme.STYLESHEET.split(selector)[1].split("}")[0]
            return float(re.search(r"--bloom:\s*rgba\([^)]*,\s*([0-9.]+)\)", block).group(1))
        self.assertGreater(alpha(':root[data-theme="dark"] {'), alpha("\n:root {"))


class AuthoringToolTests(unittest.TestCase):
    """blog.py is what makes posting possible without touching git."""

    def test_the_next_slug_follows_the_highest_existing_one(self):
        import blog

        saved = blog.ROOT
        tmp = Path(tempfile.mkdtemp())
        (tmp / "posts").mkdir()
        blog.ROOT = tmp
        try:
            self.assertEqual(blog.next_slug(), "update-1")
            (tmp / "posts" / "2026-01-01-update-1.md").touch()
            (tmp / "posts" / "2026-02-01-update-7.md").touch()
            self.assertEqual(blog.next_slug(), "update-8")
        finally:
            blog.ROOT = saved

    def test_a_scaffolded_post_is_valid_front_matter(self):
        # The template once wrapped its tagline over two lines, which the parser
        # rejects — so every scaffolded post was born broken.
        import blog

        text = blog.TEMPLATE.format(
            title="Fiesta Forever", today="2026-08-22", category="Event",
            covers="abc1234", tag="event"
        )
        meta, body = build.parse_front_matter(text, Path("2026-08-22-update-2.md"))
        self.assertEqual(meta["title"], "Fiesta Forever")
        self.assertEqual(meta["category"], "Event")
        self.assertTrue(body.strip())

    def test_the_template_carries_the_house_layout(self):
        import blog

        self.assertIn("## ", blog.TEMPLATE)
        self.assertIn("### ", blog.TEMPLATE)
        self.assertIn("![](", blog.TEMPLATE)
        for key in ("title:", "tagline:", "date:", "category:", "cover:", "icon:"):
            self.assertIn(key, blog.TEMPLATE)
        self.assertNotIn("\nhero:", blog.TEMPLATE)

    def test_new_records_the_commit_it_covers(self):
        import blog

        self.assertIn("covers: {covers}", blog.TEMPLATE)

    def test_the_range_excludes_the_blog_itself(self):
        # A server update post must never describe a change to the website.
        import blog

        source = Path(blog.__file__).read_text(encoding="utf-8")
        body = source.split("def report_range")[1].split("def next_slug")[0]
        self.assertIn(":(exclude)devblog", body)

    def test_the_range_falls_back_for_posts_without_the_key(self):
        import blog

        body = Path(blog.__file__).read_text(encoding="utf-8")
        body = body.split("def report_range")[1].split("def next_slug")[0]
        self.assertIn("--since=", body)

    def test_covers_is_read_back_off_a_post(self):
        import blog

        tmp = Path(tempfile.mkdtemp()) / "2026-01-01-x.md"
        tmp.write_text("---\ntitle: T\ncovers: abc1234\n---\n\nbody", encoding="utf-8")
        self.assertEqual(blog.covers_of(tmp), "abc1234")

    def test_a_post_without_covers_reads_as_empty(self):
        import blog

        tmp = Path(tempfile.mkdtemp()) / "2026-01-01-x.md"
        tmp.write_text("---\ntitle: T\n---\n\nbody", encoding="utf-8")
        self.assertEqual(blog.covers_of(tmp), "")

    def test_covers_does_not_break_the_build(self):
        # It is metadata for the next author; the generator must ignore it.
        meta, _body = build.parse_front_matter(
            "---\ntitle: T\ncovers: abc1234\n---\n\nbody", Path("2026-01-01-x.md")
        )
        post = build.Post(Path("2026-01-01-x.md"), meta, "body")
        self.assertEqual(post.title, "T")

    def test_publish_waits_for_checks_to_register(self):
        # GitHub takes seconds to queue a new PR's checks; until it does,
        # `gh pr checks` exits non-zero saying none were reported. Treating that
        # as a failure aborted a publish whose checks then went on to pass.
        import blog

        source = Path(blog.__file__).read_text(encoding="utf-8")
        body = source.split("def cmd_publish")[1]
        self.assertIn("no checks reported", body)
        self.assertIn("time.sleep", body)

    def test_every_template_front_matter_line_is_one_key_value(self):
        import blog

        front = blog.TEMPLATE.split("---")[1]
        for line in front.strip().splitlines():
            self.assertRegex(line, r"^[a-z_]+:\s", "wrapped line in the template: %r" % line)



class FooterSeamTests(unittest.TestCase):
    def test_the_footer_has_no_top_margin(self):
        # margin-top exposed the body colour, which is darker than either
        # neighbour, drawing a black bar between the page and the footer once
        # the footer gained its own background.
        block = theme.STYLESHEET.split(".site-footer {")[1].split("}")[0]
        self.assertIn("margin-top: 0", block)
        self.assertIn("padding: 3rem", block)


class PostContentTests(unittest.TestCase):
    """Copy that outlives the feature it describes."""

    POSTS = sorted((Path(__file__).resolve().parents[1] / "posts").glob("*.md"))

    def test_no_post_advertises_rss(self):
        # The feed was removed; a post telling readers to subscribe to it sends
        # them to a 404.
        for path in self.POSTS:
            self.assertNotIn("RSS", path.read_text(encoding="utf-8"), path.name)

    def test_no_post_links_a_page_that_does_not_exist(self):
        pages = {p.stem for p in (Path(__file__).resolve().parents[1] / "pages").glob("*.md")}
        known = pages | {"blog", "index.html", ""}
        for path in self.POSTS:
            for href in re.findall(r"\]\(/([^)]*)\)", path.read_text(encoding="utf-8")):
                self.assertIn(href.strip("/"), known, "%s links /%s" % (path.name, href))



class ContrastTests(unittest.TestCase):
    """Brightening a background costs contrast; this is what stops it going too far."""

    PAIRS = (
        ("--ink", "--page-bg"),
        ("--text-muted", "--page-bg"),
        ("--grey", "--page-bg"),
        ("--ink", "--surface"),
        ("--text-muted", "--surface-raised"),
        ("--grey", "--surface"),
    )

    def tokens(self, selector):
        block = theme.STYLESHEET.split(selector)[1].split("}")[0]
        return dict(re.findall(r"(--[a-z-]+):\s*(#[0-9a-f]{6})", block))

    @staticmethod
    def luminance(value):
        def channel(index):
            c = int(value[index:index + 2], 16) / 255
            return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
        return 0.2126 * channel(1) + 0.7152 * channel(3) + 0.0722 * channel(5)

    def ratio(self, foreground, background):
        high, low = sorted((self.luminance(foreground), self.luminance(background)),
                           reverse=True)
        return (high + 0.05) / (low + 0.05)

    def test_every_text_pair_clears_wcag_aa(self):
        for selector in (':root[data-theme="dark"] {', "\n:root {"):
            tokens = self.tokens(selector)
            for foreground, background in self.PAIRS:
                got = self.ratio(tokens[foreground], tokens[background])
                self.assertGreaterEqual(
                    got, 4.5,
                    "%s on %s is %.2f:1 in %s" % (foreground, background, got, selector.strip()),
                )

    def test_the_dark_theme_is_not_near_black(self):
        # It was 5.7% lightness, which read as black rather than dark.
        import colorsys

        tokens = self.tokens(':root[data-theme="dark"] {')
        for name in ("--page-bg", "--canvas", "--surface"):
            value = tokens[name]
            rgb = [int(value[i:i + 2], 16) / 255 for i in (1, 3, 5)]
            lightness = colorsys.rgb_to_hls(*rgb)[1]
            self.assertGreater(lightness, 0.09, "%s (%s) is too dark" % (name, value))



class SystemDefaultTests(unittest.TestCase):
    """The site follows the operating system unless the reader chooses."""

    def test_no_stored_choice_leaves_it_to_the_os(self):
        # No attribute stamped means the prefers-color-scheme block answers.
        self.assertNotIn("'light')", theme.THEME_BOOT.split("catch")[0].split("s==='light'")[1])
        self.assertIn("s==='light'||s==='dark'", theme.THEME_BOOT)

    def test_an_explicit_choice_is_stamped_before_first_paint(self):
        self.assertIn("setAttribute('data-theme',s)", theme.THEME_BOOT)

    def test_the_switch_ships_on_system(self):
        page = theme.render_404(prefix="/")
        switch = re.search(r'<div class="theme-switch".*?</div>', page, re.S).group(0)
        pairs = dict(zip(re.findall(r'data-theme="(\w+)"', switch),
                         re.findall(r'aria-checked="(\w+)"', switch)))
        self.assertEqual(pairs["system"], "true")
        self.assertEqual(pairs["light"], "false")
        self.assertEqual(pairs["dark"], "false")

    def test_the_script_agrees_with_the_boot_script(self):
        self.assertIn("localStorage.getItem('theme') || 'system'", theme.THEME_SCRIPT)

    def test_the_os_preference_block_is_still_wired_up(self):
        self.assertIn("@media (prefers-color-scheme: dark)", theme.STYLESHEET)
        self.assertIn(':root:not([data-theme="light"])', theme.STYLESHEET)


class LightDepthTests(unittest.TestCase):
    """Stop the light theme reading as white slapped on white."""

    def test_cards_sit_clearly_above_the_page(self):
        import colorsys

        block = theme.STYLESHEET.split("\n:root {")[1].split("}")[0]
        tokens = dict(re.findall(r"(--[a-z-]+):\s*(#[0-9a-f]{6})", block))

        def light(value):
            return colorsys.rgb_to_hls(
                *[int(value[i:i + 2], 16) / 255 for i in (1, 3, 5)]
            )[1] * 100

        gap = light(tokens["--surface"]) - light(tokens["--page-bg"])
        self.assertGreater(gap, 4, "only %.1f points between page and surface" % gap)

    def test_shadows_are_layered_rather_than_one_blur(self):
        # A single blurred box never reads as height.
        for token in ("--lift-1", "--lift-2", "--lift-3"):
            value = theme.STYLESHEET.split(token + ":")[1].split(";")[0]
            self.assertGreaterEqual(
                value.count("rgb(var(--shadow-rgb)"), 3, "%s has too few stops" % token
            )

    def test_the_light_shadow_has_colour_in_it(self):
        # A neutral grey shadow on a warm page looks like dirt.
        block = theme.STYLESHEET.split("\n:root {")[1].split("}")[0]
        red, green, blue = re.search(r"--shadow-rgb:\s*(\d+),\s*(\d+),\s*(\d+)", block).groups()
        self.assertGreater(int(red), int(blue))

    def test_raised_surfaces_carry_an_edge(self):
        for selector in (".stats-card {", ".community-card {", ".hero-feature a {"):
            block = theme.STYLESHEET.split(selector)[1].split("}")[0]
            self.assertIn("border", block, selector)
            self.assertIn("box-shadow", block, selector)



if __name__ == "__main__":
    unittest.main(verbosity=2)
