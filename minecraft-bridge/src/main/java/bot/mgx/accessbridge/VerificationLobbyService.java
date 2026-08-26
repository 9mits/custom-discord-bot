package bot.mgx.accessbridge;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** A sealed world where an unknown player can safely prove Discord ownership. */
final class VerificationLobbyService implements Listener, CommandExecutor {
    static final String WORLD_NAME = "mgx_verification";
    private static final Pattern DISCORD_USERNAME = Pattern.compile("[A-Za-z0-9_.]{2,32}");
    private static final long REQUEST_COOLDOWN_MILLIS = 10_000L;

    private record Session(
            UUID loginUuid,
            UUID accountUuid,
            MinecraftEdition edition,
            String username,
            String xuid
    ) {
    }

    private final MGXAccessBridge plugin;
    private final BridgeClient bridge;
    private final World world;
    private final Location spawn;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRequests = new ConcurrentHashMap<>();
    private final Set<UUID> releasing = ConcurrentHashMap.newKeySet();

    VerificationLobbyService(MGXAccessBridge plugin, BridgeClient bridge) {
        this.plugin = plugin;
        this.bridge = bridge;
        World loaded = Bukkit.getWorld(WORLD_NAME);
        if (loaded == null) {
            loaded = Bukkit.createWorld(new WorldCreator(WORLD_NAME)
                    .environment(World.Environment.NORMAL)
                    .type(WorldType.FLAT)
                    .generateStructures(false));
        }
        if (loaded == null) {
            throw new IllegalStateException("Could not create the verification lobby world");
        }
        world = loaded;
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setTime(6000L);
        world.getWorldBorder().setCenter(0.5, 0.5);
        world.getWorldBorder().setSize(48.0);
        int y = world.getHighestBlockYAt(0, 0) + 1;
        spawn = new Location(world, 0.5, y, 0.5, 0.0F, 0.0F);
        world.setSpawnLocation(spawn);
        Bukkit.getScheduler().runTaskTimer(plugin, this::remindPlayers, 200L, 200L);
    }

    static boolean isLobbyWorld(World candidate) {
        return candidate != null && isLobbyWorldName(candidate.getName());
    }

    static boolean isLobbyWorldName(String candidate) {
        return WORLD_NAME.equals(candidate);
    }

    void markAwaiting(
            UUID loginUuid,
            UUID accountUuid,
            MinecraftEdition edition,
            String username,
            String xuid
    ) {
        sessions.put(loginUuid, new Session(loginUuid, accountUuid, edition, username, xuid));
    }

    boolean isLobbyPlayer(UUID uuid) {
        return sessions.containsKey(uuid);
    }

    void showStatus(UUID accountUuid, String status, String message) {
        Player player = playerForAccount(accountUuid);
        if (player == null) {
            return;
        }
        player.sendMessage(Component.text(message, status.equals("FAILED")
                ? NamedTextColor.RED : NamedTextColor.YELLOW));
        player.sendActionBar(Component.text(message, NamedTextColor.GOLD));
        if (status.equals("DM_SENT")) {
            player.showTitle(Title.title(
                    Component.text("CHECK DISCORD", NamedTextColor.AQUA, TextDecoration.BOLD),
                    Component.text("Open the newest DM and confirm", NamedTextColor.WHITE)
            ));
        }
    }

