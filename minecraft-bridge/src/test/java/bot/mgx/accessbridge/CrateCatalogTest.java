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
            Map.entry("oak_wood", 10_360),
            Map.entry("raw_iron", 10_000),
            Map.entry("raw_gold", 8_500),
            Map.entry("emeralds", 8_000),
            Map.entry("diamonds", 6_300),
            Map.entry("wind_charges", 6_600),
            Map.entry("breeze_rods", 5_800),
            Map.entry("golden_apple", 5_737),
            Map.entry("echo_shards", 5_000),
            Map.entry("ominous_bottle", 4_000),
            Map.entry("heart_of_the_sea", 3_000),
            Map.entry("shulker_shells", 2_000),
            Map.entry("ancient_debris", 2_000),
            Map.entry("netherite_scrap", 1_500),
            Map.entry("totem_of_undying", 750),
            Map.entry("netherite_ingot", 300),
            Map.entry("enchanted_golden_apple", 200),
            Map.entry("heavy_core", 150),
            Map.entry("mace", 50),
            Map.entry("potion_healing_ii", 1_000),
            Map.entry("potion_strength_ii", 750),
            Map.entry("potion_swiftness_ii", 750),
            Map.entry("potion_fire_resistance", 600),
            Map.entry("enchant_excavation_i", 10),
            Map.entry("enchant_unbreaking_iv", 300),
            Map.entry("enchant_unbreaking_v", 75),
            Map.entry("enchant_protection_v", 125),
            Map.entry("enchant_fortune_iv", 200),
            Map.entry("enchant_fortune_v", 50),
            Map.entry("fortune_potion_i", 250),
            Map.entry("fortune_potion_ii", 100),
            Map.entry("fortune_potion_iii", 35),
            Map.entry("fortune_potion_iv", 10),
            Map.entry("fortune_potion_v", 2),
            Map.entry("crate_luck_ii", 75),
            Map.entry("crate_luck_iii", 25),
            Map.entry("crate_luck_iv", 5),
            Map.entry("crate_luck_v", 1)
    );

    @Test
    void completePoolTotalsExactlyOneHundredPercent() {
        assertEquals(100_000, CrateCatalog.totalWeight());
        assertEquals("100.000%", CrateCatalog.percentage(CrateCatalog.totalWeight()));
        assertEquals(84_610, CrateCatalog.all().stream()
                .filter(reward -> !reward.cosmetic())
                .mapToInt(CrateCatalog.Reward::weight)
                .sum());
        assertEquals(15_390, CrateCatalog.all().stream()
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
        assertEquals(5, secret.weight());
        assertEquals("Event Horizon", secret.displayName());
        assertEquals("???", secret.displayedChance());
        assertEquals("0.005%", secret.actualChance());
        assertEquals("BLACK_DYE", secret.materialName());
        assertEquals(CrateCatalog.Category.SECRET, secret.category());
        assertEquals(9, CrateCatalog.all().stream().filter(CrateCatalog.Reward::secret).count());
        assertTrue(CrateCatalog.all().stream()
                .filter(CrateCatalog.Reward::secret)
                .allMatch(reward -> reward.weight() == 5));
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
        assertTrue(weight("totem_of_undying") <= 750);
        assertTrue(weight("netherite_ingot") <= 300);
        assertTrue(weight("enchanted_golden_apple") <= 200);
        assertTrue(weight("heavy_core") <= 150);
        assertTrue(weight("mace") <= 50);
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
    void crateLuckMultipliesOnlyRareRewardWeights() {
        int multiplier = 5;
        int expectedTotal = CrateCatalog.all().stream()
                .mapToInt(reward -> reward.weight() * (reward.rare() ? multiplier : 1))
                .sum();
        assertEquals(expectedTotal, CrateCatalog.luckyTotalWeight(multiplier));

        Map<String, Integer> observed = new HashMap<>();
        for (int ticket = 0; ticket < expectedTotal; ticket++) {
            observed.merge(CrateCatalog.rewardAtLucky(ticket, multiplier).id(), 1, Integer::sum);
        }
        for (CrateCatalog.Reward reward : CrateCatalog.all()) {
            assertEquals(
                    reward.weight() * (reward.rare() ? multiplier : 1),
                    observed.getOrDefault(reward.id(), 0), reward.id()
            );
        }
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
    void lookupAcceptsCommandCaseAndRejectsMissingIds() {
        CrateCatalog.Reward mace = CrateCatalog.find("mace").orElseThrow();

        assertSame(mace, CrateCatalog.find("  MACE ").orElseThrow());
        assertTrue(CrateCatalog.find(null).isEmpty());
        assertTrue(CrateCatalog.find("not_a_reward").isEmpty());
    }

    private static int weight(String id) {
        return CrateCatalog.find(id).orElseThrow().weight();
    }
}
