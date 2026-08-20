package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeyTimerTest {
    private static final long HOUR = Duration.ofHours(1).toMillis();

    @Test
    void secondsSinceTheLastPulseCountDownTheBar() {
        // The store still reports a full hour a few seconds after crediting; the bar
        // has to show those seconds or it would sit still between pulses.
        assertEquals(HOUR - 30_000L, KeyTimer.remaining(HOUR, 30_000L));
        assertEquals(0L, KeyTimer.remaining(HOUR, HOUR));
    }

    @Test
    void aLateCreditNeverReadsAsNegativeTime() {
        assertEquals(0L, KeyTimer.remaining(1_000L, 90_000L));
        assertEquals(HOUR, KeyTimer.remaining(HOUR, -5_000L));
    }

    @Test
    void progressRunsFromEmptyToFull() {
        assertEquals(0f, KeyTimer.progress(HOUR, HOUR));
        assertEquals(0.5f, KeyTimer.progress(HOUR / 2, HOUR));
        assertEquals(1f, KeyTimer.progress(0L, HOUR));
        // Clamped, because the store can report a stale value straight after a credit.
        assertEquals(1f, KeyTimer.progress(-1_000L, HOUR));
        assertEquals(0f, KeyTimer.progress(HOUR * 2, HOUR));
    }

    @Test
    void theLabelDropsMinutesOnceInsideTheLastOne() {
        assertEquals("42m 7s", KeyTimer.label(42 * 60_000L + 7_000L));
        assertEquals("9s", KeyTimer.label(8_400L));
        assertEquals("1m 0s", KeyTimer.label(60_000L));
        assertEquals("", KeyTimer.label(0L));
    }
}
