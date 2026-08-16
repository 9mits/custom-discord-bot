package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
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
import java.util.Deque;

/**
 * One-shot launch: countdown in chat, strip barrier blocks, hold PvP off for five hours.
 */
final class LaunchService {
    private static final int COUNTDOWN_SECONDS = 10;
    private static final long PVP_HOLD_MILLIS = 5L * 60L * 60L * 1000L;
    private static final int SPAWN_CHUNK_RADIUS = 12;
    private static final int CHUNKS_PER_TICK = 8;

    private final MGXAccessBridge plugin;
    private final Path holdFile;
    private boolean countdownRunning;
    private BukkitTask restoreTask;

    LaunchService(MGXAccessBridge plugin, Path dataFolder) {
        this.plugin = plugin;
        this.holdFile = dataFolder.resolve("pvp-hold-until");
    }

    void restoreOnEnable() {
        long until = readHoldUntil();
        if (until <= System.currentTimeMillis()) {
            return;
        }
        setPvp(false);
        scheduleRestore(until);
        plugin.getLogger().info("PvP is still held off until " + until + ".");
    }

    void start(CommandSender sender) {
        if (countdownRunning) {
            throw new IllegalArgumentException("A start countdown is already running.");
        }
        countdownRunning = true;
        plugin.getLogger().info(sender.getName() + " started the server launch countdown.");
        tickCountdown(COUNTDOWN_SECONDS);
    }

    private void tickCountdown(int remaining) {
        if (remaining <= 0) {
            finishStart();
            return;
        }
        Bukkit.broadcast(Component.text("Server starting in " + remaining, NamedTextColor.GOLD)
                .decorate(TextDecoration.BOLD));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> tickCountdown(remaining - 1), 20L);
    }

    private void finishStart() {
        countdownRunning = false;
        Bukkit.broadcast(Component.text("Server starting.", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        setPvp(false);
        long until = System.currentTimeMillis() + PVP_HOLD_MILLIS;
        writeHoldUntil(until);
        scheduleRestore(until);
        stripBarriers();
    }

    private void stripBarriers() {
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
        drainBarrierQueue(queue, 0);
    }

    private void drainBarrierQueue(Deque<long[]> queue, int removed) {
        int processed = 0;
        int total = removed;
        while (!queue.isEmpty() && processed < CHUNKS_PER_TICK) {
            long[] next = queue.removeFirst();
            World world = Bukkit.getWorld(new java.util.UUID(next[0], next[1]));
            if (world != null) {
                total += clearBarriers(world.getChunkAt((int) next[2], (int) next[3]));
            }
            processed++;
        }
        if (queue.isEmpty()) {
            plugin.getLogger().info("Removed " + total + " barrier blocks.");
            return;
        }
        int carried = total;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> drainBarrierQueue(queue, carried), 1L);
    }

    private static int clearBarriers(Chunk chunk) {
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
                        block.setType(Material.AIR, false);
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    private void setPvp(boolean enabled) {
        for (World world : Bukkit.getWorlds()) {
            world.setPVP(enabled);
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
