package bot.mgx.accessbridge;

/**
 * How far the world is allowed to go, and which spawn chunks must stay loaded so
 * a death far away can still respawn.
 *
 * <p>The red vignette, fog and ambient sounds vanilla plays when you walk far
 * enough are the world-border warning, not a corrupted world. The playable
 * radius is {@link #OVERWORLD_RADIUS} blocks from spawn; the nether is scaled
 * 8:1 so a portal cannot dump someone past the overworld edge.
 *
 * <p>Zero for a configured radius means "leave the panel value alone".
 */
final class WorldLimits {
    static final double OVERWORLD_RADIUS = 100_000;
    static final double NETHER_SCALE = 8;
    static final int WARNING_DISTANCE = 100;
    static final int SPAWN_TICKET_RADIUS = 1;

    private WorldLimits() {
    }

    static double diameter(boolean nether, double overworldRadius) {
        double radius = overworldRadius <= 0 ? OVERWORLD_RADIUS : overworldRadius;
        if (nether) {
            return (radius / NETHER_SCALE) * 2;
        }
        return radius * 2;
    }

    static int spawnChunkX() {
        return WorldSpawn.X >> 4;
    }

    static int spawnChunkZ() {
        return WorldSpawn.Z >> 4;
    }
}
