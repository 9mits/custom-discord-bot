package bot.mgx.accessbridge;

import java.util.Locale;
import java.util.Optional;

/**
 * The leaderboard categories. {@link #clanEligible} marks the ones that make sense
 * summed across a clan; the rest are individual-only because a combined total would
 * reward clan size rather than achievement.
 */
enum LeaderboardType {
    WEALTH(true),
    KILLS(true),
    PLAYTIME(true),
    BLOCKS_MINED(false),
    BLOCKS_WALKED(false);

    private final boolean clanEligible;

    LeaderboardType(boolean clanEligible) {
        this.clanEligible = clanEligible;
    }

    boolean clanEligible() {
        return clanEligible;
    }

    /** The boards Discord and {@code /leaderboard} actually show. */
    boolean published() {
        return this == WEALTH || this == KILLS;
    }

    /** Renders a raw figure the way players read it, not the way the game stores it. */
    String describe(long value) {
        return switch (this) {
            case PLAYTIME -> String.format(Locale.ROOT, "%,dh %dm", value / 72_000, (value / 1_200) % 60);
            case BLOCKS_WALKED -> String.format(Locale.ROOT, "%,d blocks", value / 100);
            case WEALTH -> EconomyFormat.dollars(value);
            default -> String.format(Locale.ROOT, "%,d", value);
        };
    }

    String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    static Optional<LeaderboardType> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        for (LeaderboardType type : values()) {
            if (type.key().equalsIgnoreCase(key.trim())) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
