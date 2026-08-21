"""Look and feel for the dev blog: the stylesheet and the page shells.

Modelled closely on the BIG Games update-post layout — Outfit throughout, the
orange rgb(255,157,67) full-width underlined section headers, a category pill
and date above a left-aligned title, centred line-per-beat body copy with a
screenshot after every section, a sticky server card down the right, and
blurred-backdrop cards with a centred square icon on the index.

Kept apart from `build.py` so a design tweak never risks the generator.
"""

from __future__ import annotations

import html
from typing import Dict, List, Optional, Sequence

from config import (
    APPLY_URL,
    DEFAULT_CATEGORY,
    DISCORD_URL,
    SERVER_ADDRESS,
    SERVER_EDITIONS,
    SERVER_VERSION,
    SITE_NAME,
    SITE_TAGLINE,
    TWITTER_URL,
    YOUTUBE_URL,
)

FONT_URL = (
    "https://fonts.googleapis.com/css2?"
    "family=Outfit:wght@300;400;500;600;700;800;900&display=swap"
)


STYLESHEET = """
/* ===== tokens ============================================================ */
:root {
  --page-bg: #f7f8fa;
  --canvas: #eceff4;
  --surface: #ffffff;
  --surface-raised: #eff1f5;
  --ink: #1b1d21;
  --text-muted: #6b7280;
  --grey: #8b93a1;
  --orange: #ff9d43;
  --orange-deep: #ef8420;
  --blue: #3b82f6;
  --green: #34c46b;
  --line: rgba(110, 110, 110, .20);
  --shadow-rgb: 27, 29, 33;
  --card-radius: 1.875rem;
  --img-radius: 1.25rem;
  --page-max: 1240px;
  --column: 730px;
  --rail: 24px;
  --nav-h: 4.75rem;
  --nav-h-mobile: 4rem;
}

@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    --page-bg: #0e1013;
    --canvas: #14171a;
    --surface: #1a1e24;
    --surface-raised: #232830;
    --ink: #f2f4f7;
    --text-muted: #9aa3b1;
    --grey: #7b8492;
    --line: rgba(255, 255, 255, .10);
    --shadow-rgb: 0, 0, 0;
  }
}

:root[data-theme="dark"] {
  --page-bg: #0e1013;
  --canvas: #14171a;
  --surface: #1a1e24;
  --surface-raised: #232830;
  --ink: #f2f4f7;
  --text-muted: #9aa3b1;
  --grey: #7b8492;
  --line: rgba(255, 255, 255, .10);
  --shadow-rgb: 0, 0, 0;
}

/* ===== base ============================================================== */
*, *::before, *::after { box-sizing: border-box; }
html { -webkit-text-size-adjust: 100%; scroll-behavior: smooth; }

body {
  margin: 0;
  background: var(--page-bg);
  color: var(--text-muted);
  font-family: Outfit, "Segoe UI", system-ui, -apple-system, Helvetica, Arial, sans-serif;
  font-size: 17px;
  line-height: 1.55;
  -webkit-font-smoothing: antialiased;
}

img { max-width: 100%; height: auto; display: block; }
a { color: inherit; }

.shell { width: 100%; max-width: var(--page-max); margin: 0 auto; padding: 0 var(--rail); }

/* The whole content area sits on a soft vertical gradient, as on BIG Games. */
.page {
  background: linear-gradient(to bottom, var(--page-bg), var(--canvas));
  padding-bottom: 3rem;
}

/* ===== top bar =========================================================== */
.topbar {
  position: sticky; top: 0; z-index: 200;
  background: color-mix(in srgb, var(--page-bg) 78%, transparent);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
  border-bottom: 1px solid var(--line);
}
.topbar-inner {
  height: var(--nav-h-mobile);
  max-width: var(--page-max); margin: 0 auto; padding: 0 var(--rail);
  display: flex; align-items: center; gap: 1rem;
}
@media (min-width: 1024px) { .topbar-inner { height: var(--nav-h); } }

.brand { display: flex; align-items: center; gap: .625rem; text-decoration: none; flex-shrink: 0; }
.brand img { width: 2.35rem; height: 2.35rem; border-radius: .625rem; }
.brand span { font-weight: 800; font-size: 1.05rem; color: var(--ink); letter-spacing: -.02em; white-space: nowrap; }
@media (min-width: 1024px) { .brand img { width: 3rem; height: 3rem; } }

.nav-divider { width: 1px; align-self: stretch; background: var(--line); margin: 0 .5rem; display: none; }
@media (min-width: 1024px) { .nav-divider { display: block; } }

.topbar nav { display: flex; align-items: center; gap: .25rem; }
.topbar nav a {
  position: relative; text-decoration: none; font-weight: 500; font-size: 1.0625rem;
  color: var(--grey); padding: .5rem .75rem; transition: color .15s ease;
}
.topbar nav a:hover, .topbar nav a[aria-current="page"] { color: var(--ink); }
/* The orange underline that grows in on hover is the BIG Games nav signature. */
.topbar nav a::before {
  content: ""; position: absolute; left: .75rem; bottom: -.35rem; height: 2px; width: 0;
  background: var(--orange); border-radius: 100px 100px 0 0; opacity: 0;
  transition: width .2s ease, opacity .2s ease;
}
.topbar nav a:hover::before { width: calc(100% - 1.5rem); opacity: 1; }
.topbar nav a[aria-current="page"]::before { width: calc(100% - 1.5rem); opacity: 1; }

.topbar .spacer { margin-left: auto; }

.btn {
  display: inline-flex; align-items: center; justify-content: center; gap: .4375rem;
  font-weight: 700; text-decoration: none; border: 0; cursor: pointer;
  border-radius: 999px; transition: transform .2s ease, filter .2s ease, background .2s ease;
  box-shadow: 0 3px 6px 0 rgb(var(--shadow-rgb) / .15);
}
.btn:hover { transform: scale(1.05); filter: brightness(1.06); }
.btn:active { transform: scale(.96); }
.btn-orange { background: var(--orange); color: #fff; height: 3.125rem; padding: 0 1.5rem; font-size: 1.0625rem; }
.btn-sm { height: 2.5rem; padding: 0 1.125rem; font-size: .95rem; }

/* ===== post layout ======================================================= */
.post-layout { display: flex; align-items: flex-start; gap: 5rem; padding-top: 2.5rem; }
.post-main { flex: 1; min-width: 0; }

.post-topline {
  display: flex; align-items: center; justify-content: space-between;
  gap: 1rem; flex-wrap: wrap; margin-bottom: 1.25rem;
}
.pill {
  display: inline-flex; align-items: center;
  background: var(--surface-raised); color: var(--text-muted);
  font-weight: 700; font-size: .95rem;
  border-radius: 999px; padding: .4rem 1rem;
}
.post-date { font-weight: 600; color: var(--text-muted); font-size: .95rem; }

.post-head { padding-bottom: 1.5rem; }
.post-title {
  margin: 0; font-size: 2.25rem; line-height: 1.1; font-weight: 700;
  letter-spacing: -.02em; color: var(--ink); text-wrap: balance;
}
@media (min-width: 768px) { .post-title { font-size: 3rem; } }
.post-tagline {
  margin: .75rem 0 0; max-width: 48rem; font-size: 1.125rem;
  color: var(--text-muted); line-height: 1.5;
}

/* The body is centred, one beat per line — that rhythm is the whole look. */
.post-body { text-align: center; }
.post-body p { margin: 0 0 .55rem; font-size: 1.0625rem; color: var(--text-muted); }
.post-body strong { color: var(--ink); font-weight: 700; }
.post-body em, .post-body i { color: var(--grey); }

/* Section header: orange, bold, underlined the full width of the column. */
.post-body h2 {
  margin: 2.75rem 0 1rem; font-size: 2.1875rem; line-height: 1.2;
  font-weight: 700; color: var(--orange);
  text-decoration: underline; text-decoration-thickness: 2px; text-underline-offset: 6px;
}
.post-body h2:first-child { margin-top: 1rem; }

.post-body h3 {
  margin: 1.75rem 0 .75rem; font-size: 1.3125rem; font-weight: 700; color: var(--ink);
}
.post-body h4 { margin: 1.25rem 0 .5rem; font-size: 1.0625rem; font-weight: 700; color: var(--ink); }

.post-body figure.shot { margin: 1.5rem 0 1.75rem; }
.post-body figure.shot img {
  width: 100%; border-radius: var(--img-radius);
  box-shadow: 0 15px 35px rgb(var(--shadow-rgb) / .10);
}
.post-body figure.shot img + img { margin-top: .875rem; }

.post-body ul, .post-body ol { display: inline-block; text-align: left; margin: .5rem 0 1rem; padding-left: 1.35rem; }
.post-body li { margin: .2rem 0; }

.post-body hr { border: 0; height: 1px; background: var(--line); margin: 2.5rem auto; width: 60%; }

.post-body blockquote {
  margin: 1.25rem auto; max-width: 34rem; padding: 1rem 1.25rem; text-align: left;
  background: var(--surface); border: 1px solid var(--line);
  border-left: 4px solid var(--orange); border-radius: var(--img-radius);
}
.post-body blockquote p:last-child { margin-bottom: 0; }

.post-body code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .9em;
  background: var(--surface-raised); border-radius: 6px; padding: .1em .4em; color: var(--ink);
}
.post-body pre {
  text-align: left; overflow-x: auto; padding: 1rem 1.125rem;
  background: var(--surface); border: 1px solid var(--line); border-radius: var(--img-radius);
}
.post-body pre code { background: none; padding: 0; }

.post-body table { margin: 1.25rem auto; border-collapse: collapse; text-align: left; font-size: .95rem; }
.post-body .table-wrap { overflow-x: auto; }
.post-body th, .post-body td { padding: .55rem .875rem; border-bottom: 1px solid var(--line); }
.post-body th { color: var(--ink); font-weight: 700; white-space: nowrap; }

.signoff { margin: 2rem 0 0; text-align: center; font-size: 1.0625rem; color: var(--text-muted); }
.signoff strong { color: var(--ink); }

/* ===== sidebar =========================================================== */
.sidebar { display: none; position: sticky; top: calc(var(--nav-h) + 1.5rem); width: 410px; flex-shrink: 0; }
@media (min-width: 1024px) { .sidebar { display: block; } }
.sidebar-stack { display: flex; flex-direction: column; gap: 1rem; }

.server-card { background: #000; border-radius: var(--card-radius); overflow: hidden; }
.server-hero { position: relative; height: 14.5rem; }
.server-hero img { width: 100%; height: 100%; object-fit: cover; }
.server-hero .veil {
  position: absolute; inset: 0 0 auto 0; padding: .9rem 1.25rem 2rem;
  background: linear-gradient(to bottom, rgba(0,0,0,.7), rgba(0,0,0,.3), transparent);
  pointer-events: none;
}
.server-hero .veil p {
  margin: 0; color: #fff; font-weight: 700; font-size: 1.5rem;
  text-shadow: 0 2px 6px rgba(0,0,0,.6);
}
.server-stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: .5rem; padding: .75rem 1.25rem 1rem; }
.server-stats div { display: flex; flex-direction: column; align-items: center; text-align: center; }
.server-stats .k { font-size: .875rem; color: rgba(255,255,255,.6); font-weight: 500; }
.server-stats .v { font-size: 1.5rem; color: #fff; font-weight: 700; font-variant-numeric: tabular-nums; }

.btn-join {
  display: flex; align-items: center; justify-content: center; gap: .75rem;
  width: 100%; height: 5rem; border-radius: 1rem; border: 0; cursor: pointer;
  background: var(--blue); color: #fff; font-weight: 700; font-size: 1.5rem;
  font-family: inherit; text-decoration: none;
  box-shadow: 0 3px 6px 0 rgb(var(--shadow-rgb) / .15);
  transition: transform .2s ease, filter .2s ease;
}
.btn-join:hover { transform: translateY(-2px); filter: brightness(1.1); }
.btn-join:active { transform: scale(.97); }
.btn-join .addr { font-size: 1.125rem; font-weight: 600; opacity: .85; }
.btn-join.copied { background: var(--green); }

.side-row { display: grid; grid-template-columns: 1fr 1fr; gap: .75rem; }
.side-row-single { grid-template-columns: 1fr; }
.side-btn {
  display: inline-flex; align-items: center; justify-content: center; gap: .5rem;
  height: 3.125rem; border-radius: 1rem; text-decoration: none;
  font-weight: 700; font-size: 1.0625rem; color: #fff;
  box-shadow: 0 3px 6px 0 rgb(var(--shadow-rgb) / .15);
  transition: transform .2s ease, filter .2s ease;
}
.side-btn:hover { transform: translateY(-2px); filter: brightness(1.1); }
.side-btn.discord { background: #5865f2; }
.side-btn.apply { background: var(--orange); }

.side-facts {
  background: var(--surface); border: 1px solid var(--line);
  border-radius: var(--card-radius); padding: 1.125rem 1.375rem;
}
.side-facts dl { margin: 0; display: grid; grid-template-columns: auto 1fr; gap: .4rem 1rem; }
.side-facts dt { color: var(--grey); font-size: .9375rem; font-weight: 600; }
.side-facts dd { margin: 0; color: var(--ink); font-size: .9375rem; font-weight: 700; text-align: right; }

/* ===== index ============================================================= */
.masthead { padding: 3.5rem 0 2rem; text-align: center; }
.masthead img { width: 5.5rem; height: 5.5rem; margin: 0 auto 1rem; border-radius: 1.25rem; }
.masthead h1 {
  margin: 0 0 .5rem; font-size: 2.75rem; font-weight: 800;
  letter-spacing: -.03em; color: var(--ink); line-height: 1.05;
}
@media (min-width: 768px) { .masthead h1 { font-size: 3.5rem; } }
.masthead p { margin: 0 auto; max-width: 34rem; color: var(--text-muted); font-size: 1.125rem; }

.card-grid { display: grid; grid-template-columns: 1fr; gap: 1.25rem; }
@media (min-width: 640px) { .card-grid { grid-template-columns: 1fr 1fr; } }
@media (min-width: 1024px) { .card-grid { grid-template-columns: repeat(3, 1fr); } }

.card {
  display: flex; flex-direction: column; gap: .75rem;
  padding: 1rem 0 1.25rem; text-decoration: none; color: inherit;
  max-width: 480px; width: 100%; margin: 0 auto;
  transition: transform .2s ease;
}
@media (min-width: 1024px) { .card:hover { transform: translateY(-4px); } }

/* Blurred, scaled copy of the art fills the tile; a crisp square sits on top. */
.card-thumb {
  position: relative; overflow: hidden; width: 100%;
  aspect-ratio: 13 / 8; border-radius: var(--card-radius);
  background: var(--surface-raised);
  transition: box-shadow .2s ease;
}
.card:hover .card-thumb { box-shadow: 0 0 0 1px var(--orange), 0 18px 40px rgb(var(--shadow-rgb) / .16); }
.card-thumb .blur {
  position: absolute; inset: 0; width: 100%; height: 100%;
  object-fit: cover; filter: blur(12px); transform: scale(1.1);
}
.card-thumb .icon-wrap {
  position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
}
.card-thumb .icon {
  width: 50%; aspect-ratio: 1 / 1; object-fit: cover;
  border-radius: 1.25rem; transition: transform .2s ease;
}
@media (min-width: 1024px) { .card:hover .card-thumb .icon { transform: scale(1.05); } }

.card-meta { display: flex; align-items: center; justify-content: space-between; gap: .5rem; flex-wrap: wrap; padding: 0 1rem; }
.card-meta .pill { font-size: .8125rem; padding: .25rem .75rem; }
.card-meta .date { font-weight: 700; color: var(--text-muted); font-size: .875rem; letter-spacing: .02em; }
.card h3 {
  margin: 0; padding: 0 1rem; font-size: 1.375rem; font-weight: 700;
  line-height: 1.15; color: var(--ink); letter-spacing: -.01em;
}
@media (min-width: 1024px) { .card h3 { font-size: 1.75rem; } }
.card p {
  margin: 0; padding: 0 1rem; font-size: .9375rem; color: var(--grey); line-height: 1.4;
  display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3; overflow: hidden;
}

.empty {
  text-align: center; padding: 4rem 1.25rem; color: var(--grey);
  border: 1px dashed var(--line); border-radius: var(--card-radius);
}

/* ===== more-updates strip ================================================ */
.more { margin-top: 3rem; }
.more hr { border: 0; border-top: 1px solid var(--line); margin: 0 0 1.75rem; }
.more h5 { margin: 0 0 1.25rem; font-size: 1.875rem; font-weight: 700; color: var(--ink); }

/* ===== footer ============================================================ */
.site-footer { margin-top: 3rem; padding: 0 var(--rail) 3rem; }
.footer-inner { max-width: var(--page-max); margin: 0 auto; }
.footer-cols { display: flex; gap: 3rem; justify-content: space-evenly; padding: 2rem 0; }
@media (min-width: 1024px) { .footer-cols { justify-content: flex-end; gap: 5rem; } }
.footer-col h6 {
  margin: 0 0 .5rem; font-size: 1.0625rem; font-weight: 700; color: var(--ink);
}
.footer-col ul { list-style: none; margin: 0; padding: 0; }
.footer-col li { padding: .2rem 0; }
.footer-col a { color: var(--text-muted); text-decoration: none; font-weight: 500; font-size: 1.0625rem; }
.footer-col a:hover { color: var(--ink); }

.footer-base { display: flex; flex-direction: column; gap: .75rem; align-items: center; padding-top: 1.5rem; }
@media (min-width: 1024px) { .footer-base { align-items: flex-start; } }
.footer-base img.mark { width: 2.35rem; height: 2.35rem; border-radius: .625rem; }
.footer-base p { margin: 0; color: var(--text-muted); font-weight: 500; font-size: .9375rem; }
.footer-addr {
  display: inline-block; padding: .25rem .75rem; border-radius: 999px;
  background: var(--surface-raised); color: var(--ink);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .875rem;
}

/* three-state theme switch, mirroring the BIG Games footer control */
.theme-switch {
  display: inline-flex; align-items: center; height: 2.25rem;
  border: 1px solid var(--line); background: var(--surface);
  border-radius: 999px; padding: .25rem;
}
.theme-switch button {
  display: inline-flex; align-items: center; justify-content: center;
  height: 1.75rem; width: 2.25rem; border: 0; cursor: pointer; padding: 0;
  border-radius: 999px; background: transparent; color: var(--grey);
  transition: background .15s ease, color .15s ease;
}
.theme-switch button:hover { color: var(--ink); }
.theme-switch button[aria-checked="true"] { background: var(--orange); color: #fff; }
.theme-switch button:focus-visible { outline: 2px solid var(--orange); outline-offset: 2px; }

/* ===== 404 =============================================================== */
.notfound { text-align: center; padding: 6rem 0 3rem; }
.notfound h1 { margin: 0; font-size: 4.5rem; font-weight: 800; color: var(--ink); }
.notfound p { color: var(--grey); }

a:focus-visible, button:focus-visible { outline: 2px solid var(--orange); outline-offset: 3px; }

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation: none !important; transition: none !important; }
  html { scroll-behavior: auto; }
}
"""


