package bot.mgx.accessbridge;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Sound;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Turns Essentials broadcasts into a clean, temporary banner above the game view. */
final class BroadcastDisplayService implements Listener {
    private static final Set<String> GLOBAL_COMMANDS = Set.of(
            "broadcast", "bc", "ebc", "bcast", "ebcast", "ebroadcast", "shout", "eshout"
    );
    private static final Set<String> WORLD_COMMANDS = Set.of(
            "broadcastworld", "bcw", "ebcw", "bcastw", "ebcastw", "ebroadcastworld",
            "shoutworld", "eshoutworld"
    );
    private static final int DISPLAY_SECONDS = 10;
    private static final long DISPLAY_TICKS = DISPLAY_SECONDS * 20L;
    private static final TextColor BROADCAST_RED = TextColor.color(0xFF3B30);
    /** Bold strikethrough spaces render as a solid rule, so chat cannot be mistaken for talk. */
    private static final String RULE = " ".repeat(44);

    private final MGXAccessBridge plugin;
    private final PlayerSettingsStore settings;
    // One entry per live broadcast. Broadcasts deliberately stack rather than
    // replacing each other, so a second announcement adds a second bar.
    private final Map<BossBar, BukkitTask> active = new ConcurrentHashMap<>();

    BroadcastDisplayService(MGXAccessBridge plugin, PlayerSettingsStore settings) {
        this.plugin = plugin;
        this.settings = settings;
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
        BossBar bar = BossBar.bossBar(
                Component.text("BROADCAST: ", BROADCAST_RED, TextDecoration.BOLD)
                        .append(Component.text(
                                parsed.message(), NamedTextColor.WHITE, TextDecoration.BOLD
                        )),
                1f,
                BossBar.Color.RED,
                BossBar.Overlay.PROGRESS
        );
        for (Player player : audience) {
            if (PlayerBroadcast.wants(
                    settings, PlayerSettingsStore.Setting.BROADCAST_BAR, player
            )) {
                player.showBossBar(bar);
            }
        }
        announceBanner(audience, "BROADCAST", Component.text(
                "  " + parsed.message(), NamedTextColor.WHITE, TextDecoration.BOLD
        ));
        // The bar drains over its lifetime, so the emptying track is the countdown to
        // the message disappearing.
        long[] elapsed = {0L};
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            elapsed[0]++;
            if (elapsed[0] >= DISPLAY_TICKS) {
                retire(bar);
                return;
            }
            bar.progress(Math.max(0f, 1f - (float) elapsed[0] / DISPLAY_TICKS));
        }, 1L, 1L);
        active.put(bar, task);
    }

    /**
     * The bar alone is missable, so the same announcement lands in chat as a bordered
     * red block with a bell, well clear of ordinary conversation.
     */
    void announceBanner(Collection<? extends Player> audience, String headingLabel, Component body) {
        Component rule = Component.text(RULE, BROADCAST_RED)
                .decoration(TextDecoration.STRIKETHROUGH, true)
                .decoration(TextDecoration.BOLD, true);
        Component heading = Component.text(
                "  ▶ " + headingLabel + " ◀", BROADCAST_RED, TextDecoration.BOLD
        );
        for (Player player : audience) {
            player.sendMessage(Component.empty());
            player.sendMessage(rule);
            player.sendMessage(heading);
            player.sendMessage(body);
            player.sendMessage(rule);
            player.sendMessage(Component.empty());
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.2f);
        }
    }

    private void retire(BossBar bar) {
        BukkitTask task = active.remove(bar);
        if (task != null) {
            task.cancel();
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.hideBossBar(bar);
        }
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
        for (BossBar bar : Set.copyOf(active.keySet())) {
            retire(bar);
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
