package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopCatalogTest {
    @Test
    void shopMatchesThePublishedBuildingAndUtilityList() {
        assertEquals(2_000L, ShopCatalog.offer("COBBLESTONE").orElseThrow().price());
        assertEquals(3_000L, ShopCatalog.offer("STONE").orElseThrow().price());
        assertEquals(18_000L, ShopCatalog.offer("OAK_LOG").orElseThrow().price());
        assertEquals(50_000L, ShopCatalog.offer("SADDLE").orElseThrow().price());
        assertEquals(75_000L, ShopCatalog.offer("NAME_TAG").orElseThrow().price());
        assertEquals(4_000L, ShopCatalog.offer("WATER_BUCKET").orElseThrow().price());
        assertEquals(8_000L, ShopCatalog.offer("LAVA_BUCKET").orElseThrow().price());
        assertFalse(ShopCatalog.offer("DIAMOND").isPresent());
    }

    @Test
    void sellMatchesTheBulkFarmSheetAndSkipsGems() {
        assertEquals(75L, ShopCatalog.sellCredit("WHEAT", 1));
        assertEquals(100L, ShopCatalog.sellCredit("SUGAR_CANE", 1));
        assertEquals(20L, ShopCatalog.sellCredit("BAMBOO", 1));
        assertEquals(20L, ShopCatalog.sellCredit("KELP", 1));
        assertEquals(4L, ShopCatalog.sellCredit("COBBLESTONE", 1));
        assertEquals(275L, ShopCatalog.sellCredit("IRON_INGOT", 1));
        assertEquals(90L, ShopCatalog.sellCredit("RED_WOOL", 1));
        assertFalse(ShopCatalog.isSellable("DIAMOND"));
        assertFalse(ShopCatalog.isSellable("EMERALD"));
        assertFalse(ShopCatalog.isSellable("ANCIENT_DEBRIS"));
        assertFalse(ShopCatalog.isSellable("NETHERITE_INGOT"));
        assertFalse(ShopCatalog.isSellable("ENDER_PEARL"));
    }

    @Test
    void rareProgressionItemsStayOffBothCounters() {
        assertFalse(ShopCatalog.offer("ELYTRA").isPresent());
        assertFalse(ShopCatalog.isSellable("TOTEM_OF_UNDYING"));
        assertFalse(ShopCatalog.isSellable("SHULKER_SHELL"));
        assertFalse(ShopCatalog.isSellable("ENCHANTED_GOLDEN_APPLE"));
    }

    @Test
    void everyCategoryHasOffers() {
        for (ShopCatalog.Category category : ShopCatalog.categories()) {
            assertFalse(ShopCatalog.offers(category).isEmpty(), category.name());
        }
    }

    @Test
    void maxOrdersRespectBalanceAndSpace() {
        ShopCatalog.Offer cobble = ShopCatalog.offer("COBBLESTONE").orElseThrow();
        assertEquals(0, cobble.maxOrders(1_999, 64));
        assertEquals(1, cobble.maxOrders(2_000, 64));
        assertEquals(2, cobble.maxOrders(10_000, 128));
    }
}