# Painted before first render so a dark-mode visitor never sees a white flash.
THEME_BOOT = (
    "<script>(function(){try{var s=localStorage.getItem('theme');"
    "if(s==='light'||s==='dark'){document.documentElement.setAttribute('data-theme',s);}"
    "}catch(e){}})();</script>"
)

THEME_SCRIPT = """
<script>
(function () {
  var root = document.documentElement;
  var buttons = document.querySelectorAll('.theme-switch button');
  function stored() { try { return localStorage.getItem('theme') || 'system'; } catch (e) { return 'system'; } }
  function paint(choice) {
    if (choice === 'system') { root.removeAttribute('data-theme'); }
    else { root.setAttribute('data-theme', choice); }
    buttons.forEach(function (b) {
      b.setAttribute('aria-checked', String(b.dataset.theme === choice));
      b.tabIndex = b.dataset.theme === choice ? 0 : -1;
    });
  }
  buttons.forEach(function (b) {
    b.addEventListener('click', function () {
      try { localStorage.setItem('theme', b.dataset.theme); } catch (e) {}
      paint(b.dataset.theme);
    });
  });
  paint(stored());
})();
</script>
"""

COPY_SCRIPT = """
<script>
(function () {
  var btn = document.querySelector('.btn-join[data-copy]');
  if (!btn) return;
  var label = btn.querySelector('.label');
  var original = label.textContent;
  btn.addEventListener('click', function () {
    var value = btn.dataset.copy;
    var done = function () {
      btn.classList.add('copied');
      label.textContent = 'Copied';
      setTimeout(function () { btn.classList.remove('copied'); label.textContent = original; }, 1600);
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(value).then(done, function () {});
    } else {
      var f = document.createElement('textarea');
      f.value = value; document.body.appendChild(f); f.select();
      try { document.execCommand('copy'); done(); } catch (e) {}
      document.body.removeChild(f);
    }
  });
})();
</script>
"""


