package bot.mgx.accessbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerStatisticIncrementEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Builds ranked standings and pushes them to the Discord bot.
 *
 * <p>Push rather than request/response: the bot keeps the newest snapshot and edits its
 * permanent message from cache, so a dropped snapshot costs nothing — the next one
 * replaces it. That also caps how often the statistics files are read.
 */
final class LeaderboardService implements Listener {
    /** Top ten on every board — hologram, menu, and Discord. */
    private static final int ROWS = 10;
    /** Publish shortly after boot so a freshly placed board is not blank for minutes. */
    private static final long FIRST_PUBLISH_TICKS = 200L;

    private final MGXAccessBridge plugin;
    private final BridgeClient bridge;
    private final PlayerStatsService stats;
    private final ClanStore clans;
    private final ClanBattleStore clanBattles;
    private final PersonalNotificationService notifications;
    private final GameVariableStore variables;
    private final long refreshTicks;
    private final AtomicBoolean publishing = new AtomicBoolean();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private int taskId = -1;
    private volatile JsonObject latest = new JsonObject();
    private volatile Map<UUID, LeaderboardStandings.Standing> standings = Map.of();
    private volatile Map<LeaderboardStandings.BoardPlayer, LeaderboardStandings.Standing>
            individualStandings = Map.of();
    private volatile boolean standingsInitialized;
    private volatile Runnable publishObserver = () -> { };

