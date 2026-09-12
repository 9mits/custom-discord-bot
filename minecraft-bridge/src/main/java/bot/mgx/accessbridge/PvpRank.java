package bot.mgx.accessbridge;

import java.util.Locale;

/**
 * The duelling ladder: eight tiers, most of them split into three divisions.
 *
 * <p>Free of Bukkit so the arithmetic can be tested, which is the whole reason it is
 * a class rather than a few expressions inlined into the duel service. Everything
 * here is an edge case waiting to happen — a rating that goes negative, a promotion
 * that skips a division, a demotion that undoes an achievement somebody earned.
 *
 * <p>Rating is Elo, so what a win is worth depends on who it was against. A flat
 * "+25 a win" ladder is a ladder you climb by duelling the worst player online until
 * you reach the top, which makes the number at the top mean nothing.
 */
enum PvpRank {
    BRONZE_I("Bronze", "I", 0d),
    BRONZE_II("Bronze", "II", 1d),
    BRONZE_III("Bronze", "III", 2d),
    SILVER_I("Silver", "I", 3d),
    SILVER_II("Silver", "II", 4d),
    SILVER_III("Silver", "III", 5d),
    GOLD_I("Gold", "I", 6d),
    GOLD_II("Gold", "II", 7d),
    GOLD_III("Gold", "III", 8d),
    PLATINUM_I("Platinum", "I", 9d),
    PLATINUM_II("Platinum", "II", 10d),
    PLATINUM_III("Platinum", "III", 11d),
    DIAMOND_I("Diamond", "I", 12d),
    DIAMOND_II("Diamond", "II", 13d),
    DIAMOND_III("Diamond", "III", 14d),
    ELITE("Elite", "", 15d),
    CHAMPION("Champion", "", 16.5d),
    UNREAL("Unreal", "", 18d);

    /**
     * How hard one result can move a rating.
     *
     * <p>The default win factor pays 28 for an even win while the lower loss factor
     * costs 16 for an even loss. With 75-point divisions, an active player sees
     * progress quickly without making the upper ladder automatic.
     */
    static final int K_FACTOR = 56;
    static final int LOSS_K_FACTOR = 32;
    static final int DIVISION_SIZE = 75;

    private static volatile java.util.function.ToDoubleFunction<String> tuning = key -> Double.NaN;

    private final String tier;
    private final String division;
    private final double floorSteps;

    PvpRank(String tier, String division, double floorSteps) {
        this.tier = tier;
        this.division = division;
        this.floorSteps = floorSteps;
    }

    String tier() {
        return tier;
    }

    long floor() {
        return Math.round(floorSteps * tuned("pvp-ranked.division-size", DIVISION_SIZE));
    }

    /** {@code Gold II}, or just {@code Unreal} where a tier has no divisions. */
    String display() {
        return division.isEmpty() ? tier : tier + " " + division;
    }

    /** The custom badge for the whole tier: a division is not a different metal. */
    String glyph() {
        return BadgeIcons.pvp(this);
    }

    static PvpRank of(long rating) {
        PvpRank found = BRONZE_I;
        for (PvpRank rank : values()) {
            if (rating >= rank.floor()) {
                found = rank;
            }
        }
        return found;
    }

    /**
     * The lowest rating this rank's tier allows.
     *
     * <p>Demotion stops at a tier boundary: reaching Gold is an achievement, and a
     * bad evening should cost divisions rather than take it away. Losing at the very
     * bottom of a tier still stings — it just cannot undo the promotion.
     */
    long tierFloor() {
        PvpRank lowest = this;
        for (PvpRank rank : values()) {
            if (rank.tier.equals(tier) && rank.floor() < lowest.floor()) {
                lowest = rank;
            }
        }
        return lowest.floor();
    }

    /** The rating at which the next division begins, or this one's floor at the top. */
    long nextFloor() {
        return this == UNREAL ? floor() : values()[ordinal() + 1].floor();
    }

    /** How far through the current division a rating sits, 0 to 1. */
    double progress(long rating) {
        if (this == UNREAL) {
            return 1d;
        }
        long floor = floor();
        long span = nextFloor() - floor;
        return span <= 0L ? 1d
                : Math.max(0d, Math.min(1d, (double) (rating - floor) / span));
    }

    /**
     * The rating change for one result, from the loser's point of view as well.
     *
     * @param score 1 for a win, 0 for a loss, 0.5 for a draw
     */
    static int change(long rating, long opponentRating, double score) {
        double expected = 1d / (1d + Math.pow(10d, (opponentRating - rating) / 400d));
        int factor = score < 0.5d
                ? tuned("pvp-ranked.loss-k-factor", LOSS_K_FACTOR)
                : tuned("pvp-ranked.win-k-factor", K_FACTOR);
        long change = Math.round(factor * (score - expected));
        // A win always pays and a loss always costs, however lopsided the pairing —
        // otherwise beating somebody far below you is free, and so is losing to
        // somebody far above.
        if (score > 0.5d) {
            return (int) Math.max(1L, change);
        }
        if (score < 0.5d) {
            return (int) Math.min(-1L, change);
        }
        return (int) change;
    }

    /**
     * Applies a change, holding the floor of the best tier ever reached.
     *
     * <p>{@code bestReached} is the highest rank the player has held, not their
     * current one, so the protection survives the demotion that would otherwise
     * remove it.
     */
    static long apply(long rating, int change, PvpRank bestReached) {
        return Math.max(bestReached.tierFloor(), Math.max(0L, rating + change));
    }

    static PvpRank higher(PvpRank first, PvpRank second) {
        return first.ordinal() >= second.ordinal() ? first : second;
    }

    String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    static void tuningSource(java.util.function.ToDoubleFunction<String> source) {
        tuning = source == null ? key -> Double.NaN : source;
    }

    private static int tuned(String key, int fallback) {
        double value = tuning.applyAsDouble(key);
        return Double.isFinite(value) ? Math.max(1, (int) Math.round(value)) : fallback;
    }
}
