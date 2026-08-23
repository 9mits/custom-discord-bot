package bot.mgx.accessbridge;

import java.util.Locale;
import java.util.Optional;

/**
 * The operator's PvP pin as it is stored between restarts.
 *
 * <p>Free of Bukkit imports so the parsing and the countdown wording are unit tested.
 * Anything the file does not say plainly is no pin at all: a half-written or hand-edited
 * value must fall back to the launch hold rather than guess a combat state.
 */
final class PvpPin {
    static final String ON = "on";
    static final String OFF = "off";

    private PvpPin() {
    }

    static Optional<Boolean> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (value.equals(ON)) {
            return Optional.of(true);
        }
        if (value.equals(OFF)) {
            return Optional.of(false);
        }
        return Optional.empty();
    }

    static String format(boolean enabled) {
        return enabled ? ON : OFF;
    }

    /** What is left of the launch hold, rounded up so it never reads as "0m". */
    static String describe(long millis) {
        long minutes = Math.max(1L, (millis + 59_999L) / 60_000L);
        long hours = minutes / 60L;
        return hours > 0 ? hours + "h " + (minutes % 60L) + "m" : minutes + "m";
    }
}
