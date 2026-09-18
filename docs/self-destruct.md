# Self-destruct and the licence lock

This plugin is the owner's property. Two mechanisms protect it, both in
`SelfDestruct.java`, and both depend on **one file the owner places by hand** at the
Paper server root and never commits: `mgx-license.key`.

## The keyfile

```
# mgx-license.key — server root, next to server.jar. Never in git, never on the panel.
license=any-non-empty-marker-you-like
destruct=<sha256 of your destruct passphrase>
```

- **`license`** — any non-empty value. Its mere presence is what licenses the server.
  A copy of the jar taken off the control panel has no keyfile, so the plugin refuses
  to start (`InvalidPluginException`-style disable, with a clear log line).
- **`destruct`** — the SHA-256 of the passphrase that arms the kill switch. The
  passphrase itself is written down nowhere. Omit this line to keep the switch disarmed.

Generate the destruct hash without putting the passphrase in a file:

```
printf %s 'your secret passphrase' | shasum -a 256    # macOS
printf %s 'your secret passphrase' | sha256sum        # Linux
```

Put the 64-hex result after `destruct=`.

The **local test server is exempt** (`server-id: mgx-local-test`), so development needs
no key. Everything else requires one.

## Firing it

In Discord, as the **owner role only** (not Developer, not an operator, not the panel):

```
/mgxadmin selfdestruct passphrase:<your passphrase>
```

The bot relays the passphrase to the plugin over the authenticated bridge. The plugin
**re-checks it against the keyfile itself**, so a compromised bot cannot detonate without
the passphrase, and the passphrase never has to be stored anywhere.

Optional early gate: set `MINECRAFT_DESTRUCT_SHA256` in the bot's `.env.minecraft` to the
same hash, and a wrong passphrase is rejected before it ever reaches the server.

## What it does

1. Writes `mgx-terminated.flag` at the server root — first, so a power loss mid-wipe
   still finishes on the next boot.
2. Wipes every plugin data file under `plugins/MGXAccessBridge/`.
3. Unloads and deletes every world folder (any directory with a `level.dat`).
4. Shuts the server down.

On every subsequent boot, while the flag is present, the plugin erases the worlds again
before Paper can load them and then disables itself. The server is dead until the owner
**deletes `mgx-terminated.flag`** and restores a world. This is irreversible without a
backup taken beforehand.

## The honest limit

Nothing running on a machine can hide a secret from someone with root on that machine.
The licence lock defends against the stated threat — the jar being copied off the shared
panel — not against someone who also has filesystem access to the Minecraft host and
copies the keyfile too. For that, keep the keyfile's directory permissions tight.
