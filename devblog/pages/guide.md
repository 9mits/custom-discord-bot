---
title: Server Guide
nav: Guide
order: 2
tagline: Everything the in-game information panel covers — commands, clans, levels, perks, mods and version support.
---

This page mirrors the **information panel** in Discord. It is generated from the bot's own copy, so the two never drift.

## Commands

Everything available to you in game.

### Most used

`/shop` · `/sell` — buy building blocks, or sell farm bulk
`/bal` · `/pay <player> <amount>` — your wallet
`/sethome` · `/home` — save a spot and return to it
`/tpa <player>` — ask to teleport to someone

### Full list

The buttons below cover homes and travel, teleports, communication, economy, clans, and your account.

### Commands — Homes & Travel

Saving locations and moving between them.

#### Homes

`/sethome [name]` — save your current location
`/home [name]` — travel to a saved home
`/delhome <name>` — remove one
`/renamehome <old> <new>` — rename one

#### Travel

`/back` — return to your previous location
`/warp [name]` — list available warps, or travel to one

#### Worth knowing

**3 homes** included
Travel pauses **5 seconds**, and cancels if you move or take damage

### Commands — Teleports

Teleporting to other players is by request; both sides must agree.

#### Sending a request

`/tpa <player>` — ask to teleport to them
`/tpahere <player>` — ask them to teleport to you
`/tpacancel` — withdraw your request

#### Answering a request

`/tpaccept` — accept
`/tpdeny` — decline
`/tptoggle` — stop receiving requests entirely

#### Timing

**5 second** wait, cancelled if you move or take damage
**30 seconds** between uses

### Commands — Communication

Talking to players in game, whether or not they are online.

#### Private messages

`/msg <player> <message>` — send a private message
`/r <message>` — reply to the last message received
`/msgtoggle` — stop receiving private messages
`/ignore <player>` — mute someone privately

#### Offline and public

`/mail send <player> <message>` — message an offline player
`/mail read` — read your mail
`/me <action>` — emote in chat
`/afk [reason]` — mark yourself away

#### Needing help

`/helpop <message>` — reach whoever is on duty
For rule breaking, or anything needing intervention

### Commands — Economy

Money in your wallet, and money on other players.

#### Shop and sell

`/shop` — buy from the server
`/sell` — see what you can sell, then sell a stack or all of it
`/sell hand` · `/sell all` — sell what you are holding, or everything the server buys
Elytras, netherite, totems, shulker shells and enchanted golden apples are not sold

#### Auction house

`/ah` — browse listings
`/ah sell <price>` — list the item in your hand
`/ah listings` · `/ah expired` · `/ah search <name>`

#### Wallet and bounties

`/bal` · `/pay <player> <amount>` — your wallet, and sending money
`/bounty` — board of every pot, highest first
`/bounty set <player> <amount>` — put money on someone's head
`/bounty clan <player> <amount>` — clan owner, paid from the treasury
`/bounty check` — look up one player

### Commands — Clans

Founding, joining and running a clan.

#### Everyday

`/clans create <name>` — found a clan and lead it
`/clans accept` · `/clans decline` — answer an invite
`/clans invite <player>` — invite an online player
`/clans chat` — speak to your clan only
`/clans list` — every clan on the server
`/clans` — the clan menu: donate, balance, members, upgrades
`/clans members` — the roster, with everyone's Discord name
`/clans leave` — depart your clan

#### If you lead one

`/clans promote` · `/clans demote` — manage clan staff
`/clans rename` · `/clans color` — change name or colour
`/clans transfer <player>` — hand over leadership
`/clans kick <player>` — remove a member
`/clans upgrade` — spend the balance on levels or slots
`/clans disband` — dissolve the clan

#### Inspecting

`/claninfo [name]` — any clan's card, and its roster
`/clans help` — only what your role currently permits

### Commands — Account

Your perks and preferences, plus details about the server.

#### Your account

`/perks` — your level rewards and bonuses
`/settings` — a panel of toggles: clan tags, Discord chat, and whether others see your Discord name
`/discordnames` — whether others see your Discord name
`/playtime` — time spent on the server
`/shop` — buy from the server shop
`/sell` · `/sell hand` · `/sell all` — sell items for money
`/ah` — auction house; `/ah sell <price>` lists the item in hand
`/bal` · `/pay <player> <amount>` — wallet and transfers
`/bounty set <player> <amount>` — put money on someone's head
`/bounty clan <player> <amount>` — clan owner, paid from the treasury

