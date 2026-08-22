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

Drop them into the folder it made. Two are special:

| Name | Where it shows |
|---|---|
| `hero.png` | The big image at the top of the post |
| `icon.png` | The square on the home page and `/blog` cards |

Everything else is up to you — reference any image by **bare filename**:

```markdown
![](eternal-maze.png)
```

Wide screenshots work best. A square or transparent logo is fine too; it sits
on a dark plate so light artwork stays visible on both themes.

### Item icons are already in the repo

For crate keys, auras, trails and kill effects you do not need to take a
screenshot — the real textures live in
`assets/resourcepack/src/assets/mgx/textures/item/`. They are 16x16, so they
need upscaling with nearest-neighbour, not a smooth resize. Ask me and I will
prepare them.

There are **five** textures in total: `crate_key`, and one each for `aura`,
`trail`, `kill_effect` and `secret`. Every cosmetic shares its category's icon —
per-cosmetic art was tried and deliberately reverted — so there is no such thing
as eighteen separate cosmetic icons to publish.

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
