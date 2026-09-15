package bot.mgx.accessbridge;

/** Inclusive horizontal bounds for the spawn building's no-zombie zone. */
record SpawnMobBarrier(int minX, int maxX, int minZ, int maxZ) {
    SpawnMobBarrier {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("Spawn barrier bounds are reversed");
        }
    }

    boolean contains(double x, double z) {
        return x >= minX && x <= maxX + 1d && z >= minZ && z <= maxZ + 1d;
    }

    boolean enters(double fromX, double fromZ, double toX, double toZ) {
        return !contains(fromX, fromZ) && contains(toX, toZ);
    }

    /** Inside the box and more than {@code band} blocks from every edge. */
    boolean containsBeyondEdge(double x, double z, double band) {
        return x >= minX + band && x <= maxX + 1d - band
                && z >= minZ + band && z <= maxZ + 1d - band;
    }

    /**
     * The closest point just outside the box, {@code clearance} blocks past the nearest
     * edge, keeping the other coordinate. Used to put back a mob that stepped over.
     */
    static double[] nearestOutside(SpawnMobBarrier box, double x, double z, double clearance) {
        double west = x - box.minX();
        double east = box.maxX() + 1d - x;
        double north = z - box.minZ();
        double south = box.maxZ() + 1d - z;
        double nearest = Math.min(Math.min(west, east), Math.min(north, south));
        if (nearest == west) {
            return new double[]{box.minX() - clearance, z};
        }
        if (nearest == east) {
            return new double[]{box.maxX() + 1d + clearance, z};
        }
        if (nearest == north) {
            return new double[]{x, box.minZ() - clearance};
        }
        return new double[]{x, box.maxZ() + 1d + clearance};
    }
}
