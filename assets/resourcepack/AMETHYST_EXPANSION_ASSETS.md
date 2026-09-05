# Amethyst expansion asset sources

Only visual and audio assets were imported. None of the source projects' recipes,
statistics, abilities, drops, entities, or gameplay code is used by the server.

- **Amethyst Revamped 1.2.3** (`amethyst_revamped-1.2.3-neoforge-1.21.4.jar`,
  supplied by the server owner): hoe and armor item sprites. Its so-called rod
  sprite is a short Amethyst stick, so it is not used for the Fishing Rod.
- **Amethyst Sword 1.0.0** (`amethyst_sword-1.0.0-forge-1.20.1.jar`, supplied by
  the server owner): exact Amethyst Sword item sprite.
- **Dragon Mounts Remastered 1.9.2**
  (`Dragon Mounts Remastered-1.21.1-1.9.2.jar`, supplied by the server owner;
  PolyForm Noncommercial 1.0.0): Amethyst Dragon palette and animated egg texture.
  The mod's GeckoLib body atlas does not match Minecraft's Ender Dragon UV map,
  so its palette is applied to the correct vanilla Dragon atlas to prevent broken geometry.
- **Amethyst Apples 1.0.0**
  (`amethyst_apples-1.0.0-neoforge-1.21.1.jar`, supplied by the server owner):
  exact Amethyst Apple item art, reduced with nearest-neighbour filtering.
- **Amethyst Equipment** (Modrinth, MIT): Amethyst Bow inventory sprite.
  <https://modrinth.com/resourcepack/amethyst-equipment>
- **Amethyst Expansion RP** by General Schnitzel (Modrinth, CC BY-NC 4.0):
  Amethyst Elytra inventory and worn-wing textures and modern armor layers.
  <https://modrinth.com/resourcepack/amethyst-expansion-rp>
- **Amethyst Update** by redfox193 (GitHub, MIT): Amethyst Arrow inventory sprite.
  <https://github.com/redfox193/Amethyst-Update>
- **`music.ogg`** (supplied by the server owner): music for the Secret Amethyst
  Dragon Ascendant reveal.
- The Fishing Rod uses Minecraft's correct cast/uncast fishing-rod silhouettes,
  recoloured from the supplied Amethyst Dragon palette after the supplied packs
  and available external packs were checked for a suitable 2D item sprite.
- The Amethyst Dragon cosmetics and individual/clan podium icons are derived from
  the existing detailed 32x32 wardrobe icon set. `build_dragon_cosmetic_icons.py`
  preserves that established vanilla-style pixel density and shading.
