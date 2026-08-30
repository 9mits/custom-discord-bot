package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CrateOddsBalanceTest {
    private static final double TARGET = 0.10d;

    /** The expectation a window of unluckied opens carries. */
    private static double expected(long opens) {
        return opens * TARGET;
    }

    /** A handful of lucky pulls must not be allowed to move the table. */
    @Test
    void nothingMovesBelowTheMinimumSample() {
        assertEquals(CrateOddsBalance.NEUTRAL_PERCENT,
                CrateOddsBalance.percent(10L, 10L, expected(10L), TARGET));
        long thin = CrateOddsBalance.MINIMUM_SAMPLE - 1L;
        assertEquals(CrateOddsBalance.NEUTRAL_PERCENT,
                CrateOddsBalance.percent(thin, 0L, expected(thin), TARGET));
    }

    @Test
    void ordinaryVarianceIsLeftAlone() {
        // 105 hits against 100 expected is half a standard deviation.
        assertEquals(CrateOddsBalance.NEUTRAL_PERCENT,
                CrateOddsBalance.percent(1_000L, 105L, expected(1_000L), TARGET));
    }

    @Test
    void tooGenerousIsNerfedAndTooStingyIsBuffed() {
        int nerfed = CrateOddsBalance.percent(1_000L, 200L, expected(1_000L), TARGET);
        assertTrue(nerfed < CrateOddsBalance.NEUTRAL_PERCENT, "twice the target must nerf");

        int buffed = CrateOddsBalance.percent(1_000L, 50L, expected(1_000L), TARGET);
        assertTrue(buffed > CrateOddsBalance.NEUTRAL_PERCENT, "half the target must buff");
    }

    /** A freak streak must never zero a rare out or turn the table into a jackpot. */
    @Test
    void theCorrectionIsClampedBothWays() {
        assertEquals(CrateOddsBalance.FLOOR_PERCENT,
                CrateOddsBalance.percent(1_000L, 1_000L, expected(1_000L), TARGET));
        assertEquals(CrateOddsBalance.CEILING_PERCENT,
                CrateOddsBalance.percent(1_000L, 0L, expected(1_000L), TARGET));
    }

    /**
     * The deadband has to widen as the sample thins, or one constant is deaf on a busy
     * server and twitchy on a quiet one. The same relative overshoot must be noise in a
     * small window and a correction in a large one.
     */
    @Test
    void theDeadbandScalesWithTheSample() {
        // 20% above expectation. At 500 opens that is 1.4 sigma; at 4,000 it is 4.
        assertEquals(CrateOddsBalance.NEUTRAL_PERCENT,
                CrateOddsBalance.percent(500L, 60L, expected(500L), TARGET),
                "20% over on fifty expected hits is ordinary variance");
        assertTrue(CrateOddsBalance.percent(4_000L, 480L, expected(4_000L), TARGET)
                        < CrateOddsBalance.NEUTRAL_PERCENT,
                "the same 20% over on four hundred expected hits is real drift");
    }

    /**
     * The flaw this replaced: rares won under a potion or a 2x Crate Luck event were
     * recorded as evidence the table was too generous, so the balancer nerfed the base
     * rate for everybody and quietly fought the event that had been advertised.
     */
    @Test
    void luckIsNotMistakenForTheTableDrifting() {
        int doubled = 200;
        double rate = CrateOddsBalance.expectedRareRate(TARGET, doubled);
        assertTrue(rate > TARGET, "doubling the rare weights must raise the rate");

        long opens = 1_000L;
        double expected = opens * rate;
        long hits = Math.round(expected);

        assertEquals(CrateOddsBalance.NEUTRAL_PERCENT,
                CrateOddsBalance.percent(opens, hits, expected, TARGET),
                "a window played entirely under a potion must not move the table");

        // The old arithmetic compared those same hits against the unluckied target and
        // read a fair table as wildly over-generous.
        assertTrue(hits > expected(opens) * 1.5d,
                "the raw count really is far above the published rate, which is the trap");
    }

    /** Scaling rare weights raises the rate sub-linearly, because the pool grows too. */
    @Test
    void theExpectedRateIsSubLinearInTheMultiplier() {
        double doubled = CrateOddsBalance.expectedRareRate(TARGET, 200);
        assertTrue(doubled < TARGET * 2d, "the commons dilute the boost");
        assertTrue(doubled > TARGET, "but it is still a boost");
        assertEquals(TARGET, CrateOddsBalance.expectedRareRate(TARGET, 100), 1e-9,
                "no multiplier is the published rate exactly");
        assertTrue(CrateOddsBalance.expectedRareRate(TARGET, 50) < TARGET,
                "a nerf lowers it");
    }

    /** The correction has to land where it was aimed rather than converge on it. */
    @Test
    void aCorrectionReachesTheRateItWasAimedAt() {
        double wanted = 0.15d;
        int multiplier = CrateOddsBalance.weightPercentForRate(TARGET, wanted);
        assertEquals(wanted, CrateOddsBalance.expectedRareRate(TARGET, multiplier), 1e-3,
                "the inverse must round-trip");
        assertTrue(multiplier > 150,
                "reaching 1.5x the rate needs more than 1.5x the weight, since the pool grows");
    }

    @Test
    void countersDecayOnceTheWindowIsFull() {
        assertFalse(CrateOddsBalance.shouldDecay(CrateOddsBalance.WINDOW_OPENS - 1L));
        assertTrue(CrateOddsBalance.shouldDecay(CrateOddsBalance.WINDOW_OPENS));

        CrateOddsStore.Counts counts =
                new CrateOddsStore.Counts(4_000L, 400L, 380d).decayed();
        assertEquals(2_000L, counts.opens());
        assertEquals(200L, counts.rareHits());
        assertEquals(190d, counts.expectedHits(), 1e-9,
                "expectation has to decay with the counts it is compared against");
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
            assertEquals(rate, CrateOddsBalance.expectedRareRate(rate, 100), 1e-9,
                    kind + " must expect its own published rate when no luck is in play");
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

    /**
     * The balancer must never be handed a raw hit count again. Guards the shape of the
     * bug rather than this instance of it: any caller that skips the expectation is
     * reintroducing exactly the luck contamination this replaced.
     */
    @Test
    void theBalancerIsOnlyEverAskedWithAnExpectation() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/CrateService.java"
        ));
        assertTrue(source.contains("counts.expectedHits()"),
                "balancePercent must pass the accumulated expectation");
        assertTrue(source.contains("CrateOddsBalance.expectedRareRate("),
                "every recorded open must carry the rate of the table it rolled on");
    }
}
