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
    REDDIT_URL,
    SERVER_ADDRESS,
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
  /* The page is a warm off-white and cards stay pure white, so a card reads as
     raised rather than as the same sheet with a line drawn on it. That gap was
     1.2 points of lightness before; it is 6.3 now. */
  --page-bg: #f4f0ea;
  --canvas: #e9e2d9;
  --surface: #ffffff;
  --surface-raised: #faf6f1;
  --ink: #1f1c19;
  --text-muted: #655c55;
  --grey: #736c64;
  /* Ambient brand bloom behind every page. */
  --bloom: rgba(255, 106, 0, .14);
  --bloom-2: rgba(255, 150, 40, .09);
  --orange: #ff9d43;
  --orange-deep: #ef8420;
  /* Sampled from the logo art: a red-to-amber ramp with a deep red shadow. */
  --brand-red: #f03000;
  --brand-orange: #f06000;
  --brand-amber: #f09030;
  --brand-deep: #a01000;
  --brand-ramp: linear-gradient(100deg, var(--brand-red), var(--brand-orange) 45%, var(--brand-amber));
  --blue: #3b82f6;
  --green: #34c46b;
  --line: rgba(120, 96, 72, .22);
  --shadow-rgb: 62, 42, 26;
  /* One motion vocabulary for the whole site. Buttons, cards and the nav all
     use these, so nothing feels like it came from a different page. */
  --ease: cubic-bezier(.2, .7, .3, 1);
  --ease-out: cubic-bezier(.16, 1, .3, 1);
  --dur-fast: .14s;
  --dur: .24s;
  --dur-slow: .45s;
  /* Three stops each: a tight contact shadow, a mid, and a wide ambient one.
     One blurred box never reads as height. */
  --lift-1:
    0 1px 1px rgb(var(--shadow-rgb) / .05),
    0 2px 6px rgb(var(--shadow-rgb) / .07),
    0 6px 14px rgb(var(--shadow-rgb) / .05);
  --lift-2:
    0 1px 2px rgb(var(--shadow-rgb) / .06),
    0 6px 14px rgb(var(--shadow-rgb) / .10),
    0 16px 32px rgb(var(--shadow-rgb) / .10);
  --lift-3:
    0 2px 4px rgb(var(--shadow-rgb) / .07),
    0 12px 26px rgb(var(--shadow-rgb) / .13),
    0 28px 60px rgb(var(--shadow-rgb) / .15);
  /* Art plate. Post artwork is often a transparent PNG with light ink, which
     is invisible on the light theme's near-white page. Opaque art covers this
     completely, so it costs nothing there. */
  --art-plate: #292220;
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
    --page-bg: #332d29;
    --canvas: #3b3430;
    --surface: #443b36;
    --grey: #c0b8af;
    --surface-raised: #554b44;
    --ink: #fefdfc;
    --text-muted: #ded7d1;
    --line: rgba(255, 186, 130, .20);
    --shadow-rgb: 0, 0, 0;
    --bloom: rgba(255, 106, 0, .30);
    --bloom-2: rgba(255, 150, 40, .17);
  }
}

:root[data-theme="dark"] {
  --page-bg: #332d29;
  --canvas: #3b3430;
  --surface: #443b36;
  --grey: #c0b8af;
  --surface-raised: #554b44;
  --ink: #fefdfc;
  --text-muted: #ded7d1;
  --line: rgba(255, 186, 130, .20);
  --shadow-rgb: 0, 0, 0;
  --bloom: rgba(255, 106, 0, .30);
  --bloom-2: rgba(255, 150, 40, .17);
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
  position: relative;
  background: linear-gradient(to bottom, var(--page-bg), var(--canvas));
  padding-bottom: 3rem;
}
/* Two soft brand blooms sit over the page gradient — one behind the masthead,
   one low and off to the side — so the background carries the brand instead of
   being flat neutral dark. */
.page::before {
  content: ""; position: absolute; inset: 0; pointer-events: none; z-index: 0;
  background:
    radial-gradient(120% 40rem at 50% -8rem, var(--bloom), transparent 62%),
    radial-gradient(60% 30rem at 92% 26%, var(--bloom-2), transparent 66%),
    radial-gradient(70% 34rem at 8% 78%, var(--bloom-2), transparent 68%);
}
.page > * { position: relative; z-index: 1; }

/* ===== top bar =========================================================== */
/* Brand ramp pinned above the nav — the first thing that reads as "ours". */
.brandbar { height: 3px; background: var(--brand-ramp); }

.topbar {
  position: sticky; top: 0; z-index: 200;
  background: color-mix(in srgb, var(--canvas) 84%, transparent);
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
  --nav-pad: .75rem;
  position: relative; text-decoration: none; font-weight: 500; font-size: 1.0625rem;
  color: var(--grey); padding: .5rem var(--nav-pad); white-space: nowrap;
  transition: color var(--dur) var(--ease);
}
.topbar nav a:hover, .topbar nav a[aria-current="page"] { color: var(--ink); }
/* The orange underline that grows in on hover is the BIG Games nav signature. */
.topbar nav a::before {
  content: ""; position: absolute; left: var(--nav-pad); bottom: -.35rem; height: 2px; width: 0;
  background: var(--brand-ramp); border-radius: 100px 100px 0 0; opacity: 0;
  transition: width var(--dur) var(--ease), opacity var(--dur) var(--ease);
}
.topbar nav a:hover::before { width: calc(100% - var(--nav-pad) * 2); opacity: 1; }
.topbar nav a[aria-current="page"]::before { width: calc(100% - var(--nav-pad) * 2); opacity: 1; }

.topbar .spacer { margin-left: auto; }