    LeaderboardService(
            MGXAccessBridge plugin,
            BridgeClient bridge,
            PlayerStatsService stats,
            ClanStore clans,
            ClanBattleStore clanBattles,
            PersonalNotificationService notifications,
            GameVariableStore variables,
            long refreshTicks
    ) {
        this.plugin = plugin;
        this.bridge = bridge;
        this.stats = stats;
        this.clans = clans;
        this.clanBattles = clanBattles;
        this.notifications = notifications;
        this.variables = variables;
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

    /** Runs on the main thread after each fresh snapshot, for in-world displays. */
    void onPublished(Runnable observer) {
        publishObserver = observer == null ? () -> { } : observer;
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

    private Map<UUID, Long> snapshotOnlineKills() {
        Map<UUID, Long> onlineKills = new HashMap<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            onlineKills.put(
                    player.getUniqueId(),
                    (long) player.getStatistic(Statistic.PLAYER_KILLS)
            );
        }
        return onlineKills;
    }

    void stop() {
        if (taskId >= 0) {
            plugin.getServer().getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        refreshQueued.set(false);
    }

    /** Publishes immediately, off the timer. Must run on the main thread. */
    void publishNow() {
        publish();
    }

    /** Coalesces money and kill changes into one near-immediate leaderboard pass. */
    void refreshSoon() {
        if (!refreshQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                refreshQueued.set(false);
                publish();
            }, 20L);
        } catch (RuntimeException exception) {
            refreshQueued.set(false);
            throw exception;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStatisticIncrement(PlayerStatisticIncrementEvent event) {
        if (event.getStatistic() == Statistic.PLAYER_KILLS) {
            refreshSoon();
        }
    }

    private void publish() {
        if (!publishing.compareAndSet(false, true)) {
            refreshSoon();
            return;
        }
        Map<UUID, String> knownNames = snapshotKnownPlayerNames();
        Map<UUID, Long> onlineKills = snapshotOnlineKills();
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    buildAndSend(knownNames, onlineKills);
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

    private void buildAndSend(
            Map<UUID, String> knownNames,
            Map<UUID, Long> onlineKills
    ) {
        List<PlayerStats> everyone = stats.everyKnownPlayer(knownNames, onlineKills);
        Map<LeaderboardStandings.BoardPlayer, LeaderboardStandings.Standing> previous =
                individualStandings;
        Map<LeaderboardStandings.BoardPlayer, LeaderboardStandings.Standing> updated =
                LeaderboardStandings.individualByBoard(everyone);
        boolean announceChanges = standingsInitialized;
        individualStandings = updated;
        standings = LeaderboardStandings.bestByPlayer(updated);
        finalizeDragonRewards(updated);
        standingsInitialized = true;
        if (announceChanges) {
            plugin.getServer().getScheduler().runTask(
                    plugin, () -> announceImprovements(previous, updated)
            );
        }
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
        clan.add("clan_battle", rankClanBattle());
        clanBattles.active(clans).ifPresent(active -> {
            JsonObject event = new JsonObject();
            event.addProperty("id", active.id().toString());
            event.addProperty("kind", active.kind().id());
            event.addProperty("name", active.kind().displayName());
            event.addProperty("objective", active.kind().objective());
            event.addProperty("started_at", active.startedAt());
            event.addProperty("ends_at", active.endsAt());
            snapshot.add("clan_battle", event);
        });
        snapshot.add("individual", individual);
        snapshot.add("clan", clan);
        latest = snapshot;
        bridge.sendLeaderboardSnapshot(snapshot);
        plugin.getServer().getScheduler().runTask(plugin, publishObserver);
    }

    JsonObject latest() {
        return latest;
    }

    Optional<LeaderboardStandings.Standing> standing(UUID playerId) {
        return Optional.ofNullable(standings.get(playerId));
    }

    Optional<LeaderboardStandings.Standing> dragonStanding(UUID playerId) {
        return bestStanding(playerId, type -> type == LeaderboardType.DRAGON_DAMAGE
                || type == LeaderboardType.DRAGON_CRYSTALS);
    }

    Optional<LeaderboardStandings.Standing> standardStanding(UUID playerId) {
        return bestStanding(playerId, type -> type != LeaderboardType.DRAGON_DAMAGE
                && type != LeaderboardType.DRAGON_CRYSTALS
                && type != LeaderboardType.DRAGON_CRATES);
    }

    private Optional<LeaderboardStandings.Standing> bestStanding(
            UUID playerId, Predicate<LeaderboardType> accepts
    ) {
        return individualStandings.entrySet().stream()
                .filter(entry -> entry.getKey().playerId().equals(playerId))
                .map(Map.Entry::getValue)
                .filter(standing -> accepts.test(standing.type()))
                .min(Comparator.comparingInt(LeaderboardStandings.Standing::placement)
                        .thenComparing(standing -> standing.type().key()));
    }

    private void finalizeDragonRewards(
            Map<LeaderboardStandings.BoardPlayer, LeaderboardStandings.Standing> current
    ) {
        long endsAt = (long) variables.decimal("amethyst-events.ends-at") * 1_000L;
        if (System.currentTimeMillis() < endsAt) {
            return;
        }
        Map<UUID, Integer> rewards = new HashMap<>();
        current.forEach((player, standing) -> {
            if (standing.placement() > 3
                    || (standing.type() != LeaderboardType.DRAGON_DAMAGE
                    && standing.type() != LeaderboardType.DRAGON_CRYSTALS)) {
                return;
            }
            rewards.merge(player.playerId(), dragonShardReward(standing.placement()), Math::addExact);
        });
        if (clanBattles.queueShardRewardsOnce(
                "amethyst-dragon-expansion", rewards, "Amethyst Dragon Leaderboards"
        )) {
            plugin.getServer().getScheduler().runTask(
                    plugin, () -> plugin.clanBattles().deliverPendingShardRewards()
            );
        }
    }

    private int dragonShardReward(int placement) {
        return switch (placement) {
            case 1 -> variables.integer("leaderboard.dragon.first-shards");
            case 2 -> variables.integer("leaderboard.dragon.second-shards");
            case 3 -> variables.integer("leaderboard.dragon.third-shards");
            default -> 0;
        };
    }

    private void announceImprovements(
            Map<LeaderboardStandings.BoardPlayer, LeaderboardStandings.Standing> previous,
            Map<LeaderboardStandings.BoardPlayer, LeaderboardStandings.Standing> updated
    ) {
        updated.forEach((key, current) -> improvement(previous.get(key), current)
                .ifPresent(rise -> {
                    Player player = plugin.getServer().getPlayer(key.playerId());
                    if (player == null) {
                        return;
                    }
                    notifications.notify(
                            player,
                            leaderboardChat(rise),
                            leaderboardActionBar(rise)
                    );
                }));
    }

    static Optional<LeaderboardRise> improvement(
            LeaderboardStandings.Standing previous,
            LeaderboardStandings.Standing current
    ) {
        if (current == null
                || (previous != null && current.placement() >= previous.placement())) {
            return Optional.empty();
        }
        return Optional.of(new LeaderboardRise(previous, current));
    }

    private static Component leaderboardChat(LeaderboardRise rise) {
        LeaderboardStandings.Standing current = rise.current();
        Component prefix = Component.text(
                "LEADERBOARD » ", NamedTextColor.GOLD, TextDecoration.BOLD
        );
        String board = boardName(current.type());
        if (current.placement() <= 3) {
            String rewards = CosmeticCatalog.Category.values().length + " podium cosmetics";
            return prefix.append(Component.text(
                    "You reached #" + current.placement() + " on " + board + "! "
                            + "Your " + rewards + " are available in /cosmetics.",
                    NamedTextColor.GREEN
            ));
        }
        String movement = rise.previous() == null
                ? "You entered " + board + " at #" + current.placement() + "."
                : "You climbed from #" + rise.previous().placement() + " to #"
                        + current.placement() + " on " + board + ".";
        return prefix.append(Component.text(movement, NamedTextColor.GREEN));
    }

    private static Component leaderboardActionBar(LeaderboardRise rise) {
        LeaderboardStandings.Standing current = rise.current();
        if (current.placement() <= 3) {
            return Component.text(
                    "PODIUM UNLOCKED  •  #" + current.placement() + " "
                            + boardName(current.type()) + "  •  Check /cosmetics",
                    NamedTextColor.GOLD, TextDecoration.BOLD
            );
        }
        return Component.text(
                "LEADERBOARD UP  •  #" + current.placement() + " "
                        + boardName(current.type()),
                NamedTextColor.GREEN, TextDecoration.BOLD
        );
    }

    private static String boardName(LeaderboardType type) {
        return switch (type) {
            case WEALTH -> "Money $";
            case KILLS -> "Kills " + type.icon();
            case PLAYTIME -> "Playtime";
            case BLOCKS_MINED -> "Blocks Mined";
            case BLOCKS_WALKED -> "Blocks Walked";
            case AMETHYST_CRATES -> "Amethyst Crates Opened";
            case AMETHYST_AIRDROPS -> "Amethyst Airdrops Opened";
            case DRAGON_DAMAGE -> "Amethyst Dragon Damage";
            case DRAGON_CRYSTALS -> "End Crystals Broken";
            case DRAGON_CRATES -> "Dragon Crates Opened";
        };
    }

    record LeaderboardRise(
            LeaderboardStandings.Standing previous,
            LeaderboardStandings.Standing current
    ) {
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
            clans.clanOf(row.minecraftUuid()).ifPresent(view -> {
                entry.addProperty("clan", view.name());
                entry.addProperty("clan_icon", view.icon());
            });
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
            // Every clan row carries its id so a reader can open that clan. Without it
            // the name is only text, which is what made the boards unclickable.
            row.addProperty("clan_id", clan.id().toString());
            row.addProperty("clan", clan.name());
            row.addProperty("members", clan.members().size());
            row.addProperty("colour", clan.themeColor());
            row.addProperty("icon", clan.icon());
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
        Map<String, String> ids = new HashMap<>();
        Map<String, String> icons = new HashMap<>();
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
            ids.putIfAbsent(name, clan.get().id().toString());
            icons.putIfAbsent(name, clan.get().icon());
        }
        List<Map.Entry<String, Long>> ranked = new ArrayList<>(totals.entrySet());
        ranked.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        JsonArray rows = new JsonArray();
        for (Map.Entry<String, Long> entry : ranked) {
            if (rows.size() >= ROWS || entry.getValue() <= 0) {
                break;
            }
            JsonObject row = new JsonObject();
            row.addProperty("clan_id", ids.getOrDefault(entry.getKey(), ""));
            row.addProperty("clan", entry.getKey());
            row.addProperty("members", members.getOrDefault(entry.getKey(), 0));
            row.addProperty("colour", colours.getOrDefault(entry.getKey(), 0xFF9900));
            row.addProperty("icon", icons.getOrDefault(entry.getKey(), ClanIcon.DEFAULT.id()));
            row.addProperty("level", levels.getOrDefault(entry.getKey(), 0));
            row.addProperty("value", entry.getValue());
            row.addProperty("display", type.describe(entry.getValue()));
            rows.add(row);
        }
        return rows;
    }

    private JsonArray rankClanBattle() {
        JsonArray rows = new JsonArray();
        ClanBattleStore.ActiveView active = clanBattles.active(clans).orElse(null);
        if (active == null) {
            return rows;
        }
        for (ClanBattleStore.Standing standing : active.standings()) {
            if (rows.size() >= ROWS) {
                break;
            }
            JsonObject row = new JsonObject();
            row.addProperty("rank", standing.rank());
            row.addProperty("clan_id", standing.clanId().toString());
            row.addProperty("clan", standing.clanName());
            row.addProperty("members", standing.members().size());
            row.addProperty("colour", standing.colour());
            row.addProperty("icon", clans.findClanById(standing.clanId())
                    .map(ClanStore.ClanView::icon)
                    .orElse(ClanIcon.DEFAULT.id()));
            row.addProperty("level", standing.level());
            row.addProperty("value", standing.score());
            row.addProperty("display", String.format("%,d openings", standing.score()));
            row.addProperty("badges", clanBattles.badges(standing.clanId()).compact());
            rows.add(row);
        }
        return rows;
    }
}
