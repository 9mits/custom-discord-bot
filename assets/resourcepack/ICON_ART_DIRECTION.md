# Custom icon art direction

MGX cosmetic textures are derived from artwork generated with ChatGPT's built-in
image-generation tool. The pack must not use a script to draw new icon geometry
pixel by pixel. Exact supplied source assets are the exceptions documented below.

## Production rules

- Design on a deliberate **16x16 logical pixel grid**, exported at 32x32 as exact
  2x2 blocks. This preserves strong Minecraft-scale pixels without tiny dotted detail.
  The two potion reskins are the only exception: they retain the supplied official
  reference's exact 160x160 canvas, alpha mask, bottle pixels, and pixel geometry.
- Use one strong silhouette, stepped square pixels, crisp edges, and no antialiasing.
- Light from the upper left; use a dark lower-right outline and material shadow.
- Use a restrained material palette. The importer caps the final sprite at 24 colours.
- Keep the object dimensional without smooth gradients, glow haze, or mobile-game gloss.
- Keep Java and Bedrock identical. Bedrock is always generated from the Java texture.
- Review every icon both enlarged with nearest-neighbour scaling and at inventory size.

### Locked Amethyst equipment assets

The Amethyst Pickaxe, Shovel, Axe, and Shield are not generated artwork. Their
textures and the Shield's normal/blocking geometry come directly from the
user-supplied `Amethyst Revamped 1.2.4` JAR. Do not redraw, rescale, regenerate,
or pass these IDs through `import_generated_icons.py`.

- `amethyst_pickaxe.png`, `amethyst_shovel.png`, and `amethyst_axe.png` remain the
  exact 16x16 item textures from that JAR.
- `amethyst_shield.png` remains the exact 64x64 model texture atlas from that JAR.
- `models/custom/amethyst_shield*.json` preserve only its normal and blocking
  Blockbench geometry; the source-only parent names were removed and the wrapper
  models were changed only to use the `mgx` namespace.
- `textures/item/model/amethyst_cluster.png` is an exact copy of Minecraft
  1.21.11's vanilla Amethyst Cluster texture. It keeps every shield layer on the
  item atlas; directly referencing `minecraft:block/amethyst_cluster` makes the
  client reject the model for mixing the item and block atlases.
- Java renders the imported three-dimensional model as its inventory icon.
  `amethyst_shield_icon.png` is the original supplied Modrinth shield render kept
  only as Bedrock's flat-icon fallback because Bedrock cannot render Java item-model JSON.

No classes, recipes, metadata, sounds, particles, entities, blocks, or any other
content from the supplied mod JAR belongs in this repository.

The local generated sources are imported with:

```bash
python assets/resourcepack/import_generated_icons.py \
  --manifest <local-generated-source-map.json> \
  --output assets/resourcepack/src/assets/mgx/textures/item \
  --contact-sheet <review-sheet.png>
```

The manifest is intentionally local: it contains machine-specific paths to image-model
outputs. The selected PNGs committed under `src/` are the canonical assets.

## Shared generation prompt

```text
Use case: stylized-concept
Asset type: single Minecraft inventory item icon for Java and Bedrock resource packs
Input images: Image 1 is the official-feeling pixel-art quality reference; Image 2 is
only the old subject reference and must be fully redesigned.
Style/medium: authentic vanilla Minecraft inventory sprite visual language on a 16x16 logical grid;
one isolated object made from chunky deliberate square pixels, stepped diagonals,
crisp hard edges, a restrained hand-authored-looking material value ramp, and a
strong readable silhouette; no antialiasing.
Composition/framing: centered, filling roughly 12x12 to 14x14 logical pixels with padding.
Lighting/mood: top-left highlight and dark lower-right outline/shadow; dimensional
but restrained.
Scene/backdrop: a completely flat removable background.
Constraints: exactly one icon; no text, labels, UI slot, border, floor shadow,
scenery, watermark, checkerboard, gradients, blur, glow haze, subpixel detail,
rounded vector edges, or high-resolution painting. Do not copy an official item except
for the two potion reskins, which deliberately retain the supplied vanilla bottle.
Avoid: flat symbol design, mobile-game gloss, smooth illustration, excessive detail.
```

