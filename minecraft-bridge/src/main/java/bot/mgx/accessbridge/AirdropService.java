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
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.random.RandomGenerator;

/** Random Amethyst Airdrops, their temporary structure, loot, effects, and cleanup. */
final class AirdropService implements Listener {
    static final long DEFAULT_MINIMUM_DELAY_MILLIS = Duration.ofMinutes(30).toMillis();
    static final long DEFAULT_MAXIMUM_DELAY_MILLIS = Duration.ofMinutes(90).toMillis();
    static final long DEFAULT_LIFETIME_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final long EFFECT_PERIOD_TICKS = 5L;
    private static final long COUNTDOWN_PERIOD_TICKS = 20L;
    private static final int DEFAULT_MINIMUM_RADIUS = 500;
    private static final int DEFAULT_ATTEMPTS = 24;
    private static final int BORDER_MARGIN = 24;
    private static final String LABEL_TAG = "mgx_airdrop_label";
    private static final double LABEL_BOTTOM = 1.55d;
    private static final double LABEL_GAP = 0.28d;
    private static final TextColor AMETHYST = TextColor.color(0xB56CFF);
    private static final Particle.DustOptions VANISH_DEEP = new Particle.DustOptions(
            Color.fromRGB(105, 35, 196), 1.75f
    );
    private static final Particle.DustOptions VANISH_BRIGHT = new Particle.DustOptions(
            Color.fromRGB(232, 173, 255), 1.45f
    );
    private static final Set<InventoryAction> WITHDRAW_ACTIONS = EnumSet.of(
            InventoryAction.PICKUP_ALL,
            InventoryAction.PICKUP_HALF,
            InventoryAction.PICKUP_ONE,
            InventoryAction.PICKUP_SOME,
            InventoryAction.MOVE_TO_OTHER_INVENTORY,
            InventoryAction.DROP_ALL_SLOT,
            InventoryAction.DROP_ONE_SLOT
    );

    private record BlockKey(UUID worldId, int x, int y, int z) {
        static BlockKey of(Block block) {
            return new BlockKey(
                    block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()
            );
        }

        static BlockKey of(Location location) {
            return new BlockKey(
                    location.getWorld().getUID(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ()
            );
        }
    }

    private record SavedBlock(Location location, BlockData data) {
    }

    private record Candidate(World world, int x, int z) {
    }

    private static final class ActiveAirdrop {
        private final UUID id;
        private final AirdropCatalog.Rarity rarity;
        private final Location anchor;
        private final Location chest;
        private final List<SavedBlock> savedBlocks;
        private final Set<BlockKey> protectedBlocks;
        private final Set<Chunk> chunks;
        private final long spawnedAtMillis;
        private final long expiresAtMillis;
        private final List<ArmorStand> labels;
        private UUID firstOpener;

        private ActiveAirdrop(
                UUID id,
                AirdropCatalog.Rarity rarity,
                Location anchor,
                Location chest,
                List<SavedBlock> savedBlocks,
                Set<BlockKey> protectedBlocks,
                Set<Chunk> chunks,
                long spawnedAtMillis,
                long expiresAtMillis,
                List<ArmorStand> labels
        ) {
            this.id = id;
            this.rarity = rarity;
            this.anchor = anchor;
            this.chest = chest;
            this.savedBlocks = savedBlocks;
            this.protectedBlocks = protectedBlocks;
            this.chunks = chunks;
            this.spawnedAtMillis = spawnedAtMillis;
            this.expiresAtMillis = expiresAtMillis;
            this.labels = labels;
        }
    }

    record Snapshot(
            AirdropCatalog.Rarity rarity,
            String world,
            int x,
            int y,
            int z
    ) {
        String describe() {
            return rarity.displayName() + " Amethyst Airdrop at X " + x + " • Y " + y
                    + " • Z " + z + " in the " + world;
        }
    }

    private final MGXAccessBridge plugin;
    private final CrateItems crateItems;
    private final CosmeticStore cosmeticStore;
    private final CosmeticItems cosmeticItems;
    private final AmethystProgressStore progress;
    private final PlayerSettingsStore settings;
    private final NamespacedKey cosmeticMarker;
    private final RandomGenerator random;
    private final long lifetimeMillis;
    private final int minimumRadius;
    private final int attempts;
    private final boolean enabled;

    private ActiveAirdrop active;
    private BukkitTask expiryTask;
    private BukkitTask effectTask;
    private BukkitTask countdownTask;
    private BossBar announcementBar;
    private final AirdropGuardService guards;
    private volatile boolean stopped = true;
    private BooleanSupplier otherEventActive = () -> false;
    private Runnable spawnedCallback;
    private Runnable finishedCallback;
    private Runnable failedCallback;

    AirdropService(
            MGXAccessBridge plugin,
            CrateItems crateItems,
            CosmeticStore cosmeticStore,
            CosmeticItems cosmeticItems,
            AmethystProgressStore progress,
            PlayerSettingsStore settings,
            AirdropGuardService guards
    ) {
        this(plugin, crateItems, cosmeticStore, cosmeticItems, progress, settings, guards,
                ThreadLocalRandom.current());
    }

