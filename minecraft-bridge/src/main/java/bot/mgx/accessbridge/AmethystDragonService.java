package bot.mgx.accessbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.Orientable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.noise.SimplexNoiseGenerator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/** Scheduled, cooperative Amethyst Dragon arena and its post-kill crate phase. */
final class AmethystDragonService implements Listener, CommandExecutor, TabCompleter {
    static final String WORLD_NAME = "mgx_amethyst_dragon_event";
    private static final TextColor AMETHYST = TextColor.color(0xB56CFF);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final Particle.DustOptions BRIGHT = new Particle.DustOptions(
            Color.fromRGB(202, 116, 255), 1.25f);
    private static final Particle.DustOptions DARK = new Particle.DustOptions(
            Color.fromRGB(92, 32, 180), 1.0f);
    private static final String DISPLAY_TAG = "mgx_dragon_display";
    private static final String CRYSTAL_TAG = "mgx_dragon_crystal";
    private static final String DRAGON_TAG = "mgx_amethyst_dragon";
    private static final String MINION_TAG = "mgx_dragon_minion";
    private static final String KEY_EFFECT_TAG = "mgx_dragon_key_effect";
    private static final String CRATE_LABEL_TAG = "mgx_dragon_crate_label";
    private static final String CRATE_COUNTDOWN_TAG = "mgx_dragon_crate_countdown";
    private static final String PORTAL_DISPLAY_TAG = "mgx_dragon_portal_display";
    private static final String PORTAL_STATUS_TAG = "mgx_dragon_portal_status";

    enum Phase { WAITING, PORTAL_OPEN, SUMMONING, FIGHT, VICTORY, REWARDS }

    private record Portal(UUID worldId, double x, double y, double z, float yaw) {
        Location location() {
            World world = Bukkit.getWorld(worldId);
            return world == null ? null : new Location(world, x, y, z);
        }
    }

    private record PillarSpec(int index, int x, int z, int base, int top, int width) {}

    private static final class RunStats {
        double damage;
        int crystals;
        int keys;
        long enteredAt;
        long lastActive;
        long persistedDamage;
    }

    private final MGXAccessBridge plugin;
    private final GameVariableStore variables;
    private final CrateItems items;
    private final CrateService crates;
    private final AmethystItemService amethystItems;
    private final AmethystProgressStore progress;
    private final ClanBattleService clanBattles;
    private final AmethystMobService amethystMobs;
    private final Path portalFile;
    private final NamespacedKey eggKey;
    private Portal portal;
    private Phase phase = Phase.WAITING;
    private Instant scheduledAt;
    private long phaseEndsAt;
    private World arena;
    private EnderDragon dragon;
    private UUID lastDragonAttacker;
    private double rewardedDamage;
    private double dragonMaximumHealth;
    private double dragonHealth;
    private double dragonHealthScale = 1d;
    private BossBar admissionBar;
    private BossBar dragonBar;
    private long lastAggressiveAttackAt;
    private long lastMinionWaveAt;
    private long lastChaosAt;
    private int effectFrame;
    private Location rewardChest;
    private Location returnGate;
    private long runGeneration;
    private final List<PillarSpec> pillarSpecs = new ArrayList<>();
    private final Set<UUID> entrants = new HashSet<>();
    private final Set<UUID> departed = new HashSet<>();
    private final Map<UUID, RunStats> stats = new HashMap<>();
    private final Set<String> claimableEggs = new HashSet<>();
    private final Set<Location> portalBlocks = new HashSet<>();
    private final Set<Location> rewardStructureBlocks = new HashSet<>();
    private BukkitTask ticker;
    private BukkitTask summoningTask;

