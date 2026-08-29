package bot.mgx.accessbridge;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * The homes screens.
 *
 * <p>Essentials still owns the homes themselves — every action here runs the command a
 * player would type, so warmups, limits and permissions are unchanged and there is no
 * second copy of the data to fall out of step.
 */
final class HomesDialogService {
    private static final ClickCallback.Options CALLBACK_OPTIONS = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(Duration.ofMinutes(10))
            .build();
    private static final String NAME_INPUT = "home_name";

    private final TeleportMenuService teleports;
    private final SettingsClientSupport clientSupport;

    HomesDialogService(TeleportMenuService teleports, SettingsClientSupport clientSupport) {
        this.teleports = teleports;
        this.clientSupport = clientSupport;
    }

    void open(Player player) {
        if (!clientSupport.supportsDialogs(player)) {
            teleports.openHomes(player, 1);
            return;
        }
        List<String> homes = teleports.homeNamesOf(player);
        List<ActionButton> buttons = new ArrayList<>();
        for (String home : homes) {
            buttons.add(ActionButton.builder(Component.empty()
                            .append(MenuText.icon(Material.WHITE_BED))
                            .append(Component.text(" " + home, NamedTextColor.WHITE)))
                    .tooltip(Component.text("Open this home", MenuText.LABEL))
                    .width(150)
                    .action(callback((response, audience) -> openHome(audience, home)))
                    .build());
        }
        buttons.add(ActionButton.builder(Component.text("New Home", MenuText.VALUE))
                .tooltip(Component.text("Set a home where you are standing.", MenuText.LABEL))
                .width(150)
                .action(callback((response, audience) -> openNewHome(audience)))
                .build());
        buttons.add(ActionButton.builder(Component.text("Close", MenuText.LABEL))
                .width(150)
                .action(callback((response, audience) -> audience.closeDialog()))
                .build());
        show(player, "Homes", homes.isEmpty()
                ? "You have not set a home yet."
                : "Click a home to open it.", buttons, 2);
    }

    private void openHome(Player player, String home) {
        List<ActionButton> buttons = List.of(
                action("Teleport", "Go there now.", audience -> {
                    audience.closeDialog();
                    audience.performCommand("home " + home);
                }),
                action("Rename", "Give it a different name.",
                        audience -> openRename(audience, home)),
                action("Delete", "Remove this home for good.",
                        audience -> openDelete(audience, home), NamedTextColor.RED),
                action("Back", "Return to your homes.", this::open)
        );
        show(player, home, "What would you like to do?", new ArrayList<>(buttons), 2);
    }

    private void openNewHome(Player player) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title("New Home"))
                        .body(List.of(DialogBody.plainMessage(
                                MenuText.body("Names a home where you are standing."), 400
                        )))
                        .inputs(List.of(DialogInput.text(NAME_INPUT,
                                        Component.text("Name", MenuText.LABEL))
                                .maxLength(32)
                                .build()))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text("Set Home", MenuText.VALUE))
                                .width(150)
                                .action(callback((response, audience) -> {
                                    String name = clean(response.getText(NAME_INPUT));
                                    if (name == null) {
                                        PlayerMenuService.error(audience,
                                                "Use letters, numbers, - and _ for a home name.");
                                        return;
                                    }
                                    audience.performCommand("sethome " + name);
                                }))
                                .build(),
                        ActionButton.builder(Component.text("Cancel", MenuText.LABEL))
                                .width(150)
                                .action(callback((response, audience) -> open(audience)))
                                .build()
                )));
        player.showDialog(dialog);
    }

    private void openRename(Player player, String home) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title("Rename " + home))
                        .body(List.of(DialogBody.plainMessage(
                                MenuText.body("Sets a new home here and removes the old one."), 400
                        )))
                        .inputs(List.of(DialogInput.text(NAME_INPUT,
                                        Component.text("New name", MenuText.LABEL))
                                .maxLength(32)
                                .build()))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text("Rename", MenuText.VALUE))
                                .width(150)
                                .action(callback((response, audience) -> {
                                    String name = clean(response.getText(NAME_INPUT));
                                    if (name == null) {
                                        PlayerMenuService.error(audience,
                                                "Use letters, numbers, - and _ for a home name.");
                                        return;
                                    }
                                    // Essentials has no rename, and a home is a position:
                                    // standing on the old one is what makes this honest.
                                    audience.performCommand("home " + home);
                                    audience.sendMessage(Component.text(
                                            "Teleporting to " + home + ". Run /sethome " + name
                                                    + " when you arrive, then /delhome " + home
                                                    + ".",
                                            MenuText.LABEL
                                    ));
                                }))
                                .build(),
                        ActionButton.builder(Component.text("Cancel", MenuText.LABEL))
                                .width(150)
                                .action(callback((response, audience) -> openHome(audience, home)))
                                .build()
                )));
        player.showDialog(dialog);
    }

    private void openDelete(Player player, String home) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title("Delete " + home))
                        .body(List.of(DialogBody.plainMessage(
                                Component.text("This cannot be undone.", NamedTextColor.RED), 400
                        )))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text("Delete", NamedTextColor.RED))
                                .width(150)
                                .action(callback((response, audience) ->
                                        audience.performCommand("delhome " + home)))
                                .build(),
                        ActionButton.builder(Component.text("Keep it", MenuText.LABEL))
                                .width(150)
                                .action(callback((response, audience) -> openHome(audience, home)))
                                .build()
                )));
        player.showDialog(dialog);
    }

    private ActionButton action(String label, String tooltip, java.util.function.Consumer<Player> run) {
        return action(label, tooltip, run, NamedTextColor.WHITE);
    }

    private ActionButton action(
            String label,
            String tooltip,
            java.util.function.Consumer<Player> run,
            net.kyori.adventure.text.format.TextColor colour
    ) {
        return ActionButton.builder(Component.text(label, colour))
                .tooltip(Component.text(tooltip, MenuText.LABEL))
                .width(150)
                .action(callback((response, audience) -> run.accept(audience)))
                .build();
    }

    private void show(
            Player player, String title, String body, List<ActionButton> buttons, int columns
    ) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title(title))
                        .body(List.of(DialogBody.plainMessage(MenuText.body(body), 400)))
                        .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                        .build())
                .type(DialogType.multiAction(buttons).columns(columns).build()));
        player.showDialog(dialog);
    }

    /** Essentials home names are a command argument, so anything odd is refused here. */
    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.strip();
        return name.matches("[A-Za-z0-9_-]{1,32}") ? name : null;
    }

    private DialogAction callback(BiConsumer<DialogResponseView, Player> callback) {
        return DialogAction.customClick((response, audience) -> {
            if (audience instanceof Player player && player.isOnline()) {
                callback.accept(response, player);
            }
        }, CALLBACK_OPTIONS);
    }
}
