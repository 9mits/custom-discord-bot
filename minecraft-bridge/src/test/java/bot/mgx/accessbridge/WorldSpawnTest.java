package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSpawnTest {
    @Test
    void theOverworldSpawnIsTheOriginBlock() {
        assertEquals(0, WorldSpawn.X);
        assertEquals(69, WorldSpawn.Y);
        assertEquals(0, WorldSpawn.Z);
        assertEquals(0, WorldSpawn.RADIUS);
    }

    @Test
    void nearbyBlocksAreNotTheSpawn() {
        assertTrue(WorldSpawn.isExact(0, 69, 0));
        assertFalse(WorldSpawn.isExact(1, 69, 0));
        assertFalse(WorldSpawn.isExact(0, 70, 0));
        assertFalse(WorldSpawn.isExact(0, 69, 1));
    }
}
