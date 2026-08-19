package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoBuyTest {
    @Test
    void theIntervalButtonCyclesAndWraps() {
        assertEquals(1, AutoBuy.firstInterval());
        assertEquals(2, AutoBuy.nextInterval(1));
        assertEquals(5, AutoBuy.nextInterval(2));
        assertEquals(10, AutoBuy.nextInterval(5));
        assertEquals(30, AutoBuy.nextInterval(10));
        assertEquals(1, AutoBuy.nextInterval(30));
        // A value left over from a different set of steps must still move on.
        assertEquals(1, AutoBuy.nextInterval(7));
    }

    @Test
    void anOrderIsDueOnlyOnceTheIntervalHasPassed() {
        assertFalse(AutoBuy.due(0, 0, 5));
        assertFalse(AutoBuy.due(99, 0, 5));
        assertTrue(AutoBuy.due(100, 0, 5));
        assertTrue(AutoBuy.due(101, 0, 5));
    }

    @Test
    void aLagSpikeCatchesUpRatherThanDriftingFurtherBehind() {
        // Five seconds of skipped ticks: the next tick after them is due, not the
        // next tick five seconds after them.
        assertTrue(AutoBuy.due(5_000, 4_800, 5));
        // And the run after that is measured from when it actually ran.
        assertFalse(AutoBuy.due(5_050, 5_000, 5));
        assertTrue(AutoBuy.due(5_100, 5_000, 5));
    }

    @Test
    void anIntervalOfNothingNeverFires() {
        // Otherwise a corrupted setting would buy on every tick of the server.
        assertFalse(AutoBuy.due(1_000_000, 0, 0));
        assertFalse(AutoBuy.due(1_000_000, 0, -5));
    }

    @Test
    void affordabilityCountsTheWholeRepeatNotOneItem() {
        assertTrue(AutoBuy.affordable(300, 3, 100));
        assertFalse(AutoBuy.affordable(299, 3, 100));
        assertFalse(AutoBuy.affordable(1_000_000, 0, 100));
        assertFalse(AutoBuy.affordable(1_000_000, 3, 0));
    }
}
