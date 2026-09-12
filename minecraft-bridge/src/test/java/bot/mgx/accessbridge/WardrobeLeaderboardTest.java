package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.List;

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

    /**
     * The Dragon boards award their own podium set. Resolving a Dragon placement
     * through the general list returned Solar Imperium, so Dragon's First Crown never
     * matched, never rendered, and was cleared off the player.
     */
    @Test
    void dragonPodiumCosmeticsRenderForTheDragonBoardsThatAwardThem() {
        for (LeaderboardType board : List.of(
                LeaderboardType.DRAGON_CRYSTALS, LeaderboardType.DRAGON_DAMAGE)) {
            LeaderboardStandings.Standing standing =
                    new LeaderboardStandings.Standing(board, 1, 9_000);

            assertEquals("dragon_podium_1", WardrobeService.podiumRewardForMenu(
                    standing, CosmeticCatalog.Category.AURA, false
            ).orElseThrow().id());
            assertEquals("dragon_podium_1", CosmeticEffectService.selectedLeaderboardReward(
                    standing, CosmeticCatalog.Category.AURA, "dragon_podium_1"
            ).orElseThrow().id());
        }
    }

    /** A Dragon placement still does not hand out the ordinary podium aura. */
    @Test
    void aDragonPlacementDoesNotEntitleTheOrdinaryPodiumAura() {
        LeaderboardStandings.Standing standing = new LeaderboardStandings.Standing(
                LeaderboardType.DRAGON_CRYSTALS, 1, 9_000
        );

        assertTrue(CosmeticEffectService.selectedLeaderboardReward(
                standing, CosmeticCatalog.Category.AURA, "solar_imperium"
        ).isEmpty());
    }
}
