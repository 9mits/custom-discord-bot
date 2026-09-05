package bot.mgx.accessbridge;

import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Owns the one shared Amethyst world-event slot. Successful events alternate,
 * giving Airdrops and Huge Blocks equal frequency, and the cooldown starts only
 * after cleanup so one cannot follow the other immediately.
 */
final class AmethystEventCoordinator {
    private static final long RETRY_MILLIS = Duration.ofMinutes(5).toMillis();

    enum Kind {
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
    private final GameVariableStore variables;
    private Kind next;
    private BukkitTask task;
    private boolean reserved;
    private boolean stopped = true;

    AmethystEventCoordinator(
            MGXAccessBridge plugin, AirdropService airdrops,
            AmethystBlockEventService blocks, GameVariableStore variables
    ) {
        this(plugin, airdrops, blocks, variables, ThreadLocalRandom.current());
    }

    AmethystEventCoordinator(
            MGXAccessBridge plugin, AirdropService airdrops,
            AmethystBlockEventService blocks, GameVariableStore variables,
            RandomGenerator random
    ) {
        this.plugin = plugin;
        this.airdrops = airdrops;
        this.blocks = blocks;
        this.variables = variables;
        this.random = random;
        next = random.nextBoolean() ? Kind.AIRDROP : Kind.HUGE_BLOCK;
        airdrops.blockWhile(blocks::isActiveOrSpawning);
        blocks.blockWhile(airdrops::isActiveOrSpawning);
        variables.onChange(key -> {
            if (key.startsWith("amethyst-events.")) rescheduleCooldown();
        });
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
        long configuredMinimum = Duration.ofMinutes(
                variables.integer("amethyst-events.minimum-delay-minutes")
        ).toMillis();
        long configuredMaximum = Duration.ofMinutes(
                variables.integer("amethyst-events.maximum-delay-minutes")
        ).toMillis();
        long minimumDelayMillis = Math.min(configuredMinimum, configuredMaximum);
        long maximumDelayMillis = Math.max(configuredMinimum, configuredMaximum);
        long delay = AirdropService.randomDelayMillis(
                random, minimumDelayMillis, maximumDelayMillis
        );
        schedule(hastened(delay), this::tryStart);
    }

    private void rescheduleCooldown() {
        if (!stopped && !reserved) scheduleCooldown();
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
        double boost = lowActivityBoostActive()
                ? variables.decimal("low-activity-boost.spawn-multiplier") : 1d;
        return Math.max(1L, Math.round(delay / Math.max(1d, factor) / Math.max(0.1d, boost)));
    }

    private boolean lowActivityBoostActive() {
        return lowActivityBoostActive(variables);
    }

    static boolean lowActivityBoostActive(GameVariableStore variables) {
        if (!variables.bool("low-activity-boost.enabled")) return false;
        try {
            LocalTime now = LocalTime.now(ZoneOffset.UTC);
            LocalTime start = LocalTime.parse(variables.string("low-activity-boost.start-utc"));
            LocalTime end = LocalTime.parse(variables.string("low-activity-boost.end-utc"));
            if (start.equals(end)) return true;
            return start.isBefore(end) ? !now.isBefore(start) && now.isBefore(end)
                    : !now.isBefore(start) || now.isBefore(end);
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    static double lowActivityRewardMultiplier(GameVariableStore variables) {
        return lowActivityBoostActive(variables)
                ? variables.decimal("low-activity-boost.reward-multiplier") : 1d;
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

    /**
     * Starts one world event now, on demand.
     *
     * <p>The in-game command spawns near whoever ran it. Nobody is standing anywhere
     * when the control panel asks, so this takes the scheduler's own path — it picks a
     * site, announces it, and hands back to the normal rotation when it finishes.
     *
     * @return false when something is already standing, rather than stacking a second
     *         event on top of it
     */
    synchronized boolean startNow(Kind requested) {
        if (stopped || reserved) {
            return false;
        }
        reserved = true;
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
        }
        return accepted;
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
