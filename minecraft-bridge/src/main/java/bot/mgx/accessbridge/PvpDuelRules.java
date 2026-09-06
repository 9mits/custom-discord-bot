package bot.mgx.accessbridge;

/** Pure rules shared by PvP location search, wager settlement, and tests. */
final class PvpDuelRules {
    private PvpDuelRules() {
    }

    /** After winning both deposits, a wallet ends one wager above where it began. */
    static boolean canReceivePool(long balanceBefore, long wagerEach) {
        if (balanceBefore < 0L || wagerEach < 0L) {
            return false;
        }
        try {
            Math.addExact(balanceBefore, wagerEach);
            return true;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    static boolean inside(double x, double z, double centerX, double centerZ, double diameter) {
        double half = Math.max(1d, diameter / 2d);
        return Math.abs(x - centerX) < half && Math.abs(z - centerZ) < half;
    }

    /** Uniform by area, matching RTP rather than over-filling the centre. */
    static double radius(double minimum, double maximum, double sample) {
        double clamped = Math.max(0d, Math.min(Math.nextDown(1d), sample));
        return Math.sqrt(minimum * minimum
                + clamped * (maximum * maximum - minimum * minimum));
    }

    static boolean separated(
            double x, double z, double otherX, double otherZ, double minimumDistance
    ) {
        double dx = x - otherX;
        double dz = z - otherZ;
        return dx * dx + dz * dz >= minimumDistance * minimumDistance;
    }
}
