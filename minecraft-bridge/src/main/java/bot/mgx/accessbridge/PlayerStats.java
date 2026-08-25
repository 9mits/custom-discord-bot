package bot.mgx.accessbridge;

import java.util.UUID;

/**
 * One player's leaderboard figures.
 *
 * <p>Everything except {@link #wealth} comes from the vanilla statistics file the
 * server already writes. Wealth is the player's wallet, not what they are carrying.
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

    PlayerStats withKills(long updatedKills) {
        return new PlayerStats(
                minecraftUuid,
                username,
                Math.max(0, updatedKills),
                deaths,
                playTimeTicks,
                blocksMined,
                walkedCm,
                wealth
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
