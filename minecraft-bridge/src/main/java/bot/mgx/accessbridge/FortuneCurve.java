package bot.mgx.accessbridge;

/**
 * How much of a beyond-vanilla Fortune level actually pays out.
 *
 * <p>Vanilla rolls an ore's drop count uniformly over {@code 1, 1, 2, … level + 1}, so
 * the expected multiplier climbs slowly: 2.20x at Fortune III, 3.14x at Fortune V. The
 * crate sells IV and V, and the plugin keeps half of what each level adds beyond the
 * vanilla ceiling — the book is still the best pickaxe on the server without turning one
 * vein into a stack.
 *
 * <p>Free of Bukkit imports so the arithmetic is unit tested; the caller trims the drops.
 */
final class FortuneCurve {
    /** The highest level vanilla hands out, and the point the discount starts from. */
    static final int VANILLA_MAX = 3;
    /** How much of each level above {@link #VANILLA_MAX} survives. */
    static final double KEPT_SHARE = 0.5d;

    private FortuneCurve() {
    }

    /** Vanilla's expected drop multiplier at this Fortune level. */
    static double expected(int level) {
        if (level <= 0) {
            return 1d;
        }
        return (1d + (level + 1) * (level + 2) / 2d) / (level + 2);
    }

    /**
     * The share of drops to keep so that {@code level} pays half of what it adds over
     * Fortune III. Levels at or below the vanilla ceiling are untouched.
     */
    static double keptShare(int level) {
        if (level <= VANILLA_MAX) {
            return 1d;
        }
        double vanilla = expected(VANILLA_MAX);
        double raw = expected(level);
        return (vanilla + (raw - vanilla) * KEPT_SHARE) / raw;
    }
}
