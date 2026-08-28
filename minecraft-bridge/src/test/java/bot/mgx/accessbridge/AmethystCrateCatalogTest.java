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
        assertEquals(455, CosmeticCatalog.amethystRewards().stream()
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
    void genuineSecretIsOneInFiveHundredThousandAndAbsentFromEveryPublishedPool() {
        CosmeticCatalog.Definition exotic = CosmeticCatalog
                .find(CosmeticCatalog.HIDDEN_AMETHYST_COSMETIC_ID).orElseThrow();
        CrateCatalog.Reward reward = CrateCatalog.hiddenAmethyst().getFirst();

        assertEquals("Iridescent Imperium", exotic.displayName());
        assertEquals("Secret", exotic.rarityDisplay());
        assertEquals(500_000, exotic.oneIn());
        assertEquals("1 in 500,000", exotic.oneInDisplay(false));
        assertTrue(exotic.description().toLowerCase(java.util.Locale.ROOT)
                .contains("music-synced"));
        assertTrue(exotic.nameplateWorthy());
        assertTrue(exotic.secret());
        assertFalse(CosmeticCatalog.amethystRewards().contains(exotic));
        assertFalse(CrateCatalog.amethyst().contains(reward));
        assertFalse(CrateCatalog.all().contains(reward));
        assertTrue(CrateCatalog.everyReward().contains(reward));
        assertTrue(CrateCatalog.isHiddenAmethyst(reward));
        assertEquals("1 in 500,000", reward.displayedChance());
        assertEquals(CrateCatalog.RevealTier.GENUINE_SECRET, reward.revealTier());
        assertEquals(reward, CrateCatalog.hiddenAmethystAt(0).orElseThrow());
        assertTrue(CrateCatalog.hiddenAmethystAt(1).isEmpty());
        assertTrue(CrateCatalog.hiddenAmethystAt(499_999).isEmpty());
    }

    @Test
    void highValueEventRewardsStayRareAcrossTenThousandCommunityRolls() {
        Map<String, Integer> maximumWeights = Map.of(
                "amethyst_enchanted_apple", 50,
                "amethyst_excavation_i", 5,
                "amethyst_pickaxe", 20,
                "amethyst_shovel", 25,
                "amethyst_axe", 30,
                "amethyst_shield", 5,
                "amethyst_totem", 10
        );
        maximumWeights.forEach((id, maximum) -> assertTrue(
                CrateCatalog.find(id).orElseThrow().weight() <= maximum, id
        ));
        Set<String> chaseIds = new HashSet<>(maximumWeights.keySet());
        CosmeticCatalog.amethystRewards().forEach(
                definition -> chaseIds.add("cosmetic_" + definition.id())
        );
        assertEquals(600, CrateCatalog.amethyst().stream()
                .filter(reward -> chaseIds.contains(reward.id()))
                .mapToInt(CrateCatalog.Reward::weight)
                .sum());
    }

    @Test
    void afkOpeningPoolHasUsefulVanillaFoodPotionsAndEnchantments() {
        Set<String> expected = Set.of(
                "amethyst_golden_carrots",
                "amethyst_potion_regeneration_ii",
                "amethyst_potion_night_vision",
                "amethyst_potion_water_breathing",
                "amethyst_enchant_mending_i",
                "amethyst_enchant_protection_iv",
                "amethyst_enchant_sharpness_v",
                "amethyst_enchant_efficiency_v",
                "amethyst_enchant_fortune_iii",
                "amethyst_enchant_looting_iii",
                "amethyst_enchant_silk_touch_i",
                "amethyst_enchant_unbreaking_iii"
        );
        Set<String> actual = new HashSet<>();
        CrateCatalog.amethyst().forEach(reward -> actual.add(reward.id()));
        assertTrue(actual.containsAll(expected));
    }

    @Test
    void eventDeadlineIsSaturdaySeptemberFifthAtThreePmJst() {
        assertEquals(1_788_588_000_000L, CrateKind.AMETHYST.closesAt());
        assertEquals(1, CrateKind.DEFAULT.keyCost());
        assertEquals(2, CrateKind.AMETHYST.keyCost());
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
        assertTrue(CrateCatalog.amethystAdminRewards().containsAll(CrateCatalog.hiddenAmethyst()));
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
    /**
     * "Opening 3x Limited Amethyst Crate" spends a title bar on a qualifier before it
     * reaches the crate. The screens use the short name; chat, the hologram and the key
     * lore keep the full one, which is where "Limited" tells somebody something.
     */
    @Test
    void crateScreensUseAShorterNameThanAnnouncementsDo() {
        assertTrue(CrateKind.AMETHYST.displayName().contains("Limited"));
        assertFalse(CrateKind.AMETHYST.menuName().contains("Limited"));
        assertTrue(CrateKind.AMETHYST.menuName().contains("Amethyst"));
        assertTrue(CrateKind.AMETHYST.menuName().length()
                < CrateKind.AMETHYST.displayName().length());

        // The default crate has nothing to shorten, so both names stay identical.
        assertEquals(CrateKind.DEFAULT.displayName(), CrateKind.DEFAULT.menuName());
    }
}
