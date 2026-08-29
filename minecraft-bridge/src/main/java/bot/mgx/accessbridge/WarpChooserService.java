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
 * Asks which warps were meant: the server's, or the player's clan's.
 *
 * <p>Skipped entirely for anyone without a clan — a chooser with one real option is
 * a click charged for nothing.
 */
final class WarpChooserService {
    private static final ClickCallback.Options CALLBACK_OPTIONS = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(Duration.ofMinutes(10))
            .build();

    private static final int PER_PAGE = 14;

    private final TeleportMenuService warps;
    private final ClanWarpDialogService clanWarps;
    private final ClanStore clans;
    private final SettingsClientSupport clientSupport;
    private final BedrockForms forms;

    WarpChooserService(
            TeleportMenuService warps,
            ClanWarpDialogService clanWarps,
            ClanStore clans,
            SettingsClientSupport clientSupport,
            BedrockForms forms
    ) {
        this.warps = warps;
        this.clanWarps = clanWarps;
        this.clans = clans;
        this.clientSupport = clientSupport;
        this.forms = forms;
    }

    void open(Player player) {
        boolean hasClan = clans.clanOf(player.getUniqueId()).isPresent();
        if (!hasClan) {
            // No clan means no choice to make, so skip straight to the server warps
            // rather than charging a click for a menu with one real option.
            openServerWarps(player, 1);
            return;
        }
        if (!clientSupport.supportsDialogs(player)) {
            // Without this a Bedrock player could not reach their clan warps from the
            // Warps button at all, which is the whole reason the chooser exists.
            boolean shown = forms.menu(player, "Warps", "Where are you going?", List.of(
                    new BedrockForms.Button("Server Warps", () -> openServerWarps(player, 1)),
                    new BedrockForms.Button("Clan Warps", () -> clanWarps.open(player))
            ));
            if (!shown) {
                openServerWarps(player, 1);
            }
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button("block/lodestone_top", "Server Warps",
                "Public places anyone can reach.", audience -> openServerWarps(audience, 1)));
        buttons.add(button("item/ender_pearl", "Clan Warps",
                "Places your clan has set.", clanWarps::open));
        buttons.add(ActionButton.builder(Component.text("Close", MenuText.LABEL))
                .width(150)
                .action(callback((response, audience) -> audience.closeDialog()))
                .build());
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title("Warps"))
                        .body(List.of(DialogBody.plainMessage(
                                MenuText.body("Where are you going?"), 400
                        )))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons).columns(2).build()));
        player.showDialog(dialog);
    }

    /**
     * The public warps, which had no screen of their own at all: the Warps button led
     * straight into the chest list for everybody, clan or not.
     */
    void openServerWarps(Player player, int page) {
        List<String> names = warps.warpNamesOf();
        if (!clientSupport.supportsDialogs(player)) {
            List<BedrockForms.Button> buttons = new ArrayList<>();
            for (String name : names) {
                buttons.add(new BedrockForms.Button(name,
                        () -> player.performCommand("essentials:warp " + name)));
            }
            if (!forms.menu(player, "Warps",
                    names.isEmpty() ? "No warps yet." : "Choose a warp.", buttons)) {
                warps.openWarps(player, page);
            }
            return;
        }
        int pages = Math.max(1, (names.size() + PER_PAGE - 1) / PER_PAGE);
        int current = Math.clamp(page, 1, pages);
        int first = (current - 1) * PER_PAGE;
        int last = Math.min(names.size(), first + PER_PAGE);
        List<ActionButton> buttons = new ArrayList<>();
        for (int index = first; index < last; index++) {
            String name = names.get(index);
            buttons.add(ActionButton.builder(Component.empty()
                            .append(MenuText.sprite("block/lodestone_top"))
                            .append(Component.text(" " + name, NamedTextColor.WHITE)))
                    .tooltip(Component.text("Travel to this warp.", MenuText.LABEL))
                    .width(150)
                    .action(callback((response, audience) ->
                            audience.performCommand("essentials:warp " + name)))
                    .build());
        }
        if (current > 1) {
            buttons.add(plain("Previous", audience -> openServerWarps(audience, current - 1)));
        }
        if (current < pages) {
            buttons.add(plain("Next", audience -> openServerWarps(audience, current + 1)));
        }
        buttons.add(ActionButton.builder(Component.text("Close", MenuText.LABEL))
                .width(150)
                .action(callback((response, audience) -> audience.closeDialog()))
                .build());
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title("Warps"))
                        .body(List.of(DialogBody.plainMessage(MenuText.body(
                                names.isEmpty() ? "No warps yet." : "Choose a warp."
                        ), 400)))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons).columns(2).build()));
        player.showDialog(dialog);
    }

    private ActionButton plain(String label, java.util.function.Consumer<Player> run) {
        return ActionButton.builder(Component.text(label, NamedTextColor.WHITE))
                .width(150)
                .action(callback((response, audience) -> run.accept(audience)))
                .build();
    }

    private ActionButton button(
            String sprite, String label, String tooltip, java.util.function.Consumer<Player> run
    ) {
        return ActionButton.builder(Component.empty()
                        .append(MenuText.sprite(sprite))
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
