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
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** Native Java settings dialogs with the inventory screens as a safe fallback. */
final class PlayerSettingsDialogService {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private static final TextColor GOLD = TextColor.color(0xFFB52E);
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
            if (category == PlayerSettingsStore.Category.PRIVACY) {
                showPrivacyDialog(player);
            } else {
                showCategoryDialog(player, category);
            }
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
                    .tooltip(Component.text(category.description(), NamedTextColor.GRAY))
                    .width(280)
                    .action(callback((response, audience) -> openCategory(audience, category)))
                    .build());
        }
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Settings", ORANGE, TextDecoration.BOLD))
                        .body(List.of(DialogBody.plainMessage(Component.text(
                                "Choose a category to configure your server experience.",
                                NamedTextColor.GRAY
                        ), 360)))
                        .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                        .build())
                .type(DialogType.multiAction(categories)
                        .columns(1)
                        .exitAction(ActionButton.create(
                                Component.text("Close", NamedTextColor.GRAY),
                                null,
                                100,
                                callback((response, audience) -> audience.closeDialog())
                        ))
                        .build()));
        player.showDialog(dialog);
    }

    private void showCategoryDialog(Player player, PlayerSettingsStore.Category category) {
        List<PlayerSettingsStore.Setting> settings = category.settings();
        List<DialogInput> inputs = new ArrayList<>();
        for (PlayerSettingsStore.Setting setting : settings) {
            inputs.add(DialogInput.bool(setting.key(), Component.text(setting.label()))
                    .initial(store.isEnabled(player.getUniqueId(), setting))
                    .build());
        }

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(category.label(), ORANGE, TextDecoration.BOLD))
                        .body(List.of(DialogBody.plainMessage(settingDescriptions(settings), 420)))
                        .inputs(inputs)
                        .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.create(
                                Component.text("Save", NamedTextColor.GREEN, TextDecoration.BOLD),
                                Component.text("Save these settings.", NamedTextColor.GRAY),
                                120,
                                callback((response, audience) -> save(audience, category, settings, response))
                        ),
                        ActionButton.create(
                                Component.text("Back", NamedTextColor.GRAY),
                                Component.text("Return to all settings.", NamedTextColor.GRAY),
                                120,
                                callback((response, audience) -> open(audience))
                        )
                )));
        player.showDialog(dialog);
    }

    private void showPrivacyDialog(Player player) {
        boolean visible = menus.discordNameVisible(player.getUniqueId());
        ActionButton toggle = ActionButton.create(
                Component.text(
                        visible ? "Discord name: ON" : "Discord name: OFF",
                        visible ? NamedTextColor.GREEN : NamedTextColor.RED,
                        TextDecoration.BOLD
                ),
                Component.text(
                        visible ? "Click to hide your linked name." : "Click to show your linked name.",
                        NamedTextColor.GRAY
                ),
                260,
                callback((response, audience) -> toggleDiscordName(audience))
        );
        ActionButton back = ActionButton.create(
                Component.text("Back", NamedTextColor.GRAY),
                Component.text("Return to all settings.", NamedTextColor.GRAY),
                120,
                callback((response, audience) -> open(audience))
        );
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Privacy", ORANGE, TextDecoration.BOLD))
                        .body(List.of(DialogBody.plainMessage(Component.text(
                                "Control whether other players can see your linked Discord name.",
                                NamedTextColor.GRAY
                        ), 380)))
                        .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                        .build())
                .type(DialogType.multiAction(List.of(toggle, back))
                        .columns(1)
                        .build()));
        player.showDialog(dialog);
    }

    private void save(
            Player player,
            PlayerSettingsStore.Category category,
            List<PlayerSettingsStore.Setting> settings,
            DialogResponseView response
    ) {
        Map<PlayerSettingsStore.Setting, Boolean> requested = new LinkedHashMap<>();
        for (PlayerSettingsStore.Setting setting : settings) {
            Boolean enabled = response.getBoolean(setting.key());
            if (enabled == null) {
                PlayerMenuService.error(player, "That settings response was incomplete. Please try again.");
                openCategory(player, category);
                return;
            }
            requested.put(setting, enabled);
        }
        try {
            store.setEnabled(player.getUniqueId(), requested);
            plugin.refreshClans();
            player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                    "Settings saved.", NamedTextColor.GREEN
            )));
            openCategory(player, category);
        } catch (UncheckedIOException exception) {
            plugin.getLogger().warning("Could not save player settings: " + exception.getMessage());
            PlayerMenuService.error(player, "Those settings could not be saved. Please try again.");
            openCategory(player, category);
        }
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

    private DialogAction callback(BiConsumer<DialogResponseView, Player> callback) {
        return DialogAction.customClick((response, audience) -> {
            if (audience instanceof Player player && player.isOnline()) {
                callback.accept(response, player);
            }
        }, CALLBACK_OPTIONS);
    }

    private static Component settingDescriptions(List<PlayerSettingsStore.Setting> settings) {
        Component descriptions = Component.empty();
        for (int index = 0; index < settings.size(); index++) {
            PlayerSettingsStore.Setting setting = settings.get(index);
            if (index > 0) {
                descriptions = descriptions.append(Component.newline());
            }
            descriptions = descriptions
                    .append(Component.text(setting.label() + ": ", GOLD, TextDecoration.BOLD))
                    .append(Component.text(setting.description(), NamedTextColor.GRAY));
        }
        return descriptions;
    }
}
