package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AmethystCrateCatalogTest {
    @Test
    void limitedPoolCoversEveryTicketExactlyOnce() {
        assertEquals(
                CrateCatalog.TOTAL_WEIGHT,
                CrateCatalog.amethyst().stream().mapToInt(CrateCatalog.Reward::weight).sum()
        );
        Set<String> ids = new HashSet<>();
        CrateCatalog.amethyst().forEach(reward -> assertTrue(ids.add(reward.id())));
        for (int ticket = 0; ticket < CrateCatalog.TOTAL_WEIGHT; ticket++) {
            CrateCatalog.Reward reward = CrateCatalog.rewardAt(CrateCatalog.amethyst(), ticket);
            assertTrue(CrateCatalog.amethyst().contains(reward));
        }
    }

    @Test
    void limitedPoolContainsTwoAnimatedCosmeticsPerCategory() {
        Map<CosmeticCatalog.Category, Integer> counts = new EnumMap<>(CosmeticCatalog.Category.class);
        CosmeticCatalog.amethystRewards().forEach(definition ->
                counts.merge(definition.category(), 1, Integer::sum)
        );
        for (CosmeticCatalog.Category category : CosmeticCatalog.Category.values()) {
            assertEquals(2, counts.getOrDefault(category, 0), category.name());
        }
        assertEquals(6_000, CosmeticCatalog.amethystRewards().stream()
                .mapToInt(CosmeticCatalog.Definition::weight).sum());
    }

    @Test
    void limitedCosmeticsNeverLeakIntoPermanentCrate() {
        Set<String> permanent = new HashSet<>();
        CrateCatalog.all().forEach(reward -> permanent.add(reward.id()));
        for (CosmeticCatalog.Definition definition : CosmeticCatalog.amethystRewards()) {
            assertFalse(permanent.contains("cosmetic_" + definition.id()));
            assertTrue(CrateCatalog.find("cosmetic_" + definition.id()).isPresent());
        }
    }

    @Test
    void eventDeadlineIsSaturdaySeptemberFifthAtThreePmJst() {
        assertEquals(1_788_588_000_000L, CrateKind.AMETHYST.closesAt());
        assertTrue(CrateKind.AMETHYST.available(CrateKind.AMETHYST.closesAt() - 1));
        assertFalse(CrateKind.AMETHYST.available(CrateKind.AMETHYST.closesAt()));
        assertEquals("Event ended", CrateKind.AMETHYST.remaining(CrateKind.AMETHYST.closesAt()));
    }

    @Test
    void timedEquipmentHasUniqueModelsAndTwentyFourHourDuration() {
        assertEquals(86_400_000L, AmethystItemService.ACTIVE_MILLIS);
        Set<String> models = new HashSet<>();
        int timed = 0;
        for (CrateCatalog.Reward reward : CrateCatalog.amethyst()) {
            if (Set.of(
                    "amethyst_pickaxe", "amethyst_shovel", "amethyst_axe", "amethyst_shield"
            ).contains(reward.id())) {
                timed++;
                assertTrue(models.add(reward.modelKey()));
            }
        }
        assertEquals(4, timed);
    }

    @Test
    void adminRewardDirectoryIncludesEveryLimitedReward() {
        assertTrue(CrateCatalog.everyReward().containsAll(CrateCatalog.amethyst()));
        CrateCatalog.amethyst().forEach(reward -> assertTrue(
                CrateCatalog.find(reward.id()).isPresent()
        ));
    }

    @Test
    void onlyExclusiveRewardsReceiveLimitedProvenance() {
        assertTrue(CrateCatalog.find("amethyst_shield")
                .filter(CrateCatalog::isExclusiveAmethyst).isPresent());
        assertTrue(CrateCatalog.find("cosmetic_geode_bloom")
                .filter(CrateCatalog::isExclusiveAmethyst).isPresent());
        assertFalse(CrateCatalog.find("amethyst_excavation_i")
                .filter(CrateCatalog::isExclusiveAmethyst).isPresent());
        assertFalse(CrateCatalog.find("amethyst_enchanted_apple")
                .filter(CrateCatalog::isExclusiveAmethyst).isPresent());
        assertFalse(CrateCatalog.find("amethyst_shards")
                .filter(CrateCatalog::isExclusiveAmethyst).isPresent());
    }

    @Test
    void activeItemCountdownIsRelativeAndTimezoneFree() {
        assertEquals("23h 48m", AmethystItemService.remainingDuration(
                Duration.ofHours(23).plusMinutes(48).toMillis()
        ));
        assertEquals("42m", AmethystItemService.remainingDuration(Duration.ofMinutes(42).toMillis()));
        assertEquals("30s", AmethystItemService.remainingDuration(Duration.ofSeconds(30).toMillis()));
    }
}
