package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.Instant;
import java.util.List;
import java.io.UncheckedIOException;

/** Adds one tradable victim trophy for each killer-victim pair per rolling day. */
final class TrophyHeadService implements Listener {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private final MGXAccessBridge plugin;
    private final TrophyHeadStore store;
    private final PlayerSettingsStore settings;
    private final NamespacedKey victimKey;
    private final NamespacedKey earnedAtKey;

    TrophyHeadService(
            MGXAccessBridge plugin,
            TrophyHeadStore store,
            PlayerSettingsStore settings
    ) {
        this.plugin = plugin;
        this.store = store;
        this.settings = settings;
        victimKey = new NamespacedKey(plugin, "trophy_victim");
        earnedAtKey = new NamespacedKey(plugin, "trophy_earned_at");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        Player killer = victim.getKiller();
        if (killer == null) {
            return;
        }
        try {
            if (!store.claim(
                    killer.getUniqueId(), victim.getUniqueId(), System.currentTimeMillis()
            )) {
                return;
            }
        } catch (UncheckedIOException exception) {
            plugin.getLogger().warning("Could not save a trophy-head award: "
                    + exception.getMessage());
            return;
        }
        event.getDrops().add(head(victim));
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (!settings.isEnabled(
                    viewer.getUniqueId(), PlayerSettingsStore.Setting.TROPHY_MESSAGES
            )) {
                continue;
            }
            viewer.sendMessage(PlayerMenuService.prefix()
                    .append(Component.text(killer.getName(), NamedTextColor.GOLD))
                    .append(Component.text(" claimed ", NamedTextColor.GRAY))
                    .append(Component.text(victim.getName() + "'s trophy head.", NamedTextColor.WHITE)));
        }
    }

    private ItemStack head(Player victim) {
        ItemStack item = new ItemStack(org.bukkit.Material.PLAYER_HEAD);
        if (!(item.getItemMeta() instanceof SkullMeta meta)) {
            return item;
        }
        meta.setOwningPlayer(victim);
        meta.displayName(Component.text(victim.getName() + "'s Trophy", ORANGE, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                line("A PvP trophy from Mysterious SMP X."),
                line("Tradable and protected from /sell."),
                line("Repeated pairs can earn one every 24 hours.")
        ));
        meta.getPersistentDataContainer().set(
                victimKey, PersistentDataType.STRING, victim.getUniqueId().toString()
        );
        meta.getPersistentDataContainer().set(
                earnedAtKey, PersistentDataType.LONG, Instant.now().toEpochMilli()
        );
        item.setItemMeta(meta);
        return item;
    }

    private static Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }
}
