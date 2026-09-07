package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PvpFarmGuardTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID CARA = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final long REST = 600_000L;

    @Test
    void aPairingRestsOnlyOnceItHasFoughtItselfTheWholeLimit() {
        PvpFarmGuard guard = new PvpFarmGuard();

        for (int fight = 1; fight < 5; fight++) {
            assertFalse(guard.recordFight(ALICE, BOB, fight * 1_000L, 5, REST),
                    "fight " + fight + " should not have tripped the limit");
            assertEquals(0L, guard.restRemaining(ALICE, BOB, fight * 1_000L));
        }

        assertTrue(guard.recordFight(ALICE, BOB, 5_000L, 5, REST));
        assertEquals(REST, guard.restRemaining(ALICE, BOB, 5_000L));
        // Order is not part of the pairing: the person refused must be the same one
        // whichever of them opens the screen.
        assertEquals(REST, guard.restRemaining(BOB, ALICE, 5_000L));
    }

    @Test
    void theRestExpiresOnItsOwn() {
        PvpFarmGuard guard = new PvpFarmGuard();
        for (int fight = 0; fight < 5; fight++) {
            guard.recordFight(ALICE, BOB, fight * 1_000L, 5, REST);
        }

        assertEquals(1L, guard.restRemaining(ALICE, BOB, 4_000L + REST - 1L));
        assertEquals(0L, guard.restRemaining(ALICE, BOB, 4_000L + REST));
    }

    @Test
    void fightingSomebodyElseStartsTheRunAgain() {
        PvpFarmGuard guard = new PvpFarmGuard();
        for (int fight = 0; fight < 4; fight++) {
            guard.recordFight(ALICE, BOB, fight * 1_000L, 5, REST);
        }
        assertEquals(4, guard.runLength(ALICE, BOB));
        assertEquals(4, guard.runLength(BOB, ALICE));

        // A real rivalry has other fights in it. The measure is back to back, so these
        // reset both runs rather than counting towards them.
        guard.recordFight(ALICE, CARA, 5_000L, 5, REST);
        guard.recordFight(BOB, CARA, 6_000L, 5, REST);
        assertEquals(0, guard.runLength(ALICE, BOB));
        assertEquals(0, guard.runLength(BOB, ALICE));

        assertFalse(guard.recordFight(ALICE, BOB, 7_000L, 5, REST));
        assertEquals(0L, guard.restRemaining(ALICE, BOB, 7_000L));
    }

    @Test
    void oneSidesDetourDoesNotLaunderTheOthersRun() {
        PvpFarmGuard guard = new PvpFarmGuard();
        for (int fight = 0; fight < 4; fight++) {
            guard.recordFight(ALICE, BOB, fight * 1_000L, 5, REST);
        }

        // Alice goes and fights somebody else, so her own run starts over. Bob's does
        // not: every fight he has had is still against Alice, which is the shape being
        // guarded against, and one detour by his opponent must not clear it.
        guard.recordFight(ALICE, CARA, 5_000L, 5, REST);
        assertEquals(0, guard.runLength(ALICE, BOB));
        assertEquals(4, guard.runLength(BOB, ALICE));

        assertTrue(guard.recordFight(ALICE, BOB, 6_000L, 5, REST));
        assertEquals(REST, guard.restRemaining(ALICE, BOB, 6_000L));
    }

    @Test
    void aPairingComesBackFromZeroRatherThanStraightBackToTheLimit() {
        PvpFarmGuard guard = new PvpFarmGuard();
        for (int fight = 0; fight < 5; fight++) {
            guard.recordFight(ALICE, BOB, fight * 1_000L, 5, REST);
        }

        long after = 4_000L + REST;
        assertEquals(0L, guard.restRemaining(ALICE, BOB, after));
        // The very next fight must not trip the limit again, or the rest would double
        // every time and the pairing would eventually never be allowed.
        assertFalse(guard.recordFight(ALICE, BOB, after, 5, REST));
    }

    @Test
    void aLimitOfZeroTurnsTheRestOff() {
        PvpFarmGuard guard = new PvpFarmGuard();

        for (int fight = 0; fight < 50; fight++) {
            assertFalse(guard.recordFight(ALICE, BOB, fight * 1_000L, 0, REST));
        }
        assertEquals(0L, guard.restRemaining(ALICE, BOB, 50_000L));
    }

    @Test
    void aRestOfNothingStillEndsTheRunWithoutClaimingItRested() {
        PvpFarmGuard guard = new PvpFarmGuard();

        boolean tripped = false;
        for (int fight = 0; fight < 5; fight++) {
            tripped = guard.recordFight(ALICE, BOB, fight * 1_000L, 5, 0L);
        }

        // Nothing to announce when the rest is configured away, and nothing to wait for.
        assertFalse(tripped);
        assertEquals(0L, guard.restRemaining(ALICE, BOB, 4_000L));
    }

    @Test
    void aRestIsNotSomethingARelogClears() {
        PvpFarmGuard guard = new PvpFarmGuard();
        for (int fight = 0; fight < 5; fight++) {
            guard.recordFight(ALICE, BOB, fight * 1_000L, 5, REST);
        }

        // There is deliberately no per-player forget. Quitting and rejoining is the
        // first thing anybody being told to wait would try.
        assertEquals(REST, guard.restRemaining(ALICE, BOB, 4_000L));
    }

    @Test
    void nobodyIsCountedAgainstThemselves() {
        PvpFarmGuard guard = new PvpFarmGuard();

        assertFalse(guard.recordFight(ALICE, ALICE, 0L, 1, REST));
        assertEquals(0L, guard.restRemaining(ALICE, ALICE, 0L));
    }
}
