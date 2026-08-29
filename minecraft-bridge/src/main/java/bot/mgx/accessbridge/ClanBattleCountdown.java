package bot.mgx.accessbridge;

import java.util.Locale;

/**
 * Clan battle deadlines are shown in two lengths: a ticking {@code 2d 04h 11m 09s}
 * for the hologram and menus, and a rounded "3 days" for chat announcements that
 * would otherwise scroll a second-by-second figure past the reader.
 */
final class ClanBattleCountdown {
    private ClanBattleCountdown() {
    }

    /** The ticking form, matching the limited-crate hologram beside it. */
    static String clock(long millis) {
        if (millis <= 0L) {
            return "ENDED";
        }
        long totalSeconds = millis / 1_000L;
        long days = totalSeconds / 86_400L;
        long hours = (totalSeconds % 86_400L) / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (days > 0L) {
            return String.format(Locale.ROOT, "%dd %02dh %02dm %02ds", days, hours, minutes, seconds);
        }
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, seconds);
        }
        if (minutes > 0L) {
            return String.format(Locale.ROOT, "%dm %02ds", minutes, seconds);
        }
        return seconds + "s";
    }

    /** The rounded form for chat, so a broadcast reads as a sentence. */
    static String remaining(long millis) {
        if (millis <= 0L) {
            return "moments";
        }
        long totalSeconds = millis / 1_000L;
        long days = totalSeconds / 86_400L;
        long hours = (totalSeconds % 86_400L) / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        if (days > 0L) {
            return plural(days, "day") + (hours > 0L ? " " + plural(hours, "hour") : "");
        }
        if (hours > 0L) {
            return plural(hours, "hour") + (minutes > 0L ? " " + plural(minutes, "minute") : "");
        }
        if (minutes > 0L) {
            return plural(minutes, "minute");
        }
        return plural(Math.max(1L, totalSeconds), "second");
    }

    private static String plural(long count, String noun) {
        return count + " " + noun + (count == 1L ? "" : "s");
    }
}