.btn {
  display: inline-flex; align-items: center; justify-content: center; gap: .4375rem;
  font-weight: 700; text-decoration: none; border: 0; cursor: pointer;
  border-radius: 999px; box-shadow: var(--lift-1);
  transition: transform var(--dur) var(--ease), box-shadow var(--dur) var(--ease),
              filter var(--dur) var(--ease);
}
.btn:hover { transform: translateY(-1px); box-shadow: var(--lift-2); filter: brightness(1.04); }
.btn:active { transform: translateY(0) scale(.985); box-shadow: var(--lift-1); }
.btn-orange { background: var(--brand-ramp); color: #fff; height: 3.125rem; padding: 0 1.5rem; font-size: 1.0625rem; }
.btn-sm { height: 2.5rem; padding: 0 1.125rem; font-size: .95rem; }

/* ===== post layout ======================================================= */
.post-layout { padding-top: 2rem; }
.post-main { max-width: var(--column); margin: 0 auto; }

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

.post-head { padding-bottom: 1rem; }
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
.post-body { text-align: center; overflow-wrap: anywhere; }
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

.post-body figure.shot { margin: 1.75rem 0 2rem; }
/* width:auto with both maxes means a wide screenshot fills the column while a
   square logo is capped instead of blowing up to 730x730 and eating the page.
   Nothing is ever upscaled past its natural size, so nothing goes soft. */
.post-body figure.shot img {
  width: auto; max-width: 100%; max-height: 26rem;
  margin-left: auto; margin-right: auto;
  background: var(--art-plate);
  border-radius: var(--img-radius);
  box-shadow: var(--lift-2);
}
.post-body figure.shot img + img { margin-top: .875rem; }

/* The opening image is allowed a little more height than an inline one. */
.post-body figure.shot.hero { margin-top: .5rem; }
.post-body figure.shot.hero img { max-height: 30rem; }

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

.post-body table {
  display: block; width: max-content; max-width: 100%; overflow-x: auto;
  margin: 1.25rem auto; border-collapse: collapse; text-align: left; font-size: .95rem;
}
.post-body .table-wrap { overflow-x: auto; }
.post-body th, .post-body td { padding: .55rem .875rem; border-bottom: 1px solid var(--line); }
.post-body th { color: var(--ink); font-weight: 700; white-space: nowrap; }

.signoff { margin: 2rem 0 0; text-align: center; font-size: 1.0625rem; color: var(--text-muted); }
.signoff strong { color: var(--ink); }

/* ===== landing hero ====================================================== */
.hero-band { position: relative; overflow: hidden; padding: 2.5rem var(--rail) 3rem; }
/* Three soft colour orbs behind the hero, as on the reference landing page. */
.hero-band .orb { position: absolute; width: 18rem; height: 18rem; border-radius: 50%; filter: blur(100px); pointer-events: none; }
.hero-band .orb-1 { left: -5rem; top: -5rem; background: rgba(240, 96, 0, .30); }
.hero-band .orb-2 { right: -6rem; top: 2.5rem; background: rgba(59, 130, 246, .22); }
.hero-band .orb-3 { left: 33%; bottom: -6rem; background: rgba(52, 196, 107, .16); }
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) .hero-band .orb-1 { background: rgba(240, 96, 0, .22); }
  :root:not([data-theme="light"]) .hero-band .orb-2 { background: rgba(59, 130, 246, .14); }
  :root:not([data-theme="light"]) .hero-band .orb-3 { background: rgba(52, 196, 107, .10); }
}
:root[data-theme="dark"] .hero-band .orb-1 { background: rgba(240, 96, 0, .22); }
:root[data-theme="dark"] .hero-band .orb-2 { background: rgba(59, 130, 246, .14); }
:root[data-theme="dark"] .hero-band .orb-3 { background: rgba(52, 196, 107, .10); }

.hero-inner {
  position: relative; max-width: var(--page-max); margin: 0 auto; padding: 2rem 0;
  display: flex; flex-direction: column; gap: 2.5rem;
}
@media (min-width: 1024px) {
  .hero-inner { flex-direction: row; align-items: center; justify-content: space-between; gap: 3rem; padding: 3.5rem 0; }
}

.hero-copy { position: relative; max-width: 36rem; text-align: center; margin: 0 auto; }
@media (min-width: 1024px) { .hero-copy { flex: 1; text-align: left; margin: 0; } }
/* The wordmark leads the page, at real size. */
.hero-copy .wordmark { width: auto; height: auto; max-width: min(26rem, 100%); max-height: 9rem; margin: 0 auto 1.5rem; }
@media (min-width: 1024px) {
  .hero-copy .wordmark { margin: 0 0 1.75rem; max-width: min(30rem, 100%); max-height: 10.5rem; }
}
.hero-copy h1 {
  margin: 0; font-size: 2.25rem; line-height: 1.1; font-weight: 700;
  letter-spacing: -.02em; color: var(--ink);
}
@media (min-width: 768px) { .hero-copy h1 { font-size: 3rem; } }
@media (min-width: 1024px) { .hero-copy h1 { font-size: 3.75rem; line-height: 1.05; } }
.hero-copy .lede { margin: 1rem 0 0; font-size: 1.25rem; color: var(--text-muted); }
@media (min-width: 1024px) { .hero-copy .lede { font-size: 1.4rem; } }

.hero-cta { display: flex; flex-direction: column; align-items: center; gap: .75rem; margin-top: 2rem; }
@media (min-width: 640px) { .hero-cta { flex-direction: row; justify-content: center; } }
@media (min-width: 1024px) { .hero-cta { justify-content: flex-start; } }
.cta {
  position: relative; display: inline-flex; align-items: center; justify-content: center;
  height: 3.125rem; padding: 0 1.75rem; border-radius: 999px;
  font-weight: 700; font-size: 1.0625rem; text-decoration: none;
  letter-spacing: -.005em;
  transition: transform var(--dur) var(--ease), box-shadow var(--dur) var(--ease),
              background-position var(--dur-slow) var(--ease), border-color var(--dur) var(--ease);
}
@media (min-width: 1024px) { .cta { height: 3.5rem; padding: 0 2rem; } }
.cta:active { transform: translateY(0) scale(.985); }
/* The ramp is oversized and slides on hover, which reads as depth rather than
   the button jumping at the cursor. */
.cta-primary {
  background: var(--brand-ramp); background-size: 200% 100%; background-position: 0% 50%;
  color: #fff; box-shadow: 0 4px 10px rgba(200, 60, 0, .22), 0 12px 28px rgba(200, 60, 0, .18);
}
.cta-primary:hover {
  transform: translateY(-2px); background-position: 100% 50%;
  box-shadow: 0 6px 14px rgba(200, 60, 0, .26), 0 18px 40px rgba(200, 60, 0, .24);
}
.cta-ghost {
  background: var(--surface); color: var(--ink);
  border: 1px solid var(--line); box-shadow: var(--lift-1);
}
.cta-ghost:hover {
  transform: translateY(-2px); border-color: var(--brand-orange); box-shadow: var(--lift-2);
}

