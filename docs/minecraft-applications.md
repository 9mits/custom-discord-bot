# Minecraft applications and access bridge

## Architecture

The Minecraft application system is intentionally isolated from the two moderation bots:

```text
.env.minecraft
      |
      v
minecraft_main.py -> MinecraftAccessBot -> runtime/minecraft/minecraft.db
                              ^
                              | signed WSS, Paper connects outbound
                              v
                     MGXAccessBridge.jar -> Paper / Floodgate whitelist
```

`start.py` launches the Minecraft process only when `.env.minecraft` exists. That process loads only the `/minecraft` command group and the application/review components; it does not import the moderation cogs, command registry, or customer databases.

The bridge exposes an HTTP WebSocket path inside the Minecraft bot process. Production traffic must reach it through a TLS endpoint (`wss://`). The Paper plugin makes the outbound connection, so the Minecraft server does not need an inbound bridge port and RCON is never used.

Every bridge message carries an HMAC-SHA256 signature, timestamp, random nonce, and idempotency key. Both sides reject messages outside a 30-second clock window and replayed nonces. The bridge accepts only `APPROVE`, `REVOKE`, `KICK`, `SYNC_PENDING`, `REMOVE_PENDING`, `STATUS`, `DISCORD_CHAT`, and the transient `SYNC_PROFILE` perk update; it cannot run arbitrary console commands. Paper sends signed, acknowledged `PLAYER_JOIN`, `PLAYER_LEAVE`, and `MINECRAFT_CHAT` events and receives a derived level profile when a linked player joins or their milestone roles change.

Each Discord member can link at most one Java account and one Bedrock account. The limit is enforced transactionally when an application is created and checked again when Paper verifies ownership.

## Discord bot configuration

Copy [`.env.example`](../.env.example) to a git-ignored `.env.minecraft`. Only identity,
secrets, and process-level bridge settings belong in this file:

| Variable | Purpose |
|---|---|
| `MINECRAFT_DISCORD_BOT_TOKEN` | Token for the dedicated Discord application. Do not reuse either moderation bot token. |
| `MINECRAFT_GUILD_ID` | Discord server that owns the application system. |
| `MINECRAFT_BRIDGE_SECRET` | Exactly 32 random bytes encoded as 64 hexadecimal characters. |
| `MINECRAFT_SERVER_ID` | Must equal the plugin's `server-id`. |
| `MINECRAFT_BRIDGE_PATH` | WebSocket route, normally `/minecraft-bridge`. |
| `MINECRAFT_BRIDGE_HOST` | Local bind address, normally `0.0.0.0`. |
| `MINECRAFT_BRIDGE_PORT` | Local HTTP port allocated to this process. |
| `MINECRAFT_BRIDGE_TLS_CERT` | Optional certificate PEM path for direct WSS. Must be set with the key. |
| `MINECRAFT_BRIDGE_TLS_KEY` | Optional private-key PEM path for direct WSS. Must be set with the certificate. |
| `MINECRAFT_ALLOW_INSECURE_LOCALHOST` | Keep `0` in production. Set `1` only for a local `ws://localhost` test. |
| `MINECRAFT_DATA_DIR` | Dedicated runtime directory; defaults to `runtime/minecraft`. |

Application/review channels, application/verification/player-activity logs, two-way chat,
moderator/approved-member roles, and the public Java and Bedrock addresses are
configured inside Discord with `/minecraft setup`. They are saved
to `minecraft.db` and take effect immediately. Older `.env.minecraft` files may still
contain `MINECRAFT_APPLICATION_CHANNEL_ID`, `MINECRAFT_REVIEW_CHANNEL_ID`,
`MINECRAFT_MOD_ROLE_ID`, `MINECRAFT_MEMBER_ROLE_ID`, `MINECRAFT_JAVA_ADDRESS`,
`MINECRAFT_BEDROCK_ADDRESS`, and `MINECRAFT_BEDROCK_PORT`; those values are accepted as
one-time bootstrap defaults when the database has no saved value, but can be removed
after the first successful startup.

