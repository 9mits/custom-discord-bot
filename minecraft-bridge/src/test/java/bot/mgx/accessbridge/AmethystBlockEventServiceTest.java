package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AmethystBlockEventServiceTest {
    @Test
    void seamlessVisualSkinKeepsPhysicalBlockMining() throws Exception {
        assertEquals(12, AmethystBlockEventService.STRUCTURE_SIZE);
        String source = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/AmethystBlockEventService.java"
        ));

        assertTrue(source.contains("BlockDamageEvent"));
        assertTrue(source.contains("BlockDamageAbortEvent"));
        assertTrue(source.contains("BlockBreakEvent"));
        assertTrue(source.contains("block.setType(Material.AMETHYST_BLOCK, false)"));
        assertTrue(source.contains("BlockDisplay visual"));
        assertTrue(source.contains("new Vector3f(structureSize + 0.02f"));
        assertTrue(source.contains("List.of(\"huge\", \"giant\", \"humongous\")"));
        assertTrue(source.contains("structureSize = variables.integer(tier + \"-amethyst.size\")"));
        assertFalse(source.contains("EntityDamageByEntityEvent"));
        assertFalse(source.contains("Interaction interaction"));
    }

    @Test
    void ambientAuraUsesTheEarlierParticleDensity() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/AmethystBlockEventService.java"
        ));

        assertTrue(source.contains("ring < 4"));
        assertTrue(source.contains("point < 32"));
        assertTrue(source.contains("Particle.REVERSE_PORTAL, centre, 35"));
        assertTrue(source.contains("Particle.END_ROD, centre, 10"));
        assertFalse(source.contains("ring < 6"));
    }

    @Test
    void eventHasCrashCleanupKeyFountainsAndAStagedFinale() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/AmethystBlockEventService.java"
        ));

        assertTrue(source.contains("entity.setPersistent(true)"));
        assertTrue(source.contains("clearStaleStructures"));
        assertTrue(source.contains("amethyst-block-event.yml"));
        assertTrue(source.contains("writeJournal(anchor)"));
        assertTrue(source.contains("restoreJournal()"));
        assertTrue(source.contains("launchKeyFountain(block.anchor, 14, 24, true)"));
        assertTrue(source.contains("beginShatterAnimation"));
        assertTrue(source.contains("Particle.TOTEM_OF_UNDYING, centre, 850"));
    }
}
