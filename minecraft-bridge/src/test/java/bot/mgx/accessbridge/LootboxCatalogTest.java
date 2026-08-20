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

class LootboxCatalogTest {
    private static final Map<String, Integer> EXPECTED_ITEM_WEIGHTS = Map.ofEntries(
            Map.entry("raw_copper", 12_700),
            Map.entry("raw_iron", 12_000),
            Map.entry("raw_gold", 10_000),
            Map.entry("emeralds", 9_000),
            Map.entry("diamonds", 7_000),
            Map.entry("wind_charges", 7_000),
            Map.entry("breeze_rods", 6_000),
            Map.entry("golden_apple", 6_000),
            Map.entry("echo_shards", 5_000),
            Map.entry("ominous_bottle", 4_000),
            Map.entry("heart_of_the_sea", 3_000),
            Map.entry("shulker_shells", 2_000),
            Map.entry("ancient_debris", 1_500),
            Map.entry("netherite_scrap", 1_250),
            Map.entry("totem_of_undying", 500),
            Map.entry("netherite_ingot", 200),
            Map.entry("enchanted_golden_apple", 150),
            Map.entry("heavy_core", 100),
            Map.entry("mace", 35)
    );

    @Test
    void completePoolTotalsExactlyOneHundredPercent() {
        assertEquals(100_000, LootboxCatalog.totalWeight());
        assertEquals("100.000%", LootboxCatalog.percentage(LootboxCatalog.totalWeight()));
        assertEquals(87_435, LootboxCatalog.all().stream()
                .filter(reward -> !reward.cosmetic())
                .mapToInt(LootboxCatalog.Reward::weight)
                .sum());
        assertEquals(12_565, LootboxCatalog.all().stream()
                .filter(LootboxCatalog.Reward::cosmetic)
                .mapToInt(LootboxCatalog.Reward::weight)
                .sum());
    }

    @Test
    void everyIntegerTicketMapsToExactlyItsConfiguredReward() {
        Map<String, Integer> observed = new HashMap<>();
        for (int ticket = 0; ticket < LootboxCatalog.TOTAL_WEIGHT; ticket++) {
            observed.merge(LootboxCatalog.rewardAt(ticket).id(), 1, Integer::sum);
        }
        for (LootboxCatalog.Reward reward : LootboxCatalog.all()) {
            assertEquals(reward.weight(), observed.getOrDefault(reward.id(), 0), reward.id());
        }
    }

