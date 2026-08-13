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
                    + "Your application has been sent to the moderators.\n"
                    + "You will receive a Discord notification when it is reviewed."
    );
    private static final Component VERIFICATION_HELP_MESSAGE = Component.text(
            "No active verification matched this account.\n\n"
                    + "Check the username and expiry shown on your Discord Minecraft card, then try again."
    );
    private static final Component APPLICATION_ALREADY_SENT_MESSAGE = Component.text(
            "Your application has already been sent.\n\n"
                    + "Your Minecraft account is verified and staff are reviewing it.\n"
                    + "Please wait for your result in Discord before trying to join again."
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
        ClanStore clanStore;
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
        luckPermsService = LuckPermsService.createIfAvailable(this);
        identityService = new DiscordIdentityService(this, identityStore);
        ClanService clanService = new ClanService(this, clanStore, identityService, perkService);
        GuideService guideService = new GuideService();
        sidebarService = new SidebarService(
                this,
                perkService,
                clanStore,
                identityService,
                bridgeConfig.scoreboardFooter(),
                bridgeConfig.scoreboardUpdateTicks()
        );
        bridgeClient = new BridgeClient(
                this, bridgeConfig, pending, processed, verificationEvents, verifiedApplications, networkExecutor
        );
        chatRelayService = new ChatRelayService(bridgeClient);
        // Statistics live beside the main world, which is where the server writes them.
        PlayerStatsService statsService = new PlayerStatsService(
                this,
                getServer().getWorlds().get(0).getWorldFolder().toPath().resolve("stats"),
                new java.util.concurrent.ConcurrentHashMap<>()
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
        getServer().getPluginManager().registerEvents(chatRelayService, this);
        if (getCommand("clans") == null
                || getCommand("claninfo") == null
                || getCommand("guide") == null
                || getCommand("perks") == null
                || getCommand("discord") == null
                || getCommand("discordnames") == null) {
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
        sidebarService.start();
        leaderboardService.start();
        bridgeClient.start();
    }

    @Override
    public void onDisable() {
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
        refreshClanAppearance();
    }

    void applyPlayerRank(UUID minecraftUuid, String rankGroup) {
        if (luckPermsService != null) {
            luckPermsService.applyRank(minecraftUuid, rankGroup);
        }
    }

    void refreshClanAppearance() {
        if (sidebarService != null) {
            sidebarService.refreshAll();
        }
    }

    void applyDiscordIdentity(UUID minecraftUuid, String discordUsername) {
        if (identityService != null) {
            identityService.sync(minecraftUuid, discordUsername);
        }
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.KICK_WHITELIST) {
            return;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        FloodgatePlayer floodgatePlayer;
        try {
            floodgatePlayer = FloodgateApi.getInstance().getPlayer(uuid);
        } catch (RuntimeException exception) {
            getLogger().warning("Floodgate lookup failed during account verification: "
                    + exception.getClass().getSimpleName());
            return;
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

        Optional<PendingVerification> match = pending.match(edition, actualUsername);
        if (match.isEmpty()) {
            event.kickMessage(
                    verifiedApplications.find(uuid).isPresent()
                            ? APPLICATION_ALREADY_SENT_MESSAGE
                            : VERIFICATION_HELP_MESSAGE
            );
            return;
        }
        if (!bridgeClient.queueVerification(match.get(), edition, uuid, actualUsername, xuid)) {
            event.kickMessage(Component.text(
                    "Verification is temporarily unavailable. Your application is safe; please try again shortly."
            ));
            return;
        }

        // Preserve KICK_WHITELIST. Only the text changes; bans, full-server and
        // every other login rejection result were returned above untouched.
        event.kickMessage(VERIFIED_MESSAGE);
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
