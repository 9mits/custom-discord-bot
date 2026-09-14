package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ender pearls are fair movement in every fight, but never a way out of the ring. */
final class PvpPearlRulesTest {
    @Test
    void privateDuelsAllowPearlsThatLandInsideTheRing() throws Exception {
        String duel = read("PvpDuelService.java");
        assertFalse(duel.contains("Ender pearls do not work in a fight."));
        String rule = duel.substring(duel.indexOf("private boolean pearlInsideRing("),
                duel.indexOf("public void onTeleportMonitor("));
        assertTrue(rule.contains("TeleportCause.ENDER_PEARL"));
        assertTrue(rule.contains("fight.phase == Phase.FIGHTING"));
        assertTrue(rule.contains("PvpDuelRules.inside(to.getX(), to.getZ()"),
                "a pearl past the border must still be refused");
        String monitor = duel.substring(duel.indexOf("public void onTeleportMonitor("),
                duel.indexOf("public void onCommand("));
        assertTrue(monitor.contains("!pearlInsideRing(event)"),
                "the MONITOR restatement must not undo an allowed pearl");
    }

    @Test
    void competitiveMatchesAllowPearlsThatLandInsideTheBorder() throws Exception {
        String competition = read("PvpCompetitionService.java");
        assertFalse(competition.contains("Ender pearls do not work in a fight."));
        String rule = competition.substring(competition.indexOf("private boolean pearlInsideArena("),
                competition.indexOf("public void onTeleportMonitor("));
        assertTrue(rule.contains("match.alive.contains(playerId)"));
        assertTrue(rule.contains("insideArena(match, event.getTo())"));
        assertTrue(competition.contains("&& !internalTeleports.contains(playerId) && !pearlInsideArena(event)"));
        // Chorus fruit is still an uncontrolled teleport and stays refused.
        assertTrue(competition.contains("Chorus fruit does not work in a fight."));
    }

    private static String read(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/bot/mgx/accessbridge/" + name),
                StandardCharsets.UTF_8);
    }
}
