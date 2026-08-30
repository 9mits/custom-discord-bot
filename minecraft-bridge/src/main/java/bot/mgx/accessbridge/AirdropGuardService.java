package bot.mgx.accessbridge;

import org.bukkit.Location;
import org.bukkit.Material;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * The Amethyst Airdrop's garrison.
 *
 * <p>Every airdrop lands defended, and the rarer the drop the heavier the guard. The
 * mobs are the ordinary Amethyst variants, so they drop crate keys like any other; the
 * Amethyst Golem exists only here, which is what keeps it off the natural spawn table.
 *
 * <p>The garrison also gives the drop a way to be lost. Once a player has arrived and
 * the fight is live, walking away hands the drop to the mobs: after
 * {@link #CLAIM_SECONDS} with nobody inside the ring, the guards claim it and the whole
 * airdrop goes up in particles. Leaving the timer dormant until someone first shows up
 * is what stops a drop that nobody has had time to reach from being lost on its own.
 */
final class AirdropGuardService {
    /** How far out the guards stand, and how far in a player counts as engaged. */
    private static final double INNER_RING = 6d;
    private static final double OUTER_RING = 14d;
    static final double ENGAGE_RADIUS = 28d;
    /** How long the guards need alone with the drop before they take it. */
    static final int CLAIM_SECONDS = 20;
    private static final long PERIOD_TICKS = 10L;
    private static final int VERTICAL_SEARCH = 6;

    /** How many of each mob stand over a drop of a given rarity. */
    record Garrison(int zombies, int skeletons, int golems) {
        int total() {
            return zombies + skeletons + golems;
        }
    }

    private final MGXAccessBridge plugin;
    private final AmethystMobService mobs;
    private final RandomGenerator random;

    private final List<UUID> guards = new ArrayList<>();
    private Location post;
    private BukkitTask task;
    private Runnable claimedCallback;
    private boolean engaged;
    private int unattendedTicks;

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
    void deploy(Location chest, AirdropCatalog.Rarity rarity, Runnable onClaimed) {
        dismiss();
        post = chest.clone();
        claimedCallback = onClaimed;
        engaged = false;
        unattendedTicks = 0;

        Garrison garrison = garrisonFor(rarity);
        spawnAll(EntityType.HUSK, garrison.zombies());
        spawnAll(EntityType.STRAY, garrison.skeletons());
        spawnAll(EntityType.IRON_GOLEM, garrison.golems());

        task = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::patrol, PERIOD_TICKS, PERIOD_TICKS
        );
    }

    /** Sends the garrison away in a puff of amethyst, however the drop ended. */
    void dismiss() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID id : List.copyOf(guards)) {
            Entity entity = plugin.getServer().getEntity(id);
            if (entity != null) {
                burst(entity.getLocation());
                entity.remove();
            }
        }
        guards.clear();
        post = null;
        claimedCallback = null;
        engaged = false;
        unattendedTicks = 0;
    }

    int standing() {
        return guards.size();
    }

    private void spawnAll(EntityType type, int count) {
        for (int index = 0; index < count; index++) {
            Location where = ring();
            if (where == null) {
                continue;
            }
            LivingEntity guard = mobs.deploy(where, type);
            guard.setPersistent(false);
            guards.add(guard.getUniqueId());
        }
    }

    /** A standing spot on solid ground somewhere in the ring around the chest. */
    private Location ring() {
        World world = post.getWorld();
        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2d;
            double distance = INNER_RING + random.nextDouble() * (OUTER_RING - INNER_RING);
            int x = post.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = post.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            Location standing = ground(world, x, post.getBlockY(), z);
            if (standing != null) {
                return standing;
            }
        }
        return null;
    }

    private Location ground(World world, int x, int startY, int z) {
        for (int offset = 0; offset <= VERTICAL_SEARCH; offset++) {
            for (int direction : new int[] {1, -1}) {
                int y = startY + offset * direction;
                if (standable(world, x, y, z)) {
                    return new Location(world, x + 0.5d, y, z + 0.5d);
                }
                if (offset == 0) {
                    break;
                }
            }
        }
        return null;
    }

    private boolean standable(World world, int x, int y, int z) {
        Material floor = world.getBlockAt(x, y - 1, z).getType();
        return floor.isSolid()
                && world.getBlockAt(x, y, z).getType() == Material.AIR
                && world.getBlockAt(x, y + 1, z).getType() == Material.AIR;
    }

    private void patrol() {
        if (post == null) {
            return;
        }
        guards.removeIf(id -> {
            Entity entity = plugin.getServer().getEntity(id);
            return entity == null || !entity.isValid();
        });
        if (guards.isEmpty()) {
            return;
        }

        Player nearest = nearestPlayer();
        if (nearest != null) {
            engaged = true;
            unattendedTicks = 0;
            for (UUID id : guards) {
                if (plugin.getServer().getEntity(id) instanceof Mob guard
                        && guard.getTarget() == null) {
                    guard.setTarget(nearest);
                }
            }
            return;
        }
        if (!engaged) {
            return;
        }
        unattendedTicks += (int) PERIOD_TICKS;
        if (unattendedTicks >= CLAIM_SECONDS * 20) {
            Runnable claimed = claimedCallback;
            claimedCallback = null;
            if (claimed != null) {
                claimed.run();
            }
        }
    }

    private Player nearestPlayer() {
        Player nearest = null;
        double best = ENGAGE_RADIUS * ENGAGE_RADIUS;
        for (Player player : post.getWorld().getPlayers()) {
            if (player.isDead() || !player.getGameMode().name().equals("SURVIVAL")) {
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

    private void burst(Location where) {
        where.getWorld().spawnParticle(
                Particle.DUST, where.clone().add(0d, 1d, 0d), 40,
                0.4d, 0.8d, 0.4d, 0d,
                new Particle.DustOptions(org.bukkit.Color.fromRGB(181, 108, 255), 1.5f)
        );
        where.getWorld().playSound(where, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.7f, 1.3f);
    }
}
