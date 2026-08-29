package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** Sends important private alerts to chat and the action bar without countdown clashes. */
final class PersonalNotificationService implements Listener {
    private final MGXAccessBridge plugin;
    private final PlayerSettingsStore settings;
    private final PersonalNotificationGate gate = new PersonalNotificationGate();

    PersonalNotificationService(MGXAccessBridge plugin, PlayerSettingsStore settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    void notify(Player player, Component chat, Component actionBar) {
        player.sendMessage(chat);
        actionBar(player, actionBar);
    }

    void actionBar(Player player, Component message) {
        // Every short notice funnels through here, so one switch covers teleport
        // warmups, update notices and the rest without chasing each call site.
        if (!settings.isEnabled(
                player.getUniqueId(), PlayerSettingsStore.Setting.ACTION_BAR_TIPS
        )) {
            return;
        }
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
