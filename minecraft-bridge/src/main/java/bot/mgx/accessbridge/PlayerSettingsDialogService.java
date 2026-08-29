package bot.mgx.accessbridge;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** Native Java settings dialogs with the inventory screens as a safe fallback. */
final class PlayerSettingsDialogService {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private static final ClickCallback.Options CALLBACK_OPTIONS = ClickCallback.Options.builder()
            .uses(1)
            .lifetime(Duration.ofMinutes(10))
            .build();

    private final MGXAccessBridge plugin;
    private final PlayerSettingsStore store;
    private final PlayerMenuService menus;
    private final SettingsClientSupport clientSupport;

    PlayerSettingsDialogService(
            MGXAccessBridge plugin,
            PlayerSettingsStore store,
            PlayerMenuService menus
    ) {
        this(plugin, store, menus, new SettingsClientSupport());
    }

    PlayerSettingsDialogService(
            MGXAccessBridge plugin,
            PlayerSettingsStore store,
            PlayerMenuService menus,
            SettingsClientSupport clientSupport
    ) {
        this.plugin = plugin;
        this.store = store;
        this.menus = menus;
        this.clientSupport = clientSupport;
    }

    void open(Player player) {
        if (!clientSupport.supportsDialogs(player)) {
            menus.openSettings(player);
            return;
        }
        try {
            showRootDialog(player);
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().warning(
                    "Could not open the native settings dialog: " + exception.getMessage()
            );
            menus.openSettings(player);
        }
    }

    void openCosmeticSettings(Player player) {
        openCategory(player, PlayerSettingsStore.Category.VISUALS);
    }

    private void openCategory(Player player, PlayerSettingsStore.Category category) {
        if (!clientSupport.supportsDialogs(player)) {
            menus.openSettingsCategory(player, category);
            return;
        }
        try {
            showCategoryDialog(player, category);
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().warning(
                    "Could not open the native " + category.label() + " settings dialog: "
                            + exception.getMessage()
            );
            menus.openSettingsCategory(player, category);
        }
    }

    private void showRootDialog(Player player) {
        List<ActionButton> categories = new ArrayList<>();
        for (PlayerSettingsStore.Category category : PlayerSettingsStore.Category.values()) {
            categories.add(ActionButton.builder(Component.text(category.label(), NamedTextColor.WHITE))
                    .tooltip(Component.text("Open " + category.label() + " settings", NamedTextColor.GRAY))
                    .width(200)
                    .action(callback((response, audience) -> openCategory(audience, category)))
                    .build());
        }
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Settings", ORANGE, TextDecoration.BOLD))
                        .body(List.of(DialogBody.plainMessage(Component.text(
                                "Choose a category to change your Mysterious SMP X settings.",
                                NamedTextColor.GRAY
                        ), 400)))
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(categories)
                        .columns(2)
                        .exitAction(ActionButton.create(
                                Component.text("Close", NamedTextColor.GRAY),
                                null,
                                200,
                                callback((response, audience) -> audience.closeDialog())
                        ))
                        .build()));
        player.showDialog(dialog);
    }

    /**
     * Every toggle carries its own state and flips where it sits. Re-showing the dialog
     * from the click is what repaints the new value, so the player never leaves the
     * category to change one thing and never has to confirm a form.
     */
    private void showCategoryDialog(Player player, PlayerSettingsStore.Category category) {
        List<ActionButton> buttons = new ArrayList<>();
        for (PlayerSettingsStore.Setting setting : category.settings()) {
            boolean enabled = store.isEnabled(player.getUniqueId(), setting);
            buttons.add(toggleButton(
                    setting.label(),
                    enabled,
                    setting.description(),
                    audience -> toggle(audience, category, setting)
            ));
        }
        if (category == PlayerSettingsStore.Category.VISUALS) {
            int volume = store.musicVolume(player.getUniqueId());
            buttons.add(ActionButton.builder(Component.text(
                            "Synced Music Volume: " + volume + "%",
                            volume == 0 ? NamedTextColor.RED : NamedTextColor.AQUA,
                            TextDecoration.BOLD
                    ))
                    .tooltip(Component.text(
                            "Separate from Minecraft Music. Click to cycle 100 / 75 / 50 / 25 / 0.",
                            NamedTextColor.GRAY
                    ))
                    .width(310)
                    .action(callback((response, audience) -> cycleMusicVolume(audience)))
                    .build());
        }
        if (category == PlayerSettingsStore.Category.PRIVACY) {
            buttons.add(toggleButton(
                    "Discord Name",
                    menus.discordNameVisible(player.getUniqueId()),
                    "Let other players see the account you linked.",
                    audience -> toggleDiscordName(audience)
            ));
        }
        buttons.add(ActionButton.create(
                Component.text("Back", NamedTextColor.GRAY),
                Component.text("Return to all settings.", NamedTextColor.GRAY),
                310,
                callback((response, audience) -> open(audience))
        ));

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Settings - " + category.label(), ORANGE, TextDecoration.BOLD))
                        .body(List.of(DialogBody.plainMessage(
                                Component.text(category.description(), NamedTextColor.GRAY), 400
                        )))
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons)
                        .columns(1)
                        .build()));
        player.showDialog(dialog);
    }

    /** A button reading "Label: ON" in green or "Label: OFF" in red, as the value stands. */
    private ActionButton toggleButton(
            String label,
            boolean enabled,
            String description,
            java.util.function.Consumer<Player> action
    ) {
        Component text = Component.text(label + ": ", NamedTextColor.WHITE)
                .append(Component.text(
                        enabled ? "ON" : "OFF",
                        enabled ? NamedTextColor.GREEN : NamedTextColor.RED,
                        TextDecoration.BOLD
                ));
        return ActionButton.builder(text)
                .tooltip(Component.text(description, NamedTextColor.GRAY))
                .width(310)
                .action(callback((response, audience) -> action.accept(audience)))
                .build();
    }

    private void toggle(
            Player player,
            PlayerSettingsStore.Category category,
            PlayerSettingsStore.Setting setting
    ) {
        try {
            store.toggle(player.getUniqueId(), setting);
            plugin.refreshClans();
        } catch (UncheckedIOException exception) {
            plugin.getLogger().warning("Could not save a player setting: " + exception.getMessage());
            PlayerMenuService.error(player, "That setting could not be saved. Please try again.");
        }
        openCategory(player, category);
    }

    private void toggleDiscordName(Player player) {
        try {
            menus.toggleDiscordName(player.getUniqueId());
        } catch (IllegalStateException exception) {
            PlayerMenuService.error(player, exception.getMessage());
        } catch (UncheckedIOException exception) {
            plugin.getLogger().warning(
                    "Could not save a Discord-name preference: " + exception.getMessage()
            );
            PlayerMenuService.error(player, "That setting could not be saved. Please try again.");
        }
        openCategory(player, PlayerSettingsStore.Category.PRIVACY);
    }

    private void cycleMusicVolume(Player player) {
        try {
            store.cycleMusicVolume(player.getUniqueId());
        } catch (UncheckedIOException exception) {
            plugin.getLogger().warning("Could not save synced music volume: "
                    + exception.getMessage());
            PlayerMenuService.error(player, "That volume could not be saved. Please try again.");
        }
        openCategory(player, PlayerSettingsStore.Category.VISUALS);
    }

    private DialogAction callback(BiConsumer<DialogResponseView, Player> callback) {
        return DialogAction.customClick((response, audience) -> {
            if (audience instanceof Player player && player.isOnline()) {
                callback.accept(response, player);
            }
        }, CALLBACK_OPTIONS);
    }
}
