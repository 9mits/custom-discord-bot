package bot.mgx.accessbridge;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * The Amethyst Airdrop's garrison.
 *
 * <p>Every airdrop lands defended, and the rarer the drop the heavier the guard. The
 * mobs are the ordinary Amethyst variants, so they drop crate keys like any other; the
 * Amethyst Golem exists only here, which is what keeps it off the natural spawn table.
 *
 * <p>Guards defend the drop for its full visible lifetime. They never run a hidden
 * abandonment timer: the Airdrop countdown is the only clock that can expire it.
 */
final class AirdropGuardService {
    /** How far out the guards stand, and how far in a player counts as engaged. */
    private static final double INNER_RING = 6d;
    private static final double OUTER_RING = 14d;
    /** Guards hunt further out than the ring they defend, so nobody picks them off safely. */
    private static final double HUNT_RADIUS = 40d;
    private static final double FOLLOW_RANGE = 48d;
    private static final double SPEED_MULTIPLIER = 1.2d;
    /** Live tuning; the constants above stay the defaults and stand alone in tests. */
    private static volatile java.util.function.ToDoubleFunction<String> tuning = key -> Double.NaN;

    static void tuningSource(java.util.function.ToDoubleFunction<String> source) {
        if (source != null) {
            tuning = source;
        }
    }

    private static double tuned(String key, double fallback) {
        double value = tuning.applyAsDouble(key);
        return Double.isNaN(value) ? fallback : value;
    }

    private static final long PERIOD_TICKS = 5L;
    private static final int VERTICAL_SEARCH = 10;
    /** An Amethyst Golem is nearly three blocks tall. */
    private static final int GOLEM_CLEARANCE = 3;

    /** How many of each mob stand over a drop of a given rarity. */
    record Garrison(int zombies, int skeletons, int golems) {
        int total() {
            return zombies + skeletons + golems;
        }
    }

    private final MGXAccessBridge plugin;
    private final AmethystMobService mobs;
    private final RandomGenerator random;

    /**
     * One drop's garrison. Several drops can stand at once, so everything a patrol
     * reads about "the" drop - where it is, who is guarding it, how long it has been
     * alone - belongs to that drop rather than to the service.
     */
    private static final class Post {
        private final Location post;
        private final List<UUID> guards = new ArrayList<>();
        private Post(Location post) {
            this.post = post;
        }
    }

    private final Map<UUID, Post> posts = new LinkedHashMap<>();
    /** One patrol for every post: the work is per-post, the schedule is not. */
    private BukkitTask task;

    AirdropGuardService(MGXAccessBridge plugin, AmethystMobService mobs) {
        this(plugin, mobs, ThreadLocalRandom.current());
    }

    AirdropGuardService(
            MGXAccessBridge plugin, AmethystMobService mobs, RandomGenerator random
    ) {
        this.plugin = plugin;
        this.mobs = mobs;
        this.random = random;
    }

    static Garrison garrisonFor(AirdropCatalog.Rarity rarity) {
        return switch (rarity) {
            case COMMON -> new Garrison(4, 4, 0);
            case RARE -> new Garrison(7, 7, 1);
            case LEGENDARY -> new Garrison(11, 11, 3);
            case MYTHIC -> new Garrison(15, 15, 5);
        };
    }

    /** Stands the garrison up around a freshly landed drop. */
    void deploy(UUID dropId, Location chest, AirdropCatalog.Rarity rarity) {
        dismiss(dropId);
        Post standing = new Post(chest.clone());
        posts.put(dropId, standing);

        Garrison garrison = garrisonFor(rarity);
        int zombies = spawnAll(standing, EntityType.HUSK, garrison.zombies());
        int skeletons = spawnAll(standing, EntityType.STRAY, garrison.skeletons());
        int golems = spawnAll(standing, EntityType.IRON_GOLEM, garrison.golems());
        // Reported every time, because a garrison that quietly lands short is invisible
        // in game — it just looks like the mobs were never written.
        String placed = zombies + "/" + garrison.zombies() + " zombies, "
                + skeletons + "/" + garrison.skeletons() + " skeletons, "
                + golems + "/" + garrison.golems() + " golems";
        if (zombies + skeletons + golems < garrison.total()) {
            plugin.getLogger().warning(
                    "Amethyst garrison landed short at " + describe(standing.post) + ": " + placed
            );
        } else {
            plugin.getLogger().info(
                    "Amethyst garrison standing at " + describe(standing.post) + ": " + placed
            );
        }

        if (task == null) {
            task = plugin.getServer().getScheduler().runTaskTimer(
                    plugin, this::patrol, PERIOD_TICKS, PERIOD_TICKS
            );
        }
    }

