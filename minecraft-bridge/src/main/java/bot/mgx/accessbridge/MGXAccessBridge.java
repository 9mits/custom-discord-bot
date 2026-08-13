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

    private ScheduledExecutorService networkExecutor;
    private BridgeClient bridgeClient;
    private PendingVerificationCache pending;

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
        try {
            Path resultFile = getDataFolder().toPath().resolve("processed-actions.properties");
            processed = new ProcessedActionStore(resultFile, networkExecutor);
            verificationEvents = new VerificationEventStore(
                    getDataFolder().toPath().resolve("verification-events.json")
            );
        } catch (IOException exception) {
            getLogger().severe("MGXAccessBridge could not open its idempotency store: " + exception.getMessage());
            networkExecutor.shutdownNow();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        bridgeClient = new BridgeClient(
                this, bridgeConfig, pending, processed, verificationEvents, networkExecutor
        );
        getServer().getPluginManager().registerEvents(this, this);
        bridgeClient.start();
    }

    @Override
    public void onDisable() {
        if (bridgeClient != null) {
            bridgeClient.close();
        }
        if (networkExecutor != null) {
            networkExecutor.shutdownNow();
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
            event.kickMessage(VERIFICATION_HELP_MESSAGE);
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
