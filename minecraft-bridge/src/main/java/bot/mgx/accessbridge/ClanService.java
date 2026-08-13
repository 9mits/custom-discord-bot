package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
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
import java.util.Optional;
import java.util.UUID;

final class ClanService implements CommandExecutor, TabCompleter, Listener {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private static final TextColor LIGHT_ORANGE = TextColor.color(0xFFC266);
    private static final List<String> SUBCOMMANDS = List.of(
            "help", "create", "invite", "accept", "decline", "info", "list", "rename",
            "promote", "demote", "transfer", "kick", "leave", "chat", "friendlyfire", "disband"
    );

    private final MGXAccessBridge plugin;
    private final ClanStore store;

    ClanService(MGXAccessBridge plugin, ClanStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("The clans command is available to players only.");
            return true;
        }
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (action) {
                case "help" -> sendHelp(player);
                case "create" -> create(player, remainder(args, 1));
                case "invite", "add" -> invite(player, argument(args, 1, "Usage: /clans invite <player>"));
                case "accept", "join" -> accept(player);
                case "decline" -> decline(player);
                case "info" -> info(player, remainder(args, 1));
                case "list" -> list(player, args.length >= 2 ? args[1] : "1");
                case "rename", "name" -> rename(player, remainder(args, 1));
                case "promote" -> setStaff(player, argument(args, 1, "Usage: /clans promote <player>"), true);
                case "demote" -> setStaff(player, argument(args, 1, "Usage: /clans demote <player>"), false);
                case "transfer", "leader" -> transfer(player, argument(args, 1, "Usage: /clans transfer <player>"));
                case "kick", "remove" -> kick(player, argument(args, 1, "Usage: /clans kick <player>"));
                case "leave" -> leave(player);
                case "chat" -> chat(player, remainder(args, 1));
                case "friendlyfire", "ff" -> friendlyFire(player);
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
        if (name.isBlank()) {
            throw new ClanStore.ClanException("Usage: /clans create <name>");
        }
        ClanStore.ClanView clan = store.create(player.getUniqueId(), player.getName(), name);
        success(player, "Created " + clan.name() + ". You are its leader.");
    }