    @Test
    void rewardBoundariesAreContiguousAndDeterministic() {
        int first = 0;
        for (LootboxCatalog.Reward reward : LootboxCatalog.all()) {
            assertSame(reward, LootboxCatalog.rewardAt(first));
            assertSame(reward, LootboxCatalog.rewardAt(first + reward.weight() - 1));
            first += reward.weight();
        }
        assertEquals(LootboxCatalog.TOTAL_WEIGHT, first);
        assertThrows(IllegalArgumentException.class, () -> LootboxCatalog.rewardAt(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> LootboxCatalog.rewardAt(LootboxCatalog.TOTAL_WEIGHT)
        );
    }

    @Test
    void exactItemOddsArePinned() {
        Map<String, Integer> actual = new LinkedHashMap<>();
        LootboxCatalog.all().stream()
                .filter(reward -> !reward.cosmetic())
                .forEach(reward -> actual.put(reward.id(), reward.weight()));

        assertEquals(EXPECTED_ITEM_WEIGHTS, actual);
    }

    @Test
    void cosmeticsMirrorTheCosmeticCatalogWithoutDrift() {
        for (CosmeticCatalog.Definition cosmetic : CosmeticCatalog.all()) {
            LootboxCatalog.Reward reward = LootboxCatalog.find(
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
    void secretIsAHiddenSilhouetteButItsActualWeightIsPinned() {
        LootboxCatalog.Reward secret = LootboxCatalog.find(
                "cosmetic_event_horizon"
        ).orElseThrow();

        assertTrue(secret.secret());
        assertEquals(5, secret.weight());
        assertEquals("???", secret.displayName());
        assertEquals("???", secret.displayedChance());
        assertEquals("0.005%", secret.actualChance());
        assertEquals("BLACK_DYE", secret.materialName());
        assertEquals(LootboxCatalog.Category.SECRET, secret.category());
        assertEquals(1, LootboxCatalog.all().stream().filter(LootboxCatalog.Reward::secret).count());
    }

    @Test
    void progressionRewardsStayOutOfTheShopAndElytraIsForbidden() {
        for (LootboxCatalog.Reward reward : LootboxCatalog.all()) {
            if (reward.cosmetic()) {
                continue;
            }
            assertTrue(
                    ShopCatalog.offer(reward.materialName()).isEmpty(),
                    reward.materialName() + " is already available in /shop"
            );
            assertFalse(
                    reward.materialName().equals("ELYTRA"),
                    "Elytra must never enter the lootbox pool"
            );
        }
    }

    @Test
    void highImpactRewardsStayUnderTheirEconomicCaps() {
        assertTrue(weight("totem_of_undying") <= 500);
        assertTrue(weight("netherite_ingot") <= 200);
        assertTrue(weight("enchanted_golden_apple") <= 150);
        assertTrue(weight("heavy_core") <= 100);
        assertTrue(weight("mace") <= 35);
    }

    @Test
    void auditBoundaryIncludesHighImpactItemsAndOnlyTheSecretCosmetic() {
        Set<String> audited = new HashSet<>();
        LootboxCatalog.all().stream()
                .filter(LootboxCatalog.Reward::highImpact)
                .map(LootboxCatalog.Reward::id)
                .forEach(audited::add);

        assertEquals(Set.of(
                "totem_of_undying",
                "netherite_ingot",
                "enchanted_golden_apple",
                "heavy_core",
                "mace",
                "cosmetic_event_horizon"
        ), audited);
    }

    @Test
    void everyRewardUsesARealItemMaterial() {
        for (LootboxCatalog.Reward reward : LootboxCatalog.all()) {
            Material material = Material.getMaterial(reward.materialName());
            assertTrue(material != null, reward.id() + " has no material");
            assertFalse(material.isLegacy(), reward.id() + " uses a legacy material");
            assertTrue(reward.amount() > 0);
        }
    }

    @Test
    void stableIdsAndModelKeysAreUnique() {
        Set<String> ids = new HashSet<>();
        Set<String> models = new HashSet<>();
        for (LootboxCatalog.Reward reward : LootboxCatalog.all()) {
            assertTrue(ids.add(reward.id()), "duplicate ID " + reward.id());
            assertTrue(models.add(reward.modelKey()), "duplicate model " + reward.modelKey());
        }
        Set<String> expectedIds = new HashSet<>(EXPECTED_ITEM_WEIGHTS.keySet());
        CosmeticCatalog.all().forEach(cosmetic -> expectedIds.add("cosmetic_" + cosmetic.id()));
        assertEquals(expectedIds, ids);
    }

    @Test
    void allEntriesCarryCategoryChanceRarityAndDescriptionMetadata() {
        Set<LootboxCatalog.Category> present = new HashSet<>();
        for (LootboxCatalog.Reward reward : LootboxCatalog.all()) {
            present.add(reward.category());
            assertFalse(reward.category().displayName().isBlank());
            assertFalse(reward.displayedChance().isBlank());
            assertFalse(reward.rarityDisplay().isBlank());
            assertFalse(reward.description().isBlank());
            assertEquals(reward.secret(), reward.displayedChance().equals("???"));
        }
        assertEquals(Set.of(LootboxCatalog.Category.values()), present);
    }

    @Test
    void lookupAcceptsCommandCaseAndRejectsMissingIds() {
        LootboxCatalog.Reward mace = LootboxCatalog.find("mace").orElseThrow();

        assertSame(mace, LootboxCatalog.find("  MACE ").orElseThrow());
        assertTrue(LootboxCatalog.find(null).isEmpty());
        assertTrue(LootboxCatalog.find("not_a_reward").isEmpty());
    }

    private static int weight(String id) {
        return LootboxCatalog.find(id).orElseThrow().weight();
    }
}
