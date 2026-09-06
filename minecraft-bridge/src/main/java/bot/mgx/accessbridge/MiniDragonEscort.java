package bot.mgx.accessbridge;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Two small dragons circling the wearer of the Amethyst Dragon Ascendant.
 *
 * <p>Each one is a Dragon Head item model on a display entity, flown along the aura's
 * own music clock, with the wings and tail that turn a head into a dragon drawn as
 * particles by the aura around the position this class reports back.
 *
 * <p>It was a real {@code EnderDragon} shrunk with the generic scale attribute, which
 * does not work: the Ender Dragon is drawn by its own boss renderer rather than the one
 * every scalable mob uses, so the attribute has no visual effect, and its hitbox parts
 * are a fixed size regardless. What that actually produced was a full-size Ender Dragon
 * parked over the wearer's base.
 *
 * <p>A display entity has no AI, no health, no hitbox, no boss bar and no capacity to
 * damage anything or eat a wall, so the failure that mattered cannot recur: the worst a
 * bug here can leave behind is a floating head, which the sweeps below clear.
 */
final class MiniDragonEscort implements Listener {
    /** Marks an escort so a sweep can recognise one without holding the map. */
    static final String TAG = "mgx_mini_dragon";

    private static final int ESCORTS = 2;
    private static final double ORBIT_RADIUS = 2.35d;
    private static final double ORBIT_SPEED = 0.045d;
    /** Chosen so the model's wingspan lands near two blocks beside a 1.8-block player. */
    private static final float MODEL_SCALE = 0.5f;
    /**
     * Three wing positions, cycled to make a wingbeat.
     *
     * <p>A display shows one static model, and an item model element takes a single
     * fixed rotation, so a wing cannot be animated in place. Swapping between three
     * whole models is what a beat actually is here. It also keeps every part of the
     * dragon in one model, which is what a previous attempt at separate wing entities
     * failed to keep aligned.
     */
    private static final String[] FLAP = {
            "mgx:mini_dragon_mid", "mgx:mini_dragon_up",
            "mgx:mini_dragon_mid", "mgx:mini_dragon_down"
    };
    /** Aura frames per wing position. Four ticks a frame is a beat, not a flicker. */
    private static final int FLAP_FRAMES = 2;
    /** Matches the aura's own tick period so the flight interpolates instead of stepping. */
    private static final int TELEPORT_TICKS = 2;

    private final MGXAccessBridge plugin;
    private final Map<UUID, List<UUID>> escorts = new HashMap<>();
    /** Who is currently being kept from seeing one owner's escort, so it is only diffed. */
    private final Map<UUID, Set<UUID>> hiddenFrom = new HashMap<>();
    /** Owners whose escort has already failed to spawn, so the log is not repeated. */
    private final Set<UUID> spawnFailures = new HashSet<>();
    /** Which wing position each escort is currently showing, so it is only swapped on change. */
    private final Map<UUID, String> wearing = new HashMap<>();

    MiniDragonEscort(MGXAccessBridge plugin) {
        this.plugin = plugin;
    }

    /** Any escort left behind by a crash or a reload, swept before the first one is spawned. */
    void start() {
        for (World world : plugin.getServer().getWorlds()) {
            sweep(world.getEntities());
        }
    }

    void stop() {
        for (UUID ownerId : List.copyOf(escorts.keySet())) {
            release(ownerId);
        }
    }

