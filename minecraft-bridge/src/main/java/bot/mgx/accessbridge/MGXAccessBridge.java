package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public final class MGXAccessBridge extends JavaPlugin implements Listener {
    private static final Component VERIFIED_MESSAGE = Component.text(
            "Minecraft account verified!\n\n"
                    + "One step left: return to Discord and fill out the short application form.\n"
                    + "Press Continue Application on your Minecraft application card."
    );
    private static final Component VERIFICATION_HELP_MESSAGE = Component.text(
            "No active verification matched this account.\n\n"
                    + "Check the username and expiry shown on your Discord Minecraft card, then try again."
    );
    private static final Component APPLICATION_ALREADY_SENT_MESSAGE = Component.text(
            "Your Minecraft account is already verified.\n\n"
                    + "If you have not finished the written application, press Continue Application\n"
                    + "on your Minecraft application card in Discord. Staff review it once it is\n"
                    + "submitted, and you will be let in as soon as they approve it."
    );

    private static final Component MAINTENANCE_MESSAGE = Component.text(
            "Mysterious SMP X is not open right now.\n\n"
                    + "The server is closed for maintenance and nobody can join. Nothing is\n"
                    + "wrong with your account — check Discord to find out when it opens."
    );

    private ScheduledExecutorService networkExecutor;
    private BridgeClient bridgeClient;
    private PendingVerificationCache pending;
    private PlayerPerkService perkService;
    private SidebarService sidebarService;
    private VerifiedApplicationStore verifiedApplications;
    private DiscordIdentityService identityService;
    private ChatRelayService chatRelayService;
    private LuckPermsService luckPermsService;
    private LeaderboardService leaderboardService;
    private CapabilityService capabilityService;
    private ClanStore clanStore;
    private ClanMenuService clanMenuService;
    private PlayerMenuService playerMenuService;
    private PlayerSettingsStore playerSettings;
    private WealthStore wealthStore;
    private RankSyncStore rankSyncStore;
    private MaintenanceStore maintenanceStore;
    private final WhitelistDirectory whitelistDirectory = new WhitelistDirectory();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        BridgeConfig bridgeConfig;
        try {
            bridgeConfig = BridgeConfig.load(getConfig());
        } catch (IllegalArgumentException exception) {
            getLogger().severe("MGXAccessBridge configuration is invalid: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        networkExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MGXAccessBridge-Network");
            thread.setDaemon(true);
            return thread;
        });
        pending = new PendingVerificationCache();
        ProcessedActionStore processed;
        VerificationEventStore verificationEvents;
        DiscordIdentityStore identityStore;
        try {
            Path resultFile = getDataFolder().toPath().resolve("processed-actions.properties");
            processed = new ProcessedActionStore(resultFile, networkExecutor);
            verificationEvents = new VerificationEventStore(
                    getDataFolder().toPath().resolve("verification-events.json")
            );
            verifiedApplications = new VerifiedApplicationStore(
                    getDataFolder().toPath().resolve("verified-applications.json")
            );
            clanStore = new ClanStore(getDataFolder().toPath().resolve("clans.json"));
            playerSettings = new PlayerSettingsStore(
                    getDataFolder().toPath().resolve("player-settings.json")
            );
            wealthStore = new WealthStore(getDataFolder().toPath().resolve("wealth.json"));
            rankSyncStore = new RankSyncStore(getDataFolder().toPath().resolve("rank-sync.json"));
            maintenanceStore = new MaintenanceStore(
                    getDataFolder().toPath().resolve("maintenance.flag")
            );
            identityStore = new DiscordIdentityStore(
                    getDataFolder().toPath().resolve("discord-identities.json")
            );
        } catch (IOException exception) {
            getLogger().severe("MGXAccessBridge could not open its data stores: " + exception.getMessage());
            networkExecutor.shutdownNow();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        perkService = new PlayerPerkService();
        luckPermsService = LuckPermsService.createIfAvailable(this, rankSyncStore);
        identityService = new DiscordIdentityService(this, identityStore);
        clanMenuService = new ClanMenuService(this, clanStore, identityService);
        playerMenuService = new PlayerMenuService(
                this, playerSettings, identityService, whitelistDirectory
        );
        ClanService clanService = new ClanService(
                this, clanStore, identityService, perkService, playerSettings, clanMenuService
        );
        GuideService guideService = new GuideService(playerMenuService);
        sidebarService = new SidebarService(
                this,
                perkService,
                clanStore,
                identityService,
                playerSettings,
                bridgeConfig.scoreboardFooter(),
                bridgeConfig.scoreboardUpdateTicks()
        );
        bridgeClient = new BridgeClient(
                this, bridgeConfig, pending, processed, verificationEvents, verifiedApplications, networkExecutor
        );
        chatRelayService = new ChatRelayService(bridgeClient, playerSettings);
        // Statistics live beside the main world, which is where the server writes them.
        PlayerStatsService statsService = new PlayerStatsService(
                this,
                getServer().getWorlds().get(0).getWorldFolder().toPath().resolve("stats"),
                wealthStore
        );
        capabilityService = new CapabilityService(
                this,
                bridgeClient,
                clanStore,
                luckPermsService,
                bridgeConfig.leaderboardRefreshTicks()
        );
        leaderboardService = new LeaderboardService(
                this,
                bridgeClient,
                statsService,
                clanStore,
                bridgeConfig.leaderboardRefreshTicks()
        );
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(perkService, this);
        getServer().getPluginManager().registerEvents(clanService, this);
        getServer().getPluginManager().registerEvents(clanMenuService, this);
        getServer().getPluginManager().registerEvents(playerMenuService, this);
        getServer().getPluginManager().registerEvents(chatRelayService, this);
        if (getCommand("clans") == null
                || getCommand("claninfo") == null
                || getCommand("guide") == null
                || getCommand("perks") == null
                || getCommand("discord") == null
                || getCommand("discordnames") == null
                || getCommand("settings") == null
                || getCommand("mgxadmin") == null
                || getCommand("whitelisted") == null) {
            getLogger().severe("A required Minecraft command is missing from plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("clans").setExecutor(clanService);
        getCommand("clans").setTabCompleter(clanService);
        getCommand("claninfo").setExecutor(clanService);
        getCommand("claninfo").setTabCompleter(clanService);
        getCommand("guide").setExecutor(guideService);
        getCommand("guide").setTabCompleter(guideService);
        getCommand("perks").setExecutor(guideService);
        getCommand("discord").setExecutor(guideService);
        getCommand("discordnames").setExecutor(identityService);
        PlayerSettingsService settingsService = new PlayerSettingsService(this, playerSettings, playerMenuService);
        getCommand("settings").setExecutor(settingsService);
        getCommand("settings").setTabCompleter(settingsService);
        WhitelistDirectoryService whitelistService = new WhitelistDirectoryService(whitelistDirectory, playerMenuService);
        getCommand("whitelisted").setExecutor(whitelistService);
        getCommand("whitelisted").setTabCompleter(whitelistService);
        AdminCommandService adminService = new AdminCommandService(
                this,
                rankSyncStore,
                new ServerDataResetService(
                        this,
                        clanStore,
                        wealthStore,
                        rankSyncStore,
                        identityStore,
                        playerSettings,
                        verifiedApplications,
                        verificationEvents,
                        processed,
                        getServer().getWorlds().get(0).getWorldFolder().toPath(),
                        // Where the worlds live is also where whitelist.json and
                        // usercache.json sit, which is what the reset needs.
                        getServer().getWorldContainer().toPath()
                )
        );
        getCommand("mgxadmin").setExecutor(adminService);
        getCommand("mgxadmin").setTabCompleter(adminService);
        sidebarService.start();
        leaderboardService.start();
        capabilityService.start();
        bridgeClient.start();
    }

    @Override
    public void onDisable() {
        if (clanMenuService != null) {
            // Items sitting in an open donation window exist only in that inventory
            // object. Closing banks them; not closing loses them.
            clanMenuService.closeAll();
        }
        if (capabilityService != null) {
            capabilityService.stop();
        }
        if (leaderboardService != null) {
            leaderboardService.stop();
        }
        if (sidebarService != null) {
            sidebarService.stop();
        }
        if (perkService != null) {
            perkService.clearOnline(getServer().getOnlinePlayers());
        }
        if (bridgeClient != null) {
            bridgeClient.close();
        }
        if (networkExecutor != null) {
            networkExecutor.shutdownNow();
        }
    }

    void applyPlayerProfile(org.bukkit.entity.Player player, PlayerProfile profile) {
        perkService.apply(player, profile);
        sidebarService.refresh(player);
        refreshClans();
    }

    /** Called when the bridge reconnects, so the bot is never left without standings. */
    void republishLeaderboard() {
        if (leaderboardService != null) {
            getServer().getScheduler().runTask(this, leaderboardService::publishNow);
        }
    }

    /** Called when the bridge reconnects, so Discord is never left guessing. */
    void republishCapabilities() {
        if (capabilityService != null) {
            getServer().getScheduler().runTask(this, capabilityService::publishNow);
        }
    }

    /**
     * Performs a clan action requested from Discord.
     *
     * <p>Only actions that do not need the player standing in the world are offered;
     * anything positional stays in game. {@link ClanStore} decides whether the actor
     * is allowed, so this is a thin dispatch rather than a second rulebook.
     */
    String runClanAction(UUID actor, String action, String argument) throws java.io.IOException {
        java.util.Optional<ClanStore.ClanView> clan = clanStore.clanOf(actor);
        switch (action) {
            case "leave" -> {
                String name = clanStore.leave(actor);
                refreshClans();
                republishCapabilities();
                return "You left " + name + ".";
            }
            case "disband" -> {
                // Disbanding refunds the vault, and Discord cannot hand anyone items.
                // Refusing beats destroying what members contributed.
                boolean holdsVault = clan.map(view -> !view.vault().isEmpty()).orElse(false);
                if (holdsVault) {
                    throw new ClanStore.ClanException(
                            "Your clan vault still holds materials. Disband in game with "
                                    + "/clans disband confirm so they are returned to you."
                    );
                }
                ClanStore.ClanView disbanded = clanStore.disband(actor);
                refreshClans();
                republishCapabilities();
                return disbanded.name() + " was disbanded.";
            }
            case "rename" -> {
                ClanStore.ClanView renamed = clanStore.rename(actor, argument);
                refreshClans();
                republishCapabilities();
                return "Your clan is now called " + renamed.name() + ".";
            }
            case "color" -> {
                ClanStore.ClanView recoloured = clanStore.setThemeColor(actor, argument);
                refreshClans();
                return String.format("Clan colour set to #%06X.", recoloured.themeColor());
            }
            case "kick", "promote", "demote", "transfer" -> {
                ClanStore.ClanView view = clan.orElseThrow(
                        () -> new ClanStore.ClanException("You are not in a clan.")
                );
                UUID target = clanStore.findMember(view.id(), argument).orElseThrow(
                        () -> new ClanStore.ClanException("No clan member has that name.")
                );
                String outcome = switch (action) {
                    case "kick" -> clanStore.kick(actor, target) + " was removed from the clan.";
                    case "promote" -> {
                        clanStore.setStaff(actor, target, true);
                        yield argument + " is now clan staff.";
                    }
                    case "demote" -> {
                        clanStore.setStaff(actor, target, false);
                        yield argument + " is no longer clan staff.";
                    }
                    default -> {
                        clanStore.transfer(actor, target);
                        yield argument + " now leads the clan.";
                    }
                };
                refreshClans();
                republishCapabilities();
                return outcome;
            }
            default -> throw new ClanStore.ClanException("That clan action is not available here.");
        }
    }

    /**
     * Runs one staff tool on behalf of a Discord-originated request.
     *
     * <p>The payload's claim about who is asking is never trusted on its own: permission
     * is re-checked here, through the player's live session if they are online or
     * through LuckPerms' own storage if they are not — which is the normal case for a
     * moderator acting from Discord. Only a tool with a remote command builder can run
     * this way; the rest are investigative or need the actor standing in the world.
     */
    java.util.concurrent.CompletableFuture<String> runStaffAction(
            UUID actor, String toolKey, String target, String reason, String duration
    ) {
        StaffTools.StaffTool tool = StaffTools.find(toolKey)
                .orElseThrow(() -> new StaffActionException("That is not a recognised staff tool."));
        StaffTools.RemoteCommand remote = tool.remote()
                .orElseThrow(() -> new StaffActionException(
                        "That tool has to be used in game, not from Discord."
                ));

        // Broadcast-style tools carry no player, so an empty target is correct there
        // and must not be pushed through the username check.
        String safeTarget = tool.needsTarget() ? StaffActionInput.sanitizeUsername(target) : "";
        String safeReason = StaffActionInput.sanitizeFreeText(reason, 200);
        String safeDuration = StaffActionInput.sanitizeFreeText(duration, 20);
        String command;
        try {
            command = remote.build(safeTarget, safeReason, safeDuration);
        } catch (IllegalArgumentException exception) {
            throw new StaffActionException(exception.getMessage());
        }
        String finalCommand = command;

        return authorised(actor, tool.permission()).thenCompose(allowed -> {
            if (!allowed) {
                return java.util.concurrent.CompletableFuture.failedFuture(
                        new StaffActionException("You do not have permission to do that.")
                );
            }
            java.util.concurrent.CompletableFuture<String> dispatched =
                    new java.util.concurrent.CompletableFuture<>();
            getServer().getScheduler().runTask(this, () -> {
                boolean ran = getServer().dispatchCommand(getServer().getConsoleSender(), finalCommand);
                getLogger().info("Discord ran staff tool '" + toolKey + "' as " + actor);
                dispatched.complete(ran
                        ? StaffTools.confirmation(toolKey, safeTarget, safeReason, safeDuration)
                        : "The server did not recognise that command.");
            });
            return dispatched;
        });
    }

    /**
     * An online session's permissions are authoritative for that session; an offline
     * player has none we can check without LuckPerms, so absent it, the action refuses
     * rather than guessing.
     */
    private java.util.concurrent.CompletableFuture<Boolean> authorised(UUID actor, String permission) {
        org.bukkit.entity.Player online = getServer().getPlayer(actor);
        if (online != null) {
            return java.util.concurrent.CompletableFuture.completedFuture(online.hasPermission(permission));
        }
        if (luckPermsService == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        return luckPermsService.hasPermission(actor, permission);
    }

    /**
     * Opens or closes the server. Anyone already on who is not exempt is kicked
     * when it closes, since holding it shut only for new logins leaves whoever was
     * online playing.
     */
    void setMaintenance(boolean enabled) {
        if (maintenanceStore == null || !maintenanceStore.set(enabled)) {
            return;
        }
        getLogger().warning("Maintenance mode " + (enabled ? "enabled" : "disabled") + " from Discord.");
        if (!enabled) {
            return;
        }
        getServer().getScheduler().runTask(this, () -> {
            for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
                if (!bypassesMaintenance(player)) {
                    player.kick(MAINTENANCE_MESSAGE);
                }
            }
        });
    }

    void applyPlayerRank(UUID minecraftUuid, String rankGroup) {
        if (luckPermsService != null) {
            luckPermsService.applyRank(minecraftUuid, rankGroup);
        }
    }

    /**
     * Reports an in-game action to the Discord activity log.
     *
     * <p>Routed through the plugin rather than handed the bridge directly, because the
     * services that raise events are built before the bridge client is and would
     * otherwise all need a late setter.
     *
     * <p>Actions that arrive <em>from</em> Discord are deliberately not reported here:
     * the bot already audits its own commands, and logging them again would double
     * every entry.
     */
    void recordServerEvent(ServerEvent event) {
        if (bridgeClient != null && event != null) {
            bridgeClient.queueServerEvent(event);
        }
    }

    /**
     * Re-reads clan state for everyone online: tags, and the perks their clan level
     * grants. Every membership change already routes through here, which is what makes
     * clan perks membership-scoped — a player who leaves or is kicked is recomputed at
     * level 0 on the same tick and loses the boost immediately.
     */
    void refreshClans() {
        if (sidebarService != null) {
            sidebarService.refreshAll();
        }
        if (perkService != null && clanStore != null) {
            for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
                perkService.applyClan(player, clanStore.clanOf(player.getUniqueId())
                        .map(ClanStore.ClanView::perks)
                        .orElse(ClanLevel.Perks.NONE));
            }
        }
    }

    void applyDiscordIdentity(UUID minecraftUuid, String discordUsername) {
        if (identityService != null) {
            identityService.sync(minecraftUuid, discordUsername);
        }
    }

    /** Drops a cached Discord name once the bot reports the account is not linked. */
    void forgetDiscordIdentity(UUID minecraftUuid) {
        if (identityService != null) {
            identityService.forget(minecraftUuid);
        }
    }

    void applyWhitelistDirectory(java.util.List<WhitelistDirectory.Entry> entries) {
        whitelistDirectory.replace(entries);
    }

    void broadcastDiscordChat(
            String discordUsername,
            String minecraftUsername,
            String message,
            int attachmentCount,
            String attachmentUrl
    ) {
        if (chatRelayService != null) {
            chatRelayService.broadcastDiscordChat(
                    discordUsername,
                    minecraftUsername,
                    message,
                    attachmentCount,
                    attachmentUrl
            );
        }
    }

    /**
     * Whether the server is closed to everybody who is not exempt.
     *
     * <p>Exempt means holds {@link AdminCommandService#PERMISSION} — the same tier
     * that already gates {@code /mgxadmin} — rather than a permission invented just
     * for this. A dedicated {@code maintenance.bypass} node existed once and
     * defaulted to {@code op}, which is what let every operator, including whoever
     * was testing the hold, through it without anyone granting anything. Reusing an
     * already-visible, already-audited permission means there is nothing left that
     * grants access silently: an operator gets in because they are an operator, and
     * anyone else needs {@code mgxaccessbridge.admin} explicitly set in LuckPerms.
     */
    private boolean maintenanceHeld() {
        return maintenanceStore != null && maintenanceStore.enabled();
    }

    private boolean bypassesMaintenance(org.bukkit.entity.Player player) {
        return player.hasPermission(AdminCommandService.PERMISSION);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerLogin(PlayerLoginEvent event) {
        PlayerLoginEvent.Result result = event.getResult();
        // A ban or a full server is somebody else's refusal and carries a more
        // useful message than ours. They are not getting in either way.
        if (result != PlayerLoginEvent.Result.ALLOWED
                && result != PlayerLoginEvent.Result.KICK_WHITELIST) {
            return;
        }

        // Verification runs before the hold is applied, and never needed the login
        // to succeed: an applicant is turned away whether the server is open or
        // closed, and only the wording differs. That is what lets a held server
        // still verify accounts.
        Component verdict = result == PlayerLoginEvent.Result.KICK_WHITELIST
                ? handleVerification(event)
                : null;

        if (!maintenanceHeld() || bypassesMaintenance(event.getPlayer())) {
            return;
        }
        if (result == PlayerLoginEvent.Result.ALLOWED) {
            getLogger().info("Refused " + event.getPlayer().getName()
                    + ": the server is closed for maintenance.");
        }
        // Rewriting the result to KICK_OTHER is what makes the hold hold. Floodgate
        // re-allows Bedrock players by looking for KICK_WHITELIST, so leaving that
        // result in place let every Bedrock login walk straight through.
        event.disallow(
                PlayerLoginEvent.Result.KICK_OTHER,
                verdict != null ? verdict : MAINTENANCE_MESSAGE
        );
    }

    /**
     * Matches a whitelist-refused login against the pending verifications.
     *
     * @return the message this login has earned, or null when there was nothing to
     *         say — which leaves the refusal exactly as it was found.
     */
    private Component handleVerification(PlayerLoginEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        FloodgatePlayer floodgatePlayer;
        try {
            floodgatePlayer = FloodgateApi.getInstance().getPlayer(uuid);
        } catch (RuntimeException exception) {
            getLogger().warning("Floodgate lookup failed during account verification: "
                    + exception.getClass().getSimpleName());
            return null;
        }

        MinecraftEdition edition;
        String actualUsername;
        String xuid = null;
        if (floodgatePlayer != null) {
            edition = MinecraftEdition.BEDROCK;
            actualUsername = floodgatePlayer.getUsername();
            xuid = String.valueOf(floodgatePlayer.getXuid());
        } else {
            edition = MinecraftEdition.JAVA;
            actualUsername = event.getPlayer().getName();
        }

        Component message;
        Optional<PendingVerification> match = pending.match(edition, actualUsername);
        if (match.isEmpty()) {
            message = verifiedApplications.find(uuid).isPresent()
                    ? APPLICATION_ALREADY_SENT_MESSAGE
                    : VERIFICATION_HELP_MESSAGE;
        } else if (bridgeClient.queueVerification(match.get(), edition, uuid, actualUsername, xuid)) {
            message = VERIFIED_MESSAGE;
        } else {
            message = Component.text(
                    "Verification is temporarily unavailable. Your application is safe; "
                            + "please try again shortly."
            );
        }
        // Only the text changes here. The KICK_WHITELIST result is preserved so that
        // an open server keeps behaving exactly as it did; the caller decides
        // separately whether a maintenance hold overrides it.
        event.kickMessage(message);
        return message;
    }

    /**
     * Last line of the maintenance hold.
     *
     * <p>The login refusal is the polite path, but it depends on every other plugin
     * leaving the result alone after this one has read it. Anybody who reaches the
     * world anyway is removed here.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMaintenanceJoin(PlayerJoinEvent event) {
        if (!maintenanceHeld() || bypassesMaintenance(event.getPlayer())) {
            return;
        }
        getLogger().warning("Removed " + event.getPlayer().getName()
                + " after login: the server is closed for maintenance.");
        event.getPlayer().kick(MAINTENANCE_MESSAGE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        queuePlayerActivity(event.getPlayer().getUniqueId(), event.getPlayer().getName(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        queuePlayerActivity(event.getPlayer().getUniqueId(), event.getPlayer().getName(), false);
    }

    private void queuePlayerActivity(UUID uuid, String javaUsername, boolean joined) {
        FloodgatePlayer floodgatePlayer;
        try {
            floodgatePlayer = FloodgateApi.getInstance().getPlayer(uuid);
        } catch (RuntimeException exception) {
            getLogger().warning("Floodgate lookup failed for player activity: "
                    + exception.getClass().getSimpleName());
            floodgatePlayer = null;
        }
        if (floodgatePlayer == null) {
            bridgeClient.queuePlayerActivity(joined, MinecraftEdition.JAVA, uuid, javaUsername, null);
        } else {
            bridgeClient.queuePlayerActivity(
                    joined,
                    MinecraftEdition.BEDROCK,
                    uuid,
                    floodgatePlayer.getUsername(),
                    String.valueOf(floodgatePlayer.getXuid())
            );
        }
    }
}
