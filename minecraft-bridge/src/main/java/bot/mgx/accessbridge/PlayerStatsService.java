package bot.mgx.accessbridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Reads leaderboard figures from the vanilla statistics files the server already
 * writes, so kills, deaths, playtime, blocks mined and distance walked are complete
 * for every player who has ever joined — including those offline right now.
 *
 * <p>Wealth is the exception: nothing records it, so it is measured while a player is
 * online and the last known figure is kept for when they are not.
 */
final class PlayerStatsService {
    private static final String CUSTOM = "minecraft:custom";
    private static final String MINED = "minecraft:mined";

    private final MGXAccessBridge plugin;
    private final Path statsDirectory;
    private final WealthStore wealth;

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
        try (Stream<Path> files = Files.list(statsDirectory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                statsFor(file, onlineNames).ifPresent(all::add);
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not list player statistics: " + exception.getMessage());
        }
        return all;
    }

    private java.util.Optional<PlayerStats> statsFor(Path file, Map<UUID, String> onlineNames) {
        String name = file.getFileName().toString().replace(".json", "");
        UUID uuid;
        try {
            uuid = UUID.fromString(name);
        } catch (IllegalArgumentException ignored) {
            return java.util.Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                return java.util.Optional.empty();
            }
            JsonObject stats = parsed.getAsJsonObject().getAsJsonObject("stats");
            if (stats == null) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new PlayerStats(
                    uuid,
                    usernameOf(uuid, onlineNames),
                    custom(stats, "minecraft:player_kills"),
                    custom(stats, "minecraft:deaths"),
                    custom(stats, "minecraft:play_time"),
                    totalOf(stats, MINED),
                    custom(stats, "minecraft:walk_one_cm"),
                    wealth.snapshots().getOrDefault(uuid, 0L)
            ));
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().warning(
                    "Could not read statistics for " + name + ": " + exception.getMessage()
            );
            return java.util.Optional.empty();
        }
    }

    private static long custom(JsonObject stats, String key) {
        JsonObject section = stats.getAsJsonObject(CUSTOM);
        if (section == null || !section.has(key)) {
            return 0L;
        }
        return section.get(key).getAsLong();
    }

    /** Sums a whole section, which is how "blocks mined" is derived from per-block counts. */
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

    private String usernameOf(UUID uuid, Map<UUID, String> onlineNames) {
        String online = onlineNames.get(uuid);
        if (online != null) {
            return online;
        }
        // Safe off the main thread: a UUID lookup reads the local cache and never
        // makes a web request, unlike looking a player up by name.
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }

    /** Measures what a player is carrying right now and remembers it for when they log off. */
    void snapshotWealth(Player player) {
        long total = 0L;
        total += valueOf(player.getInventory());
        total += valueOf(player.getEnderChest());
        for (ItemStack armour : player.getInventory().getArmorContents()) {
            total += valueOf(armour);
        }
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
