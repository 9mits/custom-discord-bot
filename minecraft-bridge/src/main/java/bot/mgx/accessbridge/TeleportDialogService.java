package bot.mgx.accessbridge;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Picks somebody to ask for a teleport.
 *
 * <p>The request itself stays with Essentials: this only chooses the target and runs
 * {@code /tpa}, so warmups, cooldowns and the receiving player's own settings all keep
 * working exactly as they do when the command is typed.
 */
final class TeleportDialogService implements CommandExecutor {
    private static final ClickCallback.Options CALLBACK_OPTIONS = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(Duration.ofMinutes(5))
            .build();
    private static final int MENU_SIZE = 27;
    private static final String NAME_INPUT = "player_name";

    private final MGXAccessBridge plugin;
    private final SettingsClientSupport clientSupport;
    private final BedrockForms forms;

    TeleportDialogService(
            MGXAccessBridge plugin, SettingsClientSupport clientSupport, BedrockForms forms
    ) {
        this.plugin = plugin;
        this.clientSupport = clientSupport;
        this.forms = forms;
    }

    @Override
    public boolean onCommand(
            CommandSender sender, Command command, String label, String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("The teleport menu is available to players only.");
            return true;
        }
        open(player);
        return true;
    }

    void open(Player viewer) {
        List<Player> targets = targets(viewer);
        if (!clientSupport.supportsDialogs(viewer)) {
            List<BedrockForms.Button> buttons = new ArrayList<>();
            for (Player target : targets) {
                String name = target.getName();
                buttons.add(new BedrockForms.Button(name,
                        () -> viewer.performCommand("tpa " + name)));
            }
            buttons.add(new BedrockForms.Button("Type a Name",
                    () -> forms.prompt(viewer, "Teleport by Name", "Name", "", typed -> {
                        String name = typed.strip();
                        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
                            PlayerMenuService.error(viewer, "That is not a Minecraft name.");
                            open(viewer);
                            return;
                        }
                        viewer.closeDialog();
                        viewer.performCommand("tpa " + name);
                    }, () -> open(viewer))));
            if (!forms.menu(viewer, "Teleport", "Choose a player.", buttons)) {
                openChest(viewer, targets);
            }
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (Player target : targets) {
            UUID id = target.getUniqueId();
            buttons.add(ActionButton.builder(Component.empty()
                            .append(MenuText.head(id))
                            .append(Component.text(" " + target.getName(), NamedTextColor.WHITE)))
                    .tooltip(Component.text("Ask to teleport to them", MenuText.LABEL))
                    .width(150)
                    .action(callback((response, audience) -> request(audience, id)))
                    .build());
        }
        buttons.add(ActionButton.builder(Component.text("Teleport by Name", MenuText.VALUE))
                .tooltip(Component.text("Type an exact Minecraft name.", MenuText.LABEL))
                .width(150)
                .action(callback((response, audience) -> requestByName(
                        audience, response.getText(NAME_INPUT))))
                .build());
        Screens.show(viewer, "Teleport", Screens.body(
                targets.isEmpty()
                        ? "Nobody else is online. You can still type a name."
                        : "Choose a player, or type a name."
        ), List.of(DialogInput.text(NAME_INPUT, Component.text("Player name", MenuText.LABEL))
                        .maxLength(16)
                        .build()),
                buttons, 2, null);
    }

    private void openChest(Player viewer, List<Player> targets) {
        Menu menu = new Menu(Menu.Kind.TELEPORT_PLAYERS, null, 1, null);
        Inventory inventory = Bukkit.createInventory(
                menu, MENU_SIZE, Component.text("Teleport", MenuText.ORANGE)
        );
        menu.attach(inventory);
        for (int index = 0; index < targets.size() && index < MENU_SIZE - 1; index++) {
            Player target = targets.get(index);
            inventory.setItem(index, MenuItems.head(
                    target.getUniqueId(), target.getName(), List.of("Ask to teleport to them")
            ));
            menu.option(index, target.getName());
        }
        // The chest fallback is reached from the chest main menu, so it goes Back
        // there for the same reason the dialog does.
        inventory.setItem(MENU_SIZE - 1, MenuItems.button(
                Material.BARRIER, "Back", "Return to the main menu."));
        MenuItems.show(plugin, viewer, inventory);
    }

    private List<Player> targets(Player viewer) {
        List<Player> targets = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(viewer)
                    && !VerificationLobbyService.isLobbyWorld(online.getWorld())) {
                targets.add(online);
            }
        }
        targets.sort(java.util.Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        return targets;
    }

    private void request(Player viewer, UUID targetId) {
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            PlayerMenuService.error(viewer, "They went offline.");
            return;
        }
        viewer.closeDialog();
        viewer.performCommand("tpa " + target.getName());
    }

    private void requestByName(Player viewer, String rawName) {
        String name = rawName == null ? "" : rawName.strip();
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            PlayerMenuService.error(viewer, "That is not a Minecraft name.");
            return;
        }
        viewer.closeDialog();
        viewer.performCommand("tpa " + name);
    }

    private DialogAction callback(BiConsumer<DialogResponseView, Player> callback) {
        return DialogAction.customClick((response, audience) -> {
            if (audience instanceof Player player && player.isOnline()) {
                callback.accept(response, player);
            }
        }, CALLBACK_OPTIONS);
    }
}
