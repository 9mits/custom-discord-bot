# Command reference

Generated from the route table in `MgxCommandRouter`, which is also what
dispatches and what tab completion reads. It cannot describe a command that
does not exist, or miss one that does.

Everything is `/mgx <area> <thing> <verb>`. Tab completion offers each level,
and `/mgx help <area>` prints just that area.

## Access tiers

| Tier | Permission | Covers |
|---|---|---|
| Staff | `mgx.admin.staff` | Day-to-day running: events, giving rewards, PvP status |
| Manage | `mgx.admin.manage` | Staff, plus world, economy, players and the server |
| Owner | `mgx.admin.owner` | Everything, including live configuration and data resets |

Operators are deliberately **not** administrators here. The tier node is the
only thing consulted, so access is always something somebody granted rather
than something inherited. The legacy `mgxaccessbridge.admin` node keeps
working, so nobody loses access on upgrade.

## /mgx world

| Command | Tier | What it does | Replaces |
|---|---|---|---|
| `/mgx world hologram create` | Manage | place a leaderboard or crate hologram | `/mgxadmin hologram` **+** `/cratehologram` |
| `/mgx world hologram list` | Manage | every hologram, with its id | `/mgxadmin hologram` **+** `/cratehologram` |
| `/mgx world hologram delete` | Manage | remove one by id, or the nearest | `/mgxadmin hologram` **+** `/cratehologram` |
| `/mgx world hologram reload` | Manage | redraw them all | `/mgxadmin hologram` **+** `/cratehologram` |
| `/mgx world spawn show` | Manage | where spawn is | *new* |

## /mgx event

| Command | Tier | What it does | Replaces |
|---|---|---|---|
| `/mgx event list` | Staff | every event control | `/mgxadmin event list` |
| `/mgx event multiplier enable` | Staff | start a multiplier | `/mgxadmin multiplier` |
| `/mgx event multiplier disable` | Staff | end a multiplier | `/mgxadmin multiplier` |
| `/mgx event multiplier show` | Staff | what is running | `/mgxadmin event multiplier` |
| `/mgx event airdrop start` | Staff | call in an Airdrop | `/mgxadmin airdrop` |
| `/mgx event airdrop show` | Staff | Airdrops standing now | `/mgxadmin event airdrop status` |
| `/mgx event airdrop delete` | Staff | end the standing Airdrops | `/mgxadmin event airdrop end` |
| `/mgx event airdrop schedule` | Manage | how often they arrive | `/mgxadmin event schedule` |
| `/mgx event amethyst start` | Staff | place a Huge Amethyst Block | `/mgxadmin event amethyst-block start` |
| `/mgx event amethyst show` | Staff | the block standing now | `/mgxadmin event amethyst-block status` |
| `/mgx event amethyst delete` | Staff | remove the standing block | `/mgxadmin event amethyst-block end` |
| `/mgx event chaos list` | Staff | every admin event | `/mgxadmin event admin controls` |
| `/mgx event chaos start` | Staff | run an admin event | `/mgxadmin abuse` |
| `/mgx event clanbattle start` | Manage | open a clan contest | `/mgxadmin clanbattle start` |
| `/mgx event clanbattle show` | Staff | standings now | `/mgxadmin clanbattle status` |
| `/mgx event clanbattle disable` | Manage | close it and pay out | `/mgxadmin clanbattle end` |
| `/mgx event clanbattle delete` | Owner | abandon it, paying nobody | `/mgxadmin clanbattle cancel` |

## /mgx economy

| Command | Tier | What it does | Replaces |
|---|---|---|---|
| `/mgx economy balance edit` | Manage | add, take or set money | `/mgxadmin eco` |
| `/mgx economy joinbonus enable` | Manage | pay everyone who joins | `/mgxadmin eco join on` |
| `/mgx economy joinbonus disable` | Manage | stop paying joiners | `/mgxadmin eco join off` |
| `/mgx economy bounty create` | Manage | put money on a head | `/mgxadmin bounty set` |
| `/mgx economy bounty joinbonus` | Manage | bounty everyone who joins | `/mgxadmin bounty join` |

## /mgx player

| Command | Tier | What it does | Replaces |
|---|---|---|---|
| `/mgx player give` | Staff | hand someone a reward | `/mgxadmin give` |
| `/mgx player rank list` | Manage | everyone held out of rank sync | `/mgxadmin ranks list` |
| `/mgx player rank hold` | Manage | stop rank sync touching someone | `/mgxadmin ranks hold` |
| `/mgx player rank release` | Manage | hand them back to rank sync | `/mgxadmin ranks release` |
| `/mgx player cosmetic delete` | Owner | delete someone's cosmetics | `/mgxadmin cosmetics delete` |
| `/mgx player cosmetic reserial` | Owner | renumber one cosmetic | `/mgxadmin serials reset` |

## /mgx server

| Command | Tier | What it does | Replaces |
|---|---|---|---|
| `/mgx server pvp show` | Staff | is PvP on | `/mgxadmin pvp status` |
| `/mgx server pvp enable` | Manage | pin PvP on | `/mgxadmin pvp on` |
| `/mgx server pvp disable` | Manage | pin PvP off | `/mgxadmin pvp off` |
| `/mgx server launch start` | Owner | run the launch countdown | `/mgxadmin startserver` |
| `/mgx server update publish` | Manage | show everyone the update banner | `/mgxadmin update` |
| `/mgx server reset list` | Owner | what a reset can clear | `/mgxadmin reset` |
| `/mgx server reset run` | Owner | clear recorded progress | `/mgxadmin reset` |

## /mgx config

| Command | Tier | What it does | Replaces |
|---|---|---|---|
| `/mgx config list` | Owner | browse every live value | `/mgxadmin variables list` |
| `/mgx config show` | Owner | one value, with its range | `/mgxadmin variables get` |
| `/mgx config set` | Owner | change one value | `/mgxadmin variables set` |
| `/mgx config reset` | Owner | put one value back to its default | `/mgxadmin variables reset` |

## /mgx dev

| Command | Tier | What it does | Replaces |
|---|---|---|---|
| `/mgx dev crate reveal` | Owner | run a crate reveal without granting | `/mgxadmin testcrate` |
| `/mgx dev airdrop` | Owner | the local Airdrop harness | `/mgxadmin testairdrop` |
| `/mgx dev amethyst start` | Owner | exercise a Huge Amethyst Block | `/mgxadmin testamethystblock` |
| `/mgx dev verify reset` | Owner | unverify yourself | `/mgxadmin testverify reset` |
| `/mgx dev screenshot` | Owner | screenshot mode | `/mgxadmin devblog` |

## The old commands

`/mgxadmin`, `/mcadmin` and `/cratehologram` all still work, unchanged. Each
prints its replacement once per session per person — a nudge, not nagging.
Nothing is removed without a dev-blog post saying so first.

## Why the verbs are a closed set

The old surface had eight different words for ending something — `remove`,
`end`, `stop`, `expire`, `cancel`, `finish`, `delete` — and which one worked
depended on the subsystem. There are now two: `delete` removes a thing,
`disable` stops one. `MgxCommandRouterTest` fails if a route ends in a verb
outside the set, so a new area cannot invent its own word for an idea that
already has one.

