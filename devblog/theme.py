"""Look and feel for the dev blog: the stylesheet and the page shells.

Kept apart from `build.py` so a design tweak never risks the generator, and so
the whole theme is one file to read when adjusting the update-post layout.
"""

from __future__ import annotations

import html
from typing import Dict, List, Optional, Sequence

from config import (
    DISCORD_URL,
    SERVER_ADDRESS,
    SITE_NAME,
    SITE_TAGLINE,
)


STYLESHEET = """
/* --- tokens ------------------------------------------------------------- */
:root {
  --bg: #eef1f5;
  --surface: #ffffff;
  --surface-2: #f7f9fb;
  --ink: #39404e;
  --body: #5c6675;
  --muted: #8b94a3;
  --accent: #f0912a;
  --accent-deep: #d97a12;
  --line: #dfe4ea;
  --shadow: 0 1px 2px rgba(28, 34, 45, .06), 0 8px 24px rgba(28, 34, 45, .07);
  --radius: 18px;
  --radius-sm: 12px;
  --column: 720px;
}

@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    --bg: #12151a;
    --surface: #1a1e25;
    --surface-2: #20252d;
    --ink: #eaeef4;
    --body: #a9b3c1;
    --muted: #7d8797;
    --accent: #ffa63f;
    --accent-deep: #ffbb6b;
    --line: #2b313a;
    --shadow: 0 1px 2px rgba(0, 0, 0, .3), 0 10px 30px rgba(0, 0, 0, .35);
  }
}

:root[data-theme="dark"] {
  --bg: #12151a;
  --surface: #1a1e25;
  --surface-2: #20252d;
  --ink: #eaeef4;
  --body: #a9b3c1;
  --muted: #7d8797;
  --accent: #ffa63f;
  --accent-deep: #ffbb6b;
  --line: #2b313a;
  --shadow: 0 1px 2px rgba(0, 0, 0, .3), 0 10px 30px rgba(0, 0, 0, .35);
}

/* --- base --------------------------------------------------------------- */
*, *::before, *::after { box-sizing: border-box; }

html { -webkit-text-size-adjust: 100%; scroll-behavior: smooth; }

body {
  margin: 0;
  background: var(--bg);
  color: var(--body);
  font-family: Nunito, "Segoe UI", system-ui, -apple-system, Helvetica, Arial, sans-serif;
  font-size: 17px;
  line-height: 1.6;
  -webkit-font-smoothing: antialiased;
}

img { max-width: 100%; height: auto; display: block; }

a { color: var(--accent-deep); }

.wrap { width: 100%; max-width: var(--column); margin: 0 auto; padding: 0 20px; }

/* --- site chrome -------------------------------------------------------- */
.topbar {
  position: sticky; top: 0; z-index: 20;
  background: color-mix(in srgb, var(--bg) 86%, transparent);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid var(--line);
}
.topbar-inner {
  max-width: 1040px; margin: 0 auto; padding: 12px 20px;
  display: flex; align-items: center; gap: 14px;
}
.brand {
  display: flex; align-items: center; gap: 10px;
  font-weight: 900; color: var(--ink); text-decoration: none;
  letter-spacing: -.02em; font-size: 17px;
}
.brand img { width: 30px; height: 30px; border-radius: 8px; }
.brand span { white-space: nowrap; }
.topbar nav { margin-left: auto; display: flex; align-items: center; gap: 6px; }
.topbar nav a {
  color: var(--body); text-decoration: none; font-weight: 700; font-size: 14px;
  padding: 7px 12px; border-radius: 999px;
}
.topbar nav a:hover { background: var(--surface); color: var(--ink); }
.topbar nav a.cta { background: var(--accent); color: #fff; }
.topbar nav a.cta:hover { background: var(--accent-deep); color: #fff; }

.site-footer {
  margin-top: 72px; padding: 32px 20px 44px;
  border-top: 1px solid var(--line); color: var(--muted);
  font-size: 14px; text-align: center;
}
.site-footer a { color: var(--muted); }
.site-footer .addr {
  display: inline-block; margin-top: 6px; padding: 4px 10px;
  background: var(--surface); border: 1px solid var(--line);
  border-radius: 999px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px; color: var(--body);
}

/* --- post page ---------------------------------------------------------- */
.post { padding: 40px 0 0; }

.post-head { margin-bottom: 26px; }
.post-title {
  margin: 0 0 10px;
  font-size: clamp(34px, 7vw, 46px);
  line-height: 1.08;
  font-weight: 900;
  letter-spacing: -.03em;
  color: var(--ink);
}
.post-tagline { margin: 0; font-size: 16px; color: var(--muted); line-height: 1.55; }
.post-meta {
  display: flex; flex-wrap: wrap; align-items: center; gap: 10px;
  margin-top: 16px; font-size: 13px; font-weight: 700; color: var(--muted);
}
.tag {
  display: inline-block; padding: 3px 10px; border-radius: 999px;
  background: var(--surface); border: 1px solid var(--line);
  color: var(--body); font-size: 12px; font-weight: 800;
  text-transform: uppercase; letter-spacing: .04em;
}

.hero { margin: 0 0 34px; }
.hero img { border-radius: var(--radius); box-shadow: var(--shadow); width: 100%; }

/* The update body is centred, line-per-beat, the way the patch notes read. */
.post-body { text-align: center; }
.post-body p { margin: 0 0 14px; color: var(--body); }
.post-body strong { color: var(--ink); font-weight: 800; }
.post-body em { color: var(--muted); }

.post-body h2 {
  margin: 46px 0 16px;
  font-size: clamp(26px, 5vw, 32px);
  font-weight: 900;
  letter-spacing: -.02em;
  color: var(--accent);
  text-decoration: underline;
  text-decoration-thickness: 3px;
  text-underline-offset: 5px;
}
.post-body h2:first-child { margin-top: 6px; }

.post-body h3 {
  margin: 34px 0 12px;
  font-size: 21px; font-weight: 900; letter-spacing: -.01em;
  color: var(--ink);
}
.post-body h4 {
  margin: 24px 0 8px;
  font-size: 17px; font-weight: 800; color: var(--ink);
}

.post-body figure.shot { margin: 24px 0 30px; }
.post-body figure.shot img {
  border-radius: var(--radius);
  box-shadow: var(--shadow);
  width: 100%;
}
.post-body figure.shot img + img { margin-top: 14px; }

.post-body ul, .post-body ol {
  display: inline-block; text-align: left; margin: 8px 0 18px; padding-left: 22px;
}
.post-body li { margin: 4px 0; }

.post-body hr {
  border: 0; height: 1px; background: var(--line); margin: 44px auto; width: 70%;
}

.post-body blockquote {
  margin: 22px 0; padding: 16px 20px;
  background: var(--surface); border: 1px solid var(--line);
  border-left: 4px solid var(--accent);
  border-radius: var(--radius-sm); text-align: left; color: var(--body);
}
.post-body blockquote p:last-child { margin-bottom: 0; }

.post-body code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .9em;
  background: var(--surface); border: 1px solid var(--line);
  border-radius: 6px; padding: 1px 6px; color: var(--ink);
}
.post-body pre {
  text-align: left; overflow-x: auto; padding: 16px 18px;
  background: var(--surface); border: 1px solid var(--line);
  border-radius: var(--radius-sm);
}
.post-body pre code { background: none; border: 0; padding: 0; }

.table-scroll, .post-body table { display: block; overflow-x: auto; }
.post-body table {
  margin: 20px auto; border-collapse: collapse; text-align: left; font-size: 15px;
}
.post-body th, .post-body td { padding: 9px 14px; border-bottom: 1px solid var(--line); }
.post-body th { color: var(--ink); font-weight: 800; white-space: nowrap; }

.post-foot {
  margin-top: 54px; padding-top: 26px; border-top: 1px solid var(--line);
  display: flex; flex-wrap: wrap; gap: 12px; justify-content: space-between;
  align-items: center; font-weight: 700; font-size: 15px;
}
.post-foot a { text-decoration: none; }
.post-foot a:hover { text-decoration: underline; }

/* --- index -------------------------------------------------------------- */
.masthead { padding: 56px 0 30px; text-align: center; }
.masthead img.logo { width: 82px; height: 82px; margin: 0 auto 18px; border-radius: 20px; }
.masthead h1 {
  margin: 0 0 10px; font-size: clamp(36px, 8vw, 52px);
  font-weight: 900; letter-spacing: -.035em; color: var(--ink); line-height: 1.05;
}
.masthead p { margin: 0 auto; max-width: 520px; color: var(--muted); font-size: 17px; }

.feed { display: grid; gap: 22px; padding-bottom: 10px; }
@media (min-width: 760px) { .feed { grid-template-columns: 1fr 1fr; } }

.card {
  display: flex; flex-direction: column;
  background: var(--surface); border: 1px solid var(--line);
  border-radius: var(--radius); overflow: hidden;
  text-decoration: none; color: inherit;
  box-shadow: var(--shadow);
  transition: transform .16s ease, border-color .16s ease;
}
.card:hover { transform: translateY(-3px); border-color: var(--accent); }
.card .thumb { aspect-ratio: 16 / 9; background: var(--surface-2); overflow: hidden; }
.card .thumb img { width: 100%; height: 100%; object-fit: cover; }
.card .card-body { padding: 18px 20px 22px; }
.card .card-date {
  font-size: 12px; font-weight: 800; text-transform: uppercase;
  letter-spacing: .05em; color: var(--accent-deep);
}
.card h2 {
  margin: 6px 0 8px; font-size: 22px; font-weight: 900;
  letter-spacing: -.02em; color: var(--ink); line-height: 1.2;
}
.card p { margin: 0; font-size: 15px; color: var(--body); line-height: 1.5; }

/* The newest update spans the grid so it reads as the headline. */
@media (min-width: 760px) {
  .feed .card.latest { grid-column: 1 / -1; }
  .feed .card.latest h2 { font-size: 30px; }
  .feed .card.latest .thumb { aspect-ratio: 21 / 9; }
}

.empty {
  text-align: center; padding: 60px 20px; color: var(--muted);
  border: 1px dashed var(--line); border-radius: var(--radius);
}

/* --- 404 ---------------------------------------------------------------- */
.notfound { text-align: center; padding: 90px 0 40px; }
.notfound h1 { font-size: 64px; margin: 0; color: var(--ink); font-weight: 900; }
.notfound p { color: var(--muted); }
.button {
  display: inline-block; margin-top: 18px; padding: 11px 22px;
  background: var(--accent); color: #fff; text-decoration: none;
  border-radius: 999px; font-weight: 800;
}
.button:hover { background: var(--accent-deep); color: #fff; }

@media (prefers-reduced-motion: reduce) {
  * { animation: none !important; transition: none !important; }
  html { scroll-behavior: auto; }
}
"""


