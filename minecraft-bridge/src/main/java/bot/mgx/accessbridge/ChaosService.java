package bot.mgx.accessbridge;

import net.kyori.adventure.bossbar.BossBar;
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
import org.bukkit.SoundCategory;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Slime;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Zombie;
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
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
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
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
    private final CrateItems crateItems;
    private final EventShow show;
    private final NamespacedKey scaleKey;
    private final NamespacedKey headKey;
    private final List<Session> active = new ArrayList<>();
    /** Live pinatas, by entity id, with the hits they have left. */
    private final Map<UUID, Pinata> pinatas = new HashMap<>();
    /** Live Alfredos, by entity id. */
    private final Map<UUID, Alfredo> alfredos = new HashMap<>();

    ChaosService(MGXAccessBridge plugin, CrateItems crateItems) {
        this.plugin = plugin;
        this.crateItems = crateItems;
        this.show = new EventShow(plugin);
        this.scaleKey = new NamespacedKey(plugin, "chaos_scale");
        this.headKey = new NamespacedKey(plugin, "chaos_head");
    }

    /** A pinata being hit: how much is left, and the bar showing it. */
    private static final class Pinata {
        private final BossBar bar;
        private final int total;
        private int remaining;
        private int keys;
        private boolean burst;

        Pinata(BossBar bar, int total, int keys) {
            this.bar = bar;
            this.total = total;
            this.remaining = total;
            this.keys = keys;
        }
    }

    /**
     * A live Alfredo: his bar, what he is carrying, and how much of it he has
     * already coughed up.
     */
    private static final class Alfredo {
        private final BossBar bar;
        private final Session session;
        private int burstKeys;
        private int burstDiamonds;
        private int finaleKeys;
        private int finaleDiamonds;
        private int burstsLeft;
        private double nextBurstAt;
        private double maxHealth;
        private boolean finished;
        private boolean autoDamage;

        Alfredo(BossBar bar, Session session, int keys, int diamonds, int bursts, double maxHealth) {
            this.bar = bar;
            this.session = session;
            // Half is paid out on the way down, half erupts when he dies. The
            // finale is the point of the fight, so it cannot be spent early.
            this.burstKeys = keys / 2;
            this.burstDiamonds = diamonds / 2;
            this.finaleKeys = keys - this.burstKeys;
            this.finaleDiamonds = diamonds - this.burstDiamonds;
            this.burstsLeft = bursts;
            this.maxHealth = maxHealth;
            this.nextBurstAt = maxHealth - (maxHealth / (bursts + 1));
        }
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
        /** Who this event started a record for, so only they get it stopped. */
        private final Set<UUID> listeners = new HashSet<>();
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
                Pinata pinata = pinatas.remove(id);
                if (pinata != null) {
                    show.hideBar(pinata.bar, online());
                }
                Alfredo boss = alfredos.remove(id);
                if (boss != null) {
                    show.hideBar(boss.bar, online());
                }
                Entity entity = plugin.getServer().getEntity(id);
                if (entity != null) {
                    entity.remove();
                }
            }
            spawned.clear();
            listeners.clear();
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
        return run(operator, effect, seconds, radius, 0, true);
    }

    /**
     * @param keys how many crate keys this event should hand out, or 0 to use
     *             the event's own default. Only the payout events read it.
     */
    String run(Player operator, ChaosCatalog effect, int seconds, double radius, int keys) {
        return run(operator, effect, seconds, radius, keys, true);
    }

    /**
     * @param telegraph whether to count the event in. Chaos counts itself in once
     *                  and then starts its components silently, because five
     *                  overlapping countdowns is noise, not hype.
     */
    private String run(
            Player operator, ChaosCatalog effect, int seconds, double radius,
            int keys, boolean telegraph
    ) {
        if (effect == ChaosCatalog.CHAOS) {
            return chaos(operator, seconds, radius);
        }
        Session session = new Session(operator.getLocation().clone(), radius, effect.physical());
        active.add(session);
        long ticks = Math.max(20L, seconds * 20L);
        long lead = telegraph ? EventShow.TELEGRAPH_TICKS : 0L;

        Runnable land = () -> {
            // stop can be run during the countdown. Landing on a dead session
            // would queue undos onto something that will never run them.
            if (session.ended) {
                return;
            }
            // Every effect, not only the ones that move people: blindness or
            // slowness while a mob is chewing on you is as lethal as a low ceiling.
            protect(session);
            begin(session, effect, ticks, keys);
            List<Player> audience = targets(session);
            audience.forEach(player -> session.listeners.add(player.getUniqueId()));
            show.music(audience, trackFor(effect));
            if (effect.timed()) {
                BossBar bar = show.bar(label(effect).toUpperCase(Locale.ROOT), colourFor(effect));
                repeat(session, 10L, ticks, frame -> show.showBar(
                        bar, targets(session), 1f - (frame * 10f / ticks)
                ));
                session.undo(() -> show.hideBar(bar, online()));
            }
            session.undo(() -> {
                // Only the people this event started a record for. Stopping the
                // RECORDS channel for everybody would kill somebody's jukebox
                // on the other side of the world.
                show.stopMusic(session.listeners.stream()
                        .map(plugin.getServer()::getPlayer)
                        .filter(java.util.Objects::nonNull)
                        .toList());
                show.finale(targets(session), label(effect).toUpperCase(Locale.ROOT));
            });
        };

        if (telegraph) {
            show.telegraph(() -> targets(session), label(effect).toUpperCase(Locale.ROOT),
                    effect.blurb(), textColourFor(effect), land);
        } else {
            land.run();
        }

        // Every session ends on its own clock, even the one-shots, so nothing is
        // left holding an undo it will never run.
        session.tasks.add(plugin.getServer().getScheduler()
                .runTaskLater(plugin, session::end, lead + lifeTicks(effect, ticks) + 2L));
        return started(label(effect), seconds) + " within " + (int) radius + " blocks";
    }

    private void begin(Session session, ChaosCatalog effect, long ticks, int keys) {
        switch (effect) {
            case DISCO -> disco(session, ticks);
            case BLACKOUT -> blackout(session, ticks);
            case THUNDERDOME -> thunderdome(session, ticks);
            case LAVAFLOOR -> fakeFloor(session, ticks, Material.MAGMA_BLOCK, 0, true);
            // Barrier is invisible but solid, so the hole is only ever a picture
            // — the client still walks on the floor that is really there.
            case VOIDFLOOR -> fakeFloor(session, ticks, Material.BARRIER, 4, false);
            case GIANTS -> scale(session, ticks, 2.0d, "GIANTS", "Mind the ceiling.");
            case TINY -> scale(session, ticks, -0.75d, "TINY", "Mind your step.");
            case YOYO -> yoyo(session, ticks);
            case LAUNCH -> launch(session);
            case FLOAT -> drift(session, ticks);
            case SPIN -> spin(session, ticks);
            case DRUNK -> drunk(session, ticks);
            case GHOSTS -> ghosts(session, ticks);
            case RAVE -> rave(session, ticks);
            case SWAP -> swap(session);
            case MOBSTORM -> batStorm(session, ticks);
            case METEORS -> meteors(session, ticks);
            case CONFETTI -> confetti(session);
            case HEADS -> heads(session, ticks);
            case AIRDROP -> airdrop(session, 200L,
                    keys > 0 ? keys : DEFAULT_AIRDROP_KEYS);
            case PINATA -> pinata(session, ticks, keys);
            case JACKPOT -> jackpot(session, keys);
            case ALFREDO -> alfredo(session, ticks,
                    ALFREDO_DEFAULT_HEALTH, ALFREDO_DEFAULT_KEYS, ALFREDO_DEFAULT_DIAMONDS);
            default -> throw new IllegalArgumentException(effect.id() + " is not handled here.");
        }
    }

    /**
     * How long a session must stay alive. A one-shot has no duration but still
     * has an animation: ending the session on its nominal 20 ticks would delete
     * the supply crate somewhere over the drop zone.
     */
    private static long lifeTicks(ChaosCatalog effect, long ticks) {
        return switch (effect) {
            // Long enough for a real fight; his death ends the session early.
            case ALFREDO -> 12_000L;
            case AIRDROP -> 240L;
            case JACKPOT -> 280L;
            case LAUNCH, SWAP, CONFETTI -> 60L;
            default -> ticks;
        };
    }

    private static String label(ChaosCatalog effect) {
        String id = effect.id();
        return Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }

    /** Each event gets its own record, so they are told apart by ear. */
    private static Sound trackFor(ChaosCatalog effect) {
        return switch (effect) {
            case DISCO, RAVE -> Sound.MUSIC_DISC_PIGSTEP;
            case BLACKOUT, VOIDFLOOR, GHOSTS -> Sound.MUSIC_DISC_11;
            case THUNDERDOME, METEORS -> Sound.MUSIC_DISC_OTHERSIDE;
            case AIRDROP, JACKPOT, PINATA -> Sound.MUSIC_DISC_CREATOR;
            case ALFREDO -> Sound.MUSIC_DISC_PIGSTEP;
            case LAVAFLOOR, GIANTS, YOYO -> Sound.MUSIC_DISC_STAL;
            default -> Sound.MUSIC_DISC_CAT;
        };
    }

    private static BossBar.Color colourFor(ChaosCatalog effect) {
        return switch (effect) {
            case BLACKOUT, VOIDFLOOR, GHOSTS -> BossBar.Color.PURPLE;
            case THUNDERDOME, METEORS -> BossBar.Color.BLUE;
            case LAVAFLOOR, GIANTS -> BossBar.Color.RED;
            case AIRDROP, JACKPOT -> BossBar.Color.YELLOW;
            case ALFREDO -> BossBar.Color.RED;
            case PINATA, DISCO, RAVE -> BossBar.Color.PINK;
            default -> BossBar.Color.GREEN;
        };
    }

    private static TextColor textColourFor(ChaosCatalog effect) {
        return switch (effect) {
            case BLACKOUT, VOIDFLOOR, GHOSTS -> TextColor.color(0xB05CFF);
            case THUNDERDOME, METEORS -> TextColor.color(0x4FC3F7);
            case LAVAFLOOR, GIANTS -> TextColor.color(0xFF5722);
            case AIRDROP, JACKPOT, PINATA -> TextColor.color(0xFFD54F);
            case ALFREDO -> TextColor.color(0xFF3B30);
            default -> ORANGE;
        };
    }

    /**
     * Alfredo takes a loot budget rather than a duration, so he gets his own way
     * in instead of being squeezed through the generic effect arguments.
     */
    String summonAlfredo(
            Player operator, int health, int keys, int diamonds, double radius, boolean test
    ) {
        Session session = new Session(operator.getLocation().clone(), radius, false);
        active.add(session);
        long ticks = lifeTicks(ChaosCatalog.ALFREDO, 0L);
        protect(session);
        List<Player> audience = targets(session);
        audience.forEach(player -> session.listeners.add(player.getUniqueId()));
        show.music(audience, trackFor(ChaosCatalog.ALFREDO));
        alfredo(session, ticks, health, keys, diamonds);
        if (test) {
            startAlfredoSelfTest(session);
        }
        session.undo(() -> show.stopMusic(session.listeners.stream()
                .map(plugin.getServer()::getPlayer)
                .filter(java.util.Objects::nonNull)
                .toList()));
        session.tasks.add(plugin.getServer().getScheduler()
                .runTaskLater(plugin, session::end, ticks + 2L));
        return "Alfredo is up with " + health + " health, " + keys + " keys and "
                + diamonds + " diamonds to give away"
                + (test ? " (test run: he dies on his own in about "
                        + ALFREDO_TEST_SECONDS + "s)" : "");
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
                run(operator, effect, seconds, radius, 0, false);
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
        pinatas.values().forEach(pinata -> show.hideBar(pinata.bar, online()));
        pinatas.clear();
        alfredos.values().forEach(boss -> show.hideBar(boss.bar, online()));
        alfredos.clear();
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



    // ------------------------------------------------------------ live events

    /**
     * A crate falls out of the sky onto a marked spot and bursts open.
     *
     * <p>The marker beam goes up first so there is somewhere to run to. That is
     * the whole difference between an event and loot appearing.
     */
    private void airdrop(Session session, long ticks, int keys) {
        World world = session.anchor.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("The drop zone has no world.");
        }
        Location ground = world.getHighestBlockAt(session.anchor).getLocation().add(0.5d, 1d, 0.5d);
        announce(session, Component.text("SUPPLY DROP", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(" Get to the beam.", NamedTextColor.WHITE)));
        BossBar bar = show.bar("SUPPLY DROP INBOUND", BossBar.Color.YELLOW);

        // The beam: a column of colour standing on the landing spot.
        repeat(session, 2L, ticks, frame -> {
            for (double height = 0d; height < 30d; height += 0.6d) {
                world.spawnParticle(Particle.DUST, ground.clone().add(0d, height, 0d), 1, 0.05d, 0d, 0.05d, 0d,
                        new Particle.DustOptions(Color.fromRGB(255, 200, 40), 1.4f));
            }
        });

        Location start = ground.clone().add(0d, 40d, 0d);
        BlockDisplay crate = world.spawn(start, BlockDisplay.class, display -> {
            display.setBlock(Material.CHEST.createBlockData());
            display.setPersistent(false);
            display.setGlowing(true);
        });
        session.spawned.add(crate.getUniqueId());

        repeat(session, 1L, ticks, step -> {
            List<Player> watching = targets(session);
            if (!crate.isValid()) {
                return;
            }
            Location at = crate.getLocation().subtract(0d, 0.55d, 0d);
            boolean landed = at.getY() <= ground.getY();
            show.showBar(bar, watching, landed ? 0f
                    : (float) ((at.getY() - ground.getY()) / 40d));
            if (!landed) {
                crate.teleport(at);
                world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at, 4, 0.2d, 0.2d, 0.2d, 0.01d);
                world.spawnParticle(Particle.FLAME, at, 6, 0.3d, 0.3d, 0.3d, 0.02d);
                for (Player player : watching) {
                    player.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST,
                            SoundCategory.MASTER, 0.35f, 0.7f);
                }
                return;
            }
            session.spawned.remove(crate.getUniqueId());
            crate.remove();
            burstOpen(session, ground, keys, "SUPPLY DROP");
            show.hideBar(bar, watching);
        });
        session.undo(() -> show.hideBar(bar, online()));
    }

    /**
     * A giant pinata everybody punches. Damage is cancelled and counted instead,
     * so the pinata cannot die, drop slimeballs, or hurt anyone on the way.
     */
    private void pinata(Session session, long ticks, int keys) {
        World world = session.anchor.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("The pinata has no world.");
        }
        Location at = world.getHighestBlockAt(session.anchor).getLocation().add(0.5d, 3.5d, 0.5d);
        announce(session, Component.text("PINATA", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                .append(Component.text(" Hit it. Keep hitting it.", NamedTextColor.WHITE)));

        int hits = Math.max(20, targets(session).size() * 15);
        // 0 keeps the old behaviour of scaling with the crowd.
        int payout = keys > 0 ? keys : 30 + targets(session).size() * 5;
        BossBar bar = show.bar("PINATA", BossBar.Color.PINK);
        Slime body = world.spawn(at, Slime.class, slime -> {
            slime.setSize(8);
            // No AI at all: a pinata that chases people is a mob, not a pinata.
            slime.setAI(false);
            slime.setGravity(false);
            slime.setGlowing(true);
            slime.setSilent(true);
            slime.setPersistent(false);
        });
        session.spawned.add(body.getUniqueId());
        pinatas.put(body.getUniqueId(), new Pinata(bar, hits, payout));

        repeat(session, 5L, ticks, frame -> {
            List<Player> watching = targets(session);
            Pinata state = pinatas.get(body.getUniqueId());
            if (state == null || !body.isValid()) {
                return;
            }
            show.showBar(bar, watching, state.remaining / (float) state.total);
            Location centre = body.getLocation().add(0d, 2d, 0d);
            Location swing = at.clone();
            swing.setY(at.getY() + Math.sin(frame / 6d) * 0.6d);
            swing.setYaw((frame * 9f) % 360f);
            body.teleport(swing);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int spark = 0; spark < 6; spark++) {
                world.spawnParticle(Particle.DUST, centre, 1, 1.6d, 1.6d, 1.6d, 0d,
                        new Particle.DustOptions(randomColour(random), 1.6f));
            }
        });
        session.undo(() -> {
            Pinata state = pinatas.remove(body.getUniqueId());
            if (state != null) {
                show.hideBar(state.bar, online());
            }
        });
    }

    /** A drumroll, a reel of names, and everybody collects. */
    private void jackpot(Session session, int keys) {
        announce(session, Component.text("JACKPOT", NamedTextColor.YELLOW, TextDecoration.BOLD)
                .append(Component.text(" Rolling...", NamedTextColor.WHITE)));
        List<String> reel = List.of(
                "NOTHING", "ONE KEY", "FIVE KEYS", "TEN KEYS", "TWENTY KEYS", "THE LOT"
        );
        int spins = 22;
        for (int spin = 0; spin < spins; spin++) {
            int index = spin;
            // The reel slows as it lands. A constant tick reads as a loading bar.
            long delay = (long) (index * (2 + index * 0.35));
            session.tasks.add(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                String face = reel.get(ThreadLocalRandom.current().nextInt(reel.size()));
                for (Player player : targets(session)) {
                    player.sendActionBar(Component.text("[ " + face + " ]",
                            NamedTextColor.YELLOW, TextDecoration.BOLD));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT,
                            SoundCategory.MASTER, 0.8f, 1.2f);
                }
            }, delay));
        }
        session.tasks.add(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            // 0 means "surprise me", which is what a jackpot should do by default.
            int payout = keys > 0 ? keys : 5 + ThreadLocalRandom.current().nextInt(16);
            for (Player player : targets(session)) {
                player.showTitle(net.kyori.adventure.title.Title.title(
                        Component.text("JACKPOT!", NamedTextColor.GOLD, TextDecoration.BOLD),
                        Component.text(payout + " keys", NamedTextColor.WHITE)
                ));
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP,
                        SoundCategory.MASTER, 1f, 1.2f);
                player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING,
                        player.getLocation().add(0d, 1d, 0d), 80, 1d, 1d, 1d, 0.8d);
            }
            burstOpen(session, session.anchor, payout, "JACKPOT");
        }, 200L));
    }

    /** The payout moment shared by the drop, the pinata and the jackpot. */
    private void burstOpen(Session session, Location where, int keys, String label) {
        World world = where.getWorld();
        if (world == null) {
            return;
        }
        world.spawnParticle(Particle.EXPLOSION_EMITTER, where, 3, 1d, 1d, 1d, 0d);
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, where, 160, 1.5d, 1.5d, 1.5d, 1.2d);
        for (Player player : targets(session)) {
            player.playSound(where, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.MASTER, 1f, 1.3f);
            player.playSound(where, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 1f, 1f);
        }
        announce(session, Component.text(label + " OPEN!", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(" " + keys + " keys are on the floor.", NamedTextColor.WHITE)));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int index = 0; index < keys; index++) {
            Item drop = world.dropItem(where.clone().add(0d, 1d, 0d), crateItems.key(1));
            drop.setGlowing(true);
            drop.setPickupDelay(20);
            drop.setVelocity(new Vector(
                    random.nextDouble(-0.35d, 0.35d),
                    random.nextDouble(0.35d, 0.75d),
                    random.nextDouble(-0.35d, 0.35d)
            ));
        }
    }

    /** Every hit lands on the counter, never on the pinata's health. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPinataHit(EntityDamageByEntityEvent event) {
        Pinata state = pinatas.get(event.getEntity().getUniqueId());
        if (state == null) {
            return;
        }
        event.setCancelled(true);
        if (state.burst || !(event.getDamager() instanceof Player striker)) {
            return;
        }
        state.remaining = Math.max(0, state.remaining - 1);
        Location at = event.getEntity().getLocation().add(0d, 2d, 0d);
        World world = at.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.DUST, at, 12, 1.2d, 1.2d, 1.2d, 0d,
                    new Particle.DustOptions(Color.fromRGB(255, 90, 200), 1.8f));
        }
        striker.playSound(at, Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, SoundCategory.MASTER, 1f,
                1.2f + (1f - state.remaining / (float) state.total));
        if (state.remaining > 0) {
            return;
        }
        state.burst = true;
        Session owner = active.stream()
                .filter(candidate -> candidate.spawned.contains(event.getEntity().getUniqueId()))
                .findFirst()
                .orElse(null);
        event.getEntity().remove();
        pinatas.remove(event.getEntity().getUniqueId());
        show.hideBar(state.bar, online());
        if (owner != null) {
            burstOpen(owner, at, state.keys, "PINATA");
        }
    }


    // ---------------------------------------------------------------- Alfredo

    static final double ALFREDO_DEFAULT_HEALTH = 2000d;
    static final int ALFREDO_DEFAULT_KEYS = 60;
    static final int ALFREDO_DEFAULT_DIAMONDS = 128;
    private static final int ALFREDO_BURSTS = 10;
    static final int DEFAULT_AIRDROP_KEYS = 40;
    /**
     * Minecraft caps generic.scale at 16, so this is as large as an entity can
     * legally be. A vanilla zombie is 1.95 blocks, which puts him near 31.
     */
    private static final double ALFREDO_SCALE = 16d;
    /** Roughly how long the test run should take before he falls. */
    private static final long ALFREDO_TEST_SECONDS = 55L;

    /**
     * Spawns the boss.
     *
     * <p>Health is clamped to whatever `spigot.yml` allows rather than being set
     * blindly: Paper silently caps `attribute.maxHealth.max`, so asking for more
     * than the server permits would quietly produce a different fight from the
     * one that was configured. A clamp that says so is better than a surprise.
     */
    private void alfredo(Session session, long ticks, double requestedHealth, int keys, int diamonds) {
        World world = session.anchor.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("Alfredo has nowhere to stand.");
        }
        // Where the operator is standing, not the highest block above them:
        // getHighestBlockAt puts him on the roof when the summon happens indoors,
        // which is how you get a boss nobody can find.
        Location at = session.anchor.clone();
        Zombie boss = world.spawn(at, Zombie.class, zombie -> {
            zombie.setCustomName("Alfredo");
            // The vanilla nameplate is a fixed size and sits just above the head,
            // which at this scale is off the top of the screen. A TextDisplay
            // scaled to match him replaces it.
            zombie.setCustomNameVisible(false);
            zombie.setPersistent(false);
            zombie.setGlowing(true);
            zombie.setRemoveWhenFarAway(false);
            zombie.setShouldBurnInDay(false);
        });
        // Scale is applied after the spawn, not in the consumer above: attribute
        // changes made before the entity joins the world did not survive, which
        // left a normal-sized zombie under a nameplate floating in the sky.
        AttributeInstance scale = boss.getAttribute(Attribute.SCALE);
        if (scale == null) {
            plugin.getLogger().warning("Alfredo has no SCALE attribute; he will be zombie-sized.");
        } else {
            scale.setBaseValue(ALFREDO_SCALE);
        }
        double health = applyBossHealth(boss, requestedHealth);
        session.spawned.add(boss.getUniqueId());

        // Measured, never assumed. getHeight reflects whatever scale actually
        // took, so the nameplate sits on his head even if the attribute was
        // clamped or missing entirely.
        double headroom = boss.getHeight() + 1.5d;
        plugin.getLogger().info("Alfredo spawned at "
                + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ()
                + " scale=" + (scale == null ? "none" : scale.getValue())
                + " height=" + String.format(java.util.Locale.ROOT, "%.1f", boss.getHeight())
                + " health=" + (long) health);

        TextDisplay label = world.spawn(at.clone().add(0d, headroom, 0d), TextDisplay.class, text -> {
            text.setText("ALFREDO");
            text.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            text.setSeeThrough(true);
            text.setPersistent(false);
            text.setViewRange(4f);
            float size = (float) Math.max(3d, boss.getHeight() / 4d);
            text.setTransformation(new Transformation(
                    new org.joml.Vector3f(),
                    new org.joml.Quaternionf(),
                    new org.joml.Vector3f(size, size, size),
                    new org.joml.Quaternionf()
            ));
        });
        session.spawned.add(label.getUniqueId());

        // Nobody fights each other while Alfredo is up. A boss brawl turning
        // into a PvP free-for-all is how an event ends in an argument.
        plugin.suspendPvp();
        session.undo(plugin::restorePvp);

        BossBar bar = show.bar("ALFREDO", BossBar.Color.RED);
        alfredos.put(boss.getUniqueId(), new Alfredo(
                bar, session, Math.max(0, keys), Math.max(0, diamonds), ALFREDO_BURSTS, health
        ));
        announce(session, Component.text("ALFREDO", NamedTextColor.RED, TextDecoration.BOLD)
                .append(Component.text(" has arrived. Hit him until he gives it up.",
                        NamedTextColor.WHITE)));

        // The spectacle. Every piece is one the world guards already cover:
        // effect-only lightning, per-client sky colour, per-client particles.
        repeat(session, 4L, ticks, frame -> {
            if (!boss.isValid() || alfredos.get(boss.getUniqueId()) == null) {
                return;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location centre = boss.getLocation().add(0d, boss.getHeight() / 2d, 0d);
            List<Player> watching = targets(session);
            if (label.isValid()) {
                label.teleport(boss.getLocation().add(0d, boss.getHeight() + 1.5d, 0d));
            }
            for (Player player : watching) {
                // Rainbow sky, client-side only.
                player.setPlayerTime((frame * 700L) % 24_000L, false);
                for (int ring = 0; ring < 14; ring++) {
                    double angle = (Math.PI * 2d / 14d) * ring + frame * 0.3d;
                    player.spawnParticle(Particle.DUST,
                            centre.clone().add(Math.cos(angle) * 3.4d, 0d, Math.sin(angle) * 3.4d),
                            2, 0d, 1.2d, 0d, 0d,
                            new Particle.DustOptions(randomColour(random), 2.2f));
                }
                player.spawnParticle(Particle.SOUL_FIRE_FLAME, centre, 10, 3d, 6d, 3d, 0.02d);
                // The arena, not just the boss. Colour and sparks land all over
                // the ground around every player so the whole place looks alive.
                Location feet = player.getLocation();
                for (int scatter = 0; scatter < 10; scatter++) {
                    Location spot = feet.clone().add(
                            random.nextDouble(-14d, 14d),
                            random.nextDouble(0d, 6d),
                            random.nextDouble(-14d, 14d)
                    );
                    player.spawnParticle(Particle.DUST, spot, 2, 0.6d, 0.6d, 0.6d, 0d,
                            new Particle.DustOptions(randomColour(random), 2f));
                }
                player.spawnParticle(Particle.ELECTRIC_SPARK, feet, 14, 10d, 4d, 10d, 0.06d);
                player.spawnParticle(Particle.END_ROD, feet, 6, 12d, 5d, 12d, 0.02d);
                if (frame % 3 == 0) {
                    player.spawnParticle(Particle.FIREWORK, feet.clone().add(
                            random.nextDouble(-12d, 12d), random.nextDouble(2d, 8d),
                            random.nextDouble(-12d, 12d)), 8, 0.4d, 0.4d, 0.4d, 0.08d);
                }
            }
            if (frame % 12 == 0) {
                world.strikeLightningEffect(boss.getLocation().add(
                        random.nextDouble(-6d, 6d), 0d, random.nextDouble(-6d, 6d)
                ));
                for (Player player : watching) {
                    player.playSound(centre, Sound.ENTITY_ENDER_DRAGON_GROWL,
                            SoundCategory.MASTER, 0.5f, 0.5f);
                }
            }
            show.showBar(bar, watching, (float) (boss.getHealth() / Math.max(1d, health)));
        });
        forTargets(session, player -> session.undoFor(player, player::resetPlayerTime));
        session.undo(() -> {
            Alfredo state = alfredos.remove(boss.getUniqueId());
            if (state != null) {
                show.hideBar(state.bar, online());
            }
        });
    }

    /**
     * Beats Alfredo up on the operator's behalf so the whole event — bursts,
     * finale and cleanup — can be watched end to end without swinging at him
     * for five minutes, or needing a second player.
     */
    private void startAlfredoSelfTest(Session session) {
        for (Map.Entry<UUID, Alfredo> entry : Map.copyOf(alfredos).entrySet()) {
            Alfredo state = entry.getValue();
            if (state.session != session) {
                continue;
            }
            state.autoDamage = true;
            UUID id = entry.getKey();
            // One tick of damage a second, sized to land him at zero at the end.
            double perSecond = state.maxHealth / ALFREDO_TEST_SECONDS;
            repeat(session, 20L, ALFREDO_TEST_SECONDS * 20L + 40L, tick -> {
                Entity entity = plugin.getServer().getEntity(id);
                if (!(entity instanceof Zombie boss) || !boss.isValid()) {
                    return;
                }
                double left = boss.getHealth() - perSecond;
                if (left <= 0d) {
                    // setHealth(0) fires EntityDeathEvent, so the finale runs
                    // exactly as it would for a real kill.
                    boss.setHealth(0d);
                    return;
                }
                boss.setHealth(left);
                onAlfredoHurt(boss);
            });
        }
    }

    /** @return the health he actually got, which may be less than asked for */
    private double applyBossHealth(Zombie boss, double requested) {
        AttributeInstance attribute = boss.getAttribute(Attribute.MAX_HEALTH);
        if (attribute == null) {
            return boss.getHealth();
        }
        double wanted = Math.max(20d, requested);
        attribute.setBaseValue(wanted);
        // Paper clamps to spigot.yml's attribute.maxHealth.max without a word.
        double granted = attribute.getValue();
        if (granted < wanted) {
            plugin.getLogger().warning("Alfredo asked for " + (long) wanted
                    + " health but this server caps it at " + (long) granted
                    + " (spigot.yml attribute.maxHealth.max).");
        }
        boss.setHealth(granted);
        return granted;
    }

    /**
     * Every live-control command works on whichever Alfredos are up. There is
     * normally one, but summoning a second is allowed and both should obey.
     *
     * @throws IllegalArgumentException when nobody is fighting, so the operator
     *         gets told rather than seeing a silent no-op
     */
    private List<Zombie> liveAlfredos() {
        List<Zombie> found = new ArrayList<>();
        for (UUID id : Map.copyOf(alfredos).keySet()) {
            Entity entity = plugin.getServer().getEntity(id);
            if (entity instanceof Zombie boss && boss.isValid()) {
                found.add(boss);
            }
        }
        if (found.isEmpty()) {
            throw new IllegalArgumentException("No Alfredo is fighting right now.");
        }
        return found;
    }

    /** Replaces what he still owes, split the same way a fresh spawn is. */
    String setAlfredoLoot(Integer keys, Integer diamonds) {
        int touched = 0;
        for (Zombie boss : liveAlfredos()) {
            Alfredo state = alfredos.get(boss.getUniqueId());
            if (state == null) {
                continue;
            }
            if (keys != null) {
                state.burstKeys = keys / 2;
                state.finaleKeys = keys - state.burstKeys;
            }
            if (diamonds != null) {
                state.burstDiamonds = diamonds / 2;
                state.finaleDiamonds = diamonds - state.burstDiamonds;
            }
            touched++;
        }
        return "Alfredo now owes "
                + (keys != null ? keys + " keys" : "the same keys")
                + " and "
                + (diamonds != null ? diamonds + " diamonds" : "the same diamonds")
                + " (" + touched + " boss(es))";
    }

    /** Tops up what he is carrying without disturbing what he has already paid. */
    String addAlfredoLoot(int keys, int diamonds) {
        for (Zombie boss : liveAlfredos()) {
            Alfredo state = alfredos.get(boss.getUniqueId());
            if (state == null) {
                continue;
            }
            // Straight onto the finale pile: topping up mid-fight should make
            // the ending bigger, not silently reshuffle bursts already scheduled.
            state.finaleKeys += Math.max(0, keys);
            state.finaleDiamonds += Math.max(0, diamonds);
        }
        return "Added " + keys + " keys and " + diamonds + " diamonds to Alfredo's finale";
    }

    /** Fires one burst immediately, whatever his health is. */
    String forceAlfredoBurst() {
        int fired = 0;
        for (Zombie boss : liveAlfredos()) {
            Alfredo state = alfredos.get(boss.getUniqueId());
            if (state == null || state.burstsLeft <= 0) {
                continue;
            }
            // Move the threshold above his current health so the normal path
            // fires on the next damage tick, keeping one implementation of a burst.
            state.nextBurstAt = boss.getHealth() + 1d;
            onAlfredoHurt(boss);
            fired++;
        }
        if (fired == 0) {
            throw new IllegalArgumentException("Alfredo has no bursts left to give.");
        }
        return "Forced a burst out of " + fired + " Alfredo(s)";
    }

    /** Ends the fight now, running the full finale rather than deleting him. */
    String killAlfredo() {
        int killed = 0;
        for (Zombie boss : liveAlfredos()) {
            // setHealth(0) fires EntityDeathEvent, so the rain, the announcement
            // and the cleanup all happen exactly as they would for a real kill.
            boss.setHealth(0d);
            killed++;
        }
        return "Dropped " + killed + " Alfredo(s)";
    }

    String alfredoStatus() {
        StringBuilder text = new StringBuilder();
        for (Zombie boss : liveAlfredos()) {
            Alfredo state = alfredos.get(boss.getUniqueId());
            if (state == null) {
                continue;
            }
            if (text.length() > 0) {
                text.append(" | ");
            }
            text.append((long) boss.getHealth()).append("/").append((long) state.maxHealth)
                    .append(" hp, ").append(state.burstsLeft).append(" bursts left, ")
                    .append(state.burstKeys + state.finaleKeys).append(" keys and ")
                    .append(state.burstDiamonds + state.finaleDiamonds).append(" diamonds unpaid");
        }
        return text.toString();
    }

    /** Retunes a live Alfredo, rescaling the remaining bursts across what is left. */
    String retuneAlfredo(double health) {
        if (alfredos.isEmpty()) {
            throw new IllegalArgumentException("No Alfredo is fighting right now.");
        }
        int retuned = 0;
        for (Map.Entry<UUID, Alfredo> entry : Map.copyOf(alfredos).entrySet()) {
            Entity entity = plugin.getServer().getEntity(entry.getKey());
            if (!(entity instanceof Zombie boss) || !boss.isValid()) {
                continue;
            }
            Alfredo state = entry.getValue();
            double granted = applyBossHealth(boss, health);
            state.maxHealth = granted;
            // Spread whatever bursts are left over whatever health is left, so
            // raising his health mid-fight does not skip the rest of the loot.
            state.nextBurstAt = granted - (granted / (state.burstsLeft + 1));
            retuned++;
        }
        if (retuned == 0) {
            throw new IllegalArgumentException("No Alfredo is fighting right now.");
        }
        return "Retuned " + retuned + " Alfredo(s) to " + (long) health + " health";
    }

    /**
     * Every hit on Alfredo. His own attacks are cancelled elsewhere; this only
     * meters damage he takes and pays out as he crosses each threshold.
     */
    private void onAlfredoHurt(Entity entity) {
        Alfredo state = alfredos.get(entity.getUniqueId());
        if (state == null || state.finished || !(entity instanceof Zombie boss)) {
            return;
        }
        double health = boss.getHealth();
        show.showBar(state.bar, targets(state.session),
                (float) (health / Math.max(1d, state.maxHealth)));
        if (health > state.nextBurstAt || state.burstsLeft <= 0) {
            return;
        }
        state.burstsLeft--;
        // A slice of what is left, so the configured total is what actually drops.
        int keys = state.burstsLeft == 0 ? state.burstKeys
                : Math.max(0, state.burstKeys / (state.burstsLeft + 1));
        int diamonds = state.burstsLeft == 0 ? state.burstDiamonds
                : Math.max(0, state.burstDiamonds / (state.burstsLeft + 1));
        state.burstKeys -= keys;
        state.burstDiamonds -= diamonds;
        state.nextBurstAt = state.burstsLeft <= 0
                ? -1d
                : health - (health / (state.burstsLeft + 1));
        spill(state.session, boss.getLocation().add(0d, 2d, 0d), keys, diamonds, false);
    }

    /** The finale: whatever he is still carrying, all at once. */
    private void onAlfredoDown(Zombie boss) {
        Alfredo state = alfredos.remove(boss.getUniqueId());
        if (state == null || state.finished) {
            return;
        }
        state.finished = true;
        show.hideBar(state.bar, online());
        Location where = boss.getLocation().add(0d, 2d, 0d);
        announce(state.session, Component.text("ALFREDO IS DOWN!", NamedTextColor.GOLD,
                        TextDecoration.BOLD)
                .append(Component.text(" Everything he had is in the air.", NamedTextColor.WHITE)));
        World world = where.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.EXPLOSION_EMITTER, where, 8, 2d, 2d, 2d, 0d);
            for (int bolt = 0; bolt < 8; bolt++) {
                world.strikeLightningEffect(where.clone().add(
                        ThreadLocalRandom.current().nextDouble(-7d, 7d), 0d,
                        ThreadLocalRandom.current().nextDouble(-7d, 7d)
                ));
            }
        }
        // Anything the bursts did not manage to spend rides along with it.
        spill(state.session, where,
                state.finaleKeys + state.burstKeys,
                state.finaleDiamonds + state.burstDiamonds, true);
    }

    /**
     * Throws loot into the air. The finale arcs it much wider and higher, which
     * is what turns a pile on the floor into rain.
     */
    private void spill(Session session, Location where, int keys, int diamonds, boolean finale) {
        World world = where.getWorld();
        if (world == null || (keys <= 0 && diamonds <= 0)) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double spread = finale ? 0.9d : 0.35d;
        double lift = finale ? 1.4d : 0.6d;
        for (Player player : targets(session)) {
            player.playSound(where, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.MASTER,
                    1f, finale ? 0.8f : 1.4f);
            if (finale) {
                player.playSound(where, Sound.UI_TOAST_CHALLENGE_COMPLETE,
                        SoundCategory.MASTER, 1f, 1f);
            }
            player.spawnParticle(Particle.TOTEM_OF_UNDYING, where,
                    finale ? 400 : 90, 2d, 2d, 2d, finale ? 1.6d : 0.7d);
        }
        List<ItemStack> loot = new ArrayList<>();
        for (int index = 0; index < keys; index++) {
            loot.add(crateItems.key(1));
        }
        for (int index = 0; index < diamonds; index++) {
            loot.add(new ItemStack(Material.DIAMOND, 1));
        }
        for (ItemStack stack : loot) {
            Item drop = world.dropItem(where, stack);
            drop.setGlowing(true);
            drop.setPickupDelay(20);
            drop.setVelocity(new Vector(
                    random.nextDouble(-spread, spread),
                    random.nextDouble(lift * 0.5d, lift),
                    random.nextDouble(-spread, spread)
            ));
        }
        if (!finale) {
            announce(session, Component.text("Alfredo drops loot!", NamedTextColor.YELLOW,
                    TextDecoration.BOLD));
        }
    }


    /** Whether a player swung this, directly or through something they fired. */
    private static boolean dealtByPlayer(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return false;
        }
        Entity damager = byEntity.getDamager();
        return damager instanceof Player
                || (damager instanceof org.bukkit.entity.Projectile projectile
                        && projectile.getShooter() instanceof Player);
    }

    /** Alfredo never hurts anybody. A boss that kills players is a different feature. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAlfredoSwings(EntityDamageByEntityEvent event) {
        if (alfredos.containsKey(event.getDamager().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Meters damage he takes. Read on the next tick, because during the event his
     * health is still the value from before the hit lands.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAlfredoTakesDamage(EntityDamageEvent event) {
        if (!alfredos.containsKey(event.getEntity().getUniqueId())) {
            return;
        }
        // Scaled six times over, he stands in the ceiling almost anywhere. Left
        // to the world he would suffocate to death seconds after arriving, and
        // the fight would be over before anybody swung at him.
        if (!dealtByPlayer(event)) {
            event.setCancelled(true);
            return;
        }
        Entity boss = event.getEntity();
        plugin.getServer().getScheduler().runTask(plugin, () -> onAlfredoHurt(boss));
    }

    /** His own drops are replaced entirely by the budget he was given. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAlfredoDies(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Zombie boss)
                || !alfredos.containsKey(boss.getUniqueId())) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        Alfredo state = alfredos.get(boss.getUniqueId());
        onAlfredoDown(boss);
        if (state != null) {
            // The fight is the event. Once he is down, stop the sky cycling.
            plugin.getServer().getScheduler().runTaskLater(plugin, state.session::end, 60L);
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
