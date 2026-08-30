package bot.mgx.accessbridge;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Marker;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.random.RandomGenerator;

/** A cooperative event built from a real, solid twelve-block-wide Amethyst cube. */
final class AmethystBlockEventService implements Listener {
    static final long DEFAULT_LIFETIME_MILLIS = Duration.ofMinutes(30).toMillis();
    static final int STRUCTURE_SIZE = 12;
    private static final int MIN_OFFSET = -STRUCTURE_SIZE / 2;
    private static final int MAX_OFFSET = MIN_OFFSET + STRUCTURE_SIZE - 1;
    private static final int DEFAULT_MINIMUM_RADIUS = 500;
    private static final int DEFAULT_ATTEMPTS = 24;
    private static final int BORDER_MARGIN = 32;
    private static final int MINE_REACH = 7;
    private static final String ENTITY_TAG = "mgx_amethyst_block_event";
    private static final String MARKER_TAG = "mgx_amethyst_block_anchor";
    private static final TextColor AMETHYST = TextColor.color(0xB56CFF);
    private static final Particle.DustOptions DEEP = new Particle.DustOptions(
            Color.fromRGB(91, 20, 178), 2.4f
    );
    private static final Particle.DustOptions BRIGHT = new Particle.DustOptions(
            Color.fromRGB(239, 181, 255), 2.0f
    );
    private static final Particle.DustOptions WHITE = new Particle.DustOptions(
            Color.fromRGB(255, 238, 255), 1.35f
    );

    private record Candidate(World world, int x, int z) {
    }

    private record BlockPosition(int x, int y, int z) {
    }

    record Snapshot(String world, int x, int y, int z, double health, double maximumHealth) {
        String describe() {
            return "Huge Amethyst Block at X " + x + " • Y " + y + " • Z " + z
                    + " in the " + world + " • " + Math.max(0L, Math.round(health))
                    + "/" + Math.round(maximumHealth) + " HP";
        }
    }

    private static final class ActiveBlock {
        private final UUID id = UUID.randomUUID();
        /** Bottom-centre coordinate; the cube spans offsets -6 through +5. */
        private final Location anchor;
        private final Marker marker;
        private final BlockDisplay visual;
        private final TextDisplay title;
        private final TextDisplay countdown;
        private final Set<Chunk> chunks;
        private final Map<BlockPosition, BlockData> originals;
        private final long spawnedAt;
        private final long expiresAt;
        private final Map<UUID, BlockPosition> mining = new HashMap<>();
        private final Map<UUID, Double> damage = new HashMap<>();
        private final double maximumHealth;
        private double health;
        private int nextMilestone;
        private boolean finishing;

        private ActiveBlock(
                Location anchor, Marker marker, BlockDisplay visual,
                TextDisplay title, TextDisplay countdown,
                Set<Chunk> chunks, Map<BlockPosition, BlockData> originals,
                long spawnedAt, long expiresAt, double maximumHealth
        ) {
            this.anchor = anchor;
            this.marker = marker;
            this.visual = visual;
            this.title = title;
            this.countdown = countdown;
            this.chunks = chunks;
            this.originals = originals;
            this.spawnedAt = spawnedAt;
            this.expiresAt = expiresAt;
            this.maximumHealth = maximumHealth;
            this.health = maximumHealth;
        }
    }

    private final MGXAccessBridge plugin;
    private final CrateItems crateItems;
    private final PlayerSettingsStore settings;
    private final GameVariableStore variables;
    private final RandomGenerator random;
    private final Path journal;
    private final Set<Item> visualKeys = new HashSet<>();

    private ActiveBlock active;
    private BukkitTask frameTask;
    private BukkitTask visualTrailTask;
    private BukkitTask finaleTask;
    private BossBar bossBar;
    private volatile boolean stopped = true;
    private BooleanSupplier otherEventActive = () -> false;
    private Runnable spawnedCallback;
    private Runnable finishedCallback;
    private Runnable failedCallback;

    AmethystBlockEventService(
            MGXAccessBridge plugin, CrateItems crateItems, PlayerSettingsStore settings,
            GameVariableStore variables
    ) {
        this(plugin, crateItems, settings, variables, ThreadLocalRandom.current());
    }

    AmethystBlockEventService(
            MGXAccessBridge plugin, CrateItems crateItems, PlayerSettingsStore settings,
            GameVariableStore variables, RandomGenerator random
    ) {
        this.plugin = plugin;
        this.crateItems = crateItems;
        this.settings = settings;
        this.variables = variables;
        this.random = random;
        journal = plugin.getDataFolder().toPath().resolve("amethyst-block-event.yml");
    }