The Discord application needs the Server Members and Message Content intents enabled in the Developer Portal. Server Members powers review metadata, approved roles, and milestone perks; Message Content is required for Discord-to-Minecraft chat. Invite only this dedicated application to the configured guild. Its role must sit above the approved-member role selected in the panel, and it needs View Channels, Send Messages, Embed Links, Read Message History, and Manage Roles in the relevant channels/server. The optional chat-sync channel also requires Manage Webhooks.

## Shared secret

Generate one secret locally:

```bash
openssl rand -hex 32
```

Put the same 64-character value in `.env.minecraft` as `MINECRAFT_BRIDGE_SECRET` and in the plugin's `plugins/MGXAccessBridge/config.yml` as `bridge-secret`. Never paste it into Discord, logs, support tickets, or git. Rotating it requires changing both sides and restarting both processes.

### Direct TLS certificate pin

Normal installations leave `bridge-certificate-sha256` blank and use a publicly
trusted certificate. Direct WSS may instead use a private self-signed certificate
and an explicit SHA-256 leaf-certificate pin:

```yaml
bridge-certificate-sha256: "64-character-lowercase-sha256-fingerprint"
```

Pinned mode remains WSS-only, verifies the URL hostname against the certificate, checks
the exact certificate fingerprint, and keeps the signed HMAC application protocol. The
pin is the trust anchor, so it also securely supports a private self-signed certificate.
A certificate rotation fails closed until the independently verified pin is updated;
never guess a pin or use one supplied by an untrusted party.

## Building the Paper plugin

Java 21 is required. From the repository root:

```bash
cd minecraft-bridge
./gradlew clean build
```

On Windows use `gradlew.bat clean build`. The Gradle wrapper verifies the Gradle 8.10.2 distribution checksum. The shaded deployment artifact is:

```text
minecraft-bridge/build/libs/MGXAccessBridge.jar
```

Gson is shaded and relocated. Networking uses Java 21's built-in HTTP/WebSocket client, and Bukkit/Floodgate/Paper APIs remain provided by the server.

## Installing on GravelHost

1. Stop the Paper server and back up the world and plugin directory.
2. Confirm the server runs Paper 1.21.1 on Java 21 and that Floodgate and Geyser are already loading successfully.
3. Upload `minecraft-bridge/build/libs/MGXAccessBridge.jar` to the server's `plugins/` directory.
4. Start once to create `plugins/MGXAccessBridge/config.yml`, then stop the server.
5. Set `server-id`, the public `wss://.../minecraft-bridge` URL, and the shared secret. Keep `allow-insecure-localhost: false`.
6. Start Paper and look for the signed bridge connection confirmation. If configuration is missing or insecure, the plugin disables itself without changing login behavior.

The Discord bot host must expose the configured bridge port. TLS can terminate at a
WebSocket-capable reverse proxy that forwards `X-Forwarded-Proto: https`. Alternatively,
set `MINECRAFT_BRIDGE_TLS_CERT` and `MINECRAFT_BRIDGE_TLS_KEY` to terminate WSS directly
inside the bot and use a certificate whose SAN matches the public hostname or IP. Do
not expose an unencrypted public `ws://` endpoint.

## Database migration, backup, and recovery

There is no manual SQL step. On first startup, the Minecraft bot creates `minecraft.db` and all four application tables automatically. Discord snowflakes are stored as text. Future schema-version upgrades take an online SQLite backup before applying additive migrations and retain the newest five files under:

```text
runtime/minecraft/backups/
```

For routine backups, stop only the Minecraft bot process and copy the entire `MINECRAFT_DATA_DIR`. To recover, stop that process, preserve the damaged directory separately, restore `minecraft.db` plus its `-wal`/`-shm` files when present, and start the process again. Do not copy a live database without using SQLite's backup API.

Clan data is owned by Paper and saved atomically in
`plugins/MGXAccessBridge/clans.json`. Include that file when backing up the Minecraft
server. The plugin fails closed instead of replacing malformed clan data.

The durable outbox makes approval and revocation safe across disconnects. `APPROVAL_QUEUED` does not mean a player is whitelisted. The status becomes `APPROVED`, and the Discord role is assigned, only after Paper confirms the typed action.

## Scoreboard and Discord level perks

Every online player receives a compact orange-led sidebar with clear profile and stats
sections. It contains their name, highest Discord milestone, bonus hearts, optional
level-50 power bonus, optional clan, kills, deaths, ping, and the configured footer.
Clan and power rows are omitted when they do not apply. Paper's blank number
format hides the sidebar's internal score values.

