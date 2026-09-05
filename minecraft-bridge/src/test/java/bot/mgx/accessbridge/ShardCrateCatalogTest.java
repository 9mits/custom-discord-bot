package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ShardCrateCatalogTest {
    @Test
    void premiumPoolCoversEveryTicketAndNeverUsesOrdinaryFiller() {
        assertEquals(
                CrateCatalog.TOTAL_WEIGHT,
                CrateCatalog.shard().stream().mapToInt(CrateCatalog.Reward::weight).sum()
        );
        Set<String> ids = new HashSet<>();
        for (CrateCatalog.Reward reward : CrateCatalog.shard()) {
            assertTrue(ids.add(reward.id()), reward.id());
            assertTrue(reward.id().startsWith("shard_"), reward.id());
            assertFalse(Set.of(
                    "OAK_WOOD", "RAW_IRON", "RAW_GOLD", "WIND_CHARGE", "BREEZE_ROD"
            ).contains(reward.materialName()), reward.id());
        }
        for (int ticket = 0; ticket < CrateCatalog.TOTAL_WEIGHT; ticket++) {
            assertTrue(CrateCatalog.isShard(
                    CrateCatalog.rewardAt(CrateCatalog.shard(), ticket)
            ));
        }
    }

    @Test
    void permanentShardCrateCostsOneShardAndKeepsSecretsDifficult() {
        assertEquals(CrateKind.Currency.SHARD, CrateKind.SHARD.currency());
        assertEquals(1, CrateKind.SHARD.keyCost());
        assertFalse(CrateKind.SHARD.limited());
        assertTrue(CrateKind.SHARD.available(Long.MAX_VALUE));
        assertEquals("Always available", CrateKind.SHARD.remaining(System.currentTimeMillis()));

        long exoticWeight = CrateCatalog.shard().stream()
                .filter(CrateCatalog.Reward::secret)
                .mapToLong(CrateCatalog.Reward::weight)
                .sum();
        assertEquals(240L, exoticWeight);
        assertEquals(12L, CrateCatalog.shard().stream()
                .filter(CrateCatalog.Reward::secret).count());
        assertTrue(CrateCatalog.hiddenAmethystAt(0).isPresent());
    }

    @Test
    void reweightedRewardsStillResolveTheirOriginalSpecialItemBehavior() {
        assertEquals("enchant_excavation_i", find("shard_enchant_excavation_i").sourceId());
        assertEquals("amethyst_totem", find("shard_amethyst_totem").sourceId());
        assertEquals("cosmetic_event_horizon", find("shard_cosmetic_event_horizon").sourceId());
    }

    @Test
    void shardAndDragonPoolsContainTheExpansionWithoutLeaderboardExclusives() {
        Set<String> shardSources = CrateCatalog.shard().stream()
                .map(CrateCatalog.Reward::sourceId).collect(java.util.stream.Collectors.toSet());
        Set<String> expectedItems = Set.of(
                "amethyst_sword", "amethyst_hoe", "amethyst_bow", "amethyst_fishing_rod",
                "amethyst_helmet", "amethyst_chestplate", "amethyst_leggings", "amethyst_boots",
                "amethyst_elytra", "amethyst_arrows", "amethyst_apple"
        );
        assertTrue(shardSources.containsAll(expectedItems));
        assertTrue(CrateCatalog.shard().stream().noneMatch(reward -> reward.cosmetic()
                && CosmeticCatalog.find(reward.cosmeticId()).orElseThrow().leaderboardOnly()));

        assertEquals(CrateCatalog.TOTAL_WEIGHT,
                CrateCatalog.dragon().stream().mapToInt(CrateCatalog.Reward::weight).sum());
        Set<String> dragonSources = CrateCatalog.dragon().stream()
                .map(CrateCatalog.Reward::sourceId).collect(java.util.stream.Collectors.toSet());
        assertTrue(dragonSources.containsAll(expectedItems));
        assertEquals(9L, CrateCatalog.dragon().stream().filter(CrateCatalog.Reward::cosmetic).count());
    }

    private static CrateCatalog.Reward find(String id) {
        return CrateCatalog.find(id).orElseThrow();
    }
}
