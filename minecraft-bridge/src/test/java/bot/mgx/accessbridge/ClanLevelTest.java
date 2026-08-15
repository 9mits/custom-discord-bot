package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClanLevelTest {
    @Test
    void perksAreCumulativeTotalsNotIncrements() {
        // A level 4 clan holds two extra hearts in all, not two on top of level 3's one.
        assertEquals(0, ClanLevel.perksFor(0).extraHearts());
        assertEquals(0, ClanLevel.perksFor(2).extraHearts());
        assertEquals(1, ClanLevel.perksFor(3).extraHearts());
        assertEquals(2, ClanLevel.perksFor(4).extraHearts());
        assertEquals(3, ClanLevel.perksFor(5).extraHearts());
    }

    @Test
    void everyPerkClimbsOrHoldsButNeverFalls() {
        for (int level = 1; level <= ClanLevel.SECRET_LEVEL; level++) {
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
    void eachPublicLevelCostsSomething() {
        for (int level = 1; level <= ClanLevel.MAX_PUBLIC_LEVEL; level++) {
            assertFalse(ClanLevel.costOf(level).isEmpty(), "level " + level + " is free");
            for (ClanLevel.Cost cost : ClanLevel.costOf(level)) {
                assertTrue(cost.amount() > 0, "level " + level + " asks for no items");
                assertTrue(ClanLevel.isDonatable(cost.material()),
                        cost.material() + " cannot be donated, so the level is unbuyable");
            }
        }
        for (ClanLevel.MemberTier tier : ClanLevel.MEMBER_TIERS) {
            assertTrue(ClanLevel.isDonatable(tier.cost().material()),
                    tier.cost().material() + " cannot be donated, so the slot is unbuyable");
        }
    }

    @Test
    void theBadgeIsOneGlyphRecolouredRatherThanAGrowingRow() {
        // A row of stars sits in front of every chat line, so the level is carried by
        // colour instead. Colour is then the only thing telling levels apart, which is
        // why every one of them has to differ.
        for (int level = 1; level <= ClanLevel.SECRET_LEVEL; level++) {
            String badge = ClanLevel.badge(level);
            assertEquals(1, badge.codePointCount(0, badge.length()),
                    "level " + level + " badge is not a single glyph");
        }
        for (int level = 1; level <= ClanLevel.SECRET_LEVEL; level++) {
            for (int other = level + 1; other <= ClanLevel.SECRET_LEVEL; other++) {
                assertNotEquals(ClanLevel.badgeColor(level), ClanLevel.badgeColor(other),
                        "levels " + level + " and " + other + " are the same colour");
            }
        }
        // The secret level also takes its own glyph: two purples are not far enough
        // apart to read as different at a glance.
        assertNotEquals(ClanLevel.badge(ClanLevel.MAX_PUBLIC_LEVEL),
                ClanLevel.badge(ClanLevel.SECRET_LEVEL));
    }

    @Test
    void theRosterLadderOnlyEverGetsDearer() {
        int previous = 0;
        for (ClanLevel.MemberTier tier : ClanLevel.MEMBER_TIERS) {
            int price = WealthValues.valueOf(tier.cost().material()) * tier.cost().amount();
            assertTrue(price > previous,
                    "the " + tier.slots() + "-slot tier is no dearer than the one before it");
            previous = price;
        }
    }

    @Test
    void theRosterLadderBuysOneMemberAtATime() {
        assertEquals(3, ClanLevel.STARTING_MEMBER_SLOTS);
        assertEquals(25, ClanLevel.maxMemberSlots());
        // Every slot between the start and the cap is its own purchase, so there are
        // no jumps a clan pays for but cannot use.
        assertEquals(25 - 3, ClanLevel.MEMBER_TIERS.size());

        int expected = ClanLevel.STARTING_MEMBER_SLOTS + 1;
        for (ClanLevel.MemberTier tier : ClanLevel.MEMBER_TIERS) {
            assertEquals(expected++, tier.slots(), "the ladder skips a roster size");
            assertTrue(tier.cost().material().startsWith("DIAMOND")
                            || tier.cost().material().startsWith("NETHERITE"),
                    tier.cost().material() + " is neither diamond nor netherite");
        }
    }

    @Test
    void theFirstRosterSlotsAreCheapEnoughToBuyOnDayOne() {
        // A clan that cannot grow past three without a netherite budget is a clan
        // nobody bothers founding.
        ClanLevel.MemberTier first = ClanLevel.MEMBER_TIERS.get(0);
        assertEquals("DIAMOND", first.cost().material());
        assertTrue(first.cost().amount() <= 4, "the first slot costs " + first.cost().amount());

        long wholeLadder = ClanLevel.MEMBER_TIERS.stream()
                .mapToLong(tier -> (long) WealthValues.valueOf(tier.cost().material())
                        * tier.cost().amount())
                .sum();
        assertTrue(wholeLadder < 7_000, "the whole roster ladder costs " + wholeLadder);
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
        // Every size from the start to the cap is reachable; nothing outside it is.
        assertFalse(ClanLevel.isValidSlotCount(ClanLevel.STARTING_MEMBER_SLOTS - 1));
        assertFalse(ClanLevel.isValidSlotCount(ClanLevel.maxMemberSlots() + 1));
    }

    @Test
    void oldClansKeepEveryoneTheyAlreadyHold() {
        // Clans predating the ladder ran under a flat 25-player cap. Migrating them
        // down to 3 would strand members who are already inside.
        assertEquals(3, ClanLevel.smallestSlotCountHolding(1));
        assertEquals(3, ClanLevel.smallestSlotCountHolding(3));
        // Every roster size is on the ladder now, so a migrated clan lands exactly on
        // its own head count rather than being rounded up to the next rung.
        assertEquals(4, ClanLevel.smallestSlotCountHolding(4));
        assertEquals(7, ClanLevel.smallestSlotCountHolding(7));
        assertEquals(25, ClanLevel.smallestSlotCountHolding(25));
        for (int members = 1; members <= 25; members++) {
            assertTrue(ClanLevel.smallestSlotCountHolding(members) >= members,
                    members + " members would not fit");
        }
    }

    @Test
    void theSecretLevelStaysHiddenUntilThereIsNothingElseToBuy() {
        assertFalse(ClanLevel.isSecret(ClanLevel.MAX_PUBLIC_LEVEL));
        assertTrue(ClanLevel.isSecret(ClanLevel.SECRET_LEVEL));

        assertFalse(ClanLevel.canSee(0, ClanLevel.SECRET_LEVEL));
        assertFalse(ClanLevel.canSee(4, ClanLevel.SECRET_LEVEL));
        assertTrue(ClanLevel.canSee(ClanLevel.MAX_PUBLIC_LEVEL, ClanLevel.SECRET_LEVEL));
        // Everything public is visible to everyone, including a brand new clan.
        assertTrue(ClanLevel.canSee(0, ClanLevel.MAX_PUBLIC_LEVEL));
    }

    @Test
    void levelsOutsideTheLadderDoNotExist() {
        assertFalse(ClanLevel.isValid(-1));
        assertFalse(ClanLevel.isValid(ClanLevel.SECRET_LEVEL + 1));
        assertTrue(ClanLevel.isValid(0));
        assertTrue(ClanLevel.isValid(ClanLevel.SECRET_LEVEL));
    }

    @Test
    void shortfallReportsOnlyWhatIsMissing() {
        Map<String, Integer> empty = Map.of();
        assertEquals(Map.of("DIAMOND", 30), ClanLevel.shortfall(empty, 1));
        assertEquals(Map.of("DIAMOND", 5), ClanLevel.shortfall(Map.of("DIAMOND", 25), 1));
        assertTrue(ClanLevel.shortfall(Map.of("DIAMOND", 30), 1).isEmpty());
        // A surplus is still affordable.
        assertTrue(ClanLevel.shortfall(Map.of("DIAMOND", 500), 1).isEmpty());
    }

    @Test
    void theVaultTakesAnythingWorthSomething() {
        assertTrue(ClanLevel.isDonatable("DIAMOND"));
        assertTrue(ClanLevel.isDonatable("diamond"));
        // Not an upgrade material, but it has a value, so it builds the balance.
        assertTrue(ClanLevel.isDonatable("ELYTRA"));
        assertFalse(ClanLevel.isDonatable("DIRT"));
        assertFalse(ClanLevel.isDonatable(""));
        assertFalse(ClanLevel.isDonatable(null));
    }

    @Test
    void materialsReadAsWordsInMessages() {
        assertEquals("Diamond Block", ClanLevel.readableMaterial("DIAMOND_BLOCK"));
        assertEquals("Enchanted Golden Apple", ClanLevel.readableMaterial("ENCHANTED_GOLDEN_APPLE"));
        assertEquals("Diamond", ClanLevel.readableMaterial("diamond"));
        assertEquals("", ClanLevel.readableMaterial(null));
    }
}
