package bot.mgx.accessbridge;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.Set;

/** Turns Essentials broadcasts into a clean, temporary banner above the game view. */
final class BroadcastDisplayService implements Listener {
    private static final Set<String> COMMANDS = Set.of("broadcast", "bc", "ebroadcast");
    private static final long DISPLAY_TICKS = 8L * 20L;

    private final MGXAccessBridge plugin;
    private BossBar current;
    private BukkitTask removal;

    BroadcastDisplayService(MGXAccessBridge plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Parsed parsed = parse(event.getMessage());
        if (parsed == null) {
            return;
        }
        if (!event.getPlayer().hasPermission("essentials.broadcast") && !event.getPlayer().isOp()) {
            return;
        }
        event.setCancelled(true);
        show(event.getPlayer(), parsed.message());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        Parsed parsed = parse(event.getCommand());
        if (parsed == null) {
            return;
        }
        event.setCancelled(true);
        show(event.getSender(), parsed.message());
    }

    void stop() {
        clear();
    }

    private void show(CommandSender sender, String message) {
        if (message.isBlank()) {
            sender.sendMessage(Component.text("A broadcast message is required.", NamedTextColor.RED));
            return;
        }
        clear();
        current = BossBar.bossBar(
                Component.text(message, NamedTextColor.WHITE, TextDecoration.BOLD),
                1f,
                BossBar.Color.YELLOW,
                BossBar.Overlay.PROGRESS
        );
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.showBossBar(current);
        }
        removal = plugin.getServer().getScheduler().runTaskLater(plugin, this::clear, DISPLAY_TICKS);
    }

    private void clear() {
        if (removal != null) {
            removal.cancel();
            removal = null;
        }
        if (current != null) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                player.hideBossBar(current);
            }
            current = null;
        }
    }

    private static Parsed parse(String raw) {
        String command = raw == null ? "" : raw.strip();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        String[] parts = command.split("\\s+", 2);
        String label = parts[0].toLowerCase(Locale.ROOT);
        int namespace = label.indexOf(':');
        if (namespace >= 0) {
            label = label.substring(namespace + 1);
        }
        return COMMANDS.contains(label) ? new Parsed(parts.length == 2 ? parts[1].strip() : "") : null;
    }

    private record Parsed(String message) {
    }
}
