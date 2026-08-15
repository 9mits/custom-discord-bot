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
                assertTrue(ClanLevel.isDepositable(cost.material()),
                        cost.material() + " cannot be banked, so the level is unbuyable");
            }
        }
    }

    @Test
    void badgesAreOneStarPerLevelAndDistinctlyColoured() {
        assertEquals("★", ClanLevel.badge(1));
        assertEquals("★★★★★", ClanLevel.badge(ClanLevel.MAX_PUBLIC_LEVEL));
        for (int level = 1; level <= ClanLevel.MAX_PUBLIC_LEVEL; level++) {
            assertEquals(level, ClanLevel.badge(level).codePointCount(0, ClanLevel.badge(level).length()));
            assertNotEquals(ClanLevel.badgeColor(level - 1), ClanLevel.badgeColor(level),
                    "level " + level + " looks identical to the level below it");
        }
        // The secret level is marked apart rather than by star count.
        assertNotEquals(ClanLevel.badge(ClanLevel.MAX_PUBLIC_LEVEL),
                ClanLevel.badge(ClanLevel.SECRET_LEVEL));
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
    void theVaultRefusesAnythingNoUpgradeAsksFor() {
        assertTrue(ClanLevel.isDepositable("DIAMOND"));
        assertTrue(ClanLevel.isDepositable("diamond"));
        assertFalse(ClanLevel.isDepositable("DIRT"));
        assertFalse(ClanLevel.isDepositable(""));
        assertFalse(ClanLevel.isDepositable(null));
    }

    @Test
    void materialsReadAsWordsInMessages() {
        assertEquals("Diamond Block", ClanLevel.readableMaterial("DIAMOND_BLOCK"));
        assertEquals("Enchanted Golden Apple", ClanLevel.readableMaterial("ENCHANTED_GOLDEN_APPLE"));
        assertEquals("Diamond", ClanLevel.readableMaterial("diamond"));
        assertEquals("", ClanLevel.readableMaterial(null));
    }
}
