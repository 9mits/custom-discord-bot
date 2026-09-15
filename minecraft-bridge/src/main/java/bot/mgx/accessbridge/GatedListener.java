package bot.mgx.accessbridge;

import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * A listener that is registered only while the feature behind it is running.
 *
 * <p>Paper skips whole code paths when nothing listens for certain events, and
 * re-checks that every tick: {@code BlockPhysicsEvent} is built for every neighbour
 * update on the server, {@code EntityMoveEvent} for every living entity that moves, and
 * any {@code InventoryMoveItemEvent} listener turns off the fast hopper path for every
 * hopper in every world. A listener that returns early is not free, because the event
 * has already been allocated and dispatched by then. For an event like that, being
 * unregistered is the only cheap state, so a feature that needs one for a few minutes
 * registers it for those minutes.
 *
 * <p>Main thread only, like registration itself.
 */
final class GatedListener {
    private final Plugin plugin;
    private final Listener listener;
    private boolean registered;

    GatedListener(Plugin plugin, Listener listener) {
        this.plugin = plugin;
        this.listener = listener;
    }

    /** Registers or unregisters the listener; a no-op when already in that state. */
    void enable(boolean wanted) {
        if (wanted == registered) {
            return;
        }
        if (wanted) {
            if (!plugin.isEnabled()) {
                return;
            }
            plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        } else {
            HandlerList.unregisterAll(listener);
        }
        registered = wanted;
    }

    boolean enabled() {
        return registered;
    }
}
