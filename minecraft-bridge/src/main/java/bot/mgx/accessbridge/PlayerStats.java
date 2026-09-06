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
        long amethystAirdropsOpened,
        long dragonDamage,
        long dragonCrystals,
        long dragonCratesOpened,
        long duelKills
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
                blocksMined, walkedCm, wealth, 0L, 0L, 0L, 0L, 0L, 0L
        );
    }

    PlayerStats(
            UUID minecraftUuid, String username, long kills, long deaths,
            long playTimeTicks, long blocksMined, long walkedCm, long wealth,
            long amethystCratesOpened, long amethystAirdropsOpened
    ) {
        this(minecraftUuid, username, kills, deaths, playTimeTicks, blocksMined,
                walkedCm, wealth, amethystCratesOpened, amethystAirdropsOpened,
                0L, 0L, 0L, 0L);
    }

    static PlayerStats empty(UUID minecraftUuid, String username) {
        return new PlayerStats(minecraftUuid, username, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
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
                amethystAirdropsOpened,
                dragonDamage,
                dragonCrystals,
                dragonCratesOpened,
                duelKills
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
                amethystAirdropsOpened,
                dragonDamage,
                dragonCrystals,
                dragonCratesOpened,
                duelKills
        );
    }

    /** Duelling is recorded by the plugin, not by any vanilla statistic. */
    PlayerStats withDuelKills(long updatedDuelKills) {
        return new PlayerStats(
                minecraftUuid, username, kills, deaths, playTimeTicks, blocksMined,
                walkedCm, wealth, amethystCratesOpened, amethystAirdropsOpened,
                dragonDamage, dragonCrystals, dragonCratesOpened,
                Math.max(0, updatedDuelKills)
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
                progress.airdropsOpened(),
                progress.dragonDamage(),
                progress.dragonCrystals(),
                progress.dragonCratesOpened(),
                duelKills
        );
    }

    long value(LeaderboardType type) {
        return switch (type) {
            // Ranked on duels, not on whoever was caught out in the open.
            case KILLS -> duelKills;
            case WEALTH -> wealth;
            case PLAYTIME -> playTimeTicks;
            case BLOCKS_MINED -> blocksMined;
            case BLOCKS_WALKED -> walkedCm;
            case AMETHYST_CRATES -> amethystCratesOpened;
            case AMETHYST_AIRDROPS -> amethystAirdropsOpened;
            case DRAGON_DAMAGE -> dragonDamage;
            case DRAGON_CRYSTALS -> dragonCrystals;
            case DRAGON_CRATES -> dragonCratesOpened;
        };
    }
}