    AirdropService(
            MGXAccessBridge plugin,
            CrateItems crateItems,
            CosmeticStore cosmeticStore,
            CosmeticItems cosmeticItems,
            AmethystProgressStore progress,
            PlayerSettingsStore settings,
            AirdropGuardService guards,
            RandomGenerator random
    ) {
        this.plugin = plugin;
        this.crateItems = crateItems;
        this.cosmeticStore = cosmeticStore;
        this.cosmeticItems = cosmeticItems;
        this.progress = progress;
        this.settings = settings;
        this.guards = guards;
        this.random = random;
        cosmeticMarker = new NamespacedKey(plugin, "airdrop_cosmetic");
        enabled = plugin.getConfig().getBoolean("airdrop.enabled", true);
        lifetimeMillis = minutes("airdrop.lifetime-minutes", 30L);
        minimumRadius = Math.max(0, plugin.getConfig().getInt(
                "airdrop.minimum-radius", DEFAULT_MINIMUM_RADIUS
        ));
        attempts = Math.clamp(plugin.getConfig().getInt(
                "airdrop.location-attempts", DEFAULT_ATTEMPTS
        ), 1, 100);
    }

    void start() {
        stop();
        clearStaleLabels();
        stopped = false;
    }

    void blockWhile(BooleanSupplier otherEventActive) {
        this.otherEventActive = otherEventActive == null ? () -> false : otherEventActive;
    }

    boolean beginScheduled(Runnable onSpawned, Runnable onFinished, Runnable onFailed) {
        if (stopped || !enabled || active != null || otherEventActive.getAsBoolean()
                || spawnedCallback != null
                || !CrateKind.AMETHYST.available(System.currentTimeMillis())) {
            return false;
        }
        spawnedCallback = onSpawned;
        finishedCallback = onFinished;
        failedCallback = onFailed;
        attemptSpawn(AirdropCatalog.randomRarity(random), 0);
        return true;
    }

    boolean isActiveOrSpawning() {
        return active != null || spawnedCallback != null;
    }

    void stop() {
        stopped = true;
        clearCoordinatorCallbacks();
        removeActive(false, null);
        hideAnnouncement();
    }

    Optional<Snapshot> snapshot() {
        ActiveAirdrop drop = active;
        return drop == null ? Optional.empty() : Optional.of(snapshot(drop));
    }

    Snapshot spawnTest(
            Player player,
            AirdropCatalog.Rarity rarity,
            List<String> forcedCosmetics
    ) {
        if (!plugin.isLocalTestServer()) {
            throw new IllegalArgumentException(
                    "Airdrop tests are available only on the local test server."
            );
        }
        if (active != null || spawnedCallback != null || otherEventActive.getAsBoolean()) {
            throw new IllegalArgumentException(
                    "An Amethyst world event is already active or spawning."
            );
        }
        if (player.getWorld().getEnvironment() != World.Environment.NORMAL
                && player.getWorld().getEnvironment() != World.Environment.NETHER) {
            throw new IllegalArgumentException("Run the Airdrop test in the Overworld or Nether.");
        }
        if (VerificationLobbyService.isLobbyWorld(player.getWorld())) {
            throw new IllegalArgumentException("Leave the verification lobby before testing Airdrops.");
        }
        List<String> cosmetics = forcedCosmetics == null
                ? List.of() : forcedCosmetics.stream().distinct().toList();
        if (cosmetics.stream().anyMatch(id -> !CosmeticCatalog.isAmethystAirdrop(id))) {
            throw new IllegalArgumentException("That is not an Amethyst Airdrop cosmetic.");
        }
        Location anchor = findTestAnchor(player);
        if (anchor == null) {
            throw new IllegalArgumentException(
                    "No safe test site was found nearby. Move into open terrain and try again."
            );
        }
        createAirdrop(anchor, rarity, cosmetics);
        return snapshot(active);
    }

    boolean expireTest() {
        if (active == null) {
            return false;
        }
        String rarity = active.rarity.displayName();
        removeActive(true, "The " + rarity + " Amethyst Airdrop expired unclaimed.");
        return true;
    }

    boolean removeTest() {
        if (active == null) {
            return false;
        }
        removeActive(false, null);
        return true;
    }

    private long minutes(String path, long fallback) {
        long value = Math.clamp(plugin.getConfig().getLong(path, fallback), 1L, 1_440L);
        return Duration.ofMinutes(value).toMillis();
    }

    static long randomDelayMillis(RandomGenerator random, long minimum, long maximum) {
        if (random == null || minimum < 0L || maximum < minimum) {
            throw new IllegalArgumentException("Airdrop delay bounds are invalid");
        }
        return minimum == maximum ? minimum : random.nextLong(minimum, maximum + 1L);
    }

