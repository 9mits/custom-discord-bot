package bot.mgx.accessbridge;

/**
 * How much one purchase may hand over at once.
 *
 * <p>Money is not the limit here, the world is. Bone meal costs three dollars an
 * item, so seventeen million buys 5.6 million of them — 88,000 stacks, against an
 * inventory that holds thirty-six. Everything past that has to become item entities
 * on the ground, where a few hundred in one place already costs tick time and all of
 * them despawn after five minutes anyway. Handing that over is not generosity, it is
 * a server crash followed by the items evaporating.
 *
 * <p>So a purchase fills the inventory and spills a bounded number of stacks at the
 * player's feet. Whatever they cannot take is simply not charged for, and the buy
 * screen says the real figure before anything is spent.
 *
 * <p>Free of Bukkit imports so the arithmetic is unit tested.
 */
final class BulkBuy {
    /**
     * Stacks one order may still owe after the inventory is full.
     *
     * <p>Not a tick-safety figure any more — the trickle handles that. This bounds how
     * long a player is committed to standing still: twenty thousand stacks is about
     * twenty minutes of delivery, and roughly four million dollars of bone meal.
     */
    static final int MAX_ORDER_STACKS = 20_000;

    /** Stacks released per tick when the ground is clear. Eighty a second. */
    static final int STACKS_PER_TICK = 4;

    /**
     * Item entities allowed near the player before the delivery waits.
     *
     * <p>This is what makes the order safe at any size: the floor can never fill,
     * because more is only released once what is already down has been picked up.
     */
    static final int GROUND_LIMIT = 64;

    private BulkBuy() {
    }

    /** The most one order can hand over: what fits, plus what may be delivered. */
    static int ceiling(int inventorySpace, int stackSize) {
        int stack = stackSize > 0 ? stackSize : StackSplit.DEFAULT_STACK;
        return Math.max(0, inventorySpace) + MAX_ORDER_STACKS * stack;
    }

    /** How many of a purchase will not fit and have to be delivered. */
    static int overflow(int items, int inventorySpace) {
        return Math.max(0, items - Math.max(0, inventorySpace));
    }

    /**
     * How many items to release this tick.
     *
     * @param remaining    items the order still owes
     * @param stackSize    what one stack of this item holds
     * @param itemsOnGround item entities already near the player
     * @return items to drop now, or zero while the ground is still busy
     */
    static int releaseThisTick(int remaining, int stackSize, int itemsOnGround) {
        if (remaining <= 0 || itemsOnGround >= GROUND_LIMIT) {
            return 0;
        }
        int stack = stackSize > 0 ? stackSize : StackSplit.DEFAULT_STACK;
        return Math.min(remaining, STACKS_PER_TICK * stack);
    }

    /** What to give back for an order that could not be finished. */
    static long refundFor(int remaining, long unitPrice) {
        if (remaining <= 0 || unitPrice <= 0L) {
            return 0L;
        }
        return unitPrice * remaining;
    }

    /** Roughly how long an order of this size takes to arrive, in seconds. */
    static int deliverySeconds(int remaining, int stackSize) {
        if (remaining <= 0) {
            return 0;
        }
        int stack = stackSize > 0 ? stackSize : StackSplit.DEFAULT_STACK;
        int ticks = (remaining + STACKS_PER_TICK * stack - 1) / (STACKS_PER_TICK * stack);
        return Math.max(1, (ticks + 19) / 20);
    }
}
