# AGENTS.md

Instructions for AI coding agents (Claude Code, Cursor, Copilot, Codex, Aider)
working in this repo: a Python Discord moderation bot (~16k LOC, discord.py 2.x,
SQLite). Solo developer, owner `9mits`. This file is the source of truth; tool-
specific files (`CLAUDE.md`) import it.

## Non-negotiables

These four rules override everything else. The rest of this file is guidance.

1. **Automatically merge and deploy completed work.** Run the whole loop
   autonomously — branch, code, test, push, open PR, watch CI, fix failures,
   squash-merge once every required check is green, then restart production and
   verify the panel reports `running`. Do not pause to ask for merge approval.
   Stop before merging or deploying only when the user explicitly says to hold,
   leave the PR open, or skip deployment.
2. **A `minecraft-bridge/` change is not done until the jar is on the server.**
   Editing the plugin changes nothing in game. Bump `version` in
   `build.gradle.kts`, `./gradlew clean shadowJar`, then upload
   `build/libs/MGXAccessBridge.jar` over SFTP and swap it — every time, without
   being asked, the same way code is merged without being asked. Do not offer
   the upload and wait; do it, then say the restart is the user's step. The
   sequence, the byte/sha verification, and the space-free-path trap are in
   **Hosting — GravelHost** below.
3. **Keep secrets out of git.** `.env*`, `.panel.env`, `config.json`, and
   `database*/` are git-ignored and hold live tokens and user data. Stage files
   by name; read what `git status` shows before committing.
4. **Keep moderators anonymous in user-facing output.** Report DMs, the report
   log, and transcripts must never name or hint at which staff member acted —
   it invades privacy and lets bad actors target moderators.

## Commands

```bash
# Run
python main.py                 # single bot (needs DISCORD_BOT_TOKEN in env or .env)
python start.py                # moderation bots plus optional .env.minecraft process
python minecraft_main.py       # dedicated Minecraft access bot
python run_test.py             # staging: test bot on this machine, loads .env.test only

# Test + lint (run before every commit; Python tests and compilation also run in CI)
python -m unittest discover -s tests       # no Discord connection needed
python -m pyflakes core/ cogs/ minecraft_bot/ tests/
python -m py_compile cogs/*.py minecraft_bot/*.py minecraft_main.py
(cd minecraft-bridge && ./gradlew clean test shadowJar)

# Optional local quality pass (ruff config in pyproject.toml; not yet in CI)
ruff check core/ cogs/ minecraft_bot/ tests/

# Local Minecraft test server (Paper 1.21.11, same build as production)
python scripts/testserver.py setup   # once: fetch Paper, Floodgate, Geyser, LuckPerms
python scripts/testserver.py run     # build the plugin, install it, start the server

# Deploy (BisectHosting panel auto-pulls main on restart)
python panel.py restart
python panel.py status         # expect: running
```

## Workflow — the PR loop

Branch off `main`, never commit to it directly (a ruleset rejects direct pushes).
Branch prefixes: `fix/` `feat/` `chore/` `refactor/`. Full human-facing version in
`CONTRIBUTING.md`.

```bash
git checkout main && git pull
git checkout -b fix/short-description
# ...edit, then run the test+lint block above...
git add <specific files>       # stage by name; review what git status shows
git commit -m "fix: what changed and why, under 72 chars"
git push -u origin fix/short-description
& "C:\Program Files\GitHub CLI\gh.exe" pr create --title "..." --body "..." --base main
& "C:\Program Files\GitHub CLI\gh.exe" pr checks <number> --repo 9mits/custom-discord-bot
```

`gh` lives at `C:\Program Files\GitHub CLI\gh.exe` on the Windows dev machine
(authed as `9mits`, often not on PATH — use the full path). On the macOS dev
machine it is at `~/.local/bin/gh`; auth per invocation via
`GH_TOKEN=$(printf 'protocol=https\nhost=github.com\n' | git credential fill | grep '^password=' | cut -d= -f2)`
(the keychain token lacks the scope `gh auth login --with-token` wants).
Both `test (3.11)` and `test (3.12)` must pass; fix failures on the branch rather
than working around the gate. When CI is green, automatically complete the
merge-and-deploy sequence unless the user explicitly requested a hold:

```bash
& "C:\Program Files\GitHub CLI\gh.exe" pr merge <number> --squash --delete-branch
git checkout main && git pull
python panel.py restart && python panel.py status
```

`main` is protected by GitHub ruleset `18121569`: PR required, both CI checks
required, 0 approvals (so solo merge works), no force-push or deletion.

