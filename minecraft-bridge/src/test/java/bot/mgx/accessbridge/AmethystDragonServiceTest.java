package bot.mgx.accessbridge;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class AmethystDragonServiceTest {
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
                Material.BEDROCK, Material.TORCH, Material.WALL_TORCH
        }) {
            assertEquals(true, AmethystDragonService.isVanillaExitPortalBlock(material));
        }
        assertEquals(false, AmethystDragonService.isVanillaExitPortalBlock(Material.NETHER_PORTAL));
        assertEquals(false, AmethystDragonService.isVanillaExitPortalBlock(Material.OBSIDIAN));
    }
}
