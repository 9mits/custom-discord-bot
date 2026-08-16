package bot.mgx.accessbridge;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.node.types.WeightNode;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Applies Discord-derived ranks as LuckPerms groups.
 *
 * <p>Only groups in {@link #MANAGED_GROUPS} are ever added or removed, so permissions
 * assigned by hand in LuckPerms survive a sync. That list must stay in step with
 * {@code RANK_ROLES} in {@code minecraft_bot/perks.py}.
 *
 * <p>Within those groups, sync removes only the one it granted itself, recorded in
 * {@link RankSyncStore}. Clearing every managed group meant a rank set by hand — an
 * owner given in game rather than through Discord — was wiped by the next sync, since
 * Discord's answer for that player is "no rank". A player put on hold is skipped
 * entirely, which is the way to keep a rank Discord will never agree with.
 */
final class LuckPermsService {
    static final Set<String> MANAGED_GROUPS = Set.of(
            "owner",
            "admin",
            "community-manager",
            "staff",
            "legend",
            "og",
            "supporter",
            "partner",
            "booster"
    );

    private final MGXAccessBridge plugin;
    private final LuckPerms luckPerms;
    private final RankSyncStore rankSync;

    private LuckPermsService(MGXAccessBridge plugin, LuckPerms luckPerms, RankSyncStore rankSync) {
        this.plugin = plugin;
        this.luckPerms = luckPerms;
        this.rankSync = rankSync;
    }

    /** Returns null when LuckPerms is not installed, which keeps it an optional dependency. */
    static LuckPermsService createIfAvailable(MGXAccessBridge plugin, RankSyncStore rankSync) {
        try {
            RegisteredServiceProvider<LuckPerms> provider =
                    Bukkit.getServicesManager().getRegistration(LuckPerms.class);
            if (provider == null) {
                plugin.getLogger().info("LuckPerms was not found; Discord rank sync is inactive.");
                return null;
            }
            plugin.getLogger().info("LuckPerms detected; Discord rank sync is active.");
            return new LuckPermsService(plugin, provider.getProvider(), rankSync);
        } catch (NoClassDefFoundError | RuntimeException exception) {
            plugin.getLogger().info("LuckPerms was not found; Discord rank sync is inactive.");
            return null;
        }
    }

    /** Whether the LuckPerms service is present at all, so callers can degrade cleanly. */
    boolean available() {
        return true;
    }

    /** Nodes that should stay on every player even if LuckPerms strips plugin defaults. */
    void grantEveryoneDefaults() {
        luckPerms.getGroupManager().modifyGroup("default", group -> {
            group.data().add(Node.builder("voicechat.listen").value(true).build());
            group.data().add(Node.builder("voicechat.speak").value(true).build());
            group.data().add(Node.builder("voicechat.groups").value(true).build());
            group.data().add(Node.builder("mgx.clans").value(true).build());
            group.data().add(Node.builder("mgx.leaderboard").value(true).build());
        }).thenRun(() -> plugin.getLogger().info("Default players can use voice chat, clans and the leaderboard."))
                .exceptionally(throwable -> {
                    plugin.getLogger().log(Level.WARNING, "Could not grant default player permissions", throwable);
                    return null;
                });
        makeOwnerCosmetic();
    }

    /** Prefix only. No wildcards, no WorldGuard bypass, no keep-inventory, no gamemode. */
    void makeOwnerCosmetic() {
        luckPerms.getGroupManager().modifyGroup("owner", group -> {
            group.data().clear();
            group.data().add(InheritanceNode.builder("default").build());
            group.data().add(PrefixNode.builder("&c[OWNER] ", 100).build());
            group.data().add(WeightNode.builder(100).build());
            group.data().add(Node.builder("worldguard.region.bypass.*").value(false).build());
            group.data().add(Node.builder("essentials.keepinv").value(false).build());
            group.data().add(Node.builder("essentials.gamemode").value(false).build());
            group.data().add(Node.builder("essentials.gamemode.*").value(false).build());
        }).thenRun(() -> plugin.getLogger().info("LuckPerms owner is cosmetic only."))
                .exceptionally(throwable -> {
                    plugin.getLogger().log(Level.WARNING, "Could not reset the owner group", throwable);
                    return null;
                });
    }

    /**
     * Whether a player — online or not — holds a permission, per LuckPerms' own
     * cached data. This is the authoritative check for remote Discord actions:
     * {@code Player#hasPermission} only reflects the current session, but staff
     * running a command from Discord are usually not logged in at the time.
     */
    CompletableFuture<Boolean> hasPermission(UUID playerId, String permission) {
        return luckPerms.getUserManager().loadUser(playerId)
                .thenApply(user -> user.getCachedData()
                        .getPermissionData()
                        .checkPermission(permission)
                        .asBoolean())
                .exceptionally(error -> false);
    }

    /**
     * Whether LuckPerms actually granted {@code permission} — a node on the user
     * or a group they inherit, not Bukkit's {@code default: op}.
     *
     * <p>{@code checkPermission} includes that Bukkit default, which is how a
     * Floodgate account with no groups reported {@code bypass=true} while
     * {@code op=false}. Only an explicit grant counts for the maintenance hold.
     */
    static boolean grants(String key, boolean value, String permission) {
        return value && key.equalsIgnoreCase(permission);
    }

    static boolean anyNodeGrants(Iterable<? extends Node> nodes, String permission) {
        for (Node node : nodes) {
            if (grants(node.getKey(), node.getValue(), permission)) {
                return true;
            }
        }
        return false;
    }

    boolean hasExplicitPermissionLoaded(UUID playerId, String permission) {
        User user = luckPerms.getUserManager().getUser(playerId);
        if (user == null) {
            return false;
        }
        return anyNodeGrants(user.resolveInheritedNodes(user.getQueryOptions()), permission);
    }

    CompletableFuture<Boolean> hasExplicitPermission(UUID playerId, String permission) {
        return luckPerms.getUserManager().loadUser(playerId)
                .thenApply(user -> anyNodeGrants(
                        user.resolveInheritedNodes(user.getQueryOptions()), permission
                ))
                .exceptionally(error -> false);
    }

    /** Drops every stored node so the player sits on the default group only. */
    CompletableFuture<Void> resetToDefaultGroup(UUID playerId) {
        return luckPerms.getUserManager().modifyUser(playerId, user -> user.data().clear())
                .exceptionally(error -> {
                    plugin.getLogger().log(Level.WARNING, "Could not reset LuckPerms user " + playerId, error);
                    return null;
                });
    }

    /**
     * Applies {@code group} to the player, giving up only the group sync itself last
     * granted. An empty group withdraws that grant, which is what happens when a member
     * loses their Discord rank role; groups set by hand are left where they are.
     */
    void applyRank(UUID minecraftUuid, String group) {
        String desired = group == null ? "" : group.trim();
        if (!desired.isEmpty() && !MANAGED_GROUPS.contains(desired)) {
            plugin.getLogger().warning("Ignoring unknown Discord rank group: " + desired);
            return;
        }
        if (rankSync.isHeld(minecraftUuid)) {
            return;
        }
        String previous = rankSync.appliedRank(minecraftUuid).orElse("");
        if (previous.equals(desired)) {
            // Re-adding an unchanged grant would churn the LuckPerms file on every join.
            return;
        }
        luckPerms.getUserManager().modifyUser(minecraftUuid, user -> {
                    if (rankSync.isHeld(minecraftUuid)) {
                        return;
                    }
                    mutate(user, previous, desired);
                })
                .thenRun(() -> {
                    if (rankSync.isHeld(minecraftUuid)) {
                        return;
                    }
                    rankSync.recordApplied(minecraftUuid, desired);
                })
                .exceptionally(throwable -> {
                    plugin.getLogger().log(
                            Level.WARNING,
                            "Could not apply a Discord rank for " + minecraftUuid,
                            throwable
                    );
                    return null;
                });
    }

    private void mutate(User user, String previous, String desired) {
        if (!previous.isEmpty()) {
            user.data().clear(node -> {
                if (node.getType() != NodeType.INHERITANCE) {
                    return false;
                }
                String name = NodeType.INHERITANCE.cast(node).getGroupName();
                return name.equals(previous) && !name.equals(desired);
            });
        }
        if (!desired.isEmpty()) {
            user.data().add(InheritanceNode.builder(desired).build());
        }
    }
}
