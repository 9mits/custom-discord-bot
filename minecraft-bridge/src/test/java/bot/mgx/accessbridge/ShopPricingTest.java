package bot.mgx.accessbridge;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shop prices as multipliers.
 *
 * <p>408 offers and as many sell quotes. One setting each would have roughly doubled the
 * registry and buried the values anyone actually wants, so pricing scales instead.
 */
final class ShopPricingTest {
    @TempDir
    Path temporary;

    @AfterEach
    void restoreDefaults() {
        // Static source, so a test that left it moved would price every later one.
        ShopCatalog.multiplierSource(key -> 100);
    }

    private GameVariableStore store() throws Exception {
        GameVariableStore variables = new GameVariableStore(
                temporary.resolve("game-variables.json"), new YamlConfiguration());
        ShopCatalog.multiplierSource(variables::integer);
        return variables;
    }

    @Test
    void catalogueePricesStandWhenNothingIsChanged() throws Exception {
        store();
        assertEquals(100, ShopCatalog.buyPercent(ShopCatalog.Category.STONE));
        assertEquals(100, ShopCatalog.sellPercent());
    }

    @Test
    void aShopWideFigureMovesEveryShelf() throws Exception {
        GameVariableStore variables = store();
        long before = ShopCatalog.offers(ShopCatalog.Category.STONE).get(0).price();
        variables.set("shop.buy-percent", "200");
        long after = ShopCatalog.offers(ShopCatalog.Category.STONE).get(0).price();
        assertEquals(before * 2, after);
    }

    @Test
    void aShelfFigureStacksWithTheShopWideOne() throws Exception {
        GameVariableStore variables = store();
        long listed = ShopCatalog.offers(ShopCatalog.Category.STONE).get(0).price();
        variables.set("shop.buy-percent", "200");
        variables.set("shop.category.stone.buy-percent", "50");
        // Half of double is the catalogue price.
        assertEquals(listed, ShopCatalog.offers(ShopCatalog.Category.STONE).get(0).price());
        // Another shelf is untouched by the stone figure.
        assertEquals(200, ShopCatalog.buyPercent(ShopCatalog.Category.WOOD));
    }

    @Test
    void sellingScalesSeparatelyFromBuying() throws Exception {
        GameVariableStore variables = store();
        String material = ShopCatalog.offers(ShopCatalog.Category.STONE).get(0).material();
        variables.set("shop.buy-percent", "500");
        long credit = ShopCatalog.sellCredit(material, 1);
        variables.set("shop.sell-percent", "300");
        assertTrue(ShopCatalog.sellQuote(material).isEmpty()
                        || ShopCatalog.sellCredit(material, 1) >= credit,
                "raising the sell figure must not lower what selling pays");
    }

    /** A shelf at nothing would hand out stacks rather than close. */
    @Test
    void aPriceNeverFallsBelowOne() throws Exception {
        GameVariableStore variables = store();
        variables.set("shop.buy-percent", "1");
        variables.set("shop.category.stone.buy-percent", "1");
        for (ShopCatalog.Offer offer : ShopCatalog.offers(ShopCatalog.Category.STONE)) {
            assertTrue(offer.price() >= 1L, offer.material() + " became free");
        }
    }

    @Test
    void lookingUpOneItemAgreesWithItsShelf() throws Exception {
        GameVariableStore variables = store();
        variables.set("shop.category.stone.buy-percent", "250");
        ShopCatalog.Offer fromShelf = ShopCatalog.offers(ShopCatalog.Category.STONE).get(0);
        ShopCatalog.Offer direct = ShopCatalog.offer(fromShelf.material()).orElseThrow();
        assertEquals(fromShelf.price(), direct.price(),
                "the shelf and the single-item lookup must not disagree on price");
    }
}
