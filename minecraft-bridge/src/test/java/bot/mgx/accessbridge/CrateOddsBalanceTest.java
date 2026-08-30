package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CrateOddsBalanceTest {
    private static final double TARGET = 0.10d;

    /** A handful of lucky pulls must not be allowed to move the table. */
    @Test
    void nothingMovesBelowTheMinimumSample() {
        assertEquals(CrateOddsBalance.NEUTRAL_PERCENT,
                CrateOddsBalance.percent(10L, 10L, TARGET));
        assertEquals(CrateOddsBalance.NEUTRAL_PERCENT,
                CrateOddsBalance.percent(CrateOddsBalance.MINIMUM_SAMPLE - 1L, 0L, TARGET));
    }

    @Test
    void ordinaryVarianceIsLeftAlone() {
        // 10.5% against a 10% target is inside tolerance.
        assertEquals(CrateOddsBalance.NEUTRAL_PERCENT,
                CrateOddsBalance.percent(1_000L, 105L, TARGET));
    }

    @Test
    void tooGenerousIsNerfedAndTooStingyIsBuffed() {
        int nerfed = CrateOddsBalance.percent(1_000L, 200L, TARGET);
        assertTrue(nerfed < CrateOddsBalance.NEUTRAL_PERCENT, "twice the target must nerf");
        assertEquals(50, nerfed, "half weight brings 20% back to the 10% target");

        int buffed = CrateOddsBalance.percent(1_000L, 50L, TARGET);
        assertTrue(buffed > CrateOddsBalance.NEUTRAL_PERCENT, "half the target must buff");
        assertEquals(200, buffed, "double weight brings 5% back to the 10% target");
    }

    /** A freak streak must never zero a rare out or turn the table into a jackpot. */
    @Test
    void theCorrectionIsClampedBothWays() {
        assertEquals(CrateOddsBalance.FLOOR_PERCENT,
                CrateOddsBalance.percent(1_000L, 1_000L, TARGET));
        assertEquals(CrateOddsBalance.CEILING_PERCENT,
                CrateOddsBalance.percent(1_000L, 0L, TARGET));
    }

    @Test
    void countersDecayOnceTheWindowIsFull() {
        assertFalse(CrateOddsBalance.shouldDecay(CrateOddsBalance.WINDOW_OPENS - 1L));
        assertTrue(CrateOddsBalance.shouldDecay(CrateOddsBalance.WINDOW_OPENS));

        CrateOddsStore.Counts counts = new CrateOddsStore.Counts(4_000L, 400L).decayed();
        assertEquals(2_000L, counts.opens());
        assertEquals(200L, counts.rareHits());
    }

    /** A potion still does what its lore says, on top of whatever the table needs. */
    @Test
    void aPlayersLuckComposesWithTheCorrectionRatherThanBeingReplaced() {
        assertEquals(100, CrateOddsBalance.compose(100, 100));
        assertEquals(50, CrateOddsBalance.compose(100, 50));
        assertEquals(200, CrateOddsBalance.compose(100, 200));
        assertEquals(150, CrateOddsBalance.compose(300, 50));
        // Player luck keeps its own ceiling before the correction is applied.
        assertEquals(300, CrateOddsBalance.compose(9_999, 100));
    }

    @Test
    void everyCrateAdvertisesARareRateTheBalancerCanSteerTowards() {
        for (CrateKind kind : CrateKind.values()) {
            double rate = kind.advertisedRareRate();
            assertTrue(rate > 0d && rate < 1d,
                    kind + " advertises an unusable rare rate: " + rate);
        }
    }

    /** The roll has to honour a nerf; the potion clamp alone floors at 100. */
    @Test
    void theRollBandAllowsTheBalancerToNerfButAPotionNeverCan() throws Exception {
        assertEquals(CrateOddsBalance.FLOOR_PERCENT, CrateCatalog.clampRollPercent(1));
        assertEquals(100, CrateCatalog.clampLuckPercent(1));

        String source = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/CrateCatalog.java"
        ));
        assertTrue(source.contains("int safePercent = clampRollPercent(luckPercent);"),
                "the roll must use the wider band or a nerf is silently discarded");
    }
}