    void release(UUID accountUuid, String discordUsername) {
        Player player = playerForAccount(accountUuid);
        if (player == null) {
            return;
        }
        World main = Bukkit.getWorlds().stream()
                .filter(candidate -> !candidate.equals(world))
                .filter(candidate -> candidate.getEnvironment() == World.Environment.NORMAL)
                .findFirst()
                .orElse(null);
        if (main == null) {
            player.kick(Component.text("Verification succeeded. Reconnect in a moment."));
            return;
        }
        releasing.add(player.getUniqueId());
        boolean moved = player.teleport(
                main.getSpawnLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN
        );
        if (!moved || player.getWorld().equals(world)) {
            releasing.remove(player.getUniqueId());
            player.kick(Component.text(
                    "Verification succeeded. Reconnect to enter Mysterious SMP X."
            ));
            return;
        }
        sessions.remove(player.getUniqueId());
        lastRequests.remove(player.getUniqueId());
        player.setGameMode(GameMode.SURVIVAL);
        player.setInvulnerable(false);
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.showPlayer(plugin, player);
            player.showPlayer(plugin, online);
        }
        player.showTitle(Title.title(
                Component.text("VERIFIED", NamedTextColor.GREEN, TextDecoration.BOLD),
                Component.text("Welcome to Mysterious SMP X", NamedTextColor.WHITE),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(500))
        ));
        player.sendMessage(Component.text("Your Discord account @" + discordUsername
                + " is linked. Welcome!", NamedTextColor.GREEN));
        releasing.remove(player.getUniqueId());
    }

    private Player playerForAccount(UUID accountUuid) {
        for (Session session : sessions.values()) {
            if (session.accountUuid().equals(accountUuid) || session.loginUuid().equals(accountUuid)) {
                return Bukkit.getPlayer(session.loginUuid());
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpawn(AsyncPlayerSpawnLocationEvent event) {
        UUID uuid = event.getConnection().getProfile().getId();
        if (uuid != null && sessions.containsKey(uuid)) {
            event.setSpawnLocation(spawn.clone());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!isLobbyPlayer(player.getUniqueId())) {
            return;
        }
        event.joinMessage(null);
        player.setGameMode(GameMode.ADVENTURE);
        player.setInvulnerable(true);
        player.teleport(spawn, PlayerTeleportEvent.TeleportCause.PLUGIN);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(player)) {
                continue;
            }
            online.hidePlayer(plugin, player);
            player.hidePlayer(plugin, online);
        }
        showInstructions(player);
    }

    private void showInstructions(Player player) {
        player.showTitle(Title.title(
                Component.text("CONNECT DISCORD", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text("Type /verify your_discord_username", NamedTextColor.WHITE)
        ));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("Welcome! Link Discord before entering the SMP.", NamedTextColor.GOLD));
        player.sendMessage(Component.text("1. Type ", NamedTextColor.GRAY)
                .append(Component.text("/verify your_discord_username", NamedTextColor.AQUA)));
        player.sendMessage(Component.text("2. Open the newest DM from Mysterious SMP X.", NamedTextColor.GRAY));
        player.sendMessage(Component.text("3. Press ", NamedTextColor.GRAY)
                .append(Component.text("Yes, This Is Me", NamedTextColor.GREEN))
                .append(Component.text(". You will enter automatically.", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("Not in Discord? Click here to join", NamedTextColor.LIGHT_PURPLE)
                .clickEvent(ClickEvent.openUrl(GuideService.DISCORD_INVITE_URL))
                .hoverEvent(HoverEvent.showText(Component.text(GuideService.DISCORD_INVITE_DISPLAY))));
        player.sendMessage(Component.text(
                "No passwords, codes, downloads, or Discord login details are ever requested.",
                NamedTextColor.DARK_GRAY
        ));
    }

    private void remindPlayers() {
        for (UUID uuid : sessions.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendActionBar(Component.text(
                        "Link Discord: /verify your_discord_username  •  Need Discord? /discord",
                        NamedTextColor.GOLD
                ));
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return true;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            player.sendMessage(Component.text("Your Minecraft account is already verified.", NamedTextColor.GREEN));
            return true;
        }
        if (args.length != 1) {
            showInstructions(player);
            return true;
        }
        String discordUsername = args[0].strip();
        while (discordUsername.startsWith("@")) {
            discordUsername = discordUsername.substring(1);
        }
        if (!DISCORD_USERNAME.matcher(discordUsername).matches()) {
            player.sendMessage(Component.text(
                    "Use your exact Discord username: letters, numbers, dots, or underscores.",
                    NamedTextColor.RED
            ));
            return true;
        }
        long now = System.currentTimeMillis();
        long previous = lastRequests.getOrDefault(player.getUniqueId(), 0L);
        if (now - previous < REQUEST_COOLDOWN_MILLIS) {
            long seconds = Math.max(1L, (REQUEST_COOLDOWN_MILLIS - (now - previous) + 999L) / 1000L);
            player.sendMessage(Component.text("Wait " + seconds + " seconds before sending another request.",
                    NamedTextColor.YELLOW));
            return true;
        }
        lastRequests.put(player.getUniqueId(), now);
        if (session.edition() == MinecraftEdition.BEDROCK
                && (session.xuid() == null || session.xuid().isBlank())) {
            player.sendMessage(Component.text(
                    "Bedrock identity is still loading. Reconnect once, then use /verify again.",
                    NamedTextColor.YELLOW
            ));
            return true;
        }
        bridge.queueLinkRequest(
                session.edition(), session.accountUuid(), session.username(), session.xuid(), discordUsername
        );
        player.sendMessage(Component.text("Request saved. Looking for @" + discordUsername + " on Discord…",
                NamedTextColor.AQUA));
        player.sendActionBar(Component.text("Verification request saved • Keep this screen open", NamedTextColor.GOLD));
        return true;
    }

    private boolean protectedPlayer(Player player) {
        return isLobbyPlayer(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!protectedPlayer(event.getPlayer())) {
            return;
        }
        String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (!label.equals("verify") && !label.equals("discord")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text(
                    "Finish verification first. Use /verify or /discord.", NamedTextColor.YELLOW
            ));
        }
    }

    @EventHandler public void onChat(AsyncChatEvent event) { if (protectedPlayer(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onBreak(BlockBreakEvent event) { if (protectedPlayer(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent event) { if (protectedPlayer(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onInteract(PlayerInteractEvent event) { if (protectedPlayer(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onInteractEntity(PlayerInteractEntityEvent event) { if (protectedPlayer(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onInventory(InventoryOpenEvent event) { if (event.getPlayer() instanceof Player player && protectedPlayer(player)) event.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent event) { if (protectedPlayer(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onFood(FoodLevelChangeEvent event) { if (event.getEntity() instanceof Player player && protectedPlayer(player)) event.setCancelled(true); }
    @EventHandler public void onDamage(EntityDamageEvent event) { if (event.getEntity() instanceof Player player && protectedPlayer(player)) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!protectedPlayer(event.getPlayer()) || releasing.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        if (event.getTo() == null || !world.equals(event.getTo().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            sessions.remove(uuid);
            lastRequests.remove(uuid);
            releasing.remove(uuid);
        });
    }
}
