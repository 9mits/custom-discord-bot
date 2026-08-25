# Bedrock resource pack

Geyser does not translate the Java resource pack sent through
`server.properties`. Bedrock clients instead need both generated files here:

- `MysteriousSMPX-Bedrock.mcpack` goes in `plugins/Geyser-Spigot/packs/`.
- `mgx_items.json` goes in `plugins/Geyser-Spigot/custom_mappings/`.

Run `python3 build_pack.py` after changing an item model or texture. The build is
deterministic and derives every Bedrock texture from the canonical Java asset, so
both editions show the same pixels. The manifest version changes with its inputs
to prevent Bedrock from reusing an old cached pack.

The Java bitmap-font logo is deliberately not included. Bedrock glyph sheets use
fixed square cells and cannot reproduce the wide Java-only logo; the plugin uses
its existing text fallback for Geyser players.