/* The framed featured card on the right of the hero. */
.hero-feature { position: relative; width: 100%; }
@media (min-width: 1024px) { .hero-feature { width: 36rem; max-width: 52%; } }
.hero-feature a {
  display: block; overflow: hidden; border-radius: 2rem; padding: .75rem;
  background: var(--surface); text-decoration: none;
  border: 1px solid var(--line); box-shadow: var(--lift-2);
  transition: transform var(--dur-slow) var(--ease-out), box-shadow var(--dur-slow) var(--ease-out);
}
.hero-feature a:hover { transform: translateY(-4px); box-shadow: var(--lift-3); }
.hero-feature .frame {
  position: relative; overflow: hidden; aspect-ratio: 16 / 9;
  border-radius: 1.5rem; background: var(--art-plate);
}
.hero-feature .frame img {
  position: absolute; inset: 0; width: 100%; height: 100%;
  object-fit: contain;
  transition: transform var(--dur-slow) var(--ease-out);
}
.hero-feature a:hover .frame img { transform: scale(1.04); }
.hero-feature .veil {
  position: absolute; inset: 0 0 auto 0; padding: .9rem 1.25rem 2rem;
  background: linear-gradient(to bottom, rgba(0,0,0,.7), rgba(0,0,0,.3), transparent);
  pointer-events: none;
}
.hero-feature .veil p { margin: 0; color: #fff; font-weight: 700; font-size: 1.5rem; text-shadow: 0 2px 6px rgba(0,0,0,.6); }
.hero-feature .veil time {
  display: block; margin-top: .35rem; color: rgba(255,255,255,.82);
  font-size: .95rem; font-weight: 600; text-shadow: 0 2px 6px rgba(0,0,0,.6);
}

/* ===== blog archive ====================================================== */
.blog-head { padding: 3rem 0 1.5rem; }
.blog-head h1 {
  margin: 0; font-size: 2.5rem; font-weight: 800; letter-spacing: -.03em;
  line-height: 1.05; color: var(--ink);
}
@media (min-width: 768px) { .blog-head h1 { font-size: 3.125rem; } }
.blog-lede { margin: .75rem 0 0; font-size: 1.125rem; color: var(--text-muted); max-width: 40rem; }

.cat-tabs {
  display: flex; gap: 1.75rem; align-items: stretch;
  overflow-x: auto; scrollbar-width: none; -ms-overflow-style: none;
  border-bottom: 1px solid var(--line); margin-bottom: .5rem;
}
.cat-tabs::-webkit-scrollbar { display: none; }
.cat-tabs button {
  position: relative; flex: 0 0 auto; appearance: none; border: 0; cursor: pointer;
  background: none; font: inherit; font-weight: 700; font-size: 1.0625rem;
  color: var(--grey); padding: 0 0 .75rem; white-space: nowrap;
  display: inline-flex; align-items: center; gap: .5rem;
  transition: color var(--dur) var(--ease);
}
.cat-tabs button::after {
  content: ""; position: absolute; left: 0; bottom: -1px; height: 2px; width: 0;
  background: var(--brand-ramp); border-radius: 999px;
  transition: width var(--dur) var(--ease);
}
.cat-tabs button:hover { color: var(--ink); }
.cat-tabs button[aria-selected="true"] { color: var(--orange); }
.cat-tabs button[aria-selected="true"]::after { width: 100%; }
.cat-tabs .count {
  font-size: .75rem; font-weight: 800; line-height: 1;
  padding: .25rem .45rem; border-radius: 999px;
  background: var(--surface-raised); color: var(--grey);
}
.cat-tabs button[aria-selected="true"] .count { background: rgba(240, 96, 0, .14); color: var(--orange); }

.no-match {
  margin: 2rem auto; text-align: center; color: var(--grey);
  max-width: var(--page-max); padding: 0 var(--rail);
}

@media (max-width: 640px) {
  .blog-head { padding: 2rem 0 1rem; }
  .cat-tabs { gap: 1.25rem; }
  .cat-tabs button { font-size: .9375rem; }
}

/* ===== live server stats ================================================= */
.stats { padding: 0 var(--rail); margin-top: -.5rem; }
.stats-card {
  max-width: var(--page-max); margin: 0 auto; padding: 1.5rem 1.25rem 1.25rem;
  background: var(--surface); border: 1px solid var(--line);
  border-radius: var(--card-radius); box-shadow: var(--lift-2);
}
.stats-row {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 1rem;
}
.stat { display: flex; flex-direction: column; align-items: center; text-align: center; gap: .15rem; }
.stat-v {
  font-size: 2rem; font-weight: 800; letter-spacing: -.02em; line-height: 1;
  font-variant-numeric: tabular-nums;
  background: var(--brand-ramp); -webkit-background-clip: text;
  background-clip: text; color: transparent;
}
@media (min-width: 768px) { .stat-v { font-size: 2.75rem; } }
.stat-k {
  font-size: .8125rem; font-weight: 700; text-transform: uppercase;
  letter-spacing: .08em; color: var(--grey);
}
.stat-note {
  margin: 1rem 0 0; text-align: center; font-size: .8125rem; color: var(--grey);
}

/* ===== featured strip ==================================================== */
.eyebrow {
  margin: 0; font-weight: 700; font-size: .9375rem; text-transform: uppercase;
  letter-spacing: .14em; color: var(--brand-orange);
}
.featured { padding: 4rem var(--rail) 1rem; }
.featured-inner { max-width: 64rem; margin: 0 auto; display: flex; flex-direction: column; gap: 1.5rem; }
@media (min-width: 1024px) { .featured-inner { flex-direction: row; align-items: flex-start; gap: 3rem; } }
.featured-art { width: 100%; max-width: 28rem; margin: 0 auto; }
@media (min-width: 1024px) { .featured-art { margin: 0; flex: 0 0 28rem; } }
.featured-art .card-thumb { aspect-ratio: 4 / 3; }
.featured-art .card-thumb .icon { width: 52%; max-height: 62%; }
.featured-body { text-align: center; }
@media (min-width: 1024px) { .featured-body { text-align: left; flex: 1; } }
.featured-body h2 {
  margin: .75rem 0; font-size: 1.75rem; font-weight: 700;
  color: var(--ink); letter-spacing: -.02em; line-height: 1.15;
}
@media (min-width: 1024px) { .featured-body h2 { font-size: 3rem; } }
.featured-body .lede { margin: 0; font-size: 1.125rem; color: var(--grey); line-height: 1.4; }
.featured-meta {
  display: flex; align-items: center; gap: .75rem; flex-wrap: wrap;
  justify-content: center; margin-top: .75rem;
}
@media (min-width: 1024px) { .featured-meta { justify-content: flex-start; } }
.featured-meta .date { font-weight: 700; color: var(--text-muted); font-size: .9375rem; }
.featured-body .cta { margin-top: 1.5rem; }

/* ===== index grid ======================================================== */
.section-head { max-width: var(--page-max); margin: 0 auto; padding: 3rem var(--rail) 1.25rem; }
.section-head h2 { margin: .25rem 0 0; font-size: 1.875rem; font-weight: 700; color: var(--ink); }

.card-grid {
  display: grid; grid-template-columns: 1fr; gap: 1.25rem;
  max-width: var(--page-max); margin: 0 auto; padding: 0 var(--rail);
}
@media (min-width: 640px) { .card-grid { grid-template-columns: 1fr 1fr; } }
@media (min-width: 1024px) { .card-grid { grid-template-columns: repeat(3, 1fr); } }

.card {
  display: flex; flex-direction: column; gap: .75rem;
  padding: 1rem 0 1.25rem; text-decoration: none; color: inherit;
  max-width: 480px; width: 100%; margin: 0 auto;
  transition: transform var(--dur) var(--ease);
}
@media (min-width: 1024px) { .card:hover { transform: translateY(-6px); } }

/* Blurred, scaled copy of the art fills the tile; a crisp square sits on top. */
.card-thumb {
  position: relative; overflow: hidden; width: 100%;
  aspect-ratio: 13 / 8; border-radius: var(--card-radius);
  background: var(--art-plate); box-shadow: var(--lift-1);
  transition: box-shadow var(--dur) var(--ease);
}
.card:hover .card-thumb { box-shadow: 0 0 0 2px var(--brand-orange), 0 18px 40px rgb(var(--shadow-rgb) / .18); }
.card-thumb .blur {
  position: absolute; inset: 0; width: 100%; height: 100%;
  object-fit: cover; filter: blur(22px) saturate(1.1); transform: scale(1.18);
  opacity: .5;
  transition: transform var(--dur-slow) var(--ease-out);
}
.card:hover .card-thumb .blur { transform: scale(1.24); }
/* Warms the blurred backdrop toward the brand so every card reads as a set. */
.card-thumb::after {
  content: ""; position: absolute; inset: 0; pointer-events: none;
  background: linear-gradient(160deg, rgba(240, 48, 0, .12), rgba(240, 144, 48, .06));
}
.card-thumb .icon-wrap {
  position: absolute; inset: 0; z-index: 1;
  display: flex; align-items: center; justify-content: center;
}
.card-thumb .icon {
  /* contain, not cover: the art may be a wordmark, and cropping it to a square
     would cut the ends off the words. */
  width: 46%; height: auto; max-height: 58%; object-fit: contain;
  transition: transform var(--dur-slow) var(--ease-out);
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
  max-width: var(--page-max); margin: 0 auto;
}

/* ===== community band ==================================================== */
.community { padding: 4rem var(--rail) 0; }
.community-card {
  max-width: var(--page-max); margin: 0 auto; padding: 3rem 2rem;
  background: var(--surface); border: 1px solid var(--line);
  border-radius: 30px; text-align: center;
  box-shadow: var(--lift-3);
}
.community-card h2 { margin: 0 0 .5rem; font-size: 2rem; font-weight: 700; color: var(--ink); }
@media (min-width: 1024px) { .community-card h2 { font-size: 2.75rem; } }
.community-card p { margin: 0 0 1.75rem; font-size: 1.25rem; color: var(--text-muted); }
.community-links { display: flex; flex-wrap: wrap; gap: .75rem; justify-content: center; }
.social-btn {
  display: inline-flex; align-items: center; justify-content: center;
  height: 3.125rem; min-width: 8.5rem; padding: 0 1.5rem; border-radius: 999px;
  color: #fff; font-weight: 700; font-size: 1.0625rem; text-decoration: none;
  box-shadow: var(--lift-1);
  transition: transform var(--dur) var(--ease), box-shadow var(--dur) var(--ease),
              filter var(--dur) var(--ease);
}
.social-btn:hover { transform: translateY(-2px); box-shadow: var(--lift-2); filter: brightness(1.06); }
.social-btn:active { transform: translateY(0) scale(.985); box-shadow: var(--lift-1); }
.social-btn.discord { background: #5865f2; }
.social-btn.apply { background: var(--brand-ramp); }
.social-btn.reddit { background: #ff4500; }
.social-btn.youtube { background: #ff0000; }
.social-btn.x { background: #101215; border: 1px solid rgba(255, 255, 255, .18); }
.social-btn.ip {
  gap: .5rem; border: 0; cursor: pointer; font-family: inherit;
  background: var(--ink); color: var(--surface);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .95rem;
}
.social-btn.ip.copied { background: var(--green); color: #fff; }

/* ===== more-updates strip ================================================ */
.more { margin-top: 3rem; }
.more hr { border: 0; border-top: 1px solid var(--line); margin: 0 0 1.75rem; }
.more h5 { margin: 0 0 1.25rem; font-size: 1.875rem; font-weight: 700; color: var(--ink); }
/* This grid lives inside the narrow article column, not the full-width archive.
   Three cards here collapse into postage stamps on a desktop viewport. */
.post-main .more .card-grid { grid-template-columns: 1fr; padding: 0; }
@media (min-width: 640px) {
  .post-main .more .card-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

/* ===== footer ============================================================ */
.site-footer {
  margin-top: 0; padding: 3rem var(--rail);
  background: linear-gradient(to bottom, var(--canvas), var(--page-bg));
}
.site-footer::before {
  content: ""; display: block; height: 2px; max-width: var(--page-max);
  margin: 0 auto; background: var(--brand-ramp); opacity: .55; border-radius: 2px;
}
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
.footer-base img.mark { width: auto; height: auto; max-width: min(11rem, 100%); max-height: 2.75rem; opacity: .9; }
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
  transition: background var(--dur) var(--ease), color var(--dur) var(--ease);
}
.theme-switch button:hover { color: var(--ink); }
.theme-switch button[aria-checked="true"] { background: var(--orange); color: #fff; }
.theme-switch button:focus-visible { outline: 2px solid var(--orange); outline-offset: 2px; }

/* ===== standing pages (guide / rules / apply) ============================ */
.doc { max-width: var(--column); margin: 0 auto; padding: 2.5rem 0 1rem; }
.doc-head { padding-bottom: 1.25rem; border-bottom: 1px solid var(--line); margin-bottom: 1.75rem; }
.doc-head h1 {
  margin: 0; font-size: 2.25rem; line-height: 1.1; font-weight: 700;
  letter-spacing: -.02em; color: var(--ink);
}
@media (min-width: 768px) { .doc-head h1 { font-size: 3rem; } }
.page-tagline { margin: .75rem 0 0; font-size: 1.125rem; color: var(--text-muted); line-height: 1.5; }

.doc-body { color: var(--text-muted); overflow-wrap: anywhere; }
.doc-body h2 {
  margin: 2.5rem 0 .75rem; font-size: 1.625rem; font-weight: 700;
  color: var(--orange); letter-spacing: -.01em; line-height: 1.2;
  scroll-margin-top: calc(var(--nav-h) + 1rem);
}
.doc-body h2:first-child { margin-top: 0; }
.doc-body h3 {
  margin: 1.75rem 0 .5rem; font-size: 1.1875rem; font-weight: 700; color: var(--ink);
  scroll-margin-top: calc(var(--nav-h) + 1rem);
}
.doc-body h4 { margin: 1.25rem 0 .35rem; font-size: 1.0625rem; font-weight: 700; color: var(--ink); }
.doc-body p { margin: 0 0 .9rem; line-height: 1.6; }
.doc-body strong { color: var(--ink); font-weight: 700; }
.doc-body ul, .doc-body ol { margin: .5rem 0 1.1rem; padding-left: 1.35rem; }
.doc-body li { margin: .3rem 0; line-height: 1.55; }
.doc-body a, .post-body a {
  color: var(--orange-deep); text-decoration: none;
  background-image: linear-gradient(currentColor, currentColor);
  background-repeat: no-repeat; background-position: 0 100%; background-size: 0% 1.5px;
  transition: background-size var(--dur) var(--ease), color var(--dur) var(--ease);
}
.doc-body a:hover, .post-body a:hover { background-size: 100% 1.5px; }
.doc-body hr { border: 0; border-top: 1px solid var(--line); margin: 2rem 0; }
.doc-body code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .9em;
  background: var(--surface-raised); border-radius: 6px; padding: .1em .4em; color: var(--ink);
  overflow-wrap: anywhere;
}
.doc-body pre {
  overflow-x: auto; padding: 1rem 1.125rem; background: var(--surface);
  border: 1px solid var(--line); border-radius: var(--img-radius);
}
.doc-body pre code { background: none; padding: 0; }
.doc-body blockquote {
  margin: 1.25rem 0; padding: .875rem 1.125rem;
  background: var(--surface); border: 1px solid var(--line);
  border-left: 4px solid var(--orange); border-radius: var(--img-radius);
}
.doc-body blockquote p:last-child { margin-bottom: 0; }
.doc-body table {
  display: block; width: max-content; max-width: 100%; overflow-x: auto;
  border-collapse: collapse; margin: 1.25rem 0; font-size: .95rem;
}
.doc-body th, .doc-body td { padding: .55rem .875rem; border-bottom: 1px solid var(--line); text-align: left; }
.doc-body th { color: var(--ink); font-weight: 700; white-space: nowrap; }
.doc-body img { border-radius: var(--img-radius); margin: 1.25rem 0; }

/* ===== narrow screens ==================================================== */
@media (max-width: 900px) {
  /* Brand text, four nav items and a button do not fit a phone. The wordmark
     text goes first because the icon still identifies the site. */
  .brand span { display: none; }
  .nav-divider { display: none; }
  .topbar-inner { gap: .5rem; }
  .topbar nav {
    flex: 1 1 auto; min-width: 0; overflow-x: auto;
    scrollbar-width: none; -ms-overflow-style: none;
  }
  .topbar nav::-webkit-scrollbar { display: none; }
  .topbar nav a { --nav-pad: .55rem; font-size: .9375rem; }
}
@media (max-width: 560px) {
  /* Discord is reachable from the community band and the footer on every page. */
  .nav-cta { display: none; }
}
@media (max-width: 640px) {
  :root { --rail: 16px; }
  .post-body h2 { font-size: 1.75rem; }
  .post-body h3 { font-size: 1.1875rem; }
  .doc-head h1, .post-title { font-size: 2rem; }
  .community-card { padding: 2rem 1.25rem; border-radius: 22px; }
  .community-card h2 { font-size: 1.625rem; }
  .community-card p { font-size: 1.0625rem; }
  .social-btn { min-width: 0; flex: 1 1 auto; padding: 0 1.125rem; }
  .footer-cols { gap: 1.5rem; justify-content: space-between; }
  .hero-inner { padding: 1rem 0 0; gap: 2rem; }
  .featured { padding-top: 2.5rem; }
  .section-head { padding-top: 2rem; }
  .cta { width: 100%; }
}

/* ===== 404 =============================================================== */
.notfound { text-align: center; padding: 6rem 0 3rem; }
.notfound h1 { margin: 0; font-size: 4.5rem; font-weight: 800; color: var(--ink); }
.notfound p { color: var(--grey); }

a:focus-visible, button:focus-visible {
  outline: 2px solid var(--brand-orange); outline-offset: 3px; border-radius: 4px;
}
::selection { background: rgba(240, 96, 0, .22); color: var(--ink); }

/* Content settles in on load. Opt-in only: prefers-reduced-motion below turns
   every animation off, and nothing depends on these having run. */
@media (prefers-reduced-motion: no-preference) {
  @keyframes rise {
    from { opacity: 0; transform: translateY(12px); }
    to { opacity: 1; transform: none; }
  }
  .hero-copy > *, .hero-feature, .featured-inner > *, .doc-head, .post-head, .post-topline {
    animation: rise var(--dur-slow) var(--ease-out) both;
  }
  .hero-copy > *:nth-child(2) { animation-delay: .05s; }
  .hero-copy > *:nth-child(3) { animation-delay: .1s; }
  .hero-copy > *:nth-child(4) { animation-delay: .15s; }
  .hero-feature { animation-delay: .12s; }
  .featured-inner > *:nth-child(2) { animation-delay: .06s; }
}

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation: none !important; transition: none !important; }
  html { scroll-behavior: auto; }
}
"""


# Painted before first render, so a dark-mode visitor never sees a white flash.
# Only an explicit choice is stamped; with none, the attribute stays off and the
# prefers-color-scheme block answers, which is how the site follows the
# operating system by default.
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

FILTER_SCRIPT = """
<script>
(function () {
  var tabs = Array.prototype.slice.call(document.querySelectorAll(".cat-tabs [data-cat]"));
  var grid = document.getElementById("all-posts");
  if (!tabs.length || !grid) return;
  var cards = Array.prototype.slice.call(grid.querySelectorAll(".card"));
  var featured = document.getElementById("featured");
  var empty = document.querySelector(".no-match");

  function apply(cat) {
    var shown = 0;
    cards.forEach(function (card) {
      // In the "all" view the newest post is already called out above, so its
      // card stays hidden here rather than appearing twice.
      var show = cat === "*"
        ? card.dataset.featured !== "1"
        : card.dataset.category === cat;
      card.hidden = !show;
      if (show) shown++;
    });
    if (featured) featured.hidden = cat !== "*";
    if (empty) empty.hidden = shown > 0;
    tabs.forEach(function (t) {
      t.setAttribute("aria-selected", String(t.dataset.cat === cat));
    });
    try {
      var url = new URL(window.location);
      if (cat === "*") url.searchParams.delete("category");
      else url.searchParams.set("category", cat);
      history.replaceState(null, "", url);
    } catch (e) {}
  }

  tabs.forEach(function (t) {
    t.addEventListener("click", function () { apply(t.dataset.cat); });
  });

  // Honour ?category= so a filtered view can be linked to.
  try {
    var wanted = new URL(window.location).searchParams.get("category");
    if (wanted && tabs.some(function (t) { return t.dataset.cat === wanted; })) apply(wanted);
  } catch (e) {}
})();
</script>
"""

AGO_SCRIPT = """
<script>
(function () {
  var el = document.querySelector("[data-ago]");
  if (!el) return;
  var then = Date.parse(el.getAttribute("data-ago"));
  if (isNaN(then)) return;               // leave the absolute stamp in place
  var mins = Math.round((Date.now() - then) / 60000);
  if (mins < 0) return;                  // clock skew; the stamp is safer
  var text;
  if (mins < 1) text = "just now";
  else if (mins < 60) text = mins + (mins === 1 ? " minute ago" : " minutes ago");
  else {
    var hrs = Math.round(mins / 60);
    if (hrs < 24) text = hrs + (hrs === 1 ? " hour ago" : " hours ago");
    else { var d = Math.round(hrs / 24); text = d + (d === 1 ? " day ago" : " days ago"); }
  }
  el.textContent = text;
})();
</script>
"""

COPY_SCRIPT = """
<script>
(function () {
  var btn = document.querySelector('[data-copy]');
  if (!btn) return;
  var label = btn.querySelector('.label');
  var original = label.textContent;
  btn.addEventListener('click', function () {
    var value = btn.dataset.copy;
    var done = function () {
      btn.classList.add('copied');
      label.textContent = 'Copied!';
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
        '<link rel="preconnect" href="https://fonts.googleapis.com">',
        '<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>',
        '<link rel="stylesheet" href="%s">' % FONT_URL,
        '<link rel="stylesheet" href="%sassets/style.css">' % prefix,
        THEME_BOOT,
    ]
    return "\n".join(tags)


def _home_href(prefix: str) -> str:
    """The site root, without a trailing index.html that GitHub Pages then shows."""
    return prefix or "./"


def _nav_href(prefix: str, url: str) -> str:
    return _home_href(prefix) if not url or url == "index.html" else prefix + url


def _topbar(prefix: str, current: str = "", nav: Sequence[Dict[str, str]] = ()) -> str:
    items = "".join(
        '<a href="%s"%s>%s</a>'
        % (
            _esc(_nav_href(prefix, str(entry["url"]))),
            ' aria-current="page"' if current == entry["slug"] else "",
            _esc(str(entry["label"])),
        )
        for entry in nav
    )
    cta = (
        '<a class="btn btn-orange btn-sm nav-cta" href="%s" rel="noopener">Discord</a>'
        % _esc(DISCORD_URL)
        if DISCORD_URL
        else ""
    )
    return (
        '<header class="topbar"><div class="topbar-inner">'
        '<a class="brand" href="%s" aria-label="%s home">'
        '<img src="%sassets/icon.png" alt=""><span>%s</span></a>'
        '<span class="nav-divider"></span>'
        '<nav aria-label="Primary">%s</nav>'
        '<span class="spacer"></span>%s'
        "</div></header>"
        % (_home_href(prefix), _esc(SITE_NAME), prefix, _esc(SITE_NAME), items, cta)
    )


def _social_links() -> List["tuple[str, str, str]"]:
    """(url, label, css class) for every social that has actually been set."""
    return [
        (url, label, cls)
        for url, label, cls in (
            (DISCORD_URL, "Discord", "discord"),
            (APPLY_URL, "Join", "apply"),
            (REDDIT_URL, "Reddit", "reddit"),
            (TWITTER_URL, "X", "x"),
            (YOUTUBE_URL, "YouTube", "youtube"),
        )
        if url
    ]


def _community(prefix: str) -> str:
    """The join-us band that closes the home page, holding every social link."""
    links = _social_links()
    if not links and not SERVER_ADDRESS:
        return ""
    buttons = "".join(
        '<a class="social-btn %s" href="%s" rel="noopener">%s</a>' % (cls, _esc(url), _esc(label))
        for url, label, cls in links
    )
    if SERVER_ADDRESS:
        buttons = (
            '<button type="button" class="social-btn ip" data-copy="%s">'
            '%s<span class="label">%s</span></button>'
            % (_esc(SERVER_ADDRESS), _icon("copy"), _esc(SERVER_ADDRESS))
        ) + buttons
    return (
        '<section class="community"><div class="community-card">'
        "<h2>Join our community!</h2>"
        "<p>Get the latest updates and more.</p>"
        '<div class="community-links">%s</div>'
        "</div></section>" % buttons
    )


def _footer(prefix: str, nav: Sequence[Dict[str, str]] = ()) -> str:
    site_links = [(_nav_href(prefix, str(entry["url"])), str(entry["label"])) for entry in nav]
    connect = [(url, label) for url, label, _ in _social_links()]

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
        '<img class="mark" src="%sassets/logo.png" alt="%s">'
        "%s<p>&copy; %s</p>%s</div>"
        "</div></footer>"
        % ("".join(cols), prefix, _esc(SITE_NAME), address, _esc(SITE_NAME), switch)
    )


def _page(title: str, description: str, prefix: str, body: str,
          og_image: Optional[str] = None, url: str = "", current: str = "",
          scripts: str = "", nav: Sequence[Dict[str, str]] = ()) -> str:
    return (
        "<!doctype html>\n"
        '<html lang="en">\n<head>\n%s\n</head>\n<body>\n%s%s\n%s\n%s\n%s%s\n</body>\n</html>\n'
        % (
            _head(title, description, prefix, og_image, url),
            '<div class="brandbar"></div>',
            _topbar(prefix, current, nav),
            body,
            _footer(prefix, nav),
            THEME_SCRIPT,
            scripts,
        )
    )


def _thumb(card: Dict[str, object]) -> str:
    """Blurred backdrop with the crisp square centred on top."""
    backdrop = card.get("cover") or card.get("hero") or card.get("icon")
    icon = card.get("icon") or card.get("cover") or card.get("hero")
    if not backdrop:
        return '<div class="card-thumb"></div>'
    return (
        '<div class="card-thumb">'
        '<img class="blur" src="%s" alt="" aria-hidden="true" loading="lazy">'
        '<div class="icon-wrap"><img class="icon" src="%s" alt="" loading="lazy"></div>'
        "</div>" % (_esc(str(backdrop)), _esc(str(icon)))
    )


def _stats_band(stats: Optional[Dict[str, object]]) -> str:
    """Three figures from the live server, or nothing at all.

    Rendered at build time, so it is honest about being a snapshot: the page
    prints when it was checked rather than implying a live read.
    """
    if not stats:
        return ""
    tiles = [("Players online", str(stats["online"])), ("Server slots", str(stats["max"]))]
    if stats.get("version"):
        tiles.append(("Running", str(stats["version"])))
    cells = "".join(
        '<div class="stat"><span class="stat-v">%s</span>'
        '<span class="stat-k">%s</span></div>' % (_esc(value), _esc(key))
        for key, value in tiles
    )
    checked = _esc(str(stats.get("checked_at") or ""))
    caption = (
        '<p class="stat-note">Checked <time datetime="%s" data-ago="%s">%s</time></p>'
        % (checked, checked, checked.replace("T", " ").replace("Z", " UTC"))
        if checked
        else ""
    )
    return (
        '<section class="stats"><div class="stats-card">'
        '<div class="stats-row">%s</div>%s</div></section>' % (cells, caption)
    )


def render_card(card: Dict[str, object], prefix: str) -> str:
    thumb = _thumb(card)

    category = str(card.get("category") or DEFAULT_CATEGORY)
    return (
        '<a class="card" data-category="%s"%s href="%s">%s'
        '<div class="card-meta"><span class="pill">%s</span>'
        '<span class="date">%s</span></div>'
        "<h3>%s</h3><p>%s</p></a>"
        % (
            _esc(category),
            ' data-featured="1" hidden' if card.get("featured") else "",
            _esc(str(card["url"])), thumb,
            _esc(category),
            _esc(str(card["date"])),
            _esc(str(card["title"])),
            _esc(str(card["excerpt"])),
        )
    )


def render_post(post, body_html: str, hero: Optional[str], prefix: str, site_url: str,
                related: Sequence[Dict[str, object]] = (),
                nav: Sequence[Dict[str, str]] = (), current: str = "") -> str:
    tagline = '<p class="post-tagline">%s</p>' % _esc(post.tagline) if post.tagline else ""
    hero_block = (
        '<figure class="shot hero"><img src="%s" alt="%s"></figure>'
        % (_esc(hero), _esc(post.title))
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
        "</main></div>%s</div></div>"
        % (
            _esc(post.category), post.date.strftime("%Y-%m-%d"), _esc(post.display_date()),
            _esc(post.title), tagline, hero_block, body_html, signoff, more,
        )
    )

    share_art = post.cover or post.hero or post.icon
    absolute_art = None
    if share_art:
        if share_art.startswith(("http", "//")):
            absolute_art = share_art
        elif share_art.startswith("/"):
            absolute_art = "%s%s" % (site_url.rstrip("/"), share_art)
        else:
            absolute_art = "%s/media/%s/%s" % (site_url.rstrip("/"), post.slug, share_art)

    description = post.tagline or "%s update notes." % SITE_NAME
    return _page(
        "%s | %s" % (post.title, SITE_NAME), description, prefix, body,
        absolute_art, "%s/%s" % (site_url.rstrip("/"), post.url),
        scripts=COPY_SCRIPT, nav=nav, current=current,
    )


def _featured_strip(featured: Dict[str, object], eyebrow: str = "Latest Dev Blog") -> str:
    return (
        '<section class="featured" id="featured"><div class="featured-inner">'
        '<div class="featured-art">%s</div>'
        '<div class="featured-body">'
        '<p class="eyebrow">%s</p>'
        '<div class="featured-meta"><span class="pill">%s</span>'
        '<span class="date">%s</span></div>'
        "<h2>%s</h2>"
        '<p class="lede">%s</p>'
        '<a class="cta cta-primary" href="%s">Read More</a>'
        "</div></div></section>"
        % (
            _thumb(featured),
            _esc(eyebrow),
            _esc(str(featured.get("category") or DEFAULT_CATEGORY)),
            _esc(str(featured["date"])),
            _esc(str(featured["title"])),
            _esc(str(featured["excerpt"])),
            _esc(str(featured["url"])),
        )
    )


def render_blog(featured: Optional[Dict[str, object]], cards: Sequence[Dict[str, object]],
                prefix: str, site_url: str, nav: Sequence[Dict[str, str]] = ()) -> str:
    """Every update, with a category filter across the top.

    The grid holds every post; the newest is also called out above it and its
    card starts hidden so it does not appear twice. Filtering to a category
    hides the callout and reveals the full set for that category.
    """
    if not cards:
        body = (
            '<div class="page"><div class="shell"><div class="blog-head">'
            "<h1>Dev Blog</h1></div>"
            '<div class="empty">No updates posted yet. Check back soon.</div>'
            "</div></div>"
        )
        return _page("Dev Blog | %s" % SITE_NAME, SITE_TAGLINE, prefix, body,
                     None, "%s/blog/" % site_url.rstrip("/"), current="blog", nav=nav)

    counts: Dict[str, int] = {}
    for card in cards:
        name = str(card.get("category") or DEFAULT_CATEGORY)
        counts[name] = counts.get(name, 0) + 1

    tabs = ['<button type="button" role="tab" aria-selected="true" data-cat="*">'
            'All Posts<span class="count">%d</span></button>' % len(cards)]
    for name in sorted(counts):
        tabs.append(
            '<button type="button" role="tab" aria-selected="false" data-cat="%s">'
            '%s<span class="count">%d</span></button>'
            % (_esc(name), _esc(name), counts[name])
        )
    filter_bar = (
        '<div class="cat-tabs" role="tablist" aria-label="Filter by category">%s</div>'
        % "".join(tabs)
        if len(counts) > 1
        else ""
    )

    marked = []
    for index, card in enumerate(cards):
        entry = dict(card)
        entry["featured"] = index == 0 and featured is not None
        marked.append(entry)

    body = (
        '<div class="page"><div class="shell">'
        '<div class="blog-head"><h1>Dev Blog</h1>'
        '<p class="blog-lede">%s</p></div>%s</div>'
        "%s"
        '<div class="shell"><div class="card-grid" id="all-posts">%s</div>'
        '<p class="no-match" hidden>Nothing in that category yet.</p></div>'
        "</div>"
        % (
            _esc(SITE_TAGLINE),
            filter_bar,
            _featured_strip(featured) if featured else "",
            "".join(render_card(card, prefix) for card in marked),
        )
    )
    return _page(
        "Dev Blog | %s" % SITE_NAME, SITE_TAGLINE, prefix, body, None,
        "%s/blog/" % site_url.rstrip("/"), current="blog", nav=nav, scripts=FILTER_SCRIPT,
    )


def render_events(featured: Optional[Dict[str, object]], cards: Sequence[Dict[str, object]],
                  prefix: str, site_url: str,
                  nav: Sequence[Dict[str, str]] = ()) -> str:
    """Upcoming-event announcements, authored and published independently of updates."""
    description = "Upcoming Mysterious SMP X events, dates, rules and rewards."
    if not cards:
        body = (
            '<div class="page"><div class="shell"><div class="blog-head">'
            '<h1>Upcoming Events</h1><p class="blog-lede">%s</p></div>'
            '<div class="empty">No upcoming events announced yet. Check back soon.</div>'
            '</div></div>' % _esc(description)
        )
    else:
        marked = []
        for index, card in enumerate(cards):
            entry = dict(card)
            entry["featured"] = index == 0 and featured is not None
            marked.append(entry)
        body = (
            '<div class="page"><div class="shell"><div class="blog-head">'
            '<h1>Upcoming Events</h1><p class="blog-lede">%s</p></div></div>%s'
            '<div class="shell"><div class="card-grid">%s</div></div></div>'
            % (
                _esc(description),
                _featured_strip(featured, "Next Event") if featured else "",
                "".join(render_card(card, prefix) for card in marked),
            )
        )
    return _page(
        "Events | %s" % SITE_NAME, description, prefix, body, None,
        "%s/events/" % site_url.rstrip("/"), current="events", nav=nav,
    )


def render_index(featured: Optional[Dict[str, object]], cards: Sequence[Dict[str, object]],
                 prefix: str, site_url: str, nav: Sequence[Dict[str, str]] = (),
                 stats: Optional[Dict[str, object]] = None) -> str:
    """The landing page: hero, the newest update called out, then the archive."""
    if featured:
        hero_art = featured.get("cover") or featured.get("icon") or featured.get("hero")
        veil = (
            '<div class="veil"><p>%s</p>'
            '<time datetime="%s">%s</time></div>'
            % (
                _esc(str(featured["title"])),
                _esc(str(featured["iso"])),
                _esc(str(featured["date"])),
            )
        )
        frame = (
            '<div class="frame"><img src="%s" alt="">'
            '%s</div>'
            % (_esc(str(hero_art)), veil)
            if hero_art
            else '<div class="frame">%s</div>' % veil
        )
        hero_feature = (
            '<div class="hero-feature"><a href="%s">%s</a></div>'
            % (_esc(str(featured["url"])), frame)
        )
        read_latest = (
            '<a class="cta cta-primary" href="%s">Read Latest Update</a>'
            % _esc(str(featured["url"]))
        )
    else:
        hero_feature = ""
        read_latest = ""

    second_cta = (
        '<a class="cta cta-ghost" href="%s" rel="noopener">Join the Discord</a>' % _esc(DISCORD_URL)
        if DISCORD_URL
        else ""
    )

    hero = (
        '<section class="hero-band">'
        '<span class="orb orb-1"></span><span class="orb orb-2"></span><span class="orb orb-3"></span>'
        '<div class="hero-inner"><div class="hero-copy">'
        '<img class="wordmark" src="%sassets/logo.png" alt="%s">'
        "<h1>The latest SMP news!</h1>"
        '<p class="lede">%s</p>'
        '<div class="hero-cta">%s%s</div>'
        "</div>%s</div></section>"
        % (prefix, _esc(SITE_NAME), _esc(SITE_TAGLINE), read_latest, second_cta, hero_feature)
    )

    featured_strip = _featured_strip(featured) if featured else ""

    if cards:
        archive = (
            '<section><div class="section-head"><p class="eyebrow">Archive</p>'
            "<h2>All Updates</h2></div>"
            '<div class="card-grid">%s</div></section>'
            % "".join(render_card(card, prefix) for card in cards)
        )
    elif featured:
        archive = ""
    else:
        archive = '<div class="empty">No updates posted yet. Check back soon.</div>'

    body = '<div class="page">%s%s%s%s%s</div>' % (
        hero, _stats_band(stats), featured_strip, archive, _community(prefix)
    )
    return _page(
        "%s Dev Blog" % SITE_NAME, SITE_TAGLINE, prefix, body, None, site_url,
        current="index", scripts=COPY_SCRIPT + AGO_SCRIPT, nav=nav,
    )


def render_page(page, body_html: str, prefix: str, site_url: str,
                nav: Sequence[Dict[str, str]] = ()) -> str:
    """A guide, rules or how-to page: left-aligned prose, no dateline."""
    tagline = '<p class="page-tagline">%s</p>' % _esc(page.tagline) if page.tagline else ""
    body = (
        '<div class="page"><div class="shell"><div class="doc">'
        '<header class="doc-head"><h1>%s</h1>%s</header>'
        '<article class="doc-body">%s</article>'
        "</div></div></div>" % (_esc(page.title), tagline, body_html)
    )
    return _page(
        "%s | %s" % (page.title, SITE_NAME),
        page.tagline or "%s — %s" % (SITE_NAME, page.title),
        prefix, body, None, "%s/%s" % (site_url.rstrip("/"), page.url),
        current=page.slug, nav=nav,
    )


def render_404(prefix: str, nav: Sequence[Dict[str, str]] = ()) -> str:
    body = (
        '<div class="page"><div class="shell"><div class="notfound"><h1>404</h1>'
        "<p>That update does not exist &mdash; or it has not shipped yet.</p>"
        '<a class="btn btn-orange" href="%s">Back to updates</a>'
        "</div></div></div>" % _home_href(prefix)
    )
    return _page("Not found | %s" % SITE_NAME, "Page not found.", prefix, body, nav=nav)
