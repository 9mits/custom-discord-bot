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
        assertEquals(GiftbagTimeline.ORBIT_RADIUS * 1.3, GiftbagTimeline.orbitRadius(0.45, 1.0), 1e-9);
        assertEquals(0.0, GiftbagTimeline.shake(0.4), "the bag is steady until it fills");
        assertTrue(GiftbagTimeline.shake(0.99) > GiftbagTimeline.shake(0.7), "the bag strains hardest just before it bursts");
        assertTrue(GiftbagTimeline.beatPeriod(0.95) < GiftbagTimeline.beatPeriod(0.2), "the beat quickens");
        assertEquals(1.5, GiftbagTimeline.revealScale(GiftbagTimeline.REVEAL_GROW_TICKS, 0.0));
    }

    @Test
    void theBetterThePrizeTheBiggerTheBagGrows() {
        // The bag never shrinks because of grandeur, and a mythic bag ends up twice the size
        // of an ordinary one by the time it bursts.
        double previous = 0;
        for (int tick = 0; tick <= DURATION; tick++) {
            double t = GiftbagTimeline.progress(tick, DURATION);
            double swell = GiftbagTimeline.swell(t, 1.0);
            assertTrue(swell >= previous, "the swell must only grow");
            previous = swell;
            assertEquals(1.0, GiftbagTimeline.swell(t, 0.0), "an ordinary prize keeps the plain bag");
            assertTrue(GiftbagTimeline.bagScale(t, 1.0) >= GiftbagTimeline.bagScale(t, 0.45),
                    "a rarer prize is never the smaller bag at " + t);
        }
        assertEquals(1.0 + GiftbagTimeline.GRANDEUR_GROWTH, GiftbagTimeline.swell(1.0, 1.0), 1e-9);
        assertTrue(GiftbagTimeline.bagScale(1.0, 1.0) > GiftbagTimeline.bagScale(1.0, 0.0) * 1.9,
                "the mythic bag towers over the ordinary one");
        assertTrue(GiftbagTimeline.revealScale(GiftbagTimeline.REVEAL_GROW_TICKS, 1.0)
                > GiftbagTimeline.revealScale(GiftbagTimeline.REVEAL_GROW_TICKS, 0.0),
                "the rarest prize is revealed largest");
        assertEquals(0.0, GiftbagService.grandeur(GiftbagCatalog.Rarity.RARE));
        assertEquals(1.0, GiftbagService.grandeur(GiftbagCatalog.Rarity.MYTHIC_ITEM));
        assertTrue(GiftbagService.grandeur(GiftbagCatalog.Rarity.MYTHIC)
                > GiftbagService.grandeur(GiftbagCatalog.Rarity.EXCLUSIVE));
    }

    @Test
    void theRevealOutlivesTheBagItCameOutOf() {
        assertTrue(GiftbagService.staged(false, true, false), "the bag carries the buildup");
        assertFalse(GiftbagService.staged(false, false, false), "a destroyed bag ends the opening");
        assertTrue(GiftbagService.staged(true, false, true),
                "after the burst the prize is the stage, and the bag is gone by design");
        assertFalse(GiftbagService.staged(true, true, false), "a destroyed prize ends the reveal");
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
