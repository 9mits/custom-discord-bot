package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopCatalogTest {
    @Test
    void shopSellsConvenienceAndExpensiveOresNotFarmMeta() {
        assertEquals(15_000L, ShopCatalog.offer("COBBLESTONE").orElseThrow().price());
        assertEquals(75_000L, ShopCatalog.offer("DIAMOND").orElseThrow().price());
        assertEquals(80_000L, ShopCatalog.offer("SADDLE").orElseThrow().price());
        assertEquals(6_500L, ShopCatalog.offer("WATER_BUCKET").orElseThrow().price());
        assertTrue(ShopCatalog.offer("ENDER_PEARL").isPresent());
        assertFalse(ShopCatalog.offer("BLAZE_ROD").isPresent());
        assertFalse(ShopCatalog.offer("SLIME_BALL").isPresent());
        assertFalse(ShopCatalog.offer("GUNPOWDER").isPresent());
        assertFalse(ShopCatalog.offer("GHAST_TEAR").isPresent());
    }

    @Test
    void sellRejectsAfkPrintersAndBuysPlayLoot() {
        assertEquals(70L, ShopCatalog.sellCredit("WHEAT", 1));
        assertEquals(180L, ShopCatalog.sellCredit("RAW_IRON", 1));
        assertEquals(900L, ShopCatalog.sellCredit("DIAMOND", 1));
        assertFalse(ShopCatalog.isSellable("SUGAR_CANE"));
        assertFalse(ShopCatalog.isSellable("BAMBOO"));
        assertFalse(ShopCatalog.isSellable("KELP"));
        assertFalse(ShopCatalog.isSellable("CACTUS"));
        assertFalse(ShopCatalog.isSellable("COBBLESTONE"));
        assertFalse(ShopCatalog.isSellable("GUNPOWDER"));
        assertFalse(ShopCatalog.isSellable("BLAZE_ROD"));
        assertFalse(ShopCatalog.isSellable("SLIME_BALL"));
    }

    @Test
    void rareProgressionItemsAreNotSoldOrBought() {
        assertFalse(ShopCatalog.offer("ELYTRA").isPresent());
        assertFalse(ShopCatalog.isSellable("NETHERITE_INGOT"));
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
        ShopCatalog.Offer diamond = ShopCatalog.offer("DIAMOND").orElseThrow();
        assertEquals(0, diamond.maxOrders(74_999, 64));
        assertEquals(1, diamond.maxOrders(75_000, 1));
        assertEquals(2, diamond.maxOrders(200_000, 2));
    }
}
