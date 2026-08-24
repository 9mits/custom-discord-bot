package bot.mgx.accessbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds ranked standings and pushes them to the Discord bot.
 *
 * <p>Push rather than request/response: the bot keeps the newest snapshot and edits its
 * permanent message from cache, so a dropped snapshot costs nothing — the next one
 * replaces it. That also caps how often the statistics files are read.
 */
final class LeaderboardService {
    /** Top ten on every board — hologram, menu, and Discord. */
    private static final int ROWS = 10;
    /** Publish shortly after boot so a freshly placed board is not blank for minutes. */
    private static final long FIRST_PUBLISH_TICKS = 200L;

    private final MGXAccessBridge plugin;
    private final BridgeClient bridge;
    private final PlayerStatsService stats;
    private final ClanStore clans;
    private final long refreshTicks;
    private final AtomicBoolean publishing = new AtomicBoolean();
    private int taskId = -1;
    private volatile JsonObject latest = new JsonObject();
    private volatile Map<UUID, LeaderboardStandings.Standing> standings = Map.of();

    LeaderboardService(
            MGXAccessBridge plugin,
            BridgeClient bridge,
            PlayerStatsService stats,
            ClanStore clans,
            long refreshTicks
    ) {
        this.plugin = plugin;
        this.bridge = bridge;
        this.stats = stats;
        this.clans = clans;
        this.refreshTicks = refreshTicks;
    }

    void start() {
        taskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
                plugin,
                this::publish,
                FIRST_PUBLISH_TICKS,
                refreshTicks
        );
    }

    /** Bukkit's player directory is captured on the main thread before disk work begins. */
    private Map<UUID, String> snapshotKnownPlayerNames() {
        Map<UUID, String> knownNames = new HashMap<>();
        for (OfflinePlayer offline : plugin.getServer().getOfflinePlayers()) {
            String name = offline.getName();
            if (name != null && !name.isBlank()) {
                knownNames.put(offline.getUniqueId(), name);
            }
        }
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            knownNames.put(online.getUniqueId(), online.getName());
        }
        return knownNames;
    }

    void stop() {
        if (taskId >= 0) {
            plugin.getServer().getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    /** Publishes immediately, off the timer. Must run on the main thread. */
    void publishNow() {
        publish();
    }

    private void publish() {
        if (!publishing.compareAndSet(false, true)) {
            return;
        }
        Map<UUID, String> knownNames = snapshotKnownPlayerNames();
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    buildAndSend(knownNames);
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning(
                            "Could not publish Minecraft standings: " + exception.getMessage()
                    );
                } finally {
                    publishing.set(false);
                }
            });
        } catch (RuntimeException exception) {
            publishing.set(false);
            throw exception;
        }
    }

    private void buildAndSend(Map<UUID, String> knownNames) {
        List<PlayerStats> everyone = stats.everyKnownPlayer(knownNames);
        standings = LeaderboardStandings.bestByPlayer(everyone);
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("generated_at", System.currentTimeMillis());

        JsonObject individual = new JsonObject();
        JsonObject clan = new JsonObject();
        for (LeaderboardType type : LeaderboardType.values()) {
            if (!type.published()) {
                continue;
            }
            individual.add(type.key(), rankIndividuals(everyone, type));
            if (type.clanEligible()) {
                clan.add(type.key(), type == LeaderboardType.WEALTH
                        ? rankClansByTreasury()
                        : rankClans(everyone, type));
            }
        }
        snapshot.add("individual", individual);
        snapshot.add("clan", clan);
        latest = snapshot;
        bridge.sendLeaderboardSnapshot(snapshot);
    }

    JsonObject latest() {
        return latest;
    }

    Optional<LeaderboardStandings.Standing> standing(UUID playerId) {
        return Optional.ofNullable(standings.get(playerId));
    }

    private JsonArray rankIndividuals(List<PlayerStats> everyone, LeaderboardType type) {
        List<PlayerStats> ranked = new ArrayList<>(everyone);
        ranked.sort(Comparator
                .comparingLong((PlayerStats row) -> row.value(type)).reversed()
                .thenComparing(PlayerStats::username, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(row -> row.minecraftUuid().toString()));
        JsonArray rows = new JsonArray();
        for (PlayerStats row : ranked) {
            if (rows.size() >= ROWS) {
                break;
            }
            if (row.value(type) <= 0) {
                continue; // an empty board reads better than a list of zeroes
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("minecraft_uuid", row.minecraftUuid().toString());
            entry.addProperty("username", row.username());
            entry.addProperty("value", row.value(type));
            entry.addProperty("display", type.describe(row.value(type)));
            clans.clanOf(row.minecraftUuid())
                    .ifPresent(view -> entry.addProperty("clan", view.name()));
            rows.add(entry);
        }
        return rows;
    }

    /** The richest board: every clan ordered by donated money in its treasury. */
    private JsonArray rankClansByTreasury() {
        List<ClanStore.ClanView> ranked = new ArrayList<>(clans.list());
        ranked.sort(Comparator.comparingLong(ClanStore.ClanView::balance).reversed());
        JsonArray rows = new JsonArray();
        for (ClanStore.ClanView clan : ranked) {
            long total = clan.balance();
            if (rows.size() >= ROWS || total <= 0) {
                break;
            }
            JsonObject row = new JsonObject();
            row.addProperty("clan", clan.name());
            row.addProperty("members", clan.members().size());
            row.addProperty("colour", clan.themeColor());
            row.addProperty("level", clan.level());
            row.addProperty("value", total);
            row.addProperty("display", LeaderboardType.WEALTH.describe(total));
            rows.add(row);
        }
        return rows;
    }

    private JsonArray rankClans(List<PlayerStats> everyone, LeaderboardType type) {
        Map<String, Long> totals = new HashMap<>();
        Map<String, Integer> members = new HashMap<>();
        Map<String, Integer> colours = new HashMap<>();
        Map<String, Integer> levels = new HashMap<>();
        for (PlayerStats row : everyone) {
            Optional<ClanStore.ClanView> clan = clans.clanOf(row.minecraftUuid());
            if (clan.isEmpty()) {
                continue;
            }
            String name = clan.get().name();
            totals.merge(name, row.value(type), Long::sum);
            members.merge(name, 1, Integer::sum);
            colours.putIfAbsent(name, clan.get().themeColor());
            levels.putIfAbsent(name, clan.get().level());
        }
        List<Map.Entry<String, Long>> ranked = new ArrayList<>(totals.entrySet());
        ranked.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        JsonArray rows = new JsonArray();
        for (Map.Entry<String, Long> entry : ranked) {
            if (rows.size() >= ROWS || entry.getValue() <= 0) {
                break;
            }
            JsonObject row = new JsonObject();
            row.addProperty("clan", entry.getKey());
            row.addProperty("members", members.getOrDefault(entry.getKey(), 0));
            row.addProperty("colour", colours.getOrDefault(entry.getKey(), 0xFF9900));
            row.addProperty("level", levels.getOrDefault(entry.getKey(), 0));
            row.addProperty("value", entry.getValue());
            row.addProperty("display", type.describe(entry.getValue()));
            rows.add(row);
        }
        return rows;
    }
}
