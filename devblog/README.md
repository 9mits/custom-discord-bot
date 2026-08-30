# Mysterious SMP X — Dev Blog

A dependency-light static site for update posts, modelled on the BIG Games /
Pet Simulator 99 update-post layout: Outfit throughout, `rgb(255,157,67)` orange
full-width underlined section headers, a category pill and date above a
left-aligned title, centred line-per-beat body copy with a screenshot after every
section, and index cards built from a blurred backdrop with a crisp square
icon centred on top.

The home page follows the same studio's landing page: a hero with the wordmark,
a headline and two calls to action beside a framed shot of the newest update,
then that update called out again with its category and date, then the archive
grid, then a community band holding every social link.

Published to GitHub Pages by a workflow on every push to `main` that touches
`devblog/`. That workflow ships here as `deploy.yml` and needs installing once
— see **Publishing** below.

## Build it

```bash
pip install -r devblog/requirements.txt
python devblog/build.py                 # writes devblog/dist/
python devblog/build.py --serve         # build, then http://127.0.0.1:8000
python devblog/build.py --drafts        # include posts marked draft
python devblog/tests/test_build.py      # 154 tests
python devblog/sync_from_bot.py        # regenerate guide.md and rules.md
MC_SERVER_HOST=... MC_SERVER_PORT=... python devblog/server_status.py
```

**The blog's CI job installs `devblog/requirements.txt` and nothing else** — no
discord.py. Anything under `devblog/` that only works because the bot's deps
happen to be in your local venv will pass here and fail there. To check the way
CI will see it:

```bash
python3 -m venv /tmp/civenv
/tmp/civenv/bin/pip install -r devblog/requirements.txt pyflakes
/tmp/civenv/bin/python devblog/tests/test_build.py
```

This is also why `sync_from_bot.py` imports the bot inside `load_bot()` rather
than at module scope: the tests read a constant out of that module, and a
module-scope import took the whole suite down on CI.
```

`dist/` is git-ignored — the workflow rebuilds it. Never commit it.

## Write a post

**If you just want to publish a post, read [WRITING.md](WRITING.md)** — it is
four commands and needs no git. The rest of this file is how the thing works.

```bash
python devblog/blog.py new "Fiesta Forever" --category Event
python devblog/blog.py preview
python devblog/blog.py publish
```

Upcoming events use their own archive and never have to masquerade as a server
update. Start one as a private draft, fill in the confirmed schedule and art,
then remove `draft: true` only when it is ready to announce:

```bash
python devblog/blog.py new-event "Void RNG Weekend" --date "2026-09-05 18:00"
python devblog/blog.py preview
python devblog/blog.py publish
```

Event markdown lives in `devblog/events/` and publishes under `/events/<slug>`.
The `/events` index is generated automatically. Creating the draft does not put
it on the live site.

### The format

Two things go in: **one markdown file** and **one folder of images**.

```
devblog/posts/2026-08-21-update-1.md      <- the post
devblog/media/update-1/                    <- its images, slug-named
    cover.png
    icon.png
    hero.png                               <- optional
    eternal-maze.png
