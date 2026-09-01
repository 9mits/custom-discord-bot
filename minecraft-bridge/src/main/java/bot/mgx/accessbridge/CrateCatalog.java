package bot.mgx.accessbridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    /** Live tuning; the constants above stay the defaults and stand alone in tests. */
    private static volatile java.util.function.ToDoubleFunction<String> tuning = key -> Double.NaN;

    static void tuningSource(java.util.function.ToDoubleFunction<String> source) {
        if (source != null) {
            tuning = source;
        }
    }

    private static double tuned(String key, double fallback) {
        double value = tuning.applyAsDouble(key);
        return Double.isNaN(value) ? fallback : value;
    }

    /** 0.01% of {@link #TOTAL_WEIGHT}; the server-wide chime is reserved for rarer wins. */
    static final int JACKPOT_WEIGHT = TOTAL_WEIGHT / 10_000;
    static final int HIDDEN_AMETHYST_ONE_IN = CosmeticCatalog.HIDDEN_AMETHYST_ONE_IN;

    enum RevealTier {
        NONE,
        LEGENDARY,
        MYTHIC,
        SECRET,
        GENUINE_SECRET
    }

    enum Category {
        RESOURCE("Resources"),
        TRIAL("Trial Chamber"),
        TREASURE("Treasure"),
        POTION("Potions"),
        ENCHANTMENT("Enchantments"),
        COSMETIC("Cosmetics"),
        SECRET("Exotic");

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

        /** The original reward ID when the Shard Crate reweights an existing prize. */
        String sourceId() {
            return id.startsWith("shard_") ? id.substring("shard_".length()) : id;
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
            if (isHiddenAmethyst(this)) {
                return String.format(Locale.ROOT, "1 in %,d", HIDDEN_AMETHYST_ONE_IN);
            }
            return secret() ? "???" : percentage(weight);
        }

        String actualChance() {
            if (isHiddenAmethyst(this)) {
                return String.format(Locale.ROOT, "1 in %,d", HIDDEN_AMETHYST_ONE_IN);
            }
            return percentage(weight);
        }

        String rarityDisplay() {
            if (isHiddenAmethyst(this)) {
                return "Secret";
            }
            if (secret()) {
                return "Exotic";
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

        RevealTier revealTier() {
            if (isHiddenAmethyst(this)) {
                return RevealTier.GENUINE_SECRET;
            }
            if (secret()) {
                return RevealTier.SECRET;
            }
            return switch (rarityDisplay()) {
                case "Mythic" -> RevealTier.MYTHIC;
                case "Legendary" -> RevealTier.LEGENDARY;
                default -> RevealTier.NONE;
            };
        }
    }

    private static final List<Reward> REWARDS = buildRewards();
    private static final List<Reward> AMETHYST_REWARDS = buildAmethystRewards();
    private static final List<Reward> SHARD_REWARDS = buildShardRewards();
    private static final List<Reward> HIDDEN_AMETHYST_REWARDS =
            CosmeticCatalog.hiddenAmethystRewards().stream().map(CrateCatalog::cosmetic).toList();
    private static final Set<String> AMETHYST_EXCLUSIVE_IDS = Set.of(
            "amethyst_pickaxe", "amethyst_shovel", "amethyst_axe",
            "amethyst_shield", "amethyst_totem"
    );
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

    static List<Reward> shard() {
        return SHARD_REWARDS;
    }

    static List<Reward> hiddenAmethyst() {
        return HIDDEN_AMETHYST_REWARDS;
    }

    static List<Reward> amethystAdminRewards() {
        return java.util.stream.Stream.concat(
                AMETHYST_REWARDS.stream(), HIDDEN_AMETHYST_REWARDS.stream()
        ).toList();
    }

    /** The built-in reward list for one crate, before an owner's additions or removals. */
    static List<Reward> builtIn(CrateKind kind) {
        return switch (kind) {
            case DEFAULT -> REWARDS;
            case AMETHYST -> AMETHYST_REWARDS;
            case SHARD -> SHARD_REWARDS;
        };
    }

    static java.util.Set<String> builtInIds(CrateKind kind) {
        return builtIn(kind).stream().map(Reward::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    static Optional<Reward> builtInReward(CrateKind kind, String id) {
        return builtIn(kind).stream().filter(reward -> reward.id().equals(id)).findFirst();
    }

    /**
     * What the crate actually contains right now: the built-ins an owner has not removed,
     * plus the ones they added.
     *
     * <p>Order matters and is deliberate — built-ins keep their catalogue order so the
     * odds pages do not reshuffle when something is added, and additions land at the end.
     */
    static List<Reward> effectiveRewards(CrateKind kind, CustomCatalogStore custom) {
        if (custom == null) {
            return builtIn(kind);
        }
        java.util.Set<String> removed = custom.disabledRewards(kind.key());
        List<Reward> rewards = new java.util.ArrayList<>(
                builtIn(kind).stream().filter(reward -> !removed.contains(reward.id())).toList()
        );
        for (CustomCatalogStore.CrateAddition addition : custom.addedRewards(kind.key())) {
            rewards.add(fromAddition(addition));
        }
        return List.copyOf(rewards);
    }

    /**
     * Turns a stored addition into a reward the rest of the crate engine can use.
     *
     * <p>The description is what the odds screen shows under the name. Rewards must have
     * one, but making an owner write flavour text before they can add copper is friction
     * for its own sake, so a blank one becomes a plain statement of what the reward is.
     */
    static Reward fromAddition(CustomCatalogStore.CrateAddition addition) {
        String description = addition.description() == null ? "" : addition.description().strip();
        if (description.isEmpty()) {
            description = addition.amount() + "x "
                    + addition.material().toLowerCase(Locale.ROOT).replace('_', ' ') + ".";
        }
        return item(
                addition.id(),
                addition.displayName(),
                Category.valueOf(addition.category()),
                Math.max(1, addition.weight()),
                addition.material(),
                addition.amount(),
                description
        );
    }

    /** Every reward an administrator may grant, across permanent and limited crates. */
    static List<Reward> everyReward() {
        return java.util.stream.Stream.of(
                        REWARDS.stream(), AMETHYST_REWARDS.stream(),
                        HIDDEN_AMETHYST_REWARDS.stream(), SHARD_REWARDS.stream()
                )
                .flatMap(stream -> stream)
                .toList();
    }

    /** One real catalog entry for exercising the exact reveal path without granting it. */
    static Optional<Reward> revealExample(RevealTier tier) {
        if (tier == null || tier == RevealTier.NONE) {
            return Optional.empty();
        }
        return everyReward().stream().filter(reward -> reward.revealTier() == tier).findFirst();
    }

    static boolean isExclusiveAmethyst(Reward reward) {
        return reward != null
                && (AMETHYST_REWARDS.contains(reward) || HIDDEN_AMETHYST_REWARDS.contains(reward))
                && (reward.cosmetic() || AMETHYST_EXCLUSIVE_IDS.contains(reward.id()));
    }

    static boolean isHiddenAmethyst(Reward reward) {
        return reward != null && HIDDEN_AMETHYST_REWARDS.contains(reward);
    }

    static boolean isAmethyst(Reward reward) {
        return reward != null
                && (AMETHYST_REWARDS.contains(reward) || HIDDEN_AMETHYST_REWARDS.contains(reward));
    }

    static boolean isShard(Reward reward) {
        return reward != null && SHARD_REWARDS.contains(reward);
    }

    /** A separate fixed jackpot roll keeps the reward absent from every published table. */
    static Optional<Reward> hiddenAmethystAt(int ticket) {
        if (ticket < 0 || ticket >= HIDDEN_AMETHYST_ONE_IN) {
            throw new IllegalArgumentException(
                    "Hidden Amethyst ticket must be between 0 and "
                            + (HIDDEN_AMETHYST_ONE_IN - 1)
            );
        }
        return ticket == 0 ? Optional.of(HIDDEN_AMETHYST_REWARDS.getFirst()) : Optional.empty();
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
        int safePercent = clampRollPercent(luckPercent);
        return rewards.stream().mapToInt(reward -> effectiveWeight(reward, safePercent)).sum();
    }

    /** Rare rewards receive the advertised proportional weight while commons do not. */
    static Reward rewardAtLucky(int ticket, int luckPercent) {
        return rewardAtLucky(REWARDS, ticket, luckPercent);
    }

    static Reward rewardAtLucky(List<Reward> rewards, int ticket, int luckPercent) {
        int safePercent = clampRollPercent(luckPercent);
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
        return (int) Math.max(tuned("crates.luck.minimum-percent", NO_LUCK_PERCENT),
                Math.min(tuned("crates.luck.maximum-percent", MAX_LUCK_PERCENT), luckPercent));
    }

    /**
     * The band a roll may actually use. Wider at the bottom than {@link #clampLuckPercent}
     * because {@link CrateOddsBalance} is allowed to nerf below the baseline, while a
     * potion on its own never is.
     */
    static int clampRollPercent(int percent) {
        return (int) Math.max(CrateOddsBalance.FLOOR_PERCENT,
                Math.min(tuned("crates.luck.maximum-percent", MAX_LUCK_PERCENT), percent));
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
                "RAW_GOLD", 6, "Smelt it into ingots for crafting or bartering."
        ));
        rewards.add(item(
                "emeralds", "4 Emeralds", Category.RESOURCE, 8_445,
                "EMERALD", 4, "Four emeralds for trading or building."
        ));
        rewards.add(item(
                "diamonds", "2 Diamonds", Category.RESOURCE, 6_651,
                "DIAMOND", 2, "Two diamonds for tools, armour, or trading."
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
                "MACE", 1, "A complete heavy weapon built for smash attacks."
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

    /**
     * The limited pool.
     *
     * <p>Weighted so that roughly five openings in eight land on a common: a crate
     * whose commons are a minority makes its rare tier feel routine, which is the
     * one thing a limited crate cannot afford. The chase tier is fixed at 600 and
     * is not what was rebalanced — the tiers above the commons were.
     */
    private static List<Reward> buildAmethystRewards() {
        List<Reward> rewards = new ArrayList<>();
        // Common - 63,000 of 100,000, and every one of them purple.
        //
        // The geode shell was the obvious theme and the wrong one: calcite is white
        // stone and smooth basalt and tinted glass both read as black, so five
        // openings in eight paid out something that looked like rubble. What the
        // crate is called is what it should be full of.
        rewards.add(item(
                "amethyst_shards", "32 Amethyst Shards", Category.RESOURCE, 13_000,
                "AMETHYST_SHARD", 32, "A bright stack of vanilla amethyst shards."
        ));
        rewards.add(item(
                "amethyst_blocks", "16 Blocks of Amethyst", Category.RESOURCE, 10_000,
                "AMETHYST_BLOCK", 16, "Sixteen musical amethyst building blocks."
        ));
        rewards.add(item(
                "amethyst_purpur", "64 Purpur Blocks", Category.RESOURCE, 10_000,
                "PURPUR_BLOCK", 64, "A full stack of violet End stone brick."
        ));
        rewards.add(item(
                "amethyst_purple_glass", "32 Purple Stained Glass", Category.RESOURCE, 10_000,
                "PURPLE_STAINED_GLASS", 32, "Amethyst light, in a pane you can build with."
        ));
        rewards.add(item(
                "amethyst_purple_concrete", "64 Purple Concrete", Category.RESOURCE, 10_000,
                "PURPLE_CONCRETE", 64, "The flattest, richest purple in the game."
        ));
        rewards.add(item(
                "amethyst_golden_carrots", "16 Golden Carrots", Category.RESOURCE, 10_000,
                "GOLDEN_CARROT", 16, "Reliable food for long opening sessions."
        ));
        // Uncommon - 22,000.
        rewards.add(item(
                "amethyst_clusters", "16 Amethyst Clusters", Category.RESOURCE, 6_000,
                "AMETHYST_CLUSTER", 16, "A decorative bundle of full-grown clusters."
        ));
        rewards.add(item(
                "amethyst_experience_bottles", "16 Bottles o' Enchanting", Category.RESOURCE, 5_500,
                "EXPERIENCE_BOTTLE", 16, "A bundle of experience for repairs and enchanting."
        ));
        rewards.add(item(
                "amethyst_iron_ingots", "16 Iron Ingots", Category.RESOURCE, 5_500,
                "IRON_INGOT", 16, "A useful vanilla building and crafting bundle."
        ));
        rewards.add(item(
                "amethyst_ender_pearls", "8 Ender Pearls", Category.RESOURCE, 5_000,
                "ENDER_PEARL", 8, "Eight pearls for travel or Eyes of Ender."
        ));
        // Rare - 11,000.
        rewards.add(item(
                "amethyst_gold_ingots", "8 Gold Ingots", Category.RESOURCE, 2_000,
                "GOLD_INGOT", 8, "Eight gold ingots for crafting or bartering."
        ));
        rewards.add(item(
                "amethyst_glowstone", "32 Glowstone Dust", Category.RESOURCE, 1_400,
                "GLOWSTONE_DUST", 32, "Bright dust for brewing and lighting."
        ));
        rewards.add(item(
                "amethyst_emeralds", "4 Emeralds", Category.RESOURCE, 1_200,
                "EMERALD", 4, "Four emeralds for villager trading."
        ));
        rewards.add(item(
                "amethyst_lapis", "32 Lapis Lazuli", Category.RESOURCE, 1_200,
                "LAPIS_LAZULI", 32, "A full enchanting-table supply."
        ));
        rewards.add(item(
                "amethyst_redstone", "32 Redstone Dust", Category.RESOURCE, 1_200,
                "REDSTONE", 32, "A compact redstone engineering supply."
        ));
        rewards.add(item(
                "amethyst_potion_healing_ii", "Potion of Healing II", Category.POTION, 1_000,
                "POTION", 1, "Instantly restores eight health points."
        ));
        rewards.add(item(
                "amethyst_diamonds", "2 Diamonds", Category.RESOURCE, 1_000,
                "DIAMOND", 2, "Two diamonds for tools, armour, or trading."
        ));
        rewards.add(item(
                "budding_amethyst", "2 Budding Amethyst", Category.TREASURE, 1_000,
                "BUDDING_AMETHYST", 2, "Two rare blocks that grow amethyst buds."
        ));
        rewards.add(item(
                "amethyst_golden_apples", "2 Golden Apples", Category.TREASURE, 1_000,
                "GOLDEN_APPLE", 2, "Two vanilla golden apples for dangerous fights."
        ));
        // Epic - 3,400.
        rewards.add(item(
                "amethyst_potion_strength_ii", "Potion of Strength II", Category.POTION, 350,
                "POTION", 1, "A strong combat potion for difficult fights."
        ));
        rewards.add(item(
                "amethyst_potion_swiftness_ii", "Potion of Swiftness II", Category.POTION, 350,
                "POTION", 1, "A fast movement potion for combat or travel."
        ));
        rewards.add(item(
                "amethyst_potion_fire_resistance", "Potion of Fire Resistance", Category.POTION, 320,
                "POTION", 1, "Protection from fire and lava."
        ));
        rewards.add(item(
                "amethyst_wind_charges", "16 Wind Charges", Category.TRIAL, 250,
                "WIND_CHARGE", 16, "Movement and combat utility from trial chambers."
        ));
        rewards.add(item(
                "amethyst_potion_regeneration_ii", "Potion of Regeneration II", Category.POTION, 210,
                "POTION", 1, "Rapidly regenerates health for ninety seconds."
        ));
        rewards.add(item(
                "amethyst_breeze_rods", "4 Breeze Rods", Category.TRIAL, 180,
                "BREEZE_ROD", 4, "Four trial-chamber crafting drops."
        ));
        rewards.add(item(
                "amethyst_potion_night_vision", "Potion of Night Vision", Category.POTION, 150,
                "POTION", 1, "Eight minutes of clear vision in darkness."
        ));
        rewards.add(item(
                "amethyst_potion_water_breathing", "Potion of Water Breathing", Category.POTION, 150,
                "POTION", 1, "Eight minutes of underwater breathing."
        ));
        rewards.add(item(
                "amethyst_enchant_mending_i", "Mending", Category.ENCHANTMENT, 250,
                "ENCHANTED_BOOK", 1, "The vanilla Mending enchantment."
        ));
        rewards.add(item(
                "amethyst_enchant_protection_iv", "Protection IV", Category.ENCHANTMENT, 200,
                "ENCHANTED_BOOK", 1, "Maximum vanilla general armour protection."
        ));
        rewards.add(item(
                "amethyst_enchant_sharpness_v", "Sharpness V", Category.ENCHANTMENT, 200,
                "ENCHANTED_BOOK", 1, "Maximum vanilla melee damage."
        ));
        rewards.add(item(
                "amethyst_enchant_efficiency_v", "Efficiency V", Category.ENCHANTMENT, 200,
                "ENCHANTED_BOOK", 1, "Maximum vanilla tool speed."
        ));
        rewards.add(item(
                "amethyst_enchant_fortune_iii", "Fortune III", Category.ENCHANTMENT, 170,
                "ENCHANTED_BOOK", 1, "Maximum vanilla Fortune."
        ));
        rewards.add(item(
                "amethyst_enchant_looting_iii", "Looting III", Category.ENCHANTMENT, 140,
                "ENCHANTED_BOOK", 1, "Maximum vanilla mob-drop Looting."
        ));
        rewards.add(item(
                "amethyst_enchant_silk_touch_i", "Silk Touch", Category.ENCHANTMENT, 140,
                "ENCHANTED_BOOK", 1, "Collects many blocks in their original form."
        ));
        rewards.add(item(
                "amethyst_enchant_unbreaking_iii", "Unbreaking III", Category.ENCHANTMENT, 140,
                "ENCHANTED_BOOK", 1, "Maximum vanilla durability protection."
        ));
        // Chase - 600, unchanged. Rebalancing the tiers above must not touch these.
        rewards.add(item(
                "amethyst_enchanted_apple", "Enchanted Golden Apple", Category.TREASURE, 50,
                "ENCHANTED_GOLDEN_APPLE", 1, "A powerful enchanted golden apple."
        ));
        rewards.add(item(
                "amethyst_excavation_i", "Excavation I", Category.ENCHANTMENT, 5,
                "ENCHANTED_BOOK", 1, "A pickaxe enchantment that mines a 3x3 area."
        ));
        rewards.add(amethystItem(
                "amethyst_pickaxe", "Amethyst Pickaxe", Category.TREASURE, 20,
                "DIAMOND_PICKAXE", "mgx:amethyst_pickaxe",
                "Activates for 24 hours on first use: 3x3 mining and automatic smelting."
        ));
        rewards.add(amethystItem(
                "amethyst_shovel", "Amethyst Shovel", Category.TREASURE, 25,
                "DIAMOND_SHOVEL", "mgx:amethyst_shovel",
                "Activates for 24 hours on first use and clears a 3x3 digging plane."
        ));
        rewards.add(amethystItem(
                "amethyst_axe", "Amethyst Axe", Category.TREASURE, 30,
                "DIAMOND_AXE", "mgx:amethyst_axe",
                "Activates for 24 hours on first use and fells an entire tree."
        ));
        rewards.add(amethystItem(
                "amethyst_shield", "Amethyst Shield", Category.TREASURE, 5,
                "SHIELD", "mgx:amethyst_shield",
                "Activates for 24 hours on its first block with crystal combat abilities."
        ));
        rewards.add(amethystItem(
                "amethyst_totem", "Amethyst Totem", Category.TREASURE, 10,
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

    /**
     * Permanent premium pool bought with Shards rather than ordinary crate keys.
     * Every entry is one of the strongest rewards already proven by another crate;
     * only its weight in this pool changes. Exotic cosmetics remain a combined
     * 0.24%, and Iridescent Imperium gets its separate unchanged 1-in-500,000 roll.
     */
    private static List<Reward> buildShardRewards() {
        List<Reward> rewards = new ArrayList<>();
        rewards.add(shardCopy("enchant_excavation_i", 2_500));
        rewards.add(shardCopy("enchant_unbreaking_v", 6_000));
        rewards.add(shardCopy("enchant_protection_v", 6_000));
        rewards.add(shardCopy("enchant_fortune_v", 6_000));
        rewards.add(shardCopy("fortune_potion_v", 4_500));
        rewards.add(shardCopy("crate_luck_v", 4_500));
        rewards.add(shardCopy("mace", 7_000));
        rewards.add(shardCopy("heavy_core", 7_000));
        rewards.add(shardCopy("enchanted_golden_apple", 7_500));
        rewards.add(shardCopy(
                "netherite_ingot", "2 Netherite Ingots", 9_000, 2,
                "Two complete netherite ingots."
        ));
        rewards.add(shardCopy("amethyst_pickaxe", 7_000));
        rewards.add(shardCopy("amethyst_shovel", 7_000));
        rewards.add(shardCopy("amethyst_axe", 7_000));
        rewards.add(shardCopy("amethyst_shield", 6_500));
        rewards.add(shardCopy("amethyst_totem", 5_060));

        for (String cosmeticId : List.of(
                "soul_requiem", "celestial_crown", "prismatic_trail",
                "amethyst_ascension", "geode_cathedral", "crystal_guillotine",
                "violet_detonation", "shardstorm_wake", "geode_bloom"
        )) {
            rewards.add(shardCosmetic(cosmeticId, 800));
        }
        for (String cosmeticId : List.of(
                "event_horizon", "reapers_verdict", "divine_rupture",
                "astral_sovereign", "infernal_dominion", "abyssal_seraph",
                "galaxy_wake", "phantom_chains", "reality_fracture",
                "crystalline_extinction", "resonant_apotheosis", "shattered_continuum"
        )) {
            rewards.add(shardCosmetic(cosmeticId, 20));
        }
        int total = rewards.stream().mapToInt(Reward::weight).sum();
        if (total != TOTAL_WEIGHT) {
            throw new IllegalStateException("Shard crate weights total " + total);
        }
        return List.copyOf(rewards);
    }

    private static Reward shardCopy(String sourceId, int weight) {
        Reward source = originalReward(sourceId);
        return shardCopy(
                sourceId, source.displayName(), weight, source.amount(), source.description()
        );
    }

    private static Reward shardCopy(
            String sourceId, String displayName, int weight, int amount, String description
    ) {
        Reward source = originalReward(sourceId);
        return new Reward(
                "shard_" + source.id(), displayName, source.category(), weight,
                source.materialName(), amount, source.modelKey(), source.cosmeticId(), description
        );
    }

    private static Reward shardCosmetic(String cosmeticId, int weight) {
        CosmeticCatalog.Definition cosmetic = CosmeticCatalog.find(cosmeticId).orElseThrow(
                () -> new IllegalStateException("Unknown Shard Crate cosmetic " + cosmeticId)
        );
        return new Reward(
                "shard_cosmetic_" + cosmetic.id(), cosmetic.displayName(),
                cosmetic.secret() ? Category.SECRET : Category.COSMETIC,
                weight, cosmetic.materialName(), 1, cosmetic.modelKey(), cosmetic.id(),
                cosmetic.description()
        );
    }

    private static Reward originalReward(String id) {
        return java.util.stream.Stream.concat(REWARDS.stream(), AMETHYST_REWARDS.stream())
                .filter(reward -> reward.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown Shard Crate reward " + id));
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
        for (Reward reward : java.util.stream.Stream.of(
                        REWARDS.stream(), AMETHYST_REWARDS.stream(),
                        HIDDEN_AMETHYST_REWARDS.stream(), SHARD_REWARDS.stream()
                ).flatMap(stream -> stream).toList()) {
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