The dedicated Discord bot derives profiles from these exact milestone roles:

| Discord role ID | Displayed milestone | Perk |
|---|---:|---|
| `1476839722172158018` | 5 | +1 heart |
| `1476839722172158019` | 10 | +1 heart |
| `1476839722172158020` | 20 | +1 heart |
| `1476839722172158021` | 30 | +1 heart |
| `1476839722172158022` | 40 | +1 heart |
| `1476839722172158023` | 50 | +5% direct melee damage |

Heart milestones unlock cumulatively from the highest role to a maximum of five extra
hearts. Level 50 does not add a sixth heart; it retains all lower milestone perks and enables the small
damage bonus without a potion effect, particles, or a permanent player-data change.
Removing a Discord role removes its matching perk. Profiles are sent only across the
signed bridge and transient health modifiers are cleared when the plugin disables.

The sidebar is configured in `plugins/MGXAccessBridge/config.yml`:

```yaml
scoreboard:
  footer: "discord.gg/mgx"
  update-ticks: 100
world:
  keep-spawn-loaded: false
  max-view-distance: 6
  max-simulation-distance: 4
  border-radius: 100000
```

The footer accepts 1-32 characters. Refresh intervals accept 10-200 ticks; the default
is every five seconds. View and simulation caps drop 1.21 chunk residency; set a
distance to `0` to leave the panel value alone. The border is 100,000 blocks from
spawn; set `border-radius: -1` to leave the panel value.

## Discord and Minecraft chat

Select a **Minecraft chat** channel in `/minecraft setup`, or use
`/minecraft chat-channel`. Minecraft messages are posted there through a bot-owned
webhook. A linked player uses the linked Discord account's username and avatar; the
embed still identifies the Minecraft username and edition. Discord messages are sent
to Paper with a blurple `◆ DISCORD` badge, an optional clan tag, the Discord username,
and the original plain text. Attachments become a clickable link back to the Discord
message because Minecraft cannot render Discord files directly.

Bots and webhook messages are ignored on the Discord-to-Minecraft path to prevent
relay loops. All chat travels through the existing signed bridge; no second Discord
client or separate account-link database is introduced. A linked Discord username is
shown between the clan tag and normal Minecraft name in public chat, clan chat,
nametags, and the player list. Players can use `/discordnames` to hide or restore their
own linked name without changing their clan or Minecraft display name.

## Minecraft clans

Clans are UUID-based, survive name changes and server restarts, and have one leader,
optional staff, up to 25 members, five-minute invites, and private online chat. Members
of the same clan can never damage one another; this protection cannot be changed. Clan
membership appears on the sidebar only when applicable.

| Command | Purpose |
|---|---|
| `/clans create <name>` | Create a uniquely named clan. |
| `/clans invite <player>` | Invite an online player; `/clans add` is an alias. |
| `/clans accept` / `/clans decline` | Answer the latest active invite. |
| `/clans info [name]` / `/clans list [page]` | Inspect or browse clans. |
| `/clans rename <name>` | Change the clan name as leader. |
| `/clans promote <player>` / `/clans demote <player>` | Add or remove clan staff. |
| `/clans transfer <player>` | Transfer leadership to an existing member. |
| `/clans kick <player>` / `/clans leave` | Remove a member or leave voluntarily. |
| `/clans chat <message>` | Message online members of the same clan. |
| `/clans disband confirm` | Permanently remove the clan after explicit confirmation. |

Leaders can perform every management action. Staff can invite and remove regular
members, but cannot rename or disband the clan, change staff, transfer leadership, or
remove another staff member.

## Posting the application panel

After the dedicated bot is online, a server Administrator runs:

```text
/minecraft setup
```

The command opens an ephemeral Components V2 dashboard in the same visual style as the
main bot. Select the application channel, private review channel, optional logging
and chat-sync channels, moderator role, and approved-member role, then use **Edit Addresses** for the public Java and Bedrock
addresses. The dashboard validates channel permissions and role hierarchy before
**Post Application Panel** is available as a successful action.

Posting edits the saved panel message when it still exists or posts a replacement if it
was deleted. Applicants click Apply, select Java or Bedrock in the modal, and submit
their username and answers. No slash command is required from applicants.

