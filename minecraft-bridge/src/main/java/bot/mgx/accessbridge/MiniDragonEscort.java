package bot.mgx.accessbridge;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Two real, shrunken Ender Dragons circling the wearer of the Amethyst Dragon Ascendant.
 *
 * <p>Particles cannot beat a wing, so the escort is the actual {@code EnderDragon} model
 * scaled down through the generic scale attribute — that is what buys the real flap
 * animation, the head turn, and the silhouette everybody already recognises.
 *
 * <p>Everything an Ender Dragon otherwise <em>does</em> is taken away rather than trusted
 * to stay quiet. The entity has no AI, no gravity, no boss bar, no sound, no collision and
 * no drops, it is invulnerable, and the three ways a dragon can still reach the world —
 * chewing through walls, hurting whoever it passes, and picking a target — are cancelled
 * by id here rather than left to a game rule. It is a costume, so it is treated as one.
 */
final class MiniDragonEscort implements Listener {
    /** Marks an escort so a guard can recognise one without holding the map. */
    static final String TAG = "mgx_mini_dragon";

    private static final int ESCORTS = 2;
    private static final double ORBIT_RADIUS = 2.35d;
    private static final double ORBIT_SPEED = 0.045d;
    private static final double SCALE = 0.11d;

    private final MGXAccessBridge plugin;
    private final Map<UUID, List<UUID>> escorts = new HashMap<>();
    /** Who is currently being kept from seeing one owner's escort, so it is only diffed. */
    private final Map<UUID, Set<UUID>> hiddenFrom = new HashMap<>();

    MiniDragonEscort(MGXAccessBridge plugin) {
        this.plugin = plugin;
    }

