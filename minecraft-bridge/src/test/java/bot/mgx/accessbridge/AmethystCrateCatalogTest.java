package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
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
    void limitedPoolContainsTwoPublicAndOneSecretCosmeticPerCategory() {
        Map<CosmeticCatalog.Category, Integer> counts = new EnumMap<>(CosmeticCatalog.Category.class);
        CosmeticCatalog.amethystRewards().forEach(definition ->
                counts.merge(definition.category(), 1, Integer::sum)
        );
        for (CosmeticCatalog.Category category : CosmeticCatalog.Category.values()) {
            assertEquals(3, counts.getOrDefault(category, 0), category.name());
            assertEquals(1, CosmeticCatalog.amethystRewards().stream()
                    .filter(CosmeticCatalog.Definition::secret)
                    .filter(definition -> definition.category() == category)
                    .count(), category.name());
        }
        assertEquals(6_009, CosmeticCatalog.amethystRewards().stream()
                .mapToInt(CosmeticCatalog.Definition::weight).sum());
    }

    @Test
    void limitedSecretsRevealNothingInTheCrateDirectory() {
        Set<String> expected = Set.of(
                "crystalline_extinction", "resonant_apotheosis", "shattered_continuum"
        );
        Set<String> actual = new HashSet<>();
        for (CosmeticCatalog.Definition definition : CosmeticCatalog.amethystRewards()) {
            if (!definition.secret()) {
                continue;
            }
            actual.add(definition.id());
            assertEquals("???", definition.displayedChance());
            assertEquals(CosmeticCatalog.MASKED_NAME, "???");
            assertTrue(CosmeticItems.masksSecret(definition, true));
            assertEquals(CosmeticCatalog.MASKED_MODEL_KEY,
                    CosmeticItems.previewModelKey(definition, true));
            assertEquals("BLACK_DYE", CosmeticItems.previewMaterialName(definition, true));
        }
        assertEquals(expected, actual);
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

    @Test
    void inactiveAuctionStatusIsRemovedWithoutTouchingActiveCountdown() {
        Component description = Component.text("May be enchanted before or after activation.");
        Component active = Component.text("ACTIVE — expires in 23h 48m", NamedTextColor.LIGHT_PURPLE);
        assertEquals(
                List.of(description),
                AmethystItemService.withoutInactiveAuctionStatus(List.of(
                        description,
                        Component.empty(),
                        Component.text("INACTIVE — safe to auction", NamedTextColor.GREEN)
                ))
        );
        assertEquals(
                List.of(description, Component.empty(), active),
                AmethystItemService.withoutInactiveAuctionStatus(
                        List.of(description, Component.empty(), active)
                )
        );
    }
}
