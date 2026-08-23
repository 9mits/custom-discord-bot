package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FortuneCurveTest {
    @Test
    void vanillaExpectationsMatchTheOreFormula() {
        assertEquals(1d, FortuneCurve.expected(0), 1e-9);
        assertEquals(4d / 3d, FortuneCurve.expected(1), 1e-9);
        assertEquals(1.75d, FortuneCurve.expected(2), 1e-9);
        assertEquals(2.2d, FortuneCurve.expected(3), 1e-9);
        assertEquals(8d / 3d, FortuneCurve.expected(4), 1e-9);
        assertEquals(22d / 7d, FortuneCurve.expected(5), 1e-9);
    }

    @Test
    void vanillaLevelsAreLeftAlone() {
        for (int level = 0; level <= FortuneCurve.VANILLA_MAX; level++) {
            assertEquals(1d, FortuneCurve.keptShare(level), 1e-9);
        }
    }

    @Test
    void everyLevelAboveVanillaKeepsHalfOfWhatItAdds() {
        for (int level = FortuneCurve.VANILLA_MAX + 1; level <= 10; level++) {
            double paid = FortuneCurve.expected(level) * FortuneCurve.keptShare(level);
            double halfway = FortuneCurve.expected(FortuneCurve.VANILLA_MAX)
                    + (FortuneCurve.expected(level)
                    - FortuneCurve.expected(FortuneCurve.VANILLA_MAX)) * 0.5d;
            assertEquals(halfway, paid, 1e-9);
            assertTrue(paid > FortuneCurve.expected(FortuneCurve.VANILLA_MAX),
                    "Fortune " + level + " must still beat vanilla");
            assertTrue(paid < FortuneCurve.expected(level));
        }
    }

    @Test
    void theTwoLevelsTheCrateSellsLandWhereTheBalanceIntends() {
        assertEquals(2.433d, FortuneCurve.expected(4) * FortuneCurve.keptShare(4), 5e-4);
        assertEquals(2.671d, FortuneCurve.expected(5) * FortuneCurve.keptShare(5), 5e-4);
    }
}
