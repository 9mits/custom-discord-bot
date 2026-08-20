# Minecraft lootboxes, cosmetics, and trophies

## Opening rules

One `Mysterious Lootbox Key` buys exactly one spin. Keys are physical, tradable
items and are not sold by `/shop`. An administrator can issue event keys with
`/lootbox key <online-player> [amount]`.

Each player may reserve at most three spins in any rolling 24-hour window. The
limit is recorded when the key is consumed, so closing the animation or leaving
the server cannot bypass it. Odds never decrease and there is no pity modifier;
the percentages below therefore remain exact on every permitted spin.

The reward is selected and saved before the 27-slot reel begins. A disconnect or
closed inventory leaves it claimable with `/lootbox claim`, and the plugin also
attempts delivery on the player's next join. Full inventories do not reroll or
discard a saved reward.

## Exact reward table

The implementation uses 100,000 equally likely integer tickets. All listed item
rewards are absent from `/shop`, and Elytra is deliberately excluded.

| Reward | Amount | Exact chance |
|---|---:|---:|
| Raw Copper | 16 | 12.700% |
| Raw Iron | 8 | 12.000% |
| Raw Gold | 6 | 10.000% |
| Emerald | 4 | 9.000% |
| Diamond | 2 | 7.000% |
| Wind Charge | 16 | 7.000% |
| Breeze Rod | 4 | 6.000% |
| Golden Apple | 1 | 6.000% |
| Echo Shard | 3 | 5.000% |
| Ominous Bottle | 1 | 4.000% |
| Heart of the Sea | 1 | 3.000% |
| Shulker Shell | 2 | 2.000% |
| Ancient Debris | 1 | 1.500% |
| Netherite Scrap | 1 | 1.250% |
| Totem of Undying | 1 | 0.500% |
| Netherite Ingot | 1 | 0.200% |
| Enchanted Golden Apple | 1 | 0.150% |
| Heavy Core | 1 | 0.100% |
| Mace | 1 | 0.035% |
| Blood Burst kill effect | 1 | 2.000% |
| Frozen Shatter kill effect | 1 | 0.750% |
| Shining Light kill effect | 1 | 0.350% |
| Void Collapse kill effect | 1 | 0.100% |
| Soul Requiem kill effect | 1 | 0.030% |
| Solar Orbit aura | 1 | 1.500% |
| Crimson Orbit aura | 1 | 0.500% |
| Emerald Orbit aura | 1 | 0.250% |
| Amethyst Orbit aura | 1 | 0.100% |
| Celestial Crown aura | 1 | 0.020% |
| Ember Trail | 1 | 5.000% |
| Blood Trail | 1 | 0.750% |
| Frost Trail | 1 | 0.500% |
| Cherry Blossom Trail | 1 | 0.350% |
| Drool Trail | 1 | 0.250% |
| Ender Trail | 1 | 0.100% |
| Prismatic Trail | 1 | 0.010% |
| Unknown secret cosmetic | 1 | `???` in game; 0.005% actual |

The item subtotal is 87.435%, the cosmetic subtotal is 12.565%, and the complete
table is exactly 100.000%.

At the maximum 90 spins in a 30-day period, the expected per-player output is
0.45 Totems, 0.135 Enchanted Golden Apples, 0.09 Heavy Cores, 0.0315 Maces, and
about 0.799 Netherite-Ingot equivalents. The rolling cap also prevents a burst at
midnight.

## Wardrobe and physical ownership

`/wardrobe` separates Kill Effects, Auras, Trails, and the hidden Secret category.
A newly won cosmetic begins protected in the wardrobe. Left-click equips it;
right-click withdraws the same unique serial as a physical item. Holding a token
and using `/wardrobe deposit`, or right-clicking its wardrobe entry, stores it
again without creating a copy.

While withdrawn, the token is the ownership record. Moving it to a chest, dropping
it, losing it on death, or listing it in `/ah` immediately disables its effect for
the former holder. Whoever physically receives the valid token can equip or deposit
it. Tokens retain their metadata through the auction house and are rejected by
`/sell` because they have custom metadata. A season reset advances the token
generation so old physical copies cannot become valid again.

The secret uses a black silhouette, the name `???`, and no public percentage. Its
actual weight is five tickets out of 100,000. Winning it triggers a title, expanding
dark rings, portal, dragon-breath, totem, end-rod and challenge effects; equipping it
adds its own aura and kill sequence.

The Java resource pack contains custom item-model icons for the key and all four
cosmetic categories. Bedrock players receive the same named vanilla carrier items
when a Java custom model cannot be translated by Geyser.

## Trophy heads

A real player-versus-player kill adds the victim's named player head to the death
drops. The head is a normal tradable item with provenance metadata and cannot be
sold to `/sell`. One directional killer-victim pair can create at most one trophy
every rolling 24 hours, limiting alternate-account farming without suppressing
ordinary PvP rewards.

