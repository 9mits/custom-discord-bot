package bot.mgx.accessbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;

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
import java.util.concurrent.ThreadLocalRandom;

/** Scheduled, cooperative Amethyst Dragon arena and its post-kill crate phase. */
final class AmethystDragonService implements Listener, CommandExecutor, TabCompleter {
    static final String WORLD_NAME = "mgx_amethyst_dragon";
    private static final TextColor AMETHYST = TextColor.color(0xB56CFF);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final Particle.DustOptions BRIGHT = new Particle.DustOptions(
            Color.fromRGB(202, 116, 255), 1.25f);
    private static final Particle.DustOptions DARK = new Particle.DustOptions(
            Color.fromRGB(92, 32, 180), 1.0f);
    private static final String DISPLAY_TAG = "mgx_dragon_display";
    private static final String CRYSTAL_TAG = "mgx_dragon_crystal";
    private static final String DRAGON_TAG = "mgx_amethyst_dragon";

    enum Phase { WAITING, PORTAL_OPEN, FIGHT, REWARDS }

    private record Portal(UUID worldId, double x, double y, double z) {
        Location location() {
            World world = Bukkit.getWorld(worldId);
            return world == null ? null : new Location(world, x, y, z);
        }
    }

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
    private final Set<UUID> entrants = new HashSet<>();
    private final Set<UUID> departed = new HashSet<>();
    private final Map<UUID, RunStats> stats = new HashMap<>();
    private final Set<String> claimableEggs = new HashSet<>();
    private final Set<Location> portalLights = new HashSet<>();
    private BukkitTask ticker;

    AmethystDragonService(
            MGXAccessBridge plugin,
            GameVariableStore variables,
            CrateItems items,
            CrateService crates,
            AmethystItemService amethystItems,
            AmethystProgressStore progress,
            ClanBattleService clanBattles
    ) throws IOException {
        this.plugin = plugin;
        this.variables = variables;
        this.items = items;
        this.crates = crates;
        this.amethystItems = amethystItems;
        this.progress = progress;
        this.clanBattles = clanBattles;
        this.portalFile = plugin.getDataFolder().toPath().resolve("dragon-portal.json");
        this.eggKey = new NamespacedKey(plugin, "amethyst_dragon_egg");
        loadPortal();
        CrateKind.dragonEndSource(() -> phaseEndsAt);
    }

    void start() {
        stop();
        CrateKind.dragonEndSource(() -> phaseEndsAt);
        arena = Bukkit.getWorld(WORLD_NAME);
        if (arena != null && !arena.getPlayers().isEmpty()) {
            sendEveryoneHome();
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
        if (!isArena(event.getPlayer().getWorld()) || (phase != Phase.FIGHT && phase != Phase.REWARDS)) {
            return false;
        }
        event.setRespawnLocation(arenaSpawn());
        return true;
    }

    boolean handlePortal(org.bukkit.event.player.PlayerPortalEvent event) {
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
            beginFight();
        } else if (phase == Phase.FIGHT && now >= phaseEndsAt) {
            finishRun(false, null);
        } else if (phase == Phase.REWARDS && now >= phaseEndsAt) {
            closeRewards();
        }
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
        ensureArena();
        phase = Phase.PORTAL_OPEN;
        setPortalLit(true);
        entrants.clear();
        departed.clear();
        stats.clear();
        claimableEggs.clear();
        rewardedDamage = 0d;
        lastDragonAttacker = null;
        announce(render(variables.string("dragon-event.portal-open-message"),
                "minutes", String.valueOf(variables.integer("dragon-event.portal-open-minutes"))),
                configuredSound("dragon-event.portal-open-sound", Sound.BLOCK_BEACON_ACTIVATE));
        if (variables.bool("dragon-event.effects-enabled")) {
            portal.location().getWorld().spawnParticle(Particle.DUST, portal.location(),
                    variables.integer("dragon-event.portal-particle-count"),
                    2.5, 4.0, 2.5, 0.02, BRIGHT);
        }
    }

