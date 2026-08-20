# Paper 1.21.11 production upgrade

This is the tested deployment manifest for the GravelHost Minecraft server. The
upgrade is one-way for world data: never start the production worlds on 1.21.11
until a stopped, downloadable backup has been verified.

Paper 1.21.11 is the specifically requested target, but it is no longer Paper's
security-current release. Plan a later move to the current release family after
the custom plugin and client policy are ready.

## Tested matrix

The complete set below reached Paper's `Done` state on Java 21 and shut down
cleanly in a clone of the production configuration.

| Component | Production before upgrade | Tested target |
|---|---|---|
| Paper | 1.21.1 build 133 | 1.21.11 build 132 |
| MGXAccessBridge | 2.92.0 | 3.0.0 from this repository |
| CombatLog | 1.9 | 1.19 |
| Geyser | 2.11.1 build 1218 | 2.11.1 build 1225 |
| GrimAC | 2.3.74-5920e74 | 2.3.74-98be8c1 |
| WorldEdit | 7.3.9 | 7.4.2 |
| WorldGuard | 7.0.12 | 7.0.17 |
| Chunky | 1.4.40 | keep 1.4.40 |
| CoreProtect CE | 24.0 | keep 24.0 |
| EssentialsX | 2.22.0 | keep 2.22.0 |
| Floodgate | 2.2.5 build 140 | keep build 140 |
| LuckPerms | 5.5.71 | keep 5.5.71 |
| ViaVersion | 5.11.0 | keep 5.11.0 |
| ViaBackwards | 5.11.0 | keep 5.11.0 |
| Simple Voice Chat | 2.6.21 | keep 2.6.21 |
| external spark | 1.10.175 | remove; Paper bundles spark |

WorldEdit 7.4.3 through 7.4.5 contain Java 25 bytecode and do not load on the
host's Java 21 runtime. WorldEdit 7.4.2 plus WorldGuard 7.0.17 is the newest pair
that was verified on this host.

Verified SHA-256 values:

```text
Paper 1.21.11-132  5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba
CombatLog 1.19     c549e16aa99b3b62eaf949b4cc511f37e2e2c95f56584d69bc1cd8cf56bfc514
Geyser b1225       654409e570850657dec4a51d6b19f569c96568c27408965993bf2523081151ae
GrimAC 98be8c1     8c5692038653d39be7e9daaf016c2324af8a1cafbdab1b9f46d0aa12df9f9999
WorldEdit 7.4.2    0ee152b1be5dfb51500505e2bf5a8c9d66f09c7fa484bf9aea384d7e7b459b06
WorldGuard 7.0.17  3f14562509bf01e7680571b6f56932239157ff938f257c3226df3b4088ae54f2
```

## Deployment sequence

1. Merge the bridge and resource-pack changes so the configured pack URL serves
   the new archive.
2. Send `save-all flush`, stop the Minecraft server from the hosting panel, and
   verify that it is fully offline.
3. Download a full backup of the worlds, `plugins/`, root JSON files, YAML files,
   properties, and the old server jar. Record file counts and archive checksum.
4. Replace the Paper jar and the jars marked for upgrade above. Move the external
   spark jar and superseded plugin jars to a backup directory outside `plugins/`.
5. Remove the `.paper-remapped` cache. Do not delete any plugin data directories.
6. Install the prepared CombatLog 1.19 `config.yml` and `messages.yml`; its own
   version-1 migration does not preserve every old restriction.
7. Set `resource-pack-sha1` to
   `02bd8bf67bc42315454197754f0d7cf0d4bc0c62` in `server.properties`.
8. Start once, inspect the complete startup log, and confirm all 14 plugins enable.
   Then test Java 1.21.11, a translated Java 1.21.6 client, and Bedrock before
   reopening normal access.

The prepared CombatLog configuration keeps the production behavior: 15-second
timer, Elytra/teleport/riptide restrictions enabled, 5 damage punishment,
kill-on-logout enabled, the existing blocked-command list, and anti-kill-abuse
disabled. New pearl, explosion, mending, untag-on-kill, and safe-zone barrier
behaviors remain disabled during the compatibility upgrade.

ViaBackwards is required for Java 1.21.6 through 1.21.10. GrimAC warns that its
vehicle checks do not officially support translated older clients on a 1.21.2+
backend; this warning does not prevent startup. Bedrock continues through
Geyser/Floodgate and receives the inventory settings fallback.

## Required production control

SFTP can transfer files but cannot safely flush, stop, start, or verify the live
process. Deployment therefore requires either a hosting-panel stop/start or a
Pterodactyl client API key stored only in the git-ignored local environment. Never
overwrite the active Paper or plugin jars while the server is running.
