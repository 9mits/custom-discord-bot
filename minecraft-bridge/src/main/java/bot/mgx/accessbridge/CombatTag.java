package bot.mgx.accessbridge;

/**
 * How long a fight keeps hold of a player.
 *
 * <p>Free of Bukkit so the window and its wording can be unit tested. This exists because
 * AFK grants damage immunity: without it, {@code /afk} is a panic button that makes a
 * losing fight unloseable, and the idle sweep could mark somebody AFK mid-fight and hand
 * them the same immunity by accident.
 */
final class CombatTag {
    /** Long enough to cover a fight, short enough not to strand somebody who walked away. */
    static final long DEFAULT_SECONDS = 15L;

    private CombatTag() {
    }

    static boolean inCombat(long lastCombatMillis, long nowMillis, long windowMillis) {
        return lastCombatMillis > 0L && nowMillis - lastCombatMillis < windowMillis;
    }

    /** Seconds left on the tag, rounded up so it never reads as "0s". */
    static long remainingSeconds(long lastCombatMillis, long nowMillis, long windowMillis) {
        long remaining = windowMillis - (nowMillis - lastCombatMillis);
        return remaining <= 0L ? 0L : (remaining + 999L) / 1000L;
    }

    static String describe(long seconds) {
        return seconds + (seconds == 1L ? " second" : " seconds");
    }
}
