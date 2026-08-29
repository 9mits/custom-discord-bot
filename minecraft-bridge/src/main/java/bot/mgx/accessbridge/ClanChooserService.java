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
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Asks which clan screen was meant.
 *
 * <p>{@code /clans} used to go straight to the player's own clan and refuse anyone
 * without one, which hid the directory from exactly the people looking for a clan to
 * join. The two are different questions, so the menu asks.
 */
final class ClanChooserService {
    private static final ClickCallback.Options CALLBACK_OPTIONS = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(Duration.ofMinutes(10))
            .build();

    private final ClanMenuService menus;
    private ClanDirectoryService directory;
    private ClanWarpDialogService clanWarps;
    private ClanDialogService clanDialogs;
    private final ClanStore clans;
    private final ClanBattleStore clanBattles;
    private final SettingsClientSupport clientSupport;
    private final BedrockForms forms;

    ClanChooserService(
            ClanMenuService menus,
            ClanStore clans,
            ClanBattleStore clanBattles,
            SettingsClientSupport clientSupport,
            BedrockForms forms
    ) {
        this.menus = menus;
        this.clans = clans;
        this.clanBattles = clanBattles;
        this.clientSupport = clientSupport;
        this.forms = forms;
    }

    /** Wired after construction; the directory and the chooser reference each other. */
    void useDirectory(ClanDirectoryService directory) {
        this.directory = directory;
    }

    void useClanWarps(ClanWarpDialogService clanWarps) {
        this.clanWarps = clanWarps;
    }

    void useClanDialogs(ClanDialogService clanDialogs) {
        this.clanDialogs = clanDialogs;
    }

    void open(Player player) {
        ClanStore.ClanView own = clans.clanOf(player.getUniqueId()).orElse(null);
        if (!clientSupport.supportsDialogs(player)) {
            List<BedrockForms.Button> choices = new ArrayList<>();
            if (own != null) {
                choices.add(new BedrockForms.Button("My Clan", () -> openOwn(player)));
                choices.add(new BedrockForms.Button("Clan Warps", () -> {
                    if (clanWarps != null) {
                        clanWarps.open(player);
                    } else {
                        menus.openWarps(player);
                    }
                }));
            }
            choices.add(new BedrockForms.Button("Browse Clans", () -> browse(player)));
            if (!forms.menu(player, "Clans",
                    own == null ? "You are not in a clan yet." : "Your clan, or everyone else's.",
                    choices)) {
                if (own == null) {
                    browse(player);
                } else {
                    openOwn(player);
                }
            }
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        if (own == null) {
            buttons.add(button("item/barrier", "No Clan Yet",
                    "Found one with /clans create <name>.", audience -> audience.closeDialog()));
        } else {
            String medals = ClanTag.plainMedals(clanBattles.badges(own.id())).strip();
            buttons.add(button("item/iron_chestplate", "My Clan",
                    "[" + own.name() + "]" + (medals.isBlank() ? "" : "  " + medals),
                    menus::openHub));
        }
        if (own != null) {
            buttons.add(button("item/ender_pearl", "Clan Warps",
                    "Places your clan has set.",
                    audience -> {
                        if (clanWarps != null) {
                            clanWarps.open(audience);
                        } else {
                            menus.openWarps(audience);
                        }
                    }));
        }
        buttons.add(button("item/spyglass", "Browse Clans",
                "Every clan, A to Z.", this::browse));
        buttons.add(ActionButton.builder(Component.text("Close", MenuText.LABEL))
                .width(150)
                .action(callback((response, audience) -> audience.closeDialog()))
                .build());

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title("Clans"))
                        .body(List.of(DialogBody.plainMessage(MenuText.body(
                                own == null
                                        ? "You are not in a clan yet."
                                        : "Your clan, or everyone else's."
                        ), 400)))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons).columns(2).build()));
        player.showDialog(dialog);
    }

    private void openOwn(Player player) {
        if (clanDialogs != null) {
            clanDialogs.openHub(player);
        } else {
            menus.openHub(player);
        }
    }

    private void browse(Player player) {
        if (directory != null) {
            directory.open(player, 1);
        } else {
            menus.openList(player, 1);
        }
    }

    private ActionButton button(
            String icon, String label, String tooltip, java.util.function.Consumer<Player> run
    ) {
        return ActionButton.builder(Component.empty()
                        .append(MenuText.sprite(icon))
                        .append(Component.text(" " + label, NamedTextColor.WHITE)))
                .tooltip(Component.text(tooltip, MenuText.LABEL))
                .width(150)
                .action(callback((response, audience) -> run.accept(audience)))
                .build();
    }

    private DialogAction callback(BiConsumer<DialogResponseView, Player> callback) {
        return DialogAction.customClick((response, audience) -> {
            if (audience instanceof Player player && player.isOnline()) {
                callback.accept(response, player);
            }
        }, CALLBACK_OPTIONS);
    }
}
