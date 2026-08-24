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
}
