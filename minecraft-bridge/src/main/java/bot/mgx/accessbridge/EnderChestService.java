package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.persistence.PersistentDataType;

/** Unlocks /echest only through real interaction with an Ender Chest block. */
final class EnderChestService implements CommandExecutor, Listener {
    private final NamespacedKey unlockedKey;

    EnderChestService(MGXAccessBridge plugin) {
        unlockedKey = new NamespacedKey(plugin, "remote_ender_chest_unlocked");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPhysicalEnderChest(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null
                || event.getClickedBlock().getType() != Material.ENDER_CHEST) {
            return;
        }
        Player player = event.getPlayer();
        if (unlocked(player)) {
            return;
        }
        player.getPersistentDataContainer().set(unlockedKey, PersistentDataType.BYTE, (byte) 1);
        player.sendMessage(PlayerMenuService.prefix()
                .append(Component.text("Remote Ender Chest unlocked! ", NamedTextColor.LIGHT_PURPLE,
                        TextDecoration.BOLD))
                .append(Component.text("You can now use /echest.", NamedTextColor.WHITE)));
        player.sendActionBar(Component.text("◆ /ECHEST UNLOCKED ◆", NamedTextColor.LIGHT_PURPLE,
                TextDecoration.BOLD));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players have an Ender Chest.");
            return true;
        }
        if (!unlocked(player)) {
            PlayerMenuService.error(
                    player,
                    "Open a physical Ender Chest once before using /echest."
            );
            return true;
        }
        player.openInventory(player.getEnderChest());
        return true;
    }

    boolean unlocked(Player player) {
        return player.getPersistentDataContainer().has(unlockedKey, PersistentDataType.BYTE);
    }
}