def _esc(text: str) -> str:
    return html.escape(text or "", quote=True)


def _head(title: str, description: str, prefix: str, og_image: Optional[str], url: str) -> str:
    tags = [
        '<meta charset="utf-8">',
        '<meta name="viewport" content="width=device-width, initial-scale=1">',
        "<title>%s</title>" % _esc(title),
        '<meta name="description" content="%s">' % _esc(description),
        '<meta property="og:title" content="%s">' % _esc(title),
        '<meta property="og:description" content="%s">' % _esc(description),
        '<meta property="og:type" content="website">',
        '<meta name="twitter:card" content="summary_large_image">',
        '<meta name="theme-color" content="#f0912a">',
    ]
    if url:
        tags.append('<meta property="og:url" content="%s">' % _esc(url))
    if og_image:
        tags.append('<meta property="og:image" content="%s">' % _esc(og_image))
    tags += [
        '<link rel="icon" href="%sassets/icon.png">' % prefix,
        '<link rel="alternate" type="application/rss+xml" title="%s" href="%sfeed.xml">'
        % (_esc(SITE_NAME), prefix),
        '<link rel="preconnect" href="https://fonts.googleapis.com">',
        '<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>',
        '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?'
        'family=Nunito:wght@400;700;800;900&display=swap">',
        '<link rel="stylesheet" href="%sassets/style.css">' % prefix,
    ]
    return "\n".join(tags)


