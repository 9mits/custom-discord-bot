package bot.mgx.accessbridge;

/**
 * The arithmetic behind a buy order, kept away from Bukkit so it can be tested.
 *
 * <p>An order is the auction house pointing the other way. The auction house lets
 * somebody who <em>has</em> a thing find somebody with money; this lets somebody with
 * money find whoever is willing to go and get the thing. On a server where three
 * wallets hold nearly half the currency and the median wallet holds almost none,
 * that direction is the one that matters — the money is already sitting still, and
 * what is missing is a way to pay somebody to do something with it.
 *
 * <p>The whole amount is taken from the buyer when the order is placed and held
 * against it. Nobody hands over a stack for a promise: a filled order pays out of
 * money that was removed from the buyer's wallet before the order was ever visible.
 */
final class OrderRules {
    /** Most an order may ask for. Two shulker boxes' worth is already a big ask. */
    static final int MAXIMUM_QUANTITY = 2_304;
    /** Most one item may be worth, so a typo cannot escrow a fortune. */
    static final long MAXIMUM_PRICE_EACH = 10_000_000L;
    /** Open orders per player, so the board stays a market and not one person's list. */
    static final int MAXIMUM_OPEN_PER_PLAYER = 5;
    /** How long an unfilled order stays up before the rest of the money goes back. */
    static final long LIFETIME_MILLIS = 7L * 24L * 60L * 60L * 1_000L;

    private OrderRules() {
    }

    /**
     * What placing this order costs, or a message explaining why it cannot be placed.
     *
     * <p>Returns the problem rather than throwing, because every caller wants to put
     * the reason back on the screen the player is looking at.
     */
    static String problemWith(int quantity, long priceEach, long balance, int openOrders) {
        if (quantity <= 0) {
            return "Order at least one item.";
        }
        if (quantity > MAXIMUM_QUANTITY) {
            return "An order cannot ask for more than " + MAXIMUM_QUANTITY + " items.";
        }
        if (priceEach <= 0L) {
            return "Offer at least $1 each.";
        }
        if (priceEach > MAXIMUM_PRICE_EACH) {
            return "The most you may offer is "
                    + EconomyFormat.dollars(MAXIMUM_PRICE_EACH) + " each.";
        }
        if (openOrders >= MAXIMUM_OPEN_PER_PLAYER) {
            return "You already have " + MAXIMUM_OPEN_PER_PLAYER
                    + " orders up. Cancel one first.";
        }
        long total = escrowFor(quantity, priceEach);
        if (total <= 0L) {
            return "That order is too large to pay for.";
        }
        if (balance < total) {
            return "That order costs " + EconomyFormat.dollars(total)
                    + " up front and you have " + EconomyFormat.dollars(balance) + ".";
        }
        return null;
    }

    /**
     * The whole cost of an order, or 0 when it would overflow.
     *
     * <p>Both figures are capped well below the point where the product could wrap,
     * but the check stays: an escrow that silently became negative would be an order
     * that paid the buyer to place it.
     */
    static long escrowFor(int quantity, long priceEach) {
        if (quantity <= 0 || priceEach <= 0L) {
            return 0L;
        }
        try {
            return Math.multiplyExact((long) quantity, priceEach);
        } catch (ArithmeticException overflow) {
            return 0L;
        }
    }

    /** How many of an offered stack this order can still take. */
    static int fillable(int requested, int filled, int offered) {
        return Math.max(0, Math.min(offered, requested - filled));
    }

    /** What a partial fill pays the person who brought the items. */
    static long payoutFor(int accepted, long priceEach) {
        return escrowFor(accepted, priceEach);
    }

    /** What goes back to the buyer when an order is cancelled or runs out of time. */
    static long refundFor(int requested, int filled, long priceEach) {
        return escrowFor(Math.max(0, requested - filled), priceEach);
    }

    static boolean complete(int requested, int filled) {
        return filled >= requested;
    }
}
