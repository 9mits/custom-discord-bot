package bot.mgx.accessbridge;

/**
 * How much of a stay-online reward time spent AFK is worth.
 *
 * <p>The ladder paid a player standing still in a corner exactly what it paid somebody
 * playing, so the best way to earn keys and diamonds was to leave the game running.
 * Active time still pays in full; AFK time pays a configurable share of the keys and,
 * unless the owner allows it, no item rolls at all for an hour spent mostly AFK.
 *
 * <p>Free of Bukkit so the arithmetic is unit tested.
 */
final class AfkRewardShare {
    private AfkRewardShare() {
    }

    /** The online time credited towards hourly keys for one pulse. */
    static long creditedMillis(long elapsedMillis, boolean afk, int afkPercent) {
        long safe = Math.max(0L, elapsedMillis);
        return afk ? safe * clampPercent(afkPercent) / 100L : safe;
    }

    /** The fraction of an interval spent AFK, 0 to 1. */
    static double share(long afkMillis, long intervalMillis) {
        if (intervalMillis <= 0L) return 0d;
        return Math.max(0d, Math.min(1d, (double) Math.max(0L, afkMillis) / intervalMillis));
    }

    /** Bonus keys after AFK time is paid at its reduced share. */
    static int keys(int keys, double afkShare, int afkPercent) {
        if (keys <= 0) return 0;
        double kept = 1d - afkShare * (1d - clampPercent(afkPercent) / 100d);
        return (int) Math.round(keys * kept);
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }
}
