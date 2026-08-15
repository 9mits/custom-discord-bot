package bot.mgx.accessbridge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The clan upgrade ladder: what each level costs, what it grants, and how it is badged.
 *
 * <p>Deliberately free of Bukkit imports so the whole table stays unit-testable;
 * {@link ClanStore} and the command classes delegate here rather than restating any of
 * it. Costs name their material as a string for the same reason — the service layer
 * resolves those to {@code Material}.
 *
 * <p>Perk figures are cumulative totals held at a level, not per-level increments: a
 * level 4 clan has two extra hearts in all, not two on top of level 3's one.
 */
final class ClanLevel {
    /** The highest level a clan can see documented or reach through normal play. */
    static final int MAX_PUBLIC_LEVEL = 5;
    /**
     * Revealed only to a clan that already stands at {@link #MAX_PUBLIC_LEVEL}, and
     * holdable by one clan on the server. Never name it in help, docs or tab
     * completion — {@link #isSecret(int)} guards the surfaces that enumerate levels.
     */
    static final int SECRET_LEVEL = 6;

    /** Totals a clan holds at a level, applied to every member while they belong to it. */
    record Perks(
            int extraHearts,
            double strength,
            double saturation,
            double diggingSpeed,
            double resistance,
            double speed
    ) {
        static final Perks NONE = new Perks(0, 0, 0, 0, 0, 0);

        boolean isNone() {
            return equals(NONE);
        }
    }

    /** One line of an upgrade price. The material is a {@code Material} enum name. */
    record Cost(String material, int amount) {
    }

    private static final Map<Integer, Perks> PERKS = Map.of(
            0, Perks.NONE,
            1, new Perks(0, 0.03, 0.03, 0.00, 0.00, 0.00),
            2, new Perks(0, 0.05, 0.05, 0.10, 0.00, 0.00),
            3, new Perks(1, 0.10, 0.10, 0.15, 0.05, 0.05),
            4, new Perks(2, 0.10, 0.15, 0.20, 0.10, 0.10),
            5, new Perks(3, 0.10, 0.15, 0.25, 0.15, 0.15),
            6, new Perks(3, 0.10, 0.15, 0.25, 0.15, 0.15)
    );

    private static final Map<Integer, List<Cost>> COSTS = Map.of(
            1, List.of(new Cost("DIAMOND", 30)),
            2, List.of(new Cost("DIAMOND_BLOCK", 30)),
            3, List.of(
                    new Cost("DIAMOND", 64),
                    new Cost("NETHERITE_INGOT", 10),
                    new Cost("NETHER_STAR", 1)
            ),
            4, List.of(new Cost("NETHERITE_BLOCK", 10)),
            5, List.of(
                    new Cost("NETHERITE_BLOCK", 64),
                    new Cost("NETHER_STAR", 3),
                    new Cost("ENCHANTED_GOLDEN_APPLE", 1)
            ),
            6, List.of(new Cost("DRAGON_EGG", 1))
    );

    /** A clan's roster before it buys any of {@link #MEMBER_TIERS}. */
    static final int STARTING_MEMBER_SLOTS = 3;

    /**
     * Roster upgrades as (slots, price), one member at a time from
     * {@link #STARTING_MEMBER_SLOTS} to 25.
     *
     * <p>Diamonds, then diamond blocks, then netherite, each step dearer than the last
     * by {@link WealthValues} — a test holds that ordering, since a cheaper later tier
     * would let a clan skip the ladder. The first few slots are deliberately near-free
     * so a new clan can grow the day it is founded.
     */
    static final List<MemberTier> MEMBER_TIERS = List.of(
            new MemberTier(4, new Cost("DIAMOND", 1)),
            new MemberTier(5, new Cost("DIAMOND", 2)),
            new MemberTier(6, new Cost("DIAMOND", 3)),
            new MemberTier(7, new Cost("DIAMOND", 4)),
            new MemberTier(8, new Cost("DIAMOND", 5)),
            new MemberTier(9, new Cost("DIAMOND", 6)),
            new MemberTier(10, new Cost("DIAMOND", 7)),
            new MemberTier(11, new Cost("DIAMOND", 8)),
            new MemberTier(12, new Cost("DIAMOND", 10)),
            new MemberTier(13, new Cost("DIAMOND", 12)),
            new MemberTier(14, new Cost("DIAMOND", 14)),
            new MemberTier(15, new Cost("DIAMOND", 16)),
            new MemberTier(16, new Cost("DIAMOND_BLOCK", 2)),
            new MemberTier(17, new Cost("DIAMOND_BLOCK", 3)),
            new MemberTier(18, new Cost("DIAMOND_BLOCK", 4)),
            new MemberTier(19, new Cost("DIAMOND_BLOCK", 5)),
            new MemberTier(20, new Cost("DIAMOND_BLOCK", 6)),
            new MemberTier(21, new Cost("DIAMOND_BLOCK", 7)),
            new MemberTier(22, new Cost("NETHERITE_INGOT", 6)),
            new MemberTier(23, new Cost("NETHERITE_INGOT", 7)),
            new MemberTier(24, new Cost("NETHERITE_INGOT", 8)),
            new MemberTier(25, new Cost("NETHERITE_INGOT", 10))
    );

