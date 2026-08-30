package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AmethystBlockEventServiceTest {
    @Test
    void eventIsFortyPercentSmallerAndUsesPhysicalBlockMining() throws Exception {
        assertEquals(12, AmethystBlockEventService.STRUCTURE_SIZE);
        String source = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/AmethystBlockEventService.java"
        ));

        assertTrue(source.contains("BlockDamageEvent"));
        assertTrue(source.contains("BlockDamageAbortEvent"));
        assertTrue(source.contains("BlockBreakEvent"));
        assertTrue(source.contains("block.setType(Material.AMETHYST_BLOCK, false)"));
        assertFalse(source.contains("EntityDamageByEntityEvent"));
        assertFalse(source.contains("Interaction interaction"));
        assertFalse(source.contains("BlockDisplay display"));
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