#### The server

`/list` — who is online
`/ping` — your connection latency
`/whitelisted [page]` — everyone with access
`/realname <name>` — look up a display name
`/rules` · `/motd` — rules and welcome text
`/guide` — the in-game guide
`/discord` — the community invite

#### In Discord

`/minecraft account` — your application and linked account
`/minecraft whitelist` — everyone with access
`/minecraft clan view` — your clan and permitted actions

## Clans

A clan is a named group with a shared tag and colour, shown beside your name in chat, above your head, and in the player list.

### General information

Members **cannot damage each other**
Starts at **3 members**, upgradeable to **25**
**5 levels**, bought from a shared treasury
Donate money; nobody can take it back
Join by invite, or start your own with `/clans create`

### Roles inside a clan

**Member** — clan chat, the roster, and donate
**Staff** — the above, plus invite, kick, and upgrades
**Leader** — the above, plus rename, colour, promote, transfer and disband

### Clans — Levels

A clan climbs on what its members donate, through **5 levels**. Every perk applies to **everyone in the clan**, and stacks on top of the perks your Discord level already gives you.

#### Donating

`/clans` — open the clan menu
`/clans donate` — give money from your wallet
`/clans donate <amount>` — a custom sum
`/clans members` — every member, their role and Discord name
`/clans balance` — the clan treasury
`/clans donors` — who has given what, largest first
`/clans upgrade` — leader or clan staff; spends the treasury

**Donations are one way.** Nobody can take money back out, and disbanding the clan destroys the balance with it.

#### What the levels give

Extra hearts, and bonuses to strength, saturation, digging speed, resistance and speed — every one of them shared by the whole clan.
The upgrade menu shows what your next level costs and what it grants. Find out how far you can get.

#### Keeping the perks

Perks last exactly as long as your membership. Leave the clan or get kicked and they stop at once.
A star beside a clan tag shows its level by colour, so you can read it at a glance in chat, the player list and above someone's head.

### Clans — Roster

A new clan holds **3 members**. Room for more is bought from the clan balance, the same way levels are.

`/clans upgrade` — leader or clan staff; the roster track sits beside the level track
**One member at a time**, so the next slot is always in reach
Every slot up to **25** has to be earned
Invites are refused once the roster is full

Each slot costs more than the last. The menu quotes the next one when you open it.

### Clans — Roles

Three ranks, each able to do everything the one below it can.

#### Member

`/clans chat` — speak to your clan only
`/clans info` · `/clans list` — the roster and every clan
`/clans leave` — depart the clan

#### Staff

Everything a member can, plus:
`/clans invite <player>` — bring someone in
`/clans kick <player>` — remove a **member**

#### Leader

Everything staff can, plus:
`/clans rename` · `/clans color` — change name or colour
`/clans promote` · `/clans demote` — manage clan staff
`/clans transfer <player>` — hand over the clan
`/clans disband` — close the clan for everyone

#### Who can remove whom

Staff can kick members
**Only the leader can remove** another staff member
The **leader cannot be kicked** by anyone

*Promoting somebody puts them beyond everyone's reach but yours.*

### Clans — Joining

Clans are invitation only. You cannot join one by asking the server.

#### Getting invited

A leader or staff runs `/clans invite <player>`
You must be **online** to receive it
It expires after **5 minutes**

Answer with `/clans accept` or `/clans decline`

#### Starting your own

`/clans create <name>` — founds it and makes you leader
The name must not already be taken
`/clans list` — see what already exists

**Colours** — orange, gold, yellow, red, pink, purple, blue, aqua, green, white

#### When a clan is full

A clan can only hold as many members as it has bought room for, starting at **3**. At that point no further invites can be accepted, and an outstanding one fails when used. Buy another roster slot, or somebody has to leave first.

### Clans — Leaving

How to depart a clan, hand it over, or close it entirely.

#### Members and staff

`/clans leave` — immediate, no confirmation asked

*You keep everything you own. Only the tag and the damage immunity go with it.*

