package bot.mgx.accessbridge;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Edition-neutral manual and automatic AFK tracking. */
final class AfkService implements Listener, CommandExecutor {
    private final MGXAccessBridge plugin;
    private final long timeoutMillis;
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private final Set<UUID> afk = ConcurrentHashMap.newKeySet();
    private BukkitTask task;

    AfkService(MGXAccessBridge plugin, long timeoutSeconds) {
        this.plugin = plugin;
        this.timeoutMillis = Math.max(60L, timeoutSeconds) * 1_000L;
    }

    void start() {
        long now = System.currentTimeMillis();
        plugin.getServer().getOnlinePlayers().forEach(player -> lastActivity.put(player.getUniqueId(), now));
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkIdle, 20L, 20L);
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        afk.clear();
        lastActivity.clear();
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
        } else {
            markAfk(player);
        }
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        lastActivity.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastActivity.remove(id);
        afk.remove(id);
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

    private void checkIdle() {
        long cutoff = System.currentTimeMillis() - timeoutMillis;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!afk.contains(player.getUniqueId())
                    && lastActivity.getOrDefault(player.getUniqueId(), 0L) <= cutoff) {
                markAfk(player);
            }
        }
    }

    private void activity(Player player) {
        lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
        if (afk.contains(player.getUniqueId())) {
            plugin.getServer().getScheduler().runTask(plugin, () -> markActive(player));
        }
    }

    private void markAfk(Player player) {
        lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
        if (afk.add(player.getUniqueId())) {
            player.sendActionBar(Component.text("You are now AFK.", NamedTextColor.GRAY));
            refreshTab();
        }
    }

    private void markActive(Player player) {
        lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
        if (afk.remove(player.getUniqueId())) {
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
