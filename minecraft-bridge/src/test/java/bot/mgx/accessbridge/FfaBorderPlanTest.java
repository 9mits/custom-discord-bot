package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfaBorderPlanTest {
    private static FfaBorderPlan defaults(int players) {
        return FfaBorderPlan.of(players, 80, 12, 300, 16d, 2d, 8);
    }

    @Test
    void theBorderGrowsWithThePlayersWhoEntered() {
        assertEquals(104d, defaults(2).initial());
        assertEquals(20d, defaults(2).fin());
        assertEquals(176d, defaults(8).initial());
        assertEquals(224d, defaults(12).initial());
        assertEquals(40d, defaults(12).fin());
        assertTrue(defaults(12).initial() > defaults(4).initial());
    }

    @Test
    void everyFieldClosesInTheSameNumberOfStepsAndRespectsTheCap() {
        FfaBorderPlan small = defaults(2);
        FfaBorderPlan full = defaults(12);
        assertEquals(8d, (small.initial() - small.fin()) / small.step(), 1e-9);
        assertEquals(8d, (full.initial() - full.fin()) / full.step(), 1e-9);
        assertEquals(150d, FfaBorderPlan.of(12, 80, 12, 150, 16d, 2d, 8).initial());
        FfaBorderPlan flat = FfaBorderPlan.of(12, 80, 0, 300, 100d, 0d, 8);
        assertEquals(80d, flat.fin(), "the final zone can never be wider than the start");
    }
}
