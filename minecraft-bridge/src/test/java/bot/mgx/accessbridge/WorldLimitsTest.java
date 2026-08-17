package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldLimitsTest {
    @Test
    void overworldDiameterIsFiftyThousandEitherSideOfSpawn() {
        assertEquals(100_000, WorldLimits.diameter(false, 50_000));
    }

    @Test
    void netherDiameterFollowsTheEightToOnePortalScale() {
        assertEquals(12_500, WorldLimits.diameter(true, 50_000));
    }

    @Test
    void aMissingConfigRadiusUsesTheDefault() {
        assertEquals(100_000, WorldLimits.diameter(false, 0));
        assertEquals(12_500, WorldLimits.diameter(true, -1));
    }

    @Test
    void spawnTicketsCoverTheLockedSpawnBlock() {
        assertEquals(0, WorldLimits.spawnChunkX());
        assertEquals(0, WorldLimits.spawnChunkZ());
    }
}