def _esc(text: str) -> str:
    return html.escape(text or "", quote=True)


# --- small pieces -----------------------------------------------------------

def _icon(name: str) -> str:
    paths = {
        "sun": '<circle cx="12" cy="12" r="4"></circle><path d="M12 2v2"></path>'
               '<path d="M12 20v2"></path><path d="m4.93 4.93 1.41 1.41"></path>'
               '<path d="m17.66 17.66 1.41 1.41"></path><path d="M2 12h2"></path>'
               '<path d="M20 12h2"></path><path d="m6.34 17.66-1.41 1.41"></path>'
               '<path d="m19.07 4.93-1.41 1.41"></path>',
        "monitor": '<rect width="20" height="14" x="2" y="3" rx="2"></rect>'
                   '<line x1="8" x2="16" y1="21" y2="21"></line>'
                   '<line x1="12" x2="12" y1="17" y2="21"></line>',
        "moon": '<path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"></path>',
        "copy": '<rect width="14" height="14" x="8" y="8" rx="2"></rect>'
                '<path d="M4 16V4a2 2 0 0 1 2-2h10"></path>',
    }
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24"'
        ' fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"'
        ' stroke-linejoin="round" aria-hidden="true">%s</svg>' % paths[name]
    )


def _head(title: str, description: str, prefix: str, og_image: Optional[str], url: str) -> str:
    tags = [
        '<meta charset="utf-8">',
        '<meta name="viewport" content="width=device-width, initial-scale=1">',
        "<title>%s</title>" % _esc(title),
        '<meta name="description" content="%s">' % _esc(description),
        '<meta name="robots" content="index, follow">',
        '<meta property="og:title" content="%s">' % _esc(title),
        '<meta property="og:description" content="%s">' % _esc(description),
        '<meta property="og:site_name" content="%s">' % _esc(SITE_NAME),
        '<meta name="twitter:card" content="summary_large_image">',
        '<meta name="theme-color" content="#ff9d43">',
    ]
    if url:
        tags.append('<meta property="og:url" content="%s">' % _esc(url))
        tags.append('<link rel="canonical" href="%s">' % _esc(url))
    if og_image:
        tags.append('<meta property="og:image" content="%s">' % _esc(og_image))
        tags.append('<meta name="twitter:image" content="%s">' % _esc(og_image))
    tags += [
        '<link rel="icon" href="%sassets/icon.png">' % prefix,
        '<link rel="alternate" type="application/rss+xml" title="%s" href="%sfeed.xml">'
        % (_esc(SITE_NAME), prefix),
        '<link rel="preconnect" href="https://fonts.googleapis.com">',
        '<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>',
        '<link rel="stylesheet" href="%s">' % FONT_URL,
        '<link rel="stylesheet" href="%sassets/style.css">' % prefix,
        THEME_BOOT,
    ]
    return "\n".join(tags)


