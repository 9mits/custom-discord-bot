package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the order screens put their buttons.
 *
 * <p>Paging owns three slots on a double chest and Back owns a fourth, and a button
 * placed on one of them is drawn over by the paging pass and then never reachable,
 * because the click handler tests Back and the arrows first. That is a button that
 * silently does nothing, which is the failure this whole file exists to prevent —
 * the duel screens shipped exactly that bug once already.
 */
final class OrderSlotsTest {
    private static final int BOARD = 54;

    private static Set<Integer> reserved() {
        return Set.of(
                MenuItems.PREVIOUS_SLOT,
                MenuItems.NEXT_SLOT,
                MenuPaging.backSlot(BOARD)
        );
    }

    @Test
    void theBoardsOwnButtonsAreClearOfPagingAndBack() {
        for (int slot : List.of(
                OrderService.CREATE_SLOT,
                OrderService.MINE_SLOT,
                OrderService.REFRESH_SLOT)) {
            assertFalse(reserved().contains(slot),
                    "slot " + slot + " belongs to paging or Back");
            assertTrue(slot >= MenuItems.PER_PAGE && slot < BOARD,
                    "slot " + slot + " sits in the rows the board fills with orders");
        }
    }

    @Test
    void noTwoButtonsShareASlotOnTheCreateScreen() {
        List<Integer> used = new ArrayList<>();
        for (int slot : OrderService.QUANTITY_ADD_SLOTS) used.add(slot);
        for (int slot : OrderService.QUANTITY_TAKE_SLOTS) used.add(slot);
        for (int slot : OrderService.PRICE_ADD_SLOTS) used.add(slot);
        for (int slot : OrderService.PRICE_TAKE_SLOTS) used.add(slot);
        used.add(OrderService.PREVIEW_SLOT);
        used.add(OrderService.CONFIRM_SLOT);
        used.add(OrderService.STACK_SLOT);
        used.add(MenuPaging.backSlot(BOARD));

        assertEquals(used.size(), new HashSet<>(used).size(),
                "two create-screen buttons share a slot: " + used);
        for (int slot : used) {
            assertTrue(slot >= 0 && slot < BOARD, "slot " + slot + " is off the board");
        }
    }

    @Test
    void everyStepHasAButtonAtBothEnds() {
        assertEquals(OrderService.QUANTITY_STEPS.length,
                OrderService.QUANTITY_ADD_SLOTS.length);
        assertEquals(OrderService.QUANTITY_STEPS.length,
                OrderService.QUANTITY_TAKE_SLOTS.length);
        assertEquals(OrderService.PRICE_STEPS.length,
                OrderService.PRICE_ADD_SLOTS.length);
        assertEquals(OrderService.PRICE_STEPS.length,
                OrderService.PRICE_TAKE_SLOTS.length);
    }

    @Test
    void theStepsReachTheLimitsWithoutAnAbsurdNumberOfClicks() {
        long biggestQuantityStep = 0;
        for (int step : OrderService.QUANTITY_STEPS) {
            biggestQuantityStep = Math.max(biggestQuantityStep, step);
        }
        long biggestPriceStep = 0;
        for (long step : OrderService.PRICE_STEPS) {
            biggestPriceStep = Math.max(biggestPriceStep, step);
        }

        // Nobody is clicking a hundred times to reach the cap the rules allow.
        assertTrue(OrderRules.MAXIMUM_QUANTITY / biggestQuantityStep <= 40);
        assertTrue(OrderRules.MAXIMUM_PRICE_EACH / biggestPriceStep <= 40);
    }
}
