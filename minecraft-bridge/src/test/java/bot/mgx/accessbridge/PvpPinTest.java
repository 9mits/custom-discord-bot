package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PvpPinTest {
    @Test
    void onlyOnAndOffCountAsAPin() {
        assertEquals(Optional.of(true), PvpPin.parse("on"));
        assertEquals(Optional.of(false), PvpPin.parse("off"));
        assertEquals(Optional.of(true), PvpPin.parse("  ON\n"));
        assertEquals(Optional.empty(), PvpPin.parse(null));
        assertEquals(Optional.empty(), PvpPin.parse(""));
        assertEquals(Optional.empty(), PvpPin.parse("true"));
        assertEquals(Optional.empty(), PvpPin.parse("of"));
    }

    @Test
    void whatIsWrittenIsWhatIsReadBack() {
        assertEquals(Optional.of(true), PvpPin.parse(PvpPin.format(true)));
        assertEquals(Optional.of(false), PvpPin.parse(PvpPin.format(false)));
    }

    @Test
    void aHoldWithSecondsLeftNeverReadsAsZeroMinutes() {
        assertEquals("1m", PvpPin.describe(1L));
        assertEquals("1m", PvpPin.describe(60_000L));
        assertEquals("2m", PvpPin.describe(61_000L));
        assertEquals("1h 0m", PvpPin.describe(60L * 60_000L));
        assertEquals("4h 12m", PvpPin.describe((4L * 60L + 12L) * 60_000L));
        assertEquals("5h 0m", PvpPin.describe(5L * 60L * 60L * 1000L));
    }
}
