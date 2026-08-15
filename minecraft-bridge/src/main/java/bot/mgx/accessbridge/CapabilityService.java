package bot.mgx.accessbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Tells the bot what each player is allowed to do, so Discord can offer exactly that.
 *
 * <p>Permissions live in LuckPerms on this side, so the bot cannot work them out alone.
 * This pushes them the same way standings are pushed: periodically and on reconnect,
 * with the bot caching the newest. Staleness only affects what Discord *offers* — the
 * server still checks permission when a command actually runs.
 */
final class CapabilityService {
    private final MGXAccessBridge plugin;
    private final BridgeClient bridge;
    private final ClanStore clans;
    private final LuckPermsService luckPerms;
    private final long refreshTicks;
    private int taskId = -1;

    CapabilityService(
            MGXAccessBridge plugin,
            BridgeClient bridge,
            ClanStore clans,
            LuckPermsService luckPerms,
            long refreshTicks
    ) {
        this.plugin = plugin;
        this.bridge = bridge;
        this.clans = clans;
        this.luckPerms = luckPerms;
        this.refreshTicks = refreshTicks;
    }

    void start() {
        taskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
                plugin,
                this::publish,
                200L,
                refreshTicks
        );
    }

    void stop() {
        if (taskId >= 0) {
            plugin.getServer().getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    void publishNow() {
        publish();
    }

    /**
     * Online players are resolved synchronously here, on the main thread their
     * permissions actually live on. Offline players need LuckPerms' storage, which is
     * async, so those finish later and the snapshot is sent once every future settles.
     */
    private void publish() {
        List<UUID> offlineTargets = new ArrayList<>();
        JsonObject players = new JsonObject();

        for (Player online : plugin.getServer().getOnlinePlayers()) {
            players.add(online.getUniqueId().toString(), describeOnline(online));
        }
        for (ClanStore.ClanView clan : clans.list()) {
            for (UUID member : clan.members().keySet()) {
                if (!players.has(member.toString()) && !offlineTargets.contains(member)) {
                    offlineTargets.add(member);
                }
            }
        }

        if (offlineTargets.isEmpty() || luckPerms == null) {
            for (UUID uuid : offlineTargets) {
                players.add(uuid.toString(), describeOffline(uuid, List.of()));
            }
            send(players);
            return;
        }

        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (UUID uuid : offlineTargets) {
            List<String> held = new ArrayList<>();
            List<CompletableFuture<Void>> checks = new ArrayList<>();
            for (StaffTools.StaffTool tool : StaffTools.ALL) {
                checks.add(
                        luckPerms.hasPermission(uuid, tool.permission())
                                .thenAccept(allowed -> {
                                    if (allowed) {
                                        synchronized (held) {
                                            held.add(tool.key());
                                        }
                                    }
                                })
                );
            }
            pending.add(
                    CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new))
                            .thenRun(() -> players.add(uuid.toString(), describeOffline(uuid, held)))
            );
        }

        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                .thenRun(() -> plugin.getServer().getScheduler().runTask(plugin, () -> send(players)))
                .exceptionally(error -> {
                    plugin.getLogger().warning("Could not resolve offline staff permissions: " + error);
                    plugin.getServer().getScheduler().runTask(plugin, () -> send(players));
                    return null;
                });
    }

    private void send(JsonObject players) {
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("generated_at", System.currentTimeMillis());
        snapshot.add("players", players);
        bridge.sendCapabilitySnapshot(snapshot);
    }

    private JsonObject describeOnline(Player online) {
        UUID playerId = online.getUniqueId();
        JsonObject entry = clanFields(playerId);
        JsonArray tools = new JsonArray();
        for (StaffTools.StaffTool tool : StaffTools.ALL) {
            if (online.hasPermission(tool.permission())) {
                tools.add(tool.key());
            }
        }
        entry.add("staff_tools", tools);
        return entry;
    }

    private JsonObject describeOffline(UUID playerId, List<String> held) {
        JsonObject entry = clanFields(playerId);
        JsonArray tools = new JsonArray();
        held.forEach(tools::add);
        entry.add("staff_tools", tools);
        return entry;
    }

    private JsonObject clanFields(UUID playerId) {
        JsonObject entry = new JsonObject();
        Optional<ClanStore.ClanView> clan = clans.clanOf(playerId);
        clan.ifPresent(view -> {
            entry.addProperty("clan", view.name());
            entry.addProperty("clan_colour", view.themeColor());
            entry.addProperty("clan_role", clanRole(view, playerId));
            entry.addProperty("clan_members", view.members().size());
            entry.addProperty("clan_level", view.level());
        });
        return entry;
    }

    private static String clanRole(ClanStore.ClanView clan, UUID playerId) {
        if (clan.leader().equals(playerId)) {
            return "leader";
        }
        return clan.staff().contains(playerId) ? "staff" : "member";
    }
}
