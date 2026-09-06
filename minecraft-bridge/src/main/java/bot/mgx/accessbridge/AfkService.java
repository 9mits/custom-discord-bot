package bot.mgx.accessbridge;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Edition-neutral manual and automatic AFK tracking. */
final class AfkService implements Listener, CommandExecutor {
    private final MGXAccessBridge plugin;
    private final long timeoutMillis;
    private final boolean invincible;
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCombat = new ConcurrentHashMap<>();
    private final Set<UUID> afk = ConcurrentHashMap.newKeySet();
    /** When each live AFK stretch began, so it can be closed into {@link AfkStore}. */
    private final Map<UUID, Long> afkSince = new ConcurrentHashMap<>();
    /**
     * Where each AFK player was standing when they stopped playing.
     *
     * <p>The last line of defence behind the team collision rule: whatever a piston,
     * a boat, a wind charge or a client the server does not control manages to shove,
     * the horizontal position is put back. Only X and Z are restored, so a player whose
     * floor is mined still falls and a player in a vehicle is left entirely alone.
     */
    private final Map<UUID, Location> anchors = new ConcurrentHashMap<>();
    private final AfkStore store;
    private BukkitTask task;
    private BukkitTask anchorTask;
    /** How far an AFK player may drift before they are put back, in blocks. */
    private static final double ANCHOR_TOLERANCE = 0.08d;

    AfkService(MGXAccessBridge plugin, long timeoutSeconds, boolean invincible, AfkStore store) {
        this.store = store;
        // Live: the combat window is a balance figure, not a startup one.
        this.plugin = plugin;
        this.timeoutMillis = Math.max(60L, timeoutSeconds) * 1_000L;
        this.invincible = invincible;
    }

