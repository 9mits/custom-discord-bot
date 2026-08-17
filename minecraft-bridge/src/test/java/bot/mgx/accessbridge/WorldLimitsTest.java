package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldLimitsTest {
    @Test
    void overworldDiameterIsOneHundredThousandEitherSideOfSpawn() {
        assertEquals(200_000, WorldLimits.diameter(false, 100_000));
    }

    @Test
    void netherDiameterFollowsTheEightToOnePortalScale() {
        assertEquals(25_000, WorldLimits.diameter(true, 100_000));
    }

    @Test
    void aMissingConfigRadiusUsesTheDefault() {
        assertEquals(200_000, WorldLimits.diameter(false, 0));
        assertEquals(25_000, WorldLimits.diameter(true, -1));
    }

    @Test
    void spawnTicketsCoverTheLockedSpawnBlock() {
        assertEquals(0, WorldLimits.spawnChunkX());
        assertEquals(0, WorldLimits.spawnChunkZ());
    }
}
