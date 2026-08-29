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

    private final TeleportMenuService warps;
    private final ClanWarpDialogService clanWarps;
    private final ClanStore clans;
    private final SettingsClientSupport clientSupport;

    WarpChooserService(
            TeleportMenuService warps,
            ClanWarpDialogService clanWarps,
            ClanStore clans,
            SettingsClientSupport clientSupport
    ) {
        this.warps = warps;
        this.clanWarps = clanWarps;
        this.clans = clans;
        this.clientSupport = clientSupport;
    }

    void open(Player player) {
        boolean hasClan = clans.clanOf(player.getUniqueId()).isPresent();
        if (!hasClan || !clientSupport.supportsDialogs(player)) {
            warps.openWarps(player, 1);
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button("block/lodestone_top", "Server Warps",
                "Public places anyone can reach.", audience -> warps.openWarps(audience, 1)));
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
