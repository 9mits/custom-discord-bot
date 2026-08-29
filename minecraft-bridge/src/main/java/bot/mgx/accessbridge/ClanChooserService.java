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
import org.bukkit.Material;
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
    private final ClanStore clans;
    private final ClanBattleStore clanBattles;
    private final SettingsClientSupport clientSupport;

    ClanChooserService(
            ClanMenuService menus,
            ClanStore clans,
            ClanBattleStore clanBattles,
            SettingsClientSupport clientSupport
    ) {
        this.menus = menus;
        this.clans = clans;
        this.clanBattles = clanBattles;
        this.clientSupport = clientSupport;
    }

    void open(Player player) {
        ClanStore.ClanView own = clans.clanOf(player.getUniqueId()).orElse(null);
        if (!clientSupport.supportsDialogs(player)) {
            // Without dialogs the directory is still the safe landing: it works whether
            // or not the player has a clan.
            if (own == null) {
                menus.openList(player, 1);
            } else {
                menus.openHub(player);
            }
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        if (own == null) {
            buttons.add(button(Material.BARRIER, "No Clan Yet",
                    "Found one with /clans create <name>.", audience -> audience.closeDialog()));
        } else {
            String medals = ClanTag.plainMedals(clanBattles.badges(own.id())).strip();
            buttons.add(button(Material.SHIELD, "My Clan",
                    "[" + own.name() + "]" + (medals.isBlank() ? "" : "  " + medals),
                    menus::openHub));
        }
        buttons.add(button(Material.SPYGLASS, "Browse Clans",
                "Every clan, richest first.", audience -> menus.openList(audience, 1)));
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

    private ActionButton button(
            Material icon, String label, String tooltip, java.util.function.Consumer<Player> run
    ) {
        return ActionButton.builder(Component.empty()
                        .append(MenuText.icon(icon))
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
