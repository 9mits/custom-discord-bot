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
 * The multiplier is fixed per event rather than chosen per activation: every one of
 * these is advertised to players by its factor, on the boss bar and in the server list,
 * and an event called 2x that quietly paid 3x would be worse than one that did not
 * exist. A test holds the name and the factor together.
 */
enum ServerEventType {
    CRATE_LUCK("crateluck", 2, "2x Crate Luck", "2X CRATE LUCK EVENT!", "luck", "crate"),
    FORTUNE("fortune", 2, "2x Fortune", "2X FORTUNE EVENT!", "ore", "mining"),
    KEY("key", 2, "2x Keys", "2X KEY EVENT!", "keys"),
    MONEY("money", 2, "2x Money", "2X MONEY EVENT!", "cash", "coins"),
    /** Halves the wait between Amethyst Airdrops rather than doubling their loot. */
    AIRDROP("airdrop", 2, "2x Airdrops", "2X AIRDROP EVENT!", "drops", "drop"),
    /** Halves the wait between Huge Amethyst Blocks. */
    AMETHYST_BLOCK("amethystblock", 2, "2x Amethyst Blocks", "2X AMETHYST BLOCK EVENT!",
            "block", "amethyst"),
    /** The big one. Stacks with nothing: the largest key factor in play wins. */
    MEGA_KEY("megakey", 4, "4x Keys", "4X KEY EVENT!", "megakeys", "bigkey");

    /** The factor an event carries when no per-type figure applies. */
    static final int MULTIPLIER = 2;

    /** A minute is the shortest worth announcing; a fortnight is the longest worth forgetting. */
    static final long MINIMUM_SECONDS = 60L;
    static final long MAXIMUM_SECONDS = 1_209_600L;

    private final String id;
    private final int multiplier;
    private final String displayName;
    private final String motdLabel;
    private final Set<String> aliases;

    ServerEventType(
            String id, int multiplier, String displayName, String motdLabel, String... aliases
    ) {
        this.id = id;
        this.multiplier = multiplier;
        this.displayName = displayName;
        this.motdLabel = motdLabel;
        this.aliases = Set.copyOf(new LinkedHashSet<>(Arrays.asList(aliases)));
    }

    String id() {
        return id;
    }

    /** The factor this event ships with, before an owner changes it. */
    int baseMultiplier() {
        return multiplier;
    }

    /** What this event multiplies by while it is running. */
    int multiplier() {
        return multiplier;
    }

    /**
     * The name with the factor stripped: "Keys", not "2x Keys".
     *
     * <p>The advertised name is built from whatever factor is actually in force, so the
     * two can no longer disagree. That was the reason the factor was fixed in the first
     * place — an event called 2x that quietly paid 3x would be worse than one that did
     * not exist — and deriving it keeps that guarantee while letting the number move.
     */
    String baseDisplayName() {
        return displayName.replaceFirst("^\\d+x\\s*", "");
    }

    /** For chat, the boss bar and the join banner, at the factor actually in force. */
    String displayName(int factor) {
        return factor + "x " + baseDisplayName();
    }

    /** For the second line of the server list entry, at the factor actually in force. */
    String motdLabel(int factor) {
        return factor + "X " + baseDisplayName().toUpperCase(Locale.ROOT) + " EVENT!";
    }

    /**
     * The key factor in play: the largest of the key events that is running, since 2x and
     * 4x keys must not compound into 8x behind a boss bar that promises 4x.
     */
    static int keyMultiplier(int keyFactor, int megaKeyFactor) {
        return Math.max(keyFactor, megaKeyFactor);
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
