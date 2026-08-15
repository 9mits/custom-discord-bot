package bot.mgx.accessbridge;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What an administrative reset is allowed to erase.
 *
 * <p>Nothing here touches the world itself. Region files, entities and the seed are
 * never in scope, so everything anyone has built survives a reset of every scope at
 * once — which is the entire point: a season's progress can be wiped after testing
 * without losing the builds that testing produced.
 *
 * <p>Free of Bukkit imports so the parsing below can be unit tested.
 */
enum ResetScope {
    /** Vanilla statistics: deaths, kills, playtime, blocks mined, distance travelled. */
    STATS("stats", "statistics — deaths, kills, playtime, blocks mined, distance"),
    /** Vanilla advancements, which are what Minecraft shows as achievements. */
    ADVANCEMENTS("advancements", "advancements and the achievements screen"),
    /** Carried items, ender chests, experience, health and hunger. */
    INVENTORIES("inventories", "inventories, ender chests, experience, health and hunger"),
    /** Every clan, its vault balance and its donation ledger. */
    CLANS("clans", "every clan, clan balance and donation record"),
    /** Recorded wealth, which is the one leaderboard figure the server does not keep. */
    WEALTH("wealth", "recorded wealth for the leaderboard");

    private final String key;
    private final String description;

    ResetScope(String key, String description) {
        this.key = key;
        this.description = description;
    }

    String key() {
        return key;
    }

    String description() {
        return description;
    }

    /** Whether this scope needs the player's own data file removed when they are offline. */
    boolean isPlayerData() {
        return this == STATS || this == ADVANCEMENTS || this == INVENTORIES;
    }

    static List<String> keys() {
        return EnumSet.allOf(ResetScope.class).stream().map(ResetScope::key).toList();
    }

    static Optional<ResetScope> fromKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String cleaned = raw.strip().toLowerCase(Locale.ROOT);
        for (ResetScope scope : values()) {
            if (scope.key.equals(cleaned)) {
                return Optional.of(scope);
            }
        }
        return Optional.empty();
    }

    /**
     * Reads the scope arguments of a reset command.
     *
     * <p>{@code all} expands to every scope. An unrecognised word is rejected rather
     * than skipped: silently ignoring a typo on a destructive command would erase
     * something other than what was asked for.
     */
    static Set<ResetScope> parse(List<String> arguments) {
        EnumSet<ResetScope> scopes = EnumSet.noneOf(ResetScope.class);
        for (String argument : arguments) {
            if (argument == null || argument.isBlank()) {
                continue;
            }
            String cleaned = argument.strip().toLowerCase(Locale.ROOT);
            if (cleaned.equals("all") || cleaned.equals("everything")) {
                scopes.addAll(EnumSet.allOf(ResetScope.class));
                continue;
            }
            scopes.add(fromKey(cleaned).orElseThrow(() -> new IllegalArgumentException(
                    "Unknown reset scope '" + argument.strip() + "'. Choose from: "
                            + String.join(", ", keys()) + ", all."
            )));
        }
        return scopes;
    }
}
