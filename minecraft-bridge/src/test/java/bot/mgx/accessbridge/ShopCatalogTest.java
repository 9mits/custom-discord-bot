package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopCatalogTest {
    @Test
    void shopBuyPricesMatchThePublishedSheet() {
        ShopCatalog.Offer cobble = ShopCatalog.offer("COBBLESTONE").orElseThrow();
        assertEquals(64, cobble.amount());
        assertEquals(4_500L, cobble.price());
        assertEquals(6_000L, ShopCatalog.offer("DIAMOND").orElseThrow().price());
        assertEquals(9_000L, ShopCatalog.offer("OAK_LOG").orElseThrow().price());
    }

    @Test
    void sellPaysThePerItemSheetNotTheShopPrice() {
        assertEquals(20L, ShopCatalog.sellCredit("COBBLESTONE", 1));
        assertEquals(1_280L, ShopCatalog.sellCredit("COBBLESTONE", 64));
        assertEquals(900L, ShopCatalog.sellCredit("DIAMOND", 1));
        assertEquals(200L, ShopCatalog.sellCredit("SUGAR_CANE", 1));
    }

    @Test
    void shopAndSellAreDifferentTables() {
        assertTrue(ShopCatalog.offer("GLASS").isPresent());
        assertFalse(ShopCatalog.isSellable("GLASS"));
        assertTrue(ShopCatalog.isSellable("RAW_IRON"));
        assertTrue(ShopCatalog.offer("IRON_INGOT").isPresent());
        assertFalse(ShopCatalog.offer("RAW_IRON").isPresent());
    }

    @Test
    void rareProgressionItemsAreNotSoldOrBought() {
        assertFalse(ShopCatalog.isSellable("ELYTRA"));
        assertFalse(ShopCatalog.offer("ELYTRA").isPresent());
        assertFalse(ShopCatalog.isSellable("NETHERITE_INGOT"));
        assertFalse(ShopCatalog.isSellable("TOTEM_OF_UNDYING"));
        assertFalse(ShopCatalog.isSellable("SHULKER_SHELL"));
        assertFalse(ShopCatalog.isSellable("ENCHANTED_GOLDEN_APPLE"));
        assertFalse(ShopCatalog.isSellable("GOLDEN_APPLE"));
    }

    @Test
    void everyCategoryHasOffers() {
        for (ShopCatalog.Category category : ShopCatalog.categories()) {
            assertFalse(ShopCatalog.offers(category).isEmpty(), category.name());
        }
    }

    @Test
    void dyedBlocksAreAllBuyableAtTheSamePrice() {
        assertEquals(7_500L, ShopCatalog.offer("WHITE_WOOL").orElseThrow().price());
        assertEquals(7_500L, ShopCatalog.offer("BLACK_WOOL").orElseThrow().price());
        assertEquals(12_000L, ShopCatalog.offer("RED_CONCRETE").orElseThrow().price());
    }

    @Test
    void maxOrdersRespectBalanceAndSpace() {
        ShopCatalog.Offer diamond = ShopCatalog.offer("DIAMOND").orElseThrow();
        assertEquals(0, diamond.maxOrders(5_999, 64));
        assertEquals(2, diamond.maxOrders(12_000, 2));
        assertEquals(1, diamond.maxOrders(10_000, 1));
    }

    @Test
    void fillOrdersAreLimitedByHowManyStacksFitNotRawItemSlots() {
        ShopCatalog.Offer cobble = ShopCatalog.offer("COBBLESTONE").orElseThrow();
        assertEquals(1, cobble.maxOrders(10_000, 64));
        assertEquals(2, cobble.maxOrders(10_000, 128));
        assertEquals(0, cobble.maxOrders(10_000, 63));
    }
}
