# AGENTS.md

Instructions for AI coding agents (Claude Code, Cursor, Copilot, Codex, Aider)
working in this repo: a Python Discord moderation bot (~16k LOC, discord.py 2.x,
SQLite). Solo developer, owner `9mits`. This file is the source of truth; tool-
specific files (`CLAUDE.md`) import it.

## Non-negotiables

These three rules override everything else. The rest of this file is guidance.

1. **Automatically merge and deploy completed work.** Run the whole loop
   autonomously — branch, code, test, push, open PR, watch CI, fix failures,
   squash-merge once every required check is green, then restart production and
   verify the panel reports `running`. Do not pause to ask for merge approval.
   Stop before merging or deploying only when the user explicitly says to hold,
   leave the PR open, or skip deployment.
2. **Keep secrets out of git.** `.env*`, `.panel.env`, `config.json`, and
   `database*/` are git-ignored and hold live tokens and user data. Stage files
   by name; read what `git status` shows before committing.
3. **Keep moderators anonymous in user-facing output.** Report DMs, the report
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

# Optional local quality pass (ruff config in pyproject.toml; not yet in CI)
ruff check core/ cogs/ minecraft_bot/ tests/

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

## Environments

| Stage | Entry point | Tokens | Runs on |
|---|---|---|---|
| local | `python -m unittest …` | none | dev machine |
| staging | `python run_test.py` | `.env.test` only | dev machine |
| production | `python panel.py restart` | `.env.bot1` + `.env.bot2` + optional `.env.minecraft` | BisectHosting panel |

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
- **Maintenance mode** (`/mcadmin maintenance`) is a pre-launch hold, not a
  whitelist change: applications, verification and acceptance all carry on, and
  Paper refuses the login instead. Enforced in the plugin at
  `EventPriority.HIGHEST`, judged on the **final** login result: Floodgate
  re-allows Bedrock players after the vanilla whitelist has refused them, so a
  handler reading the result earlier sees `KICK_WHITELIST`, leaves it alone, and
  lets Floodgate wave them through the hold. A `KICK_WHITELIST` result is still
  left untouched, so verification works against a closed server. A join handler
  kicks anyone who reaches the world regardless, since the login refusal depends
  on other plugins leaving the result alone. Paper persists the flag itself, because it accepts
  logins before the bridge connects; the bot restates it on connect so Discord
  stays the authority.
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
