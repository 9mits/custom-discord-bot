package bot.mgx.accessbridge;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Tracks plugin boss bars so a focused activity can temporarily hide the rest. */
final class BossBarDisplay implements Listener {
    private final Map<UUID, Set<BossBar>> wanted = new HashMap<>();
    private final Set<UUID> suppressed = new HashSet<>();

    void show(Player player, BossBar bar) {
        wanted.computeIfAbsent(player.getUniqueId(), ignored ->
                Collections.newSetFromMap(new IdentityHashMap<>())).add(bar);
        if (!suppressed.contains(player.getUniqueId())) {
            player.showBossBar(bar);
        }
    }

    void hide(Player player, BossBar bar) {
        Set<BossBar> bars = wanted.get(player.getUniqueId());
        if (bars != null) {
            bars.remove(bar);
            if (bars.isEmpty()) wanted.remove(player.getUniqueId());
        }
        player.hideBossBar(bar);
    }

    /** Hides every ordinary bar and keeps later ordinary bars hidden too. */
    void suppress(Player player) {
        UUID playerId = player.getUniqueId();
        if (!suppressed.add(playerId)) return;
        for (BossBar bar : wanted.getOrDefault(playerId, Set.of())) {
            player.hideBossBar(bar);
        }
    }

    /** Restores whichever ordinary bars are still active. */
    void restore(Player player) {
        UUID playerId = player.getUniqueId();
        if (!suppressed.remove(playerId)) return;
        for (BossBar bar : wanted.getOrDefault(playerId, Set.of())) {
            player.showBossBar(bar);
        }
    }

    /** A focused activity's one allowed bar; it deliberately bypasses suppression. */
    void showExclusive(Player player, BossBar bar) {
        player.showBossBar(bar);
    }

    void hideExclusive(Player player, BossBar bar) {
        player.hideBossBar(bar);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        wanted.remove(playerId);
        suppressed.remove(playerId);
    }
}
