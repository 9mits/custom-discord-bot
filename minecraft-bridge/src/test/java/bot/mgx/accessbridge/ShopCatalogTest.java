package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopCatalogTest {
    @Test
    void buildingBlocksMatchThePublishedSellSheet() {
        ShopCatalog.Offer cobble = ShopCatalog.offer("COBBLESTONE").orElseThrow();
        assertEquals(64, cobble.amount());
        assertEquals(35L, cobble.price());
        assertEquals(200L, ShopCatalog.offer("OBSIDIAN").orElseThrow().price());
    }

    @Test
    void aPartialStackSellsAtTheProRataRate() {
        ShopCatalog.Offer cobble = ShopCatalog.offer("COBBLESTONE").orElseThrow();
        assertEquals(17L, cobble.creditFor(32));
        assertEquals(0L, cobble.creditFor(1));
        assertEquals(35L, cobble.creditFor(64));
        assertEquals(70L, cobble.costOf(2));
    }

    @Test
    void rareProgressionItemsAreNotSold() {
        assertFalse(ShopCatalog.isSellable("ELYTRA"));
        assertFalse(ShopCatalog.isSellable("NETHERITE_INGOT"));
        assertFalse(ShopCatalog.isSellable("TOTEM_OF_UNDYING"));
        assertFalse(ShopCatalog.isSellable("SHULKER_SHELL"));
        assertFalse(ShopCatalog.isSellable("ENCHANTED_GOLDEN_APPLE"));
        assertTrue(ShopCatalog.isSellable("GOLDEN_APPLE"));
    }

    @Test
    void everyCategoryHasOffers() {
        for (ShopCatalog.Category category : ShopCatalog.categories()) {
            assertFalse(ShopCatalog.offers(category).isEmpty(), category.name());
        }
    }

    @Test
    void maxOrdersRespectBalanceAndSpace() {
        ShopCatalog.Offer diamond = ShopCatalog.offer("DIAMOND").orElseThrow();
        assertEquals(0, diamond.maxOrders(249, 64));
        assertEquals(2, diamond.maxOrders(500, 2));
        assertEquals(1, diamond.maxOrders(10_000, 1));
    }
}
