package bot.mgx.accessbridge;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AirdropCatalogTest {
    @Test
    void rarityTableCoversEveryTicketAtPinnedBoundaries() {
        assertEquals(10_000, java.util.Arrays.stream(AirdropCatalog.Rarity.values())
                .mapToInt(AirdropCatalog.Rarity::weight).sum());
        assertEquals(AirdropCatalog.Rarity.COMMON, AirdropCatalog.rarityAt(0));
        assertEquals(AirdropCatalog.Rarity.COMMON, AirdropCatalog.rarityAt(5_499));
        assertEquals(AirdropCatalog.Rarity.RARE, AirdropCatalog.rarityAt(5_500));
        assertEquals(AirdropCatalog.Rarity.RARE, AirdropCatalog.rarityAt(8_499));
        assertEquals(AirdropCatalog.Rarity.LEGENDARY, AirdropCatalog.rarityAt(8_500));
        assertEquals(AirdropCatalog.Rarity.LEGENDARY, AirdropCatalog.rarityAt(9_699));
        assertEquals(AirdropCatalog.Rarity.MYTHIC, AirdropCatalog.rarityAt(9_700));
        assertEquals(AirdropCatalog.Rarity.MYTHIC, AirdropCatalog.rarityAt(9_999));
    }

    @Test
    void everyRarityAlwaysPaysKeysAndValidRandomizedLoot() {
        Random random = new Random(29L);
        for (AirdropCatalog.Rarity rarity : AirdropCatalog.Rarity.values()) {
            for (int roll = 0; roll < 500; roll++) {
                AirdropCatalog.Contents contents = AirdropCatalog.roll(rarity, random);
                assertTrue(contents.keys() >= rarity.minimumKeys(), rarity.name());
                assertTrue(contents.keys() <= rarity.maximumKeys(), rarity.name());
                assertTrue(contents.materialLoot().size() >= rarity.lootRolls(), rarity.name());
                for (AirdropCatalog.MaterialLoot loot : contents.materialLoot()) {
                    assertNotNull(Material.matchMaterial(loot.materialName()), loot.materialName());
                    assertTrue(loot.amount() > 0);
                }
            }
        }
    }

    @Test
    void cosmeticsAreOnePerTypeWithEqualSelectionWeightAndPrivateOdds() {
        Map<CosmeticCatalog.Category, Integer> categories =
                new EnumMap<>(CosmeticCatalog.Category.class);
        Set<Integer> weights = new HashSet<>();
        for (CosmeticCatalog.Definition definition : CosmeticCatalog.amethystAirdropRewards()) {
            categories.merge(definition.category(), 1, Integer::sum);
            weights.add(definition.weight());
            assertTrue(CosmeticCatalog.isAmethystAirdrop(definition.id()));
            assertFalse(definition.nameplateWorthy(), definition.id());
            assertFalse(CosmeticCatalog.all().contains(definition));
            assertFalse(CosmeticItems.showsReciprocalOdds(definition));
            assertFalse(CosmeticItems.showsExactChance(definition, true));
        }

        assertEquals(Set.of(CosmeticCatalog.Category.values()), categories.keySet());
        assertTrue(categories.values().stream().allMatch(count -> count == 1));
        assertEquals(1, weights.size());
        assertEquals(3, AirdropCatalog.cosmeticIds().size());
        assertEquals("Part of the limited-time Amethyst Airdrop",
                CosmeticItems.AMETHYST_AIRDROP_SOURCE);
        assertEquals(0, AirdropCatalog.Rarity.COMMON.cosmeticWeight());
        assertTrue(AirdropCatalog.Rarity.RARE.cosmeticWeight()
                < AirdropCatalog.Rarity.LEGENDARY.cosmeticWeight());
        assertTrue(AirdropCatalog.Rarity.LEGENDARY.cosmeticWeight()
                < AirdropCatalog.Rarity.MYTHIC.cosmeticWeight());
    }
}
