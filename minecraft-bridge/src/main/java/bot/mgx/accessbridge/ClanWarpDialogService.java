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
 * A clan's own warps, in the same shape as the public ones.
 *
 * <p>They were only reachable from inside the clan hub, several clicks past a screen
 * about donations and levels, which is a long way from where somebody stands when
 * they want to travel. The Warps button now offers both.
 */
final class ClanWarpDialogService {
    private static final ClickCallback.Options CALLBACK_OPTIONS = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(Duration.ofMinutes(10))
            .build();

    private final ClanStore clans;
    private final ClanMenuService menus;
    private final SettingsClientSupport clientSupport;

    ClanWarpDialogService(
            ClanStore clans, ClanMenuService menus, SettingsClientSupport clientSupport
    ) {
        this.clans = clans;
        this.menus = menus;
        this.clientSupport = clientSupport;
    }

    void open(Player player) {
        ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElse(null);
        if (clan == null) {
            PlayerMenuService.error(player, "You are not in a clan.");
            return;
        }
        if (!clientSupport.supportsDialogs(player)) {
            menus.openWarps(player);
            return;
        }
        List<String> names = clan.warps().keySet().stream().sorted(
                String.CASE_INSENSITIVE_ORDER
        ).toList();
        List<ActionButton> buttons = new ArrayList<>();
        for (String name : names) {
            buttons.add(ActionButton.builder(Component.empty()
                            .append(MenuText.sprite("item/ender_pearl"))
                            .append(Component.text(" " + name, NamedTextColor.WHITE)))
                    .tooltip(Component.text("Travel to this clan warp.", MenuText.LABEL))
                    .width(150)
                    .action(callback((response, audience) -> travel(audience, name)))
                    .build());
        }
        buttons.add(ActionButton.builder(Component.text("Close", MenuText.LABEL))
                .width(150)
                .action(callback((response, audience) -> audience.closeDialog()))
                .build());

        int slots = ClanLevel.warpSlots(clan.level());
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title(clan.name() + " Warps"))
                        .body(List.of(DialogBody.plainMessage(MenuText.body(
                                names.isEmpty()
                                        ? "No clan warps yet. Set one with /clans setwarp <name>."
                                        : names.size() + " of " + slots + " used."
                        ), 400)))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons).columns(2).build()));
        player.showDialog(dialog);
    }

    /** The warmup, permissions and world checks stay with the existing command path. */
    private void travel(Player player, String warp) {
        try {
            menus.useWarp(player, warp);
        } catch (ClanStore.ClanException failure) {
            PlayerMenuService.error(player, failure.getMessage());
        }
    }

    private DialogAction callback(BiConsumer<DialogResponseView, Player> callback) {
        return DialogAction.customClick((response, audience) -> {
            if (audience instanceof Player player && player.isOnline()) {
                callback.accept(response, player);
            }
        }, CALLBACK_OPTIONS);
    }
}
