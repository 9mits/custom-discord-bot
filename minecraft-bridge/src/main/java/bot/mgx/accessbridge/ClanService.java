package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.ItemStack;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.StringUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class ClanService implements CommandExecutor, TabCompleter, Listener {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private static final TextColor LIGHT_ORANGE = TextColor.color(0xFFC266);
    private static final List<String> THEME_COLORS = List.of(
            "orange", "gold", "yellow", "red", "pink", "purple", "blue", "aqua", "green", "white"
    );
    private static final List<String> CLANLESS_SUBCOMMANDS = List.of(
            "help", "create", "accept", "decline", "info", "list"
    );
    private static final List<String> MEMBER_SUBCOMMANDS = List.of(
            "help", "info", "list", "members", "chat", "leave", "menu", "donate", "balance",
            "donors", "allies", "warp"
    );
    private static final List<String> STAFF_SUBCOMMANDS = List.of(
            "help", "invite", "info", "list", "members", "kick", "chat", "leave",
            "menu", "donate", "balance", "donors", "upgrade", "ally", "unally", "allies", "warp"
    );
    private static final List<String> LEADER_SUBCOMMANDS = List.of(
            "help", "invite", "info", "list", "rename", "color", "promote", "demote",
            "transfer", "kick", "chat", "disband", "menu", "donate", "balance", "donors", "upgrade",
            "members", "ally", "unally", "allies", "warp"
    );

    private final MGXAccessBridge plugin;
    private final ClanStore store;
    private final DiscordIdentityService identities;
    private final PlayerPerkService perks;
    private final PlayerSettingsStore settings;
    private final ClanMenuService menus;
    private final ClanBattleStore clanBattles;
    private ClanChooserService chooser;
    private ClanDirectoryService directory;
    private ClanWarpDialogService clanWarps;

    ClanService(
            MGXAccessBridge plugin,
            ClanStore store,
            DiscordIdentityService identities,
            PlayerPerkService perks,
            PlayerSettingsStore settings,
            ClanMenuService menus,
            ClanBattleStore clanBattles
    ) {
        this.plugin = plugin;
        this.store = store;
        this.identities = identities;
        this.perks = perks;
        this.settings = settings;
        this.menus = menus;
        this.clanBattles = clanBattles;
    }

    /** Wired after construction; the chooser needs this service's menus. */
    void useChooser(ClanChooserService chooser) {
        this.chooser = chooser;
    }

    void useDirectory(ClanDirectoryService directory) {
        this.directory = directory;
    }

    void useClanWarps(ClanWarpDialogService clanWarps) {
        this.clanWarps = clanWarps;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        args = CommandArgs.withoutEchoedSender(sender.getName(), args);
        if (!(sender instanceof Player player)) {
            sender.sendMessage("The clans command is available to players only.");
            return true;
        }
        boolean infoAlias = command.getName().equalsIgnoreCase("claninfo");
        // A bare /clans asks which screen was meant. It used to go straight to the
        // player's own clan and refuse anyone without one, which hid the directory
        // from exactly the people looking for a clan to join.
        String bare = "menu";
        String action = infoAlias ? "info" : args.length == 0 ? bare : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (action) {
                case "help" -> sendHelp(player);
                case "create" -> create(player, remainder(args, 1));
                case "invite", "add" -> invite(player, remainder(args, 1));
                case "accept", "join" -> accept(player);
                case "decline" -> decline(player);
                case "info" -> menus.openInfo(player, remainder(args, infoAlias ? 0 : 1));
                case "members", "roster" -> menus.openMembers(
                        player, ownClan(player).id(), page(args, 1),
                        Menu.Destination.of(Menu.Kind.CLAN_HUB));
                case "list" -> {
                    if (directory != null) {
                        directory.open(player, page(args, 1));
                    } else {
                        menus.openList(player, page(args, 1));
                    }
                }
                case "rename", "name" -> rename(player, remainder(args, 1));
                case "color", "colour", "theme" -> color(player, remainder(args, 1));
                case "promote" -> setStaff(player, remainder(args, 1), true);
                case "demote" -> setStaff(player, remainder(args, 1), false);
                case "transfer", "leader" -> transfer(player, remainder(args, 1));
                case "kick", "remove" -> kick(player, remainder(args, 1));
                case "ally", "alliance" -> ally(player, remainder(args, 1));
                case "unally", "breakally" -> unally(player, remainder(args, 1));
                case "allies", "alliances" -> allies(player);
                case "leave" -> leave(player);
                case "chat" -> chat(player, remainder(args, 1));
                case "menu" -> {
                    if (chooser != null) {
                        chooser.open(player);
                    } else {
                        menus.openHub(player);
                    }
                }
                case "donate" -> {
                    if (args.length >= 2) {
                        menus.donate(player, EconomyFormat.parseAmount(args[1]));
                    } else {
                        menus.openDonate(player);
                    }
                }
                case "balance", "vault", "bank" -> menus.openBalance(player);
                case "donors", "contributors" -> menus.openDonors(player);
                case "upgrade", "levelup" -> menus.openUpgrade(player);
                case "warp", "warps" -> warp(player, args);
                case "disband" -> disband(player, args.length >= 2 ? args[1] : "");
                default -> throw new ClanStore.ClanException("Unknown subcommand. Use /clans help.");
            }
        } catch (ClanStore.ClanException exception) {
            error(player, exception.getMessage());
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save clan data: " + exception.getMessage());
            error(player, "Clan data could not be saved. Contact an administrator before retrying.");
        }
        return true;
    }

    private void create(Player player, String name) throws IOException {
        if (store.clanOf(player.getUniqueId()).isPresent()) {
            throw new ClanStore.ClanException("You already have a clan!");
        }
        if (name.isBlank()) {
            throw new ClanStore.ClanException("Usage: /clans create <name>");
        }
        ClanStore.ClanView clan = store.create(player.getUniqueId(), player.getName(), name);
        plugin.refreshClans();
        report(player, "clan_create", "Founded the clan " + clan.name())
                .detail("clan", clan.name())
                .record();
        success(player, "Created [" + clan.name() + "]. You are its leader.");
    }

    private void invite(Player player, String targetName) throws IOException {
        staffClan(player);
        if (targetName.isBlank()) {
            throw new ClanStore.ClanException("Usage: /clans invite <player>");
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            throw new ClanStore.ClanException("That player must be online to receive an invite.");
        }
        store.invite(player.getUniqueId(), target.getUniqueId(), target.getName(), System.currentTimeMillis());
        ClanStore.ClanView clan = store.clanOf(player.getUniqueId()).orElseThrow();
        report(player, "clan_invite", "Invited " + target.getName() + " to " + clan.name())
                .detail("clan", clan.name())
                .detail("target", target.getName())
                .record();
        success(player, "Invited " + target.getName() + " to " + clan.name() + ".");
        target.sendMessage(prefix().append(Component.text(
                player.getName() + " invited you to " + clan.name() + ". Use /clans accept within five minutes.",
                NamedTextColor.WHITE
        )));
    }

    private void accept(Player player) throws IOException {
        ClanStore.ClanView clan = store.accept(
                player.getUniqueId(), player.getName(), System.currentTimeMillis()
        );
        plugin.refreshClans();
        report(player, "clan_join", "Joined the clan " + clan.name())
                .detail("clan", clan.name())
                .detail("members", clan.members().size() + "/" + clan.memberSlots())
                .record();
        broadcast(clan, Component.text(player.getName() + " joined the clan.", LIGHT_ORANGE));
    }

    private void decline(Player player) throws IOException {
        store.decline(player.getUniqueId());
        success(player, "Declined the clan invite.");
    }

    /**
     * Offers an alliance, or accepts one already waiting.
     *
     * <p>One command does both halves, so neither clan has to remember a different
     * word for their side of it.
     */
    private void ally(Player player, String targetName) throws IOException {
        staffClan(player);
        if (targetName.isBlank()) {
            throw new ClanStore.ClanException("Usage: /clans ally <clan>");
        }
        ClanStore.AllyResult result = store.ally(
                player.getUniqueId(), targetName, System.currentTimeMillis()
        );
        plugin.refreshClans();
        if (!result.formed()) {
            report(player, "clan_ally_offer",
                    "Offered " + result.other().name() + " an alliance with " + result.own().name())
                    .detail("clan", result.own().name())
                    .detail("ally", result.other().name())
                    .record();
            success(player, "Offered " + result.other().name() + " an alliance. They accept with "
                    + "/clans ally " + result.own().name() + " within ten minutes.");
            broadcast(result.other(), Component.text(
                    result.own().name() + " offered your clan an alliance. Accept with /clans ally "
                            + result.own().name() + ".",
                    LIGHT_ORANGE
            ));
            return;
        }
        report(player, "clan_ally",
                result.own().name() + " allied with " + result.other().name())
                .detail("clan", result.own().name())
                .detail("ally", result.other().name())
                .record();
        Component formed = Component.text(
                result.own().name() + " and " + result.other().name()
                        + " are now allies. You cannot damage each other.",
                LIGHT_ORANGE
        );
        broadcast(result.own(), formed);
        broadcast(result.other(), formed);
    }

    private void unally(Player player, String targetName) throws IOException {
        staffClan(player);
        if (targetName.isBlank()) {
            throw new ClanStore.ClanException("Usage: /clans unally <clan>");
        }
        ClanStore.AllyResult result = store.unally(player.getUniqueId(), targetName);
        plugin.refreshClans();
        report(player, "clan_unally",
                result.own().name() + " ended its alliance with " + result.other().name())
                .detail("clan", result.own().name())
                .detail("ally", result.other().name())
                .record();
        Component ended = Component.text(
                result.own().name() + " and " + result.other().name()
                        + " are no longer allies. You can damage each other again.",
                LIGHT_ORANGE
        );
        broadcast(result.own(), ended);
        broadcast(result.other(), ended);
    }

    private void allies(Player player) {
        ClanStore.ClanView clan = ownClan(player);
        List<String> names = clan.allyNames();
        if (names.isEmpty()) {
            player.sendMessage(prefix().append(Component.text(
                    clan.name() + " has no allies. Use /clans ally <clan> to offer one.",
                    NamedTextColor.GRAY)));
            return;
        }
        player.sendMessage(prefix().append(Component.text(
                clan.name() + " allies (" + names.size() + "/" + ClanStore.MAX_ALLIES + "):",
                NamedTextColor.GRAY)));
        for (String name : names) {
            player.sendMessage(prefix().append(Component.text("  " + name, ORANGE, TextDecoration.BOLD))
                    .append(Component.text(" — no friendly fire", NamedTextColor.GRAY)));
        }
    }



    /** A page argument, defaulting to the first and never below it. */
    private static int page(String[] args, int index) {
        if (args.length <= index) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(args[index]));
        } catch (NumberFormatException exception) {
            throw new ClanStore.ClanException("The page must be a number.");
        }
    }

    private void rename(Player player, String name) throws IOException {
        String previousName = leaderClan(player).name();
        if (name.isBlank()) {
            throw new ClanStore.ClanException("Usage: /clans rename <new name>");
        }
        ClanStore.ClanView clan = store.rename(player.getUniqueId(), name);
        plugin.refreshClans();
        report(player, "clan_rename", "Renamed " + previousName + " to " + clan.name())
                .detail("clan", clan.name())
                .detail("previous", previousName)
                .record();
        broadcast(clan, Component.text("The clan is now named " + clan.name() + ".", LIGHT_ORANGE));
    }

    private void color(Player player, String requestedColor) throws IOException {
        leaderClan(player);
        if (requestedColor.isBlank()) {
            throw new ClanStore.ClanException("Usage: /clans color <color|#RRGGBB>");
        }
        ClanStore.ClanView clan = store.setThemeColor(
                player.getUniqueId(),
                resolveThemeColor(requestedColor)
        );
        plugin.refreshClans();
        report(player, "clan_color", "Set the " + clan.name() + " theme colour")
                .detail("clan", clan.name())
                .detail("colour", String.format("#%06X", clan.themeColor()))
                .record();
        TextColor theme = clanColor(clan);
        broadcast(clan, Component.text("Clan theme changed to ", NamedTextColor.GRAY)
                .append(Component.text(String.format("#%06X", clan.themeColor()), theme)));
    }

    private void setStaff(Player player, String targetName, boolean promoted) throws IOException {
        ClanStore.ClanView clan = leaderClan(player);
        if (targetName.isBlank()) {
            throw new ClanStore.ClanException(
                    promoted ? "Usage: /clans promote <player>" : "Usage: /clans demote <player>"
            );
        }
        UUID target = member(clan, targetName);
        ClanStore.ClanView updated = store.setStaff(player.getUniqueId(), target, promoted);
        String name = updated.members().get(target);
        report(player, promoted ? "clan_promote" : "clan_demote",
                (promoted ? "Promoted " : "Demoted ") + name + " in " + updated.name())
                .detail("clan", updated.name())
                .detail("target", name)
                .record();
        broadcast(updated, Component.text(
                name + (promoted ? " is now clan staff." : " is no longer clan staff."),
                LIGHT_ORANGE
        ));
    }

    private void transfer(Player player, String targetName) throws IOException {
        ClanStore.ClanView clan = leaderClan(player);
        if (targetName.isBlank()) {
            throw new ClanStore.ClanException("Usage: /clans transfer <player>");
        }
        UUID target = member(clan, targetName);
        ClanStore.ClanView updated = store.transfer(player.getUniqueId(), target);
        report(player, "clan_transfer",
                "Handed " + updated.name() + " to " + updated.members().get(target))
                .detail("clan", updated.name())
                .detail("target", updated.members().get(target))
                .record();
        broadcast(updated, Component.text(
                updated.members().get(target) + " is now the clan leader.", LIGHT_ORANGE
        ));
    }

    private void kick(Player player, String targetName) throws IOException {
        ClanStore.ClanView clan = staffClan(player);
        if (targetName.isBlank()) {
            throw new ClanStore.ClanException("Usage: /clans kick <player>");
        }
        UUID target = member(clan, targetName);
        String removedName = store.kick(player.getUniqueId(), target);
        plugin.refreshClans();
        report(player, "clan_kick", "Removed " + removedName + " from " + clan.name())
                .detail("clan", clan.name())
                .detail("target", removedName)
                .record();
        success(player, "Removed " + removedName + " from " + clan.name() + ".");
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            error(online, "You were removed from " + clan.name() + ".");
        }
    }

    private void leave(Player player) throws IOException {
        String clanName = store.leave(player.getUniqueId());
        plugin.refreshClans();
        report(player, "clan_leave", "Left the clan " + clanName).detail("clan", clanName)
                .record();
        success(player, "You left " + clanName + ".");
    }

    private void chat(Player player, String message) {
        ClanStore.ClanView clan = ownClan(player);
        if (message.isBlank()) {
            throw new ClanStore.ClanException("Usage: /clans chat <message>");
        }
        if (message.length() > 160) {
            throw new ClanStore.ClanException("Clan chat messages can contain at most 160 characters.");
        }
        Component chat = Component.empty()
                .append(clanTag(clan))
                .append(Component.text("CLAN  ", NamedTextColor.DARK_GRAY))
                .append(identities.tag(player.getUniqueId()))
                .append(Component.text(player.getName(), NamedTextColor.WHITE))
                .append(Component.text(": ", NamedTextColor.DARK_GRAY))
                .append(Component.text(message, NamedTextColor.WHITE));
        for (UUID memberId : clan.members().keySet()) {
            Player online = Bukkit.getPlayer(memberId);
            // The sender always sees their own line; muting it would look like the
            // message never sent.
            if (online != null && (online.equals(player) || settings.isEnabled(
                    memberId, PlayerSettingsStore.Setting.CLAN_CHAT_VISIBLE
            ))) {
                online.sendMessage(chat);
            }
        }
    }

    private void disband(Player player, String confirmation) throws IOException {
        ClanStore.ClanView clan = leaderClan(player);
        if (!confirmation.equalsIgnoreCase("confirm")) {
            if (clan.balance() > 0) {
                // Donations are one-way, so this is the last chance anyone gets to
                // hear that the balance dies with the clan.
                error(player, "This also destroys the clan treasury of "
                        + EconomyFormat.dollars(clan.balance())
                        + ". Donations cannot be taken back out.");
            }
            throw new ClanStore.ClanException("This removes the clan permanently. Use /clans disband confirm.");
        }
        store.disband(player.getUniqueId());
        plugin.refreshClans();
        report(player, "clan_disband", "Disbanded the clan " + clan.name())
                .detail("clan", clan.name())
                .detail("balance", clan.balance())
                .detail("members", String.valueOf(clan.members().size()))
                .record();
        for (UUID memberId : clan.members().keySet()) {
            Player online = Bukkit.getPlayer(memberId);
            if (online != null) {
                online.sendMessage(prefix().append(Component.text(
                        clan.name() + " was disbanded.", NamedTextColor.WHITE
                )));
            }
        }
    }

    private void sendHelp(Player player) {
        Optional<ClanStore.ClanView> current = store.clanOf(player.getUniqueId());
        player.sendMessage(prefix().append(Component.text("Commands", NamedTextColor.WHITE, TextDecoration.BOLD)));
        if (current.isEmpty()) {
            player.sendMessage(help("/clans create <name>", "Create a clan"));
            player.sendMessage(help("/clans accept | decline", "Answer your latest invite"));
        }
        player.sendMessage(help("/clans info [name] | list [page]", "Browse clans"));
        if (current.isPresent()) {
            ClanStore.ClanRole role = current.get().roleOf(player.getUniqueId());
            player.sendMessage(help("/clans", "Open the clan menu"));
            player.sendMessage(help("/clans donate [amount]", "Give money to the clan"));
            player.sendMessage(help("/clans balance | donors", "What the clan holds, and who gave it"));
            player.sendMessage(help("/clans allies", "Clans you cannot damage"));
            player.sendMessage(help("/clans warp [name]", "Open or use the shared warp directory"));
            if (role == ClanStore.ClanRole.LEADER || role == ClanStore.ClanRole.STAFF) {
                player.sendMessage(help("/clans upgrade", "Spend the balance on levels or slots"));
                player.sendMessage(help("/clans invite <player>", "Invite an online player"));
                player.sendMessage(help("/clans kick <player>", "Remove a clan member"));
                player.sendMessage(help("/clans ally <clan>", "Offer or accept an alliance"));
                player.sendMessage(help("/clans unally <clan>", "End an alliance"));
                player.sendMessage(help("/clans warp set | delete <name>", "Manage shared locations"));
            }
            if (role == ClanStore.ClanRole.LEADER) {
                player.sendMessage(help("/clans rename <name>", "Change your clan name"));
                player.sendMessage(help("/clans color <color|#hex>", "Change your clan theme"));
                player.sendMessage(help("/clans promote | demote <player>", "Manage clan staff"));
                player.sendMessage(help("/clans transfer <player>", "Transfer leadership"));
                player.sendMessage(help("/clans disband confirm", "Permanently remove your clan"));
            } else {
                player.sendMessage(help("/clans leave", "Leave your current clan"));
            }
            player.sendMessage(help("/clans chat <message>", "Message online clan members"));
        }
        player.sendMessage(Component.text(
                "  Clan protection: members of the same clan, and of allied clans,"
                        + " cannot damage one another.",
                NamedTextColor.GRAY
        ));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClanDamage(EntityDamageByEntityEvent event) {
        Player attacker = attackingPlayer(event);
        if (attacker == null || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        // pvpBlocked rather than two clanOf calls: this fires for every arrow and
        // every swing, and building a ClanView rebuilds both rosters to answer a
        // question the member index already knows.
        if (!store.pvpBlocked(attacker.getUniqueId(), victim.getUniqueId())) {
            return;
        }
        // LOWEST so CombatLog has not tagged yet when we cancel. A next-tick
        // untag covers a CombatLog that listens at LOWEST and registered first.
        event.setCancelled(true);
        clearCombatLog(attacker, victim);
        plugin.getServer().getScheduler().runTask(plugin, () -> clearCombatLog(attacker, victim));
    }

    private static void clearCombatLog(Player... players) {
        org.bukkit.plugin.Plugin combatLog = Bukkit.getPluginManager().getPlugin("CombatLog");
        if (combatLog == null || !combatLog.isEnabled()) {
            return;
        }
        for (Player player : players) {
            for (String name : List.of("untag", "removeTag", "removeCombat", "endCombat")) {
                try {
                    combatLog.getClass().getMethod(name, Player.class).invoke(combatLog, player);
                    break;
                } catch (ReflectiveOperationException ignored) {
                    // Try the next known method name.
                }
            }
        }
    }

    /** Chat body text: deliberately neutral, never a LuckPerms rank colour. */
    private static final TextColor CHAT_NAME_COLOUR = NamedTextColor.WHITE;
    private static final TextColor CHAT_MESSAGE_COLOUR = TextColor.color(0xD6D6D6);

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPublicChat(AsyncChatEvent event) {
        Optional<ClanStore.ClanView> clan = store.clanOf(event.getPlayer().getUniqueId());
        PlayerProfile profile = perks.profile(event.getPlayer().getUniqueId());
        // Every message is re-rendered, not just tagged ones: the vanilla renderer
        // wraps names in <> and inherits whatever colours other plugins set, and the
        // house style is a plain name and message regardless of rank.
        event.renderer((source, sourceDisplayName, message, viewer) -> {
            // Tags are the viewer's choice, so each player is rendered for separately.
            boolean showClan = !(viewer instanceof Player watcher)
                    || settings.isEnabled(watcher.getUniqueId(), PlayerSettingsStore.Setting.CLAN_TAGS);
            Component prefix = SidebarService.rankTag(profile);
            if (clan.isPresent() && showClan) {
                prefix = prefix.append(clanTag(clan.get()));
            }
            prefix = prefix.append(identities.tag(event.getPlayer().getUniqueId()));
            return prefix
                    .append(Component.text(source.getName(), CHAT_NAME_COLOUR)
                            .decoration(TextDecoration.BOLD, false))
                    .append(Component.text(": ", NamedTextColor.DARK_GRAY))
                    .append(message.colorIfAbsent(CHAT_MESSAGE_COLOUR)
                            .decoration(TextDecoration.BOLD, false));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        try {
            store.touchPlayerName(event.getPlayer().getUniqueId(), event.getPlayer().getName());
            plugin.refreshClans();
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not update a clan member name: " + exception.getMessage());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("claninfo")) {
            return args.length == 1
                    ? partial(args[0], store.list().stream().map(ClanStore.ClanView::name).toList())
                    : List.of();
        }
        if (args.length == 1) {
            List<String> available = sender instanceof Player player
                    ? availableSubcommands(player)
                    : CLANLESS_SUBCOMMANDS;
            return partial(args[0], available);
        }
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!availableSubcommands(player).contains(canonicalAction(action))) {
            return List.of();
        }
        if (List.of("warp", "warps").contains(action)) {
            ClanStore.ClanView clan = store.clanOf(player.getUniqueId()).orElse(null);
            if (clan == null) {
                return List.of();
            }
            if (args.length == 2) {
                List<String> choices = new ArrayList<>(clan.warps().keySet());
                if (clan.roleOf(player.getUniqueId()) != ClanStore.ClanRole.MEMBER) {
                    choices.add("set");
                    choices.add("delete");
                }
                return partial(args[1], choices);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("delete")) {
                return partial(args[2], new ArrayList<>(clan.warps().keySet()));
            }
            return List.of();
        }
        if (args.length != 2) {
            return List.of();
        }
        if (action.equals("invite") || action.equals("add")) {
            return partial(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        if (List.of("promote", "demote", "transfer", "leader", "kick", "remove").contains(action)) {
            return store.clanOf(player.getUniqueId())
                    .map(clan -> partial(args[1], new ArrayList<>(clan.members().values())))
                    .orElse(List.of());
        }
        if (action.equals("info")) {
            return partial(args[1], store.list().stream().map(ClanStore.ClanView::name).toList());
        }
        if (List.of("ally", "alliance").contains(action)) {
            // Every clan but their own, so the list never suggests something refused.
            String own = store.clanOf(player.getUniqueId()).map(ClanStore.ClanView::name).orElse("");
            return partial(args[1], store.list().stream()
                    .map(ClanStore.ClanView::name)
                    .filter(name -> !name.equals(own))
                    .toList());
        }
        if (List.of("unally", "breakally").contains(action)) {
            return store.clanOf(player.getUniqueId())
                    .map(clan -> partial(args[1], clan.allyNames()))
                    .orElse(List.of());
        }
        if (List.of("color", "colour", "theme").contains(action)) {
            return partial(args[1], THEME_COLORS);
        }
        if (action.equals("disband")) {
            return partial(args[1], List.of("confirm"));
        }
        return List.of();
    }

    private static Player attackingPlayer(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            return shooter instanceof Player player ? player : null;
        }
        return null;
    }

    private void warp(Player player, String[] args) throws IOException {
        ownClan(player);
        if (args.length == 1) {
            openWarps(player);
            return;
        }
        if (args[1].equalsIgnoreCase("set")) {
            if (args.length < 3) {
                throw new ClanStore.ClanException("Usage: /clans warp set <name>");
            }
            menus.setWarp(player, args[2]);
            openWarps(player);
            return;
        }
        if (args[1].equalsIgnoreCase("delete") || args[1].equalsIgnoreCase("remove")) {
            if (args.length < 3) {
                throw new ClanStore.ClanException("Usage: /clans warp delete <name>");
            }
            menus.removeWarp(player, args[2]);
            openWarps(player);
            return;
        }
        menus.useWarp(player, args[1]);
    }

    /** One way in, so the replaced screen is not reachable by another route. */
    private void openWarps(Player player) {
        if (clanWarps != null) {
            clanWarps.open(player);
        } else {
            menus.openWarps(player);
        }
    }

    private ClanStore.ClanView ownClan(Player player) {
        return store.clanOf(player.getUniqueId())
                .orElseThrow(() -> new ClanStore.ClanException("You are not in a clan."));
    }

    private ClanStore.ClanView staffClan(Player player) {
        ClanStore.ClanView clan = ownClan(player);
        if (clan.roleOf(player.getUniqueId()) == ClanStore.ClanRole.MEMBER) {
            throw new ClanStore.ClanException("Only clan staff can do that.");
        }
        return clan;
    }

    private ClanStore.ClanView leaderClan(Player player) {
        ClanStore.ClanView clan = ownClan(player);
        if (clan.roleOf(player.getUniqueId()) != ClanStore.ClanRole.LEADER) {
            throw new ClanStore.ClanException("Only the clan leader can do that.");
        }
        return clan;
    }

    private List<String> availableSubcommands(Player player) {
        return store.clanOf(player.getUniqueId())
                .map(clan -> switch (clan.roleOf(player.getUniqueId())) {
                    case LEADER -> LEADER_SUBCOMMANDS;
                    case STAFF -> STAFF_SUBCOMMANDS;
                    case MEMBER -> MEMBER_SUBCOMMANDS;
                })
                .orElse(CLANLESS_SUBCOMMANDS);
    }

    private static String canonicalAction(String action) {
        return switch (action) {
            case "add" -> "invite";
            case "join" -> "accept";
            case "name" -> "rename";
            case "colour", "theme" -> "color";
            case "leader" -> "transfer";
            case "remove" -> "kick";
            case "vault", "bank" -> "balance";
            case "contributors" -> "donors";
            case "roster" -> "members";
            case "levelup" -> "upgrade";
            case "alliance" -> "ally";
            case "breakally" -> "unally";
            case "alliances" -> "allies";
            case "warps" -> "warp";
            default -> action;
        };
    }

    private UUID member(ClanStore.ClanView clan, String name) {
        return store.findMember(clan.id(), name)
                .orElseThrow(() -> new ClanStore.ClanException("No clan member has that name."));
    }

    private void broadcast(ClanStore.ClanView clan, Component message) {
        Component rendered = prefix().append(message);
        for (UUID memberId : clan.members().keySet()) {
            Player online = Bukkit.getPlayer(memberId);
            if (online != null) {
                online.sendMessage(rendered);
            }
        }
    }

    /**
     * Reports a clan action to the Discord activity log.
     *
     * <p>Only actions taken in game come through here. The same action requested from
     * Discord is audited by the bot at the point of the command, so reporting it again
     * would log it twice.
     */
    private ServerEvent.Builder report(Player actor, String event, String summary) {
        return ServerEvent.of(
                event, ServerEvent.CATEGORY_CLAN, actor.getUniqueId(), actor.getName(),
                plugin::recordServerEvent
        ).summary(summary);
    }

    private static void success(Player player, String message) {
        player.sendMessage(prefix().append(Component.text(message, NamedTextColor.WHITE)));
    }

    private static void error(Player player, String message) {
        player.sendMessage(prefix().append(Component.text(message, NamedTextColor.RED)));
    }

    private static Component prefix() {
        return Component.text("CLANS » ", ORANGE, TextDecoration.BOLD);
    }

    private static Component help(String command, String description) {
        return Component.text("  " + command, LIGHT_ORANGE)
                .append(Component.text(" — " + description, NamedTextColor.GRAY));
    }

    private static TextColor clanColor(ClanStore.ClanView clan) {
        return TextColor.color(clan.themeColor());
    }

    private Component clanTag(ClanStore.ClanView clan) {
        return ClanTag.of(clan, clanBattles.badges(clan.id()));
    }

    private static String resolveThemeColor(String requestedColor) {
        return switch (requestedColor.toLowerCase(Locale.ROOT)) {
            case "orange" -> "FF9900";
            case "gold" -> "FFAA00";
            case "yellow" -> "FFFF55";
            case "red" -> "FF5555";
            case "pink" -> "FF55FF";
            case "purple" -> "AA00AA";
            case "blue" -> "5555FF";
            case "aqua" -> "55FFFF";
            case "green" -> "55FF55";
            case "white" -> "FFFFFF";
            default -> requestedColor;
        };
    }

    private static String remainder(String[] args, int start) {
        if (args.length <= start) {
            return "";
        }
        return String.join(" ", java.util.Arrays.copyOfRange(args, start, args.length)).trim();
    }

    private static List<String> partial(String token, List<String> candidates) {
        ArrayList<String> results = new ArrayList<>();
        StringUtil.copyPartialMatches(token, candidates, results);
        results.sort(String.CASE_INSENSITIVE_ORDER);
        return results;
    }
}
