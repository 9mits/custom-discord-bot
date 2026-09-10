package bot.mgx.accessbridge;

import io.papermc.paper.event.entity.EntityMoveEvent;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.util.UUID;

/**
 * The protected spawn region: 100x100 centred on the locked spawn at 0,0.
 *
 * <p>Inside it no hostile mob spawns, hostile mobs walking in from outside are stopped at
 * the edge, blocks cannot be broken or placed by non-operators, and PvP is refused. The
 * mob rules are enforced three ways because none alone is enough: the spawn event is
 * cancelled, movement across the boundary is blocked, and a one-second sweep clears
 * anything that arrived by teleport, mount, or a spawn path that skipped the event.
 *
 * <p>Mob *entry* is why this lives here rather than in WorldGuard, which the production
 * server does run: WorldGuard can deny spawning but has no flag for keeping a mob that
 * spawned outside from walking in.
 *
 * <p>Amethyst mobs are exempt from the mob rules. {@code Husk extends Zombie}, so an
 * earlier zombie-only version of this silently cancelled every Amethyst Zombie an airdrop
 * garrison placed near spawn and swept away any that got through. The region is for
 * ambient spawns; a garrison is deliberate and temporary.
 */
final class SpawnMobBarrierService implements Listener {
    private final MGXAccessBridge plugin;
    private final AmethystMobService amethystMobs;
    private final UUID worldId;
    private BukkitTask sweep;

    SpawnMobBarrierService(MGXAccessBridge plugin, AmethystMobService amethystMobs) {
        this.plugin = plugin;
        this.amethystMobs = amethystMobs;
        this.worldId = plugin.getServer().getWorlds().getFirst().getUID();
    }

    private boolean enabled() {
        return plugin.gameVariables().bool("spawn.protection.enabled");
    }

    /** Rebuilt per check so moving the box does not need a restart. */
    private SpawnMobBarrier bounds() {
        GameVariableStore variables = plugin.gameVariables();
        return new SpawnMobBarrier(
                variables.integer("spawn.protection.min-x"),
                variables.integer("spawn.protection.max-x"),
                variables.integer("spawn.protection.min-z"),
                variables.integer("spawn.protection.max-z")
        );
    }

    void start() {
        if (!enabled() || sweep != null) {
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
        if (enabled() && isProtected(event.getEntity().getWorld(), event.getLocation().getX(),
                event.getLocation().getZ()) && hostile(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** Nothing hostile is broken, built on or fought over inside the region. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (denyBuild(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (denyBuild(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /**
     * No PvP inside the region. Checked on the victim's position, so stepping over the
     * line does not let an attacker keep swinging from outside.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!enabled() || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        Entity source = event.getDamager();
        if (source instanceof Projectile projectile
                && projectile.getShooter() instanceof Entity shooter) {
            source = shooter;
        }
        if (source instanceof Player
                && isProtected(victim.getWorld(), victim.getX(), victim.getZ())) {
            event.setCancelled(true);
        }
    }

    private boolean denyBuild(Player player, Block block) {
        return enabled()
                && !player.isOp()
                && isProtected(block.getWorld(), block.getX() + 0.5d, block.getZ() + 0.5d);
    }

    /**
     * Amethyst mobs are exempt everywhere in here. Husk extends Zombie, so the barrier was
     * silently cancelling every Amethyst Zombie an airdrop garrison placed near spawn.
     */
    private boolean hostile(Entity entity) {
        return entity instanceof Monster && !amethystMobs.isAmethystMob(entity);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onZombieMove(EntityMoveEvent event) {
        if (!enabled() || !hostile(event.getEntity())
                || !event.getEntity().getWorld().getUID().equals(worldId)) {
            return;
        }
        if (bounds().enters(
                event.getFrom().getX(), event.getFrom().getZ(),
                event.getTo().getX(), event.getTo().getZ()
        )) {
            event.setCancelled(true);
        }
    }

    /**
     * Clears whatever got inside the box by teleport, mount or a spawn path that
     * skipped the event.
     *
     * <p>Scoped to the region rather than the world. {@code getEntitiesByClass} walks
     * every entity the world has loaded, so a busy overworld paid for a full monster
     * scan every second to look at a 100x100 box; the bounded query only touches the
     * chunks the box actually covers. The bounds are read once per sweep too — they were
     * being rebuilt inside the loop, which meant four game-variable lookups for every
     * monster on the server, every second.
     */
    private void removeInside() {
        World world = plugin.getServer().getWorld(worldId);
        if (world == null) {
            return;
        }
        SpawnMobBarrier box = bounds();
        BoundingBox region = new BoundingBox(
                box.minX(), world.getMinHeight(), box.minZ(),
                box.maxX() + 1d, world.getMaxHeight(), box.maxZ() + 1d
        );
        for (Entity entity : world.getNearbyEntities(region, Monster.class::isInstance)) {
            Monster monster = (Monster) entity;
            if (box.contains(monster.getX(), monster.getZ()) && hostile(monster)) {
                monster.remove();
            }
        }
    }

    private boolean isProtected(World world, double x, double z) {
        return world.getUID().equals(worldId) && bounds().contains(x, z);
    }
}
