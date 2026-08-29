package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ClanBattleCountdownTest {
    @Test
    void theClockKeepsItsPlacesSoTheHologramDoesNotJitter() {
        assertEquals("2d 04h 05m 06s", ClanBattleCountdown.clock(
                (2 * 86_400L + 4 * 3_600L + 5 * 60L + 6) * 1_000L
        ));
        assertEquals("4h 05m 06s", ClanBattleCountdown.clock((4 * 3_600L + 5 * 60L + 6) * 1_000L));
        assertEquals("5m 06s", ClanBattleCountdown.clock((5 * 60L + 6) * 1_000L));
        assertEquals("6s", ClanBattleCountdown.clock(6_000L));
        assertEquals("ENDED", ClanBattleCountdown.clock(0L));
    }

    @Test
    void chatReadsAsASentenceRatherThanATimer() {
        assertEquals("1 day", ClanBattleCountdown.remaining(86_400_000L));
        assertEquals("2 days 3 hours", ClanBattleCountdown.remaining(
                (2 * 86_400L + 3 * 3_600L) * 1_000L
        ));
        assertEquals("1 hour", ClanBattleCountdown.remaining(3_600_000L));
        assertEquals("10 minutes", ClanBattleCountdown.remaining(600_000L));
        assertEquals("moments", ClanBattleCountdown.remaining(0L));
    }

    @Test
    void lengthsParseSinglyAndCombined() {
        assertEquals(86_400_000L, ClanBattleCountdown.parse("1d"));
        assertEquals(7 * 86_400_000L, ClanBattleCountdown.parse(" 7D "));
        assertEquals(90 * 60_000L, ClanBattleCountdown.parse("90m"));
        assertEquals(86_400_000L + 12 * 3_600_000L, ClanBattleCountdown.parse("1d12h"));
    }

    @Test
    void anUnusableLengthIsRefusedRatherThanTreatedAsZero() {
        assertThrows(IllegalArgumentException.class, () -> ClanBattleCountdown.parse(""));
        assertThrows(IllegalArgumentException.class, () -> ClanBattleCountdown.parse("7"));
        assertThrows(IllegalArgumentException.class, () -> ClanBattleCountdown.parse("d"));
        assertThrows(IllegalArgumentException.class, () -> ClanBattleCountdown.parse("7w"));
        assertThrows(IllegalArgumentException.class, () -> ClanBattleCountdown.parse("0d"));
    }
}
