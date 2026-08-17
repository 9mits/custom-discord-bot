package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import static bot.mgx.accessbridge.MenuItems.ORANGE;

/** Pays join-time money and places join-time bounties while those toggles are on. */
final class JoinGrantService implements Listener {
    private final EconomyStore money;
    private final BountyStore bounties;
    private final JoinGrantStore grants;

    JoinGrantService(EconomyStore money, BountyStore bounties, JoinGrantStore grants) {
        this.money = money;
        this.bounties = bounties;
        this.grants = grants;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (grants.tryClaim(JoinGrantStore.Kind.MONEY, player.getUniqueId())) {
            long amount = grants.amount(JoinGrantStore.Kind.MONEY);
            money.deposit(player.getUniqueId(), amount);
            player.sendMessage(Component.text(
                    "You received " + EconomyFormat.dollars(amount) + " for joining.",
                    ORANGE
            ));
        }
        if (grants.tryClaim(JoinGrantStore.Kind.BOUNTY, player.getUniqueId())) {
            long amount = grants.amount(JoinGrantStore.Kind.BOUNTY);
            bounties.add(player.getUniqueId(), amount, false);
            player.sendMessage(Component.text(
                    "A " + EconomyFormat.dollars(amount) + " bounty was placed on you.",
                    NamedTextColor.RED
            ));
        }
    }
}
