package bot.mgx.accessbridge;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reports what happens in the world to the Discord activity log.
 *
 * <p>The bridge already carried what a player does through a menu — founding a clan,
 * buying from the shop. This is the rest of the server: who killed whom, what came out
 * of the ground, what a crate paid out.
 *
 * <p><b>Volume is the whole design problem.</b> A busy hour is thousands of ore blocks
 * and hundreds of mob kills, and one Discord message each is not a log, it is an
 * outage. So every high-rate topic is counted per player and flushed as a single line
 * on a timer, and only the events worth interrupting somebody for — a player kill, a
 * diamond, a boss — are reported the moment they happen. A player who logs out is
 * flushed immediately, because a tally nobody ever sees is worse than no tally.
 *
 * <p>Each topic can be switched off in {@code config.yml} under {@code activity-log},
 * which is the source-side half of the customisation; the Discord side decides which
 * channel each topic is written to and can mute one outright.
 */
final class ActivityLogService implements Listener {
    /** Individually worth a line the moment it happens. */
    private static final Set<Material> NOTABLE_ORES = EnumSet.of(
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.ANCIENT_DEBRIS,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE
    );

    /** Counted and reported as a tally. Mining these is a session, not an event. */
    private static final Set<Material> BULK_ORES = EnumSet.of(
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.NETHER_GOLD_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.NETHER_QUARTZ_ORE
    );

    private static final Set<EntityType> BOSSES = EnumSet.of(
            EntityType.ENDER_DRAGON, EntityType.WITHER,
            EntityType.WARDEN, EntityType.ELDER_GUARDIAN
    );

    /**
     * Placed ore is not mined ore.
     *
     * <p>Without this a player can place and break the same block to fill the log.
     * Bounded and evicted oldest-first: this is log hygiene, not an audit trail, and
     * an unbounded set of every block anybody ever placed is a memory leak with a
     * schedule.
     */
    private static final int PLACED_MEMORY = 20_000;