    void start() {
        long now = System.currentTimeMillis();
        plugin.getServer().getOnlinePlayers().forEach(player -> lastActivity.put(player.getUniqueId(), now));
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkIdle, 20L, 20L);
        anchorTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::holdAnchors, 1L, 1L);
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (anchorTask != null) {
            anchorTask.cancel();
            anchorTask = null;
        }
        // A shutdown must not swallow the stretch a player is in the middle of, or a
        // server that restarts nightly records almost no AFK at all.
        for (UUID playerId : Set.copyOf(afk)) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                player.setCollidable(true);
            }
            closeSession(playerId, null);
        }
        afk.clear();
        afkSince.clear();
        anchors.clear();
        lastActivity.clear();
        lastCombat.clear();
    }

    /** Lifetime AFK for one player in seconds, including the stretch they are in now. */
    long afkSeconds(UUID playerId) {
        Long since = afkSince.get(playerId);
        long live = since == null ? 0L : Math.max(0L, System.currentTimeMillis() - since);
        return (store.totals(playerId).afkMillis() + live) / 1_000L;
    }

    /** Start of the current uninterrupted AFK stretch, or zero while active. */
    long sessionStartedAt(UUID playerId) {
        return afkSince.getOrDefault(playerId, 0L);
    }

    /** How many players are AFK right now, for the periodic snapshot. */
    int afkCount() {
        return afk.size();
    }

    /** Ends one AFK stretch: adds it to the lifetime total and tells Discord. */
    private void closeSession(UUID playerId, Player player) {
        Long since = afkSince.remove(playerId);
        if (since == null) {
            return;
        }
        long millis = Math.max(0L, System.currentTimeMillis() - since);
        store.record(playerId, millis);
        if (player != null) {
            report(player, false, millis);
        }
    }

    /** Tells Discord an AFK stretch started or ended. */
    private void report(Player player, boolean nowAfk, long sessionMillis) {
        try {
            plugin.queueAfkChange(player, nowAfk, afk.size(), Math.max(0L, sessionMillis) / 1_000L);
        } catch (RuntimeException failure) {
            // Statistics are never worth failing a player's AFK toggle over.
            plugin.getLogger().warning("Could not report AFK change: " + failure.getMessage());
        }
    }

    boolean isAfk(UUID playerId) {
        return afk.contains(playerId);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can set an AFK status.");
            return true;
        }
        if (afk.contains(player.getUniqueId())) {
            markActive(player);
            return true;
        }
        if (inCombat(player)) {
            // AFK grants damage immunity, so allowing it here turns a losing fight into
            // an unloseable one.
            player.sendActionBar(Component.text(
                    "You cannot go AFK for another "
                            + CombatTag.describe(remainingCombatSeconds(player)) + ".",
                    NamedTextColor.RED
            ));
            return true;
        }
        markAfk(player);
        return true;
    }

    /**
     * Every server-side shove: knockback, wind charges, explosions, fishing rods.
     *
     * <p>An AFK player is already immune to the damage those carry, so letting the
     * push through was the one half of the effect that still landed.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        if (afk.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().setCollidable(true);
        lastActivity.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        event.getPlayer().setCollidable(true);
        // Closed before the player is forgotten, so their stretch still reaches Discord.
        closeSession(id, event.getPlayer());
        lastActivity.remove(id);
        lastCombat.remove(id);
        anchors.remove(id);
        afk.remove(id);
    }

    boolean inCombat(Player player) {
        return CombatTag.inCombat(
                lastCombat.getOrDefault(player.getUniqueId(), 0L),
                System.currentTimeMillis(), plugin.gameVariables().integer("afk.combat-tag-seconds") * 1000L
        );
    }

    private long remainingCombatSeconds(Player player) {
        return CombatTag.remainingSeconds(
                lastCombat.getOrDefault(player.getUniqueId(), 0L),
                System.currentTimeMillis(), plugin.gameVariables().integer("afk.combat-tag-seconds") * 1000L
        );
    }

    /** Both sides of a fight are held, so neither can duck out through AFK. */
    private void tagCombat(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player victim) {
            lastCombat.put(victim.getUniqueId(), System.currentTimeMillis());
        }
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity source = byEntity.getDamager();
            if (source instanceof Projectile projectile
                    && projectile.getShooter() instanceof Entity shooter) {
                source = shooter;
            }
            if (source instanceof Player attacker) {
                lastCombat.put(attacker.getUniqueId(), System.currentTimeMillis());
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to != null && differentBlock(event.getFrom(), to)) {
            activity(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        activity(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventory(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            activity(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        activity(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String command = event.getMessage().substring(1).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!command.equals("afk") && !command.endsWith(":afk")) {
            activity(event.getPlayer());
        }
    }

    /**
     * Runs at HIGHEST so a plugin that already cancelled the hit keeps its say,
     * and so the decision is the last one made before damage is applied.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        tagCombat(event);
        AfkProtection.Decision decision = AfkProtection.decide(
                invincible,
                afk.contains(player.getUniqueId()),
                attackerIsPlayer(event),
                event.getCause() == EntityDamageEvent.DamageCause.VOID
        );
        switch (decision) {
            case WAKE -> markActive(player);
            case BLOCK -> {
                event.setCancelled(true);
                // Cancelling FIRE_TICK every tick otherwise leaves them burning
                // for as long as they stand there, and kills them on return.
                if (player.getFireTicks() > 0) {
                    player.setFireTicks(0);
                }
            }
            case IGNORE -> {
                // Nothing to do.
            }
        }
    }

    private static boolean attackerIsPlayer(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return false;
        }
        Entity damager = byEntity.getDamager();
        if (damager instanceof Player) {
            return true;
        }
        return damager instanceof Projectile projectile && projectile.getShooter() instanceof Player;
    }

    private void checkIdle() {
        long cutoff = System.currentTimeMillis() - timeoutMillis;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!afk.contains(player.getUniqueId())
                    && lastActivity.getOrDefault(player.getUniqueId(), 0L) <= cutoff
                    && !inCombat(player)) {
                markAfk(player);
            }
        }
    }

    /**
     * Puts an AFK player back on the spot they left, once per tick.
     *
     * <p>Deliberately horizontal only, and only while they are standing on their own
     * two feet. Restoring Y would hold someone in the air when their floor is removed,
     * and a player in a boat or a minecart is meant to travel.
     */
    private void holdAnchors() {
        for (UUID playerId : Set.copyOf(afk)) {
            Player player = plugin.getServer().getPlayer(playerId);
            Location anchor = anchors.get(playerId);
            if (player == null || anchor == null || player.getVehicle() != null) {
                continue;
            }
            Location now = player.getLocation();
            if (anchor.getWorld() != now.getWorld()) {
                anchors.put(playerId, now.clone());
                continue;
            }
            double driftX = now.getX() - anchor.getX();
            double driftZ = now.getZ() - anchor.getZ();
            if (driftX * driftX + driftZ * driftZ < ANCHOR_TOLERANCE * ANCHOR_TOLERANCE) {
                continue;
            }
            Location held = now.clone();
            held.setX(anchor.getX());
            held.setZ(anchor.getZ());
            player.teleport(held, PlayerTeleportEvent.TeleportCause.PLUGIN);
            player.setVelocity(new Vector(0d, Math.min(0d, player.getVelocity().getY()), 0d));
        }
    }

    private void activity(Player player) {
        lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
        if (!afk.contains(player.getUniqueId())) {
            return;
        }
        // Waking on the next tick was fine while nothing held the player in place. The
        // anchor runs every tick, so a deferred wake let a returning player take one
        // step and be dragged back before their own move event had been acted on.
        if (plugin.getServer().isPrimaryThread()) {
            markActive(player);
        } else {
            plugin.getServer().getScheduler().runTask(plugin, () -> markActive(player));
        }
    }

    private void markAfk(Player player) {
        lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
        if (afk.add(player.getUniqueId())) {
            afkSince.put(player.getUniqueId(), System.currentTimeMillis());
            anchors.put(player.getUniqueId(), player.getLocation().clone());
            player.setCollidable(false);
            player.sendActionBar(Component.text("You are now AFK.", NamedTextColor.GRAY));
            report(player, true, 0L);
            refreshTab();
        }
    }

    private void markActive(Player player) {
        lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
        if (afk.remove(player.getUniqueId())) {
            anchors.remove(player.getUniqueId());
            player.setCollidable(true);
            closeSession(player.getUniqueId(), player);
            player.sendActionBar(Component.text("Welcome back.", NamedTextColor.GREEN));
            refreshTab();
        }
    }

    private void refreshTab() {
        if (plugin.sidebarService() != null) {
            plugin.sidebarService().refreshAll();
        }
    }

    private static boolean differentBlock(Location from, Location to) {
        return from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ();
    }
}
