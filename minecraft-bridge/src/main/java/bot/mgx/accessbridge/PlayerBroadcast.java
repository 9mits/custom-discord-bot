package bot.mgx.accessbridge;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Server-wide messages that respect the reader's own settings.
 *
 * <p>{@code Bukkit.broadcast} reaches everyone regardless, which is why a toggle for
 * "show me other players' crate wins" could not exist. Routing the same messages
 * through here is what makes those settings mean something instead of sitting in the
 * panel doing nothing.
 *
 * <p>Console still sees everything: an operator reading logs is not the audience a
 * player preference is about.
 */
final class PlayerBroadcast {
    private PlayerBroadcast() {
    }

    /** Sends to every player who has not switched this kind of message off. */
    static void broadcast(
            PlayerSettingsStore settings, PlayerSettingsStore.Setting setting, Component message
    ) {
        Bukkit.getConsoleSender().sendMessage(message);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (settings.isEnabled(player.getUniqueId(), setting)) {
                player.sendMessage(message);
            }
        }
    }

    /** Shows a bar to everyone who still wants that kind of bar. */
    static void showBar(
            PlayerSettingsStore settings, PlayerSettingsStore.Setting setting, BossBar bar
    ) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (settings.isEnabled(player.getUniqueId(), setting)) {
                player.showBossBar(bar);
            }
        }
    }

    /** True when this player wants to see it; for a message built per receiver. */
    static boolean wants(
            PlayerSettingsStore settings, PlayerSettingsStore.Setting setting, Player player
    ) {
        return settings.isEnabled(player.getUniqueId(), setting);
    }
}
