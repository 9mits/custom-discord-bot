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
/* Any author `display` beats the hidden attribute's UA rule, so a flex or grid
   component toggled with `el.hidden` stays on screen — empty, and often on top of
   everything else. Patching it per element is what let that happen twice; this makes
   `hidden` mean hidden for anything added later. */
[hidden] { display: none !important; }

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

.hero-address {
  width: fit-content; max-width: 100%; height: 2.75rem; margin: 1.35rem auto 0;
  padding: 0 1rem; display: flex; align-items: center; justify-content: center;
  gap: .65rem; color: var(--text-muted); background: var(--surface);
  border: 1px solid var(--line); border-radius: 999px; box-shadow: var(--lift-1);
  cursor: pointer; font: inherit;
  transition: transform var(--dur) var(--ease), border-color var(--dur) var(--ease),
              box-shadow var(--dur) var(--ease), color var(--dur) var(--ease);
}
@media (min-width: 1024px) { .hero-address { margin-left: 0; margin-right: 0; } }
.hero-address:hover {
  transform: translateY(-2px); color: var(--ink);
  border-color: var(--brand-orange); box-shadow: var(--lift-2);
}
.hero-address:active { transform: translateY(0) scale(.99); }
.hero-address .label {
  display: block; overflow-wrap: anywhere;
  font-size: clamp(.9rem, 3.5vw, 1rem); font-weight: 700; letter-spacing: -.005em;
}
.hero-address svg { flex: 0 0 auto; width: 1.05rem; height: 1.05rem; color: currentColor; }
.hero-address.copied { color: var(--green); border-color: var(--green); }

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
  position: relative;
  max-width: var(--page-max); margin: 0 auto; padding: 3rem 2rem;
  background: var(--surface); border: 1px solid var(--line);
  border-radius: 30px; text-align: center;
  box-shadow: var(--lift-3);
}
/* Urabe leans out of the card rather than sitting inside it: she crosses the top
   edge, and her render stops mid-torso exactly on the bottom one, so the frame's
   own line is what cuts her off. Breaking the frame is the whole effect, so
   nothing on this card may set overflow, and the card keeps a lane clear for her
   rather than letting her land on the buttons.

   Hanging her below that line was tried and is wrong: her crop then floats in
   open space with nothing to land against, and no amount of fading it out reads
   as deliberate. The line has to do the cutting. */
