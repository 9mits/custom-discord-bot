package bot.mgx.accessbridge;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.bossbar.BossBar;
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
import org.bukkit.Material;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
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
    private static final long PROMPT_INTERVAL_TICKS = 40L;
    private static final int ROOM_RADIUS = 12;
    private static final int ROOM_FLOOR_Y = 64;
    private static final int ROOM_CEILING_Y = 72;
    private static final Component VERIFY_PROMPT = Component.text(
            "VERIFY  •  Type /verify <your Discord username>",
            NamedTextColor.GOLD,
            TextDecoration.BOLD
    );

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
    private final File inventoryFile;
    private final YamlConfiguration inventoryStashes;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRequests = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> promptBars = new ConcurrentHashMap<>();
    private final Map<UUID, Component> prompts = new ConcurrentHashMap<>();
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
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setTime(18000L);
        world.setStorm(false);
        world.setThundering(false);
        world.getWorldBorder().setCenter(0.5, 0.5);
        // The player is contained by the room, not a distracting striped border.
        world.getWorldBorder().setSize(2048.0);
        buildRoom();
        spawn = new Location(world, 0.5, ROOM_FLOOR_Y + 1.0, 0.5, 0.0F, 0.0F);
        world.setSpawnLocation(spawn);
        inventoryFile = new File(plugin.getDataFolder(), "verification-inventories.yml");
        inventoryStashes = YamlConfiguration.loadConfiguration(inventoryFile);
        Bukkit.getScheduler().runTaskTimer(
                plugin, this::remindPlayers, PROMPT_INTERVAL_TICKS, PROMPT_INTERVAL_TICKS
        );
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
        switch (status) {
            case "DM_SENT" -> {
                updatePrompt(
                        player,
                        Component.text(
                                "CHECK DISCORD  •  Open the newest DM and press Yes, This Is Me",
                                NamedTextColor.AQUA,
                                TextDecoration.BOLD
                        ),
                        BossBar.Color.BLUE
                );
                player.showTitle(Title.title(
                        Component.text("CHECK DISCORD", NamedTextColor.AQUA, TextDecoration.BOLD),
                        Component.text("Open the newest DM and confirm", NamedTextColor.WHITE)
                ));
            }
            case "JOIN_DISCORD" -> updatePrompt(
                    player,
                    Component.text("JOIN DISCORD  •  Use /discord, then your DM arrives", NamedTextColor.LIGHT_PURPLE),
                    BossBar.Color.PURPLE
            );
            case "DMS_CLOSED" -> updatePrompt(
                    player,
                    Component.text("ENABLE DISCORD DMs  •  Then run /verify again", NamedTextColor.RED),
                    BossBar.Color.RED
            );
            case "RATE_LIMITED" -> updatePrompt(
                    player,
                    Component.text("PLEASE WAIT  •  Then run /verify again", NamedTextColor.YELLOW),
                    BossBar.Color.YELLOW
            );
            case "ACTIVATING" -> updatePrompt(
                    player,
                    Component.text("VERIFIED  •  Opening the SMP…", NamedTextColor.GREEN, TextDecoration.BOLD),
                    BossBar.Color.GREEN
            );
            default -> updatePrompt(
                    player,
                    Component.text(message, status.equals("FAILED") ? NamedTextColor.RED : NamedTextColor.YELLOW),
                    status.equals("FAILED") ? BossBar.Color.RED : BossBar.Color.YELLOW
            );
        }
        player.sendMessage(Component.text(message, status.equals("FAILED")
                ? NamedTextColor.RED : NamedTextColor.YELLOW));
    }

    void release(UUID accountUuid, String discordUsername) {
        Player player = playerForAccount(accountUuid);
        if (player == null) {
            return;
        }
        restoreIfNeeded(player);
        World main = Bukkit.getWorlds().stream()
                .filter(candidate -> !candidate.equals(world))
                .filter(candidate -> candidate.getEnvironment() == World.Environment.NORMAL)
                .findFirst()
                .orElse(null);
        if (main == null) {
            clearPrompt(player);
            player.kick(Component.text("Verification succeeded. Reconnect in a moment."));
            return;
        }
        releasing.add(player.getUniqueId());
        boolean moved = player.teleport(
                main.getSpawnLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN
        );
        if (!moved || player.getWorld().equals(world)) {
            releasing.remove(player.getUniqueId());
            clearPrompt(player);
            player.kick(Component.text(
                    "Verification succeeded. Reconnect to enter Mysterious SMP X."
            ));
            return;
        }
        sessions.remove(player.getUniqueId());
        lastRequests.remove(player.getUniqueId());
        clearPrompt(player);
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
            restoreIfNeeded(player);
            return;
        }
        event.joinMessage(null);
        stashInventory(player);
        player.setGameMode(GameMode.ADVENTURE);
        player.setInvulnerable(true);
        player.setFoodLevel(20);
        player.setSaturation(20.0F);
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.DARKNESS, PotionEffect.INFINITE_DURATION, 0, false, false, false
        ));
        player.teleport(spawn, PlayerTeleportEvent.TeleportCause.PLUGIN);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(player)) {
                continue;
            }
            online.hidePlayer(plugin, player);
            player.hidePlayer(plugin, online);
        }
        showInstructions(player, true);
    }

    private void showInstructions(Player player, boolean sendChatPrompt) {
        player.showTitle(Title.title(
                Component.text("DISCORD VERIFICATION", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text("Type /verify <your Discord username>", NamedTextColor.WHITE),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(5), Duration.ofMillis(500))
        ));
        updatePrompt(player, VERIFY_PROMPT, BossBar.Color.YELLOW);
        if (!sendChatPrompt) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !protectedPlayer(player)) {
                return;
            }
            player.sendMessage(Component.text("VERIFY  →  ", NamedTextColor.GOLD, TextDecoration.BOLD)
                    .append(Component.text("/verify your_discord_username", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.suggestCommand("/verify "))
                            .hoverEvent(HoverEvent.showText(Component.text("Click to enter your Discord username"))))
                    .append(Component.text("  •  ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("Need Discord? Click here", NamedTextColor.LIGHT_PURPLE)
                            .clickEvent(ClickEvent.openUrl(GuideService.DISCORD_INVITE_URL))
                            .hoverEvent(HoverEvent.showText(Component.text(GuideService.DISCORD_INVITE_DISPLAY)))));
        }, 20L);
    }

    private void updatePrompt(Player player, Component prompt, BossBar.Color color) {
        UUID uuid = player.getUniqueId();
        prompts.put(uuid, prompt);
        BossBar bar = promptBars.computeIfAbsent(uuid, ignored -> BossBar.bossBar(
                prompt, 1.0F, color, BossBar.Overlay.PROGRESS
        ));
        bar.name(prompt);
        bar.color(color);
        player.showBossBar(bar);
        player.sendActionBar(prompt);
    }

    private void clearPrompt(Player player) {
        UUID uuid = player.getUniqueId();
        BossBar bar = promptBars.remove(uuid);
        prompts.remove(uuid);
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    private void remindPlayers() {
        for (UUID uuid : sessions.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                Component prompt = prompts.getOrDefault(uuid, VERIFY_PROMPT);
                BossBar bar = promptBars.get(uuid);
                if (bar != null) {
                    player.showBossBar(bar);
                }
                player.sendActionBar(prompt);
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
            showInstructions(player, false);
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
        boolean bridgeConnected = bridge.isConnected();
        bridge.queueLinkRequest(
                session.edition(), session.accountUuid(), session.username(), session.xuid(), discordUsername
        );
        if (bridgeConnected) {
            Component waiting = Component.text(
                    "REQUEST SENT  •  Waiting for Discord…", NamedTextColor.AQUA, TextDecoration.BOLD
            );
            updatePrompt(player, waiting, BossBar.Color.BLUE);
            player.sendMessage(Component.text(
                    "Request sent to @" + discordUsername + ". Watch for a new Discord DM.",
                    NamedTextColor.AQUA
            ));
        } else {
            Component reconnecting = Component.text(
                    "DM DELAYED  •  Discord service is reconnecting automatically",
                    NamedTextColor.RED,
                    TextDecoration.BOLD
            );
            updatePrompt(player, reconnecting, BossBar.Color.RED);
            player.showTitle(Title.title(
                    Component.text("DM DELAYED", NamedTextColor.RED, TextDecoration.BOLD),
                    Component.text("Request saved • Discord service is reconnecting", NamedTextColor.WHITE)
            ));
            player.sendMessage(Component.text(
                    "Discord verification is temporarily offline. Your request is saved and will send when it reconnects.",
                    NamedTextColor.RED
            ));
        }
        return true;
    }

    private boolean protectedPlayer(Player player) {
        return isLobbyPlayer(player.getUniqueId());
    }

    /** Build the same deterministic sealed room even when the flat world already existed. */
    private void buildRoom() {
        for (int x = -ROOM_RADIUS; x <= ROOM_RADIUS; x++) {
            for (int z = -ROOM_RADIUS; z <= ROOM_RADIUS; z++) {
                for (int y = ROOM_FLOOR_Y; y <= ROOM_CEILING_Y; y++) {
                    boolean shell = x == -ROOM_RADIUS || x == ROOM_RADIUS
                            || z == -ROOM_RADIUS || z == ROOM_RADIUS
                            || y == ROOM_FLOOR_Y || y == ROOM_CEILING_Y;
                    world.getBlockAt(x, y, z).setType(
                            shell ? Material.BLACK_CONCRETE : Material.AIR,
                            false
                    );
                }
            }
        }
    }

    /**
     * The lobby must look empty without deleting a returning tester's belongings.
     * The disk-backed stash also survives a disconnect or Paper restart mid-flow.
     */
    private void stashInventory(Player player) {
        String root = player.getUniqueId().toString();
        if (!inventoryStashes.isConfigurationSection(root)) {
            ItemStack[] contents = player.getInventory().getContents();
            inventoryStashes.set(root + ".size", contents.length);
            for (int slot = 0; slot < contents.length; slot++) {
                inventoryStashes.set(root + ".inventory." + slot, contents[slot]);
            }
            inventoryStashes.set(root + ".held-slot", player.getInventory().getHeldItemSlot());
            inventoryStashes.set(root + ".experience", player.getExp());
            inventoryStashes.set(root + ".level", player.getLevel());
            inventoryStashes.set(root + ".total-experience", player.getTotalExperience());
            inventoryStashes.set(root + ".game-mode", player.getGameMode().name());
            inventoryStashes.set(root + ".invulnerable", player.isInvulnerable());
            inventoryStashes.set(root + ".allow-flight", player.getAllowFlight());
            inventoryStashes.set(root + ".flying", player.isFlying());
            try {
                inventoryStashes.save(inventoryFile);
            } catch (IOException exception) {
                inventoryStashes.set(root, null);
                plugin.getLogger().severe("Could not protect " + player.getName()
                        + "'s inventory before verification: " + exception.getMessage());
                return;
            }
        }
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        player.setExp(0.0F);
        player.setLevel(0);
        player.setTotalExperience(0);
    }

    /** Restore a lobby stash after approval, including approval completed while offline. */
    void restoreIfNeeded(Player player) {
        String root = player.getUniqueId().toString();
        player.removePotionEffect(PotionEffectType.DARKNESS);
        if (!inventoryStashes.isConfigurationSection(root)) {
            return;
        }
        int size = Math.max(0, inventoryStashes.getInt(root + ".size"));
        ItemStack[] contents = new ItemStack[size];
        for (int slot = 0; slot < size; slot++) {
            contents[slot] = inventoryStashes.getItemStack(root + ".inventory." + slot);
        }
        player.getInventory().clear();
        player.getInventory().setContents(contents);
        player.getInventory().setHeldItemSlot(Math.max(0, Math.min(8,
                inventoryStashes.getInt(root + ".held-slot"))));
        player.setExp((float) inventoryStashes.getDouble(root + ".experience"));
        player.setLevel(inventoryStashes.getInt(root + ".level"));
        player.setTotalExperience(inventoryStashes.getInt(root + ".total-experience"));
        try {
            player.setGameMode(GameMode.valueOf(
                    inventoryStashes.getString(root + ".game-mode", GameMode.SURVIVAL.name())
            ));
        } catch (IllegalArgumentException ignored) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        player.setInvulnerable(inventoryStashes.getBoolean(root + ".invulnerable"));
        player.setAllowFlight(inventoryStashes.getBoolean(root + ".allow-flight"));
        player.setFlying(player.getAllowFlight() && inventoryStashes.getBoolean(root + ".flying"));
        inventoryStashes.set(root, null);
        try {
            inventoryStashes.save(inventoryFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Restored " + player.getName()
                    + " but could not clear their verification inventory stash: "
                    + exception.getMessage());
        }
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
        clearPrompt(event.getPlayer());
        Bukkit.getScheduler().runTask(plugin, () -> {
            sessions.remove(uuid);
            lastRequests.remove(uuid);
            releasing.remove(uuid);
        });
    }
}