def _topbar(prefix: str) -> str:
    links = [
        '<a href="%sindex.html">Updates</a>' % prefix,
        '<a href="%sfeed.xml">RSS</a>' % prefix,
    ]
    if DISCORD_URL:
        links.append(
            '<a class="cta" href="%s" rel="noopener">Discord</a>' % _esc(DISCORD_URL)
        )
    return (
        '<header class="topbar"><div class="topbar-inner">'
        '<a class="brand" href="%sindex.html">'
        '<img src="%sassets/icon.png" alt=""><span>%s</span></a>'
        "<nav>%s</nav></div></header>"
        % (prefix, prefix, _esc(SITE_NAME), "".join(links))
    )


def _footer() -> str:
    address = (
        '<div class="addr">%s</div>' % _esc(SERVER_ADDRESS) if SERVER_ADDRESS else ""
    )
    label = "%s &middot; join us at" if SERVER_ADDRESS else "%s"
    return (
        '<footer class="site-footer"><div>%s</div>%s</footer>'
        % (label % _esc(SITE_NAME), address)
    )


def _page(title: str, description: str, prefix: str, body: str,
          og_image: Optional[str] = None, url: str = "") -> str:
    return (
        "<!doctype html>\n"
        '<html lang="en">\n<head>\n%s\n</head>\n<body>\n%s\n%s\n%s\n</body>\n</html>\n'
        % (_head(title, description, prefix, og_image, url), _topbar(prefix), body, _footer())
    )


