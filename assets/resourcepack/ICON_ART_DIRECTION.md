# Custom icon art direction

All shipped MGX item and cosmetic textures are derived from artwork generated with
ChatGPT's built-in image-generation tool. The pack must not use a script to draw
new icon geometry pixel by pixel.

## Production rules

- Design against a real **16x16 inventory canvas**, not a high-resolution UI icon.
- Use one strong silhouette, stepped square pixels, crisp edges, and no antialiasing.
- Light from the upper left; use a dark lower-right outline and material shadow.
- Use a restrained material palette. The importer caps the final sprite at 16 colours.
- Keep the object dimensional without smooth gradients, glow haze, or mobile-game gloss.
- Keep Java and Bedrock identical. Bedrock is always generated from the Java texture.
- Review every icon both enlarged with nearest-neighbour scaling and at true 16x16 size.

The local generated sources are imported with:

```bash
python assets/resourcepack/import_generated_icons.py \
  --manifest <local-generated-source-map.json> \
  --output assets/resourcepack/src/assets/mgx/textures/item \
  --contact-sheet <review-sheet.png>
```

The manifest is intentionally local: it contains machine-specific paths to image-model
outputs. The selected 16x16 PNGs committed under `src/` are the canonical assets.

## Shared generation prompt

```text
Use case: stylized-concept
Asset type: single Minecraft inventory item icon for Java and Bedrock resource packs
Input images: Image 1 is the official-feeling pixel-art quality reference; Image 2 is
only the old subject reference and must be fully redesigned.
Style/medium: authentic vanilla Minecraft 16x16 inventory sprite visual language;
one isolated object made from chunky deliberate square pixels, stepped diagonals,
crisp hard edges, a restrained hand-authored-looking material value ramp, and a
strong readable silhouette; no antialiasing.
Composition/framing: centered, filling roughly 13x13 logical pixels with padding.
Lighting/mood: top-left highlight and dark lower-right outline/shadow; dimensional
but restrained.
Scene/backdrop: a completely flat removable background.
Constraints: exactly one icon; no text, labels, UI slot, border, floor shadow,
scenery, watermark, checkerboard, gradients, blur, glow haze, subpixel detail,
rounded vector edges, or high-resolution painting; do not copy an official item.
Avoid: flat symbol design, mobile-game gloss, smooth illustration, excessive detail.
```

## Selected subject prompts

| ID | Final subject direction |
|---|---|
| `aura` | Warm-gold magical core inside two offset orbital bands with a tiny sparkle. |
| `crate_key` | Diagonal old-gold crate key with a faceted violet gem and distinctive notched bit. |
| `crate_luck_potion` | Corked glass bottle of deep-violet liquid containing a molten-orange lucky spark. |
| `fortune_potion` | Corked glass bottle of emerald liquid with a small gold clover-like glint. |
| `kill_effect` | Cracked dark-iron impact medallion struck by one diagonal crimson slash. |
| `secret` | Faceted obsidian sealed relic with a recessed violet seam and mysterious glint. |
| `trail` | Enchanted cyan boot angled forward with short stepped motion streaks. |
| `abyssal_seraph` | Obsidian seraph relic with six angular violet-black wings around a cyan void core. |
| `amethyst_orbit` | Pointed violet amethyst shard passing through a pale-lilac orbit with two fragments. |
| `argent_dominion` | Silver lunar circlet with offset crescent halos and a pale-blue moonstone. |
| `astral_sovereign` | Midnight star-crown relic with a cyan-white star and violet constellation arc. |
| `blood_burst` | Faceted crimson impact crystal exploding into dark-red droplets and shards. |
| `blood_trail` | Three dimensional crimson claw streaks ending in glossy ruby droplets. |
| `bronze_cataclysm` | Heavy bronze war axe with a medal worked into the head and an ember-red crack. |
| `bronze_vanguard` | Thick bronze laurel circlet surrounding an ember gem. |
| `celestial_crown` | Cold-silver starlight crown with three angular points and a pale-blue star gem. |
| `cherry_blossom_trail` | Dark cherrywood twig carrying one dimensional blossom and drifting petals. |
| `conquerors_march` | Battle-worn bronze war banner with a dark staff and embossed medal. |
| `crimson_orbit` | Cut crimson gemstone held inside broken dark-red orbital bands. |
| `divine_rupture` | Dominant white-gold lightning bolt splitting a cracked sunstone seal. |
| `drool_trail` | Glossy aqua slime-droplet charm stretched by motion with two trailing drops. |
| `ember_trail` | Charred ember-feather with a burnt spine, golden edge, and loose square coals. |
| `emerald_orbit` | Faceted emerald crystal inside asymmetrical lime and deep-green orbital bands. |
| `ender_trail` | Ender-pearl shard blinking through a torn violet portal ribbon. |
| `event_horizon` | Off-centre black-hole relic with a broken indigo and violet accretion rim. |
| `frost_trail` | Pale-blue frozen feather with an angular snowflake at its tail. |
| `frozen_shatter` | Pale-blue ice crystal cracking into sharp cold-white and deep-blue shards. |
| `galaxy_wake` | Blue-white stellar comet with a broad midnight-violet, star-filled tail. |
| `golden_finality` | Single royal-gold execution sword with a sun-shaped royal fitting. |
| `infernal_dominion` | Blackened nether-metal crown with lava cracks and a yellow-hot ember gem. |
| `kingmakers_wake` | Torn royal-gold banner with a crown finial and purple mantle cloth. |
| `moonlit_procession` | Thick silver-blue crescent comet with two dark-blue trailing facets. |
| `phantom_chains` | Three interlocked dark-teal chain links filled with cyan soul-light. |
| `prismatic_trail` | Clear prism shard splitting into short red, gold, cyan, and violet ribbons. |
| `reality_fracture` | Obsidian-glass shard split by a jagged magenta-and-cyan reality crack. |
| `reapers_verdict` | Spectral scythe with a weathered handle, cold-silver blade, and violet soul-light. |
| `secret_silhouette` | Unknown relic enclosed in charcoal-black wrappings with only a muted-violet seam. |
| `shining_light` | Tangible white-gold star relic with four stepped rays and a warm underside. |
| `silver_reckoning` | Single cold-silver execution sword with dark steel fittings and a moonlit edge. |
| `solar_imperium` | Heavy royal-gold crown with sun-ray points and a white-gold central gem. |
| `solar_orbit` | Faceted miniature sun with one diagonal gold orbit and a tiny solar flare. |
| `soul_requiem` | Dark iron reliquary lantern containing a cyan spirit flame and soul wisp. |
| `void_collapse` | Dense black-violet imploding sphere with a broken amethyst rim. |
