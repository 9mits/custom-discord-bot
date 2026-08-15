package bot.mgx.accessbridge;

/**
 * When the maintenance hold must refuse a login, and how the join kick is timed.
 *
 * <p>Free of Bukkit so the contract can be unit tested. The interesting part is
 * not the boolean — it is which events Paper and Floodgate actually consult.
 *
 * <p>Floodgate's 1.21 login path calls {@code AsyncPlayerPreLoginEvent} and then
 * starts client verification. {@code PlayerLoginEvent.disallow} is what Java
 * honours; Geyser can ignore it and spawn anyway. A kick issued inside
 * {@code PlayerJoinEvent} is dropped for Bedrock because the client is still
 * completing spawn — hence the delayed retries, not a single immediate kick.
 */
final class MaintenanceGate {
    /**
     * Tick delays for the join kick, including zero for the immediate attempt.
     *
     * <p>Java leaves on the first one. Bedrock needs the later ticks, after
     * Geyser has finished transferring the client into the world.
     */
    static final long[] JOIN_KICK_TICKS = {0L, 1L, 5L, 20L, 40L};

    private MaintenanceGate() {
    }

    static boolean shouldRefuse(boolean held, boolean bypass) {
        return held && !bypass;
    }

    /**
     * Logins we are willing to rewrite. A ban or a full server already carries a
     * better message, and those players are not getting in either way.
     */
    static boolean isRefusable(boolean allowed, boolean whitelistKick) {
        return allowed || whitelistKick;
    }
}
