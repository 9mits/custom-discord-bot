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
    RANK(false),
    PLAYTIME(true),
    BLOCKS_MINED(false),
    BLOCKS_WALKED(false),
    AMETHYST_CRATES(false),
    AMETHYST_AIRDROPS(false),
    DRAGON_DAMAGE(false),
    DRAGON_CRYSTALS(false),
    DRAGON_CRATES(false);

    private final boolean clanEligible;

    LeaderboardType(boolean clanEligible) {
        this.clanEligible = clanEligible;
    }

    boolean clanEligible() {
        return clanEligible;
    }

    /** The boards Discord, the website and {@code /leaderboard} actually show. */
    boolean published() {
        return this == WEALTH
                || this == KILLS
                || this == RANK
                || this == DRAGON_DAMAGE
                || this == DRAGON_CRYSTALS;
    }

    /**
     * Whether reaching the top of this board is itself a prize.
     *
     * <p>A published board is one people can look at; a rewarded board is one that
     * mints a nameplate standing and the podium cosmetics. They are not the same set.
     * The PvP Rank ladder is deliberately published but not rewarded — its podium
     * already pays out the top-three Scythes, and adding the standard podium on top
     * would be paying twice for the same three places.
     */
    boolean rewarded() {
        return published() && this != RANK;
    }

    /** Renders a raw figure the way players read it, not the way the game stores it. */
    String describe(long value) {
        return switch (this) {
            case PLAYTIME -> String.format(Locale.ROOT, "%,dh %dm", value / 72_000, (value / 1_200) % 60);
            case BLOCKS_WALKED -> String.format(Locale.ROOT, "%,d blocks", value / 100);
            case WEALTH -> EconomyFormat.dollars(value);
            case RANK -> PvpRank.of(value).display()
                    + String.format(Locale.ROOT, "  (%,d RP)", value);
            default -> String.format(Locale.ROOT, "%,d", value);
        };
    }

    String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    String icon() {
        return switch (this) {
            case WEALTH -> "$";
            case KILLS -> "⚔";
            case RANK -> "★";
            case PLAYTIME -> "◷";
            case BLOCKS_MINED -> "⛏";
            case BLOCKS_WALKED -> "»";
            case AMETHYST_CRATES -> "◇";
            case AMETHYST_AIRDROPS -> "◆";
            case DRAGON_DAMAGE -> "♢";
            case DRAGON_CRYSTALS -> "✦";
            case DRAGON_CRATES -> "◇";
        };
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
