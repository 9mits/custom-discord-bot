package bot.mgx.accessbridge;

/** Pure login-gate decisions shared by Java and Floodgate event paths. */
final class VerificationGate {
    /** Immediate Java kick plus delayed retries after Geyser finishes spawning. */
    static final long[] JOIN_KICK_TICKS = {0L, 5L, 20L, 60L};

    private VerificationGate() {
    }

    static boolean isRefusable(boolean allowed, boolean whitelistKick) {
        return allowed || whitelistKick;
    }

    static boolean shouldRefuse(boolean allowed, boolean whitelistKick, boolean verified) {
        return isRefusable(allowed, whitelistKick) && !verified;
    }
}
