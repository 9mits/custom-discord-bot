# Writing a dev blog post

Four commands. You never need to touch git.

```bash
python devblog/blog.py new "Fiesta Forever" --category Event
# ...drop screenshots in, write the post...
python devblog/blog.py preview
python devblog/blog.py publish
```

---

## 1. Start the post

```bash
python devblog/blog.py new "Fiesta Forever" --category Event
```

This creates two things, tells you where they are, **and prints every change
that has landed since your last post** — which is what the post should cover:

- `devblog/posts/2026-08-22-update-2.md` — the post, pre-filled with the layout
- `devblog/media/update-2/` — where its images go

The slug (`update-2`) is picked automatically as the next number, and becomes
the URL: `mysterioussmpx.blog/update-2`. Pass `--slug something-else` to
override it.

`--category` is the pill shown above the title and the filter it appears under
on `/blog`. Use whatever you like — `Event`, `Patch`, `Announcement` — new ones
create new filter tabs by themselves.

## 2. Add the images

Drop them into the folder it made. Three names have distinct jobs:

| Name | Where it shows |
|---|---|
| `cover.png` | Designed artwork on the home page and social previews |
| `hero.png` | An optional polished image at the top of the post |
| `icon.png` | The square on the home page and `/blog` cards |

Everything else is up to you — reference any image by **bare filename**:

```markdown
![](eternal-maze.png)
```

Wide screenshots work best. A square or transparent logo is fine too; it sits
on a dark plate so light artwork stays visible on both themes.

The **cover is not a gameplay screenshot**. It must be purpose-made feature art:
a clean logo, item lineup, key art, or a deliberate composite of real assets.
Never promote a capture containing a hotbar, scoreboard, chat, profile panel,
debug text, cursor, or unrelated UI to `cover:`. Gameplay captures belong next
to the feature they demonstrate inside the post.

### Item icons are already in the repo

For crate keys, auras, trails and kill effects you do not need to take a
screenshot — the real textures live in
`assets/resourcepack/src/assets/mgx/textures/item/`. They are 16x16, so they
need upscaling with nearest-neighbour, not a smooth resize. Ask me and I will
prepare them.

There are distinct textures for `crate_key`, the Fortune and Crate Luck potion
families, and all eighteen cosmetics. Cosmetic files live under `item/cosmetic/`
and match their catalog IDs, so use the exact asset for the feature being shown.

Screenshots of a **menu or an effect in play** still have to come from you —
but `/mgxadmin devblog` in game makes that far less painful:

| | |
|---|---|
| `/mgxadmin devblog` | stash your gear, hide the sidebar and other players |
| `/mgxadmin devblog on keeparmour` | same, but keep your armour on for the shot |
| `/mgxadmin devblog time noon` | fix your sky — `day`, `noon`, `dusk`, `night`, `midnight`, `dawn` |
| `/mgxadmin devblog weather clear` | stop the rain, for you only |
| `/mgxadmin devblog cam` | spectator, and back to the exact spot you left |
| `/mgxadmin devblog players` | show the others again |
| `/mgxadmin devblog off` | put everything back |

Time and weather are **yours alone** — nobody else's sky changes, so you can
shoot a night scene at noon without touching the server.

Press **F1** to hide the HUD once you are set up.

Your belongings are written to disk the moment the session starts, so a crash
or a restart cannot eat them; they come back the next time you join.

### Get a shot list before taking anything

Do not settle for "send screenshots." Before a post is drafted, the coding
agent must turn the shipped feature list into a **numbered, feature-specific
shot brief**. Every requested image must say:

- the exact filename and which section it supports;
- where to stand, what menu or command to open, and what action to catch;
- who or what must be visible (and what must be hidden);
- the `/mgxadmin devblog` time, weather, camera, armour and player settings;
- whether **F1 should hide the HUD** or the UI is the subject of the shot;
- the framing and orientation, plus which real assets should become the
  **designed cover** and **card icon**. A screenshot is only a `hero` when it is
  deliberately clean enough to lead the article.

Ask for the smallest set that tells the whole update: one designed cover made
from real assets, then one screenshot (or a deliberately paired set) for each
major visual beat. Do not ask for a screenshot of a number or patch note that
reads better as text.

Capture at the monitor's native resolution and send the original PNG. Do not
resize, crop, annotate or compress it first; the agent can make a non-destructive
web crop while keeping the source. Use **16:9 landscape** for world/action shots.
For menus, centre the complete panel with enough clean margin to crop it tightly.
Do not use F1 when it would hide the menu being photographed.

## 3. Write it

The layout is already in the file. The shape that makes it read like a real
update post:

```markdown
## Section Heading          <- big orange underlined heading

### A Feature               <- bold sub-heading

One idea per line.
Single newlines are real line breaks.
Bold the **nouns that matter**.

*An italic line closes a section well.*

![](screenshot.png)         <- an image after each section
```

**Write short lines.** The centred one-beat-per-line rhythm is the whole look —
a paragraph of prose will not read the same way.

### Match the voice, not only the formatting

The reference voice is a reveal, not documentation. It is excited, direct and
slightly playful:

- Lead with what just appeared: "A strange door has opened!"
- Use active player verbs: **OPEN**, **STEAL**, **BUILD**, **RACE**.
- Give a feature two to five compact beats, then let the screenshot prove it.
- Put the reward, risk or surprise up front. Save precise mechanics for the next
  line only when a player needs them.
- Use confident fragments and occasional questions. End sections with a small
  joke, dare or warning.
- Cut phrases such as "this is deliberate," "the realistic use is," and long
  implementation explanations unless they prevent a real player mistake.

Accuracy still wins. Never turn hype into an invented reward, number or promise.
Read the **Piñata Maze** and **Void RNG Event** references as the tone benchmark;
`2026-08-22-update-2.md` shows the local Markdown shape.

Emoji work if you want them (`### 🎉 Eternal Maze`). Lists, tables, `code`,
quotes and `---` rules all work too.

Front matter keys are documented in [README.md](README.md).

## 4. Look at it

```bash
python devblog/blog.py preview
```

Builds and serves on <http://127.0.0.1:8000>. It includes drafts, so a post
with `draft: true` still shows here but stays off the live site.

It runs `check` first and refuses to serve if something is wrong. Narrow the
window to test mobile, and use the theme switch in the footer to check both.

## 5. Publish

```bash
python devblog/blog.py publish
```

This does the whole thing: branches, commits, pushes, opens a pull request,
waits for both required checks, merges, and deploys. It prints the URL at the
end. Takes two or three minutes, mostly waiting on the checks.

If a check fails it **stops and says so**, and nothing is published. Fix the
problem and run `publish` again.

---

## When something is wrong

Run this any time:

```bash
python devblog/blog.py check
```

It reports problems in plain English — a missing image, a malformed front
matter line, template text left in the post — and says nothing when all is well.

**Front matter must be one `key: value` per line.** A wrapped line is the most
common mistake and `check` catches it.

## Holding a post back

Add `draft: true` to the front matter. It shows in `preview` and stays off the
live site until you remove the line.
