package bot.mgx.accessbridge;

/**
 * The repeat settings for a standing shop order.
 *
 * <p>Buying a farm's worth of bone meal in one go still runs out. An autobuy keeps
 * buying the same amount on a timer, so a hopper platform stays fed without anybody
 * going back to the shop — which is the thing the one-big-order button was really
 * being used to fake.
 *
 * <p>Free of Bukkit imports so the timing is unit tested.
 */
final class AutoBuy {
    /**
     * Item entities allowed near the player before a dropping order waits.
     *
     * <p>What keeps a standing order safe however long it runs: more is only put down
     * once the hoppers have taken what is already there, so the floor cannot fill.
     */
    static final int GROUND_LIMIT = 64;

    /** How often a standing order can repeat. */
    static final int[] INTERVAL_SECONDS = {1, 2, 5, 10, 30};

    private AutoBuy() {
    }

    static int firstInterval() {
        return INTERVAL_SECONDS[0];
    }

    /** Cycles the interval button, wrapping round. */
    static int nextInterval(int current) {
        for (int index = 0; index < INTERVAL_SECONDS.length; index++) {
            if (INTERVAL_SECONDS[index] == current) {
                return INTERVAL_SECONDS[(index + 1) % INTERVAL_SECONDS.length];
            }
        }
        return firstInterval();
    }

    /**
     * Whether a standing order is due again.
     *
     * <p>Compares against the last run rather than counting down, so a server that
     * skipped ticks catches up on the next one instead of drifting further behind
     * every lag spike.
     */
    static boolean due(long nowTicks, long lastRunTicks, int intervalSeconds) {
        if (intervalSeconds <= 0) {
            return false;
        }
        return nowTicks - lastRunTicks >= 20L * intervalSeconds;
    }

    /** Whether the balance still covers one more repeat. */
    static boolean affordable(long balance, long unitPrice, int quantity) {
        if (unitPrice <= 0L || quantity <= 0) {
            return false;
        }
        return balance >= unitPrice * (long) quantity;
    }
}
