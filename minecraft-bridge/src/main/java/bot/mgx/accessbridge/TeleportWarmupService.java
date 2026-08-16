package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Five-second stand-still wait before warp, home, tpa, and the other TP commands. */
final class TeleportWarmupService implements Listener {
    private static final int WARMUP_SECONDS = 5;
    private static final Set<String> COMMANDS = Set.of(
            "warp", "warps", "home", "homes", "spawn", "back",
            "tpa", "tpahere", "tpaccept", "tpyes", "tphere", "tp",
            "tpr", "rtp", "top", "jump", "world"
    );

    private final MGXAccessBridge plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Set<UUID> releasing = ConcurrentHashMap.newKeySet();

    TeleportWarmupService(MGXAccessBridge plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (releasing.remove(player.getUniqueId())) {
            return;
        }
        String message = event.getMessage();
        if (message.length() < 2 || message.charAt(0) != '/') {
            return;
        }
        String label = message.substring(1).split(" +", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        if (!COMMANDS.contains(label)) {
            return;
        }
        event.setCancelled(true);
        cancel(player, false);
        Location origin = player.getLocation().clone();
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pending.remove(player.getUniqueId());
            if (!player.isOnline()) {
                return;
            }
            releasing.add(player.getUniqueId());
            player.performCommand(message.substring(1));
        }, WARMUP_SECONDS * 20L);
        pending.put(player.getUniqueId(), new Pending(origin, task));
        player.sendMessage(Component.text(
                "Teleporting in " + WARMUP_SECONDS + " seconds. Do not move.",
                NamedTextColor.GOLD
        ));
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        cancel(event.getPlayer(), true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer(), false);
    }

    private void cancel(Player player, boolean moved) {
        Pending current = pending.remove(player.getUniqueId());
        if (current == null) {
            return;
        }
        current.task().cancel();
        if (moved && player.isOnline()) {
            player.sendMessage(Component.text("Teleport cancelled because you moved.", NamedTextColor.RED));
        }
    }

    private record Pending(Location origin, BukkitTask task) {
    }
}
