package bot.mgx.accessbridge;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.Orientable;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

/**
 * Queue-driven PvP over the original survival-inventory /pvp arena engine: teams, FFA,
 * anchored spectating, durable recovery, rating, and mode records.
 *
 * <p>{@link PvpDuelService} owns the real-world ring, terrain rollback and chunk
 * lifecycle for both private and queued fights. This class adds only the queue,
 * teams, mode results and lobby extension around that existing system.
 */
final class PvpCompetitionService implements Listener {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private static final long INVITE_MILLIS = 120_000L;
    private static final long PAD_COOLDOWN_MILLIS = 1_500L;
    private static final String ENTRANCE_DISPLAY_TAG = "mgx_pvp_entrance_display";
    private static final String ENTRANCE_TEXT_TAG = "mgx_pvp_entrance_text";
    private static final String ENTRANCE_FALLBACK_TAG = "mgx_pvp_entrance_fallback";
    private static final List<String> PLAYER_COMMANDS = List.of(
            "queue", "leave", "status", "party", "lobby", "return", "spectate",
            "live", "stats", "rankings", "rules", "rewards", "private", "forfeit", "giveup"
    );

    private enum Phase { COUNTDOWN, FIGHTING, AFTERMATH, ENDING }

    private static final class Party {
        UUID leader;
        final LinkedHashSet<UUID> members = new LinkedHashSet<>();

        Party(UUID leader) {
            this.leader = leader;
            members.add(leader);
        }
    }

    private record PartyInvite(UUID leader, long expiresAt) { }

    private record PendingStart(PvpMode mode, List<UUID> players) {
        PendingStart {
            players = List.copyOf(players);
        }
    }

    private static final class Match {
        final UUID id = UUID.randomUUID();
        final PvpMode mode;
        final PvpDuelService.PreparedArena arena;
        final List<UUID> first;
        final List<UUID> second;
        final List<UUID> players;
        final Map<UUID, Integer> team = new HashMap<>();
        final Set<UUID> alive = new LinkedHashSet<>();
        final Set<UUID> eliminated = new LinkedHashSet<>();
        final Set<UUID> spectators = new LinkedHashSet<>();
        final Map<UUID, Integer> kills = new HashMap<>();
        final Map<UUID, Double> damage = new HashMap<>();
        final Map<UUID, UUID> lastHitBy = new HashMap<>();
        final Map<UUID, Long> lastHitAt = new HashMap<>();
        final Map<UUID, Long> lastAction = new HashMap<>();
        final Map<UUID, Location> assigned = new HashMap<>();
        final Map<UUID, WorldBorder> previousBorders = new HashMap<>();
        BossBar bar;
        Phase phase = Phase.COUNTDOWN;
        long fightingSince;
        long endsAt;
        long nextShrinkAt;
        long nextArenaSweepAt;
        double borderSize;
        boolean shrinkWarned;
        BukkitTask countdown;
        BukkitTask returnTask;

        Match(PvpMode mode, PvpDuelService.PreparedArena arena, List<UUID> first, List<UUID> second) {
            this.mode = mode;
            this.arena = arena;
            this.first = List.copyOf(first);
            this.second = List.copyOf(second);
            List<UUID> combined = new ArrayList<>(first);
            combined.addAll(second);
            this.players = List.copyOf(combined);
            for (UUID player : first) team.put(player, 0);
            for (UUID player : second) team.put(player, mode.freeForAll() ? team.size() : 1);
            alive.addAll(players);
        }
    }

    private final MGXAccessBridge plugin;
    private final PvpDuelService duels;
    private final EconomyStore economy;
    private final SettingsClientSupport clientSupport;
    private final BedrockForms forms;
    private final DiscordIdentityService identities;
    private final ClanStore clans;
    private final PvpRecordStore records;
    private final PvpLobbyStore lobbyStore;
    private final PvpClanRecordStore clanRecords;
    private final PvpDuelStore recovery;
    private final Map<PvpMode, List<PvpMatchmaking.Entry>> queues = new EnumMap<>(PvpMode.class);
    private final Map<UUID, PvpMatchmaking.Entry> queuedPlayers = new HashMap<>();
    private final Map<UUID, PvpMode> queuedModes = new HashMap<>();
    private final Map<UUID, Party> parties = new HashMap<>();
    private final Map<UUID, PartyInvite> partyInvites = new HashMap<>();
    private final Map<UUID, Match> matches = new LinkedHashMap<>();
    private final Map<UUID, Match> matchByPlayer = new HashMap<>();
    /** When each mode's queue was last called out, so the shout cannot become spam. */
    private final Map<PvpMode, Long> announcedQueues = new EnumMap<>(PvpMode.class);
    private int boardTick;
    private final Map<UUID, Match> viewing = new HashMap<>();
    private final Map<UUID, PendingStart> preparing = new HashMap<>();
    private final Set<UUID> internalTeleports = new HashSet<>();
    private final Map<UUID, Long> padCooldowns = new HashMap<>();
    private final Map<UUID, Location> lobbyOrigins = new HashMap<>();
    /** Nether-portal blocks this service lit inside the registered SMP entrance. */
    private final Set<Location> entrancePortalBlocks = new HashSet<>();
    private final PvpFarmGuard farmGuard = new PvpFarmGuard();
    private BukkitTask clock;
    private boolean stopping;

    PvpCompetitionService(
            MGXAccessBridge plugin,
            PvpDuelService duels,
            EconomyStore economy,
            SettingsClientSupport clientSupport,
            BedrockForms forms,
            DiscordIdentityService identities,
            ClanStore clans,
            PvpRecordStore records,
            java.nio.file.Path lobbyFile,
            java.nio.file.Path clanRecordFile,
            java.nio.file.Path recoveryFile
    ) throws IOException {
        this.plugin = plugin;
        this.duels = duels;
        this.economy = economy;
        this.clientSupport = clientSupport;
        this.forms = forms;
        this.identities = identities;
        this.clans = clans;
        this.records = records;
        // Keep the legacy filename so version 8.0.0 lobby/portal coordinates migrate
        // in place; PvpLobbyStore deliberately ignores its obsolete arena array.
        this.lobbyStore = new PvpLobbyStore(lobbyFile);
        this.clanRecords = new PvpClanRecordStore(clanRecordFile);
        this.recovery = new PvpDuelStore(recoveryFile);
        loadConfiguredWorld();
        if (lobbyStore.needsLobbyBuild()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, this::installStarterLobby, 40L);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, this::refreshEntrancePortal, 1L);
        plugin.gameVariables().onChange(key -> {
            if (key.equals("pvp-competitive.lobby-title-scale")
                    || key.equals("pvp-competitive.lobby-line-scale")
                    || key.equals("pvp-competitive.lobby-gate-title-scale")
                    || key.equals("pvp-competitive.lobby-leaderboard-title-scale")) {
                plugin.getServer().getScheduler().runTask(plugin, () -> lobbyStore.lobby()
                        .ifPresent(lobby -> PvpLobbyBuilder.refreshHolograms(
                                lobby, plugin.gameVariables())));
            }
            if (key.equals("pvp-competitive.portal-light-radius")
                    || key.equals("pvp-competitive.portal-light-height")) {
                plugin.getServer().getScheduler().runTask(plugin, this::refreshEntrancePortal);
            } else if (key.equals("pvp-competitive.portal-display-height")
                    || key.equals("pvp-competitive.portal-title-scale")
                    || key.equals("pvp-competitive.portal-status-scale")
                    || key.equals("pvp-competitive.portal-title")
                    || key.equals("pvp-competitive.portal-status")) {
                plugin.getServer().getScheduler().runTask(plugin, this::refreshEntranceDisplay);
            }
        });
        clock = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private boolean enabled() {
        return plugin.gameVariables().bool("pvp-competitive.enabled");
    }

    boolean isParticipant(UUID playerId) {
        return matchByPlayer.containsKey(playerId) || viewing.containsKey(playerId);
    }

    boolean isFighter(UUID playerId) {
        return matchByPlayer.containsKey(playerId);
    }

    boolean busy(UUID playerId) {
        return isParticipant(playerId) || queuedPlayers.containsKey(playerId)
                || preparing.containsKey(playerId);
    }

    boolean areOpponents(UUID first, UUID second) {
        Match match = matchByPlayer.get(first);
        if (match == null || match != matchByPlayer.get(second) || first.equals(second)) return false;
        return match.mode.freeForAll() || !match.team.get(first).equals(match.team.get(second));
    }

