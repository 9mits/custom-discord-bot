package bot.mgx.accessbridge;

import java.util.UUID;

/**
 * One player's leaderboard figures.
 *
 * <p>Everything except {@link #wealth} comes from the vanilla statistics file the
 * server already writes, so these numbers are complete for players who have not
 * logged in since the feature shipped.
 */
record PlayerStats(
        UUID minecraftUuid,
        String username,
        long kills,
        long deaths,
        long playTimeTicks,
        long blocksMined,
        long walkedCm,
        long wealth
) {
    static PlayerStats empty(UUID minecraftUuid, String username) {
        return new PlayerStats(minecraftUuid, username, 0, 0, 0, 0, 0, 0);
    }

    PlayerStats withWealth(long updatedWealth) {
        return new PlayerStats(
                minecraftUuid,
                username,
                kills,
                deaths,
                playTimeTicks,
                blocksMined,
                walkedCm,
                Math.max(0, updatedWealth)
        );
    }

    long value(LeaderboardType type) {
        return switch (type) {
            case KILLS -> kills;
            case WEALTH -> wealth;
            case PLAYTIME -> playTimeTicks;
            case BLOCKS_MINED -> blocksMined;
            case BLOCKS_WALKED -> walkedCm;
        };
    }
}
