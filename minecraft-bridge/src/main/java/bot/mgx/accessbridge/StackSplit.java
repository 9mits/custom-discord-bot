package bot.mgx.accessbridge;

/**
 * Splits a quantity into portions no bigger than one stack.
 *
 * <p>Handing Bukkit a single {@code ItemStack} of 99 and trusting it to sort the
 * stacking out does not hold: whatever it cannot fit comes back as leftover at the
 * size it was given, and {@code dropItemNaturally} drops that as one item entity.
 * Pick it up and 99 of something sits in a slot that holds 64. The shop could hand
 * out up to a full inventory in one purchase, so it hit this the moment anyone bought
 * in bulk with a fullish inventory.
 *
 * <p>Free of Bukkit imports so the arithmetic is unit tested; the caller supplies the
 * stack size for the item in hand.
 */
final class StackSplit {
    /** What Bukkit falls back to when an item reports a nonsense stack size. */
    static final int DEFAULT_STACK = 64;

    private StackSplit() {
    }

    /**
     * @param amount        how many were bought, collected or refunded
     * @param maxStackSize  what one stack of this item holds
     * @return the size of each stack to hand over, largest first, never empty for a
     *         positive amount
     */
    static int[] portions(int amount, int maxStackSize) {
        if (amount <= 0) {
            return new int[0];
        }
        // A zero or negative stack size would loop forever rather than fail loudly.
        int stack = maxStackSize > 0 ? maxStackSize : DEFAULT_STACK;
        int count = (amount + stack - 1) / stack;
        int[] portions = new int[count];
        int remaining = amount;
        for (int index = 0; index < count; index++) {
            portions[index] = Math.min(remaining, stack);
            remaining -= portions[index];
        }
        return portions;
    }
}
