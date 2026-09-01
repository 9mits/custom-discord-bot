package bot.mgx.accessbridge;

/**
 * Who an operator event is allowed to touch.
 *
 * <p>Free of Bukkit so the rules can be unit tested. Three exclusions, each
 * there because of something a player could be in the middle of:
 *
 * <ul>
 *   <li><b>Distance.</b> Events run at spawn. Somebody mining or farming
 *       thousands of blocks away should never learn one happened.</li>
 *   <li><b>AFK.</b> An AFK chamber is a player parked in an exact spot, often
 *       facing an exact way. Moving, resizing or spinning them breaks the build
 *       they set up, and they are not there to notice or recover.</li>
 *   <li><b>Vehicles.</b> Only for effects that physically move somebody:
 *       launching or teleporting a rider ejects them, which strands a minecart
 *       or boat and can drop them somewhere they never chose to be.</li>
 * </ul>
 */
final class ChaosTargeting {
    /** Radius used when the operator does not name one. */
    static final double DEFAULT_RADIUS = 64.0d;
    static final double MINIMUM_RADIUS = 4.0d;
    static final double MAXIMUM_RADIUS = 256.0d;
    /** Live tuning; the constants above stay the defaults and stand alone in tests. */
    private static volatile java.util.function.ToDoubleFunction<String> tuning = key -> Double.NaN;

    static void tuningSource(java.util.function.ToDoubleFunction<String> source) {
        if (source != null) {
            tuning = source;
        }
    }

    private static double tuned(String key, double fallback) {
        double value = tuning.applyAsDouble(key);
        return Double.isNaN(value) ? fallback : value;
    }


    private ChaosTargeting() {
    }

    /**
     * @param sameWorld       whether the player shares the operator's world
     * @param distanceSquared squared distance from the event's anchor
     * @param radius          the event's reach
     * @param afk             whether the player is marked AFK
     * @param inVehicle       whether the player is riding something
     * @param physical        whether this effect moves players about
     */
    static boolean eligible(
            boolean sameWorld,
            double distanceSquared,
            double radius,
            boolean afk,
            boolean inVehicle,
            boolean physical
    ) {
        if (!sameWorld || afk) {
            return false;
        }
        if (physical && inVehicle) {
            return false;
        }
        return distanceSquared <= radius * radius;
    }

    /**
     * Clamps a requested radius.
     *
     * @throws IllegalArgumentException if the operator asked for something outside the rail
     */
    static double radiusOrThrow(String requested, double fallback) {
        if (requested == null || requested.isBlank()) {
            return clamp(fallback);
        }
        double value;
        try {
            value = Double.parseDouble(requested.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Radius must be a number of blocks.");
        }
        if (value < tuned("admin-events.minimum-radius", MINIMUM_RADIUS) || value > tuned("admin-events.maximum-radius", MAXIMUM_RADIUS)) {
            throw new IllegalArgumentException(
                    "Radius must be between " + (int) tuned("admin-events.minimum-radius", MINIMUM_RADIUS)
                            + " and " + (int) tuned("admin-events.maximum-radius", MAXIMUM_RADIUS) + " blocks."
            );
        }
        return value;
    }

    /** Keeps a configured default inside the rail even if the config is wrong. */
    static double clamp(double value) {
        return Math.min(tuned("admin-events.maximum-radius", MAXIMUM_RADIUS), Math.max(tuned("admin-events.minimum-radius", MINIMUM_RADIUS), value));
    }
}
