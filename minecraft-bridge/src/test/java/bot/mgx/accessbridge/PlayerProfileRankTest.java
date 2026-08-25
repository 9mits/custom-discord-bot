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
        PlayerProfile profile = new PlayerProfile(50, 5, true, "  staff  ", "  STAFF  ", 0x1FFFFFF, 40, false);

        assertEquals("staff", profile.rankGroup());
        assertEquals("STAFF", profile.rankLabel());
        assertEquals(0xFFFFFF, profile.rankColour());
        assertEquals(40, profile.rankWeight());
        assertTrue(profile.hasRank());
        assertTrue(profile.hasRankLabel());
    }

    @Test
    void rankWeightIsClampedToUsableSortRange() {
        assertEquals(0, new PlayerProfile(0, 0, false, "", "", 0, -1, false).rankWeight());
        assertEquals(9_999, new PlayerProfile(0, 0, false, "", "", 0, 50_000, false).rankWeight());
    }

    @Test
    void nullRankFieldsBecomeEmpty() {
        PlayerProfile profile = new PlayerProfile(0, 0, false, null, null, -5, -3, false);

        assertEquals("", profile.rankGroup());
        assertEquals("", profile.rankLabel());
        assertEquals(0, profile.rankColour());
        assertFalse(profile.hasRank());
    }

    @Test
    void rankWithoutLabelIsNotDisplayable() {
        PlayerProfile profile = new PlayerProfile(0, 0, false, "booster", "", 0xFF73FA, 2, false);

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

    @Test
    void printerOnlyBypassesPacketModificationForKnownFalsePositives() {
        assertEquals(7, LuckPermsService.GRIM_PRINTER_PERMISSIONS.size());
        assertTrue(LuckPermsService.GRIM_PRINTER_PERMISSIONS.contains(
                "grim.nomodifypacket.rotationplace"
        ));
        assertTrue(LuckPermsService.GRIM_PRINTER_PERMISSIONS.contains(
                "grim.nomodifypacket.multiplace"
        ));
        assertFalse(LuckPermsService.GRIM_PRINTER_PERMISSIONS.contains("grim.nomodifypacket"));
        assertFalse(LuckPermsService.GRIM_PRINTER_PERMISSIONS.contains("grim.exempt"));
    }

    @Test
    void boosterAddsAHeartOnTopOfLevelRewards() {
        PlayerProfile levelsOnly = new PlayerProfile(50, 5, true, "owner", "OWNER", 0, 0, false);
        PlayerProfile boosting = new PlayerProfile(50, 5, true, "owner", "OWNER", 0, 0, true);

        assertEquals(5, levelsOnly.totalExtraHearts());
        assertEquals(6, boosting.totalExtraHearts());
    }

    @Test
    void elitAndBoostStackAdditively() {
        assertEquals(1.0, new PlayerProfile(10, 1, false).damageMultiplier(), 1e-9);
        assertEquals(
                1.15,
                new PlayerProfile(50, 5, true, "", "", 0, 0, false).damageMultiplier(),
                1e-9
        );
        assertEquals(
                1.10,
                new PlayerProfile(10, 1, false, "", "", 0, 0, true).damageMultiplier(),
                1e-9
        );
        assertEquals(
                1.25,
                new PlayerProfile(50, 5, true, "", "", 0, 0, true).damageMultiplier(),
                1e-9
        );
    }
}
