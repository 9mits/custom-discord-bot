package bot.mgx.accessbridge;

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
 * <p>Wealth is the player's wallet. Anyone with money but no statistics file is
 * still listed, so a sale is enough to appear on the richest board.
 *
 * <p>Unchanged files are not parsed again, and usernames are remembered after the
 * first lookup so the five-minute pass does not keep warming Paper's offline-player
 * cache.
 */
final class PlayerStatsService {
    private final MGXAccessBridge plugin;
    private final Path statsDirectory;
    private final EconomyStore money;
    private final Map<UUID, Cached> cache = new HashMap<>();
    private final Map<UUID, String> rememberedNames = new HashMap<>();

    private record Cached(long mtime, PlayerStatsParser.Snapshot snapshot) {
    }

    PlayerStatsService(MGXAccessBridge plugin, Path statsDirectory, EconomyStore money) {
        this.plugin = plugin;
        this.statsDirectory = statsDirectory;
        this.money = money;
    }

    /**
     * Every player the server has a statistics file for.
     *
     * <p>Safe to call off the main thread: {@code knownNames} is captured by the caller
     * beforehand, so this never touches Bukkit's player directory.
     */
    List<PlayerStats> everyKnownPlayer(Map<UUID, String> knownNames) {
        List<PlayerStats> all = new ArrayList<>();
        rememberedNames.putAll(knownNames);
        Set<UUID> seen = new HashSet<>();
        if (!Files.isDirectory(statsDirectory)) {
            plugin.getLogger().warning("No statistics directory at " + statsDirectory);
        } else try (Stream<Path> files = Files.list(statsDirectory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                statsFor(file, knownNames).ifPresent(row -> {
                    seen.add(row.minecraftUuid());
                    all.add(row);
                });
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not list player statistics: " + exception.getMessage());
        }
        for (Map.Entry<UUID, Long> wallet : money.snapshots().entrySet()) {
            if (seen.contains(wallet.getKey()) || wallet.getValue() <= 0L) {
                continue;
            }
            seen.add(wallet.getKey());
            all.add(PlayerStats.empty(wallet.getKey(), usernameOf(wallet.getKey(), knownNames))
                    .withWealth(wallet.getValue()));
        }
        cache.keySet().removeIf(uuid -> !seen.contains(uuid));
        return all;
    }

    private java.util.Optional<PlayerStats> statsFor(Path file, Map<UUID, String> knownNames) {
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
                    usernameOf(uuid, knownNames),
                    snapshot.kills(),
                    snapshot.deaths(),
                    snapshot.playTimeTicks(),
                    snapshot.blocksMined(),
                    snapshot.walkedCm(),
                    money.balance(uuid)
            ));
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().warning(
                    "Could not read statistics for " + uuid + ": " + exception.getMessage()
            );
            return java.util.Optional.empty();
        }
    }

    private String usernameOf(UUID uuid, Map<UUID, String> knownNames) {
        String cached = PlayerStatsParser.cachedUsername(uuid, knownNames, rememberedNames);
        if (!cached.isEmpty()) {
            rememberedNames.put(uuid, cached);
            return cached;
        }
        String resolved = PlayerStatsParser.fallbackUsername(uuid);
        rememberedNames.put(uuid, resolved);
        return resolved;
    }

}
