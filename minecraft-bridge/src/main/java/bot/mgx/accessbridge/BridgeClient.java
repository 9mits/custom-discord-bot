package bot.mgx.accessbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class BridgeClient implements WebSocket.Listener, AutoCloseable {
    /**
     * Advertised in the HELLO handshake. The bot gates each capability on this
     * number, so it must be raised whenever the plugin learns to handle a new
     * field. Keep in step with the *_PROTOCOL_VERSION constants in
     * {@code minecraft_bot/bridge.py}. 5 added the SYNC_PROFILE rank fields;
     * 6 added the SYNC_WHITELIST directory snapshot; 7 added SERVER_EVENT, which
     * reports in-game actions to the Discord activity log; 8 added
     * SET_MAINTENANCE, which holds the server closed before launch.
     */
    static final int PROTOCOL_VERSION = 8;

    private final MGXAccessBridge plugin;
    private final BridgeConfig config;
    private final PendingVerificationCache pending;
    private final SignedProtocol protocol;
    private final ProcessedActionStore processedActions;
    private final ScheduledExecutorService networkExecutor;
    private final HttpClient httpClient;
    private final VerificationEventStore verificationOutbox;
    private final VerifiedApplicationStore verifiedApplications;
    private final ConcurrentHashMap<String, PlayerActivity> playerActivityOutbox = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JsonObject> minecraftChatOutbox = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JsonObject> serverEventOutbox = new ConcurrentHashMap<>();
    private final StringBuilder inbound = new StringBuilder();
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    private volatile WebSocket socket;
    private volatile boolean authenticated;
    private volatile boolean stopping;
    private volatile int reconnectAttempts;

    BridgeClient(
            MGXAccessBridge plugin,
            BridgeConfig config,
            PendingVerificationCache pending,
            ProcessedActionStore processedActions,
            VerificationEventStore verificationOutbox,
            VerifiedApplicationStore verifiedApplications,
            ScheduledExecutorService networkExecutor
    ) {
        this.plugin = plugin;
        this.config = config;
        this.pending = pending;
        this.processedActions = processedActions;
        this.verificationOutbox = verificationOutbox;
        this.verifiedApplications = verifiedApplications;
        this.networkExecutor = networkExecutor;
        this.protocol = new SignedProtocol(config.secret());
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .executor(networkExecutor);
        if (config.certificateSha256() != null) {
            httpClientBuilder.sslContext(BridgeTls.pinnedSslContext(config.certificateSha256()));
            plugin.getLogger().warning(
                    "Bridge TLS certificate pin is active; update it whenever the certificate rotates."
            );
        }
        this.httpClient = httpClientBuilder.build();
    }

    void start() {
        connect();
        networkExecutor.scheduleAtFixedRate(this::heartbeat, 15, 15, TimeUnit.SECONDS);
    }

    boolean isConnected() {
        return authenticated && socket != null && !socket.isOutputClosed();
    }

    private void connect() {
        WebSocket current = socket;
        if (stopping || (current != null && !current.isOutputClosed()) || !connecting.compareAndSet(false, true)) {
            return;
        }
        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(config.bridgeUri(), this)
                .whenComplete((connected, error) -> {
                    connecting.set(false);
                    if (error != null) {
                        plugin.getLogger().warning("Minecraft bridge connection failed: " + safeError(error));
                        scheduleReconnect();
                    } else {
                        socket = connected;
                    }
                });
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        this.socket = webSocket;
        this.authenticated = false;
        JsonObject hello = new JsonObject();
        hello.addProperty("server_id", config.serverId());
        hello.addProperty("protocol_version", PROTOCOL_VERSION);
        sendRaw(protocol.create("HELLO", UUID.randomUUID().toString(), hello));
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        synchronized (inbound) {
            inbound.append(data);
            if (inbound.length() > 1024 * 1024) {
                inbound.setLength(0);
                webSocket.sendClose(1009, "Message too large");
                return null;
            }
            if (!last) {
                webSocket.request(1);
                return null;
            }
            String message = inbound.toString();
            inbound.setLength(0);
            networkExecutor.execute(() -> handleMessage(message));
        }
        webSocket.request(1);
        return null;
    }

    private void handleMessage(String text) {
        JsonObject envelope;
        try {
            envelope = protocol.verify(text);
        } catch (SecurityException exception) {
            plugin.getLogger().warning("Rejected an invalid signed bridge message: " + exception.getMessage());
            WebSocket current = socket;
            if (current != null) {
                current.sendClose(1008, "Invalid signed message");
            }
            return;
        }
        try {
            String type = envelope.get("type").getAsString();
            JsonObject payload = envelope.getAsJsonObject("payload");
            if (!authenticated && !"HELLO_ACK".equals(type)) {
                plugin.getLogger().warning("Ignored " + type + " before HELLO_ACK.");
                return;
            }
            switch (type) {
                case "HELLO_ACK" -> {
                    if (!config.serverId().equals(payload.get("server_id").getAsString())) {
                        closeSocket(1008, "Server ID mismatch");
                        return;
                    }
                    authenticated = true;
                    reconnectAttempts = 0;
                    plugin.getLogger().info("Connected to the signed Minecraft application bridge.");
                    flushVerificationOutbox();
                    flushPlayerActivityOutbox();
                    flushMinecraftChatOutbox();
                    flushServerEventOutbox();
                    // The bot holds standings in memory, so a restart leaves it with none.
                    // Republish at once rather than making it wait for the next interval.
                    plugin.republishLeaderboard();
                    plugin.republishCapabilities();
                }
                case "HEARTBEAT_ACK" -> {
                    // The signed response is sufficient proof of liveness.
                }
                case "VERIFICATION_ACK" -> {
                    String key = envelope.get("idempotency_key").getAsString();
                    JsonObject verification = verificationOutbox.remove(key);
                    if (verification != null) {
                        pending.remove(verification.get("application_id").getAsLong());
                    }
                }
                case "PLAYER_EVENT_ACK" -> {
                    String key = payload.get("event_idempotency_key").getAsString();
                    playerActivityOutbox.remove(key);
                }
                case "MINECRAFT_CHAT_ACK" -> {
                    String key = payload.get("event_idempotency_key").getAsString();
                    minecraftChatOutbox.remove(key);
                }
                case "SERVER_EVENT_ACK" -> {
                    String key = payload.get("event_idempotency_key").getAsString();
                    serverEventOutbox.remove(key);
                }
                case "ACTION" -> processAction(envelope.get("idempotency_key").getAsString(), payload);
                default -> plugin.getLogger().warning("Ignored unsupported bridge message type: " + type);
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Bridge message failed: " + exception.getMessage());
        }
    }

    private void processAction(String idempotencyKey, JsonObject payload) {
        String action;
        try {
            action = payload.get("action").getAsString();
        } catch (RuntimeException exception) {
            recordAndSend(idempotencyKey, new ProcessedActionStore.Result(false, "Action is missing"));
            return;
        }
        if (action.equals("SYNC_PROFILE")) {
            Bukkit.getScheduler().runTask(
                    plugin,
                    () -> executeProfileSync(idempotencyKey, payload)
            );
            return;
        }
        if (action.equals("DISCORD_CHAT")) {
            Bukkit.getScheduler().runTask(
                    plugin,
                    () -> executeDiscordChat(idempotencyKey, payload)
            );
            return;
        }
        if (action.equals("SET_MAINTENANCE")) {
            try {
                if (!payload.has("enabled") || !payload.get("enabled").isJsonPrimitive()) {
                    recordAndSend(idempotencyKey, new ProcessedActionStore.Result(false, "enabled is required"));
                    return;
                }
                plugin.setMaintenance(payload.get("enabled").getAsBoolean());
                recordAndSend(idempotencyKey, new ProcessedActionStore.Result(true, ""));
            } catch (RuntimeException exception) {
                recordAndSend(idempotencyKey, new ProcessedActionStore.Result(false, safeError(exception)));
            }
            return;
        }
        if (action.equals("SYNC_WHITELIST")) {
            // Fire-and-forget like SYNC_PROFILE: the next snapshot replaces this
            // one, so there is nothing to deduplicate.
            executeWhitelistSync(idempotencyKey, payload);
            return;
        }
        if (action.equals("SYNC_PENDING")) {
            try {
                applyPendingSync(payload);
                recordAndSend(idempotencyKey, new ProcessedActionStore.Result(true, ""));
            } catch (RuntimeException exception) {
                recordAndSend(idempotencyKey, new ProcessedActionStore.Result(false, safeError(exception)));
            }
            return;
        }
        Optional<ProcessedActionStore.Result> existing = processedActions.get(idempotencyKey);
        if (existing.isPresent()) {
            sendActionResult(idempotencyKey, existing.get());
            return;
        }
        if (!processedActions.reserve(idempotencyKey)) {
            processedActions.get(idempotencyKey).ifPresent(result -> sendActionResult(idempotencyKey, result));
            return;
        }
        switch (action) {
            case "REMOVE_PENDING" -> {
                long applicationId = payload.get("application_id").getAsLong();
                pending.remove(applicationId);
                verifiedApplications.remove(applicationId);
                recordAndSend(idempotencyKey, new ProcessedActionStore.Result(true, ""));
            }
            case "CLAN_ACTION" -> Bukkit.getScheduler().runTask(
                    plugin,
                    () -> executeClanAction(idempotencyKey, payload)
            );
            case "STAFF_ACTION" -> Bukkit.getScheduler().runTask(
                    plugin,
                    () -> executeStaffAction(idempotencyKey, payload)
            );
            case "APPROVE", "REVOKE", "KICK", "STATUS" -> Bukkit.getScheduler().runTask(
                    plugin,
                    () -> executeMainThreadAction(idempotencyKey, action, payload)
            );
            default -> recordAndSend(
                    idempotencyKey,
                    new ProcessedActionStore.Result(false, "Action is not allowlisted")
            );
        }
    }

    /**
     * Runs a clan action on the requester's behalf.
     *
     * <p>{@link ClanStore} enforces who may do what, so this never has to trust the
     * bot's view of a player's role — a stale menu in Discord cannot grant anything.
     */
    private void executeClanAction(String key, JsonObject payload) {
        try {
            UUID actor = UUID.fromString(payload.get("actor_uuid").getAsString());
            String action = payload.get("clan_action").getAsString();
            String argument = optionalString(payload, "argument");
            String outcome = plugin.runClanAction(actor, action, argument);
            recordAndSend(key, new ProcessedActionStore.Result(true, outcome));
        } catch (ClanStore.ClanException exception) {
            // A refusal is a real answer for the player, not a bridge failure.
            recordAndSend(key, new ProcessedActionStore.Result(false, exception.getMessage()));
        } catch (RuntimeException | java.io.IOException exception) {
            recordAndSend(key, new ProcessedActionStore.Result(false, safeError(exception)));
        }
    }

    /**
     * Runs a staff tool on the requester's behalf.
     *
     * <p>{@link MGXAccessBridge#runStaffAction} re-checks the actor's permission through
     * LuckPerms before dispatching anything — the payload's own claim is never trusted.
     * The check is async because it may need to load an offline player's data, so the
     * result completes later rather than returning here.
     */
    private void executeStaffAction(String key, JsonObject payload) {
        try {
            UUID actor = UUID.fromString(payload.get("actor_uuid").getAsString());
            String tool = payload.get("tool").getAsString();
            String target = optionalString(payload, "target");
            String reason = optionalString(payload, "reason");
            String duration = optionalString(payload, "duration");
            plugin.runStaffAction(actor, tool, target, reason, duration)
                    .whenComplete((outcome, error) -> {
                        if (error == null) {
                            recordAndSend(key, new ProcessedActionStore.Result(true, outcome));
                            return;
                        }
                        // CompletableFuture wraps async failures in CompletionException;
                        // unwrap so a refusal is a clean message rather than a nested one.
                        Throwable cause = error instanceof java.util.concurrent.CompletionException
                                ? error.getCause()
                                : error;
                        String message = cause instanceof StaffActionException
                                ? cause.getMessage()
                                : safeError(cause);
                        recordAndSend(key, new ProcessedActionStore.Result(false, message));
                    });
        } catch (StaffActionException exception) {
            // A refusal thrown before dispatch is a real answer for the requester, not
            // an internal failure — relay it without the exception class name.
            recordAndSend(key, new ProcessedActionStore.Result(false, exception.getMessage()));
        } catch (RuntimeException exception) {
            recordAndSend(key, new ProcessedActionStore.Result(false, safeError(exception)));
        }
    }

    private void executeProfileSync(String key, JsonObject payload) {
        try {
            UUID minecraftUuid = UUID.fromString(payload.get("minecraft_uuid").getAsString());
            PlayerProfile profile = new PlayerProfile(
                    payload.get("level").getAsInt(),
                    payload.get("extra_hearts").getAsInt(),
                    payload.get("elite").getAsBoolean(),
                    optionalString(payload, "rank_group"),
                    optionalString(payload, "rank_label"),
                    payload.has("rank_colour") ? payload.get("rank_colour").getAsInt() : 0,
                    payload.has("rank_weight") ? payload.get("rank_weight").getAsInt() : 0,
                    payload.has("booster") && payload.get("booster").getAsBoolean()
            );
            // Older bots omit the rank fields entirely; only touch LuckPerms when they are sent.
            if (payload.has("rank_group")) {
                plugin.applyPlayerRank(minecraftUuid, profile.rankGroup());
            }
            Player online = Bukkit.getPlayer(minecraftUuid);
            // Present-and-empty means the bot knows this player has no linked account,
            // so the cached name is stale and must go. The field is omitted entirely
            // when the bot could not work out the link state, which is why absence and
            // emptiness cannot be treated the same: clearing on a failed lookup would
            // drop everyone's name the first time Discord was slow.
            if (payload.has("discord_username") && !payload.get("discord_username").isJsonNull()) {
                String username = payload.get("discord_username").getAsString();
                if (username.isBlank()) {
                    plugin.forgetDiscordIdentity(minecraftUuid);
                } else {
                    plugin.applyDiscordIdentity(minecraftUuid, username);
                }
            }
            if (online != null) {
                plugin.applyPlayerProfile(online, profile);
            }
            sendActionResult(key, new ProcessedActionStore.Result(true, ""));
        } catch (RuntimeException exception) {
            sendActionResult(key, new ProcessedActionStore.Result(false, safeError(exception)));
        }
    }

    private void executeWhitelistSync(String key, JsonObject payload) {
        try {
            ArrayList<WhitelistDirectory.Entry> entries = new ArrayList<>();
            JsonArray players = payload.getAsJsonArray("players");
            if (players != null) {
                for (JsonElement element : players) {
                    JsonObject player = element.getAsJsonObject();
                    entries.add(new WhitelistDirectory.Entry(
                            optionalString(player, "username"),
                            optionalString(player, "edition"),
                            optionalString(player, "discord_username")
                    ));
                }
            }
            plugin.applyWhitelistDirectory(entries);
            sendActionResult(key, new ProcessedActionStore.Result(true, ""));
        } catch (RuntimeException exception) {
            sendActionResult(key, new ProcessedActionStore.Result(false, safeError(exception)));
        }
    }

    private void applyPendingSync(JsonObject payload) {
        if (payload.has("full") && payload.get("full").getAsBoolean()) {
            JsonArray applications = payload.getAsJsonArray("applications");
            ArrayList<PendingVerification> replacements = new ArrayList<>();
            for (JsonElement element : applications) {
                replacements.add(parsePending(element.getAsJsonObject()));
            }
            pending.replace(replacements);
        } else {
            pending.put(parsePending(payload));
        }
    }

    private PendingVerification parsePending(JsonObject payload) {
        long maximumExpiry = java.time.Instant.now().getEpochSecond() + config.verificationExpirySeconds();
        return new PendingVerification(
                payload.get("application_id").getAsLong(),
                MinecraftEdition.valueOf(payload.get("edition").getAsString()),
                payload.get("claimed_username").getAsString(),
                payload.get("normalized_username").getAsString(),
                Math.min(payload.get("expires_at").getAsLong(), maximumExpiry)
        );
    }

    private void executeMainThreadAction(String key, String action, JsonObject payload) {
        if (!Bukkit.isPrimaryThread()) {
            recordAndSend(key, new ProcessedActionStore.Result(false, "Bukkit action was not on the main thread"));
            return;
        }
        try {
            UUID minecraftUuid = payload.has("minecraft_uuid")
                    ? UUID.fromString(payload.get("minecraft_uuid").getAsString())
                    : null;
            switch (action) {
                case "APPROVE" -> {
                    MinecraftEdition edition = MinecraftEdition.valueOf(payload.get("edition").getAsString());
                    if (minecraftUuid == null) {
                        throw new IllegalArgumentException("APPROVE requires a UUID");
                    }
                    if (edition == MinecraftEdition.JAVA) {
                        OfflinePlayer player = Bukkit.getOfflinePlayer(minecraftUuid);
                        player.setWhitelisted(true);
                        if (!player.isWhitelisted()) {
                            throw new IllegalStateException("Bukkit did not confirm the whitelist change");
                        }
                    } else {
                        boolean accepted = Bukkit.dispatchCommand(
                                Bukkit.getConsoleSender(),
                                "fwhitelist add " + minecraftUuid
                        );
                        if (!accepted) {
                            throw new IllegalStateException("Floodgate rejected the whitelist command");
                        }
                    }
                    verifiedApplications.remove(payload.get("application_id").getAsLong());
                }
                case "REVOKE" -> {
                    MinecraftEdition edition = MinecraftEdition.valueOf(payload.get("edition").getAsString());
                    if (minecraftUuid == null) {
                        throw new IllegalArgumentException("REVOKE requires a UUID");
                    }
                    if (edition == MinecraftEdition.JAVA) {
                        Bukkit.getOfflinePlayer(minecraftUuid).setWhitelisted(false);
                    } else {
                        boolean accepted = Bukkit.dispatchCommand(
                                Bukkit.getConsoleSender(),
                                "fwhitelist remove " + minecraftUuid
                        );
                        if (!accepted) {
                            throw new IllegalStateException("Floodgate rejected the whitelist command");
                        }
                    }
                    Player online = Bukkit.getPlayer(minecraftUuid);
                    if (online != null) {
                        online.kick(net.kyori.adventure.text.Component.text("Your Minecraft access was revoked."));
                    }
                }
                case "KICK" -> {
                    if (minecraftUuid == null) {
                        throw new IllegalArgumentException("KICK requires a UUID");
                    }
                    Player online = Bukkit.getPlayer(minecraftUuid);
                    if (online != null) {
                        online.kick(net.kyori.adventure.text.Component.text("Disconnected by the access system."));
                    }
                }
                case "STATUS" -> {
                    // Reaching the main thread successfully is the status result.
                }
                default -> throw new IllegalArgumentException("Action is not allowlisted");
            }
            recordAndSend(key, new ProcessedActionStore.Result(true, ""));
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Bridge action " + action + " failed: " + safeError(exception));
            recordAndSend(key, new ProcessedActionStore.Result(false, safeError(exception)));
        }
    }

    private void executeDiscordChat(String key, JsonObject payload) {
        try {
            UUID minecraftUuid = payload.has("minecraft_uuid")
                    ? UUID.fromString(payload.get("minecraft_uuid").getAsString())
                    : null;
            String discordUsername = payload.get("discord_username").getAsString().trim();
            String minecraftUsername = payload.has("minecraft_username")
                    ? payload.get("minecraft_username").getAsString().trim()
                    : null;
            String message = payload.get("message").getAsString();
            if (discordUsername.isEmpty() || discordUsername.length() > 32) {
                throw new IllegalArgumentException("DISCORD_CHAT has an invalid username");
            }
            if (message.length() > 2_000) {
                throw new IllegalArgumentException("DISCORD_CHAT message is too long");
            }
            if (minecraftUsername != null && (minecraftUsername.isEmpty() || minecraftUsername.length() > 32)) {
                throw new IllegalArgumentException("DISCORD_CHAT has an invalid Minecraft username");
            }
            int attachmentCount = payload.has("attachment_count")
                    ? Math.max(0, Math.min(payload.get("attachment_count").getAsInt(), 10))
                    : 0;
            String attachmentUrl = payload.has("attachment_url")
                    ? payload.get("attachment_url").getAsString()
                    : null;
            plugin.broadcastDiscordChat(
                    discordUsername,
                    minecraftUsername,
                    message,
                    attachmentCount,
                    attachmentUrl
            );
            sendActionResult(key, new ProcessedActionStore.Result(true, ""));
        } catch (RuntimeException exception) {
            sendActionResult(key, new ProcessedActionStore.Result(false, safeError(exception)));
        }
    }

    boolean queueVerification(
            PendingVerification application,
            MinecraftEdition edition,
            UUID minecraftUuid,
            String currentUsername,
            String xuid
    ) {
        String key = "verification:" + application.applicationId() + ":" + minecraftUuid;
        JsonObject payload = new JsonObject();
        payload.addProperty("application_id", application.applicationId());
        payload.addProperty("edition", edition.name());
        payload.addProperty("minecraft_uuid", minecraftUuid.toString());
        payload.addProperty("current_username", currentUsername);
        if (xuid == null) {
            payload.add("xuid", null);
        } else {
            payload.addProperty("xuid", xuid);
        }
        try {
            verifiedApplications.put(
                    application.applicationId(), minecraftUuid, currentUsername
            );
            verificationOutbox.put(key, payload);
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Could not persist verification event: " + safeError(exception));
            return false;
        }
        if (isConnected()) {
            sendRaw(protocol.create("VERIFICATION", key, payload));
        }
        return true;
    }

    private void flushVerificationOutbox() {
        for (Map.Entry<String, JsonObject> entry : verificationOutbox.snapshot().entrySet()) {
            sendRaw(protocol.create("VERIFICATION", entry.getKey(), entry.getValue()));
        }
    }

    void queuePlayerActivity(
            boolean joined,
            MinecraftEdition edition,
            UUID minecraftUuid,
            String currentUsername,
            String xuid
    ) {
        if (playerActivityOutbox.size() >= 10_000) {
            playerActivityOutbox.keySet().stream().findFirst().ifPresent(playerActivityOutbox::remove);
        }
        String key = "player:" + UUID.randomUUID();
        JsonObject payload = new JsonObject();
        payload.addProperty("edition", edition.name());
        payload.addProperty("minecraft_uuid", minecraftUuid.toString());
        payload.addProperty("current_username", currentUsername);
        if (xuid == null) {
            payload.add("xuid", null);
        } else {
            payload.addProperty("xuid", xuid);
        }
        playerActivityOutbox.put(key, new PlayerActivity(joined, payload));
        if (isConnected()) {
            sendRaw(protocol.create(joined ? "PLAYER_JOIN" : "PLAYER_LEAVE", key, payload));
        }
    }

    private void flushPlayerActivityOutbox() {
        for (Map.Entry<String, PlayerActivity> entry : playerActivityOutbox.entrySet()) {
            PlayerActivity event = entry.getValue();
            sendRaw(protocol.create(
                    event.joined() ? "PLAYER_JOIN" : "PLAYER_LEAVE",
                    entry.getKey(),
                    event.payload()
            ));
        }
    }

    private record PlayerActivity(boolean joined, JsonObject payload) {
    }

    void queueMinecraftChat(
            MinecraftEdition edition,
            UUID minecraftUuid,
            String currentUsername,
            String message
    ) {
        if (minecraftChatOutbox.size() >= 1_000) {
            minecraftChatOutbox.keySet().stream().findFirst().ifPresent(minecraftChatOutbox::remove);
        }
        String key = "minecraft-chat:" + UUID.randomUUID();
        JsonObject payload = new JsonObject();
        payload.addProperty("edition", edition.name());
        payload.addProperty("minecraft_uuid", minecraftUuid.toString());
        payload.addProperty("current_username", currentUsername);
        payload.addProperty("message", message);
        minecraftChatOutbox.put(key, payload);
        if (isConnected()) {
            sendRaw(protocol.create("MINECRAFT_CHAT", key, payload));
        }
    }

    /**
     * Pushes a leaderboard snapshot. Deliberately fire-and-forget with no outbox:
     * a dropped snapshot is replaced by the next one a few minutes later, so
     * retaining stale standings would be worse than losing them.
     */
    void sendLeaderboardSnapshot(JsonObject snapshot) {
        if (!isConnected()) {
            return;
        }
        sendRaw(protocol.create("LEADERBOARD_SNAPSHOT", UUID.randomUUID().toString(), snapshot));
    }

    /** Fire-and-forget like standings: the next push replaces anything dropped. */
    void sendCapabilitySnapshot(JsonObject snapshot) {
        if (!isConnected()) {
            return;
        }
        sendRaw(protocol.create("CAPABILITY_SNAPSHOT", UUID.randomUUID().toString(), snapshot));
    }

    private void flushMinecraftChatOutbox() {
        for (Map.Entry<String, JsonObject> entry : minecraftChatOutbox.entrySet()) {
            sendRaw(protocol.create("MINECRAFT_CHAT", entry.getKey(), entry.getValue()));
        }
    }

    /**
     * Reports something a player did in game so it reaches the Discord activity log.
     *
     * <p>Outboxed rather than fire-and-forget: an audit line that vanishes because the
     * bot happened to be restarting is worse than one that arrives late. The cap is
     * generous but finite, so a long outage cannot grow the heap without bound.
     */
    void queueServerEvent(ServerEvent event) {
        if (serverEventOutbox.size() >= 5_000) {
            serverEventOutbox.keySet().stream().findFirst().ifPresent(serverEventOutbox::remove);
        }
        String key = "server-event:" + UUID.randomUUID();
        serverEventOutbox.put(key, event.toPayload());
        if (isConnected()) {
            sendRaw(protocol.create("SERVER_EVENT", key, serverEventOutbox.get(key)));
        }
    }

    private void flushServerEventOutbox() {
        for (Map.Entry<String, JsonObject> entry : serverEventOutbox.entrySet()) {
            sendRaw(protocol.create("SERVER_EVENT", entry.getKey(), entry.getValue()));
        }
    }

    private void recordAndSend(String key, ProcessedActionStore.Result result) {
        processedActions.put(key, result).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().warning("Could not persist bridge idempotency result: " + safeError(error));
                return;
            }
            sendActionResult(key, result);
        });
    }

    private void sendActionResult(String actionKey, ProcessedActionStore.Result result) {
        JsonObject payload = new JsonObject();
        payload.addProperty("action_idempotency_key", actionKey);
        payload.addProperty("success", result.success());
        // Success outcomes carry text too ("Ran on the server: ..."). Older bots only
        // read "error", so failures keep that field alongside the newer "message".
        payload.addProperty("message", result.error());
        if (!result.success()) {
            payload.addProperty("error", result.error());
        }
        sendRaw(protocol.create("ACTION_RESULT", UUID.randomUUID().toString(), payload));
    }

    private void heartbeat() {
        if (!isConnected()) {
            return;
        }
        flushVerificationOutbox();
        flushPlayerActivityOutbox();
        flushMinecraftChatOutbox();
        flushServerEventOutbox();
        JsonObject payload = new JsonObject();
        payload.addProperty("server_id", config.serverId());
        payload.addProperty("pending_count", pending.size());
        sendRaw(protocol.create("HEARTBEAT", UUID.randomUUID().toString(), payload));
    }

    private void sendRaw(String message) {
        WebSocket current = socket;
        if (current == null || current.isOutputClosed()) {
            return;
        }
        current.sendText(message, true).whenComplete((ignored, error) -> {
            if (error != null && config.debug()) {
                plugin.getLogger().warning("Bridge send failed: " + safeError(error));
            }
        });
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        authenticated = false;
        socket = null;
        if (!stopping) {
            plugin.getLogger().warning("Minecraft bridge disconnected; reconnecting automatically.");
            scheduleReconnect();
        }
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        authenticated = false;
        socket = null;
        if (!stopping) {
            plugin.getLogger().warning("Minecraft bridge error: " + safeError(error));
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (stopping) {
            return;
        }
        int attempt = Math.min(++reconnectAttempts, 20);
        long base = Math.min(config.reconnectMaxSeconds(), 1L << Math.min(attempt - 1, 16));
        long delayMillis = base * 1000L + ThreadLocalRandom.current().nextLong(250, 1250);
        networkExecutor.schedule(this::connect, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void closeSocket(int code, String reason) {
        WebSocket current = socket;
        if (current != null) {
            current.sendClose(code, reason);
        }
    }

    private static String optionalString(JsonObject payload, String key) {
        return payload.has(key) && !payload.get(key).isJsonNull()
                ? payload.get(key).getAsString()
                : "";
    }

    private static String safeError(Throwable error) {
        Throwable candidate = error;
        while (candidate.getCause() != null) {
            candidate = candidate.getCause();
        }
        String name = candidate.getClass().getSimpleName();
        String message = candidate.getMessage();
        return message == null || message.isBlank() ? name : name + ": " + message.replace('\n', ' ');
    }

    @Override
    public void close() {
        stopping = true;
        authenticated = false;
        WebSocket current = socket;
        socket = null;
        if (current != null && !current.isOutputClosed()) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "Plugin shutting down");
        }
    }
}