## Workflow — posting a server update to the dev blog

When asked to put an update on the dev blog, the whole job is
`devblog/blog.py`. Never hand-edit git for this.

```bash
python devblog/blog.py new "Crates & Cosmetics!" --category Update
# ...write the post...
python devblog/blog.py preview
python devblog/blog.py publish
```

Upcoming-event announcements are a separate collection and never count as a
server update. Scaffold them as private drafts; the Events index and nested URL
are generated automatically:

```bash
python devblog/blog.py new-event "Event Name" --date "YYYY-MM-DD HH:MM"
python devblog/blog.py preview
# remove `draft: true` only when the schedule and rewards are confirmed
python devblog/blog.py publish
```

Do not create an event post merely because event tooling shipped. The user must
actually ask to announce a specific event.

### Work out what the update actually is

`new` prints it. Every post records a `covers:` commit in its front matter, so
the next post knows exactly where the previous one stopped, and `new` reports
`git log covers..HEAD` with the blog's own commits excluded — a post about the
server must never describe a change to the website.

**That range is the post.** Anything outside it either shipped in an earlier
post or has not shipped at all. If the user also pastes a Discord changelog,
that is the authoritative wording of what players get; the commit range is how
you check nothing was missed. Where the two disagree, ask rather than guess.

Posts written before `covers:` existed fall back to the previous post's date.

### Give the human an exact screenshot brief first

Before drafting a post, turn the confirmed update range into a numbered shot
list. Never ask vaguely for "screenshots." For every image, specify the filename,
feature/section, exact location and action or open menu, required players/items,
`/mgxadmin devblog` camera/time/weather/armour/player settings, whether F1 should
hide the HUD, and the intended framing. Identify the cover and card-icon assets.
Ask for original, native-resolution files; use 16:9 landscape for action/world
shots and a centred complete panel for UI shots. Keep the list economical: one
designed cover plus one screenshot or purposeful pair per major visual beat.
Do not ask for an image when text communicates the change better.

The home-page/social `cover:` must be designed editorial artwork made from real
feature assets — never a raw gameplay screenshot. Any capture showing a hotbar,
scoreboard, chat, profile panel, debug text, cursor, or unrelated UI is body-only
evidence. `hero:` is optional and only for a deliberately clean article opener;
do not add one merely because the field exists.

### The house style

Modelled on the BIG Games / Pet Simulator 99 update posts. Read a published one
first — `devblog/posts/2026-08-22-update-2.md` is the reference.

- **`## Section`** — the big orange underlined header. One per beat of the
  update: `Featuring`, `Changes`, whatever the update needs.
- **`### 🎁 Feature`** — bold sub-header, usually emoji-led. One per feature.
- **One idea per line.** Single newlines are real line breaks, and that centred
  line-per-beat rhythm *is* the format. A paragraph of prose breaks it.
- **Bold the nouns that matter** — commands, item names, numbers.
- **An italic line closes a section**, and often lands a joke.
- `tagline:` is the emoji-led hype line; it becomes the card excerpt and the
  link preview, so it carries the whole update in one sentence.
- `signoff:` is the bold closing line.
- **Match the reference voice, not just its Markdown.** Write like a reveal:
  short, confident, excited beats; active player verbs; the reward, risk or
  surprise first; and a playful dare, joke or warning to close. Let images do
  the explaining. Avoid release-note narration such as "this is deliberate,"
  implementation history, and cautious prose unless it prevents a player from
  making a real mistake. The supplied **Piñata Maze** and **Void RNG Event**
  pages are the tone benchmark.

Emoji are correct here and are the one place this repo's no-decorative-emoji
convention does not apply — that rule is about the Discord bot's own output.

### Rules

- **Never invent a feature, number, or reward.** Only what is in the changelog
  or the commit range.
- **Never fabricate a screenshot**, and never describe one that does not exist.
  In game, `/mgxadmin devblog` is the screenshot mode that makes taking a real
  one easy — it stashes the operator's gear, hides the sidebar and other
  players, and fixes their sky.
  Images are supplied by a human, or come from real assets in this repo (see
  `devblog/README.md`). A missing image is a missing image; `blog.py check`
  fails on a reference to one, which is the correct outcome.
- **`publish` stops on a failing check** and nothing reaches the site. Fix the
  cause, then run it again — it reuses the open pull request.
- Not shipped yet? `draft: true` keeps it in `preview` and off the live site.

## Environments