    /** Returns true when this command belongs to the competitive layer. */
    boolean handleCommand(Player player, String[] original) {
        String[] args = original == null ? new String[0] : original;
        if (isParticipant(player.getUniqueId())) {
            handleParticipantCommand(player, args);
            return true;
        }
        if (args.length == 0) {
            if (queuedPlayers.containsKey(player.getUniqueId())
                    || preparing.containsKey(player.getUniqueId())) {
                openQueueStatus(player);
                return true;
            }
            return false;
        }
        String root = args[0].toLowerCase(Locale.ROOT);
        if ((queuedPlayers.containsKey(player.getUniqueId())
                || preparing.containsKey(player.getUniqueId()))
                && !List.of("leave", "status", "return", "exit").contains(root)) {
            openQueueStatus(player);
            return true;
        }
        switch (root) {
            case "queue", "play" -> {
                if (args.length < 2) openModes(player);
                else PvpMode.from(args[1]).filter(PvpMode::queueable)
                        .ifPresentOrElse(mode -> joinQueue(player, mode,
                                        args.length < 3 || !args[2].equalsIgnoreCase("nofill")),
                                () -> error(player, "Choose casual, ranked, 2v2, 3v3, clan, or ffa."));
                return true;
            }
            case "leave" -> {
                if (!leaveQueue(player.getUniqueId(), true)) {
                    error(player, "You are not in a competitive queue.");
                }
                return true;
            }
            case "status" -> {
                showStatus(player);
                return true;
            }
            case "party" -> {
                handleParty(player, slice(args, 1));
                return true;
            }
            case "lobby", "hub" -> {
                enterLobby(player);
                return true;
            }
            case "return", "exit" -> {
                returnToServer(player);
                return true;
            }
            case "stats" -> {
                openStats(player);
                return true;
            }
            case "rank", "ranks", "ranking", "rankings", "leaderboard" -> {
                openRankings(player);
                return true;
            }
            case "rules", "rewards" -> {
                openRules(player);
                return true;
            }
            case "spectate", "watch" -> {
                if (args.length < 2) return false;
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null || !matchByPlayer.containsKey(target.getUniqueId())) return false;
                spectate(player, target.getName());
                return true;
            }
            case "live", "matches" -> {
                openLive(player);
                return true;
            }
            case "portal" -> {
                handleAdmin(player, args);
                return true;
            }
            case "admin" -> {
                handleAdmin(player, slice(args, 1));
                return true;
            }
            case "private", "challenge", "fight", "accept", "decline", "deny" -> {
                return false;
            }
            case "forfeit", "giveup", "surrender", "ff" -> {
                error(player, "You are not in a competitive match.");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> roots = new ArrayList<>(PLAYER_COMMANDS);
            if (isAdmin(sender)) roots.addAll(List.of("portal", "admin"));
            return partial(args[0], roots);
        }
        if (args.length == 2 && List.of("queue", "play").contains(args[0].toLowerCase(Locale.ROOT))) {
            return partial(args[1], List.of("casual", "ranked", "2v2", "3v3", "clan", "ffa"));
        }
        if (args.length == 3 && List.of("queue", "play").contains(args[0].toLowerCase(Locale.ROOT))) {
            return partial(args[2], List.of("fill", "nofill"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("party")) {
            return partial(args[1], List.of("invite", "accept", "leave", "kick", "disband"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("party")
                && List.of("invite", "kick").contains(args[1].toLowerCase(Locale.ROOT))) {
            return partial(args[2], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        if (isAdmin(sender)) return adminTabs(args);
        return List.of();
    }

    private void handleParticipantCommand(Player player, String[] args) {
        Match match = matchByPlayer.get(player.getUniqueId());
        if (match != null) {
            if (args.length > 0 && List.of("forfeit", "giveup", "surrender", "ff", "leave")
                    .contains(args[0].toLowerCase(Locale.ROOT))) {
                if (match.phase == Phase.FIGHTING) {
                    eliminate(match, player.getUniqueId(), null, "forfeited");
                } else if (match.phase == Phase.COUNTDOWN) {
                    abortStart(match, "A player left before the fight began.");
                } else {
                    info(player, "Your result is already decided.");
                }
            } else {
                info(player, matchSummary(match));
            }
            return;
        }
        if (viewing.containsKey(player.getUniqueId())) {
            if (args.length == 0 || args[0].equalsIgnoreCase("leave")) leaveSpectator(player, true);
            else error(player, "Use /pvp leave to stop spectating.");
        }
    }

    private void joinQueue(Player player, PvpMode mode, boolean fill) {
        UUID playerId = player.getUniqueId();
        if (!enabled()) {
            error(player, "Competitive PvP is currently disabled.");
            return;
        }
        if (plugin.inPvpDuel(player)) {
            error(player, "Finish your private fight first.");
            return;
        }
        if (queuedPlayers.containsKey(playerId)) {
            error(player, "Leave your current queue before joining another one.");
            return;
        }
        if (recovery.find(playerId).isPresent()) {
            recover(player, true);
            return;
        }
        Party party = parties.get(playerId);
        List<UUID> members = party == null ? List.of(playerId) : List.copyOf(party.members);
        if (party != null && !party.leader.equals(playerId)) {
            error(player, "Only the party leader can queue the team.");
            return;
        }
        if (mode.freeForAll() && members.size() != 1) {
            error(player, "FFA is a solo queue. Leave your party first.");
            return;
        }
        if (members.size() > mode.teamSize()) {
            error(player, mode.display() + " allows at most " + mode.teamSize() + " player(s) per team.");
            return;
        }
        if (!fill && members.size() != mode.teamSize()) {
            error(player, "No-fill requires a complete team of " + mode.teamSize() + ".");
            return;
        }
        if (mode.clan()) {
            if (members.size() != mode.teamSize()) {
                error(player, "Clan vs Clan requires a complete party of " + mode.teamSize() + ".");
                return;
            }
            Optional<ClanStore.ClanView> clan = clans.clanOf(playerId);
            if (clan.isEmpty() || members.stream().anyMatch(id -> !clan.get().members().containsKey(id))) {
                error(player, "Every party member must belong to the same clan.");
                return;
            }
        }
        for (UUID memberId : members) {
            Player member = Bukkit.getPlayer(memberId);
            if (member == null || !member.isOnline()) {
                error(player, "Every party member must be online.");
                return;
            }
            if (busy(memberId) || plugin.inPvpDuel(member)) {
                error(player, name(memberId) + " is already busy with PvP.");
                return;
            }
            if (VerificationLobbyService.isLobbyWorld(member.getWorld())
                    || plugin.inScreenshotMode(member)) {
                error(player, name(memberId) + " cannot queue from their current mode.");
                return;
            }
            if (plugin.afkService() != null && plugin.afkService().inCombat(member)) {
                error(player, name(memberId) + " must finish their current combat tag first.");
                return;
            }
        }
        UUID clanId = mode.clan() ? clans.clanOf(playerId).orElseThrow().id() : null;
        long totalRating = members.stream().mapToLong(id -> records.of(id).rating()).sum();
        PvpMatchmaking.Entry entry = new PvpMatchmaking.Entry(
                party == null ? playerId : party.leader,
                members, fill, System.currentTimeMillis(), totalRating / members.size(), clanId
        );
        queues.computeIfAbsent(mode, ignored -> new ArrayList<>()).add(entry);
        for (UUID memberId : members) {
            queuedPlayers.put(memberId, entry);
            queuedModes.put(memberId, mode);
            Player member = Bukkit.getPlayer(memberId);
            if (member != null) {
                info(member, "Queued for " + mode.display() + (fill ? " with fill." : " with no-fill."));
                teleportToLobbyIfReady(member);
            }
        }
        tryMatch(mode, System.currentTimeMillis());
    }

    private boolean leaveQueue(UUID playerId, boolean announce) {
        PvpMatchmaking.Entry entry = queuedPlayers.get(playerId);
        if (entry == null) {
            PendingStart pending = preparing.get(playerId);
            if (pending == null) return false;
            clearPending(pending);
            if (announce) notifyPlayers(pending.players(),
                    "The " + pending.mode().display() + " match preparation was cancelled.");
            return true;
        }
        PvpMode mode = queuedModes.get(playerId);
        List<PvpMatchmaking.Entry> queue = mode == null ? null : queues.get(mode);
        if (queue != null) queue.remove(entry);
        for (UUID memberId : entry.members()) {
            queuedPlayers.remove(memberId);
            queuedModes.remove(memberId);
            if (announce) {
                Player member = Bukkit.getPlayer(memberId);
                if (member != null) info(member, "Left the " + (mode == null ? "PvP" : mode.display()) + " queue.");
            }
        }
        return true;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        lobbyStore.lobby().ifPresent(lobby -> {
            PvpLobbyBuilder.pulse(lobby,
                    integer("pvp-competitive.lobby-portal-particle-count"));
            PvpLobbyBuilder.updateLabelViewers(lobby, plugin, clientSupport,
                    decimal("pvp-competitive.lobby-label-view-distance"),
                    decimal("pvp-competitive.lobby-board-view-distance"),
                    decimal("pvp-competitive.lobby-leaderboard-view-distance"));
            PvpLobbyBuilder.refreshStatus(lobby, this::gateStatus);
            // The boards move far more slowly than a queue count; every fifth tick is
            // plenty and keeps the entity work off the other four.
            if (boardTick++ % 5 == 0) {
                PvpLobbyBuilder.refreshBoards(lobby, leaderboardBoards(), liveBoard());
            }
        });
        pulseEntrancePortal();
        updateEntranceLabelViewers();
        suppressCustomPortalTravel();
        announceWaiting(now);
        partyInvites.entrySet().removeIf(row -> row.getValue().expiresAt() <= now);
        validateQueues();
        for (PvpMode mode : PvpMode.values()) {
            if (mode.queueable()) tryMatch(mode, now);
        }
        for (Match match : List.copyOf(matches.values())) {
            tickMatch(match, now);
        }
    }

    /**
     * How many players could actually take part right now.
     *
     * <p>Counts everybody online who is not already in a match, because that is the
     * number that decides whether a queue can ever fill. On a server whose busiest hour
     * holds single figures this is the fact that matters most, and until now it was the
     * one thing the lobby never said.
     */
    private int availablePlayers() {
        int available = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isParticipant(player.getUniqueId())) available++;
        }
        return available;
    }

    private int queuedFor(PvpMode mode) {
        return queues.getOrDefault(mode, List.of()).stream()
                .mapToInt(entry -> entry.members().size()).sum();
    }

    int requiredPlayers(PvpMode mode) {
        return mode.minimumPlayers(integer("pvp-competitive.ffa-minimum-players"));
    }

    /** True when this mode cannot start however long anybody waits. */
    boolean shortStaffed(PvpMode mode) {
        return availablePlayers() < requiredPlayers(mode);
    }

    /** One line of plain truth, written on the arch and repeated in the menu. */
    String queueSummary(PvpMode mode) {
        if (!enabled()) return "PvP is closed right now";
        int required = requiredPlayers(mode);
        int queued = queuedFor(mode);
        int available = availablePlayers();
        if (available < required) {
            return "Needs " + required + " players - " + available + " free online";
        }
        if (queued == 0) return "Nobody waiting - be the first";
        int missing = Math.max(0, required - queued);
        return missing == 0
                ? queued + " waiting - starting now"
                : queued + " waiting - needs " + missing + " more";
    }

    private Map<PvpLobbyBuilder.LeaderboardBoard, List<Component>> leaderboardBoards() {
        Map<PvpLobbyBuilder.LeaderboardBoard, List<Component>> boards =
                new EnumMap<>(PvpLobbyBuilder.LeaderboardBoard.class);
        boards.put(PvpLobbyBuilder.LeaderboardBoard.RATING, ratingBoard());
        boards.put(PvpLobbyBuilder.LeaderboardBoard.WINS,
                playerRecordBoard(PvpRecordStore.Record::wins, "wins"));
        boards.put(PvpLobbyBuilder.LeaderboardBoard.KILLS,
                playerRecordBoard(PvpRecordStore.Record::kills, "kills"));
        boards.put(PvpLobbyBuilder.LeaderboardBoard.STREAK,
                playerRecordBoard(PvpRecordStore.Record::bestStreak, "streak"));
        boards.put(PvpLobbyBuilder.LeaderboardBoard.CLAN_WINS,
                clanRecordBoard(PvpClanRecordStore.Record::wins, "wins"));
        boards.put(PvpLobbyBuilder.LeaderboardBoard.CLAN_KILLS,
                clanRecordBoard(PvpClanRecordStore.Record::kills, "kills"));
        return Map.copyOf(boards);
    }

    /** The top of the rating ladder, including each player's permanent rank badge. */
    private List<Component> ratingBoard() {
        List<PvpRankLeaderboard.Row> top = PvpRankLeaderboard.top(
                records.all(), PvpCompetitionService::name, PvpLobbyBuilder.boardLines());
        if (top.isEmpty()) {
            return List.of(Component.text("No rated fights yet - the first one names you",
                    NamedTextColor.GRAY));
        }
        List<Component> lines = new ArrayList<>();
        for (int index = 0; index < top.size(); index++) {
            PvpRankLeaderboard.Row row = top.get(index);
            lines.add(Component.text((index + 1) + ". ", ORANGE, TextDecoration.BOLD)
                    .append(Component.text(row.username() + " ", NamedTextColor.WHITE))
                    .append(BadgeIcons.glyph(row.record().rank().glyph()))
                    .append(Component.text(" " + row.record().rank().display()
                            + " (" + row.record().rating() + ")", NamedTextColor.GRAY)));
        }
        return List.copyOf(lines);
    }

    private List<Component> playerRecordBoard(
            ToLongFunction<PvpRecordStore.Record> metric, String unit
    ) {
        List<Map.Entry<UUID, PvpRecordStore.Record>> top = records.all().entrySet().stream()
                .filter(row -> metric.applyAsLong(row.getValue()) > 0L)
                .sorted(Comparator.<Map.Entry<UUID, PvpRecordStore.Record>>comparingLong(
                                row -> metric.applyAsLong(row.getValue())).reversed()
                        .thenComparing(row -> name(row.getKey()), String.CASE_INSENSITIVE_ORDER))
                .limit(PvpLobbyBuilder.boardLines()).toList();
        if (top.isEmpty()) return emptyBoard();
        List<Component> lines = new ArrayList<>();
        for (int index = 0; index < top.size(); index++) {
            Map.Entry<UUID, PvpRecordStore.Record> row = top.get(index);
            lines.add(boardLine(index, name(row.getKey()), metric.applyAsLong(row.getValue()), unit));
        }
        return List.copyOf(lines);
    }

    private List<Component> clanRecordBoard(
            ToLongFunction<PvpClanRecordStore.Record> metric, String unit
    ) {
        List<Map.Entry<UUID, PvpClanRecordStore.Record>> top = clanRecords.all().entrySet().stream()
                .filter(row -> metric.applyAsLong(row.getValue()) > 0L)
                .sorted(Comparator.<Map.Entry<UUID, PvpClanRecordStore.Record>>comparingLong(
                                row -> metric.applyAsLong(row.getValue())).reversed()
                        .thenComparing(row -> clanName(row.getKey()), String.CASE_INSENSITIVE_ORDER))
                .limit(PvpLobbyBuilder.boardLines()).toList();
        if (top.isEmpty()) return emptyBoard();
        List<Component> lines = new ArrayList<>();
        for (int index = 0; index < top.size(); index++) {
            Map.Entry<UUID, PvpClanRecordStore.Record> row = top.get(index);
            lines.add(boardLine(index, clanName(row.getKey()),
                    metric.applyAsLong(row.getValue()), unit));
        }
        return List.copyOf(lines);
    }

    private static Component boardLine(int index, String name, long value, String unit) {
        return Component.text("#" + (index + 1) + " ", ORANGE, TextDecoration.BOLD)
                .append(Component.text(name + " • ", NamedTextColor.WHITE))
                .append(Component.text(value + " " + unit, NamedTextColor.GRAY));
    }

    private static List<Component> emptyBoard() {
        return List.of(Component.text("No results yet • claim #1", NamedTextColor.GRAY));
    }

    private String clanName(UUID clanId) {
        return clans.findClanById(clanId).map(ClanStore.ClanView::name)
                .orElse(clanId.toString().substring(0, 8));
    }

    /** What is happening right now, for the pavilion that promises that. */
    private List<Component> liveBoard() {
        List<Component> lines = new ArrayList<>();
        int fights = matches.size() + plugin.liveDuelCount();
        lines.add(Component.text(fights == 0 ? "No fights in progress"
                        : fights + (fights == 1 ? " fight in progress" : " fights in progress"),
                fights == 0 ? NamedTextColor.GRAY : NamedTextColor.GREEN, TextDecoration.BOLD));
        for (PvpMode mode : PvpMode.values()) {
            if (!mode.queueable()) continue;
            int queued = queuedFor(mode);
            if (queued == 0) continue;
            lines.add(Component.text(mode.display() + ": ", NamedTextColor.WHITE)
                    .append(Component.text(queued + " waiting", ORANGE)));
        }
        if (lines.size() == 1) {
            lines.add(Component.text(availablePlayers() + " player(s) free to fight",
                    NamedTextColor.GRAY));
            lines.add(Component.text("Nobody is queued - walk into a gateway",
                    NamedTextColor.GRAY));
        }
        return lines.size() > PvpLobbyBuilder.liveBoardLines()
                ? lines.subList(0, PvpLobbyBuilder.liveBoardLines()) : List.copyOf(lines);
    }

    private Component gateStatus(PvpMode mode) {
        int required = requiredPlayers(mode);
        int queued = queuedFor(mode);
        int available = availablePlayers();
        String line;
        if (!enabled()) line = "CLOSED";
        else if (available < required) line = available + " ONLINE • " + required + " NEEDED";
        else if (queued == 0) line = "READY • WALK THROUGH";
        else {
            int missing = Math.max(0, required - queued);
            line = missing == 0 ? queued + "/" + required + " QUEUED • STARTING"
                    : queued + "/" + required + " QUEUED • " + missing + " TO START";
        }
        NamedTextColor colour = !enabled() || shortStaffed(mode) ? NamedTextColor.RED
                : queuedFor(mode) > 0 ? NamedTextColor.GREEN : NamedTextColor.GRAY;
        return Component.text(line, colour, TextDecoration.BOLD);
    }

    /**
     * Tells the server when somebody starts waiting.
     *
     * <p>A queue nobody can see is a queue nobody joins. With a handful of players
     * online, one person waiting in silence is the single most common way a fight fails
     * to happen, so the first entry into an empty queue is announced once, and then not
     * again for that mode until the cooldown passes.
     */
    private void announceWaiting(long now) {
        if (!enabled() || !plugin.gameVariables().bool("pvp-competitive.announce-queues")) return;
        long cooldown = integer("pvp-competitive.queue-announce-cooldown-seconds") * 1_000L;
        for (PvpMode mode : PvpMode.values()) {
            if (!mode.queueable()) continue;
            int queued = queuedFor(mode);
            if (queued == 0) {
                announcedQueues.remove(mode);
                continue;
            }
            if (shortStaffed(mode)) continue;
            Long announcedAt = announcedQueues.get(mode);
            if (announcedAt != null && now - announcedAt < cooldown) continue;
            announcedQueues.put(mode, now);
            Component line = Component.text("PVP ", ORANGE, TextDecoration.BOLD)
                    .append(Component.text(queued + " waiting for " + mode.display()
                            + " - use /pvp to join", NamedTextColor.WHITE));
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (isParticipant(player.getUniqueId())
                        || queuedPlayers.containsKey(player.getUniqueId())
                        || plugin.inPvpDuel(player)) {
                    continue;
                }
                player.sendMessage(line);
                player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BELL, 0.4f, 1.6f);
            }
        }
    }

    private void validateQueues() {
        for (Map.Entry<PvpMode, List<PvpMatchmaking.Entry>> row : queues.entrySet()) {
            for (PvpMatchmaking.Entry entry : List.copyOf(row.getValue())) {
                boolean valid = entry.members().stream().allMatch(id -> {
                    Player player = Bukkit.getPlayer(id);
                    return player != null && player.isOnline() && queuedPlayers.get(id) == entry;
                });
                if (valid && row.getKey().clan()) {
                    valid = entry.clanId() != null && entry.members().stream().allMatch(id ->
                            clans.clanOf(id).map(ClanStore.ClanView::id)
                                    .filter(entry.clanId()::equals).isPresent());
                }
                if (!valid) removeEntry(row.getKey(), entry,
                        "Your PvP queue ended because a teammate left or the clan roster changed.");
            }
        }
    }

    private void tryMatch(PvpMode mode, long now) {
        if (!enabled()) return;
        while (true) {
            List<PvpMatchmaking.Entry> queue = queues.getOrDefault(mode, List.of());
            if (queue.isEmpty()) return;
            if (mode.freeForAll()) {
                if (!tryFfa(mode, queue, now)) return;
                continue;
            }
            if (mode.clan()) {
                if (!tryClan(mode, queue, now)) return;
                continue;
            }
            long base = mode.rated() ? integer("pvp-competitive.matchmaking-base-range") : 10_000_000L;
            long widen = mode.rated() ? integer("pvp-competitive.matchmaking-widen-per-second") : 0L;
            long maximum = mode.rated() ? integer("pvp-competitive.matchmaking-maximum-range") : 10_000_000L;
            Optional<PvpMatchmaking.Plan> plan = PvpMatchmaking.teams(
                    queue, mode.teamSize(), now, base, widen, maximum,
                    (first, second) -> opponentsAllowed(first, second, now)
            );
            if (plan.isEmpty()) return;
            startPlan(mode, plan.get().firstEntries(), plan.get().secondEntries());
        }
    }

    private boolean tryClan(PvpMode mode, List<PvpMatchmaking.Entry> queue, long now) {
        List<PvpMatchmaking.Entry> sorted = queue.stream()
                .sorted(Comparator.comparingLong(PvpMatchmaking.Entry::joinedAt)).toList();
        for (int first = 0; first < sorted.size(); first++) {
            for (int second = first + 1; second < sorted.size(); second++) {
                PvpMatchmaking.Entry left = sorted.get(first);
                PvpMatchmaking.Entry right = sorted.get(second);
                if (left.clanId() != null && right.clanId() != null
                        && !left.clanId().equals(right.clanId())
                        && opponentsAllowed(left.members(), right.members(), now)) {
                    startPlan(mode, List.of(left), List.of(right));
                    return true;
                }
            }
        }
        return false;
    }

    private boolean tryFfa(PvpMode mode, List<PvpMatchmaking.Entry> queue, long now) {
        int minimum = mode.minimumPlayers(integer("pvp-competitive.ffa-minimum-players"));
        if (queue.size() < minimum) return false;
        List<PvpMatchmaking.Entry> sorted = queue.stream()
                .sorted(Comparator.comparingLong(PvpMatchmaking.Entry::joinedAt)).toList();
        long waited = now - sorted.get(0).joinedAt();
        if (queue.size() < mode.maximumPlayers()
                && waited < integer("pvp-competitive.ffa-start-wait-seconds") * 1_000L) return false;
        List<PvpMatchmaking.Entry> selected = new ArrayList<>();
        for (PvpMatchmaking.Entry candidate : sorted) {
            if (selected.size() >= mode.maximumPlayers()) break;
            boolean blockedPair = selected.stream().anyMatch(existing ->
                    !opponentsAllowed(existing.members(), candidate.members(), now));
            if (!blockedPair) selected.add(candidate);
        }
        if (selected.size() < minimum) return false;
        List<UUID> first = selected.stream().flatMap(entry -> entry.members().stream()).toList();
        startPlan(mode, selected, List.of(), first, List.of());
        return true;
    }

    private void startPlan(
            PvpMode mode,
            List<PvpMatchmaking.Entry> firstEntries,
            List<PvpMatchmaking.Entry> secondEntries
    ) {
        startPlan(mode, firstEntries, secondEntries,
                flatten(firstEntries), flatten(secondEntries));
    }

    private void startPlan(
            PvpMode mode,
            List<PvpMatchmaking.Entry> firstEntries,
            List<PvpMatchmaking.Entry> secondEntries,
            List<UUID> first,
            List<UUID> second
    ) {
        List<PvpMatchmaking.Entry> allEntries = new ArrayList<>(firstEntries);
        allEntries.addAll(secondEntries);
        for (PvpMatchmaking.Entry entry : allEntries) removeEntry(mode, entry, null);
        if (!playersReady(first) || !playersReady(second)) {
            notifyPlayers(concat(first, second), "The match could not start because somebody left.");
            return;
        }
        List<UUID> all = concat(first, second);
        PendingStart pending = new PendingStart(mode, all);
        for (UUID playerId : all) preparing.put(playerId, pending);
        List<Player> firstPlayers = first.stream().map(Bukkit::getPlayer)
                .filter(java.util.Objects::nonNull).toList();
        List<Player> secondPlayers = second.stream().map(Bukkit::getPlayer)
                .filter(java.util.Objects::nonNull).toList();
        duels.prepareCompetitiveArena(mode, firstPlayers, secondPlayers,
                arena -> {
                    boolean current = all.stream().allMatch(id -> preparing.get(id) == pending);
                    clearPending(pending);
                    if (!current || !playersReady(all)) {
                        duels.discardCompetitiveArena(arena);
                        if (current) notifyPlayers(all,
                                "The match could not start because somebody left.");
                        return;
                    }
                    startMatch(mode, arena, first, second);
                }, reason -> {
                    boolean current = all.stream().anyMatch(id -> preparing.get(id) == pending);
                    clearPending(pending);
                    if (current) notifyPlayers(all, reason);
                });
    }

    private void startMatch(
            PvpMode mode, PvpDuelService.PreparedArena arena, List<UUID> first, List<UUID> second
    ) {
        Match match = new Match(mode, arena, first, second);
        List<UUID> all = match.players;
        duels.activateCompetitiveArena(arena, all);
        Map<UUID, PvpDuelStore.Recovery> snapshots = new LinkedHashMap<>();
        for (UUID playerId : all) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || recovery.find(playerId).isPresent()) {
                notifyPlayers(all, "The match could not save every player's state.");
                duels.releaseCompetitiveArena(arena.id());
                return;
            }
            snapshots.put(playerId, snapshot(match.id, PvpDuelStore.Role.FIGHTER, player));
        }
        try {
            recovery.putAll(snapshots);
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Could not save competitive PvP recovery: " + failure.getMessage());
            notifyPlayers(all, "The match could not safely save player inventories.");
            duels.releaseCompetitiveArena(arena.id());
            return;
        }
        matches.put(match.id, match);
        for (UUID playerId : all) matchByPlayer.put(playerId, match);
        for (UUID playerId : all) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) continue;
            String opponents = (mode.freeForAll() ? all.stream().filter(id -> !id.equals(playerId))
                    : (match.team.get(playerId) == 0 ? second.stream() : first.stream()))
                    .map(PvpCompetitionService::name).reduce((a, b) -> a + ", " + b)
                    .orElse("the field");
            info(player, "Match found — " + mode.display() + ". Opponent"
                    + (opponents.contains(",") ? "s: " : ": ") + opponents + ".");
        }
        try {
            int slot = 0;
            for (UUID playerId : all) {
                Player player = Bukkit.getPlayer(playerId);
                Location start = arena.spawns().get(slot++);
                if (player == null || start == null || !prepareFighter(match, player, start)) {
                    throw new IllegalStateException("An arena spawn was unavailable.");
                }
            }
        } catch (RuntimeException failure) {
            abortStart(match, "The arena could not prepare safely.");
            plugin.getLogger().warning("Competitive PvP start failed: " + failure.getMessage());
            return;
        }
        match.borderSize = arena.arena().diameter();
        match.bar = BossBar.bossBar(
                Component.text(mode.display() + "  •  GET READY", NamedTextColor.GOLD),
                1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS
        );
        forEachOnline(match.players, player -> plugin.bossBars().showExclusive(player, match.bar));
        startCountdown(match, integer("pvp-competitive.countdown-seconds"));
    }

    private boolean prepareFighter(Match match, Player player, Location start) {
        UUID playerId = player.getUniqueId();
        match.previousBorders.put(playerId, player.getWorldBorder());
        match.assigned.put(playerId, start.clone());
        match.lastAction.put(playerId, System.currentTimeMillis());
        plugin.bossBars().suppress(player);
        player.closeInventory();
        player.closeDialog();
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setInvulnerable(true);
        player.setInvisible(false);
        player.setCollidable(true);
        player.setCanPickupItems(true);
        player.setFireTicks(0);
        player.setFallDistance(0f);
        player.setFoodLevel(20);
        player.setSaturation(20f);
        if (player.getAttribute(Attribute.MAX_HEALTH) != null) {
            player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
        }
        player.setWorldBorder(personalBorder(match, match.arena.arena().diameter()));
        boolean moved = teleport(player, start);
        if (moved) {
            player.playSound(player, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.4f);
            player.sendMessage(prefix().append(Component.text(
                    "KEEP INVENTORY is active. You are fighting with your own survival loadout.",
                    NamedTextColor.GREEN)));
        }
        return moved;
    }

    private void startCountdown(Match match, int seconds) {
        final int[] remaining = {Math.max(1, seconds)};
        match.countdown = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (match.phase != Phase.COUNTDOWN) {
                if (match.countdown != null) match.countdown.cancel();
                return;
            }
            if (remaining[0] <= 0) {
                match.countdown.cancel();
                match.countdown = null;
                beginFight(match);
                return;
            }
            match.bar.name(Component.text(
                    match.mode.display() + "  •  STARTS IN " + remaining[0], NamedTextColor.GOLD));
            forEachOnline(match.players, player -> {
                player.showTitle(Title.title(
                        Component.text(String.valueOf(remaining[0]), NamedTextColor.GOLD,
                                TextDecoration.BOLD),
                        Component.text(teamLabel(match, player.getUniqueId()), NamedTextColor.GRAY),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(850), Duration.ofMillis(100))
                ));
                player.playSound(player, Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f,
                        0.8f + (seconds - remaining[0]) * 0.08f);
            });
            remaining[0]--;
        }, 0L, 20L);
    }

    private void beginFight(Match match) {
        match.phase = Phase.FIGHTING;
        duels.setCompetitiveArenaActive(match.arena.id(), true);
        match.fightingSince = System.currentTimeMillis();
        match.endsAt = match.fightingSince
                + integer("pvp-competitive.match-minutes") * 60_000L;
        match.nextShrinkAt = match.fightingSince
                + integer("pvp-competitive.ffa-shrink-delay-seconds") * 1_000L;
        match.nextArenaSweepAt = match.fightingSince + 5_000L;
        forEachOnline(match.players, player -> {
            player.setInvulnerable(false);
            match.lastAction.put(player.getUniqueId(), match.fightingSince);
            player.showTitle(Title.title(
                    Component.text("FIGHT!", NamedTextColor.RED, TextDecoration.BOLD),
                    Component.text(match.mode.display(), NamedTextColor.GOLD),
                    Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(300))
            ));
            player.playSound(player, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.65f, 1.35f);
        });
        updateBar(match, match.fightingSince);
    }

    private void tickMatch(Match match, long now) {
        if (match.phase != Phase.FIGHTING) return;
        if (now >= match.nextArenaSweepAt) {
            duels.maintainCompetitiveArena(match.arena.id());
            match.nextArenaSweepAt = now + 5_000L;
        }
        if (now >= match.endsAt) {
            finish(match, List.of(), "Time expired — draw", false);
            return;
        }
        long afkMillis = integer("pvp-competitive.afk-seconds") * 1_000L;
        List<UUID> inactive = match.alive.stream().filter(playerId ->
                now - match.lastAction.getOrDefault(playerId, match.fightingSince) >= afkMillis
        ).toList();
        if (!inactive.isEmpty() && inactive.size() == match.alive.size()) {
            match.eliminated.addAll(inactive);
            match.alive.clear();
            notifyMatch(match, "Every remaining player was inactive — draw.", NamedTextColor.GRAY);
            finish(match, List.of(), "Inactivity — draw", false);
            return;
        }
        for (UUID playerId : inactive) {
            eliminate(match, playerId, null, "was eliminated for inactivity");
            if (match.phase != Phase.FIGHTING) return;
        }
        if (match.mode.freeForAll()) {
            double minimum = decimal("pvp-competitive.ffa-minimum-border");
            if (match.borderSize > minimum + 1.0e-6d && !match.shrinkWarned
                    && now >= match.nextShrinkAt - 10_000L) {
                match.shrinkWarned = true;
                long warningSeconds = Math.max(1L,
                        (match.nextShrinkAt - now + 999L) / 1_000L);
                forEachOnline(concat(match.players, List.copyOf(match.spectators)), player -> {
                    player.sendActionBar(Component.text(
                            "Border shrinks in " + warningSeconds + " seconds!", NamedTextColor.RED));
                    player.playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 0.7f);
                });
            }
            if (now >= match.nextShrinkAt) shrinkBorder(match, now);
        }
        updateBar(match, now);
    }

    private void updateBar(Match match, long now) {
        long duration = integer("pvp-competitive.match-minutes") * 60_000L;
        long remaining = Math.max(0L, match.endsAt - now);
        float progress = duration <= 0L ? 0f
                : (float) Math.max(0d, Math.min(1d, (double) remaining / duration));
        match.bar.progress(progress);
        String text = match.mode.display() + "  •  " + match.alive.size() + " alive  •  "
                + formatSeconds((remaining + 999L) / 1_000L);
        if (!match.mode.freeForAll()) {
            long firstAlive = aliveOnTeam(match, 0);
            long secondAlive = aliveOnTeam(match, 1);
            text = match.mode.display() + "  •  " + firstAlive + "–" + secondAlive
                    + " alive  •  " + formatSeconds((remaining + 999L) / 1_000L);
        }
        match.bar.name(Component.text(text, NamedTextColor.GOLD));
    }

    private void shrinkBorder(Match match, long now) {
        double minimum = decimal("pvp-competitive.ffa-minimum-border");
        double next = Math.max(minimum,
                match.borderSize - decimal("pvp-competitive.ffa-shrink-blocks"));
        match.nextShrinkAt = now
                + integer("pvp-competitive.ffa-shrink-interval-seconds") * 1_000L;
        match.shrinkWarned = false;
        if (next >= match.borderSize - 1.0e-6d) {
            match.nextShrinkAt = Long.MAX_VALUE;
            return;
        }
        match.borderSize = next;
        forEachOnline(match.alive, player -> {
            WorldBorder border = player.getWorldBorder();
            if (border != null) {
                border.setWarningDistance(6);
                border.changeSize(next, 100L);
            }
            player.sendActionBar(Component.text(
                    "Border shrinking to " + Math.round(next) + " blocks!", NamedTextColor.RED));
            player.playSound(player, Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 1.1f);
        });
    }

    private void eliminate(Match match, UUID victimId, UUID killerId, String reason) {
        if (match.phase != Phase.FIGHTING || !match.alive.remove(victimId)) return;
        match.eliminated.add(victimId);
        if (killerId != null && !killerId.equals(victimId) && areOpponents(killerId, victimId)) {
            match.kills.merge(killerId, 1, Integer::sum);
        }
        Player victim = Bukkit.getPlayer(victimId);
        if (victim != null) prepareEliminated(match, victim);
        String line = name(victimId) + " " + reason + ".";
        notifyMatch(match, line, NamedTextColor.GRAY);

        List<UUID> winners = winnersIfDecided(match);
        if (!winners.isEmpty()) finish(match, winners, winnerText(match, winners), true);
    }

    private void prepareEliminated(Match match, Player player) {
        player.setInvulnerable(true);
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setInvisible(true);
        player.setCollidable(false);
        player.setCanPickupItems(false);
        forEachOnline(match.alive, fighter -> fighter.hidePlayer(plugin, player));
        Location stand = match.arena.spectator();
        if (stand != null) {
            match.assigned.put(player.getUniqueId(), stand);
            teleport(player, stand);
        }
        player.sendMessage(prefix().append(Component.text(
                "Eliminated — you are anchored above the arena until the result.",
                NamedTextColor.GRAY)));
    }

    private List<UUID> winnersIfDecided(Match match) {
        if (match.mode.freeForAll()) {
            return match.alive.size() == 1 ? List.copyOf(match.alive) : List.of();
        }
        Set<Integer> aliveTeams = new LinkedHashSet<>();
        for (UUID player : match.alive) aliveTeams.add(match.team.get(player));
        if (aliveTeams.size() != 1) return List.of();
        int winner = aliveTeams.iterator().next();
        return match.players.stream().filter(id -> match.team.get(id) == winner).toList();
    }

    private void finish(Match match, List<UUID> winners, String reason, boolean decided) {
        if (match.phase == Phase.AFTERMATH || match.phase == Phase.ENDING) return;
        match.phase = Phase.AFTERMATH;
        duels.setCompetitiveArenaActive(match.arena.id(), false);
        if (match.countdown != null) match.countdown.cancel();
        match.countdown = null;
        Set<UUID> deaths = Set.copyOf(match.eliminated);
        List<UUID> losers = decided
                ? match.players.stream().filter(id -> !winners.contains(id)).toList()
                : List.of();
        Map<UUID, PvpRecordStore.RatingChange> changes = Map.of();
        try {
            changes = decided
                    ? records.settleMatch(match.mode, winners, losers, match.kills, deaths,
                            match.mode.rated())
                    : records.drawMatch(match.mode, match.players, match.kills, deaths,
                            match.mode.rated());
            if (decided && match.mode.clan()) settleClanMatch(match, winners, losers);
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Could not save competitive PvP result: " + failure.getMessage());
        }
        recordOpponentRuns(match);

        for (UUID playerId : match.players) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) continue;
            player.setInvulnerable(true);
            player.setAllowFlight(false);
            player.setFlying(false);
            PvpRecordStore.RatingChange change = changes.get(playerId);
            String rating = change == null || change.delta() == 0 ? ""
                    : "  •  Rating " + (change.delta() > 0 ? "+" : "") + change.delta();
            boolean won = winners.contains(playerId);
            String heading = decided ? (won ? "VICTORY" : "DEFEAT") : "DRAW";
            NamedTextColor colour = decided ? (won ? NamedTextColor.GREEN : NamedTextColor.RED)
                    : NamedTextColor.YELLOW;
            player.showTitle(Title.title(
                    Component.text(heading, colour, TextDecoration.BOLD),
                    Component.text(reason + rating, NamedTextColor.GOLD),
                    Title.Times.times(Duration.ZERO, Duration.ofSeconds(3), Duration.ofMillis(400))
            ));
            player.sendMessage(prefix().append(Component.text(
                    resultLine(match, playerId, reason, rating), colour)));
            player.playSound(player, won ? Sound.UI_TOAST_CHALLENGE_COMPLETE
                    : Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, won ? 1.1f : 0.8f);
        }
        int delay = integer("pvp-competitive.return-seconds");
        match.bar.name(Component.text(reason + "  •  Returning in " + delay + "s",
                NamedTextColor.GOLD));
        match.bar.progress(0f);
        match.returnTask = plugin.getServer().getScheduler().runTaskLater(
                plugin, () -> closeMatch(match), Math.max(1L, delay * 20L));
    }

    private void settleClanMatch(Match match, List<UUID> winners, List<UUID> losers) {
        if (winners.isEmpty() || losers.isEmpty()) return;
        UUID winner = clans.clanOf(winners.get(0)).map(ClanStore.ClanView::id).orElse(null);
        UUID loser = clans.clanOf(losers.get(0)).map(ClanStore.ClanView::id).orElse(null);
        if (winner != null && loser != null && !winner.equals(loser)) {
            int winnerKills = winners.stream().mapToInt(id -> match.kills.getOrDefault(id, 0)).sum();
            int loserKills = losers.stream().mapToInt(id -> match.kills.getOrDefault(id, 0)).sum();
            clanRecords.settle(winner, loser, winnerKills, loserKills);
        }
    }

    private void recordOpponentRuns(Match match) {
        long now = System.currentTimeMillis();
        int limit = integer("pvp-competitive.repeat-opponent-limit");
        long rest = integer("pvp-competitive.repeat-opponent-rest-seconds") * 1_000L;
        if (match.mode.freeForAll()) {
            for (int first = 0; first < match.players.size(); first++) {
                for (int second = first + 1; second < match.players.size(); second++) {
                    farmGuard.recordFight(match.players.get(first), match.players.get(second),
                            now, limit, rest);
                }
            }
            return;
        }
        for (UUID first : match.first) {
            for (UUID second : match.second) {
                farmGuard.recordFight(first, second, now, limit, rest);
            }
        }
    }

    private void closeMatch(Match match) {
        if (match.phase == Phase.ENDING) return;
        match.phase = Phase.ENDING;
        duels.setCompetitiveArenaActive(match.arena.id(), false);
        if (match.returnTask != null) match.returnTask.cancel();
        match.returnTask = null;
        for (UUID playerId : match.players) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) restore(player);
            matchByPlayer.remove(playerId);
        }
        for (UUID spectatorId : List.copyOf(match.spectators)) {
            Player spectator = Bukkit.getPlayer(spectatorId);
            if (spectator != null) restore(spectator);
            viewing.remove(spectatorId);
        }
        forEachOnline(concat(match.players, List.copyOf(match.spectators)),
                player -> plugin.bossBars().hideExclusive(player, match.bar));
        matches.remove(match.id);
        duels.releaseCompetitiveArena(match.arena.id());
    }

    private void abortStart(Match match, String reason) {
        if (match.phase == Phase.ENDING) return;
        match.phase = Phase.ENDING;
        if (match.countdown != null) match.countdown.cancel();
        notifyPlayers(match.players, reason);
        for (UUID playerId : match.players) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) restore(player);
            matchByPlayer.remove(playerId);
        }
        if (match.bar != null) forEachOnline(match.players,
                player -> plugin.bossBars().hideExclusive(player, match.bar));
        matches.remove(match.id);
        duels.releaseCompetitiveArena(match.arena.id());
    }

    private void restore(Player player) {
        PvpDuelStore.Recovery saved = recovery.find(player.getUniqueId()).orElse(null);
        if (saved == null) return;
        player.closeInventory();
        if (saved.role() == PvpDuelStore.Role.SPECTATOR
                || !saved.encodedInventory().isEmpty()) {
            clearEffects(player);
            player.getInventory().clear();
            player.getInventory().setContents(PvpStateCodec.sized(
                    PvpStateCodec.decodeItems(saved.encodedInventory()),
                    player.getInventory().getContents().length));
            for (PotionEffect effect : PvpStateCodec.decodeEffects(saved.encodedEffects())) {
                player.addPotionEffect(effect);
            }
            player.setLevel(saved.level());
            player.setExp(saved.experience());
            player.setTotalExperience(saved.totalExperience());
            player.getInventory().setHeldItemSlot(Math.max(0, Math.min(8, saved.heldSlot())));
        }
        try {
            player.setGameMode(GameMode.valueOf(saved.gameMode()));
        } catch (IllegalArgumentException unknown) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        player.setInvulnerable(saved.invulnerable());
        player.setAllowFlight(saved.allowFlight());
        player.setFlying(saved.allowFlight() && saved.flying());
        player.setInvisible(saved.invisible());
        player.setCollidable(saved.collidable());
        player.setCanPickupItems(saved.canPickupItems());
        player.setFoodLevel(saved.food());
        player.setSaturation(saved.saturation());
        player.setExhaustion(saved.exhaustion());
        player.setFireTicks(saved.fireTicks());
        player.setFallDistance(saved.fallDistance());
        player.setRemainingAir(saved.remainingAir());
        if (!player.isDead() && player.getAttribute(Attribute.MAX_HEALTH) != null) {
            player.setHealth(Math.max(0.1d, Math.min(saved.health(),
                    player.getAttribute(Attribute.MAX_HEALTH).getValue())));
        }
        Location destination = origin(saved);
        if (destination == null && !Bukkit.getWorlds().isEmpty()) {
            destination = Bukkit.getWorlds().get(0).getSpawnLocation();
        }
        if (destination != null) teleport(player, destination);
        Match match = matchByPlayer.get(player.getUniqueId());
        if (match == null) match = viewing.get(player.getUniqueId());
        if (match != null) {
            forEachOnline(match.players, fighter -> fighter.showPlayer(plugin, player));
            if (match.bar != null) plugin.bossBars().hideExclusive(player, match.bar);
        }
        player.setWorldBorder(match == null ? null : match.previousBorders.get(player.getUniqueId()));
        plugin.bossBars().restore(player);
        safeRemoveRecovery(player.getUniqueId());
        player.saveData();
    }

    private PvpDuelStore.Recovery snapshot(UUID matchId, PvpDuelStore.Role role, Player player) {
        Location at = player.getLocation();
        return new PvpDuelStore.Recovery(
                matchId, role, at.getWorld().getUID(), at.getWorld().getName(),
                at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch(),
                player.getGameMode().name(), player.isInvulnerable(), player.getAllowFlight(),
                player.isFlying(), player.getHealth(), player.getFoodLevel(), player.getSaturation(),
                player.getExhaustion(), player.getFireTicks(), player.getFallDistance(),
                player.getRemainingAir(), player.getInventory().getHeldItemSlot(),
                economy.balance(player.getUniqueId()), "",
                role == PvpDuelStore.Role.SPECTATOR
                        ? PvpStateCodec.encodeItems(player.getInventory().getContents()) : "",
                PvpStateCodec.encodeEffects(player.getActivePotionEffects()),
                player.getLevel(), player.getExp(), player.getTotalExperience(),
                player.isCollidable(), player.isInvisible(), player.getCanPickupItems()
        );
    }

    private void recover(Player player, boolean announce) {
        if (recovery.find(player.getUniqueId()).isEmpty()) return;
        restore(player);
        if (announce) info(player, "An interrupted competitive match was recovered as a draw.");
    }

    private void safeRemoveRecovery(UUID playerId) {
        try {
            recovery.remove(playerId);
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Could not clear PvP recovery for " + playerId + ": "
                    + failure.getMessage());
        }
    }

    private void openHub(Player player) {
        if (queuedPlayers.containsKey(player.getUniqueId())) {
            openQueueStatus(player);
            return;
        }
        duels.openHub(player);
    }

    private record MenuAction(
            String sprite, String label, String hint, java.util.function.Consumer<Player> action
    ) { }

    void openModes(Player player) {
        List<MenuAction> actions = new ArrayList<>();
        for (PvpMode mode : PvpMode.values()) {
            if (!mode.queueable()) continue;
            // The live count, not just the description: whether a queue can fill is
            // the thing a player is deciding, and it changes minute to minute here.
            actions.add(new MenuAction(modeSprite(mode),
                    mode.display() + (shortStaffed(mode) ? " (not enough players)" : ""),
                    queueSummary(mode) + " - " + modeHint(mode),
                    p -> openMode(p, mode)));
        }
        String body = "Choose a mode. Every match uses your own survival loadout"
                + " in untouched overworld terrain.\n"
                + availablePlayers() + " player(s) free to fight right now.";
        showMenu(player, "Choose PvP Mode", body, actions, this::openHub);
    }

    private void openMode(Player player, PvpMode mode) {
        Party party = parties.get(player.getUniqueId());
        int team = party == null ? 1 : party.members.size();
        String body = modeHint(mode) + "\n\n"
                + queueSummary(mode) + "\n"
                + "Party: " + team + "/" + mode.teamSize() + "\n"
                + "Loadout: your current inventory and armour";
        if (shortStaffed(mode)) {
            // Said plainly rather than hiding the button: players do arrange to come
            // online together, and a queue they chose to sit in is their call.
            body += "\n\nThis mode needs " + requiredPlayers(mode)
                    + " players and cannot start yet. You can still wait here.";
        }
        List<MenuAction> actions = new ArrayList<>();
        if (mode.teamSize() == 1 && !mode.freeForAll()) {
            actions.add(new MenuAction("item/lime_dye", "Join Queue",
                    "Find an opponent automatically.", p -> joinQueue(p, mode, true)));
        } else if (!mode.clan()) {
            actions.add(new MenuAction("item/lime_dye", "Queue with Fill",
                    "Match missing teammate slots automatically.", p -> joinQueue(p, mode, true)));
        }
        if (mode.teamSize() > 1 && team == mode.teamSize()) {
            actions.add(new MenuAction("item/red_dye", "Queue No-Fill",
                    "Keep this complete party together.", p -> joinQueue(p, mode, false)));
        }
        if (mode.freeForAll()) {
            actions = new ArrayList<>(List.of(new MenuAction("item/lime_dye", "Join FFA Queue",
                    "Start full or after the minimum-player wait.", p -> joinQueue(p, mode, true))));
        }
        if (mode.teamSize() > 1 && !mode.freeForAll()) {
            actions.add(new MenuAction("item/writable_book", "Manage Team",
                    "Invite or remove teammates for this queue.",
                    viewer -> openParty(viewer, backViewer -> openMode(backViewer, mode))));
        }
        showMenu(player, mode.display(), body, actions, this::openModes);
    }

    void openStats(Player player) {
        openStats(player, this::openHub);
    }

    private void openStats(Player player, Consumer<Player> back) {
        PvpRecordStore.Record record = records.of(player.getUniqueId());
        String kd = record.deaths() == 0L
                ? (record.kills() == 0L ? "0.00" : "∞")
                : String.format(Locale.ROOT, "%.2f", (double) record.kills() / record.deaths());
        StringBuilder body = new StringBuilder()
                .append("Rank: ").append(record.rank().display()).append(" • ")
                .append(record.rating()).append(" rating\n")
                .append("Highest: ").append(record.bestRank().display()).append('\n')
                .append("Matches: ").append(record.matchesPlayed()).append(" • Win rate: ")
                .append(Math.round(record.winRate() * 100d)).append("%\n")
                .append("Overall: ").append(record.wins()).append("W / ")
                .append(record.losses()).append("L / ").append(record.draws()).append("D\n")
                .append("Kills / Deaths: ").append(record.kills()).append(" / ")
                .append(record.deaths()).append(" • K/D: ").append(kd)
                .append("\nStreak: ").append(record.streak())
                .append(" • Best streak: ").append(record.bestStreak());
        for (PvpMode mode : PvpMode.values()) {
            PvpRecordStore.ModeRecord row = record.mode(mode);
            if (row.matches() == 0L) continue;
            body.append("\n\n").append(mode.display()).append(": ")
                    .append(row.wins()).append("W / ").append(row.losses()).append("L / ")
                    .append(row.draws()).append("D • ").append(row.kills()).append("K / ")
                    .append(row.deaths()).append("D");
        }
        clans.clanOf(player.getUniqueId()).ifPresent(clan -> {
            PvpClanRecordStore.Record row = clanRecords.of(clan.id());
            body.append("\n\nClan PvP — ").append(clan.name()).append(": ")
                    .append(row.wins()).append("W / ").append(row.losses()).append("L")
                    .append(" • ").append(row.kills()).append(" kills")
                    .append(" • best streak ").append(row.bestStreak());
        });
        List<MenuAction> actions = List.of(new MenuAction("item/nether_star", "Rating Leaderboard",
                "See the highest current ratings.",
                viewer -> openRankings(viewer, backViewer -> openStats(backViewer, back))));
        showMenu(player, "PvP Statistics", body.toString(), actions, back);
    }

    private void openLadder(Player player) {
        PvpRecordStore.Record record = records.of(player.getUniqueId());
        PvpRank rank = record.rank();
        long remaining = Math.max(0L, rank.nextFloor() - record.rating());
        String progress = rank == PvpRank.UNREAL ? "Top rank reached"
                : remaining + " RP to " + PvpRank.values()[rank.ordinal() + 1].display();
        String body = "YOUR RANK\n"
                + rank.display() + " • " + record.rating() + " RP\n"
                + progress + "\n\n"
                + "Ranked 1v1, 2v2 and 3v3 use Elo rating."
                + " Once you reach a tier, its floor stays unlocked permanently.";
        showMenu(player, "Rank Progression", body, List.of(
                new MenuAction("item/nether_star", "View Every Tier",
                        "See each tier and its starting rating.", this::openTierGuide),
                new MenuAction("item/spyglass", "Top Ratings",
                        "Open the full current leaderboard.",
                        viewer -> openRankings(viewer, this::openLadder)),
                new MenuAction("item/book", "My PvP Statistics",
                        "Open your complete PvP record.",
                        viewer -> openStats(viewer, this::openLadder))
        ), this::openHub);
    }

    private void openTierGuide(Player player) {
        StringBuilder body = new StringBuilder("Divisions are "
                + integer("pvp-ranked.division-size")
                + " RP. Tier floors are permanent once reached.\n\n");
        Set<String> shown = new LinkedHashSet<>();
        for (PvpRank rank : PvpRank.values()) {
            if (!shown.add(rank.tier())) continue;
            body.append(rank.glyph()).append(' ').append(rank.tier())
                    .append(" • ").append(rank.floor()).append(" RP\n");
        }
        showMenu(player, "PvP Rank Tiers", body.toString(), List.of(), this::openLadder);
    }

    private void openRankings(Player player) {
        openRankings(player, this::openHub);
    }

    private void openRankings(Player player, Consumer<Player> back) {
        List<PvpRankLeaderboard.Row> top = PvpRankLeaderboard.top(
                records.all(), PvpCompetitionService::name, 10);
        StringBuilder body = new StringBuilder("Ranked results from automatic survival-loadout queues.\n");
        if (top.isEmpty()) body.append("\nNo ranked matches have been completed yet.");
        for (int index = 0; index < top.size(); index++) {
            PvpRankLeaderboard.Row row = top.get(index);
            body.append("\n").append(index + 1).append(". ").append(row.username())
                    .append(" — ").append(row.record().rank().display())
                    .append(" (").append(row.record().rating()).append(")");
        }
        List<Map.Entry<UUID, PvpClanRecordStore.Record>> clanTop = clanRecords.all().entrySet()
                .stream().filter(row -> row.getValue().wins() + row.getValue().losses() > 0L)
                .sorted(Map.Entry.<UUID, PvpClanRecordStore.Record>comparingByValue(
                        Comparator.comparingLong(PvpClanRecordStore.Record::wins)
                                .thenComparingLong(PvpClanRecordStore.Record::bestStreak)).reversed())
                .limit(5).toList();
        if (!clanTop.isEmpty()) {
            body.append("\n\nClan vs Clan");
            for (int index = 0; index < clanTop.size(); index++) {
                Map.Entry<UUID, PvpClanRecordStore.Record> row = clanTop.get(index);
                String clanName = clans.findClanById(row.getKey()).map(ClanStore.ClanView::name)
                        .orElse(row.getKey().toString().substring(0, 8));
                body.append("\n").append(index + 1).append(". ").append(clanName)
                        .append(" — ").append(row.getValue().wins()).append("W / ")
                        .append(row.getValue().losses()).append("L • ")
                        .append(row.getValue().kills()).append(" kills");
            }
        }
        showMenu(player, "PvP Rankings", body.toString(), List.of(), back);
    }

    private void openRules(Player player) {
        String body = "Every mode uses the armour, weapons, food and supplies you earned in survival.\n"
                + "KEEP INVENTORY is active. The ring uses untouched overworld terrain,"
                + " returns you to where you entered, and puts every changed block back.\n\n"
                + "Competitive fights and rank-ups do not pay money. Private-fight wagers remain optional"
                + " and use only the stakes both players accepted.\n\n"
                + "Same-owner accounts cannot match. Repeated opponents are rested."
                + " Forfeits, disconnects and AFK eliminations lose normally.";
        showMenu(player, "PvP Rules & Fair Play", body, List.of(), this::openHub);
    }

    void openLive(Player player) {
        List<Match> live = matches.values().stream()
                .filter(match -> match.phase == Phase.FIGHTING || match.phase == Phase.COUNTDOWN)
                .toList();
        List<MenuAction> actions = live.stream().map(match -> new MenuAction(
                "item/spyglass", match.mode.display() + " • " + match.alive.size() + " alive",
                match.mode.display(), viewer -> spectate(viewer, match))).toList();
        showMenu(player, "Live PvP Matches",
                live.isEmpty() ? "No competitive matches are live." : "Choose a match to watch.",
                actions, this::openHub);
    }

    void openParty(Player player) {
        openParty(player, this::openHub);
    }

    private void openParty(Player player, Consumer<Player> back) {
        Party party = parties.get(player.getUniqueId());
        String body;
        if (party == null) {
            body = "You are not in a PvP party. Invite an online player to create one."
                    + " Parties are only used for competitive team queues.";
        } else {
            body = "Leader: " + name(party.leader) + "\nMembers (" + party.members.size()
                    + "/3): " + party.members.stream().map(PvpCompetitionService::name)
                    .reduce((a, b) -> a + ", " + b).orElse("None");
        }
        List<MenuAction> actions = new ArrayList<>();
        if (party == null || party.leader.equals(player.getUniqueId())) {
            actions.add(new MenuAction("item/writable_book", "Invite Player",
                    "Choose an online player.", viewer -> openPartyInvites(viewer, back)));
        }
        if (party != null) {
            actions.add(new MenuAction("item/barrier", party.leader.equals(player.getUniqueId())
                    ? "Disband Party" : "Leave Party", "End this team arrangement.",
                    p -> leaveParty(p, party.leader.equals(p.getUniqueId()))));
        }
        PartyInvite invite = partyInvites.get(player.getUniqueId());
        if (invite != null && invite.expiresAt() > System.currentTimeMillis()) {
            actions.add(new MenuAction("item/lime_dye", "Accept " + name(invite.leader()),
                    "Join this PvP party.", p -> acceptParty(p, name(invite.leader()))));
        }
        showMenu(player, "PvP Party", body, actions, back);
    }

    private void openPartyInvites(Player player) {
        openPartyInvites(player, this::openHub);
    }

    private void openPartyInvites(Player player, Consumer<Player> back) {
        List<MenuAction> actions = Bukkit.getOnlinePlayers().stream()
                .filter(target -> !target.equals(player) && !parties.containsKey(target.getUniqueId()))
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .map(target -> new MenuAction(null, target.getName(), "Invite to your PvP party.",
                        ignored -> inviteParty(player, target.getName()))).toList();
        showMenu(player, "Invite Teammate",
                actions.isEmpty() ? "No available players are online." : "Choose a teammate.",
                actions, viewer -> openParty(viewer, back));
    }

    private void showMenu(
            Player player, String title, String body, List<MenuAction> actions,
            java.util.function.Consumer<Player> back
    ) {
        if (!clientSupport.supportsDialogs(player)) {
            List<BedrockForms.Button> buttons = actions.stream()
                    .map(action -> new BedrockForms.Button(action.label(),
                            () -> action.action().accept(player))).toList();
            if (forms.menu(player, title, body, buttons, back)) return;
            player.sendMessage(prefix().append(Component.text(title + ": " + body,
                    NamedTextColor.GRAY)));
            return;
        }
        List<ActionButton> buttons = actions.stream().map(action -> Screens.button(
                action.sprite(), action.label(), action.hint(), action.action())).toList();
        Screens.show(player, title, List.of(DialogBody.plainMessage(MenuText.body(body), 500)),
                buttons, Math.min(2, Math.max(1, buttons.size())), back);
    }

    private static String modeSprite(PvpMode mode) {
        return switch (mode) {
            case CASUAL_DUEL -> "item/iron_sword";
            case RANKED_DUEL -> "item/netherite_sword";
            case DOUBLES -> "item/diamond_sword";
            case TRIPLES -> "item/trident";
            case CLAN_BATTLE -> "item/red_dye";
            case FFA -> "item/ender_eye";
            default -> "item/wooden_sword";
        };
    }

    private static String modeHint(PvpMode mode) {
        return switch (mode) {
            case CASUAL_DUEL -> "Automatic unrated 1v1 with the gear you brought.";
            case RANKED_DUEL -> "Skill-matched 1v1 that changes your PvP rating.";
            case DOUBLES -> "Ranked 2v2. Bring a teammate or let fill create your team.";
            case TRIPLES -> "Ranked 3v3. Parties stay together and open slots are filled.";
            case CLAN_BATTLE -> "Three members of one existing clan eliminate another clan.";
            case FFA -> "Solo elimination. The personal border shrinks until one player remains.";
            default -> "Unrated private survival-inventory fight.";
        };
    }

    private void handleParty(Player player, String[] args) {
        if (args.length == 0) {
            openParty(player);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "invite" -> {
                if (args.length < 2) openPartyInvites(player);
                else inviteParty(player, args[1]);
            }
            case "accept" -> acceptParty(player, args.length >= 2 ? args[1] : "");
            case "leave" -> leaveParty(player, false);
            case "disband" -> leaveParty(player, true);
            case "kick" -> kickParty(player, args.length >= 2 ? args[1] : "");
            default -> error(player, "Use /pvp party invite, accept, leave, kick, or disband.");
        }
    }

    private void inviteParty(Player player, String typed) {
        UUID playerId = player.getUniqueId();
        Party party = parties.get(playerId);
        if (party == null) {
            party = new Party(playerId);
            parties.put(playerId, party);
        }
        if (!party.leader.equals(playerId)) {
            error(player, "Only the party leader can invite players.");
            return;
        }
        if (party.members.size() >= 3) {
            error(player, "PvP parties hold at most three players.");
            return;
        }
        Player target = Bukkit.getPlayerExact(typed);
        if (target == null || target.equals(player)) {
            error(player, "Choose another online player.");
            return;
        }
        if (parties.containsKey(target.getUniqueId()) || busy(target.getUniqueId())) {
            error(player, target.getName() + " is already in a party or PvP activity.");
            return;
        }
        partyInvites.put(target.getUniqueId(), new PartyInvite(playerId,
                System.currentTimeMillis() + INVITE_MILLIS));
        info(player, "Invited " + target.getName() + " to your PvP party.");
        target.sendMessage(prefix().append(Component.text(
                player.getName() + " invited you to a PvP party. Use /pvp party accept "
                        + player.getName() + ".", NamedTextColor.GOLD)));
    }

    private void acceptParty(Player player, String leaderName) {
        UUID playerId = player.getUniqueId();
        PartyInvite invite = partyInvites.get(playerId);
        if (invite == null || invite.expiresAt() <= System.currentTimeMillis()
                || (!leaderName.isBlank() && !name(invite.leader()).equalsIgnoreCase(leaderName))) {
            error(player, "That PvP party invite is no longer available.");
            return;
        }
        Party party = parties.get(invite.leader());
        if (party == null || party.members.size() >= 3 || parties.containsKey(playerId)
                || busy(playerId)) {
            error(player, "That PvP party can no longer be joined.");
            return;
        }
        leaveQueue(invite.leader(), true);
        party.members.add(playerId);
        parties.put(playerId, party);
        partyInvites.remove(playerId);
        notifyPlayers(party.members, player.getName() + " joined the PvP party.");
    }

    private void leaveParty(Player player, boolean disband) {
        Party party = parties.get(player.getUniqueId());
        if (party == null) {
            error(player, "You are not in a PvP party.");
            return;
        }
        if (busy(player.getUniqueId()) && !queuedPlayers.containsKey(player.getUniqueId())) {
            error(player, "Finish the current match before changing your party.");
            return;
        }
        leaveQueue(player.getUniqueId(), true);
        if (party.leader.equals(player.getUniqueId()) && (disband || party.members.size() == 1)) {
            for (UUID member : party.members) parties.remove(member);
            notifyPlayers(party.members, "The PvP party was disbanded.");
            return;
        }
        party.members.remove(player.getUniqueId());
        parties.remove(player.getUniqueId());
        if (party.leader.equals(player.getUniqueId())) party.leader = party.members.iterator().next();
        info(player, "You left the PvP party.");
        notifyPlayers(party.members, player.getName() + " left the PvP party.");
    }

    private void kickParty(Player player, String typed) {
        Party party = parties.get(player.getUniqueId());
        if (party == null || !party.leader.equals(player.getUniqueId())) {
            error(player, "Only the party leader can remove a teammate.");
            return;
        }
        UUID target = party.members.stream().filter(id -> name(id).equalsIgnoreCase(typed))
                .findFirst().orElse(null);
        if (target == null || target.equals(player.getUniqueId())) {
            error(player, "Choose one of your teammates.");
            return;
        }
        leaveQueue(player.getUniqueId(), true);
        party.members.remove(target);
        parties.remove(target);
        notifyPlayers(party.members, name(target) + " was removed from the PvP party.");
        Player removed = Bukkit.getPlayer(target);
        if (removed != null) info(removed, "You were removed from the PvP party.");
    }

    private void showStatus(Player player) {
        Match match = matchByPlayer.get(player.getUniqueId());
        if (match != null) {
            info(player, matchSummary(match));
            return;
        }
        Match watched = viewing.get(player.getUniqueId());
        if (watched != null) {
            info(player, "Watching " + watched.mode.display() + ".");
            return;
        }
        PvpMode mode = queuedModes.get(player.getUniqueId());
        PendingStart pending = preparing.get(player.getUniqueId());
        if (pending != null) {
            info(player, "Preparing untouched overworld terrain for "
                    + pending.mode().display() + ".");
            return;
        }
        if (mode == null) {
            info(player, "You are not queued. Use /pvp to choose a mode.");
            return;
        }
        PvpMatchmaking.Entry entry = queuedPlayers.get(player.getUniqueId());
        long waited = Math.max(0L, System.currentTimeMillis() - entry.joinedAt()) / 1_000L;
        info(player, "Queued for " + mode.display() + " for " + formatSeconds(waited)
                + " with " + entry.members().size() + " player(s).");
    }

    private void openQueueStatus(Player player) {
        PvpMode mode = queuedModes.get(player.getUniqueId());
        PvpMatchmaking.Entry entry = queuedPlayers.get(player.getUniqueId());
        PendingStart pending = preparing.get(player.getUniqueId());
        if (pending != null) {
            showMenu(player, "PvP Match Found",
                    "Preparing untouched overworld terrain for " + pending.mode().display() + ".",
                    List.of(new MenuAction("item/barrier", "Cancel Match",
                            "Cancel before the countdown begins.",
                            this::leaveQueueAndOpenHub)), duels::openHub);
            return;
        }
        if (mode == null || entry == null) {
            openModes(player);
            return;
        }
        long waited = Math.max(0L, System.currentTimeMillis() - entry.joinedAt()) / 1_000L;
        String body = "Queued for " + mode.display() + "\n"
                + "Waiting: " + formatSeconds(waited) + "\n"
                + "Team: " + entry.members().size() + "/" + mode.teamSize();
        showMenu(player, "PvP Queue", body, List.of(
                new MenuAction("item/barrier", "Leave Queue",
                        "Leave this queue with your current team.",
                        this::leaveQueueAndOpenHub)
        ), duels::openHub);
    }

    private void leaveQueueAndOpenHub(Player player) {
        leaveQueue(player.getUniqueId(), true);
        duels.openHub(player);
    }

    int liveMatchCount() {
        return matches.size();
    }

    void enterLobby(Player player) {
        if (busy(player.getUniqueId()) || plugin.inPvpDuel(player)) {
            error(player, "Leave your queue or finish the current fight first.");
            return;
        }
        if (plugin.afkService() != null && plugin.afkService().inCombat(player)) {
            error(player, "Finish your current combat tag before entering PvP.");
            return;
        }
        Location lobby = lobbyStore.lobby().map(PvpLobbyStore.Point::resolve).orElse(null);
        if (lobby == null) {
            error(player, "The PvP lobby is not configured yet.");
            return;
        }
        if (!inLobbyArea(player.getLocation())) {
            lobbyOrigins.put(player.getUniqueId(), player.getLocation().clone());
        }
        teleport(player, lobby);
        player.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f);
        openHub(player);
    }

    private void teleportToLobbyIfReady(Player player) {
        Location lobby = lobbyStore.lobby().map(PvpLobbyStore.Point::resolve).orElse(null);
        if (lobby != null && !inLobbyArea(player.getLocation())) {
            lobbyOrigins.put(player.getUniqueId(), player.getLocation().clone());
            teleport(player, lobby);
        }
    }

    private void returnToServer(Player player) {
        if (isParticipant(player.getUniqueId())) {
            error(player, "Use /pvp leave or forfeit while a match is active.");
            return;
        }
        if (!inLobbyArea(player.getLocation()) && !isPvpWorld(player.getWorld())) {
            error(player, "You are not in the PvP lobby.");
            return;
        }
        leaveQueue(player.getUniqueId(), true);
        Location target = lobbyOrigins.remove(player.getUniqueId());
        if (target == null) {
            World world = Bukkit.getWorlds().stream().filter(candidate -> !isPvpWorld(candidate))
                    .findFirst().orElse(null);
            target = world == null ? null : world.getSpawnLocation();
        }
        if (target == null) {
            error(player, "The main world is unavailable.");
            return;
        }
        teleport(player, target);
        player.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.9f);
    }

    private void spectate(Player viewer, String playerName) {
        Player target = Bukkit.getPlayerExact(playerName);
        Match match = target == null ? null : matchByPlayer.get(target.getUniqueId());
        if (match == null) {
            error(viewer, "That player is not in a live competitive match.");
            return;
        }
        spectate(viewer, match);
    }

    private void spectate(Player viewer, Match match) {
        UUID viewerId = viewer.getUniqueId();
        if (busy(viewerId) || plugin.inPvpDuel(viewer)) {
            error(viewer, "Leave your queue or current fight first.");
            return;
        }
        if (match.phase != Phase.FIGHTING && match.phase != Phase.COUNTDOWN) {
            error(viewer, "That match is already ending.");
            return;
        }
        if (match.spectators.size() >= integer("pvp-competitive.maximum-spectators")) {
            error(viewer, "That viewing point is full.");
            return;
        }
        if (recovery.find(viewerId).isPresent()) {
            recover(viewer, true);
            return;
        }
        Location stand = match.arena.spectator();
        if (stand == null) {
            error(viewer, "That arena's viewing point is unavailable.");
            return;
        }
        try {
            recovery.putAll(Map.of(viewerId,
                    snapshot(match.id, PvpDuelStore.Role.SPECTATOR, viewer)));
        } catch (RuntimeException failure) {
            error(viewer, "Your inventory could not be saved safely.");
            return;
        }
        match.spectators.add(viewerId);
        viewing.put(viewerId, match);
        match.previousBorders.put(viewerId, viewer.getWorldBorder());
        match.assigned.put(viewerId, stand);
        forEachOnline(match.players, fighter -> fighter.hidePlayer(plugin, viewer));
        plugin.bossBars().suppress(viewer);
        clearEffects(viewer);
        viewer.getInventory().clear();
        viewer.getInventory().setArmorContents(new ItemStack[4]);
        viewer.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        viewer.setGameMode(GameMode.ADVENTURE);
        viewer.setInvulnerable(true);
        viewer.setAllowFlight(true);
        viewer.setFlying(true);
        viewer.setInvisible(true);
        viewer.setCollidable(false);
        viewer.setCanPickupItems(false);
        viewer.setWorldBorder(personalBorder(match, match.borderSize));
        teleport(viewer, stand);
        plugin.bossBars().showExclusive(viewer, match.bar);
        info(viewer, "Watching " + match.mode.display()
                + ". You are anchored and cannot interact or coach. Use /pvp leave to return.");
    }

    private void leaveSpectator(Player player, boolean announce) {
        Match match = viewing.get(player.getUniqueId());
        if (match == null) return;
        match.spectators.remove(player.getUniqueId());
        plugin.bossBars().hideExclusive(player, match.bar);
        restore(player);
        viewing.remove(player.getUniqueId());
        if (announce) info(player, "You stopped spectating.");
    }

    private void handleAdmin(Player player, String[] original) {
        if (!isAdmin(player)) {
            error(player, "You do not have permission to manage PvP.");
            return;
        }
        String[] args = original;
        if (args.length == 0) {
            info(player, "Use /pvp admin setup confirm or /pvp portal set|remove."
                    + " Portal set registers the obsidian frame you are looking at.");
            return;
        }
        String root = args[0].toLowerCase(Locale.ROOT);
        if (root.equals("setup")) {
            if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
                error(player, "Use /pvp admin setup confirm. This rebuilds the decorated PvP lobby.");
                return;
            }
            if (!matches.isEmpty()) {
                error(player, "Wait for every competitive match to end first.");
                return;
            }
            installStarterLobby();
            info(player, "The decorated PvP lobby and its queue portals are installed.");
            return;
        }
        if (root.equals("portal")) {
            if (args.length >= 2 && args[1].equalsIgnoreCase("remove")) {
                setEntrancePortalLit(false);
                clearEntranceDisplays();
                lobbyStore.clearPortal();
                info(player, "PvP entrance removed.");
            } else if (args.length >= 2 && args[1].equalsIgnoreCase("set")) {
                Block target = player.getTargetBlockExact(
                        integer("pvp-competitive.portal-selection-distance"));
                if (target == null) {
                    error(player, "Look at a block on the PvP entrance's obsidian frame.");
                    return;
                }
                setEntrancePortalLit(false);
                clearEntranceDisplays();
                Location at = target.getLocation().add(0.5d, 0.5d, 0.5d);
                at.setYaw(player.getYaw());
                lobbyStore.setPortal(PvpLobbyStore.Point.of(at), 2.5d);
                boolean lit = setEntrancePortalLit(true);
                refreshEntranceDisplay();
                if (lit) {
                    info(player, "PvP lobby portal registered and lit. Walk through it to enter.");
                } else {
                    error(player, "PvP entrance saved, but no complete obsidian frame could be lit nearby.");
                }
            } else error(player, "Use /pvp portal set or /pvp portal remove.");
            return;
        }
        error(player, "Unknown PvP admin action.");
    }

    /** Recreates the persistent SMP entrance from the same frame-selection model as Dragon. */
    private void refreshEntrancePortal() {
        if (stopping || !plugin.isEnabled()) return;
        setEntrancePortalLit(false);
        clearEntranceDisplays();
        if (lobbyStore.portal().isEmpty()) return;
        if (!setEntrancePortalLit(true)) {
            plugin.getLogger().warning("The PvP lobby entrance could not ignite:"
                    + " register a block on its complete obsidian frame.");
        }
        refreshEntranceDisplay();
    }

    /** Uses Minecraft's own ignition first, then fills a valid oversized frame safely. */
    private boolean setEntrancePortalLit(boolean lit) {
        if (!lit) {
            Set<Location> remove = new HashSet<>(entrancePortalBlocks);
            remove.addAll(nearestEntrancePortalComponent());
            for (Location location : remove) {
                if (location.getWorld() != null
                        && location.getBlock().getType() == Material.NETHER_PORTAL) {
                    location.getBlock().setType(Material.AIR, false);
                }
            }
            entrancePortalBlocks.clear();
            return true;
        }
        if (lobbyStore.portal().map(PvpLobbyStore.Point::resolve).orElse(null) == null) return false;
        if (igniteEntranceFrame()) return true;
        return false;
    }

    private boolean igniteEntranceFrame() {
        Location registered = lobbyStore.portal().map(PvpLobbyStore.Point::resolve).orElse(null);
        if (registered == null) return false;
        Block centre = registered.getBlock();
        int radius = integer("pvp-competitive.portal-light-radius");
        int height = integer("pvp-competitive.portal-light-height");
        List<Block> candidates = new ArrayList<>();
        for (int y = -height; y <= height; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    Block block = centre.getRelative(x, y, z);
                    if (!block.getType().isAir() && block.getType() != Material.LIGHT) continue;
                    if (!isObsidianFrame(block.getRelative(0, -1, 0))) continue;
                    if (!isObsidianFrame(block.getRelative(1, 0, 0))
                            && !isObsidianFrame(block.getRelative(-1, 0, 0))
                            && !isObsidianFrame(block.getRelative(0, 0, 1))
                            && !isObsidianFrame(block.getRelative(0, 0, -1))) continue;
                    candidates.add(block);
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(block ->
                block.getLocation().distanceSquared(centre.getLocation())));
        for (Block candidate : candidates) {
            candidate.setType(Material.FIRE, true);
            Set<Location> created = nearestEntrancePortalComponent();
            if (!created.isEmpty()) {
                entrancePortalBlocks.addAll(created);
                return true;
            }
            if (candidate.getType() == Material.FIRE) candidate.setType(Material.AIR, false);
            created = fillEntranceFrame(candidate, radius, height);
            if (!created.isEmpty()) {
                entrancePortalBlocks.addAll(created);
                return true;
            }
        }
        return false;
    }

    private Set<Location> fillEntranceFrame(Block corner, int maximumWidth, int maximumHeight) {
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] direction : directions) {
            int dx = direction[0];
            int dz = direction[1];
            if (!isObsidianFrame(corner.getRelative(-dx, 0, -dz))) continue;
            int width = 0;
            while (width < Math.min(21, maximumWidth * 2 + 1)
                    && replaceablePortalInterior(corner.getRelative(dx * width, 0, dz * width))) {
                width++;
            }
            if (width < 2 || !isObsidianFrame(corner.getRelative(dx * width, 0, dz * width))) continue;
            int height = 0;
            while (height < Math.min(21, maximumHeight)
                    && replaceablePortalInterior(corner.getRelative(0, height, 0))) {
                height++;
            }
            if (height < 3 || !isObsidianFrame(corner.getRelative(0, height, 0))) continue;
            boolean valid = true;
            for (int side = 0; side < width && valid; side++) {
                if (!isObsidianFrame(corner.getRelative(dx * side, -1, dz * side))
                        || !isObsidianFrame(corner.getRelative(dx * side, height, dz * side))) {
                    valid = false;
                }
                for (int y = 0; y < height && valid; y++) {
                    if (!replaceablePortalInterior(corner.getRelative(dx * side, y, dz * side))) {
                        valid = false;
                    }
                }
            }
            for (int y = 0; y < height && valid; y++) {
                if (!isObsidianFrame(corner.getRelative(-dx, y, -dz))
                        || !isObsidianFrame(corner.getRelative(dx * width, y, dz * width))) {
                    valid = false;
                }
            }
            if (!valid) continue;
            Orientable data = (Orientable) Material.NETHER_PORTAL.createBlockData();
            data.setAxis(dx == 0 ? Axis.Z : Axis.X);
            Set<Location> created = new HashSet<>();
            for (int side = 0; side < width; side++) {
                for (int y = 0; y < height; y++) {
                    Block block = corner.getRelative(dx * side, y, dz * side);
                    block.setBlockData(data.clone(), false);
                    created.add(block.getLocation());
                }
            }
            return created;
        }
        return Set.of();
    }

    private static boolean replaceablePortalInterior(Block block) {
        return block.getType().isAir() || block.getType() == Material.FIRE
                || block.getType() == Material.LIGHT || block.getType() == Material.NETHER_PORTAL;
    }

    private static boolean isObsidianFrame(Block block) {
        return block.getType() == Material.OBSIDIAN;
    }

    private Set<Location> nearestEntrancePortalComponent() {
        Location registered = lobbyStore.portal().map(PvpLobbyStore.Point::resolve).orElse(null);
        if (registered == null) return Set.of();
        Block centre = registered.getBlock();
        int radius = integer("pvp-competitive.portal-light-radius");
        int height = integer("pvp-competitive.portal-light-height");
        Block nearest = null;
        double distance = Double.MAX_VALUE;
        for (int y = -height; y <= height; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    Block candidate = centre.getRelative(x, y, z);
                    if (candidate.getType() != Material.NETHER_PORTAL) continue;
                    double candidateDistance = candidate.getLocation()
                            .distanceSquared(centre.getLocation());
                    if (candidateDistance < distance) {
                        nearest = candidate;
                        distance = candidateDistance;
                    }
                }
            }
        }
        if (nearest == null) return Set.of();
        Set<Location> found = new HashSet<>();
        List<Block> pending = new ArrayList<>();
        pending.add(nearest);
        for (int index = 0; index < pending.size() && found.size() < 4096; index++) {
            Block block = pending.get(index);
            if (block.getType() != Material.NETHER_PORTAL
                    || !found.add(block.getLocation())) continue;
            pending.add(block.getRelative(1, 0, 0));
            pending.add(block.getRelative(-1, 0, 0));
            pending.add(block.getRelative(0, 1, 0));
            pending.add(block.getRelative(0, -1, 0));
            pending.add(block.getRelative(0, 0, 1));
            pending.add(block.getRelative(0, 0, -1));
        }
        return found;
    }

    private boolean touchesEntrancePortal(Location location) {
        Location registered = lobbyStore.portal().map(PvpLobbyStore.Point::resolve).orElse(null);
        if (registered == null || location == null || !sameWorld(registered, location)) return false;
        boolean portal = location.getBlock().getType() == Material.NETHER_PORTAL
                || location.clone().add(0, 1, 0).getBlock().getType() == Material.NETHER_PORTAL;
        if (!portal) return false;
        double radius = integer("pvp-competitive.portal-light-radius") + 2d;
        return horizontalSquared(registered, location) <= radius * radius
                && Math.abs(registered.getY() - location.getY())
                <= integer("pvp-competitive.portal-light-height") + 2d;
    }

    private void refreshEntranceDisplay() {
        clearEntranceDisplays();
        Location registered = lobbyStore.portal().map(PvpLobbyStore.Point::resolve).orElse(null);
        if (registered == null) return;
        Set<Location> blocks = activeEntrancePortalBlocks();
        Location anchor = portalCentre(blocks, registered);
        double height = decimal("pvp-competitive.portal-display-height");
        entranceLabel(anchor.clone().add(0d, height, 0d),
                Component.text(plugin.gameVariables().string("pvp-competitive.portal-title"),
                        TextColor.color(0xB56CFF), TextDecoration.BOLD),
                (float) decimal("pvp-competitive.portal-title-scale"));
        entranceLabel(anchor.clone().add(0d, height - 1.35d, 0d),
                Component.text(plugin.gameVariables().string("pvp-competitive.portal-status"),
                        NamedTextColor.WHITE, TextDecoration.BOLD),
                (float) decimal("pvp-competitive.portal-status-scale"));
    }

    private static Location portalCentre(Set<Location> blocks, Location fallback) {
        if (blocks.isEmpty()) return fallback.clone();
        int minimumX = Integer.MAX_VALUE;
        int maximumX = Integer.MIN_VALUE;
        int minimumY = Integer.MAX_VALUE;
        int maximumY = Integer.MIN_VALUE;
        int minimumZ = Integer.MAX_VALUE;
        int maximumZ = Integer.MIN_VALUE;
        for (Location block : blocks) {
            minimumX = Math.min(minimumX, block.getBlockX());
            maximumX = Math.max(maximumX, block.getBlockX());
            minimumY = Math.min(minimumY, block.getBlockY());
            maximumY = Math.max(maximumY, block.getBlockY());
            minimumZ = Math.min(minimumZ, block.getBlockZ());
            maximumZ = Math.max(maximumZ, block.getBlockZ());
        }
        return new Location(fallback.getWorld(), (minimumX + maximumX + 1d) / 2d,
                (minimumY + maximumY + 1d) / 2d, (minimumZ + maximumZ + 1d) / 2d);
    }

    private void entranceLabel(Location at, Component text, float scale) {
        at.getWorld().spawn(at, TextDisplay.class, display -> {
            display.addScoreboardTag(ENTRANCE_DISPLAY_TAG);
            display.addScoreboardTag(ENTRANCE_TEXT_TAG);
            display.text(text);
            display.setBillboard(Display.Billboard.VERTICAL);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setShadowed(true);
            display.setSeeThrough(false);
            display.setLineWidth(320);
            display.setBackgroundColor(Color.fromARGB(225, 5, 3, 10));
            display.setTextOpacity((byte) -1);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setViewRange(12f);
            display.setPersistent(false);
            display.setVisibleByDefault(false);
            display.setTransformation(new Transformation(
                    new org.joml.Vector3f(), new org.joml.AxisAngle4f(),
                    new org.joml.Vector3f(scale), new org.joml.AxisAngle4f()));
        });
        ArmorStand fallback = CrateDisplayService.spawnStyledLabel(at, text,
                ENTRANCE_DISPLAY_TAG, ENTRANCE_FALLBACK_TAG);
        fallback.setPersistent(false);
        fallback.setVisibleByDefault(false);
    }

    private void clearEntranceDisplays() {
        for (World world : Bukkit.getWorlds()) {
            world.getEntities().stream()
                    .filter(display -> display.getScoreboardTags().contains(ENTRANCE_DISPLAY_TAG))
                    .forEach(Entity::remove);
        }
    }

    private void updateEntranceLabelViewers() {
        for (World world : Bukkit.getWorlds()) {
            List<Entity> labels = world.getEntities().stream()
                    .filter(entity -> entity.getScoreboardTags().contains(ENTRANCE_DISPLAY_TAG))
                    .toList();
            if (labels.isEmpty()) continue;
            for (Player player : world.getPlayers()) {
                boolean useText = clientSupport.supportsTextDisplays(player);
                for (Entity label : labels) {
                    boolean correctType = useText
                            ? label.getScoreboardTags().contains(ENTRANCE_TEXT_TAG)
                            : label.getScoreboardTags().contains(ENTRANCE_FALLBACK_TAG);
                    boolean nearby = label.getLocation().distanceSquared(player.getLocation()) <= 2304d;
                    if (correctType && nearby) player.showEntity(plugin, label);
                    else player.hideEntity(plugin, label);
                }
            }
        }
    }

    /** Returns the already-discovered component without scanning the world on a live tick. */
    private Set<Location> activeEntrancePortalBlocks() {
        entrancePortalBlocks.removeIf(location -> location.getWorld() == null
                || location.getBlock().getType() != Material.NETHER_PORTAL);
        return Set.copyOf(entrancePortalBlocks);
    }

    private void pulseEntrancePortal() {
        if (!plugin.gameVariables().bool("pvp-competitive.portal-effects-enabled")) return;
        Location registered = lobbyStore.portal().map(PvpLobbyStore.Point::resolve).orElse(null);
        if (registered == null || registered.getWorld().getPlayers().isEmpty()) return;
        Set<Location> blocks = activeEntrancePortalBlocks();
        if (blocks.isEmpty()) return;
        Location centre = portalCentre(blocks, registered);
        int count = integer("pvp-competitive.portal-particle-count");
        registered.getWorld().spawnParticle(Particle.REVERSE_PORTAL,
                centre, count, 1.8d, 2.6d, 1.8d, 0.03d);
        registered.getWorld().spawnParticle(Particle.END_ROD,
                centre.clone().add(0d, 2.4d, 0d), Math.max(0, count / 3),
                1.4d, 0.4d, 1.4d, 0.01d);
    }

    /** Keeps vanilla Nether travel from taking focus away from a custom portal menu. */
    private void suppressCustomPortalTravel() {
        PvpLobbyStore.Point lobby = lobbyStore.lobby().orElse(null);
        int cooldown = integer("pvp-competitive.portal-suppression-ticks");
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location at = player.getLocation();
            if (touchesEntrancePortal(at) || PvpLobbyBuilder.modePad(lobby, at) != null
                    || PvpLobbyBuilder.returnPad(lobby, at)) {
                player.setPortalCooldown(cooldown);
            }
        }
    }

    private void installStarterLobby() {
        if (stopping || !plugin.isEnabled()) return;
        if (!matches.isEmpty()) {
            plugin.getServer().getScheduler().runTaskLater(
                    plugin, this::installStarterLobby, 100L);
            return;
        }
        try {
            PvpLobbyBuilder.Built built = PvpLobbyBuilder.build(plugin);
            lobbyStore.installGenerated(built.lobby());
            plugin.getLogger().info("Installed the decorated PvP queue lobby."
                    + " Combat remains in temporary overworld rings.");
        } catch (RuntimeException failure) {
            plugin.getLogger().severe("Could not install the PvP lobby: " + failure.getMessage());
        }
    }

    private void loadConfiguredWorld() {
        String name = lobbyStore.lobby().map(PvpLobbyStore.Point::worldName).orElse("");
        if (PvpLobbyBuilder.WORLD_NAME.equals(name) && Bukkit.getWorld(name) == null) {
            PvpLobbyBuilder.load(plugin);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            if (event instanceof EntityDamageByEntityEvent byEntity) {
                Player attacker = attackingPlayer(byEntity);
                if (attacker != null && isParticipant(attacker.getUniqueId())) event.setCancelled(true);
            }
            return;
        }
        UUID victimId = victim.getUniqueId();
        if (!isParticipant(victimId) && inLobbyArea(victim.getLocation())) {
            event.setCancelled(true);
            return;
        }
        if (viewing.containsKey(victimId)) {
            event.setCancelled(true);
            return;
        }
        Match match = matchByPlayer.get(victimId);
        if (match == null) {
            if (event instanceof EntityDamageByEntityEvent byEntity) {
                Player attacker = attackingPlayer(byEntity);
                if (attacker != null && isParticipant(attacker.getUniqueId())) event.setCancelled(true);
            }
            return;
        }
        if (match.phase != Phase.FIGHTING || !match.alive.contains(victimId)) {
            event.setCancelled(true);
            return;
        }
        UUID source = event instanceof EntityDamageByEntityEvent byEntity
                ? attackingSource(byEntity) : recentAttacker(match, victimId);
        if (source != null) {
            if (!areOpponents(source, victimId) || !match.alive.contains(source)) {
                event.setCancelled(true);
                return;
            }
            match.lastHitBy.put(victimId, source);
            match.lastHitAt.put(victimId, System.currentTimeMillis());
            match.lastAction.put(source, System.currentTimeMillis());
            match.lastAction.put(victimId, System.currentTimeMillis());
            match.damage.merge(source, event.getFinalDamage(), Double::sum);
        }
        // getFinalDamage() has already had absorption taken out of it, so the health a
        // hit actually removes is that figure against the health bar alone. Adding
        // absorption on top of the remaining health made a lethal blow on a golden-apple
        // player read as survivable, and the elimination path was skipped for a real death.
        double remaining = victim.getHealth();
        if (event.getFinalDamage() + 1.0e-6d >= remaining) {
            event.setCancelled(true);
            UUID killer = source != null ? source : recentAttacker(match, victimId);
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> eliminate(match, victimId, killer, "was eliminated"));
        }
    }

    /**
     * Whether this death belongs to the competitive layer, whatever caused it.
     *
     * <p>The old test was "in a match that is still FIGHTING", which is another way of
     * saying "killed cleanly by an opponent". A crystal chain decides the match on its
     * first detonation and kills the rest of the lobby on the following ticks, by which
     * point the phase has moved on and those players lost everything they were carrying.
     */
    private boolean insidePvpGamemode(Player player) {
        UUID playerId = player.getUniqueId();
        return isParticipant(playerId)
                || queuedPlayers.containsKey(playerId)
                || preparing.containsKey(playerId)
                || inLobbyArea(player.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDeath(PlayerDeathEvent event) {
        Match match = matchByPlayer.get(event.getPlayer().getUniqueId());
        if (match == null || match.phase != Phase.FIGHTING) {
            if (insidePvpGamemode(event.getPlayer())) PvpDuelService.keepEverything(event);
            return;
        }
        PvpDuelService.keepEverything(event);
        event.deathMessage(null);
        UUID victim = event.getPlayer().getUniqueId();
        UUID killer = recentAttacker(match, victim);
        plugin.getServer().getScheduler().runTask(plugin,
                () -> eliminate(match, victim, killer, "was eliminated"));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Match match = matchByPlayer.get(player.getUniqueId());
        if (match == null) return;
        Location spectator = match.arena.spectator();
        if (spectator != null) event.setRespawnLocation(spectator);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (match.eliminated.contains(player.getUniqueId())) prepareEliminated(match, player);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        Match match = matchByPlayer.get(playerId);
        if (match == null) match = viewing.get(playerId);
        if (match != null) {
            Location anchor = match.assigned.get(playerId);
            boolean anchored = viewing.containsKey(playerId) || match.eliminated.contains(playerId)
                    || match.phase != Phase.FIGHTING;
            if (anchored && anchor != null && movedPosition(event.getFrom(), event.getTo())) {
                event.setTo(anchor);
                return;
            }
            if (matchByPlayer.containsKey(playerId) && match.phase == Phase.FIGHTING
                    && match.alive.contains(playerId)) {
                if (!insideArena(match, event.getTo())) {
                    event.setCancelled(true);
                    player.sendActionBar(Component.text("Stay inside the PvP arena.", NamedTextColor.RED));
                    return;
                }
                if (movedPosition(event.getFrom(), event.getTo())) {
                    match.lastAction.put(playerId, System.currentTimeMillis());
                }
            }
            return;
        }
        if (!movedBlock(event.getFrom(), event.getTo())) return;
        long now = System.currentTimeMillis();
        PvpLobbyStore.Point lobby = lobbyStore.lobby().orElse(null);
        Location lobbyAt = lobby == null ? null : lobby.resolve();
        if (lobbyAt != null && isPvpWorld(event.getPlayer().getWorld())
                && event.getTo().getY() < lobbyAt.getY() - 8d) {
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> teleport(event.getPlayer(), lobbyAt));
            return;
        }
        if (padCooldowns.getOrDefault(playerId, 0L) > now) return;
        if (touchesEntrancePortal(event.getTo())) {
            player.setPortalCooldown(integer("pvp-competitive.portal-suppression-ticks"));
            padCooldowns.put(playerId, now + PAD_COOLDOWN_MILLIS);
            plugin.getServer().getScheduler().runTask(plugin, () -> enterLobby(player));
            return;
        }
        PvpMode pad = PvpLobbyBuilder.modePad(lobby, event.getTo());
        if (pad != null && pad.queueable()) {
            player.setPortalCooldown(integer("pvp-competitive.portal-suppression-ticks"));
            padCooldowns.put(playerId, now + PAD_COOLDOWN_MILLIS);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (queuedPlayers.containsKey(playerId)) openQueueStatus(player);
                else openMode(player, pad);
            });
        } else if (PvpLobbyBuilder.returnPad(lobby, event.getTo())) {
            player.setPortalCooldown(integer("pvp-competitive.portal-suppression-ticks"));
            padCooldowns.put(playerId, now + PAD_COOLDOWN_MILLIS);
            plugin.getServer().getScheduler().runTask(plugin, () -> returnToServer(player));
        } else if (lobbyAt != null && isPvpWorld(player.getWorld())) {
            PvpMode nearby = PvpLobbyBuilder.nearbyMode(lobby, event.getTo(), 8d);
            if (nearby != null) {
                player.sendActionBar(Component.text(nearby.display() + "  •  WALK THROUGH TO CHOOSE",
                        TextColor.color(0xE3C6FF), TextDecoration.BOLD));
            }
        }
    }

    /** The custom PvP portals are interactions, never vanilla Nether travel. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPortal(PlayerPortalEvent event) {
        PvpLobbyStore.Point lobby = lobbyStore.lobby().orElse(null);
        Location from = event.getFrom();
        PvpMode mode = PvpLobbyBuilder.modePad(lobby, from);
        if (touchesEntrancePortal(from) || mode != null
                || PvpLobbyBuilder.returnPad(lobby, from)) {
            event.setCancelled(true);
            event.getPlayer().setPortalCooldown(
                    integer("pvp-competitive.portal-suppression-ticks"));
            // If a client reached portal processing before the proactive cooldown was
            // applied, it may already have dismissed its dialog. Restore the exact
            // queue page on the next tick instead of leaving the player with no UI.
            if (mode != null && mode.queueable()) {
                Player player = event.getPlayer();
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline() || isParticipant(player.getUniqueId())) return;
                    if (queuedPlayers.containsKey(player.getUniqueId())) openQueueStatus(player);
                    else openMode(player, mode);
                });
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (internalTeleports.contains(playerId)) return;
        if (isParticipant(playerId)) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text(
                    "Teleporting is disabled during competitive PvP.", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onTeleportMonitor(PlayerTeleportEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (!event.isCancelled() && isParticipant(playerId)
                && !internalTeleports.contains(playerId)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!isParticipant(event.getPlayer().getUniqueId())) return;
        String lower = event.getMessage().strip().toLowerCase(Locale.ROOT);
        if (lower.equals("/pvp") || lower.startsWith("/pvp ")
                || lower.equals("/duel") || lower.startsWith("/duel ")) return;
        event.setCancelled(true);
        error(event.getPlayer(), "Outside commands are disabled during competitive PvP.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        if (isParticipant(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isParticipant(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            Match match = matchByPlayer.get(player.getUniqueId());
            boolean anchored = viewing.containsKey(player.getUniqueId())
                    || (match != null && match.eliminated.contains(player.getUniqueId()));
            if (anchored || (match != null
                    && event.getInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && (viewing.containsKey(player.getUniqueId())
                || Optional.ofNullable(matchByPlayer.get(player.getUniqueId()))
                .map(match -> match.eliminated.contains(player.getUniqueId())).orElse(false))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && (viewing.containsKey(player.getUniqueId())
                || Optional.ofNullable(matchByPlayer.get(player.getUniqueId()))
                .map(match -> match.eliminated.contains(player.getUniqueId())).orElse(false))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (isParticipant(player.getUniqueId())) {
            Match match = matchByPlayer.get(player.getUniqueId());
            if (match == null || match.phase != Phase.FIGHTING
                    || !match.alive.contains(player.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
            match.lastAction.put(player.getUniqueId(), System.currentTimeMillis());
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null
                || event.getHand() != EquipmentSlot.HAND) return;
        PvpLobbyStore.Point lobby = lobbyStore.lobby().orElse(null);
        PvpLobbyBuilder.PavilionAction action = PvpLobbyBuilder.pavilionAction(
                lobby, event.getClickedBlock().getLocation());
        if (action == null) return;
        event.setCancelled(true);
        switch (action) {
            case LADDER -> openLadder(player);
            case RULES -> openRules(player);
            case RATINGS -> openRankings(player);
            case LIVE -> openLive(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (isParticipant(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onProjectile(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player) {
            if (event.getEntity() instanceof EnderPearl && isParticipant(player.getUniqueId())) {
                event.setCancelled(true);
                error(player, "Ender pearls do not work in a fight.");
                return;
            }
            Match match = matchByPlayer.get(player.getUniqueId());
            if (viewing.containsKey(player.getUniqueId()) || match == null
                    || match.phase != Phase.FIGHTING || !match.alive.contains(player.getUniqueId())) {
                if (isParticipant(player.getUniqueId())) event.setCancelled(true);
            } else {
                match.lastAction.put(player.getUniqueId(), System.currentTimeMillis());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (event.getItem().getType() == Material.CHORUS_FRUIT
                && isParticipant(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            error(event.getPlayer(), "Chorus fruit does not work in a fight.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player) {
            Match match = matchByPlayer.get(player.getUniqueId());
            if (viewing.containsKey(player.getUniqueId())
                    || (match != null && match.eliminated.contains(player.getUniqueId()))) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isPvpWorld(event.getBlock().getWorld()) || inLobbyArea(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isPvpWorld(event.getBlock().getWorld()) || inLobbyArea(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> isPvpWorld(block.getWorld())
                || inLobbyArea(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> isPvpWorld(block.getWorld())
                || inLobbyArea(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        Match match = viewing.get(playerId);
        if (match == null) {
            Match fighterMatch = matchByPlayer.get(playerId);
            if (fighterMatch != null && fighterMatch.eliminated.contains(playerId)) match = fighterMatch;
        }
        if (match != null) {
            event.setCancelled(true);
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> error(event.getPlayer(), "Spectator chat is disabled to prevent coaching."));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (recovery.find(playerId).isPresent() && !isParticipant(playerId)) {
            plugin.getServer().getScheduler().runTask(plugin, () -> recover(event.getPlayer(), true));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        leaveQueue(playerId, true);
        Party party = parties.get(playerId);
        if (party != null) {
            party.members.remove(playerId);
            parties.remove(playerId);
            if (party.members.isEmpty()) {
                // nothing remains
            } else if (party.leader.equals(playerId)) {
                party.leader = party.members.iterator().next();
            }
            for (UUID member : party.members) parties.put(member, party);
        }
        Match watched = viewing.remove(playerId);
        if (watched != null) watched.spectators.remove(playerId);
        Match match = matchByPlayer.get(playerId);
        if (match == null) return;
        if (match.phase == Phase.COUNTDOWN) {
            abortStart(match, "A player disconnected before combat began.");
        } else if (match.phase == Phase.FIGHTING) {
            eliminate(match, playerId, null, "disconnected and forfeited");
        }
    }

    void pauseAll(String reason) {
        for (PvpMode mode : PvpMode.values()) {
            for (PvpMatchmaking.Entry entry : List.copyOf(queues.getOrDefault(mode, List.of()))) {
                removeEntry(mode, entry, reason);
            }
        }
        for (Match match : List.copyOf(matches.values())) {
            if (match.phase == Phase.COUNTDOWN) abortStart(match, reason);
            else if (match.phase == Phase.FIGHTING) finish(match, List.of(), reason, false);
        }
    }

    void stop() {
        stopping = true;
        if (clock != null) clock.cancel();
        clock = null;
        setEntrancePortalLit(false);
        clearEntranceDisplays();
        for (Match match : List.copyOf(matches.values())) {
            if (match.countdown != null) match.countdown.cancel();
            if (match.returnTask != null) match.returnTask.cancel();
            match.phase = Phase.ENDING;
            for (UUID playerId : match.players) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) restore(player);
                matchByPlayer.remove(playerId);
            }
            for (UUID spectatorId : match.spectators) {
                Player player = Bukkit.getPlayer(spectatorId);
                if (player != null) restore(player);
                viewing.remove(spectatorId);
            }
            if (match.bar != null) forEachOnline(concat(match.players,
                    List.copyOf(match.spectators)),
                    player -> plugin.bossBars().hideExclusive(player, match.bar));
            duels.releaseCompetitiveArena(match.arena.id());
        }
        matches.clear();
        preparing.clear();
        queues.clear();
        queuedPlayers.clear();
        queuedModes.clear();
        partyInvites.clear();
        parties.clear();
        farmGuard.clear();
    }

    private boolean opponentsAllowed(List<UUID> first, List<UUID> second, long now) {
        for (UUID left : first) {
            for (UUID right : second) {
                if (identities != null && identities.sameOwner(left, right)) return false;
                if (farmGuard.restRemaining(left, right, now) > 0L) return false;
            }
        }
        return true;
    }

    private void removeEntry(PvpMode mode, PvpMatchmaking.Entry entry, String message) {
        List<PvpMatchmaking.Entry> queue = queues.get(mode);
        if (queue != null) queue.remove(entry);
        for (UUID member : entry.members()) {
            queuedPlayers.remove(member);
            queuedModes.remove(member);
        }
        if (message != null) notifyPlayers(entry.members(), message);
    }

    private void clearPending(PendingStart pending) {
        for (UUID playerId : pending.players()) preparing.remove(playerId, pending);
    }

    private static List<UUID> flatten(List<PvpMatchmaking.Entry> entries) {
        return entries.stream().flatMap(entry -> entry.members().stream()).toList();
    }

    private static List<UUID> concat(List<UUID> first, List<UUID> second) {
        List<UUID> all = new ArrayList<>(first);
        all.addAll(second);
        return List.copyOf(all);
    }

    private static boolean playersReady(List<UUID> players) {
        return players.stream().allMatch(id -> {
            Player player = Bukkit.getPlayer(id);
            return player != null && player.isOnline();
        });
    }

    private static long aliveOnTeam(Match match, int team) {
        return match.alive.stream().filter(id -> match.team.get(id) == team).count();
    }

    private static String teamLabel(Match match, UUID player) {
        if (match.mode.freeForAll()) return "Every player for themselves";
        return match.team.get(player) == 0 ? "Team Gold" : "Team Red";
    }

    private static String winnerText(Match match, List<UUID> winners) {
        if (match.mode.freeForAll()) return name(winners.get(0)) + " is the last player standing";
        return (match.team.get(winners.get(0)) == 0 ? "Team Gold" : "Team Red") + " wins";
    }

    private static String resultLine(Match match, UUID playerId, String reason, String rating) {
        int kills = match.kills.getOrDefault(playerId, 0);
        double damage = match.damage.getOrDefault(playerId, 0d);
        return reason + rating + "  •  " + kills + " kills  •  " + Math.round(damage) + " damage";
    }

    private static String matchSummary(Match match) {
        return match.mode.display() + " — " + match.alive.size()
                + " alive. Use /pvp forfeit to concede.";
    }

    private UUID recentAttacker(Match match, UUID victim) {
        long at = match.lastHitAt.getOrDefault(victim, 0L);
        return System.currentTimeMillis() - at <= 10_000L ? match.lastHitBy.get(victim) : null;
    }

    private static UUID attackingSource(EntityDamageByEntityEvent event) {
        return PvpDuelService.fightingSource(event);
    }

    private static Player attackingPlayer(EntityDamageByEntityEvent event) {
        return PvpDuelService.attackingPlayer(event);
    }

    private WorldBorder personalBorder(Match match, double size) {
        WorldBorder border = Bukkit.createWorldBorder();
        Location location = match.arena.arena().center();
        if (location != null) border.setCenter(location);
        border.setSize(Math.max(1d, size));
        border.setWarningDistance(5);
        border.setDamageBuffer(0d);
        border.setDamageAmount(2d);
        return border;
    }

    private boolean teleport(Player player, Location target) {
        internalTeleports.add(player.getUniqueId());
        try {
            return player.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            internalTeleports.remove(player.getUniqueId());
        }
    }

    private static Location origin(PvpDuelStore.Recovery saved) {
        World world = Bukkit.getWorld(saved.worldId());
        if (world == null) world = Bukkit.getWorld(saved.worldName());
        return world == null ? null : new Location(world, saved.x(), saved.y(), saved.z(),
                saved.yaw(), saved.pitch());
    }

    private static void clearEffects(Player player) {
        for (PotionEffect effect : List.copyOf(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
    }

    private static boolean insideArena(Match match, Location location) {
        Location center = match.arena.arena().center();
        return location != null && location.getWorld() != null && center.getWorld() != null
                && location.getWorld().equals(center.getWorld())
                && PvpDuelRules.inside(location.getX(), location.getZ(),
                        center.getX(), center.getZ(), match.borderSize);
    }

    private static boolean movedPosition(Location from, Location to) {
        return to != null && (from.getX() != to.getX() || from.getY() != to.getY()
                || from.getZ() != to.getZ());
    }

    private static boolean movedBlock(Location from, Location to) {
        return to != null && (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ() || !sameWorld(from, to));
    }

    private static boolean sameWorld(Location first, Location second) {
        return first != null && second != null && first.getWorld() != null
                && first.getWorld().equals(second.getWorld());
    }

    private static double horizontalSquared(Location first, Location second) {
        double x = first.getX() - second.getX();
        double z = first.getZ() - second.getZ();
        return x * x + z * z;
    }

    private boolean isPvpWorld(World world) {
        return world != null && PvpLobbyBuilder.WORLD_NAME.equals(world.getName());
    }

    private boolean inLobbyArea(Location at) {
        Location lobby = lobbyStore.lobby().map(PvpLobbyStore.Point::resolve).orElse(null);
        return lobby != null && sameWorld(lobby, at)
                && horizontalSquared(lobby, at)
                <= PvpLobbyBuilder.PROTECTED_RADIUS * PvpLobbyBuilder.PROTECTED_RADIUS
                && Math.abs(lobby.getY() - at.getY()) <= 24d;
    }

    private int integer(String key) {
        return plugin.gameVariables().integer(key);
    }

    private double decimal(String key) {
        return plugin.gameVariables().decimal(key);
    }

    private static boolean isAdmin(CommandSender sender) {
        return sender.isOp() || sender.hasPermission(AdminCommandService.PERMISSION);
    }

    private static String[] slice(String[] args, int start) {
        if (args.length <= start) return new String[0];
        return java.util.Arrays.copyOfRange(args, start, args.length);
    }

    private static List<String> partial(String typed, Collection<String> choices) {
        String prefix = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        return choices.stream().filter(choice -> choice.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private List<String> adminTabs(String[] original) {
        String[] args = original;
        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) args = slice(args, 1);
        if (args.length == 1) return partial(args[0], List.of("setup", "portal"));
        if (args.length == 2 && args[0].equalsIgnoreCase("portal")) {
            return partial(args[1], List.of("set", "remove"));
        }
        return List.of();
    }

    private static String formatSeconds(long seconds) {
        long minutes = seconds / 60L;
        long remainder = seconds % 60L;
        return minutes <= 0L ? remainder + "s" : minutes + "m " + remainder + "s";
    }

    private static String name(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) return online.getName();
        String stored = Bukkit.getOfflinePlayer(playerId).getName();
        return stored == null ? playerId.toString().substring(0, 8) : stored;
    }

    private static Component prefix() {
        return Component.text("PVP  ", ORANGE, TextDecoration.BOLD);
    }

    private static void info(Player player, String message) {
        player.sendMessage(prefix().append(Component.text(message, NamedTextColor.GRAY)));
    }

    private static void error(Player player, String message) {
        player.sendMessage(prefix().append(Component.text(message, NamedTextColor.RED)));
    }

    private static void notifyPlayers(Collection<UUID> players, String message) {
        forEachOnline(players, player -> info(player, message));
    }

    private static void notifyMatch(Match match, String message, NamedTextColor colour) {
        forEachOnline(concat(match.players, List.copyOf(match.spectators)), player ->
                player.sendMessage(prefix().append(Component.text(message, colour))));
    }

    private static void forEachOnline(
            Collection<UUID> players, java.util.function.Consumer<Player> action
    ) {
        for (UUID playerId : players) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) action.accept(player);
        }
    }
}
