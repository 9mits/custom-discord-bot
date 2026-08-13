package bot.mgx.accessbridge;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class PlayerPerkService implements Listener {
    static final double ELITE_DAMAGE_MULTIPLIER = 1.05;
    private static final UUID HEART_MODIFIER_ID = UUID.nameUUIDFromBytes(
            "mgx:discord-level-hearts".getBytes(StandardCharsets.UTF_8)
    );
    private static final String HEART_MODIFIER_NAME = "mgx_discord_level_hearts";

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
        AttributeModifier current = health.getModifier(HEART_MODIFIER_ID);
        if (current != null) {
            health.removeModifier(current);
        }
        if (profile.extraHearts() > 0) {
            health.addTransientModifier(new AttributeModifier(
                    HEART_MODIFIER_ID,
                    HEART_MODIFIER_NAME,
                    profile.extraHearts() * 2.0,
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
            AttributeModifier modifier = health.getModifier(HEART_MODIFIER_ID);
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
    public void onEliteDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && profile(player.getUniqueId()).elite()) {
            event.setDamage(event.getDamage() * ELITE_DAMAGE_MULTIPLIER);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        profiles.remove(event.getPlayer().getUniqueId());
    }
}