| Stage | Entry point | Tokens | Runs on |
|---|---|---|---|
| local | `python -m unittest …` | none | dev machine |
| staging | `python run_test.py` | `.env.test` only | dev machine |
| production | `python panel.py restart` | `.env.bot1` + `.env.bot2` + optional `.env.minecraft` | BisectHosting panel |

`minecraft-bridge/` has its own local stage: `scripts/testserver.py` runs the
same pinned Paper build as production in the git-ignored `runtime/testserver/`,
with Floodgate (a hard `depend:`), Geyser and LuckPerms alongside. It is
deliberately not a copy of production — offline mode and no whitelist so alt
accounts can join to test the multiplayer events, no resource pack so a slow
GitHub cannot stall a test, and a bridge URL pointing at a local port that need
not be listening. Everything that does not need Discord works without a bot.
Use it before the SFTP deploy in non-negotiable 2, not instead of it.

The Minecraft EULA is left unaccepted; flipping `eula=true` is the user's to do.

The staging bot runs locally, not on the panel. The panel runs `start.py`, which
picks up `.env.bot1`, `.env.bot2`, and `.env.minecraft` when present (`.env.test`
is never loaded, so the test token cannot double-run).

## Hosting — BisectHosting (Pterodactyl)

One panel server, ID `19d7e6d1`, at `games.bisecthosting.com`. On each restart it
auto-pulls `main` from `github.com/9mits/custom-discord-bot` and reinstalls pip
deps — so `panel.py restart` is the deploy. There is no SSH or systemd; the panel
API is the only remote control path.

- `panel.py` wraps the Pterodactyl client API (stdlib only). Creds come from the
  git-ignored `.panel.env` next to it.
- `panel.py` sends a browser User-Agent because Cloudflare blocks urllib's default
  (error 1010).
- `core/bot.py:on_ready` prints `successfully finished startup` — the panel scans
  stdout for that exact phrase to flip `starting` → `running`. Keep it.

## Hosting — GravelHost (Minecraft)

The Paper server is **not** on the Pterodactyl panel — `panel.py restart` only
redeploys the Discord bots and does nothing for the plugin. GravelHost exposes no
API, so **SFTP is the only deploy path**, using the git-ignored `.env.gravel`
(`GRAVEL_SFTP_HOST/PORT/USERNAME/PASSWORD`, `GRAVEL_SERVER_ROOT`).

Deploy sequence, required by non-negotiable 2 after any `minecraft-bridge/` change:

1. Bump `version` in `build.gradle.kts`.
2. `./gradlew clean shadowJar`. **Clean matters** — it restamps `plugin.yml`.
   Ship `build/libs/MGXAccessBridge.jar`, the shaded jar, never a versioned thin one.
3. Check `api-version` in the built `plugin.yml` is **<=** the running server's
   Minecraft version, read from `logs/latest.log` (`Starting minecraft server
   version …`). Too high and Paper refuses the plugin outright with
   `InvalidPluginException: Unsupported API version` — in game that reads as the
   whole server being gone.
4. Copy the jar to a **space-free path** first. The repo lives in
   `~/Documents/Discord Bot`; the space splits the argument and the upload
   silently writes a fragment into a mirrored directory tree while reporting 100%.
5. Upload, then verify the **remote byte count and sha256 match the local file**.
   That check is what catches step 4 going wrong.
6. Rename the live jar to `MGXAccessBridge.jar.backup-<stamp>`, then rename the
   upload into place. Put-then-rename is atomic and the JVM keeps its handle on
   the old inode, so swapping under a running server is safe.

**Restarting the Minecraft server is the user's step** — there is no API for it.
Say so explicitly every time a change lives in the plugin. Plugin config edits
(`plugins/*/config.yml`) go over the same SFTP path and need the same restart.

Some behaviour has no plugin API and lives only in the server's own config, which
is not in git — read the live file over SFTP before changing it. How far away a
mob stays visible is one: `spigot.yml` `entity-tracking-range` is per category
(a ghast is `monsters`), it defaults to 48 blocks against vanilla's 160, and it
is capped by the view distance the plugin sets in `world.max-view-distance`.

## Layout

