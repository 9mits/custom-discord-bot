package bot.mgx.accessbridge;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * The main menu, reached by the quick-actions key or {@code /menu}.
 *
 * <p>The key itself is bound by {@link QuickMenuDatapack}; this covers the command,
 * which is the only way in for Bedrock players, who have no such key and cannot be
 * shown a dialog either. They get the chest board instead.
 */
final class MainMenuService implements CommandExecutor, Listener {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private static final int BUTTON_WIDTH = 150;
    private static final int MENU_SIZE = 27;

    private final MGXAccessBridge plugin;
    private final SettingsClientSupport clientSupport;

    MainMenuService(MGXAccessBridge plugin) {
        this(plugin, new SettingsClientSupport());
    }

    MainMenuService(MGXAccessBridge plugin, SettingsClientSupport clientSupport) {
        this.plugin = plugin;
        this.clientSupport = clientSupport;
    }

    @Override
    public boolean onCommand(
            CommandSender sender, Command command, String label, String[] args
    ) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage("The menu is available to players only.");
            return true;
        }
        open(player);
        return true;
    }

    void open(org.bukkit.entity.Player player) {
        if (!clientSupport.supportsDialogs(player)) {
            openChestMenu(player);
            return;
        }
        try {
            showDialog(player);
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().warning(
                    "Could not open the native main menu: " + exception.getMessage()
            );
            openChestMenu(player);
        }
    }

    private void showDialog(org.bukkit.entity.Player player) {
        List<ActionButton> buttons = new ArrayList<>();
        for (MainMenu entry : MainMenu.entries()) {
            buttons.add(ActionButton.builder(Component.text(entry.label(), NamedTextColor.WHITE))
                    .tooltip(Component.text(entry.tooltip(), NamedTextColor.GRAY))
                    .width(BUTTON_WIDTH)
                    .action(DialogAction.staticAction(
                            net.kyori.adventure.text.event.ClickEvent.runCommand(
                                    "/" + entry.command()
                            )
                    ))
                    .build());
        }
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(
                                Component.text("Mysterious SMP X", ORANGE, TextDecoration.BOLD)
                        )
                        .body(List.of(DialogBody.plainMessage(
                                Component.text("Choose where to go.", NamedTextColor.GRAY), 400
                        )))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(buttons).columns(2).build()));
        player.showDialog(dialog);
    }

    private void openChestMenu(org.bukkit.entity.Player player) {
        Menu menu = new Menu(Menu.Kind.MAIN_MENU, null, 1, null);
        Inventory inventory = org.bukkit.Bukkit.createInventory(
                menu, MENU_SIZE, Component.text("Mysterious SMP X", ORANGE)
        );
        menu.attach(inventory);
        List<MainMenu> entries = MainMenu.entries();
        for (int index = 0; index < entries.size() && index < MENU_SIZE; index++) {
            MainMenu entry = entries.get(index);
            inventory.setItem(index, MenuItems.button(
                    entry.icon(), entry.label(), entry.tooltip()
            ));
        }
        MenuItems.show(plugin, player, inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        Menu.Kind kind = menu.kind();
        if (kind != Menu.Kind.MAIN_MENU && kind != Menu.Kind.PLAYER_PROFILE
                && kind != Menu.Kind.TELEPORT_PLAYERS) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof org.bukkit.entity.Player player)) {
            return;
        }
        if (kind == Menu.Kind.PLAYER_PROFILE) {
            // A profile board is read-only; the only click that does anything closes it.
            plugin.getServer().getScheduler().runTask(plugin, (Runnable) player::closeInventory);
            return;
        }
        if (kind == Menu.Kind.TELEPORT_PLAYERS) {
            menu.option(event.getSlot()).ifPresentOrElse(
                    name -> runLater(player, "tpa " + name),
                    () -> plugin.getServer().getScheduler()
                            .runTask(plugin, (Runnable) player::closeInventory)
            );
            return;
        }
        List<MainMenu> entries = MainMenu.entries();
        int slot = event.getSlot();
        if (slot < 0 || slot >= entries.size()) {
            return;
        }
        String command = entries.get(slot).command();
        runLater(player, command);
    }

    /**
     * Bedrock drops a screen opened from inside a click, so anything that opens the
     * next menu has to run on the following tick.
     */
    private void runLater(org.bukkit.entity.Player player, String command) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.closeInventory();
                player.performCommand(command);
            }
        });
    }
}
