package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopAmountsTest {
    @Test
    void theButtonStartsAtOne() {
        assertEquals(1, ShopAmounts.first());
    }

    @Test
    void clickingCyclesUpAndWrapsBackToOne() {
        assertEquals(8, ShopAmounts.next(1));
        assertEquals(16, ShopAmounts.next(8));
        assertEquals(32, ShopAmounts.next(16));
        assertEquals(64, ShopAmounts.next(32));
        assertEquals(1, ShopAmounts.next(64));
    }

    @Test
    void anAmountThatIsNotOnTheListFallsBackRatherThanSticking() {
        // A value left over from a different set of steps must still move on the next
        // click; returning it unchanged would freeze the button for that player.
        assertEquals(1, ShopAmounts.next(7));
        assertEquals(1, ShopAmounts.next(0));
        assertEquals(1, ShopAmounts.next(-5));
    }

    @Test
    void everyStepIsReachableFromEveryOther() {
        int amount = ShopAmounts.first();
        for (int step = 0; step < ShopAmounts.STEPS.length; step++) {
            amount = ShopAmounts.next(amount);
        }
        assertEquals(ShopAmounts.first(), amount);
    }

    @Test
    void theLabelNamesEveryStepInOrder() {
        assertEquals("1, 8, 16, 32, 64", ShopAmounts.label());
        assertTrue(ShopAmounts.STEPS.length > 1);
    }
}