    void start() {
        stop();
        clearStaleStructures();
        stopped = false;
        visualTrailTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::trailVisualKeys, 2L, 2L
        );
    }

    void stop() {
        stopped = true;
        clearCoordinatorCallbacks();
        removeActive(false, null);
        if (visualTrailTask != null) {
            visualTrailTask.cancel();
            visualTrailTask = null;
        }
        visualKeys.forEach(Entity::remove);
        visualKeys.clear();
    }

    void blockWhile(BooleanSupplier otherEventActive) {
        this.otherEventActive = otherEventActive == null ? () -> false : otherEventActive;
    }

    boolean beginScheduled(Runnable onSpawned, Runnable onFinished, Runnable onFailed) {
        if (stopped || !variables.bool("huge-amethyst.enabled")
                || active != null || spawnedCallback != null
                || otherEventActive.getAsBoolean()
                || !CrateKind.AMETHYST.available(System.currentTimeMillis())) {
            return false;
        }
        spawnedCallback = onSpawned;
        finishedCallback = onFinished;
        failedCallback = onFailed;
        attemptSpawn(0);
        return true;
    }

    boolean isActiveOrSpawning() {
        return active != null || spawnedCallback != null;
    }

    Snapshot spawnTest(Player player) {
        if (!plugin.isLocalTestServer()) {
            throw new IllegalArgumentException(
                    "Amethyst Block tests are available only on the local test server."
            );
        }
        return spawnNear(player);
    }

    /** Calls a Huge Amethyst Block in near an administrator on any server. */
    Snapshot spawnNear(Player player) {
        if (stopped) {
            throw new IllegalArgumentException("Huge Amethyst Blocks are not running.");
        }
        if (active != null || spawnedCallback != null || otherEventActive.getAsBoolean()) {
            throw new IllegalArgumentException("An Amethyst world event is already active or spawning.");
        }
        if (player.getWorld().getEnvironment() != World.Environment.NORMAL
                || VerificationLobbyService.isLobbyWorld(player.getWorld())) {
            throw new IllegalArgumentException("Run this in the Overworld outside the verification lobby.");
        }
        Location anchor = findTestAnchor(player);
        if (anchor == null) {
            throw new IllegalArgumentException("No flat, empty site was found nearby.");
        }
        create(anchor);
        return snapshot();
    }

    Snapshot snapshot() {
        ActiveBlock block = active;
        if (block == null) {
            return null;
        }
        return new Snapshot(
                worldName(block.anchor.getWorld()), block.anchor.getBlockX(),
                block.anchor.getBlockY(), block.anchor.getBlockZ(), block.health,
                block.maximumHealth
        );
    }

    boolean damageTest(double amount) {
        if (active == null || active.finishing || amount <= 0d) {
            return false;
        }
        applyDamage(null, Math.min(amount, active.health));
        return true;
    }

    boolean expireTest() {
        if (active == null) {
            return false;
        }
        removeActive(true, "The Huge Amethyst Block dissolved before it was broken.");
        return true;
    }

    boolean removeTest() {
        if (active == null) {
            return false;
        }
        removeActive(false, null);
        return true;
    }

    private void attemptSpawn(int attempt) {
        if (stopped || active != null || otherEventActive.getAsBoolean()) {
            failScheduledSpawn();
            return;
        }
        int attempts = variables.integer("huge-amethyst.location-attempts");
        if (attempt >= attempts) {
            plugin.getLogger().warning("Could not find open ground for a Huge Amethyst Block after "
                    + attempts + " attempts; retrying this event later.");
            failScheduledSpawn();
            return;
        }
        Candidate candidate = randomCandidate();
        if (candidate == null) {
            failScheduledSpawn();
            return;
        }
        candidate.world().getChunkAtAsync(candidate.x() >> 4, candidate.z() >> 4, true)
                .whenComplete((chunk, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        attemptSpawn(attempt + 1);
                        return;
                    }
                    Location anchor = findAnchor(candidate);
                    if (anchor == null || !safeSite(anchor)) {
                        attemptSpawn(attempt + 1);
                        return;
                    }
                    try {
                        create(anchor);
                    } catch (RuntimeException exception) {
                        plugin.getLogger().warning("Could not create a Huge Amethyst Block: "
                                + exception.getMessage());
                        attemptSpawn(attempt + 1);
                        return;
                    }
                    Runnable callback = spawnedCallback;
                    spawnedCallback = null;
                    if (callback != null) {
                        callback.run();
                    }
                }));
    }

    private Candidate randomCandidate() {
        List<World> worlds = plugin.getServer().getWorlds().stream()
                .filter(world -> world.getEnvironment() == World.Environment.NORMAL)
                .filter(world -> !VerificationLobbyService.isLobbyWorld(world))
                .toList();
        if (worlds.isEmpty()) {
            return null;
        }
        World world = worlds.get(random.nextInt(worlds.size()));
        WorldBorder border = world.getWorldBorder();
        Location centre = border.getCenter();
        int limit = Math.max(1, (int) Math.floor(border.getSize() / 2d) - BORDER_MARGIN);
        for (int index = 0; index < 24; index++) {
            int x = centre.getBlockX() + random.nextInt(-limit, limit + 1);
            int z = centre.getBlockZ() + random.nextInt(-limit, limit + 1);
            int minimumRadius = variables.integer("huge-amethyst.minimum-radius");
            if ((long) x * x + (long) z * z < (long) minimumRadius * minimumRadius) {
                continue;
            }
            return new Candidate(world, x, z);
        }
        return null;
    }

    private Location findAnchor(Candidate candidate) {
        World world = candidate.world();
        int highest = world.getMinHeight();
        int lowest = world.getMaxHeight();
        for (int x = MIN_OFFSET; x <= MAX_OFFSET; x++) {
            for (int z = MIN_OFFSET; z <= MAX_OFFSET; z++) {
                int groundY = world.getHighestBlockYAt(
                        candidate.x() + x, candidate.z() + z,
                        HeightMap.MOTION_BLOCKING_NO_LEAVES
                );
                Material ground = world.getBlockAt(
                        candidate.x() + x, groundY, candidate.z() + z
                ).getType();
                if (!ground.isSolid() || ground == Material.BEDROCK) {
                    return null;
                }
                highest = Math.max(highest, groundY);
                lowest = Math.min(lowest, groundY);
            }
        }
        if (highest - lowest > 3) {
            return null;
        }
        return new Location(world, candidate.x(), highest + 1, candidate.z());
    }

    private Location findTestAnchor(Player player) {
        Location origin = player.getLocation();
        double facing = Math.toRadians(origin.getYaw() + 90d);
        for (int radius = 18; radius <= 120; radius += 6) {
            for (int point = 0; point < 16; point++) {
                double angle = facing + point * Math.PI * 2d / 16d;
                int x = origin.getBlockX() + (int) Math.round(Math.cos(angle) * radius);
                int z = origin.getBlockZ() + (int) Math.round(Math.sin(angle) * radius);
                Location anchor = findAnchor(new Candidate(player.getWorld(), x, z));
                if (anchor != null && safeSite(anchor)) {
                    return anchor;
                }
            }
        }
        return null;
    }

    /** The physical cube never overwrites terrain: every column is flat and every cell is air. */
    private boolean safeSite(Location anchor) {
        World world = anchor.getWorld();
        if (anchor.getBlockY() + STRUCTURE_SIZE + 4 >= world.getMaxHeight()) {
            return false;
        }
        for (int x = MIN_OFFSET; x <= MAX_OFFSET; x++) {
            for (int z = MIN_OFFSET; z <= MAX_OFFSET; z++) {
                Location edge = anchor.clone().add(x, 0d, z);
                if (!world.getWorldBorder().isInside(edge)) {
                    return false;
                }
                int ground = world.getHighestBlockYAt(
                        edge.getBlockX(), edge.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES
                );
                if (ground + 1 > anchor.getBlockY()
                        || anchor.getBlockY() - (ground + 1) > 3) {
                    return false;
                }
                for (int y = 0; y < STRUCTURE_SIZE; y++) {
                    if (!world.getBlockAt(edge.getBlockX(), anchor.getBlockY() + y,
                            edge.getBlockZ()).isEmpty()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void create(Location anchor) {
        World world = anchor.getWorld();
        Set<Chunk> chunks = ticketChunks(anchor);
        Map<BlockPosition, BlockData> originals = new LinkedHashMap<>();
        List<Entity> created = new ArrayList<>();
        try {
            writeJournal(anchor);
            Marker marker = world.spawn(anchor.clone().add(0d, 0.1d, 0d), Marker.class, entity -> {
                entity.setPersistent(true);
                entity.addScoreboardTag(ENTITY_TAG);
                entity.addScoreboardTag(MARKER_TAG);
            });
            created.add(marker);
            forEachCubeBlock(anchor, block -> {
                if (!block.isEmpty()) {
                    throw new IllegalStateException("The event site stopped being empty.");
                }
                BlockPosition position = position(block);
                originals.put(position, block.getBlockData().clone());
                block.setType(Material.AMETHYST_BLOCK, false);
            });

            BlockDisplay visual = world.spawn(new Location(
                    world, anchor.getBlockX() + MIN_OFFSET, anchor.getBlockY(),
                    anchor.getBlockZ() + MIN_OFFSET
            ), BlockDisplay.class, display -> {
                display.setBlock(Material.AMETHYST_BLOCK.createBlockData());
                display.setViewRange(12f);
                display.setTransformation(new Transformation(
                        new Vector3f(-0.01f, -0.01f, -0.01f), new Quaternionf(),
                        new Vector3f(12.02f, 12.02f, 12.02f), new Quaternionf()
                ));
                decorate(display);
            });
            created.add(visual);

            TextDisplay title = label(anchor.clone().add(0d, STRUCTURE_SIZE + 3d, 0d),
                    Component.text("HUGE AMETHYST BLOCK", AMETHYST, TextDecoration.BOLD), 2.7f);
            created.add(title);
            long lifetimeMillis = Duration.ofMinutes(
                    variables.integer("huge-amethyst.lifetime-minutes")
            ).toMillis();
            TextDisplay countdown = label(anchor.clone().add(0d, STRUCTURE_SIZE + 1.7d, 0d),
                    countdownText(lifetimeMillis), 2.0f);
            created.add(countdown);

            long spawnedAt = System.currentTimeMillis();
            double maximumHealth = variables.integer("huge-amethyst.maximum-health");
            active = new ActiveBlock(
                    anchor.clone(), marker, visual, title, countdown, Set.copyOf(chunks),
                    Map.copyOf(originals), spawnedAt, Math.addExact(spawnedAt, lifetimeMillis),
                    maximumHealth
            );
            bossBar = BossBar.bossBar(
                    bossTitle(active), 1f, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_20
            );
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                showBar(player);
                playSpawnCue(player);
            }
            frameTask = plugin.getServer().getScheduler().runTaskTimer(
                    plugin, this::frame, 1L, 5L
            );
            spawnArrival(active);
            announce(EventBanner.chat(
                    "Huge Amethyst Block", AMETHYST, worldName(world),
                    anchor.getBlockX(), anchor.getBlockY(), anchor.getBlockZ(),
                    "Mine it together within",
                    AirdropService.formatCountdown(active.expiresAt - System.currentTimeMillis())
            ));
            plugin.getLogger().info("Spawned solid " + STRUCTURE_SIZE + "x" + STRUCTURE_SIZE
                    + "x" + STRUCTURE_SIZE + " Huge Amethyst Block at " + coordinates(anchor)
                    + " in " + worldName(world));
        } catch (RuntimeException exception) {
            restoreBlocks(world, originals);
            created.forEach(Entity::remove);
            chunks.forEach(chunk -> chunk.removePluginChunkTicket(plugin));
            clearJournal();
            throw exception;
        }
    }

    private Set<Chunk> ticketChunks(Location anchor) {
        World world = anchor.getWorld();
        int minChunkX = (anchor.getBlockX() + MIN_OFFSET) >> 4;
        int maxChunkX = (anchor.getBlockX() + MAX_OFFSET) >> 4;
        int minChunkZ = (anchor.getBlockZ() + MIN_OFFSET) >> 4;
        int maxChunkZ = (anchor.getBlockZ() + MAX_OFFSET) >> 4;
        Set<Chunk> chunks = new HashSet<>();
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                Chunk chunk = world.getChunkAt(x, z);
                chunk.addPluginChunkTicket(plugin);
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    private TextDisplay label(Location at, Component text, float scale) {
        return at.getWorld().spawn(at, TextDisplay.class, display -> {
            display.text(text);
            display.setBillboard(Display.Billboard.CENTER);
            display.setSeeThrough(true);
            display.setShadowed(true);
            display.setViewRange(12f);
            display.setTransformation(new Transformation(
                    new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale),
                    new Quaternionf()
            ));
            decorate(display);
        });
    }

    private static void decorate(Entity entity) {
        entity.setPersistent(false);
        entity.setInvulnerable(true);
        entity.addScoreboardTag(ENTITY_TAG);
    }

    /** Mining begins by damaging one of the real blocks, never by attacking an entity. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMineStart(BlockDamageEvent event) {
        ActiveBlock block = active;
        if (block == null || !contains(block, event.getBlock())) {
            return;
        }
        event.setInstaBreak(false);
        if (block.finishing || miningRate(event.getPlayer()) <= 0d) {
            event.setCancelled(true);
            if (!block.finishing) {
                PlayerMenuService.error(event.getPlayer(),
                        "Use a pickaxe to mine the Huge Amethyst Block.");
            }
            return;
        }
        block.mining.put(event.getPlayer().getUniqueId(), position(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMineStop(BlockDamageAbortEvent event) {
        ActiveBlock block = active;
        if (block != null && contains(block, event.getBlock())) {
            block.mining.remove(event.getPlayer().getUniqueId());
        }
    }

    /** Vanilla may finish one crack cycle, but the shared HP owns destruction. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        ActiveBlock block = active;
        if (block == null || !contains(block, event.getBlock())) {
            return;
        }
        event.setCancelled(true);
        event.setDropItems(false);
        event.setExpToDrop(0);
        if (!block.finishing && miningRate(event.getPlayer()) > 0d) {
            block.mining.put(event.getPlayer().getUniqueId(), position(event.getBlock()));
            fracture(event.getBlock().getLocation().add(0.5d, 0.5d, 0.5d), false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        protectExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        protectExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        ActiveBlock block = active;
        if (block != null && event.getBlocks().stream().anyMatch(moved ->
                contains(block, moved) || contains(block, moved.getRelative(event.getDirection())))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        ActiveBlock block = active;
        if (block != null && event.getBlocks().stream().anyMatch(moved ->
                contains(block, moved) || contains(block, moved.getRelative(event.getDirection())))) {
            event.setCancelled(true);
        }
    }

    private void protectExplosion(List<Block> affected) {
        ActiveBlock block = active;
        if (block != null) {
            affected.removeIf(candidate -> contains(block, candidate));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (active != null) {
            showBar(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (active != null) {
            active.mining.remove(event.getPlayer().getUniqueId());
        }
    }

    private void frame() {
        ActiveBlock block = active;
        if (block == null || block.finishing) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= block.expiresAt) {
            removeActive(true, "The Huge Amethyst Block dissolved before it was broken.");
            return;
        }
        List<Player> miners = block.mining.keySet().stream()
                .map(plugin.getServer()::getPlayer)
                .filter(player -> validMiner(block, player))
                .toList();
        block.mining.keySet().removeIf(id -> miners.stream()
                .noneMatch(player -> player.getUniqueId().equals(id)));
        double raw = miners.stream().mapToDouble(this::miningRate).sum();
        double damage = AmethystBlockRewards.groupDamagePerSecond(raw, miners.size()) / 4d;
        if (damage > 0d) {
            double multiplier = damage / raw;
            for (Player miner : miners) {
                double dealt = miningRate(miner) / 4d * multiplier;
                block.damage.merge(miner.getUniqueId(), dealt, Double::sum);
                Block target = miner.getTargetBlockExact(MINE_REACH);
                if (target != null && plugin.getServer().getCurrentTick() % 10 == 0) {
                    fracture(target.getLocation().add(0.5d, 0.5d, 0.5d), false);
                }
            }
            applyDamage(null, damage);
        }
        drawAura(block, now);
        refreshDisplays(block, now);
    }

    private boolean validMiner(ActiveBlock block, Player player) {
        if (player == null || !player.isOnline() || !player.getWorld().equals(block.anchor.getWorld())
                || miningRate(player) <= 0d) {
            return false;
        }
        Block target = player.getTargetBlockExact(MINE_REACH);
        return target != null && contains(block, target);
    }

    private double miningRate(Player player) {
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!tool.getType().name().endsWith("_PICKAXE")) {
            return 0d;
        }
        double tier = switch (tool.getType()) {
            case WOODEN_PICKAXE -> 1d;
            case STONE_PICKAXE -> 1.25d;
            case IRON_PICKAXE -> 1.75d;
            case GOLDEN_PICKAXE -> 2d;
            case DIAMOND_PICKAXE, NETHERITE_PICKAXE -> 2.5d;
            default -> 0d;
        };
        return tier * (1d + 0.2d * tool.getEnchantmentLevel(Enchantment.EFFICIENCY));
    }

    private void applyDamage(Player source, double amount) {
        ActiveBlock block = active;
        if (block == null || block.finishing || amount <= 0d) {
            return;
        }
        if (source != null) {
            block.damage.merge(source.getUniqueId(), amount, Double::sum);
        }
        block.health = Math.max(0d, block.health - amount);
        while (block.nextMilestone < AmethystBlockRewards.REWARD_HEALTH_PERCENTAGES.length
                && healthPercent(block.health, block.maximumHealth)
                <= variables.integer("huge-amethyst.wave."
                        + (block.nextMilestone + 1) + ".health-percent")) {
            rewardEveryone(AmethystBlockRewards.rollMilestone(random, variables), false, block);
            milestoneBurst(block);
            block.nextMilestone++;
        }
        if (block.health <= 0d) {
            rewardEveryone(AmethystBlockRewards.completionBundle(random, variables), true, block);
            complete(block);
        }
    }

    private void rewardEveryone(
            AmethystBlockRewards.Bundle bundle, boolean completion, ActiveBlock block
    ) {
        for (Player player : eligiblePlayers()) {
            int bonus = completion
                    ? AmethystBlockRewards.contributionKeys(
                            player.getUniqueId(), block.damage,
                            variables.integer("huge-amethyst.contribution-base-keys"),
                            variables.integer("huge-amethyst.contribution-pool-keys")
                    ) : 0;
            giveOwned(player, crateItems.key(bundle.keys() + bonus));
            giveOwned(player, new ItemStack(Material.DIAMOND, bundle.diamonds()));
            giveOwned(player, new ItemStack(Material.EMERALD, bundle.emeralds()));
            giveOwned(player, new ItemStack(Material.GOLD_INGOT, bundle.gold()));
            if (bundle.shards() > 0) {
                giveOwned(player, crateItems.shard(bundle.shards()));
            }
            player.sendMessage(PlayerMenuService.prefix()
                    .append(Component.text(completion ? "Block broken! " : "Reward wave! ",
                            AMETHYST, TextDecoration.BOLD))
                    .append(Component.text("Your identical event bundle was delivered"
                            + (bonus > 0 ? " plus " + bonus + " contribution keys." : "."),
                            NamedTextColor.WHITE)));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f,
                    completion ? 1.35f : 1.1f);
        }
    }

    private void giveOwned(Player player, ItemStack stack) {
        if (stack.getAmount() <= 0) {
            return;
        }
        player.getInventory().addItem(stack).values().forEach(overflow -> {
            Item item = player.getWorld().dropItemNaturally(player.getLocation(), overflow);
            item.setOwner(player.getUniqueId());
            item.setPickupDelay(0);
        });
    }

    /** Five staged eruptions make a threshold feel like a key fountain, then rainfall. */
    private void milestoneBurst(ActiveBlock block) {
        launchKeyFountain(block.anchor, 6, 18, false);
        Location centre = cubeCentre(block.anchor);
        World world = centre.getWorld();
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, centre, 220,
                3d, 3d, 3d, 0.8d);
        world.spawnParticle(Particle.EXPLOSION, centre, 18, 4d, 4d, 4d, 0.08d);
        world.playSound(centre, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 12f, 0.7f);
    }

    private void launchKeyFountain(
            Location anchor, int waves, int perWave, boolean finale
    ) {
        Location source = anchor.clone().add(0d, STRUCTURE_SIZE + 0.8d, 0d);
        World world = source.getWorld();
        for (int wave = 0; wave < waves; wave++) {
            int currentWave = wave;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                double turn = currentWave * 0.47d;
                for (int index = 0; index < perWave; index++) {
                    double angle = turn + index * Math.PI * 2d / perWave
                            + random.nextDouble(-0.12d, 0.12d);
                    double speed = finale
                            ? random.nextDouble(0.55d, 1.05d)
                            : random.nextDouble(0.35d, 0.75d);
                    Item item = world.dropItem(source.clone().add(
                            random.nextDouble(-0.8d, 0.8d),
                            random.nextDouble(-0.4d, 0.8d),
                            random.nextDouble(-0.8d, 0.8d)
                    ), crateItems.key(1));
                    item.setGlowing(true);
                    item.setPickupDelay(Integer.MAX_VALUE);
                    item.setPersistent(false);
                    item.setWillAge(false);
                    item.addScoreboardTag(ENTITY_TAG);
                    item.setVelocity(new Vector(
                            Math.cos(angle) * speed,
                            finale ? random.nextDouble(1.65d, 2.35d)
                                    : random.nextDouble(1.25d, 1.9d),
                            Math.sin(angle) * speed
                    ));
                    visualKeys.add(item);
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        visualKeys.remove(item);
                        item.remove();
                    }, finale ? 240L : 180L);
                }
                world.spawnParticle(Particle.TOTEM_OF_UNDYING, source, finale ? 90 : 45,
                        1.5d, 1.5d, 1.5d, finale ? 1.2d : 0.8d);
                world.spawnParticle(Particle.FIREWORK, source, finale ? 35 : 18,
                        1d, 1d, 1d, 0.22d);
                world.playSound(source, Sound.ENTITY_FIREWORK_ROCKET_BLAST,
                        finale ? 16f : 10f, 0.65f + currentWave * 0.04f);
            }, wave * (finale ? 4L : 5L));
        }
    }

    private void trailVisualKeys() {
        visualKeys.removeIf(item -> !item.isValid() || item.isDead());
        for (Item item : visualKeys) {
            Location at = item.getLocation();
            at.getWorld().spawnParticle(Particle.DUST, at, 2, 0.08d, 0.08d, 0.08d, 0d,
                    random.nextBoolean() ? BRIGHT : WHITE);
            if (plugin.getServer().getCurrentTick() % 4 == 0) {
                at.getWorld().spawnParticle(Particle.END_ROD, at, 1, 0d, 0d, 0d, 0d);
            }
        }
    }

    private void complete(ActiveBlock block) {
        block.finishing = true;
        block.mining.clear();
        if (frameTask != null) {
            frameTask.cancel();
            frameTask = null;
        }
        if (bossBar != null) {
            bossBar.progress(0f);
            bossBar.name(Component.text("HUGE AMETHYST BLOCK • SHATTERING", AMETHYST,
                    TextDecoration.BOLD));
        }
        block.title.text(Component.text("AMETHYST CORE SHATTERED!", NamedTextColor.LIGHT_PURPLE,
                TextDecoration.BOLD));
        block.countdown.text(Component.text("REWARDS DELIVERED", NamedTextColor.GREEN,
                TextDecoration.BOLD));

        String strongest = block.damage.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(entry -> {
                    Player player = plugin.getServer().getPlayer(entry.getKey());
                    return player == null ? entry.getKey().toString() : player.getName();
                }).orElse("none");
        ServerEvent.of(
                "amethyst_block_break", ServerEvent.CATEGORY_CRATE, null, "Server",
                plugin::recordServerEvent
        ).summary("Players broke the Huge Amethyst Block")
                .detail("world", worldName(block.anchor.getWorld()))
                .detail("coordinates", coordinates(block.anchor))
                .detail("contributors", String.valueOf(block.damage.size()))
                .detail("top_contributor", strongest)
                .detail("event_id", block.id.toString())
                .record();

        launchKeyFountain(block.anchor, 14, 24, true);
        beginShatterAnimation(block);
    }

    /** The cube disintegrates in 36 visible waves instead of vanishing in one tick. */
    private void beginShatterAnimation(ActiveBlock block) {
        // Reveal the physical blocks before the staged breakup begins.
        block.visual.remove();
        List<BlockPosition> positions = new ArrayList<>(block.originals.keySet());
        shuffle(positions);
        World world = block.anchor.getWorld();
        Location centre = cubeCentre(block.anchor);
        for (int bolt = 0; bolt < 8; bolt++) {
            double angle = bolt * Math.PI * 2d / 8d;
            world.strikeLightningEffect(block.anchor.clone().add(
                    Math.cos(angle) * 8d, 0d, Math.sin(angle) * 8d
            ));
        }
        world.spawnParticle(Particle.EXPLOSION_EMITTER, centre, 8, 4d, 4d, 4d, 0d);
        final int batch = Math.max(1, (positions.size() + 35) / 36);
        finaleTask = new BukkitRunnable() {
            private int cursor;
            private int wave;

            @Override
            public void run() {
                if (active != block) {
                    cancel();
                    finaleTask = null;
                    return;
                }
                int end = Math.min(positions.size(), cursor + batch);
                for (int index = cursor; index < end; index++) {
                    BlockPosition position = positions.get(index);
                    Block physical = world.getBlockAt(position.x(), position.y(), position.z());
                    if (physical.getType() == Material.AMETHYST_BLOCK) {
                        if ((index - cursor) % 6 == 0) {
                            fracture(physical.getLocation().add(0.5d, 0.5d, 0.5d), true);
                        }
                        physical.setType(Material.AIR, false);
                    }
                }
                cursor = end;
                wave++;
                double radius = 2d + wave * 0.22d;
                for (int point = 0; point < 24; point++) {
                    double angle = point * Math.PI * 2d / 24d + wave * 0.28d;
                    world.spawnParticle(Particle.DUST, centre.clone().add(
                            Math.cos(angle) * radius,
                            random.nextDouble(-5d, 5d),
                            Math.sin(angle) * radius
                    ), 3, 0.15d, 0.4d, 0.15d, 0d, wave % 2 == 0 ? DEEP : BRIGHT);
                }
                world.spawnParticle(Particle.ELECTRIC_SPARK, centre, 28,
                        radius, 5d, radius, 0.13d);
                world.playSound(centre, Sound.BLOCK_AMETHYST_BLOCK_BREAK,
                        18f, Math.min(1.9f, 0.55f + wave * 0.035f));
                if (cursor < positions.size()) {
                    return;
                }
                cancel();
                finaleTask = null;
                world.spawnParticle(Particle.EXPLOSION_EMITTER, centre, 14,
                        6d, 6d, 6d, 0d);
                world.spawnParticle(Particle.TOTEM_OF_UNDYING, centre, 850,
                        7d, 8d, 7d, 1.5d);
                world.spawnParticle(Particle.FIREWORK, centre, 300,
                        7d, 8d, 7d, 0.4d);
                world.playSound(centre, Sound.ENTITY_GENERIC_EXPLODE, 25f, 0.55f);
                world.playSound(centre, Sound.UI_TOAST_CHALLENGE_COMPLETE, 20f, 1.1f);
                removeActive(true,
                        "The Huge Amethyst Block shattered! Every online player received rewards.");
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    private void shuffle(List<BlockPosition> positions) {
        for (int index = positions.size() - 1; index > 0; index--) {
            int other = random.nextInt(index + 1);
            BlockPosition value = positions.get(index);
            positions.set(index, positions.get(other));
            positions.set(other, value);
        }
    }

    private void spawnArrival(ActiveBlock block) {
        Location centre = cubeCentre(block.anchor);
        World world = centre.getWorld();
        world.spawnParticle(Particle.EXPLOSION_EMITTER, centre, 6, 4d, 4d, 4d, 0d);
        world.spawnParticle(Particle.REVERSE_PORTAL, centre, 500, 7d, 7d, 7d, 0.12d);
        world.spawnParticle(Particle.END_ROD, centre, 180, 7d, 7d, 7d, 0.08d);
        world.playSound(centre, Sound.ENTITY_ENDER_DRAGON_GROWL, 16f, 0.45f);
    }

    /** The earlier, restrained rotating aura around the whole cube. */
    private void drawAura(ActiveBlock block, long now) {
        Location centre = cubeCentre(block.anchor);
        double phase = now / 550d;
        for (Player viewer : centre.getWorld().getPlayers()) {
            if (!settings.isEnabled(viewer.getUniqueId(),
                    PlayerSettingsStore.Setting.AIRDROP_PARTICLES)) {
                continue;
            }
            for (int ring = 0; ring < 4; ring++) {
                double radius = 7.2d + ring * 0.8d;
                double y = -4.5d + ring * 3d;
                for (int point = 0; point < 32; point++) {
                    double angle = phase * (ring % 2 == 0 ? 1d : -1d)
                            + point * Math.PI * 2d / 32d;
                    Location at = centre.clone().add(
                            Math.cos(angle) * radius,
                            y + Math.sin(angle * 3d) * 0.8d,
                            Math.sin(angle) * radius
                    );
                    viewer.spawnParticle(Particle.DUST, at, 1,
                            0d, 0d, 0d, 0d,
                            ring % 2 == 0 ? DEEP : BRIGHT);
                }
            }
            viewer.spawnParticle(Particle.REVERSE_PORTAL, centre, 35,
                    7d, 6d, 7d, 0.025d);
            viewer.spawnParticle(Particle.END_ROD, centre, 10,
                    6d, 6d, 6d, 0.01d);
        }
    }

    private void fracture(Location at, boolean large) {
        World world = at.getWorld();
        world.spawnParticle(Particle.BLOCK, at, large ? 35 : 9,
                large ? 0.8d : 0.35d, large ? 0.8d : 0.35d,
                large ? 0.8d : 0.35d, large ? 0.18d : 0.08d,
                Material.AMETHYST_BLOCK.createBlockData());
        world.spawnParticle(Particle.DUST, at, large ? 18 : 5,
                0.4d, 0.4d, 0.4d, 0d, BRIGHT);
    }

    private void refreshDisplays(ActiveBlock block, long now) {
        long remaining = Math.max(0L, block.expiresAt - now);
        if (bossBar != null) {
            bossBar.progress((float) Math.clamp(
                    block.health / block.maximumHealth, 0d, 1d
            ));
            bossBar.name(bossTitle(block));
        }
        if (block.countdown.isValid()) {
            block.countdown.text(countdownText(remaining));
        }
    }

    private Component bossTitle(ActiveBlock block) {
        return EventBanner.bossBar(
                "Huge Amethyst Block", AMETHYST,
                block.anchor.getBlockX(), block.anchor.getBlockY(), block.anchor.getBlockZ(),
                EventBanner.number(Math.max(0L, Math.round(block.health))) + " HP",
                AirdropService.formatCountdown(block.expiresAt - System.currentTimeMillis())
        );
    }

    private static Component countdownText(long remaining) {
        return Component.text("TIME LEFT  ", NamedTextColor.GRAY, TextDecoration.BOLD)
                .append(Component.text(AirdropService.formatCountdown(remaining),
                        NamedTextColor.WHITE, TextDecoration.BOLD));
    }

    private void showBar(Player player) {
        if (bossBar != null && PlayerBroadcast.wants(
                settings, PlayerSettingsStore.Setting.AIRDROP_BAR, player
        )) {
            player.showBossBar(bossBar);
        }
    }

    private void playSpawnCue(Player player) {
        if (!settings.isEnabled(player.getUniqueId(),
                PlayerSettingsStore.Setting.AIRDROP_SOUNDS)) {
            return;
        }
        player.playSound(player.getLocation(), Sound.EVENT_RAID_HORN, 2f, 0.65f);
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.8f, 0.55f);
    }

    private void announce(Component message) {
        PlayerBroadcast.broadcast(
                settings, PlayerSettingsStore.Setting.AIRDROP_ANNOUNCEMENTS, message
        );
    }

    private List<Player> eligiblePlayers() {
        return plugin.getServer().getOnlinePlayers().stream()
                .map(player -> (Player) player)
                .filter(player -> !VerificationLobbyService.isLobbyWorld(player.getWorld()))
                .toList();
    }

    private void removeActive(boolean shouldAnnounce, String message) {
        ActiveBlock block = active;
        active = null;
        if (frameTask != null) {
            frameTask.cancel();
            frameTask = null;
        }
        if (finaleTask != null) {
            finaleTask.cancel();
            finaleTask = null;
        }
        if (bossBar != null) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                player.hideBossBar(bossBar);
            }
            bossBar = null;
        }
        if (block == null) {
            return;
        }
        restoreBlocks(block.anchor.getWorld(), block.originals);
        clearJournal();
        block.marker.remove();
        block.visual.remove();
        block.title.remove();
        block.countdown.remove();
        block.chunks.forEach(chunk -> chunk.removePluginChunkTicket(plugin));
        if (shouldAnnounce && message != null) {
            announce(Component.text("AMETHYST EVENT » ", AMETHYST, TextDecoration.BOLD)
                    .append(Component.text(message, NamedTextColor.WHITE)));
        }
        Runnable callback = finishedCallback;
        finishedCallback = null;
        failedCallback = null;
        if (callback != null && !stopped) {
            callback.run();
        }
    }

    private void failScheduledSpawn() {
        Runnable callback = failedCallback;
        clearCoordinatorCallbacks();
        if (callback != null && !stopped) {
            callback.run();
        }
    }

    private void clearCoordinatorCallbacks() {
        spawnedCallback = null;
        finishedCallback = null;
        failedCallback = null;
    }

    /** Persistent markers make an interrupted server restart clean its solid cube. */
    private void clearStaleStructures() {
        restoreJournal();
        for (World world : plugin.getServer().getWorlds()) {
            List<Entity> stale = world.getEntities().stream()
                    .filter(entity -> entity.getScoreboardTags().contains(ENTITY_TAG))
                    .toList();
            for (Entity entity : stale) {
                if (entity instanceof Marker
                        && entity.getScoreboardTags().contains(MARKER_TAG)) {
                    Location anchor = new Location(world, entity.getLocation().getBlockX(),
                            entity.getLocation().getBlockY(), entity.getLocation().getBlockZ());
                    clearCubeToAir(anchor);
                }
                entity.remove();
            }
        }
    }

    private void writeJournal(Location anchor) {
        YamlConfiguration state = new YamlConfiguration();
        state.set("world", anchor.getWorld().getName());
        state.set("x", anchor.getBlockX());
        state.set("y", anchor.getBlockY());
        state.set("z", anchor.getBlockZ());
        try {
            Files.createDirectories(journal.getParent());
            state.save(journal.toFile());
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not create the Amethyst Block cleanup journal", exception
            );
        }
    }

    private void restoreJournal() {
        if (!Files.isRegularFile(journal)) {
            return;
        }
        YamlConfiguration state = YamlConfiguration.loadConfiguration(journal.toFile());
        World world = plugin.getServer().getWorld(state.getString("world", ""));
        if (world == null) {
            plugin.getLogger().warning("Could not restore the previous Huge Amethyst Block: "
                    + "its world is not loaded. The cleanup journal was preserved.");
            return;
        }
        Location anchor = new Location(
                world, state.getInt("x"), state.getInt("y"), state.getInt("z")
        );
        clearCubeToAir(anchor);
        clearJournal();
        plugin.getLogger().info("Restored an interrupted Huge Amethyst Block at "
                + coordinates(anchor) + ".");
    }

    private void clearJournal() {
        try {
            Files.deleteIfExists(journal);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not remove the Amethyst Block cleanup journal: "
                    + exception.getMessage());
        }
    }

    private static void clearCubeToAir(Location anchor) {
        forEachCubeBlock(anchor, block -> {
            if (block.getType() == Material.AMETHYST_BLOCK) {
                block.setType(Material.AIR, false);
            }
        });
    }

    private static void restoreBlocks(World world, Map<BlockPosition, BlockData> originals) {
        originals.forEach((position, data) -> world.getBlockAt(
                position.x(), position.y(), position.z()
        ).setBlockData(data, false));
    }

    private static boolean contains(ActiveBlock active, Block block) {
        if (!block.getWorld().equals(active.anchor.getWorld())) {
            return false;
        }
        int dx = block.getX() - active.anchor.getBlockX();
        int dy = block.getY() - active.anchor.getBlockY();
        int dz = block.getZ() - active.anchor.getBlockZ();
        return dx >= MIN_OFFSET && dx <= MAX_OFFSET
                && dz >= MIN_OFFSET && dz <= MAX_OFFSET
                && dy >= 0 && dy < STRUCTURE_SIZE;
    }

    private static void forEachCubeBlock(Location anchor, java.util.function.Consumer<Block> use) {
        World world = anchor.getWorld();
        for (int x = MIN_OFFSET; x <= MAX_OFFSET; x++) {
            for (int y = 0; y < STRUCTURE_SIZE; y++) {
                for (int z = MIN_OFFSET; z <= MAX_OFFSET; z++) {
                    use.accept(world.getBlockAt(
                            anchor.getBlockX() + x,
                            anchor.getBlockY() + y,
                            anchor.getBlockZ() + z
                    ));
                }
            }
        }
    }

    private static BlockPosition position(Block block) {
        return new BlockPosition(block.getX(), block.getY(), block.getZ());
    }

    private static Location cubeCentre(Location anchor) {
        return new Location(anchor.getWorld(), anchor.getBlockX(),
                anchor.getBlockY() + STRUCTURE_SIZE / 2d, anchor.getBlockZ());
    }

    private static int healthPercent(double health, double maximumHealth) {
        return (int) Math.floor(health * 100d / maximumHealth);
    }

    private static String coordinates(Location location) {
        return "X " + location.getBlockX() + " • Y " + location.getBlockY()
                + " • Z " + location.getBlockZ();
    }

    private static String worldName(World world) {
        return switch (world.getEnvironment()) {
            case NORMAL -> "Overworld";
            case NETHER -> "Nether";
            case THE_END -> "End";
            default -> world.getName();
        };
    }
}