def _topbar(prefix: str, current: str = "") -> str:
    def link(href: str, label: str, key: str) -> str:
        mark = ' aria-current="page"' if key == current else ""
        return '<a href="%s"%s>%s</a>' % (href, mark, label)

    cta = (
        '<a class="btn btn-orange btn-sm" href="%s" rel="noopener">Discord</a>' % _esc(DISCORD_URL)
        if DISCORD_URL
        else ""
    )
    return (
        '<header class="topbar"><div class="topbar-inner">'
        '<a class="brand" href="%sindex.html" aria-label="%s home">'
        '<img src="%sassets/icon.png" alt=""><span>%s</span></a>'
        '<span class="nav-divider"></span>'
        "<nav>%s%s</nav>"
        '<span class="spacer"></span>%s'
        "</div></header>"
        % (
            prefix, _esc(SITE_NAME), prefix, _esc(SITE_NAME),
            link("%sindex.html" % prefix, "Updates", "index"),
            link("%sfeed.xml" % prefix, "RSS", "rss"),
            cta,
        )
    )


def _sidebar(prefix: str) -> str:
    """The sticky right rail: server card, join button, links."""
    blocks: List[str] = []

    if SERVER_ADDRESS:
        facts = []
        if SERVER_EDITIONS:
            facts.append(("Editions", SERVER_EDITIONS))
        if SERVER_VERSION:
            facts.append(("Version", SERVER_VERSION))
        stats = "".join(
            '<div><span class="k">%s</span><span class="v">%s</span></div>'
            % (_esc(k), _esc(v))
            for k, v in facts
        )
        blocks.append(
            '<div class="server-card">'
            '<div class="server-hero"><img src="%sassets/banner.png" alt="">'
            '<div class="veil"><p>%s</p></div></div>'
            '<div class="server-stats">%s</div></div>'
            % (prefix, _esc(SITE_NAME), stats)
        )
        blocks.append(
            '<button type="button" class="btn-join" data-copy="%s">'
            '%s<span><span class="label">Copy IP</span><br>'
            '<span class="addr">%s</span></span></button>'
            % (_esc(SERVER_ADDRESS), _icon("copy"), _esc(SERVER_ADDRESS))
        )

    row = []
    if DISCORD_URL:
        row.append('<a class="side-btn discord" href="%s" rel="noopener">Discord</a>' % _esc(DISCORD_URL))
    if APPLY_URL:
        row.append('<a class="side-btn apply" href="%s" rel="noopener">Apply</a>' % _esc(APPLY_URL))
    if len(row) == 2:
        blocks.append('<div class="side-row">%s</div>' % "".join(row))
    elif row:
        # One link alone spans the rail rather than sitting in a half-width cell.
        blocks.append('<div class="side-row side-row-single">%s</div>' % row[0])

    if not blocks:
        return ""
    return '<aside class="sidebar"><div class="sidebar-stack">%s</div></aside>' % "".join(blocks)