```
main.py / start.py / run_test.py / panel.py   entry points (see Commands)
minecraft_main.py / minecraft_bot/             isolated Minecraft access bot
minecraft-bridge/                               Java 21 Paper/Floodgate plugin
pyproject.toml   project metadata + tool config (ruff); deps stay in requirements.txt
core/      framework, no Discord UI code
  bot.py        MGXBot class, intents, background tasks, EXTENSIONS, lifecycle
  data.py       DataManager (persistence), AntiAbuseSystem, resolve_bot_token
  services.py   config validation, escalation matrix, normalization
  constants.py  IDs, brand strings, colours, scope labels, TOKEN_ENV_VARS
  context.py    proxy singletons: bot, tree, abuse_system
  models.py     dataclasses shared across services (currently ValidationFinding)
  utils.py      stateless helpers: parse_duration_str, format_duration, truncate_text
  project_stats.py  cross-instance fleet snapshots powering /about (shared folder)
cogs/      one discord.py extension per domain
  shared.py            embed builders, log senders, permission checks (no Cog class)
  cases.py / history.py / case_panel.py   case mgmt, history UI, transcript export
  moderation.py        execute_punishment, ModGroup commands, /punish
  roles.py / derole.py custom booster roles; bulk role removal
  modmail.py           ticket relay, control/panel views
  automod.py           native + smart automod engine, /automod
  config.py            /setup, /config and settings views
  analytics.py         /stats, /directory, staff profiles
  admin.py             admin commands, anti-nuke, branding
  events.py            raw @bot.event listeners + native AutoMod bridge
  event_leaderboard.py VC-time leaderboard (gated on EVENT_CONTROL=1)
  registry.py          documents the cog dependency graph
  testkit.py           test-only cog, loaded only under TEST_MODE=1
tests/     unittest suite (no real Discord connection)
```

## Architecture notes

Read the relevant file when you touch an area; these are the non-obvious points.

- **Startup:** `core/bot.py:run()` → `setup_hook` opens the SQLite DB, loads all
  state into memory, restores persistent views, loads `EXTENSIONS`, starts the
  background task loops. `testkit` loads only under `TEST_MODE=1`.
- **Data:** `DataManager` holds everything in memory and persists to
  `<BOT_DATA_DIR>/bot.db` (aiosqlite; defaults to `database/`). First run
  auto-migrates legacy `*.json` to SQLite. Persist through the dirty-flag methods
  (`mark_config_dirty()`, `save_punishments()`, …), not `save_all()` directly.
- **Cogs:** each domain file defines a `*Cog` and `async def setup(bot)`. Slash
  commands are module-level `@tree.command` functions registered via
  `core/context.tree` at import; `setup()` only adds the Cog and its listeners.
- **Command sync:** `on_ready` auto-syncs the tree once per process (guild-scoped,
  instant) so each single-guild instance keeps its own guild current on deploy (=
  panel restart) with no manual step. Target via `_resolve_sync_targets`: under
  `TEST_MODE=1` strictly `TEST_GUILD_ID` (no fallback, so staging can't leak into
  a live server); in production the configured `guild_id` when the bot is a member
  of it, else a fallback to whatever guild(s) the bot is actually in (covers a
  fresh instance pre-`/setup`). A per-guild command fingerprint is cached in
  `config[synced_command_fingerprint_<guild>]` so unchanged restarts skip the API
  call (rate-limit safety). The `!sync` prefix command in `admin.py` stays as a
  manual override; sync runs after the panel's startup print and never blocks it.
- **Circular imports:** `shared.py` ↔ `automod.py`/`cases.py`/`roles.py`/
  `modmail.py`/`admin.py` are mutually dependent. Resolve any new cross-domain
  call with a lazy import inside the function body, matching the existing pattern.
  Do not hoist these to module top level — it reintroduces the cycle.
  `cogs/registry.py` has the full graph.
- **Context proxies:** import `bot`, `tree`, `abuse_system` from `core/context.py`
  rather than threading the bot instance through call signatures.
- **Tokens:** `resolve_bot_token()` checks `config.json:"token_env_var"`, then
  falls back through `TOKEN_ENV_VARS` (`DISCORD_BOT_TOKEN`, `MBX_BOT_TOKEN`).
- **Fleet stats (`/about`):** each instance is single-guild with its own DB, so no
  process can read a sibling's *live* numbers (member_count lives in the gateway
  cache). `core/project_stats.py` bridges this by having every instance write a
  JSON snapshot of its own stats into a shared `project_stats/` folder (one file
  per bot user id, refreshed by `project_stats_task` every 5 min); `/about` sums
  all snapshots. `project_stats.py` imports only stdlib + discord — never `cogs/`.
