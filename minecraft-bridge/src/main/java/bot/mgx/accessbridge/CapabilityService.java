package bot.mgx.accessbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final AtomicBoolean publishing = new AtomicBoolean();
    private int taskId = -1;

    private record OfflineCapabilities(UUID playerId, List<String> tools) {
    }

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
        if (!publishing.compareAndSet(false, true)) {
            return;
        }
        Set<UUID> offlineTargets = new LinkedHashSet<>();
        JsonObject players = new JsonObject();
        try {
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                players.add(online.getUniqueId().toString(), describeOnline(online));
            }
            for (ClanStore.ClanView clan : clans.list()) {
                for (UUID member : clan.members().keySet()) {
                    if (!players.has(member.toString())) {
                        offlineTargets.add(member);
                    }
                }
            }

            if (offlineTargets.isEmpty() || luckPerms == null) {
                for (UUID uuid : offlineTargets) {
                    players.add(uuid.toString(), describeOffline(uuid, List.of()));
                }
                send(players);
                publishing.set(false);
                return;
            }

            List<CompletableFuture<OfflineCapabilities>> pending = new ArrayList<>();
            for (UUID uuid : offlineTargets) {
                boolean operator = plugin.getServer().getOfflinePlayer(uuid).isOp();
                pending.add(resolveOffline(uuid, operator));
            }

            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                    .whenComplete((ignored, error) -> finishPublish(players, pending, error));
        } catch (RuntimeException exception) {
            publishing.set(false);
            throw exception;
        }
    }

    private CompletableFuture<OfflineCapabilities> resolveOffline(UUID uuid, boolean operator) {
        if (operator) {
            return CompletableFuture.completedFuture(new OfflineCapabilities(
                    uuid,
                    StaffTools.ALL.stream().map(StaffTools.StaffTool::key).toList()
            ));
        }
        List<CompletableFuture<Boolean>> checks = StaffTools.ALL.stream()
                .map(tool -> luckPerms.hasExplicitPermission(uuid, tool.permission()))
                .toList();
        return CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    List<String> held = new ArrayList<>();
                    for (int index = 0; index < checks.size(); index++) {
                        if (checks.get(index).join()) {
                            held.add(StaffTools.ALL.get(index).key());
                        }
                    }
                    return new OfflineCapabilities(uuid, List.copyOf(held));
                });
    }

    private void finishPublish(
            JsonObject players,
            List<CompletableFuture<OfflineCapabilities>> pending,
            Throwable error
    ) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    if (error != null) {
                        plugin.getLogger().warning(
                                "Could not resolve every offline staff permission: " + error.getMessage()
                        );
                    }
                    for (CompletableFuture<OfflineCapabilities> future : pending) {
                        if (future.isCompletedExceptionally() || future.isCancelled()) {
                            continue;
                        }
                        OfflineCapabilities resolved = future.getNow(null);
                        if (resolved != null) {
                            players.add(
                                    resolved.playerId().toString(),
                                    describeOffline(resolved.playerId(), resolved.tools())
                            );
                        }
                    }
                    send(players);
                } finally {
                    publishing.set(false);
                }
            });
        } catch (RuntimeException exception) {
            publishing.set(false);
            plugin.getLogger().warning("Could not finish capability snapshot: " + exception.getMessage());
        }
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
        boolean operator = online.isOp();
        for (StaffTools.StaffTool tool : StaffTools.ALL) {
            if (operator
                    || (luckPerms != null
                    && luckPerms.hasExplicitPermissionLoaded(playerId, tool.permission()))) {
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
            entry.addProperty("clan_balance", view.balance());
            entry.addProperty("clan_member_slots", view.memberSlots());
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