    /** One rung of the roster ladder. */
    record MemberTier(int slots, Cost cost) {
    }

    /**
     * One star the whole way up, recoloured rather than repeated: a growing row of
     * stars sits in front of every chat line and clutters chat and the player list.
     * The secret level takes a different glyph because two purples are not far enough
     * apart to read as different on their own.
     */
    private static final Map<Integer, String> BADGES = Map.of(
            0, "",
            1, "★",
            2, "★",
            3, "★",
            4, "★",
            5, "★",
            6, "✦"
    );

    private static final Map<Integer, Integer> BADGE_COLORS = Map.of(
            0, 0xFFFFFF,
            1, 0xAAAAAA,
            2, 0x55FF55,
            3, 0x55FFFF,
            4, 0xFFAA00,
            5, 0xFF55FF,
            6, 0xAA00AA
    );

    private ClanLevel() {
    }

    static Perks perksFor(int level) {
        return PERKS.getOrDefault(clamp(level), Perks.NONE);
    }

    /** The price of reaching {@code level}, or empty when there is nothing to buy. */
    static List<Cost> costOf(int level) {
        return COSTS.getOrDefault(level, List.of());
    }

    static String badge(int level) {
        return BADGES.getOrDefault(clamp(level), "");
    }

    static int badgeColor(int level) {
        return BADGE_COLORS.getOrDefault(clamp(level), 0xFFFFFF);
    }

    /** Whether a level exists at all. */
    static boolean isValid(int level) {
        return level >= 0 && level <= SECRET_LEVEL;
    }

    /** Whether a level must stay hidden from a clan standing below it. */
    static boolean isSecret(int level) {
        return level > MAX_PUBLIC_LEVEL;
    }

    /**
     * Whether {@code current} may see {@code level} named. A clan learns the secret
     * level only once it has nothing else left to buy.
     */
    static boolean canSee(int current, int level) {
        return !isSecret(level) || current >= MAX_PUBLIC_LEVEL;
    }

    /** The vault takes anything worth something, which is what gives it a balance. */
    static boolean isDonatable(String material) {
        return WealthValues.isValuable(material);
    }

    static String normalizeMaterial(String material) {
        return WealthValues.normalize(material);
    }

    static String readableMaterial(String material) {
        return WealthValues.readable(material);
    }

    /** Slots a clan holds after buying {@code bought} rungs of the roster ladder. */
    static int slotsAfter(int bought) {
        if (bought <= 0) {
            return STARTING_MEMBER_SLOTS;
        }
        int capped = Math.min(bought, MEMBER_TIERS.size());
        return MEMBER_TIERS.get(capped - 1).slots();
    }

    /** The rung {@code bought} rungs in, or empty at the top of the ladder. */
    static Optional<MemberTier> nextMemberTier(int bought) {
        return bought < 0 || bought >= MEMBER_TIERS.size()
                ? Optional.empty()
                : Optional.of(MEMBER_TIERS.get(bought));
    }

    static int maxMemberSlots() {
        return MEMBER_TIERS.get(MEMBER_TIERS.size() - 1).slots();
    }

    /** Whether a saved roster size is one this ladder can actually produce. */
    static boolean isValidSlotCount(int slots) {
        if (slots == STARTING_MEMBER_SLOTS) {
            return true;
        }
        return MEMBER_TIERS.stream().anyMatch(tier -> tier.slots() == slots);
    }

    /**
     * The smallest roster size on this ladder that still holds {@code members}.
     *
     * <p>Used to migrate clans built under the old flat cap: dropping them to the
     * starting size would strand members who are already in them.
     */
    static int smallestSlotCountHolding(int members) {
        if (members <= STARTING_MEMBER_SLOTS) {
            return STARTING_MEMBER_SLOTS;
        }
        for (MemberTier tier : MEMBER_TIERS) {
            if (tier.slots() >= members) {
                return tier.slots();
            }
        }
        return maxMemberSlots();
    }

    /** How many rungs a clan has bought, read back from its saved roster size. */
    static int tiersBoughtFor(int slots) {
        for (int index = MEMBER_TIERS.size() - 1; index >= 0; index--) {
            if (MEMBER_TIERS.get(index).slots() == slots) {
                return index + 1;
            }
        }
        return 0;
    }

    /**
     * What {@code vault} still lacks to buy {@code level}, in cost order. Empty means
     * the upgrade is affordable.
     */
    static Map<String, Integer> shortfall(Map<String, Integer> vault, int level) {
        return shortfall(vault, costOf(level));
    }

    /** What {@code vault} still lacks to cover {@code costs}, in cost order. */
    static Map<String, Integer> shortfall(Map<String, Integer> vault, List<Cost> costs) {
        LinkedHashMap<String, Integer> missing = new LinkedHashMap<>();
        for (Cost cost : costs) {
            int held = vault == null ? 0 : vault.getOrDefault(cost.material(), 0);
            if (held < cost.amount()) {
                missing.put(cost.material(), cost.amount() - held);
            }
        }
        return missing;
    }

    private static int clamp(int level) {
        return Math.max(0, Math.min(level, SECRET_LEVEL));
    }
}
