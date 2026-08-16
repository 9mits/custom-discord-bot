package bot.mgx.accessbridge;

/**
 * The overworld spawn is a single block. Vanilla otherwise picks a random
 * safe spot in a radius around it, which is why joins were landing at
 * 0.5/2.5 and similar instead of the point that was set.
 */
final class WorldSpawn {
    static final int X = 0;
    static final int Y = 69;
    static final int Z = 0;
    static final int RADIUS = 0;

    private WorldSpawn() {
    }

    static boolean isExact(int x, int y, int z) {
        return x == X && y == Y && z == Z;
    }
}
