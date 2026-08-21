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
python devblog/tests/test_build.py      # 88 tests
python devblog/sync_from_bot.py        # regenerate guide.md and rules.md
```

`dist/` is git-ignored — the workflow rebuilds it. Never commit it.

## Write a post

Two things go in: **one markdown file** and **one folder of images**.

```
devblog/posts/2026-08-21-update-1.md      <- the post
devblog/media/update-1/                    <- its images, slug-named
    hero.png
    icon.png
    eternal-maze.png
```

The folder under `media/` must match the post's slug, which is the filename with
the date prefix stripped (`2026-08-21-update-1.md` → `update-1`). Inside the
post, reference images by bare filename — the build rewrites them.

## Standing pages

Alongside the dated updates, `pages/*.md` become top-level pages that appear in
the nav, ordered by their `order` key:

| Page | URL | Source |
|---|---|---|
| Server Guide | `/guide` | **generated** from the bot's information panel |
| Server Rules | `/rules` | **generated** from `SERVER_RULES` in the bot |
| How to Apply | `/apply` | hand-written |

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

`WEB_REWRITES` handles copy that is right in Discord but wrong on a public page —
the bot's "ask staff for the address" fallback becomes "given to you when your
application is accepted", because the connect address is private and applying is
how a player gets it. A test fails if a rewrite stops matching.

`pages/apply.md` is hand-written and `sync_from_bot.py` does not touch it.

## URLs

Posts sit at the **site root**, one folder each:

| | |
|---|---|
| `https://mysterioussmpx.blog` | the home page |
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
hero: hero.png
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
| `hero` | no | Filename in the post's media folder. Opens the post, and becomes the blurred backdrop on the index card. |
| `icon` | no | Square art centred on the index card. Falls back to `hero`. |
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

## Site settings

Everything configurable lives in `config.py`. **Every link and server field
ships empty**, and an empty value is omitted rather than rendered as a dead
link or an empty box — so the site is honest before you have filled it in.

| Setting | Drives |
|---|---|
| `SERVER_ADDRESS` | **Leave empty.** The connect address is private — players get it from the bot when their application is accepted. Setting this puts a Copy IP button in the community band and the address in every page footer. A test enforces the blank. |
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
