package bot.mgx.accessbridge;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class AmethystDragonServiceTest {
    @Test
    void dragonArenaRescuesPlayersBeforeVanillaVoidDamage() {
        assertEquals(true, AmethystDragonService.belowVoidRescueHeight(19.99, 20));
        assertEquals(false, AmethystDragonService.belowVoidRescueHeight(20, 20));
        assertEquals(false, AmethystDragonService.belowVoidRescueHeight(80, 20));
    }

    @Test
    void dragonMinionsUseTheirVanillaMovementSpeeds() {
        assertEquals(0.23d, AmethystDragonService.normalMinionSpeed(EntityType.HUSK));
        assertEquals(0.25d, AmethystDragonService.normalMinionSpeed(EntityType.STRAY));
        assertEquals(0.25d, AmethystDragonService.normalMinionSpeed(EntityType.IRON_GOLEM));
        assertThrows(IllegalArgumentException.class,
                () -> AmethystDragonService.normalMinionSpeed(EntityType.ZOMBIE));
    }

    @Test
    void vanillaExitFountainMaterialsAreRemovedFromTheArenaCentre() {
        for (Material material : new Material[]{
                Material.END_PORTAL, Material.END_GATEWAY, Material.END_PORTAL_FRAME,
                Material.BEDROCK, Material.END_STONE, Material.END_STONE_BRICKS,
                Material.TORCH, Material.WALL_TORCH
        }) {
            assertEquals(true, AmethystDragonService.isVanillaExitPortalBlock(material));
        }
        assertEquals(false, AmethystDragonService.isVanillaExitPortalBlock(Material.NETHER_PORTAL));
        assertEquals(false, AmethystDragonService.isVanillaExitPortalBlock(Material.OBSIDIAN));
    }

    @Test
    void phasedSkyIsBrightOutsideCombatAndDarkDuringTheFight() {
        for (AmethystDragonService.Phase phase : AmethystDragonService.Phase.values()) {
            long expected = phase == AmethystDragonService.Phase.FIGHT ? 18_000L : 6_000L;
            assertEquals(expected,
                    AmethystDragonService.arenaSkyTime("PHASED", phase, 6_000L, 18_000L));
        }
        assertEquals(18_000L, AmethystDragonService.arenaSkyTime(
                "END", AmethystDragonService.Phase.PORTAL_OPEN, 6_000L, 18_000L));
    }

    @Test
    void everyActiveDragonPhaseReturnsVoidFallsToTheIsland() {
        for (AmethystDragonService.Phase phase : AmethystDragonService.Phase.values()) {
            assertEquals(phase != AmethystDragonService.Phase.WAITING,
                    AmethystDragonService.returnsToIsland(phase));
        }
    }

    @Test
    void visualKeyFountainDistributesEveryItemAcrossItsWaves() {
        int total = 0;
        for (int wave = 0; wave < 6; wave++) {
            int count = AmethystDragonService.visualKeyWaveCount(108, 6, wave);
            assertEquals(18, count);
            total += count;
        }
        assertEquals(108, total);

        assertEquals(4, AmethystDragonService.visualKeyWaveCount(20, 6, 0));
        assertEquals(3, AmethystDragonService.visualKeyWaveCount(20, 6, 5));
        assertEquals(0, AmethystDragonService.visualKeyWaveCount(20, 6, 6));
    }
}
