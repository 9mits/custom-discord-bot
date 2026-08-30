package bot.mgx.accessbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/** Hidden weighted tables for Amethyst Airdrop rarity and chest contents. */
final class AirdropCatalog {
    private static final int RARITY_WEIGHT = 10_000;
    private static final int COSMETIC_WEIGHT = 10_000;
    private static final int SHARD_WEIGHT = 2_000;

    enum Rarity {
        COMMON("Common", 5_500, 64, 96, 6, 0),
        RARE("Rare", 3_000, 96, 144, 8, 150),
        LEGENDARY("Legendary", 1_200, 128, 192, 10, 1_000),
        MYTHIC("Mythic", 300, 192, 256, 12, 2_500);

        private final String displayName;
        private final int weight;
        private final int minimumKeys;
        private final int maximumKeys;
        private final int lootRolls;
        private final int cosmeticWeight;

        Rarity(
                String displayName,
                int weight,
                int minimumKeys,
                int maximumKeys,
                int lootRolls,
                int cosmeticWeight
        ) {
            this.displayName = displayName;
            this.weight = weight;
            this.minimumKeys = minimumKeys;
            this.maximumKeys = maximumKeys;
            this.lootRolls = lootRolls;
            this.cosmeticWeight = cosmeticWeight;
        }

        String displayName() {
            return displayName;
        }

        int weight() {
            return weight;
        }

        int minimumKeys() {
            return minimumKeys;
        }

        int maximumKeys() {
            return maximumKeys;
        }

        int lootRolls() {
            return lootRolls;
        }

        int cosmeticWeight() {
            return cosmeticWeight;
        }
    }

    record MaterialLoot(String materialName, int amount) {
        MaterialLoot {
            if (materialName == null || materialName.isBlank() || amount <= 0) {
                throw new IllegalArgumentException("Airdrop material loot must be positive");
            }
        }
    }

    record Contents(
            int keys,
            List<MaterialLoot> materialLoot,
            Optional<String> cosmeticId,
            int shards
    ) {
        Contents {
            materialLoot = List.copyOf(materialLoot);
            cosmeticId = cosmeticId == null ? Optional.empty() : cosmeticId;
        }
    }

    private record LootDefinition(
            String materialName,
            int minimumAmount,
            int maximumAmount,
            int commonWeight,
            int rareWeight,
            int legendaryWeight,
            int mythicWeight
    ) {
        int weight(Rarity rarity) {
            return switch (rarity) {
                case COMMON -> commonWeight;
                case RARE -> rareWeight;
                case LEGENDARY -> legendaryWeight;
                case MYTHIC -> mythicWeight;
            };
        }

        int amount(Rarity rarity, RandomGenerator random) {
            int base = random.nextInt(minimumAmount, maximumAmount + 1);
            int multiplier = switch (rarity) {
                case COMMON -> 1;
                case RARE -> 2;
                case LEGENDARY -> 3;
                case MYTHIC -> 4;
            };
            return Math.multiplyExact(base, multiplier);
        }
    }

    /** Read-only catalog metadata used to build the live control registry. */
    record LootDefinitionView(
            String materialName,
            int minimumAmount,
            int maximumAmount,
            int commonWeight,
            int rareWeight,
            int legendaryWeight,
            int mythicWeight
    ) {
        int weight(Rarity rarity) {
            return switch (rarity) {
                case COMMON -> commonWeight;
                case RARE -> rareWeight;
                case LEGENDARY -> legendaryWeight;
                case MYTHIC -> mythicWeight;
            };
        }
    }

    private static final List<LootDefinition> LOOT = List.of(
            loot("AMETHYST_SHARD", 8, 16, 30, 18, 8, 3),
            loot("DIAMOND", 3, 8, 25, 28, 24, 18),
            loot("EMERALD", 5, 12, 28, 24, 16, 8),
            loot("GOLDEN_APPLE", 1, 3, 17, 18, 14, 8),
            loot("EXPERIENCE_BOTTLE", 8, 16, 15, 12, 7, 3),
            loot("ENDER_PEARL", 4, 8, 12, 8, 4, 2),
            loot("TOTEM_OF_UNDYING", 1, 1, 0, 5, 8, 10),
            loot("NETHERITE_SCRAP", 1, 3, 0, 7, 15, 18),
            loot("ANCIENT_DEBRIS", 1, 3, 0, 5, 14, 20),
            loot("NETHERITE_INGOT", 1, 2, 0, 0, 7, 18),
            loot("ENCHANTED_GOLDEN_APPLE", 1, 1, 0, 1, 5, 12),
            loot("NETHER_STAR", 1, 1, 0, 0, 1, 5),
            loot("BEACON", 1, 1, 0, 0, 0, 2)
    );

    private AirdropCatalog() {
    }

    static Rarity randomRarity(RandomGenerator random) {
        return rarityAt(random.nextInt(RARITY_WEIGHT));
    }