#### The leader cannot simply leave

**Transfer or disband first** — there is no other way out

*This stops a clan being stranded with a full roster and nobody able to run it.*

#### Handing it over

`/clans transfer <player>` — they become leader
You stay in the clan as **staff**, not removed

*You keep invite and kick; renaming, promoting and disbanding pass to them.*

#### Disbanding

`/clans disband` — leader only, cannot be undone

*It closes the clan for everyone at once, not just for you.*

## Levels and Perks

Chatting in text channels and talking in voice earns Discord levels, which become permanent bonuses in Minecraft.

### What each milestone gives you

**Level 5** — **1 extra heart**
**Level 10** — **2 extra hearts**
**Level 20** — **3 extra hearts**
**Level 30** — **4 extra hearts**
**Level 40** — **5 extra hearts**
**Level 50** — **5 extra hearts** and **+15% damage**

### It all stacks

Milestones add up — the figure beside each role is your total
**Boosting** — +1 heart and +10% damage on top of your level
**Maximum** — 6 extra hearts and +25% damage

### Checking yours

`/perks` — your level, hearts and damage bonus
The sidebar shows the same while you play
How levelling works: [read it in Discord](https://discord.com/channels/1476839721731620938/1476839722734190647)

## Boosting

Boosting the Discord server adds bonuses on top of your level rewards.

### What boosting adds

**+1 extra heart**
**+10% damage**
**Hunger drains 10% more slowly**

### How it stacks

Added to your level rewards, never instead of them
Damage adds rather than multiplies — level 50 plus boosting is **+25%**
**Maximum** — 6 extra hearts and +25% damage

### If you stop boosting

Your boosting perks are removed immediately
Keep boosting to keep them

*Your level rewards, rank and clan are unaffected.*

## Mods and Voice Chat

Mods that change how the game looks or runs are fine. Mods that show you what you could not see, or play the game for you, are not.

### Voice chat

The server officially supports **[Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat)**, which lets you talk to players standing near you.
Install the version matching the Minecraft version you play on.
**Java only — see below for Bedrock.**

### Examples of permitted mods on Java

**Performance** — **[Sodium](https://modrinth.com/mod/sodium)**, **[Lithium](https://modrinth.com/mod/lithium)**, **[OptiFine](https://optifine.net/downloads)**
**Shaders** — **[Iris Shaders](https://modrinth.com/mod/iris)**
**Mapping** — **[Xaero's Minimap](https://modrinth.com/mod/xaeros-minimap)** or **[JourneyMap](https://modrinth.com/mod/journeymap)**, with cave mapping and player radar off
**Building** — **[Litematica](https://modrinth.com/mod/litematica)**, printer included
**Quality of life** — **[AppleSkin](https://modrinth.com/mod/appleskin)** and similar
**Loader** — most of these need **[Fabric](https://fabricmc.net/use/installer/)**

### Clients

Custom Minecraft clients such as **[Lunar Client](https://www.lunarclient.com)** and **[Feather](https://feathermc.com)** are permitted, and come with most of the above already installed.

### Not allowed on any edition

**Seeing what is hidden** — X-ray, ore and cave finders, freecam, tracers, player radar
**Playing for you** — kill aura, aim assist, auto-clickers, auto-walk
**Changing what your character can do** — extra reach, speed, flight, no fall damage

*Examples of each kind, not the full list.*

### Bedrock

Bedrock cannot install mods at all, so nothing above applies to you.
**Simple Voice Chat does not work on Bedrock.** There is no way to add it.
Join a Discord voice channel instead.

## Server and Versions

What the server runs, and how to connect on each edition.

### Software

**[Paper](https://papermc.io)** 1.21.11 — the server
**[Geyser](https://geysermc.org)** — lets Bedrock players in
**[ViaVersion](https://modrinth.com/plugin/viaversion)** and ViaBackwards — translate Java clients in both directions

### Java Edition

**1.21.6 and newer** — including the current release
No launcher changes needed; play on whatever you already use

Add the server under **Multiplayer → Add Server**
```text
given to you when your application is accepted
```

### Bedrock Edition

Any current version, from phone, console, tablet or Windows
Add it as an external server with both values below

**Address**
```text
given to you when your application is accepted
```
**Port**
```text
19132
```
