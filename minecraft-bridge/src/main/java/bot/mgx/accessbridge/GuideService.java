package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class GuideService implements CommandExecutor, TabCompleter {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private static final TextColor GOLD = TextColor.color(0xFFB52E);
    private static final List<String> GUIDE_PAGES = List.of("levels", "clans", "commands");

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Server guides are available to players in Minecraft.");
            return true;
        }

        String page = switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "perks" -> "levels";
            case "discord" -> "discord";
            default -> args.length == 0 ? "overview" : args[0].toLowerCase(Locale.ROOT);
        };
        switch (page) {
            case "overview" -> overview(player);
            case "levels", "level", "perks" -> levels(player);
            case "clans", "clan" -> clans(player);
            case "commands", "command" -> commands(player);
            case "discord" -> discord(player);
            default -> {
                error(player, "Unknown guide page. Use /guide to see the available topics.");
                return true;
            }
        }
        return true;
    }

    private static void overview(Player player) {
        title(player, "SERVER GUIDE");
        player.sendMessage(guideLink("/guide levels", "Discord levels and Minecraft perks", NamedTextColor.AQUA));
        player.sendMessage(guideLink("/guide clans", "Clan creation, identity, and protection", GOLD));
        player.sendMessage(guideLink("/guide commands", "Useful custom server commands", NamedTextColor.GREEN));
        player.sendMessage(guideLink("/discord", "Community, support, and announcements", ORANGE));
        footer(player);
    }

    private static void levels(Player player) {
        title(player, "DISCORD LEVEL PERKS");
        player.sendMessage(body("Chat naturally in the Mysterious SMP X Discord to earn XP and level roles."));
        player.sendMessage(body("Milestone roles sync to Minecraft automatically after Discord awards them."));
        player.sendMessage(Component.empty());
        player.sendMessage(section("MILESTONES", NamedTextColor.AQUA));
        player.sendMessage(milestone(5, "+1 bonus heart"));
        player.sendMessage(milestone(10, "+2 total bonus hearts"));
        player.sendMessage(milestone(20, "+3 total bonus hearts"));
        player.sendMessage(milestone(30, "+4 total bonus hearts"));
        player.sendMessage(milestone(40, "+5 total bonus hearts"));
        player.sendMessage(milestone(50, "+5% direct combat damage; no additional heart"));
        player.sendMessage(Component.empty());
        player.sendMessage(body("Your active level, hearts, and level-50 power appear on the scoreboard."));
        footer(player);
    }

    private static void clans(Player player) {
        title(player, "CLAN GUIDE");
        player.sendMessage(body("A clan gives members one shared identity across the server."));
        player.sendMessage(Component.empty());
        player.sendMessage(detail("IDENTITY", "The clan name is also its 2-6 character tag.", GOLD));
        player.sendMessage(detail("THEME", "Leaders can choose a named color or custom hex color.", NamedTextColor.LIGHT_PURPLE));
        player.sendMessage(detail("VISIBILITY", "Your tag appears in chat, nametags, Tab, and the scoreboard.", NamedTextColor.AQUA));
        player.sendMessage(detail("PROTECTION", "Members of the same clan cannot damage one another.", NamedTextColor.GREEN));
        player.sendMessage(detail(
                "MANAGEMENT",
                "Leaders manage settings and staff; leaders and staff can invite or remove members.",
                NamedTextColor.YELLOW
        ));
        player.sendMessage(Component.empty());
        player.sendMessage(guideLink("/clan help", "View the commands available to you", GOLD));
        footer(player);
    }

    private static void commands(Player player) {
        title(player, "USEFUL COMMANDS");
        player.sendMessage(guideLink("/clan", "Open your available clan commands", GOLD));
        player.sendMessage(guideLink("/claninfo [name]", "Open a clan information card", NamedTextColor.AQUA));
        player.sendMessage(guideLink("/perks", "View Discord level rewards", NamedTextColor.LIGHT_PURPLE));
        player.sendMessage(guideLink("/discord", "Open the community invite", ORANGE));
        player.sendMessage(guideLink("/guide", "Return to the main server guide", NamedTextColor.WHITE));
        footer(player);
    }

    private static void discord(Player player) {
        title(player, "COMMUNITY DISCORD");
        player.sendMessage(body("Join for server news, support, applications, level progression, and community chat."));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("discord.gg/mgx", ORANGE, TextDecoration.BOLD)
                .clickEvent(ClickEvent.openUrl("https://discord.gg/mgx"))
                .hoverEvent(HoverEvent.showText(Component.text("Open the Mysterious SMP X Discord", NamedTextColor.GRAY))));
        footer(player);
    }

    private static void title(Player player, String page) {
        player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY));
        player.sendMessage(Component.empty()
                .append(Component.text("MYSTERIOUS", ORANGE, TextDecoration.BOLD))
                .append(Component.text(" SMP X", GOLD, TextDecoration.BOLD)));
        player.sendMessage(Component.text(page, NamedTextColor.GRAY));
        player.sendMessage(Component.empty());
    }

    private static Component section(String label, TextColor color) {
        return Component.text(label, color, TextDecoration.BOLD);
    }

    private static Component body(String text) {
        return Component.text(text, NamedTextColor.GRAY);
    }

    private static Component milestone(int level, String reward) {
        return Component.text("  LEVEL " + level, NamedTextColor.AQUA)
                .append(Component.text("  ›  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(reward, level == 50 ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.RED));
    }

    private static Component detail(String label, String description, TextColor color) {
        return Component.empty()
                .append(Component.text(label + "  ", color, TextDecoration.BOLD))
                .append(Component.text(description, NamedTextColor.GRAY));
    }

    private static Component guideLink(String command, String description, TextColor color) {
        int anglePlaceholder = command.indexOf('<');
        int squarePlaceholder = command.indexOf('[');
        int placeholder = anglePlaceholder < 0
                ? squarePlaceholder
                : squarePlaceholder < 0 ? anglePlaceholder : Math.min(anglePlaceholder, squarePlaceholder);
        String suggested = placeholder < 0 ? command : command.substring(0, placeholder).stripTrailing();
        return Component.empty()
                .append(Component.text("› ", NamedTextColor.DARK_GRAY))
                .append(Component.text(command, color)
                        .clickEvent(ClickEvent.suggestCommand(suggested + " "))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to prepare this command", NamedTextColor.GRAY))))
                .append(Component.text("  —  " + description, NamedTextColor.GRAY));
    }

    private static void footer(Player player) {
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("discord.gg/mgx", ORANGE));
        player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY));
    }

    private static void error(Player player, String message) {
        player.sendMessage(Component.empty()
                .append(Component.text("GUIDE  ›  ", ORANGE, TextDecoration.BOLD))
                .append(Component.text(message, NamedTextColor.RED)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("guide") || args.length != 1) {
            return List.of();
        }
        ArrayList<String> results = new ArrayList<>();
        StringUtil.copyPartialMatches(args[0], GUIDE_PAGES, results);
        results.sort(String.CASE_INSENSITIVE_ORDER);
        return results;
    }
}
