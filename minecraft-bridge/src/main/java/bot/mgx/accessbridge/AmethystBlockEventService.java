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
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.random.RandomGenerator;

/** A server-wide cooperative, twenty-block-wide Amethyst Block mining event. */
final class AmethystBlockEventService implements Listener {
    static final long DEFAULT_LIFETIME_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final int DEFAULT_MINIMUM_RADIUS = 500;
    private static final int DEFAULT_ATTEMPTS = 24;
    private static final int BORDER_MARGIN = 32;
    private static final float DISPLAY_SCALE = 20f;
    private static final long MINE_GRACE_MILLIS = 1_500L;
    private static final String ENTITY_TAG = "mgx_amethyst_block_event";
    private static final TextColor AMETHYST = TextColor.color(0xB56CFF);
    private static final Particle.DustOptions DEEP = new Particle.DustOptions(
            Color.fromRGB(111, 42, 194), 2.1f
    );
    private static final Particle.DustOptions BRIGHT = new Particle.DustOptions(
            Color.fromRGB(227, 164, 255), 1.65f
    );

    private record Candidate(World world, int x, int z) {
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
        private final Location anchor;
        private final BlockDisplay display;
        private final Interaction interaction;
        private final TextDisplay title;
        private final TextDisplay countdown;
        private final Set<Chunk> chunks;
        private final long spawnedAt;
        private final long expiresAt;
        private final Map<UUID, Long> mining = new HashMap<>();
        private final Map<UUID, Double> damage = new HashMap<>();
        private double health = AmethystBlockRewards.MAX_HEALTH;
        private int nextMilestone;

        private ActiveBlock(
                Location anchor, BlockDisplay display, Interaction interaction,
                TextDisplay title, TextDisplay countdown, Set<Chunk> chunks,
                long spawnedAt, long expiresAt
        ) {
            this.anchor = anchor;
            this.display = display;
            this.interaction = interaction;
            this.title = title;
            this.countdown = countdown;
            this.chunks = chunks;
            this.spawnedAt = spawnedAt;
            this.expiresAt = expiresAt;
        }
    }

    private final MGXAccessBridge plugin;
    private final CrateItems crateItems;
    private final PlayerSettingsStore settings;
    private final RandomGenerator random;
    private final long lifetimeMillis;
    private final int minimumRadius;
    private final int attempts;
    private final boolean enabled;

    private ActiveBlock active;
    private BukkitTask frameTask;
    private BossBar bossBar;
    private volatile boolean stopped = true;
    private BooleanSupplier otherEventActive = () -> false;
    private Runnable spawnedCallback;
    private Runnable finishedCallback;
    private Runnable failedCallback;

    AmethystBlockEventService(
            MGXAccessBridge plugin, CrateItems crateItems, PlayerSettingsStore settings
    ) {
        this(plugin, crateItems, settings, ThreadLocalRandom.current());
    }

    AmethystBlockEventService(
            MGXAccessBridge plugin, CrateItems crateItems, PlayerSettingsStore settings,
            RandomGenerator random
    ) {
        this.plugin = plugin;
        this.crateItems = crateItems;
        this.settings = settings;
        this.random = random;
        enabled = plugin.getConfig().getBoolean("amethyst-block-event.enabled", true);
        lifetimeMillis = Duration.ofMinutes(Math.clamp(plugin.getConfig().getLong(
                "amethyst-block-event.lifetime-minutes", 30L
        ), 1L, 1_440L)).toMillis();
        minimumRadius = Math.max(0, plugin.getConfig().getInt(
                "amethyst-block-event.minimum-radius", DEFAULT_MINIMUM_RADIUS
        ));
        attempts = Math.clamp(plugin.getConfig().getInt(
                "amethyst-block-event.location-attempts", DEFAULT_ATTEMPTS
        ), 1, 100);
    }

    void start() {
        stop();
        clearStaleEntities();
        stopped = false;
    }

    void stop() {
        stopped = true;
        clearCoordinatorCallbacks();
        removeActive(false, null);
    }

    void blockWhile(BooleanSupplier otherEventActive) {
        this.otherEventActive = otherEventActive == null ? () -> false : otherEventActive;
    }

