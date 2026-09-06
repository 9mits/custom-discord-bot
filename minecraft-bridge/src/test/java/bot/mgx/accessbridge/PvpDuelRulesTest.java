package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PvpDuelRulesTest {
    @Test
    void reasonCarriesActualContextAndIsNormalized() {
        assertFalse(PvpDuelRules.validReason(""));
        assertFalse(PvpDuelRules.validReason("no"));
        assertTrue(PvpDuelRules.validReason("friendly rematch"));
        assertEquals("story rematch at spawn", PvpDuelRules.cleanReason(
                "  story   rematch\n at spawn  "));
        assertFalse(PvpDuelRules.validReason("x".repeat(81)));
    }

    @Test
    void wagerCanBeFreeButNeverNegativeOrOverTheOwnerLimit() {
        assertTrue(PvpDuelRules.validMoney(0L, 1_000_000L));
        assertTrue(PvpDuelRules.validMoney(1_000_000L, 1_000_000L));
        assertFalse(PvpDuelRules.validMoney(-1L, 1_000_000L));
        assertFalse(PvpDuelRules.validMoney(1_000_001L, 1_000_000L));
        assertTrue(PvpDuelRules.canReceivePool(1_000L, 500L));
        assertFalse(PvpDuelRules.canReceivePool(Long.MAX_VALUE, 1L));
    }

    @Test
    void arenaBoundaryIsStrictAndCentred() {
        assertTrue(PvpDuelRules.inside(47.99, -47.99, 0d, 0d, 96d));
        assertFalse(PvpDuelRules.inside(48d, 0d, 0d, 0d, 96d));
        assertFalse(PvpDuelRules.inside(0d, -48d, 0d, 0d, 96d));
    }

    @Test
    void locationSamplingIsUniformByAreaAndDuelsStayApart() {
        assertEquals(2_000d, PvpDuelRules.radius(2_000d, 90_000d, 0d));
        assertTrue(PvpDuelRules.radius(2_000d, 90_000d, 0.5d) > 45_000d);
        assertTrue(PvpDuelRules.separated(0d, 0d, 300d, 0d, 288d));
        assertFalse(PvpDuelRules.separated(0d, 0d, 200d, 0d, 288d));
    }
}
