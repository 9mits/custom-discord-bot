package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Makes the server's own chatter opt-out per reader.
 *
 * <p>Join, leave and death lines are normally one message the server sends to
 * everybody, so there is no per-player switch to offer. Each is cancelled here and
 * re-sent only to the players who still want it — which is what turns those settings
 * into something real rather than a toggle that writes to a file and changes nothing.
 *
 * <p>A player always sees their own death: hiding it would read as the death not
 * having registered.
 */
final class ChatVisibilityService implements Listener {
    private final PlayerSettingsStore settings;

    ChatVisibilityService(PlayerSettingsStore settings) {
        this.settings = settings;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Component message = event.joinMessage();
        if (message == null) {
            return;
        }
        event.joinMessage(null);
        deliver(message, PlayerSettingsStore.Setting.JOIN_LEAVE_MESSAGES, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Component message = event.quitMessage();
        if (message == null) {
            return;
        }
        event.quitMessage(null);
        deliver(message, PlayerSettingsStore.Setting.JOIN_LEAVE_MESSAGES, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Component message = event.deathMessage();
        if (message == null) {
            return;
        }
        event.deathMessage(null);
        deliver(message, PlayerSettingsStore.Setting.DEATH_MESSAGES, event.getEntity());
    }

    /** Re-sends a cancelled server message to whoever still wants that kind. */
    private void deliver(
            Component message, PlayerSettingsStore.Setting setting, Player always
    ) {
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (player.equals(always)
                    || settings.isEnabled(player.getUniqueId(), setting)) {
                player.sendMessage(message);
            }
        }
        org.bukkit.Bukkit.getConsoleSender().sendMessage(message);
    }
}