.community-mascot {
  display: block; width: min(9.5rem, 36vw); height: auto;
  margin: -6.5rem auto .75rem;
  pointer-events: none; -webkit-user-select: none; user-select: none;
  filter: drop-shadow(0 14px 20px rgba(0, 0, 0, .28));
}
@media (min-width: 1024px) {
  /* Clearance overhead for the part of her that leaves the card. */
  .community { padding-top: 7rem; }
  .community-card { text-align: left; padding: 3.5rem 22rem 3.5rem 6.5rem; }
  .community-card .community-links { justify-content: flex-start; }
  .community-mascot {
    position: absolute; right: 3rem; bottom: 0; margin: 0;
    width: min(21rem, 28vw);
  }
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

/* ===== live server pages ================================================= */
.live-doc { max-width: none; padding-top: 3rem; }
.live-body { overflow: visible; }
.live-body .mgx-live-page { display: grid; gap: 1.5rem; }
.live-body .mgx-live-page p { margin: 0; }
.live-leaderboard-toolbar {
  display: flex; align-items: center; justify-content: space-between; gap: 1rem;
}
.live-view-tabs {
  display: inline-flex; gap: .35rem; padding: .35rem; border: 1px solid var(--line);
  border-radius: 999px; background: var(--surface); box-shadow: var(--lift-1);
}
.live-view-tabs button {
  border: 0; border-radius: 999px; padding: .72rem 1.15rem; cursor: pointer;
  background: transparent; color: var(--grey); font: inherit; font-size: .84rem; font-weight: 800;
  transition: color var(--dur) var(--ease), background var(--dur) var(--ease), box-shadow var(--dur) var(--ease);
}
.live-view-tabs button[aria-selected="true"] { color: #fff; background: var(--brand-ramp); box-shadow: var(--lift-1); }
.live-view-panel { display: grid; gap: 1.5rem; }
.live-eyebrow {
  color: var(--brand-orange); font-size: .72rem; font-weight: 900;
  letter-spacing: .16em; text-transform: uppercase;
}
.live-status-card {
  display: inline-flex; justify-self: end; align-items: center; gap: .9rem;
  padding: .9rem 1.1rem; border: 1px solid var(--line); border-radius: 1rem;
  background: var(--surface); box-shadow: var(--lift-1);
}
.live-status-card small, .live-status-card strong { display: block; }
.live-status-card small { color: var(--grey); font-size: .66rem; font-weight: 800; letter-spacing: .1em; }
.live-status-card strong { color: var(--ink); font-size: .9rem; }
.live-pulse {
  width: .65rem; height: .65rem; border-radius: 50%; background: var(--green);
  box-shadow: 0 0 0 .35rem color-mix(in srgb, var(--green) 18%, transparent);
}
.live-panel {
  padding: clamp(1.25rem, 3vw, 2rem); border: 1px solid var(--line);
  border-radius: 1.35rem; background: var(--surface); box-shadow: var(--lift-2);
}
.live-clan-panel { background: linear-gradient(145deg, var(--surface), var(--surface-raised)); }
.live-panel-head {
  display: flex; align-items: end; justify-content: space-between;
  gap: 1.5rem; margin-bottom: 1.5rem;
}
.live-body .live-panel h2, .live-body .live-battle h2, .live-lock-card h2 {
  margin: .25rem 0 0; color: var(--ink); font-size: clamp(1.65rem, 3vw, 2.35rem);
  text-decoration: none; letter-spacing: -.025em;
}
.live-tabs, .live-category-rail { display: flex; flex-wrap: wrap; gap: .45rem; }
.live-tabs button, .live-category-rail button {
  border: 1px solid var(--line); background: var(--surface-raised); color: var(--grey);
  border-radius: 999px; padding: .55rem .8rem; font: inherit;
  font-size: .76rem; font-weight: 800; cursor: pointer;
}
.live-tabs button[aria-selected="true"], .live-category-rail button[aria-pressed="true"] {
  border-color: var(--brand-orange); background: var(--brand-ramp); color: #fff;
}
.live-tabs button { display: inline-flex; align-items: center; }
.live-board { min-width: 0; }
.live-podium {
  display: grid; grid-template-columns: repeat(3, minmax(0, 1fr));
  align-items: end; gap: .85rem; padding: .65rem 0 1rem;
}
.live-podium-card {
  --rank: var(--orange); --rank-deep: var(--orange-deep);
  position: relative; display: flex; flex-direction: column; min-width: 0;
  height: 22rem; overflow: hidden; padding: 1rem 1rem 2.1rem;
  border: 1px solid color-mix(in srgb, var(--rank) 62%, var(--line));
  border-radius: 1.15rem; background:
    radial-gradient(circle at 50% 32%, color-mix(in srgb, var(--rank) 16%, transparent), transparent 44%),
    linear-gradient(160deg, var(--surface-raised), var(--surface));
  box-shadow: var(--lift-1); transition: transform var(--dur) var(--ease), box-shadow var(--dur) var(--ease);
}
.live-podium-card:hover { transform: translateY(-3px); box-shadow: var(--lift-2); }
.live-podium-card.rank-gold {
  --rank: #f2bd3d; --rank-deep: #8e5700; order: 2; height: 24rem;
  box-shadow: var(--lift-2), 0 10px 35px color-mix(in srgb, var(--rank) 16%, transparent);
}
.live-podium-card.rank-silver { --rank: #b9c3ce; --rank-deep: #5d6874; order: 1; }
.live-podium-card.rank-bronze { --rank: #c98250; --rank-deep: #753d20; order: 3; }
.live-podium-card header { display: flex; align-items: center; justify-content: space-between; gap: .6rem; }
.live-medal {
  display: grid; place-items: center; width: 2.65rem; height: 2.65rem; flex: 0 0 auto;
  border: 1px solid color-mix(in srgb, var(--rank) 65%, #fff); border-radius: 50%;
  background: color-mix(in srgb, var(--rank) 28%, var(--surface)); color: var(--ink);
  font-size: 1.15rem; font-weight: 900;
}
.live-accolade { color: var(--ink); font-size: .62rem; font-weight: 900; letter-spacing: .12em; text-transform: uppercase; }
.live-player-art { position: relative; display: grid; place-items: center; flex: 0 0 auto; }
.live-player-art.full { height: 9.75rem; margin: .45rem 0 .2rem; }
.live-player-art.head { width: 2.75rem; height: 2.75rem; }
.live-player-art img { position: relative; z-index: 1; margin: 0; image-rendering: pixelated; }
.live-skin-render {
  width: 9.5rem; max-width: 100%; height: 9.5rem; object-fit: contain;
  object-position: center bottom; filter: drop-shadow(0 9px 8px rgb(var(--shadow-rgb) / .25));
}
/* The article image rule is more specific than a class selector. Target the img
   directly so small player renders stay square and fully visible, not rounded
   into a circle or cropped like editorial photography. */
img.live-head-render { width: 2.75rem; height: 2.75rem; border-radius: 0; object-fit: contain; }
img.live-skin-render { border-radius: 0; }
.live-podium-copy { min-width: 0; margin-top: auto; text-align: center; }
.live-podium-copy h3 { margin: 0; color: var(--ink); font-size: 1.05rem; line-height: 1.2; overflow-wrap: anywhere; }
.live-discord-name { margin-top: .18rem; color: var(--grey); font-size: .75rem; line-height: 1.25; overflow-wrap: anywhere; }
.live-value { display: block; margin-top: .55rem; color: var(--ink); font-size: .9rem; line-height: 1.2; }
.live-podium-step {
  position: absolute; left: 0; right: 0; bottom: 0; height: .75rem;
  background: linear-gradient(90deg, color-mix(in srgb, var(--rank) 78%, #fff), var(--rank));
}
.live-clan-crest {
  display: grid; place-items: center; width: 2.75rem; height: 2.75rem; flex: 0 0 auto;
}
/* Type selector on purpose: `.doc-body img` below the article styles is more
   specific than a lone class, and its 1.25rem margin drops every dashboard icon
   out of the box that centres it. */
img.live-minecraft-icon {
  display: block; width: 100%; height: 100%; margin: 0; border-radius: 0; object-fit: contain;
  image-rendering: pixelated; filter: drop-shadow(0 .35rem .35rem rgb(var(--shadow-rgb) / .28));
}
.live-clan-crest .live-minecraft-icon { width: 2.65rem; height: 2.65rem; }
.live-podium-card .live-clan-crest { width: 6.5rem; height: 6.5rem; margin: 2.1rem auto 1.6rem; }
.live-podium-card .live-clan-crest .live-minecraft-icon { width: 5.5rem; height: 5.5rem; }
.live-rank-list { display: grid; gap: .45rem; margin: 0; padding: 0; list-style: none; }
.live-rank-list:empty { display: none; }
.live-rank-row {
  display: grid; grid-template-columns: 3rem 2.75rem minmax(0, 1fr) auto;
  align-items: center; gap: .8rem; min-width: 0; padding: .65rem .85rem;
  border: 1px solid var(--line); border-radius: .85rem; background: var(--surface-raised);
}
.live-row-place { color: var(--brand-orange); font-size: 1rem; font-weight: 900; }
.live-row-player { display: grid; min-width: 0; line-height: 1.25; }
.live-row-player strong { color: var(--ink); overflow-wrap: anywhere; }
.live-row-player span { color: var(--grey); font-size: .75rem; overflow-wrap: anywhere; }
.live-row-value { color: var(--ink); font-size: .85rem; text-align: right; }
.live-event-heading { display: flex; align-items: center; gap: 1rem; }
.live-board-icon {
  display: grid; place-items: center; width: 4rem; height: 4rem; flex: 0 0 auto;
}
.live-board-icon .live-minecraft-icon { width: 3.4rem; height: 3.4rem; }
.live-panel-description { margin-top: .35rem !important; color: var(--grey); font-size: .82rem; }
.live-event-panel { background: linear-gradient(155deg, var(--surface), var(--surface-raised)); }
.live-battle {
  display: grid; grid-template-columns: minmax(15rem, .7fr) 1.3fr; gap: 2rem;
  padding: clamp(1.5rem, 4vw, 3rem); border-radius: 1.35rem;
  color: var(--text-muted); background:
    radial-gradient(circle at 0 0, color-mix(in srgb, var(--brand-orange) 14%, transparent), transparent 38%),
    linear-gradient(145deg, var(--surface), var(--surface-raised));
  border: 1px solid var(--line); box-shadow: var(--lift-2);
}
.live-body .live-battle h2 { color: var(--ink); }
.live-battle-icon { width: 3.4rem; height: 3.4rem; margin-bottom: 1.25rem; }
.live-battle-copy > p:not(.live-eyebrow) { color: var(--text-muted); margin-top: .75rem; }
.live-deadline { margin-top: 1.25rem; color: var(--orange-deep); font-size: .8rem; font-weight: 800; }
.live-battle-board { display: grid; align-content: start; gap: .55rem; }
.live-battle-row {
  display: grid; grid-template-columns: 3rem 2.75rem minmax(0, 1fr) auto; align-items: center; gap: .8rem;
  padding: .8rem 1rem; border: 1px solid var(--line);
  border-radius: .75rem; background: var(--surface);
}
.live-battle-row span:first-child { justify-self: end; color: var(--brand-orange); font-size: 1.1rem; font-weight: 900; }
.live-battle-row span:last-child { color: var(--grey); font-size: .78rem; }
.live-battle-row:nth-child(1) {
  border-color: color-mix(in srgb, #f2bd3d 68%, var(--line)); background: color-mix(in srgb, #f2bd3d 10%, var(--surface));
}
.live-battle-row:nth-child(2) {
  border-color: color-mix(in srgb, #b9c3ce 62%, var(--line)); background: color-mix(in srgb, #b9c3ce 8%, var(--surface));
}
.live-battle-row:nth-child(3) {
  border-color: color-mix(in srgb, #c98250 62%, var(--line)); background: color-mix(in srgb, #c98250 8%, var(--surface));
}
.live-battle-row:nth-child(-n+3) span:first-child { color: var(--ink); }
.live-owner-account { display: flex; justify-content: flex-end; min-height: 2.75rem; }
.btn-discord { background: #5865f2; color: #fff !important; height: 2.75rem; padding: 0 1rem; }
.live-lock-card {
  display: grid; justify-items: center; gap: .8rem; padding: 3.5rem 1.25rem;
  text-align: center; border: 1px dashed var(--line); border-radius: 1.25rem;
  background: var(--surface); box-shadow: var(--lift-1);
}
.live-lock-card img { width: 4rem; height: 4rem; margin: 0; border-radius: 1rem; }
.live-lock-card p { max-width: 38rem; color: var(--text-muted); }
.live-user-pill { display: flex; align-items: center; gap: .65rem; color: var(--grey); font-size: .82rem; }
.live-user-pill img { width: 2.25rem; height: 2.25rem; margin: 0; border-radius: 50%; }
.live-user-pill strong { display: block; color: var(--ink); }
.live-user-pill button { border: 0; padding: 0; background: none; color: var(--brand-orange); cursor: pointer; }
#owner-content { display: grid; gap: 1.5rem; }
.live-control-actions { display: flex; gap: .55rem; }
.live-control-actions input {
  min-width: 17rem; border: 1px solid var(--line); background: var(--surface-raised);
  color: var(--ink); border-radius: .7rem; padding: .65rem .85rem; font: inherit;
}
.live-secondary { height: 2.75rem; padding: 0 1rem; background: var(--surface-raised); color: var(--ink); border: 1px solid var(--line); }
.live-category-rail { margin-bottom: 1.1rem; }
.live-settings-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: .7rem; }

/* ---- Server Statistics ------------------------------------------------- */
.stat-select, .stat-range-label { font-size: .82rem; color: var(--grey); }
.stat-select {
  padding: .4rem .6rem; border: 1px solid var(--line); border-radius: .6rem;
  background: var(--surface); color: var(--ink);
}
.stat-tile-grid {
  display: grid; gap: .7rem; margin-top: .25rem;
  grid-template-columns: repeat(auto-fit, minmax(11rem, 1fr));
}
.stat-tile {
  display: flex; flex-direction: column; gap: .15rem; padding: .9rem 1rem;
  border: 1px solid var(--line); border-radius: .9rem; background: var(--surface-raised);
}
.stat-tile-name {
  font-size: .68rem; text-transform: uppercase; letter-spacing: .09em; color: var(--grey);
}
.stat-tile-value {
  font-size: 1.65rem; font-weight: 700; color: var(--ink); font-variant-numeric: tabular-nums;
}
.stat-tile-note { font-size: .72rem; color: var(--grey); }

.stat-note { margin: 0 0 1rem !important; font-size: .78rem; color: var(--grey); }
.stat-toggle-rail { display: flex; flex-wrap: wrap; gap: .4rem; margin-bottom: 1.1rem; }
.stat-toggle {
  padding: .35rem .7rem; border: 1px solid var(--line); border-radius: 999px;
  background: var(--surface); color: var(--grey); font-size: .78rem; cursor: pointer;
}
.stat-toggle.on {
  background: var(--brand-orange); border-color: var(--brand-orange); color: #fff;
}
.stat-chart-grid {
  display: grid; gap: 1rem; grid-template-columns: repeat(auto-fit, minmax(20rem, 1fr));
}
.stat-chart-card {
  margin: 0; padding: 1rem; border: 1px solid var(--line);
  border-radius: .9rem; background: var(--surface-raised);
}
.stat-chart-head {
  display: flex; align-items: baseline; justify-content: space-between;
  gap: 1rem; margin-bottom: .6rem;
}
.stat-chart-name { font-size: .8rem; color: var(--grey); }
.stat-chart-value {
  font-size: 1.15rem; font-weight: 700; color: var(--ink); font-variant-numeric: tabular-nums;
}
.stat-delta { font-size: .75rem; font-weight: 600; }
.stat-delta.up { color: var(--green); }
.stat-delta.down { color: var(--brand-red); }
.stat-delta.flat { color: var(--grey); }
.stat-chart { width: 100%; height: auto; display: block; overflow: visible; }
.stat-grid { stroke: var(--line); stroke-width: 1; }
.stat-axis { fill: var(--grey); font-size: 9px; }
.stat-line { fill: none; stroke: var(--brand-orange); stroke-width: 2; stroke-linejoin: round; }
.stat-area { fill: color-mix(in srgb, var(--brand-orange) 14%, transparent); stroke: none; }
.stat-endpoint { fill: var(--brand-orange); }
.stat-empty { color: var(--grey); font-size: .82rem; margin: .5rem 0 !important; }

.stat-heatmap-wrap, .stat-table-wrap { overflow-x: auto; }
.stat-heatmap { border-collapse: collapse; width: 100%; }
.stat-heatmap th {
  font-size: .65rem; color: var(--grey); font-weight: 500; padding: .15rem .2rem;
  text-align: center;
}
.stat-heatmap td {
  height: 1.15rem; border-radius: .2rem; border: 1px solid var(--canvas);
  background: color-mix(in srgb, var(--brand-orange) calc(var(--heat) * 100%), var(--surface-raised));
}
.stat-table { border-collapse: collapse; width: 100%; font-size: .84rem; }
.stat-table th, .stat-table td {
  padding: .45rem .6rem; border-bottom: 1px solid var(--line); text-align: left;
}
.stat-table th { font-size: .68rem; text-transform: uppercase; letter-spacing: .07em; color: var(--grey); }
.stat-table .num, .stat-table th.num { text-align: right; font-variant-numeric: tabular-nums; }
.stat-player { display: flex; align-items: center; gap: .45rem; }
.doc-body .stat-player img, .doc-body .stat-board img {
  margin: 0; border-radius: 0; object-fit: contain; image-rendering: pixelated;
}
.stat-board-grid {
  display: grid; gap: 1rem; grid-template-columns: repeat(auto-fit, minmax(15rem, 1fr));
}
.stat-board {
  padding: .9rem 1rem; border: 1px solid var(--line);
  border-radius: .9rem; background: var(--surface-raised);
}
.stat-board h3 {
  margin: 0 0 .6rem !important; font-size: .72rem; text-transform: uppercase;
  letter-spacing: .08em; color: var(--grey);
}
.stat-board ol { margin: 0 !important; padding-left: 1.1rem; }
.stat-board li {
  display: flex; align-items: center; justify-content: space-between;
  gap: .6rem; font-size: .84rem; padding: .12rem 0;
}
.stat-board li span { display: flex; align-items: center; gap: .4rem; }
.stat-board li b { font-variant-numeric: tabular-nums; }
@media (max-width: 640px) {
  .stat-chart-grid { grid-template-columns: 1fr; }
}
.live-setting-card {
  display: flex; flex-direction: column; min-height: 13rem; padding: 1rem;
  border: 1px solid var(--line); border-radius: .9rem; background: var(--surface-raised);
}
.live-setting-card header { display: flex; justify-content: space-between; gap: .75rem; }
.live-setting-card h3 { margin: 0; color: var(--ink); font-size: .92rem; }
.live-setting-card code { color: var(--grey); font-size: .64rem; overflow-wrap: anywhere; }
.live-setting-card p { margin: .65rem 0; color: var(--text-muted); font-size: .78rem; }
.live-setting-input { display: flex; gap: .4rem; margin-top: auto; }
.live-setting-input input, .live-setting-input select {
  width: 100%; min-width: 0; border: 1px solid var(--line); border-radius: .55rem;
  background: var(--surface); color: var(--ink); padding: .55rem;
}
.live-setting-input button {
  /* The field beside this is width:100%, so without flex-shrink:0 the button is the
     only thing that can give — and "Apply" breaks across two lines. Both properties
     are load-bearing: nowrap alone still lets the box shrink narrower than its text. */
  flex: 0 0 auto; white-space: nowrap;
  border: 0; border-radius: .55rem; padding: .55rem .7rem;
  background: var(--brand-ramp); color: #fff; font-weight: 800; cursor: pointer;
}
.live-setting-input button.reset { background: var(--surface); color: var(--grey); border: 1px solid var(--line); }
.live-chance { color: #8b5cf6; font: .7rem ui-monospace, monospace; }
.live-overridden { color: var(--brand-orange); font: .65rem ui-monospace, monospace; }
.live-log-list { display: grid; gap: .5rem; }
.live-log-row {
  display: grid; grid-template-columns: 7rem 1fr auto; align-items: center; gap: 1rem;
  padding: .8rem 1rem; border: 1px solid var(--line); border-radius: .7rem;
  color: var(--grey); font-size: .8rem;
}
.live-log-row strong { color: var(--ink); }
.live-log-row .ok { color: var(--green); }
.live-log-row .failed { color: #ef4444; }
.live-log-change {
  display: inline-flex; flex-wrap: wrap; align-items: baseline; gap: .4rem; margin-top: .2rem;
}
.live-log-change code { font-size: .7rem; color: var(--grey); background: none; padding: 0; }
.live-log-from, .live-log-to {
  font: 500 .78rem ui-monospace, SFMono-Regular, Menlo, monospace;
  padding: .05rem .35rem; border-radius: .3rem; background: var(--surface); border: 1px solid var(--line);
}
.live-log-from { color: var(--grey); text-decoration: line-through; }
.live-log-to { color: var(--ink); }
.live-log-arrow { color: var(--brand-orange); font-weight: 800; }
.live-empty { grid-column: 1 / -1; color: var(--grey); }

/* ---------- owner console ---------- */
/* An instrument, not an article. It gets its own document shell (theme.render_console):
   no marketing nav, no footer, no centred article column — a fixed sidebar and a work
   area that scrolls on its own, which is how every panel this stands next to is built.
   Putting it through the blog layout is what made it read as a web page with forms on it.

   The dev-blog's warm cream is right for a page you read and wrong for one you operate:
   at this density it muddies every surface boundary, and orange on everything leaves
   nothing for the controls that matter. Cool graphite here, with the brand orange spent
   only on the active section, the primary action and live state. The shared --token
   names are overridden rather than shadowed by a parallel set, so the .live-* components
   reused inside the console inherit the console's palette automatically. */
body.cx {
  --page-bg: #0b0d11;
  --canvas: #101319;
  --surface: #14181f;
  --surface-raised: #1b2029;
  --ink: #e9edf4;
  --text-muted: #97a1b2;
  --grey: #6b7688;
  --line: #212734;
  --line-strong: #2f3746;
  --green: #35d17f;
  --blue: #57a9ff;
  --amber: #f2b23e;
  --red: #ef5b47;
  --focus: 0 0 0 2px var(--page-bg), 0 0 0 4px rgba(255, 122, 26, .55);
  /* Outfit is a geometric display face: at 13px in a dense row its apertures close up
     and adjacent letters run together — that merging is why the console looked smeared.
     UI text uses the system face, which is drawn for this size. Outfit stays on the page
     title and the wordmark, so the brand still reads where there is room for it. */
  --ui: -apple-system, BlinkMacSystemFont, "SF Pro Text", "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
  --mono: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace;
  font-family: var(--ui);
  font-size: 13.5px;
  line-height: 1.5;
  letter-spacing: 0;
  background: var(--page-bg);
  color: var(--text-muted);
  height: 100dvh;
  overflow: hidden;
  -webkit-font-smoothing: antialiased;
}
@media (prefers-color-scheme: light) {
  :root:not([data-theme="dark"]) body.cx {
    --page-bg: #eef1f6;
    --canvas: #e6eaf1;
    --surface: #ffffff;
    --surface-raised: #f4f6fa;
    --ink: #0f1621;
    --text-muted: #4a5566;
    --grey: #6c7889;
    --line: #dde2ea;
    --line-strong: #c3cbd8;
    --green: #17915a;
    --blue: #1f6fd0;
    --amber: #a86b06;
    --red: #c33720;
  }
}
:root[data-theme="light"] body.cx {
  --page-bg: #eef1f6;
  --canvas: #e6eaf1;
  --surface: #ffffff;
  --surface-raised: #f4f6fa;
  --ink: #0f1621;
  --text-muted: #4a5566;
  --grey: #6c7889;
  --line: #dde2ea;
  --line-strong: #c3cbd8;
  --green: #17915a;
  --blue: #1f6fd0;
  --amber: #a86b06;
  --red: #c33720;
}
body.cx h1, body.cx h2, body.cx h3, body.cx h4 {
  color: var(--ink); font-weight: 650; letter-spacing: -.012em; margin: 0;
}
body.cx p { margin: 0; }
body.cx ::selection { background: rgba(255, 122, 26, .3); }
body.cx :focus-visible { outline: none; box-shadow: var(--focus); border-radius: .3rem; }

/* Every rung of the chain has to carry the height or the app shell collapses back into
   a document that scrolls as one piece. */
#console-root { height: 100%; display: flex; flex-direction: column; min-height: 0; }
#owner-content { flex: 1; min-height: 0; }
.con-shell { display: grid; grid-template-columns: 15.5rem minmax(0, 1fr); height: 100%; min-height: 0; }

/* ---------- sidebar ---------- */
.con-rail {
  display: flex; flex-direction: column; min-height: 0;
  background: var(--canvas); border-right: 1px solid var(--line);
}
.cx-brand {
  display: flex; align-items: center; gap: .55rem; flex: none;
  height: 3.25rem; padding: 0 .9rem; border-bottom: 1px solid var(--line);
}
.cx-brand img { width: 1.35rem; height: 1.35rem; border-radius: .3rem; }
.cx-brand b {
  font-family: "Outfit", var(--ui); font-size: .95rem; font-weight: 700;
  color: var(--ink); letter-spacing: -.02em;
}
.cx-brand small {
  margin-left: auto; font-size: .6rem; font-weight: 700; letter-spacing: .1em;
  text-transform: uppercase; color: var(--grey);
  border: 1px solid var(--line-strong); border-radius: 2rem; padding: .1rem .4rem;
}
.cx-nav { flex: 1; min-height: 0; overflow-y: auto; padding: .5rem .5rem 1rem; }
.cx-nav::-webkit-scrollbar, .con-main::-webkit-scrollbar { width: 9px; }
.cx-nav::-webkit-scrollbar-thumb, .con-main::-webkit-scrollbar-thumb {
  background: var(--line-strong); border-radius: 9px;
  border: 3px solid transparent; background-clip: content-box;
}
.cx-group {
  padding: .95rem .55rem .3rem; font-size: .625rem; font-weight: 700;
  letter-spacing: .1em; text-transform: uppercase; color: var(--grey);
}
.cx-group:first-child { padding-top: .35rem; }
.con-rail button {
  display: flex; align-items: center; gap: .5rem; width: 100%;
  padding: .38rem .55rem; border: 0; border-radius: .4rem; background: none;
  color: var(--text-muted); font: inherit; font-size: .82rem; text-align: left;
  cursor: pointer; position: relative;
}
.con-rail button:hover { background: var(--surface-raised); color: var(--ink); }
.con-rail button[aria-current="page"] { background: var(--surface-raised); color: var(--ink); font-weight: 600; }
/* The spine reads as "you are here" without spending the accent on a whole filled row. */
.con-rail button[aria-current="page"]::before {
  content: ""; position: absolute; left: -.5rem; top: .28rem; bottom: .28rem;
  width: 2px; border-radius: 0 2px 2px 0; background: var(--brand-orange);
}
.con-rail button .cx-label { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.con-count {
  flex: none; font-family: var(--mono); font-size: .68rem; font-variant-numeric: tabular-nums;
  color: var(--grey);
}
.con-rail button[aria-current="page"] .con-count { color: var(--text-muted); }
.con-dot {
  flex: none; min-width: 1.1rem; height: 1.1rem; padding: 0 .25rem; border-radius: 1rem;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--brand-orange); color: #1a0d02;
  font-size: .62rem; font-weight: 800; font-variant-numeric: tabular-nums;
}
.cx-side-foot {
  flex: none; border-top: 1px solid var(--line); padding: .55rem .7rem;
  display: flex; align-items: center; gap: .5rem;
}
.cx-side-foot .live-owner-account { flex: 1; min-width: 0; }
body.cx .theme-switch {
  display: flex; gap: 1px; flex: none; padding: 2px; border-radius: .4rem;
  background: var(--surface-raised); border: 1px solid var(--line);
}
body.cx .theme-switch button {
  display: grid; place-items: center; width: 1.4rem; height: 1.4rem;
  border: 0; border-radius: .3rem; background: none; color: var(--grey); cursor: pointer;
}
body.cx .theme-switch button svg { width: .8rem; height: .8rem; }
body.cx .theme-switch button:hover { color: var(--ink); }
body.cx .theme-switch button[aria-checked="true"] { background: var(--brand-orange); color: #180c02; }

/* ---------- work area ---------- */
.con-main { min-height: 0; overflow-y: auto; background: var(--page-bg); display: flex; flex-direction: column; }
.con-head {
  position: sticky; top: 0; z-index: 20; flex: none;
  display: flex; align-items: center; gap: 1rem;
  /* min-height, not height: a hard height clipped the title instead of letting the
     row grow, so an overflow became invisible text rather than a visible squeeze. */
  min-height: 3.25rem; padding: 0 1.5rem;
  background: color-mix(in srgb, var(--page-bg) 88%, transparent);
  backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--line);
}
.con-head h2 {
  font-family: "Outfit", var(--ui); font-size: 1.05rem; font-weight: 700;
  /* The title truncates rather than wrapping: a flex item with no basis will happily
     shrink to one character per line and disappear behind whatever crowded it. */
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-width: 0;
}
.con-head .cx-crumb { font-size: .78rem; color: var(--grey); white-space: nowrap; flex: none; }
.con-head .cx-crumb::after { content: "/"; margin: 0 .45rem; color: var(--line-strong); }
.con-head-title {
  /* No overflow:hidden here — it makes this a separate paint context, which stops it
     inheriting the header's backdrop blur and leaves a darker rectangle behind the
     title. The h2 truncates itself, so the container does not need to. */
  display: flex; align-items: baseline; gap: 0;
  /* A floor, not min-width: 0. Which page you are on is the header's whole job, so the
     search box gives up room first — with no floor the title shrank to nothing and the
     header read as empty. */
  flex: 1 1 auto; min-width: 8rem;
}
.cx-top-right {
  margin-left: auto; display: flex; align-items: center; gap: .6rem;
  flex: 0 1 auto; min-width: 0;
}
.cx-search { position: relative; display: flex; align-items: center; }
.cx-search kbd {
  position: absolute; right: .4rem; pointer-events: none;
  font-family: var(--mono); font-size: .68rem; color: var(--grey);
  border: 1px solid var(--line-strong); border-radius: .25rem;
  padding: 0 .3rem; line-height: 1.3; background: var(--surface-raised);
}
.cx-search:focus-within kbd { display: none; }
#con-search {
  /* Shrinkable: at 19rem fixed it pushed the page title out of the header entirely. */
  width: 19rem; min-width: 6rem; max-width: 100%;
  height: 1.9rem; padding: 0 1.7rem 0 .6rem;
  border: 1px solid var(--line); border-radius: .4rem;
  background: var(--surface); color: var(--ink); font: inherit; font-size: .8rem;
}
#con-search::placeholder { color: var(--grey); }
#con-search:focus { border-color: var(--line-strong); }
.cx-status {
  display: inline-flex; align-items: center; gap: .35rem; flex: none;
  font-size: .72rem; font-weight: 600; color: var(--text-muted);
  padding: .2rem .5rem; border: 1px solid var(--line); border-radius: 2rem;
}
.cx-status::before {
  content: ""; width: .45rem; height: .45rem; border-radius: 50%; background: var(--green);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--green) 22%, transparent);
}
.cx-status.off { color: var(--grey); }
.cx-status.off::before { background: var(--grey); box-shadow: none; }
#con-page { flex: 1; padding: 1.5rem 1.5rem 5rem; max-width: 78rem; width: 100%; }
.con-offline {
  display: block; margin-bottom: 1.5rem; padding: .65rem .8rem; font-size: .8rem;
  border: 1px solid color-mix(in srgb, var(--amber) 45%, transparent);
  border-left-width: 3px; border-radius: .4rem;
  background: color-mix(in srgb, var(--amber) 10%, transparent); color: var(--text-muted);
}
.con-offline strong { color: var(--amber); }
.con-intro { max-width: 52rem; margin-bottom: 1.75rem; color: var(--text-muted); font-size: .84rem; }
.con-intro strong { color: var(--ink); font-weight: 600; }

/* ---------- sections ---------- */
/* .con-section wraps a heading and its content; the heading carries the rule. */
.con-section { margin: 0 0 2.25rem; }
.con-intro + .con-section, .con-table + .con-section { margin-top: .5rem; }
.con-section > h3 {
  display: flex; align-items: baseline; gap: .5rem;
  font-size: .82rem; font-weight: 650;
  padding-bottom: .5rem; margin-bottom: .25rem; border-bottom: 1px solid var(--line);
}
.con-section-count {
  font-family: var(--mono); font-size: .7rem; color: var(--grey);
  font-variant-numeric: tabular-nums; font-weight: 500;
}
.con-help.wide { max-width: 52rem; margin: .6rem 0 1rem; }
.con-section > .con-help.wide { margin-top: .75rem; }

/* ---------- a setting, as a list row ---------- */
/* Cards floating in a grid is what made 516 values look like a toy. A settings row is a
   label and its control on one line, hairline dividers between, controls aligned down a
   single column — the shape of every settings screen a person has already learned. */
.con-grid { display: flex; flex-direction: column; }
.con-setting {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(12rem, 19rem);
  column-gap: 2.5rem; align-items: start;
  padding: .85rem .9rem; border-bottom: 1px solid var(--line);
  border-left: 2px solid transparent; position: relative;
}
.con-setting:hover { background: color-mix(in srgb, var(--surface) 55%, transparent); }
.con-setting > * { grid-column: 1; min-width: 0; }
/* controlFor() only ever returns one of these at the top level. The control spans the
   title and description rows rather than sitting in the title's alone: a two-line control
   (a text field with its character count) otherwise stretched row one and left a gap
   above the description. */
.con-setting > .con-odds, .con-setting > .con-switch, .con-setting > .con-range-fields {
  grid-column: 2; grid-row: 1 / span 2; align-self: center; justify-self: end;
}
.con-setting.dirty {
  border-left-color: var(--brand-orange);
  background: color-mix(in srgb, var(--brand-orange) 6%, transparent);
}
.con-setting.invalid {
  border-left-color: var(--red);
  background: color-mix(in srgb, var(--red) 7%, transparent);
}
.con-setting-head { display: flex; align-items: center; gap: .5rem; }
.con-setting-head h4 { font-size: .84rem; font-weight: 600; }
.con-help { font-size: .78rem; color: var(--text-muted); margin-top: .1rem; max-width: 44rem; }
.con-meaning {
  font-size: .78rem; color: var(--ink); margin-top: .35rem;
  padding-left: .55rem; border-left: 2px solid var(--blue);
}
.con-related { font-size: .74rem; color: var(--grey); margin-top: .25rem; }
.con-finding {
  grid-column: 1 / -1; margin-top: .5rem; font-size: .76rem; color: var(--red);
  background: color-mix(in srgb, var(--red) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--red) 35%, transparent);
  border-radius: .35rem; padding: .35rem .5rem;
}
/* The footer spans both columns so the key it pushes right lands on the row's real
   right edge; inside column one it stopped mid-page and read as stranded. */
.con-setting-foot {
  grid-column: 1 / -1; display: flex; align-items: center; gap: .6rem; margin-top: .5rem;
  font-size: .7rem; color: var(--grey); flex-wrap: wrap;
}
.con-flag {
  font-size: .6rem; font-weight: 800; letter-spacing: .07em; text-transform: uppercase;
  color: var(--brand-orange);
}
.con-live, .con-lag { display: inline-flex; align-items: center; gap: .3rem; font-weight: 600; }
.con-live { color: var(--green); }
.con-lag { color: var(--amber); }
.con-live::before, .con-lag::before {
  content: ""; width: .4rem; height: .4rem; border-radius: 50%; background: currentColor;
}
/* No separator glyph: the footer is a flex row with its own gap, and a CSS-escaped
   middot here rendered as replacement text in Chromium. */
.con-limits { color: var(--grey); font-variant-numeric: tabular-nums; }
.con-key {
  margin-left: auto; font-family: var(--mono); font-size: .66rem; color: var(--grey);
  background: var(--surface-raised); border-radius: .25rem; padding: .05rem .3rem;
}
.con-link {
  border: 0; background: none; padding: 0; cursor: pointer;
  font: inherit; font-size: .72rem; color: var(--blue); text-decoration: underline;
  text-underline-offset: 2px;
}
.con-link:hover { color: var(--ink); }
.con-range-fields { display: flex; align-items: flex-end; gap: .4rem; }
.con-range-fields label {
  display: flex; flex-direction: column; gap: .2rem; font-size: .64rem; color: var(--grey);
}
.con-range-fields .con-number { width: 5rem; }
.con-range-dash { font-size: .7rem; color: var(--grey); padding-bottom: .5rem; }
.con-setting .con-unit { font-size: .7rem; color: var(--grey); padding-bottom: .5rem; }

/* ---------- inputs ---------- */
.con-odds { display: flex; align-items: center; gap: .5rem; justify-content: flex-end; flex-wrap: wrap; }
.con-odds-read {
  font-size: .72rem; color: var(--grey); text-align: right;
  font-variant-numeric: tabular-nums; order: -1;
}
.con-odds-read strong { color: var(--ink); font-weight: 650; }
.con-number, .con-choice, .con-search-field {
  height: 1.9rem; padding: 0 .5rem; border: 1px solid var(--line-strong);
  border-radius: .4rem; background: var(--surface); color: var(--ink);
  font: inherit; font-size: .82rem;
}
.con-number {
  width: 6rem; flex: none; text-align: right;
  font-family: var(--mono); font-variant-numeric: tabular-nums;
}
.con-number.tight { width: 5rem; }
.con-choice { width: 10rem; flex: none; padding-right: .3rem; }
.con-search-field { width: 12rem; }
.con-number:focus, .con-choice:focus, .con-search-field:focus, .con-pct:focus {
  border-color: var(--brand-orange);
}
.con-unit { font-size: .72rem; color: var(--grey); }
/* Fields inside a stacked label take the label's width; the parent is a column, so a
   flex-basis here would set their height instead. */
.con-field { display: flex; flex-direction: column; gap: .2rem; min-width: 0; }
.con-field > span { font-size: .7rem; color: var(--grey); font-weight: 600; }
.con-field > em { font-size: .68rem; color: var(--grey); font-style: normal; }
.con-field .con-number, .con-field .con-choice, .con-field .con-search-field {
  width: 100%; text-align: left;
}
.con-field-row { display: flex; gap: .75rem; flex-wrap: wrap; }

.con-switch { display: inline-flex; align-items: center; gap: .5rem; cursor: pointer; }
.con-switch input { position: absolute; opacity: 0; width: 0; height: 0; }
.con-track {
  width: 2.1rem; height: 1.2rem; border-radius: 1rem; background: var(--line-strong);
  position: relative; flex: none; transition: background .15s ease;
}
.con-track::after {
  content: ""; position: absolute; top: .15rem; left: .15rem;
  width: .9rem; height: .9rem; border-radius: 50%; background: #fff;
  transition: transform .15s ease;
}
.con-switch input:checked + .con-track { background: var(--brand-orange); }
.con-switch input:checked + .con-track::after { transform: translateX(.9rem); }
.con-switch input:focus-visible + .con-track { box-shadow: var(--focus); }
.con-switch-text { font-size: .78rem; color: var(--text-muted); font-weight: 600; min-width: 4rem; }
/* The swatch leads the select: the colour is the value, the name only labels it. */
.con-swatch {
  width: 1.05rem; height: 1.05rem; border-radius: .25rem; order: -2;
  border: 1px solid var(--line-strong); flex: none; background: var(--surface-raised);
}
.con-swatch.tone-pink { background: #f472b6; }
.con-swatch.tone-blue { background: #3b82f6; }
.con-swatch.tone-red { background: #ef4444; }
.con-swatch.tone-green { background: #34c46b; }
.con-swatch.tone-yellow { background: #eab308; }
.con-swatch.tone-purple { background: #8b5cf6; }
.con-swatch.tone-white { background: #f8fafc; }

/* ---------- buttons ---------- */
.con-primary, .con-secondary, .con-danger {
  display: inline-flex; align-items: center; justify-content: center; gap: .35rem;
  min-height: 1.95rem; padding: .25rem .75rem; border-radius: .4rem;
  font: inherit; font-size: .8rem; font-weight: 600; cursor: pointer;
  white-space: normal; text-align: center; line-height: 1.25; max-width: 100%;
}
.con-primary { border: 1px solid var(--brand-orange); background: var(--brand-orange); color: #180c02; }
.con-primary:hover:not(:disabled) { filter: brightness(1.08); }
.con-secondary { border: 1px solid var(--line-strong); background: var(--surface); color: var(--ink); }
.con-secondary:hover { background: var(--surface-raised); }
.con-danger {
  border: 1px solid color-mix(in srgb, var(--red) 60%, transparent);
  background: color-mix(in srgb, var(--red) 14%, transparent); color: var(--red);
}
.con-danger:hover { background: var(--red); color: #fff; border-color: var(--red); }
.con-primary:disabled, .con-secondary:disabled, .con-danger:disabled { opacity: .45; cursor: not-allowed; }
.con-remove, .con-move {
  border: 1px solid var(--line-strong); background: var(--surface); color: var(--text-muted);
  border-radius: .3rem; padding: 0 .35rem; font: inherit; font-size: .74rem; cursor: pointer;
  line-height: 1.5;
}
.con-remove:hover { border-color: var(--red); color: var(--red); }

/* ---------- overview: stats, tasks, actions ---------- */
.con-stats { display: grid; gap: .75rem; grid-template-columns: repeat(auto-fit, minmax(13rem, 1fr)); margin-bottom: 2rem; }
.con-stat {
  border: 1px solid var(--line); border-radius: .5rem; background: var(--surface);
  padding: .85rem .95rem; display: flex; flex-direction: column; gap: .1rem; min-width: 0;
}
.con-stat > span {
  font-size: .62rem; font-weight: 700; letter-spacing: .09em; text-transform: uppercase;
  color: var(--grey);
}
.con-stat > strong {
  font-family: "Outfit", var(--ui); font-size: 1.6rem; font-weight: 700; color: var(--ink);
  font-variant-numeric: tabular-nums; line-height: 1.15;
}
.con-stat > em { font-style: normal; font-size: .73rem; color: var(--grey); }

.con-tasks { display: grid; gap: .7rem; grid-template-columns: repeat(auto-fill, minmax(15rem, 1fr)); }
.con-task {
  display: flex; flex-direction: column; gap: .3rem; min-width: 0; text-align: left;
  border: 1px solid var(--line); border-radius: .5rem; background: var(--surface);
  padding: .85rem .95rem; font: inherit; color: inherit; cursor: pointer;
}
.con-task:hover { border-color: var(--brand-orange); background: var(--surface-raised); }
.con-task > strong { font-size: .84rem; font-weight: 600; color: var(--ink); }
.con-task > span { font-size: .77rem; color: var(--text-muted); }
.con-task > em {
  margin-top: auto; padding-top: .35rem; font-style: normal;
  font-size: .64rem; font-weight: 700; letter-spacing: .08em; text-transform: uppercase;
  color: var(--grey);
}

/* An opened task: a header, the one-click options, then the settings behind them. */
.con-task-open { display: block; }
.con-task-open > header { margin-bottom: 1.25rem; }
.con-task-open > header h2 {
  font-family: "Outfit", var(--ui); font-size: 1.3rem; font-weight: 700; margin: .5rem 0 .3rem;
}
.con-task-open > header p { font-size: .84rem; color: var(--text-muted); max-width: 46rem; }
.con-task-open > h3 {
  font-size: .82rem; font-weight: 650; margin: 2rem 0 .25rem;
  padding-bottom: .5rem; border-bottom: 1px solid var(--line);
}
.con-strengths {
  display: grid; gap: .6rem; grid-template-columns: repeat(auto-fit, minmax(13rem, 1fr));
  margin-bottom: .5rem;
}
.con-strength {
  display: flex; flex-direction: column; gap: .15rem; text-align: left; cursor: pointer;
  border: 1px solid var(--line); border-radius: .5rem; background: var(--surface);
  padding: .7rem .85rem; font: inherit; color: inherit;
}
.con-strength:hover { border-color: var(--brand-orange); background: var(--surface-raised); }
.con-strength > strong { font-size: .82rem; font-weight: 600; color: var(--ink); }
.con-strength > em { font-style: normal; font-size: .68rem; color: var(--grey); }

.con-grid.con-cards {
  display: grid; gap: .7rem; grid-template-columns: repeat(auto-fill, minmax(20rem, 1fr));
  align-items: start;
}
/* An action is a small form, so it leaves the two-column settings row behind. */
.con-setting.con-action {
  display: flex; flex-direction: column; gap: .45rem; align-items: stretch;
  border: 1px solid var(--line); border-left-width: 1px; border-radius: .5rem;
  background: var(--surface); padding: .9rem; margin-bottom: .7rem;
}
.con-setting.con-action:hover { background: var(--surface); border-color: var(--line-strong); }
.con-action .con-field-row { margin-top: .1rem; }
.con-action .con-field { max-width: 100%; }
.con-action .con-field .con-choice, .con-action .con-field .con-search-field,
.con-action .con-field .con-number { width: 100%; max-width: 16rem; }
.con-action .con-warn {
  font-size: .73rem; display: flex; gap: .4rem; align-items: flex-start;
  color: var(--amber); margin-top: .2rem;
}
.con-action .con-warn::before { content: "!"; font-weight: 800; flex: none; }
.con-action .con-setting-foot { margin-top: .35rem; }
.con-action .con-setting-foot .con-primary { margin-left: auto; }

/* ---------- loot tables ---------- */
/* .con-table is the section around the editor, not the <table> itself. */
.con-table {
  border: 1px solid var(--line); border-radius: .6rem; background: var(--surface);
  padding: .95rem; margin-bottom: 1.5rem;
}
.con-table.invalid { border-color: var(--red); }
.con-table > header {
  display: flex; align-items: flex-start; gap: 1rem; flex-wrap: wrap; margin-bottom: .3rem;
}
.con-table > header h3 { font-size: .92rem; font-weight: 650; }
.con-table > header p { font-size: .78rem; color: var(--text-muted); margin-top: .1rem; }
.con-table-actions { display: flex; gap: .9rem; align-items: center; margin-left: auto; flex: none; }
.con-table > header > div:first-child { min-width: 0; flex: 1; }
.con-table-total {
  display: flex; flex-direction: column; align-items: flex-end;
  font-size: .72rem; color: var(--grey); line-height: 1.35;
}
.con-table-total strong { color: var(--ink); font-size: .82rem; font-variant-numeric: tabular-nums; }
.con-table-note { font-size: .74rem; color: var(--grey); margin-top: .55rem; }
.con-table-scroll {
  overflow-x: auto; border: 1px solid var(--line); border-radius: .45rem;
  background: var(--canvas);
}
.con-table-scroll table { width: 100%; border-collapse: collapse; font-size: .8rem; }
.con-table-scroll th {
  text-align: left; font-size: .62rem; font-weight: 700; letter-spacing: .08em;
  text-transform: uppercase; color: var(--grey);
  padding: .45rem .6rem; border-bottom: 1px solid var(--line); white-space: nowrap;
}
.con-table-scroll td {
  padding: .3rem .6rem; border-bottom: 1px solid var(--line); vertical-align: middle;
}
.con-table-scroll tbody tr:last-child td { border-bottom: 0; }
.con-table-scroll tbody tr:hover { background: var(--surface-raised); }
.con-table-scroll tr.dirty td { background: color-mix(in srgb, var(--brand-orange) 8%, transparent); }
.con-table-scroll tr.invalid td { background: color-mix(in srgb, var(--red) 9%, transparent); }
.con-table-scroll tr.jackpot td { background: color-mix(in srgb, var(--blue) 7%, transparent); }
/* Numeric columns shrink to their content so the data sits together and the entry name
   takes the slack. Without this the table stretches every column evenly and a weight
   ends up half a screen from the item it belongs to. */
.con-table-scroll .con-num { text-align: right; white-space: nowrap; width: 1%; }
/* Scoped to the loot editor: its entry column needs room, but the same rule applied to
   every table in the console made a "#" column twelve rems wide. */
.con-table > .con-table-scroll th:first-child,
.con-table > .con-table-scroll td:first-child { width: auto; min-width: 12rem; }
/* The heatmap is 24 equal columns; the loot table's wide first column would squash them. */
.con-heatmap th:first-child, .con-heatmap td:first-child { min-width: 3rem; width: 3rem; }
.con-heatmap th, .con-heatmap td { width: auto; }
.con-table-scroll th.con-num { text-align: right; }
.con-table-scroll td:last-child { padding-left: .2rem; }
.con-muted { color: var(--grey); font-variant-numeric: tabular-nums; }
.con-pct {
  width: 5.5rem; height: 1.7rem; padding: 0 .35rem; text-align: right;
  border: 1px solid var(--line-strong); border-radius: .35rem;
  background: var(--surface); color: var(--ink);
  font-family: var(--mono); font-size: .78rem; font-variant-numeric: tabular-nums;
}
.con-move { border: 0; background: none; padding: 0; color: var(--brand-orange); font-size: .72rem; }
.con-move:not(.shown) { visibility: hidden; }
.con-entry { display: flex; align-items: center; gap: .45rem; min-width: 0; }
.con-entry > span { display: inline-flex; align-items: center; gap: .4rem; min-width: 0; }
.con-icon { width: 1.05rem; height: 1.05rem; image-rendering: pixelated; flex: none; }
.con-icon-gap { width: 1.05rem; height: 1.05rem; flex: none; display: inline-block; }
.con-tag {
  font-style: normal; font-size: .58rem; font-weight: 800; letter-spacing: .06em;
  text-transform: uppercase; padding: .05rem .3rem; border-radius: .25rem;
  border: 1px solid var(--line-strong); color: var(--grey); white-space: nowrap;
}
.con-added { color: var(--green); font-weight: 800; margin-left: .25rem; }
.con-dist {
  display: flex; height: .35rem; border-radius: 1rem; overflow: hidden;
  background: var(--surface-raised); margin: .5rem 0 .65rem; gap: 1px;
}
.con-dist-slice { height: 100%; background: var(--line-strong); }
.con-dist-slice.tone-0 { background: #ff7a1a; }
.con-dist-slice.tone-1 { background: #57a9ff; }
.con-dist-slice.tone-2 { background: #35d17f; }
.con-dist-slice.tone-3 { background: #b98bff; }
.con-dist-slice.tone-4 { background: #f2b23e; }
.con-dist-slice.tone-5 { background: #ff6f91; }
.con-removed {
  display: flex; flex-wrap: wrap; gap: .4rem; align-items: center;
  margin-bottom: .65rem; font-size: .74rem; color: var(--grey);
}
.con-chip {
  display: inline-flex; align-items: center; gap: .3rem; padding: .1rem .45rem;
  border: 1px dashed var(--line-strong); border-radius: 2rem;
  font-size: .72rem; color: var(--text-muted);
}
.con-empty { color: var(--grey); font-size: .82rem; padding: 1.5rem 0; }
.con-warn { color: var(--red); }

/* ---------- activity log ---------- */
.con-category-rail { display: flex; flex-wrap: wrap; gap: .35rem; margin-bottom: .9rem; }
.con-category-rail button {
  display: inline-flex; align-items: center; gap: .35rem;
  border: 1px solid var(--line-strong); background: var(--surface); color: var(--text-muted);
  border-radius: 2rem; padding: .2rem .65rem; font: inherit; font-size: .74rem; cursor: pointer;
}
.con-category-rail button:hover { color: var(--ink); }
.con-category-rail button[aria-pressed="true"] {
  background: var(--brand-orange); border-color: var(--brand-orange);
  color: #180c02; font-weight: 650;
}
.con-category-rail .con-count { color: inherit; opacity: .7; }
.con-log { border: 1px solid var(--line); border-radius: .5rem; background: var(--surface); overflow: hidden; }
.con-log-row {
  display: grid; grid-template-columns: 6.5rem minmax(0, 1fr) 5.5rem;
  gap: .75rem; align-items: baseline; padding: .4rem .7rem;
  border-bottom: 1px solid var(--line); font-size: .78rem;
}
.con-log-row:last-child { border-bottom: 0; }
.con-log-row:hover { background: var(--surface-raised); }
.con-log-row > div { display: flex; align-items: baseline; gap: .5rem; min-width: 0; flex-wrap: wrap; }
.con-log-row strong { color: var(--ink); font-weight: 500; }
.con-log-row time {
  font-family: var(--mono); font-size: .7rem; color: var(--grey);
  font-variant-numeric: tabular-nums; text-align: right; white-space: nowrap;
}
.con-log-cat {
  font-size: .58rem; font-weight: 800; letter-spacing: .06em; text-transform: uppercase;
  color: var(--grey); border: 1px solid var(--line-strong); border-radius: .25rem;
  padding: .05rem .3rem; justify-self: start; white-space: nowrap;
}
.con-log-actor { color: var(--brand-orange); font-size: .72rem; font-weight: 600; }

/* ---------- update notice ---------- */
.con-announce { display: grid; gap: 1.25rem; grid-template-columns: minmax(0, 1fr) minmax(0, 22rem); align-items: start; }
.con-announce-form { display: flex; flex-direction: column; gap: .75rem; min-width: 0; }
.con-announce-form .con-search-field { width: 100%; }
.con-textarea {
  width: 100%; min-height: 8rem; padding: .5rem .6rem; resize: vertical;
  border: 1px solid var(--line-strong); border-radius: .4rem;
  background: var(--surface); color: var(--ink); font: inherit; font-size: .82rem;
  line-height: 1.5;
}
.con-textarea:focus { border-color: var(--brand-orange); outline: none; }
.con-announce-preview { min-width: 0; }
.con-preview-label {
  font-size: .62rem; font-weight: 700; letter-spacing: .09em; text-transform: uppercase;
  color: var(--grey); margin-bottom: .5rem;
}
/* Drawn the way Discord draws an embed, so the preview is worth trusting. */
.con-embed {
  border-left: 4px solid var(--embed, var(--brand-orange));
  border-radius: .3rem; background: var(--surface); padding: .8rem .9rem;
  display: flex; flex-direction: column; gap: .5rem;
}
.con-embed h4 { font-size: .9rem; font-weight: 650; }
.con-embed p { font-size: .82rem; color: var(--text-muted); white-space: pre-wrap; }
.con-embed img { width: 100%; border-radius: .3rem; }
.con-embed footer { font-size: .7rem; color: var(--grey); }

/* ---------- presets ---------- */
.con-presets { display: grid; gap: .6rem; grid-template-columns: repeat(auto-fill, minmax(18rem, 1fr)); }
.con-preset {
  display: flex; align-items: center; gap: 1rem; flex-wrap: wrap;
  border: 1px solid var(--line); border-radius: .5rem; background: var(--surface);
  padding: .7rem .85rem; min-width: 0;
}
.con-preset > div:first-child { display: flex; flex-direction: column; min-width: 0; }
.con-preset h4 { font-size: .82rem; font-weight: 600; }
.con-preset span { font-size: .72rem; color: var(--grey); }
.con-preset .con-table-actions { margin-left: auto; margin-top: 0; }

/* ---------- statistics charts ---------- */
.con-chart-grid {
  display: grid; gap: .75rem; grid-template-columns: repeat(auto-fill, minmax(20rem, 1fr));
}
.con-chart-card {
  border: 1px solid var(--line); border-radius: .5rem; background: var(--surface);
  padding: .85rem .95rem; min-width: 0; position: relative;
}
.con-chart-card > header {
  display: flex; align-items: baseline; justify-content: space-between;
  gap: .75rem; margin-bottom: .5rem; flex-wrap: wrap;
}
.con-chart-card h4 { font-size: .8rem; font-weight: 600; }
.con-chart-card > header > div { display: flex; align-items: baseline; gap: .5rem; }
.con-chart-card strong {
  font-family: "Outfit", var(--ui); font-size: 1.15rem; font-weight: 700;
  color: var(--ink); font-variant-numeric: tabular-nums;
}
.con-delta { font-size: .68rem; font-variant-numeric: tabular-nums; }
.con-delta.up { color: var(--green); }
.con-delta.down { color: var(--red); }
.con-delta.flat { color: var(--grey); }
.con-chart { display: block; width: 100%; height: 7rem; overflow: visible; }
.con-chart-grid-line, .con-chart-grid { stroke: var(--line); stroke-width: 1; }
.con-chart-area { fill: color-mix(in srgb, var(--brand-orange) 18%, transparent); stroke: none; }
.con-chart-line {
  fill: none; stroke: var(--brand-orange); stroke-width: 1.75;
  stroke-linejoin: round; stroke-linecap: round;
  /* The viewBox is stretched to the card, so an unscaled stroke keeps its weight. */
  vector-effect: non-scaling-stroke;
}
.con-chart-point { fill: var(--brand-orange); stroke: var(--surface); stroke-width: 1.5; }
/* Where settings were published. Dashed and cool, so it reads as an annotation on the
   data rather than as another series in it. */
.con-chart-mark {
  stroke: var(--blue); stroke-width: 1; stroke-dasharray: 3 3; opacity: .75;
  vector-effect: non-scaling-stroke;
}
/* The two numbers bound the y axis, so they sit at the top and bottom of the plot.
   Side by side under it they read as a start and an end, which is the x axis. */
.con-chart-wrap { position: relative; }
.con-chart-scale {
  position: absolute; inset: 0 0 auto auto; height: 7rem;
  display: flex; flex-direction: column; justify-content: space-between;
  align-items: flex-end; padding: .1rem .1rem;
  font-family: var(--mono); font-size: .62rem; color: var(--grey);
  font-variant-numeric: tabular-nums; pointer-events: none;
}
.con-chart-scale span {
  background: color-mix(in srgb, var(--surface) 85%, transparent);
  padding: 0 .15rem; border-radius: .15rem;
}
.con-chart-empty { font-size: .76rem; color: var(--grey); padding: 1.5rem 0; text-align: center; }

/* ---------- standings ---------- */
.con-boards { display: grid; gap: .75rem; grid-template-columns: repeat(auto-fill, minmax(16rem, 1fr)); }
.con-board {
  border: 1px solid var(--line); border-radius: .5rem; background: var(--surface);
  padding: .8rem .9rem; min-width: 0;
}
.con-board h4 { font-size: .78rem; font-weight: 650; margin-bottom: .45rem; }
.con-board ol { list-style: none; margin: 0; padding: 0; counter-reset: rank; }
.con-board li {
  display: flex; align-items: center; gap: .5rem; padding: .22rem 0;
  border-top: 1px solid var(--line); font-size: .78rem; counter-increment: rank;
}
.con-board li:first-child { border-top: 0; }
.con-board li::before {
  content: counter(rank); flex: none; width: 1.1rem; text-align: right;
  font-family: var(--mono); font-size: .66rem; color: var(--grey);
}
.con-board li span {
  display: flex; align-items: center; gap: .4rem; min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.con-board li b {
  margin-left: auto; color: var(--ink); font-variant-numeric: tabular-nums; font-weight: 650;
}
/* Not .con-head: that is the console's own page header, and naming a 1.1rem avatar the
   same thing sized the header to 1.1rem too, which clamped to its padding and left a
   48px grey block where the page title should have been. */
.con-avatar {
  width: 1.1rem; height: 1.1rem; border-radius: .2rem; image-rendering: pixelated;
  flex: none; background: var(--surface-raised);
}
/* An avatar that will not load becomes a quiet square rather than a broken-image glyph. */
.con-avatar.missing { visibility: hidden; }

/* ---------- statistics heatmap ---------- */
/* Fixed layout, or auto-sizing hands the slack to the weekday column and squashes the
   24 hours it exists to show. */
.con-heatmap { width: 100%; border-collapse: collapse; font-size: .72rem; table-layout: fixed; }
.con-heatmap th {
  font-size: .62rem; font-weight: 700; letter-spacing: .06em; text-transform: uppercase;
  color: var(--grey); padding: .3rem .25rem; text-align: center; white-space: nowrap;
}
.con-heatmap tbody th { text-align: left; padding-left: .6rem; }
/* One channel, so a busy hour reads as intensity rather than as a different thing. */
.con-heat {
  padding: 0; height: 1.35rem; min-width: 1.1rem;
  background: color-mix(in srgb, var(--brand-orange) calc(var(--heat) * 100%), transparent);
  border: 1px solid var(--line);
}

/* ---------- publish history ---------- */
.con-history { display: flex; flex-direction: column; gap: .6rem; }
.con-publish {
  border: 1px solid var(--line); border-radius: .5rem; background: var(--surface); padding: .8rem .9rem;
}
.con-publish > header { display: flex; align-items: flex-start; gap: 1rem; }
.con-publish > header > div { display: flex; flex-direction: column; min-width: 0; }
.con-publish > header strong { font-size: .84rem; color: var(--ink); font-weight: 600; }
.con-publish > header span { font-size: .72rem; color: var(--grey); }
.con-publish > header button { margin-left: auto; flex: none; }
.con-publish ul { list-style: none; margin: .6rem 0 0; padding: 0; display: flex; flex-direction: column; }
.con-publish li {
  display: flex; align-items: baseline; gap: 1rem; padding: .3rem 0;
  border-top: 1px solid var(--line); font-size: .77rem;
}
.con-publish li span { color: var(--text-muted); min-width: 0; }
.con-publish li code {
  margin-left: auto; font-family: var(--mono); font-size: .72rem; color: var(--ink);
  background: var(--surface-raised); border-radius: .3rem; padding: .05rem .35rem; white-space: nowrap;
}

/* ---------- save bar ---------- */
/* Docked to the work column, not floated over the viewport, so it can never sit on top
   of the sidebar or a dialog. */
.con-draftbar {
  position: sticky; bottom: 0; z-index: 30; flex: none;
  display: flex; align-items: center; gap: 1rem; flex-wrap: wrap;
  margin: 0 1.5rem 1.5rem; padding: .6rem .9rem;
  border: 1px solid var(--line-strong); border-radius: .5rem;
  background: color-mix(in srgb, var(--surface) 94%, transparent);
  backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px);
  box-shadow: 0 8px 28px rgba(0, 0, 0, .38);
}
.con-draft-count { display: flex; align-items: baseline; gap: .5rem; font-size: .8rem; color: var(--text-muted); }
.con-draft-count strong { color: var(--ink); font-size: .95rem; font-variant-numeric: tabular-nums; }
.con-draft-count .bad { color: var(--red); font-weight: 600; }
.con-draft-actions { margin-left: auto; display: flex; gap: .45rem; }

/* ---------- dialogs ---------- */
.con-modal {
  position: fixed; inset: 0; z-index: 200; display: flex; align-items: center; justify-content: center;
  padding: 1.5rem; background: rgba(4, 6, 10, .66);
  backdrop-filter: blur(3px); -webkit-backdrop-filter: blur(3px);
}
.con-modal-card.is-narrow { width: min(26rem, 100%); }
.con-modal-card {
  width: min(38rem, 100%); max-height: 85vh; display: flex; flex-direction: column;
  border: 1px solid var(--line-strong); border-radius: .6rem; background: var(--canvas);
  box-shadow: 0 24px 64px rgba(0, 0, 0, .5); overflow: hidden;
}
.con-modal-card > header {
  display: flex; align-items: center; gap: .75rem; padding: .8rem 1rem;
  border-bottom: 1px solid var(--line); flex: none;
}
.con-modal-card > header h3 { font-size: .92rem; }
.con-modal-card > div { padding: 1rem; overflow-y: auto; flex: 1; min-height: 0; font-size: .82rem; }
.con-modal-card > footer {
  display: flex; justify-content: flex-end; gap: .45rem; padding: .75rem 1rem;
  border-top: 1px solid var(--line); flex: none; background: var(--surface);
}
.con-close {
  margin-left: auto; border: 0; background: none; color: var(--grey);
  font-size: 1.3rem; line-height: 1; cursor: pointer; padding: 0 .2rem;
}
.con-close:hover { color: var(--ink); }
.con-preview-list { list-style: none; margin: .75rem 0 0; padding: 0; display: flex; flex-direction: column; }
.con-preview-list li {
  display: flex; align-items: center; gap: 1rem; padding: .5rem 0;
  border-top: 1px solid var(--line); font-size: .8rem;
}
.con-preview-list li > div { display: flex; flex-direction: column; min-width: 0; }
.con-preview-list strong { color: var(--ink); font-weight: 600; }
.con-preview-list span { color: var(--grey); font-size: .72rem; }
.con-preview-list code {
  margin-left: auto; font-family: var(--mono); font-size: .74rem; color: var(--ink);
  background: var(--surface-raised); border-radius: .3rem; padding: .1rem .4rem; white-space: nowrap;
}
.con-materials { max-height: 14rem; overflow-y: auto; }
#con-add-body { display: flex; flex-direction: column; gap: .85rem; }
#con-add-body .con-field-row { gap: 1rem; }
#con-add-body .con-search-field, #con-add-body .con-choice { width: 100%; }
#con-add-body .con-field-row .con-field { flex: 0 1 auto; }
#con-add-body .con-field-row .con-number { width: 5rem; }
#con-add-body .con-field-row .con-choice { width: 11rem; }
#con-add-body .con-field > span {
  font-size: .72rem; font-weight: 600; color: var(--text-muted);
}
#con-add-body .con-field > em { font-style: normal; font-size: .7rem; color: var(--grey); }
#con-add-body .con-freq { margin-top: .25rem; }
#con-add-body .con-help { font-size: .74rem; margin-top: .35rem; }
.con-freq { display: flex; flex-direction: column; gap: .1rem; }
.con-freq-option {
  display: flex; align-items: center; gap: .55rem; font-size: .8rem; cursor: pointer;
  padding: .3rem .4rem; border-radius: .35rem;
}
.con-freq-option:hover { background: var(--surface-raised); }
/* The label and its blurb are adjacent inline elements: without a gap the bold ran
   straight into the italic and read as one smeared word. */
.con-freq-option > span { display: flex; align-items: baseline; gap: .5rem; flex-wrap: wrap; }
.con-freq-option strong { color: var(--ink); font-weight: 600; }
.con-freq-option em { font-style: normal; color: var(--grey); font-size: .76rem; }
.con-freq-option.custom em { display: inline-flex; align-items: center; gap: .4rem; }
.con-freq-option.custom input[type="number"] {
  width: 5rem; height: 1.7rem; padding: 0 .35rem; border-radius: .35rem;
  border: 1px solid var(--line-strong); background: var(--surface); color: var(--ink);
  font-family: var(--mono); font-size: .78rem; text-align: right;
}

/* ---------- the sign-in gate ---------- */
body.cx #control-lock {
  margin: auto; max-width: 24rem; text-align: center;
  border: 1px solid var(--line); border-radius: .6rem; background: var(--surface); padding: 2rem;
}
body.cx #control-lock img { width: 2.5rem; height: 2.5rem; margin: 0 auto .75rem; display: block; }
body.cx #control-lock h2 { font-size: 1.05rem; margin-bottom: .5rem; }
body.cx #control-lock p { font-size: .82rem; margin-bottom: 1.25rem; }
.live-owner-account { display: flex; align-items: center; gap: .5rem; font-size: .78rem; }
.live-user-pill { display: flex; align-items: center; gap: .45rem; min-width: 0; color: var(--ink); }
.live-user-pill img { width: 1.35rem; height: 1.35rem; border-radius: 50%; flex: none; }
.live-user-pill span { display: flex; flex-direction: column; min-width: 0; line-height: 1.25; }
.live-user-pill strong { font-size: .78rem; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
#owner-logout {
  align-self: flex-start; border: 0; background: none; padding: 0; cursor: pointer;
  font: inherit; font-size: .68rem; color: var(--grey); text-decoration: underline;
  text-underline-offset: 2px;
}
#owner-logout:hover { color: var(--ink); }

/* The site name is context, not identity — it goes before the title is squeezed. */
@media (max-width: 1200px) {
  .con-head .cx-crumb { display: none; }
}

@media (max-width: 900px) {
  body.cx { height: auto; overflow: auto; }
  #console-root, #owner-content { height: auto; }
  .con-shell { grid-template-columns: 1fr; height: auto; }
  .con-rail { border-right: 0; border-bottom: 1px solid var(--line); }
  /* A capped-height column clipped a heading mid-word behind a scrollbar nobody could
     see. The sections wrap as chips instead: nothing is hidden and nothing scrolls. */
  .cx-nav { display: flex; flex-wrap: wrap; gap: .3rem; overflow: visible; padding: .6rem; }
  .cx-group { width: 100%; padding: .5rem 0 .1rem; }
  .cx-group:first-child { padding-top: 0; }
  .con-rail button {
    width: auto; border: 1px solid var(--line); border-radius: 2rem; padding: .25rem .65rem;
  }
  .con-rail button[aria-current="page"] { border-color: var(--brand-orange); }
  .con-rail button[aria-current="page"]::before { display: none; }
  .con-main { overflow: visible; }
  .con-head { flex-wrap: wrap; height: auto; padding: .6rem 1rem; gap: .5rem; }
  #con-search { width: 100%; }
  #con-page { padding: 1rem 1rem 4rem; }
  .con-setting { grid-template-columns: 1fr; row-gap: .6rem; }
  .con-setting > .con-odds, .con-setting > .con-switch, .con-setting > .con-range-fields {
    grid-column: 1; grid-row: auto; justify-self: start;
  }
  .con-draftbar { margin: 0 1rem 1rem; }
  .con-log-row { grid-template-columns: 1fr; gap: .2rem; }
  .con-announce { grid-template-columns: 1fr; }
}

.live-toast {
  position: fixed; z-index: 300; right: 1.5rem; bottom: 1.5rem; max-width: 26rem;
  padding: .9rem 1rem; border: 1px solid var(--line); border-radius: .75rem;
  background: var(--surface); color: var(--ink); box-shadow: var(--lift-3);
  transform: translateY(160%); transition: transform var(--dur) var(--ease);
}
.live-toast.show { transform: translateY(0); }
.live-toast.error { border-color: #ef4444; }

@media (max-width: 1000px) {
  .live-podium-card { height: 20.5rem; }
  .live-podium-card.rank-gold { height: 22rem; }
  .live-settings-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .live-battle { grid-template-columns: 1fr; }
}
@media (max-width: 680px) {
  .live-leaderboard-toolbar { align-items: stretch; flex-direction: column; }
  .live-view-tabs { display: grid; grid-template-columns: repeat(3, 1fr); border-radius: 1rem; }
  .live-view-tabs button { padding: .7rem .45rem; }
  .live-panel-head { align-items: flex-start; flex-direction: column; }
  .live-podium { grid-template-columns: 1fr; align-items: stretch; }
  .live-podium-card, .live-podium-card.rank-gold { height: 20.5rem; }
  .live-podium-card.rank-gold { order: 1; }
  .live-podium-card.rank-silver { order: 2; }
  .live-podium-card.rank-bronze { order: 3; }
  .live-rank-row { grid-template-columns: 2.5rem 2.75rem minmax(0, 1fr); }
  .live-row-value { grid-column: 3; text-align: left; }
  .live-battle-row { grid-template-columns: 2.5rem 2.75rem minmax(0, 1fr); }
  .live-battle-row span:last-child { grid-column: 3; }
  .live-event-heading { align-items: flex-start; }
  .live-settings-grid { grid-template-columns: 1fr; }
  .live-control-actions { width: 100%; }
  .live-control-actions input { min-width: 0; width: 100%; }
  .live-log-row { grid-template-columns: 1fr; gap: .25rem; }
  .live-status-card { justify-self: stretch; }
}

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

STATS_SCRIPT = """
<script>
(function () {
  var band = document.querySelector('.stats[data-stats-src]');
  var stamp = document.querySelector('[data-ago]');

  function ago(el) {
    if (!el) return;
    var then = Date.parse(el.getAttribute('data-ago'));
    if (isNaN(then)) return;               // leave the absolute stamp in place
    var mins = Math.round((Date.now() - then) / 60000);
    if (mins < 0) return;                  // clock skew; the stamp is safer
    var text;
    if (mins < 1) text = 'just now';
    else if (mins < 60) text = mins + (mins === 1 ? ' minute ago' : ' minutes ago');
    else {
      var hrs = Math.round(mins / 60);
      if (hrs < 24) text = hrs + (hrs === 1 ? ' hour ago' : ' hours ago');
      else { var d = Math.round(hrs / 24); text = d + (d === 1 ? ' day ago' : ' days ago'); }
    }
    el.textContent = text;
  }

  ago(stamp);
  if (!band || !window.fetch) return;     // the built-in numbers stand on their own

  var busy = false;
  function refresh() {
    if (busy || document.hidden) return;  // nothing to show a tab nobody is looking at
    busy = true;
    // Cache-busted on purpose: the only reason to ask again is to get a newer file.
    fetch(band.getAttribute('data-stats-src') + '?t=' + Date.now(), { cache: 'no-store' })
      .then(function (response) { return response.ok ? response.json() : null; })
      .then(function (data) {
        if (!data || typeof data.online !== 'number' || typeof data.max !== 'number') return;
        var values = { online: data.online, max: data.max, version: data.version };
        Array.prototype.forEach.call(band.querySelectorAll('[data-stat]'), function (el) {
          var next = values[el.getAttribute('data-stat')];
          if (next === undefined || next === null) return;
          if (String(next) !== el.textContent) el.textContent = String(next);
        });
        if (stamp && data.checked_at) {
          stamp.setAttribute('datetime', data.checked_at);
          stamp.setAttribute('data-ago', data.checked_at);
        }
        ago(stamp);
      })
      .catch(function () {})               // a failed poll leaves the built figures alone
      .then(function () { busy = false; });
  }

  setInterval(refresh, 60000);
  setInterval(function () { ago(stamp); }, 60000);
  // Coming back to the tab is the moment stale numbers are most obvious.
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) refresh();
  });
})();
</script>
"""

COPY_SCRIPT = """
<script>
(function () {
  document.querySelectorAll('[data-copy]').forEach(function (btn) {
    var label = btn.querySelector('.label');
    if (!label) return;
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
        '<img class="community-mascot" src="%sassets/urabe.png" alt="" aria-hidden="true"'
        ' width="760" height="821" decoding="async">'
        "<h2>Join our community!</h2>"
        "<p>Get the latest updates and more.</p>"
        '<div class="community-links">%s</div>'
        "</div></section>" % (prefix, buttons)
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


def _stats_band(stats: Optional[Dict[str, object]], prefix: str = "") -> str:
    """Three figures from the live server, or nothing at all.

    Baked in at build time and then refreshed in the browser from
    `assets/stats.json` — the same document `server_status.py` writes, published
    beside the page. That is safe by construction rather than by care: the file
    holds counts, a version and a timestamp and nothing else, no address and no
    player names, and a test pins that.

    Still honest about being a snapshot. Polling a file the build refreshes every
    few minutes makes the numbers fresher, not live, so the page keeps printing
    when they were checked.
    """
    if not stats:
        return ""
    tiles = [
        ("online", "Players online", str(stats["online"])),
        ("max", "Server slots", str(stats["max"])),
    ]
    if stats.get("version"):
        tiles.append(("version", "Running", str(stats["version"])))
    cells = "".join(
        '<div class="stat"><span class="stat-v" data-stat="%s">%s</span>'
        '<span class="stat-k">%s</span></div>' % (key, _esc(value), _esc(label))
        for key, label, value in tiles
    )
    checked = _esc(str(stats.get("checked_at") or ""))
    caption = (
        '<p class="stat-note">Checked <time datetime="%s" data-ago="%s">%s</time></p>'
        % (checked, checked, checked.replace("T", " ").replace("Z", " UTC"))
        if checked
        else ""
    )
    return (
        '<section class="stats" data-stats-src="%sassets/stats.json">'
        '<div class="stats-card">'
        '<div class="stats-row">%s</div>%s</div></section>' % (prefix, cells, caption)
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
    server_address = (
        '<button type="button" class="hero-address" data-copy="%s"'
        ' aria-label="Copy Java server address %s">'
        '<span class="label">%s</span>%s</button>'
        % (
            _esc(SERVER_ADDRESS),
            _esc(SERVER_ADDRESS),
            _esc(SERVER_ADDRESS),
            _icon("copy"),
        )
        if SERVER_ADDRESS
        else ""
    )

    hero = (
        '<section class="hero-band">'
        '<span class="orb orb-1"></span><span class="orb orb-2"></span><span class="orb orb-3"></span>'
        '<div class="hero-inner"><div class="hero-copy">'
        '<img class="wordmark" src="%sassets/logo.png" alt="%s">'
        "<h1>The latest SMP news!</h1>"
        '<p class="lede">%s</p>'
        "%s"
        '<div class="hero-cta">%s%s</div>'
        "</div>%s</div></section>"
        % (
            prefix, _esc(SITE_NAME), _esc(SITE_TAGLINE), server_address,
            read_latest, second_cta, hero_feature,
        )
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
        hero, _stats_band(stats, prefix), featured_strip, archive, _community(prefix)
    )
    return _page(
        "%s Dev Blog" % SITE_NAME, SITE_TAGLINE, prefix, body, None, site_url,
        current="index", scripts=COPY_SCRIPT + STATS_SCRIPT, nav=nav,
    )


def render_page(page, body_html: str, prefix: str, site_url: str,
                nav: Sequence[Dict[str, str]] = ()) -> str:
    """A guide, rules or how-to page: left-aligned prose, no dateline."""
    tagline = '<p class="page-tagline">%s</p>' % _esc(page.tagline) if page.tagline else ""
    # The statistics page is a dashboard that also loads its own charting script, and
    # the owner console is a dashboard that replaces the shared one entirely — it draws
    # typed controls and loot tables rather than the leaderboard's cards.
    layout = getattr(page, "layout", "document")
    statistics = layout == "statistics"
    console = layout == "console"
    dashboard = layout == "dashboard" or statistics or console
    doc_class = "doc live-doc" if dashboard else "doc"
    body_class = "doc-body live-body" if dashboard else "doc-body"
    body = (
        '<div class="page"><div class="shell"><div class="%s">'
        '<header class="doc-head"><h1>%s</h1>%s</header>'
        '<article class="%s">%s</article>'
        "</div></div></div>" % (doc_class, _esc(page.title), tagline, body_class, body_html)
    )
    if console:
        scripts = '<script src="%sassets/owner-console.js" defer></script>' % prefix
    elif dashboard:
        scripts = '<script src="%sassets/server-dashboard.js" defer></script>' % prefix
    else:
        scripts = ""
    if statistics:
        scripts += (
            '<script src="%sassets/server-statistics.js" defer></script>' % prefix
        )
    return _page(
        "%s | %s" % (page.title, SITE_NAME),
        page.tagline or "%s — %s" % (SITE_NAME, page.title),
        prefix, body, None, "%s/%s" % (site_url.rstrip("/"), page.url),
        current=page.slug, nav=nav, scripts=scripts,
    )


def render_console(page, body_html: str, prefix: str, site_url: str) -> str:
    """The owner console: its own document, not a page of the site.

    Everything else here is a document — a topbar to navigate the site with, an article
    column, a footer. The console is an application: a fixed sidebar, a work area that
    scrolls by itself, and a save bar docked to the bottom of that area. Rendering it
    through _page() put a public marketing nav and a Discord button above an operator's
    settings screen and squeezed 516 controls into a reading column, which is what made
    it read as a web page with forms on it rather than a control panel. So it gets its
    own shell: no topbar, no footer, no .doc wrapper, and noindex because it is private.
    """
    tags = [
        '<meta charset="utf-8">',
        '<meta name="viewport" content="width=device-width, initial-scale=1">',
        "<title>%s | %s</title>" % (_esc(page.title), _esc(SITE_NAME)),
        '<meta name="robots" content="noindex, nofollow">',
        '<meta name="theme-color" content="#0b0d11">',
        '<link rel="icon" href="%sassets/icon.png">' % prefix,
        '<link rel="preconnect" href="https://fonts.googleapis.com">',
        '<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>',
        '<link rel="stylesheet" href="%s">' % FONT_URL,
        '<link rel="stylesheet" href="%sassets/style.css">' % prefix,
        THEME_BOOT,
    ]
    switch_markup = (
        '<div class="theme-switch" role="radiogroup" aria-label="Theme">'
        '<button type="button" role="radio" aria-checked="false" tabindex="-1"'
        ' data-theme="light" aria-label="Light theme">%s</button>'
        '<button type="button" role="radio" aria-checked="true" tabindex="0"'
        ' data-theme="system" aria-label="System theme">%s</button>'
        '<button type="button" role="radio" aria-checked="false" tabindex="-1"'
        ' data-theme="dark" aria-label="Dark theme">%s</button>'
        "</div>" % (_icon("sun"), _icon("monitor"), _icon("moon"))
    )
    body_html = body_html.replace("<!--theme-switch-->", switch_markup, 1)
    return (
        "<!doctype html>\n"
        '<html lang="en">\n<head>\n%s\n</head>\n<body class="cx">\n%s\n%s\n'
        '<script src="%sassets/owner-console.js" defer></script>\n</body>\n</html>\n'
        % ("\n".join(tags), body_html, THEME_SCRIPT, prefix)
    )


def render_404(prefix: str, nav: Sequence[Dict[str, str]] = ()) -> str:
    body = (
        '<div class="page"><div class="shell"><div class="notfound"><h1>404</h1>'
        "<p>That update does not exist &mdash; or it has not shipped yet.</p>"
        '<a class="btn btn-orange" href="%s">Back to updates</a>'
        "</div></div></div>" % _home_href(prefix)
    )
    return _page("Not found | %s" % SITE_NAME, "Page not found.", prefix, body, nav=nav)
