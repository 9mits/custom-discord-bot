package bot.mgx.accessbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CombatHoldTest {

    @Test
    void aHoldSurvivesARestart(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("combat-hold");
        CombatHold first = new CombatHold(file);
        assertFalse(first.active(), "nothing held to begin with");
        assertTrue(first.hold(CombatHold.NO_EXPIRY));

        // The competition it protects runs for days; a restart in the middle must not
        // quietly reopen the world.
        assertTrue(new CombatHold(file).active());
    }

    @Test
    void anExpiredHoldLiftsItselfOnTheNextRead(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("combat-hold");
        CombatHold hold = new CombatHold(file);
        long deadline = 5_000L;
        hold.hold(deadline);

        assertTrue(hold.active(deadline - 1));
        assertFalse(hold.active(deadline), "the deadline itself ends it");
        assertFalse(Files.exists(file), "an expired hold clears its own file");
        // And it stays lifted for the next process, with no timer to have missed.
        assertFalse(new CombatHold(file).active(deadline + 1));
    }

    @Test
    void anUnreadableHoldReopensPvpRatherThanLockingCombatOff(@TempDir Path folder)
            throws IOException {
        Path file = folder.resolve("combat-hold");
        Files.writeString(file, "sometime next week");
        assertFalse(new CombatHold(file).active(),
                "a hold nobody can point at is no hold");

        assertEquals(Long.MIN_VALUE, CombatHold.parse(null));
        assertEquals(Long.MIN_VALUE, CombatHold.parse("  "));
        assertEquals(Long.MIN_VALUE, CombatHold.parse("-1"));
        assertEquals(CombatHold.NO_EXPIRY, CombatHold.parse("0"));
        assertEquals(1_700_000_000_000L, CombatHold.parse(" 1700000000000 "));
    }

    @Test
    void liftingAndReHoldingReportWhetherAnythingChanged(@TempDir Path folder)
            throws IOException {
        CombatHold hold = new CombatHold(folder.resolve("combat-hold"));
        assertFalse(hold.lift(), "lifting nothing changes nothing");
        assertTrue(hold.hold(CombatHold.NO_EXPIRY));
        assertFalse(hold.hold(CombatHold.NO_EXPIRY), "the same hold twice is one hold");
        assertTrue(hold.hold(9_000L), "a new deadline is a change");
        assertTrue(hold.lift());
    }

    @Test
    void theStatusLinePromisesDuelsKeepWorking(@TempDir Path folder) throws IOException {
        CombatHold hold = new CombatHold(folder.resolve("combat-hold"));
        assertEquals("Open-world PvP is on.", hold.describe(0L));

        hold.hold(CombatHold.NO_EXPIRY);
        assertTrue(hold.describe(0L).contains("/pvp still works"));
        assertTrue(hold.describe(0L).contains("until an operator lifts it"));

        hold.hold(3_600_000L);
        String timed = hold.describe(0L);
        // Player-facing times are UTC, never the operator's own clock.
        assertTrue(timed.contains("UTC"), timed);
        assertTrue(timed.contains("1h 0m left"), timed);
        assertTrue(timed.contains("/pvp still works"), timed);
    }

    @Test
    void holdingPvpNeverReachesForTheGameRule() throws IOException {
        // The whole point. The PVP game rule refuses a player-on-player attack before
        // any plugin sees it, so switching it off takes /pvp duels down with the
        // ambushes — while this command's own message promises it does not.
        String bridge = Files.readString(
                Path.of("src/main/java/bot/mgx/accessbridge/MGXAccessBridge.java"),
                StandardCharsets.UTF_8);
        int start = bridge.indexOf("void forcePvp(boolean enabled)");
        assertTrue(start > 0, "forcePvp has moved");
        String body = bridge.substring(start, bridge.indexOf("\n    }", start));
        assertTrue(body.contains("launchService.forcePvp(true)"),
                "the rule has to stay on or the duel handler never gets the event");
        assertTrue(body.contains("combatHold.hold(") && body.contains("combatHold.lift()"),
                "off is a combat hold, not a game rule");

        int gate = bridge.indexOf("boolean openWorldPvpEnabled()");
        assertTrue(gate > 0, "openWorldPvpEnabled has moved");
        assertTrue(bridge.substring(gate, bridge.indexOf("\n    }", gate))
                        .contains("combatHold.active()"),
                "the hold is what makes ordinary PvP report off");
    }

    @Test
    void aFighterIsStillLetThroughWhileTheHoldIsOn() throws IOException {
        // The exemption this design leans on: the damage handler returns early for
        // ordinary PvP only when it is enabled, and a fighter skips that check.
        String duels = Files.readString(
                Path.of("src/main/java/bot/mgx/accessbridge/PvpDuelService.java"),
                StandardCharsets.UTF_8);
        assertTrue(duels.contains(
                        "if (!isFighter(attacker.getUniqueId()) && plugin.openWorldPvpEnabled())"),
                "the fighter exemption in onDamage has moved");
    }

    @Test
    void startupSaysTheHoldIsOnAndNotJustThatTheRuleIs() throws IOException {
        // forcePvp(false) pins the game rule ON, so LaunchService logs "PvP is pinned
        // on by an operator" — the opposite of what players are experiencing.
        String bridge = Files.readString(
                Path.of("src/main/java/bot/mgx/accessbridge/MGXAccessBridge.java"),
                StandardCharsets.UTF_8);
        int start = bridge.indexOf("launchService.restoreOnEnable();");
        assertTrue(start > 0, "the startup call has moved");
        assertTrue(bridge.substring(start, start + 400).contains("combatHold.describe("),
                "a restart under a hold has to say so");
    }
}
