package bot.mgx.accessbridge;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Wipes accumulated player and clan progress while leaving the world alone.
 *
 * <p>The world is never opened here. Region files, entities, the seed and the spawn
 * point are untouched, so everything built during testing survives; only what players
 * <em>accumulated</em> — statistics, advancements, items, clan balances — is cleared.
 *
 * <p>Online players are reset through the Bukkit API rather than by deleting their
 * files, because the server holds their data in memory and writes it back out when
 * they disconnect: deleting the file under a logged-in player achieves nothing.
 * Offline players have no such copy, so their files are removed directly.
 */
final class ServerDataResetService {
    private final MGXAccessBridge plugin;
    private final ClanStore clans;
    private final WealthStore wealth;
    private final RankSyncStore rankSync;
    private final Path worldFolder;

    ServerDataResetService(
            MGXAccessBridge plugin,
            ClanStore clans,
            WealthStore wealth,
            RankSyncStore rankSync,
            Path worldFolder
    ) {
        this.plugin = plugin;
        this.clans = clans;
        this.wealth = wealth;
        this.rankSync = rankSync;
        this.worldFolder = worldFolder;
    }

    /** What a reset actually did, so the operator gets a receipt rather than "done". */
    record Summary(Map<ResetScope, Integer> cleared, List<String> problems) {
        String describe(ResetScope scope) {
            return String.valueOf(cleared.getOrDefault(scope, 0));
        }
    }

    /**
     * Runs the reset. Must be called on the main thread: it touches online players.
     */
    Summary reset(Set<ResetScope> scopes) {
        Map<ResetScope, Integer> cleared = new EnumMap<>(ResetScope.class);
        List<String> problems = new ArrayList<>();
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());

        if (scopes.contains(ResetScope.STATS)) {
            int touched = 0;
            for (Player player : online) {
                clearStatistics(player);
                touched++;
            }
            touched += deleteOfflineFiles("stats", ".json", online, problems);
            cleared.put(ResetScope.STATS, touched);
        }
        if (scopes.contains(ResetScope.ADVANCEMENTS)) {
            int touched = 0;
            for (Player player : online) {
                clearAdvancements(player);
                touched++;
            }
            touched += deleteOfflineFiles("advancements", ".json", online, problems);
            cleared.put(ResetScope.ADVANCEMENTS, touched);
        }
        if (scopes.contains(ResetScope.INVENTORIES)) {
            int touched = 0;
            for (Player player : online) {
                clearInventory(player);
                touched++;
            }
            // Player data holds the inventory of everyone offline, and their position,
            // health and experience with it. Deleting it makes their next join a fresh
            // spawn, which is the offline equivalent of what the API does above.
            touched += deleteOfflineFiles("playerdata", ".dat", online, problems);
            cleared.put(ResetScope.INVENTORIES, touched);
        }
        if (scopes.contains(ResetScope.CLANS)) {
            try {
                cleared.put(ResetScope.CLANS, clans.clearAll());
                plugin.refreshClans();
                plugin.republishCapabilities();
            } catch (IOException exception) {
                problems.add("Clans could not be cleared: " + exception.getMessage());
            }
        }
        if (scopes.contains(ResetScope.WEALTH)) {
            try {
                cleared.put(ResetScope.WEALTH, wealth.clearAll());
            } catch (RuntimeException exception) {
                problems.add("Wealth could not be cleared: " + exception.getMessage());
            }
        }
        // Ranks are Discord-derived and are deliberately not reset. Forgetting what the
        // bridge granted, though, keeps the record honest when player data has gone.
        if (scopes.contains(ResetScope.INVENTORIES) && scopes.contains(ResetScope.STATS)) {
            rankSync.clearApplied();
        }
        plugin.republishLeaderboard();
        return new Summary(cleared, problems);
    }

    /**
     * Zeroes every statistic a player has.
     *
     * <p>Statistics are keyed three different ways, and the typed ones have to be
     * cleared per material and per entity type. That is a lot of calls, which is why
     * this is an operator command run between seasons rather than anything routine.
     */
    private void clearStatistics(Player player) {
        for (Statistic statistic : Statistic.values()) {
            switch (statistic.getType()) {
                case UNTYPED -> setQuietly(() -> player.setStatistic(statistic, 0));
                case BLOCK, ITEM -> {
                    for (Material material : Material.values()) {
                        setQuietly(() -> player.setStatistic(statistic, material, 0));
                    }
                }
                case ENTITY -> {
                    for (EntityType type : EntityType.values()) {
                        setQuietly(() -> player.setStatistic(statistic, type, 0));
                    }
                }
                default -> { }
            }
        }
    }

    /**
     * Not every statistic accepts every material or entity: Bukkit throws rather than
     * ignoring the combinations that do not exist, and there is no way to enumerate the
     * valid ones. Refusals are the normal case here, not a failure.
     */
    private static void setQuietly(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // This statistic does not apply to that material or entity type.
        }
    }

    private void clearAdvancements(Player player) {
        Iterator<Advancement> advancements = Bukkit.advancementIterator();
        while (advancements.hasNext()) {
            AdvancementProgress progress = player.getAdvancementProgress(advancements.next());
            for (String criterion : List.copyOf(progress.getAwardedCriteria())) {
                progress.revokeCriteria(criterion);
            }
        }
    }

    private void clearInventory(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getEnderChest().clear();
        player.setLevel(0);
        player.setExp(0f);
        player.setTotalExperience(0);
        player.setFoodLevel(20);
        player.setSaturation(5f);
        player.setFireTicks(0);
        AttributeInstance maximum = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        player.setHealth(maximum == null ? 20d : maximum.getValue());
    }

    /**
     * Removes one per-player file for everyone who is not currently online.
     *
     * <p>Online players are skipped because the server would write their in-memory copy
     * straight back; they were already reset through the API by the caller.
     */
    private int deleteOfflineFiles(
            String directoryName, String suffix, List<Player> online, List<String> problems
    ) {
        Path directory = worldFolder.resolve(directoryName);
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        Set<UUID> skip = online.stream().map(Player::getUniqueId)
                .collect(java.util.stream.Collectors.toSet());
        int deleted = 0;
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                // ".dat_old" is the server's own backup of the same player, so it has
                // to go too or the next join restores what was just deleted.
                if (!name.endsWith(suffix) && !name.endsWith(suffix + "_old")) {
                    continue;
                }
                String identifier = name.substring(0, name.indexOf(suffix));
                UUID playerId;
                try {
                    playerId = UUID.fromString(identifier);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (skip.contains(playerId)) {
                    continue;
                }
                try {
                    if (Files.deleteIfExists(file) && name.endsWith(suffix)) {
                        deleted++;
                    }
                } catch (IOException exception) {
                    problems.add("Could not delete " + directoryName + "/" + name
                            + ": " + exception.getMessage());
                }
            }
        } catch (IOException exception) {
            problems.add("Could not list " + directoryName + ": " + exception.getMessage());
        }
        return deleted;
    }
}
