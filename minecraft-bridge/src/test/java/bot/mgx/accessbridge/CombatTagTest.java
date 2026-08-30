package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CombatTagTest {
    private static final long WINDOW = 15_000L;

    @Test
    void aFightHoldsThePlayerUntilTheWindowLapses() {
        assertTrue(CombatTag.inCombat(1_000L, 1_000L, WINDOW));
        assertTrue(CombatTag.inCombat(1_000L, 15_999L, WINDOW));
        assertFalse(CombatTag.inCombat(1_000L, 16_000L, WINDOW));
    }

    /** Nobody has been in a fight before their first hit lands. */
    @Test
    void aPlayerWhoHasNeverFoughtIsNeverHeld() {
        assertFalse(CombatTag.inCombat(0L, 500_000L, WINDOW));
    }

    @Test
    void theCountdownRoundsUpSoItNeverReadsAsZero() {
        assertEquals(15L, CombatTag.remainingSeconds(1_000L, 1_000L, WINDOW));
        assertEquals(1L, CombatTag.remainingSeconds(1_000L, 15_999L, WINDOW));
        assertEquals(0L, CombatTag.remainingSeconds(1_000L, 16_000L, WINDOW));
        assertEquals("1 second", CombatTag.describe(1L));
        assertEquals("15 seconds", CombatTag.describe(15L));
    }

    /**
     * AFK grants damage immunity, so both routes into it have to be closed: the command
     * and the idle sweep. Leaving either open makes a losing fight unloseable.
     */
    @Test
    void bothRoutesIntoAfkAreClosedDuringAFight() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/AfkService.java"
        ));

        assertTrue(source.contains("if (inCombat(player)) {"),
                "/afk must refuse while the player is held");
        assertTrue(source.contains("&& !inCombat(player)"),
                "the idle sweep must not mark a fighting player AFK");
        // Mobs count, or an airdrop garrison can be waited out inside an AFK shield.
        assertTrue(source.contains("tagCombat(event)"));
    }
}
