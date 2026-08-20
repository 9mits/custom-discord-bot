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
    private final MGXAccessBridge plugin;
    private final EconomyStore money;
    private final BountyStore bounties;
    private final JoinGrantStore grants;

    JoinGrantService(
            MGXAccessBridge plugin,
            EconomyStore money,
            BountyStore bounties,
            JoinGrantStore grants
    ) {
        this.plugin = plugin;
        this.money = money;
        this.bounties = bounties;
        this.grants = grants;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        long moneyAmount = grants.amount(JoinGrantStore.Kind.MONEY);
        if (grants.enabled(JoinGrantStore.Kind.MONEY) && moneyAmount > 0L
                && !money.canDeposit(player.getUniqueId(), moneyAmount)) {
            player.sendMessage(Component.text(
                    "Your join reward is waiting, but your wallet is at its limit.",
                    NamedTextColor.RED
            ));
        } else {
            boolean delivered = false;
            try {
                if (grants.tryClaim(JoinGrantStore.Kind.MONEY, player.getUniqueId())) {
                    money.deposit(player.getUniqueId(), moneyAmount);
                    delivered = true;
                }
            } catch (RuntimeException failure) {
                restoreClaim(JoinGrantStore.Kind.MONEY, player, failure);
            }
            if (delivered) {
                player.sendMessage(Component.text(
                        "You received " + EconomyFormat.dollars(moneyAmount) + " for joining.",
                        ORANGE
                ));
            }
        }
        long bountyAmount = grants.amount(JoinGrantStore.Kind.BOUNTY);
        if (grants.enabled(JoinGrantStore.Kind.BOUNTY) && bountyAmount > 0L
                && !bounties.canAdd(player.getUniqueId(), bountyAmount)) {
            player.sendMessage(Component.text(
                    "Your join bounty is waiting, but your current bounty is at its limit.",
                    NamedTextColor.RED
            ));
        } else {
            boolean delivered = false;
            try {
                if (grants.tryClaim(JoinGrantStore.Kind.BOUNTY, player.getUniqueId())) {
                    bounties.add(player.getUniqueId(), bountyAmount, false);
                    delivered = true;
                }
            } catch (RuntimeException failure) {
                restoreClaim(JoinGrantStore.Kind.BOUNTY, player, failure);
            }
            if (delivered) {
                player.sendMessage(Component.text(
                        "A " + EconomyFormat.dollars(bountyAmount) + " bounty was placed on you.",
                        NamedTextColor.RED
                ));
            }
        }
    }

    private void restoreClaim(JoinGrantStore.Kind kind, Player player, RuntimeException failure) {
        try {
            grants.releaseClaim(kind, player.getUniqueId());
        } catch (RuntimeException restoreFailure) {
            failure.addSuppressed(restoreFailure);
        }
        plugin.getLogger().warning(
                "Could not deliver " + kind.name().toLowerCase() + " join grant to "
                        + player.getName() + ": " + failure.getMessage()
        );
        player.sendMessage(Component.text(
                "Your join reward could not be saved. It will be retried the next time you join.",
                NamedTextColor.RED
        ));
    }
}
