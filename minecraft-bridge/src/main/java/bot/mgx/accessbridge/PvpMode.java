package bot.mgx.accessbridge;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Competitive queues offered by the PvP lobby. */
enum PvpMode {
    CASUAL_DUEL("casual", "Casual 1v1", 1, 2, false, false, false,
            List.of("casual", "casual-1v1", "1v1-casual")),
    RANKED_DUEL("ranked", "Ranked 1v1", 1, 2, true, false, false,
            List.of("ranked", "ranked-1v1", "1v1", "1v1-ranked")),
    DOUBLES("2v2", "Ranked 2v2", 2, 4, true, false, false,
            List.of("2v2", "doubles", "duos")),
    TRIPLES("3v3", "Ranked 3v3", 3, 6, true, false, false,
            List.of("3v3", "triples", "trios")),
    CLAN_BATTLE("clan", "Clan vs Clan", 3, 6, false, true, false,
            List.of("clan", "clans", "clan-v-clan", "clan-vs-clan")),
    FFA("ffa", "Last Player Standing", 1, 12, false, false, true,
            List.of("ffa", "last-player-standing", "lps")),
    PRIVATE_DUEL("private", "Private Duel", 1, 2, false, false, false,
            List.of("private", "challenge"));

    private final String key;
    private final String display;
    private final int teamSize;
    private final int maximumPlayers;
    private final boolean rated;
    private final boolean clan;
    private final boolean freeForAll;
    private final List<String> aliases;

    PvpMode(
            String key,
            String display,
            int teamSize,
            int maximumPlayers,
            boolean rated,
            boolean clan,
            boolean freeForAll,
            List<String> aliases
    ) {
        this.key = key;
        this.display = display;
        this.teamSize = teamSize;
        this.maximumPlayers = maximumPlayers;
        this.rated = rated;
        this.clan = clan;
        this.freeForAll = freeForAll;
        this.aliases = List.copyOf(aliases);
    }

    String key() {
        return key;
    }

    String display() {
        return display;
    }

    int teamSize() {
        return teamSize;
    }

    int maximumPlayers() {
        return maximumPlayers;
    }

    int minimumPlayers(int configuredFfaMinimum) {
        return freeForAll ? Math.max(2, Math.min(maximumPlayers, configuredFfaMinimum))
                : maximumPlayers;
    }

    boolean rated() {
        return rated;
    }

    boolean clan() {
        return clan;
    }

    boolean freeForAll() {
        return freeForAll;
    }

    boolean queueable() {
        return this != PRIVATE_DUEL;
    }

    static Optional<PvpMode> from(String typed) {
        if (typed == null) return Optional.empty();
        String wanted = typed.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        for (PvpMode mode : values()) {
            if (mode.aliases.contains(wanted) || mode.key.equals(wanted)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
