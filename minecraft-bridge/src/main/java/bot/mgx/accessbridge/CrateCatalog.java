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

    enum Category {
        RESOURCE("Resources"),
        TRIAL("Trial Chamber"),
        TREASURE("Treasure"),
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

    static int totalWeight() {
        return REWARDS.stream().mapToInt(Reward::weight).sum();
    }

    /** Resolves one uniformly generated ticket in the inclusive range 0..99,999. */
    static Reward rewardAt(int ticket) {
        if (ticket < 0 || ticket >= TOTAL_WEIGHT) {
            throw new IllegalArgumentException(
                    "Crate ticket must be between 0 and " + (TOTAL_WEIGHT - 1)
            );
        }
        int boundary = 0;
        for (Reward reward : REWARDS) {
            boundary += reward.weight();
            if (ticket < boundary) {
                return reward;
            }
        }
        throw new IllegalStateException("Crate reward weights do not cover every ticket");
    }

    static String percentage(int weight) {
        return String.format(Locale.ROOT, "%.3f%%", weight / 1_000.0d);
    }

    private static List<Reward> buildRewards() {
        List<Reward> rewards = new ArrayList<>();
        rewards.add(item(
                "raw_copper", "16 Raw Copper", Category.RESOURCE, 11_200,
                "RAW_COPPER", 16, "A useful mining bundle without shop cash."
        ));
        rewards.add(item(
                "raw_iron", "8 Raw Iron", Category.RESOURCE, 10_800,
                "RAW_IRON", 8, "A small bundle ready to smelt."
        ));
        rewards.add(item(
                "raw_gold", "6 Raw Gold", Category.RESOURCE, 9_200,
                "RAW_GOLD", 6, "A restrained bundle of raw gold."
        ));
        rewards.add(item(
                "emeralds", "4 Emeralds", Category.RESOURCE, 8_700,
                "EMERALD", 4, "Four emeralds for trading or building."
        ));
        rewards.add(item(
                "diamonds", "2 Diamonds", Category.RESOURCE, 6_800,
                "DIAMOND", 2, "Two diamonds, kept well below equipment quantities."
        ));
        rewards.add(item(
                "wind_charges", "16 Wind Charges", Category.TRIAL, 7_000,
                "WIND_CHARGE", 16, "A bundle of movement and combat utility."
        ));
        rewards.add(item(
                "breeze_rods", "4 Breeze Rods", Category.TRIAL, 6_000,
                "BREEZE_ROD", 4, "Four trial-chamber crafting drops."
        ));
        rewards.add(item(
                "golden_apple", "Golden Apple", Category.TREASURE, 6_000,
                "GOLDEN_APPLE", 1, "One normal golden apple."
        ));
        rewards.add(item(
                "echo_shards", "3 Echo Shards", Category.TREASURE, 5_000,
                "ECHO_SHARD", 3, "Three ancient-city crafting shards."
        ));
        rewards.add(item(
                "ominous_bottle", "Ominous Bottle", Category.TRIAL, 4_000,
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
                "totem_of_undying", "Totem of Undying", Category.TREASURE, 750,
                "TOTEM_OF_UNDYING", 1, "A rare single-use survival item."
        ));
        rewards.add(item(
                "netherite_ingot", "Netherite Ingot", Category.RESOURCE, 300,
                "NETHERITE_INGOT", 1, "One complete netherite ingot."
        ));
        rewards.add(item(
                "enchanted_golden_apple", "Enchanted Golden Apple", Category.TREASURE, 200,
                "ENCHANTED_GOLDEN_APPLE", 1, "One exceptionally rare enchanted apple."
        ));
        rewards.add(item(
                "heavy_core", "Heavy Core", Category.TRIAL, 150,
                "HEAVY_CORE", 1, "The rare crafting core for a mace."
        ));
        rewards.add(item(
                "mace", "Mace", Category.TRIAL, 50,
                "MACE", 1, "A complete mace at the table's lowest visible item chance."
        ));
        for (CosmeticCatalog.Definition cosmetic : CosmeticCatalog.all()) {
            rewards.add(cosmetic(cosmetic));
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

    private static Map<String, Reward> indexRewards() {
        Map<String, Reward> indexed = new LinkedHashMap<>();
        for (Reward reward : REWARDS) {
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
