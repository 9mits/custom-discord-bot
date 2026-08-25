package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** Sends important private alerts to chat and the action bar without countdown clashes. */
final class PersonalNotificationService implements Listener {
    private final MGXAccessBridge plugin;
    private final PersonalNotificationGate gate = new PersonalNotificationGate();

    PersonalNotificationService(MGXAccessBridge plugin) {
        this.plugin = plugin;
    }

    void notify(Player player, Component chat, Component actionBar) {
        player.sendMessage(chat);
        actionBar(player, actionBar);
    }

    void actionBar(Player player, Component message) {
        gate.offer(player.getUniqueId(), message).ifPresent(player::sendActionBar);
    }

    int reserveActionBar(Player player) {
        return gate.reserve(player.getUniqueId());
    }

    void releaseActionBarAfter(Player player, int reservation, long delayTicks) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                gate.clear(player.getUniqueId());
                return;
            }
            gate.release(player.getUniqueId(), reservation).ifPresent(player::sendActionBar);
        }, Math.max(0L, delayTicks));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        gate.clear(event.getPlayer().getUniqueId());
    }

    void stop() {
        gate.clear();
    }
}
