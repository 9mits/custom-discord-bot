package bot.mgx.accessbridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static bot.mgx.accessbridge.MenuItems.ORANGE;

/**
 * The operator command: {@code /mgxadmin}.
 *
 * <p>Two jobs live behind it, both of which exist because Discord is normally the
 * authority and occasionally must not be:
 *
 * <ul>
 *   <li>{@code ranks hold} takes a player out of Discord rank sync, so a group given
 *       to them in LuckPerms by hand stops being undone by their Discord roles.
 *   <li>{@code reset} clears accumulated progress — statistics, advancements, items,
 *       clans and balances — without touching anything anyone has built.
 * </ul>
 */
final class AdminCommandService implements CommandExecutor, TabCompleter {
    static final String PERMISSION = "mgxaccessbridge.admin";
    /**
     * What completion offers at the root.
     *
     * <p>Deliberately excludes {@code airdrop}, {@code abuse} and {@code multiplier}:
     * each still dispatches, but only as a legacy spelling of an {@code event} path
     * that does the same work, so completion steers to the one event family instead of
     * advertising two ways to do the same thing. {@code AdminCompletionCoverageTest}
     * names them, and {@code AdminEventCommandTest} holds them out of this list.
     */
    private static final List<String> SUBCOMMANDS = List.of(
            "startserver", "teststart", "pvp", "give", "ranks", "eco", "bounty", "hologram",
            "reset", "testverify", "testcrate", "testairdrop", "testamethystblock", "devblog", "update", "serials",
            "cosmetics", "clanbattle", "event", "variables", "help"
    );
    private static final List<String> CRATE_REVEAL_TIERS = List.of(
            "legendary", "mythic", "exotic", "secret"
    );
    private static final List<String> AIRDROP_RARITIES = List.of(
            "common", "rare", "legendary", "mythic"
    );
    private static final List<String> PVP_ACTIONS = List.of("on", "off", "status");
    private static final List<String> RANK_ACTIONS = List.of("hold", "release", "list");
    private static final List<String> DEVBLOG_ACTIONS = List.of(
            "on", "off", "cam", "time", "weather", "players", "status"
    );
    private static final List<String> DEVBLOG_TIMES = List.of(
            "day", "noon", "dusk", "night", "midnight", "dawn", "reset"
    );
    private static final List<String> DEVBLOG_WEATHER = List.of("clear", "rain", "reset");
    private static final List<String> ECO_ACTIONS = List.of("give", "take", "set", "join");
    private static final List<String> BOUNTY_ACTIONS = List.of("set", "join");
    private static final List<String> JOIN_ACTIONS = List.of("on", "off");
    private static final List<String> EVERYONE = List.of("everyone", "*", "all");
    private static final List<String> HOLOGRAM_BOARDS = List.of(
            "wealth", "kills", "amethyst-crates", "amethyst-airdrops",
            "clans-wealth", "clans-kills", "clan-battle", "remove"
    );
    private static final List<String> CLAN_BATTLE_ACTIONS = List.of(
            "start", "status", "end", "cancel"
    );

    private final MGXAccessBridge plugin;
    private final RankSyncStore rankSync;
    private final EconomyStore economy;
    private final CrateItems crateItems;
    private final CosmeticStore cosmetics;
    private final CosmeticItems cosmeticItems;
    private final BountyStore bounties;
    private final JoinGrantStore joinGrants;
    private final HologramService holograms;
    private final ServerDataResetService resets;
    private final DevBlogService devBlog;
    private final AdminEventService adminEvents;
    private final EconomyMenuService auctionHouse;
    private final UpdateNoticeService updateNotices;
    private final CrateService crates;
    private final AirdropService airdrops;
    private final AmethystBlockEventService amethystBlocks;
    private final AmethystProgressStore amethystProgress;
    private final ClanBattleService clanBattles;
    private final GameVariableStore variables;

    AdminCommandService(
            MGXAccessBridge plugin,
            RankSyncStore rankSync,
            EconomyStore economy,
            CrateItems crateItems,
            CosmeticStore cosmetics,
            CosmeticItems cosmeticItems,
            BountyStore bounties,
            JoinGrantStore joinGrants,
            HologramService holograms,
            ServerDataResetService resets,
            DevBlogService devBlog,
            AdminEventService adminEvents,
            EconomyMenuService auctionHouse,
            UpdateNoticeService updateNotices,
            CrateService crates,
            AirdropService airdrops,
            AmethystBlockEventService amethystBlocks,
            AmethystProgressStore amethystProgress,
            ClanBattleService clanBattles,
            GameVariableStore variables
    ) {
        this.plugin = plugin;
        this.rankSync = rankSync;
        this.economy = economy;
        this.crateItems = crateItems;
        this.cosmetics = cosmetics;
        this.cosmeticItems = cosmeticItems;
        this.bounties = bounties;
        this.joinGrants = joinGrants;
        this.holograms = holograms;
        this.resets = resets;
        this.devBlog = devBlog;
        this.adminEvents = adminEvents;
        this.auctionHouse = auctionHouse;
        this.updateNotices = updateNotices;
        this.crates = crates;
        this.airdrops = airdrops;
        this.amethystBlocks = amethystBlocks;
        this.amethystProgress = amethystProgress;
        this.clanBattles = clanBattles;
        this.variables = variables;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        args = CommandArgs.withoutEchoedSender(sender.getName(), args);
        // Only when typed directly; the router calls this with its own label.
        if (label.equalsIgnoreCase("mgxadmin") || label.equalsIgnoreCase("mcadmin")) {
            MgxCommandRouter.noteDeprecation(sender, "/" + label, args);
        }
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (action.equals("variables") || action.equals("variable") || action.equals("vars")) {
            if (sender instanceof Player player && !plugin.hasOwnerRankLoaded(player.getUniqueId())) {
                error(sender, "Only the synced OWNER role can change live game variables.");
                return true;
            }
            try {
                variables(sender, args);
            } catch (IllegalArgumentException exception) {
                error(sender, exception.getMessage());
            }
            return true;
        }
        if (!plugin.mayAdminister(sender)) {
            error(sender, "You do not have permission to do that.");
            return true;
        }
        try {
            switch (action) {
                case "startserver" -> {
                    plugin.startLaunch(sender);
                    success(sender, "Launch countdown started.");
                }
                case "teststart" -> {
                    plugin.startLaunchTest(sender);
                    success(sender, "Test countdown started. Barriers return in 1 minute.");
                }
                case "pvp" -> pvp(sender, args);
                case "give" -> give(sender, args);
                case "ranks" -> ranks(sender, args);
                case "eco" -> eco(sender, args);
                case "bounty" -> bounty(sender, args);
                case "hologram", "holograms", "lb" -> hologram(sender, args);
                case "reset" -> reset(sender, args);
                case "testverify" -> testVerify(sender, args);
                case "testcrate", "cratetest", "testreveal" -> testCrateReveal(sender, args);
                case "airdrop", "calldrop" -> callAirdrop(sender, args);
                case "testairdrop", "airdroptest", "testdrop" -> testAirdrop(sender, args);
                case "testamethystblock", "testhugeblock" -> testAmethystBlock(sender, args);
                case "devblog", "screenshot" -> devBlog(sender, args);
                case "update" -> publishUpdate(sender);
                case "serials" -> serials(sender, args);
                case "cosmetics" -> cosmetics(sender, args);
                case "clanbattle", "clan-battle" -> clanBattle(sender, args);
                case "abuse" -> {
                    String summary = adminEvents.run(sender, args);
                    success(sender, summary + ".");
                    report(sender, "admin_event", summary).record();
                }
                case "event", "events" -> event(sender, args);
                case "multiplier" -> serverEvent(sender, args);
                default -> sendHelp(sender);
            }
        } catch (IllegalArgumentException exception) {
            error(sender, exception.getMessage());
        }
        return true;
    }