    private void attemptSpawn(AirdropCatalog.Rarity rarity, int attempt) {
        if (stopped || active != null || otherEventActive.getAsBoolean()
                || !CrateKind.AMETHYST.available(System.currentTimeMillis())) {
            failScheduledSpawn();
            return;
        }
        if (attempt >= attempts) {
            plugin.getLogger().warning(
                    "Could not find safe ground for an Amethyst Airdrop after "
                            + attempts + " attempts; retrying in five minutes."
            );
            failScheduledSpawn();
            return;
        }
        Candidate candidate = randomCandidate();
        if (candidate == null) {
            failScheduledSpawn();
            return;
        }
        candidate.world().getChunkAtAsync(candidate.x() >> 4, candidate.z() >> 4, true)
                .whenComplete((chunk, error) -> {
                    if (stopped) {
                        return;
                    }
                    plugin.getServer().getScheduler().runTask(
                            plugin, () -> finishAttempt(rarity, attempt, candidate, error)
                    );
                });
    }

    private void finishAttempt(
            AirdropCatalog.Rarity rarity,
            int attempt,
            Candidate candidate,
            Throwable error
    ) {
        if (stopped || active != null || otherEventActive.getAsBoolean()) {
            failScheduledSpawn();
            return;
        }
        if (error != null) {
            plugin.getLogger().warning("Could not load an Airdrop chunk: " + error.getMessage());
            attemptSpawn(rarity, attempt + 1);
            return;
        }
        Location anchor = findAnchor(candidate);
        if (anchor == null || !safeStructureSite(anchor)) {
            attemptSpawn(rarity, attempt + 1);
            return;
        }
        try {
            createAirdrop(anchor, rarity);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not create an Amethyst Airdrop: "
                    + exception.getMessage());
            attemptSpawn(rarity, attempt + 1);
            return;
        }
        Runnable callback = spawnedCallback;
        spawnedCallback = null;
        if (callback != null) {
            callback.run();
        }
    }

    private Candidate randomCandidate() {
        List<World> worlds = plugin.getServer().getWorlds().stream()
                .filter(world -> world.getEnvironment() == World.Environment.NORMAL
                        || world.getEnvironment() == World.Environment.NETHER)
                .filter(world -> !VerificationLobbyService.isLobbyWorld(world))
                .toList();
        if (worlds.isEmpty()) {
            plugin.getLogger().warning("No Overworld or Nether is loaded for Amethyst Airdrops.");
            return null;
        }
        World world = worlds.get(random.nextInt(worlds.size()));
        WorldBorder border = world.getWorldBorder();
        Location centre = border.getCenter();
        int limit = Math.max(1, (int) Math.floor(border.getSize() / 2d) - BORDER_MARGIN);
        int localMinimum = world.getEnvironment() == World.Environment.NETHER
                ? Math.max(64, minimumRadius / 8)
                : minimumRadius;
        for (int index = 0; index < 24; index++) {
            int x = centre.getBlockX() + random.nextInt(-limit, limit + 1);
            int z = centre.getBlockZ() + random.nextInt(-limit, limit + 1);
            if ((long) x * x + (long) z * z < (long) localMinimum * localMinimum) {
                continue;
            }
            return new Candidate(world, x, z);
        }
        return null;
    }

    private Location findAnchor(Candidate candidate) {
        World world = candidate.world();
        int x = candidate.x();
        int z = candidate.z();
        if (world.getEnvironment() == World.Environment.NORMAL) {
            int groundY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Block ground = world.getBlockAt(x, groundY, z);
            if (!ground.getType().isSolid() || ground.isLiquid()) {
                return null;
            }
            return new Location(world, x, groundY + 1, z);
        }
        int ceiling = Math.min(world.getMaxHeight() - AirdropStructure.height() - 3, 116);
        for (int y = ceiling; y >= world.getMinHeight() + 5; y--) {
            Block ground = world.getBlockAt(x, y, z);
            if (!ground.getType().isSolid()
                    || ground.getType() == Material.BEDROCK
                    || ground.isLiquid()) {
                continue;
            }
            boolean clear = true;
            for (int above = 1; above <= AirdropStructure.height() + 1; above++) {
                if (!replaceable(world.getBlockAt(x, y + above, z))) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                return new Location(world, x, y + 1, z);
            }
        }
        return null;
    }

    private Location findTestAnchor(Player player) {
        World world = player.getWorld();
        int originX = player.getLocation().getBlockX();
        int originZ = player.getLocation().getBlockZ();
        double facing = Math.toRadians(player.getLocation().getYaw() + 90d);
        for (int radius = 12; radius <= 96; radius += 6) {
            for (int point = 0; point < 16; point++) {
                double angle = facing + point * Math.PI * 2d / 16d;
                int x = originX + (int) Math.round(Math.cos(angle) * radius);
                int z = originZ + (int) Math.round(Math.sin(angle) * radius);
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                Location anchor = findAnchor(new Candidate(world, x, z));
                if (anchor != null && safeStructureSite(anchor)) {
                    return anchor;
                }
            }
        }
        return null;
    }