    static Rarity rarityAt(int ticket) {
        if (ticket < 0 || ticket >= RARITY_WEIGHT) {
            throw new IllegalArgumentException("Airdrop rarity ticket must be 0-9,999");
        }
        int cursor = 0;
        for (Rarity rarity : Rarity.values()) {
            cursor += rarity.weight();
            if (ticket < cursor) {
                return rarity;
            }
        }
        throw new IllegalStateException("Airdrop rarity table does not total 10,000");
    }

    static Contents roll(Rarity rarity, RandomGenerator random) {
        int keys = random.nextInt(rarity.minimumKeys(), rarity.maximumKeys() + 1);
        int rolls = rarity.lootRolls() + random.nextInt(3);
        List<MaterialLoot> loot = new ArrayList<>(rolls);
        for (int index = 0; index < rolls; index++) {
            LootDefinition reward = randomLoot(rarity, random);
            loot.add(new MaterialLoot(
                    reward.materialName(), reward.amount(rarity, random)
            ));
        }
        Optional<String> cosmetic = random.nextInt(COSMETIC_WEIGHT) < rarity.cosmeticWeight()
                ? Optional.of(randomCosmetic(random))
                : Optional.empty();
        int shards = random.nextInt(SHARD_WEIGHT) == 0 ? 1 : 0;
        return new Contents(keys, loot, cosmetic, shards);
    }

    static Contents roll(Rarity rarity, RandomGenerator random, GameVariableStore variables) {
        int minimumKeys = variables.rarityValue(rarity, "minimum-keys");
        int maximumKeys = variables.rarityValue(rarity, "maximum-keys");
        int keys = random.nextInt(minimumKeys, maximumKeys + 1);
        int rolls = variables.rarityValue(rarity, "loot-rolls")
                + random.nextInt(variables.integer("airdrop.bonus-loot-rolls") + 1);
        List<MaterialLoot> loot = new ArrayList<>(rolls);
        for (int index = 0; index < rolls; index++) {
            LootDefinition reward = randomLoot(rarity, random, variables);
            int minimum = variables.lootValue(reward.materialName(), "minimum-amount");
            int maximum = variables.lootValue(reward.materialName(), "maximum-amount");
            int base = random.nextInt(minimum, maximum + 1);
            int multiplier = switch (rarity) {
                case COMMON -> 1;
                case RARE -> 2;
                case LEGENDARY -> 3;
                case MYTHIC -> 4;
            };
            loot.add(new MaterialLoot(reward.materialName(), Math.multiplyExact(base, multiplier)));
        }
        Optional<String> cosmetic = random.nextInt(COSMETIC_WEIGHT)
                < variables.rarityValue(rarity, "cosmetic-weight")
                ? Optional.of(randomCosmetic(random)) : Optional.empty();
        int shards = random.nextInt(variables.integer("airdrop.shard-one-in")) == 0
                ? variables.integer("airdrop.shard-amount") : 0;
        return new Contents(keys, loot, cosmetic, shards);
    }

    static List<String> cosmeticIds() {
        return CosmeticCatalog.amethystAirdropRewards().stream()
                .map(CosmeticCatalog.Definition::id)
                .toList();
    }

    static List<LootDefinitionView> lootDefinitions() {
        return LOOT.stream().map(value -> new LootDefinitionView(
                value.materialName(), value.minimumAmount(), value.maximumAmount(),
                value.commonWeight(), value.rareWeight(), value.legendaryWeight(), value.mythicWeight()
        )).toList();
    }

    private static LootDefinition randomLoot(Rarity rarity, RandomGenerator random) {
        int total = LOOT.stream().mapToInt(entry -> entry.weight(rarity)).sum();
        int ticket = random.nextInt(total);
        int cursor = 0;
        for (LootDefinition reward : LOOT) {
            cursor += reward.weight(rarity);
            if (ticket < cursor) {
                return reward;
            }
        }
        throw new IllegalStateException("Airdrop loot table is empty for " + rarity);
    }

    private static LootDefinition randomLoot(
            Rarity rarity, RandomGenerator random, GameVariableStore variables
    ) {
        long total = LOOT.stream().mapToLong(entry -> variables.lootValue(
                entry.materialName(), rarity.name().toLowerCase(java.util.Locale.ROOT) + "-weight"
        )).sum();
        if (total <= 0) {
            throw new IllegalStateException("Airdrop loot table is empty for " + rarity);
        }
        long ticket = random.nextLong(total);
        long cursor = 0;
        for (LootDefinition reward : LOOT) {
            cursor += variables.lootValue(
                    reward.materialName(), rarity.name().toLowerCase(java.util.Locale.ROOT) + "-weight"
            );
            if (ticket < cursor) return reward;
        }
        throw new IllegalStateException("Airdrop loot table is empty for " + rarity);
    }

    private static String randomCosmetic(RandomGenerator random) {
        List<String> ids = cosmeticIds();
        return ids.get(random.nextInt(ids.size()));
    }

    private static LootDefinition loot(
            String material,
            int minimum,
            int maximum,
            int common,
            int rare,
            int legendary,
            int mythic
    ) {
        return new LootDefinition(
                material, minimum, maximum, common, rare, legendary, mythic
        );
    }
}
