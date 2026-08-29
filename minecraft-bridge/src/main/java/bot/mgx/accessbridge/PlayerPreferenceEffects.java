package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;
import java.util.Set;

/**
 * Applies the player settings that change the world rather than the screen.
 *
 * <p>Night vision is re-applied rather than granted once: a death, a milk bucket or
 * another plugin's effect all clear it, and a setting that silently stops working is
 * worse than one that was never offered.
 */
final class PlayerPreferenceEffects implements Listener {
    /** Long enough that the periodic top-up never lets the vignette flicker. */
    private static final int NIGHT_VISION_TICKS = 20 * 60;
    private static final long REFRESH_TICKS = 20L * 20L;
    /** Essentials owns these, so the block has to happen before it reads the command. */
    private static final Set<String> TELEPORT_REQUESTS = Set.of(
            "tpa", "tpahere", "tpask", "call", "tpayes"
    );

    private final MGXAccessBridge plugin;
    private final PlayerSettingsStore settings;

    PlayerPreferenceEffects(MGXAccessBridge plugin, PlayerSettingsStore settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    void start() {
        plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
                plugin, this::refreshAll, 40L, REFRESH_TICKS
        );
    }

    private void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyNightVision(player);
        }
    }

    /** Public so a toggle takes effect on the click rather than at the next sweep. */
    void applyNightVision(Player player) {
        boolean wanted = settings.isEnabled(
                player.getUniqueId(), PlayerSettingsStore.Setting.NIGHT_VISION
        );
        if (wanted) {
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.NIGHT_VISION, NIGHT_VISION_TICKS, 0, true, false, false
            ));
            return;
        }
        PotionEffect current = player.getPotionEffect(PotionEffectType.NIGHT_VISION);
        // Only ours is ambient and hidden, so a potion the player actually drank stays.
        if (current != null && current.isAmbient() && !current.hasParticles()) {
            player.removePotionEffect(PotionEffectType.NIGHT_VISION);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        applyNightVision(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().substring(1).trim().split("\\s+");
        if (parts.length < 2 || !TELEPORT_REQUESTS.contains(parts[0].toLowerCase(Locale.ROOT))) {
            return;
        }
        Player target = Bukkit.getPlayerExact(parts[1]);
        if (target == null || target.equals(event.getPlayer())
                || settings.isEnabled(
                        target.getUniqueId(), PlayerSettingsStore.Setting.TELEPORT_REQUESTS
                )) {
            return;
        }
        event.setCancelled(true);
        // Naming the setting rather than the player's choice keeps it from reading as a
        // personal refusal, which is what turns a preference into an argument.
        event.getPlayer().sendMessage(PlayerMenuService.prefix().append(Component.text(
                target.getName() + " is not accepting teleport requests.", NamedTextColor.RED
        )));
    }
}
