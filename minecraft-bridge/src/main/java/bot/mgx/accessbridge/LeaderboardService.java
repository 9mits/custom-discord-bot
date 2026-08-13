package bot.mgx.accessbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds ranked standings and pushes them to the Discord bot.
 *
 * <p>Push rather than request/response: the bot keeps the newest snapshot and edits its
 * permanent message from cache, so a dropped snapshot costs nothing — the next one
 * replaces it. That also caps how often the statistics files are read.
 */
final class LeaderboardService {
    /** Enough rows for a top ten with room for the podium to shuffle. */
    private static final int ROWS = 10;
    /** Publish shortly after boot so a freshly placed board is not blank for minutes. */
    private static final long FIRST_PUBLISH_TICKS = 200L;

    private final MGXAccessBridge plugin;
    private final BridgeClient bridge;
    private final PlayerStatsService stats;
    private final ClanStore clans;
    private final long refreshTicks;
    private int taskId = -1;

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
        // Wealth only changes while someone is playing, so refresh the online players
        // first and let everyone else keep their last known figure.
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            stats.snapshotWealth(online);
        }
        List<PlayerStats> everyone = stats.everyKnownPlayer();
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("generated_at", System.currentTimeMillis());

        JsonObject individual = new JsonObject();
        JsonObject clan = new JsonObject();
        for (LeaderboardType type : LeaderboardType.values()) {
            individual.add(type.key(), rankIndividuals(everyone, type));
            if (type.clanEligible()) {
                clan.add(type.key(), rankClans(everyone, type));
            }
        }
        snapshot.add("individual", individual);
        snapshot.add("clan", clan);
        bridge.sendLeaderboardSnapshot(snapshot);
    }

    private JsonArray rankIndividuals(List<PlayerStats> everyone, LeaderboardType type) {
        List<PlayerStats> ranked = new ArrayList<>(everyone);
        ranked.sort(Comparator.comparingLong((PlayerStats row) -> row.value(type)).reversed());
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

    private JsonArray rankClans(List<PlayerStats> everyone, LeaderboardType type) {
        Map<String, Long> totals = new HashMap<>();
        Map<String, Integer> members = new HashMap<>();
        Map<String, Integer> colours = new HashMap<>();
        for (PlayerStats row : everyone) {
            Optional<ClanStore.ClanView> clan = clans.clanOf(row.minecraftUuid());
            if (clan.isEmpty()) {
                continue;
            }
            String name = clan.get().name();
            totals.merge(name, row.value(type), Long::sum);
            members.merge(name, 1, Integer::sum);
            colours.putIfAbsent(name, clan.get().themeColor());
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
            row.addProperty("value", entry.getValue());
            row.addProperty("display", type.describe(entry.getValue()));
            rows.add(row);
        }
        return rows;
    }

    /** Exposed so a UUID with no statistics file still resolves for tests. */
    static UUID parseUuid(String raw) {
        return UUID.fromString(raw);
    }
}