## Selected subject prompts

| ID | Final subject direction |
|---|---|
| `aura` | Warm-gold magical core inside two offset orbital bands with a tiny sparkle. |
| `crate_key` | Diagonal old-gold crate key with a faceted violet gem and distinctive notched bit. |
| `crate_luck_potion` | Supplied official potion bottle reskinned with deep-violet liquid only. |
| `fortune_potion` | Supplied official potion bottle reskinned with emerald liquid only. |
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
| `crystalline_extinction` | Dark amethyst geode eclipse crushed by four violet crystal spikes around a white-lilac impact core. |
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
| `iridescent_imperium` | Dominant royal-amethyst resonance heart in a broken couture-gold crown ring, set with restrained ruby, sapphire, emerald, and champagne facets. |
| `kingmakers_wake` | Torn royal-gold banner with a crown finial and purple mantle cloth. |
| `moonlit_procession` | Thick silver-blue crescent comet with two dark-blue trailing facets. |
| `phantom_chains` | Three interlocked dark-teal chain links filled with cyan soul-light. |
| `prismatic_trail` | Clear prism shard splitting into short red, gold, cyan, and violet ribbons. |
| `reality_fracture` | Obsidian-glass shard split by a jagged magenta-and-cyan reality crack. |
| `reapers_verdict` | Spectral scythe with a weathered handle, cold-silver blade, and violet soul-light. |
| `resonant_apotheosis` | Regal faceted amethyst crown-crystal inside two broken resonance rings with a chime sparkle. |
| `secret_silhouette` | Unknown relic enclosed in charcoal-black wrappings with only a muted-violet seam. |
| `shining_light` | Tangible white-gold star relic with four stepped rays and a warm underside. |
| `shattered_continuum` | Diagonal amethyst portal shard tearing through a broken ring with displaced crystal afterimages. |
| `silver_reckoning` | Single cold-silver execution sword with dark steel fittings and a moonlit edge. |
| `solar_imperium` | Heavy royal-gold crown with sun-ray points and a white-gold central gem. |
| `solar_orbit` | Faceted miniature sun with one diagonal gold orbit and a tiny solar flare. |
| `soul_requiem` | Dark iron reliquary lantern containing a cyan spirit flame and soul wisp. |
| `void_collapse` | Dense black-violet imploding sphere with a broken amethyst rim. |

## Potion reskin edit prompt

The two potion sources use ChatGPT image generation in edit mode with the supplied
official Potion of Healing icon as the locked edit target:

```text
Preserve the exact bottle silhouette, pixel-grid geometry, cork, pale-blue glass
rim, highlights, shadows, transparency, scale, centering, and empty padding from
the input. Change only the red liquid and its internal highlights. The result must
still unmistakably be the official Minecraft potion bottle with a different liquid.
No new bottle shape, label, text, particles outside the bottle, smooth painting,
antialiasing, extra detail, or watermark.
```

`crate_luck_potion` changes the liquid to deep violet. `fortune_potion` changes it
to emerald/lime.

Image generation supplies only each liquid's colour and lighting ramp. The importer
applies that ramp to the seven red liquid colours in the supplied 160x160 official
Potion of Healing reference. Every cork, bottle, glass, highlight, shadow,
transparent-padding, silhouette, and pixel coordinate remains byte-for-byte equal
to that reference. The two results therefore look like true vanilla potion
reskins, not redesigned bottles.

### Permanent potion invariant

Never commit an image-generation output directly as either potion texture, even if
it appears close to the reference. Image generation may propose the liquid colour
ramp only. `import_generated_icons.py` must apply that ramp to
`icon-sources/potion_of_healing_reference.png`; it must not resize, redraw,
reinterpret, or regenerate the bottle.

Both `tests/test_resourcepack_icons.py` and `ResourcePackCatalogTest` enforce this:
the complete alpha mask and every non-liquid RGBA pixel must match the canonical
reference, the two item models must resolve to different texture files, Fortune
must remain green, and Crate Luck must remain violet. A failing invariant means the
asset is wrong; do not weaken the test to accept a redesigned bottle.
