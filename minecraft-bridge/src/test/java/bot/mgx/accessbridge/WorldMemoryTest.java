package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldMemoryTest {
    @Test
    void defaultCapsAreBelowPaperStockDistances() {
        assertEquals(6, WorldMemory.MAX_VIEW_DISTANCE);
        assertEquals(4, WorldMemory.MAX_SIMULATION_DISTANCE);
    }

    @Test
    void aWorldAlreadyUnderTheCapIsLeftAlone() {
        assertEquals(4, WorldMemory.capDistance(4, 6));
        assertEquals(6, WorldMemory.capDistance(6, 6));
    }

    @Test
    void aHighDistanceIsCutToTheCap() {
        assertEquals(6, WorldMemory.capDistance(10, 6));
        assertEquals(4, WorldMemory.capDistance(12, 4));
    }

    @Test
    void zeroOrOutOfRangeCapsMeanDoNotChange() {
        assertEquals(10, WorldMemory.capDistance(10, 0));
        assertEquals(10, WorldMemory.capDistance(10, 1));
        assertEquals(10, WorldMemory.capDistance(10, 33));
    }

    @Test
    void simulationCannotExceedTheViewDistanceInUse() {
        assertEquals(4, WorldMemory.capSimulation(10, 4, 6));
        assertEquals(6, WorldMemory.capSimulation(10, 8, 6));
        assertEquals(4, WorldMemory.capSimulation(4, 8, 6));
    }

    @Test
    void aChunkLoadedOnlyForTheScanIsUnloadedAfterwards() {
        assertTrue(WorldMemory.shouldUnloadScannedChunk(false));
        assertFalse(WorldMemory.shouldUnloadScannedChunk(true));
    }
}
