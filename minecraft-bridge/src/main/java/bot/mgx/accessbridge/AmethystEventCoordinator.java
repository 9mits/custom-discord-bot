package bot.mgx.accessbridge;

import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Owns the one shared Amethyst world-event slot. Successful events alternate,
 * giving Airdrops and Huge Blocks equal frequency, and the cooldown starts only
 * after cleanup so one cannot follow the other immediately.
 */
final class AmethystEventCoordinator {
    private static final long RETRY_MILLIS = Duration.ofMinutes(5).toMillis();

    private enum Kind {
        AIRDROP,
        HUGE_BLOCK;

        Kind next() {
            return this == AIRDROP ? HUGE_BLOCK : AIRDROP;
        }
    }

    private final MGXAccessBridge plugin;
    private final AirdropService airdrops;
    private final AmethystBlockEventService blocks;
    private final RandomGenerator random;
    private final long minimumDelayMillis;
    private final long maximumDelayMillis;
    private Kind next;
    private BukkitTask task;
    private boolean reserved;
    private boolean stopped = true;

    AmethystEventCoordinator(
            MGXAccessBridge plugin, AirdropService airdrops,
            AmethystBlockEventService blocks
    ) {
        this(plugin, airdrops, blocks, ThreadLocalRandom.current());
    }

    AmethystEventCoordinator(
            MGXAccessBridge plugin, AirdropService airdrops,
            AmethystBlockEventService blocks, RandomGenerator random
    ) {
        this.plugin = plugin;
        this.airdrops = airdrops;
        this.blocks = blocks;
        this.random = random;
        long configuredMinimum = minutes("amethyst-events.minimum-delay-minutes",
                plugin.getConfig().getLong("airdrop.minimum-delay-minutes", 30L));
        long configuredMaximum = minutes("amethyst-events.maximum-delay-minutes",
                plugin.getConfig().getLong("airdrop.maximum-delay-minutes", 90L));
        minimumDelayMillis = Math.min(configuredMinimum, configuredMaximum);
        maximumDelayMillis = Math.max(configuredMinimum, configuredMaximum);
        next = random.nextBoolean() ? Kind.AIRDROP : Kind.HUGE_BLOCK;
        airdrops.blockWhile(blocks::isActiveOrSpawning);
        blocks.blockWhile(airdrops::isActiveOrSpawning);
    }

    void start() {
        stop();
        stopped = false;
        airdrops.start();
        blocks.start();
        scheduleCooldown();
    }

    void stop() {
        stopped = true;
        reserved = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
        airdrops.stop();
        blocks.stop();
    }

    private long minutes(String path, long fallback) {
        return Duration.ofMinutes(Math.clamp(
                plugin.getConfig().getLong(path, fallback), 1L, 1_440L
        )).toMillis();
    }

    private void scheduleCooldown() {
        long delay = AirdropService.randomDelayMillis(
                random, minimumDelayMillis, maximumDelayMillis
        );
        schedule(hastened(delay), this::tryStart);
    }

    /**
     * A 2x Airdrop or 2x Amethyst Block event halves the wait rather than doubling the
     * loot: these are world events, so "twice as much" can only mean twice as often. One
     * cooldown feeds both, so the strongest event running wins instead of the two
     * compounding into a quarter of the wait.
     */
    long hastened(long delay) {
        int factor = Math.max(
                plugin.serverEventMultiplier(ServerEventType.AIRDROP),
                plugin.serverEventMultiplier(ServerEventType.AMETHYST_BLOCK)
        );
        return factor <= 1 ? delay : Math.max(1L, delay / factor);
    }

    private void schedule(long millis, Runnable action) {
        if (stopped) {
            return;
        }
        if (task != null) {
            task.cancel();
        }
        task = plugin.getServer().getScheduler().runTaskLater(
                plugin, () -> {
                    task = null;
                    action.run();
                }, Math.max(1L, millis / 50L)
        );
    }

    private void tryStart() {
        if (stopped || reserved) {
            return;
        }
        reserved = true;
        Kind requested = next;
        boolean accepted = switch (requested) {
            case AIRDROP -> airdrops.beginScheduled(
                    () -> onSpawned(requested), this::onFinished, this::onFailed
            );
            case HUGE_BLOCK -> blocks.beginScheduled(
                    () -> onSpawned(requested), this::onFinished, this::onFailed
            );
        };
        if (!accepted) {
            reserved = false;
            schedule(RETRY_MILLIS, this::tryStart);
        }
    }

    private void onSpawned(Kind spawned) {
        next = spawned.next();
    }

    private void onFinished() {
        reserved = false;
        scheduleCooldown();
    }

    private void onFailed() {
        reserved = false;
        schedule(RETRY_MILLIS, this::tryStart);
    }
}
