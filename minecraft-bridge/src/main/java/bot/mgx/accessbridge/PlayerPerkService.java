package bot.mgx.accessbridge;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class PlayerPerkService implements Listener {
    static final double ELITE_DAMAGE_BONUS = 0.15;
    static final double BOOSTER_DAMAGE_BONUS = 0.10;
    /** Boosters lose hunger 10% more slowly, which is how the saturation perk is felt. */
    static final float BOOSTER_EXHAUSTION_MULTIPLIER = 0.90f;
    private static final NamespacedKey HEART_MODIFIER_KEY = Objects.requireNonNull(
            NamespacedKey.fromString("mgx:discord_level_hearts")
    );

    private final Map<UUID, PlayerProfile> profiles = new HashMap<>();

    PlayerProfile profile(UUID playerId) {
        return profiles.getOrDefault(playerId, PlayerProfile.NONE);
    }

    void apply(Player player, PlayerProfile profile) {
        profiles.put(player.getUniqueId(), profile);
        AttributeInstance health = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (health == null) {
            return;
        }
        AttributeModifier current = health.getModifier(HEART_MODIFIER_KEY);
        if (current != null) {
            health.removeModifier(current);
        }
        if (profile.totalExtraHearts() > 0) {
            health.addTransientModifier(new AttributeModifier(
                    HEART_MODIFIER_KEY,
                    profile.totalExtraHearts() * 2.0,
                    AttributeModifier.Operation.ADD_NUMBER
            ));
        }
        if (player.getHealth() > health.getValue()) {
            player.setHealth(health.getValue());
        }
    }

    void clearOnline(Iterable<? extends Player> players) {
        for (Player player : players) {
            AttributeInstance health = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            if (health == null) {
                continue;
            }
            AttributeModifier modifier = health.getModifier(HEART_MODIFIER_KEY);
            if (modifier != null) {
                health.removeModifier(modifier);
                if (player.getHealth() > health.getValue()) {
                    player.setHealth(health.getValue());
                }
            }
        }
        profiles.clear();
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPerkDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            double multiplier = profile(player.getUniqueId()).damageMultiplier();
            if (multiplier != 1.0) {
                event.setDamage(event.getDamage() * multiplier);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBoosterExhaustion(EntityExhaustionEvent event) {
        if (event.getEntity() instanceof Player player && profile(player.getUniqueId()).booster()) {
            event.setExhaustion(event.getExhaustion() * BOOSTER_EXHAUSTION_MULTIPLIER);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        profiles.remove(event.getPlayer().getUniqueId());
    }
}
