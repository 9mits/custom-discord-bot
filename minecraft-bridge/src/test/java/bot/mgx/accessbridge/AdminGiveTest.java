package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminGiveTest {
    @Test
    void moneyNeedsAPositiveAmount() {
        assertEquals(
                new AdminGive.Request(AdminGive.Type.MONEY, 2_500L, null),
                AdminGive.parse("money", "2500")
        );
        assertThrows(IllegalArgumentException.class, () -> AdminGive.parse("money", null));
        assertThrows(IllegalArgumentException.class, () -> AdminGive.parse("money", "0"));
    }

    @Test
    void keysDefaultToOneAndStopAtAStack() {
        // The amount is optional because handing over a single key is the common case.
        assertEquals(new AdminGive.Request(AdminGive.Type.KEY, 1L, null), AdminGive.parse("key", null));
        assertEquals(new AdminGive.Request(AdminGive.Type.KEY, 64L, null), AdminGive.parse("keys", "64"));
        assertThrows(IllegalArgumentException.class, () -> AdminGive.parse("key", "65"));
        assertThrows(IllegalArgumentException.class, () -> AdminGive.parse("key", "0"));
        assertThrows(IllegalArgumentException.class, () -> AdminGive.parse("key", "many"));
    }

    @Test
    void shardsDefaultToOneAndStopAtAStack() {
        assertEquals(
                new AdminGive.Request(AdminGive.Type.SHARD, 1L, null),
                AdminGive.parse("shard", null)
        );
        assertEquals(
                new AdminGive.Request(AdminGive.Type.SHARD, 64L, null),
                AdminGive.parse("shards", "64")
        );
        assertThrows(IllegalArgumentException.class, () -> AdminGive.parse("shard", "65"));
        assertTrue(AdminGive.TYPES.contains("shard"));
    }

    @Test
    void cosmeticsCarryTheirIdNormalised() {
        assertEquals(
                new AdminGive.Request(AdminGive.Type.COSMETIC, 1L, "ember_trail"),
                AdminGive.parse("cosmetic", "  Ember_Trail ")
        );
        assertThrows(IllegalArgumentException.class, () -> AdminGive.parse("cosmetic", null));
        assertThrows(IllegalArgumentException.class, () -> AdminGive.parse("cosmetic", "   "));
    }

    @Test
    void pluralCosmeticsWithoutAnIdMeansTheTemporaryLeaderboardSet() {
        assertEquals(
                new AdminGive.Request(AdminGive.Type.LEADERBOARD_COSMETICS, 1L, null),
                AdminGive.parse("cosmetics", null)
        );
        assertEquals(
                new AdminGive.Request(AdminGive.Type.COSMETIC, 1L, "solar_imperium"),
                AdminGive.parse("cosmetics", " Solar_Imperium ")
        );
    }

    @Test
    void cosmeticCompletionIncludesLeaderboardPreviews() {
        assertTrue(AdminGive.cosmeticIds().contains("blood_burst"));
        assertTrue(AdminGive.cosmeticIds().contains("solar_imperium"));
        assertTrue(AdminGive.cosmeticIds().contains("argent_dominion"));
        assertTrue(AdminGive.cosmeticIds().contains("bronze_vanguard"));
        assertTrue(AdminGive.cosmeticIds().contains("galactic_conquest"));
    }

    @Test
    void crateRewardsCarryTheirIdNormalised() {
        assertEquals(
                new AdminGive.Request(AdminGive.Type.REWARD, 1L, "enchant_excavation_i"),
                AdminGive.parse("reward", "  Enchant_Excavation_I ")
        );
        assertThrows(IllegalArgumentException.class, () -> AdminGive.parse("reward", null));
        assertThrows(IllegalArgumentException.class, () -> AdminGive.parse("reward", "   "));
    }

    @Test
    void amethystShortcutGrantsTheCompleteLimitedPool() {
        assertEquals(
                new AdminGive.Request(AdminGive.Type.AMETHYST_REWARDS, 1L, null),
                AdminGive.parse("amethyst", null)
        );
        assertTrue(AdminGive.TYPES.contains("amethyst"));
    }

    @Test
    void aliasesResolveAndUnknownTypesExplainThemselves() {
        assertEquals(AdminGive.Type.MONEY, AdminGive.parse("CASH", "10").type());
        assertEquals(AdminGive.Type.KEY, AdminGive.parse("Crates", "2").type());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> AdminGive.parse("diamonds", "5")
        );
        assertEquals(AdminGive.usage(), failure.getMessage());
    }
}
