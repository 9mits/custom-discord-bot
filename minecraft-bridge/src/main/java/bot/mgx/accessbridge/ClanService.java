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
            "help", "info", "list", "chat", "leave", "vault", "deposit"
    );
    private static final List<String> STAFF_SUBCOMMANDS = List.of(
            "help", "invite", "info", "list", "kick", "chat", "leave", "icon", "vault", "deposit"
    );
    private static final List<String> LEADER_SUBCOMMANDS = List.of(
            "help", "invite", "info", "list", "rename", "color", "icon", "promote", "demote",
            "transfer", "kick", "chat", "disband", "vault", "deposit", "withdraw", "upgrade"
    );

    private final MGXAccessBridge plugin;
    private final ClanStore store;
    private final DiscordIdentityService identities;
    private final PlayerPerkService perks;
    private final PlayerSettingsStore settings;

    ClanService(
            MGXAccessBridge plugin,
            ClanStore store,
            DiscordIdentityService identities,
            PlayerPerkService perks,
            PlayerSettingsStore settings
    ) {
        this.plugin = plugin;
        this.store = store;
        this.identities = identities;
        this.perks = perks;
        this.settings = settings;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("The clans command is available to players only.");
            return true;
        }
        boolean infoAlias = command.getName().equalsIgnoreCase("claninfo");
        String action = infoAlias ? "info" : args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (action) {
                case "help" -> sendHelp(player);
                case "create" -> create(player, remainder(args, 1));
                case "invite", "add" -> invite(player, remainder(args, 1));
                case "accept", "join" -> accept(player);
                case "decline" -> decline(player);
                case "info" -> info(player, remainder(args, infoAlias ? 0 : 1));
                case "list" -> list(player, args.length >= 2 ? args[1] : "1");
                case "rename", "name" -> rename(player, remainder(args, 1));
                case "color", "colour", "theme" -> color(player, remainder(args, 1));
                case "icon", "logo" -> icon(player, remainder(args, 1));
                case "promote" -> setStaff(player, remainder(args, 1), true);
                case "demote" -> setStaff(player, remainder(args, 1), false);
                case "transfer", "leader" -> transfer(player, remainder(args, 1));
                case "kick", "remove" -> kick(player, remainder(args, 1));
                case "leave" -> leave(player);
                case "chat" -> chat(player, remainder(args, 1));
                case "vault", "bank" -> vault(player);
                case "deposit" -> deposit(player, args.length >= 2 ? args[1] : "");
                case "withdraw" -> withdraw(player, remainder(args, 1));
                case "upgrade", "levelup" -> upgrade(player, args.length >= 2 ? args[1] : "");
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
        List<String> staff = clan.staff().stream().map(clan.members()::get).sorted().toList();
        List<String> members = clan.members().entrySet().stream()
                .filter(entry -> !entry.getKey().equals(clan.leader()) && !clan.staff().contains(entry.getKey()))
                .map(java.util.Map.Entry::getValue).sorted().toList();
        long online = clan.members().keySet().stream().filter(id -> Bukkit.getPlayer(id) != null).count();
        TextColor theme = clanColor(clan);
        player.sendMessage(divider(theme));
        player.sendMessage(Component.text("        [" + clan.name() + "]", theme, TextDecoration.BOLD));
        player.sendMessage(Component.text(" "));
        player.sendMessage(label("LEADER", leader));
        player.sendMessage(label("ONLINE", online + "/" + clan.members().size()));
        player.sendMessage(label("ROSTER", clan.members().size() + "/" + ClanStore.MAX_MEMBERS));
        player.sendMessage(label(
                "THEME",
                Component.text(String.format("#%06X", clan.themeColor()), theme)
        ));
        player.sendMessage(Component.text(" "));
        player.sendMessage(label("STAFF", staff.isEmpty() ? "None" : String.join(", ", staff)));
        player.sendMessage(label("MEMBERS", members.isEmpty() ? "None" : String.join(", ", members)));
        player.sendMessage(divider(theme));
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
                player.sendMessage(Component.text("  [" + clan.name() + "]", clanColor(clan), TextDecoration.BOLD)
                        .append(Component.text("  " + clan.members().size() + " members", NamedTextColor.GRAY)))
        );
        if (clans.isEmpty()) {
            player.sendMessage(Component.text("  No clans have been created yet.", NamedTextColor.GRAY));
        }
    }

    private void rename(Player player, String name) throws IOException {
        leaderClan(player);
        if (name.isBlank()) {
            throw new ClanStore.ClanException("Usage: /clans rename <new name>");
        }
        ClanStore.ClanView clan = store.rename(player.getUniqueId(), name);
        plugin.refreshClans();
        broadcast(clan, Component.text("The clan is now named " + clan.name() + ".", LIGHT_ORANGE));
    }

    /**
     * The icon is never drawn in game. It exists so the Discord clan leaderboard can
     * show something other than a default beside the clans at the top of it.
     */
    private void icon(Player player, String requestedIcon) throws IOException {
        if (requestedIcon.equalsIgnoreCase("clear") || requestedIcon.equalsIgnoreCase("reset")) {
            ClanStore.ClanView cleared = store.clearIcon(player.getUniqueId());
            broadcast(cleared, Component.text(
                    "Clan icon removed; the default returns on the leaderboard.",
                    NamedTextColor.GRAY
            ));
            return;
        }
        ClanStore.ClanView clan = store.setIcon(player.getUniqueId(), requestedIcon);
        broadcast(clan, Component.text("Clan icon updated.", NamedTextColor.GRAY));
        success(player, "It appears beside your clan on the Discord leaderboard.");
        if (ClanIcon.isExpiringDiscordLink(clan.icon())) {
            // Discord signs attachment links with an expiry, so this one will stop
            // resolving in a day or so and the clan would silently lose its icon.
            player.sendMessage(Component.text(
                    "Warning: Discord attachment links expire after about a day. "
                            + "Host the image somewhere permanent to keep it.",
                    NamedTextColor.GOLD
            ));
        }
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
        success(player, "Removed " + removedName + " from " + clan.name() + ".");
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            error(online, "You were removed from " + clan.name() + ".");
        }
    }

    private void leave(Player player) throws IOException {
        String clanName = store.leave(player.getUniqueId());
        plugin.refreshClans();
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
            if (online != null) {
                online.sendMessage(chat);
            }
        }
    }

    private void disband(Player player, String confirmation) throws IOException {
        ClanStore.ClanView clan = leaderClan(player);
        if (!confirmation.equalsIgnoreCase("confirm")) {
            if (!clan.vault().isEmpty()) {
                success(player, "The clan vault will be returned to you.");
            }
            throw new ClanStore.ClanException("This removes the clan permanently. Use /clans disband confirm.");
        }
        ClanStore.ClanView disbanded = store.disband(player.getUniqueId());
        // Members contributed these; destroying them on disband would be a support ticket.
        disbanded.vault().forEach((material, amount) -> {
            Material resolved = Material.matchMaterial(material);
            if (resolved != null) {
                give(player, resolved, amount);
            }
        });
        if (!disbanded.vault().isEmpty()) {
            success(player, "The clan vault was returned to you.");
        }
        plugin.refreshClans();
        for (UUID memberId : clan.members().keySet()) {
            Player online = Bukkit.getPlayer(memberId);
            if (online != null) {
                online.sendMessage(prefix().append(Component.text(
                        clan.name() + " was disbanded.", NamedTextColor.WHITE
                )));
            }
        }
    }

    private void vault(Player player) {
        ClanStore.ClanView clan = ownClan(player);
        TextColor theme = clanColor(clan);
        player.sendMessage(divider(theme));
        player.sendMessage(Component.text("        [" + clan.name() + "] ", theme, TextDecoration.BOLD)
                .append(badge(clan)));
        player.sendMessage(Component.text(" "));
        player.sendMessage(label("LEVEL", describeLevel(clan.level())));
        player.sendMessage(Component.text(" "));
        if (clan.vault().isEmpty()) {
            player.sendMessage(Component.text("  The vault is empty.", NamedTextColor.GRAY));
        } else {
            clan.vault().forEach((material, amount) -> player.sendMessage(
                    Component.text("  " + amount + "x ", NamedTextColor.WHITE)
                            .append(Component.text(
                                    ClanLevel.readableMaterial(material), LIGHT_ORANGE
                            ))
            ));
        }
        player.sendMessage(Component.text(" "));
        sendNextLevelCost(player, clan);
        player.sendMessage(divider(theme));
    }

    private void deposit(Player player, String requestedAmount) throws IOException {
        ClanStore.ClanView clan = ownClan(player);
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            throw new ClanStore.ClanException("Hold the materials you want to deposit.");
        }
        String material = held.getType().name();
        if (!ClanLevel.isDepositable(material)) {
            throw new ClanStore.ClanException(
                    ClanLevel.readableMaterial(material) + " is not a clan upgrade material."
            );
        }
        int amount = held.getAmount();
        if (!requestedAmount.isBlank()) {
            try {
                amount = Integer.parseInt(requestedAmount);
            } catch (NumberFormatException exception) {
                throw new ClanStore.ClanException("The amount must be a number.");
            }
            if (amount < 1 || amount > held.getAmount()) {
                throw new ClanStore.ClanException(
                        "You are holding " + held.getAmount() + "x "
                                + ClanLevel.readableMaterial(material) + "."
                );
            }
        }
        // Bank first, then take: a failed save must not cost anyone their items.
        ClanStore.ClanView updated = store.deposit(player.getUniqueId(), material, amount);
        held.setAmount(held.getAmount() - amount);
        broadcast(updated, Component.text(
                player.getName() + " deposited " + amount + "x "
                        + ClanLevel.readableMaterial(material) + " into the clan vault.",
                LIGHT_ORANGE
        ));
        sendNextLevelCost(player, updated);
    }

    private void withdraw(Player player, String arguments) throws IOException {
        ownClan(player);
        String[] parts = arguments.trim().split("\\s+");
        if (arguments.isBlank()) {
            throw new ClanStore.ClanException("Usage: /clans withdraw <material> [amount]");
        }
        String material = ClanLevel.normalizeMaterial(parts[0]);
        Material resolved = Material.matchMaterial(material);
        if (resolved == null) {
            throw new ClanStore.ClanException("No material is called " + parts[0] + ".");
        }
        int amount = 1;
        if (parts.length >= 2) {
            try {
                amount = Integer.parseInt(parts[1]);
            } catch (NumberFormatException exception) {
                throw new ClanStore.ClanException("The amount must be a number.");
            }
        }
        ClanStore.ClanView updated = store.withdraw(player.getUniqueId(), material, amount);
        give(player, resolved, amount);
        success(player, "Withdrew " + amount + "x " + ClanLevel.readableMaterial(material) + ".");
        sendNextLevelCost(player, updated);
    }

    private void upgrade(Player player, String confirmation) throws IOException {
        ClanStore.ClanView clan = leaderClan(player);
        Optional<Integer> next = clan.nextLevel();
        if (next.isEmpty()) {
            throw new ClanStore.ClanException("Your clan is already at the highest level.");
        }
        if (!confirmation.equalsIgnoreCase("confirm")) {
            TextColor theme = clanColor(clan);
            player.sendMessage(divider(theme));
            player.sendMessage(Component.text("        CLAN UPGRADE", theme, TextDecoration.BOLD));
            player.sendMessage(Component.text(" "));
            player.sendMessage(label("NOW", describeLevel(clan.level())));
            player.sendMessage(label("NEXT", describeLevel(next.get())));
            player.sendMessage(Component.text(" "));
            sendNextLevelCost(player, clan);
            player.sendMessage(Component.text(" "));
            player.sendMessage(Component.text("  Grants at that level:", NamedTextColor.GRAY));
            for (String line : perkLines(ClanLevel.perksFor(next.get()))) {
                player.sendMessage(Component.text("  " + line, NamedTextColor.WHITE));
            }
            player.sendMessage(Component.text(" "));
            player.sendMessage(Component.text(
                    "  Spends the clan vault. Use /clans upgrade confirm.", NamedTextColor.GRAY
            ));
            player.sendMessage(divider(theme));
            return;
        }
        ClanStore.ClanView upgraded = store.upgrade(player.getUniqueId());
        plugin.refreshClans();
        broadcast(upgraded, Component.text(
                "The clan reached level " + upgraded.level() + "! ", LIGHT_ORANGE
        ).append(badge(upgraded)));
        for (String line : perkLines(upgraded.perks())) {
            broadcast(upgraded, Component.text("  " + line, NamedTextColor.WHITE));
        }
    }

    /** What the next level costs and what the vault still lacks, or silence at the top. */
    private void sendNextLevelCost(Player player, ClanStore.ClanView clan) {
        Optional<Integer> next = clan.nextLevel();
        if (next.isEmpty()) {
            player.sendMessage(Component.text(
                    "  Your clan is at the highest level.", NamedTextColor.GRAY
            ));
            return;
        }
        int level = next.get();
        player.sendMessage(Component.text("  Level " + level + " costs:", NamedTextColor.GRAY));
        Map<String, Integer> missing = ClanLevel.shortfall(clan.vault(), level);
        for (ClanLevel.Cost cost : ClanLevel.costOf(level)) {
            int held = clan.vault().getOrDefault(cost.material(), 0);
            boolean covered = !missing.containsKey(cost.material());
            player.sendMessage(Component.text("  " + Math.min(held, cost.amount())
                            + "/" + cost.amount() + " ",
                            covered ? NamedTextColor.GREEN : NamedTextColor.RED)
                    .append(Component.text(
                            ClanLevel.readableMaterial(cost.material()), NamedTextColor.WHITE
                    )));
        }
        if (missing.isEmpty()) {
            player.sendMessage(Component.text(
                    "  The vault covers it. The leader can /clans upgrade confirm.",
                    NamedTextColor.GREEN
            ));
        }
    }

    private static List<String> perkLines(ClanLevel.Perks perks) {
        if (perks.isNone()) {
            return List.of("No perks yet.");
        }
        List<String> lines = new ArrayList<>();
        if (perks.extraHearts() > 0) {
            lines.add("+" + perks.extraHearts()
                    + (perks.extraHearts() == 1 ? " extra heart" : " extra hearts"));
        }
        addPercent(lines, perks.strength(), "strength");
        addPercent(lines, perks.saturation(), "saturation");
        addPercent(lines, perks.diggingSpeed(), "digging speed");
        addPercent(lines, perks.resistance(), "resistance");
        addPercent(lines, perks.speed(), "speed");
        return lines;
    }

    private static void addPercent(List<String> lines, double fraction, String label) {
        if (fraction > 0) {
            lines.add("+" + Math.round(fraction * 100) + "% " + label);
        }
    }

    private static String describeLevel(int level) {
        return level == 0 ? "Unranked" : "Level " + level;
    }

    /** Puts items in the player's inventory, dropping whatever will not fit. */
    private static void give(Player player, Material material, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            int stack = Math.min(remaining, material.getMaxStackSize());
            ItemStack items = new ItemStack(material, stack);
            player.getInventory().addItem(items).values().forEach(overflow ->
                    player.getWorld().dropItemNaturally(player.getLocation(), overflow));
            remaining -= stack;
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
            player.sendMessage(help("/clans vault", "The clan vault and the next level"));
            player.sendMessage(help("/clans deposit [amount]", "Bank the materials you hold"));
            if (role == ClanStore.ClanRole.LEADER) {
                player.sendMessage(help("/clans withdraw <material> [amount]", "Take from the vault"));
                player.sendMessage(help("/clans upgrade", "Spend the vault on the next level"));
            }
            if (role == ClanStore.ClanRole.LEADER || role == ClanStore.ClanRole.STAFF) {
                player.sendMessage(help("/clans invite <player>", "Invite an online player"));
                player.sendMessage(help("/clans kick <player>", "Remove a clan member"));
                player.sendMessage(help("/clans icon <url>", "Set your Discord leaderboard icon"));
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
                "  Clan protection: members of the same clan cannot damage one another.",
                NamedTextColor.GRAY
        ));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClanDamage(EntityDamageByEntityEvent event) {
        Player attacker = attackingPlayer(event);
        if (attacker == null || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        Optional<ClanStore.ClanView> attackerClan = store.clanOf(attacker.getUniqueId());
        Optional<ClanStore.ClanView> victimClan = store.clanOf(victim.getUniqueId());
        if (attackerClan.isPresent()
                && victimClan.isPresent()
                && attackerClan.get().id().equals(victimClan.get().id())) {
            event.setCancelled(true);
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
        if (!(sender instanceof Player player) || args.length != 2) {
            return List.of();
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!availableSubcommands(player).contains(canonicalAction(action))) {
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
        if (List.of("color", "colour", "theme").contains(action)) {
            return partial(args[1], THEME_COLORS);
        }
        if (action.equals("disband")) {
            return partial(args[1], List.of("confirm"));
        }
        if (action.equals("upgrade") || action.equals("levelup")) {
            return partial(args[1], List.of("confirm"));
        }
        if (action.equals("withdraw")) {
            // Only what this clan actually banked, so the list never hints at a
            // material the player has not seen.
            return store.clanOf(player.getUniqueId())
                    .map(clan -> partial(args[1], clan.vault().keySet().stream()
                            .map(material -> material.toLowerCase(Locale.ROOT))
                            .toList()))
                    .orElse(List.of());
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
            case "bank" -> "vault";
            case "levelup" -> "upgrade";
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

    private static void success(Player player, String message) {
        player.sendMessage(prefix().append(Component.text(message, NamedTextColor.WHITE)));
    }

    private static void error(Player player, String message) {
        player.sendMessage(prefix().append(Component.text(message, NamedTextColor.RED)));
    }

    private static Component prefix() {
        return Component.text("CLANS » ", ORANGE, TextDecoration.BOLD);
    }

    private static Component label(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.WHITE));
    }

    private static Component label(String label, Component value) {
        return Component.text(label + ": ", NamedTextColor.GRAY).append(value);
    }

    private static Component help(String command, String description) {
        return Component.text("  " + command, LIGHT_ORANGE)
                .append(Component.text(" — " + description, NamedTextColor.GRAY));
    }

    private static Component divider(TextColor color) {
        return Component.text("━━━━━━━━━━━━━━━━━━━━━━━━", color);
    }

    private static TextColor clanColor(ClanStore.ClanView clan) {
        return TextColor.color(clan.themeColor());
    }

    private static Component clanTag(ClanStore.ClanView clan) {
        Component tag = Component.text("[" + clan.name() + "] ", clanColor(clan), TextDecoration.BOLD);
        if (clan.level() <= 0) {
            return tag;
        }
        return tag.append(badge(clan)).append(Component.text(" "));
    }

    /** The stars that stand in for writing the clan's level beside every name. */
    private static Component badge(ClanStore.ClanView clan) {
        return Component.text(
                ClanLevel.badge(clan.level()),
                TextColor.color(ClanLevel.badgeColor(clan.level())),
                TextDecoration.BOLD
        );
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