- **Minecraft bridge protocol:** `BridgeClient.PROTOCOL_VERSION` (Java) and the
  `*_PROTOCOL_VERSION` constants in `minecraft_bot/bridge.py` must move together;
  a test parses the Java and fails if they drift. Each capability is gated on the
  peer's advertised number so an older plugin keeps working. v7 added
  `SERVER_EVENT`, which reports in-game actions to the Discord activity log —
  actions arriving *from* Discord are audited by the bot instead, so the plugin
  must not report those or every entry doubles.
- **Discord rank sync:** `LuckPermsService` removes only the group it recorded
  granting in `RankSyncStore`, so a group set by hand in LuckPerms survives a
  sync. `/mgxadmin ranks hold <player>` takes a player out of sync entirely,
  which is how someone gets a rank Discord will never agree with.
- **`discord_username` in SYNC_PROFILE is three-valued.** Present and non-empty
  sets the name; present and empty means "no linked account" and makes the
  plugin *forget* the cached one; absent means "could not determine" and leaves
  it alone. Never collapse the last two — clearing on any empty value drops
  every player's name the first time a Discord lookup fails. `link_known`
  controls which is sent, mirroring `rank_known` for LuckPerms groups.
- **Maintenance mode** (`/mcadmin maintenance`) closes the server to everybody
  except operators and staff holding `mgxaccessbridge.admin` — already-whitelisted
  regular players are turned away on both editions. The exemption deliberately
  reuses `mgxaccessbridge.admin`, the same permission that already gates
  `/mgxadmin`, instead of a maintenance-only node: a dedicated
  `maintenance.bypass` permission existed once and defaulted to `op`, which
  covered every operator without anyone consciously granting it, so the hold
  looked enabled while the people testing it walked straight in. Reusing an
  already-audited permission means access is never silent — an operator gets in
  because they are an operator. `bypassesMaintenance` is `isOp()` or an
  **explicit** LuckPerms grant of `mgxaccessbridge.admin` on the user or a group
  they inherit. `checkPermission` and Bukkit `hasPermission` are not used: both
  honour `default: op`, which Floodgate users inherit, and that produced
  `op=false bypass=true` for a default Bedrock account. There is no dedicated
  maintenance bypass node. The login refusal, join handler, sweep, and kick of
  anyone already online when the hold is enabled all use that same check.
  Nothing in Discord branches on the flag; the plugin states the closure on its
  own kick screen, where it is true at the moment it is read. Verification
  still carries on regardless, so a held server keeps linking accounts.
  Enforced in three event places plus a 1-second sweep of whoever is online,
  because Floodgate's 1.21 path does not honour a `PlayerLoginEvent` refusal the
  way Java does and Geyser can skip those events entirely: `onPlayerPreLogin`
  (`AsyncPlayerPreLoginEvent` at `HIGHEST`) is what Floodgate actually consults
  before starting client verification; `onPlayerLogin` at `HIGHEST` still rewrites
  the result to `KICK_OTHER` after Floodgate has re-allowed whitelisted Bedrock
  players, and a `MONITOR` pass undoes a later re-allow; a join handler then kicks
  anyone who reaches the world, with delayed retries, because Geyser drops a kick
  issued during `PlayerJoinEvent` while the Bedrock client is still spawning.
  The sweep is what actually keeps the world empty — a never-seen default Bedrock
  account (no op, no permission) walked through every event-only hold, and
  Bukkit `hasPermission` must not be the bypass check: Floodgate players can
  report true for `default: op` nodes before attachments exist. Use `isOp()` and
  LuckPerms' explicit nodes only.
  Verification is matched **before** the hold is applied and never needed the
  login to succeed — an applicant is turned away either way and only the wording
  differs — which is what lets a closed server still verify accounts. Leaving
  `KICK_WHITELIST` in place is what let Floodgate's whitelist fixer wave Bedrock
  logins through. Paper persists the flag itself, because it accepts logins
  before the bridge connects; the bot restates it on connect so Discord stays the
  authority. If the bot is unreachable, delete
  `plugins/MGXAccessBridge/maintenance.flag` and restart.
- **Overworld spawn is locked to `0 69 0`.** `WorldSpawn` sets the block and
  `spawnRadius` 0 so vanilla cannot scatter joins. `SpawnChangeEvent` puts it
  back if anything else moves it. Bed and respawn-anchor deaths are left alone.
  `WorldMemory` caps view/simulation distance (defaults 6 / 4). A 3x3 plugin
  ticket stays around the spawn chunk
  so a death far from 0,0 can still respawn. `WorldLimits` sets the overworld
  border to 100,000 blocks from spawn (nether 1/8) — the red fog and sounds
  past a small border are vanilla's warning, not a broken world.
  `/mgxadmin startserver` unloads each barrier scan chunk it had to load, so
  the launch pass cannot leave ~1,250 chunks in RAM. Set
  `world.max-view-distance: 0` in the plugin config to leave the panel value
  alone.
