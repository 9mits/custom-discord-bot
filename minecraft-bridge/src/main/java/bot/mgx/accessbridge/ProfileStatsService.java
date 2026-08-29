package bot.mgx.accessbridge;

import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One player's numbers, whether or not they are online.
 *
 * <p>Vanilla already writes every statistic to {@code world/stats/<uuid>.json}, so an
 * offline profile needs no database of our own — reading that file is both the whole
 * answer and cheaper than keeping a second copy in step with it. Bukkit only exposes
 * statistics for an online player, which is why the leaderboard could not open a card
 * for anyone who had logged off.
 *
 * <p>Reads are cached against the file's modification time, so repeatedly opening the
 * same profile parses nothing.
 */
final class ProfileStatsService {
    record Profile(
            UUID playerId,
            String name,
            long money,
            long shards,
            long playerKills,
            long mobKills,
            long deaths,
            long playTimeTicks,
            long blocksBroken,
            long blocksPlaced,
            long walkedCm
    ) {
    }

    private record Cached(long modified, PlayerStatsParser.Snapshot snapshot) {
    }

    private final MGXAccessBridge plugin;
    private final Path statsDirectory;
    private final EconomyStore money;
    private final CrateItems crateItems;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    ProfileStatsService(
            MGXAccessBridge plugin,
            Path statsDirectory,
            EconomyStore money,
            CrateItems crateItems
    ) {
        this.plugin = plugin;
        this.statsDirectory = statsDirectory;
        this.money = money;
        this.crateItems = crateItems;
    }

    Profile of(UUID playerId, String fallbackName) {
        Player online = Bukkit.getPlayer(playerId);
        String name = online != null ? online.getName() : resolveName(playerId, fallbackName);
        PlayerStatsParser.Snapshot saved = snapshot(playerId);
        // An online player's own statistics are newer than the file, which vanilla only
        // rewrites periodically, so they win wherever both have an answer.
        return new Profile(
                playerId,
                name,
                money.balance(playerId),
                online == null ? 0L : crateItems.countShards(online),
                pick(online, Statistic.PLAYER_KILLS, saved == null ? 0L : saved.kills()),
                pick(online, Statistic.MOB_KILLS, saved == null ? 0L : saved.mobKills()),
                pick(online, Statistic.DEATHS, saved == null ? 0L : saved.deaths()),
                pick(online, Statistic.PLAY_ONE_MINUTE, saved == null ? 0L : saved.playTimeTicks()),
                saved == null ? 0L : saved.blocksMined(),
                saved == null ? 0L : saved.blocksPlaced(),
                pick(online, Statistic.WALK_ONE_CM, saved == null ? 0L : saved.walkedCm())
        );
    }

    private static long pick(Player online, Statistic statistic, long saved) {
        if (online == null) {
            return saved;
        }
        try {
            return Math.max(saved, online.getStatistic(statistic));
        } catch (IllegalArgumentException ignored) {
            return saved;
        }
    }

    private PlayerStatsParser.Snapshot snapshot(UUID playerId) {
        Path file = statsDirectory.resolve(playerId + ".json");
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            long modified = Files.getLastModifiedTime(file).toMillis();
            Cached cached = cache.get(playerId);
            if (cached != null && cached.modified() == modified) {
                return cached.snapshot();
            }
            PlayerStatsParser.Snapshot read = PlayerStatsParser.read(file).orElse(null);
            if (read != null) {
                cache.put(playerId, new Cached(modified, read));
            }
            return read;
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().warning(
                    "Could not read statistics for " + playerId + ": " + exception.getMessage()
            );
            return null;
        }
    }

    private static String resolveName(UUID playerId, String fallbackName) {
        if (fallbackName != null && !fallbackName.isBlank()) {
            return fallbackName;
        }
        String known = Bukkit.getOfflinePlayer(playerId).getName();
        return known == null ? playerId.toString().substring(0, 8) : known;
    }
}
