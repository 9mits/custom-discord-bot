package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Collection;
import java.util.List;

/** First login after a published update gets the broadcast-style NEW UPDATE card. */
final class UpdateNoticeService implements Listener {
    static final String BLOG_URL = "https://mysterioussmpx.blog";
    static final String HEADING = "NEW UPDATE";
    private static final long JOIN_DELAY_TICKS = 40L;

    private final MGXAccessBridge plugin;
    private final UpdateNoticeStore store;
    private final BroadcastDisplayService broadcasts;

    UpdateNoticeService(
            MGXAccessBridge plugin,
            UpdateNoticeStore store,
            BroadcastDisplayService broadcasts
    ) {
        this.plugin = plugin;
        this.store = store;
        this.broadcasts = broadcasts;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        if (!store.active()) {
            return;
        }
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            try {
                if (store.tryClaim(player.getUniqueId())) {
                    show(List.of(player));
                }
            } catch (RuntimeException failure) {
                plugin.getLogger().warning(
                        "Could not show the update notice to " + player.getName()
                                + ": " + failure.getMessage()
                );
            }
        }, JOIN_DELAY_TICKS);
    }

    int publish(Collection<? extends Player> online) {
        int generation = store.publish();
        show(online);
        store.markSeen(online.stream().map(Player::getUniqueId).toList());
        return generation;
    }

    private void show(Collection<? extends Player> audience) {
        broadcasts.announceBanner(audience, HEADING, body());
    }

    static Component body() {
        return Component.text("  There's a NEW UPDATE! Check ", NamedTextColor.WHITE, TextDecoration.BOLD)
                .append(Component.text("mysterioussmpx.blog", NamedTextColor.WHITE, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.openUrl(BLOG_URL))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "Open the Mysterious SMP X dev blog", NamedTextColor.GRAY
                        ))))
                .append(Component.text(" for everything that shipped.", NamedTextColor.WHITE, TextDecoration.BOLD));
    }
}
