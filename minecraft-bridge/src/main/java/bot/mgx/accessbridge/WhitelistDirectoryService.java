package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

/** The in-game /whitelisted command: every approved player and their Discord name. */
final class WhitelistDirectoryService implements CommandExecutor, TabCompleter {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private static final TextColor BLURPLE = TextColor.color(0x5865F2);
    private static final int PAGE_SIZE = 10;

    private final WhitelistDirectory directory;
    private final PlayerMenuService menus;

    WhitelistDirectoryService(WhitelistDirectory directory, PlayerMenuService menus) {
        this.directory = directory;
        this.menus = menus;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        args = CommandArgs.withoutEchoedSender(sender.getName(), args);
        if (!directory.synced()) {
            sender.sendMessage(Component.text(
                    "The whitelist directory has not synced from Discord yet. Try again shortly.",
                    NamedTextColor.GRAY
            ));
            return true;
        }
        if (sender instanceof org.bukkit.entity.Player player) {
            int page = 1;
            if (args.length >= 1) {
                try {
                    page = Math.max(1, Integer.parseInt(args[0]));
                } catch (NumberFormatException ignored) {
                    page = 1;
                }
            }
            menus.openWhitelist(player, page);
            return true;
        }
        // Console has no inventory, so it keeps the text listing.
        List<WhitelistDirectory.Entry> entries = directory.entries();
        if (entries.isEmpty()) {
            sender.sendMessage(Component.text("Nobody is whitelisted yet.", NamedTextColor.GRAY));
            return true;
        }
        int pages = (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int page = 1;
        if (args.length > 0) {
            try {
                page = Math.max(1, Math.min(Integer.parseInt(args[0]), pages));
            } catch (NumberFormatException ignored) {
                page = 1;
            }
        }
        sender.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY));
        sender.sendMessage(Component.empty()
                .append(Component.text("WHITELISTED PLAYERS", ORANGE, TextDecoration.BOLD))
                .append(Component.text("  " + entries.size() + " total", NamedTextColor.GRAY)));
        for (int index = (page - 1) * PAGE_SIZE; index < Math.min(page * PAGE_SIZE, entries.size()); index++) {
            WhitelistDirectory.Entry entry = entries.get(index);
            Component line = Component.text("› ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(entry.username(), NamedTextColor.WHITE));
            if (!entry.discordUsername().isBlank()) {
                line = line.append(Component.text("  @" + entry.discordUsername(), BLURPLE));
            }
            line = line.append(Component.text(
                    "  " + entry.edition().toLowerCase(Locale.ROOT),
                    NamedTextColor.GRAY
            ));
            sender.sendMessage(line);
        }
        if (pages > 1) {
            sender.sendMessage(Component.text(
                    "Page " + page + "/" + pages + " — /whitelisted <page>",
                    NamedTextColor.GRAY
            ));
        }
        sender.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
