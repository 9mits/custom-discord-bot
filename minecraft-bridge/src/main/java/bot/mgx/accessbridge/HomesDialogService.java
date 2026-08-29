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

    private static final String SEARCH_INPUT = "icon_search";
    /** Fits the dialog without scrolling; the rest is a page away. */
    private static final int ICONS_PER_PAGE = 24;

    private final TeleportMenuService teleports;
    private final SettingsClientSupport clientSupport;
    private final HomeIconStore homeIcons;

    HomesDialogService(
            TeleportMenuService teleports,
            SettingsClientSupport clientSupport,
            HomeIconStore homeIcons
    ) {
        this.teleports = teleports;
        this.clientSupport = clientSupport;
        this.homeIcons = homeIcons;
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
                            .append(MenuText.sprite(homeIcons.iconOf(player.getUniqueId(), home)))
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
                action("Change Icon", "Pick the icon this home shows.",
                        audience -> openIconPicker(audience, home, "", 1)),
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
                                MenuText.body("Give this home a different name."), 400
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
                                    // EssentialsX renames in place, so the home keeps its
                                    // position and the player stays where they are.
                                    audience.performCommand(
                                            "renamehome " + home + " " + name
                                    );
                                    homeIcons.rename(audience.getUniqueId(), home, name);
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
                                .action(callback((response, audience) -> {
                                    audience.performCommand("delhome " + home);
                                    homeIcons.forget(audience.getUniqueId(), home);
                                }))
                                .build(),
                        ActionButton.builder(Component.text("Keep it", MenuText.LABEL))
                                .width(150)
                                .action(callback((response, audience) -> openHome(audience, home)))
                                .build()
                )));
        player.showDialog(dialog);
    }

    /**
     * Browse every icon, with a search box.
     *
     * <p>Searching matches the readable label rather than the texture path, because
     * "Grass Block" is what the button says and {@code block/grass_block_side} is not
     * something a player should have to know.
     */
    private void openIconPicker(Player player, String home, String query, int page) {
        List<String> matches = HomeIcons.search(query);
        int pages = Math.max(1, (matches.size() + ICONS_PER_PAGE - 1) / ICONS_PER_PAGE);
        int current = Math.clamp(page, 1, pages);
        int first = (current - 1) * ICONS_PER_PAGE;
        int last = Math.min(matches.size(), first + ICONS_PER_PAGE);

        List<ActionButton> buttons = new ArrayList<>();
        for (int index = first; index < last; index++) {
            String sprite = matches.get(index);
            buttons.add(ActionButton.builder(Component.empty()
                            .append(MenuText.sprite(sprite))
                            .append(Component.text(" " + HomeIcons.label(sprite),
                                    NamedTextColor.WHITE)))
                    .width(150)
                    .action(callback((response, audience) -> {
                        try {
                            homeIcons.setIcon(audience.getUniqueId(), home, sprite);
                        } catch (IllegalArgumentException | java.io.UncheckedIOException failure) {
                            PlayerMenuService.error(audience, "That icon could not be saved.");
                            return;
                        }
                        openHome(audience, home);
                    }))
                    .build());
        }
        if (current > 1) {
            buttons.add(action("Previous", "Earlier icons.",
                    audience -> openIconPicker(audience, home, query, current - 1)));
        }
        if (current < pages) {
            buttons.add(action("Next", "More icons.",
                    audience -> openIconPicker(audience, home, query, current + 1)));
        }
        if (!query.isBlank()) {
            buttons.add(action("Clear search", "Show every icon.",
                    audience -> openIconPicker(audience, home, "", 1)));
        }
        // Search is an ordinary button. It used to be the dialog's exitAction, which is
        // what escape triggers — so escape ran a search instead of closing, and the
        // search itself only fired when the player was trying to leave.
        buttons.add(ActionButton.builder(Component.text("Search", MenuText.VALUE))
                .tooltip(Component.text("Filter by what the button says.", MenuText.LABEL))
                .width(150)
                .action(callback((response, audience) -> openIconPicker(
                        audience, home,
                        response.getText(SEARCH_INPUT) == null
                                ? "" : response.getText(SEARCH_INPUT),
                        1
                )))
                .build());
        buttons.add(action("Back", "Return to " + home + ".",
                audience -> openHome(audience, home)));

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title("Choose Icon"))
                        .body(List.of(DialogBody.plainMessage(MenuText.body(
                                matches.isEmpty()
                                        ? "Nothing matches \"" + query + "\"."
                                        : "Page " + current + " of " + pages
                                                + ". Search, then click an icon."
                        ), 400)))
                        .inputs(List.of(DialogInput.text(SEARCH_INPUT,
                                        Component.text("Search", MenuText.LABEL))
                                .maxLength(32)
                                .build()))
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .pause(false)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons)
                        .columns(3)
                        .exitAction(ActionButton.builder(
                                        Component.text("Close", MenuText.LABEL))
                                .width(150)
                                .action(callback((response, audience) -> audience.closeDialog()))
                                .build())
                        .build()));
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
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .pause(false)
                        .canCloseWithEscape(true)
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
