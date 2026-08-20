package bot.mgx.accessbridge;

/**
 * The arithmetic behind the hourly-key bar, kept away from Bukkit so it can be tested.
 *
 * <p>The store only learns about elapsed online time once a minute, so every reading
 * here takes the seconds since that pulse as a second argument. Without it the bar
 * would hold still and then jump a minute at a time.
 */
final class KeyTimer {
    private KeyTimer() {
    }

    /** Never negative: past the boundary the key is owed, not overdue by some amount. */
    static long remaining(long untilNextFromStore, long uncredited) {
        return Math.max(0L, untilNextFromStore - Math.max(0L, uncredited));
    }

    /** 0 at a fresh hour, 1 when the key is due. Clamped, since the store can lag. */
    static float progress(long remaining, long hourMillis) {
        if (hourMillis <= 0L) {
            return 1f;
        }
        return Math.min(1f, Math.max(0f, 1f - (float) remaining / hourMillis));
    }

    /** "42m 7s", "9s", or empty when the key is already owed. */
    static String label(long remaining) {
        if (remaining <= 0L) {
            return "";
        }
        long seconds = (remaining + 999L) / 1_000L;
        long minutes = seconds / 60L;
        return minutes > 0L ? minutes + "m " + (seconds % 60L) + "s" : (seconds % 60L) + "s";
    }
}