def _footer(prefix: str) -> str:
    site_links = [
        ('%sindex.html' % prefix, "Updates"),
        ('%sfeed.xml' % prefix, "RSS feed"),
    ]
    connect = [(url, label) for url, label in (
        (DISCORD_URL, "Discord"),
        (YOUTUBE_URL, "YouTube"),
        (TWITTER_URL, "Twitter"),
        (APPLY_URL, "Apply"),
    ) if url]

    cols = [
        '<div class="footer-col"><h6>Site</h6><ul>%s</ul></div>'
        % "".join('<li><a href="%s">%s</a></li>' % (h, _esc(t)) for h, t in site_links)
    ]
    if connect:
        cols.append(
            '<div class="footer-col"><h6>Connect</h6><ul>%s</ul></div>'
            % "".join(
                '<li><a href="%s" rel="noopener">%s</a></li>' % (_esc(h), _esc(t))
                for h, t in connect
            )
        )

    address = (
        '<p><span class="footer-addr">%s</span></p>' % _esc(SERVER_ADDRESS)
        if SERVER_ADDRESS
        else ""
    )
    switch = (
        '<div class="theme-switch" role="radiogroup" aria-label="Theme">'
        '<button type="button" role="radio" aria-checked="false" tabindex="-1"'
        ' data-theme="light" aria-label="Light theme">%s</button>'
        '<button type="button" role="radio" aria-checked="true" tabindex="0"'
        ' data-theme="system" aria-label="System theme">%s</button>'
        '<button type="button" role="radio" aria-checked="false" tabindex="-1"'
        ' data-theme="dark" aria-label="Dark theme">%s</button>'
        "</div>" % (_icon("sun"), _icon("monitor"), _icon("moon"))
    )

    return (
        '<footer class="site-footer"><div class="footer-inner">'
        '<div class="footer-cols">%s</div>'
        '<div class="footer-base">'
        '<img class="mark" src="%sassets/icon.png" alt="">'
        "%s<p>&copy; %s</p>%s</div>"
        "</div></footer>" % ("".join(cols), prefix, address, _esc(SITE_NAME), switch)
    )


