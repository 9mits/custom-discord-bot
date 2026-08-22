package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChaosTargetingTest {
    private static boolean eligible(double distance, boolean afk, boolean vehicle, boolean physical) {
        return ChaosTargeting.eligible(true, distance * distance, 64d, afk, vehicle, physical);
    }

    @Test
    void insideTheRadiusIsTouched() {
        assertTrue(eligible(10d, false, false, false));
    }

    @Test
    void theRadiusIsInclusiveAtItsEdge() {
        assertTrue(eligible(64d, false, false, false));
        assertFalse(eligible(64.1d, false, false, false));
    }

    @Test
    void faraway_playersAreNeverTouched() {
        assertFalse(eligible(5000d, false, false, false));
    }

    @Test
    void anotherWorldIsAlwaysOutOfReach() {
        assertFalse(ChaosTargeting.eligible(false, 0d, 64d, false, false, false));
    }

    @Test
    void afkPlayersAreSkippedEvenStandingOnTheOperator() {
        assertFalse(eligible(0d, true, false, false));
    }

    @Test
    void ridersAreSkippedOnlyByEffectsThatMoveThem() {
        assertFalse(eligible(10d, false, true, true));
        assertTrue(eligible(10d, false, true, false));
    }

    @Test
    void omittedRadiusUsesTheConfiguredDefault() {
        assertEquals(80d, ChaosTargeting.radiusOrThrow(null, 80d));
        assertEquals(80d, ChaosTargeting.radiusOrThrow("   ", 80d));
    }

    @Test
    void aNonsenseConfiguredDefaultIsClampedNotObeyed() {
        assertEquals(ChaosTargeting.MAXIMUM_RADIUS, ChaosTargeting.radiusOrThrow(null, 999999d));
        assertEquals(ChaosTargeting.MINIMUM_RADIUS, ChaosTargeting.radiusOrThrow(null, -5d));
    }

    @Test
    void anExplicitRadiusIsHeldToTheRail() {
        assertEquals(120d, ChaosTargeting.radiusOrThrow("120", 64d));
        assertThrows(IllegalArgumentException.class, () -> ChaosTargeting.radiusOrThrow("1", 64d));
        assertThrows(IllegalArgumentException.class, () -> ChaosTargeting.radiusOrThrow("9999", 64d));
        assertThrows(IllegalArgumentException.class, () -> ChaosTargeting.radiusOrThrow("wide", 64d));
    }

    @Test
    void onlyTheEffectsThatMovePeopleAreMarkedPhysical() {
        assertTrue(ChaosCatalog.LAUNCH.physical());
        assertTrue(ChaosCatalog.FLOAT.physical());
        assertTrue(ChaosCatalog.SWAP.physical());
        assertFalse(ChaosCatalog.DISCO.physical());
        assertFalse(ChaosCatalog.LAVAFLOOR.physical());
    }
}
