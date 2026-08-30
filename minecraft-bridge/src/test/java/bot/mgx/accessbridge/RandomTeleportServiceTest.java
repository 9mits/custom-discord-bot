package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RandomTeleportServiceTest {
    @Test
    void radiusStaysInsideTheConfiguredAnnulus() {
        assertEquals(500d, RandomTeleportService.radius(500d, 25_000d, 0d));
        assertTrue(RandomTeleportService.radius(500d, 25_000d, 1d) < 25_000d);
    }

    @Test
    void samplingIsUniformByArea() {
        double halfway = RandomTeleportService.radius(0d, 100d, 0.5d);

        assertEquals(Math.sqrt(5_000d), halfway, 0.0001d);
    }

    /**
     * The escape route this closes: CombatLog's blanket teleport block covered /rtp by
     * accident, its blocked-commands list has never named it, and that blanket is being
     * relaxed so ender pearls work in combat. Without a gate of our own, /rtp becomes a
     * free way out of a losing fight.
     *
     * <p>Reads the source because the check needs a live player and a running server.
     * A poor substitute for exercising it, and it catches exactly what would regress.
     */
    @Test
    void randomTeleportRefusesWhileInCombat() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/bot/mgx/accessbridge/RandomTeleportService.java"
        ));
        assertTrue(source.contains("afk.inCombat(player)"),
                "/rtp must refuse in combat, not rely on another plugin's config");
        assertTrue(source.contains("while in combat"),
                "and say why rather than failing silently");
    }

    @Test
    void theTeleportMenuRefusesWhileInCombat() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/bot/mgx/accessbridge/TeleportDialogService.java"
        ));
        assertTrue(source.contains("afk.inCombat(player)"),
                "/tpmenu is a teleport request and must be gated the same way");
    }
}
