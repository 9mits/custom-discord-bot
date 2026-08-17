package bot.mgx.accessbridge;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Reads leaderboard figures from the vanilla statistics files the server already
 * writes, so kills, deaths, playtime, blocks mined and distance walked are complete
 * for every player who has ever joined — including those offline right now.
 *
 * <p>Wealth is the exception: nothing records it, so it is measured while a player is
 * online and the last known figure is kept for when they are not.
 *
 * <p>Unchanged files are not parsed again, and usernames are remembered after the
 * first lookup so the five-minute pass does not keep warming Paper's offline-player
 * cache.
 */
final class PlayerStatsService {
    private final MGXAccessBridge plugin;
    private final Path statsDirectory;
    private final WealthStore wealth;
    private final Map<UUID, Cached> cache = new HashMap<>();
    private final Map<UUID, String> rememberedNames = new HashMap<>();

    private record Cached(long mtime, PlayerStatsParser.Snapshot snapshot) {
    }

    PlayerStatsService(MGXAccessBridge plugin, Path statsDirectory, WealthStore wealth) {
        this.plugin = plugin;
        this.statsDirectory = statsDirectory;
        this.wealth = wealth;
    }

    /**
     * Every player the server has a statistics file for.
     *
     * <p>Safe to call off the main thread: {@code onlineNames} is captured by the caller
     * beforehand so this never has to ask Bukkit who is online.
     */
    List<PlayerStats> everyKnownPlayer(Map<UUID, String> onlineNames) {
        List<PlayerStats> all = new ArrayList<>();
        if (!Files.isDirectory(statsDirectory)) {
            plugin.getLogger().warning("No statistics directory at " + statsDirectory);
            return all;
        }
        rememberedNames.putAll(onlineNames);
        Set<UUID> seen = new HashSet<>();
        try (Stream<Path> files = Files.list(statsDirectory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                statsFor(file, onlineNames).ifPresent(row -> {
                    seen.add(row.minecraftUuid());
                    all.add(row);
                });
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not list player statistics: " + exception.getMessage());
        }
        cache.keySet().removeIf(uuid -> !seen.contains(uuid));
        return all;
    }

    private java.util.Optional<PlayerStats> statsFor(Path file, Map<UUID, String> onlineNames) {
        java.util.Optional<UUID> parsedUuid = PlayerStatsParser.uuidFromFileName(file);
        if (parsedUuid.isEmpty()) {
            return java.util.Optional.empty();
        }
        UUID uuid = parsedUuid.get();
        try {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            Cached cached = cache.get(uuid);
            PlayerStatsParser.Snapshot snapshot;
            if (cached != null && cached.mtime() == mtime) {
                snapshot = cached.snapshot();
            } else {
                java.util.Optional<PlayerStatsParser.Snapshot> read = PlayerStatsParser.read(file);
                if (read.isEmpty()) {
                    return java.util.Optional.empty();
                }
                snapshot = read.get();
                cache.put(uuid, new Cached(mtime, snapshot));
            }
            return java.util.Optional.of(new PlayerStats(
                    uuid,
                    usernameOf(uuid, onlineNames),
                    snapshot.kills(),
                    snapshot.deaths(),
                    snapshot.playTimeTicks(),
                    snapshot.blocksMined(),
                    snapshot.walkedCm(),
                    wealth.snapshots().getOrDefault(uuid, 0L)
            ));
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().warning(
                    "Could not read statistics for " + uuid + ": " + exception.getMessage()
            );
            return java.util.Optional.empty();
        }
    }

    private String usernameOf(UUID uuid, Map<UUID, String> onlineNames) {
        String cached = PlayerStatsParser.cachedUsername(uuid, onlineNames, rememberedNames);
        if (!cached.isEmpty()) {
            rememberedNames.put(uuid, cached);
            return cached;
        }
        // Safe off the main thread: a UUID lookup reads the local cache and never
        // makes a web request, unlike looking a player up by name. Remembered after
        // the first hit so later passes do not keep Paper's offline-player map warm.
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName();
        String resolved = name == null ? PlayerStatsParser.fallbackUsername(uuid) : name;
        rememberedNames.put(uuid, resolved);
        return resolved;
    }

    /** Measures what a player is carrying right now and remembers it for when they log off. */
    void snapshotWealth(Player player) {
        long total = 0L;
        total += valueOf(player.getInventory());
        total += valueOf(player.getEnderChest());
        wealth.record(player.getUniqueId(), total);
    }

    /** Flushes measured wealth to disk; a no-op when nothing changed. */
    void saveWealth() {
        try {
            wealth.saveIfChanged();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not save player wealth: " + exception.getMessage());
        }
    }

    private static long valueOf(Inventory inventory) {
        long total = 0L;
        for (ItemStack item : inventory.getContents()) {
            total += valueOf(item);
        }
        return total;
    }

    private static long valueOf(ItemStack item) {
        if (item == null) {
            return 0L;
        }
        return (long) WealthTable.valueOfIncludingVariants(item.getType()) * item.getAmount();
    }

}
