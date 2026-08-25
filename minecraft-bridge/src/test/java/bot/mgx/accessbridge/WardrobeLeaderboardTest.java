package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WardrobeLeaderboardTest {
    @Test
    void currentPodiumRewardAppearsInItsWardrobeCategory() {
        LeaderboardStandings.Standing standing = new LeaderboardStandings.Standing(
                LeaderboardType.KILLS, 1, 42
        );

        CosmeticCatalog.Definition reward = WardrobeService.podiumRewardForMenu(
                standing, CosmeticCatalog.Category.AURA, false
        ).orElseThrow();

        assertEquals("solar_imperium", reward.id());
    }

    @Test
    void podiumRewardNeverAppearsInTheTradableListingFlow() {
        LeaderboardStandings.Standing standing = new LeaderboardStandings.Standing(
                LeaderboardType.WEALTH, 2, 1_000
        );

        assertTrue(WardrobeService.podiumRewardForMenu(
                standing, CosmeticCatalog.Category.TRAIL, true
        ).isEmpty());
    }

    @Test
    void nonPodiumPlayersDoNotReceiveWardrobeEntitlements() {
        LeaderboardStandings.Standing standing = new LeaderboardStandings.Standing(
                LeaderboardType.KILLS, 4, 12
        );

        assertTrue(WardrobeService.podiumRewardForMenu(
                standing, CosmeticCatalog.Category.KILL_EFFECT, false
        ).isEmpty());
    }

    @Test
    void podiumCosmeticsOnlyRenderWhenThePlayerSelectsThem() {
        LeaderboardStandings.Standing standing = new LeaderboardStandings.Standing(
                LeaderboardType.KILLS, 1, 42
        );

        assertTrue(CosmeticEffectService.selectedLeaderboardReward(
                standing, CosmeticCatalog.Category.AURA, null
        ).isEmpty());
        assertTrue(CosmeticEffectService.selectedLeaderboardReward(
                standing, CosmeticCatalog.Category.AURA, "argent_dominion"
        ).isEmpty());
        assertEquals("solar_imperium", CosmeticEffectService.selectedLeaderboardReward(
                standing, CosmeticCatalog.Category.AURA, "solar_imperium"
        ).orElseThrow().id());
    }
}
