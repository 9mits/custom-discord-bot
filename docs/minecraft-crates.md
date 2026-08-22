# Minecraft crates, cosmetics, and trophies

## Opening rules

One `Mysterious Crate Key` opens exactly one crate. Every accumulated hour a
player remains online earns one physical key, including AFK time. Discord server
boosters earn two keys for that same hour. Partial hours and keys waiting for
inventory space survive restarts, and every completed credit is announced in chat
even when the progress bar is hidden. An administrator can also issue event keys
with `/mgxadmin give <online-player> key [amount]`.

Keys behave like any other item: they stack, go in chests and shulkers, and move
through hoppers. The one restriction is that they cannot be sold to `/shop` or
listed in `/ah`. Existing keys using the former lootbox marker
are upgraded when they enter a player's inventory.

There is no opening cap. Auto Open can consume every available key after one
confirmation, while ordinary Open spends one key at a time.

The reward is selected and saved before the 45-slot wooden crate reel begins. A disconnect or
closed inventory leaves it claimable with `/crate claim`, and the plugin also
attempts delivery on the player's next join. Full inventories do not reroll or
discard a saved reward.

Any reward below 1.000% is announced to the entire server in chat and plays a
challenge sound for every online player. The exact 1.000% boundary is not announced.

## Exact reward table

The base implementation uses 100,000 equally likely integer tickets. All listed
item rewards are absent from `/shop`, and Elytra is deliberately excluded. Crate
Luck temporarily multiplies the ticket weight of every reward below 1.000% by its
advertised 2x-5x value; common reward weights do not increase, and the expanded
pool is rolled directly.

| Reward | Amount | Exact chance |
|---|---:|---:|
| Raw Copper | 16 | 10.360% |
| Raw Iron | 8 | 10.000% |
| Raw Gold | 6 | 8.500% |
| Emerald | 4 | 8.000% |
| Diamond | 2 | 6.300% |
| Wind Charge | 16 | 6.600% |
| Breeze Rod | 4 | 5.800% |
| Golden Apple | 1 | 5.737% |
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
| Potion of Healing II | 1 | 1.000% |
| Potion of Strength II | 1 | 0.750% |
| Potion of Swiftness II | 1 | 0.750% |
| Potion of Fire Resistance | 1 | 0.600% |
| Excavation I | 1 | 0.010% |
| Unbreaking IV | 1 | 0.300% |
| Unbreaking V | 1 | 0.075% |
| Protection V | 1 | 0.125% |
| Fortune IV | 1 | 0.200% |
| Fortune V | 1 | 0.050% |
| Fortune Potion I | 1 | 0.250% |
| Fortune Potion II | 1 | 0.100% |
| Fortune Potion III | 1 | 0.035% |
| Fortune Potion IV | 1 | 0.010% |
| Fortune Potion V | 1 | 0.002% |
| Crate Luck II | 1 | 0.075% |
| Crate Luck III | 1 | 0.025% |
| Crate Luck IV | 1 | 0.005% |
| Crate Luck V | 1 | 0.001% |
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
| Unknown secret Kill Effect | 3 | `???` in game; 0.005% each |
| Unknown secret Aura | 3 | `???` in game; 0.005% each |
| Unknown secret Trail | 3 | `???` in game; 0.005% each |

The item subtotal is 84.610%, the cosmetic subtotal is 15.390%, and the complete
table is exactly 100.000%.

Fortune Potions remain active for 5 minutes, survive reconnects, and multiply
eligible block drops from ores, Ancient Debris, crops, leaves, and the other blocks
affected by vanilla Fortune. Excavation I is pickaxe-only and breaks the matching
3x3 mining face. The extended vanilla books are applied through an anvil.

## Wardrobe and physical ownership

`/wardrobe` separates Kill Effects, Auras, and Trails. A won secret appears inside
its real effect category, so one secret from every category may be equipped together.
There is no separate Secret category or placeholder tile. Secret items use a purple
tooltip frame to distinguish their descriptions from ordinary cosmetics.
A global `In existence` count and the selected token's permanent serial number are
shown in `/wardrobe`. It counts every valid unique token in the current cosmetic
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

The crate hides all nine secrets behind the same dimensional black silhouette, the
name `???`, and no public percentage. Each has an actual weight of five tickets out
of 100,000. There are three secret Kill Effects, three secret Auras, and three secret
Trails. Winning one reveals its real name, custom icon and VFX in the result screen,
physical token and wardrobe.

The Java resource pack contains a distinct custom item-model icon for the key, both
custom potion families, and every individual cosmetic. Bedrock players receive the
same named vanilla carrier items when a Java custom model cannot be translated by
Geyser.

## Trophy heads

A real player-versus-player kill adds the victim's named player head to the death
drops. The head is a normal tradable item with provenance metadata and cannot be
sold to `/sell`. One directional killer-victim pair can create at most one trophy
every rolling 24 hours, limiting alternate-account farming without suppressing
ordinary PvP rewards.