    /** Sends one drop's garrison away in a puff of amethyst, however that drop ended. */
    void dismiss(UUID dropId) {
        Post standing = posts.remove(dropId);
        if (standing != null) {
            clear(standing);
        }
        if (posts.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Clears every garrison, for a shutdown or a full reset. */
    void dismissAll() {
        for (UUID dropId : List.copyOf(posts.keySet())) {
            dismiss(dropId);
        }
    }

    private void clear(Post standing) {
        for (UUID id : List.copyOf(standing.guards)) {
            Entity entity = plugin.getServer().getEntity(id);
            if (entity != null) {
                burst(entity.getLocation());
                entity.remove();
            }
        }
        standing.guards.clear();
    }

    int standing() {
        return posts.values().stream().mapToInt(post -> post.guards.size()).sum();
    }

    private int spawnAll(Post standing, EntityType type, int count) {
        int clearance = type == EntityType.IRON_GOLEM ? GOLEM_CLEARANCE : 2;
        int placed = 0;
        for (int index = 0; index < count; index++) {
            Location where = ring(standing.post, clearance);
            if (where == null) {
                // Better a guard standing on the drop than a garrison quietly short of
                // the count the rarity promised.
                where = standing.post.clone().add(0d, 1d, 0d);
            }
            LivingEntity guard = mobs.deploy(where, type);
            if (guard == null) {
                continue;
            }
            // Guards must not wander off or despawn while the drop is still standing.
            guard.setPersistent(true);
            guard.setRemoveWhenFarAway(false);
            scale(guard, Attribute.FOLLOW_RANGE, tuned("airdrop.guard.follow-range", FOLLOW_RANGE));
            multiply(guard, Attribute.MOVEMENT_SPEED, tuned("airdrop.guard.speed", SPEED_MULTIPLIER));
            if (guard instanceof Mob mob) {
                mob.setAware(true);
            }
            standing.guards.add(guard.getUniqueId());
            placed++;
        }
        return placed;
    }

    private static String describe(Location where) {
        return "X " + where.getBlockX() + " Y " + where.getBlockY()
                + " Z " + where.getBlockZ();
    }

    /** A standing spot on solid ground somewhere in the ring around the chest. */
    private Location ring(Location post, int clearance) {
        World world = post.getWorld();
        for (int attempt = 0; attempt < 64; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2d;
            double inner = tuned("airdrop.guard.inner-ring", INNER_RING);
            double outer = tuned("airdrop.guard.outer-ring", OUTER_RING);
            double distance = inner + random.nextDouble() * (outer - inner);
            int x = post.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = post.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            Location standing = ground(world, x, post.getBlockY(), z, clearance);
            if (standing != null) {
                return standing;
            }
        }
        return null;
    }

    private Location ground(World world, int x, int startY, int z, int clearance) {
        for (int offset = 0; offset <= VERTICAL_SEARCH; offset++) {
            for (int direction : new int[] {1, -1}) {
                int y = startY + offset * direction;
                if (standable(world, x, y, z, clearance)) {
                    return new Location(world, x + 0.5d, y, z + 0.5d);
                }
                if (offset == 0) {
                    break;
                }
            }
        }
        return null;
    }

    /**
     * Grass, flowers and snow layers are not air, and an Amethyst Golem is nearly three
     * blocks tall. Demanding bare air over exactly two blocks rejected most of the
     * overworld, so garrisons landed a fraction of the size their rarity promised.
     */
    private boolean standable(World world, int x, int y, int z, int clearance) {
        Block floor = world.getBlockAt(x, y - 1, z);
        if (!floor.getType().isSolid() || floor.isLiquid()) {
            return false;
        }
        for (int step = 0; step < clearance; step++) {
            Block block = world.getBlockAt(x, y + step, z);
            if (!block.isPassable() || block.isLiquid()) {
                return false;
            }
        }
        return true;
    }

    private void patrol() {
        // Over a copy of the keys, not the entries: a post's claim callback removes the
        // drop, which dismisses that post mid-loop.
        for (UUID dropId : List.copyOf(posts.keySet())) {
            Post standing = posts.get(dropId);
            if (standing != null) {
                patrolPost(standing);
            }
        }
    }

    private void patrolPost(Post standing) {
        standing.guards.removeIf(id -> {
            Entity entity = plugin.getServer().getEntity(id);
            return entity == null || !entity.isValid();
        });
        if (standing.guards.isEmpty()) {
            return;
        }
        Location post = standing.post;

        Player hunted = nearestTarget(post, tuned("airdrop.guard.hunt-radius", HUNT_RADIUS));
        if (hunted != null) {
            for (UUID id : standing.guards) {
                if (!(plugin.getServer().getEntity(id) instanceof Mob guard)) {
                    continue;
                }
                // Anything that is not a live player is a distraction from the drop, and
                // a guard that has lost its target must pick the player straight back up.
                if (!(guard.getTarget() instanceof Player player) || player.isDead()) {
                    guard.setTarget(hunted);
                }
            }
        }
    }

    /** Who the guards will chase: mobs do not hunt creative or spectating players. */
    private Player nearestTarget(Location post, double radius) {
        return nearest(post, radius, player -> !player.isDead()
                && (player.getGameMode() == GameMode.SURVIVAL
                        || player.getGameMode() == GameMode.ADVENTURE));
    }

    private Player nearest(Location post, double radius, Predicate<Player> eligible) {
        Player nearest = null;
        double best = radius * radius;
        for (Player player : post.getWorld().getPlayers()) {
            if (!eligible.test(player)) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(post);
            if (distance <= best) {
                best = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    /** Guards spot and chase from much further out than an ordinary mob. */
    private static void scale(LivingEntity mob, Attribute attribute, double value) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(Math.max(instance.getBaseValue(), value));
        }
    }

    private static void multiply(LivingEntity mob, Attribute attribute, double factor) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(instance.getBaseValue() * factor);
        }
    }

    private void burst(Location where) {
        where.getWorld().spawnParticle(
                Particle.DUST, where.clone().add(0d, 1d, 0d), 40,
                0.4d, 0.8d, 0.4d, 0d,
                new Particle.DustOptions(org.bukkit.Color.fromRGB(181, 108, 255), 1.5f)
        );
        where.getWorld().playSound(where, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.7f, 1.3f);
    }
}
