package bot.mgx.accessbridge;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
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

    /** Dropdown holding the category. Namespaced so it cannot collide with a setting key. */
    private static final String CATEGORY_KEY = "mgx_category";
    /** Privacy has no stored Setting, so its one toggle needs a key of its own. */
    private static final String DISCORD_NAME_KEY = "mgx_discord_name";

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
        openCategory(player, PlayerSettingsStore.Category.values()[0]);
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
            showSettingsDialog(player, category);
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().warning(
                    "Could not open the native settings dialog: " + exception.getMessage()
            );
            menus.openSettingsCategory(player, category);
        }
    }

    /**
     * One page for every category. The dropdown swaps which toggles are shown, so a
     * player changing two things in different categories never walks a menu tree.
     */
    private void showSettingsDialog(Player player, PlayerSettingsStore.Category current) {
        List<PlayerSettingsStore.Setting> settings = current.settings();
        boolean privacy = current == PlayerSettingsStore.Category.PRIVACY;

        List<SingleOptionDialogInput.OptionEntry> options = new ArrayList<>();
        for (PlayerSettingsStore.Category category : PlayerSettingsStore.Category.values()) {
            options.add(SingleOptionDialogInput.OptionEntry.create(
                    category.name(),
                    Component.text(category.label()),
                    category == current
            ));
        }

        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(DialogInput.singleOption(
                        CATEGORY_KEY,
                        Component.text("Category", GOLD, TextDecoration.BOLD),
                        options
                )
                .width(300)
                .labelVisible(true)
                .build());
        for (PlayerSettingsStore.Setting setting : settings) {
            inputs.add(DialogInput.bool(setting.key(), Component.text(setting.label()))
                    .initial(store.isEnabled(player.getUniqueId(), setting))
                    .build());
        }
        if (privacy) {
            inputs.add(DialogInput.bool(DISCORD_NAME_KEY, Component.text("Show Discord name"))
                    .initial(menus.discordNameVisible(player.getUniqueId()))
                    .build());
        }

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Settings", ORANGE, TextDecoration.BOLD))
                        .body(List.of(DialogBody.plainMessage(body(current, settings, privacy), 420)))
                        .inputs(inputs)
                        .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.create(
                                Component.text("Apply", NamedTextColor.GREEN, TextDecoration.BOLD),
                                Component.text(
                                        "Save these toggles and show the selected category.",
                                        NamedTextColor.GRAY
                                ),
                                140,
                                callback((response, audience) ->
                                        apply(audience, current, settings, privacy, response))
                        ),
                        ActionButton.create(
                                Component.text("Close", NamedTextColor.GRAY),
                                null,
                                140,
                                callback((response, audience) -> audience.closeDialog())
                        )
                )));
        player.showDialog(dialog);
    }

    private void apply(
            Player player,
            PlayerSettingsStore.Category shown,
            List<PlayerSettingsStore.Setting> settings,
            boolean privacy,
            DialogResponseView response
    ) {
        // The dropdown is read first so a failed save still lands the player where they
        // asked to go, rather than bouncing them back to the category they were leaving.
        PlayerSettingsStore.Category next = PlayerSettingsStore.Category.fromId(
                response.getText(CATEGORY_KEY), shown
        );
        Map<PlayerSettingsStore.Setting, Boolean> requested = new LinkedHashMap<>();
        for (PlayerSettingsStore.Setting setting : settings) {
            Boolean enabled = response.getBoolean(setting.key());
            if (enabled == null) {
                PlayerMenuService.error(player, "That settings response was incomplete. Please try again.");
                openCategory(player, shown);
                return;
            }
            requested.put(setting, enabled);
        }
        try {
            if (!requested.isEmpty()) {
                store.setEnabled(player.getUniqueId(), requested);
                plugin.refreshClans();
            }
        } catch (UncheckedIOException exception) {
            plugin.getLogger().warning("Could not save player settings: " + exception.getMessage());
            PlayerMenuService.error(player, "Those settings could not be saved. Please try again.");
            openCategory(player, shown);
            return;
        }
        if (privacy && !applyDiscordName(player, response)) {
            openCategory(player, shown);
            return;
        }
        if (!requested.isEmpty() || privacy) {
            player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                    "Settings saved.", NamedTextColor.GREEN
            )));
        }
        openCategory(player, next);
    }

    /** Returns false when the preference could not be stored and the player was told. */
    private boolean applyDiscordName(Player player, DialogResponseView response) {
        Boolean wanted = response.getBoolean(DISCORD_NAME_KEY);
        if (wanted == null || wanted == menus.discordNameVisible(player.getUniqueId())) {
            return true;
        }
        try {
            menus.toggleDiscordName(player.getUniqueId());
            return true;
        } catch (IllegalStateException exception) {
            PlayerMenuService.error(player, exception.getMessage());
            return false;
        } catch (UncheckedIOException exception) {
            plugin.getLogger().warning(
                    "Could not save a Discord-name preference: " + exception.getMessage()
            );
            PlayerMenuService.error(player, "That setting could not be saved. Please try again.");
            return false;
        }
    }

    private DialogAction callback(BiConsumer<DialogResponseView, Player> callback) {
        return DialogAction.customClick((response, audience) -> {
            if (audience instanceof Player player && player.isOnline()) {
                callback.accept(response, player);
            }
        }, CALLBACK_OPTIONS);
    }

    private static Component body(
            PlayerSettingsStore.Category category,
            List<PlayerSettingsStore.Setting> settings,
            boolean privacy
    ) {
        Component body = Component.text(category.description(), NamedTextColor.GRAY);
        if (privacy) {
            return body.append(Component.newline())
                    .append(Component.text("Show Discord name: ", GOLD, TextDecoration.BOLD))
                    .append(Component.text(
                            "Let other players see the account you linked.", NamedTextColor.GRAY
                    ));
        }
        for (PlayerSettingsStore.Setting setting : settings) {
            body = body.append(Component.newline())
                    .append(Component.text(setting.label() + ": ", GOLD, TextDecoration.BOLD))
                    .append(Component.text(setting.description(), NamedTextColor.GRAY));
        }
        return body;
    }
}