    private boolean safeStructureSite(Location anchor) {
        World world = anchor.getWorld();
        if (anchor.getBlockY() < world.getMinHeight() + 1
                || anchor.getBlockY() + AirdropStructure.height() >= world.getMaxHeight()) {
            return false;
        }
        WorldBorder border = world.getWorldBorder();
        for (AirdropStructure.Placement placement : AirdropStructure.blueprint()) {
            Block block = world.getBlockAt(
                    anchor.getBlockX() + placement.x(),
                    anchor.getBlockY() + placement.y(),
                    anchor.getBlockZ() + placement.z()
            );
            if (!border.isInside(block.getLocation()) || block.getState() instanceof Container) {
                return false;
            }
            if (placement.y() > 0 && !replaceable(block)) {
                return false;
            }
            if (placement.y() == 0 && !replaceable(block) && !naturalGround(block.getType())) {
                return false;
            }
        }
        return true;
    }

    private void createAirdrop(Location anchor, AirdropCatalog.Rarity rarity) {
        createAirdrop(anchor, rarity, List.of());
    }

    private void createAirdrop(
            Location anchor,
            AirdropCatalog.Rarity rarity,
            List<String> forcedCosmetics
    ) {
        List<SavedBlock> saved = new ArrayList<>();
        Set<BlockKey> protectedBlocks = new HashSet<>();
        Set<Chunk> chunks = new HashSet<>();
        List<ArmorStand> labels = new ArrayList<>();
        for (AirdropStructure.Placement placement : AirdropStructure.blueprint()) {
            Block block = anchor.getWorld().getBlockAt(
                    anchor.getBlockX() + placement.x(),
                    anchor.getBlockY() + placement.y(),
                    anchor.getBlockZ() + placement.z()
            );
            saved.add(new SavedBlock(block.getLocation(), block.getBlockData().clone()));
            protectedBlocks.add(BlockKey.of(block));
            Chunk chunk = block.getChunk();
            if (chunks.add(chunk)) {
                chunk.addPluginChunkTicket(plugin);
            }
            block.setType(placement.material(), false);
        }

        try {
            Location chestLocation = anchor.clone().add(0d, 1d, 0d);
            BlockState state = chestLocation.getBlock().getState();
            if (!(state instanceof Chest chest)) {
                throw new IllegalStateException("The Amethyst Airdrop chest could not be created");
            }
            chest.customName(Component.text(
                    chestTitle(rarity), rarityColour(rarity),
                    TextDecoration.BOLD
            ));
            chest.update(true, false);
            fillChest(
                    chest.getBlockInventory(), AirdropCatalog.roll(rarity, random), forcedCosmetics
            );

            long spawnedAt = System.currentTimeMillis();
            long expiresAt = Math.addExact(spawnedAt, lifetimeMillis);
            labels.addAll(spawnLabels(chestLocation, rarity, expiresAt));
            active = new ActiveAirdrop(
                    UUID.randomUUID(), rarity, anchor.clone(), chestLocation,
                    List.copyOf(saved), Set.copyOf(protectedBlocks), Set.copyOf(chunks),
                    spawnedAt, expiresAt, List.copyOf(labels)
            );
            effectTask = plugin.getServer().getScheduler().runTaskTimer(
                    plugin, this::drawEffects, 1L, EFFECT_PERIOD_TICKS
            );
            countdownTask = plugin.getServer().getScheduler().runTaskTimer(
                    plugin, this::refreshCountdown, 1L, COUNTDOWN_PERIOD_TICKS
            );
            expiryTask = plugin.getServer().getScheduler().runTaskLater(
                    plugin,
                    () -> removeActive(true, "The " + rarity.displayName()
                            + " Amethyst Airdrop expired unclaimed."),
                    Math.max(1L, lifetimeMillis / 50L)
            );
            guards.deploy(chestLocation, rarity, () -> removeActive(
                    true, "The guards claimed the " + rarity.displayName()
                            + " Amethyst Airdrop. Nobody stayed to fight for it."
            ));
            announceSpawn(active);
            plugin.getLogger().info("Spawned " + rarity.displayName() + " Amethyst Airdrop at "
                    + coordinates(chestLocation) + " in " + worldName(anchor.getWorld()));
        } catch (RuntimeException exception) {
            active = null;
            cancel(expiryTask);
            expiryTask = null;
            cancel(effectTask);
            effectTask = null;
            cancel(countdownTask);
            countdownTask = null;
            hideAnnouncement();
            removeLabels(labels);
            restore(saved, chunks);
            throw exception;
        }
    }