    boolean beginScheduled(Runnable onSpawned, Runnable onFinished, Runnable onFailed) {
        if (stopped || !enabled || active != null || spawnedCallback != null
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
        if (active != null || spawnedCallback != null || otherEventActive.getAsBoolean()) {
            throw new IllegalArgumentException("An Amethyst world event is already active or spawning.");
        }
        if (player.getWorld().getEnvironment() != World.Environment.NORMAL
                || VerificationLobbyService.isLobbyWorld(player.getWorld())) {
            throw new IllegalArgumentException("Run this test in the Overworld outside the verification lobby.");
        }
        Location anchor = findTestAnchor(player);
        if (anchor == null) {
            throw new IllegalArgumentException("No open test site was found nearby.");
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
                AmethystBlockRewards.MAX_HEALTH
        );
    }

    boolean damageTest(double amount) {
        if (active == null || amount <= 0d) {
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
            if ((long) x * x + (long) z * z < (long) minimumRadius * minimumRadius) {
                continue;
            }
            return new Candidate(world, x, z);
        }
        return null;
    }

    private Location findAnchor(Candidate candidate) {
        int y = candidate.world().getHighestBlockYAt(
                candidate.x(), candidate.z(), HeightMap.MOTION_BLOCKING_NO_LEAVES
        );
        Material ground = candidate.world().getBlockAt(candidate.x(), y, candidate.z()).getType();
        if (!ground.isSolid() || ground == Material.BEDROCK) {
            return null;
        }
        return new Location(candidate.world(), candidate.x(), y + 1, candidate.z());
    }

    private Location findTestAnchor(Player player) {
        Location origin = player.getLocation();
        double facing = Math.toRadians(origin.getYaw() + 90d);
        for (int radius = 18; radius <= 96; radius += 6) {
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

    private boolean safeSite(Location anchor) {
        World world = anchor.getWorld();
        if (anchor.getBlockY() + 22 >= world.getMaxHeight()
                || !world.getWorldBorder().isInside(anchor)) {
            return false;
        }
        // Sample the footprint. The display needs open sky, but slight terrain
        // variation is fine and prevents location searches from rejecting plains.
        for (int x : new int[]{-10, -5, 0, 5, 10}) {
            for (int z : new int[]{-10, -5, 0, 5, 10}) {
                int ground = world.getHighestBlockYAt(
                        anchor.getBlockX() + x, anchor.getBlockZ() + z,
                        HeightMap.MOTION_BLOCKING_NO_LEAVES
                );
                if (Math.abs((ground + 1) - anchor.getBlockY()) > 3) {
                    return false;
                }
            }
        }
        return true;
    }

    private void create(Location anchor) {
        World world = anchor.getWorld();
        Location centre = anchor.clone().add(0.5d, 0d, 0.5d);
        Set<Chunk> chunks = new HashSet<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                Chunk chunk = world.getChunkAt((anchor.getBlockX() >> 4) + x,
                        (anchor.getBlockZ() >> 4) + z);
                chunk.addPluginChunkTicket(plugin);
                chunks.add(chunk);
            }
        }
        List<Entity> created = new ArrayList<>();
        try {
            BlockDisplay display = world.spawn(centre, BlockDisplay.class, entity -> {
                entity.setBlock(Material.AMETHYST_BLOCK.createBlockData());
                entity.setTransformation(new Transformation(
                        new Vector3f(-10f, 0f, -10f), new Quaternionf(),
                        new Vector3f(DISPLAY_SCALE, DISPLAY_SCALE, DISPLAY_SCALE), new Quaternionf()
                ));
                decorate(entity);
            });
            created.add(display);
            Interaction interaction = world.spawn(centre, Interaction.class, entity -> {
                entity.setInteractionWidth(DISPLAY_SCALE);
                entity.setInteractionHeight(DISPLAY_SCALE);
                entity.setResponsive(true);
                decorate(entity);
            });
            created.add(interaction);
            TextDisplay title = label(centre.clone().add(0d, 23d, 0d),
                    Component.text("HUGE AMETHYST BLOCK", AMETHYST, TextDecoration.BOLD), 3.3f);
            created.add(title);
            TextDisplay countdown = label(centre.clone().add(0d, 21.3d, 0d),
                    countdownText(lifetimeMillis), 2.5f);
            created.add(countdown);

            long spawnedAt = System.currentTimeMillis();
            active = new ActiveBlock(
                    anchor.clone(), display, interaction, title, countdown, Set.copyOf(chunks),
                    spawnedAt, Math.addExact(spawnedAt, lifetimeMillis)
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
            announce(Component.text("AMETHYST EVENT » ", AMETHYST, TextDecoration.BOLD)
                    .append(Component.text("A Huge Amethyst Block appeared at "
                            + coordinates(anchor) + " in the " + worldName(world)
                            + "! Mine it together before 30:00 runs out.", NamedTextColor.WHITE)));
            plugin.getLogger().info("Spawned Huge Amethyst Block at " + coordinates(anchor)
                    + " in " + worldName(world));
        } catch (RuntimeException exception) {
            created.forEach(Entity::remove);
            chunks.forEach(chunk -> chunk.removePluginChunkTicket(plugin));
            throw exception;
        }
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMine(EntityDamageByEntityEvent event) {
        ActiveBlock block = active;
        if (block == null || !event.getEntity().getUniqueId().equals(block.interaction.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getDamager() instanceof Player player) || miningRate(player) <= 0d) {
            if (event.getDamager() instanceof Player player) {
                PlayerMenuService.error(player, "Use a pickaxe to mine the Huge Amethyst Block.");
            }
            return;
        }
        block.mining.put(player.getUniqueId(), System.currentTimeMillis());
        player.swingMainHand();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (active != null) {
            showBar(event.getPlayer());
        }
    }

    private void frame() {
        ActiveBlock block = active;
        if (block == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= block.expiresAt) {
            removeActive(true, "The Huge Amethyst Block dissolved before it was broken.");
            return;
        }
        List<Player> miners = block.mining.entrySet().stream()
                .filter(entry -> now - entry.getValue() <= MINE_GRACE_MILLIS)
                .map(entry -> plugin.getServer().getPlayer(entry.getKey()))
                .filter(player -> player != null && player.isOnline()
                        && player.getWorld().equals(block.anchor.getWorld())
                        && player.getLocation().distanceSquared(block.anchor) <= 34d * 34d
                        && miningRate(player) > 0d)
                .toList();
        double raw = miners.stream().mapToDouble(this::miningRate).sum();
        double damage = AmethystBlockRewards.groupDamagePerSecond(raw, miners.size()) / 4d;
        if (damage > 0d) {
            double multiplier = raw <= 0d ? 0d : damage / raw;
            for (Player miner : miners) {
                double dealt = miningRate(miner) / 4d * multiplier;
                block.damage.merge(miner.getUniqueId(), dealt, Double::sum);
            }
            applyDamage(null, damage);
        }
        drawAura(block, now);
        refreshDisplays(block, now);
    }

    private double miningRate(Player player) {
        ItemStack tool = player.getInventory().getItemInMainHand();
        String name = tool.getType().name();
        if (!name.endsWith("_PICKAXE")) {
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
        if (block == null || amount <= 0d) {
            return;
        }
        if (source != null) {
            block.damage.merge(source.getUniqueId(), amount, Double::sum);
        }
        block.health = Math.max(0d, block.health - amount);
        while (block.nextMilestone < AmethystBlockRewards.REWARD_HEALTH_PERCENTAGES.length
                && healthPercent(block.health)
                <= AmethystBlockRewards.REWARD_HEALTH_PERCENTAGES[block.nextMilestone]) {
            rewardEveryone(AmethystBlockRewards.rollMilestone(random), false, block);
            keyRain(block);
            block.nextMilestone++;
        }
        if (block.health <= 0d) {
            rewardEveryone(AmethystBlockRewards.completionBundle(random), true, block);
            complete(block);
        }
    }

    private void rewardEveryone(
            AmethystBlockRewards.Bundle bundle, boolean completion, ActiveBlock block
    ) {
        List<Player> recipients = eligiblePlayers();
        for (Player player : recipients) {
            int bonus = completion
                    ? AmethystBlockRewards.contributionKeys(player.getUniqueId(), block.damage) : 0;
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

    private void keyRain(ActiveBlock block) {
        World world = block.anchor.getWorld();
        Location top = block.anchor.clone().add(0.5d, 23d, 0.5d);
        List<Item> visuals = new ArrayList<>();
        for (int index = 0; index < 28; index++) {
            Location at = top.clone().add(random.nextDouble(-9d, 9d),
                    random.nextDouble(0d, 5d), random.nextDouble(-9d, 9d));
            Item item = world.dropItem(at, crateItems.key(1));
            item.setPickupDelay(Integer.MAX_VALUE);
            item.setPersistent(false);
            item.setWillAge(false);
            item.setVelocity(new Vector(random.nextDouble(-0.08d, 0.08d),
                    random.nextDouble(0.05d, 0.2d), random.nextDouble(-0.08d, 0.08d)));
            visuals.add(item);
        }
        world.playSound(top, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 10f, 0.75f);
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> visuals.forEach(Entity::remove), 60L);
    }

    private void complete(ActiveBlock block) {
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
        block.anchor.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER,
                block.anchor.clone().add(0.5d, 10d, 0.5d), 5, 6d, 8d, 6d, 0d);
        removeActive(true, "The Huge Amethyst Block was broken! Every online player received rewards.");
    }

    private void drawAura(ActiveBlock block, long now) {
        World world = block.anchor.getWorld();
        Location centre = block.anchor.clone().add(0.5d, 10d, 0.5d);
        double phase = now / 550d;
        for (int ring = 0; ring < 4; ring++) {
            double radius = 11.5d + ring * 1.3d;
            double y = -8d + ring * 5d;
            for (int point = 0; point < 32; point++) {
                double angle = phase * (ring % 2 == 0 ? 1d : -1d)
                        + point * Math.PI * 2d / 32d;
                spawnForViewers(world, Particle.DUST, centre.clone().add(
                        Math.cos(angle) * radius, y + Math.sin(angle * 3d) * 1.1d,
                        Math.sin(angle) * radius
                ), ring % 2 == 0 ? DEEP : BRIGHT);
            }
        }
        world.spawnParticle(Particle.REVERSE_PORTAL, centre, 35, 11d, 10d, 11d, 0.025d);
        world.spawnParticle(Particle.END_ROD, centre, 10, 9d, 9d, 9d, 0.01d);
    }

    private void spawnForViewers(
            World world, Particle particle, Location at, Particle.DustOptions options
    ) {
        for (Player viewer : world.getPlayers()) {
            if (settings.isEnabled(viewer.getUniqueId(),
                    PlayerSettingsStore.Setting.AIRDROP_PARTICLES)) {
                viewer.spawnParticle(particle, at, 1, 0d, 0d, 0d, 0d, options);
            }
        }
    }

    private void refreshDisplays(ActiveBlock block, long now) {
        long remaining = Math.max(0L, block.expiresAt - now);
        if (bossBar != null) {
            bossBar.progress((float) Math.clamp(
                    block.health / AmethystBlockRewards.MAX_HEALTH, 0d, 1d
            ));
            bossBar.name(bossTitle(block));
        }
        if (block.countdown.isValid()) {
            block.countdown.text(countdownText(remaining));
        }
    }

    private Component bossTitle(ActiveBlock block) {
        return Component.text("HUGE AMETHYST BLOCK • " + coordinates(block.anchor)
                        + " • " + Math.max(0L, Math.round(block.health)) + " HP • "
                        + AirdropService.formatCountdown(block.expiresAt - System.currentTimeMillis()),
                AMETHYST, TextDecoration.BOLD);
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

    private void removeActive(boolean announce, String message) {
        ActiveBlock block = active;
        active = null;
        if (frameTask != null) {
            frameTask.cancel();
            frameTask = null;
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
        block.display.remove();
        block.interaction.remove();
        block.title.remove();
        block.countdown.remove();
        block.chunks.forEach(chunk -> chunk.removePluginChunkTicket(plugin));
        if (announce && message != null) {
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

    private void clearStaleEntities() {
        for (World world : plugin.getServer().getWorlds()) {
            world.getEntities().stream()
                    .filter(entity -> entity.getScoreboardTags().contains(ENTITY_TAG))
                    .forEach(Entity::remove);
        }
    }

    private static int healthPercent(double health) {
        return (int) Math.floor(health * 100d / AmethystBlockRewards.MAX_HEALTH);
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
