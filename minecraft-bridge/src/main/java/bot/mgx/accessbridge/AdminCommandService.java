package bot.mgx.accessbridge;

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
    private static final List<String> SUBCOMMANDS = List.of(
            "startserver", "teststart", "ranks", "eco", "bounty", "hologram", "reset", "help"
    );
    private static final List<String> RANK_ACTIONS = List.of("hold", "release", "list");
    private static final List<String> ECO_ACTIONS = List.of("give", "take", "set", "join");
    private static final List<String> BOUNTY_ACTIONS = List.of("set", "join");
    private static final List<String> JOIN_ACTIONS = List.of("on", "off");
    private static final List<String> EVERYONE = List.of("everyone", "*", "all");
    private static final List<String> HOLOGRAM_BOARDS = List.of(
            "wealth", "kills", "clans-wealth", "clans-kills", "remove"
    );

    private final MGXAccessBridge plugin;
    private final RankSyncStore rankSync;
    private final EconomyStore economy;
    private final BountyStore bounties;
    private final JoinGrantStore joinGrants;
    private final HologramService holograms;
    private final ServerDataResetService resets;

    AdminCommandService(
            MGXAccessBridge plugin,
            RankSyncStore rankSync,
            EconomyStore economy,
            BountyStore bounties,
            JoinGrantStore joinGrants,
            HologramService holograms,
            ServerDataResetService resets
    ) {
        this.plugin = plugin;
        this.rankSync = rankSync;
        this.economy = economy;
        this.bounties = bounties;
        this.joinGrants = joinGrants;
        this.holograms = holograms;
        this.resets = resets;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        args = CommandArgs.withoutEchoedSender(sender.getName(), args);
        if (!plugin.mayAdminister(sender)) {
            error(sender, "You do not have permission to do that.");
            return true;
        }
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
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
                case "ranks" -> ranks(sender, args);
                case "eco" -> eco(sender, args);
                case "bounty" -> bounty(sender, args);
                case "hologram", "holograms", "lb" -> hologram(sender, args);
                case "reset" -> reset(sender, args);
                default -> sendHelp(sender);
            }
        } catch (IllegalArgumentException exception) {
            error(sender, exception.getMessage());
        }
        return true;
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
            info(sender, "Run /mcadmin wipe in Discord too — that side keeps its own "
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
        sender.sendMessage(Component.text("  /mgxadmin ranks hold <player>", ORANGE)
                .append(Component.text("  stop Discord rank sync touching them", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin ranks release <player>", ORANGE)
                .append(Component.text("  hand them back to Discord rank sync", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin ranks list", ORANGE)
                .append(Component.text("  everyone currently held", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin eco give <player|everyone> <amount>", ORANGE)
                .append(Component.text("  add money", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin eco join on|off [amount]", ORANGE)
                .append(Component.text("  pay everyone who joins", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin bounty set <player|everyone> <amount>", ORANGE)
                .append(Component.text("  place a bounty without paying", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin bounty join on|off [amount]", ORANGE)
                .append(Component.text("  bounty everyone who joins, once each", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin hologram <board|remove>", ORANGE)
                .append(Component.text("  place or remove a spawn leaderboard", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /mgxadmin reset", ORANGE)
                .append(Component.text("  clear progress, keeping the world", NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args
    ) {
        if (!plugin.mayAdminister(sender)) {
            return List.of();
        }
        if (args.length == 1) {
            return partial(args[0], SUBCOMMANDS);
        }
        String action = args[0].toLowerCase(Locale.ROOT);
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
        return Component.text("MGX » ", ORANGE, TextDecoration.BOLD);
    }
}
