# Minecraft crates, cosmetics, and trophies

## Opening rules

One `Mysterious Crate Key` opens exactly one crate. Every accumulated hour a
player remains online earns one physical key, including AFK time. Partial hours and
keys waiting for inventory space survive restarts. An administrator can also issue
event keys with `/mgxadmin give <online-player> key [amount]`.

Keys cannot be sold to `/shop`, listed in `/ah`, placed in containers, or moved by
hoppers. They remain usable in a player's inventory or offhand and may be dropped
for a direct player-to-player trade. Existing keys using the former lootbox marker
are upgraded when they enter a player's inventory.

Each player may reserve at most 12 openings in any rolling 24-hour window. The
limit is recorded when the key is consumed, so closing the animation or leaving
the server cannot bypass it. Odds never decrease and there is no pity modifier;
the percentages below therefore remain exact on every permitted opening. A player
online for more than 12 hours may retain or directly trade the extra keys.

The reward is selected and saved before the 45-slot wooden crate reel begins. A disconnect or
closed inventory leaves it claimable with `/crate claim`, and the plugin also
attempts delivery on the player's next join. Full inventories do not reroll or
discard a saved reward.

Any reward below 1.000% is announced to the entire server in chat and plays a
challenge sound for every online player. The exact 1.000% boundary is not announced.

## Exact reward table

The implementation uses 100,000 equally likely integer tickets. All listed item
rewards are absent from `/shop`, and Elytra is deliberately excluded.

| Reward | Amount | Exact chance |
|---|---:|---:|
| Raw Copper | 16 | 11.200% |
| Raw Iron | 8 | 10.800% |
| Raw Gold | 6 | 9.200% |
| Emerald | 4 | 8.700% |
| Diamond | 2 | 6.800% |
| Wind Charge | 16 | 7.000% |
| Breeze Rod | 4 | 6.000% |
| Golden Apple | 1 | 6.000% |
| Echo Shard | 3 | 5.000% |
| Ominous Bottle | 1 | 4.000% |
| Heart of the Sea | 1 | 3.000% |
| Shulker Shell | 2 | 2.000% |
| Ancient Debris | 1 | 2.000% |
| Netherite Scrap | 1 | 1.500% |
| Totem of Undying | 1 | 0.750% |
| Netherite Ingot | 1 | 0.300% |
| Enchanted Golden Apple | 1 | 0.200% |
| Heavy Core | 1 | 0.150% |
| Mace | 1 | 0.050% |
| Blood Burst kill effect | 1 | 2.500% |
| Frozen Shatter kill effect | 1 | 1.000% |
| Shining Light kill effect | 1 | 0.500% |
| Void Collapse kill effect | 1 | 0.150% |
| Soul Requiem kill effect | 1 | 0.050% |
| Solar Orbit aura | 1 | 2.000% |
| Crimson Orbit aura | 1 | 0.750% |
| Emerald Orbit aura | 1 | 0.400% |
| Amethyst Orbit aura | 1 | 0.150% |
| Celestial Crown aura | 1 | 0.030% |
| Ember Trail | 1 | 5.000% |
| Blood Trail | 1 | 1.000% |
| Frost Trail | 1 | 0.750% |
| Cherry Blossom Trail | 1 | 0.500% |
| Drool Trail | 1 | 0.400% |
| Ender Trail | 1 | 0.150% |
| Prismatic Trail | 1 | 0.015% |
| Unknown secret cosmetic | 1 | `???` in game; 0.005% actual |

The item subtotal is 84.650%, the cosmetic subtotal is 15.350%, and the complete
table is exactly 100.000%.

At the maximum 360 openings in a 30-day period, the expected per-player output is
2.7 Totems, 0.72 Enchanted Golden Apples, 0.54 Heavy Cores, 0.18 Maces, and
about 4.23 Netherite-Ingot equivalents. The rolling cap also prevents a burst at
midnight.

## Wardrobe and physical ownership

`/wardrobe` separates Kill Effects, Auras, Trails, and the hidden Secret category.
A global `In existence` count is shown beside every cosmetic in both `/wardrobe`
and the crate odds menu. It counts every valid unique token in the current cosmetic
generation, whether stored in a wardrobe or represented by a physical tradable item.
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
