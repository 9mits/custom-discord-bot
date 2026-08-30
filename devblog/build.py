"""Static site generator for the Mysterious SMP X dev blog.

Reads `posts/*.md` (YAML-ish front matter + Markdown body), renders them with
the update-post theme in `theme.py`, and writes a self-contained site to
`dist/` ready for GitHub Pages.

    python devblog/build.py            # build into devblog/dist
    python devblog/build.py --serve    # build, then serve on localhost:8000
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Sequence

try:
    import markdown
except ModuleNotFoundError:  # pragma: no cover - guidance beats a stack trace
    sys.exit(
        "The Markdown package is missing. Install the blog deps with:\n"
        "    pip install -r devblog/requirements.txt"
    )

import config
import theme

ROOT = Path(__file__).resolve().parent
POSTS_DIR = ROOT / "posts"
EVENTS_DIR = ROOT / "events"
PAGES_DIR = ROOT / "pages"
DATA_DIR = ROOT / "data"
MEDIA_DIR = ROOT / "media"
STATIC_DIR = ROOT / "static"
DIST_DIR = ROOT / "dist"

# Front matter keys that hold a comma-separated list rather than a single value.
LIST_KEYS = {"tags"}

# Posts sit at the site root now, so a slug must not shadow anything the build
# writes there itself.
RESERVED_SLUGS = {"assets", "media", "index", "404", "index.html", "blog", "events"}

MD_EXTENSIONS = [
    "extra",        # tables, footnotes, attr_list, fenced code
    "nl2br",        # a single newline is a line break: posts read line-per-beat
    "sane_lists",
    "smarty",
]


class PostError(Exception):
    """Raised when a post file cannot be understood."""


class Post:
    def __init__(
        self, path: Path, meta: Dict[str, object], body_md: str, event: bool = False
    ):
        self.path = path
        self.meta = meta
        self.body_md = body_md
        self.event = event

        self.slug = str(meta.get("slug") or self._slug_from_filename(path))
        self.title = str(meta.get("title") or "").strip()
        if not self.title:
            raise PostError("%s: front matter is missing a 'title'" % path.name)

        self.tagline = str(meta.get("tagline") or "").strip()
        self.date = self._parse_date(meta.get("date"), path)
        # Editorial artwork for home/archive cards and social previews. It is
        # deliberately separate from an in-post gameplay hero.
        self.cover = str(meta.get("cover") or "").strip()
        self.hero = str(meta.get("hero") or "").strip()
        # The square art shown on cards; falls back to cover/hero artwork.
        self.icon = str(meta.get("icon") or "").strip()
        self.category = str(meta.get("category") or config.DEFAULT_CATEGORY).strip()
        self.signoff = str(meta.get("signoff") or "").strip()
        self.tags = list(meta.get("tags") or [])  # type: ignore[arg-type]
        self.draft = str(meta.get("draft") or "").lower() in {"1", "true", "yes"}

    @staticmethod
    def _slug_from_filename(path: Path) -> str:
        # 2026-08-21-fiesta-forever.md -> fiesta-forever
        stem = path.stem
        match = re.match(r"^\d{4}-\d{2}-\d{2}-(.+)$", stem)
        return match.group(1) if match else stem

    @staticmethod
    def _parse_date(raw: object, path: Path) -> datetime:
        if raw:
            text = str(raw).strip()
            for fmt in ("%Y-%m-%d %H:%M", "%Y-%m-%d"):
                try:
                    return datetime.strptime(text, fmt).replace(tzinfo=timezone.utc)
                except ValueError:
                    continue
            raise PostError("%s: could not read date %r (use YYYY-MM-DD)" % (path.name, text))
        match = re.match(r"^(\d{4}-\d{2}-\d{2})", path.stem)
        if match:
            return datetime.strptime(match.group(1), "%Y-%m-%d").replace(tzinfo=timezone.utc)
        raise PostError("%s: needs a 'date' in front matter or a dated filename" % path.name)

    @property
    def url(self) -> str:
        return "events/%s/" % self.slug if self.event else "%s/" % self.slug

    @property
    def media_dir(self) -> Path:
        return MEDIA_DIR / self.slug

    def display_date(self) -> str:
        # Cross-platform day-without-leading-zero; %-d is glibc-only.
        return "%s %d, %d" % (self.date.strftime("%B"), self.date.day, self.date.year)


class Page:
    """A standing page — guide, rules, apply — rather than a dated update."""

    def __init__(self, path: Path, meta: Dict[str, object], body_md: str):
        self.path = path
        self.slug = str(meta.get("slug") or path.stem)
        self.title = str(meta.get("title") or "").strip()
        if not self.title:
            raise PostError("%s: front matter is missing a 'title'" % path.name)
        self.nav = str(meta.get("nav") or self.title).strip()
        self.nav_hidden = str(meta.get("nav_hidden") or "").lower() in {
            "1", "true", "yes"
        }
        self.tagline = str(meta.get("tagline") or "").strip()
        try:
            self.order = int(str(meta.get("order") or "99"))
        except ValueError:
            raise PostError("%s: 'order' must be a whole number" % path.name)
        self.layout = str(meta.get("layout") or "document").strip().lower()
        if self.layout not in {"document", "dashboard"}:
            raise PostError("%s: unknown page layout %r" % (path.name, self.layout))
        self.body_md = body_md

    @property
    def url(self) -> str:
        return "%s/" % self.slug


def parse_front_matter(text: str, path: Path) -> "tuple[Dict[str, object], str]":
    """Split `---` delimited front matter off the top of a post."""
    if not text.startswith("---"):
        raise PostError("%s: must open with a '---' front matter block" % path.name)

    parts = text.split("\n---", 2)
    if len(parts) < 2:
        raise PostError("%s: front matter block is never closed with '---'" % path.name)

    raw_meta = parts[0][3:]  # drop the opening '---'
    body = parts[1]
    if body.startswith("-"):  # tolerate a '----' style closer
        body = body.lstrip("-")

    meta: Dict[str, object] = {}
    for line_no, line in enumerate(raw_meta.splitlines(), start=2):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if ":" not in stripped:
            raise PostError("%s line %d: expected 'key: value'" % (path.name, line_no))
        key, _, value = stripped.partition(":")
        key = key.strip().lower()
        value = value.strip().strip('"').strip("'")
        if key in LIST_KEYS:
            meta[key] = [item.strip() for item in value.split(",") if item.strip()]
        else:
            meta[key] = value
    return meta, body.lstrip("\n")


def load_posts(include_drafts: bool = False) -> List[Post]:
    posts: List[Post] = []
    for path in sorted(POSTS_DIR.glob("*.md")):
        raw = path.read_text(encoding="utf-8")
        meta, body = parse_front_matter(raw, path)
        post = Post(path, meta, body)
        if post.draft and not include_drafts:
            continue
        posts.append(post)

    slugs = [p.slug for p in posts]
    duplicates = {s for s in slugs if slugs.count(s) > 1}
    if duplicates:
        raise PostError("two posts share the slug(s): %s" % ", ".join(sorted(duplicates)))

    check_slugs([post.slug for post in posts], "posts")

    posts.sort(key=lambda p: (p.date, p.slug), reverse=True)
    return posts


def load_events(include_drafts: bool = False) -> List[Post]:
    events: List[Post] = []
    if not EVENTS_DIR.exists():
        return events
    for path in sorted(EVENTS_DIR.glob("*.md")):
        meta, body = parse_front_matter(path.read_text(encoding="utf-8"), path)
        event = Post(path, meta, body, event=True)
        if event.draft and not include_drafts:
            continue
        events.append(event)
    slugs = [event.slug for event in events]
    duplicates = {slug for slug in slugs if slugs.count(slug) > 1}
    if duplicates:
        raise PostError("two events share the slug(s): %s" % ", ".join(sorted(duplicates)))
    check_slugs(slugs, "events")
    events.sort(key=lambda event: (event.date, event.slug))
    return events


def load_stats() -> Optional[Dict[str, object]]:
    """Server numbers written by `server_status.py`, if CI managed to fetch them.

    Absent on any local build without the secrets, and absent when the query
    failed — the panel simply does not render rather than showing zeros.
    """
    path = DATA_DIR / "stats.json"
    if not path.exists():
        return None
    try:
        stats = json.loads(path.read_text(encoding="utf-8"))
    except ValueError:
        return None
    if not isinstance(stats.get("online"), int) or not isinstance(stats.get("max"), int):
        return None
    return stats


def load_pages() -> List[Page]:
    pages: List[Page] = []
    if not PAGES_DIR.exists():
        return pages
    for path in sorted(PAGES_DIR.glob("*.md")):
        meta, body = parse_front_matter(path.read_text(encoding="utf-8"), path)
        pages.append(Page(path, meta, body))
    pages.sort(key=lambda page: (page.order, page.slug))
    return pages


def rewrite_media_urls(html_body: str, post: Post, prefix: str) -> str:
    """Point bare image/link filenames at the post's own media folder.

    A post says `![](maze.png)` and gets `<prefix>media/<slug>/maze.png`, so
    the markdown stays readable and the files sit next to each other on disk.
    """
    def repl(match: "re.Match[str]") -> str:
        attr, quote, value = match.group(1), match.group(2), match.group(3)
        if re.match(r"^(https?:|//|/|#|mailto:|data:)", value):
            return match.group(0)
        return '%s=%s%smedia/%s/%s%s' % (attr, quote, prefix, post.slug, value, quote)

    return re.sub(r'\b(src|href|poster)=(["\'])([^"\']+)\2', repl, html_body)


def render_body(post: Post, prefix: str) -> str:
    md = markdown.Markdown(extensions=MD_EXTENSIONS)
    body = md.convert(post.body_md)
    body = rewrite_media_urls(body, post, prefix)
    # Standalone images become full-bleed figures rather than inline text runs.
    body = re.sub(
        r"<p>(\s*(?:<img\b[^>]*>\s*)+)</p>",
        lambda m: '<figure class="shot">%s</figure>' % m.group(1).strip(),
        body,
    )
    return body


def excerpt(post: Post, limit: int = 190) -> str:
    if post.tagline:
        text = post.tagline
    else:
        stripped = re.sub(r"!\[[^\]]*\]\([^)]*\)", " ", post.body_md)
        stripped = re.sub(r"[#*_>`\[\]()]", " ", stripped)
        text = " ".join(stripped.split())
    if len(text) <= limit:
        return text
    return text[:limit].rsplit(" ", 1)[0] + "…"


def media_url(post: Post, filename: str, prefix: str) -> Optional[str]:
    if not filename:
        return None
    if re.match(r"^(https?:|//|/)", filename):
        return filename
    return "%smedia/%s/%s" % (prefix, post.slug, filename)


def hero_url(post: Post, prefix: str) -> Optional[str]:
    return media_url(post, post.hero, prefix)


def card_for(post: Post, prefix: str) -> Dict[str, object]:
    """The shape both the index grid and the more-updates strip render from."""
    return {
        "url": ("%s%s" % (prefix, post.url)) if prefix else post.url,
        "title": post.title,
        "date": post.display_date(),
        "iso": post.date.strftime("%Y-%m-%d"),
        "excerpt": excerpt(post),
        "cover": media_url(post, post.cover, prefix),
        "hero": media_url(post, post.hero, prefix),
        "icon": media_url(post, post.icon, prefix),
        "category": post.category,
        "tags": post.tags,
    }


def copy_tree(src: Path, dest: Path) -> None:
    if not src.exists():
        return
    for item in src.iterdir():
        if item.name.startswith("."):
            continue
        target = dest / item.name
        if item.is_dir():
            shutil.copytree(item, target, dirs_exist_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(item, target)


def check_slugs(names: Sequence[str], what: str) -> None:
    for name in names:
        if name.lower() in RESERVED_SLUGS:
            raise PostError(
                "%s: the slug %r is reserved by the site itself; pick another" % (what, name)
            )
        if not re.match(r"^[a-z0-9][a-z0-9-]*$", name):
            raise PostError(
                "%s: the slug %r must be lowercase letters, digits and hyphens" % (what, name)
            )


def build(site_url: str, include_drafts: bool = False) -> List[Post]:
    posts = load_posts(include_drafts=include_drafts)
    events = load_events(include_drafts=include_drafts)
    pages = load_pages()

    # Posts and pages share the root namespace, so they must not collide.
    page_slugs = [page.slug for page in pages]
    check_slugs(page_slugs, "pages")
    clash = sorted(set(page_slugs) & {post.slug for post in posts})
    if clash:
        raise PostError("a page and a post share the slug(s): %s" % ", ".join(clash))

    if DIST_DIR.exists():
        shutil.rmtree(DIST_DIR)
    DIST_DIR.mkdir(parents=True)

    (DIST_DIR / "assets").mkdir(parents=True, exist_ok=True)
    (DIST_DIR / "assets" / "style.css").write_text(theme.STYLESHEET, encoding="utf-8")
    copy_tree(STATIC_DIR, DIST_DIR / "assets")
    copy_tree(MEDIA_DIR, DIST_DIR / "media")

    nav = [
        {"url": "", "label": "Home", "slug": "index"},
        {"url": "blog/", "label": "Blog", "slug": "blog"},
        {"url": "events/", "label": "Events", "slug": "events"},
    ]
    nav += [
        {"url": page.url, "label": page.nav, "slug": page.slug}
        for page in pages if not page.nav_hidden
    ]
    stats = load_stats()
    if stats is not None:
        # Published beside the page so the browser can refresh the figures without
        # waiting for the next rebuild. Safe by construction rather than by care:
        # server_status.py writes counts, a version and a timestamp and nothing
        # else - no address, no player names, no MOTD - and a test pins that.
        (DIST_DIR / "assets" / "stats.json").write_text(
            json.dumps(stats, indent=2) + "\n", encoding="utf-8"
        )

    for page in pages:
        prefix = "../"
        md = markdown.Markdown(extensions=MD_EXTENSIONS)
        out = DIST_DIR / page.slug / "index.html"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(
            theme.render_page(
                page=page,
                body_html=md.convert(page.body_md),
                prefix=prefix,
                site_url=site_url,
                nav=nav,
            ),
            encoding="utf-8",
        )

    for post in posts:
        prefix = "../"
        # Up to three other updates, newest first, skipping this one.
        related = [card_for(other, prefix) for other in posts if other is not post][:3]
        page = theme.render_post(
            post=post,
            body_html=render_body(post, prefix),
            hero=hero_url(post, prefix),
            prefix=prefix,
            site_url=site_url,
            related=related,
            nav=nav,
            current="blog",
        )
        out = DIST_DIR / post.slug / "index.html"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(page, encoding="utf-8")

    for event in events:
        prefix = "../../"
        related = [card_for(other, prefix) for other in events if other is not event][:3]
        page = theme.render_post(
            post=event,
            body_html=render_body(event, prefix),
            hero=hero_url(event, prefix),
            prefix=prefix,
            site_url=site_url,
            related=related,
            nav=nav,
            current="events",
        )
        out = DIST_DIR / "events" / event.slug / "index.html"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(page, encoding="utf-8")

    cards = [card_for(post, "") for post in posts]
    (DIST_DIR / "index.html").write_text(
        theme.render_index(
            featured=cards[0] if cards else None,
            cards=cards[1:],
            prefix="",
            site_url=site_url,
            nav=nav,
            stats=stats,
        ),
        encoding="utf-8",
    )
    (DIST_DIR / "blog" / "index.html").parent.mkdir(parents=True, exist_ok=True)
    (DIST_DIR / "blog" / "index.html").write_text(
        theme.render_blog(
            featured=card_for(posts[0], "../") if posts else None,
            cards=[card_for(post, "../") for post in posts],
            prefix="../",
            site_url=site_url,
            nav=nav,
        ),
        encoding="utf-8",
    )
    (DIST_DIR / "events" / "index.html").parent.mkdir(parents=True, exist_ok=True)
    (DIST_DIR / "events" / "index.html").write_text(
        theme.render_events(
            featured=card_for(events[0], "../") if events else None,
            cards=[card_for(event, "../") for event in events],
            prefix="../",
            site_url=site_url,
            nav=nav,
        ),
        encoding="utf-8",
    )
    (DIST_DIR / "404.html").write_text(
        theme.render_404(prefix="/", nav=nav), encoding="utf-8"
    )
    # A tiny manifest naming the newest post, for anything that wants to know
    # when the blog changed without scraping HTML. The Minecraft plugin polls it
    # and shows the in-game NEW UPDATE banner when the slug moves, so publishing
    # a post is the single action that announces it everywhere.
    newest = next((post for post in posts if not post.event), None)
    (DIST_DIR / "latest.json").write_text(
        json.dumps(
            {
                "slug": newest.slug if newest else "",
                "title": newest.title if newest else "",
                "date": str(newest.date) if newest else "",
                "url": "%s/%s" % (site_url.rstrip("/"), newest.slug) if newest else "",
            },
            indent=2,
        ),
        encoding="utf-8",
    )

    # Tell GitHub Pages not to run Jekyll over the output.
    (DIST_DIR / ".nojekyll").write_text("", encoding="utf-8")

    return posts


def main() -> int:
    parser = argparse.ArgumentParser(description="Build the Mysterious SMP X dev blog.")
    parser.add_argument(
        "--site-url",
        default=os.environ.get("DEVBLOG_SITE_URL", config.DEFAULT_SITE_URL),
        help="Absolute base URL used for canonical and share tags.",
    )
    parser.add_argument("--drafts", action="store_true", help="Include posts marked draft.")
    parser.add_argument("--serve", action="store_true", help="Serve dist/ after building.")
    parser.add_argument("--port", type=int, default=8000)
    args = parser.parse_args()

    try:
        posts = build(args.site_url, include_drafts=args.drafts)
    except PostError as exc:
        print("error: %s" % exc, file=sys.stderr)
        return 1

    print("Built %d post(s) into %s" % (len(posts), DIST_DIR))
    for post in posts:
        print("  %s  %s" % (post.date.strftime("%Y-%m-%d"), post.title))

    if args.serve:
        import functools
        import http.server
        import socketserver

        handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(DIST_DIR))
        with socketserver.TCPServer(("127.0.0.1", args.port), handler) as httpd:
            print("\nServing http://127.0.0.1:%d  (ctrl-c to stop)" % args.port)
            try:
                httpd.serve_forever()
            except KeyboardInterrupt:
                print("\nstopped")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