## Testing Java and Bedrock applications

### Java

1. Apply with a valid Java username.
2. Connect once to the Java address shown by the bot within ten minutes.
3. Confirm the connection remains rejected with the account-verified message.
4. Confirm the staff review record shows the authoritative UUID and current username.
5. Approve and wait for the staff record to show `Approved` before reconnecting.

### Bedrock

1. Apply with the real Xbox gamertag, including spaces exactly as Floodgate reports it.
2. Connect to the Bedrock address and port shown by the bot within ten minutes.
3. Confirm the connection remains rejected and the review record contains the Floodgate UUID and XUID.
4. Approve and verify Paper accepted `fwhitelist add <verified-uuid>` before reconnecting.

The submitted edition, UUID, and XUID are never trusted. Paper and Floodgate provide the authoritative values during the intentionally rejected login.

## Approval, denial, revocation, and management

- **Approve** atomically claims a pending review and queues a typed whitelist action. Duplicate clicks cannot create a second approval. Offline bridge work remains queued.
- **Deny** collects an internal staff note and an optional applicant-facing reason. The applicant receives an anonymous decision DM; only the public reason is included.
- **View Previous Applications** shows staff a member's application history without exposing internal notes.
- `/minecraft status` reports connection, heartbeat, application, and outbox state.
- `/minecraft lookup user:<member>` shows linked UUID/XUID records and application history.
- `/minecraft unlink user:<member> edition:<Java|Bedrock> reason:<text>` removes one linked account. Approved access is revoked on Paper before the link is deleted; unapproved links are removed immediately.
- `/minecraft revoke user:<member> reason:<text>` queues whitelist removal for all approved accounts, removes the Discord role after Paper confirms the last revocation, and kicks matching online accounts.
- `/minecraft retry application:<id>` retries failed bridge work.
- `/minecraft cancel application:<id>` cancels an unfinished application and removes it from Paper's verification cache. A potentially in-flight approval is followed by an idempotent revocation.
- `/minecraft log-channel log:<type> channel:<channel>` configures the shared Activity Log or the quieter Important Log. Omit `channel` to disable that stream.
- `/minecraft chat-channel channel:<channel>` configures two-way Discord and Minecraft chat. Omit `channel` to disable the relay.
- `/minecraft applications status:<optional> limit:<optional>` lists recent applications and their current lifecycle state.
- `/minecraft audit application:<id>` shows the recorded lifecycle events for one application.

The application log receives the submitted edition, claimed username, answers, and
verification deadline immediately—before the player connects. The verification log
receives the authoritative username, UUID, and Floodgate XUID. The player-activity log
receives acknowledged join and leave events from Paper. These channels should remain
staff-only because they contain account and application data.

Applicants are notified by DM when possible. Closed DMs never roll back a state transition. Moderator identities remain out of applicant DMs; private staff records and the audit log retain the reviewer for accountability.

## Troubleshooting

### Bridge is offline

- Run `/minecraft status` and inspect the dedicated bot and Paper logs.
- Confirm `server-id` and the shared secret match exactly.
- Confirm system clocks are synchronized; messages more than 30 seconds apart are rejected.
- Confirm the URL uses `wss://`, the certificate is valid, and the reverse proxy supports WebSocket upgrades.
- Confirm the host routes the configured path and port to the Minecraft bot process.
- If pinned TLS is enabled, confirm the current leaf-certificate SHA-256 matches
  `bridge-certificate-sha256`; certificate rotation requires a pin update.
- Do not switch to public `ws://`; the insecure setting exists only for localhost development.

### Verification never appears

- Confirm the application has not passed its ten-minute expiry.
- Confirm the submitted edition and account name match the real connecting account.
- For Bedrock, use the real gamertag, not Floodgate's prefix or transformed Java-facing name.
- Confirm Paper's login result is specifically `KICK_WHITELIST`. Bans, a full server, and other security rejections are intentionally ignored.

### Approval remains queued or failed

- Restore the bridge, then run `/minecraft retry application:<id>` for failed work.
- Confirm Paper's whitelist is enabled and the Floodgate `fwhitelist` command is available.
- Never edit `whitelist.json`, issue RCON commands, or mark the Discord record approved manually.
