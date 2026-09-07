package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OrderRulesTest {
    @Test
    void anOrderCostsItsWholeValueUpFront() {
        assertEquals(64_000L, OrderRules.escrowFor(64, 1_000L));
        assertEquals(0L, OrderRules.escrowFor(0, 1_000L));
        assertEquals(0L, OrderRules.escrowFor(64, 0L));
    }

    @Test
    void anEscrowThatWouldOverflowIsRefusedRatherThanWrapped() {
        // A negative escrow is an order that pays the buyer to place it.
        assertEquals(0L, OrderRules.escrowFor(Integer.MAX_VALUE, Long.MAX_VALUE));
        assertNotNull(OrderRules.problemWith(
                OrderRules.MAXIMUM_QUANTITY, Long.MAX_VALUE, Long.MAX_VALUE, 0));
    }

    @Test
    void aValidOrderHasNoProblem() {
        assertNull(OrderRules.problemWith(64, 1_000L, 100_000L, 0));
    }

    @Test
    void anOrderIsRefusedWhenTheBuyerCannotCoverIt() {
        String problem = OrderRules.problemWith(64, 1_000L, 63_999L, 0);

        assertNotNull(problem);
        assertTrue(problem.contains("up front"), problem);
    }

    @Test
    void theBoardStaysAMarketRatherThanOnePersonsList() {
        assertNotNull(OrderRules.problemWith(
                1, 1L, Long.MAX_VALUE / 2, OrderRules.MAXIMUM_OPEN_PER_PLAYER));
        assertNull(OrderRules.problemWith(
                1, 1L, Long.MAX_VALUE / 2, OrderRules.MAXIMUM_OPEN_PER_PLAYER - 1));
    }

    @Test
    void nonsenseQuantitiesAndPricesAreRefused() {
        assertNotNull(OrderRules.problemWith(0, 100L, 1_000_000L, 0));
        assertNotNull(OrderRules.problemWith(-5, 100L, 1_000_000L, 0));
        assertNotNull(OrderRules.problemWith(64, 0L, 1_000_000L, 0));
        assertNotNull(OrderRules.problemWith(
                OrderRules.MAXIMUM_QUANTITY + 1, 100L, Long.MAX_VALUE / 2, 0));
        assertNotNull(OrderRules.problemWith(
                64, OrderRules.MAXIMUM_PRICE_EACH + 1, Long.MAX_VALUE / 2, 0));
    }

    @Test
    void aPartialFillTakesOnlyWhatIsStillWanted() {
        // Bringing a double chest to a 10-item order sells ten of them.
        assertEquals(10, OrderRules.fillable(10, 0, 64));
        assertEquals(4, OrderRules.fillable(10, 6, 64));
        assertEquals(6, OrderRules.fillable(10, 0, 6));
        assertEquals(0, OrderRules.fillable(10, 10, 64));
        assertEquals(0, OrderRules.fillable(10, 12, 64));
    }

    @Test
    void thePayoutAndTheRefundNeverExceedWhatWasEscrowed() {
        long escrow = OrderRules.escrowFor(100, 500L);
        long paid = OrderRules.payoutFor(40, 500L);
        long refunded = OrderRules.refundFor(100, 40, 500L);

        // What the sellers were paid plus what goes back to the buyer is exactly
        // what the buyer put in. Any other answer is money created or destroyed.
        assertEquals(escrow, paid + refunded);
    }

    @Test
    void aFullyFilledOrderRefundsNothing() {
        assertEquals(0L, OrderRules.refundFor(64, 64, 1_000L));
        assertTrue(OrderRules.complete(64, 64));
        assertTrue(OrderRules.complete(64, 70));
        assertFalse(OrderRules.complete(64, 63));
    }
}
