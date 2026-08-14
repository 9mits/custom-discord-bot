package bot.mgx.accessbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tells the bot what each player is allowed to do, so Discord can offer exactly that.
 *
 * <p>Permissions live in LuckPerms on this side, so the bot cannot work them out alone.
 * This pushes them the same way standings are pushed: periodically and on reconnect,
 * with the bot caching the newest. Staleness only affects what Discord *offers* — the
 * server still checks permission when a command actually runs.
 */
final class CapabilityService {
    /** Staff tools worth surfacing in Discord, each with the permission that runs it. */
    private static final List<String[]> STAFF_TOOLS = List.of(
            new String[]{"inspect", "coreprotect.inspect"},
            new String[]{"lookup", "coreprotect.lookup"},
            new String[]{"rollback", "coreprotect.rollback"},
            new String[]{"restore", "coreprotect.restore"},
            new String[]{"alerts", "grim.alerts"},
            new String[]{"heal", "essentials.heal"},
            new String[]{"god", "essentials.god"},
            new String[]{"invsee", "essentials.invsee"},
            new String[]{"vanish", "essentials.vanish"},
            new String[]{"kick", "essentials.kick"},
            new String[]{"mute", "essentials.mute"},
            new String[]{"ban", "essentials.ban"},
            new String[]{"tempban", "essentials.tempban"},
            new String[]{"unban", "essentials.unban"},
            new String[]{"broadcast", "essentials.broadcast"},
            new String[]{"gamemode", "essentials.gamemode"}
    );

    private final MGXAccessBridge plugin;
    private final BridgeClient bridge;
    private final ClanStore clans;
    private final long refreshTicks;
    private int taskId = -1;

    CapabilityService(
            MGXAccessBridge plugin,
            BridgeClient bridge,
            ClanStore clans,
            long refreshTicks
    ) {
        this.plugin = plugin;
        this.bridge = bridge;
        this.clans = clans;
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
     * Permission lookups need the main thread for online players, so the whole pass
     * runs here. It only walks players the server already knows, which is cheap.
     */
    private void publish() {
        JsonObject players = new JsonObject();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            players.add(online.getUniqueId().toString(), describe(online.getUniqueId(), online));
        }
        for (ClanStore.ClanView clan : clans.list()) {
            for (UUID member : clan.members().keySet()) {
                String key = member.toString();
                if (!players.has(key)) {
                    players.add(key, describe(member, null));
                }
            }
        }
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("generated_at", System.currentTimeMillis());
        snapshot.add("players", players);
        bridge.sendCapabilitySnapshot(snapshot);
    }

    private JsonObject describe(UUID playerId, Player online) {
        JsonObject entry = new JsonObject();
        Optional<ClanStore.ClanView> clan = clans.clanOf(playerId);
        clan.ifPresent(view -> {
            entry.addProperty("clan", view.name());
            entry.addProperty("clan_colour", view.themeColor());
            entry.addProperty("clan_role", clanRole(view, playerId));
            entry.addProperty("clan_members", view.members().size());
        });
        JsonArray tools = new JsonArray();
        for (String[] tool : STAFF_TOOLS) {
            if (holds(playerId, online, tool[1])) {
                tools.add(tool[0]);
            }
        }
        entry.add("staff_tools", tools);
        return entry;
    }

    private static String clanRole(ClanStore.ClanView clan, UUID playerId) {
        if (clan.leader().equals(playerId)) {
            return "leader";
        }
        return clan.staff().contains(playerId) ? "staff" : "member";
    }

    /**
     * Offline players are checked through their effective permissions, so a moderator
     * still sees their tools in Discord while they are not playing.
     */
    private boolean holds(UUID playerId, Player online, String permission) {
        if (online != null) {
            return online.hasPermission(permission);
        }
        try {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(playerId);
            return offline.isOp() || permissionDefaultsTrue(permission);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** A permission every player holds is not worth hiding behind a rank. */
    private boolean permissionDefaultsTrue(String permission) {
        Permission registered = Bukkit.getPluginManager().getPermission(permission);
        return registered != null && registered.getDefault().getValue(false);
    }
}
