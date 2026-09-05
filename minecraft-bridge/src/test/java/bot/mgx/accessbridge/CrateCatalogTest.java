package bot.mgx.accessbridge;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrateCatalogTest {
    private static final Map<String, Integer> EXPECTED_ITEM_WEIGHTS = Map.ofEntries(
            Map.entry("oak_wood", 10_936),
            Map.entry("raw_iron", 10_556),
            Map.entry("raw_gold", 8_973),
            Map.entry("emeralds", 8_445),
            Map.entry("diamonds", 6_651),
            Map.entry("wind_charges", 6_967),
            Map.entry("breeze_rods", 6_123),
            Map.entry("golden_apple", 6_056),
            Map.entry("echo_shards", 5_278),
            Map.entry("ominous_bottle", 4_223),
            Map.entry("heart_of_the_sea", 3_000),
            Map.entry("shulker_shells", 2_000),
            Map.entry("ancient_debris", 2_000),
            Map.entry("netherite_scrap", 1_500),
            Map.entry("totem_of_undying", 413),
            Map.entry("netherite_ingot", 165),
            Map.entry("enchanted_golden_apple", 110),
            Map.entry("heavy_core", 82),
            Map.entry("mace", 28),
            Map.entry("potion_healing_ii", 1_000),
            Map.entry("potion_strength_ii", 413),
            Map.entry("potion_swiftness_ii", 413),
            Map.entry("potion_fire_resistance", 330),
            Map.entry("enchant_excavation_i", 6),
            Map.entry("enchant_unbreaking_iv", 165),
            Map.entry("enchant_unbreaking_v", 41),
            Map.entry("enchant_protection_v", 69),
            Map.entry("enchant_fortune_iv", 110),
            Map.entry("enchant_fortune_v", 28),
            Map.entry("fortune_potion_i", 138),
            Map.entry("fortune_potion_ii", 55),
            Map.entry("fortune_potion_iii", 19),
            Map.entry("fortune_potion_iv", 6),
            Map.entry("fortune_potion_v", 1),
            Map.entry("crate_luck_ii", 41),
            Map.entry("crate_luck_iii", 14),
            Map.entry("crate_luck_iv", 3),
            Map.entry("crate_luck_v", 1)
    );

    @Test
    void completePoolTotalsExactlyOneHundredPercent() {
        assertEquals(100_000, CrateCatalog.totalWeight());
        assertEquals("100.000%", CrateCatalog.percentage(CrateCatalog.totalWeight()));
        assertEquals(86_359, CrateCatalog.all().stream()
                .filter(reward -> !reward.cosmetic())
                .mapToInt(CrateCatalog.Reward::weight)
                .sum());
        assertEquals(13_641, CrateCatalog.all().stream()
                .filter(CrateCatalog.Reward::cosmetic)
                .mapToInt(CrateCatalog.Reward::weight)
                .sum());
    }

    @Test
    void everyIntegerTicketMapsToExactlyItsConfiguredReward() {
        Map<String, Integer> observed = new HashMap<>();
        for (int ticket = 0; ticket < CrateCatalog.TOTAL_WEIGHT; ticket++) {
            observed.merge(CrateCatalog.rewardAt(ticket).id(), 1, Integer::sum);
        }
        for (CrateCatalog.Reward reward : CrateCatalog.all()) {
            assertEquals(reward.weight(), observed.getOrDefault(reward.id(), 0), reward.id());
        }
    }

    @Test
    void rewardBoundariesAreContiguousAndDeterministic() {
        int first = 0;
        for (CrateCatalog.Reward reward : CrateCatalog.all()) {
            assertSame(reward, CrateCatalog.rewardAt(first));
            assertSame(reward, CrateCatalog.rewardAt(first + reward.weight() - 1));
            first += reward.weight();
        }
        assertEquals(CrateCatalog.TOTAL_WEIGHT, first);
        assertThrows(IllegalArgumentException.class, () -> CrateCatalog.rewardAt(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> CrateCatalog.rewardAt(CrateCatalog.TOTAL_WEIGHT)
        );
    }

    @Test
    void exactItemOddsArePinned() {
        Map<String, Integer> actual = new LinkedHashMap<>();
        CrateCatalog.all().stream()
                .filter(reward -> !reward.cosmetic())
                .forEach(reward -> actual.put(reward.id(), reward.weight()));

        assertEquals(EXPECTED_ITEM_WEIGHTS, actual);
    }

    @Test
    void cosmeticsMirrorTheCosmeticCatalogWithoutDrift() {
        for (CosmeticCatalog.Definition cosmetic : CosmeticCatalog.all()) {
            CrateCatalog.Reward reward = CrateCatalog.find(
                    "cosmetic_" + cosmetic.id()
            ).orElseThrow();

            assertTrue(reward.cosmetic());
            assertEquals(cosmetic.id(), reward.cosmeticId());
            assertEquals(cosmetic.weight(), reward.weight());
            assertEquals(cosmetic.materialName(), reward.materialName());
            assertEquals(cosmetic.modelKey(), reward.modelKey());
            assertEquals(cosmetic.secret(), reward.secret());
        }
    }

    @Test
    void secretsAreHiddenSilhouettesWithPinnedActualWeights() {
        CrateCatalog.Reward secret = CrateCatalog.find(
                "cosmetic_event_horizon"
        ).orElseThrow();

        assertTrue(secret.secret());
        assertEquals(3, secret.weight());
        assertEquals("Event Horizon", secret.displayName());
        assertEquals("???", secret.displayedChance());
        assertEquals("0.003%", secret.actualChance());
        assertEquals("BLACK_DYE", secret.materialName());
        assertEquals(CrateCatalog.Category.SECRET, secret.category());
        assertEquals(9, CrateCatalog.all().stream().filter(CrateCatalog.Reward::secret).count());
        assertTrue(CrateCatalog.all().stream()
                .filter(CrateCatalog.Reward::secret)
                .allMatch(reward -> reward.weight() == 3));
    }

    @Test
    void progressionRewardsStayOutOfTheShopAndElytraIsForbidden() {
        for (CrateCatalog.Reward reward : CrateCatalog.all()) {
            if (reward.cosmetic()) {
                continue;
            }
            assertTrue(
                    ShopCatalog.offer(reward.materialName()).isEmpty(),
                    reward.materialName() + " is already available in /shop"
            );
            assertFalse(
                    reward.materialName().equals("ELYTRA"),
                    "Elytra must never enter the crate pool"
            );
        }
    }

    @Test
    void highImpactRewardsStayUnderTheirEconomicCaps() {
        assertTrue(weight("totem_of_undying") <= 413);
        assertTrue(weight("netherite_ingot") <= 165);
        assertTrue(weight("enchanted_golden_apple") <= 110);
        assertTrue(weight("heavy_core") <= 82);
        assertTrue(weight("mace") <= 28);
    }

    @Test
    void rareBoundaryIsStrictlyBelowOnePercentAndMatchesAudit() {
        Set<String> audited = new HashSet<>();
        CrateCatalog.all().stream()
                .filter(CrateCatalog.Reward::highImpact)
                .map(CrateCatalog.Reward::id)
                .forEach(audited::add);
        Set<String> expected = new HashSet<>();
        CrateCatalog.all().stream()
                .filter(reward -> reward.weight() < 1_000)
                .map(CrateCatalog.Reward::id)
                .forEach(expected::add);

        assertEquals(expected, audited);
        assertTrue(CrateCatalog.all().stream()
                .filter(reward -> reward.weight() == 1_000)
                .noneMatch(CrateCatalog.Reward::rare));
    }

    @Test
    void jackpotChimeIsReservedForWinsRarerThanOneHundredthOfAPercent() {
        assertEquals(10, CrateCatalog.JACKPOT_WEIGHT);
        assertEquals("0.010%", CrateCatalog.percentage(CrateCatalog.JACKPOT_WEIGHT));
        Set<String> expected = new HashSet<>();
        CrateCatalog.all().stream()
                .filter(reward -> reward.weight() < 10)
                .map(CrateCatalog.Reward::id)
                .forEach(expected::add);
        Set<String> chiming = new HashSet<>();
        CrateCatalog.all().stream()
                .filter(CrateCatalog.Reward::jackpot)
                .map(CrateCatalog.Reward::id)
                .forEach(chiming::add);

        assertEquals(expected, chiming);
        assertTrue(chiming.contains("crate_luck_v"));
        assertTrue(CrateCatalog.all().stream()
                .filter(CrateCatalog.Reward::secret)
                .allMatch(CrateCatalog.Reward::jackpot));
        assertFalse(CrateCatalog.find("crate_luck_iii").orElseThrow().jackpot());
        assertTrue(CrateCatalog.all().stream()
                .filter(CrateCatalog.Reward::jackpot)
                .allMatch(CrateCatalog.Reward::rare));
    }

    @Test
    void everyRewardUsesARealItemMaterial() {
        for (CrateCatalog.Reward reward : CrateCatalog.all()) {
            Material material = Material.getMaterial(reward.materialName());
            assertTrue(material != null, reward.id() + " has no material");
            assertFalse(material.isLegacy(), reward.id() + " uses a legacy material");
            assertTrue(reward.amount() > 0);
        }
    }

    @Test
    void stableIdsAndModelKeysAreUnique() {
        Set<String> ids = new HashSet<>();
        for (CrateCatalog.Reward reward : CrateCatalog.all()) {
            assertTrue(ids.add(reward.id()), "duplicate ID " + reward.id());
        }
        Set<String> expectedIds = new HashSet<>(EXPECTED_ITEM_WEIGHTS.keySet());
        CosmeticCatalog.all().forEach(cosmetic -> expectedIds.add("cosmetic_" + cosmetic.id()));
        assertEquals(expectedIds, ids);
    }

    @Test
    void crateLuckScalesOnlyRareRewardWeights() {
        for (int percent : new int[] {150, 200, 250, CrateCatalog.MAX_LUCK_PERCENT}) {
            int expectedTotal = CrateCatalog.all().stream()
                    .mapToInt(reward -> luckyWeight(reward, percent))
                    .sum();
            assertEquals(expectedTotal, CrateCatalog.luckyTotalWeight(percent));

            Map<String, Integer> observed = new HashMap<>();
            for (int ticket = 0; ticket < expectedTotal; ticket++) {
                observed.merge(CrateCatalog.rewardAtLucky(ticket, percent).id(), 1, Integer::sum);
            }
            for (CrateCatalog.Reward reward : CrateCatalog.all()) {
                assertEquals(
                        luckyWeight(reward, percent),
                        observed.getOrDefault(reward.id(), 0), reward.id() + " at " + percent
                );
            }
        }
    }

    @Test
    void crateLuckIsClampedAndTheSmallestWeightsStillGain() {
        assertEquals(CrateCatalog.NO_LUCK_PERCENT, CrateCatalog.clampLuckPercent(1));
        assertEquals(CrateCatalog.NO_LUCK_PERCENT, CrateCatalog.clampLuckPercent(-40));
        assertEquals(CrateCatalog.MAX_LUCK_PERCENT, CrateCatalog.clampLuckPercent(900));
        assertEquals(
                CrateCatalog.totalWeight(),
                CrateCatalog.luckyTotalWeight(CrateCatalog.NO_LUCK_PERCENT)
        );
        // Crate Luck V on a weight of 1: rounding down would make the potion worthless
        // for exactly the rewards it is bought for.
        assertEquals(3, luckyWeight(CrateCatalog.find("crate_luck_v").orElseThrow(), 300));
    }

    /** Mirrors the catalog's rounding, so the expectation is not the code under test. */
    private static int luckyWeight(CrateCatalog.Reward reward, int percent) {
        return reward.rare()
                ? Math.max(1, (reward.weight() * percent + 50) / 100)
                : reward.weight();
    }

    @Test
    void allEntriesCarryCategoryChanceRarityAndDescriptionMetadata() {
        Set<CrateCatalog.Category> present = new HashSet<>();
        for (CrateCatalog.Reward reward : CrateCatalog.all()) {
            present.add(reward.category());
            assertFalse(reward.category().displayName().isBlank());
            assertFalse(reward.displayedChance().isBlank());
            assertFalse(reward.rarityDisplay().isBlank());
            assertFalse(reward.description().isBlank());
            assertEquals(reward.secret(), reward.displayedChance().equals("???"));
        }
        assertEquals(Set.of(CrateCatalog.Category.values()), present);
    }

    @Test
    void spectacleTiersOnlyEscalateLegendaryAndAbove() {
        assertEquals(CrateCatalog.RevealTier.NONE,
                CrateCatalog.find("totem_of_undying").orElseThrow().revealTier());
        assertEquals(CrateCatalog.RevealTier.LEGENDARY,
                CrateCatalog.find("mace").orElseThrow().revealTier());
        assertEquals(CrateCatalog.RevealTier.MYTHIC,
                CrateCatalog.find("cosmetic_prismatic_trail").orElseThrow().revealTier());
        assertEquals(CrateCatalog.RevealTier.SECRET,
                CrateCatalog.find("cosmetic_event_horizon").orElseThrow().revealTier());
        assertEquals(CrateCatalog.RevealTier.GENUINE_SECRET,
                CrateCatalog.find("cosmetic_iridescent_imperium").orElseThrow().revealTier());
    }

    @Test
    void everyTestableRevealTierUsesARealCatalogReward() {
        for (CrateCatalog.RevealTier tier : CrateCatalog.RevealTier.values()) {
            if (tier == CrateCatalog.RevealTier.NONE) {
                assertTrue(CrateCatalog.revealExample(tier).isEmpty());
                continue;
            }
            assertEquals(tier, CrateCatalog.revealExample(tier).orElseThrow().revealTier());
        }
    }

    @Test
    void lookupAcceptsCommandCaseAndRejectsMissingIds() {
        CrateCatalog.Reward mace = CrateCatalog.find("mace").orElseThrow();

        assertSame(mace, CrateCatalog.find("  MACE ").orElseThrow());
        assertTrue(CrateCatalog.find(null).isEmpty());
        assertTrue(CrateCatalog.find("not_a_reward").isEmpty());
    }

    @Test
    void dragonCrateIsMostlyCommonLootAtHighOpeningVolume() {
        Set<String> common = Set.of(
                "dragon_amethyst_shards", "dragon_amethyst_blocks", "dragon_amethyst_purpur",
                "dragon_amethyst_purple_glass", "dragon_amethyst_purple_concrete",
                "dragon_amethyst_clusters", "dragon_amethyst_experience_bottles",
                "dragon_amethyst_golden_carrots", "dragon_amethyst_glowstone"
        );
        int commonWeight = CrateKind.DRAGON.rewards().stream()
                .filter(reward -> common.contains(reward.id()))
                .mapToInt(CrateCatalog.Reward::weight).sum();
        assertEquals(99_158, commonWeight);
        assertTrue(commonWeight > CrateCatalog.TOTAL_WEIGHT * .99);
        assertEquals(CrateCatalog.TOTAL_WEIGHT,
                CrateKind.DRAGON.rewards().stream().mapToInt(CrateCatalog.Reward::weight).sum());
    }

    private static int weight(String id) {
        return CrateCatalog.find(id).orElseThrow().weight();
    }
}
