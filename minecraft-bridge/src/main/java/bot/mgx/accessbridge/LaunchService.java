package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameRules;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One-shot launch: countdown in chat, strip barrier blocks, hold PvP off for five hours.
 *
 * <p>An operator can also pin PvP with {@code /mgxadmin pvp on|off}. That pin outranks
 * the launch hold in both directions — it survives a restart and cancels the timer that
 * would otherwise switch PvP back on — because the reason to reach for it is always that
 * the automatic schedule is currently wrong.
 */
final class LaunchService {
    private static final int COUNTDOWN_SECONDS = 10;
    private static final long PVP_HOLD_MILLIS = 5L * 60L * 60L * 1000L;
    /** Live tuning; the constants above stay the defaults and stand alone in tests. */
    private static volatile java.util.function.ToDoubleFunction<String> tuning = key -> Double.NaN;

    static void tuningSource(java.util.function.ToDoubleFunction<String> source) {
        if (source != null) {
            tuning = source;
        }
    }

    private static double tuned(String key, double fallback) {
        double value = tuning.applyAsDouble(key);
        return Double.isNaN(value) ? fallback : value;
    }

    private static final long TEST_RESTORE_TICKS = 60L * 20L;
    private static final int SPAWN_CHUNK_RADIUS = 12;
    private static final int CHUNKS_PER_TICK = 8;

    private final MGXAccessBridge plugin;
    private final Path holdFile;
    private final Path forcedFile;
    /** An operator's pin, or null when PvP follows the launch hold. */
    private Boolean forced;
    private boolean countdownRunning;
    private boolean testRun;
    private BukkitTask restoreTask;
    private BukkitTask barrierRestoreTask;
    private final List<BarrierBlock> removedBarriers = new ArrayList<>();

    /** Packed block we stripped in a test run, without holding a live World. */
    private record BarrierBlock(UUID worldId, int x, int y, int z) {
    }

    /** A chunk we loaded only to restore a test barrier, keyed without a World. */
    private record ScannedChunk(UUID worldId, int x, int z) {
    }

    LaunchService(MGXAccessBridge plugin, Path dataFolder) {
        this.plugin = plugin;
        this.holdFile = dataFolder.resolve("pvp-hold-until");
        this.forcedFile = dataFolder.resolve("pvp-forced");
    }

    void restoreOnEnable() {
        forced = readForced();
        if (forced != null) {
            setPvp(forced);
            plugin.getLogger().info("PvP is pinned " + (forced ? "on" : "off") + " by an operator.");
            return;
        }
        long until = readHoldUntil();
        if (until <= System.currentTimeMillis()) {
            // Asserted, not left alone. PvP is a persisted gamerule in 1.21, so an event
            // that switched it off and never switched it back leaves the world that way
            // for good, and nothing else in the plugin ever puts it right.
            setPvp(true);
            return;
        }
        setPvp(false);
        scheduleRestore(until);
        plugin.getLogger().info("PvP is still held off until " + until + ".");
    }

    /** Where PvP belongs right now: an operator's pin, else the launch hold, else on. */
    private boolean intendedPvp() {
        return forced != null ? forced : readHoldUntil() <= System.currentTimeMillis();
    }

    /** An event borrowing PvP: switched off without disturbing the pin or the hold. */
    void suspendPvp() {
        setPvp(false);
    }

    /**
     * Hands PvP back to whatever owns it.
     *
     * <p>Deliberately not "restore what it was when I took it": two events overlapping
     * meant the second captured the first one's off, and PvP stayed off after both had
     * ended, with no session left to blame.
     */
    void restorePvp() {
        setPvp(intendedPvp());
    }

    void start(CommandSender sender) {
        begin(sender, false);
    }

    void startTest(CommandSender sender) {
        begin(sender, true);
    }

    private void begin(CommandSender sender, boolean test) {
        if (countdownRunning) {
            throw new IllegalArgumentException("A start countdown is already running.");
        }
        countdownRunning = true;
        testRun = test;
        plugin.getLogger().info(sender.getName() + " started the "
                + (test ? "test " : "") + "server launch countdown.");
        tickCountdown((int) tuned("launch.countdown-seconds", COUNTDOWN_SECONDS));
    }

