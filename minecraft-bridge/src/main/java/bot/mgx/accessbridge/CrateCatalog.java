package bot.mgx.accessbridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The single crate reward pool.
 *
 * <p>Weights are exact integer portions of {@value #TOTAL_WEIGHT}; one point is
 * {@code 0.001%}. Rolling an integer ticket first and animating it afterwards keeps
 * the reel cosmetic: closing it cannot change what was won.
 */
final class CrateCatalog {
    static final int TOTAL_WEIGHT = 100_000;
    /** Crate luck is a percentage of a rare reward's weight; 100 is no potion at all. */
    static final int NO_LUCK_PERCENT = 100;
    /** Crate Luck V, and the ceiling once a live event has multiplied a potion. */
    static final int MAX_LUCK_PERCENT = 300;
    /** 0.01% of {@link #TOTAL_WEIGHT}; the server-wide chime is reserved for rarer wins. */
    static final int JACKPOT_WEIGHT = TOTAL_WEIGHT / 10_000;

    enum Category {
        RESOURCE("Resources"),
        TRIAL("Trial Chamber"),
        TREASURE("Treasure"),
        POTION("Potions"),
        ENCHANTMENT("Enchantments"),
        COSMETIC("Cosmetics"),
        SECRET("Secret");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }
    }

    record Reward(
            String id,
            String displayName,
            Category category,
            int weight,
            String materialName,
            int amount,
            String modelKey,
            String cosmeticId,
            String description
    ) {
        Reward {
            id = requireText(id, "Reward ID").toLowerCase(Locale.ROOT);
            displayName = requireText(displayName, "Reward display name");
            if (category == null) {
                throw new IllegalArgumentException("Reward category is required");
            }
            if (weight <= 0) {
                throw new IllegalArgumentException("Reward weight must be positive");
            }
            materialName = requireText(materialName, "Reward material")
                    .toUpperCase(Locale.ROOT);
            if (amount <= 0) {
                throw new IllegalArgumentException("Reward amount must be positive");
            }
            modelKey = requireText(modelKey, "Reward model key").toLowerCase(Locale.ROOT);
            cosmeticId = cosmeticId == null ? null : requireText(cosmeticId, "Cosmetic ID")
                    .toLowerCase(Locale.ROOT);
            description = requireText(description, "Reward description");
            boolean cosmeticCategory = category == Category.COSMETIC || category == Category.SECRET;
            if (cosmeticCategory != (cosmeticId != null)) {
                throw new IllegalArgumentException(
                        "Only cosmetic rewards may reference a cosmetic ID"
                );
            }
        }

        boolean cosmetic() {
            return cosmeticId != null;
        }

        boolean secret() {
            return category == Category.SECRET;
        }

        boolean highImpact() {
            return rare();
        }

        boolean rare() {
            return secret() || weight < 1_000;
        }

        /** Rarer than 0.01%: the only wins the whole server hears rather than only reads. */
        boolean jackpot() {
            return weight < JACKPOT_WEIGHT;
        }

        String displayedChance() {
            return secret() ? "???" : percentage(weight);
        }

        String actualChance() {
            return percentage(weight);
        }

        String rarityDisplay() {
            if (secret()) {
                return "Secret";
            }
            if (cosmetic()) {
                return CosmeticCatalog.find(cosmeticId)
                        .map(CosmeticCatalog.Definition::rarityDisplay)
                        .orElse("Cosmetic");
            }
            if (weight >= 10_000) {
                return "Common";
            }
            if (weight >= 5_000) {
                return "Uncommon";
            }
            if (weight >= 1_000) {
                return "Rare";
            }
            if (weight >= 100) {
                return "Epic";
            }
            return "Legendary";
        }
    }

    private static final List<Reward> REWARDS = buildRewards();
    private static final List<Reward> AMETHYST_REWARDS = buildAmethystRewards();
    private static final Map<String, Reward> BY_ID = indexRewards();

    private CrateCatalog() {
    }

    static Optional<Reward> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_ID.get(id.strip().toLowerCase(Locale.ROOT)));
    }

    static List<Reward> all() {
        return REWARDS;
    }

    static List<Reward> amethyst() {
        return AMETHYST_REWARDS;
    }

    /** Every reward an administrator may grant, across permanent and limited crates. */
    static List<Reward> everyReward() {
        return java.util.stream.Stream.concat(REWARDS.stream(), AMETHYST_REWARDS.stream())
                .toList();
    }

    static boolean isLimitedAmethyst(Reward reward) {
        return reward != null && AMETHYST_REWARDS.contains(reward);
    }

    static int totalWeight() {
        return REWARDS.stream().mapToInt(Reward::weight).sum();
    }

    /** Resolves one uniformly generated ticket in the inclusive range 0..99,999. */
    static Reward rewardAt(int ticket) {
        return rewardAt(REWARDS, ticket);
    }

    static Reward rewardAt(List<Reward> rewards, int ticket) {
        if (ticket < 0 || ticket >= TOTAL_WEIGHT) {
            throw new IllegalArgumentException(
                    "Crate ticket must be between 0 and " + (TOTAL_WEIGHT - 1)
            );
        }
        int boundary = 0;
        for (Reward reward : rewards) {
            boundary += reward.weight();
            if (ticket < boundary) {
                return reward;
            }
        }
        throw new IllegalStateException("Crate reward weights do not cover every ticket");
    }

    static int luckyTotalWeight(int luckPercent) {
        return luckyTotalWeight(REWARDS, luckPercent);
    }

    static int luckyTotalWeight(List<Reward> rewards, int luckPercent) {
        int safePercent = clampLuckPercent(luckPercent);
        return rewards.stream().mapToInt(reward -> effectiveWeight(reward, safePercent)).sum();
    }

    /** Rare rewards receive the advertised proportional weight while commons do not. */
    static Reward rewardAtLucky(int ticket, int luckPercent) {
        return rewardAtLucky(REWARDS, ticket, luckPercent);
    }

    static Reward rewardAtLucky(List<Reward> rewards, int ticket, int luckPercent) {
        int safePercent = clampLuckPercent(luckPercent);
        int total = luckyTotalWeight(rewards, safePercent);
        if (ticket < 0 || ticket >= total) {
            throw new IllegalArgumentException(
                    "Lucky crate ticket must be between 0 and " + (total - 1)
            );
        }
        int boundary = 0;
        for (Reward reward : rewards) {
            boundary += effectiveWeight(reward, safePercent);
            if (ticket < boundary) {
                return reward;
            }
        }
        throw new IllegalStateException("Lucky crate weights do not cover every ticket");
    }

    /** Rounds to the nearest whole ticket so the smallest weights still gain from luck. */
    private static int effectiveWeight(Reward reward, int luckPercent) {
        if (!reward.rare()) {
            return reward.weight();
        }
        return Math.max(1, (Math.multiplyExact(reward.weight(), luckPercent) + 50) / 100);
    }

    /** No potion at all, and the ceiling a potion plus a live event may reach. */
    static int clampLuckPercent(int luckPercent) {
        return Math.max(NO_LUCK_PERCENT, Math.min(MAX_LUCK_PERCENT, luckPercent));
    }

    static String percentage(int weight) {
        return String.format(Locale.ROOT, "%.3f%%", weight / 1_000.0d);
    }

    private static List<Reward> buildRewards() {
        List<Reward> rewards = new ArrayList<>();
        rewards.add(item(
                // Oak Wood, not Oak Log: the shop sells every log, and a crate
                // reward you could simply buy is not a reward. The six-sided
                // bark block is the one wood variant /shop does not stock.
                "oak_wood", "32 Oak Wood", Category.RESOURCE, 10_936,
                "OAK_WOOD", 32, "A building stack the shop does not stock."
        ));
        rewards.add(item(
                "raw_iron", "8 Raw Iron", Category.RESOURCE, 10_556,
                "RAW_IRON", 8, "A small bundle ready to smelt."
        ));
        rewards.add(item(
                "raw_gold", "6 Raw Gold", Category.RESOURCE, 8_973,
                "RAW_GOLD", 6, "A restrained bundle of raw gold."
        ));
        rewards.add(item(
                "emeralds", "4 Emeralds", Category.RESOURCE, 8_445,
                "EMERALD", 4, "Four emeralds for trading or building."
        ));
        rewards.add(item(
                "diamonds", "2 Diamonds", Category.RESOURCE, 6_651,
                "DIAMOND", 2, "Two diamonds, kept well below equipment quantities."
        ));
        rewards.add(item(
                "wind_charges", "16 Wind Charges", Category.TRIAL, 6_967,
                "WIND_CHARGE", 16, "A bundle of movement and combat utility."
        ));
        rewards.add(item(
                "breeze_rods", "4 Breeze Rods", Category.TRIAL, 6_123,
                "BREEZE_ROD", 4, "Four trial-chamber crafting drops."
        ));
        rewards.add(item(
                "golden_apple", "Golden Apple", Category.TREASURE, 6_056,
                "GOLDEN_APPLE", 1, "One normal golden apple."
        ));
        rewards.add(item(
                "echo_shards", "3 Echo Shards", Category.TREASURE, 5_278,
                "ECHO_SHARD", 3, "Three ancient-city crafting shards."
        ));
        rewards.add(item(
                "ominous_bottle", "Ominous Bottle", Category.TRIAL, 4_223,
                "OMINOUS_BOTTLE", 1, "A single trial or raid catalyst."
        ));
        rewards.add(item(
                "heart_of_the_sea", "Heart of the Sea", Category.TREASURE, 3_000,
                "HEART_OF_THE_SEA", 1, "One conduit component."
        ));
        rewards.add(item(
                "shulker_shells", "2 Shulker Shells", Category.TREASURE, 2_000,
                "SHULKER_SHELL", 2, "Exactly enough shells for one shulker box."
        ));
        rewards.add(item(
                "ancient_debris", "Ancient Debris", Category.RESOURCE, 2_000,
                "ANCIENT_DEBRIS", 1, "One piece of ancient debris."
        ));
        rewards.add(item(
                "netherite_scrap", "Netherite Scrap", Category.RESOURCE, 1_500,
                "NETHERITE_SCRAP", 1, "One quarter of the scrap for an ingot."
        ));
        rewards.add(item(
                "totem_of_undying", "Totem of Undying", Category.TREASURE, 413,
                "TOTEM_OF_UNDYING", 1, "A rare single-use survival item."
        ));
        rewards.add(item(
                "netherite_ingot", "Netherite Ingot", Category.RESOURCE, 165,
                "NETHERITE_INGOT", 1, "One complete netherite ingot."
        ));
        rewards.add(item(
                "enchanted_golden_apple", "Enchanted Golden Apple", Category.TREASURE, 110,
                "ENCHANTED_GOLDEN_APPLE", 1, "One exceptionally rare enchanted apple."
        ));
        rewards.add(item(
                "heavy_core", "Heavy Core", Category.TRIAL, 82,
                "HEAVY_CORE", 1, "The rare crafting core for a mace."
        ));
        rewards.add(item(
                "mace", "Mace", Category.TRIAL, 28,
                "MACE", 1, "A complete mace at the table's lowest visible item chance."
        ));
        rewards.add(item(
                "potion_healing_ii", "Potion of Healing II", Category.POTION, 1_000,
                "POTION", 1, "Instantly restores eight health points."
        ));
        rewards.add(item(
                "potion_strength_ii", "Potion of Strength II", Category.POTION, 413,
                "POTION", 1, "A strong combat potion unavailable in the shop."
        ));
        rewards.add(item(
                "potion_swiftness_ii", "Potion of Swiftness II", Category.POTION, 413,
                "POTION", 1, "A fast movement potion unavailable in the shop."
        ));
        rewards.add(item(
                "potion_fire_resistance", "Potion of Fire Resistance", Category.POTION, 330,
                "POTION", 1, "Protection from fire and lava."
        ));
        rewards.add(item(
                "enchant_excavation_i", "Excavation I", Category.ENCHANTMENT, 6,
                "ENCHANTED_BOOK", 1, "A super-rare pickaxe enchantment that mines a 3x3 area."
        ));
        rewards.add(item(
                "enchant_unbreaking_iv", "Unbreaking IV", Category.ENCHANTMENT, 165,
                "ENCHANTED_BOOK", 1, "Pushes Unbreaking one level beyond vanilla."
        ));
        rewards.add(item(
                "enchant_unbreaking_v", "Unbreaking V", Category.ENCHANTMENT, 41,
                "ENCHANTED_BOOK", 1, "The highest Unbreaking level in the crate."
        ));
        rewards.add(item(
                "enchant_protection_v", "Protection V", Category.ENCHANTMENT, 69,
                "ENCHANTED_BOOK", 1, "Armour protection beyond the vanilla limit."
        ));
        rewards.add(item(
                "enchant_fortune_iv", "Fortune IV", Category.ENCHANTMENT, 110,
                "ENCHANTED_BOOK", 1, "A mining fortune level beyond vanilla."
        ));
        rewards.add(item(
                "enchant_fortune_v", "Fortune V", Category.ENCHANTMENT, 28,
                "ENCHANTED_BOOK", 1, "The highest permanent Fortune level."
        ));
        rewards.add(customPotion("fortune_potion_i", "Fortune Potion I", 138,
                "mgx:fortune_potion", "Multiplies eligible ore drops by 1.25x."));
        rewards.add(customPotion("fortune_potion_ii", "Fortune Potion II", 55,
                "mgx:fortune_potion", "Multiplies eligible ore drops by 1.5x."));
        rewards.add(customPotion("fortune_potion_iii", "Fortune Potion III", 19,
                "mgx:fortune_potion", "Multiplies eligible ore drops by 2x."));
        rewards.add(customPotion("fortune_potion_iv", "Fortune Potion IV", 6,
                "mgx:fortune_potion", "Multiplies eligible ore drops by 2.5x."));
        rewards.add(customPotion("fortune_potion_v", "Fortune Potion V", 1,
                "mgx:fortune_potion", "Multiplies eligible ore drops by 3x."));
        rewards.add(customPotion("crate_luck_ii", "Crate Luck II", 41,
                "mgx:crate_luck_potion", "1.5x rare reward weight for a limited time."));
        rewards.add(customPotion("crate_luck_iii", "Crate Luck III", 14,
                "mgx:crate_luck_potion", "2x rare reward weight for a limited time."));
        rewards.add(customPotion("crate_luck_iv", "Crate Luck IV", 3,
                "mgx:crate_luck_potion", "2.5x rare reward weight for a limited time."));
        rewards.add(customPotion("crate_luck_v", "Crate Luck V", 1,
                "mgx:crate_luck_potion", "3x rare reward weight for a limited time."));
        for (CosmeticCatalog.Definition cosmetic : CosmeticCatalog.all()) {
            rewards.add(cosmetic(cosmetic));
        }
        return List.copyOf(rewards);
    }

    private static List<Reward> buildAmethystRewards() {
        List<Reward> rewards = new ArrayList<>();
        rewards.add(item(
                "amethyst_shards", "32 Amethyst Shards", Category.RESOURCE, 25_000,
                "AMETHYST_SHARD", 32, "A bright stack of vanilla amethyst shards."
        ));
        rewards.add(item(
                "amethyst_blocks", "16 Blocks of Amethyst", Category.RESOURCE, 18_000,
                "AMETHYST_BLOCK", 16, "Sixteen musical amethyst building blocks."
        ));
        rewards.add(item(
                "budding_amethyst", "2 Budding Amethyst", Category.TREASURE, 8_000,
                "BUDDING_AMETHYST", 2, "Two rare blocks that grow amethyst buds."
        ));
        rewards.add(item(
                "amethyst_clusters", "16 Amethyst Clusters", Category.RESOURCE, 12_000,
                "AMETHYST_CLUSTER", 16, "A decorative bundle of full-grown clusters."
        ));
        rewards.add(item(
                "amethyst_enchanted_apple", "Enchanted Golden Apple", Category.TREASURE, 5_000,
                "ENCHANTED_GOLDEN_APPLE", 1, "A powerful enchanted golden apple."
        ));
        rewards.add(item(
                "amethyst_excavation_i", "Excavation I", Category.ENCHANTMENT, 8_000,
                "ENCHANTED_BOOK", 1, "A pickaxe enchantment that mines a 3x3 area."
        ));
        rewards.add(amethystItem(
                "amethyst_pickaxe", "Amethyst Pickaxe", Category.TREASURE, 5_000,
                "DIAMOND_PICKAXE", "mgx:amethyst_pickaxe",
                "A 24-hour 3x3 pickaxe with automatic smelting."
        ));
        rewards.add(amethystItem(
                "amethyst_shovel", "Amethyst Shovel", Category.TREASURE, 5_000,
                "DIAMOND_SHOVEL", "mgx:amethyst_shovel",
                "A 24-hour shovel that clears a 3x3 plane."
        ));
        rewards.add(amethystItem(
                "amethyst_axe", "Amethyst Axe", Category.TREASURE, 4_000,
                "DIAMOND_AXE", "mgx:amethyst_axe",
                "A 24-hour axe that brings down an entire tree."
        ));
        rewards.add(amethystItem(
                "amethyst_shield", "Amethyst Shield", Category.TREASURE, 2_500,
                "SHIELD", "mgx:amethyst_shield",
                "A 24-hour reactive shield with crystal combat abilities."
        ));
        rewards.add(amethystItem(
                "amethyst_totem", "Amethyst Totem", Category.TREASURE, 1_500,
                "TOTEM_OF_UNDYING", "mgx:amethyst_totem",
                "A one-use crystal rescue with a ten-heart shell."
        ));
        for (CosmeticCatalog.Definition cosmetic : CosmeticCatalog.amethystRewards()) {
            rewards.add(cosmetic(cosmetic));
        }
        int total = rewards.stream().mapToInt(Reward::weight).sum();
        if (total != TOTAL_WEIGHT) {
            throw new IllegalStateException("Amethyst crate weights total " + total);
        }
        return List.copyOf(rewards);
    }

    private static Reward item(
            String id,
            String displayName,
            Category category,
            int weight,
            String material,
            int amount,
            String description
    ) {
        return new Reward(
                id,
                displayName,
                category,
                weight,
                material,
                amount,
                "minecraft:" + material.toLowerCase(Locale.ROOT),
                null,
                description
        );
    }

    private static Reward cosmetic(CosmeticCatalog.Definition cosmetic) {
        return new Reward(
                "cosmetic_" + cosmetic.id(),
                cosmetic.displayName(),
                cosmetic.secret() ? Category.SECRET : Category.COSMETIC,
                cosmetic.weight(),
                cosmetic.materialName(),
                1,
                cosmetic.modelKey(),
                cosmetic.id(),
                cosmetic.description()
        );
    }

    private static Reward customPotion(
            String id, String displayName, int weight, String modelKey, String description
    ) {
        return new Reward(
                id, displayName, Category.POTION, weight, "POTION", 1,
                modelKey, null, description
        );
    }

    private static Reward amethystItem(
            String id,
            String displayName,
            Category category,
            int weight,
            String material,
            String modelKey,
            String description
    ) {
        return new Reward(
                id, displayName, category, weight, material, 1,
                modelKey, null, description
        );
    }

    private static Map<String, Reward> indexRewards() {
        Map<String, Reward> indexed = new LinkedHashMap<>();
        for (Reward reward : java.util.stream.Stream.concat(
                REWARDS.stream(), AMETHYST_REWARDS.stream()
        ).toList()) {
            if (indexed.putIfAbsent(reward.id(), reward) != null) {
                throw new IllegalStateException("Duplicate crate reward ID " + reward.id());
            }
        }
        return Map.copyOf(indexed);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.strip();
    }
}
