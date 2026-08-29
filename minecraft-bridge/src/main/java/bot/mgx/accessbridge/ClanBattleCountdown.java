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

    /**
     * Parses {@code 7d}, {@code 12h}, {@code 90m} or a run of them such as
     * {@code 7d12h}. Used to time a clan battle that is not tied to a crate close.
     */
    static long parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Give a length such as 7d, 12h or 90m.");
        }
        String text = raw.strip().toLowerCase(Locale.ROOT);
        long total = 0L;
        long digits = -1L;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character >= '0' && character <= '9') {
                digits = (digits < 0 ? 0L : digits) * 10L + (character - '0');
                if (digits > 3_650L) {
                    throw new IllegalArgumentException("That clan battle length is too long.");
                }
                continue;
            }
            if (digits < 0) {
                throw new IllegalArgumentException("Give a length such as 7d, 12h or 90m.");
            }
            total = Math.addExact(total, digits * unitMillis(character));
            digits = -1L;
        }
        if (digits >= 0L) {
            throw new IllegalArgumentException("Add a unit: d, h or m — such as 7d.");
        }
        if (total <= 0L) {
            throw new IllegalArgumentException("A clan battle has to run for longer than that.");
        }
        return total;
    }

    private static long unitMillis(char unit) {
        return switch (unit) {
            case 'd' -> 86_400_000L;
            case 'h' -> 3_600_000L;
            case 'm' -> 60_000L;
            default -> throw new IllegalArgumentException(
                    "Use d, h or m for a clan battle length, such as 7d."
            );
        };
    }
}
