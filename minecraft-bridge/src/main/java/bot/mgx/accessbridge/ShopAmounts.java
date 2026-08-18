package bot.mgx.accessbridge;

/**
 * The amounts a plain shop click can buy, and how the button cycles them.
 *
 * <p>Exists because Geyser does not hand the server a right or shift click from a
 * Bedrock container: an amount chosen by modifier alone is a Java-only feature, and a
 * Bedrock player stuck on one item per click cannot use the shop. A button that says
 * the amount out loud works on both editions.
 *
 * <p>Free of Bukkit imports so the wrap-around is unit tested — an amount that is not
 * on the list has to land somewhere sensible rather than stick.
 */
final class ShopAmounts {
    static final int[] STEPS = {1, 8, 16, 32, 64};

    private ShopAmounts() {
    }

    static int first() {
        return STEPS[0];
    }

    static int next(int current) {
        for (int index = 0; index < STEPS.length; index++) {
            if (STEPS[index] == current) {
                return STEPS[(index + 1) % STEPS.length];
            }
        }
        return first();
    }

    static String label() {
        StringBuilder text = new StringBuilder();
        for (int step : STEPS) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(step);
        }
        return text.toString();
    }
}