    private final MGXAccessBridge plugin;
    private final Map<UUID, Tally> tallies = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> placedOre = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(1_024, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                    return size() > PLACED_MEMORY;
                }
            }
    );
    private final Set<String> silenced;
    private final long flushTicks;
    private BukkitTask flushTask;

    ActivityLogService(MGXAccessBridge plugin, ConfigurationSection section) {
        this.plugin = plugin;
        this.silenced = readSilencedTopics(section);
        long minutes = section == null ? 5L : Math.max(1L, section.getLong("flush-minutes", 5L));
        this.flushTicks = minutes * 60L * 20L;
    }

    /**
     * Only the topics the operator has explicitly switched off.
     *
     * <p>Stated as a deny list on purpose. An allow list means a topic added to the
     * plugin later goes silent on every server that already has a config file, which
     * is the failure nobody notices — the log simply never mentions the new thing.
     */
    private static Set<String> readSilencedTopics(ConfigurationSection section) {
        ConfigurationSection topics = section == null
                ? null : section.getConfigurationSection("topics");
        if (topics == null) {
            return Set.of();
        }
        Set<String> off = new java.util.HashSet<>();
        for (String key : topics.getKeys(false)) {
            if (!topics.getBoolean(key, true)) {
                off.add(key.toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(off);
    }

    /** Whether this server reports a topic at all. Unknown topics report. */
    boolean reports(String topic) {
        return topic == null || !silenced.contains(topic.toLowerCase(Locale.ROOT));
    }

    void start() {
        flushTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::flushAll, flushTicks, flushTicks
        );
    }

    void stop() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        flushAll();
        placedOre.clear();
    }

    // ---------------------------------------------------------------- combat

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!reports(ServerEvent.CATEGORY_COMBAT)) {
            return;
        }
        Player victim = event.getEntity();
        if (skip(victim)) {
            return;
        }
        Player killer = victim.getKiller();
        String cause = event.deathMessage() == null
                ? "Unknown"
                : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(event.deathMessage());
        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            ServerEvent.of(
                    "player_kill", ServerEvent.CATEGORY_COMBAT,
                    killer.getUniqueId(), killer.getName(), plugin::recordServerEvent
            ).summary(killer.getName() + " killed " + victim.getName())
                    .detail("victim", victim.getName())
                    .detail("weapon", heldName(killer))
                    .detail("killer_health_left", String.valueOf(Math.round(killer.getHealth())))
                    .detail("where", place(victim.getLocation()))
                    .detail("message", cause)
                    .record();
            return;
        }
        ServerEvent.of(
                "player_death", ServerEvent.CATEGORY_COMBAT,
                victim.getUniqueId(), victim.getName(), plugin::recordServerEvent
        ).summary(cause)
                .detail("where", place(victim.getLocation()))
                .detail("experience_lost", event.getDroppedExp())
                .record();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!reports(ServerEvent.CATEGORY_COMBAT)) {
            return;
        }
        LivingEntity dead = event.getEntity();
        if (dead instanceof Player) {
            return;                       // handled by onPlayerDeath
        }
        Player killer = dead.getKiller();
        if (killer == null || skip(killer)) {
            return;
        }
        if (BOSSES.contains(dead.getType())) {
            ServerEvent.of(
                    "boss_kill", ServerEvent.CATEGORY_COMBAT,
                    killer.getUniqueId(), killer.getName(), plugin::recordServerEvent
            ).summary(killer.getName() + " killed a " + friendly(dead.getType()))
                    .detail("boss", friendly(dead.getType()))
                    .detail("where", place(dead.getLocation()))
                    .record();
            return;
        }
        tally(killer).mobs.merge(friendly(dead.getType()), 1L, Long::sum);
    }

    // ---------------------------------------------------------------- mining

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Material type = event.getBlock().getType();
        if (NOTABLE_ORES.contains(type) || BULK_ORES.contains(type)) {
            placedOre.put(key(event.getBlock()), Boolean.TRUE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();
        boolean notable = NOTABLE_ORES.contains(type);
        if (!notable && !BULK_ORES.contains(type)) {
            return;
        }
        // Always forget the block, whether or not mining is being reported, so the
        // memory cannot fill with blocks nothing will ever ask about again.
        boolean placed = placedOre.remove(key(block)) != null;
        Player player = event.getPlayer();
        if (placed || !reports(ServerEvent.CATEGORY_MINING) || skip(player)) {
            return;
        }
        if (notable) {
            ServerEvent.of(
                    "rare_ore", ServerEvent.CATEGORY_MINING,
                    player.getUniqueId(), player.getName(), plugin::recordServerEvent
            ).summary(player.getName() + " mined " + friendly(type))
                    .detail("ore", friendly(type))
                    .detail("where", place(block.getLocation()))
                    .detail("tool", heldName(player))
                    .record();
            return;
        }
        tally(player).ores.merge(friendly(type), 1L, Long::sum);
    }

    // ----------------------------------------------------------- progression

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!reports(ServerEvent.CATEGORY_PROGRESSION) || skip(event.getPlayer())) {
            return;
        }
        NamespacedKey key = event.getAdvancement().getKey();
        // Recipe unlocks are advancements the game awards silently by the hundred.
        if (key.getKey().startsWith("recipes/")) {
            return;
        }
        Player player = event.getPlayer();
        String name = friendlyKey(key.getKey());
        ServerEvent.of(
                "advancement", ServerEvent.CATEGORY_PROGRESSION,
                player.getUniqueId(), player.getName(), plugin::recordServerEvent
        ).summary(player.getName() + " earned " + name)
                .detail("advancement", name)
                .detail("key", key.toString())
                .record();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLevel(PlayerLevelChangeEvent event) {
        if (!reports(ServerEvent.CATEGORY_PROGRESSION) || skip(event.getPlayer())) {
            return;
        }
        int reached = event.getNewLevel();
        // Every level would be noise and levels are lost as often as gained; the
        // round numbers are the ones somebody would mention in chat.
        if (reached <= event.getOldLevel() || reached % 10 != 0) {
            return;
        }
        Player player = event.getPlayer();
        ServerEvent.of(
                "level_milestone", ServerEvent.CATEGORY_PROGRESSION,
                player.getUniqueId(), player.getName(), plugin::recordServerEvent
        ).summary(player.getName() + " reached experience level " + reached)
                .detail("level", reached)
                .record();
    }

    // -------------------------------------------------------------- tallying

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // A tally that is never flushed is a tally nobody sees.
        flush(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    private Tally tally(Player player) {
        return tallies.computeIfAbsent(player.getUniqueId(), id -> new Tally(player.getName()));
    }

    private void flushAll() {
        for (UUID playerId : Set.copyOf(tallies.keySet())) {
            flush(playerId, null);
        }
    }

    private void flush(UUID playerId, String name) {
        Tally tally = tallies.remove(playerId);
        if (tally == null) {
            return;
        }
        String actor = name == null ? tally.name : name;
        if (!tally.ores.isEmpty() && reports(ServerEvent.CATEGORY_MINING)) {
            ServerEvent.Builder builder = ServerEvent.of(
                    "ores_mined", ServerEvent.CATEGORY_MINING,
                    playerId, actor, plugin::recordServerEvent
            ).summary(actor + " mined " + total(tally.ores) + " ore blocks");
            detail(builder, tally.ores);
            builder.record();
        }
        if (!tally.mobs.isEmpty() && reports(ServerEvent.CATEGORY_COMBAT)) {
            ServerEvent.Builder builder = ServerEvent.of(
                    "mobs_killed", ServerEvent.CATEGORY_COMBAT,
                    playerId, actor, plugin::recordServerEvent
            ).summary(actor + " killed " + total(tally.mobs) + " mobs");
            detail(builder, tally.mobs);
            builder.record();
        }
    }

    /** {@link ServerEvent} keeps ten details, and one of them is the total. */
    static final int DETAIL_ROWS = 9;

    private static void detail(ServerEvent.Builder builder, Map<String, Long> counts) {
        for (Map.Entry<String, Long> entry : ranked(counts, DETAIL_ROWS)) {
            builder.detail(entry.getKey(), entry.getValue());
        }
        builder.detail("total", total(counts));
    }

    /**
     * The tally, biggest first, trimmed to what one event will carry.
     *
     * <p>What gets dropped has to be the part nobody would have read — the long tail,
     * never the headline — and ties break by name so two flushes of the same counts
     * read the same way. The total is on the summary either way, so nothing is lost,
     * only the breakdown of the smallest rows.
     */
    static List<Map.Entry<String, Long>> ranked(Map<String, Long> counts, int limit) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(Math.max(0, limit))
                .toList();
    }

    static long total(Map<String, Long> counts) {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    // --------------------------------------------------------------- helpers

    /** Staff building in creative and anybody stuck in the lobby are not playing. */
    private static boolean skip(Player player) {
        return player == null
                || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR
                || VerificationLobbyService.isLobbyWorld(player.getWorld());
    }

    private static long key(Block block) {
        return key(block.getX(), block.getY(), block.getZ());
    }

    /**
     * One block, as one long.
     *
     * <p>26 bits of X and Z either side of 12 bits of Y. The world border is 100,000
     * blocks and world height is -64 to 320, so nothing reachable can collide — and a
     * collision here would mean a mined ore silently going unreported because some
     * other block was placed once.
     */
    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38)
                | ((long) (z & 0x3FFFFFF) << 12)
                | (y + 2048L) & 0xFFFL;
    }

    private static String heldName(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        return held.getType() == Material.AIR ? "Fists" : friendly(held.getType());
    }

    private static String place(Location location) {
        return location.getWorld().getName() + " " + location.getBlockX()
                + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }

    private static String friendly(Enum<?> value) {
        return friendlyKey(value.name());
    }

    private static String friendlyKey(String raw) {
        String[] words = raw.toLowerCase(Locale.ROOT).replace('/', ' ').split("_");
        StringBuilder text = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return text.toString();
    }

    /** One player's uncommitted counts. Sorted so a flush reads the same way twice. */
    private static final class Tally {
        private final String name;
        private final Map<String, Long> ores = new TreeMap<>();
        private final Map<String, Long> mobs = new TreeMap<>();

        private Tally(String name) {
            this.name = name;
        }
    }
}
