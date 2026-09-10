package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SpawnProtectionTest {
    /** Inclusive bounds, so -50..49 is exactly 100 blocks across. */
    private static final SpawnMobBarrier REGION = new SpawnMobBarrier(-50, 49, -50, 49);

    @Test
    void theRegionIsExactlyOneHundredByOneHundred() {
        assertEquals(100, REGION.maxX() - REGION.minX() + 1);
        assertEquals(100, REGION.maxZ() - REGION.minZ() + 1);
        assertTrue(REGION.contains(0d, 0d), "the locked spawn must be inside it");
    }

    @Test
    void theEdgesAreCoveredAndTheOutsideIsNot() {
        assertTrue(REGION.contains(-50d, -50d));
        assertTrue(REGION.contains(49.9d, 49.9d));
        assertFalse(REGION.contains(-50.5d, 0d));
        assertFalse(REGION.contains(0d, 51d));
    }

    @Test
    void walkingInIsDetectedButWalkingAroundOutsideIsNot() {
        assertTrue(REGION.enters(-60d, 0d, -40d, 0d));
        assertFalse(REGION.enters(-40d, 0d, -30d, 0d), "already inside is not an entry");
        assertFalse(REGION.enters(-60d, 0d, -70d, 0d));
    }

    /**
     * Every rule the protected spawn is supposed to enforce. WorldGuard can deny spawning
     * but has no flag for stopping a mob that spawned outside from walking in, which is
     * why entry is enforced here.
     */
    @Test
    void theRegionEnforcesEveryProtection() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/SpawnMobBarrierService.java"
        ));

        assertTrue(source.contains("public void onCreatureSpawn"), "mobs must not spawn");
        assertTrue(source.contains("public void onZombieMove"), "mobs must not walk in");
        assertTrue(source.contains("public void onBlockBreak"), "blocks must not break");
        assertTrue(source.contains("public void onBlockPlace"), "blocks must not be placed");
        assertTrue(source.contains("public void onPvp"), "PvP must be refused");
        // Every hostile, not only zombies - the filter is what carries that, and it
        // must stay a Monster test rather than narrowing to a single type again.
        assertTrue(source.contains("Monster.class::isInstance"),
                "the sweep must cover every hostile, not only zombies");
        // ...and it must stay scoped to the region. getEntitiesByClass walks the whole
        // world, which made a 100x100 box cost a full entity scan every second.
        assertTrue(source.contains("world.getNearbyEntities(region"),
                "the sweep must query the region, not the whole world");
        assertFalse(source.contains("getEntitiesByClass(Monster.class)"),
                "a world-wide monster scan must not come back");
        assertTrue(source.contains("amethystMobs.isAmethystMob(entity)"),
                "an airdrop garrison must still be able to stand here");
    }
}
