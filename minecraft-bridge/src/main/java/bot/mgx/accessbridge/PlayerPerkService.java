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
import org.bukkit.event.entity.EntityDamageEvent;
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
    // Clan perks carry their own keys so they stack with the Discord level perks above
    // rather than replacing them.
    private static final NamespacedKey CLAN_HEART_KEY = Objects.requireNonNull(
            NamespacedKey.fromString("mgx:clan_level_hearts")
    );
    private static final NamespacedKey CLAN_SPEED_KEY = Objects.requireNonNull(
            NamespacedKey.fromString("mgx:clan_level_speed")
    );
    private static final NamespacedKey CLAN_DIG_KEY = Objects.requireNonNull(
            NamespacedKey.fromString("mgx:clan_level_dig")
    );

    private final Map<UUID, PlayerProfile> profiles = new HashMap<>();
    private final Map<UUID, ClanLevel.Perks> clanPerks = new HashMap<>();

    PlayerProfile profile(UUID playerId) {
        return profiles.getOrDefault(playerId, PlayerProfile.NONE);
    }

    ClanLevel.Perks clanPerks(UUID playerId) {
        return clanPerks.getOrDefault(playerId, ClanLevel.Perks.NONE);
    }

    void apply(Player player, PlayerProfile profile) {
        profiles.put(player.getUniqueId(), profile);
        applyHearts(player, HEART_MODIFIER_KEY, profile.totalExtraHearts());
    }

    /**
     * Applies what a player's clan level grants. Passing {@link ClanLevel.Perks#NONE}
     * strips every clan modifier, which is how leaving or being kicked removes them.
     */
    void applyClan(Player player, ClanLevel.Perks perks) {
        ClanLevel.Perks applied = perks == null ? ClanLevel.Perks.NONE : perks;
        clanPerks.put(player.getUniqueId(), applied);
        applyHearts(player, CLAN_HEART_KEY, applied.extraHearts());
        applyScalar(player, Attribute.GENERIC_MOVEMENT_SPEED, CLAN_SPEED_KEY, applied.speed());
        applyScalar(player, Attribute.PLAYER_BLOCK_BREAK_SPEED, CLAN_DIG_KEY, applied.diggingSpeed());
    }

    void clearOnline(Iterable<? extends Player> players) {
        for (Player player : players) {
            applyHearts(player, HEART_MODIFIER_KEY, 0);
            applyHearts(player, CLAN_HEART_KEY, 0);
            applyScalar(player, Attribute.GENERIC_MOVEMENT_SPEED, CLAN_SPEED_KEY, 0);
            applyScalar(player, Attribute.PLAYER_BLOCK_BREAK_SPEED, CLAN_DIG_KEY, 0);
        }
        profiles.clear();
        clanPerks.clear();
    }

    private void applyHearts(Player player, NamespacedKey key, int extraHearts) {
        AttributeInstance health = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (health == null) {
            return;
        }
        AttributeModifier current = health.getModifier(key);
        if (current != null) {
            health.removeModifier(current);
        }
        if (extraHearts > 0) {
            health.addTransientModifier(new AttributeModifier(
                    key,
                    extraHearts * 2.0,
                    AttributeModifier.Operation.ADD_NUMBER
            ));
        }
        if (player.getHealth() > health.getValue()) {
            player.setHealth(health.getValue());
        }
    }

    /** ADD_SCALAR reads as a percentage of the base value, which is what perks promise. */
    private void applyScalar(Player player, Attribute attribute, NamespacedKey key, double fraction) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        AttributeModifier current = instance.getModifier(key);
        if (current != null) {
            instance.removeModifier(current);
        }
        if (fraction > 0) {
            instance.addTransientModifier(new AttributeModifier(
                    key,
                    fraction,
                    AttributeModifier.Operation.ADD_SCALAR
            ));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPerkDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            // Clan strength joins elite and booster additively, matching how those two
            // already stack, so the figures on the perk pages add up as written.
            double multiplier = profile(player.getUniqueId()).damageMultiplier()
                    + clanPerks(player.getUniqueId()).strength();
            if (multiplier != 1.0) {
                event.setDamage(event.getDamage() * multiplier);
            }
        }
    }

    /**
     * Clan resistance, applied to the victim. Deliberately on the broader event so it
     * covers fall, fire and blast damage rather than melee alone.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onClanResistance(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        double resistance = clanPerks(player.getUniqueId()).resistance();
        if (resistance > 0) {
            event.setDamage(event.getDamage() * (1.0 - resistance));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBoosterExhaustion(EntityExhaustionEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        float exhaustion = event.getExhaustion();
        if (profile(player.getUniqueId()).booster()) {
            exhaustion *= BOOSTER_EXHAUSTION_MULTIPLIER;
        }
        double saturation = clanPerks(player.getUniqueId()).saturation();
        if (saturation > 0) {
            exhaustion *= (float) (1.0 - saturation);
        }
        if (exhaustion != event.getExhaustion()) {
            event.setExhaustion(exhaustion);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        profiles.remove(event.getPlayer().getUniqueId());
        clanPerks.remove(event.getPlayer().getUniqueId());
    }
}