    private void beginFight() {
        if (arena == null) ensureArena();
        setPortalLit(false);
        phase = Phase.FIGHT;
        phaseEndsAt = System.currentTimeMillis()
                + variables.integer("dragon-event.fight-minutes") * 60_000L;
        buildArena();
        dragon = arena.spawn(new Location(arena, 0.5, 92, 0.5), EnderDragon.class, entity -> {
            entity.addScoreboardTag(DRAGON_TAG);
            var max = entity.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) max.setBaseValue(variables.integer("dragon-event.maximum-health"));
            entity.setHealth(variables.integer("dragon-event.maximum-health"));
            entity.customName(Component.text("Amethyst Dragon", AMETHYST, TextDecoration.BOLD));
            entity.setCustomNameVisible(true);
            entity.setRemoveWhenFarAway(false);
        });
        spawnCrystals();
        CrateKind.dragonAvailableSource(() -> crateAvailable());
        announce(variables.string("dragon-event.started-message"),
                configuredSound("dragon-event.start-sound", Sound.ENTITY_ENDER_DRAGON_GROWL));
        if (variables.bool("dragon-event.effects-enabled")) {
            arena.spawnParticle(Particle.DRAGON_BREATH, dragon.getLocation(),
                    variables.integer("dragon-event.spawn-particle-count"), 8, 5, 8, 0.08);
        }
    }

    private void finishRun(boolean victory, Player killer) {
        if (phase != Phase.FIGHT) return;
        flushDamageStats();
        if (!victory) {
            announce(variables.string("dragon-event.timeout-message"),
                    configuredSound("dragon-event.timeout-sound", Sound.BLOCK_BEACON_DEACTIVATE));
            phase = Phase.WAITING;
            sendEveryoneHome();
            resetSchedule();
            return;
        }
        phase = Phase.REWARDS;
        phaseEndsAt = System.currentTimeMillis()
                + variables.integer("dragon-event.crate-minutes") * 60_000L;
        for (UUID playerId : activeParticipants()) {
            giveKeys(playerId, variables.integer("dragon-event.kill-keys"));
        }
        String name = killer == null ? "the team" : killer.getName();
        announce(render(variables.string("dragon-event.victory-message"), "player", name),
                configuredSound("dragon-event.victory-sound", Sound.UI_TOAST_CHALLENGE_COMPLETE));
        spawnRewardArea();
    }

    private void closeRewards() {
        if (phase != Phase.REWARDS) return;
        if (arena != null && variables.bool("dragon-event.effects-enabled")) {
            Location at = new Location(arena, 5.5, 75.5, 0.5);
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
        phase = Phase.WAITING;
        phaseEndsAt = 0L;
        dragon = null;
        setPortalLit(false);
        CrateKind.dragonAvailableSource(() -> false);
        scheduledAt = nextEvent(Instant.now().plusSeconds(30));
        clearArenaEntities();
        clearRewardArea();
        refreshPortalDisplay();
    }

    private void ensureArena() {
        arena = Bukkit.getWorld(WORLD_NAME);
        if (arena == null) {
            arena = Bukkit.createWorld(new WorldCreator(WORLD_NAME)
                    .environment(World.Environment.THE_END)
                    .generateStructures(false));
        }
        if (arena == null) throw new IllegalStateException("Could not create the Amethyst Dragon world.");
        arena.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        arena.setGameRule(GameRule.KEEP_INVENTORY, true);
        arena.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        arena.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        arena.getWorldBorder().setCenter(0.5, 0.5);
        arena.getWorldBorder().setSize(variables.integer("dragon-event.border-size"));
        arena.setSpawnLocation(0, 82, 0);
    }

    private void buildArena() {
        clearArenaEntities();
        clearRewardArea();
        int radius = Math.min(variables.integer("dragon-event.arena-radius"),
                variables.integer("dragon-event.border-size") / 2 - 8);
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                double distance = Math.sqrt(x * x + z * z);
                if (distance <= radius - 6 && (x * 31 + z * 17) % 7 == 0) {
                    int surface = arena.getHighestBlockYAt(x, z);
                    Block top = arena.getBlockAt(x, surface, z);
                    if (top.getType() == Material.END_STONE) {
                        top.setType((x + z) % 11 == 0
                                ? Material.BUDDING_AMETHYST : Material.AMETHYST_BLOCK, false);
                    }
                }
                if (distance <= radius && distance >= radius - 5) {
                    arena.getBlockAt(x, 72, z).setType((x + z) % 3 == 0
                            ? Material.AMETHYST_BLOCK : Material.CRYING_OBSIDIAN, false);
                }
            }
        }
        int crystals = variables.integer("dragon-event.crystals");
        for (int index = 0; index < crystals; index++) {
            double angle = Math.PI * 2d * index / crystals;
            int pillarRadius = variables.integer("dragon-event.pillar-radius");
            int x = (int) Math.round(Math.cos(angle) * pillarRadius);
            int z = (int) Math.round(Math.sin(angle) * pillarRadius);
            int height = variables.integer("dragon-event.pillar-base-height")
                    + (index % 5) * variables.integer("dragon-event.pillar-height-step");
            for (int y = 73; y <= 73 + height; y++) {
                arena.getBlockAt(x, y, z).setType(y % 4 == 0 ? Material.AMETHYST_BLOCK : Material.OBSIDIAN, false);
            }
        }
    }

    private void spawnCrystals() {
        int count = variables.integer("dragon-event.crystals");
        for (int index = 0; index < count; index++) {
            double angle = Math.PI * 2d * index / count;
            int pillarRadius = variables.integer("dragon-event.pillar-radius");
            int x = (int) Math.round(Math.cos(angle) * pillarRadius);
            int z = (int) Math.round(Math.sin(angle) * pillarRadius);
            int height = variables.integer("dragon-event.pillar-base-height")
                    + (index % 5) * variables.integer("dragon-event.pillar-height-step");
            arena.spawn(new Location(arena, x + 0.5, 74 + height, z + 0.5), EnderCrystal.class, crystal -> {
                crystal.addScoreboardTag(CRYSTAL_TAG);
                crystal.setShowingBottom(true);
                crystal.setBeamTarget(new Location(arena, 0.5, 88, 0.5));
            });
        }
    }

    private void spawnRewardArea() {
        Location center = new Location(arena, 0, 75, 0);
        for (int x = -4; x <= 4; x++) {
            for (int y = 73; y <= 80; y++) {
                for (int z = -4; z <= 4; z++) {
                    Block block = arena.getBlockAt(x, y, z);
                    if (block.getType() == Material.DRAGON_EGG
                            || block.getType() == Material.END_PORTAL) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) arena.getBlockAt(x, 74, z).setType(Material.END_PORTAL, false);
        }
        arena.getBlockAt(5, 74, 0).setType(Material.CHEST, false);
        label(new Location(arena, 5.5, 76.2, 0.5), Component.text("AMETHYST DRAGON CRATE", AMETHYST,
                TextDecoration.BOLD), 1.35f);
        int eggs = variables.integer("dragon-event.egg-count");
        for (int index = 0; index < eggs; index++) {
            int x = -3 + index * 2;
            Block egg = arena.getBlockAt(x, 75, 4);
            egg.setType(Material.DRAGON_EGG, false);
            claimableEggs.add(blockKey(egg));
        }
        label(center.clone().add(0.5, 3.2, 0.5), Component.text("RETURN TO SPAWN", NamedTextColor.WHITE,
                TextDecoration.BOLD), 1.2f);
        int particles = variables.integer("dragon-event.reward-area-particle-count");
        arena.spawnParticle(Particle.END_ROD, center, particles, 8, 5, 8, 0.12);
        arena.spawnParticle(Particle.DUST, center, particles, 8, 5, 8, 0.05, BRIGHT);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        Player player = event.getPlayer();
        if (phase == Phase.PORTAL_OPEN && portal != null && near(
                event.getTo(), portal.location(), variables.integer("dragon-event.portal-entry-radius"))) {
            enter(player);
            return;
        }
        if (phase == Phase.REWARDS && isArena(event.getTo().getWorld())
                && event.getTo().distanceSquared(new Location(arena, 0.5, 74.5, 0.5)) <= 5d) {
            leave(player);
        }
    }

    private void enter(Player player) {
        if (entrants.add(player.getUniqueId())) {
            RunStats row = stats.computeIfAbsent(player.getUniqueId(), ignored -> new RunStats());
            row.enteredAt = System.currentTimeMillis();
        }
        player.teleport(arenaSpawn());
        player.sendMessage(prefix().append(Component.text(
                "Fight together. The entrance seals when the countdown ends.", NamedTextColor.WHITE)));
    }

    private void leave(Player player) {
        departed.add(player.getUniqueId());
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
        if (phase == Phase.FIGHT) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text(
                    "The Dragon arena is sealed until the fight ends.", NamedTextColor.RED));
        } else if (phase == Phase.REWARDS) {
            departed.add(playerId);
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
        double damage = Math.max(0d, Math.min(event.getFinalDamage(), target.getHealth()));
        if (damage <= 0d) return;
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
        if (variables.bool("dragon-event.effects-enabled")) {
            at.getWorld().spawnParticle(Particle.DRAGON_BREATH, at,
                    variables.integer("dragon-event.crystal-particle-count"),
                    1.2, 1.2, 1.2, 0.09);
            at.getWorld().strikeLightningEffect(at);
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

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (phase == Phase.REWARDS && event.getClickedBlock() != null && isArena(event.getClickedBlock().getWorld())) {
            Block block = event.getClickedBlock();
            if (block.getType() == Material.CHEST && block.getX() == 5 && block.getY() == 74 && block.getZ() == 0) {
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
            player.sendActionBar(Component.text("+" + amount + " Dragon Keys", AMETHYST,
                    TextDecoration.BOLD));
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
        player.showTitle(net.kyori.adventure.title.Title.title(
                Component.text("DRAGON EVENT COMPLETE", AMETHYST, TextDecoration.BOLD),
                Component.text(Math.round(row.damage) + " damage • " + row.crystals
                        + " crystals • " + row.keys + " Keys", NamedTextColor.WHITE)));
        player.sendMessage(prefix().append(Component.text(
                "Your stats: " + Math.round(row.damage) + " damage, " + row.crystals
                        + " crystals, " + row.keys + " Keys, " + seconds + " seconds.",
                NamedTextColor.WHITE)));
    }

    private void sendEveryoneHome() {
        if (arena == null) return;
        for (Player player : List.copyOf(arena.getPlayers())) {
            showStats(player);
            teleportSpawn(player);
        }
    }

    private void teleportSpawn(Player player) {
        World world = Bukkit.getWorlds().stream()
                .filter(candidate -> candidate.getEnvironment() == World.Environment.NORMAL)
                .findFirst().orElse(Bukkit.getWorlds().getFirst());
        player.teleport(world.getSpawnLocation().clone().add(0.5, 0.1, 0.5));
    }

    private Location arenaSpawn() {
        ensureArena();
        return new Location(arena, 0.5, 82.0, 18.5, 180f, 0f);
    }

    private void pulseEffects() {
        if (!variables.bool("dragon-event.effects-enabled")) return;
        if (phase == Phase.PORTAL_OPEN && portal != null && portal.location() != null) {
            Location at = portal.location().clone().add(0, 1.5, 0);
            at.getWorld().spawnParticle(Particle.DUST, at,
                    variables.integer("dragon-event.portal-pulse-particles"),
                    2.0, 2.5, 2.0, 0.01, BRIGHT);
            at.getWorld().spawnParticle(Particle.REVERSE_PORTAL, at,
                    Math.max(1, variables.integer("dragon-event.portal-pulse-particles") / 2),
                    1.5, 2.0, 1.5, 0.04);
        }
        if (phase == Phase.FIGHT && arena != null && dragon != null && dragon.isValid()) {
            arena.spawnParticle(Particle.DUST, dragon.getLocation(),
                    variables.integer("dragon-event.ambient-particle-count"),
                    2.5, 1.2, 2.5, 0.02, DARK);
            if (ThreadLocalRandom.current().nextInt(
                    variables.integer("dragon-event.lightning-one-in")) == 0) {
                arena.strikeLightningEffect(dragon.getLocation());
            }
            if (ThreadLocalRandom.current().nextInt(
                    variables.integer("dragon-event.blast-one-in-per-second")) == 0) {
                amethystBlast();
            }
        }
    }

    private void amethystBlast() {
        double radius = variables.decimal("dragon-event.blast-radius");
        double damage = variables.decimal("dragon-event.blast-damage");
        Location center = dragon.getLocation();
        arena.spawnParticle(Particle.DRAGON_BREATH, center,
                variables.integer("dragon-event.blast-particle-count"), radius / 2d, 2d,
                radius / 2d, 0.06d);
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
                    .filter(entity -> entity.getScoreboardTags().contains(DISPLAY_TAG)).toList();
            if (displays.size() < 2) {
                refreshPortalDisplay();
                return;
            }
            displays.stream().min(Comparator.comparingDouble(Entity::getY))
                    .ifPresent(display -> display.text(Component.text(statusLine(), NamedTextColor.WHITE)));
        }
        if (phase == Phase.REWARDS && arena != null) {
            arena.getEntitiesByClass(TextDisplay.class).stream()
                    .filter(entity -> entity.getScoreboardTags().contains(DISPLAY_TAG))
                    .filter(entity -> entity.getX() > 3)
                    .findFirst().ifPresent(display -> display.text(Component.text(
                            "AMETHYST DRAGON CRATE • " + duration(phaseEndsAt - System.currentTimeMillis()),
                            AMETHYST, TextDecoration.BOLD)));
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
                yield opens > 0 ? "Portal opens in " + duration(opens)
                        : "Next Amethyst Dragon Event: " + duration(until);
            }
            case PORTAL_OPEN -> "PORTAL OPEN • " + duration(scheduledAt.toEpochMilli() - System.currentTimeMillis());
            case FIGHT -> "EVENT IN PROGRESS";
            case REWARDS -> "DRAGON DEFEATED • CRATE OPEN";
        };
    }

    private void refreshPortalDisplay() {
        clearDisplays();
        if (portal == null || portal.location() == null) return;
        Location at = portal.location();
        label(at.clone().add(0, 5.0, 0), Component.text(
                variables.string("dragon-event.portal-hologram-title"), AMETHYST, TextDecoration.BOLD), 1.7f);
        label(at.clone().add(0, 4.25, 0), Component.text(statusLine(), NamedTextColor.WHITE), 1.2f);
    }

    private TextDisplay label(Location at, Component text, float scale) {
        return at.getWorld().spawn(at, TextDisplay.class, display -> {
            display.addScoreboardTag(DISPLAY_TAG);
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

    private void clearDisplays() {
        for (World world : Bukkit.getWorlds()) {
            world.getEntitiesByClass(TextDisplay.class).stream()
                    .filter(entity -> entity.getScoreboardTags().contains(DISPLAY_TAG))
                    .forEach(Entity::remove);
        }
    }

    private void clearArenaEntities() {
        if (arena == null) return;
        arena.getEntities().stream().filter(entity -> !(entity instanceof Player))
                .filter(entity -> entity instanceof EnderDragon || entity instanceof EnderCrystal
                        || entity.getScoreboardTags().contains(DRAGON_TAG)
                        || entity.getScoreboardTags().contains(CRYSTAL_TAG)
                        || entity.getScoreboardTags().contains(DISPLAY_TAG))
                .forEach(Entity::remove);
    }

    private void clearRewardArea() {
        if (arena == null) return;
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                Block block = arena.getBlockAt(x, 74, z);
                if (block.getType() == Material.END_PORTAL) block.setType(Material.AIR, false);
            }
        }
        Block chest = arena.getBlockAt(5, 74, 0);
        if (chest.getType() == Material.CHEST) chest.setType(Material.AIR, false);
        for (int x = -32; x <= 32; x++) {
            Block egg = arena.getBlockAt(x, 75, 4);
            if (egg.getType() == Material.DRAGON_EGG) egg.setType(Material.AIR, false);
        }
        claimableEggs.clear();
    }

    /** Adds actual vanilla light blocks without changing the owner's portal structure. */
    private void setPortalLit(boolean lit) {
        if (!lit) {
            for (Location location : portalLights) {
                if (location.getWorld() != null && location.getBlock().getType() == Material.LIGHT) {
                    location.getBlock().setType(Material.AIR, false);
                }
            }
            portalLights.clear();
            return;
        }
        setPortalLit(false);
        if (portal == null || portal.location() == null) return;
        Block centre = portal.location().getBlock();
        int radius = variables.integer("dragon-event.portal-light-radius");
        int height = variables.integer("dragon-event.portal-light-height");
        int level = variables.integer("dragon-event.portal-light-level");
        for (int y = 0; y <= height; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    Block block = centre.getRelative(x, y, z);
                    if (!block.getType().isAir()) continue;
                    block.setType(Material.LIGHT, false);
                    if (block.getBlockData() instanceof Levelled light) {
                        light.setLevel(level);
                        block.setBlockData(light, false);
                    }
                    portalLights.add(block.getLocation());
                }
            }
        }
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
        if (args.length == 1 && args[0].equalsIgnoreCase("start")) {
            scheduledAt = Instant.now().plusSeconds(
                    variables.integer("dragon-event.admin-start-delay-seconds"));
            openPortal();
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Register or remove the Dragon portal in game.");
            return true;
        }
        try {
            if (args.length == 1 && args[0].equalsIgnoreCase("set")) {
                Block target = player.getTargetBlockExact(12);
                if (target == null) throw new IllegalArgumentException("Look at the centre of the portal.");
                setPortalLit(false);
                Location at = target.getLocation().add(0.5, 0.5, 0.5);
                portal = new Portal(at.getWorld().getUID(), at.getX(), at.getY(), at.getZ());
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
                player.sendMessage("Use /dragonportal set|remove|status|start.");
            }
        } catch (IOException | IllegalArgumentException exception) {
            PlayerMenuService.error(player, exception.getMessage());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("set", "remove", "status", "start").stream()
                .filter(value -> value.startsWith(prefix)).toList();
    }

    private void loadPortal() throws IOException {
        if (!Files.isRegularFile(portalFile) || Files.size(portalFile) == 0L) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(portalFile)).getAsJsonObject();
            if (root.isEmpty()) return;
            portal = new Portal(UUID.fromString(root.get("world_id").getAsString()),
                    root.get("x").getAsDouble(), root.get("y").getAsDouble(), root.get("z").getAsDouble());
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
