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

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The {@code /settings} panel: every toggle a player controls for themselves. */
final class PlayerSettingsService implements CommandExecutor, TabCompleter {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private static final TextColor GOLD = TextColor.color(0xFFB52E);

    private final MGXAccessBridge plugin;
    private final PlayerSettingsStore store;
    private final PlayerMenuService menus;

    PlayerSettingsService(MGXAccessBridge plugin, PlayerSettingsStore store, PlayerMenuService menus) {
        this.plugin = plugin;
        this.store = store;
        this.menus = menus;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        args = CommandArgs.withoutEchoedSender(sender.getName(), args);
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is available to players only.");
            return true;
        }
        if (args.length == 0) {
            // The menu is the panel now; the named-setting form below stays for
            // anyone who prefers typing, and for tab completion.
            menus.openSettings(player);
            return true;
        }
        PlayerSettingsStore.Setting setting = PlayerSettingsStore.Setting.fromKey(args[0])
                .filter(PlayerSettingsStore.Setting::inSettingsPanel)
                .orElse(null);
        if (setting == null) {
            player.sendMessage(Component.text("That is not a setting. Use ", NamedTextColor.RED)
                    .append(Component.text("/settings", GOLD))
                    .append(Component.text(" to see them all.", NamedTextColor.RED)));
            return true;
        }
        try {
            boolean enabled = store.toggle(player.getUniqueId(), setting);
            plugin.refreshClans();
            player.sendMessage(Component.text(setting.label() + " ", NamedTextColor.WHITE)
                    .append(enabled
                            ? Component.text("shown", NamedTextColor.GREEN, TextDecoration.BOLD)
                            : Component.text("hidden", NamedTextColor.RED, TextDecoration.BOLD)));
        } catch (UncheckedIOException exception) {
            plugin.getLogger().warning("Could not save a player setting: " + exception.getMessage());
            player.sendMessage(Component.text(
                    "That setting could not be saved. Please try again.", NamedTextColor.RED
            ));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (PlayerSettingsStore.Setting setting : PlayerSettingsStore.Setting.values()) {
            if (setting.inSettingsPanel() && setting.key().startsWith(prefix)) {
                matches.add(setting.key());
            }
        }
        return matches;
    }
}