def _page(title: str, description: str, prefix: str, body: str,
          og_image: Optional[str] = None, url: str = "", current: str = "",
          scripts: str = "") -> str:
    return (
        "<!doctype html>\n"
        '<html lang="en">\n<head>\n%s\n</head>\n<body>\n%s\n%s\n%s\n%s%s\n</body>\n</html>\n'
        % (
            _head(title, description, prefix, og_image, url),
            _topbar(prefix, current),
            body,
            _footer(prefix),
            THEME_SCRIPT,
            scripts,
        )
    )


def render_card(card: Dict[str, object], prefix: str) -> str:
    hero = card.get("hero")
    icon = card.get("icon") or hero
    if hero:
        thumb = (
            '<div class="card-thumb">'
            '<img class="blur" src="%s" alt="" aria-hidden="true" loading="lazy">'
            '<div class="icon-wrap"><img class="icon" src="%s" alt="" loading="lazy"></div>'
            "</div>" % (_esc(str(hero)), _esc(str(icon)))
        )
    else:
        thumb = '<div class="card-thumb"></div>'

    return (
        '<a class="card" href="%s">%s'
        '<div class="card-meta"><span class="pill">%s</span>'
        '<span class="date">%s</span></div>'
        "<h3>%s</h3><p>%s</p></a>"
        % (
            _esc(str(card["url"])), thumb,
            _esc(str(card.get("category") or DEFAULT_CATEGORY)),
            _esc(str(card["date"])),
            _esc(str(card["title"])),
            _esc(str(card["excerpt"])),
        )
    )


