package bot.mgx.accessbridge;

/**
 * Keeps a crate's realised rare rate honest against its advertised one.
 *
 * <p>Free of Bukkit so the whole correction can be unit tested. The rule is the ratio
 * between the rares a window <em>should</em> have paid and the rares it actually paid, so
 * it always pushes back towards the published odds and never towards a number nobody can
 * look up.
 *
 * <p>"Should have paid" is the important half. A roll made under a Crate Luck potion, a
 * 2x Crate Luck event, or a correction the balancer itself applied a moment ago is not a
 * roll on the published table, and counting its wins as evidence the table is too generous
 * is how a balancer ends up quietly cancelling the very event that was advertised. So every
 * open contributes the rare rate <em>the table it actually rolled on</em> carried, and the
 * comparison is against that sum rather than against {@code opens * targetRate}. With no
 * luck anywhere the two are identical; with luck in play only genuine drift survives.
 *
 * <p>Four things stop it running away:
 *
 * <ul>
 *   <li>nothing happens below {@link #MINIMUM_SAMPLE} opens, because a window that thin
 *       cannot separate drift from variance whatever the arithmetic says;
 *   <li>a deviation inside {@link #TOLERANCE_SIGMA} standard deviations of what was
 *       expected is left alone. A fixed percentage cannot do this job: 15% of target is
 *       half a standard deviation at four thousand opens and two at two hundred and fifty,
 *       so one constant is either deaf or twitchy depending only on how busy the server is;
 *   <li>the correction is clamped to {@link #FLOOR_PERCENT}..{@link #CEILING_PERCENT}, so
 *       a freak streak can never halve a rare into nothing or hand out a jackpot table;
 *   <li>no single account may contribute more than a fifth of a window, enforced by
 *       {@link CrateOddsStore}, so one player's streak cannot set everybody's odds.
 * </ul>
 */
final class CrateOddsBalance {
    /** No correction. Matches CrateCatalog's "no potion at all" baseline. */
    static final int NEUTRAL_PERCENT = 100;
    /** The hardest nerf and the biggest buff the balancer may ever apply. */
    static final int FLOOR_PERCENT = 50;
    static final int CEILING_PERCENT = 200;
    /** Opens needed before a window is worth reading at all. */
    static final int MINIMUM_SAMPLE = 500;
    /**
     * How far the count may sit from expectation before anything moves, in standard
     * deviations. Two is a shade under a 5% false-positive rate at every sample size,
     * which is the point of measuring it in sigma rather than in percent.
     */
    static final double TOLERANCE_SIGMA = 2.0d;
    /** Counters halve here, so the table follows recent play rather than all of history. */
    static final long WINDOW_OPENS = 4_000L;

    private CrateOddsBalance() {
    }

    /**
     * The rare rate a roll actually carries once a weight multiplier is applied.
     *
     * <p>{@code CrateCatalog.effectiveWeight} scales rare weights and leaves the commons
     * alone, so the resulting rate is sub-linear in the multiplier: doubling the rare
     * weights does not double the rare rate, because the pool got bigger too.
     *
     * @param targetRate the published rare rate, 0..1
     * @param rollPercent the weight multiplier the roll was made under, 100 being none
     */
    static double expectedRareRate(double targetRate, int rollPercent) {
        if (targetRate <= 0d || targetRate >= 1d || rollPercent <= 0) {
            return 0d;
        }
        double scaled = targetRate * rollPercent / 100d;
        return scaled / (scaled + (1d - targetRate));
    }

    /**
     * The weight multiplier that produces a wanted rare rate. The inverse of
     * {@link #expectedRareRate}, so a correction lands where it was aimed in one step
     * instead of converging on it over several windows.
     */
    static int weightPercentForRate(double targetRate, double desiredRate) {
        if (targetRate <= 0d || targetRate >= 1d || desiredRate <= 0d) {
            return FLOOR_PERCENT;
        }
        if (desiredRate >= 1d) {
            return CEILING_PERCENT;
        }
        double percent = 100d * (desiredRate * (1d - targetRate))
                / (targetRate * (1d - desiredRate));
        return (int) Math.max(FLOOR_PERCENT, Math.min(CEILING_PERCENT, Math.round(percent)));
    }

    /**
     * The rare-weight multiplier to apply right now.
     *
     * @param opens        opens counted in the current window
     * @param rareHits     rare rewards handed out in that window
     * @param expectedHits rares those same opens were expected to pay, summed per open
     *                     from the table each one actually rolled on
     * @param targetRate   the rate the published table advertises, 0..1
     */
    static int percent(long opens, long rareHits, double expectedHits, double targetRate) {
        if (opens < MINIMUM_SAMPLE || expectedHits <= 0d || targetRate <= 0d) {
            return NEUTRAL_PERCENT;
        }
        double deviation = rareHits - expectedHits;
        // sqrt(expected) slightly overstates the binomial deviation, which widens the
        // deadband rather than narrowing it. Erring towards leaving the table alone.
        if (Math.abs(deviation) <= TOLERANCE_SIGMA * Math.sqrt(expectedHits)) {
            return NEUTRAL_PERCENT;
        }
        if (rareHits <= 0L) {
            // Nobody has hit anything rare in a full window: buff as hard as allowed.
            return CEILING_PERCENT;
        }
        return weightPercentForRate(targetRate, targetRate * (expectedHits / rareHits));
    }

    /** True once the window is full and every counter should be halved. */
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
