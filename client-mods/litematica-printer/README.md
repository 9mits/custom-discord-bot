# MGX Litematica Printer compatibility build

This is the reproducible source patch for the Minecraft 1.21.11 Printer build
used by the MGX test client. It is based on upstream Litematica Printer commit
`94f25eb61037525e9372b46b37c1bf071c62832d`, licensed under AGPL-3.0.

The upstream mod sent a held-item packet directly every time Printer selected a
block. That could duplicate the vanilla selected-slot packet, trip Grim's
`BadPacketsA` check, and disconnect the player. The patch routes slot changes
through Minecraft's tracked `ensureCarriedItemSent()` path, which sends only
when the server-facing selected slot actually changed.

Build it from the repository root:

```bash
client-mods/litematica-printer/build.sh
```

The script prints the resulting 1.21.11 jar path. Install that jar in the
client's `mods/` directory in place of the upstream Printer jar. This is a
Fabric client mod; a server resource pack cannot install it.

For a fast, stable baseline, use **1 block per tick**, **8 ticks placement
cooldown**, and leave **RTT-adaptive interval** enabled. The verified 170-block
fixture completed without a disconnect or missing block at those settings.

Server-side compatibility is deliberately narrow. MGXAccessBridge grants the
default LuckPerms group only the seven per-check
`grim.nomodifypacket.<printer-check>` permissions known to false-positive on
Printer traffic. It does not grant `grim.nomodifypacket`, `grim.exempt`, or any
general anti-cheat bypass. Grim continues to flag and log the Printer pattern,
while the selected checks stop rewriting legitimate placement packets.

Upstream source: <https://github.com/Yur1Ca/litematica-printer>
