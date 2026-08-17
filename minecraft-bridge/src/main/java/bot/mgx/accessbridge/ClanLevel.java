package bot.mgx.accessbridge;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The clan upgrade ladder: what each level costs in dollars, what it grants, and
 * how it is badged.
 *
 * <p>Deliberately free of Bukkit imports so the whole table stays unit-testable.
 * Perk figures are cumulative totals held at a level, not per-level increments.
 */
final class ClanLevel {
    static final int MAX_PUBLIC_LEVEL = 5;

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

    /** One upgrade price, in whole dollars. */
    record Cost(long dollars) {
        Cost {
            if (dollars <= 0L) {
                throw new IllegalArgumentException("A clan cost must be a positive amount.");
            }
        }
    }

    private static final Map<Integer, Perks> PERKS = Map.of(
            0, Perks.NONE,
            1, new Perks(0, 0.03, 0.03, 0.00, 0.00, 0.00),
            2, new Perks(0, 0.05, 0.05, 0.10, 0.00, 0.00),
            3, new Perks(1, 0.10, 0.10, 0.15, 0.05, 0.05),
            4, new Perks(2, 0.10, 0.15, 0.20, 0.10, 0.10),
            5, new Perks(3, 0.10, 0.15, 0.25, 0.15, 0.15)
    );

    private static final Map<Integer, Cost> COSTS = Map.of(
            1, new Cost(15_000L),
            2, new Cost(150_000L),
            3, new Cost(2_000_000L),
            4, new Cost(40_000_000L),
            5, new Cost(500_000_000L)
    );

    static final int STARTING_MEMBER_SLOTS = 3;

    /**
     * Roster upgrades as (slots, price), one member at a time from
     * {@link #STARTING_MEMBER_SLOTS} to 25. Each step is dearer than the last.
     * The first few slots stay cheap so a new clan can grow the day it is founded.
     */
    static final List<MemberTier> MEMBER_TIERS = List.of(
            new MemberTier(4, new Cost(500L)),
            new MemberTier(5, new Cost(1_000L)),
            new MemberTier(6, new Cost(2_000L)),
            new MemberTier(7, new Cost(4_000L)),
            new MemberTier(8, new Cost(8_000L)),
            new MemberTier(9, new Cost(15_000L)),
            new MemberTier(10, new Cost(25_000L)),
            new MemberTier(11, new Cost(40_000L)),
            new MemberTier(12, new Cost(65_000L)),
            new MemberTier(13, new Cost(100_000L)),
            new MemberTier(14, new Cost(150_000L)),
            new MemberTier(15, new Cost(250_000L)),
            new MemberTier(16, new Cost(400_000L)),
            new MemberTier(17, new Cost(650_000L)),
            new MemberTier(18, new Cost(1_000_000L)),
            new MemberTier(19, new Cost(1_500_000L)),
            new MemberTier(20, new Cost(2_500_000L)),
            new MemberTier(21, new Cost(4_000_000L)),
            new MemberTier(22, new Cost(6_500_000L)),
            new MemberTier(23, new Cost(10_000_000L)),
            new MemberTier(24, new Cost(20_000_000L)),
            new MemberTier(25, new Cost(40_000_000L))
    );

    record MemberTier(int slots, Cost cost) {
    }

    private static final Map<Integer, String> BADGES = Map.of(
            0, "",
            1, "★",
            2, "★",
            3, "★",
            4, "★",
            5, "★"
    );

    private static final Map<Integer, Integer> BADGE_COLORS = Map.of(
            0, 0xFFFFFF,
            1, 0xAAAAAA,
            2, 0x55FF55,
            3, 0x55FFFF,
            4, 0xFFAA00,
            5, 0xFF55FF
    );

    private ClanLevel() {
    }

    static Perks perksFor(int level) {
        return PERKS.getOrDefault(clamp(level), Perks.NONE);
    }

    static Optional<Cost> costOf(int level) {
        return Optional.ofNullable(COSTS.get(level));
    }

    static String badge(int level) {
        return BADGES.getOrDefault(clamp(level), "");
    }

    static int badgeColor(int level) {
        return BADGE_COLORS.getOrDefault(clamp(level), 0xFFFFFF);
    }

    static boolean isValid(int level) {
        return level >= 0 && level <= MAX_PUBLIC_LEVEL;
    }

    static int slotsAfter(int bought) {
        if (bought <= 0) {
            return STARTING_MEMBER_SLOTS;
        }
        int capped = Math.min(bought, MEMBER_TIERS.size());
        return MEMBER_TIERS.get(capped - 1).slots();
    }

    static Optional<MemberTier> nextMemberTier(int bought) {
        return bought < 0 || bought >= MEMBER_TIERS.size()
                ? Optional.empty()
                : Optional.of(MEMBER_TIERS.get(bought));
    }

    static int maxMemberSlots() {
        return MEMBER_TIERS.get(MEMBER_TIERS.size() - 1).slots();
    }

    static boolean isValidSlotCount(int slots) {
        if (slots == STARTING_MEMBER_SLOTS) {
            return true;
        }
        return MEMBER_TIERS.stream().anyMatch(tier -> tier.slots() == slots);
    }

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

    static int tiersBoughtFor(int slots) {
        for (int index = MEMBER_TIERS.size() - 1; index >= 0; index--) {
            if (MEMBER_TIERS.get(index).slots() == slots) {
                return index + 1;
            }
        }
        return 0;
    }

    /** Dollars still needed to cover {@code cost} from {@code treasury}. Zero if affordable. */
    static long shortfall(long treasury, Cost cost) {
        if (cost == null) {
            return 0L;
        }
        return Math.max(0L, cost.dollars() - Math.max(0L, treasury));
    }

    private static int clamp(int level) {
        return Math.max(0, Math.min(level, MAX_PUBLIC_LEVEL));
    }
}