    AmethystDragonService(
            MGXAccessBridge plugin,
            GameVariableStore variables,
            CrateItems items,
            CrateService crates,
            AmethystItemService amethystItems,
            AmethystProgressStore progress,
            ClanBattleService clanBattles,
            AmethystMobService amethystMobs
    ) throws IOException {
        this.plugin = plugin;
        this.variables = variables;
        this.items = items;
        this.crates = crates;
        this.amethystItems = amethystItems;
        this.progress = progress;
        this.clanBattles = clanBattles;
        this.amethystMobs = amethystMobs;
        this.portalFile = plugin.getDataFolder().toPath().resolve("dragon-portal.json");
        this.eggKey = new NamespacedKey(plugin, "amethyst_dragon_egg");
        loadPortal();
        CrateKind.dragonEndSource(() -> phaseEndsAt);
        variables.onChange(key -> {
            if (!key.startsWith("dragon-event.")) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (key.startsWith("dragon-event.portal-") || key.equals("dragon-event.ended-hologram")) {
                    refreshPortalDisplay();
                    setPortalLit(phase == Phase.PORTAL_OPEN);
                }
                if (phase == Phase.WAITING && (key.equals("dragon-event.schedule-utc")
                        || key.equals("dragon-event.enabled"))) {
                    scheduledAt = nextEvent(Instant.now());
                }
                if (arena != null && key.equals("dragon-event.border-size")) {
                    arena.getWorldBorder().setSize(variables.integer("dragon-event.border-size"));
                }
            });
        });
    }

    void start() {
        stop();
        CrateKind.dragonEndSource(() -> phaseEndsAt);
        arena = Bukkit.getWorld(WORLD_NAME);
        if (arena != null && !arena.getPlayers().isEmpty()) {
            sendEveryoneHome();
        }
        if (arena != null) {
            clearArenaEntities();
            clearRewardArea();
        }
        phase = Phase.WAITING;
        scheduledAt = nextEvent(Instant.now());
        refreshPortalDisplay();
        ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    void stop() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        cancelSummoningTask();
        hideAdmissionBar();
        hideDragonBar();
        hideVanillaDragonBar();
        CrateKind.dragonAvailableSource(() -> false);
        CrateKind.dragonEndSource(() -> 0L);
        setPortalLit(false);
        clearDisplays();
    }

    boolean crateAvailable() {
        return phase == Phase.REWARDS && System.currentTimeMillis() < phaseEndsAt;
    }

    boolean canOpenCrate(Player player) {
        return player != null && crateAvailable() && isArena(player.getWorld())
                && entrants.contains(player.getUniqueId()) && !departed.contains(player.getUniqueId());
    }

    boolean isArena(World world) {
        return world != null && WORLD_NAME.equals(world.getName());
    }

    boolean handleRespawn(PlayerRespawnEvent event) {
        if (!isArena(event.getPlayer().getWorld())
                || (phase != Phase.SUMMONING && phase != Phase.FIGHT
                && phase != Phase.VICTORY && phase != Phase.REWARDS)) {
            return false;
        }
        event.setRespawnLocation(arenaSpawn());
        return true;
    }

    boolean handlePortal(org.bukkit.event.player.PlayerPortalEvent event) {
        if (phase == Phase.PORTAL_OPEN && portal != null && near(
                event.getFrom(), portal.location(), variables.integer("dragon-event.portal-entry-radius"))) {
            event.setCancelled(true);
            enter(event.getPlayer());
            return true;
        }
        if (!isArena(event.getFrom().getWorld())) return false;
        event.setCancelled(true);
        if (phase == Phase.REWARDS) {
            leave(event.getPlayer());
        } else {
            event.getPlayer().sendActionBar(Component.text(
                    "The return portal opens after the Dragon falls.", NamedTextColor.RED));
        }
        return true;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (phase == Phase.WAITING) {
            if (!variables.bool("dragon-event.enabled") || Instant.now().isAfter(eventEnd())) {
                scheduledAt = nextEvent(Instant.now().plus(Duration.ofMinutes(1)));
            } else {
                if (scheduledAt == null || !scheduledAt.isAfter(Instant.now().minusSeconds(30))) {
                    scheduledAt = nextEvent(Instant.now());
                }
                long openAt = scheduledAt.toEpochMilli()
                        - variables.integer("dragon-event.portal-open-minutes") * 60_000L;
                if (now >= openAt && now < scheduledAt.toEpochMilli()) {
                    openPortal();
                }
            }
        } else if (phase == Phase.PORTAL_OPEN && now >= scheduledAt.toEpochMilli()) {
            beginSummoning();
        } else if (phase == Phase.SUMMONING && now >= phaseEndsAt) {
            finishSummoningImmediately();
        } else if (phase == Phase.FIGHT && now >= phaseEndsAt) {
            finishRun(false, null);
        } else if (phase == Phase.REWARDS && now >= phaseEndsAt) {
            closeRewards();
        }
        if (phase == Phase.PORTAL_OPEN) updateAdmissionBar();
        updateDisplays();
        pulseEffects();
    }

    private Instant eventEnd() {
        return Instant.ofEpochSecond((long) variables.decimal("amethyst-events.ends-at"));
    }

    Instant nextEvent(Instant after) {
        List<LocalTime> times = schedule();
        LocalDate day = after.atZone(ZoneOffset.UTC).toLocalDate();
        for (int offset = 0; offset < 3; offset++) {
            LocalDate candidateDay = day.plusDays(offset);
            for (LocalTime time : times) {
                Instant candidate = candidateDay.atTime(time).toInstant(ZoneOffset.UTC);
                if (candidate.isAfter(after)) return candidate;
            }
        }
        return day.plusDays(1).atTime(times.getFirst()).toInstant(ZoneOffset.UTC);
    }

    private List<LocalTime> schedule() {
        List<LocalTime> parsed = new ArrayList<>();
        for (String value : variables.string("dragon-event.schedule-utc").split(",")) {
            try {
                parsed.add(LocalTime.parse(value.strip(), CLOCK));
            } catch (DateTimeParseException ignored) {
                plugin.getLogger().warning("Ignoring invalid Dragon UTC time: " + value);
            }
        }
        if (parsed.size() != 3 || parsed.stream().distinct().count() != 3L) {
            plugin.getLogger().warning("Dragon schedule must contain exactly three UTC times; using 03:00,11:00,19:00.");
            parsed.clear();
            parsed.addAll(List.of(LocalTime.of(3, 0), LocalTime.of(11, 0), LocalTime.of(19, 0)));
        }
        return parsed.stream().distinct().sorted().toList();
    }

    private void openPortal() {
        if (portal == null || portal.location() == null) {
            plugin.getLogger().warning("The scheduled Amethyst Dragon portal could not open because no portal is registered.");
            scheduledAt = nextEvent(scheduledAt.plusSeconds(30));
            return;
        }
        prepareRun();
        phase = Phase.PORTAL_OPEN;
        applyArenaSky();
        refreshPortalDisplay();
        setPortalLit(true);
        createAdmissionBar();
        announce(render(variables.string("dragon-event.portal-open-message"),
                "minutes", String.valueOf(variables.integer("dragon-event.portal-open-minutes"))),
                configuredSound("dragon-event.portal-open-sound", Sound.BLOCK_BEACON_ACTIVATE));
        portalTransition(true);
    }

    private void prepareRun() {
        runGeneration++;
        hideDragonBar();
        recreateArenaForRun();
        prepareArena();
        entrants.clear();
        departed.clear();
        stats.clear();
        claimableEggs.clear();
        rewardedDamage = 0d;
        dragonMaximumHealth = 0d;
        dragonHealth = 0d;
        dragonHealthScale = 1d;
        lastDragonAttacker = null;
    }

    private void startImmediately(CommandSender sender) {
        if (phase == Phase.PORTAL_OPEN) {
            beginSummoning();
            sender.sendMessage("Dragon admission closed; summoning started immediately.");
            return;
        }
        if (phase != Phase.WAITING) {
            sender.sendMessage("Dragon event is already " + phase.name().toLowerCase(Locale.ROOT) + ".");
            return;
        }
        prepareRun();
        phase = Phase.PORTAL_OPEN;
        scheduledAt = Instant.now();
        if (sender instanceof Player player) enter(player);
        beginSummoning();
        sender.sendMessage("Dragon arena prepared; summoning started immediately.");
    }

    private void beginSummoning() {
        if (arena == null) ensureArena();
        hideAdmissionBar();
        portalTransition(false);
        setPortalLit(false);
        phase = Phase.SUMMONING;
        applyArenaSky();
        phaseEndsAt = System.currentTimeMillis()
                + variables.integer("dragon-event.summoning-timeout-seconds") * 1000L;
        announce(variables.string("dragon-event.portal-closed-message"),
                configuredSound("dragon-event.portal-closed-sound", Sound.BLOCK_END_PORTAL_SPAWN));
        refreshPortalDisplay();
        animatePillars();
    }

    private void spawnDragonAndBeginFight() {
        if (phase != Phase.SUMMONING || arena == null) return;
        cancelSummoningTask();
        phase = Phase.FIGHT;
        applyArenaSky();
        phaseEndsAt = System.currentTimeMillis()
                + variables.integer("dragon-event.fight-minutes") * 60_000L;
        dragonMaximumHealth = variables.integer("dragon-event.maximum-health");
        dragonHealth = dragonMaximumHealth;
        dragon = arena.spawn(new Location(arena, 0.5, 92, 0.5), EnderDragon.class, entity -> {
            entity.addScoreboardTag(DRAGON_TAG);
            var max = entity.getAttribute(Attribute.MAX_HEALTH);
            double physicalHealth = Math.min(dragonMaximumHealth, 2_048d);
            dragonHealthScale = physicalHealth / dragonMaximumHealth;
            if (max != null) max.setBaseValue(physicalHealth);
            entity.setHealth(physicalHealth);
            entity.customName(Component.text("Amethyst Dragon", AMETHYST, TextDecoration.BOLD));
            entity.setCustomNameVisible(false);
            entity.setRemoveWhenFarAway(false);
            entity.setPhase(EnderDragon.Phase.CIRCLING);
        });
        hideVanillaDragonBar();
        clearVanillaExitPortal();
        scheduleVanillaExitPortalCleanup();
        dragonBar = BossBar.bossBar(
                Component.text(render(render(variables.string("dragon-event.fight-bossbar-text"),
                        "hp", String.valueOf(Math.round(dragonHealth))), "time",
                        duration(phaseEndsAt - System.currentTimeMillis())), AMETHYST, TextDecoration.BOLD),
                1f, variables.barColour("dragon-event.fight-bossbar-color", BossBar.Color.PURPLE),
                BossBar.Overlay.NOTCHED_20
        );
        entrants.stream().map(Bukkit::getPlayer).filter(java.util.Objects::nonNull)
                .forEach(player -> player.showBossBar(dragonBar));
        lastAggressiveAttackAt = 0L;
        lastMinionWaveAt = System.currentTimeMillis();
        lastChaosAt = 0L;
        spawnMinionWave();
        scheduledAt = nextEvent(Instant.now().plusSeconds(30));
        CrateKind.dragonAvailableSource(() -> crateAvailable());
        announce(variables.string("dragon-event.started-message"),
                configuredSound("dragon-event.start-sound", Sound.ENTITY_ENDER_DRAGON_GROWL));
        refreshPortalDisplay();
        if (variables.bool("dragon-event.effects-enabled")) {
            arena.spawnParticle(Particle.DRAGON_BREATH, dragon.getLocation(),
                    variables.integer("dragon-event.spawn-particle-count"), 8, 5, 8, 0.08, 1.0f);
            arena.spawnParticle(Particle.END_ROD, dragon.getLocation(),
                    variables.integer("dragon-event.spawn-particle-count"), 10, 6, 10, 0.12);
            for (int index = 0; index < variables.integer("dragon-event.spawn-lightning-count"); index++) {
                double angle = Math.PI * 2d * index
                        / Math.max(1, variables.integer("dragon-event.spawn-lightning-count"));
                Location burst = new Location(arena,
                        Math.cos(angle) * variables.decimal("dragon-event.spawn-lightning-radius"),
                        76, Math.sin(angle) * variables.decimal("dragon-event.spawn-lightning-radius"));
                arena.spawnParticle(Particle.EXPLOSION_EMITTER, burst, 1);
                arena.spawnParticle(Particle.DUST, burst, 35, 1.2, 2.5, 1.2, .02, BRIGHT);
            }
        }
    }

    private void finishRun(boolean victory, Player killer) {
        if (phase != Phase.FIGHT) return;
        flushDamageStats();
        hideDragonBar();
        hideVanillaDragonBar();
        if (!victory) {
            announce(variables.string("dragon-event.timeout-message"),
                    configuredSound("dragon-event.timeout-sound", Sound.BLOCK_BEACON_DEACTIVATE));
            phase = Phase.WAITING;
            sendEveryoneHome();
            resetSchedule();
            return;
        }
        Location deathAt = dragon == null ? new Location(arena, .5, 86, .5)
                : dragon.getLocation().clone();
        phase = Phase.VICTORY;
        applyArenaSky();
        phaseEndsAt = 0L;
        clearArenaMobs();
        clearVanillaExitPortal();
        scheduleVanillaExitPortalCleanup();
        for (UUID playerId : activeParticipants()) {
            giveKeys(playerId, variables.integer("dragon-event.kill-keys"));
        }
        String name = killer == null ? "the team" : killer.getName();
        announce(render(variables.string("dragon-event.victory-message"), "player", name),
                configuredSound("dragon-event.victory-sound", Sound.UI_TOAST_CHALLENGE_COMPLETE));
        refreshPortalDisplay();
        animateDragonDeath(deathAt);
    }

    /** A staged collapse, cutting beam and expanding shockwave before rewards appear. */
    private void animateDragonDeath(Location deathAt) {
        cancelSummoningTask();
        if (!variables.bool("dragon-event.effects-enabled")) {
            beginRewardPhase(deathAt);
            return;
        }
        long generation = runGeneration;
        int frames = variables.integer("dragon-event.death-animation-frames");
        long interval = variables.integer("dragon-event.death-animation-interval-ticks");
        int[] frame = {0};
        summoningTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (phase != Phase.VICTORY || runGeneration != generation || arena == null) {
                cancelSummoningTask();
                return;
            }
            try {
                dragonDeathFrame(deathAt, frame[0], frames);
                frame[0]++;
                if (frame[0] < frames) return;
                cancelSummoningTask();
                dragonDeathClimax(deathAt);
                beginRewardPhase(deathAt);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE,
                        "The Dragon death sequence failed; opening rewards safely.", exception);
                cancelSummoningTask();
                beginRewardPhase(deathAt);
            }
        }, 0L, interval);
    }

    private void dragonDeathFrame(Location centre, int frame, int frames) {
        double progress = (frame + 1d) / Math.max(1, frames);
        double maximumRadius = variables.decimal("dragon-event.death-animation-radius");
        double radius = Math.max(.6, maximumRadius * (1d - progress));
        int ringPoints = variables.integer("dragon-event.death-ring-points");
        double spin = progress * Math.PI * 8d;
        for (int point = 0; point < ringPoints; point++) {
            double angle = Math.PI * 2d * point / ringPoints + spin;
            double y = Math.sin(angle * 3d + spin) * (1.2d + progress * 3d);
            Location particle = centre.clone().add(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
            arena.spawnParticle(Particle.DUST, particle, 1, 0, 0, 0, 0,
                    (point & 1) == 0 ? BRIGHT : DARK);
            if (point % 6 == 0) arena.spawnParticle(Particle.REVERSE_PORTAL, particle, 1, 0, 0, 0, .02);
        }

        int beamPoints = variables.integer("dragon-event.death-beam-points");
        double beamHeight = variables.decimal("dragon-event.death-beam-height");
        for (int point = 0; point < beamPoints; point++) {
            double fraction = point / (double) Math.max(1, beamPoints - 1);
            double y = (fraction - .35d) * beamHeight;
            Location vertical = centre.clone().add(0, y, 0);
            if (point % 4 == 0) {
                arena.spawnParticle(Particle.END_ROD, vertical, 1, 0, 0, 0, 0);
            } else {
                arena.spawnParticle(Particle.DUST, vertical, 1, 0, 0, 0, 0, BRIGHT);
            }

            double across = (fraction - .5d) * maximumRadius * 2d;
            double angle = progress * Math.PI;
            Location cutting = centre.clone().add(Math.cos(angle) * across,
                    Math.sin(progress * Math.PI) * 2d, Math.sin(angle) * across);
            arena.spawnParticle(Particle.DUST, cutting, 1, 0, 0, 0, 0,
                    point % 3 == 0 ? BRIGHT : DARK);
        }

        if (frame % variables.integer("dragon-event.death-lightning-every-frames") == 0) {
            double angle = spin * .5d;
            int x = (int) Math.round(centre.getX() + Math.cos(angle) * Math.max(3d, radius));
            int z = (int) Math.round(centre.getZ() + Math.sin(angle) * Math.max(3d, radius));
            arena.strikeLightningEffect(new Location(arena, x + .5,
                    arena.getHighestBlockYAt(x, z) + 1, z + .5));
        }
        if (frame % variables.integer("dragon-event.death-sound-every-frames") == 0) {
            arena.playSound(centre,
                    configuredSound("dragon-event.death-pulse-sound", Sound.BLOCK_RESPAWN_ANCHOR_CHARGE),
                    (float) variables.decimal("dragon-event.effect-sound-volume"),
                    (float) Math.min(2d, variables.decimal("dragon-event.death-pulse-pitch")
                            + progress * 1.25d));
        }
    }

    private void dragonDeathClimax(Location centre) {
        int particles = variables.integer("dragon-event.death-climax-particle-count");
        arena.spawnParticle(Particle.EXPLOSION_EMITTER, centre, 1);
        arena.spawnParticle(Particle.DRAGON_BREATH, centre, particles,
                8, 6, 8, .18, 1.0f);
        arena.spawnParticle(Particle.END_ROD, centre, Math.max(1, particles / 2),
                12, 8, 12, .22);
        arena.spawnParticle(Particle.DUST, centre, particles,
                10, 6, 10, .08, BRIGHT);
        double radius = variables.decimal("dragon-event.death-animation-radius");
        int points = variables.integer("dragon-event.death-ring-points") * 2;
        for (int point = 0; point < points; point++) {
            double angle = Math.PI * 2d * point / points;
            Location wave = centre.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            if (point % 3 == 0) {
                arena.spawnParticle(Particle.END_ROD, wave, 1, 0, 0, 0, 0);
            } else {
                arena.spawnParticle(Particle.DUST, wave, 1, 0, 0, 0, 0, BRIGHT);
            }
        }
        arena.playSound(centre,
                configuredSound("dragon-event.death-climax-sound", Sound.ENTITY_WARDEN_SONIC_BOOM),
                (float) variables.decimal("dragon-event.effect-sound-volume"),
                (float) variables.decimal("dragon-event.death-climax-pitch"));
    }

    private void beginRewardPhase(Location deathAt) {
        if (phase != Phase.VICTORY || arena == null) return;
        hideVanillaDragonBar();
        clearVanillaExitPortal();
        phase = Phase.REWARDS;
        phaseEndsAt = System.currentTimeMillis()
                + variables.integer("dragon-event.crate-minutes") * 60_000L;
        dragon = null;
        int x = deathAt.getBlockX();
        int z = deathAt.getBlockZ();
        Location waterfall = new Location(arena, x + .5,
                arena.getHighestBlockYAt(x, z) + 5d, z + .5);
        keyWaterfall(waterfall, variables.integer("dragon-event.death-key-effect-count"));
        refreshPortalDisplay();
        spawnRewardArea();
    }

    private void closeRewards() {
        if (phase != Phase.REWARDS) return;
        if (arena != null && variables.bool("dragon-event.effects-enabled")) {
            Location at = rewardChest == null ? new Location(arena, 5.5, 76.5, 0.5)
                    : rewardChest.clone().add(.5, .5, .5);
            arena.spawnParticle(Particle.REVERSE_PORTAL, at,
                    variables.integer("dragon-event.reward-close-particle-count"),
                    2.5, 2.0, 2.5, 0.08);
        }
        announce(variables.string("dragon-event.crate-closed-message"),
                configuredSound("dragon-event.crate-closed-sound", Sound.BLOCK_BEACON_DEACTIVATE));
        sendEveryoneHome();
        resetSchedule();
    }

    private void resetSchedule() {
        cancelSummoningTask();
        hideAdmissionBar();
        hideDragonBar();
        hideVanillaDragonBar();
        phase = Phase.WAITING;
        phaseEndsAt = 0L;
        dragon = null;
        dragonMaximumHealth = 0d;
        dragonHealth = 0d;
        dragonHealthScale = 1d;
        runGeneration++;
        setPortalLit(false);
        CrateKind.dragonAvailableSource(() -> false);
        scheduledAt = nextEvent(Instant.now().plusSeconds(30));
        clearArenaEntities();
        clearRewardArea();
        refreshPortalDisplay();
    }

    private void ensureArena() {
        World.Environment desired = variables.string("dragon-event.sky-style").equals("END")
                ? World.Environment.THE_END : World.Environment.NORMAL;
        arena = Bukkit.getWorld(WORLD_NAME);
        if (arena != null && arena.getEnvironment() != desired) {
            for (Player player : List.copyOf(arena.getPlayers())) teleportSpawn(player);
            if (!Bukkit.unloadWorld(arena, false)) {
                throw new IllegalStateException("Could not unload the Dragon arena to update its sky.");
            }
            arena = null;
            deleteArenaWorld();
        }
        if (arena == null) {
            arena = Bukkit.createWorld(new WorldCreator(WORLD_NAME)
                    .environment(desired)
                    .generator(new VoidArenaGenerator())
                    .generateStructures(false));
        }
        if (arena == null) throw new IllegalStateException("Could not create the Amethyst Dragon world.");
        hideDragonBar();
        hideVanillaDragonBar();
        arena.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        arena.setGameRule(GameRule.KEEP_INVENTORY, true);
        arena.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        arena.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        arena.setGameRule(GameRule.DO_INSOMNIA, false);
        arena.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
        arena.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
        applyArenaSky();
        arena.getWorldBorder().setCenter(0.5, 0.5);
        arena.getWorldBorder().setSize(variables.integer("dragon-event.border-size"));
        arena.setSpawnLocation(0, 82, 0);
    }

    /**
     * Recreates the disposable event world before every admission period.
     *
     * <p>{@link World#getEntities()} only sees entities in loaded chunks. The terrain
     * pass subsequently loaded other chunks and brought their saved mobs, drops and
     * labels back, so an apparently clean island could still contain an earlier run's
     * entities. Removing the world directory while it is unloaded clears the entity
     * region files as well as the blocks and gives every run one authoritative reset.
     */
    private void recreateArenaForRun() {
        cancelSummoningTask();
        arena = Bukkit.getWorld(WORLD_NAME);
        if (arena != null) {
            for (Player player : List.copyOf(arena.getPlayers())) teleportSpawn(player);
            if (!Bukkit.unloadWorld(arena, false)) {
                throw new IllegalStateException("Could not unload the previous Dragon arena.");
            }
            arena = null;
        }
        deleteArenaWorld();
        ensureArena();
    }

    private void deleteArenaWorld() {
        Path folder = Bukkit.getWorldContainer().toPath().resolve(WORLD_NAME);
        if (!Files.exists(folder)) return;
        try (var paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (IOException | UncheckedIOException exception) {
            throw new IllegalStateException("Could not rebuild the Dragon arena with its configured sky.", exception);
        }
    }

    /** Builds only the island used during the five-minute gathering phase. */
    private void prepareArena() {
        cancelSummoningTask();
        clearArenaEntities();
        clearRewardArea();
        int radius = Math.min(variables.integer("dragon-event.arena-radius"),
                variables.integer("dragon-event.border-size") / 2 - 8);
        SimplexNoiseGenerator noise = new SimplexNoiseGenerator(0xA6E7_4157L);
        for (int x = -radius - 4; x <= radius + 4; x++) {
            for (int z = -radius - 4; z <= radius + 4; z++) {
                double distance = Math.sqrt(x * x + z * z);
                double edge = radius + noise.noise(x * 0.045, z * 0.045) * 7d;
                int surface = 72 + (int) Math.round(noise.noise(x * 0.032, z * 0.032) * 5d)
                        + (distance < 18 ? 2 : 0);
                for (int y = 35; y <= 125; y++) {
                    Block block = arena.getBlockAt(x, y, z);
                    if (distance > edge || y > surface || y < surface - Math.max(5, (int) ((edge - distance) * .55))) {
                        if (block.getType() != Material.AIR) block.setType(Material.AIR, false);
                        continue;
                    }
                    Material material;
                    if (y == surface) {
                        double detail = noise.noise(x * .14, z * .14);
                        material = detail > .48 ? Material.BUDDING_AMETHYST
                                : detail > .08 ? Material.AMETHYST_BLOCK
                                : detail < -.58 ? Material.CALCITE : Material.POLISHED_BLACKSTONE;
                    } else if (y >= surface - 2) {
                        material = ((x * 13 + z * 7 + y) & 7) == 0
                                ? Material.CRYING_OBSIDIAN : Material.BLACKSTONE;
                    } else {
                        material = Material.OBSIDIAN;
                    }
                    block.setType(material, false);
                }
            }
        }
        buildCentralSanctum();
        clearVanillaExitPortal();
        decorateArena(radius, noise);
        pillarSpecs.clear();
        int crystals = variables.integer("dragon-event.crystals");
        for (int index = 0; index < crystals; index++) {
            double angle = Math.PI * 2d * index / crystals;
            int pillarRadius = variables.integer("dragon-event.pillar-radius");
            int x = (int) Math.round(Math.cos(angle) * pillarRadius);
            int z = (int) Math.round(Math.sin(angle) * pillarRadius);
            int height = variables.integer("dragon-event.pillar-base-height")
                    + (index % 5) * variables.integer("dragon-event.pillar-height-step");
            int width = 3 + (index % 3);
            int base = arena.getHighestBlockYAt(x, z) + 1;
            int top = base + height;
            pillarSpecs.add(new PillarSpec(index, x, z, base, top, width));
        }
    }

    private void animatePillars() {
        cancelSummoningTask();
        clearSummoningCrystals();
        if (pillarSpecs.isEmpty()) {
            spawnAllCrystals();
            plugin.getServer().getScheduler().runTask(plugin, this::beginDragonSummoning);
            return;
        }
        int[] pillarIndex = {0};
        int[] nextLayer = {pillarSpecs.getFirst().base()};
        long interval = variables.integer("dragon-event.pillar-animation-interval-ticks");
        summoningTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            try {
                if (phase != Phase.SUMMONING || pillarIndex[0] >= pillarSpecs.size()) {
                    cancelSummoningTask();
                    return;
                }
                PillarSpec spec = pillarSpecs.get(pillarIndex[0]);
                int layers = variables.integer("dragon-event.pillar-layers-per-step");
                for (int layer = 0; layer < layers && nextLayer[0] <= spec.top(); layer++, nextLayer[0]++) {
                    buildPillarLayer(spec, nextLayer[0]);
                }
                pillarRiseEffect(spec, Math.min(nextLayer[0], spec.top() + 1));
                if (nextLayer[0] <= spec.top()) return;
                finishPillar(spec);
                pillarIndex[0]++;
                nextLayer[0] = pillarIndex[0] < pillarSpecs.size()
                        ? pillarSpecs.get(pillarIndex[0]).base() : 0;
                if (pillarIndex[0] < pillarSpecs.size()) return;
                cancelSummoningTask();
                plugin.getServer().getScheduler().runTask(plugin, this::beginDragonSummoning);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE,
                        "The Dragon pillar animation failed; completing the summoning safely.", exception);
                finishSummoningImmediately();
            }
        }, 0L, interval);
    }

    private void buildPillarLayer(PillarSpec spec, int y) {
        for (int px = spec.x() - spec.width(); px <= spec.x() + spec.width(); px++) {
            for (int pz = spec.z() - spec.width(); pz <= spec.z() + spec.width(); pz++) {
                if ((px - spec.x()) * (px - spec.x()) + (pz - spec.z()) * (pz - spec.z())
                        > spec.width() * spec.width()) continue;
                Material material = ((y + spec.index())
                        % variables.integer("dragon-event.pillar-amethyst-band-every-layers") == 0)
                        ? Material.AMETHYST_BLOCK : Material.OBSIDIAN;
                arena.getBlockAt(px, y, pz).setType(material, false);
            }
        }
    }

    private void pillarRiseEffect(PillarSpec spec, int y) {
        if (!variables.bool("dragon-event.effects-enabled")) return;
        Location at = new Location(arena, spec.x() + .5, y + .25, spec.z() + .5);
        int particles = variables.integer("dragon-event.pillar-summon-particle-count");
        arena.spawnParticle(Particle.BLOCK, at, particles, spec.width(), .35, spec.width(), .05,
                Material.AMETHYST_BLOCK.createBlockData());
        arena.spawnParticle(Particle.DUST, at, particles, spec.width(), .5, spec.width(), .02, BRIGHT);
    }

    private void finishPillar(PillarSpec spec) {
        finishPillarStructure(spec);
        Location spawn = new Location(arena, spec.x() + .5, spec.top() + 2d, spec.z() + .5);
        spawnCrystal(spawn);
        if (variables.bool("dragon-event.effects-enabled")) {
            arena.spawnParticle(Particle.FLASH, spawn, 1, 0, 0, 0, 0, BRIGHT.getColor());
            arena.spawnParticle(Particle.END_ROD, spawn,
                    variables.integer("dragon-event.pillar-complete-particle-count"), 2.5, 3, 2.5, .12);
            if (variables.bool("dragon-event.pillar-lightning-enabled")) {
                arena.strikeLightningEffect(spawn);
            }
        }
        arena.playSound(spawn,
                configuredSound("dragon-event.pillar-summon-sound", Sound.BLOCK_AMETHYST_BLOCK_RESONATE),
                (float) variables.decimal("dragon-event.effect-sound-volume"),
                (float) variables.decimal("dragon-event.pillar-summon-pitch"));
    }

    private void finishPillarStructure(PillarSpec spec) {
        arena.getBlockAt(spec.x(), spec.top() + 1, spec.z()).setType(Material.BEDROCK, false);
        if (spec.index() < variables.integer("dragon-event.caged-crystals")) {
            buildCrystalCage(spec.x(), spec.top() + 2, spec.z());
        }
    }

    private void spawnAllCrystals() {
        clearSummoningCrystals();
        for (PillarSpec spec : pillarSpecs) {
            spawnCrystal(new Location(arena, spec.x() + .5, spec.top() + 2d, spec.z() + .5));
        }
    }

    private void clearSummoningCrystals() {
        arena.getEntitiesByClass(EnderCrystal.class).stream()
                .filter(crystal -> crystal.getScoreboardTags().contains(CRYSTAL_TAG))
                .forEach(Entity::remove);
    }

    private void spawnCrystal(Location spawn) {
        arena.spawn(spawn, EnderCrystal.class, crystal -> {
            crystal.addScoreboardTag(CRYSTAL_TAG);
            crystal.setShowingBottom(true);
        });
    }

    private void finishSummoningImmediately() {
        if (phase != Phase.SUMMONING || arena == null) return;
        cancelSummoningTask();
        for (PillarSpec spec : pillarSpecs) {
            for (int y = spec.base(); y <= spec.top(); y++) buildPillarLayer(spec, y);
            finishPillarStructure(spec);
        }
        try {
            spawnAllCrystals();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "The Dragon crystals could not all spawn; continuing the fight instead of stalling.", exception);
        }
        spawnDragonAndBeginFight();
    }

    private void beginDragonSummoning() {
        int[] pulse = {0};
        long interval = variables.integer("dragon-event.dragon-summon-pulse-interval-ticks");
        int pulses = variables.integer("dragon-event.dragon-summon-pulses");
        summoningTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (phase != Phase.SUMMONING) {
                cancelSummoningTask();
                return;
            }
            if (pulse[0] >= pulses) {
                cancelSummoningTask();
                spawnDragonAndBeginFight();
                return;
            }
            dragonSummonPulse(pulse[0]++, pulses);
        }, 0L, interval);
    }

    private void dragonSummonPulse(int pulse, int pulses) {
        Location centre = new Location(arena, .5, 86, .5);
        double progress = (pulse + 1d) / Math.max(1, pulses);
        double radius = variables.decimal("dragon-event.dragon-summon-radius") * progress;
        int particles = variables.integer("dragon-event.dragon-summon-particle-count");
        if (variables.bool("dragon-event.effects-enabled")) {
            arena.spawnParticle(Particle.DRAGON_BREATH, centre, particles,
                    radius, 4d + progress * 8d, radius, .08, 1.0f);
            arena.spawnParticle(Particle.REVERSE_PORTAL, centre, particles,
                    radius, 6d + progress * 10d, radius, .16);
            arena.spawnParticle(Particle.DUST, centre, particles,
                    radius, 5d + progress * 9d, radius, .04, BRIGHT);
            if (pulse % variables.integer("dragon-event.dragon-summon-lightning-every-pulses") == 0) {
                double angle = Math.PI * 2d * pulse / Math.max(1, pulses);
                Location burst = centre.clone().add(Math.cos(angle) * radius, -10,
                        Math.sin(angle) * radius);
                arena.spawnParticle(Particle.EXPLOSION_EMITTER, burst, 1);
                arena.spawnParticle(Particle.END_ROD, burst, Math.max(12, particles / 5),
                        1.2, 4, 1.2, .08);
            }
        }
        arena.playSound(centre,
                configuredSound("dragon-event.dragon-summon-sound", Sound.ENTITY_WITHER_SPAWN),
                (float) variables.decimal("dragon-event.effect-sound-volume"),
                (float) variables.decimal("dragon-event.dragon-summon-pitch"));
    }

    private void cancelSummoningTask() {
        if (summoningTask == null) return;
        BukkitTask task = summoningTask;
        summoningTask = null;
        task.cancel();
    }

    private void buildCentralSanctum() {
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                double distance = Math.sqrt(x * x + z * z);
                if (distance > 12d) continue;
                int y = 75;
                Material floor = distance < 4d ? Material.AMETHYST_BLOCK
                        : ((x + z) & 3) == 0 ? Material.CALCITE : Material.POLISHED_BLACKSTONE;
                arena.getBlockAt(x, y, z).setType(floor, false);
                if (distance > 9d && ((Math.abs(x) + Math.abs(z)) % 3 == 0)) {
                    arena.getBlockAt(x, y + 1, z).setType(Material.AMETHYST_CLUSTER, false);
                }
            }
        }
    }

    private void decorateArena(int radius, SimplexNoiseGenerator noise) {
        int spacing = Math.max(5, variables.integer("dragon-event.decoration-spacing"));
        for (int x = -radius + 7; x <= radius - 7; x += spacing) {
            for (int z = -radius + 7; z <= radius - 7; z += spacing) {
                double distance = Math.sqrt(x * x + z * z);
                if (distance < 15 || distance > radius - 5 || noise.noise(x * .19, z * .19) < .12) continue;
                int y = arena.getHighestBlockYAt(x, z) + 1;
                Material cluster = ((x + z) & 1) == 0 ? Material.LARGE_AMETHYST_BUD : Material.AMETHYST_CLUSTER;
                arena.getBlockAt(x, y, z).setType(cluster, false);
                if (((x * 17 + z * 23) & 7) == 0) {
                    arena.getBlockAt(x, y - 1, z).setType(Material.BUDDING_AMETHYST, false);
                    arena.getBlockAt(x, y + 1, z).setType(Material.AMETHYST_CLUSTER, false);
                }
            }
        }
    }

    private void buildCrystalCage(int x, int y, int z) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) != 2 && Math.abs(dz) != 2) continue;
                arena.getBlockAt(x + dx, y + 2, z + dz).setType(Material.IRON_BARS, false);
                if (Math.abs(dx) == 2 && Math.abs(dz) == 2) {
                    for (int dy = 0; dy <= 3; dy++)
                        arena.getBlockAt(x + dx, y + dy, z + dz).setType(Material.IRON_BARS, false);
                }
            }
        }
    }

    private void spawnMinionWave() {
        if (arena == null || (phase != Phase.SUMMONING && phase != Phase.FIGHT)) return;
        int alive = (int) arena.getEntities().stream()
                .filter(entity -> entity.getScoreboardTags().contains(MINION_TAG)).count();
        int cap = variables.integer("dragon-event.minion-maximum-alive");
        int wanted = Math.min(variables.integer("dragon-event.minions-per-wave"), Math.max(0, cap - alive));
        EntityType[] types = {EntityType.HUSK, EntityType.STRAY, EntityType.IRON_GOLEM};
        for (int index = 0; index < wanted; index++) {
            double angle = Math.PI * 2d * index / Math.max(1, wanted) + ThreadLocalRandom.current().nextDouble(.6);
            double radius = ThreadLocalRandom.current().nextDouble(18d, 48d);
            int x = (int) Math.round(Math.cos(angle) * radius);
            int z = (int) Math.round(Math.sin(angle) * radius);
            int y = arena.getHighestBlockYAt(x, z) + 1;
            Location spawn = new Location(arena, x + .5, y, z + .5);
            EntityType type = types[index % types.length];
            animateMinionSpawn(spawn, type, index);
        }
    }

    private void animateMinionSpawn(Location spawn, EntityType type, int index) {
        long generation = runGeneration;
        if (variables.bool("dragon-event.effects-enabled")) {
            arena.spawnParticle(Particle.REVERSE_PORTAL, spawn.clone().add(0, 1, 0),
                    variables.integer("dragon-event.minion-spawn-particle-count"), .7, 1.1, .7, .08);
            arena.spawnParticle(Particle.DUST, spawn.clone().add(0, .8, 0),
                    variables.integer("dragon-event.minion-spawn-particle-count"), .6, .9, .6, .03, DARK);
        }
        long delay = (long) index * variables.integer("dragon-event.minion-spawn-interval-ticks");
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (runGeneration != generation || arena == null
                    || (phase != Phase.SUMMONING && phase != Phase.FIGHT)) return;
            LivingEntity minion = amethystMobs.deploy(spawn, type);
            if (minion == null) return;
            minion.addScoreboardTag(MINION_TAG);
            if (minion instanceof IronGolem golem) golem.setPlayerCreated(false);
            var maximumHealth = minion.getAttribute(Attribute.MAX_HEALTH);
            if (maximumHealth != null) {
                maximumHealth.setBaseValue(variables.decimal("dragon-event.minion-health"));
                minion.setHealth(maximumHealth.getBaseValue());
            }
            var attackDamage = minion.getAttribute(Attribute.ATTACK_DAMAGE);
            if (attackDamage != null) attackDamage.setBaseValue(
                    variables.decimal("dragon-event.minion-attack-damage"));
            var movement = minion.getAttribute(Attribute.MOVEMENT_SPEED);
            if (movement != null) movement.setBaseValue(normalMinionSpeed(type)
                    * variables.decimal("dragon-event.minion-speed-multiplier"));
            if (minion instanceof Mob mob) {
                mob.setAware(true);
                mob.setAggressive(true);
                nearestParticipant(spawn).ifPresent(mob::setTarget);
            }
            arena.playSound(spawn,
                    configuredSound("dragon-event.minion-spawn-sound", Sound.BLOCK_RESPAWN_ANCHOR_CHARGE),
                    (float) variables.decimal("dragon-event.effect-sound-volume"),
                    (float) variables.decimal("dragon-event.minion-spawn-pitch"));
        }, delay);
    }

    private Optional<Player> nearestParticipant(Location from) {
        return arena.getPlayers().stream()
                .filter(player -> entrants.contains(player.getUniqueId()) && !departed.contains(player.getUniqueId()))
                .min(Comparator.comparingDouble(player -> player.getLocation().distanceSquared(from)));
    }

    private void aggressiveAttack(long now) {
        if (dragon == null || !dragon.isValid()) return;
        long interval = variables.integer("dragon-event.aggressive-attack-seconds") * 1000L;
        if (now - lastAggressiveAttackAt < interval) return;
        List<Player> targets = arena.getPlayers().stream()
                .filter(player -> entrants.contains(player.getUniqueId()) && !departed.contains(player.getUniqueId()))
                .toList();
        if (targets.isEmpty()) return;
        lastAggressiveAttackAt = now;
        Player target = targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
        org.bukkit.util.Vector direction = target.getEyeLocation().toVector()
                .subtract(dragon.getEyeLocation().toVector()).normalize();
        int shots = variables.integer("dragon-event.fireball-volley");
        for (int index = 0; index < shots; index++) {
            DragonFireball fireball = arena.spawn(dragon.getEyeLocation().add(direction.clone().multiply(3d)),
                    DragonFireball.class);
            fireball.setShooter(dragon);
            fireball.setDirection(direction.clone().add(new org.bukkit.util.Vector(
                    ThreadLocalRandom.current().nextDouble(-.08, .08),
                    ThreadLocalRandom.current().nextDouble(-.05, .05),
                    ThreadLocalRandom.current().nextDouble(-.08, .08))));
        }
        dragon.setPhase(EnderDragon.Phase.CHARGE_PLAYER);
        dragon.setVelocity(direction.multiply(variables.decimal("dragon-event.aggression-speed")));
        arena.spawnParticle(Particle.DRAGON_BREATH, dragon.getEyeLocation(),
                variables.integer("dragon-event.attack-particle-count"), 1.4, 1.0, 1.4, .08, 1.0f);
    }

    private void driveMinions() {
        if (arena == null) return;
        for (Entity entity : arena.getEntities()) {
            if (!(entity instanceof Mob mob) || !entity.getScoreboardTags().contains(MINION_TAG)) continue;
            mob.setAware(true);
            mob.setAggressive(true);
            Player current = mob.getTarget() instanceof Player player ? player : null;
            if (current == null || !current.isOnline() || departed.contains(current.getUniqueId())) {
                nearestParticipant(entity.getLocation()).ifPresent(mob::setTarget);
            }
        }
    }

    /** Vanilla base speeds, reapplied so saved or externally altered attributes cannot compound. */
    static double normalMinionSpeed(EntityType type) {
        return switch (type) {
            case HUSK -> 0.23d;
            case STRAY, IRON_GOLEM -> 0.25d;
            default -> throw new IllegalArgumentException("Not a Dragon minion type: " + type);
        };
    }

    /** Removes every hostile or neutral mob as soon as the Dragon fight is won. */
    private void clearArenaMobs() {
        if (arena == null) return;
        int radius = variables.integer("dragon-event.arena-radius") + 16;
        int chunks = (radius + 15) >> 4;
        Set<UUID> removed = new HashSet<>();
        for (org.bukkit.Chunk chunk : List.of(arena.getLoadedChunks())) {
            removeMobs(chunk.getEntities(), removed);
        }
        for (int chunkX = -chunks; chunkX <= chunks; chunkX++) {
            for (int chunkZ = -chunks; chunkZ <= chunks; chunkZ++) {
                boolean loaded = arena.isChunkLoaded(chunkX, chunkZ);
                org.bukkit.Chunk chunk = arena.getChunkAt(chunkX, chunkZ);
                removeMobs(chunk.getEntities(), removed);
                if (!loaded) arena.unloadChunkRequest(chunkX, chunkZ);
            }
        }
    }

    private static void removeMobs(Entity[] entities, Set<UUID> removed) {
        for (Entity entity : entities) {
            if (entity instanceof Mob && removed.add(entity.getUniqueId())) entity.remove();
        }
    }

    private void chaosStrike(long now) {
        long interval = variables.integer("dragon-event.chaos-interval-seconds") * 1_000L;
        if (now - lastChaosAt < interval || arena == null || dragon == null) return;
        List<Player> targets = arena.getPlayers().stream()
                .filter(player -> entrants.contains(player.getUniqueId()) && !departed.contains(player.getUniqueId()))
                .toList();
        if (targets.isEmpty()) return;
        lastChaosAt = now;
        int strikes = variables.integer("dragon-event.chaos-strikes");
        for (int index = 0; index < strikes; index++) {
            Player target = targets.get(index % targets.size());
            double spread = variables.decimal("dragon-event.chaos-strike-spread");
            Location strike = target.getLocation().clone().add(
                    ThreadLocalRandom.current().nextDouble(-spread, spread), 0,
                    ThreadLocalRandom.current().nextDouble(-spread, spread));
            arena.spawnParticle(Particle.DUST, strike.clone().add(0, 1, 0),
                    variables.integer("dragon-event.chaos-particle-count"), .45, 1.4, .45, .02, BRIGHT);
            int delay = variables.integer("dragon-event.chaos-warning-ticks");
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (phase != Phase.FIGHT || !target.isOnline() || !isArena(target.getWorld())) return;
                arena.spawnParticle(Particle.EXPLOSION_EMITTER, strike.clone().add(0, 1, 0), 1);
                arena.spawnParticle(Particle.DRAGON_BREATH, strike.clone().add(0, 1, 0),
                        variables.integer("dragon-event.chaos-particle-count"), 1.2, 1.5, 1.2, .08, 1.0f);
                if (target.getLocation().distanceSquared(strike)
                        <= variables.decimal("dragon-event.chaos-hit-radius")
                        * variables.decimal("dragon-event.chaos-hit-radius")) {
                    target.damage(variables.decimal("dragon-event.chaos-damage"), dragon);
                }
            }, delay);
        }
        arena.playSound(dragon.getLocation(),
                configuredSound("dragon-event.chaos-sound", Sound.ENTITY_WARDEN_SONIC_BOOM),
                (float) variables.decimal("dragon-event.effect-sound-volume"),
                (float) variables.decimal("dragon-event.chaos-pitch"));
    }

    private void spawnRewardArea() {
        int gateY = arena.getHighestBlockYAt(0, 0) + 1;
        Location center = new Location(arena, .5, gateY + .1, .5);
        returnGate = center.clone();
        for (int x = -4; x <= 4; x++) {
            for (int y = gateY - 2; y <= gateY + 6; y++) {
                for (int z = -4; z <= 4; z++) {
                    Block block = arena.getBlockAt(x, y, z);
                    if (block.getType() == Material.DRAGON_EGG) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
        int chestX = variables.integer("dragon-event.reward-crate-x");
        int chestZ = variables.integer("dragon-event.reward-crate-z");
        int chestY = arena.getHighestBlockYAt(chestX, chestZ) + 1;
        rewardChest = new Location(arena, chestX, chestY, chestZ);
        int steps = variables.integer("dragon-event.reward-spawn-animation-steps");
        long interval = variables.integer("dragon-event.reward-spawn-animation-interval-ticks");
        Location animatedChest = rewardChest.clone().add(.5, .5, .5);
        for (int step = 0; step < steps; step++) {
            int frame = step;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> rewardSpawnFrame(
                    center, animatedChest, frame, steps), frame * interval);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (phase != Phase.REWARDS || rewardChest == null) return;
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
                arena.getBlockAt(x, gateY - 1, z).setType(Math.abs(x) == 2 || Math.abs(z) == 2
                        ? Material.CRYING_OBSIDIAN : Material.AMETHYST_BLOCK, false);
            rewardChest.getBlock().setType(Material.CHEST, false);
            completeReturnGate(center);
            spawnDragonCrateHologram();
            spawnRewardEggs();
            CrateDisplayService.spawnStyledLabel(center.clone().add(0, 2.03, 0),
                    Component.text(variables.string("dragon-event.return-gate-title"), AMETHYST,
                            TextDecoration.BOLD), DISPLAY_TAG);
            CrateDisplayService.spawnStyledLabel(center.clone().add(0, 1.75, 0),
                    Component.text(variables.string("dragon-event.return-gate-subtitle"),
                            NamedTextColor.GRAY), DISPLAY_TAG);
            arena.playSound(rewardChest,
                    configuredSound("dragon-event.reward-spawn-sound", Sound.BLOCK_END_PORTAL_SPAWN),
                    (float) variables.decimal("dragon-event.effect-sound-volume"),
                    (float) variables.decimal("dragon-event.reward-spawn-pitch"));
        }, (long) steps * interval);
    }

    private void rewardSpawnFrame(Location gate, Location chest, int frame, int frames) {
        if (phase != Phase.REWARDS || !variables.bool("dragon-event.effects-enabled")) return;
        double progress = (frame + 1d) / Math.max(1, frames);
        double radius = 1d + progress * 7d;
        int particles = Math.max(1, variables.integer("dragon-event.reward-area-particle-count")
                / Math.max(1, frames));
        arena.spawnParticle(Particle.END_ROD, gate.clone().add(0, 1, 0), particles,
                radius, 1.5 + progress * 3, radius, .08);
        arena.spawnParticle(Particle.DUST, chest, particles, 1.4, progress * 3, 1.4, .04, BRIGHT);
        animateReturnGateFrame(gate, progress);
        if (frame % Math.max(1, variables.integer("dragon-event.reward-lightning-every-frames")) == 0) {
            arena.spawnParticle(Particle.REVERSE_PORTAL, chest, 45, 1.3, 3.5, 1.3, .12);
        }
    }

    private void animateReturnGateFrame(Location gate, double progress) {
        List<Block> frame = new ArrayList<>();
        int baseY = gate.getBlockY();
        for (int y = 0; y <= 5; y++) {
            frame.add(arena.getBlockAt(-2, baseY + y, 0));
            frame.add(arena.getBlockAt(2, baseY + y, 0));
        }
        for (int x = -1; x <= 1; x++) {
            frame.add(arena.getBlockAt(x, baseY, 0));
            frame.add(arena.getBlockAt(x, baseY + 5, 0));
        }
        int visible = Math.min(frame.size(), (int) Math.ceil(frame.size() * progress));
        for (int index = 0; index < visible; index++) {
            Block block = frame.get(index);
            if (block.getType().isAir()) {
                block.setType(index % 4 == 0 ? Material.CRYING_OBSIDIAN : Material.OBSIDIAN, false);
                rewardStructureBlocks.add(block.getLocation());
            }
        }
    }

    private void completeReturnGate(Location gate) {
        animateReturnGateFrame(gate, 1d);
        Orientable data = (Orientable) Material.NETHER_PORTAL.createBlockData();
        data.setAxis(Axis.X);
        int baseY = gate.getBlockY();
        for (int x = -1; x <= 1; x++) {
            for (int y = 1; y <= 4; y++) {
                Block block = arena.getBlockAt(x, baseY + y, 0);
                block.setBlockData(data.clone(), false);
                rewardStructureBlocks.add(block.getLocation());
            }
        }
        returnGate = new Location(arena, .5, baseY + 1.1, .5);
    }

    private void spawnDragonCrateHologram() {
        List<Component> lines = CrateDisplayService.hologramLines(
                CrateKind.DRAGON, crates.keyCost(CrateKind.DRAGON), System.currentTimeMillis());
        for (int index = 0; index < lines.size(); index++) {
            double height = CrateDisplayService.BOTTOM_LINE
                    + CrateDisplayService.LINE_HEIGHT * (lines.size() - 1 - index);
            List<String> tags = new ArrayList<>(List.of(DISPLAY_TAG, CRATE_LABEL_TAG));
            if (index == lines.size() - 1) tags.add(CRATE_COUNTDOWN_TAG);
            CrateDisplayService.spawnStyledLabel(rewardChest.clone().add(.5, height, .5),
                    lines.get(index), tags.toArray(String[]::new));
        }
    }

    private void spawnRewardEggs() {
        int eggs = variables.integer("dragon-event.egg-count");
        for (int index = 0; index < eggs; index++) {
            int x = -3 + index * 2;
            int z = variables.integer("dragon-event.reward-egg-z");
            int y = arena.getHighestBlockYAt(x, z) + 1;
            Block egg = arena.getBlockAt(x, y, z);
            int delay = index * variables.integer("dragon-event.egg-spawn-interval-ticks");
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (phase != Phase.REWARDS) return;
                egg.setType(Material.DRAGON_EGG, false);
                claimableEggs.add(blockKey(egg));
                Location at = egg.getLocation().add(.5, .8, .5);
                arena.spawnParticle(Particle.REVERSE_PORTAL, at,
                        variables.integer("dragon-event.egg-spawn-particle-count"), .7, .9, .7, .09);
                arena.playSound(at, configuredSound("dragon-event.egg-spawn-sound",
                                Sound.BLOCK_RESPAWN_ANCHOR_CHARGE),
                        (float) variables.decimal("dragon-event.effect-sound-volume"),
                        (float) variables.decimal("dragon-event.egg-spawn-pitch"));
            }, delay);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        Player player = event.getPlayer();
        if (phase == Phase.PORTAL_OPEN && portal != null && near(
                event.getTo(), portal.location(), variables.integer("dragon-event.portal-entry-radius"))
                && touchesDragonPortal(event.getTo())) {
            enter(player);
            return;
        }
        if (phase == Phase.REWARDS && returnGate != null && isArena(event.getTo().getWorld())
                && event.getTo().distanceSquared(returnGate)
                <= Math.pow(variables.decimal("dragon-event.return-gate-radius"), 2d)
                && touchesDragonPortal(event.getTo())) {
            leave(player);
        }
    }

    private void enter(Player player) {
        if (entrants.add(player.getUniqueId())) {
            RunStats row = stats.computeIfAbsent(player.getUniqueId(), ignored -> new RunStats());
            row.enteredAt = System.currentTimeMillis();
        }
        player.teleport(arenaSpawn());
        applyArenaSky(player);
        arrivalEffect(player);
        if (admissionBar != null) player.showBossBar(admissionBar);
        player.sendMessage(prefix().append(Component.text(
                "Fight together. The entrance seals when the countdown ends.", NamedTextColor.WHITE)));
    }

    private void leave(Player player) {
        departed.add(player.getUniqueId());
        if (admissionBar != null) player.hideBossBar(admissionBar);
        showStats(player);
        teleportSpawn(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) return;
        boolean fromArena = isArena(event.getFrom().getWorld());
        boolean toArena = isArena(event.getTo().getWorld());
        UUID playerId = event.getPlayer().getUniqueId();
        if (!fromArena && toArena) {
            if (phase != Phase.PORTAL_OPEN || !entrants.contains(playerId) || departed.contains(playerId)) {
                event.setCancelled(true);
                event.getPlayer().sendActionBar(Component.text(
                        phase == Phase.FIGHT ? "The Dragon entrance is sealed."
                                : "That Dragon event can no longer be entered.", NamedTextColor.RED));
            }
            return;
        }
        if (!fromArena || toArena) return;
        if (phase == Phase.SUMMONING || phase == Phase.FIGHT || phase == Phase.VICTORY) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text(
                    "The Dragon arena is sealed until the fight ends.", NamedTextColor.RED));
        } else if (phase == Phase.REWARDS) {
            departed.add(playerId);
            resetArenaSky(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = attacker(event.getDamager());
        if (isArena(event.getEntity().getWorld()) && event.getEntity() instanceof Player
                && attacker != null) {
            event.setCancelled(true);
            return;
        }
        if (phase != Phase.FIGHT || attacker == null || !entrants.contains(attacker.getUniqueId())) return;
        if (event.getEntity() instanceof EnderCrystal crystal && crystal.getScoreboardTags().contains(CRYSTAL_TAG)) {
            event.setCancelled(true);
            crystal.remove();
            rewardCrystal(attacker, crystal.getLocation());
            return;
        }
        if (!(event.getEntity() instanceof EnderDragon target)
                || !target.getScoreboardTags().contains(DRAGON_TAG)) return;
        double damage = Math.max(0d, Math.min(event.getFinalDamage(), dragonHealth));
        if (damage <= 0d) return;
        dragonHealth -= damage;
        updateDragonBar();
        if (dragonHealth <= 0d) {
            event.setDamage(Math.max(event.getDamage(), target.getHealth() + 1d));
        } else if (dragonHealthScale < 1d) {
            event.setDamage(Math.max(0.01d, event.getDamage() * dragonHealthScale));
        }
        lastDragonAttacker = attacker.getUniqueId();
        RunStats row = active(attacker);
        row.damage += damage;
        long rounded = Math.round(row.damage);
        if (rounded - row.persistedDamage >= variables.integer("dragon-event.stats-save-damage")) {
            persistDamage(attacker.getUniqueId(), row, rounded);
        }
        rewardDamageWaves(damage);
        if (ThreadLocalRandom.current().nextInt(variables.integer("dragon-event.shard-one-in")) == 0) {
            giveShards(attacker, variables.integer("dragon-event.shard-amount"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArenaEntitySpawn(EntitySpawnEvent event) {
        if (!isArena(event.getLocation().getWorld())) return;
        if (event.getEntity() instanceof EnderDragon && phase != Phase.FIGHT) {
            event.setCancelled(true);
        } else if (event.getEntity() instanceof EnderCrystal
                && phase != Phase.SUMMONING && phase != Phase.FIGHT) {
            event.setCancelled(true);
        }
    }

    /** Runs after the Dragon's own tracking pass, which otherwise adds its bar back. */
    @EventHandler
    public void onServerTickEnd(ServerTickEndEvent event) {
        if (arena != null) hideVanillaDragonBar();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArenaPortalCreate(PortalCreateEvent event) {
        if (isArena(event.getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDragonHeal(EntityRegainHealthEvent event) {
        if (phase == Phase.FIGHT && event.getEntity() instanceof EnderDragon target
                && target.getScoreboardTags().contains(DRAGON_TAG)) {
            event.setCancelled(true);
        }
    }

    private void rewardDamageWaves(double damage) {
        double before = rewardedDamage;
        rewardedDamage += damage;
        int interval = variables.integer("dragon-event.damage-wave-hp");
        long prior = (long) Math.floor(before / interval);
        long current = (long) Math.floor(rewardedDamage / interval);
        for (long wave = prior; wave < current; wave++) {
            for (UUID playerId : activeParticipants()) giveKeys(playerId,
                    variables.integer("dragon-event.damage-wave-keys"));
            if (variables.bool("dragon-event.effects-enabled")) {
                arena.spawnParticle(Particle.DUST, dragon.getLocation(),
                        variables.integer("dragon-event.reward-particle-count"),
                        5, 3, 5, 0.05, BRIGHT);
                keyWaterfall(dragon.getLocation().add(0, 3, 0),
                        variables.integer("dragon-event.wave-key-effect-count"));
            }
            arena.playSound(dragon.getLocation(),
                    configuredSound("dragon-event.damage-wave-sound", Sound.BLOCK_AMETHYST_BLOCK_RESONATE),
                    (float) variables.decimal("dragon-event.effect-sound-volume"),
                    (float) variables.decimal("dragon-event.damage-wave-pitch"));
        }
    }

    private void rewardCrystal(Player player, Location at) {
        RunStats row = active(player);
        row.crystals++;
        progress.recordDragonCrystal(player.getUniqueId());
        int min = variables.integer("dragon-event.crystal-minimum-keys");
        int max = Math.max(min, variables.integer("dragon-event.crystal-maximum-keys"));
        int keys = ThreadLocalRandom.current().nextInt(min, max + 1);
        giveKeys(player.getUniqueId(), keys);
        keyWaterfall(at, variables.integer("dragon-event.crystal-key-effect-count"));
        if (variables.bool("dragon-event.effects-enabled")) {
            at.getWorld().spawnParticle(Particle.DRAGON_BREATH, at,
                    variables.integer("dragon-event.crystal-particle-count"),
                    1.2, 1.2, 1.2, 0.09, 1.0f);
            at.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, at, 1);
        }
        at.getWorld().playSound(at,
                configuredSound("dragon-event.crystal-break-sound", Sound.ENTITY_GENERIC_EXPLODE),
                (float) variables.decimal("dragon-event.effect-sound-volume"),
                (float) variables.decimal("dragon-event.crystal-break-pitch"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDragonDeath(EntityDeathEvent event) {
        if (phase != Phase.FIGHT || !(event.getEntity() instanceof EnderDragon dead)
                || !dead.getScoreboardTags().contains(DRAGON_TAG)) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        finishRun(true, lastDragonAttacker == null ? null : Bukkit.getPlayer(lastDragonAttacker));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAdvancementCriterion(PlayerAdvancementCriterionGrantEvent event) {
        NamespacedKey key = event.getAdvancement().getKey();
        if (isArena(event.getPlayer().getWorld())
                && key.getNamespace().equals(NamespacedKey.MINECRAFT)
                && key.getKey().startsWith("end/")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (phase == Phase.REWARDS && event.getClickedBlock() != null && isArena(event.getClickedBlock().getWorld())) {
            Block block = event.getClickedBlock();
            if (block.getType() == Material.CHEST && rewardChest != null
                    && block.getLocation().equals(rewardChest)) {
                event.setCancelled(true);
                crates.openFor(event.getPlayer(), CrateKind.DRAGON);
                return;
            }
            if (block.getType() == Material.DRAGON_EGG && claimableEggs.remove(blockKey(block))) {
                event.setCancelled(true);
                claimEgg(event.getPlayer(), block);
                return;
            }
        }
        ItemStack held = event.getItem();
        if (held != null && isDragonEgg(held) && event.getAction().isRightClick()) {
            createElytra(event.getPlayer(), held);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEggBreak(BlockBreakEvent event) {
        if (claimableEggs.remove(blockKey(event.getBlock()))) {
            event.setCancelled(true);
            claimEgg(event.getPlayer(), event.getBlock());
            return;
        }
        if (isArena(event.getBlock().getWorld())
                && !(phase == Phase.FIGHT && event.getBlock().getType() == Material.IRON_BARS)) {
            event.setCancelled(true);
        }
    }

    private void claimEgg(Player player, Block block) {
        block.setType(Material.AIR, false);
        ItemStack egg = dragonEgg();
        player.getInventory().addItem(egg).values().forEach(left ->
                player.getWorld().dropItemNaturally(player.getLocation(), left));
        progress.recordDragonEgg(player.getUniqueId());
        clanBattles.recordDragonEgg(player);
        player.sendMessage(prefix().append(Component.text("You claimed an Amethyst Dragon Egg!",
                AMETHYST, TextDecoration.BOLD)));
        player.getWorld().spawnParticle(Particle.END_ROD, block.getLocation().add(0.5, 0.8, 0.5),
                variables.integer("dragon-event.egg-particle-count"), 0.7, 0.8, 0.7, 0.08);
        player.playSound(player.getLocation(),
                configuredSound("dragon-event.egg-claim-sound", Sound.UI_TOAST_CHALLENGE_COMPLETE),
                (float) variables.decimal("dragon-event.effect-sound-volume"),
                (float) variables.decimal("dragon-event.egg-claim-pitch"));
    }

    private void createElytra(Player player, ItemStack egg) {
        int slot = -1;
        for (int index = 0; index < player.getInventory().getSize(); index++) {
            ItemStack candidate = player.getInventory().getItem(index);
            if (candidate != null && candidate.getType() == Material.ELYTRA
                    && !amethystItems.isTimed(candidate)) {
                slot = index;
                break;
            }
        }
        if (slot < 0) {
            player.sendActionBar(Component.text("Carry a normal Elytra to awaken it.", NamedTextColor.YELLOW));
            return;
        }
        ItemStack elytra = amethystItems.createById("amethyst_elytra").orElse(null);
        if (elytra == null) return;
        egg.subtract(1);
        player.getInventory().setItem(slot, elytra);
        player.sendMessage(prefix().append(Component.text("The egg awakened your Amethyst Elytra.", AMETHYST)));
        player.playSound(player.getLocation(),
                configuredSound("dragon-event.elytra-create-sound", Sound.BLOCK_RESPAWN_ANCHOR_CHARGE),
                (float) variables.decimal("dragon-event.effect-sound-volume"),
                (float) variables.decimal("dragon-event.elytra-create-pitch"));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        handleRespawn(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = event.getPlayer();
            if (player.isOnline() && isArena(player.getWorld())
                    && (phase == Phase.WAITING || !entrants.contains(player.getUniqueId()))) {
                teleportSpawn(player);
            } else if (player.isOnline() && isArena(player.getWorld())) {
                applyArenaSky(player);
            }
        });
    }

    private RunStats active(Player player) {
        RunStats row = stats.computeIfAbsent(player.getUniqueId(), ignored -> new RunStats());
        row.lastActive = System.currentTimeMillis();
        return row;
    }

    private Set<UUID> activeParticipants() {
        long cutoff = System.currentTimeMillis()
                - variables.integer("dragon-event.participation-seconds") * 1000L;
        Set<UUID> result = new HashSet<>();
        stats.forEach((playerId, row) -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline() && isArena(player.getWorld()) && row.lastActive >= cutoff) {
                result.add(playerId);
            }
        });
        return result;
    }

    private void giveKeys(UUID playerId, int amount) {
        if (amount <= 0) return;
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) return;
        if (items.giveKeysOrDrop(player, amount)) {
            stats.computeIfAbsent(playerId, ignored -> new RunStats()).keys += amount;
        }
    }

    private void giveShards(Player player, int amount) {
        if (amount <= 0) return;
        player.getInventory().addItem(items.shard(amount)).values().forEach(left ->
                player.getWorld().dropItemNaturally(player.getLocation(), left));
    }

    private void flushDamageStats() {
        stats.forEach((playerId, row) -> persistDamage(playerId, row, Math.round(row.damage)));
    }

    private void persistDamage(UUID playerId, RunStats row, long roundedTotal) {
        long delta = Math.max(0L, roundedTotal - row.persistedDamage);
        if (delta <= 0L) return;
        progress.recordDragonDamage(playerId, delta);
        row.persistedDamage = roundedTotal;
    }

    private ItemStack dragonEgg() {
        ItemStack egg = new ItemStack(Material.DRAGON_EGG);
        ItemMeta meta = egg.getItemMeta();
        meta.displayName(Component.text("Amethyst Dragon Egg", AMETHYST, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(eggKey, PersistentDataType.BYTE, (byte) 1);
        meta.lore(List.of(
                Component.text("A living crystal relic.", NamedTextColor.LIGHT_PURPLE)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Right-click while carrying an Elytra to awaken it.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        NamespacedKey model = NamespacedKey.fromString("mgx:amethyst_dragon_egg");
        if (model != null) meta.setItemModel(model);
        egg.setItemMeta(meta);
        return egg;
    }

    private boolean isDragonEgg(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(eggKey, PersistentDataType.BYTE);
    }

    private void showStats(Player player) {
        RunStats row = stats.get(player.getUniqueId());
        if (row == null) return;
        long seconds = Math.max(0L, (System.currentTimeMillis() - row.enteredAt) / 1000L);
        int damageRank = rankOf(player.getUniqueId(), Comparator.comparingDouble(
                (Map.Entry<UUID, RunStats> entry) -> entry.getValue().damage).reversed());
        int crystalRank = rankOf(player.getUniqueId(), Comparator.comparingInt(
                (Map.Entry<UUID, RunStats> entry) -> entry.getValue().crystals).reversed());
        player.showTitle(net.kyori.adventure.title.Title.title(
                Component.text(variables.string("dragon-event.stats-title"), AMETHYST,
                        TextDecoration.BOLD),
                Component.text(variables.string("dragon-event.stats-subtitle"), NamedTextColor.WHITE)));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text(variables.string("dragon-event.stats-header"), AMETHYST,
                TextDecoration.BOLD));
        player.sendMessage(Component.text(renderStats(variables.string("dragon-event.stats-damage-line"),
                row, seconds, damageRank, crystalRank), NamedTextColor.WHITE));
        player.sendMessage(Component.text(renderStats(variables.string("dragon-event.stats-crystals-line"),
                row, seconds, damageRank, crystalRank), NamedTextColor.WHITE));
        player.sendMessage(Component.text(renderStats(variables.string("dragon-event.stats-keys-line"),
                row, seconds, damageRank, crystalRank), NamedTextColor.WHITE));
        player.sendMessage(Component.text(renderStats(variables.string("dragon-event.stats-time-line"),
                row, seconds, damageRank, crystalRank), NamedTextColor.GRAY));
        player.sendMessage(Component.empty());
    }

    private int rankOf(UUID playerId, Comparator<Map.Entry<UUID, RunStats>> comparator) {
        List<Map.Entry<UUID, RunStats>> ranked = stats.entrySet().stream().sorted(comparator).toList();
        for (int index = 0; index < ranked.size(); index++) {
            if (ranked.get(index).getKey().equals(playerId)) return index + 1;
        }
        return ranked.size();
    }

    private String renderStats(String template, RunStats row, long seconds, int damageRank, int crystalRank) {
        return template
                .replace("<damage>", String.format(Locale.US, "%,d", Math.round(row.damage)))
                .replace("<damage_rank>", String.valueOf(damageRank))
                .replace("<crystals>", String.format(Locale.US, "%,d", row.crystals))
                .replace("<crystal_rank>", String.valueOf(crystalRank))
                .replace("<keys>", String.format(Locale.US, "%,d", row.keys))
                .replace("<time>", duration(seconds * 1_000L));
    }

    private void sendEveryoneHome() {
        if (arena == null) return;
        for (Player player : List.copyOf(arena.getPlayers())) {
            showStats(player);
            teleportSpawn(player);
        }
    }

    private void teleportSpawn(Player player) {
        resetArenaSky(player);
        World world = Bukkit.getWorlds().stream()
                .filter(candidate -> candidate.getEnvironment() == World.Environment.NORMAL && !isArena(candidate))
                .findFirst().orElse(Bukkit.getWorlds().getFirst());
        player.teleport(world.getSpawnLocation().clone().add(0.5, 0.1, 0.5));
    }

    /** Holds the requested bright-dark-bright presentation without changing server tick speed. */
    private void applyArenaSky() {
        if (arena == null) return;
        long time = arenaSkyTime();
        arena.setTime(time);
        for (Player player : arena.getPlayers()) applyArenaSky(player);
    }

    private void applyArenaSky(Player player) {
        if (arena == null || !isArena(player.getWorld())) return;
        player.setPlayerTime(arenaSkyTime(), false);
        player.setPlayerWeather(org.bukkit.WeatherType.CLEAR);
    }

    private long arenaSkyTime() {
        return arenaSkyTime(variables.string("dragon-event.sky-style"), phase,
                variables.integer("dragon-event.bright-sky-time"),
                variables.integer("dragon-event.fight-sky-time"));
    }

    static long arenaSkyTime(String style, Phase phase, long brightTime, long fightTime) {
        if (!style.equals("PHASED")) return 18_000L;
        return phase == Phase.FIGHT ? fightTime : brightTime;
    }

    private static void resetArenaSky(Player player) {
        player.resetPlayerTime();
        player.resetPlayerWeather();
    }

    private Location arenaSpawn() {
        ensureArena();
        int x = variables.integer("dragon-event.entry-x");
        int z = variables.integer("dragon-event.entry-z");
        int y = arena.getHighestBlockYAt(x, z) + 1;
        return new Location(arena, x + .5, y + .1, z + .5, 180f, 0f);
    }

    private void arrivalEffect(Player player) {
        Location at = player.getLocation().clone().add(0, 1, 0);
        if (variables.bool("dragon-event.effects-enabled")) {
            player.getWorld().spawnParticle(Particle.REVERSE_PORTAL, at,
                    variables.integer("dragon-event.entry-particle-count"), 1.2, 1.4, 1.2, .12);
            player.getWorld().spawnParticle(Particle.END_ROD, at,
                    Math.max(1, variables.integer("dragon-event.entry-particle-count") / 3),
                    .8, 1.1, .8, .05);
        }
        player.playSound(player.getLocation(),
                configuredSound("dragon-event.entry-sound", Sound.ENTITY_ENDERMAN_TELEPORT),
                (float) variables.decimal("dragon-event.effect-sound-volume"),
                (float) variables.decimal("dragon-event.entry-pitch"));
    }

    private void pulseEffects() {
        applyArenaSky();
        boolean visuals = variables.bool("dragon-event.effects-enabled");
        if (visuals && phase == Phase.PORTAL_OPEN && portal != null && portal.location() != null) {
            Location at = portal.location();
            int particles = variables.integer("dragon-event.portal-pulse-particles");
            double width = variables.decimal("dragon-event.portal-effect-width");
            double height = variables.decimal("dragon-event.portal-effect-height");
            boolean alongX = Math.abs(Math.sin(Math.toRadians(portal.yaw()))) < .7;
            for (int index = 0; index < particles; index++) {
                double side = ThreadLocalRandom.current().nextDouble(-width / 2d, width / 2d);
                double up = ThreadLocalRandom.current().nextDouble(0d, height);
                Location point = at.clone().add(alongX ? side : 0d, up, alongX ? 0d : side);
                at.getWorld().spawnParticle(index % 3 == 0 ? Particle.END_ROD : Particle.REVERSE_PORTAL,
                        point, 1, .08, .08, .08, .02);
                if ((index & 3) == 0) at.getWorld().spawnParticle(Particle.DUST, point, 1,
                        .08, .08, .08, 0d, BRIGHT);
            }
        }
        if (phase == Phase.FIGHT && arena != null && dragon != null && dragon.isValid()) {
            updateDragonBar();
            if (visuals) arena.spawnParticle(Particle.DUST, dragon.getLocation(),
                    variables.integer("dragon-event.ambient-particle-count"),
                    2.5, 1.2, 2.5, 0.02, DARK);
            if (visuals && ThreadLocalRandom.current().nextInt(
                    variables.integer("dragon-event.lightning-one-in")) == 0) {
                Location at = dragon.getLocation().clone().add(0, 1.5, 0);
                arena.spawnParticle(Particle.END_ROD, at, 18, 2.2, 1.4, 2.2, .08);
                arena.spawnParticle(Particle.DUST, at, 28, 2.8, 1.8, 2.8, .04, BRIGHT);
            }
            if (ThreadLocalRandom.current().nextInt(
                    variables.integer("dragon-event.blast-one-in-per-second")) == 0) {
                amethystBlast();
            }
            long now = System.currentTimeMillis();
            aggressiveAttack(now);
            chaosStrike(now);
            driveMinions();
            if (now - lastMinionWaveAt >= variables.integer("dragon-event.minion-wave-seconds") * 1000L) {
                spawnMinionWave();
                lastMinionWaveAt = now;
            }
        }
        if (visuals && phase == Phase.REWARDS && rewardChest != null
                && rewardChest.getBlock().getType() == Material.CHEST) {
            Location centre = rewardChest.clone().add(.5, .9, .5);
            CrateDisplayService.drawAmethyst(centre, effectFrame * .3d, effectFrame);
            drawRewardBeacon(centre);
        }
        if (arena != null && (phase == Phase.SUMMONING || phase == Phase.FIGHT
                || phase == Phase.VICTORY || phase == Phase.REWARDS)) {
            clearVanillaExitPortal();
        }
        effectFrame++;
    }

    /** The same purple End Rod beacon language used to make Amethyst Airdrops visible. */
    private void drawRewardBeacon(Location centre) {
        int height = Math.min(variables.integer("dragon-event.reward-beacon-height"),
                arena.getMaxHeight() - centre.getBlockY() - 1);
        int spacing = variables.integer("dragon-event.reward-beacon-spacing");
        for (int y = 0; y <= height; y += spacing) {
            Location point = centre.clone().add(0, y, 0);
            arena.spawnParticle(Particle.END_ROD, point, 1, .05, .18, .05, 0);
            arena.spawnParticle(Particle.DUST, point, 1, .08, .08, .08, 0,
                    y % (spacing * 2) == 0 ? BRIGHT : DARK);
        }
    }

    private void hideVanillaDragonBar() {
        if (dragon != null) hideVanillaBar(dragon.getBossBar());
        if (arena != null && arena.getEnderDragonBattle() != null) {
            hideVanillaBar(arena.getEnderDragonBattle().getBossBar());
        }
    }

    private static void hideVanillaBar(org.bukkit.boss.BossBar vanilla) {
        if (vanilla == null) return;
        vanilla.removeAll();
        vanilla.setVisible(false);
    }

    /** Removes the vanilla End exit fountain without touching the custom return gate. */
    private void clearVanillaExitPortal() {
        if (arena == null) return;
        int surface = arena.getHighestBlockYAt(0, 0);
        int bottom = Math.max(35, surface - 14);
        int top = Math.min(125, surface + 18);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = bottom; y <= top; y++) {
                    Block block = arena.getBlockAt(x, y, z);
                    if (isVanillaExitPortalBlock(block.getType())) block.setType(Material.AIR, false);
                }
            }
        }
    }

    static boolean isVanillaExitPortalBlock(Material material) {
        return material == Material.END_PORTAL || material == Material.END_GATEWAY
                || material == Material.END_PORTAL_FRAME || material == Material.BEDROCK
                || material == Material.END_STONE || material == Material.END_STONE_BRICKS
                || material == Material.TORCH || material == Material.WALL_TORCH;
    }

    /** Vanilla may finish creating the fountain after the death callback returns. */
    private void scheduleVanillaExitPortalCleanup() {
        long generation = runGeneration;
        for (long delay : new long[]{1L, 20L, 60L, 120L}) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (runGeneration == generation) clearVanillaExitPortal();
            }, delay);
        }
    }

    private void updateDragonBar() {
        if (dragonBar == null || dragonMaximumHealth <= 0d) return;
        dragonBar.name(Component.text(render(render(
                        variables.string("dragon-event.fight-bossbar-text"), "hp",
                        String.valueOf(Math.max(0L, Math.round(dragonHealth)))), "time",
                        duration(phaseEndsAt - System.currentTimeMillis())), AMETHYST,
                TextDecoration.BOLD));
        dragonBar.progress((float) Math.clamp(dragonHealth / dragonMaximumHealth, 0d, 1d));
        for (Player player : arena.getPlayers()) {
            if (entrants.contains(player.getUniqueId()) && !departed.contains(player.getUniqueId())) {
                player.showBossBar(dragonBar);
            }
        }
    }

    private void createAdmissionBar() {
        hideAdmissionBar();
        admissionBar = BossBar.bossBar(Component.empty(), 1f,
                BossBar.Color.valueOf(variables.string("dragon-event.countdown-bossbar-color")),
                BossBar.Overlay.PROGRESS);
        updateAdmissionBar();
    }

    private void updateAdmissionBar() {
        if (admissionBar == null || scheduledAt == null) return;
        long remaining = Math.max(0L, scheduledAt.toEpochMilli() - System.currentTimeMillis());
        long total = Math.max(1L, variables.integer("dragon-event.portal-open-minutes") * 60_000L);
        admissionBar.name(Component.text(render(
                variables.string("dragon-event.countdown-bossbar-text"), "time", duration(remaining)),
                AMETHYST, TextDecoration.BOLD));
        admissionBar.progress((float) Math.clamp((double) remaining / total, 0d, 1d));
        if (arena == null) return;
        for (Player player : arena.getPlayers()) {
            if (entrants.contains(player.getUniqueId()) && !departed.contains(player.getUniqueId())) {
                player.showBossBar(admissionBar);
            }
        }
    }

    private void hideAdmissionBar() {
        if (admissionBar == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) player.hideBossBar(admissionBar);
        admissionBar = null;
    }

    private void hideDragonBar() {
        if (dragonBar == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) player.hideBossBar(dragonBar);
        dragonBar = null;
    }

    private void keyWaterfall(Location origin, int count) {
        if (!variables.bool("dragon-event.effects-enabled") || origin == null || origin.getWorld() == null) return;
        int lifetime = variables.integer("dragon-event.key-effect-lifetime-ticks");
        double spread = variables.decimal("dragon-event.key-effect-spread");
        double height = variables.decimal("dragon-event.key-effect-height");
        for (int index = 0; index < count; index++) {
            Location spawn = origin.clone().add(
                    ThreadLocalRandom.current().nextDouble(-spread, spread),
                    ThreadLocalRandom.current().nextDouble(height * .35, height),
                    ThreadLocalRandom.current().nextDouble(-spread, spread));
            Item visual = spawn.getWorld().dropItem(spawn, items.key(1));
            visual.addScoreboardTag(KEY_EFFECT_TAG);
            visual.setPickupDelay(Integer.MAX_VALUE);
            visual.setVelocity(new org.bukkit.util.Vector(
                    ThreadLocalRandom.current().nextDouble(-.08, .08),
                    ThreadLocalRandom.current().nextDouble(.04, .18),
                    ThreadLocalRandom.current().nextDouble(-.08, .08)));
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!visual.isValid()) return;
                Location vanish = visual.getLocation();
                vanish.getWorld().spawnParticle(Particle.REVERSE_PORTAL, vanish,
                        variables.integer("dragon-event.key-vanish-particle-count"), .25, .25, .25, .05);
                visual.remove();
            }, lifetime);
        }
        origin.getWorld().spawnParticle(Particle.END_ROD, origin, Math.max(8, count * 2),
                spread, 1.5, spread, .08);
    }

    private void amethystBlast() {
        double radius = variables.decimal("dragon-event.blast-radius");
        double damage = variables.decimal("dragon-event.blast-damage");
        Location center = dragon.getLocation();
        arena.spawnParticle(Particle.DRAGON_BREATH, center,
                variables.integer("dragon-event.blast-particle-count"), radius / 2d, 2d,
                radius / 2d, 0.06d, 1.0f);
        arena.playSound(center,
                configuredSound("dragon-event.blast-sound", Sound.ENTITY_WARDEN_SONIC_BOOM),
                (float) variables.decimal("dragon-event.effect-sound-volume"),
                (float) variables.decimal("dragon-event.blast-pitch"));
        for (Player player : arena.getPlayers()) {
            if (!entrants.contains(player.getUniqueId())
                    || player.getLocation().distanceSquared(center) > radius * radius) continue;
            if (damage > 0d) player.damage(damage, dragon);
            org.bukkit.util.Vector away = player.getLocation().toVector().subtract(center.toVector());
            if (away.lengthSquared() > 0.01d) {
                player.setVelocity(away.normalize()
                        .multiply(variables.decimal("dragon-event.blast-knockback"))
                        .setY(variables.decimal("dragon-event.blast-lift")));
            }
        }
    }

    private void updateDisplays() {
        if (portal != null && portal.location() != null) {
            List<TextDisplay> displays = portal.location().getWorld().getEntitiesByClass(TextDisplay.class).stream()
                    .filter(entity -> entity.getScoreboardTags().contains(PORTAL_DISPLAY_TAG)).toList();
            if (displays.size() < 2) {
                refreshPortalDisplay();
                return;
            }
            displays.stream().filter(display -> display.getScoreboardTags().contains(PORTAL_STATUS_TAG)).findFirst()
                    .ifPresent(display -> display.text(Component.text(statusLine(), NamedTextColor.WHITE)));
        }
        if (phase == Phase.REWARDS && arena != null) {
            arena.getEntitiesByClass(ArmorStand.class).stream()
                    .filter(entity -> entity.getScoreboardTags().contains(DISPLAY_TAG))
                    .filter(entity -> entity.getScoreboardTags().contains(CRATE_COUNTDOWN_TAG))
                    .findFirst().ifPresent(display -> display.customName(
                            CrateKind.DRAGON.countdownLines(System.currentTimeMillis()).get(1)));
        }
    }

    private String statusLine() {
        if (!Instant.now().isBefore(eventEnd()) && phase == Phase.WAITING) {
            return variables.string("dragon-event.ended-hologram");
        }
        return switch (phase) {
            case WAITING -> {
                long until = scheduledAt == null ? 0L : scheduledAt.toEpochMilli() - System.currentTimeMillis();
                long opens = until - variables.integer("dragon-event.portal-open-minutes") * 60_000L;
                yield opens > 0 ? render(variables.string("dragon-event.portal-waiting-status"),
                        "time", duration(opens))
                        : render(variables.string("dragon-event.portal-next-event-status"),
                        "time", duration(until));
            }
            case PORTAL_OPEN -> render(variables.string("dragon-event.portal-open-status"), "time",
                    duration(scheduledAt.toEpochMilli() - System.currentTimeMillis()));
            case SUMMONING -> variables.string("dragon-event.portal-summoning-status");
            case FIGHT, VICTORY, REWARDS -> render(variables.string("dragon-event.portal-next-event-status"),
                    "time", duration(scheduledAt == null ? 0L
                            : scheduledAt.toEpochMilli() - System.currentTimeMillis()));
        };
    }

    private void refreshPortalDisplay() {
        clearPortalDisplays();
        if (portal == null || portal.location() == null) return;
        Location at = portal.location();
        double height = variables.decimal("dragon-event.portal-display-height");
        label(at.clone().add(0, height, 0), Component.text(
                "✦ " + variables.string("dragon-event.portal-hologram-title") + " ✦",
                AMETHYST, TextDecoration.BOLD),
                (float) variables.decimal("dragon-event.portal-title-scale"), PORTAL_DISPLAY_TAG);
        label(at.clone().add(0, height - 1.55, 0), Component.text(statusLine(), NamedTextColor.WHITE,
                TextDecoration.BOLD), (float) variables.decimal("dragon-event.portal-status-scale"),
                PORTAL_DISPLAY_TAG, PORTAL_STATUS_TAG);
    }

    private void portalTransition(boolean opening) {
        if (!variables.bool("dragon-event.effects-enabled") || portal == null || portal.location() == null) return;
        Location at = portal.location();
        double width = variables.decimal("dragon-event.portal-effect-width") / 2d;
        double height = variables.decimal("dragon-event.portal-effect-height") / 2d;
        Particle particle = opening ? Particle.END_ROD : Particle.REVERSE_PORTAL;
        at.getWorld().spawnParticle(particle, at.clone().add(0, height, 0),
                variables.integer("dragon-event.portal-particle-count"), width, height, width, .08);
        at.getWorld().spawnParticle(Particle.DUST, at.clone().add(0, height, 0),
                variables.integer("dragon-event.portal-particle-count"), width, height, width, .02,
                opening ? BRIGHT : DARK);
    }

    private TextDisplay label(Location at, Component text, float scale) {
        return label(at, text, scale, DISPLAY_TAG);
    }

    private TextDisplay label(Location at, Component text, float scale, String extraTag) {
        return label(at, text, scale, new String[]{extraTag});
    }

    private TextDisplay label(Location at, Component text, float scale, String... extraTags) {
        return at.getWorld().spawn(at, TextDisplay.class, display -> {
            display.addScoreboardTag(DISPLAY_TAG);
            for (String tag : extraTags) display.addScoreboardTag(tag);
            display.text(text);
            display.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            display.setSeeThrough(true);
            display.setShadowed(true);
            display.setPersistent(false);
            display.setTransformation(new Transformation(
                    new org.joml.Vector3f(), new org.joml.AxisAngle4f(),
                    new org.joml.Vector3f(scale), new org.joml.AxisAngle4f()));
        });
    }

    private void clearPortalDisplays() {
        for (World world : Bukkit.getWorlds()) {
            world.getEntitiesByClass(TextDisplay.class).stream()
                    .filter(entity -> entity.getScoreboardTags().contains(PORTAL_DISPLAY_TAG))
                    .forEach(Entity::remove);
        }
    }

    private void clearDisplays() {
        for (World world : Bukkit.getWorlds()) {
            world.getEntitiesByClass(TextDisplay.class).stream()
                    .filter(entity -> entity.getScoreboardTags().contains(DISPLAY_TAG))
                    .forEach(Entity::remove);
            world.getEntitiesByClass(ArmorStand.class).stream()
                    .filter(entity -> entity.getScoreboardTags().contains(DISPLAY_TAG))
                    .forEach(Entity::remove);
        }
    }

    private void clearArenaEntities() {
        if (arena == null) return;
        // This is a dedicated disposable event world. Removing every non-player entity
        // is intentional: untagged mob drops, arrows, XP, old labels and entities saved
        // by an interrupted run must never leak into the next Dragon event.
        arena.getEntities().stream()
                .filter(entity -> !(entity instanceof Player))
                .forEach(Entity::remove);
    }

    private void clearRewardArea() {
        if (arena == null) return;
        if (rewardChest != null && rewardChest.getWorld() == arena
                && rewardChest.getBlock().getType() == Material.CHEST) {
            rewardChest.getBlock().setType(Material.AIR, false);
        }
        for (int x = -32; x <= 32; x++) {
            for (int y = 35; y <= 125; y++) {
                Block egg = arena.getBlockAt(x, y, variables.integer("dragon-event.reward-egg-z"));
                if (egg.getType() == Material.DRAGON_EGG) egg.setType(Material.AIR, false);
            }
        }
        claimableEggs.clear();
        for (Location block : rewardStructureBlocks) {
            if (block.getWorld() == arena) block.getBlock().setType(Material.AIR, false);
        }
        rewardStructureBlocks.clear();
        rewardChest = null;
        returnGate = null;
    }

    /** Uses Minecraft's own ignition path so only the registered obsidian frame lights. */
    private void setPortalLit(boolean lit) {
        if (!lit) {
            Set<Location> remove = new HashSet<>(portalBlocks);
            remove.addAll(nearestPortalComponent());
            for (Location location : remove) {
                if (location.getWorld() != null && location.getBlock().getType() == Material.NETHER_PORTAL) {
                    location.getBlock().setType(Material.AIR, false);
                }
            }
            portalBlocks.clear();
            return;
        }
        setPortalLit(false);
        if (portal == null || portal.location() == null) return;
        if (igniteRegisteredFrame()) return;
        plugin.getLogger().warning("The Amethyst Dragon portal could not ignite: register a block on its obsidian frame.");
    }

    private boolean igniteRegisteredFrame() {
        Block centre = portal.location().getBlock();
        int radius = variables.integer("dragon-event.portal-light-radius");
        int height = variables.integer("dragon-event.portal-light-height");
        List<Block> candidates = new ArrayList<>();
        for (int y = -height; y <= height; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    Block block = centre.getRelative(x, y, z);
                    if (!block.getType().isAir() && block.getType() != Material.LIGHT) continue;
                    if (!isFrame(block.getRelative(0, -1, 0))) continue;
                    if (!isFrame(block.getRelative(1, 0, 0))
                            && !isFrame(block.getRelative(-1, 0, 0))
                            && !isFrame(block.getRelative(0, 0, 1))
                            && !isFrame(block.getRelative(0, 0, -1))) continue;
                    candidates.add(block);
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(block ->
                block.getLocation().distanceSquared(centre.getLocation())));
        for (Block candidate : candidates) {
            candidate.setType(Material.FIRE, true);
            Set<Location> created = nearestPortalComponent();
            if (!created.isEmpty()) {
                portalBlocks.addAll(created);
                return true;
            }
            if (candidate.getType() == Material.FIRE) candidate.setType(Material.AIR, false);
            created = fillDetectedFrame(candidate, radius, height);
            if (!created.isEmpty()) {
                portalBlocks.addAll(created);
                return true;
            }
        }
        return false;
    }

    private Set<Location> fillDetectedFrame(Block corner, int maximumWidth, int maximumHeight) {
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] direction : directions) {
            int dx = direction[0];
            int dz = direction[1];
            if (!isFrame(corner.getRelative(-dx, 0, -dz))) continue;
            int width = 0;
            while (width < Math.min(21, maximumWidth * 2 + 1)
                    && replaceablePortalInterior(corner.getRelative(dx * width, 0, dz * width))) {
                width++;
            }
            if (width < 2 || !isFrame(corner.getRelative(dx * width, 0, dz * width))) continue;
            int height = 0;
            while (height < Math.min(21, maximumHeight)
                    && replaceablePortalInterior(corner.getRelative(0, height, 0))) {
                height++;
            }
            if (height < 3 || !isFrame(corner.getRelative(0, height, 0))) continue;
            boolean valid = true;
            for (int side = 0; side < width && valid; side++) {
                if (!isFrame(corner.getRelative(dx * side, -1, dz * side))
                        || !isFrame(corner.getRelative(dx * side, height, dz * side))) valid = false;
                for (int y = 0; y < height && valid; y++) {
                    if (!replaceablePortalInterior(corner.getRelative(dx * side, y, dz * side))) valid = false;
                }
            }
            for (int y = 0; y < height && valid; y++) {
                if (!isFrame(corner.getRelative(-dx, y, -dz))
                        || !isFrame(corner.getRelative(dx * width, y, dz * width))) valid = false;
            }
            if (!valid) continue;
            Orientable data = (Orientable) Material.NETHER_PORTAL.createBlockData();
            data.setAxis(dx == 0 ? Axis.Z : Axis.X);
            Set<Location> created = new HashSet<>();
            for (int side = 0; side < width; side++) {
                for (int y = 0; y < height; y++) {
                    Block block = corner.getRelative(dx * side, y, dz * side);
                    block.setBlockData(data.clone(), false);
                    created.add(block.getLocation());
                }
            }
            return created;
        }
        return Set.of();
    }

    private static boolean replaceablePortalInterior(Block block) {
        return block.getType().isAir() || block.getType() == Material.FIRE
                || block.getType() == Material.LIGHT || block.getType() == Material.NETHER_PORTAL;
    }

    private Set<Location> nearestPortalComponent() {
        if (portal == null || portal.location() == null) return Set.of();
        Block centre = portal.location().getBlock();
        int radius = variables.integer("dragon-event.portal-light-radius");
        int height = variables.integer("dragon-event.portal-light-height");
        Block nearest = null;
        double distance = Double.MAX_VALUE;
        for (int y = -height; y <= height; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    Block candidate = centre.getRelative(x, y, z);
                    if (candidate.getType() != Material.NETHER_PORTAL) continue;
                    double candidateDistance = candidate.getLocation().distanceSquared(centre.getLocation());
                    if (candidateDistance < distance) {
                        nearest = candidate;
                        distance = candidateDistance;
                    }
                }
            }
        }
        if (nearest == null) return Set.of();
        Set<Location> found = new HashSet<>();
        List<Block> pending = new ArrayList<>();
        pending.add(nearest);
        for (int index = 0; index < pending.size() && found.size() < 4096; index++) {
            Block block = pending.get(index);
            if (block.getType() != Material.NETHER_PORTAL || !found.add(block.getLocation())) continue;
            pending.add(block.getRelative(1, 0, 0));
            pending.add(block.getRelative(-1, 0, 0));
            pending.add(block.getRelative(0, 1, 0));
            pending.add(block.getRelative(0, -1, 0));
            pending.add(block.getRelative(0, 0, 1));
            pending.add(block.getRelative(0, 0, -1));
        }
        return found;
    }

    private static boolean isFrame(Block block) {
        return block.getType() == Material.OBSIDIAN;
    }

    private void damageDragonTest(CommandSender sender, double amount) {
        if (phase != Phase.FIGHT || dragon == null || !dragon.isValid()) {
            throw new IllegalArgumentException("There is no active Amethyst Dragon to damage.");
        }
        if (!Double.isFinite(amount) || amount <= 0d) {
            throw new IllegalArgumentException("Damage must be a positive number.");
        }
        double applied = Math.min(amount, dragonHealth);
        dragonHealth -= applied;
        if (sender instanceof Player player && entrants.contains(player.getUniqueId())) {
            RunStats row = active(player);
            row.damage += applied;
            lastDragonAttacker = player.getUniqueId();
        }
        rewardDamageWaves(applied);
        if (dragonHealth <= 0d) {
            EnderDragon defeated = dragon;
            defeated.remove();
            finishRun(true, sender instanceof Player player ? player : null);
        } else {
            dragon.setHealth(Math.max(1d, dragonHealth * dragonHealthScale));
            updateDragonBar();
        }
        sender.sendMessage("Applied " + Math.round(applied) + " test damage to the Amethyst Dragon.");
    }

    private void endTestRun(CommandSender sender) {
        if (phase == Phase.WAITING) {
            throw new IllegalArgumentException("No Dragon event is active.");
        }
        cancelSummoningTask();
        hideAdmissionBar();
        hideDragonBar();
        if (dragon != null) dragon.remove();
        phase = Phase.WAITING;
        sendEveryoneHome();
        resetSchedule();
        sender.sendMessage("Ended the Dragon event without rewards.");
    }

    private void summonNow(CommandSender sender) {
        if (phase == Phase.PORTAL_OPEN) beginSummoning();
        if (phase != Phase.SUMMONING) {
            throw new IllegalArgumentException("Open admission first with /dragonportal start.");
        }
        finishSummoningImmediately();
        sender.sendMessage("Completed the summoning sequence and started the fight.");
    }

    private void spawnTestMinion(CommandSender sender, String requested) {
        if (phase != Phase.FIGHT || arena == null) {
            throw new IllegalArgumentException("Start the Dragon fight before spawning a test mob.");
        }
        EntityType type = switch (requested.toLowerCase(Locale.ROOT)) {
            case "zombie", "husk" -> EntityType.HUSK;
            case "skeleton", "stray" -> EntityType.STRAY;
            case "golem", "iron_golem" -> EntityType.IRON_GOLEM;
            default -> throw new IllegalArgumentException("Use zombie, skeleton, or golem.");
        };
        Location at = sender instanceof Player player && isArena(player.getWorld())
                ? player.getLocation().clone().add(player.getLocation().getDirection().multiply(5d))
                : arenaSpawn();
        at.setY(arena.getHighestBlockYAt(at.getBlockX(), at.getBlockZ()) + 1d);
        animateMinionSpawn(at, type, 0);
        sender.sendMessage("Summoning an Amethyst " + requested.toLowerCase(Locale.ROOT) + ".");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!plugin.mayAdminister(sender)) {
            sender.sendMessage("You do not have permission to register the Dragon portal.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("Dragon event: " + phase + "; " + statusLine());
            return true;
        }
        try {
            if (args.length >= 1 && args[0].equalsIgnoreCase("end")) {
                endTestRun(sender);
                return true;
            }
            if (args.length >= 1 && args[0].equalsIgnoreCase("damage")) {
                double amount = args.length >= 2 ? Double.parseDouble(args[1]) : 500d;
                damageDragonTest(sender, amount);
                return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("finish")) {
                damageDragonTest(sender, Math.max(1d, dragonHealth));
                return true;
            }
            if (args.length == 1 && (args[0].equalsIgnoreCase("summon")
                    || args[0].equalsIgnoreCase("skip"))) {
                summonNow(sender);
                return true;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("spawnmob")) {
                spawnTestMinion(sender, args[1]);
                return true;
            }
        } catch (NumberFormatException exception) {
            sender.sendMessage("Damage must be a positive number.");
            return true;
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(exception.getMessage());
            return true;
        }
        if ((args.length == 1 && args[0].equalsIgnoreCase("startnow"))
                || (args.length == 2 && args[0].equalsIgnoreCase("start")
                && args[1].equalsIgnoreCase("now"))) {
            startImmediately(sender);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("start")) {
            if (phase != Phase.WAITING) {
                sender.sendMessage("Dragon event is already " + phase.name().toLowerCase(Locale.ROOT) + ".");
                return true;
            }
            scheduledAt = Instant.now().plus(Duration.ofMinutes(
                    variables.integer("dragon-event.portal-open-minutes")));
            openPortal();
            sender.sendMessage("Dragon portal opened. Walk into it before the admission countdown ends.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Register or remove the Dragon portal in game.");
            return true;
        }
        try {
            if (args.length == 1 && args[0].equalsIgnoreCase("set")) {
                Block target = player.getTargetBlockExact(
                        variables.integer("dragon-event.portal-selection-distance"));
                if (target == null) throw new IllegalArgumentException("Look at the centre of the portal.");
                setPortalLit(false);
                Location at = target.getLocation().add(0.5, 0.5, 0.5);
                portal = new Portal(at.getWorld().getUID(), at.getX(), at.getY(), at.getZ(), player.getYaw());
                savePortal();
                refreshPortalDisplay();
                if (phase == Phase.PORTAL_OPEN) setPortalLit(true);
                player.sendMessage(prefix().append(Component.text("Dragon portal registered.", NamedTextColor.GREEN)));
            } else if (args.length == 1 && args[0].equalsIgnoreCase("remove")) {
                setPortalLit(false);
                portal = null;
                savePortal();
                clearDisplays();
                player.sendMessage(prefix().append(Component.text("Dragon portal removed.", NamedTextColor.GREEN)));
            } else {
                player.sendMessage("Use /dragonportal set|remove|status|start|start now|summon|damage|finish|spawnmob|end.");
            }
        } catch (IOException | IllegalArgumentException exception) {
            PlayerMenuService.error(player, exception.getMessage());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            return "now".startsWith(args[1].toLowerCase(Locale.ROOT)) ? List.of("now") : List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("spawnmob")) {
            return List.of("zombie", "skeleton", "golem").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("set", "remove", "status", "start", "startnow", "summon",
                        "damage", "finish", "spawnmob", "end").stream()
                .filter(value -> value.startsWith(prefix)).toList();
    }

    private void loadPortal() throws IOException {
        if (!Files.isRegularFile(portalFile) || Files.size(portalFile) == 0L) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(portalFile)).getAsJsonObject();
            if (root.isEmpty()) return;
            portal = new Portal(UUID.fromString(root.get("world_id").getAsString()),
                    root.get("x").getAsDouble(), root.get("y").getAsDouble(), root.get("z").getAsDouble(),
                    root.has("yaw") ? root.get("yaw").getAsFloat() : 0f);
        } catch (RuntimeException exception) {
            throw new IOException("Dragon portal location is unreadable", exception);
        }
    }

    private void savePortal() throws IOException {
        JsonObject root = new JsonObject();
        if (portal != null) {
            root.addProperty("world_id", portal.worldId().toString());
            root.addProperty("x", portal.x());
            root.addProperty("y", portal.y());
            root.addProperty("z", portal.z());
            root.addProperty("yaw", portal.yaw());
        }
        Files.createDirectories(portalFile.getParent());
        Path temporary = portalFile.resolveSibling(portalFile.getFileName() + ".tmp");
        Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, portalFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, portalFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Player attacker(Entity entity) {
        if (entity instanceof Player player) return player;
        return entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player
                ? player : null;
    }

    private static boolean near(Location first, Location second, double radius) {
        return first != null && second != null && first.getWorld().equals(second.getWorld())
                && first.distanceSquared(second) <= radius * radius;
    }

    private static boolean touchesDragonPortal(Location location) {
        return location.getBlock().getType() == Material.NETHER_PORTAL
                || location.clone().add(0, 1, 0).getBlock().getType() == Material.NETHER_PORTAL;
    }

    private static String blockKey(Block block) {
        return block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private static String duration(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long rest = seconds % 60L;
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes > 0 ? minutes + "m " + rest + "s" : rest + "s";
    }

    private static String render(String source, String key, String value) {
        return source.replace("<" + key + ">", value);
    }

    /** The custom generator keeps the End-styled arena blank and separate from the locked vanilla End. */
    private static final class VoidArenaGenerator extends ChunkGenerator {
        @Override
        public ChunkData generateChunkData(
                World world, Random random, int chunkX, int chunkZ, BiomeGrid biome
        ) {
            return createChunkData(world);
        }

        @Override
        public boolean shouldGenerateStructures() {
            return false;
        }
    }

    private Sound configuredSound(String key, Sound fallback) {
        try {
            return Sound.valueOf(variables.string(key));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private void announce(String message, Sound sound) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(prefix().append(Component.text(message, NamedTextColor.WHITE)));
            player.playSound(player.getLocation(), sound,
                    (float) variables.decimal("dragon-event.announcement-volume"),
                    (float) variables.decimal("dragon-event.announcement-pitch"));
        }
        Bukkit.getConsoleSender().sendMessage("[Amethyst Dragon] " + message);
    }

    private static Component prefix() {
        return Component.text("DRAGON EVENT » ", AMETHYST, TextDecoration.BOLD);
    }
}