    /**
     * Chunks holding an orphan are not necessarily loaded when the plugin starts, so the
     * sweep repeats as they arrive. This is also what removes the full-size Ender Dragons
     * an earlier build of this class left behind in the world.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        sweep(event.getEntities());
    }

    private void sweep(Collection<Entity> entities) {
        for (Entity entity : List.copyOf(entities)) {
            if (entity.getScoreboardTags().contains(TAG) && !isTracked(entity.getUniqueId())) {
                entity.remove();
            }
        }
    }

    private boolean isTracked(UUID entityId) {
        for (List<UUID> ids : escorts.values()) {
            if (ids.contains(entityId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Keeps two escorts alive around the owner and flies them one frame on.
     *
     * @param phaseSeconds the music timeline position, so the orbit rides the same beat
     *                     as the rest of the aura instead of drifting against it
     * @param energy       0..1 loudness, which opens the orbit out and lifts the pair
     * @return where each dragon now is, facing along its flight, so the aura can draw
     *         its wings and tail
     */
    List<Location> follow(
            Player owner,
            double phaseSeconds,
            double energy,
            boolean moving,
            Collection<Player> allowedViewers
    ) {
        if (!canHost(owner)) {
            release(owner.getUniqueId());
            return List.of();
        }
        List<UUID> ids = escorts.computeIfAbsent(owner.getUniqueId(), ignored -> new ArrayList<>());
        // Moving pulls the pair in tight against the wearer so a sprint does not drag two
        // dragons through the scenery, matching how the aura itself narrows in motion.
        double radius = (moving ? 1.35d : ORBIT_RADIUS) * (0.86d + energy * 0.3d);
        List<Location> heads = new ArrayList<>();
        for (int index = 0; index < ESCORTS; index++) {
            ItemDisplay dragon = resolve(owner, ids, index);
            if (dragon == null) {
                continue;
            }
            double angle = phaseSeconds * ORBIT_SPEED * Math.PI * 2d + index * Math.PI;
            double height = 1.4d + Math.sin(phaseSeconds * 1.15d + index * Math.PI) * 0.34d
                    + energy * 0.45d;
            Location seat = owner.getLocation().clone().add(
                    Math.cos(angle) * radius, height, Math.sin(angle) * radius
            );
            // Face along the orbit, so the pair reads as flying a circuit rather than
            // being dragged sideways through it.
            seat.setDirection(new Vector(-Math.sin(angle), 0d, Math.cos(angle)));
            dragon.teleport(seat);
            String wanted = FLAP[(int) Math.floorMod(
                    (long) (phaseSeconds * 20d / FLAP_FRAMES) + index * 2L, FLAP.length)];
            if (!wanted.equals(wearing.put(dragon.getUniqueId(), wanted))) {
                dragon.setItemStack(modelItem(wanted));
            }
            heads.add(seat);
        }
        ids.removeIf(id -> resolveById(owner.getWorld(), id) == null);
        applyVisibility(owner, ids, allowedViewers);
        return List.copyOf(heads);
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
                ItemDisplay dragon = resolveById(owner.getWorld(), id);
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
        spawnFailures.remove(ownerId);
        List<UUID> ids = escorts.remove(ownerId);
        if (ids == null) {
            return;
        }
        for (UUID id : ids) {
            Entity entity = plugin.getServer().getEntity(id);
            if (entity != null) {
                entity.remove();
            }
            wearing.remove(id);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        release(event.getPlayer().getUniqueId());
    }

    /** The verification lobby is sealed and shows nobody anything they did not come for. */
    private static boolean canHost(Player owner) {
        return owner != null
                && owner.isOnline()
                && !VerificationLobbyService.isLobbyWorld(owner.getWorld());
    }

    private ItemDisplay resolve(Player owner, List<UUID> ids, int index) {
        while (ids.size() <= index) {
            ids.add(null);
        }
        ItemDisplay existing = resolveById(owner.getWorld(), ids.get(index));
        if (existing != null) {
            return existing;
        }
        ItemDisplay spawned = spawn(owner);
        ids.set(index, spawned == null ? null : spawned.getUniqueId());
        return spawned;
    }

    /** Any item will do: the item model component replaces what is drawn entirely. */
    private static ItemStack modelItem(String model) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            NamespacedKey key = NamespacedKey.fromString(model);
            if (key != null) {
                meta.setItemModel(key);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemDisplay resolveById(World world, UUID id) {
        if (id == null) {
            return null;
        }
        Entity entity = plugin.getServer().getEntity(id);
        if (!(entity instanceof ItemDisplay dragon) || !dragon.isValid()
                || dragon.getWorld() != world) {
            return null;
        }
        return dragon;
    }

    private ItemDisplay spawn(Player owner) {
        try {
            return owner.getWorld().spawn(
                    owner.getLocation().add(0d, 2d, 0d), ItemDisplay.class, dragon -> {
                        dragon.addScoreboardTag(TAG);
                        dragon.setItemStack(modelItem(FLAP[0]));
                        dragon.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                        dragon.setBillboard(Display.Billboard.FIXED);
                        dragon.setTransformation(new Transformation(
                                new Vector3f(0f, 0f, 0f),
                                new AxisAngle4f(0f, 0f, 0f, 1f),
                                new Vector3f(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE),
                                new AxisAngle4f(0f, 0f, 0f, 1f)
                        ));
                        // Without this the head jumps between orbit positions once per
                        // aura frame instead of flying between them.
                        dragon.setTeleportDuration(TELEPORT_TICKS);
                        dragon.setPersistent(false);
                    });
        } catch (RuntimeException exception) {
            // An escort is decoration. Whatever a future server build does to entity
            // spawning, the aura it belongs to has to keep drawing, so this degrades to
            // no escort rather than propagating. Reported once per owner: the caller
            // retries every tick, and a stack trace ten times a second is its own outage.
            if (spawnFailures.add(owner.getUniqueId())) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Could not spawn a mini Dragon escort for " + owner.getName()
                                + "; the aura continues without one.",
                        exception
                );
            }
            return null;
        }
    }
}
