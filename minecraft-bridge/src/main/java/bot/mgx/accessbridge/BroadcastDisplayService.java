package bot.mgx.accessbridge;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/** Turns Essentials broadcasts into a clean, temporary banner above the game view. */
final class BroadcastDisplayService implements Listener {
    private static final Set<String> GLOBAL_COMMANDS = Set.of(
            "broadcast", "bc", "ebc", "bcast", "ebcast", "ebroadcast", "shout", "eshout"
    );
    private static final Set<String> WORLD_COMMANDS = Set.of(
            "broadcastworld", "bcw", "ebcw", "bcastw", "ebcastw", "ebroadcastworld",
            "shoutworld", "eshoutworld"
    );
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
        if (!event.getPlayer().hasPermission(parsed.permission()) && !event.getPlayer().isOp()) {
            return;
        }
        event.setCancelled(true);
        show(event.getPlayer(), parsed);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        Parsed parsed = parse(event.getCommand());
        if (parsed == null) {
            return;
        }
        event.setCancelled(true);
        show(event.getSender(), parsed);
    }

    void stop() {
        clear();
    }

    private void show(CommandSender sender, Parsed parsed) {
        Collection<? extends Player> audience = plugin.getServer().getOnlinePlayers();
        if (parsed.worldOnly()) {
            if (parsed.worldName().isBlank() || parsed.message().isBlank()) {
                sender.sendMessage(Component.text(
                        "Usage: /broadcastworld <world> <message>.",
                        NamedTextColor.RED
                ));
                return;
            }
            World world = findWorld(parsed.worldName());
            if (world == null) {
                sender.sendMessage(Component.text(
                        "No world named '" + parsed.worldName() + "' exists.",
                        NamedTextColor.RED
                ));
                return;
            }
            audience = world.getPlayers();
        } else if (parsed.message().isBlank()) {
            sender.sendMessage(Component.text("A broadcast message is required.", NamedTextColor.RED));
            return;
        }
        clear();
        current = BossBar.bossBar(
                Component.text(parsed.message(), NamedTextColor.WHITE, TextDecoration.BOLD),
                1f,
                BossBar.Color.YELLOW,
                BossBar.Overlay.PROGRESS
        );
        for (Player player : audience) {
            player.showBossBar(current);
        }
        removal = plugin.getServer().getScheduler().runTaskLater(plugin, this::clear, DISPLAY_TICKS);
    }

    private World findWorld(String name) {
        World exact = plugin.getServer().getWorld(name);
        if (exact != null) {
            return exact;
        }
        for (World world : plugin.getServer().getWorlds()) {
            if (world.getName().equalsIgnoreCase(name)) {
                return world;
            }
        }
        return null;
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

    static Parsed parse(String raw) {
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
        String arguments = parts.length == 2 ? parts[1].strip() : "";
        if (GLOBAL_COMMANDS.contains(label)) {
            return new Parsed(false, "", arguments);
        }
        if (!WORLD_COMMANDS.contains(label)) {
            return null;
        }
        String[] worldParts = arguments.split("\\s+", 2);
        return new Parsed(
                true,
                worldParts.length > 0 ? worldParts[0].strip() : "",
                worldParts.length == 2 ? worldParts[1].strip() : ""
        );
    }

    record Parsed(boolean worldOnly, String worldName, String message) {
        String permission() {
            return worldOnly ? "essentials.broadcastworld" : "essentials.broadcast";
        }
    }
}
