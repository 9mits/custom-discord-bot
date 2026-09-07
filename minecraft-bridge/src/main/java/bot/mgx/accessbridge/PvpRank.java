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
    BRONZE_I("Bronze", "I", 0),
    BRONZE_II("Bronze", "II", 100),
    BRONZE_III("Bronze", "III", 200),
    SILVER_I("Silver", "I", 300),
    SILVER_II("Silver", "II", 400),
    SILVER_III("Silver", "III", 500),
    GOLD_I("Gold", "I", 600),
    GOLD_II("Gold", "II", 700),
    GOLD_III("Gold", "III", 800),
    PLATINUM_I("Platinum", "I", 900),
    PLATINUM_II("Platinum", "II", 1_000),
    PLATINUM_III("Platinum", "III", 1_100),
    DIAMOND_I("Diamond", "I", 1_200),
    DIAMOND_II("Diamond", "II", 1_300),
    DIAMOND_III("Diamond", "III", 1_400),
    ELITE("Elite", "", 1_500),
    CHAMPION("Champion", "", 1_650),
    UNREAL("Unreal", "", 1_800);

    /**
     * How hard one result can move a rating.
     *
     * <p>40 puts a win over an equal at +20, so a division is about five of them and
     * the whole ladder is a season's work rather than an evening's.
     */
    static final int K_FACTOR = 40;

    private final String tier;
    private final String division;
    private final long floor;

    PvpRank(String tier, String division, long floor) {
        this.tier = tier;
        this.division = division;
        this.floor = floor;
    }

    String tier() {
        return tier;
    }

    long floor() {
        return floor;
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
            if (rating >= rank.floor) {
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
            if (rank.tier.equals(tier) && rank.floor < lowest.floor) {
                lowest = rank;
            }
        }
        return lowest.floor;
    }

    /** The rating at which the next division begins, or this one's floor at the top. */
    long nextFloor() {
        return this == UNREAL ? floor : values()[ordinal() + 1].floor;
    }

    /** How far through the current division a rating sits, 0 to 1. */
    double progress(long rating) {
        if (this == UNREAL) {
            return 1d;
        }
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
        long change = Math.round(K_FACTOR * (score - expected));
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
}
