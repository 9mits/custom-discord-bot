package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GiftbagTimelineTest {
    private static final int DURATION = 180;

    @Test
    void theOpeningRunsItsPhasesInOrder() {
        assertEquals(GiftbagTimeline.Phase.RISE, GiftbagTimeline.phase(0));
        assertEquals(GiftbagTimeline.Phase.ORBIT, GiftbagTimeline.phase(GiftbagTimeline.RISE_END));
        assertEquals(GiftbagTimeline.Phase.CONVERGE, GiftbagTimeline.phase(GiftbagTimeline.ORBIT_END));
        assertEquals(GiftbagTimeline.Phase.CHARGE, GiftbagTimeline.phase(GiftbagTimeline.CONVERGE_END));
        assertEquals(GiftbagTimeline.Phase.CHARGE, GiftbagTimeline.phase(1));
        assertEquals(1.0, GiftbagTimeline.progress(DURATION * 2, DURATION));
    }

    @Test
    void motionIsContinuousAcrossEveryPhaseBoundary() {
        double step = 1.0 / DURATION;
        for (double t = step; t <= 1.0; t += step) {
            double before = t - step;
            assertTrue(Math.abs(GiftbagTimeline.bagScale(t) - GiftbagTimeline.bagScale(before)) < 0.3,
                    "the bag jumps in size at " + t);
            assertTrue(Math.abs(GiftbagTimeline.orbitRadius(t) - GiftbagTimeline.orbitRadius(before)) < 0.4,
                    "the ring jumps at " + t);
            assertTrue(Math.abs(GiftbagTimeline.bagLift(t) - GiftbagTimeline.bagLift(before)) < 0.2,
                    "the bag teleports at " + t);
        }
    }

    @Test
    void theRingSpinsUpThenIsSwallowedBeforeTheBurst() {
        double earlySpeed = GiftbagTimeline.orbitAngle(0.21, 0, 8, DURATION) - GiftbagTimeline.orbitAngle(0.20, 0, 8, DURATION);
        double lateSpeed = GiftbagTimeline.orbitAngle(0.81, 0, 8, DURATION) - GiftbagTimeline.orbitAngle(0.80, 0, 8, DURATION);
        assertTrue(lateSpeed > earlySpeed * 3, "the orbit must accelerate into the pull-in");
        assertEquals(0.0, GiftbagTimeline.orbitRadius(0.05), "nothing circles while the bag rises");
        assertEquals(GiftbagTimeline.ORBIT_RADIUS, GiftbagTimeline.orbitRadius(0.45), 1e-9);
        assertEquals(0.0, GiftbagTimeline.orbitScale(0.9), "the prizes are inside the bag while it charges");
        assertTrue(GiftbagTimeline.bagScale(0.85) > GiftbagTimeline.bagScale(0.5), "the bag swells as it fills");
        assertEquals(0.0, GiftbagTimeline.shake(0.4), "the bag is steady until it fills");
        assertTrue(GiftbagTimeline.shake(0.99) > GiftbagTimeline.shake(0.7), "the bag strains hardest just before it bursts");
        assertTrue(GiftbagTimeline.beatPeriod(0.95) < GiftbagTimeline.beatPeriod(0.2), "the beat quickens");
        assertEquals(1.5, GiftbagTimeline.revealScale(GiftbagTimeline.REVEAL_GROW_TICKS));
    }

    @Test
    void theOpeningIsToldByMotionNotABossBarAndTheBagHoldsNothing() throws Exception {
        String service = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/bot/mgx/accessbridge/GiftbagService.java"));
        assertFalse(service.contains("BossBar"), "no boss bar narrates the opening");
        assertFalse(service.contains("new ItemStack(Material.BUNDLE)"), "a Giftbag is not a container");
        assertEquals("FLOWER_BANNER_PATTERN", GiftbagService.BAG_MATERIAL.name());
        assertTrue(service.contains("PROVIDES_BANNER_PATTERNS"), "the bag's loom use is stripped");
    }
}
