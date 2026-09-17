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
 * {@code grandeur} is how big a win is coming, 0 for the ordinary and 1 for a mythic item:
 * the bag swells further the better the prize, so everyone watching knows something rare is
 * about to land long before it opens.
 *
 * <p>Free of Bukkit so every phase boundary and curve is unit tested.
 */
final class GiftbagTimeline {
    enum Phase { RISE, ORBIT, CONVERGE, CHARGE }

    static final double RISE_END = 0.14;
    static final double ORBIT_END = 0.60;
    static final double CONVERGE_END = 0.86;
    /** How far the prizes circle from the bag at their widest. */
    static final double ORBIT_RADIUS = 4.2;
    /** Radians per second the ring turns at the start of the orbit, and at its fastest. */
    static final double SLOW_SPIN = 1.3;
    static final double FAST_SPIN = 11.0;
    /** Ticks the reward takes to grow to full size after the burst. */
    static final int REVEAL_GROW_TICKS = 12;
    /**
     * The bag's size in blocks, and the point of the whole thing: an ordinary bag ends up
     * about twice a player's height, and a mythic one towers about five times over them.
     * A display's scale is roughly blocks, and a player is 1.8 of them.
     */
    static final double BAG_RISEN = 2.6;
    static final double BAG_FULL = 3.4;
    /** What the best prize multiplies that by: 3.4 x 2.7 is a bag over nine blocks tall. */
    static final double GRANDEUR_GROWTH = 1.7;
    /** How much wider the ring of prizes swings for the best prizes, to clear the bag. */
    static final double GRANDEUR_SPREAD = 0.45;
    /** Blocks below the ground the bag starts, out of sight. */
    static final double BURIED = 3.2;

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

    /**
     * How much of its extra size the bag has taken on by {@code t}. It never jumps: the bag
     * grows the whole way through, so the swell reads as a build rather than a reveal.
     */
    static double swell(double t, double grandeur) {
        return 1.0 + Math.max(0.0, Math.min(1.0, grandeur)) * GRANDEUR_GROWTH * Math.pow(clamp(t), 1.4);
    }

    /** The bag pops up with a little overshoot, holds, swells as it fills, then strains. */
    static double bagScale(double t, double grandeur) {
        return bagScale(t) * swell(t, grandeur);
    }

    /** The bag's own curve, before the prize's grandeur enlarges it. */
    static double bagScale(double t) {
        double local = within(t);
        return switch (phase(t)) {
            case RISE -> BAG_RISEN * easeOutBack(local);
            case ORBIT -> BAG_RISEN;
            case CONVERGE -> BAG_RISEN + (BAG_FULL - BAG_RISEN) * easeInCubic(local);
            case CHARGE -> BAG_FULL + BAG_FULL * 0.075 * Math.sin(local * Math.PI * 9.0) * local;
        };
    }

    /**
     * Height of the bag's centre above the ground it is standing on.
     *
     * <p>A display scales around its own origin, so a bag this size anchored at eye level
     * would have half of itself underground. Lifting it by half its height instead keeps
     * it standing on the floor and growing upwards, which is also what the rise is meant
     * to look like.
     */
    static double bagCentre(double t, double grandeur) {
        return bagScale(t, grandeur) * 0.5 + bagLift(t);
    }

    /** Blocks below its resting height: the bag climbs out of the floor during the rise. */
    static double bagLift(double t) {
        return phase(t) == Phase.RISE ? -BURIED * (1.0 - easeOutCubic(within(t))) : 0.0;
    }

    /** Radians the bag has turned: a slow drift that winds up with the ring. */
    static double bagSpin(double t, int durationTicks) {
        return orbitAngle(t, 0, 1, durationTicks) * 0.35;
    }

    /** The ring swings wider for a better prize, so a big win fills more of the sky. */
    static double orbitRadius(double t, double grandeur) {
        return orbitRadius(t) * (1.0 + Math.max(0.0, Math.min(1.0, grandeur)) * GRANDEUR_SPREAD);
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
    /**
     * Height of one circling prize above the ground: the ring is tilted, each prize bobs,
     * and the whole ring rides at the height of the bag's middle rather than a player's.
     */
    static double orbitHeight(double t, int index, int count, int durationTicks, double grandeur) {
        double angle = orbitAngle(t, index, count, durationTicks);
        return bagCentre(t, grandeur)
                + Math.sin(angle) * orbitRadius(t, grandeur) * 0.28
                + Math.sin(t * 40.0 + index) * 0.16;
    }

    static double orbitScale(double t) {
        double local = within(t);
        // Big enough to read against a bag this size, without competing with it.
        return switch (phase(t)) {
            case RISE -> 0.0;
            case ORBIT -> 1.3 * easeOutCubic(Math.min(1.0, local / 0.25));
            case CONVERGE -> 1.3 * (1.0 - 0.8 * easeInCubic(local));
            case CHARGE -> 0.0;
        };
    }

    /** Blocks the bag may jitter from its centre: still until it starts to fill. */
    static double shake(double t) {
        return switch (phase(t)) {
            case RISE, ORBIT -> 0.0;
            case CONVERGE -> 0.12 * within(t);
            case CHARGE -> 0.12 + 0.25 * within(t);
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

    /** The revealed prize grows with an overshoot, then idles, larger the rarer it is. */
    static double revealScale(int ticksSinceBurst, double grandeur) {
        double full = 2.6 * (1.0 + Math.max(0.0, Math.min(1.0, grandeur)) * 0.5);
        if (ticksSinceBurst >= REVEAL_GROW_TICKS) return full;
        return full * easeOutBack(ticksSinceBurst / (double) REVEAL_GROW_TICKS);
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
