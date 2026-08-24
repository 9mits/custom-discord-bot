package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnMobBarrierTest {
    private final SpawnMobBarrier barrier = new SpawnMobBarrier(-30, 30, -32, 30);

    @Test
    void usesTheWholeInclusiveSpawnRegion() {
        assertTrue(barrier.contains(-30, -32));
        assertTrue(barrier.contains(30.99, 30.99));
        assertFalse(barrier.contains(31.01, 0));
    }

    @Test
    void detectsAPathCrossingIntoSpawn() {
        assertTrue(barrier.enters(31.1, 0, 30.9, 0));
        assertFalse(barrier.enters(10, 10, 11, 11));
        assertFalse(barrier.enters(35, 35, 34, 34));
    }
}