def render_post(post, body_html: str, hero: Optional[str], prefix: str, site_url: str) -> str:
    tagline = (
        '<p class="post-tagline">%s</p>' % _esc(post.tagline) if post.tagline else ""
    )
    hero_block = (
        '<div class="hero"><img src="%s" alt="%s"></div>' % (_esc(hero), _esc(post.title))
        if hero
        else ""
    )
    tags = "".join('<span class="tag">%s</span>' % _esc(t) for t in post.tags)
    discuss = (
        '<a href="%s" rel="noopener">Discuss on Discord &rarr;</a>' % _esc(DISCORD_URL)
        if DISCORD_URL
        else ""
    )
    meta = (
        '<div class="post-meta"><time datetime="%s">%s</time>%s</div>'
        % (post.date.strftime("%Y-%m-%d"), _esc(post.display_date()), tags)
    )

    body = (
        '<main class="wrap post">'
        '<div class="post-head">'
        '<h1 class="post-title">%s</h1>%s%s'
        "</div>%s"
        '<article class="post-body">%s</article>'
        '<div class="post-foot">'
        '<a href="%sindex.html">&larr; All updates</a>%s'
        "</div></main>"
        % (
            _esc(post.title), tagline, meta, hero_block, body_html,
            prefix, discuss,
        )
    )

    absolute_hero = None
    if hero and not hero.startswith("http"):
        absolute_hero = "%s/media/%s/%s" % (site_url.rstrip("/"), post.slug, post.hero)
    elif hero:
        absolute_hero = hero

    description = post.tagline or "%s update notes." % SITE_NAME
    url = "%s/%s" % (site_url.rstrip("/"), post.url)
    return _page(
        "%s | %s" % (post.title, SITE_NAME), description, prefix, body, absolute_hero, url
    )


def render_index(cards: Sequence[Dict[str, object]], prefix: str, site_url: str) -> str:
    if cards:
        items: List[str] = []
        for index, card in enumerate(cards):
            hero = card.get("hero")
            thumb = (
                '<div class="thumb"><img src="%s" alt="" loading="lazy"></div>' % _esc(str(hero))
                if hero
                else ""
            )
            items.append(
                '<a class="card%s" href="%s">%s<div class="card-body">'
                '<div class="card-date">%s</div><h2>%s</h2><p>%s</p>'
                "</div></a>"
                % (
                    " latest" if index == 0 else "",
                    _esc(str(card["url"])),
                    thumb,
                    _esc(str(card["date"])),
                    _esc(str(card["title"])),
                    _esc(str(card["excerpt"])),
                )
            )
        feed = '<div class="feed">%s</div>' % "".join(items)
    else:
        feed = '<div class="empty">No updates posted yet. Check back soon.</div>'

    body = (
        '<main class="wrap" style="max-width:1040px">'
        '<div class="masthead">'
        '<img class="logo" src="%sassets/logo.png" alt="">'
        "<h1>Dev Blog</h1><p>%s</p></div>%s</main>"
        % (prefix, _esc(SITE_TAGLINE), feed)
    )
    return _page(
        "%s Dev Blog" % SITE_NAME, SITE_TAGLINE, prefix, body, None, site_url
    )


def render_404(prefix: str) -> str:
    body = (
        '<main class="wrap notfound"><h1>404</h1>'
        "<p>That update does not exist &mdash; or it has not shipped yet.</p>"
        '<a class="button" href="%sindex.html">Back to updates</a></main>' % prefix
    )
    return _page("Not found | %s" % SITE_NAME, "Page not found.", prefix, body)
