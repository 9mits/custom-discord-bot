package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ladder arithmetic, where every mistake is somebody's rank. */
final class PvpRankTest {
    @Test
    void everyRatingLandsOnExactlyOneRank() {
        assertSame(PvpRank.BRONZE_I, PvpRank.of(0));
        assertSame(PvpRank.BRONZE_I, PvpRank.of(99));
        assertSame(PvpRank.BRONZE_II, PvpRank.of(100));
        assertSame(PvpRank.GOLD_I, PvpRank.of(600));
        assertSame(PvpRank.UNREAL, PvpRank.of(1_800));
        assertSame(PvpRank.UNREAL, PvpRank.of(9_999));
        // Nothing sits below the bottom of the ladder.
        assertSame(PvpRank.BRONZE_I, PvpRank.of(-500));
    }

    @Test
    void theFloorsRiseWithoutAGap() {
        PvpRank[] ranks = PvpRank.values();
        for (int index = 1; index < ranks.length; index++) {
            assertTrue(ranks[index].floor() > ranks[index - 1].floor(),
                    ranks[index].display() + " does not sit above " + ranks[index - 1].display());
            assertEquals(ranks[index].floor(), ranks[index - 1].nextFloor(),
                    "a rating between " + ranks[index - 1].display()
                            + " and " + ranks[index].display() + " belongs to neither");
        }
        assertEquals(PvpRank.UNREAL.floor(), PvpRank.UNREAL.nextFloor());
    }

    /**
     * A flat reward per win is a ladder you climb by duelling the worst player
     * online, which makes the number at the top mean nothing.
     */
    @Test
    void beatingSomebodyBetterIsWorthMore() {
        int overEqual = PvpRank.change(1_000, 1_000, 1d);
        int overBetter = PvpRank.change(1_000, 1_600, 1d);
        int overWorse = PvpRank.change(1_000, 400, 1d);

        assertEquals(PvpRank.K_FACTOR / 2, overEqual);
        assertTrue(overBetter > overEqual);
        assertTrue(overWorse < overEqual);
        assertTrue(overWorse >= 1, "a win must always pay something");
    }

    @Test
    void losingToSomebodyWorseCostsMore() {
        int toEqual = PvpRank.change(1_000, 1_000, 0d);
        int toWorse = PvpRank.change(1_000, 400, 0d);
        int toBetter = PvpRank.change(1_000, 1_600, 0d);

        assertTrue(toWorse < toEqual);
        assertTrue(toBetter > toEqual);
        assertTrue(toBetter <= -1, "a loss must always cost something");
    }

    /** Reaching Gold is an achievement; a bad evening costs divisions, not the tier. */
    @Test
    void demotionStopsAtTheFloorOfTheBestTierReached() {
        long atGoldTwo = 720;
        long afterCollapse = PvpRank.apply(atGoldTwo, -400, PvpRank.GOLD_II);

        assertEquals(PvpRank.GOLD_I.floor(), afterCollapse);
        assertSame(PvpRank.GOLD_I, PvpRank.of(afterCollapse));
        // Within the tier a loss still bites.
        assertEquals(700, PvpRank.apply(720, -20, PvpRank.GOLD_II));
    }

    @Test
    void aRatingNeverGoesNegative() {
        assertEquals(0, PvpRank.apply(10, -500, PvpRank.BRONZE_I));
        assertEquals(0, PvpRank.apply(0, -1, PvpRank.BRONZE_I));
    }

    @Test
    void progressReadsAcrossADivisionAndPinsAtTheTop() {
        assertEquals(0d, PvpRank.GOLD_I.progress(600));
        assertEquals(0.5d, PvpRank.GOLD_I.progress(650));
        assertEquals(1d, PvpRank.UNREAL.progress(1_800));
        assertEquals(1d, PvpRank.UNREAL.progress(50_000));
    }

    @Test
    void theTopTiersHaveNoDivisionInTheirName() {
        assertEquals("Gold II", PvpRank.GOLD_II.display());
        assertEquals("Unreal", PvpRank.UNREAL.display());
        assertEquals("Champion", PvpRank.CHAMPION.display());
    }

    @Test
    void everyTierKeepsOneIcon() {
        assertEquals(PvpRank.GOLD_I.sprite(), PvpRank.GOLD_III.sprite());
        assertEquals(PvpRank.GOLD_I.tierFloor(), PvpRank.GOLD_III.tierFloor());
    }
}
