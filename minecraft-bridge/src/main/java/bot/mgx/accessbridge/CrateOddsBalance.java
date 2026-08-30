package bot.mgx.accessbridge;

/**
 * Keeps a crate's realised rare rate honest against its advertised one.
 *
 * <p>Free of Bukkit so the whole correction can be unit tested. The rule is deliberately
 * one line of arithmetic rather than a tuning curve: the adjustment is the ratio between
 * the rate the table promises and the rate players are actually getting, so it always
 * pushes back towards the published odds and never towards a number nobody can look up.
 *
 * <p>It is server-wide on purpose. Per-player pity would give two people different odds on
 * the same crate and quietly punish a lucky run; one shared table keeps "0.4%" a statement
 * about the server rather than about whoever is holding the key.
 *
 * <p>Three things stop it running away:
 *
 * <ul>
 *   <li>nothing happens below {@link #MINIMUM_SAMPLE} opens, because a handful of lucky
 *       pulls would otherwise swing the table hard;
 *   <li>drift inside {@link #TOLERANCE} of target is left alone, so the odds do not twitch
 *       on ordinary variance;
 *   <li>the correction is clamped to {@link #FLOOR_PERCENT}..{@link #CEILING_PERCENT}, so
 *       a freak streak can never halve a rare into nothing or hand out a jackpot table.
 * </ul>
 */
final class CrateOddsBalance {
    /** No correction. Matches CrateCatalog's "no potion at all" baseline. */
    static final int NEUTRAL_PERCENT = 100;
    /** The hardest nerf and the biggest buff the balancer may ever apply. */
    static final int FLOOR_PERCENT = 50;
    static final int CEILING_PERCENT = 200;
    /** Opens needed before the realised rate is worth believing. */
    static final int MINIMUM_SAMPLE = 250;
    /** How far the realised rate may drift before anything moves. */
    static final double TOLERANCE = 0.15d;
    /** Counters halve here, so the table follows recent play rather than all of history. */
    static final long WINDOW_OPENS = 4_000L;

    private CrateOddsBalance() {
    }

    /**
     * The rare-weight multiplier to apply right now.
     *
     * @param opens     opens counted in the current window
     * @param rareHits  rare rewards handed out in that window
     * @param targetRate the rate the published table advertises, 0..1
     */
    static int percent(long opens, long rareHits, double targetRate) {
        if (opens < MINIMUM_SAMPLE || targetRate <= 0d) {
            return NEUTRAL_PERCENT;
        }
        double realised = (double) rareHits / (double) opens;
        if (Math.abs(realised - targetRate) <= targetRate * TOLERANCE) {
            return NEUTRAL_PERCENT;
        }
        if (realised <= 0d) {
            // Nobody has hit anything rare in a full sample: buff as hard as allowed.
            return CEILING_PERCENT;
        }
        long scaled = Math.round(NEUTRAL_PERCENT * (targetRate / realised));
        return (int) Math.max(FLOOR_PERCENT, Math.min(CEILING_PERCENT, scaled));
    }

    /** True once the window is full and both counters should be halved. */
    static boolean shouldDecay(long opens) {
        return opens >= WINDOW_OPENS;
    }

    /** Composes the balancer with a player's own luck, keeping each within its own band. */
    static int compose(int luckPercent, int balancePercent) {
        int luck = CrateCatalog.clampLuckPercent(luckPercent);
        int balance = Math.max(FLOOR_PERCENT, Math.min(CEILING_PERCENT, balancePercent));
        return (int) Math.max(1L, Math.round(luck * (balance / 100.0d)));
    }
}
