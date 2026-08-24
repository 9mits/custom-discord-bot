package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Five seconds of standing still immediately before a command teleport lands. */
final class TeleportWarmupService implements Listener {
    static final int WARMUP_SECONDS = 5;

    private final MGXAccessBridge plugin;
    private final Map<UUID, BukkitTask> pending = new ConcurrentHashMap<>();
    private final Set<UUID> releasing = ConcurrentHashMap.newKeySet();

    TeleportWarmupService(MGXAccessBridge plugin) {
        this.plugin = plugin;
    }

    /**
     * Delays the teleport itself, not the command which might eventually request one.
     * This distinction lets /tpa be sent freely and makes /tpaccept warm up the
     * requester rather than the player who merely accepted it.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (releasing.remove(playerId)) {
            return;
        }
        if (!shouldWarmup(event.getCause())) {
            cancel(player, false);
            return;
        }
        event.setCancelled(true);
        begin(player, event.getTo(), event.getCause());
    }

    void begin(Player player, Location destination) {
        begin(player, destination, PlayerTeleportEvent.TeleportCause.COMMAND);
    }

    private void begin(
            Player player,
            Location destination,
            PlayerTeleportEvent.TeleportCause cause
    ) {
        cancel(player, false);
        Location target = destination.clone();
        int[] seconds = {WARMUP_SECONDS};
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                cancel(player, false);
                return;
            }
            if (seconds[0] <= 0) {
                BukkitTask finished = pending.remove(player.getUniqueId());
                if (finished != null) {
                    finished.cancel();
                }
                player.sendActionBar(Component.text("Teleporting now...", NamedTextColor.GREEN));
                releasing.add(player.getUniqueId());
                if (!player.teleport(target, cause)) {
                    releasing.remove(player.getUniqueId());
                    player.sendActionBar(Component.text("Teleport failed.", NamedTextColor.RED));
                }
                return;
            }
            player.sendActionBar(Component.text(
                    "Teleporting in " + seconds[0] + (seconds[0] == 1 ? " second" : " seconds")
                            + "  •  Do not move",
                    NamedTextColor.GOLD
            ));
            seconds[0]--;
        }, 0L, 20L);
        pending.put(player.getUniqueId(), task);
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
        releasing.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            cancel(player, true);
        }
    }

    void stop() {
        for (BukkitTask task : pending.values()) {
            task.cancel();
        }
        pending.clear();
        releasing.clear();
    }

    static boolean shouldWarmup(PlayerTeleportEvent.TeleportCause cause) {
        return cause == PlayerTeleportEvent.TeleportCause.COMMAND;
    }

    private void cancel(Player player, boolean interrupted) {
        BukkitTask current = pending.remove(player.getUniqueId());
        if (current == null) {
            return;
        }
        current.cancel();
        if (interrupted && player.isOnline()) {
            player.sendActionBar(Component.text("Teleport cancelled.", NamedTextColor.RED));
        }
    }
}