    private void variables(CommandSender sender, String[] args) {
        String operation = args.length < 2 ? "list" : args[1].toLowerCase(Locale.ROOT);
        if (operation.equals("list")) {
            String filter = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "";
            List<JsonObject> rows = variables.snapshot().getAsJsonArray("variables").asList()
                    .stream().map(JsonElement::getAsJsonObject)
                    .filter(row -> filter.isBlank()
                            || row.get("key").getAsString().toLowerCase(Locale.ROOT).contains(filter)
                            || row.get("category").getAsString().toLowerCase(Locale.ROOT).contains(filter))
                    .toList();
            sender.sendMessage(Component.text("Live game variables", ORANGE, TextDecoration.BOLD));
            rows.stream().limit(40).forEach(row -> {
                sender.sendMessage(Component.text("  " + row.get("key").getAsString() + " = "
                        + row.get("value").getAsString(), NamedTextColor.GRAY));
            });
            sender.sendMessage(Component.text(
                    "  Showing " + Math.min(rows.size(), 40) + " of " + rows.size()
                            + ". Filter with /mgxadmin variables list <word>.", NamedTextColor.GRAY
            ));
            sender.sendMessage(Component.text(
                    "  Use get|set|reset <key> [value] for one variable.", NamedTextColor.GRAY
            ));
            return;
        }
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "Use /mgxadmin variables get|set|reset <key> [value]."
            );
        }
        String key = args[2];
        if (operation.equals("get")) {
            JsonObject row = variables.snapshot().getAsJsonArray("variables").asList().stream()
                    .map(JsonElement::getAsJsonObject)
                    .filter(value -> value.get("key").getAsString().equalsIgnoreCase(key))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "Unknown variable '" + key + "'."
                    ));
            info(sender, row.get("key").getAsString() + " = " + row.get("value").getAsString());
            return;
        }
        String summary;
        if (operation.equals("reset")) {
            summary = variables.reset(key);
        } else if (operation.equals("set") && args.length >= 4) {
            summary = variables.set(key, args[3]);
        } else {
            throw new IllegalArgumentException(
                    "Use /mgxadmin variables get|set|reset <key> [value]."
            );
        }
        success(sender, summary + ". It is live now; no restart is required.");
        report(sender, "game_variable", "Changed live game variable")
                .detail("key", key)
                .detail("value", summary)
                .record();
    }

    /** {@code /mgxadmin pvp <on|off|status>} */
    private void pvp(CommandSender sender, String[] args) {
        String action = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
        if (action.equals("status")) {
            info(sender, plugin.pvpStatus());
            return;
        }
        boolean enabled = switch (action) {
            case "on", "enable", "true" -> true;
            case "off", "disable", "false" -> false;
            default -> throw new IllegalArgumentException("Use /mgxadmin pvp <on|off|status>.");
        };
        plugin.forcePvp(enabled);
        // Players are told because it changes whether they can be hit where they stand,
        // and the pin is the one PvP change no countdown announced for them.
        Bukkit.broadcast(Component.text(
                "PvP is now " + (enabled ? "ON" : "OFF") + ".", NamedTextColor.GOLD
        ).decorate(TextDecoration.BOLD));
        success(sender, "PvP is pinned " + (enabled ? "on" : "off")
                + " everywhere. It stays there through a restart and through the launch hold.");
        report(sender, "admin_pvp", sender.getName() + " pinned PvP "
                + (enabled ? "on" : "off")).detail("state", enabled ? "on" : "off").record();
    }

    // ------------------------------------------------------------------
    // Screenshot mode, for photographing an update
    // ------------------------------------------------------------------

    /** One organized home for multiplier, Airdrop, Huge Block and admin events. */
    private void event(CommandSender sender, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("list")
                || args[1].equalsIgnoreCase("help")) {
            sendEventHelp(sender);
            return;
        }
        String category = args[1].toLowerCase(Locale.ROOT);
        switch (category) {
            case "multiplier", "boost" -> serverEvent(sender, shifted(args, "event", 2));
            case "airdrop", "drop" -> eventAirdrop(sender, args);
            case "amethyst-block", "amethystblock", "block", "huge-block" ->
                    eventAmethystBlock(sender, args);
            case "schedule", "frequency" -> eventSchedule(sender, args);
            case "admin", "chaos" -> {
                String summary = adminEvents.run(sender, shifted(args, "abuse", 2));
                success(sender, summary + ".");
                report(sender, "admin_event", summary).record();
            }
            default -> {
                // Backward compatible: /mgxadmin event key on 3600 still works.
                if (ServerEventType.resolve(args[1]).isPresent()) {
                    serverEvent(sender, args);
                } else {
                    throw new IllegalArgumentException(
                            "Use /mgxadmin event list for every event control."
                    );
                }
            }
        }
    }

    private void eventAirdrop(CommandSender sender, String[] args) {
        String operation = args.length < 3 ? "status" : args[2].toLowerCase(Locale.ROOT);
        switch (operation) {
            case "start", "spawn" -> {
                if (!(sender instanceof Player player)) {
                    throw new IllegalArgumentException("Run the Airdrop start command in game.");
                }
                AirdropCatalog.Rarity rarity = args.length > 3 ? airdropRarity(args[3]) : null;
                AirdropService.Snapshot drop = airdrops.spawnNear(player, rarity, List.of());
                success(sender, "Started " + drop.describe() + ".");
                report(sender, "airdrop_spawn", "Started an Amethyst Airdrop")
                        .detail("rarity", drop.rarity().name().toLowerCase(Locale.ROOT))
                        .detail("location", drop.world() + " " + drop.x() + " " + drop.y()
                                + " " + drop.z()).record();
            }
            case "status" -> {
                List<AirdropService.Snapshot> standing = airdrops.snapshots();
                heading(sender, "Amethyst Airdrops — " + standing.size() + "/"
                        + airdrops.maximumActive() + " active");
                if (standing.isEmpty()) {
                    info(sender, "No Airdrops are active.");
                } else {
                    standing.forEach(drop -> info(sender, drop.describe()));
                }
                showAirdropDistances(sender);
            }
            case "end", "stop", "remove" -> {
                int removed = airdrops.removeTest();
                success(sender, "Ended " + removed + " active Airdrop(s) without rewards.");
            }
            case "expire" -> {
                int expired = airdrops.expireTest();
                success(sender, "Expired " + expired + " active Airdrop(s).");
            }
            case "distance", "range" -> {
                if (args.length < 6) {
                    showAirdropDistances(sender);
                    info(sender, "Usage: /mgxadmin event airdrop distance <rarity> <minimum> <maximum>");
                    return;
                }
                AirdropCatalog.Rarity rarity = airdropRarity(args[3]);
                int minimum = positiveWhole(args[4], "minimum distance");
                int maximum = positiveWhole(args[5], "maximum distance");
                if (minimum > maximum) {
                    throw new IllegalArgumentException("Minimum distance cannot exceed maximum distance.");
                }
                setAirdropDistance(rarity, minimum, maximum);
                success(sender, rarity.displayName() + " Airdrops now land " + minimum + "-"
                        + maximum + " blocks from spawn. No restart is required.");
            }
            default -> throw new IllegalArgumentException(
                    "Use /mgxadmin event airdrop <start|status|end|expire|distance>."
            );
        }
    }

    private void eventAmethystBlock(CommandSender sender, String[] args) {
        String operation = args.length < 3 ? "status" : args[2].toLowerCase(Locale.ROOT);
        switch (operation) {
            case "start", "spawn" -> {
                if (!(sender instanceof Player player)) {
                    throw new IllegalArgumentException("Run the Huge Amethyst Block start command in game.");
                }
                AmethystBlockEventService.Snapshot block = amethystBlocks.spawnNear(player);
                success(sender, "Started " + block.describe() + ".");
            }
            case "status" -> {
                AmethystBlockEventService.Snapshot block = amethystBlocks.snapshot();
                info(sender, block == null ? "No Huge Amethyst Block is active." : block.describe());
            }
            case "damage" -> {
                double damage;
                try {
                    damage = args.length > 3 ? Double.parseDouble(args[3]) : 1_000d;
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("Damage must be a positive number.");
                }
                if (!Double.isFinite(damage) || damage <= 0d) {
                    throw new IllegalArgumentException("Damage must be a positive number.");
                }
                if (!amethystBlocks.damageTest(damage)) {
                    throw new IllegalArgumentException("There is no active block to damage.");
                }
                success(sender, "Dealt " + Math.round(damage) + " damage to the active block.");
            }
            case "finish" -> {
                if (!amethystBlocks.damageTest(AmethystBlockRewards.MAX_HEALTH)) {
                    throw new IllegalArgumentException("There is no active block to finish.");
                }
                success(sender, "Finished the active block and delivered its rewards.");
            }
            case "end", "stop", "remove" -> {
                if (!amethystBlocks.removeTest()) {
                    throw new IllegalArgumentException("There is no active block to end.");
                }
                success(sender, "Ended the active block without rewards.");
            }
            case "expire" -> {
                if (!amethystBlocks.expireTest()) {
                    throw new IllegalArgumentException("There is no active block to expire.");
                }
                success(sender, "Expired the active block.");
            }
            default -> throw new IllegalArgumentException(
                    "Use /mgxadmin event amethyst-block <start|status|damage|finish|end|expire>."
            );
        }
    }

    private void eventSchedule(CommandSender sender, String[] args) {
        String operation = args.length < 3 ? "status" : args[2].toLowerCase(Locale.ROOT);
        if (operation.equals("status")) {
            info(sender, "Amethyst events wait "
                    + variables.integer("amethyst-events.minimum-delay-minutes") + "-"
                    + variables.integer("amethyst-events.maximum-delay-minutes")
                    + " minutes after an event ends. Airdrops and Huge Blocks alternate.");
            return;
        }
        if (operation.equals("reset")) {
            setPair("amethyst-events.minimum-delay-minutes",
                    "amethyst-events.maximum-delay-minutes", "15", "30");
            success(sender, "Reset the event cooldown to 15-30 minutes. No restart is required.");
            return;
        }
        if (!operation.equals("set") || args.length < 5) {
            throw new IllegalArgumentException(
                    "Use /mgxadmin event schedule <status|set <minimum> <maximum>|reset>."
            );
        }
        int minimum = positiveWhole(args[3], "minimum delay");
        int maximum = positiveWhole(args[4], "maximum delay");
        if (minimum > maximum || maximum > 1_440) {
            throw new IllegalArgumentException("Use a 1-1,440 minute range with minimum <= maximum.");
        }
        setPair("amethyst-events.minimum-delay-minutes",
                "amethyst-events.maximum-delay-minutes", String.valueOf(minimum),
                String.valueOf(maximum));
        success(sender, "Amethyst events now wait " + minimum + "-" + maximum
                + " minutes. No restart is required.");
    }

    private void showAirdropDistances(CommandSender sender) {
        for (AirdropCatalog.Rarity rarity : AirdropCatalog.Rarity.values()) {
            String base = "airdrop.rarity-radius."
                    + rarity.name().toLowerCase(Locale.ROOT) + ".";
            info(sender, rarity.displayName() + ": " + variables.integer(base + "minimum")
                    + "-" + variables.integer(base + "maximum") + " blocks from spawn");
        }
    }

    private void setAirdropDistance(AirdropCatalog.Rarity rarity, int minimum, int maximum) {
        String base = "airdrop.rarity-radius."
                + rarity.name().toLowerCase(Locale.ROOT) + ".";
        setPair(base + "minimum", base + "maximum",
                String.valueOf(minimum), String.valueOf(maximum));
    }

    /** Changes a validated minimum/maximum pair without a transient inverted range. */
    private void setPair(String minimumKey, String maximumKey, String minimum, String maximum) {
        long newMinimum = Long.parseLong(minimum);
        if (newMinimum > variables.integer(maximumKey)) {
            variables.set(maximumKey, maximum);
            variables.set(minimumKey, minimum);
        } else {
            variables.set(minimumKey, minimum);
            variables.set(maximumKey, maximum);
        }
    }

    private static int positiveWhole(String raw, String label) {
        try {
            int parsed = Integer.parseInt(raw.replace(",", ""));
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be a positive whole number.");
        }
    }

    private static String[] shifted(String[] args, String root, int from) {
        String[] shifted = new String[Math.max(1, args.length - from + 1)];
        shifted[0] = root;
        if (args.length > from) {
            System.arraycopy(args, from, shifted, 1, args.length - from);
        }
        return shifted;
    }

    private void sendEventHelp(CommandSender sender) {
        heading(sender, "Event controls");
        info(sender, "Scheduled Amethyst events: "
                + variables.integer("amethyst-events.minimum-delay-minutes") + "-"
                + variables.integer("amethyst-events.maximum-delay-minutes")
                + " minutes after the previous event ends");
        info(sender, "Active Airdrops: " + airdrops.snapshots().size() + "/"
                + airdrops.maximumActive());
        AmethystBlockEventService.Snapshot block = amethystBlocks.snapshot();
        info(sender, block == null ? "Huge Amethyst Block: inactive"
                : "Huge Amethyst Block: " + block.describe());
        info(sender, "/mgxadmin event multiplier <type> <on|off> [seconds]");
        info(sender, "/mgxadmin event airdrop <start [rarity]|status|end|expire>");
        info(sender, "/mgxadmin event airdrop distance <rarity> <minimum> <maximum>");
        info(sender, "/mgxadmin event amethyst-block <start|status|damage [hp]|finish|end|expire>");
        info(sender, "/mgxadmin event schedule <status|set <minimum> <maximum>|reset>");
        info(sender, "/mgxadmin event admin <effect|list|controls|stop> [options]");
        ServerEventService events = plugin.serverEvents();
        if (events != null) listServerEvents(sender, events);
    }

    /** {@code /mgxadmin event multiplier <type> <on|off> [seconds]} */
    private void serverEvent(CommandSender sender, String[] args) {
        ServerEventService events = plugin.serverEvents();
        if (events == null) {
            throw new IllegalArgumentException("Server events are not available right now.");
        }
        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            listServerEvents(sender, events);
            return;
        }
        ServerEventType type = ServerEventType.resolve(args[1]).orElseThrow(
                () -> new IllegalArgumentException(
                        "Unknown event. Try: " + Arrays.stream(ServerEventType.values())
                                .map(ServerEventType::id).collect(Collectors.joining(", "))
                )
        );
        boolean enabled = args.length < 3 || !args[2].equalsIgnoreCase("off");
        long seconds = enabled ? ServerEventType.secondsOrThrow(
                args.length > 3 ? args[3] : (args.length > 2 && !args[2].equalsIgnoreCase("on")
                        ? args[2] : null)
        ) : 0L;
        if (!events.set(type, enabled, seconds)) {
            success(sender, type.displayName() + " was already "
                    + (enabled ? "running" : "off") + ".");
            return;
        }
        String summary = enabled
                ? type.displayName() + " is live"
                + (seconds > 0 ? " for " + ServerEventService.humanDuration(seconds * 1_000L)
                               : " until turned off")
                : type.displayName() + " has ended";
        success(sender, summary + ".");
        report(sender, "server_event", summary)
                .detail("event", type.id())
                .detail("enabled", String.valueOf(enabled))
                .detail("seconds", seconds)
                .record();
    }

    private void listServerEvents(CommandSender sender, ServerEventService events) {
        sender.sendMessage(Component.text("Server events", ORANGE, TextDecoration.BOLD));
        for (ServerEventType type : ServerEventType.values()) {
            boolean live = events.active(type);
            sender.sendMessage(Component.text("  " + type.id() + "  ", ORANGE)
                    .append(Component.text(type.displayName(), NamedTextColor.WHITE))
                    .append(Component.text(live ? "  LIVE" : "  off",
                            live ? NamedTextColor.GREEN : NamedTextColor.GRAY)));
        }
        sender.sendMessage(Component.text(
                "  /mgxadmin event multiplier <type> <on|off> [seconds]", NamedTextColor.GRAY));
    }

    private void publishUpdate(CommandSender sender) {
        int generation = updateNotices.publish(plugin.getServer().getOnlinePlayers());
        int online = plugin.getServer().getOnlinePlayers().size();
        success(sender, "Update notice " + generation + " is live. "
                + online + " player(s) online saw it now; everyone else sees it on their next join.");
        report(sender, "update_notice", "Published a NEW UPDATE notice")
                .detail("generation", generation)
                .detail("online", online)
                .record();
    }

    private void devBlog(CommandSender sender, String[] args) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            throw new IllegalArgumentException(
                    "Screenshot mode changes what you can see, so it has to be run in game."
            );
        }
        devBlog.handle(player, args);
    }

    private void serials(CommandSender sender, String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("reset")) {
            throw new IllegalArgumentException(
                    "Usage: /mgxadmin serials reset <cosmetic-id> confirm"
            );
        }
        CosmeticCatalog.Definition definition = CosmeticCatalog.find(args[2]).orElseThrow(
                () -> new IllegalArgumentException("No cosmetic called '" + args[2] + "'.")
        );
        boolean confirmed = Arrays.stream(args).anyMatch(value -> value.equalsIgnoreCase("confirm"));
        if (!confirmed) {
            error(sender, "This renumbers every existing " + definition.displayName()
                    + " token from #1. Add 'confirm' to run it.");
            return;
        }
        int changed = cosmetics.resetSerials(definition.id());
        int refreshed = Bukkit.getOnlinePlayers().stream()
                .mapToInt(player -> cosmeticItems.refreshCarried(
                        player, cosmetics, definition.id()
                ))
                .sum();
        success(sender, changed == 0
                ? "No " + definition.displayName() + " serials exist yet."
                : "Renumbered " + changed + " " + definition.displayName() + " serial(s) from #1.");
        if (refreshed > 0) {
            info(sender, "Updated " + refreshed + " carried token(s) with their new serial lore.");
        }
        report(sender, "cosmetic_serial_reset", "Reset serials for " + definition.displayName())
                .detail("cosmetic", definition.id())
                .detail("serials", changed)
                .detail("carried_tokens_refreshed", refreshed)
                .record();
    }

    private void cosmetics(CommandSender sender, String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("delete")) {
            throw new IllegalArgumentException(
                    "Usage: /mgxadmin cosmetics delete <player> confirm"
            );
        }
        OfflinePlayer target = requireNamedPlayer(args[2]);
        boolean confirmed = Arrays.stream(args).anyMatch(value -> value.equalsIgnoreCase("confirm"));
        if (!confirmed) {
            error(sender, "This permanently deletes every cosmetic owned by "
                    + nameOf(target) + ". Add 'confirm' to run it.");
            return;
        }
        Player online = target.getPlayer();
        Set<UUID> knownSerials = new java.util.HashSet<>(
                auctionHouse.cosmeticSerialsOwnedBy(target.getUniqueId(), cosmeticItems)
        );
        if (online != null) {
            cosmeticItems.carried(online).stream()
                    .map(CosmeticItems.TokenInfo::serial)
                    .forEach(knownSerials::add);
        }
        int deleted = cosmetics.deleteOwned(target.getUniqueId(), knownSerials);
        int auctionEntries = auctionHouse.deleteCosmeticSerials(knownSerials, cosmeticItems);
        if (online != null) {
            knownSerials.forEach(serial -> cosmeticItems.removeOne(online, serial));
        }
        success(sender, "Deleted " + deleted + " cosmetic(s) owned by " + nameOf(target) + ".");
        report(sender, "cosmetics_deleted", "Deleted a player's cosmetics")
                .detail("target_uuid", target.getUniqueId().toString())
                .detail("cosmetics", deleted)
                .detail("auction_entries", auctionEntries)
                .record();
    }

    // ------------------------------------------------------------------
    // One give for everything an operator hands out
    // ------------------------------------------------------------------

    private void give(CommandSender sender, String[] args) {
        if (args.length < 3) {
            throw new IllegalArgumentException(AdminGive.usage());
        }
        String targets = args[1];
        AdminGive.Request request = AdminGive.parse(args[2], args.length >= 4 ? args[3] : null);
        switch (request.type()) {
            case MONEY -> {
                int count = forEachTarget(
                        targets, player -> economy.deposit(player.getUniqueId(), request.amount())
                );
                String what = EconomyFormat.dollars(request.amount());
                success(sender, "Gave " + what + " to " + describeTargets(targets, count) + ".");
                audit(sender, targets, what, count);
            }
            case KEY -> {
                int amount = (int) request.amount();
                int count = forEachTarget(targets, player -> hand(player, crateItems.key(amount)));
                String what = amount + (amount == 1 ? " crate key" : " crate keys");
                success(sender, "Gave " + what + " to " + describeTargets(targets, count) + ".");
                audit(sender, targets, what, count);
            }
            case SHARD -> {
                int amount = (int) request.amount();
                int count = forEachTarget(targets, player -> hand(player, crateItems.shard(amount)));
                String what = amount + (amount == 1 ? " Shard" : " Shards");
                success(sender, "Gave " + what + " to " + describeTargets(targets, count) + ".");
                audit(sender, targets, what, count);
            }
            case COSMETIC -> {
                CosmeticCatalog.Definition definition = CosmeticCatalog.find(request.cosmeticId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "No cosmetic called '" + request.cosmeticId() + "'."
                        ));
                int count = forEachTarget(targets, player -> grantCosmetic(player, definition));
                success(sender, "Gave " + definition.displayName() + " to "
                        + describeTargets(targets, count) + ".");
                audit(sender, targets, definition.displayName(), count);
            }
            case LEADERBOARD_COSMETICS -> {
                int count = forEachTarget(targets, this::grantLeaderboardCosmetics);
                String what = "all temporary leaderboard cosmetics";
                success(sender, "Gave " + what + " to " + describeTargets(targets, count) + ".");
                audit(sender, targets, what, count);
            }
            case REWARD -> {
                CrateCatalog.Reward reward = CrateCatalog.find(request.cosmeticId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "No crate reward called '" + request.cosmeticId() + "'."
                        ));
                if (reward.cosmetic()) {
                    CosmeticCatalog.Definition definition = CosmeticCatalog
                            .find(reward.cosmeticId())
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "That cosmetic reward is no longer registered."
                            ));
                    int count = forEachTarget(
                            targets, player -> grantCosmetic(player, definition)
                    );
                    success(sender, "Gave " + definition.displayName() + " to "
                            + describeTargets(targets, count) + ".");
                    audit(sender, targets, definition.displayName(), count);
                    return;
                }
                int count = forEachTarget(targets, player -> hand(player, crateItems.reward(reward)));
                success(sender, "Gave " + reward.displayName() + " to "
                        + describeTargets(targets, count) + ".");
                audit(sender, targets, reward.displayName(), count);
            }
            case AMETHYST_REWARDS -> {
                int count = forEachTarget(targets, this::grantAmethystRewards);
                String what = "one of every Limited Amethyst Crate reward";
                success(sender, "Gave " + what + " to " + describeTargets(targets, count) + ".");
                audit(sender, targets, what, count);
            }
        }
    }

    private void grantAmethystRewards(Player player) {
        for (CrateCatalog.Reward reward : CrateCatalog.amethystAdminRewards()) {
            if (reward.cosmetic()) {
                CosmeticCatalog.find(reward.cosmeticId())
                        .ifPresent(definition -> grantCosmetic(player, definition));
            } else {
                hand(player, crateItems.reward(reward));
            }
        }
    }

    private void grantCosmetic(Player player, CosmeticCatalog.Definition definition) {
        if (definition.leaderboardOnly()) {
            cosmetics.mintPreview(player.getUniqueId(), definition.id());
            player.sendMessage(prefix().append(Component.text(
                    definition.displayName() + " added as a temporary leaderboard preview. "
                            + "It cannot be traded or listed.",
                    NamedTextColor.GREEN
            )));
            return;
        }
        if (devBlog.isActive(player)) {
            cosmetics.mintPreview(player.getUniqueId(), definition.id());
            player.sendMessage(prefix().append(Component.text(
                    definition.displayName() + " added as a serial-free screenshot preview.",
                    NamedTextColor.GREEN
            )));
            return;
        }
        hand(player, cosmeticItems.token(definition, cosmetics.mint(
                player.getUniqueId(), definition.id(), UUID.randomUUID()
        )));
    }

    private void grantLeaderboardCosmetics(Player player) {
        cosmetics.clearPreviews(player.getUniqueId());
        CosmeticCatalog.leaderboardRewards().forEach(definition ->
                cosmetics.mintPreview(player.getUniqueId(), definition.id()));
        player.sendMessage(prefix().append(Component.text(
                "All 9 leaderboard cosmetics are available in /cosmetics until restart. "
                        + "They cannot be traded or listed.",
                NamedTextColor.GREEN
        )));
    }

    /** Anything that will not fit drops at the player's feet rather than vanishing. */
    private void hand(Player player, org.bukkit.inventory.ItemStack item) {
        player.getInventory().addItem(item).values().forEach(overflow ->
                player.getWorld().dropItemNaturally(player.getLocation(), overflow));
    }

    private void audit(CommandSender sender, String targets, String what, int count) {
        report(sender, "admin_give", "Gave " + what + " to " + describeTargets(targets, count))
                .detail("target", targets)
                .detail("granted", what)
                .detail("players", count)
                .record();
    }

    // ------------------------------------------------------------------
    // Rank holds
    // ------------------------------------------------------------------

    private void ranks(CommandSender sender, String[] args) {
        String action = args.length < 2 ? "list" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "hold" -> holdRank(sender, requirePlayerArgument(args, "hold"));
            case "release" -> releaseRank(sender, requirePlayerArgument(args, "release"));
            case "list" -> listHeldRanks(sender);
            default -> throw new IllegalArgumentException(
                    "Usage: /mgxadmin ranks <hold|release|list> [player]"
            );
        }
    }

    private void holdRank(CommandSender sender, OfflinePlayer target) {
        String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();
        if (!rankSync.hold(target.getUniqueId(), name)) {
            error(sender, name + " is already held.");
            return;
        }
        success(sender, "Discord rank sync will no longer touch " + name + ".");
        info(sender, "Set their group in LuckPerms; nothing here will take it away again.");
        report(sender, "rank_hold", "Held " + name + " out of Discord rank sync")
                .detail("target", name)
                .record();
    }

    private void releaseRank(CommandSender sender, OfflinePlayer target) {
        String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();
        if (!rankSync.release(target.getUniqueId())) {
            error(sender, name + " is not held.");
            return;
        }
        success(sender, "Discord rank sync manages " + name + " again.");
        info(sender, "Any group set by hand stays until their Discord roles next change.");
        report(sender, "rank_release", "Returned " + name + " to Discord rank sync")
                .detail("target", name)
                .record();
    }

    private void listHeldRanks(CommandSender sender) {
        Map<UUID, String> holds = rankSync.holds();
        if (holds.isEmpty()) {
            info(sender, "Nobody is held; Discord rank sync manages everyone.");
            return;
        }
        heading(sender, "Held out of Discord rank sync (" + holds.size() + ")");
        holds.forEach((playerId, name) -> sender.sendMessage(
                Component.text("  " + name, NamedTextColor.WHITE)
                        .append(Component.text("  " + playerId, NamedTextColor.DARK_GRAY))
        ));
    }

    private static boolean isEveryone(String token) {
        return EVERYONE.contains(token.toLowerCase(Locale.ROOT));
    }

    private int forEachTarget(String token, java.util.function.Consumer<Player> action) {
        if (isEveryone(token)) {
            int count = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                action.accept(player);
                count++;
            }
            if (count == 0) {
                throw new IllegalArgumentException("Nobody is online.");
            }
            return count;
        }
        OfflinePlayer target = requireNamedPlayer(token);
        if (!(target instanceof Player online) || !online.isOnline()) {
            throw new IllegalArgumentException(nameOf(target) + " is not online.");
        }
        action.accept(online);
        return 1;
    }

    private static String describeTargets(String token, int count) {
        if (isEveryone(token)) {
            return count == 1 ? "1 player" : count + " players";
        }
        return token;
    }

    private OfflinePlayer requireNamedPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        for (OfflinePlayer known : Bukkit.getOfflinePlayers()) {
            if (name.equalsIgnoreCase(known.getName())) {
                return known;
            }
        }
        throw new IllegalArgumentException("No player called '" + name + "' has joined this server.");
    }

    private static String nameOf(OfflinePlayer target) {
        return target.getName() == null ? target.getUniqueId().toString() : target.getName();
    }

    private OfflinePlayer requirePlayerArgument(String[] args, String action) {
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: /mgxadmin ranks " + action + " <player>");
        }
        String name = args[2];
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        // Only players the server already knows are accepted: resolving an unknown name
        // would mean a web lookup on the main thread, and a typo would silently hold a
        // UUID belonging to nobody here.
        for (OfflinePlayer known : Bukkit.getOfflinePlayers()) {
            if (name.equalsIgnoreCase(known.getName())) {
                return known;
            }
        }
        throw new IllegalArgumentException(
                "No player called '" + name + "' has joined this server."
        );
    }

    private void eco(CommandSender sender, String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: /mgxadmin eco <give|take|set|join> ..."
            );
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("join")) {
            joinToggle(sender, args, JoinGrantStore.Kind.MONEY);
            return;
        }
        if (args.length < 4) {
            throw new IllegalArgumentException(
                    "Usage: /mgxadmin eco <give|take|set> <player|everyone> <amount>"
            );
        }
        long amount = EconomyFormat.parseAmount(args[3]);
        switch (action) {
            case "give" -> {
                int count = forEachTarget(args[2], player -> economy.deposit(player.getUniqueId(), amount));
                success(sender, "Gave " + EconomyFormat.dollars(amount) + " to " + describeTargets(args[2], count) + ".");
            }
            case "take" -> {
                if (isEveryone(args[2])) {
                    throw new IllegalArgumentException("Take one player at a time.");
                }
                OfflinePlayer target = requireNamedPlayer(args[2]);
                if (!economy.tryWithdraw(target.getUniqueId(), amount)) {
                    throw new IllegalArgumentException(
                            nameOf(target) + " only has "
                                    + EconomyFormat.dollars(economy.balance(target.getUniqueId())) + "."
                    );
                }
                success(sender, "Took " + EconomyFormat.dollars(amount) + " from " + nameOf(target) + ".");
            }
            case "set" -> {
                if (isEveryone(args[2])) {
                    throw new IllegalArgumentException("Set one player at a time.");
                }
                OfflinePlayer target = requireNamedPlayer(args[2]);
                economy.set(target.getUniqueId(), amount);
                success(sender, "Set " + nameOf(target) + " to " + EconomyFormat.dollars(amount) + ".");
            }
            default -> throw new IllegalArgumentException(
                    "Usage: /mgxadmin eco <give|take|set|join> ..."
            );
        }
    }

    private void bounty(CommandSender sender, String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: /mgxadmin bounty <set|join> ...");
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("join")) {
            joinToggle(sender, args, JoinGrantStore.Kind.BOUNTY);
            return;
        }
        if (!action.equals("set") || args.length < 4) {
            throw new IllegalArgumentException(
                    "Usage: /mgxadmin bounty set <player|everyone> <amount>"
            );
        }
        long amount = EconomyFormat.parseAmount(args[3]);
        int count = forEachTarget(args[2], player -> bounties.add(player.getUniqueId(), amount, false));
        success(sender, "Placed a " + EconomyFormat.dollars(amount) + " bounty on "
                + describeTargets(args[2], count) + ".");
    }

    private void joinToggle(CommandSender sender, String[] args, JoinGrantStore.Kind kind) {
        String noun = kind == JoinGrantStore.Kind.MONEY ? "join money" : "join bounty";
        if (args.length < 3 || args[2].equalsIgnoreCase("status")) {
            if (!joinGrants.enabled(kind)) {
                info(sender, noun + " is off.");
                return;
            }
            info(sender, noun + " is on: " + EconomyFormat.dollars(joinGrants.amount(kind))
                    + " until you turn it off.");
            return;
        }
        String mode = args[2].toLowerCase(Locale.ROOT);
        if (mode.equals("off")) {
            joinGrants.disable(kind);
            success(sender, "Turned " + noun + " off.");
            return;
        }
        if (!mode.equals("on") || args.length < 4) {
            throw new IllegalArgumentException(
                    "Usage: /mgxadmin " + args[0] + " join <on <amount>|off>"
            );
        }
        long amount = EconomyFormat.parseAmount(args[3]);
        joinGrants.enable(kind, amount);
        success(sender, "Everyone who joins now gets " + EconomyFormat.dollars(amount)
                + " " + (kind == JoinGrantStore.Kind.MONEY ? "until you turn it off."
                : "as a bounty, once each, until you turn it off."));
    }

    // ------------------------------------------------------------------
    // Data reset
    // ------------------------------------------------------------------

    private void testVerify(CommandSender sender, String[] args) {
        if (!plugin.isLocalTestServer()) {
            throw new IllegalArgumentException("This command exists only on the local test server.");
        }
        if (!(sender instanceof Player player)) {
            throw new IllegalArgumentException("Run this command as the account you want to test.");
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("reset")) {
            throw new IllegalArgumentException("Usage: /mgxadmin testverify reset");
        }
        ServerEvent.of(
                "test_unverify",
                ServerEvent.CATEGORY_ADMIN,
                player.getUniqueId(),
                player.getName(),
                plugin::recordServerEvent
        ).summary(player.getName() + " reset their verification on the local test server.")
                .record();
        success(sender, "Verification reset requested. You will disconnect, then reconnect into the lobby.");
    }

    /** Runs the real reveal presentation without changing inventories or crate state. */
    private void testCrateReveal(CommandSender sender, String[] args) {
        if (!plugin.isLocalTestServer()) {
            throw new IllegalArgumentException(
                    "Crate reveal tests are available only on the local test server."
            );
        }
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: /mgxadmin testcrate <legendary|mythic|exotic|secret> [player]"
            );
        }
        CrateCatalog.RevealTier tier = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "legendary" -> CrateCatalog.RevealTier.LEGENDARY;
            case "mythic" -> CrateCatalog.RevealTier.MYTHIC;
            case "exotic" -> CrateCatalog.RevealTier.SECRET;
            case "secret", "genuine", "genuine-secret", "genuine_secret", "genuinesecret" ->
                    CrateCatalog.RevealTier.GENUINE_SECRET;
            default -> throw new IllegalArgumentException(
                    "Use legendary, mythic, exotic, or secret."
            );
        };
        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                throw new IllegalArgumentException("That player is not online.");
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            throw new IllegalArgumentException(
                    "Console must name an online player: /mgxadmin testcrate <rarity> <player>"
            );
        }
        crates.testReveal(target, tier);
        success(sender, "Ran the complete " + tier.name().toLowerCase(Locale.ROOT)
                .replace('_', ' ') + " crate reveal for " + target.getName()
                + ". No reward was granted.");
        report(sender, "crate_reveal_test", "Tested a crate reward reveal")
                .detail("player", target.getName())
                .detail("tier", tier.name().toLowerCase(Locale.ROOT))
                .detail("reward_granted", "false")
                .record();
    }

    private void testAirdrop(CommandSender sender, String[] args) {
        if (!plugin.isLocalTestServer()) {
            throw new IllegalArgumentException(
                    "Airdrop tests are available only on the local test server."
            );
        }
        AirdropTestPlan.Request request = AirdropTestPlan.parse(args);
        switch (request.action()) {
            case HELP -> sendAirdropTestHelp(sender);
            case SPAWN -> spawnTestAirdrop(sender, request);
            case STATUS -> showAirdropTestStatus(sender, request.targetName());
            case EXPIRE -> {
                int expired = airdrops.expireTest();
                if (expired == 0) {
                    throw new IllegalArgumentException("There is no active Airdrop to expire.");
                }
                success(sender, "Expired " + expired + " Airdrop(s) and restored their sites.");
                report(sender, "airdrop_test_expire", "Expired a local test Airdrop").record();
            }
            case REMOVE -> {
                int removed = airdrops.removeTest();
                if (removed == 0) {
                    throw new IllegalArgumentException("There is no active Airdrop to remove.");
                }
                success(sender, "Removed " + removed + " Airdrop(s) and restored their sites.");
                report(sender, "airdrop_test_remove", "Removed a local test Airdrop").record();
            }
            case PROGRESS_SET -> setAirdropTestProgress(sender, request, false);
            case PROGRESS_RESET -> setAirdropTestProgress(sender, request, true);
        }
    }

    /**
     * Calls a real Airdrop in near the operator, on any server.
     *
     * <p>Separate from {@code testairdrop}, which is a local-only harness with forced
     * cosmetics and its own cleanup shortcuts. This is the live one: staff say when,
     * the whole server is told where, and several may stand at once up to
     * {@code airdrop.maximum-active}.
     */
    private void callAirdrop(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            throw new IllegalArgumentException(
                    "Run /mgxadmin airdrop in game so the Airdrop can land near you."
            );
        }
        AirdropCatalog.Rarity rarity = args.length > 1 ? airdropRarity(args[1]) : null;
        AirdropService.Snapshot drop = airdrops.spawnNear(player, rarity, List.of());
        success(sender, "Called in " + drop.describe() + ".");
        info(sender, airdrops.activeCount() + " of " + airdrops.maximumActive()
                + " Airdrops standing.");
        report(sender, "airdrop_spawn", sender.getName() + " called in an Amethyst Airdrop")
                .detail("rarity", drop.rarity().displayName())
                .detail("world", drop.world())
                .detail("coordinates", "X " + drop.x() + " Y " + drop.y() + " Z " + drop.z())
                .detail("standing", airdrops.activeCount())
                .record();
    }

    private static AirdropCatalog.Rarity airdropRarity(String name) {
        for (AirdropCatalog.Rarity rarity : AirdropCatalog.Rarity.values()) {
            if (rarity.name().equalsIgnoreCase(name)) {
                return rarity;
            }
        }
        throw new IllegalArgumentException(
                "Choose a rarity: common, rare, legendary or mythic — or none for a random one."
        );
    }

    private void spawnTestAirdrop(CommandSender sender, AirdropTestPlan.Request request) {
        if (!(sender instanceof Player player)) {
            throw new IllegalArgumentException(
                    "Run the spawn test in game so the Airdrop can appear near you."
            );
        }
        AirdropService.Snapshot drop = airdrops.spawnTest(
                player, request.rarity(), request.cosmeticIds()
        );
        success(sender, "Spawned " + drop.describe() + ".");
        info(sender, "Opening it tests the Airdrop leaderboard. Emptying it tests full cleanup.");
        if (!request.cosmeticIds().isEmpty()) {
            info(sender, request.cosmeticIds().size() == 1
                    ? "The requested cosmetic is guaranteed inside; claim it into /wardrobe."
                    : "One randomly selected Airdrop cosmetic is guaranteed inside. "
                            + "Use cosmetic <type> to test a specific one.");
        }
        info(sender, "Use /mgxadmin testairdrop expire to test the timeout path immediately.");
        if (request.cosmeticIds().size() == AirdropCatalog.cosmeticIds().size()) {
            info(sender, "Use /mgxadmin testairdrop progress 12 8, then /leaderboard, "
                    + "to test both Amethyst boards.");
        }
        report(sender, "airdrop_test_spawn", "Spawned a local test Airdrop")
                .detail("rarity", request.rarity().displayName())
                .detail("world", drop.world())
                .detail("coordinates", "X " + drop.x() + " Y " + drop.y() + " Z " + drop.z())
                .detail("cosmetic_candidates", request.cosmeticIds().size())
                .record();
    }

    private void showAirdropTestStatus(CommandSender sender, String targetName) {
        List<AirdropService.Snapshot> standing = airdrops.snapshots();
        if (standing.isEmpty()) {
            info(sender, "No Airdrop is active.");
        } else {
            for (AirdropService.Snapshot drop : standing) {
                info(sender, drop.describe());
            }
        }
        OfflinePlayer target = optionalTestTarget(sender, targetName);
        if (target == null) {
            return;
        }
        AmethystProgressStore.Counts counts = amethystProgress.counts(target.getUniqueId());
        info(sender, nameOf(target) + ": " + counts.cratesOpened()
                + " Amethyst Crates, " + counts.airdropsOpened() + " Amethyst Airdrops.");
    }

    private void setAirdropTestProgress(
            CommandSender sender,
            AirdropTestPlan.Request request,
            boolean reset
    ) {
        OfflinePlayer target = requireTestTarget(sender, request.targetName());
        long crates = reset ? 0L : request.cratesOpened();
        long drops = reset ? 0L : request.airdropsOpened();
        try {
            amethystProgress.set(target.getUniqueId(), crates, drops);
        } catch (java.io.UncheckedIOException exception) {
            throw new IllegalArgumentException("The leaderboard test fixture could not be saved.");
        }
        success(sender, (reset ? "Reset" : "Set") + " Amethyst Event test progress for "
                + nameOf(target) + ": " + crates + " crates, " + drops + " Airdrops.");
        info(sender, "Open /leaderboard to inspect both Amethyst Event boards.");
        report(sender, "airdrop_test_progress", "Changed local Amethyst leaderboard test progress")
                .detail("player", nameOf(target))
                .detail("crates", crates)
                .detail("airdrops", drops)
                .record();
    }

    private OfflinePlayer optionalTestTarget(CommandSender sender, String targetName) {
        if (targetName != null) {
            return requireNamedPlayer(targetName);
        }
        return sender instanceof Player player ? player : null;
    }

    private OfflinePlayer requireTestTarget(CommandSender sender, String targetName) {
        OfflinePlayer target = optionalTestTarget(sender, targetName);
        if (target == null) {
            throw new IllegalArgumentException("Console must name a player for this test.");
        }
        return target;
    }

    private void sendAirdropTestHelp(CommandSender sender) {
        heading(sender, "Amethyst Airdrop test suite");
        sender.sendMessage(Component.text("  /mgxadmin testairdrop all", ORANGE)
                .append(Component.text("  Mythic drop with one guaranteed cosmetic", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin testairdrop <rarity>", ORANGE)
                .append(Component.text("  real randomized loot at a nearby safe site", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin testairdrop cosmetic <type>", ORANGE)
                .append(Component.text("  force kill, trail, aura, or all", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin testairdrop progress <crates> <airdrops>", ORANGE)
                .append(Component.text("  set both leaderboard fixtures", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin testairdrop progress reset", ORANGE)
                .append(Component.text("  remove your leaderboard fixture", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin testairdrop status|expire|remove", ORANGE)
                .append(Component.text("  inspect or clean up the active test", NamedTextColor.GRAY)));
    }

    private void testAmethystBlock(CommandSender sender, String[] args) {
        if (!plugin.isLocalTestServer()) {
            throw new IllegalArgumentException(
                    "Huge Amethyst Block tests are available only on the local test server."
            );
        }
        String action = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "spawn" -> {
                if (!(sender instanceof Player player)) {
                    throw new IllegalArgumentException("Run this spawn test in game.");
                }
                AmethystBlockEventService.Snapshot block = amethystBlocks.spawnTest(player);
                success(sender, "Spawned " + block.describe() + ".");
                info(sender, "Mine it normally, or use damage/finish/expire to test each path.");
            }
            case "status" -> {
                AmethystBlockEventService.Snapshot block = amethystBlocks.snapshot();
                info(sender, block == null ? "No Huge Amethyst Block is active." : block.describe());
            }
            case "damage" -> {
                double damage = args.length < 3 ? 1_100d : Double.parseDouble(args[2]);
                if (!amethystBlocks.damageTest(damage)) {
                    throw new IllegalArgumentException("There is no active block to damage.");
                }
                success(sender, "Applied " + Math.round(damage) + " test damage.");
            }
            case "finish" -> {
                if (!amethystBlocks.damageTest(AmethystBlockRewards.MAX_HEALTH)) {
                    throw new IllegalArgumentException("There is no active block to finish.");
                }
                success(sender, "Finished the active block and delivered its rewards.");
            }
            case "expire" -> {
                if (!amethystBlocks.expireTest()) {
                    throw new IllegalArgumentException("There is no active block to expire.");
                }
                success(sender, "Expired the active block.");
            }
            case "remove" -> {
                if (!amethystBlocks.removeTest()) {
                    throw new IllegalArgumentException("There is no active block to remove.");
                }
                success(sender, "Removed the active block without rewards.");
            }
            default -> {
                heading(sender, "Huge Amethyst Block test suite");
                info(sender, "/mgxadmin testamethystblock spawn|status|damage [hp]|finish|expire|remove");
            }
        }
    }

    private void reset(CommandSender sender, String[] args) {
        List<String> rest = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
        boolean confirmed = rest.removeIf(argument -> argument.equalsIgnoreCase("confirm"));
        Set<ResetScope> scopes = ResetScope.parse(rest);
        if (scopes.isEmpty()) {
            sendResetHelp(sender);
            return;
        }
        if (!confirmed) {
            heading(sender, "This permanently deletes:");
            for (ResetScope scope : scopes) {
                sender.sendMessage(Component.text("  " + scope.description(), NamedTextColor.WHITE));
            }
            info(sender, "Kept: the world and everything built in it, operators, bans, "
                    + "and any rank holds you have set.");
            if (scopes.stream().anyMatch(ResetScope::revokesAccess)) {
                error(sender, "Everyone loses access and has to apply again through Discord.");
            }
            error(sender, "Add 'confirm' to run it: /mgxadmin reset "
                    + String.join(" ", rest) + " confirm");
            return;
        }
        ServerDataResetService.Summary summary = resets.reset(scopes);
        heading(sender, "Reset complete");
        for (ResetScope scope : scopes) {
            sender.sendMessage(Component.text(
                    "  " + scope.key() + ": " + summary.describe(scope) + " cleared",
                    NamedTextColor.WHITE
            ));
        }
        for (String problem : summary.problems()) {
            error(sender, problem);
        }
        if (scopes.contains(ResetScope.INVENTORIES)) {
            info(sender, "Players offline now respawn fresh on their next join.");
        }
        if (scopes.contains(ResetScope.ACCESS)) {
            info(sender, "Run Discord's /mgxadmin wipe too — that side keeps its own "
                    + "applications and linked accounts, which this cannot reach.");
        }
        report(sender, "data_reset", "Reset " + scopes.size() + " data scope(s)")
                .detail("scopes", scopes.stream().map(ResetScope::key)
                        .collect(java.util.stream.Collectors.joining(", ")))
                .detail("problems", String.valueOf(summary.problems().size()))
                .record();
        plugin.getLogger().warning(sender.getName() + " reset server data: "
                + scopes.stream().map(ResetScope::key)
                .collect(java.util.stream.Collectors.joining(", ")));
    }

    private void hologram(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            throw new IllegalArgumentException("Stand in the world to place a hologram.");
        }
        if (args.length < 2) {
            throw new IllegalArgumentException(HologramService.Board.usage());
        }
        try {
            if (args[1].equalsIgnoreCase("remove")) {
                holograms.removeNearby(player);
                success(sender, "Removed the nearby hologram.");
                return;
            }
            holograms.place(player, HologramService.Board.fromKey(args[1]));
            success(sender, "Placed that hologram here.");
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("The hologram could not be saved. Try again.");
        }
    }

    private void clanBattle(CommandSender sender, String[] args) {
        String action = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> info(sender, clanBattles.status());
            case "start" -> {
                ClanBattleStore.Kind kind = ClanBattleStore.Kind.from(
                        args.length >= 3 ? args[2] : "crates"
                ).orElseThrow(() -> new IllegalArgumentException(
                        "Use /mgxadmin clanbattle start crates."
                ));
                ClanBattleStore.ActiveView active = clanBattles.startBattle(
                        kind, clanBattleDeadline(args.length >= 4 ? args[3] : null)
                );
                success(sender, "Started " + active.kind().displayName() + ", ending in "
                        + ClanBattleCountdown.remaining(
                                active.endsAt() - System.currentTimeMillis()) + ".");
                report(sender, "clan_battle_start", "Started " + active.kind().displayName())
                        .detail("battle_id", active.id().toString())
                        .detail("kind", active.kind().id())
                        .detail("ends_at", active.endsAt())
                        .record();
            }
            case "end" -> {
                ClanBattleStore.CompletedView completed = clanBattles.endBattle();
                success(sender, "Ended " + completed.kind().displayName() + " and awarded "
                        + completed.winners().size() + " winning clan placement(s).");
                report(sender, "clan_battle_end", "Ended " + completed.kind().displayName())
                        .detail("battle_id", completed.id().toString())
                        .detail("winning_placements", completed.winners().size())
                        .record();
            }
            case "cancel" -> {
                if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                    throw new IllegalArgumentException(
                            "Use /mgxadmin clanbattle cancel confirm. This awards nothing."
                    );
                }
                clanBattles.cancelBattle();
                success(sender, "Cancelled the active clan battle without rewards.");
                report(sender, "clan_battle_cancel", "Cancelled the active clan battle").record();
            }
            default -> throw new IllegalArgumentException(
                    "Use /mgxadmin clanbattle <start crates [7d]|status|end|cancel confirm>."
            );
        }
    }

    /**
     * With no length given a battle runs to the Limited Amethyst Crate close, so the
     * two events finish together. Once that date is behind us the crate can no longer
     * time anything, and a later battle has to say how long it runs.
     */
    private static long clanBattleDeadline(String requested) {
        long now = System.currentTimeMillis();
        if (requested != null) {
            return Math.addExact(now, ClanBattleCountdown.parse(requested));
        }
        long crateCloses = CrateKind.AMETHYST.closesAt();
        if (crateCloses > now) {
            return crateCloses;
        }
        throw new IllegalArgumentException(
                "The Amethyst Crate has already closed, so give a length: "
                        + "/mgxadmin clanbattle start crates 7d"
        );
    }

    private void sendResetHelp(CommandSender sender) {
        heading(sender, "Reset scopes");
        for (ResetScope scope : ResetScope.values()) {
            sender.sendMessage(Component.text("  " + scope.key(), ORANGE)
                    .append(Component.text("  " + scope.description(), NamedTextColor.GRAY)));
        }
        sender.sendMessage(Component.text("  all", ORANGE)
                .append(Component.text("  every scope above", NamedTextColor.GRAY)));
        info(sender, "Usage: /mgxadmin reset <scope...|all> confirm");
        info(sender, "The world, and everything built in it, is never touched.");
    }

    // ------------------------------------------------------------------

    private void sendHelp(CommandSender sender) {
        heading(sender, "Administration");
        sender.sendMessage(Component.text("  /mgxadmin startserver", ORANGE)
                .append(Component.text("  countdown, strip barriers, hold PvP off for 5 hours", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin teststart", ORANGE)
                .append(Component.text("  same countdown; barriers come back after 1 minute", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin pvp <on|off|status>", ORANGE)
                .append(Component.text("  pin PvP either way, overriding the launch hold",
                        NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin ranks hold <player>", ORANGE)
                .append(Component.text("  stop Discord rank sync touching them", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin ranks release <player>", ORANGE)
                .append(Component.text("  hand them back to Discord rank sync", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin ranks list", ORANGE)
                .append(Component.text("  everyone currently held", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin give <player|everyone> money <amount>", ORANGE)
                .append(Component.text("  add money", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin give <player|everyone> key [amount]", ORANGE)
                .append(Component.text("  hand over crate keys", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin give <player|everyone> shard [amount]", ORANGE)
                .append(Component.text("  hand over rare Shard Crate currency", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin give <player|everyone> cosmetic <id>", ORANGE)
                .append(Component.text("  mint a cosmetic straight to them", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin give <player|everyone> reward <id>", ORANGE)
                .append(Component.text("  grant any permanent or limited crate reward",
                        NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin give <player|everyone> amethyst", ORANGE)
                .append(Component.text("  grant one of every Limited Amethyst Crate reward",
                        NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin eco join on|off [amount]", ORANGE)
                .append(Component.text("  pay everyone who joins", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text(
                        "  /mgxadmin eco <give|take|set> <player|everyone> <amount>", ORANGE
                ).append(Component.text("  manage player balances directly", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin bounty set <player|everyone> <amount>", ORANGE)
                .append(Component.text("  place a bounty without paying", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin bounty join on|off [amount]", ORANGE)
                .append(Component.text("  bounty everyone who joins, once each", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin hologram <board|remove>", ORANGE)
                .append(Component.text("  place or remove a spawn leaderboard", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin event list", ORANGE)
                .append(Component.text(
                        "  every multiplier, Airdrop, Huge Block and admin-event control",
                        NamedTextColor.GRAY
                )));
        sender.sendMessage(Component.text(
                        "  /mgxadmin clanbattle <start crates [7d]|status|end|cancel confirm>",
                        ORANGE
                ).append(Component.text(
                        "  control the current clan event and its rewards", NamedTextColor.GRAY
                )));
        sender.sendMessage(Component.text("  /mgxadmin reset", ORANGE)
                .append(Component.text("  clear progress, keeping the world", NamedTextColor.GRAY)));
        if (plugin.isLocalTestServer()) {
            sender.sendMessage(Component.text("  /mgxadmin testverify reset", ORANGE)
                    .append(Component.text("  unverify yourself for another test", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text(
                            "  /mgxadmin testcrate <rarity> [player]", ORANGE
                    ).append(Component.text(
                            "  run the complete crate reveal without granting loot",
                            NamedTextColor.GRAY
                    )));
            sender.sendMessage(Component.text("  /mgxadmin testairdrop all", ORANGE)
                    .append(Component.text(
                            "  test Airdrops, cosmetics, cleanup, and event boards",
                            NamedTextColor.GRAY
                    )));
            sender.sendMessage(Component.text("  /mgxadmin testamethystblock", ORANGE)
                    .append(Component.text(
                            "  spawn and exercise a Huge Amethyst Block",
                            NamedTextColor.GRAY
                    )));
        }
        sender.sendMessage(Component.text("  /mgxadmin serials reset <cosmetic> confirm", ORANGE)
                .append(Component.text("  renumber one cosmetic without deleting it", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin cosmetics delete <player> confirm", ORANGE)
                .append(Component.text("  delete every cosmetic owned by one player",
                        NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin update", ORANGE)
                .append(Component.text("  tell everyone the next login to check the blog",
                        NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin devblog", ORANGE)
                .append(Component.text("  screenshot mode: stash your gear, clear the screen",
                        NamedTextColor.GRAY)));
        sender.sendMessage(Component.text(
                        "  /mgxadmin variables get|set|reset <key> [value]", ORANGE
                ).append(Component.text(
                        "  OWNER-only live controls; no restart required", NamedTextColor.GRAY
                )));
        sender.sendMessage(Component.text("  /mgxadmin variables list [filter]", ORANGE)
                .append(Component.text("  browse every controllable live value", NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args
    ) {
        if (!plugin.mayAdminister(sender)) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> available = plugin.isLocalTestServer()
                    ? SUBCOMMANDS
                    : SUBCOMMANDS.stream().filter(value -> !value.startsWith("test")).toList();
            return partial(args[0], available);
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("pvp")) {
            return args.length == 2 ? partial(args[1], PVP_ACTIONS) : List.of();
        }
        if (action.equals("testverify")) {
            return args.length == 2 ? partial(args[1], List.of("reset")) : List.of();
        }
        if (action.equals("testcrate") || action.equals("cratetest")
                || action.equals("testreveal")) {
            if (args.length == 2) {
                return partial(args[1], CRATE_REVEAL_TIERS);
            }
            if (args.length == 3) {
                return partial(args[2], Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .toList());
            }
            return List.of();
        }
        if (action.equals("airdrop") || action.equals("calldrop")) {
            return args.length == 2
                    ? partial(args[1], AIRDROP_RARITIES)
                    : List.of();
        }
        if (action.equals("testairdrop") || action.equals("airdroptest")
                || action.equals("testdrop")) {
            if (args.length == 2) {
                return partial(args[1], AirdropTestPlan.ACTIONS);
            }
            if (args.length == 3 && (args[1].equalsIgnoreCase("cosmetic")
                    || args[1].equalsIgnoreCase("cosmetics"))) {
                return partial(args[2], AirdropTestPlan.COSMETICS);
            }
            if (args.length == 3 && (args[1].equalsIgnoreCase("progress")
                    || args[1].toLowerCase(Locale.ROOT).startsWith("leaderboard"))) {
                return partial(args[2], List.of("reset", "10"));
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("status")) {
                return partial(args[2], Bukkit.getOfflinePlayers().length == 0
                        ? List.of()
                        : Arrays.stream(Bukkit.getOfflinePlayers())
                                .map(AdminCommandService::nameOf)
                                .toList());
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("progress")
                    && args[2].equalsIgnoreCase("reset")) {
                return partial(args[3], Arrays.stream(Bukkit.getOfflinePlayers())
                        .map(AdminCommandService::nameOf).toList());
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("progress")) {
                return partial(args[3], List.of("8"));
            }
            if (args.length == 5 && args[1].equalsIgnoreCase("progress")) {
                return partial(args[4], Arrays.stream(Bukkit.getOfflinePlayers())
                        .map(AdminCommandService::nameOf).toList());
            }
            return List.of();
        }
        if (action.equals("devblog") || action.equals("screenshot")) {
            if (args.length == 2) {
                return partial(args[1], DEVBLOG_ACTIONS);
            }
            if (args.length == 3) {
                return switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "time" -> partial(args[2], DEVBLOG_TIMES);
                    case "weather" -> partial(args[2], DEVBLOG_WEATHER);
                    case "on", "start" -> partial(args[2], List.of("keeparmour"));
                    default -> List.of();
                };
            }
            return List.of();
        }
        if (action.equals("abuse")) {
            if (args.length == 2) {
                List<String> options = new ArrayList<>(
                        ChaosCatalog.menu().stream().map(ChaosCatalog::id).toList());
                options.add("controls");
                return partial(args[1], options);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("alfredo")) {
                return partial(args[2], List.of(
                        "test", "hp", "keys", "diamonds", "add", "burst", "kill", "status"));
            }
            return List.of();
        }
        if (action.equals("event") || action.equals("multiplier")) {
            if (action.equals("event")) {
                if (args.length == 2) {
                    return partial(args[1], List.of(
                            "list", "multiplier", "airdrop", "amethyst-block", "schedule", "admin"
                    ));
                }
                String category = args[1].toLowerCase(Locale.ROOT);
                if (category.equals("multiplier")) {
                    if (args.length == 3) {
                        return partial(args[2], Arrays.stream(ServerEventType.values())
                                .map(ServerEventType::id).toList());
                    }
                    return args.length == 4
                            ? partial(args[3], List.of("on", "off")) : List.of();
                }
                if (category.equals("airdrop")) {
                    if (args.length == 3) {
                        return partial(args[2], List.of(
                                "start", "status", "end", "expire", "distance"
                        ));
                    }
                    if (args.length == 4 && (args[2].equalsIgnoreCase("start")
                            || args[2].equalsIgnoreCase("distance"))) {
                        return partial(args[3], AIRDROP_RARITIES);
                    }
                    return List.of();
                }
                if (category.equals("amethyst-block") || category.equals("block")) {
                    return args.length == 3 ? partial(args[2], List.of(
                            "start", "status", "damage", "finish", "end", "expire"
                    )) : List.of();
                }
                if (category.equals("schedule")) {
                    return args.length == 3
                            ? partial(args[2], List.of("status", "set", "reset")) : List.of();
                }
                if (category.equals("admin")) {
                    if (args.length == 3) {
                        List<String> options = new ArrayList<>(
                                ChaosCatalog.menu().stream().map(ChaosCatalog::id).toList());
                        options.add("controls");
                        return partial(args[2], options);
                    }
                    if (args.length == 4 && args[2].equalsIgnoreCase("alfredo")) {
                        return partial(args[3], List.of(
                                "test", "hp", "keys", "diamonds", "add", "burst", "kill", "status"));
                    }
                    return List.of();
                }
            }
            if (args.length == 2) {
                List<String> options = new ArrayList<>(
                        Arrays.stream(ServerEventType.values()).map(ServerEventType::id).toList()
                );
                options.add("list");
                return partial(args[1], options);
            }
            if (args.length == 3) {
                return partial(args[2], List.of("on", "off"));
            }
            return List.of();
        }
        if (action.equals("serials")) {
            if (args.length == 2) {
                return partial(args[1], List.of("reset"));
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("reset")) {
                return partial(args[2], CosmeticCatalog.visualEntries().stream()
                        .map(CosmeticCatalog.Definition::id).toList());
            }
            if (args.length == 4) {
                return partial(args[3], List.of("confirm"));
            }
            return List.of();
        }
        if (action.equals("variables") || action.equals("variable") || action.equals("vars")) {
            if (args.length == 2) {
                return partial(args[1], List.of("list", "get", "set", "reset"));
            }
            if (args.length == 3) {
                return partial(args[2], variables.snapshot().getAsJsonArray("variables").asList()
                        .stream().map(JsonElement::getAsJsonObject)
                        .map(row -> row.get("key").getAsString()).toList());
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("set")) {
                return partial(args[3], List.of("true", "false"));
            }
            return List.of();
        }
        if (action.equals("cosmetics")) {
            if (args.length == 2) {
                return partial(args[1], List.of("delete"));
            }
            if (args.length == 3) {
                return partial(args[2], Bukkit.getOfflinePlayers().length == 0
                        ? List.of()
                        : Arrays.stream(Bukkit.getOfflinePlayers())
                                .map(AdminCommandService::nameOf)
                                .toList());
            }
            if (args.length == 4) {
                return partial(args[3], List.of("confirm"));
            }
            return List.of();
        }
        if (action.equals("give")) {
            if (args.length == 2) {
                List<String> names = new ArrayList<>(EVERYONE);
                Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
                return partial(args[1], names);
            }
            if (args.length == 3) {
                return partial(args[2], AdminGive.TYPES);
            }
            if (args.length == 4 && args[2].toLowerCase(Locale.ROOT).startsWith("cosmetic")) {
                return partial(args[3], AdminGive.cosmeticIds());
            }
            if (args.length == 4 && args[2].equalsIgnoreCase("reward")) {
                return partial(args[3], CrateCatalog.everyReward().stream()
                        .map(CrateCatalog.Reward::id).toList());
            }
            return List.of();
        }
        if (action.equals("ranks")) {
            if (args.length == 2) {
                return partial(args[1], RANK_ACTIONS);
            }
            if (args.length == 3 && !args[1].equalsIgnoreCase("list")) {
                return partial(args[2], Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName).toList());
            }
            return List.of();
        }
        if (action.equals("eco") || action.equals("bounty")) {
            if (args.length == 2) {
                return partial(args[1], action.equals("eco") ? ECO_ACTIONS : BOUNTY_ACTIONS);
            }
            if (args.length == 3) {
                if (args[1].equalsIgnoreCase("join")) {
                    return partial(args[2], JOIN_ACTIONS);
                }
                List<String> names = new ArrayList<>(EVERYONE);
                Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
                return partial(args[2], names);
            }
            return List.of();
        }
        if (action.equals("hologram") || action.equals("holograms") || action.equals("lb")) {
            if (args.length == 2) {
                return partial(args[1], HOLOGRAM_BOARDS);
            }
            return List.of();
        }
        if (action.equals("clanbattle") || action.equals("clan-battle")) {
            if (args.length == 2) {
                return partial(args[1], CLAN_BATTLE_ACTIONS);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("start")) {
                return partial(args[2], Arrays.stream(ClanBattleStore.Kind.values())
                        .map(ClanBattleStore.Kind::id).toList());
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("cancel")) {
                return partial(args[2], List.of("confirm"));
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("start")) {
                return partial(args[3], List.of("7d", "3d", "24h", "12h"));
            }
            return List.of();
        }
        if (action.equals("reset")) {
            List<String> options = new ArrayList<>(ResetScope.keys());
            options.add("all");
            options.add("confirm");
            return partial(args[args.length - 1], options);
        }
        return List.of();
    }

    private static List<String> partial(String token, List<String> candidates) {
        List<String> matches = new ArrayList<>();
        StringUtil.copyPartialMatches(token, candidates, matches);
        return matches;
    }

    private ServerEvent.Builder report(CommandSender sender, String event, String summary) {
        UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        return ServerEvent.of(
                event, ServerEvent.CATEGORY_ADMIN, actor, sender.getName(),
                plugin::recordServerEvent
        ).summary(summary);
    }

    private static void heading(CommandSender sender, String message) {
        sender.sendMessage(prefix().append(
                Component.text(message, NamedTextColor.WHITE, TextDecoration.BOLD)));
    }

    private static void success(CommandSender sender, String message) {
        sender.sendMessage(prefix().append(Component.text(message, NamedTextColor.GREEN)));
    }

    private static void info(CommandSender sender, String message) {
        sender.sendMessage(prefix().append(Component.text(message, NamedTextColor.GRAY)));
    }

    private static void error(CommandSender sender, String message) {
        sender.sendMessage(prefix().append(Component.text(message, NamedTextColor.RED)));
    }

    private static Component prefix() {
        return Component.text("SERVER » ", ORANGE, TextDecoration.BOLD);
    }
}
