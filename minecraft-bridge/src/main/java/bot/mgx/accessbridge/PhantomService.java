package bot.mgx.accessbridge;

import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.entity.Phantom;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.WorldLoadEvent;

/** Phantoms are gone. Membranes are a shop item. */
final class PhantomService implements Listener {
    private final MGXAccessBridge plugin;

    PhantomService(MGXAccessBridge plugin) {
        this.plugin = plugin;
    }

    void start() {
        for (World world : plugin.getServer().getWorlds()) {
            silence(world);
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        silence(event.getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getEntity() instanceof Phantom) {
            event.setCancelled(true);
        }
    }

    private void silence(World world) {
        Boolean spawn = world.getGameRuleValue(GameRules.SPAWN_PHANTOMS);
        if (spawn == null || spawn) {
            world.setGameRule(GameRules.SPAWN_PHANTOMS, false);
            plugin.getLogger().info("Disabled phantom spawning in " + world.getName() + ".");
        }
        int removed = 0;
        for (Phantom phantom : world.getEntitiesByClass(Phantom.class)) {
            phantom.remove();
            removed++;
        }
        if (removed > 0) {
            plugin.getLogger().info("Removed " + removed + " phantom(s) from " + world.getName() + ".");
        }
    }
}
