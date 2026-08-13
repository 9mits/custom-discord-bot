package bot.mgx.accessbridge;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Applies Discord-derived ranks as LuckPerms groups.
 *
 * <p>Only groups in {@link #MANAGED_GROUPS} are ever added or removed, so permissions
 * assigned by hand in LuckPerms survive a sync. That list must stay in step with
 * {@code RANK_ROLES} in {@code minecraft_bot/perks.py}.
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

    private LuckPermsService(MGXAccessBridge plugin, LuckPerms luckPerms) {
        this.plugin = plugin;
        this.luckPerms = luckPerms;
    }

    /** Returns null when LuckPerms is not installed, which keeps it an optional dependency. */
    static LuckPermsService createIfAvailable(MGXAccessBridge plugin) {
        try {
            RegisteredServiceProvider<LuckPerms> provider =
                    Bukkit.getServicesManager().getRegistration(LuckPerms.class);
            if (provider == null) {
                plugin.getLogger().info("LuckPerms was not found; Discord rank sync is inactive.");
                return null;
            }
            plugin.getLogger().info("LuckPerms detected; Discord rank sync is active.");
            return new LuckPermsService(plugin, provider.getProvider());
        } catch (NoClassDefFoundError | RuntimeException exception) {
            plugin.getLogger().info("LuckPerms was not found; Discord rank sync is inactive.");
            return null;
        }
    }

    /**
     * Applies {@code group} to the player, removing any other managed group.
     * An empty group clears every managed group, which is what happens when a
     * member loses their Discord rank role.
     */
    void applyRank(UUID minecraftUuid, String group) {
        String desired = group == null ? "" : group.trim();
        if (!desired.isEmpty() && !MANAGED_GROUPS.contains(desired)) {
            plugin.getLogger().warning("Ignoring unknown Discord rank group: " + desired);
            return;
        }
        luckPerms.getUserManager().modifyUser(minecraftUuid, user -> mutate(user, desired))
                .exceptionally(throwable -> {
                    plugin.getLogger().log(
                            Level.WARNING,
                            "Could not apply a Discord rank for " + minecraftUuid,
                            throwable
                    );
                    return null;
                });
    }

    private void mutate(User user, String desired) {
        user.data().clear(node -> {
            if (node.getType() != NodeType.INHERITANCE) {
                return false;
            }
            String name = NodeType.INHERITANCE.cast(node).getGroupName();
            return MANAGED_GROUPS.contains(name) && !name.equals(desired);
        });
        if (!desired.isEmpty()) {
            user.data().add(InheritanceNode.builder(desired).build());
        }
    }
}
