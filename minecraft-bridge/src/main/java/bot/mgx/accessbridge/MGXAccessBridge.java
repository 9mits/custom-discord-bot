package bot.mgx.accessbridge;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.SpawnChangeEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public final class MGXAccessBridge extends JavaPlugin implements Listener {
    // Two outcomes, because there are only two: either this account is verified
    // and plays, or it is not and is told exactly how to become verified. The
    // old ladder of six messages described an application queue that no longer
    // exists, and the worst of them told a player that being kicked was normal.
    private static final Component NOT_VERIFIED_MESSAGE = Component.text(
            "You need to verify before you can play.\n\n"
                    + "Open Discord, press Verify on the Mysterious SMP X panel, and enter this "
                    + "exact username.\n"
                    + "Then join again and you are in — no form, nothing to wait for."
    );
    private static final Component CLOSED_MESSAGE = Component.text(
            "Mysterious SMP X is closed right now.\n\n"
                    + "The world is closed for everyone, verified or not. Check Discord for news."
    );
    private static final Component UNAVAILABLE_MESSAGE = Component.text(
            "Verification could not be saved.\n\n"
                    + "Wait a minute, then join once more with the same account."
    );

    /** What the login path should do with a connection. */
    private record Verdict(boolean allow, Component kick) {
        static Verdict allowed() {
            return new Verdict(true, null);
        }

        static Verdict refuse(Component message) {
            return new Verdict(false, message);
        }
    }

    private final ConcurrentHashMap<UUID, Component> verificationKicks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PlayerConnectionIdentity> connectionIdentities =
            new ConcurrentHashMap<>();

    private LaunchService launchService;
    private ScheduledExecutorService networkExecutor;
    private BridgeClient bridgeClient;
    private PendingVerificationCache pending;
    private PlayerPerkService perkService;
    private SidebarService sidebarService;
    private DevBlogService devBlogService;
    private DevBlogStore devBlogStore;
    private VerifiedAccountStore verifiedAccounts;
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
    private EconomyStore economyStore;
    private EconomyMenuService economyMenus;
    private CrateStore crateStore;
    private CosmeticStore cosmeticStore;
    private TrophyHeadStore trophyHeadStore;
    private CrateService crates;
    private CosmeticEffectService cosmeticEffects;
    private RankSyncStore rankSyncStore;
    private MaintenanceStore maintenanceStore;
    private ServerEventStore serverEventStore;
    private AutoPayStore autoPayStore;
    private AutoPayService autoPayService;
    private ServerEventService serverEventService;
    private BlogWatchService blogWatchService;
    private UpdateNoticeService updateNotices;
    private BukkitTask maintenanceSweep;
    private AfkService afkService;
    private ChaosService chaosService;
    private BroadcastDisplayService broadcastDisplayService;
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
            verifiedAccounts = new VerifiedAccountStore(
                    getDataFolder().toPath().resolve("verified-applications.json")
            );
            clanStore = new ClanStore(getDataFolder().toPath().resolve("clans.json"));
            devBlogStore = new DevBlogStore(
                    getDataFolder().toPath().resolve("devblog-sessions.json")
            );
            playerSettings = new PlayerSettingsStore(
                    getDataFolder().toPath().resolve("player-settings.json")
            );
            wealthStore = new WealthStore(getDataFolder().toPath().resolve("wealth.json"));
            economyStore = new EconomyStore(getDataFolder().toPath().resolve("balances.json"));
            crateStore = CrateStore.open(getDataFolder().toPath());
            cosmeticStore = new CosmeticStore(getDataFolder().toPath().resolve("cosmetics.json"));
            trophyHeadStore = new TrophyHeadStore(
                    getDataFolder().toPath().resolve("trophy-heads.json")
            );
            rankSyncStore = new RankSyncStore(getDataFolder().toPath().resolve("rank-sync.json"));
            maintenanceStore = new MaintenanceStore(
                    getDataFolder().toPath().resolve("maintenance.flag")
            );
            serverEventStore = new ServerEventStore(
                    getDataFolder().toPath().resolve("server-events.json")
            );
            autoPayStore = new AutoPayStore(
                    getDataFolder().toPath().resolve("autopay.json")
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
        if (luckPermsService != null) {
            luckPermsService.grantEveryoneDefaults();
        }
        identityService = new DiscordIdentityService(this, identityStore);
        clanMenuService = new ClanMenuService(this, clanStore, identityService, economyStore);
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
                economyStore,
                bridgeConfig.scoreboardFooter(),
                bridgeConfig.scoreboardUpdateTicks()
        );
        bridgeClient = new BridgeClient(
                this, bridgeConfig, pending, processed, verificationEvents, verifiedAccounts, networkExecutor
        );
        chatRelayService = new ChatRelayService(bridgeClient, playerSettings);
        // Statistics live beside the main world, which is where the server writes them.
        PlayerStatsService statsService = new PlayerStatsService(
                this,
                getServer().getWorlds().get(0).getWorldFolder().toPath().resolve("stats"),
                economyStore
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
        getServer().getPluginManager().registerEvents(
                new StarterKitService(this, getDataFolder().toPath().resolve("starter-kits.json")),
                this
        );
        getServer().getPluginManager().registerEvents(perkService, this);
        getServer().getPluginManager().registerEvents(clanService, this);
        getServer().getPluginManager().registerEvents(clanMenuService, this);
        getServer().getPluginManager().registerEvents(playerMenuService, this);
        getServer().getPluginManager().registerEvents(chatRelayService, this);
        getServer().getPluginManager().registerEvents(new TeleportWarmupService(this), this);
        broadcastDisplayService = new BroadcastDisplayService(this);
        getServer().getPluginManager().registerEvents(broadcastDisplayService, this);
        serverEventService = new ServerEventService(this, serverEventStore);
        getServer().getPluginManager().registerEvents(serverEventService, this);
        UpdateNoticeStore updateNoticeStore;
        try {
            updateNoticeStore = new UpdateNoticeStore(getDataFolder().toPath().resolve("update-notices.json"));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not open update-notices.json", exception);
        }
        updateNotices = new UpdateNoticeService(
                this, updateNoticeStore, broadcastDisplayService
        );
        getServer().getPluginManager().registerEvents(updateNotices, this);
        PhantomService phantomService = new PhantomService(this);
        getServer().getPluginManager().registerEvents(phantomService, this);
        phantomService.start();
        afkService = new AfkService(
                this,
                getConfig().getLong("afk-timeout-seconds", 300L),
                getConfig().getBoolean("afk-invincible", true)
        );
        sidebarService.useAfkService(afkService);
        getServer().getPluginManager().registerEvents(afkService, this);
        if (getCommand("clans") == null
                || getCommand("claninfo") == null
                || getCommand("guide") == null
                || getCommand("perks") == null
                || getCommand("discord") == null
                || getCommand("discordnames") == null
                || getCommand("settings") == null
                || getCommand("mgxadmin") == null
                || getCommand("whitelisted") == null
                || getCommand("leaderboard") == null
                || getCommand("shop") == null
                || getCommand("sell") == null
                || getCommand("autosell") == null
                || getCommand("autopay") == null
                || getCommand("autobuy") == null
                || getCommand("ah") == null
                || getCommand("bal") == null
                || getCommand("pay") == null
                || getCommand("bounty") == null
                || getCommand("afk") == null
                || getCommand("crate") == null
                || getCommand("wardrobe") == null) {
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
        CosmeticItems cosmeticItems = new CosmeticItems(this);
        WardrobeService wardrobeService = new WardrobeService(
                this, cosmeticStore, cosmeticItems, settingsService
        );
        cosmeticEffects = new CosmeticEffectService(
                this, cosmeticStore, cosmeticItems, wardrobeService, playerSettings
        );
        SpecialItemService specialItems = new SpecialItemService(this);
        CrateItems crateItems = new CrateItems(this, cosmeticStore, specialItems);
        crates = new CrateService(
                this,
                crateStore,
                crateItems,
                cosmeticStore,
                cosmeticItems,
                cosmeticEffects,
                playerSettings,
                perkService,
                specialItems
        );
        getCommand("wardrobe").setExecutor(wardrobeService);
        getCommand("wardrobe").setTabCompleter(wardrobeService);
        getCommand("crate").setExecutor(crates);
        getCommand("crate").setTabCompleter(crates);
        getServer().getPluginManager().registerEvents(wardrobeService, this);
        getServer().getPluginManager().registerEvents(cosmeticEffects, this);
        getServer().getPluginManager().registerEvents(crates, this);
        getServer().getPluginManager().registerEvents(specialItems, this);
        getServer().getPluginManager().registerEvents(
                new TrophyHeadService(this, trophyHeadStore, playerSettings), this
        );
        WhitelistDirectoryService whitelistService = new WhitelistDirectoryService(whitelistDirectory, playerMenuService);
        getCommand("whitelisted").setExecutor(whitelistService);
        getCommand("whitelisted").setTabCompleter(whitelistService);
        HologramService holograms;
        BountyStore bountyStore;
        JoinGrantStore joinGrants;
        try {
            holograms = new HologramService(
                    getDataFolder().toPath().resolve("holograms.json"),
                    leaderboardService,
                    clanStore,
                    identityService
            );
            bountyStore = new BountyStore(getDataFolder().toPath().resolve("bounties.json"));
            joinGrants = new JoinGrantStore(getDataFolder().toPath().resolve("join-grants.json"));
        } catch (IOException exception) {
            getLogger().severe("MGXAccessBridge could not open holograms or bounties: "
                    + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        LeaderboardMenuService leaderboardMenus = new LeaderboardMenuService(
                this, clanStore, leaderboardService, identityService
        );
        getCommand("leaderboard").setExecutor(leaderboardMenus);
        getCommand("leaderboard").setTabCompleter(leaderboardMenus);
        getServer().getPluginManager().registerEvents(leaderboardMenus, this);
        AuctionStore auctionStore;
        try {
            auctionStore = new AuctionStore(getDataFolder().toPath().resolve("auctions.json"));
        } catch (IOException exception) {
            getLogger().severe("MGXAccessBridge could not open the auction house: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        economyMenus = new EconomyMenuService(
                this, economyStore, auctionStore, playerSettings, crateItems
        );
        wardrobeService.useAuctionHouse(economyMenus);
        economyMenus.useWardrobe(wardrobeService);
        EconomyCommandService economyCommands = new EconomyCommandService(economyStore);
        getCommand("shop").setExecutor(economyMenus);
        getCommand("shop").setTabCompleter(economyMenus);
        getCommand("autosell").setExecutor(economyMenus);
        getCommand("autosell").setTabCompleter(economyMenus);
        autoPayService = new AutoPayService(this, autoPayStore, economyStore);
        getServer().getPluginManager().registerEvents(autoPayService, this);
        getCommand("autopay").setExecutor(autoPayService);
        getCommand("autopay").setTabCompleter(autoPayService);
        getCommand("autobuy").setExecutor(economyMenus);
        getCommand("autobuy").setTabCompleter(economyMenus);
        getCommand("sell").setExecutor(economyMenus);
        getCommand("sell").setTabCompleter(economyMenus);
        getCommand("ah").setExecutor(economyMenus);
        getCommand("ah").setTabCompleter(economyMenus);
        getCommand("bal").setExecutor(economyCommands);
        getCommand("bal").setTabCompleter(economyCommands);
        getCommand("pay").setExecutor(economyCommands);
        getCommand("pay").setTabCompleter(economyCommands);
        BountyService bountyService = new BountyService(this, economyStore, bountyStore, clanStore);
        getCommand("bounty").setExecutor(bountyService);
        getCommand("bounty").setTabCompleter(bountyService);
        getCommand("afk").setExecutor(afkService);
        getServer().getPluginManager().registerEvents(bountyService, this);
        getServer().getPluginManager().registerEvents(
                new JoinGrantService(this, economyStore, bountyStore, joinGrants), this
        );
        getServer().getPluginManager().registerEvents(economyMenus, this);
        getServer().getScheduler().runTaskTimer(
                this, holograms::refresh, 220L, bridgeConfig.leaderboardRefreshTicks()
        );
        getServer().getScheduler().runTaskTimer(
                this, economyMenus::expireListings, 20L * 60L, 20L * 60L
        );
        // Standing orders repeat no faster than once a second, so checking once per
        // second avoids turning every hopper-fed farm into a per-tick entity scan.
        getServer().getScheduler().runTaskTimer(
                this, economyMenus::tickAutoOrders, 20L, 20L
        );
        // Two seconds: fast enough that a farm feels like it is selling itself, slow
        // enough that a running farm is one balance write rather than one per item.
        getServer().getScheduler().runTaskTimer(
                this, economyMenus::sweepAutoSell, 40L, 40L
        );
        devBlogService = new DevBlogService(
                this, devBlogStore, sidebarService, cosmeticStore
        );
        chaosService = new ChaosService(this, crateItems);
        getServer().getPluginManager().registerEvents(chaosService, this);
        AdminCommandService adminService = new AdminCommandService(
                this,
                rankSyncStore,
                economyStore,
                crateItems,
                cosmeticStore,
                cosmeticItems,
                bountyStore,
                joinGrants,
                holograms,
                new ServerDataResetService(
                        this,
                        clanStore,
                        wealthStore,
                        economyStore,
                        auctionStore,
                        bountyStore,
                        rankSyncStore,
                        identityStore,
                        playerSettings,
                        crateStore,
                        cosmeticStore,
                        cosmeticItems,
                        trophyHeadStore,
                        verifiedAccounts,
                        verificationEvents,
                        processed,
                        getServer().getWorlds().get(0).getWorldFolder().toPath(),
                        // Where the worlds live is also where whitelist.json and
                        // usercache.json sit, which is what the reset needs.
                        getServer().getWorldContainer().toPath()
                ),
                devBlogService,
                new AdminEventService(this, crateItems, chaosService),
                economyMenus,
                updateNotices
        );
        getCommand("mgxadmin").setExecutor(adminService);
        getCommand("mgxadmin").setTabCompleter(adminService);
        launchService = new LaunchService(this, getDataFolder().toPath());
        launchService.restoreOnEnable();
        crates.start();
        cosmeticEffects.start();
        afkService.start();
        serverEventService.start();
        autoPayService.start();
        // The blog decides when players are told there is something to read.
        blogWatchService = new BlogWatchService(
                this,
                updateNoticeStore,
                updateNotices,
                getConfig().getString("blog-latest-url", BlogWatchService.DEFAULT_MANIFEST_URL),
                getConfig().getLong("blog-poll-minutes", 5L)
        );
        if (getConfig().getBoolean("auto-update-notice", true)) {
            blogWatchService.start();
        }
        sidebarService.start();
        leaderboardService.start();
        capabilityService.start();
        bridgeClient.start();
        if (maintenanceHeld()) {
            getLogger().warning("Maintenance hold is active.");
        }
        // Event-based kicks are the polite path. Geyser can skip those events
        // and drop a kick issued during spawn, so a sweep is what actually
        // keeps a held server empty.
        maintenanceSweep = getServer().getScheduler().runTaskTimer(
                this, this::sweepMaintenance, MaintenanceGate.SWEEP_PERIOD_TICKS, MaintenanceGate.SWEEP_PERIOD_TICKS
        );
        // Worlds may not exist yet during onEnable on Paper; the next tick
        // and WorldLoadEvent both call lockWorldSpawn.
        getServer().getScheduler().runTask(this, this::lockLoadedWorldSpawns);
    }

    @Override
    public void onDisable() {
        // Before anything else: an operator event must never survive a reload.
        if (chaosService != null) {
            chaosService.stopAll();
        }
        if (autoPayService != null) {
            autoPayService.stop();
        }
        if (blogWatchService != null) {
            blogWatchService.stop();
        }
        if (serverEventService != null) {
            serverEventService.stop();
        }
        if (afkService != null) {
            afkService.stop();
        }
        if (broadcastDisplayService != null) {
            broadcastDisplayService.stop();
        }
        if (crates != null) {
            crates.stop();
        }
        if (cosmeticEffects != null) {
            cosmeticEffects.stop();
        }
        if (clanMenuService != null) {
            // Items sitting in an open donation window exist only in that inventory
            // object. Closing banks them; not closing loses them.
            clanMenuService.closeAll();
        }
        if (economyMenus != null) {
            economyMenus.closeAll();
            economyMenus.stopAutoOrders();
        }
        if (capabilityService != null) {
            capabilityService.stop();
        }
        if (leaderboardService != null) {
            leaderboardService.stop();
        }
        if (devBlogService != null) {
            devBlogService.endEverySession(getServer().getOnlinePlayers());
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
        if (maintenanceSweep != null) {
            maintenanceSweep.cancel();
            maintenanceSweep = null;
        }
        if (networkExecutor != null) {
            networkExecutor.shutdownNow();
        }
    }

    void applyPlayerProfile(org.bukkit.entity.Player player, PlayerProfile profile) {
        perkService.apply(player, profile);
        if (sidebarService != null) {
            sidebarService.refreshAll();
        }
    }

    BroadcastDisplayService broadcasts() {
        return broadcastDisplayService;
    }

    ServerEventService serverEvents() {
        return serverEventService;
    }

    /** 1 when the event is off, so every call site can multiply unconditionally. */
    int serverEventMultiplier(ServerEventType type) {
        return serverEventService == null ? 1 : serverEventService.multiplier(type);
    }

    AfkService afkService() {
        return afkService;
    }

    SidebarService sidebarService() {
        return sidebarService;
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
                boolean holdsVault = clan.map(view -> view.balance() > 0L).orElse(false);
                if (holdsVault) {
                    throw new ClanStore.ClanException(
                            "Your clan treasury still holds money. Disband in game with "
                                    + "/clans disband confirm. Donated money is destroyed."
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
        String safeDuration = StaffActionInput.sanitizeDuration(duration);
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
        Player online = getServer().getPlayer(actor);
        if (online != null && online.isOp()) {
            return java.util.concurrent.CompletableFuture.completedFuture(true);
        }
        if (online == null && getServer().getOfflinePlayer(actor).isOp()) {
            return java.util.concurrent.CompletableFuture.completedFuture(true);
        }
        if (luckPermsService == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        return luckPermsService.hasExplicitPermission(actor, permission);
    }

    /**
     * Console, {@code ops.json}, or an explicit LuckPerms grant of
     * {@code mgxaccessbridge.admin}. Bukkit {@code hasPermission} is not used:
     * Floodgate accounts inherit {@code default: op} nodes while {@code op=false}.
     */
    boolean mayAdminister(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        return player.isOp() || hasExplicitAdmin(player.getUniqueId());
    }

    /**
     * Drops the live Java and Floodgate whitelist, pending verifications, and
     * anyone online who is not exempt. Writing {@code whitelist.json} alone
     * leaves Paper's in-memory list (and Floodgate's own store) untouched.
     */
    int revokeLiveAccess() {
        int removed = 0;
        Set<OfflinePlayer> listed = new HashSet<>(getServer().getWhitelistedPlayers());
        for (OfflinePlayer player : listed) {
            if (player.isWhitelisted()) {
                player.setWhitelisted(false);
                removed++;
            }
            UUID id = player.getUniqueId();
            getServer().dispatchCommand(getServer().getConsoleSender(), "fwhitelist remove " + id);
        }
        getServer().reloadWhitelist();
        if (pending != null) {
            pending.replace(List.of());
        }
        for (Player online : getServer().getOnlinePlayers()) {
            if (bypassesMaintenance(online)) {
                continue;
            }
            online.kick(Component.text("Minecraft access was reset."));
        }
        return removed;
    }

    /**
     * Opens or closes the server. Anyone already on who is not exempt is kicked
     * when it closes, since holding it shut only for new logins leaves whoever was
     * online playing.
     */
    void startLaunch(org.bukkit.command.CommandSender sender) {
        if (launchService == null) {
            throw new IllegalStateException("Launch service is not ready.");
        }
        launchService.start(sender);
    }

    void startLaunchTest(org.bukkit.command.CommandSender sender) {
        if (launchService == null) {
            throw new IllegalStateException("Launch service is not ready.");
        }
        launchService.startTest(sender);
    }

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
                scheduleMaintenanceKick(player);
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
     * <p>Exempt means they are in {@code ops.json}, or LuckPerms has an
     * <em>explicit</em> {@code mgxaccessbridge.admin} node on them or a group
     * they inherit. Bukkit {@code hasPermission} and LuckPerms
     * {@code checkPermission} are not used: both honour {@code default: op},
     * which Floodgate users inherit, and that is what produced
     * {@code op=false bypass=true} for a default Bedrock account. There is no
     * dedicated maintenance bypass permission.
     */
    private boolean maintenanceHeld() {
        return maintenanceStore != null && maintenanceStore.enabled();
    }

    private boolean bypassesMaintenance(org.bukkit.entity.Player player) {
        return player.isOp() || hasExplicitAdmin(player.getUniqueId());
    }

    private boolean bypassesMaintenance(UUID uuid) {
        return getServer().getOfflinePlayer(uuid).isOp() || hasExplicitAdmin(uuid);
    }

    private boolean hasExplicitAdmin(UUID uuid) {
        if (luckPermsService == null) {
            return false;
        }
        if (luckPermsService.hasExplicitPermissionLoaded(uuid, AdminCommandService.PERMISSION)) {
            return true;
        }
        // Pre-login runs off the main thread and the user may not be cached yet.
        if (Bukkit.isPrimaryThread()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(
                    luckPermsService.hasExplicitPermission(uuid, AdminCommandService.PERMISSION).join()
            );
        } catch (RuntimeException exception) {
            getLogger().warning("LuckPerms explicit-permission lookup failed: "
                    + exception.getClass().getSimpleName());
            return false;
        }
    }

    private void kickUnlessExempt(org.bukkit.entity.Player player) {
        if (!player.isOnline() || bypassesMaintenance(player)) {
            return;
        }
        player.kick(verificationKicks.getOrDefault(player.getUniqueId(), CLOSED_MESSAGE));
    }

    private void sweepMaintenance() {
        if (!maintenanceHeld()) {
            return;
        }
        for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
            if (bypassesMaintenance(player)) {
                continue;
            }
            getLogger().warning("Sweep removed " + player.getName()
                    + ": the server is closed for maintenance.");
            player.kick(CLOSED_MESSAGE);
        }
    }

    /**
     * Immediate kick plus delayed retries. Java honours the first; Geyser drops
     * a kick issued while the Bedrock client is still completing spawn.
     */
    private void scheduleMaintenanceKick(org.bukkit.entity.Player player) {
        for (long delay : MaintenanceGate.JOIN_KICK_TICKS) {
            if (delay == 0L) {
                kickUnlessExempt(player);
            } else {
                getServer().getScheduler().runTaskLater(this, () -> kickUnlessExempt(player), delay);
            }
        }
    }

    /**
     * Floodgate's 1.21 login path calls this event and then starts client
     * verification. A hold that only refused {@code PlayerLoginEvent} left
     * Bedrock players — including a never-seen default account with no op, no
     * whitelist entry and no permission — walking into the world, because
     * Geyser never consulted that later event.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        AsyncPlayerPreLoginEvent.Result incoming = event.getLoginResult();
        if (!MaintenanceGate.isRefusable(
                incoming == AsyncPlayerPreLoginEvent.Result.ALLOWED,
                incoming == AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST
        )) {
            return;
        }
        // Match first. Floodgate consults this event and may never reach
        // PlayerLoginEvent once we rewrite the result to KICK_OTHER.
        Verdict verdict = handleVerification(event.getUniqueId(), event.getName());
        if (MaintenanceGate.shouldRefuse(maintenanceHeld(), bypassesMaintenance(event.getUniqueId()))) {
            getLogger().info("Refused " + event.getName() + " at pre-login: server held.");
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, CLOSED_MESSAGE);
            return;
        }
        if (verdict.allow()) {
            // Overrides the vanilla whitelist refusal for this one connection. The
            // durable APPROVE adds the real entry a moment later.
            event.allow();
            return;
        }
        // Only speak up when the whitelist already refused them. A member who is
        // whitelisted and unverified-by-our-records is an existing player, and
        // kicking them here would lock out everyone who predates this system.
        if (incoming == AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, verdict.kick());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    @SuppressWarnings("deprecation") // Floodgate can rewrite this legacy event after async pre-login.
    public void onPlayerLogin(PlayerLoginEvent event) {
        PlayerLoginEvent.Result result = event.getResult();
        // A ban or a full server is somebody else's refusal and carries a more
        // useful message than ours. They are not getting in either way.
        boolean whitelistKick = result == PlayerLoginEvent.Result.KICK_WHITELIST;
        boolean held = maintenanceHeld();
        if (!MaintenanceGate.isRefusable(
                result == PlayerLoginEvent.Result.ALLOWED,
                whitelistKick
        ) && !(held && result == PlayerLoginEvent.Result.KICK_OTHER)) {
            return;
        }

        // Verification is matched before the hold is applied, so a held server
        // still verifies accounts even though nobody gets in. During a hold,
        // pre-login already rewrote the result to KICK_OTHER — still match here
        // for Java, which honours this event, so a Floodgate re-allow cannot skip
        // the queue.
        Verdict verdict = handleVerification(
                event.getPlayer().getUniqueId(),
                event.getPlayer().getName()
        );

        if (MaintenanceGate.shouldRefuse(held, bypassesMaintenance(event.getPlayer()))) {
            if (result == PlayerLoginEvent.Result.ALLOWED) {
                getLogger().info("Refused " + event.getPlayer().getName() + " after login.");
            }
            // Rewriting the result to KICK_OTHER is what makes the hold hold.
            // Floodgate re-allows Bedrock players by looking for KICK_WHITELIST, so
            // leaving that result in place let every Bedrock login walk through.
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, CLOSED_MESSAGE);
            return;
        }
        if (verdict.allow()) {
            event.allow();
            return;
        }
        if (whitelistKick) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, verdict.kick());
        }
    }

    /**
     * Last look at the login result. Floodgate (and anything else that rewrites
     * it after {@link EventPriority#HIGHEST}) is why a hold that stopped here
     * used to let Bedrock through. MONITOR is not supposed to mutate events;
     * mutating it is the only way to undo a re-allow that happens after us.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    @SuppressWarnings("deprecation") // Deliberate final guard against Floodgate re-allowing the login.
    public void onPlayerLoginMonitor(PlayerLoginEvent event) {
        if (!MaintenanceGate.shouldRefuse(maintenanceHeld(), bypassesMaintenance(event.getPlayer()))) {
            return;
        }
        if (event.getResult() == PlayerLoginEvent.Result.ALLOWED) {
            event.disallow(
                    PlayerLoginEvent.Result.KICK_OTHER,
                    verificationKicks.getOrDefault(event.getPlayer().getUniqueId(), CLOSED_MESSAGE)
            );
        }
    }

    /**
     * Matches a whitelist-refused login against the pending verifications.
     *
     * @return the message this login has earned, or null when there was nothing to
     *         say — which leaves the refusal exactly as it was found.
     */
    private Verdict handleVerification(UUID uuid, String loginName) {
        VerificationIdentity.Resolved identity = resolveConnectingIdentity(uuid, loginName);
        Optional<PendingVerification> match = pending.matchLogin(loginName);
        if (match.isEmpty() && identity.username() != null && !identity.username().equals(loginName)) {
            match = pending.matchLogin(identity.username());
        }
        if (match.isEmpty()) {
            // Already verified, but the whitelist has not arrived yet. The store is
            // written the moment a verification is queued and survives a restart,
            // so this is what stops a reconnect in that window being turned away.
            if (verifiedAccounts.find(uuid).isPresent()
                    || (identity.uuid() != null && verifiedAccounts.find(identity.uuid()).isPresent())) {
                return Verdict.allowed();
            }
            getLogger().info("No pending verification for " + loginName
                    + " (cache=" + pending.size()
                    + " names=" + pending.snapshotNames() + ")");
            verificationKicks.put(uuid, NOT_VERIFIED_MESSAGE);
            return Verdict.refuse(NOT_VERIFIED_MESSAGE);
        }
        MinecraftEdition edition = identity.edition();
        String xuid = identity.xuid();
        UUID accountId = identity.uuid() != null ? identity.uuid() : uuid;
        if (edition == MinecraftEdition.BEDROCK && (xuid == null || xuid.isBlank())) {
            getLogger().warning("Matched " + loginName
                    + " but have no Floodgate XUID; not sending a Bedrock verification.");
            verificationKicks.put(uuid, UNAVAILABLE_MESSAGE);
            return Verdict.refuse(UNAVAILABLE_MESSAGE);
        }
        if (bridgeClient.queueVerification(
                match.get(),
                edition,
                accountId,
                identity.username(),
                xuid
        )) {
            // Verification is the whole gate, so clearing it is the same moment as
            // being let in. The durable APPROVE that follows adds the real
            // whitelist entry; this connection does not wait for it.
            verificationKicks.remove(uuid);
            getLogger().info("Verified " + loginName + " at login; letting them in.");
            return Verdict.allowed();
        }
        verificationKicks.put(uuid, UNAVAILABLE_MESSAGE);
        return Verdict.refuse(UNAVAILABLE_MESSAGE);
    }

    private VerificationIdentity.Resolved resolveConnectingIdentity(UUID uuid, String loginName) {
        VerificationIdentity.Resolved identity = VerificationIdentity.resolve(uuid, loginName);
        try {
            FloodgateApi api = FloodgateApi.getInstance();
            boolean floodgateId = false;
            try {
                floodgateId = api.isFloodgateId(uuid);
            } catch (RuntimeException ignored) {
                floodgateId = VerificationIdentity.isFloodgateUuid(uuid);
            }
            if (floodgateId && identity.edition() != MinecraftEdition.BEDROCK) {
                identity = new VerificationIdentity.Resolved(
                        MinecraftEdition.BEDROCK,
                        VerificationIdentity.bedrockUsername(loginName),
                        VerificationIdentity.xuidFromFloodgateUuid(uuid),
                        uuid
                );
            }
            FloodgatePlayer floodgatePlayer = api.getPlayer(uuid);
            if (floodgatePlayer != null) {
                return new VerificationIdentity.Resolved(
                        MinecraftEdition.BEDROCK,
                        firstNonBlank(
                                floodgatePlayer.getCorrectUsername(),
                                floodgatePlayer.getUsername(),
                                identity.username()
                        ),
                        firstNonBlank(floodgatePlayer.getXuid(), identity.xuid()),
                        floodgatePlayer.getCorrectUniqueId() != null
                                ? floodgatePlayer.getCorrectUniqueId()
                                : uuid
                );
            }
            // Not on the main thread. This is a network round trip to Floodgate's
            // account service and PlayerLoginEvent is synchronous, so waiting here
            // freezes the whole server — every player, not just this one — for up to
            // five seconds. Pre-login is asynchronous and is where the lookup belongs;
            // by the time the synchronous event runs, an answer was already found or
            // there was never going to be one. Same rule as hasExplicitAdmin.
            if (identity.edition() == MinecraftEdition.BEDROCK
                    && (identity.xuid() == null || identity.xuid().isBlank())
                    && !Bukkit.isPrimaryThread()) {
                String lookupName = VerificationIdentity.bedrockUsername(
                        identity.username().isBlank() ? loginName : identity.username()
                );
                Long lookedUp = api.getXuidFor(lookupName).get(5, java.util.concurrent.TimeUnit.SECONDS);
                if (lookedUp != null) {
                    return new VerificationIdentity.Resolved(
                            MinecraftEdition.BEDROCK,
                            lookupName,
                            Long.toUnsignedString(lookedUp),
                            api.createJavaPlayerId(lookedUp)
                    );
                }
            }
        } catch (Exception exception) {
            getLogger().warning("Floodgate lookup failed during account verification: "
                    + exception.getClass().getSimpleName());
        }
        return identity;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private void lockLoadedWorldSpawns() {
        for (World world : getServer().getWorlds()) {
            lockWorldSpawn(world);
            applyWorldMemory(world);
            applyWorldLimits(world);
            ensureHostileSpawns(world);
            disableLocatorBar(world);
        }
    }

    private void lockWorldSpawn(World world) {
        if (world.getEnvironment() != World.Environment.NORMAL) {
            return;
        }
        Location current = world.getSpawnLocation();
        if (!WorldSpawn.isExact(current.getBlockX(), current.getBlockY(), current.getBlockZ())) {
            world.setSpawnLocation(WorldSpawn.X, WorldSpawn.Y, WorldSpawn.Z);
            getLogger().info("World spawn locked to "
                    + WorldSpawn.X + " " + WorldSpawn.Y + " " + WorldSpawn.Z + ".");
        }
        Integer radius = world.getGameRuleValue(GameRules.RESPAWN_RADIUS);
        if (radius == null || radius != WorldSpawn.RADIUS) {
            world.setGameRule(GameRules.RESPAWN_RADIUS, WorldSpawn.RADIUS);
        }
    }

    private void applyWorldMemory(World world) {
        int view = WorldMemory.capDistance(
                world.getViewDistance(),
                getConfig().getInt("world.max-view-distance", WorldMemory.MAX_VIEW_DISTANCE)
        );
        if (view != world.getViewDistance()) {
            world.setViewDistance(view);
            getLogger().info("Capped view distance in " + world.getName() + " to " + view + ".");
        }
        int simulation = WorldMemory.capSimulation(
                world.getSimulationDistance(),
                getConfig().getInt("world.max-simulation-distance", WorldMemory.MAX_SIMULATION_DISTANCE),
                view
        );
        if (simulation != world.getSimulationDistance()) {
            world.setSimulationDistance(simulation);
            getLogger().info("Capped simulation distance in " + world.getName()
                    + " to " + simulation + ".");
        }
    }

    private void applyWorldLimits(World world) {
        double radius = getConfig().getDouble("world.border-radius", WorldLimits.OVERWORLD_RADIUS);
        if (radius < 0) {
            return;
        }
        boolean nether = world.getEnvironment() == World.Environment.NETHER;
        double size = WorldLimits.diameter(nether, radius);
        org.bukkit.WorldBorder border = world.getWorldBorder();
        if (border.getCenter().getX() != 0.5 || border.getCenter().getZ() != 0.5) {
            border.setCenter(0.5, 0.5);
        }
        if (Math.abs(border.getSize() - size) > 0.5) {
            border.setSize(size);
            getLogger().info("Set the world border in " + world.getName()
                    + " to " + (int) (size / 2) + " blocks from spawn.");
        }
        if (border.getWarningDistance() != WorldLimits.WARNING_DISTANCE) {
            border.setWarningDistance(WorldLimits.WARNING_DISTANCE);
        }
        pinSpawnChunks(world);
    }

    /**
     * A 3x3 around spawn, not the vanilla spawn-chunk neighborhood. Death far
     * from 0,0 still has somewhere loaded to land; the rest of the world can
     * unload.
     */
    private void pinSpawnChunks(World world) {
        if (world.getEnvironment() != World.Environment.NORMAL) {
            return;
        }
        int cx = WorldLimits.spawnChunkX();
        int cz = WorldLimits.spawnChunkZ();
        int reach = WorldLimits.SPAWN_TICKET_RADIUS;
        for (int x = -reach; x <= reach; x++) {
            for (int z = -reach; z <= reach; z++) {
                world.addPluginChunkTicket(cx + x, cz + z, this);
            }
        }
    }

    private void loadChunk(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        location.getWorld().getChunkAt(location);
    }

    private void ensureHostileSpawns(World world) {
        if (world.getEnvironment() == World.Environment.THE_END) {
            return;
        }
        Boolean spawning = world.getGameRuleValue(GameRules.SPAWN_MOBS);
        if (spawning == null || !spawning) {
            world.setGameRule(GameRules.SPAWN_MOBS, true);
            getLogger().info("Enabled mob spawning in " + world.getName() + ".");
        }
        if (world.getDifficulty() == Difficulty.PEACEFUL) {
            world.setDifficulty(Difficulty.NORMAL);
            getLogger().info("Set " + world.getName() + " difficulty to normal.");
        }
    }

    private void disableLocatorBar(World world) {
        Boolean enabled = world.getGameRuleValue(GameRules.LOCATOR_BAR);
        if (enabled == null || enabled) {
            world.setGameRule(GameRules.LOCATOR_BAR, false);
            getLogger().info("Disabled the player locator bar in " + world.getName() + ".");
        }
    }

    private Location exactWorldSpawn(World world) {
        return new Location(world, WorldSpawn.X + 0.5, WorldSpawn.Y, WorldSpawn.Z + 0.5);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        lockWorldSpawn(event.getWorld());
        applyWorldMemory(event.getWorld());
        applyWorldLimits(event.getWorld());
        ensureHostileSpawns(event.getWorld());
        disableLocatorBar(event.getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(org.bukkit.event.player.PlayerPortalEvent event) {
        if (event.getTo() != null
                && event.getTo().getWorld() != null
                && event.getTo().getWorld().getEnvironment() == World.Environment.THE_END) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(org.bukkit.event.entity.EntityPortalEvent event) {
        if (event.getTo() != null
                && event.getTo().getWorld() != null
                && event.getTo().getWorld().getEnvironment() == World.Environment.THE_END) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawnChange(SpawnChangeEvent event) {
        lockWorldSpawn(event.getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        Location loc = event.getSpawnLocation();
        World world = loc.getWorld();
        if (world == null || world.getEnvironment() != World.Environment.NORMAL) {
            return;
        }
        Location spawn = exactWorldSpawn(world);
        if (loc.getWorld().equals(world) && loc.distanceSquared(spawn) <= 256.0) {
            event.setSpawnLocation(spawn);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Location bed = event.getPlayer().getRespawnLocation();
        if (bed != null) {
            loadChunk(bed);
        }
        World overworld = getServer().getWorlds().isEmpty() ? null : getServer().getWorlds().get(0);
        if (overworld != null) {
            loadChunk(exactWorldSpawn(overworld));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (event.isBedSpawn() || event.isAnchorSpawn()) {
            loadChunk(event.getRespawnLocation());
            return;
        }
        World world = event.getRespawnLocation().getWorld();
        if (world == null || world.getEnvironment() != World.Environment.NORMAL) {
            world = getServer().getWorlds().isEmpty() ? null : getServer().getWorlds().get(0);
        }
        if (world != null) {
            Location spawn = exactWorldSpawn(world);
            loadChunk(spawn);
            event.setRespawnLocation(spawn);
        }
    }

    /**
     * Last line of the maintenance hold.
     *
     * <p>The login refusal is the polite path, but Geyser can ignore it and spawn
     * anyway. Anybody who reaches the world is removed here, with delayed retries
     * because a kick issued during this event is dropped for Bedrock.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMaintenanceJoin(PlayerJoinEvent event) {
        org.bukkit.entity.Player player = event.getPlayer();
        boolean held = maintenanceHeld();
        boolean bypass = bypassesMaintenance(player);
        if (held) {
            getLogger().info("Join " + player.getName()
                    + " uuid=" + player.getUniqueId()
                    + " op=" + player.isOp()
                    + " bypass=" + bypass);
        }
        if (!MaintenanceGate.shouldRefuse(held, bypass)) {
            return;
        }
        getLogger().warning("Removed " + player.getName()
                + " after login: the server is closed for maintenance.");
        scheduleMaintenanceKick(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (devBlogService != null) {
            // A crash or restart can leave a session open; this is
            // where the belongings come back.
            devBlogService.restoreIfStranded(event.getPlayer());
        }
        scheduleBedrockTerrainResync(event.getPlayer());
        Player player = event.getPlayer();
        PlayerConnectionIdentity identity = resolveConnectionIdentity(
                player.getUniqueId(), player.getName()
        );
        connectionIdentities.put(player.getUniqueId(), identity);
        queuePlayerActivity(identity, true, getServer().getOnlinePlayers().size());
        // Refresh the cached leave identity once Floodgate has had time to expose
        // the real gamertag. The UUID fallback already records this join as Bedrock.
        getServer().getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            connectionIdentities.put(player.getUniqueId(), resolveConnectionIdentity(
                    player.getUniqueId(), player.getName()
            ));
        }, 10L);
    }

    /**
     * Geyser can complete spawn before its first terrain batch reaches a console
     * client, leaving already-built chunks rendered as empty void until reconnect.
     * Resending the visible 3x3 after spawn settles repairs the client view without
     * changing or regenerating a single server block.
     */
    private void scheduleBedrockTerrainResync(Player player) {
        if (!isBedrockPlayer(player)) {
            return;
        }
        for (long delay : List.of(30L, 100L)) {
            getServer().getScheduler().runTaskLater(this, () -> {
                if (!player.isOnline()) {
                    return;
                }
                World world = player.getWorld();
                int centreX = player.getLocation().getBlockX() >> 4;
                int centreZ = player.getLocation().getBlockZ() >> 4;
                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        world.getChunkAt(centreX + x, centreZ + z);
                        world.refreshChunk(centreX + x, centreZ + z);
                    }
                }
            }, delay);
        }
    }

    private static boolean isBedrockPlayer(Player player) {
        try {
            return FloodgateApi.getInstance().getPlayer(player.getUniqueId()) != null;
        } catch (RuntimeException ignored) {
            return VerificationIdentity.isFloodgateUuid(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        verificationKicks.remove(event.getPlayer().getUniqueId());
        if (devBlogService != null) {
            // Restores their belongings before they leave, so logging out of
            // screenshot mode is not a way to lose an inventory.
            devBlogService.endSession(event.getPlayer());
            devBlogService.forget(event.getPlayer().getUniqueId());
        }
        if (sidebarService != null) {
            sidebarService.forget(event.getPlayer().getUniqueId());
        }
        PlayerConnectionIdentity identity = connectionIdentities.remove(event.getPlayer().getUniqueId());
        if (identity == null) {
            identity = resolveConnectionIdentity(
                    event.getPlayer().getUniqueId(), event.getPlayer().getName()
            );
        }
        queuePlayerActivity(identity, false, Math.max(0, getServer().getOnlinePlayers().size() - 1));
    }

    private PlayerConnectionIdentity resolveConnectionIdentity(UUID uuid, String javaUsername) {
        FloodgatePlayer floodgatePlayer;
        try {
            floodgatePlayer = FloodgateApi.getInstance().getPlayer(uuid);
        } catch (RuntimeException exception) {
            getLogger().warning("Floodgate lookup failed for player activity: "
                    + exception.getClass().getSimpleName());
            floodgatePlayer = null;
        }
        if (floodgatePlayer != null) {
            return new PlayerConnectionIdentity(
                    MinecraftEdition.BEDROCK,
                    uuid,
                    floodgatePlayer.getUsername(),
                    String.valueOf(floodgatePlayer.getXuid())
            );
        }
        if (VerificationIdentity.isFloodgateUuid(uuid)) {
            String username = javaUsername.startsWith(".") ? javaUsername.substring(1) : javaUsername;
            return new PlayerConnectionIdentity(
                    MinecraftEdition.BEDROCK,
                    uuid,
                    username,
                    VerificationIdentity.xuidFromFloodgateUuid(uuid)
            );
        }
        return new PlayerConnectionIdentity(MinecraftEdition.JAVA, uuid, javaUsername, null);
    }

    private void queuePlayerActivity(
            PlayerConnectionIdentity identity, boolean joined, int onlineCount
    ) {
        bridgeClient.queuePlayerActivity(
                joined,
                identity.edition(),
                identity.uuid(),
                identity.username(),
                identity.xuid(),
                onlineCount,
                System.currentTimeMillis() / 1_000L
        );
    }

    private record PlayerConnectionIdentity(
            MinecraftEdition edition, UUID uuid, String username, String xuid
    ) {
    }
}
