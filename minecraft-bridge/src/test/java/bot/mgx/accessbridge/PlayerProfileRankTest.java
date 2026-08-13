package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerProfileRankTest {
    @Test
    void legacyConstructorLeavesRankEmpty() {
        PlayerProfile profile = new PlayerProfile(10, 2, false);

        assertFalse(profile.hasRank());
        assertFalse(profile.hasRankLabel());
        assertEquals("", profile.rankGroup());
        assertEquals(0, profile.rankColour());
    }

    @Test
    void rankFieldsAreTrimmedAndClamped() {
        PlayerProfile profile = new PlayerProfile(50, 5, true, "  staff  ", "  STAFF  ", 0x1FFFFFF);

        assertEquals("staff", profile.rankGroup());
        assertEquals("STAFF", profile.rankLabel());
        assertEquals(0xFFFFFF, profile.rankColour());
        assertTrue(profile.hasRank());
        assertTrue(profile.hasRankLabel());
    }

    @Test
    void nullRankFieldsBecomeEmpty() {
        PlayerProfile profile = new PlayerProfile(0, 0, false, null, null, -5);

        assertEquals("", profile.rankGroup());
        assertEquals("", profile.rankLabel());
        assertEquals(0, profile.rankColour());
        assertFalse(profile.hasRank());
    }

    @Test
    void rankWithoutLabelIsNotDisplayable() {
        PlayerProfile profile = new PlayerProfile(0, 0, false, "booster", "", 0xFF73FA);

        assertTrue(profile.hasRank());
        assertFalse(profile.hasRankLabel());
    }

    @Test
    void protocolVersionAdvertisesRankSupport() {
        // The bot only sends rank fields to peers advertising >= 5. Shipping the
        // rank handler without raising this silently disables the whole feature.
        assertTrue(BridgeClient.PROTOCOL_VERSION >= 5);
    }

    @Test
    void managedGroupsCoverEveryRankGroup() {
        // Mirrors RANK_ROLES in minecraft_bot/perks.py; both lists must stay in step.
        assertEquals(9, LuckPermsService.MANAGED_GROUPS.size());
        assertTrue(LuckPermsService.MANAGED_GROUPS.contains("owner"));
        assertTrue(LuckPermsService.MANAGED_GROUPS.contains("community-manager"));
        assertTrue(LuckPermsService.MANAGED_GROUPS.contains("booster"));
    }
}