- **In-game money is not leaderboard wealth.** `/shop`, `/sell`, `/ah`, `/bal`
  and `/pay` use `EconomyStore` (`balances.json`). The richest-player board still
  sums wallets. Clan richest is donated treasury, not member wallets.
  Auction listings live in `auctions.json`;
  opening a page deserialises only the 45 visible stacks. Shop prices are the
  static `ShopCatalog`. Elytra, netherite, totems, shulker shells and enchanted
  golden apples are not sold.
- **Player activity statistics are event-backed.** Paper includes the exact online
  count and original event timestamp with every `PLAYER_JOIN` / `PLAYER_LEAVE`;
  the Discord bot stores those samples in `minecraft_player_activity`, and
  `/mcstaff stats` reports peaks and busiest JST windows for the selected period.
  Floodgate identity is cached at join so a Bedrock quit cannot be misreported as
  Java after Floodgate has already removed its live player object.
- **AFK and screen placement are deliberately separate.** `/afk` and the automatic
  five-minute idle detector are edition-neutral and mark the tab list; block
  movement is used so Bedrock camera jitter is not activity. Teleport warmups use
  the bottom action bar once per second, while `/broadcast` uses a timed top boss
  bar. Keep `player-idle-timeout=0` so Paper does not kick a player the plugin has
  intentionally marked AFK.
- **Cosmetic serial reset preserves the cosmetics.** `/mgxadmin serials reset
  <cosmetic-id> confirm` deterministically renumbers only that cosmetic, keeps
  custody and equipped selections, and refreshes carried tokens. Tokens stored in
  containers show old lore until they are picked up or moved through the wardrobe;
  the `CosmeticStore` value is canonical.
- **Verification is the only gate on Minecraft access.** There is no
  application form, no review queue and no staff approval: a member declares a
  username in Discord, joins once with that account, and the plugin *allows* the
  login rather than kicking them. `AccessStatus` is `PENDING_VERIFICATION`,
  `VERIFIED`, `EXPIRED`, `CANCELLED`, `REVOKED` — the old ladder
  (`PENDING_APPLICATION`, `PENDING_REVIEW`, `APPROVAL_QUEUED`, `DENIED`) is gone
  and schema 8 migrated it away. `VERIFIED` is set the instant ownership is
  proved; the durable `APPROVE` outbox adds the real whitelist entry a moment
  later, and `verified-applications.json` on the plugin side is what lets a
  reconnect inside that window through. Keep that filename — renaming it orphans
  live verification state on the server.
- **Resetting data is two commands, one per side.** `/mgxadmin reset all
  confirm` (in game) clears what Paper keeps; `/mcadmin wipe` (Discord, owner
  only) clears the bot's SQLite. Neither can reach the other's data, so a full
  reset needs both. The reset never opens the world, and deliberately keeps
  `ops.json`, bans, and rank holds — clearing ops would lock the operator out of
  the command itself.
- **Deps:** `pip install -r requirements.txt` (discord.py>=2.6, aiohttp>=3.13,
  aiosqlite>=0.22, python-dotenv).

## Conventions

Match the surrounding code; these are the project-specific choices that aren't
obvious from a single file.

- **Write user-facing output without decorative emoji.** The only allowed emoji
  are functional reactions with no text equivalent: the public-execution vote `✅`
  (added in `moderation.py`, counted in `events.py`) and the modmail relay markers
  `✅`/`📨` (`events.py`). Code comments and docstrings are exempt.
- **Set embed footers to the brand name alone** (no scope label) so they don't
  wrap on narrow clients — done in `make_embed`/`brand_embed` in `shared.py`.
- **Resolve a target to a full guild `Member` before acting on it** with
  `resolve_member()` and the `UserSelect` pickers; the native slash `user:` picker
  is client-side and silently drops some real members. `/punish` keeps a `user_id:`
  fallback for members the picker can't reach.
- **Match Components V2 (buttons + dropdowns) on the panels that already use it**
  rather than dropping back to plain embeds on that surface.
- **Write commit subjects as `type: summary`** (`fix`/`feat`/`chore`/`refactor`),
  under 72 chars.
- **Add a comment only when the "why" is non-obvious;** skip comments that restate
  what the code already says.
