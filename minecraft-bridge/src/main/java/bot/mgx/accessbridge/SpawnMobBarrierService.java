package bot.mgx.accessbridge;

import io.papermc.paper.event.entity.EntityMoveEvent;
import org.bukkit.World;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

/** Keeps every zombie out of the spawn building, including mobs spawned outside it. */
final class SpawnMobBarrierService implements Listener {
    private final MGXAccessBridge plugin;
    private final UUID worldId;
    private final SpawnMobBarrier bounds;
    private final boolean enabled;
    private BukkitTask sweep;

    SpawnMobBarrierService(MGXAccessBridge plugin) {
        this.plugin = plugin;
        this.worldId = plugin.getServer().getWorlds().getFirst().getUID();
        this.enabled = plugin.getConfig().getBoolean("spawn.zombie-barrier.enabled", true);
        this.bounds = new SpawnMobBarrier(
                plugin.getConfig().getInt("spawn.zombie-barrier.min-x", -30),
                plugin.getConfig().getInt("spawn.zombie-barrier.max-x", 30),
                plugin.getConfig().getInt("spawn.zombie-barrier.min-z", -32),
                plugin.getConfig().getInt("spawn.zombie-barrier.max-z", 30)
        );
    }

    void start() {
        if (!enabled || sweep != null) {
            return;
        }
        sweep = plugin.getServer().getScheduler().runTaskTimer(plugin, this::removeInside, 1L, 20L);
    }

    void stop() {
        if (sweep != null) {
            sweep.cancel();
            sweep = null;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (enabled && isProtected(event.getEntity().getWorld(), event.getLocation().getX(),
                event.getLocation().getZ()) && event.getEntity() instanceof Zombie) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onZombieMove(EntityMoveEvent event) {
        if (!enabled || !(event.getEntity() instanceof Zombie)
                || !event.getEntity().getWorld().getUID().equals(worldId)) {
            return;
        }
        if (bounds.enters(
                event.getFrom().getX(), event.getFrom().getZ(),
                event.getTo().getX(), event.getTo().getZ()
        )) {
            event.setCancelled(true);
        }
    }

    private void removeInside() {
        World world = plugin.getServer().getWorld(worldId);
        if (world == null) {
            return;
        }
        for (Zombie zombie : world.getEntitiesByClass(Zombie.class)) {
            if (bounds.contains(zombie.getX(), zombie.getZ())) {
                zombie.remove();
            }
        }
    }

    private boolean isProtected(World world, double x, double z) {
        return world.getUID().equals(worldId) && bounds.contains(x, z);
    }
}
