package bot.mgx.accessbridge;

/**
 * Caps each world's player-driven chunk load.
 *
 * <p>View and simulation distance are the usual 1.21 RAM cost. Zero for a cap
 * means "do not change what the panel already set".
 */
final class WorldMemory {
    static final int MAX_VIEW_DISTANCE = 6;
    static final int MAX_SIMULATION_DISTANCE = 4;
    static final int MIN_DISTANCE = 2;
    static final int ABSOLUTE_MAX_DISTANCE = 32;

    private WorldMemory() {
    }

    /**
     * Lowers {@code current} to {@code max} when the world is above the cap.
     * Values outside {@code 2..32} are treated as "leave it alone".
     */
    static int capDistance(int current, int max) {
        if (max < MIN_DISTANCE || max > ABSOLUTE_MAX_DISTANCE) {
            return current;
        }
        if (current <= max) {
            return current;
        }
        return max;
    }

    /** Simulation distance cannot exceed the view distance Paper will actually use. */
    static int capSimulation(int current, int max, int viewDistance) {
        int ceiling = max < MIN_DISTANCE || max > ABSOLUTE_MAX_DISTANCE
                ? viewDistance
                : Math.min(max, viewDistance);
        return capDistance(current, ceiling);
    }

    /** The launch scan must not leave a chunk it was the one to load. */
    static boolean shouldUnloadScannedChunk(boolean wasAlreadyLoaded) {
        return !wasAlreadyLoaded;
    }
}