```

The folder under `media/` must match the post's slug, which is the filename with
the date prefix stripped (`2026-08-21-update-1.md` → `update-1`). Inside the
post, reference images by bare filename — the build rewrites them.

## Live server stats

The home page shows players online, server slots and the running version.

**A static site cannot query the server when someone visits it** — there is no
backend. So CI queries it just before each build and bakes the numbers in, and a
`*/30 * * * *` schedule rebuilds so they stay fresh. The page prints when the
figures were checked rather than pretending they are live.

`server_status.py` speaks the Server List Ping protocol over a plain socket —
stdlib only, and no third-party status service is involved. CI receives the
query endpoint through `MC_SERVER_HOST` / `MC_SERVER_PORT`; the public connect
address itself is `play.mysterioussmpx.blog`. `data/stats.json` still contains
only counts, a version and a timestamp, and `devblog/data/` remains git-ignored.

If the query fails, **no stats are written and the panel simply disappears**. A
failure is ambiguous — the server may be down, or the runner may just not be able
to reach it — and neither justifies telling visitors the server is offline.

A local build without the secrets has no stats and renders without the panel.

## The archive

`/blog` lists every update. The newest is called out at the top and the rest sit
in the grid below; when there is more than one `category` in play, a tab strip
appears across the top.

Filtering is client-side, so it is instant and needs no extra pages. The grid
holds **every** post — the called-out one included, starting hidden so it does
not appear twice — and the tabs toggle visibility. Selecting a category also
writes `?category=` into the URL so a filtered view can be linked to, and that
parameter is honoured on load.

With JavaScript off the page still works: the callout and the full grid render,
just without the filter.

`blog` is a reserved slug, so no post can take that URL.

## Standing pages

Alongside the dated updates, `pages/*.md` become top-level pages that appear in
the nav, ordered by their `order` key:

| Page | URL | Source |
|---|---|---|
| Events | `/events` | generated from draftable `events/*.md` announcements |
| Leaderboards | `/leaderboards` | live Paper snapshot, with the clan battle kept prominent |
| Server Guide | `/guide` | **generated** from the bot's information panel |
| Server Rules | `/rules` | **generated** from `SERVER_RULES` in the bot |
| How to Join | `/apply` | hand-written |
| Owner Control | `/control` | Discord OAuth owner controls and audit logs |

`/leaderboards` and `/control` are built as native pages in this site rather than
as a separate dashboard. A plain `build.py --serve` preview shows their layout,
but it has no live API. For the complete local preview, first run
`python devblog/build.py`, then start `python minecraft_main.py` and open
`http://127.0.0.1:8090`. That backend serves the built blog and supplies the
same-origin leaderboard, OAuth, settings, and log routes. Nothing is published
to GitHub Pages by this local flow.

Guide and rules are generated so the site and Discord can never drift:

```bash
python devblog/sync_from_bot.py
```

That script imports `minecraft_bot` and renders its embeds to markdown, so it
needs the bot's dependencies. Its output is committed, which is why the site
build itself never imports discord.py. **Re-run it whenever the bot's rules or
information copy changes**, then commit the regenerated pages.

It resolves Discord role mentions to names via `ROLE_LABELS`; an unknown role id
fails the sync rather than shipping a raw `<@&123>` to the page.

`WEB_REWRITES` handles any copy that is right in Discord but wrong on a public
page. Connection details need no rewrite: the generated guide uses the same
public defaults as the bot.

`pages/apply.md` is hand-written and `sync_from_bot.py` does not touch it.

## URLs

Posts sit at the **site root**, one folder each:

| | |
|---|---|
| `https://mysterioussmpx.blog` | the home page |
| `https://mysterioussmpx.blog/blog` | the archive: every update, filterable by category |
| `https://mysterioussmpx.blog/update-1` | the post whose slug is `update-1` |
| `https://mysterioussmpx.blog/guide` | a standing page from `pages/guide.md` |

The slug is an internal codename — `update-1`, `update-2` — while the `title`
carries the real name players see (`Fiesta Forever!`). Name post files
`YYYY-MM-DD-update-N.md` and the slug follows automatically.

Posts and pages share the root namespace with the build's own output, so a slug
may not be `assets`, `media`, `index` or `404`, may not be claimed by both a page
and a post, and must be lowercase letters, digits and hyphens. The build fails
loudly rather than silently overwriting something.

There is **no RSS feed** — it was removed deliberately, and a test asserts
nothing links to one.

### Front matter

```markdown
---
title: Fiesta Forever!
tagline: Go DEEP in the ETERNAL MAZE! Race to the GARGANTUAN!
date: 2026-08-21
category: Event
cover: cover.png
icon: icon.png
signoff: SEE YOU IN THE MAZE!
tags: event, update
---
```

| Key | Required | Notes |
|---|---|---|
| `title` | yes | The big left-aligned heading and the browser title. |
| `date` | yes* | `YYYY-MM-DD`. *Optional if the filename starts with a date. |
| `tagline` | no | Grey subtitle under the title; also the card excerpt and share description. |
| `cover` | no | Designed editorial artwork for the home page and social previews. Never use a raw UI-heavy gameplay capture. |
| `hero` | no | Optional polished image opening the article. Ordinary gameplay screenshots belong in the body instead. |
| `icon` | no | Square art centred on cards. Falls back to `cover`, then `hero`. |
| `category` | no | The pill above the title. Defaults to `DEFAULT_CATEGORY` in `config.py`. |
| `signoff` | no | Bold closing line, e.g. `SEE YOU ON THE SERVER!` |
| `tags` | no | Comma separated. |
| `slug` | no | Override the URL. Defaults to the filename minus the date. |
| `draft` | no | `true` keeps it out of the build until you remove the line. |

### Body

The body is Markdown with three house conventions:

- **`## Heading`** — the big orange underlined section header (`Fiesta Forever`,
  `Featuring`). One per major beat of the update.
- **`### Heading`** — the bold dark sub-header for an individual feature
  (`Eternal Maze`, `Cursed Piñatas`).
- **An image on its own line** becomes a full-width rounded screenshot. Put one
  after each section so the post reads as text, picture, text, picture.

Single newlines are real line breaks, so write one beat per line — that is what
produces the centred, punchy patch-note rhythm rather than a wall of prose. Bold
the nouns that matter; the theme renders `**bold**` darker than body text.

```markdown
## Featuring

### Eternal Maze

Step through the new **portal** into the **Eternal Maze**!
You get **5 minutes**. Go as **DEEP** as you can!
**3 runs** a day, and entry is **FREE**!

![](eternal-maze.png)
```

Lists, tables, `code`, blockquotes and `---` rules all work if a section needs
them — useful for balance tables in a patch post.

## Art that already exists

Item icons never need a screenshot — the real textures are in the repo:

```
assets/resourcepack/src/assets/mgx/textures/item/*.png   16x16, the real textures
assets/resourcepack/icon-sources/*.svg                    their pixel-art sources
```

There are distinct textures for `crate_key`, both custom potion families, and all
eighteen cosmetics. Cosmetic files live in the `item/cosmetic/` subdirectory and
match their catalog IDs exactly; never substitute a category-wide icon.

Upscale with **nearest-neighbour at a whole-number factor**; anything smooth
turns pixel art to mush. Pillow's `Image.NEAREST` does the job.

Screenshots of a **UI or an effect in play** cannot come from here — take those
in game, where `/mgxadmin devblog` clears the screen for you.

## Site settings

Everything configurable lives in `config.py`. **Every link and server field
ships empty**, and an empty value is omitted rather than rendered as a dead
link or an empty box — so the site is honest before you have filled it in.

| Setting | Drives |
|---|---|
| `SERVER_ADDRESS` | Public Java connect address. It powers the Copy IP button in the community band and appears in every page footer. |
| `DISCORD_URL` | Top-bar button, sidebar button, footer link. |
| `APPLY_URL`, `REDDIT_URL`, `TWITTER_URL`, `YOUTUBE_URL` | A brand-coloured button each in the sidebar grid, plus a footer link. Set order is fixed; unset ones vanish and the grid re-flows. |
| `DEFAULT_CATEGORY` | The pill on posts that do not set `category`. |
| `DEFAULT_SITE_URL` | Feed and share tags. CI overrides it with the `DEVBLOG_SITE_URL` repo variable. |

`theme.py` holds the stylesheet and page shells. Colours are CSS custom
properties at the top of the stylesheet; light, dark, and a three-state theme
switch (light / system / dark, remembered in `localStorage`) are all wired up.

**Emoji.** The reference posts put an emoji at the front of each `###`
sub-header. Nothing stops you — write it in the markdown and it renders. The
repo's own no-decorative-emoji convention in `AGENTS.md` is about the Discord
bot's output, not this site, so it is your call per post.

## Publishing

The repo is public, so Pages is free. Two one-time steps:

**1. Install the workflow.** `devblog/deploy.yml` has to sit at
`.github/workflows/devblog.yml` to run. It is parked here because pushing a file
into `.github/workflows/` needs a token with the `workflow` scope, which the
local git credential does not have. Either grant the scope once:

```bash
gh auth refresh -s workflow          # interactive; then:
git mv devblog/deploy.yml .github/workflows/devblog.yml
git commit -m "chore: install the dev blog deploy workflow"
```

…or skip the scope entirely and create it through the web UI: **Add file →
Create new file**, name it `.github/workflows/devblog.yml`, and paste the
contents of `devblog/deploy.yml`.

**2. Point Pages at Actions.** Settings → Pages → Build and deployment →
Source → **GitHub Actions**.

After that every merge to `main` touching `devblog/` rebuilds and deploys. Pull
requests build and test but do not deploy.

The default URL is `https://9mits.github.io/custom-discord-bot`. For a custom
domain, add the domain under Settings → Pages, then set a repo variable
`DEVBLOG_SITE_URL` to the new base URL so the RSS feed and share tags follow.
