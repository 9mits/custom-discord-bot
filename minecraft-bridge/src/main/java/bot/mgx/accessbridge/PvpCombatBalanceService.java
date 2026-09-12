package bot.mgx.accessbridge;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Caps the damage a player actually receives after armour, enchantments and custom
 * weapon modifiers have all run.
 *
 * <p>The custom Scythes and Amethyst weapons modify Bukkit's damage event at
 * different stages. Limiting a material's attack value therefore does not limit the
 * hit the victim sees. This listener is deliberately registered after every custom
 * item listener and solves for the raw event damage that produces the configured
 * final damage. Vanilla armour, Protection, Resistance and critical hits continue to
 * matter; a stacked modifier just cannot turn one ordinary hit into half a health bar.
 */
final class PvpCombatBalanceService implements Listener {
    private final GameVariableStore variables;

    PvpCombatBalanceService(GameVariableStore variables) {
        this.variables = variables;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = attacker(event);
        if (attacker == null || attacker.equals(victim)) return;

        AttributeInstance armour = victim.getAttribute(Attribute.ARMOR);
        double points = armour == null ? 0d : armour.getValue();
        double threshold = variables.decimal("pvp-combat.armor-threshold");
        if (points + 1.0e-6d < threshold) return;

        double cap = variables.decimal("pvp-combat.armored-max-final-damage");
        if (!(cap > 0d) || event.getFinalDamage() <= cap) return;

        // Damage modifiers are recalculated each time setDamage is called. Binary
        // search keeps every modifier in Bukkit's own order rather than duplicating
        // armour/toughness/enchantment formulas that change across server versions.
        double high = Math.max(0d, event.getDamage());
        double low = 0d;
        for (int pass = 0; pass < 24; pass++) {
            double middle = (low + high) / 2d;
            event.setDamage(middle);
            if (event.getFinalDamage() > cap) high = middle;
            else low = middle;
        }
        event.setDamage(low);
    }

    private static Player attacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) return player;
        if (event.getDamager() instanceof Tameable tameable
                && tameable.getOwner() instanceof Player player) return player;
        return null;
    }
}