    private void tickCountdown(int remaining) {
        if (remaining <= 0) {
            finishStart();
            return;
        }
        String prefix = testRun ? "Test starting in " : "Server starting in ";
        Bukkit.broadcast(Component.text(prefix + remaining, NamedTextColor.GOLD)
                .decorate(TextDecoration.BOLD));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> tickCountdown(remaining - 1), 20L);
    }

    private void finishStart() {
        countdownRunning = false;
        boolean test = testRun;
        testRun = false;
        if (test) {
            Bukkit.broadcast(Component.text("Test starting. Barriers return in 1 minute.", NamedTextColor.GOLD)
                    .decorate(TextDecoration.BOLD));
            stripBarriers(true);
            return;
        }
        Bukkit.broadcast(Component.text("Server starting.", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        clearForced();
        setPvp(false);
        long until = System.currentTimeMillis()
                + (long) (tuned("launch.pvp-hold-hours", 5d) * 3_600_000d);
        writeHoldUntil(until);
        scheduleRestore(until);
        stripBarriers(false);
    }

    private void stripBarriers(boolean restoreAfterMinute) {
        if (restoreAfterMinute) {
            removedBarriers.clear();
        }
        Deque<long[]> queue = new ArrayDeque<>();
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == World.Environment.THE_END) {
                continue;
            }
            int spawnX = world.getSpawnLocation().getBlockX() >> 4;
            int spawnZ = world.getSpawnLocation().getBlockZ() >> 4;
            for (int x = spawnX - SPAWN_CHUNK_RADIUS; x <= spawnX + SPAWN_CHUNK_RADIUS; x++) {
                for (int z = spawnZ - SPAWN_CHUNK_RADIUS; z <= spawnZ + SPAWN_CHUNK_RADIUS; z++) {
                    queue.add(new long[] {world.getUID().getMostSignificantBits(),
                            world.getUID().getLeastSignificantBits(), x, z});
                }
            }
        }
        plugin.getLogger().info("Removing barrier blocks from " + queue.size() + " chunks.");
        drainBarrierQueue(queue, 0, restoreAfterMinute);
    }

    private void drainBarrierQueue(Deque<long[]> queue, int removed, boolean restoreAfterMinute) {
        int processed = 0;
        int total = removed;
        while (!queue.isEmpty() && processed < CHUNKS_PER_TICK) {
            long[] next = queue.removeFirst();
            World world = Bukkit.getWorld(new UUID(next[0], next[1]));
            if (world != null) {
                int chunkX = (int) next[2];
                int chunkZ = (int) next[3];
                boolean wasLoaded = world.isChunkLoaded(chunkX, chunkZ);
                total += clearBarriers(world.getChunkAt(chunkX, chunkZ), restoreAfterMinute);
                if (WorldMemory.shouldUnloadScannedChunk(wasLoaded)) {
                    world.unloadChunk(chunkX, chunkZ, true);
                }
            }
            processed++;
        }
        if (queue.isEmpty()) {
            plugin.getLogger().info("Removed " + total + " barrier blocks.");
            if (restoreAfterMinute) {
                scheduleBarrierRestore();
            }
            return;
        }
        int carried = total;
        plugin.getServer().getScheduler().runTaskLater(
                plugin, () -> drainBarrierQueue(queue, carried, restoreAfterMinute), 1L
        );
    }

    private int clearBarriers(Chunk chunk, boolean record) {
        int removed = 0;
        World world = chunk.getWorld();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    Block block = world.getBlockAt(baseX + x, y, baseZ + z);
                    if (block.getType() == Material.BARRIER) {
                        if (record) {
                            removedBarriers.add(new BarrierBlock(
                                    world.getUID(), baseX + x, y, baseZ + z
                            ));
                        }
                        block.setType(Material.AIR, false);
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    private void scheduleBarrierRestore() {
        if (barrierRestoreTask != null) {
            barrierRestoreTask.cancel();
        }
        barrierRestoreTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            int restored = 0;
            Set<ScannedChunk> loaded = new HashSet<>();
            for (BarrierBlock location : removedBarriers) {
                World world = Bukkit.getWorld(location.worldId());
                if (world == null) {
                    continue;
                }
                int chunkX = location.x() >> 4;
                int chunkZ = location.z() >> 4;
                boolean wasLoaded = world.isChunkLoaded(chunkX, chunkZ);
                Block block = world.getBlockAt(location.x(), location.y(), location.z());
                if (block.getType() == Material.AIR) {
                    block.setType(Material.BARRIER, false);
                    restored++;
                }
                if (WorldMemory.shouldUnloadScannedChunk(wasLoaded)) {
                    loaded.add(new ScannedChunk(location.worldId(), chunkX, chunkZ));
                }
            }
            for (ScannedChunk chunk : loaded) {
                World world = Bukkit.getWorld(chunk.worldId());
                if (world != null) {
                    world.unloadChunk(chunk.x(), chunk.z(), true);
                }
            }
            removedBarriers.clear();
            Bukkit.broadcast(Component.text("Test finished. Barriers are back.", NamedTextColor.GOLD)
                    .decorate(TextDecoration.BOLD));
            plugin.getLogger().info("Restored " + restored + " barrier blocks.");
        }, TEST_RESTORE_TICKS);
    }

    /** {@code /mgxadmin pvp on|off}: pins PvP and drops whatever the launch left running. */
    void forcePvp(boolean enabled) {
        forced = enabled;
        writeForced(enabled);
        clearHold();
        setPvp(enabled);
        plugin.getLogger().info("PvP pinned " + (enabled ? "on" : "off") + " by an operator.");
    }

    boolean pvpForced() {
        return forced != null;
    }

    boolean pvpEnabled() {
        for (World world : Bukkit.getWorlds()) {
            Boolean value = world.getGameRuleValue(GameRules.PVP);
            if (value != null) {
                return value;
            }
        }
        return true;
    }

    /** One line for {@code /mgxadmin pvp status}: the state, and what is holding it there. */
    String pvpStatus() {
        String state = "PvP is " + (pvpEnabled() ? "on" : "off");
        if (forced != null) {
            return state + ", pinned there by an operator.";
        }
        long remaining = readHoldUntil() - System.currentTimeMillis();
        if (remaining > 0) {
            return state + " for another " + PvpPin.describe(remaining) + " of the launch hold.";
        }
        return state + ".";
    }

    /** Ends the timed launch hold without touching PvP itself. */
    private void clearHold() {
        if (restoreTask != null) {
            restoreTask.cancel();
            restoreTask = null;
        }
        try {
            Files.deleteIfExists(holdFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not clear the PvP hold: " + exception.getMessage());
        }
    }

    private void setPvp(boolean enabled) {
        for (World world : Bukkit.getWorlds()) {
            world.setGameRule(GameRules.PVP, enabled);
        }
    }

    private void scheduleRestore(long untilMillis) {
        if (restoreTask != null) {
            restoreTask.cancel();
        }
        long delayTicks = Math.max(1L, (untilMillis - System.currentTimeMillis()) / 50L);
        restoreTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            setPvp(true);
            try {
                Files.deleteIfExists(holdFile);
            } catch (IOException ignored) {
                // The hold has already expired in memory.
            }
            plugin.getLogger().info("Five-hour PvP hold ended.");
        }, delayTicks);
    }

    private Boolean readForced() {
        if (!Files.isRegularFile(forcedFile)) {
            return null;
        }
        try {
            return PvpPin.parse(Files.readString(forcedFile, StandardCharsets.UTF_8)).orElse(null);
        } catch (IOException ignored) {
            // An unreadable pin is no pin; the launch hold decides instead.
            return null;
        }
    }

    private void writeForced(boolean enabled) {
        try {
            Files.writeString(forcedFile, PvpPin.format(enabled), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not persist the PvP pin: " + exception.getMessage());
        }
    }

    private void clearForced() {
        forced = null;
        try {
            Files.deleteIfExists(forcedFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not clear the PvP pin: " + exception.getMessage());
        }
    }

    private long readHoldUntil() {
        if (!Files.isRegularFile(holdFile)) {
            return 0L;
        }
        try {
            return Long.parseLong(Files.readString(holdFile, StandardCharsets.UTF_8).strip());
        } catch (IOException | NumberFormatException ignored) {
            return 0L;
        }
    }

    private void writeHoldUntil(long untilMillis) {
        try {
            Files.writeString(holdFile, Long.toString(untilMillis), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not persist the PvP hold: " + exception.getMessage());
        }
    }
}