    private void invite(Player player, String targetName) throws IOException {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            throw new ClanStore.ClanException("That player must be online to receive an invite.");
        }
        store.invite(player.getUniqueId(), target.getUniqueId(), target.getName(), System.currentTimeMillis());
        ClanStore.ClanView clan = store.clanOf(player.getUniqueId()).orElseThrow();
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
        broadcast(clan, Component.text(player.getName() + " joined the clan.", LIGHT_ORANGE));
    }

    private void decline(Player player) throws IOException {
        store.decline(player.getUniqueId());
        success(player, "Declined the clan invite.");
    }

    private void info(Player player, String requestedName) {
        Optional<ClanStore.ClanView> found = requestedName.isBlank()
                ? store.clanOf(player.getUniqueId())
                : store.findClan(requestedName);
        ClanStore.ClanView clan = found.orElseThrow(() -> new ClanStore.ClanException(
                requestedName.isBlank() ? "You are not in a clan." : "No clan has that name."
        ));
        String leader = clan.members().getOrDefault(clan.leader(), "Unknown");
        player.sendMessage(Component.text(" "));
        player.sendMessage(Component.text(clan.name(), ORANGE, TextDecoration.BOLD));
        player.sendMessage(label("Leader", leader));
        player.sendMessage(label("Members", clan.members().size() + "/" + ClanStore.MAX_MEMBERS));
        player.sendMessage(label("Staff", String.valueOf(clan.staff().size())));
        player.sendMessage(label("Friendly Fire", clan.friendlyFire() ? "Enabled" : "Disabled"));
        player.sendMessage(Component.text(
                String.join(", ", clan.members().values()), NamedTextColor.GRAY
        ));
    }

    private void list(Player player, String requestedPage) {
        int page;
        try {
            page = Integer.parseInt(requestedPage);
        } catch (NumberFormatException exception) {
            throw new ClanStore.ClanException("The page must be a number.");
        }
        List<ClanStore.ClanView> clans = store.list();
        int pages = Math.max(1, (clans.size() + 7) / 8);
        if (page < 1 || page > pages) {
            throw new ClanStore.ClanException("Choose a page from 1 to " + pages + ".");
        }
        player.sendMessage(prefix().append(Component.text(
                "Directory " + page + "/" + pages, NamedTextColor.WHITE, TextDecoration.BOLD
        )));
        clans.stream().skip((long) (page - 1) * 8).limit(8).forEach(clan ->
                player.sendMessage(Component.text("  " + clan.name(), LIGHT_ORANGE)
                        .append(Component.text("  " + clan.members().size() + " members", NamedTextColor.GRAY)))
        );
        if (clans.isEmpty()) {
            player.sendMessage(Component.text("  No clans have been created yet.", NamedTextColor.GRAY));
        }
    }

    private void rename(Player player, String name) throws IOException {
        if (name.isBlank()) {
            throw new ClanStore.ClanException("Usage: /clans rename <new name>");
        }
        ClanStore.ClanView clan = store.rename(player.getUniqueId(), name);
        broadcast(clan, Component.text("The clan is now named " + clan.name() + ".", LIGHT_ORANGE));
    }

    private void setStaff(Player player, String targetName, boolean promoted) throws IOException {
        ClanStore.ClanView clan = ownClan(player);
        UUID target = member(clan, targetName);
        ClanStore.ClanView updated = store.setStaff(player.getUniqueId(), target, promoted);
        String name = updated.members().get(target);
        broadcast(updated, Component.text(
                name + (promoted ? " is now clan staff." : " is no longer clan staff."),
                LIGHT_ORANGE
        ));
    }

    private void transfer(Player player, String targetName) throws IOException {
        ClanStore.ClanView clan = ownClan(player);
        UUID target = member(clan, targetName);
        ClanStore.ClanView updated = store.transfer(player.getUniqueId(), target);
        broadcast(updated, Component.text(
                updated.members().get(target) + " is now the clan leader.", LIGHT_ORANGE
        ));
    }

    private void kick(Player player, String targetName) throws IOException {
        ClanStore.ClanView clan = ownClan(player);
        UUID target = member(clan, targetName);
        String removedName = store.kick(player.getUniqueId(), target);
        success(player, "Removed " + removedName + " from " + clan.name() + ".");
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            error(online, "You were removed from " + clan.name() + ".");
        }
    }

    private void leave(Player player) throws IOException {
        String clanName = store.leave(player.getUniqueId());
        success(player, "You left " + clanName + ".");
    }

    private void chat(Player player, String message) {
        if (message.isBlank()) {
            throw new ClanStore.ClanException("Usage: /clans chat <message>");
        }
        if (message.length() > 160) {
            throw new ClanStore.ClanException("Clan chat messages can contain at most 160 characters.");
        }
        ClanStore.ClanView clan = ownClan(player);
        Component chat = Component.text("[" + clan.name() + "] ", ORANGE, TextDecoration.BOLD)
                .append(Component.text(player.getName() + ": ", LIGHT_ORANGE))
                .append(Component.text(message, NamedTextColor.WHITE));
        for (UUID memberId : clan.members().keySet()) {
            Player online = Bukkit.getPlayer(memberId);
            if (online != null) {
                online.sendMessage(chat);
            }
        }
    }

    private void friendlyFire(Player player) throws IOException {
        ClanStore.ClanView clan = store.toggleFriendlyFire(player.getUniqueId());
        broadcast(clan, Component.text(
                "Friendly fire is now " + (clan.friendlyFire() ? "enabled." : "disabled."),
                LIGHT_ORANGE
        ));
    }

    private void disband(Player player, String confirmation) throws IOException {
        if (!confirmation.equalsIgnoreCase("confirm")) {
            throw new ClanStore.ClanException("This removes the clan permanently. Use /clans disband confirm.");
        }
        ClanStore.ClanView clan = ownClan(player);
        store.disband(player.getUniqueId());
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
        player.sendMessage(prefix().append(Component.text("Commands", NamedTextColor.WHITE, TextDecoration.BOLD)));
        player.sendMessage(help("/clans create <name>", "Create a clan"));
        player.sendMessage(help("/clans invite <player>", "Invite an online player"));
        player.sendMessage(help("/clans accept | decline", "Answer your latest invite"));
        player.sendMessage(help("/clans info [name] | list [page]", "Browse clans"));
        player.sendMessage(help("/clans rename <name>", "Change your clan name"));
        player.sendMessage(help("/clans promote | demote <player>", "Manage clan staff"));
        player.sendMessage(help("/clans transfer <player>", "Transfer leadership"));
        player.sendMessage(help("/clans kick <player> | leave", "Manage membership"));
        player.sendMessage(help("/clans chat <message>", "Message online clan members"));
        player.sendMessage(help("/clans friendlyfire", "Toggle damage between members"));
        player.sendMessage(help("/clans disband confirm", "Permanently remove your clan"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFriendlyFire(EntityDamageByEntityEvent event) {
        Player attacker = attackingPlayer(event);
        if (attacker == null || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        Optional<ClanStore.ClanView> attackerClan = store.clanOf(attacker.getUniqueId());
        Optional<ClanStore.ClanView> victimClan = store.clanOf(victim.getUniqueId());
        if (attackerClan.isPresent()
                && victimClan.isPresent()
                && attackerClan.get().id().equals(victimClan.get().id())
                && !attackerClan.get().friendlyFire()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        try {
            store.touchPlayerName(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not update a clan member name: " + exception.getMessage());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return partial(args[0], SUBCOMMANDS);
        }
        if (!(sender instanceof Player player) || args.length != 2) {
            return List.of();
        }
        String action = args[0].toLowerCase(Locale.ROOT);
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

    private ClanStore.ClanView ownClan(Player player) {
        return store.clanOf(player.getUniqueId())
                .orElseThrow(() -> new ClanStore.ClanException("You are not in a clan."));
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

    private static void success(Player player, String message) {
        player.sendMessage(prefix().append(Component.text(message, NamedTextColor.WHITE)));
    }

    private static void error(Player player, String message) {
        player.sendMessage(prefix().append(Component.text(message, NamedTextColor.RED)));
    }

    private static Component prefix() {
        return Component.text("CLANS ", ORANGE, TextDecoration.BOLD);
    }

    private static Component label(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.WHITE));
    }

    private static Component help(String command, String description) {
        return Component.text("  " + command, LIGHT_ORANGE)
                .append(Component.text(" — " + description, NamedTextColor.GRAY));
    }

    private static String argument(String[] args, int index, String error) {
        if (args.length <= index || args[index].isBlank()) {
            throw new ClanStore.ClanException(error);
        }
        return args[index];
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
