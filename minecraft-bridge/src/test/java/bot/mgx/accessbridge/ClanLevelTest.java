package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClanLevelTest {
    @Test
    void perksAreCumulativeTotalsNotIncrements() {
        assertEquals(0, ClanLevel.perksFor(0).extraHearts());
        assertEquals(0, ClanLevel.perksFor(2).extraHearts());
        assertEquals(1, ClanLevel.perksFor(3).extraHearts());
        assertEquals(2, ClanLevel.perksFor(4).extraHearts());
        assertEquals(3, ClanLevel.perksFor(5).extraHearts());
    }

    @Test
    void everyPerkClimbsOrHoldsButNeverFalls() {
        for (int level = 1; level <= ClanLevel.MAX_PUBLIC_LEVEL; level++) {
            ClanLevel.Perks previous = ClanLevel.perksFor(level - 1);
            ClanLevel.Perks current = ClanLevel.perksFor(level);
            String at = "level " + level;
            assertTrue(current.extraHearts() >= previous.extraHearts(), at);
            assertTrue(current.strength() >= previous.strength(), at);
            assertTrue(current.saturation() >= previous.saturation(), at);
            assertTrue(current.diggingSpeed() >= previous.diggingSpeed(), at);
            assertTrue(current.resistance() >= previous.resistance(), at);
            assertTrue(current.speed() >= previous.speed(), at);
        }
    }

    @Test
    void unrankedClansGetNothing() {
        assertTrue(ClanLevel.perksFor(0).isNone());
        assertEquals("", ClanLevel.badge(0));
        assertTrue(ClanLevel.costOf(0).isEmpty());
    }

    @Test
    void eachPublicLevelCostsMoneyAndTheLastOneIsFiveHundredMillion() {
        long previous = 0L;
        for (int level = 1; level <= ClanLevel.MAX_PUBLIC_LEVEL; level++) {
            ClanLevel.Cost cost = ClanLevel.costOf(level).orElseThrow();
            assertTrue(cost.dollars() > previous, "level " + level + " is not steeper");
            previous = cost.dollars();
        }
        assertEquals(500_000_000L, ClanLevel.costOf(5).orElseThrow().dollars());
    }

    @Test
    void theBadgeIsOneGlyphRecolouredRatherThanAGrowingRow() {
        for (int level = 1; level <= ClanLevel.MAX_PUBLIC_LEVEL; level++) {
            String badge = ClanLevel.badge(level);
            assertEquals(1, badge.codePointCount(0, badge.length()),
                    "level " + level + " badge is not a single glyph");
        }
        for (int level = 1; level <= ClanLevel.MAX_PUBLIC_LEVEL; level++) {
            for (int other = level + 1; other <= ClanLevel.MAX_PUBLIC_LEVEL; other++) {
                assertNotEquals(ClanLevel.badgeColor(level), ClanLevel.badgeColor(other),
                        "levels " + level + " and " + other + " are the same colour");
            }
        }
    }

    @Test
    void theRosterLadderOnlyEverGetsDearer() {
        long previous = 0L;
        for (ClanLevel.MemberTier tier : ClanLevel.MEMBER_TIERS) {
            assertTrue(tier.cost().dollars() > previous,
                    "the " + tier.slots() + "-slot tier is no dearer than the one before it");
            previous = tier.cost().dollars();
        }
    }

    @Test
    void theRosterLadderBuysOneMemberAtATime() {
        assertEquals(3, ClanLevel.STARTING_MEMBER_SLOTS);
        assertEquals(25, ClanLevel.maxMemberSlots());
        assertEquals(25 - 3, ClanLevel.MEMBER_TIERS.size());

        int expected = ClanLevel.STARTING_MEMBER_SLOTS + 1;
        for (ClanLevel.MemberTier tier : ClanLevel.MEMBER_TIERS) {
            assertEquals(expected++, tier.slots(), "the ladder skips a roster size");
        }
    }

    @Test
    void theFirstRosterSlotsAreCheapEnoughToBuyOnDayOne() {
        assertTrue(ClanLevel.MEMBER_TIERS.get(0).cost().dollars() <= 1_000L);
    }

    @Test
    void slotsAndTiersRoundTrip() {
        assertEquals(ClanLevel.STARTING_MEMBER_SLOTS, ClanLevel.slotsAfter(0));
        assertEquals(0, ClanLevel.tiersBoughtFor(ClanLevel.STARTING_MEMBER_SLOTS));
        for (int bought = 1; bought <= ClanLevel.MEMBER_TIERS.size(); bought++) {
            int slots = ClanLevel.slotsAfter(bought);
            assertEquals(bought, ClanLevel.tiersBoughtFor(slots), "round trip at " + bought);
            assertTrue(ClanLevel.isValidSlotCount(slots));
        }
        assertTrue(ClanLevel.nextMemberTier(ClanLevel.MEMBER_TIERS.size()).isEmpty());
        assertFalse(ClanLevel.isValidSlotCount(ClanLevel.STARTING_MEMBER_SLOTS - 1));
        assertFalse(ClanLevel.isValidSlotCount(ClanLevel.maxMemberSlots() + 1));
    }

    @Test
    void oldClansKeepEveryoneTheyAlreadyHold() {
        assertEquals(3, ClanLevel.smallestSlotCountHolding(1));
        assertEquals(3, ClanLevel.smallestSlotCountHolding(3));
        assertEquals(4, ClanLevel.smallestSlotCountHolding(4));
        assertEquals(7, ClanLevel.smallestSlotCountHolding(7));
        assertEquals(25, ClanLevel.smallestSlotCountHolding(25));
        for (int members = 1; members <= 25; members++) {
            assertTrue(ClanLevel.smallestSlotCountHolding(members) >= members,
                    members + " members would not fit");
        }
    }

    @Test
    void levelsOutsideTheLadderDoNotExist() {
        assertFalse(ClanLevel.isValid(-1));
        assertFalse(ClanLevel.isValid(ClanLevel.MAX_PUBLIC_LEVEL + 1));
        assertTrue(ClanLevel.isValid(0));
        assertTrue(ClanLevel.isValid(ClanLevel.MAX_PUBLIC_LEVEL));
    }

    @Test
    void shortfallReportsOnlyWhatIsMissing() {
        ClanLevel.Cost cost = ClanLevel.costOf(1).orElseThrow();
        assertEquals(cost.dollars(), ClanLevel.shortfall(0, cost));
        assertEquals(5L, ClanLevel.shortfall(cost.dollars() - 5L, cost));
        assertEquals(0L, ClanLevel.shortfall(cost.dollars(), cost));
        assertEquals(0L, ClanLevel.shortfall(cost.dollars() + 500L, cost));
    }
}
