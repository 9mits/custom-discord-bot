package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopCatalogTest {
    @Test
    void shopSitsAroundOneAndAHalfTimesSell() {
        assertEquals(400L, ShopCatalog.offer("COBBLESTONE").orElseThrow().price());
        assertEquals(4L * 64L, ShopCatalog.sellCredit("COBBLESTONE", 64));
        assertEquals(1_800L, ShopCatalog.offer("WHEAT").orElseThrow().price());
        assertEquals(75L * 16L, ShopCatalog.sellCredit("WHEAT", 16));
        assertEquals(6_600L, ShopCatalog.offer("IRON_INGOT").orElseThrow().price());
        assertEquals(275L * 16L, ShopCatalog.sellCredit("IRON_INGOT", 16));
    }

    @Test
    void boneIsTheCheapFarmInput() {
        long boneSell = ShopCatalog.sellCredit("BONE", 1);
        long boneBuy = ShopCatalog.offer("BONE").orElseThrow().price();
        assertEquals(8L, boneSell);
        assertEquals(12L, boneBuy);
        assertTrue(boneSell < ShopCatalog.sellCredit("KELP", 1));
        assertTrue(boneSell < ShopCatalog.sellCredit("BAMBOO", 1));
        assertTrue(boneSell < ShopCatalog.sellCredit("WHEAT", 1));
        assertTrue(boneSell < ShopCatalog.sellCredit("SUGAR_CANE", 1));
        assertTrue(boneBuy < ShopCatalog.sellCredit("SUGAR_CANE", 1));
        assertEquals(50L, ShopCatalog.offer("BONE_MEAL").orElseThrow().price());
    }

    @Test
    void sellKeepsTheBulkFarmSheetAndSkipsGems() {
        assertEquals(100L, ShopCatalog.sellCredit("SUGAR_CANE", 1));
        assertEquals(20L, ShopCatalog.sellCredit("BAMBOO", 1));
        assertEquals(20L, ShopCatalog.sellCredit("KELP", 1));
        assertFalse(ShopCatalog.isSellable("DIAMOND"));
        assertFalse(ShopCatalog.offer("DIAMOND").isPresent());
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
    void shopChargesByTheSingleItem() {
        ShopCatalog.Offer cobble = ShopCatalog.offer("COBBLESTONE").orElseThrow();
        assertEquals(6L, cobble.unitPrice());
        assertEquals(6L, cobble.costOfItems(1));
        assertEquals(384L, cobble.costOfItems(64));
        assertEquals(0, cobble.maxItems(5, 64));
        assertEquals(64, cobble.maxItems(10_000, 64));
    }

    @Test
    void maxOrdersRespectBalanceAndSpace() {
        ShopCatalog.Offer cobble = ShopCatalog.offer("COBBLESTONE").orElseThrow();
        assertEquals(0, cobble.maxOrders(399, 64));
        assertEquals(1, cobble.maxOrders(400, 64));
        assertEquals(2, cobble.maxOrders(10_000, 128));
    }
}
