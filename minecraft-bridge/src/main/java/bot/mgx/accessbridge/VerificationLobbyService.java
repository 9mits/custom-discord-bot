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
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
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
    /** Live tuning; the constants above stay the defaults and stand alone in tests. */
    private static volatile java.util.function.ToDoubleFunction<String> tuning = key -> Double.NaN;

    static void tuningSource(java.util.function.ToDoubleFunction<String> source) {
        if (source != null) {
            tuning = source;
        }
    }

    private static double tuned(String key, double fallback) {
        double value = tuning.applyAsDouble(key);
        return Double.isNaN(value) ? fallback : value;
    }

    private static long requestCooldownMillis() {
        return (long) (tuned("verification.request-cooldown-seconds", 10d) * 1000d);
    }

    /** A sparse status line proves the otherwise-black lobby is still responsive. */
    private static final long PROMPT_INTERVAL_TICKS = 200L;
    private static final long PROMPT_INTERVAL_MILLIS = PROMPT_INTERVAL_TICKS * 50L;
    private static final int ROOM_RADIUS = 24;
    private static final int ROOM_FLOOR_Y = 64;
    private static final int ROOM_CEILING_Y = 72;
    /**
     * The verification prompts, rendered on demand rather than held as constants.
     *
     * <p>These were {@code static final Component}s, resolved once at class load. Making
     * them editable therefore meant making them methods: a control that pointed at a
     * constant would have changed the stored text and nothing a player ever saw, which
     * is worse than no control at all.
     */
    private static ServerMessages messages;

    static void messageSource(ServerMessages source) {
        messages = source;
    }

    private static Component rendered(String key, Component fallback) {
        ServerMessages source = messages;
        if (source == null || source.isSilenced(key)) {
            return fallback;
        }
        return source.render(key);
    }

    static Component verifyPrompt() {
        return rendered("messages.verify.step-one-chat",
                statusLine("Step 1 of 2: type /verify <your Discord username>"));
    }

    static Component verifyAction() {
        return rendered("messages.verify.step-one",
                Component.text("STEP 1 OF 2  \u2022  ", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .append(Component.text("/verify <Discord username>", NamedTextColor.YELLOW)));
    }

    private static Component confirmAction() {
        return rendered("messages.verify.step-two",
                Component.text("STEP 2 OF 2  \u2022  ", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .append(Component.text("Open newest DM \u2192 Yes, This Is Me",
                                NamedTextColor.YELLOW)));
    }

    private static final Title.Times PERSISTENT_TITLE_TIMES = Title.Times.times(
            Duration.ZERO, Duration.ofSeconds(2), Duration.ZERO
    );
    private static final LobbyTitle VERIFY_TITLE = lobbyTitle(
            "LINK DISCORD TO PLAY", NamedTextColor.GOLD,
            "Step 1: /verify <your Discord username>", NamedTextColor.YELLOW
    );
    private static final LobbyTitle CONFIRM_TITLE = lobbyTitle(
            "CHECK YOUR DISCORD DMS", NamedTextColor.GOLD,
            "Step 2: press Yes, This Is Me", NamedTextColor.YELLOW
    );

    private record LobbyTitle(Component heading, Component subtitle) {
    }

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
    private final Map<UUID, Component> prompts = new ConcurrentHashMap<>();
    private final Map<UUID, Component> actionBars = new ConcurrentHashMap<>();
    private final Map<UUID, LobbyTitle> centerTitles = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPromptMessages = new ConcurrentHashMap<>();
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
        // Chat fades quickly and many players never open it. Keep the single current
        // action visible without filling the otherwise-empty queue screen with UI.
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshHud, 20L, 20L);
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
                        statusLine("Step 2 of 2: open the newest bot DM and confirm"),
                        confirmAction(),
                        CONFIRM_TITLE
                );
            }
            case "JOIN_DISCORD" -> updatePrompt(
                    player,
                    statusLine(message),
                    Component.text("1. /discord  •  2. Join  •  3. Check newest DM",
                            NamedTextColor.YELLOW),
                    lobbyTitle("JOIN THE DISCORD", NamedTextColor.LIGHT_PURPLE,
                            "/discord → join → check your DMs", NamedTextColor.YELLOW)
            );
            case "DMS_CLOSED" -> updatePrompt(
                    player,
                    statusLine(message),
                    Component.text("Allow server-member DMs  •  Then /verify again",
                            NamedTextColor.RED),
                    lobbyTitle("DISCORD DMS ARE CLOSED", NamedTextColor.RED,
                            "Allow server-member DMs, then /verify again", NamedTextColor.YELLOW)
            );
            case "RATE_LIMITED" -> updatePrompt(
                    player,
                    statusLine(message),
                    Component.text("Check your newest DM  •  Or wait, then /verify again",
                            NamedTextColor.YELLOW),
                    lobbyTitle("A DM WAS ALREADY SENT", NamedTextColor.GOLD,
                            "Check it first, or wait and try again", NamedTextColor.YELLOW)
            );
            case "ACTIVATING" -> updatePrompt(
                    player,
                    statusLine("Verification successful — entering Mysterious SMP X"),
                    Component.text("VERIFIED  •  Entering Mysterious SMP X...",
                            NamedTextColor.GREEN, TextDecoration.BOLD),
                    lobbyTitle("VERIFIED", NamedTextColor.GREEN,
                            "Entering Mysterious SMP X...", NamedTextColor.WHITE)
            );
            default -> updatePrompt(
                    player,
                    statusLine(message),
                    Component.text(message, NamedTextColor.YELLOW),
                    lobbyTitle("VERIFICATION REQUIRED", NamedTextColor.GOLD,
                            message, NamedTextColor.YELLOW)
            );
        }
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
        prompts.put(player.getUniqueId(), verifyPrompt());
        actionBars.put(player.getUniqueId(), verifyAction());
        centerTitles.put(player.getUniqueId(), VERIFY_TITLE);
        // Essentials and other join listeners may speak later in the same event.
        // Limbo should begin as a clean black screen with one queue line, so draw it
        // after those messages and push the normal SMP history out of view.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !protectedPlayer(player)) {
                return;
            }
            player.sendMessage(Component.text("\n".repeat(40)));
            showInstructions(player);
        }, 2L);
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        if (!protectedPlayer(player)
                || event.getStatus() != PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
            return;
        }
        // A required Java pack covers the world with Mojang's loading screen, so
        // any join title sent before this event is literally invisible. Repeat the
        // compact guide once the player can actually see and act on it.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && protectedPlayer(player)) {
                showInstructions(player);
            }
        }, 5L);
    }

    private void showInstructions(Player player) {
        Component prompt = Component.text("Step 1 of 2: ", NamedTextColor.GOLD,
                        TextDecoration.BOLD)
                .append(Component.text("/verify <your Discord username>", NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.suggestCommand("/verify "))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "Click to enter your Discord username"
                        ))))
                .append(Component.text("  •  ", NamedTextColor.DARK_GRAY))
                .append(Component.text("Need Discord?", NamedTextColor.GRAY)
                        .clickEvent(ClickEvent.runCommand("/discord"))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                GuideService.DISCORD_INVITE_DISPLAY
                        ))));
        showCenterTitle(player, VERIFY_TITLE);
        player.sendMessage(Component.text("LINK DISCORD TO PLAY", NamedTextColor.GOLD,
                TextDecoration.BOLD));
        player.sendMessage(Component.text("1. ", NamedTextColor.YELLOW, TextDecoration.BOLD)
                .append(Component.text("Type ", NamedTextColor.WHITE))
                .append(Component.text("/verify <your Discord username>", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.suggestCommand("/verify ")))
                .append(Component.text(" — use your username, not display name.",
                        NamedTextColor.GRAY)));
        player.sendMessage(Component.text("2. ", NamedTextColor.YELLOW, TextDecoration.BOLD)
                .append(Component.text("Open the newest DM from Mysterious SMP X and press ",
                        NamedTextColor.WHITE))
                .append(Component.text("Yes, This Is Me", NamedTextColor.GREEN,
                        TextDecoration.BOLD)));
        player.sendMessage(Component.text("Keep Minecraft open—you will enter automatically.  •  Need Discord? ",
                        NamedTextColor.GRAY)
                .append(Component.text("/discord", NamedTextColor.LIGHT_PURPLE)
                        .clickEvent(ClickEvent.runCommand("/discord"))));
        UUID uuid = player.getUniqueId();
        prompts.put(uuid, prompt);
        actionBars.put(uuid, verifyAction());
        centerTitles.put(uuid, VERIFY_TITLE);
        lastPromptMessages.put(uuid, System.currentTimeMillis());
        player.sendActionBar(verifyAction());
    }

    private void updatePrompt(
            Player player,
            Component prompt,
            Component actionBar,
            LobbyTitle centerTitle
    ) {
        UUID uuid = player.getUniqueId();
        Component previous = prompts.put(uuid, prompt);
        actionBars.put(uuid, actionBar);
        centerTitles.put(uuid, centerTitle);
        if (!prompt.equals(previous)) {
            player.sendMessage(prompt);
            lastPromptMessages.put(uuid, System.currentTimeMillis());
        }
        player.sendActionBar(actionBar);
        showCenterTitle(player, centerTitle);
    }

    private void clearPrompt(Player player) {
        UUID uuid = player.getUniqueId();
        prompts.remove(uuid);
        actionBars.remove(uuid);
        centerTitles.remove(uuid);
        lastPromptMessages.remove(uuid);
        player.sendActionBar(Component.empty());
        player.clearTitle();
    }

    private void remindPlayers() {
        long now = System.currentTimeMillis();
        for (UUID uuid : sessions.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            long lastMessage = lastPromptMessages.getOrDefault(uuid, 0L);
            if (player != null && now - lastMessage >= PROMPT_INTERVAL_MILLIS) {
                Component prompt = prompts.getOrDefault(uuid, verifyPrompt());
                player.sendMessage(prompt);
                lastPromptMessages.put(uuid, now);
            }
        }
    }

    private void refreshHud() {
        for (UUID uuid : sessions.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            Component actionBar = actionBars.get(uuid);
            if (player != null && actionBar != null) {
                player.sendActionBar(actionBar);
            }
            LobbyTitle centerTitle = centerTitles.get(uuid);
            if (player != null && centerTitle != null) {
                showCenterTitle(player, centerTitle);
            }
        }
    }

    private static LobbyTitle lobbyTitle(
            String heading,
            NamedTextColor headingColor,
            String subtitle,
            NamedTextColor subtitleColor
    ) {
        return new LobbyTitle(
                Component.text(heading, headingColor, TextDecoration.BOLD),
                Component.text(subtitle, subtitleColor)
        );
    }

    private static void showCenterTitle(Player player, LobbyTitle centerTitle) {
        player.showTitle(Title.title(
                centerTitle.heading(), centerTitle.subtitle(), PERSISTENT_TITLE_TIMES
        ));
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
                    "That is not a valid Discord username. Use 2–32 letters, numbers, dots, "
                            + "or underscores. Example: /verify 9mits",
                    NamedTextColor.RED
            ));
            return true;
        }
        long now = System.currentTimeMillis();
        long previous = lastRequests.getOrDefault(player.getUniqueId(), 0L);
        long cooldown = requestCooldownMillis();
        if (now - previous < cooldown) {
            long seconds = Math.max(1L, (cooldown - (now - previous) + 999L) / 1000L);
            player.sendMessage(Component.text("A request was just sent. Check your newest Discord DM, "
                            + "or wait " + seconds + " seconds before trying again.",
                    NamedTextColor.YELLOW));
            return true;
        }
        lastRequests.put(player.getUniqueId(), now);
        if (session.edition() == MinecraftEdition.BEDROCK
                && (session.xuid() == null || session.xuid().isBlank())) {
            player.sendMessage(Component.text(
                    "Your Xbox account is still loading. Reconnect once, return to this lobby, "
                            + "then use /verify again.",
                    NamedTextColor.YELLOW
            ));
            return true;
        }
        boolean bridgeConnected = bridge.isConnected();
        bridge.queueLinkRequest(
                session.edition(), session.accountUuid(), session.username(), session.xuid(), discordUsername
        );
        if (bridgeConnected) {
            updatePrompt(
                    player,
                    statusLine("Step 2 of 2: open the newest bot DM and confirm"),
                    confirmAction(),
                    CONFIRM_TITLE
            );
        } else {
            Component reconnecting = Component.text(
                    "Request saved  •  Stay here while Discord reconnects automatically",
                    NamedTextColor.YELLOW
            );
            updatePrompt(
                    player,
                    statusLine("Discord is reconnecting; your request is safely saved"),
                    reconnecting,
                    lobbyTitle("DISCORD RECONNECTING", NamedTextColor.GOLD,
                            "Stay here—no need to type it again", NamedTextColor.YELLOW)
            );
        }
        return true;
    }

    private boolean protectedPlayer(Player player) {
        return isLobbyPlayer(player.getUniqueId());
    }

    /** Build an invisible limbo cell even when the old flat lobby world already existed. */
    private void buildRoom() {
        for (int x = -ROOM_RADIUS; x <= ROOM_RADIUS; x++) {
            for (int z = -ROOM_RADIUS; z <= ROOM_RADIUS; z++) {
                for (int y = ROOM_FLOOR_Y; y <= ROOM_CEILING_Y; y++) {
                    boolean shell = x == -ROOM_RADIUS || x == ROOM_RADIUS
                            || z == -ROOM_RADIUS || z == ROOM_RADIUS
                            || y == ROOM_FLOOR_Y || y == ROOM_CEILING_Y;
                    world.getBlockAt(x, y, z).setType(
                            shell ? Material.BARRIER : Material.AIR,
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
                    "Only /verify and /discord work in this lobby. Link Discord first to enter.",
                    NamedTextColor.YELLOW
            ));
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        if (protectedPlayer(event.getPlayer())) {
            event.setCancelled(true);
            return;
        }
        event.viewers().removeIf(viewer -> viewer instanceof Player player && protectedPlayer(player));
    }
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

    private static Component statusLine(String status) {
        return Component.text("Verification: ", NamedTextColor.GOLD)
                .append(Component.text(status, NamedTextColor.YELLOW));
    }
}
