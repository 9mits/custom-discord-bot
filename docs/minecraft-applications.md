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

Every bridge message carries an HMAC-SHA256 signature, timestamp, random nonce, and idempotency key. Both sides reject messages outside a 30-second clock window and replayed nonces. The bridge accepts only `APPROVE`, `REVOKE`, `KICK`, `SYNC_PENDING`, `REMOVE_PENDING`, and `STATUS`; it cannot run arbitrary console commands.

## Discord bot configuration

Copy [`.env.example`](../.env.example) to a git-ignored `.env.minecraft` and fill in:

| Variable | Purpose |
|---|---|
| `MINECRAFT_DISCORD_BOT_TOKEN` | Token for the dedicated Discord application. Do not reuse either moderation bot token. |
| `MINECRAFT_GUILD_ID` | Discord server that owns the application system. |
| `MINECRAFT_APPLICATION_CHANNEL_ID` | Public channel where `/minecraft setup` posts the Apply panel. |
| `MINECRAFT_REVIEW_CHANNEL_ID` | Private staff channel that receives verified applications. |
| `MINECRAFT_MOD_ROLE_ID` | Role allowed to review and manage applications. Administrators are also allowed. |
| `MINECRAFT_MEMBER_ROLE_ID` | Role added only after Paper confirms approval. |
| `MINECRAFT_BRIDGE_SECRET` | Exactly 32 random bytes encoded as 64 hexadecimal characters. |
| `MINECRAFT_SERVER_ID` | Must equal the plugin's `server-id`. |
| `MINECRAFT_JAVA_ADDRESS` | Address shown to Java applicants. |
| `MINECRAFT_BEDROCK_ADDRESS` | Host shown to Bedrock applicants. |
| `MINECRAFT_BEDROCK_PORT` | Port shown to Bedrock applicants. |
| `MINECRAFT_BRIDGE_PATH` | WebSocket route, normally `/minecraft-bridge`. |
| `MINECRAFT_BRIDGE_HOST` | Local bind address, normally `0.0.0.0`. |
| `MINECRAFT_BRIDGE_PORT` | Local HTTP port allocated to this process. |
| `MINECRAFT_ALLOW_INSECURE_LOCALHOST` | Keep `0` in production. Set `1` only for a local `ws://localhost` test. |
| `MINECRAFT_DATA_DIR` | Dedicated runtime directory; defaults to `runtime/minecraft`. |

The Discord application needs the Server Members intent enabled in the Developer Portal so review records can show join dates and the bot can assign the approved-member role. Invite only this dedicated application to the configured guild. Its role must sit above `MINECRAFT_MEMBER_ROLE_ID` and it needs View Channels, Send Messages, Embed Links, Read Message History, and Manage Roles in the relevant channels/server.

## Shared secret

Generate one secret locally:

```bash
openssl rand -hex 32
```

Put the same 64-character value in `.env.minecraft` as `MINECRAFT_BRIDGE_SECRET` and in the plugin's `plugins/MGXAccessBridge/config.yml` as `bridge-secret`. Never paste it into Discord, logs, support tickets, or git. Rotating it requires changing both sides and restarting both processes.

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
2. Confirm the server runs Paper 1.20.6 on Java 21 and that Floodgate and Geyser are already loading successfully.
3. Upload `minecraft-bridge/build/libs/MGXAccessBridge.jar` to the server's `plugins/` directory.
4. Start once to create `plugins/MGXAccessBridge/config.yml`, then stop the server.
5. Set `server-id`, the public `wss://.../minecraft-bridge` URL, and the shared secret. Keep `allow-insecure-localhost: false`.
6. Start Paper and look for the signed bridge connection confirmation. If configuration is missing or insecure, the plugin disables itself without changing login behavior.

The Discord bot host must route the public WSS URL to `MINECRAFT_BRIDGE_HOST:MINECRAFT_BRIDGE_PORT`. TLS can terminate at the host's reverse proxy, but it must forward `X-Forwarded-Proto: https`. Do not expose an unencrypted public `ws://` endpoint.

## Database migration, backup, and recovery

There is no manual SQL step. On first startup, the Minecraft bot creates `minecraft.db` and all four application tables automatically. Discord snowflakes are stored as text. Future schema-version upgrades take an online SQLite backup before applying additive migrations and retain the newest five files under:

```text
runtime/minecraft/backups/
```

For routine backups, stop only the Minecraft bot process and copy the entire `MINECRAFT_DATA_DIR`. To recover, stop that process, preserve the damaged directory separately, restore `minecraft.db` plus its `-wal`/`-shm` files when present, and start the process again. Do not copy a live database without using SQLite's backup API.

The durable outbox makes approval and revocation safe across disconnects. `APPROVAL_QUEUED` does not mean a player is whitelisted. The status becomes `APPROVED`, and the Discord role is assigned, only after Paper confirms the typed action.

## Posting the application panel

After the dedicated bot is online, a configured moderator or Administrator runs:

```text
/minecraft setup
```

The command edits the saved panel message when it still exists or posts a replacement if it was deleted. Applicants click Apply, select Java or Bedrock in the modal, and submit their username and answers. No slash command is required from applicants.

## Testing Java and Bedrock applications

### Java

1. Apply with a valid Java username.
2. Connect once to `104.254.131.178:50548` within ten minutes.
3. Confirm the connection remains rejected with the account-verified message.
4. Confirm the staff review record shows the authoritative UUID and current username.
5. Approve and wait for the staff record to show `Approved` before reconnecting.

### Bedrock

1. Apply with the real Xbox gamertag, including spaces exactly as Floodgate reports it.
2. Connect to `104.254.131.178`, port `50549`, within ten minutes.
3. Confirm the connection remains rejected and the review record contains the Floodgate UUID and XUID.
4. Approve and verify Paper accepted `fwhitelist add <verified-uuid>` before reconnecting.

The submitted edition, UUID, and XUID are never trusted. Paper and Floodgate provide the authoritative values during the intentionally rejected login.

## Approval, denial, revocation, and management

- **Approve** atomically claims a pending review and queues a typed whitelist action. Duplicate clicks cannot create a second approval. Offline bridge work remains queued.
- **Deny** collects an internal staff note and an optional applicant-facing reason. Only the public reason is sent to the applicant.
- **View Previous Applications** shows staff a member's application history without exposing internal notes.
- `/minecraft status` reports connection, heartbeat, application, and outbox state.
- `/minecraft lookup user:<member>` shows linked UUID/XUID records and application history.
- `/minecraft revoke user:<member> reason:<text>` queues whitelist removal for all approved accounts, removes the Discord role after Paper confirms the last revocation, and kicks matching online accounts.
- `/minecraft retry application:<id>` retries failed bridge work.
- `/minecraft cancel application:<id>` cancels an unfinished application and removes it from Paper's verification cache. A potentially in-flight approval is followed by an idempotent revocation.

Applicants are notified by DM when possible. Closed DMs never roll back a state transition. Moderator identities remain out of applicant DMs; private staff records and the audit log retain the reviewer for accountability.

## Troubleshooting

### Bridge is offline

- Run `/minecraft status` and inspect the dedicated bot and Paper logs.
- Confirm `server-id` and the shared secret match exactly.
- Confirm system clocks are synchronized; messages more than 30 seconds apart are rejected.
- Confirm the URL uses `wss://`, the certificate is valid, and the reverse proxy supports WebSocket upgrades.
- Confirm the host routes the configured path and port to the Minecraft bot process.
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
