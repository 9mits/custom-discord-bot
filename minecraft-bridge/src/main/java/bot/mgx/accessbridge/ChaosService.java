package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.CreeperPowerEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Theatrical, strictly temporary operator events.
 *
 * <p>The rule every effect obeys: nothing it does may outlive it. Scale changes
 * are <em>transient</em> attribute modifiers, which Minecraft never writes to
 * the player file, so not even a crash mid-effect can leave somebody the wrong
 * size. A floor of lava is a {@link Player#sendBlockChange} lie told to one
 * client — the real world is untouched and a relog corrects the view by itself.
 * The faked blocks are deliberately solid ones, so the client's own collision
 * still agrees with the server's and nobody gets rubber-banded.
 * Lightning is {@link World#strikeLightningEffect}, which lights no fires and
 * deals no damage. The only creature spawned is a bat: it never lands, so it
 * cannot trample farmland, and it is invulnerable, so it cannot be farmed.
 *
 * <p>Each run gets its own {@link Session}. Effects overlap freely because a
 * session owns its own frames and its own undo stack, so a short effect ending
 * cannot strip a long one that is still running.
 */
final class ChaosService implements Listener {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    /** Ceiling on live entities one bat storm may hold at once. */
    private static final int MAX_SWARM = 120;

    /** Blocks a landing player or mob destroys just by touching them. */
    private static final Set<Material> TRAMPLEABLE = Set.of(
            Material.FARMLAND, Material.TURTLE_EGG, Material.SNIFFER_EGG
    );

    /** Worn by {@link ChaosCatalog#HEADS}, tagged so they can be swept up again. */
    private static final List<Material> HEAD_MATERIALS = List.of(
            Material.ZOMBIE_HEAD, Material.SKELETON_SKULL, Material.CREEPER_HEAD,
            Material.PIGLIN_HEAD, Material.WITHER_SKELETON_SKULL, Material.DRAGON_HEAD
    );

    private final MGXAccessBridge plugin;
    private final NamespacedKey scaleKey;
    private final NamespacedKey headKey;
    private final List<Session> active = new ArrayList<>();

    ChaosService(MGXAccessBridge plugin) {
        this.plugin = plugin;
        this.scaleKey = new NamespacedKey(plugin, "chaos_scale");
        this.headKey = new NamespacedKey(plugin, "chaos_head");
    }

    /** One run of one effect: the frames it owns and the undo stack it owes. */
    private final class Session {
        private final Location anchor;
        private final double radius;
        private final boolean physical;
        private final List<BukkitTask> tasks = new ArrayList<>();

        Session(Location anchor, double radius, boolean physical) {
            this.anchor = anchor;
            this.radius = radius;
            this.physical = physical;
        }

        private final Deque<Runnable> restores = new ArrayDeque<>();
        private final Map<UUID, Deque<Runnable>> perPlayer = new HashMap<>();
        private final Set<UUID> spawned = new HashSet<>();
        private boolean ended;

        void end() {
            if (ended) {
                return;
            }
            ended = true;
            for (BukkitTask task : tasks) {
                try {
                    task.cancel();
                } catch (RuntimeException ignored) {
                    // A frame that already ran itself out is not a problem.
                }
            }
            tasks.clear();
            while (!restores.isEmpty()) {
                safely(restores.pop());
            }
            perPlayer.clear();
            for (UUID id : Set.copyOf(spawned)) {
                Entity entity = plugin.getServer().getEntity(id);
                if (entity != null) {
                    entity.remove();
                }
            }
            spawned.clear();
            active.remove(this);
        }

        void undo(Runnable restore) {
            restores.push(restore);
        }

        void undoFor(Player player, Runnable restore) {
            restores.push(restore);
            perPlayer.computeIfAbsent(player.getUniqueId(), id -> new ArrayDeque<>()).push(restore);
        }
    }

    // ---------------------------------------------------------------- routing

    String run(Player operator, ChaosCatalog effect, int seconds, double radius) {
        if (effect == ChaosCatalog.CHAOS) {
            return chaos(operator, seconds, radius);
        }
        Session session = new Session(operator.getLocation().clone(), radius, effect.physical());
        active.add(session);
        long ticks = Math.max(20L, seconds * 20L);
        // Every effect, not only the ones that move people: blindness or
        // slowness while a mob is chewing on you is as lethal as a low ceiling.
        protect(session);
        String summary = switch (effect) {
            case DISCO -> { disco(session, ticks); yield started("Disco", seconds); }
            case BLACKOUT -> { blackout(session, ticks); yield started("Blackout", seconds); }
            case THUNDERDOME -> { thunderdome(session, ticks); yield started("Thunderdome", seconds); }
            case LAVAFLOOR -> {
                fakeFloor(session, ticks, Material.MAGMA_BLOCK, 0, true);
                yield started("Lava floor", seconds);
            }
            case VOIDFLOOR -> {
                // Barrier is invisible but solid, so the hole is only ever a
                // picture — the client still walks on the floor that is there.
                fakeFloor(session, ticks, Material.BARRIER, 4, false);
                yield started("Void floor", seconds);
            }
            case GIANTS -> {
                scale(session, ticks, 2.0d, "GIANTS", "Mind the ceiling.");
                yield started("Giants", seconds);
            }
            case TINY -> {
                scale(session, ticks, -0.75d, "TINY", "Mind your step.");
                yield started("Tiny", seconds);
            }
            case YOYO -> { yoyo(session, ticks); yield started("Yo-yo", seconds); }
            case LAUNCH -> { launch(session); yield "Launched everybody"; }
            case FLOAT -> { drift(session, ticks); yield started("Float", seconds); }
            case SPIN -> { spin(session, ticks); yield started("Spin", seconds); }
            case DRUNK -> { drunk(session, ticks); yield started("Drunk", seconds); }
            case GHOSTS -> { ghosts(session, ticks); yield started("Ghosts", seconds); }
            case RAVE -> { rave(session, ticks); yield started("Rave", seconds); }
            case SWAP -> { swap(session); yield "Swapped everybody"; }
            case MOBSTORM -> { batStorm(session, ticks); yield started("Bat storm", seconds); }
            case METEORS -> { meteors(session, ticks); yield started("Meteors", seconds); }
            case CONFETTI -> { confetti(session); yield "Fired the confetti"; }
            case HEADS -> { heads(session, ticks); yield started("Heads", seconds); }
            default -> throw new IllegalArgumentException(effect.id() + " is not handled here.");
        };
        // Every session ends on its own clock, even the one-shots, so nothing is
        // left holding an undo it will never run.
        session.tasks.add(plugin.getServer().getScheduler()
                .runTaskLater(plugin, session::end, ticks + 2L));
        return summary;
    }

    private String chaos(Player operator, int seconds, double radius) {
        List<ChaosCatalog> pool = new ArrayList<>(ChaosCatalog.chaosPool());
        Collections.shuffle(pool);
        List<ChaosCatalog> picked = List.copyOf(pool.subList(0, Math.min(5, pool.size())));
        // A throwaway session, used only to work out who should hear the shout.
        Session heralds = new Session(operator.getLocation().clone(), radius, false);
        announce(heralds, Component.text("TOTAL CHAOS!", NamedTextColor.RED, TextDecoration.BOLD)
                .append(Component.text(" Everything at once. Good luck.", NamedTextColor.WHITE)));
        for (ChaosCatalog effect : picked) {
            try {
                run(operator, effect, seconds, radius);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Chaos component " + effect.id() + " failed: "
                        + exception.getClass().getSimpleName());
            }
        }
        return "Started Chaos for " + seconds + "s within " + (int) radius + " blocks: "
                + String.join(", ", picked.stream().map(ChaosCatalog::id).toList());
    }

    // ---------------------------------------------------------------- effects

    private void disco(Session session, long ticks) {
        announce(session, Component.text("DISCO!", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                .append(Component.text(" The sun has lost the plot.", NamedTextColor.WHITE)));
        forTargets(session, player -> session.undoFor(player, player::resetPlayerTime));
        repeat(session, 2L, ticks, frame -> {
            long time = (frame * 900L) % 24000L;
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (Player player : targets(session)) {
                player.setPlayerTime(time, false);
                Location at = player.getLocation().add(0d, 1.2d, 0d);
                player.spawnParticle(Particle.DUST, at, 25, 1.2d, 1.2d, 1.2d, 0d,
                        new Particle.DustOptions(randomColour(random), 1.4f));
                if (frame % 5 == 0) {
                    player.playSound(at, Sound.BLOCK_NOTE_BLOCK_BIT, 0.7f,
                            0.6f + random.nextFloat() * 1.4f);
                }
            }
        });
    }

    private void blackout(Session session, long ticks) {
        announce(session, Component.text("BLACKOUT!", NamedTextColor.DARK_GRAY, TextDecoration.BOLD)
                .append(Component.text(" Who turned the sun off?", NamedTextColor.WHITE)));
        forTargets(session, player -> {
            player.setPlayerTime(18000L, false);
            player.addPotionEffect(potion(PotionEffectType.BLINDNESS, ticks, 0));
            player.playSound(player.getLocation(), Sound.AMBIENT_CAVE, 1f, 0.5f);
            session.undoFor(player, () -> {
                player.resetPlayerTime();
                player.removePotionEffect(PotionEffectType.BLINDNESS);
            });
        });
    }

    private void thunderdome(Session session, long ticks) {
        announce(session, Component.text("THUNDERDOME!", NamedTextColor.AQUA, TextDecoration.BOLD)
                .append(Component.text(" It is all bark. Mostly.", NamedTextColor.WHITE)));
        forTargets(session, player -> {
            player.setPlayerWeather(WeatherType.DOWNFALL);
            session.undoFor(player, player::resetPlayerWeather);
        });
        repeat(session, 10L, ticks, frame -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (Player player : targets(session)) {
                Location at = player.getLocation().add(
                        random.nextDouble(-9d, 9d), 0d, random.nextDouble(-9d, 9d)
                );
                // Effect-only: no fire, no damage, no block change.
                player.getWorld().strikeLightningEffect(at);
            }
        });
    }

    /**
     * Fakes the floor for each client. Nothing is written to the world.
     *
     * <p>{@code surface} must be a block the client considers <em>solid</em>.
     * Sending real lava or real air desyncs the illusion from the server: the
     * client runs its own collision against what it was told, so it predicts
     * sinking or falling, sends those positions, and gets rubber-banded back —
     * which looks broken and reads to an anticheat as impossible movement.
     * Magma block and barrier both collide exactly like the floor really there.
     *
     * @param depth how many blocks below the surface to hollow out visually.
     *              The player stands on {@code surface}, so they never reach
     *              these and the illusion costs nothing in prediction.
     */
    private void fakeFloor(Session session, long ticks, Material surface, int depth, boolean lava) {
        announce(session, lava
                ? Component.text("THE FLOOR IS LAVA!", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .append(Component.text(" It is not. Probably.", NamedTextColor.WHITE))
                : Component.text("THE FLOOR IS GONE!", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD)
                        .append(Component.text(" Do not look down.", NamedTextColor.WHITE)));
        Map<UUID, Set<Location>> faked = new HashMap<>();
        session.undo(() -> faked.forEach((id, locations) -> {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null) {
                return;
            }
            // Resend the block that was really there the whole time.
            locations.forEach(at -> player.sendBlockChange(at, at.getBlock().getBlockData()));
        }));
        Map<UUID, Location> lastSeen = new HashMap<>();
        repeat(session, 10L, ticks, frame -> {
            for (Player player : targets(session)) {
                Set<Location> mine = faked.computeIfAbsent(player.getUniqueId(), id -> new HashSet<>());
                Location feet = player.getLocation();
                // Standing still means the illusion is already on their screen.
                Location previous = lastSeen.get(player.getUniqueId());
                boolean moved = previous == null
                        || previous.getBlockX() != feet.getBlockX()
                        || previous.getBlockY() != feet.getBlockY()
                        || previous.getBlockZ() != feet.getBlockZ();
                lastSeen.put(player.getUniqueId(), feet.clone());
                if (!moved) {
                    continue;
                }
                for (int x = -6; x <= 6; x++) {
                    for (int z = -6; z <= 6; z++) {
                        if (x * x + z * z > 36) {
                            continue;
                        }
                        for (int down = 1; down <= 1 + depth; down++) {
                            Location at = feet.clone().add(x, -down, z);
                            if (at.getBlock().getType().isAir()) {
                                continue;
                            }
                            player.sendBlockChange(at, down == 1
                                    ? surface.createBlockData()
                                    : Material.AIR.createBlockData());
                            mine.add(at.clone());
                        }
                    }
                }
                if (lava && frame % 4 == 0) {
                    player.playSound(feet, Sound.BLOCK_LAVA_AMBIENT, 0.6f, 1f);
                }
            }
        });
    }

    private void scale(Session session, long ticks, double delta, String title, String subtitle) {
        announce(session, Component.text(title + "!", NamedTextColor.YELLOW, TextDecoration.BOLD)
                .append(Component.text(" " + subtitle, NamedTextColor.WHITE)));
        forTargets(session, player -> applyScale(session, player, delta));
    }

    private void yoyo(Session session, long ticks) {
        announce(session, Component.text("YO-YO!", NamedTextColor.GREEN, TextDecoration.BOLD)
                .append(Component.text(" Pick a size and stick with it.", NamedTextColor.WHITE)));
        repeat(session, 20L, ticks, frame -> {
            boolean big = frame % 2 == 0;
            for (Player player : targets(session)) {
                applyScale(session, player, big ? 1.5d : -0.6d);
                player.playSound(player.getLocation(),
                        big ? Sound.ENTITY_ENDER_DRAGON_GROWL : Sound.ENTITY_BAT_TAKEOFF,
                        0.6f, big ? 0.6f : 1.8f);
            }
        });
    }

    private void launch(Session session) {
        announce(session, Component.text("LIFTOFF!", NamedTextColor.AQUA, TextDecoration.BOLD)
                .append(Component.text(" See you on the way down.", NamedTextColor.WHITE)));
        forTargets(session, player -> {
            // Slow falling goes on first, so the landing is a joke not a death.
            player.addPotionEffect(potion(PotionEffectType.SLOW_FALLING, 400L, 0));
            player.setVelocity(player.getVelocity().add(new Vector(0d, 2.2d, 0d)));
            player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f);
            player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(),
                    40, 0.4d, 0.2d, 0.4d, 0.15d);
        });
    }

    private void drift(Session session, long ticks) {
        announce(session, Component.text("FLOAT!", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                .append(Component.text(" Gravity is taking a break.", NamedTextColor.WHITE)));
        forTargets(session, player -> {
            player.addPotionEffect(potion(PotionEffectType.LEVITATION, ticks, 1));
            session.undoFor(player, () -> {
                player.removePotionEffect(PotionEffectType.LEVITATION);
                // The way down is the dangerous half.
                player.addPotionEffect(potion(PotionEffectType.SLOW_FALLING, 400L, 0));
            });
        });
    }

    private void spin(Session session, long ticks) {
        announce(session, Component.text("SPIN!", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(" Try walking in a straight line now.", NamedTextColor.WHITE)));
        repeat(session, 2L, ticks, frame -> {
            for (Player player : targets(session)) {
                Location at = player.getLocation();
                player.setRotation(at.getYaw() + 28f, at.getPitch());
            }
        });
    }

    private void drunk(Session session, long ticks) {
        announce(session, Component.text("WOBBLE!", NamedTextColor.DARK_GREEN, TextDecoration.BOLD)
                .append(Component.text(" The world is fine. You are not.", NamedTextColor.WHITE)));
        forTargets(session, player -> {
            player.addPotionEffect(potion(PotionEffectType.NAUSEA, ticks, 0));
            player.addPotionEffect(potion(PotionEffectType.SLOWNESS, ticks, 1));
            session.undoFor(player, () -> {
                player.removePotionEffect(PotionEffectType.NAUSEA);
                player.removePotionEffect(PotionEffectType.SLOWNESS);
            });
        });
    }

    private void ghosts(Session session, long ticks) {
        announce(session, Component.text("GHOSTS!", NamedTextColor.WHITE, TextDecoration.BOLD)
                .append(Component.text(" Nobody is where you think they are.", NamedTextColor.GRAY)));
        forTargets(session, player -> {
            player.addPotionEffect(potion(PotionEffectType.INVISIBILITY, ticks, 0));
            player.addPotionEffect(potion(PotionEffectType.GLOWING, ticks, 0));
            session.undoFor(player, () -> {
                player.removePotionEffect(PotionEffectType.INVISIBILITY);
                player.removePotionEffect(PotionEffectType.GLOWING);
            });
        });
    }

    private void rave(Session session, long ticks) {
        announce(session, Component.text("RAVE!", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                .append(Component.text(" Hands up.", NamedTextColor.WHITE)));
        forTargets(session, player -> {
            player.addPotionEffect(potion(PotionEffectType.GLOWING, ticks, 0));
            session.undoFor(player, () -> player.removePotionEffect(PotionEffectType.GLOWING));
        });
        repeat(session, 4L, ticks, frame -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (Player player : targets(session)) {
                Location at = player.getLocation().add(0d, 1d, 0d);
                for (int point = 0; point < 12; point++) {
                    double angle = (Math.PI * 2d / 12d) * point + frame * 0.35d;
                    Location spot = at.clone().add(Math.cos(angle) * 1.8d, 0d, Math.sin(angle) * 1.8d);
                    player.spawnParticle(Particle.DUST, spot, 3, 0d, 0.4d, 0d, 0d,
                            new Particle.DustOptions(randomColour(random), 1.5f));
                }
                if (frame % 4 == 0) {
                    player.playSound(at, Sound.BLOCK_NOTE_BLOCK_BASS, 0.9f, 0.5f + random.nextFloat());
                }
            }
        });
    }

    private void swap(Session session) {
        List<Player> players = new ArrayList<>(targets(session));
        if (players.size() < 2) {
            throw new IllegalArgumentException("Swap needs at least two players online.");
        }
        announce(session, Component.text("SWAP!", NamedTextColor.RED, TextDecoration.BOLD)
                .append(Component.text(" You are somebody else's problem now.", NamedTextColor.WHITE)));
        List<Location> where = players.stream().map(player -> player.getLocation().clone()).toList();
        for (int index = 0; index < players.size(); index++) {
            Player player = players.get(index);
            Location destination = where.get((index + 1) % where.size());
            player.teleport(destination);
            player.playSound(destination, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            player.getWorld().spawnParticle(Particle.PORTAL, destination, 60, 0.5d, 1d, 0.5d, 0.4d);
        }
    }

    private void batStorm(Session session, long ticks) {
        announce(session, Component.text("BAT STORM!", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD)
                .append(Component.text(" They are friendly. They are also everywhere.",
                        NamedTextColor.WHITE)));
        repeat(session, 20L, ticks, frame -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (Player player : targets(session)) {
                // Hard ceiling. Without it a full-length storm is hundreds of
                // entities per player and the server stops keeping up.
                if (session.spawned.size() >= MAX_SWARM) {
                    return;
                }
                for (int index = 0; index < 2; index++) {
                    Location at = player.getLocation().add(
                            random.nextDouble(-7d, 7d),
                            random.nextDouble(1d, 5d),
                            random.nextDouble(-7d, 7d)
                    );
                    Entity bat = player.getWorld().spawnEntity(at, EntityType.BAT);
                    // Invulnerable so nobody can farm them; non-persistent so an
                    // unclean shutdown leaves nothing behind to clean up.
                    bat.setInvulnerable(true);
                    bat.setPersistent(false);
                    bat.setGlowing(true);
                    session.spawned.add(bat.getUniqueId());
                }
            }
        });
    }

    private void meteors(Session session, long ticks) {
        announce(session, Component.text("METEORS!", NamedTextColor.RED, TextDecoration.BOLD)
                .append(Component.text(" Incoming. Harmlessly.", NamedTextColor.WHITE)));
        repeat(session, 8L, ticks, frame -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            List<Player> watching = targets(session);
            if (!watching.isEmpty()) {
                // One meteor a frame, aimed near somebody at random. Per-player
                // meteors put a 1-tick task in the air for every rock alive.
                Player player = watching.get(random.nextInt(watching.size()));
                Location start = player.getLocation().add(
                        random.nextDouble(-12d, 12d), 26d, random.nextDouble(-12d, 12d)
                );
                World world = start.getWorld();
                if (world == null) {
                    return;
                }
                BlockDisplay rock = world.spawn(start, BlockDisplay.class, display -> {
                    display.setBlock(Material.MAGMA_BLOCK.createBlockData());
                    display.setPersistent(false);
                    display.setGlowing(true);
                });
                session.spawned.add(rock.getUniqueId());
                // Displays ignore gravity, so the fall is driven frame by frame
                // and lands in particles rather than in an explosion.
                repeat(session, 1L, 60L, step -> {
                    if (!rock.isValid()) {
                        return;
                    }
                    Location at = rock.getLocation().subtract(0d, 0.9d, 0d);
                    rock.teleport(at);
                    world.spawnParticle(Particle.FLAME, at, 6, 0.2d, 0.2d, 0.2d, 0.01d);
                    if (step >= 28 || at.getBlock().getType().isSolid()) {
                        world.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1);
                        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.4f);
                        session.spawned.remove(rock.getUniqueId());
                        rock.remove();
                    }
                });
            }
        });
    }

    private void confetti(Session session) {
        announce(session, Component.text("CONFETTI!", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                .append(Component.text(" No reason. None needed.", NamedTextColor.WHITE)));
        forTargets(session, player -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location at = player.getLocation().add(0d, 1.5d, 0d);
            for (int burst = 0; burst < 8; burst++) {
                player.spawnParticle(Particle.DUST, at, 25, 2.5d, 2d, 2.5d, 0d,
                        new Particle.DustOptions(randomColour(random), 1.8f));
            }
            player.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 60, 1.5d, 1.5d, 1.5d, 0.6d);
            player.playSound(at, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.6f);
        });
    }

    private void heads(Session session, long ticks) {
        announce(session, Component.text("HEADS!", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(" A new look for everyone.", NamedTextColor.WHITE)));
        forTargets(session, player -> {
            ItemStack previous = player.getInventory().getHelmet();
            // Never take a helmet somebody is wearing. A pumpkin is what stops
            // endermen aggroing at a farm, and a real helmet is armour points,
            // Respiration and Aqua Affinity. Swapping either could get a player
            // killed doing something entirely reasonable.
            if (previous != null && !previous.getType().isAir()) {
                return;
            }
            ItemStack head = new ItemStack(
                    HEAD_MATERIALS.get(ThreadLocalRandom.current().nextInt(HEAD_MATERIALS.size()))
            );
            ItemMeta meta = head.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().set(headKey, PersistentDataType.BYTE, (byte) 1);
                head.setItemMeta(meta);
            }
            player.getInventory().setHelmet(head);
            session.undoFor(player, () -> {
                // Sweep first: a player who stashed the head elsewhere would
                // otherwise keep an item the server never really gave them.
                sweepHeads(player);
                player.getInventory().setHelmet(previous);
            });
        });
    }

    // ------------------------------------------------------------- mechanics

    private void applyScale(Session session, Player player, double delta) {
        AttributeInstance instance = player.getAttribute(Attribute.SCALE);
        if (instance == null) {
            return;
        }
        clearScale(player);
        // Transient: Minecraft never writes this to the player file, so no crash
        // or unclean shutdown can leave somebody permanently the wrong size.
        instance.addTransientModifier(new AttributeModifier(
                scaleKey, delta, AttributeModifier.Operation.MULTIPLY_SCALAR_1
        ));
        session.undoFor(player, () -> clearScale(player));
    }

    private void clearScale(Player player) {
        AttributeInstance instance = player.getAttribute(Attribute.SCALE);
        if (instance == null) {
            return;
        }
        AttributeModifier existing = instance.getModifier(scaleKey);
        if (existing != null) {
            instance.removeModifier(existing);
        }
    }

    /** Removes any head this service handed out, wherever the player put it. */
    private void sweepHeads(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || !stack.hasItemMeta()) {
                continue;
            }
            ItemMeta meta = stack.getItemMeta();
            if (meta != null
                    && meta.getPersistentDataContainer().has(headKey, PersistentDataType.BYTE)) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    /**
     * Makes every target briefly untouchable and non-colliding, for the whole
     * length of any effect. Dying to an admin event is the one change that
     * really would stick, and almost every effect can cause one: a scaled-up
     * player suffocates in a low ceiling, and blindness or slowness is lethal
     * anywhere a mob is already swinging.
     */
    private void protect(Session session) {
        forTargets(session, player -> {
            boolean wasInvulnerable = player.isInvulnerable();
            boolean wasCollidable = player.isCollidable();
            player.setInvulnerable(true);
            // A scaled-up player has a scaled-up hitbox, which would otherwise
            // shove villagers off workstations and mobs out of farms.
            player.setCollidable(false);
            session.undoFor(player, () -> {
                player.setInvulnerable(wasInvulnerable);
                player.setCollidable(wasCollidable);
            });
        });
    }

    // ------------------------------------------------------------ scheduling

    /** Runs {@code body} every {@code period} ticks until {@code ticks} elapse. */
    private void repeat(Session session, long period, long ticks, LongConsumer body) {
        long step = Math.max(1L, period);
        long frames = Math.max(1L, ticks / step);
        BukkitRunnable runnable = new BukkitRunnable() {
            private long frame;

            @Override
            public void run() {
                if (session.ended || frame >= frames) {
                    cancel();
                    return;
                }
                try {
                    body.accept(frame++);
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("Chaos frame failed: "
                            + exception.getClass().getSimpleName());
                    cancel();
                }
            }
        };
        session.tasks.add(runnable.runTaskTimer(plugin, 1L, step));
    }

    /** Ends every running effect and puts every borrowed thing back. */
    void stopAll() {
        for (Session session : List.copyOf(active)) {
            session.end();
        }
        active.clear();
        // Belt and braces, and deliberately everyone rather than the last
        // event's targets: a player may have walked out of range mid-effect.
        for (Player player : online()) {
            player.resetPlayerTime();
            player.resetPlayerWeather();
            clearScale(player);
        }
    }

    /** A player who logs out mid-effect gets their own state back immediately. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        for (Session session : List.copyOf(active)) {
            Deque<Runnable> mine = session.perPlayer.remove(event.getPlayer().getUniqueId());
            if (mine == null) {
                continue;
            }
            while (!mine.isEmpty()) {
                Runnable restore = mine.pop();
                session.restores.remove(restore);
                safely(restore);
            }
        }
    }


    // ------------------------------------------------------------- world guards
    //
    // These make the "nothing permanent" promise structural rather than a claim
    // about vanilla internals. strikeLightningEffect is documented as harmless,
    // but a bolt that turned one villager into a witch would be unrecoverable,
    // so the outcomes are refused outright while an event is running.

    /** True while any effect is still on. */
    private boolean running() {
        return !active.isEmpty();
    }

    private boolean isOurs(UUID id) {
        return active.stream().anyMatch(session -> session.spawned.contains(id));
    }

    /**
     * Launching a server full of people is the one effect that lands them again,
     * and a player landing on farmland turns it to dirt and kills the crop.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerTrample(PlayerInteractEvent event) {
        if (!running() || event.getAction() != Action.PHYSICAL) {
            return;
        }
        if (event.getClickedBlock() != null
                && TRAMPLEABLE.contains(event.getClickedBlock().getType())) {
            event.setCancelled(true);
        }
    }

    /** The same protection for anything the events shoved around. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityTrample(EntityInteractEvent event) {
        if (running() && TRAMPLEABLE.contains(event.getBlock().getType())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLightningIgnite(BlockIgniteEvent event) {
        if (running() && event.getCause() == BlockIgniteEvent.IgniteCause.LIGHTNING) {
            event.setCancelled(true);
        }
    }

    /** No villager to witch, no pig to piglin, no mooshroom repaint. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLightningTransform(EntityTransformEvent event) {
        if (running() && event.getTransformReason() == EntityTransformEvent.TransformReason.LIGHTNING) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCreeperCharge(CreeperPowerEvent event) {
        if (running() && event.getCause() == CreeperPowerEvent.PowerCause.LIGHTNING) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLightningDamage(EntityDamageEvent event) {
        if (running() && event.getCause() == EntityDamageEvent.DamageCause.LIGHTNING) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLightningBurn(EntityCombustByEntityEvent event) {
        if (running() && event.getCombuster() instanceof LightningStrike) {
            event.setCancelled(true);
        }
    }

    /** Nothing this service spawned may edit a block, whatever it is. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSpawnedChangesBlock(EntityChangeBlockEvent event) {
        if (isOurs(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void safely(Runnable restore) {
        try {
            restore.run();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Chaos restore failed: "
                    + exception.getClass().getSimpleName());
        }
    }

    private List<Player> online() {
        return List.copyOf(plugin.getServer().getOnlinePlayers());
    }

    /**
     * Everyone this event may touch, recomputed every frame so somebody who
     * wanders in joins and somebody who leaves is dropped.
     */
    private List<Player> targets(Session session) {
        World world = session.anchor.getWorld();
        List<Player> found = new ArrayList<>();
        for (Player player : online()) {
            boolean sameWorld = world != null && world.equals(player.getWorld());
            double distanceSquared = sameWorld
                    ? player.getLocation().distanceSquared(session.anchor)
                    : Double.MAX_VALUE;
            if (ChaosTargeting.eligible(
                    sameWorld,
                    distanceSquared,
                    session.radius,
                    isAfk(player),
                    player.isInsideVehicle(),
                    session.physical
            )) {
                found.add(player);
            }
        }
        return found;
    }

    private boolean isAfk(Player player) {
        AfkService afk = plugin.afkService();
        return afk != null && afk.isAfk(player.getUniqueId());
    }

    private void forTargets(Session session, Consumer<Player> body) {
        for (Player player : targets(session)) {
            try {
                body.accept(player);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Chaos effect failed for " + player.getName()
                        + ": " + exception.getClass().getSimpleName());
            }
        }
    }

    private static PotionEffect potion(PotionEffectType type, long ticks, int amplifier) {
        int span = (int) Math.min(Integer.MAX_VALUE, Math.max(20L, ticks));
        return new PotionEffect(type, span, amplifier, false, false, true);
    }

    private static Color randomColour(ThreadLocalRandom random) {
        return Color.fromRGB(random.nextInt(60, 256), random.nextInt(60, 256), random.nextInt(60, 256));
    }

    private static String started(String name, int seconds) {
        return "Started " + name + " for " + seconds + "s";
    }

    /**
     * Only the people the event reaches are told about it. A server-wide
     * "THE FLOOR IS LAVA" read as a bug to anybody whose floor stayed put.
     */
    private void announce(Session session, Component message) {
        Component announcement = Component.text("SERVER » ", ORANGE, TextDecoration.BOLD).append(message);
        targets(session).forEach(player -> player.sendMessage(announcement));
        plugin.getServer().getConsoleSender().sendMessage(announcement);
    }
}
