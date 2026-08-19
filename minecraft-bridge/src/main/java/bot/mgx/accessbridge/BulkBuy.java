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
     * Stacks a single purchase will drop on the floor.
     *
     * <p>Each one is an entity that ticks, merges and despawns. This many land in a
     * single tick and then merge down; an order of magnitude more does not.
     */
    static final int MAX_OVERFLOW_STACKS = 256;

    private BulkBuy() {
    }

    /** The most a purchase can hand over: what fits, plus the bounded spill. */
    static int ceiling(int inventorySpace, int stackSize) {
        int stack = stackSize > 0 ? stackSize : StackSplit.DEFAULT_STACK;
        return Math.max(0, inventorySpace) + MAX_OVERFLOW_STACKS * stack;
    }

    /**
     * The most the player can both afford and be given.
     *
     * @return items, never more than {@link #ceiling}, and zero when one is unaffordable
     */
    static int most(long balance, long unitPrice, int inventorySpace, int stackSize) {
        if (unitPrice <= 0L || balance < unitPrice) {
            return 0;
        }
        long affordable = balance / unitPrice;
        return (int) Math.min(ceiling(inventorySpace, stackSize), affordable);
    }

    /** How many of a purchase will not fit and have to be dropped. */
    static int overflow(int items, int inventorySpace) {
        return Math.max(0, items - Math.max(0, inventorySpace));
    }
}
