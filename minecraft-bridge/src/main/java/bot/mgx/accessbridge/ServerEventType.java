package bot.mgx.accessbridge;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The server-wide multiplier events.
 *
 * <p>Free of Bukkit so the names, aliases and duration rail can be unit tested.
 * The multiplier is deliberately a constant rather than a per-activation number:
 * every one of these is advertised to players as "2x", on the boss bar and in
 * the server list, and an event called 2x that quietly paid 3x would be worse
 * than one that did not exist.
 */
enum ServerEventType {
    CRATE_LUCK("crateluck", "2x Crate Luck", "2X CRATE LUCK EVENT!", "luck", "crate"),
    FORTUNE("fortune", "2x Fortune", "2X FORTUNE EVENT!", "ore", "mining"),
    KEY("key", "2x Keys", "2X KEY EVENT!", "keys"),
    MONEY("money", "2x Money", "2X MONEY EVENT!", "cash", "coins");

    /** What every one of these multiplies by. See the class note. */
    static final int MULTIPLIER = 2;

    /** An hour is the shortest worth announcing; a fortnight is the longest worth forgetting. */
    static final long MINIMUM_SECONDS = 60L;
    static final long MAXIMUM_SECONDS = 1_209_600L;

    private final String id;
    private final String displayName;
    private final String motdLabel;
    private final Set<String> aliases;

    ServerEventType(String id, String displayName, String motdLabel, String... aliases) {
        this.id = id;
        this.displayName = displayName;
        this.motdLabel = motdLabel;
        this.aliases = Set.copyOf(new LinkedHashSet<>(Arrays.asList(aliases)));
    }

    String id() {
        return id;
    }

    /** For chat, the boss bar and the join banner. */
    String displayName() {
        return displayName;
    }

    /** For the second line of the server list entry. */
    String motdLabel() {
        return motdLabel;
    }

    Set<String> aliases() {
        return aliases;
    }

    static Optional<ServerEventType> resolve(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String needle = token.toLowerCase(Locale.ROOT).trim();
        return Arrays.stream(values())
                .filter(type -> type.id.equals(needle) || type.aliases.contains(needle))
                .findFirst();
    }

    /**
     * Reads a requested duration.
     *
     * @return seconds, or 0 for "until somebody turns it off"
     * @throws IllegalArgumentException if the operator asked for something outside the rail
     */
    static long secondsOrThrow(String requested) {
        if (requested == null || requested.isBlank()) {
            return 0L;
        }
        long value;
        try {
            value = Long.parseLong(requested.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Duration must be a whole number of seconds.");
        }
        if (value < MINIMUM_SECONDS || value > MAXIMUM_SECONDS) {
            throw new IllegalArgumentException(
                    "Duration must be between " + MINIMUM_SECONDS + " and "
                            + MAXIMUM_SECONDS + " seconds, or omitted to run until turned off."
            );
        }
        return value;
    }
}
