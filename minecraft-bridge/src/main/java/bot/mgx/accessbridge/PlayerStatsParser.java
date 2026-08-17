package bot.mgx.accessbridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the vanilla statistics fields the leaderboard needs, without touching Bukkit.
 */
final class PlayerStatsParser {
    private static final String CUSTOM = "minecraft:custom";
    private static final String MINED = "minecraft:mined";

    record Snapshot(long kills, long deaths, long playTimeTicks, long blocksMined, long walkedCm) {
    }

    private PlayerStatsParser() {
    }

    static Optional<UUID> uuidFromFileName(Path file) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".json")) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(name.substring(0, name.length() - 5)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    static Optional<Snapshot> read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject stats = parsed.getAsJsonObject().getAsJsonObject("stats");
            if (stats == null) {
                return Optional.empty();
            }
            return Optional.of(new Snapshot(
                    custom(stats, "minecraft:player_kills"),
                    custom(stats, "minecraft:deaths"),
                    custom(stats, "minecraft:play_time"),
                    totalOf(stats, MINED),
                    custom(stats, "minecraft:walk_one_cm")
            ));
        }
    }

    static String cachedUsername(UUID uuid, Map<UUID, String> onlineNames, Map<UUID, String> remembered) {
        String online = onlineNames.get(uuid);
        if (online != null) {
            return online;
        }
        String known = remembered.get(uuid);
        return known == null ? "" : known;
    }

    static String fallbackUsername(UUID uuid) {
        return uuid.toString().substring(0, 8);
    }

    private static long custom(JsonObject stats, String key) {
        JsonObject section = stats.getAsJsonObject(CUSTOM);
        if (section == null || !section.has(key)) {
            return 0L;
        }
        return section.get(key).getAsLong();
    }

    private static long totalOf(JsonObject stats, String sectionName) {
        JsonObject section = stats.getAsJsonObject(sectionName);
        if (section == null) {
            return 0L;
        }
        long total = 0L;
        for (Map.Entry<String, JsonElement> entry : section.entrySet()) {
            total += entry.getValue().getAsLong();
        }
        return total;
    }
}