    /**
     * Any escort left behind by a crash or a reload, swept before the first one is spawned.
     *
     * <p>An orphan is a full-size hostile boss in somebody's overworld, so this runs on
     * start rather than being left to the removal paths that a clean shutdown would use.
     */
    void start() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getScoreboardTags().contains(TAG)) {
                    entity.remove();
                }
            }
        }
    }

    void stop() {
        for (UUID ownerId : List.copyOf(escorts.keySet())) {
            release(ownerId);
        }
    }

    /**
     * Keeps exactly two escorts alive around the owner and flies them one frame on.
     *
     * @param phaseSeconds the music timeline position, so the orbit rides the same beat
     *                     as the rest of the aura instead of drifting against it
     * @param energy       0..1 loudness, which opens the orbit out and lifts the pair
     */
    void follow(
            Player owner,
            double phaseSeconds,
            double energy,
            boolean moving,
            Collection<Player> allowedViewers
    ) {
        if (!canHost(owner)) {
            release(owner.getUniqueId());
            return;
        }
        List<UUID> ids = escorts.computeIfAbsent(owner.getUniqueId(), ignored -> new ArrayList<>());
        // Moving pulls the pair in tight against the wearer so a sprint does not drag two
        // dragons through the scenery, matching how the aura itself narrows in motion.
        double radius = (moving ? 1.35d : ORBIT_RADIUS) * (0.86d + energy * 0.3d);
        for (int index = 0; index < ESCORTS; index++) {
            EnderDragon dragon = resolve(owner, ids, index);
            if (dragon == null) {
                continue;
            }
            double angle = phaseSeconds * ORBIT_SPEED * Math.PI * 2d
                    + index * Math.PI;
            double height = 1.55d + Math.sin(phaseSeconds * 1.15d + index * Math.PI) * 0.42d
                    + energy * 0.55d;
            Location centre = owner.getLocation();
            Location seat = centre.clone().add(
                    Math.cos(angle) * radius, height, Math.sin(angle) * radius
            );
            // Face along the orbit, so the pair reads as flying a circuit rather than
            // being dragged sideways through it.
            Vector heading = new Vector(-Math.sin(angle), 0d, Math.cos(angle));
            seat.setDirection(heading);
            dragon.teleport(seat);
        }
        ids.removeIf(id -> resolveById(owner.getWorld(), id) == null);
        applyVisibility(owner, ids, allowedViewers);
    }

    /**
     * An escort is a real entity, so a player who turned cosmetics off cannot simply be
     * skipped the way a particle call skips them — they have to stop being sent it.
     *
     * <p>Only the difference is applied. Calling show/hide for every viewer every frame
     * would rewrite the tracker's per-plugin sets ten times a second for no change.
     */
    private void applyVisibility(
            Player owner, List<UUID> ids, Collection<Player> allowedViewers
    ) {
        Set<UUID> allowed = new HashSet<>();
        for (Player viewer : allowedViewers) {
            allowed.add(viewer.getUniqueId());
        }
        Set<UUID> hidden = hiddenFrom.computeIfAbsent(
                owner.getUniqueId(), ignored -> new HashSet<>()
        );
        for (Player viewer : owner.getWorld().getPlayers()) {
            boolean shouldHide = !allowed.contains(viewer.getUniqueId());
            if (shouldHide == hidden.contains(viewer.getUniqueId())) {
                continue;
            }
            for (UUID id : ids) {
                EnderDragon dragon = resolveById(owner.getWorld(), id);
                if (dragon == null) {
                    continue;
                }
                if (shouldHide) {
                    viewer.hideEntity(plugin, dragon);
                } else {
                    viewer.showEntity(plugin, dragon);
                }
            }
            if (shouldHide) {
                hidden.add(viewer.getUniqueId());
            } else {
                hidden.remove(viewer.getUniqueId());
            }
        }
    }

    /** Removes both escorts. Safe to call for a player that never had one. */
    void release(UUID ownerId) {
        hiddenFrom.remove(ownerId);
        List<UUID> ids = escorts.remove(ownerId);
        if (ids == null) {
            return;
        }
        for (UUID id : ids) {
            Entity entity = plugin.getServer().getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        release(event.getPlayer().getUniqueId());
    }

    /** An Ender Dragon eats every block it flies through; this is what stops it. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onExplode(EntityExplodeEvent event) {
        if (isEscort(event.getEntity())) {
            event.setCancelled(true);
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChangeBlock(EntityChangeBlockEvent event) {
        if (isEscort(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** Nothing can hurt an escort, and an escort can hurt nobody. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (isEscort(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (isEscort(event.getDamager()) || isEscort(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTarget(EntityTargetEvent event) {
        if (isEscort(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    static boolean isEscort(Entity entity) {
        return entity != null && entity.getScoreboardTags().contains(TAG);
    }

    /**
     * The End is the one place a live Ender Dragon means something to the server.
     *
     * <p>Spawning a costume dragon into a world that runs a dragon battle risks the
     * battle adopting it, so the wearer keeps the particle formation there instead.
     */
    private static boolean canHost(Player owner) {
        return owner != null
                && owner.isOnline()
                && owner.getWorld().getEnvironment() != World.Environment.THE_END
                && !VerificationLobbyService.isLobbyWorld(owner.getWorld());
    }

    private EnderDragon resolve(Player owner, List<UUID> ids, int index) {
        while (ids.size() <= index) {
            ids.add(null);
        }
        EnderDragon existing = resolveById(owner.getWorld(), ids.get(index));
        if (existing != null) {
            return existing;
        }
        EnderDragon spawned = spawn(owner);
        ids.set(index, spawned == null ? null : spawned.getUniqueId());
        return spawned;
    }

    private EnderDragon resolveById(World world, UUID id) {
        if (id == null) {
            return null;
        }
        Entity entity = plugin.getServer().getEntity(id);
        if (!(entity instanceof EnderDragon dragon) || !dragon.isValid()
                || dragon.getWorld() != world) {
            return null;
        }
        return dragon;
    }

    private EnderDragon spawn(Player owner) {
        try {
            return owner.getWorld().spawn(
                    owner.getLocation().add(0d, 2d, 0d), EnderDragon.class, dragon -> {
                        dragon.addScoreboardTag(TAG);
                        dragon.setPhase(EnderDragon.Phase.HOVER);
                        dragon.setAI(false);
                        dragon.setGravity(false);
                        dragon.setSilent(true);
                        dragon.setInvulnerable(true);
                        dragon.setCollidable(false);
                        dragon.setPersistent(false);
                        dragon.setRemoveWhenFarAway(true);
                        // The vanilla dragon bar belongs to the End fight. An aura that
                        // put one on every nearby screen would read as a live boss.
                        dragon.getBossBar().setVisible(false);
                        dragon.getBossBar().removeAll();
                        AttributeInstance scale = dragon.getAttribute(Attribute.SCALE);
                        if (scale != null) {
                            scale.setBaseValue(SCALE);
                        }
                    });
        } catch (IllegalArgumentException | IllegalStateException exception) {
            plugin.getLogger().warning(
                    "Could not spawn a mini Dragon escort for " + owner.getName()
                            + ": " + exception.getMessage()
            );
            return null;
        }
    }
}
