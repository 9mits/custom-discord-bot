package bot.mgx.accessbridge;

import java.util.UUID;

/**
 * One player's leaderboard figures.
 *
 * <p>Combat and movement come from vanilla statistics, wealth is the player's
 * wallet, and the two Amethyst Event figures come from the plugin's progress store.
 */
record PlayerStats(
        UUID minecraftUuid,
        String username,
        long kills,
        long deaths,
        long playTimeTicks,
        long blocksMined,
        long walkedCm,
        long wealth,
        long amethystCratesOpened,
        long amethystAirdropsOpened
) {
    PlayerStats(
            UUID minecraftUuid,
            String username,
            long kills,
            long deaths,
            long playTimeTicks,
            long blocksMined,
            long walkedCm,
            long wealth
    ) {
        this(
                minecraftUuid, username, kills, deaths, playTimeTicks,
                blocksMined, walkedCm, wealth, 0L, 0L
        );
    }

    static PlayerStats empty(UUID minecraftUuid, String username) {
        return new PlayerStats(minecraftUuid, username, 0, 0, 0, 0, 0, 0, 0, 0);
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
                Math.max(0, updatedWealth),
                amethystCratesOpened,
                amethystAirdropsOpened
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
                wealth,
                amethystCratesOpened,
                amethystAirdropsOpened
        );
    }

    PlayerStats withAmethystProgress(AmethystProgressStore.Counts progress) {
        return new PlayerStats(
                minecraftUuid,
                username,
                kills,
                deaths,
                playTimeTicks,
                blocksMined,
                walkedCm,
                wealth,
                progress.cratesOpened(),
                progress.airdropsOpened()
        );
    }

    long value(LeaderboardType type) {
        return switch (type) {
            case KILLS -> kills;
            case WEALTH -> wealth;
            case PLAYTIME -> playTimeTicks;
            case BLOCKS_MINED -> blocksMined;
            case BLOCKS_WALKED -> walkedCm;
            case AMETHYST_CRATES -> amethystCratesOpened;
            case AMETHYST_AIRDROPS -> amethystAirdropsOpened;
        };
    }
}