    private void fillChest(
            Inventory inventory,
            AirdropCatalog.Contents contents,
            List<String> forcedCosmetics
    ) {
        List<ItemStack> items = new ArrayList<>();
        for (int portion : StackSplit.portions(contents.keys(), 64)) {
            items.add(crateItems.key(portion));
        }
        if (contents.shards() > 0) {
            items.add(crateItems.shard(contents.shards()));
        }
        Map<Material, Integer> totals = new LinkedHashMap<>();
        for (AirdropCatalog.MaterialLoot reward : contents.materialLoot()) {
            Material material = Material.matchMaterial(reward.materialName());
            if (material == null) {
                plugin.getLogger().warning("Unknown Airdrop material " + reward.materialName());
                continue;
            }
            totals.merge(material, reward.amount(), Math::addExact);
        }
        totals.forEach((material, amount) -> {
            ItemStack template = new ItemStack(material);
            for (int portion : StackSplit.portions(amount, template.getMaxStackSize())) {
                ItemStack stack = template.clone();
                stack.setAmount(portion);
                items.add(stack);
            }
        });
        selectCosmetic(contents.cosmeticId(), forcedCosmetics, random)
                .flatMap(CosmeticCatalog::find)
                .ifPresent(definition -> {
                    ItemStack preview = cosmeticItems.preview(definition, false);
                    ItemMeta meta = preview.getItemMeta();
                    meta.getPersistentDataContainer().set(
                            cosmeticMarker, PersistentDataType.STRING, definition.id()
                    );
                    preview.setItemMeta(meta);
                    items.add(preview);
                });
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            slots.add(slot);
        }
        shuffle(slots);
        if (items.size() > slots.size()) {
            throw new IllegalStateException("Amethyst Airdrop generated too many loot stacks");
        }
        for (int index = 0; index < items.size(); index++) {
            inventory.setItem(slots.get(index), items.get(index));
        }
    }

    private void shuffle(List<Integer> slots) {
        for (int index = slots.size() - 1; index > 0; index--) {
            Collections.swap(slots, index, random.nextInt(index + 1));
        }
    }

    static Optional<String> selectCosmetic(
            Optional<String> rolled,
            List<String> forcedCandidates,
            RandomGenerator random
    ) {
        List<String> candidates = forcedCandidates == null
                ? List.of()
                : forcedCandidates.stream().filter(CosmeticCatalog::isAmethystAirdrop)
                        .distinct().toList();
        if (!candidates.isEmpty()) {
            return Optional.of(candidates.get(random.nextInt(candidates.size())));
        }
        return rolled == null ? Optional.empty() : rolled
                .filter(CosmeticCatalog::isAmethystAirdrop);
    }

    private void announceSpawn(ActiveAirdrop drop) {
        String where = coordinates(drop.chest);
        String world = worldName(drop.chest.getWorld());
        Component announcement = Component.text(
                        "AIRDROP » ", AMETHYST, TextDecoration.BOLD
                )
                .append(Component.text(drop.rarity.displayName() + " Amethyst Airdrop",
                        rarityColour(drop.rarity), TextDecoration.BOLD))
                .append(Component.text(" at " + where + " in the " + world + "!",
                        NamedTextColor.WHITE));
        broadcast(announcement);
        hideAnnouncement();
        announcementBar = BossBar.bossBar(
                bossBarTitle(drop, lifetimeMillis),
                1f,
                BossBar.Color.PURPLE,
                BossBar.Overlay.PROGRESS
        );
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (PlayerBroadcast.wants(
                    settings, PlayerSettingsStore.Setting.AIRDROP_BAR, player
            )) {
                player.showBossBar(announcementBar);
            }
            playSpawnCue(player);
        }
        refreshCountdown();
    }

    private void hideAnnouncement() {
        if (announcementBar == null) {
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.hideBossBar(announcementBar);
        }
        announcementBar = null;
    }

    private void refreshCountdown() {
        ActiveAirdrop drop = active;
        if (drop == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long remaining = Math.max(0L, drop.expiresAtMillis - now);
        if (announcementBar != null) {
            announcementBar.progress(remainingProgress(
                    now, drop.spawnedAtMillis, drop.expiresAtMillis
            ));
            announcementBar.name(bossBarTitle(drop, remaining));
        }
        if (drop.labels.size() >= 2 && drop.labels.get(1).isValid()) {
            drop.labels.get(1).customName(countdownLabel(remaining));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (active != null && announcementBar != null && PlayerBroadcast.wants(
                settings, PlayerSettingsStore.Setting.AIRDROP_BAR, event.getPlayer()
        )) {
            event.getPlayer().showBossBar(announcementBar);
        }
    }

    private Component bossBarTitle(ActiveAirdrop drop, long remainingMillis) {
        return Component.text(
                drop.rarity.displayName().toUpperCase(Locale.ROOT)
                        + " AIRDROP • " + worldName(drop.chest.getWorld()).toUpperCase(Locale.ROOT)
                        + " • " + coordinates(drop.chest)
                        + " • " + formatCountdown(remainingMillis),
                rarityColour(drop.rarity), TextDecoration.BOLD
        );
    }

    private void playSpawnCue(Player player) {
        if (!settings.isEnabled(
                player.getUniqueId(), PlayerSettingsStore.Setting.AIRDROP_SOUNDS
        )) {
            return;
        }
        Location at = player.getLocation();
        player.playSound(at, Sound.EVENT_RAID_HORN, 2.2f, 0.8f);
        player.playSound(at, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.35f, 1.15f);
        player.playSound(at, Sound.BLOCK_BEACON_ACTIVATE, 1.8f, 0.65f);
        player.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.5f, 0.75f);
    }

    /**
     * Draws a particle only for the players who still want the beam.
     *
     * <p>{@code World.spawnParticle} reaches everyone in range regardless, so a
     * per-player switch has to name its audience instead.
     */
    private <T> void spawnForViewers(
            World world, Particle particle, Location at, int count,
            double offsetX, double offsetY, double offsetZ, double extra, T data
    ) {
        for (Player viewer : world.getPlayers()) {
            if (settings.isEnabled(
                    viewer.getUniqueId(), PlayerSettingsStore.Setting.AIRDROP_PARTICLES
            )) {
                viewer.spawnParticle(
                        particle, at, count, offsetX, offsetY, offsetZ, extra, data
                );
            }
        }
    }

    static float remainingProgress(long now, long spawnedAt, long expiresAt) {
        if (expiresAt <= spawnedAt) {
            return 0f;
        }
        double fraction = (double) (expiresAt - now) / (double) (expiresAt - spawnedAt);
        return (float) Math.clamp(fraction, 0d, 1d);
    }

    static String formatCountdown(long remainingMillis) {
        long millis = Math.max(0L, remainingMillis);
        long seconds = millis / 1_000L + (millis % 1_000L == 0L ? 0L : 1L);
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L);
    }

    static String chestTitle(AirdropCatalog.Rarity rarity) {
        return rarity.displayName() + " Airdrop";
    }

    private List<ArmorStand> spawnLabels(
            Location chest,
            AirdropCatalog.Rarity rarity,
            long expiresAt
    ) {
        Location centre = chest.clone().add(0.5d, 0d, 0.5d);
        ArmorStand title = spawnLabel(
                centre.clone().add(0d, LABEL_BOTTOM + LABEL_GAP, 0d),
                Component.text(
                        rarity.displayName().toUpperCase(Locale.ROOT) + " AMETHYST AIRDROP",
                        rarityColour(rarity), TextDecoration.BOLD
                )
        );
        try {
            ArmorStand countdown = spawnLabel(
                    centre.clone().add(0d, LABEL_BOTTOM, 0d),
                    countdownLabel(Math.max(0L, expiresAt - System.currentTimeMillis()))
            );
            return List.of(title, countdown);
        } catch (RuntimeException exception) {
            title.remove();
            throw exception;
        }
    }

    private ArmorStand spawnLabel(Location at, Component name) {
        return at.getWorld().spawn(at, ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setMarker(true);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setCustomNameVisible(true);
            stand.customName(name);
            stand.addScoreboardTag(LABEL_TAG);
            stand.setPersistent(false);
            stand.setRemoveWhenFarAway(false);
        });
    }

    private static Component countdownLabel(long remainingMillis) {
        return Component.text("Time Left: ", NamedTextColor.GRAY)
                .append(Component.text(
                        formatCountdown(remainingMillis), NamedTextColor.WHITE, TextDecoration.BOLD
                ));
    }

    private void drawEffects() {
        ActiveAirdrop drop = active;
        if (drop == null) {
            return;
        }
        World world = drop.chest.getWorld();
        Location centre = drop.chest.clone().add(0.5d, 1.1d, 0.5d);
        Color deep = Color.fromRGB(132, 43, 214);
        Color bright = Color.fromRGB(220, 154, 255);
        double phase = System.currentTimeMillis() / 900.0d;
        for (int ring = 0; ring < 3; ring++) {
            double radius = 2.8d + ring * 1.15d;
            double y = -0.25d + ring * 0.8d;
            for (int point = 0; point < 24; point++) {
                double angle = phase * (ring % 2 == 0 ? 1d : -1d)
                        + point * Math.PI * 2d / 24d;
                Location at = centre.clone().add(
                        Math.cos(angle) * radius,
                        y + Math.sin(angle * 3d) * 0.35d,
                        Math.sin(angle) * radius
                );
                spawnForViewers(
                        world, Particle.DUST, at, 1, 0d, 0d, 0d, 0d,
                        new Particle.DustOptions(ring == 1 ? bright : deep, 1.15f)
                );
            }
        }
        world.spawnParticle(
                Particle.REVERSE_PORTAL, centre, 18, 3.5d, 1.8d, 3.5d, 0.03d
        );
        int beamHeight = Math.min(48, world.getMaxHeight() - centre.getBlockY() - 1);
        for (int y = 0; y <= beamHeight; y += 2) {
            Location at = centre.clone().add(0d, y, 0d);
            world.spawnParticle(Particle.END_ROD, at, 1, 0.05d, 0.18d, 0.05d, 0d);
            world.spawnParticle(
                    Particle.DUST, at, 1, 0.08d, 0.08d, 0.08d, 0d,
                    new Particle.DustOptions(y % 4 == 0 ? bright : deep, 1.25f)
            );
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || !isActiveInventory(event.getInventory())
                || active == null
                || active.firstOpener != null) {
            return;
        }
        try {
            progress.recordAirdropOpened(player.getUniqueId());
        } catch (UncheckedIOException exception) {
            plugin.getLogger().warning("Could not record an Airdrop opening: "
                    + exception.getMessage());
            return;
        }
        active.firstOpener = player.getUniqueId();
        ServerEvent.of(
                "airdrop_open",
                ServerEvent.CATEGORY_CRATE,
                player.getUniqueId(),
                player.getName(),
                plugin::recordServerEvent
        ).summary(player.getName() + " opened a " + active.rarity.displayName()
                        + " Amethyst Airdrop")
                .detail("rarity", active.rarity.displayName())
                .detail("world", worldName(active.chest.getWorld()))
                .detail("coordinates", coordinates(active.chest))
                .detail("airdrop_id", active.id.toString())
                .record();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(event.getWhoClicked() instanceof Player player) || !isActiveInventory(top)) {
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0) {
            return;
        }
        if (rawSlot < top.getSize()) {
            ItemStack current = event.getCurrentItem();
            String cosmeticId = airdropCosmetic(current);
            if (cosmeticId != null) {
                event.setCancelled(true);
                claimCosmetic(player, top, rawSlot, cosmeticId);
                return;
            }
            if (!WITHDRAW_ACTIONS.contains(event.getAction())) {
                event.setCancelled(true);
                return;
            }
        } else if (event.isShiftClick()) {
            event.setCancelled(true);
            return;
        } else if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, this::removeIfEmpty);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!isActiveInventory(top)) {
            return;
        }
        if (event.getRawSlots().stream().anyMatch(slot -> slot < top.getSize())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (isActiveInventory(event.getSource()) || isActiveInventory(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (isActiveInventory(event.getInventory())) {
            plugin.getServer().getScheduler().runTask(plugin, this::removeIfEmpty);
        }
    }

    private void claimCosmetic(Player player, Inventory inventory, int slot, String cosmeticId) {
        CosmeticCatalog.Definition definition = CosmeticCatalog.find(cosmeticId).orElse(null);
        if (definition == null || !CosmeticCatalog.isAmethystAirdrop(cosmeticId)) {
            PlayerMenuService.error(player, "That Airdrop cosmetic is invalid.");
            return;
        }
        try {
            cosmeticStore.mint(player.getUniqueId(), cosmeticId, UUID.randomUUID());
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not mint Airdrop cosmetic: " + exception.getMessage());
            PlayerMenuService.error(player, "That cosmetic could not be stored yet.");
            return;
        }
        inventory.setItem(slot, null);
        player.sendMessage(PlayerMenuService.prefix()
                .append(Component.text("You found ", NamedTextColor.WHITE))
                .append(Component.text(definition.displayName(), AMETHYST, TextDecoration.BOLD))
                .append(Component.text(". It is stored in /wardrobe.", NamedTextColor.GRAY)));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.35f);
        plugin.getServer().getScheduler().runTask(plugin, this::removeIfEmpty);
    }

    private String airdropCosmetic(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .get(cosmeticMarker, PersistentDataType.STRING);
    }

    private void removeIfEmpty() {
        ActiveAirdrop drop = active;
        if (drop == null) {
            return;
        }
        BlockState state = drop.chest.getBlock().getState();
        if (!(state instanceof Chest chest)) {
            removeActive(true, "The Amethyst Airdrop vanished after its chest was disturbed.");
            return;
        }
        for (ItemStack item : chest.getBlockInventory().getContents()) {
            if (item != null && !item.getType().isAir()) {
                return;
            }
        }
        removeActive(true, "The " + drop.rarity.displayName()
                + " Amethyst Airdrop was looted.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        if (protectedBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) {
        if (protectedBlock(event.getBlockPlaced())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFlow(BlockFromToEvent event) {
        if (protectedBlock(event.getBlock()) || protectedBlock(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(this::protectedBlock)
                || event.getBlocks().stream()
                .map(block -> block.getRelative(event.getDirection()))
                .anyMatch(this::protectedBlock)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(this::protectedBlock)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::protectedBlock);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::protectedBlock);
    }

    private boolean protectedBlock(Block block) {
        ActiveAirdrop drop = active;
        return drop != null && drop.protectedBlocks.contains(BlockKey.of(block));
    }

    private boolean isActiveInventory(Inventory inventory) {
        ActiveAirdrop drop = active;
        if (drop == null || !(inventory.getHolder() instanceof Chest chest)) {
            return false;
        }
        return BlockKey.of(chest.getLocation()).equals(BlockKey.of(drop.chest));
    }

    private void removeActive(boolean announce, String message) {
        ActiveAirdrop drop = active;
        active = null;
        guards.dismiss();
        cancel(expiryTask);
        expiryTask = null;
        cancel(effectTask);
        effectTask = null;
        cancel(countdownTask);
        countdownTask = null;
        hideAnnouncement();
        if (drop == null) {
            return;
        }
        removeLabels(drop.labels);
        BlockState state = drop.chest.getBlock().getState();
        if (state instanceof Chest chest) {
            for (org.bukkit.entity.HumanEntity viewer
                    : List.copyOf(chest.getBlockInventory().getViewers())) {
                viewer.closeInventory();
            }
            chest.getBlockInventory().clear();
        }
        if (announce) {
            playDisappearEffect(drop);
        }
        restore(drop.savedBlocks, drop.chunks);
        if (announce && message != null && !message.isBlank()) {
            broadcast(Component.text("AIRDROP » ", AMETHYST, TextDecoration.BOLD)
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

    private void playDisappearEffect(ActiveAirdrop drop) {
        World world = drop.chest.getWorld();
        Location centre = drop.chest.clone().add(0.5d, 1.1d, 0.5d);
        world.spawnParticle(Particle.REVERSE_PORTAL, centre, 240, 4.5d, 2.5d, 4.5d, 0.16d);
        world.spawnParticle(Particle.WITCH, centre, 120, 3.8d, 2d, 3.8d, 0.08d);
        world.spawnParticle(Particle.END_ROD, centre, 70, 3d, 2.4d, 3d, 0.12d);
        world.spawnParticle(Particle.DUST, centre, 90, 4d, 2.3d, 4d, 0d, VANISH_DEEP);
        world.spawnParticle(Particle.DUST, centre, 70, 2.7d, 3.2d, 2.7d, 0d, VANISH_BRIGHT);
        for (int point = 0; point < 48; point++) {
            double angle = point * Math.PI * 2d / 48d;
            Location at = centre.clone().add(
                    Math.cos(angle) * 4.6d,
                    Math.sin(angle * 3d) * 0.75d,
                    Math.sin(angle) * 4.6d
            );
            world.spawnParticle(Particle.DUST, at, 1, 0d, 0d, 0d, 0d, VANISH_BRIGHT);
        }
        world.playSound(centre, Sound.BLOCK_BEACON_DEACTIVATE, 12f, 0.55f);
        world.playSound(centre, Sound.ENTITY_ENDERMAN_TELEPORT, 8f, 0.65f);
        world.playSound(centre, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 8f, 0.55f);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Location at = player.getLocation();
            player.playSound(at, Sound.BLOCK_BEACON_DEACTIVATE, 1.35f, 0.6f);
            player.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1.15f, 0.7f);
        }
    }

    private static void removeLabels(List<ArmorStand> labels) {
        for (ArmorStand label : labels) {
            if (label != null && label.isValid()) {
                label.remove();
            }
        }
    }

    private void clearStaleLabels() {
        for (World world : plugin.getServer().getWorlds()) {
            for (ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
                if (stand.getScoreboardTags().contains(LABEL_TAG)) {
                    stand.remove();
                }
            }
        }
    }

    private void restore(List<SavedBlock> saved, Set<Chunk> chunks) {
        List<SavedBlock> reversed = new ArrayList<>(saved);
        Collections.reverse(reversed);
        for (SavedBlock original : reversed) {
            original.location().getBlock().setBlockData(original.data(), false);
        }
        for (Chunk chunk : chunks) {
            chunk.removePluginChunkTicket(plugin);
        }
    }

    private void broadcast(Component message) {
        PlayerBroadcast.broadcast(
                settings, PlayerSettingsStore.Setting.AIRDROP_ANNOUNCEMENTS, message
        );
    }

    private static boolean replaceable(Block block) {
        Material material = block.getType();
        return material.isAir() || material == Material.SHORT_GRASS
                || material == Material.TALL_GRASS
                || material == Material.SNOW
                || material == Material.VINE
                || material == Material.DEAD_BUSH
                || material == Material.FERN
                || material == Material.LARGE_FERN
                || material == Material.CRIMSON_ROOTS
                || material == Material.WARPED_ROOTS
                || material == Material.NETHER_SPROUTS;
    }

    private static boolean naturalGround(Material material) {
        String name = material.name();
        return material == Material.GRASS_BLOCK
                || material == Material.DIRT
                || material == Material.COARSE_DIRT
                || material == Material.PODZOL
                || material == Material.MYCELIUM
                || material == Material.STONE
                || material == Material.DEEPSLATE
                || material == Material.SAND
                || material == Material.RED_SAND
                || material == Material.GRAVEL
                || material == Material.CLAY
                || material == Material.NETHERRACK
                || material == Material.SOUL_SAND
                || material == Material.SOUL_SOIL
                || material == Material.CRIMSON_NYLIUM
                || material == Material.WARPED_NYLIUM
                || material == Material.BASALT
                || material == Material.BLACKSTONE
                || name.endsWith("_ORE")
                || name.endsWith("_TERRACOTTA");
    }

    private static TextColor rarityColour(AirdropCatalog.Rarity rarity) {
        return switch (rarity) {
            case COMMON -> NamedTextColor.WHITE;
            case RARE -> NamedTextColor.AQUA;
            case LEGENDARY -> TextColor.color(0xFFB52E);
            case MYTHIC -> TextColor.color(0xFF4FD8);
        };
    }

    private static String coordinates(Location location) {
        return "X " + location.getBlockX()
                + " • Y " + location.getBlockY()
                + " • Z " + location.getBlockZ();
    }

    private static String worldName(World world) {
        return world.getEnvironment() == World.Environment.NETHER ? "Nether" : "Overworld";
    }

    private static Snapshot snapshot(ActiveAirdrop drop) {
        return new Snapshot(
                drop.rarity,
                worldName(drop.chest.getWorld()),
                drop.chest.getBlockX(),
                drop.chest.getBlockY(),
                drop.chest.getBlockZ()
        );
    }

    private static void cancel(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }
}
