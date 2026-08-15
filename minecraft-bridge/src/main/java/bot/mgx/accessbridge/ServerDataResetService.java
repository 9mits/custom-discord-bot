package bot.mgx.accessbridge;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Recipe;
import org.bukkit.potion.PotionEffect;

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
    private final DiscordIdentityStore identities;
    private final PlayerSettingsStore settings;
    private final VerifiedApplicationStore verifiedApplications;
    private final VerificationEventStore verificationEvents;
    private final ProcessedActionStore processedActions;
    private final Path worldFolder;
    /** The server root, which is where whitelist.json and usercache.json live. */
    private final Path serverRoot;

    ServerDataResetService(
            MGXAccessBridge plugin,
            ClanStore clans,
            WealthStore wealth,
            RankSyncStore rankSync,
            DiscordIdentityStore identities,
            PlayerSettingsStore settings,
            VerifiedApplicationStore verifiedApplications,
            VerificationEventStore verificationEvents,
            ProcessedActionStore processedActions,
            Path worldFolder,
            Path serverRoot
    ) {
        this.plugin = plugin;
        this.clans = clans;
        this.wealth = wealth;
        this.rankSync = rankSync;
        this.identities = identities;
        this.settings = settings;
        this.verifiedApplications = verifiedApplications;
        this.verificationEvents = verificationEvents;
        this.processedActions = processedActions;
        this.worldFolder = worldFolder;
        this.serverRoot = serverRoot;
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
                clearRecipes(player);
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
            clear(ResetScope.WEALTH, wealth::clearAll, cleared, problems);
        }
        if (scopes.contains(ResetScope.IDENTITIES)) {
            clear(ResetScope.IDENTITIES, identities::clearAll, cleared, problems);
            // Nametags, the player list and chat all carry the name, so they have to be
            // redrawn rather than waiting for the next natural refresh.
            plugin.refreshClans();
        }
        if (scopes.contains(ResetScope.SETTINGS)) {
            clear(ResetScope.SETTINGS, settings::clearAll, cleared, problems);
        }
        if (scopes.contains(ResetScope.RANKS)) {
            // Only the record of what sync granted. Holds are deliberate operator
            // configuration, not accumulated data, and dropping them would quietly
            // hand somebody's hand-set group back to Discord to strip.
            clear(ResetScope.RANKS, rankSync::clearApplied, cleared, problems);
        }
        if (scopes.contains(ResetScope.ACCESS)) {
            int removed = 0;
            removed += clearQuietly(verifiedApplications::clearAll, "verified applications", problems);
            removed += clearQuietly(verificationEvents::clearAll, "queued verifications", problems);
            removed += clearQuietly(processedActions::clearAll, "processed actions", problems);
            removed += emptyJsonArray(serverRoot.resolve("whitelist.json"), problems);
            cleared.put(ResetScope.ACCESS, removed);
        }
        if (scopes.contains(ResetScope.USERCACHE)) {
            cleared.put(
                    ResetScope.USERCACHE,
                    emptyJsonArray(serverRoot.resolve("usercache.json"), problems)
            );
        }
        plugin.republishLeaderboard();
        return new Summary(cleared, problems);
    }

    private static void clear(
            ResetScope scope,
            java.util.function.IntSupplier action,
            Map<ResetScope, Integer> cleared,
            List<String> problems
    ) {
        try {
            cleared.put(scope, action.getAsInt());
        } catch (RuntimeException exception) {
            problems.add(scope.key() + " could not be cleared: " + exception.getMessage());
        }
    }

    private static int clearQuietly(
            java.util.function.IntSupplier action, String label, List<String> problems
    ) {
        try {
            return action.getAsInt();
        } catch (RuntimeException exception) {
            problems.add(label + " could not be cleared: " + exception.getMessage());
            return 0;
        }
    }

    /**
     * Truncates one of the server's own JSON list files to {@code []}.
     *
     * <p>Emptied rather than deleted: the server recreates a missing one, but an
     * operator reading the directory should still see the file it expects.
     *
     * @return 1 when the file held anything, so the receipt can say it did something
     */
    private static int emptyJsonArray(Path file, List<String> problems) {
        try {
            if (!Files.isRegularFile(file)) {
                return 0;
            }
            boolean hadContent = Files.readString(file).replaceAll("\\s", "").length() > 2;
            Files.writeString(file, "[]");
            return hadContent ? 1 : 0;
        } catch (IOException exception) {
            problems.add("Could not empty " + file.getFileName() + ": " + exception.getMessage());
            return 0;
        }
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
        player.getInventory().setItemInOffHand(null);
        player.getEnderChest().clear();
        player.setLevel(0);
        player.setExp(0f);
        player.setTotalExperience(0);
        player.setFoodLevel(20);
        player.setSaturation(5f);
        player.setFireTicks(0);
        player.setRemainingAir(player.getMaximumAir());
        for (PotionEffect effect : List.copyOf(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        AttributeInstance maximum = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        player.setHealth(maximum == null ? 20d : maximum.getValue());
        // A bed or anchor respawn is itself a trace of having played here.
        player.setRespawnLocation(null);
        player.teleport(player.getWorld().getSpawnLocation());
    }

    /**
     * Takes back every recipe a player has unlocked.
     *
     * <p>Recipes are granted by advancements and stored alongside them, so revoking
     * the advancements without this leaves the recipe book full — which is exactly the
     * kind of leftover that gives away that somebody had already played.
     */
    private void clearRecipes(Player player) {
        Iterator<Recipe> recipes = Bukkit.recipeIterator();
        List<NamespacedKey> keys = new ArrayList<>();
        while (recipes.hasNext()) {
            if (recipes.next() instanceof Keyed keyed) {
                keys.add(keyed.getKey());
            }
        }
        for (NamespacedKey key : keys) {
            try {
                player.undiscoverRecipe(key);
            } catch (IllegalArgumentException | NullPointerException ignored) {
                // A recipe the server knows but this player never had.
            }
        }
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