def render_post(post, body_html: str, hero: Optional[str], prefix: str, site_url: str,
                related: Sequence[Dict[str, object]] = ()) -> str:
    tagline = '<p class="post-tagline">%s</p>' % _esc(post.tagline) if post.tagline else ""
    hero_block = (
        '<figure class="shot"><img src="%s" alt="%s"></figure>' % (_esc(hero), _esc(post.title))
        if hero
        else ""
    )
    signoff = (
        '<p class="signoff"><strong>%s</strong></p>' % _esc(post.signoff) if post.signoff else ""
    )

    more = ""
    if related:
        more = (
            '<section class="more"><hr><h5>More Updates</h5>'
            '<div class="card-grid">%s</div></section>'
            % "".join(render_card(c, prefix) for c in related)
        )

    body = (
        '<div class="page"><div class="shell">'
        '<div class="post-layout">'
        '<main class="post-main">'
        '<div class="post-topline"><span class="pill">%s</span>'
        '<time class="post-date" datetime="%s">%s</time></div>'
        '<div class="post-head"><h1 class="post-title">%s</h1>%s</div>'
        '<article class="post-body">%s%s</article>%s'
        "</main>%s</div>%s</div></div>"
        % (
            _esc(post.category), post.date.strftime("%Y-%m-%d"), _esc(post.display_date()),
            _esc(post.title), tagline, hero_block, body_html, signoff,
            _sidebar(prefix), more,
        )
    )

    absolute_hero = None
    if hero:
        absolute_hero = hero if hero.startswith("http") else "%s/media/%s/%s" % (
            site_url.rstrip("/"), post.slug, post.hero
        )

    description = post.tagline or "%s update notes." % SITE_NAME
    return _page(
        "%s | %s" % (post.title, SITE_NAME), description, prefix, body,
        absolute_hero, "%s/%s" % (site_url.rstrip("/"), post.url),
        scripts=COPY_SCRIPT,
    )


def render_index(cards: Sequence[Dict[str, object]], prefix: str, site_url: str) -> str:
    if cards:
        feed = '<div class="card-grid">%s</div>' % "".join(
            render_card(card, prefix) for card in cards
        )
    else:
        feed = '<div class="empty">No updates posted yet. Check back soon.</div>'

    body = (
        '<div class="page"><div class="shell">'
        '<div class="masthead"><img src="%sassets/logo.png" alt="">'
        "<h1>Dev Blog</h1><p>%s</p></div>%s"
        "</div></div>" % (prefix, _esc(SITE_TAGLINE), feed)
    )
    return _page(
        "%s Dev Blog" % SITE_NAME, SITE_TAGLINE, prefix, body, None, site_url, current="index"
    )


def render_404(prefix: str) -> str:
    body = (
        '<div class="page"><div class="shell"><div class="notfound"><h1>404</h1>'
        "<p>That update does not exist &mdash; or it has not shipped yet.</p>"
        '<a class="btn btn-orange" href="%sindex.html">Back to updates</a>'
        "</div></div></div>" % prefix
    )
    return _page("Not found | %s" % SITE_NAME, "Page not found.", prefix, body)
