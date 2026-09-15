package bot.mgx.accessbridge;

/**
 * The shape of a Giftbag opening over time, as pure numbers.
 *
 * <p>The opening is told entirely by motion, never by captions: the sealed bag rises out
 * of the ground, the prizes it could hold burst out and circle it faster and faster,
 * they are dragged back inside, the bag swells and shudders, and then it bursts to leave
 * the one prize that was really inside floating in its place.
 *
 * <p>{@code t} is progress through the buildup, 0 at the first tick and 1 at the burst.
 * Free of Bukkit so every phase boundary and curve is unit tested.
 */
final class GiftbagTimeline {
    enum Phase { RISE, ORBIT, CONVERGE, CHARGE }

    static final double RISE_END = 0.14;
    static final double ORBIT_END = 0.60;
    static final double CONVERGE_END = 0.86;
    /** How far the prizes circle from the bag at their widest. */
    static final double ORBIT_RADIUS = 1.7;
    /** Radians per second the ring turns at the start of the orbit, and at its fastest. */
    static final double SLOW_SPIN = 1.3;
    static final double FAST_SPIN = 11.0;
    /** Ticks the reward takes to grow to full size after the burst. */
    static final int REVEAL_GROW_TICKS = 12;

    private GiftbagTimeline() {
    }

    static double progress(int elapsed, int duration) {
        return clamp(elapsed / (double) Math.max(1, duration));
    }

    static Phase phase(double t) {
        if (t < RISE_END) return Phase.RISE;
        if (t < ORBIT_END) return Phase.ORBIT;
        if (t < CONVERGE_END) return Phase.CONVERGE;
        return Phase.CHARGE;
    }

    /** How far through its own phase {@code t} is, 0 to 1. */
    static double within(double t) {
        return switch (phase(t)) {
            case RISE -> t / RISE_END;
            case ORBIT -> (t - RISE_END) / (ORBIT_END - RISE_END);
            case CONVERGE -> (t - ORBIT_END) / (CONVERGE_END - ORBIT_END);
            case CHARGE -> (t - CONVERGE_END) / (1.0 - CONVERGE_END);
        };
    }

    /** The bag pops up with a little overshoot, holds, swells as it fills, then strains. */
    static double bagScale(double t) {
        double local = within(t);
        return switch (phase(t)) {
            case RISE -> 1.2 * easeOutBack(local);
            case ORBIT -> 1.2;
            case CONVERGE -> 1.2 + 0.4 * easeInCubic(local);
            case CHARGE -> 1.6 + 0.12 * Math.sin(local * Math.PI * 9.0) * local;
        };
    }

    /** Blocks below its resting height: the bag climbs out of the floor during the rise. */
    static double bagLift(double t) {
        return phase(t) == Phase.RISE ? -0.9 * (1.0 - easeOutCubic(within(t))) : 0.0;
    }

    /** Radians the bag has turned: a slow drift that winds up with the ring. */
    static double bagSpin(double t, int durationTicks) {
        return orbitAngle(t, 0, 1, durationTicks) * 0.35;
    }

    static double orbitRadius(double t) {
        double local = within(t);
        return switch (phase(t)) {
            case RISE -> 0.0;
            case ORBIT -> ORBIT_RADIUS * easeOutBack(Math.min(1.0, local / 0.3));
            case CONVERGE -> ORBIT_RADIUS * (1.0 - easeInCubic(local));
            case CHARGE -> 0.0;
        };
    }

    /**
     * Where prize {@code index} of {@code count} sits on the ring. The angular speed
     * climbs with the square of progress, so this is its integral rather than speed times
     * time: the ring accelerates smoothly instead of jumping each tick.
     */
    static double orbitAngle(double t, int index, int count, int durationTicks) {
        double seconds = Math.max(1, durationTicks) / 20.0;
        double turned = SLOW_SPIN * seconds * t + (FAST_SPIN - SLOW_SPIN) * seconds * t * t * t / 3.0;
        return turned + Math.PI * 2.0 * index / Math.max(1, count);
    }

    /** Height of a prize above the bag's centre: the ring is tilted and each prize bobs. */
    static double orbitHeight(double t, int index, int count, int durationTicks) {
        double angle = orbitAngle(t, index, count, durationTicks);
        return Math.sin(angle) * orbitRadius(t) * 0.28 + Math.sin(t * 40.0 + index) * 0.08;
    }

    static double orbitScale(double t) {
        double local = within(t);
        return switch (phase(t)) {
            case RISE -> 0.0;
            case ORBIT -> 0.62 * easeOutCubic(Math.min(1.0, local / 0.25));
            case CONVERGE -> 0.62 * (1.0 - 0.8 * easeInCubic(local));
            case CHARGE -> 0.0;
        };
    }

    /** Blocks the bag may jitter from its centre: still until it starts to fill. */
    static double shake(double t) {
        return switch (phase(t)) {
            case RISE, ORBIT -> 0.0;
            case CONVERGE -> 0.05 * within(t);
            case CHARGE -> 0.05 + 0.1 * within(t);
        };
    }

    /** Ticks between the rising chimes, and later the heartbeats: faster as it builds. */
    static int beatPeriod(double t) {
        return Math.max(2, (int) Math.round(14 - 12 * t * t));
    }

    /** The chime climbs a little under two octaves over the buildup. */
    static float chimePitch(double t) {
        return (float) (0.5 + 1.45 * t);
    }

    /** The revealed prize grows with an overshoot, then idles. */
    static double revealScale(int ticksSinceBurst) {
        if (ticksSinceBurst >= REVEAL_GROW_TICKS) return 1.5;
        return 1.5 * easeOutBack(ticksSinceBurst / (double) REVEAL_GROW_TICKS);
    }

    static double easeOutBack(double x) {
        double c1 = 1.70158;
        double c3 = c1 + 1.0;
        double shifted = clamp(x) - 1.0;
        return 1.0 + c3 * shifted * shifted * shifted + c1 * shifted * shifted;
    }

    static double easeOutCubic(double x) {
        double inverse = 1.0 - clamp(x);
        return 1.0 - inverse * inverse * inverse;
    }

    static double easeInCubic(double x) {
        double clamped = clamp(x);
        return clamped * clamped * clamped;
    }

    private static double clamp(double x) {
        return Math.max(0.0, Math.min(1.0, x));
    }
}
