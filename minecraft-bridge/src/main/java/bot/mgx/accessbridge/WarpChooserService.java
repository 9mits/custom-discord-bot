package bot.mgx.accessbridge;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
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

    /** Kept as the entry point; there is no chooser screen any more. */
    void open(Player player) {
        openServerWarps(player, 1);
    }

    /**
     * The public warps, which had no screen of their own at all: the Warps button led
     * straight into the chest list for everybody, clan or not.
     */
    void openServerWarps(Player player, int page) {
        List<String> names = warps.warpNamesOf();
        int pages = Math.max(1, (names.size() + PER_PAGE - 1) / PER_PAGE);
        int current = Math.clamp(page, 1, pages);
        int first = (current - 1) * PER_PAGE;
        int last = Math.min(names.size(), first + PER_PAGE);
        if (!clientSupport.supportsDialogs(player)) {
            List<BedrockForms.Button> buttons = new ArrayList<>();
            for (int index = first; index < last; index++) {
                String name = names.get(index);
                buttons.add(new BedrockForms.Button(name,
                        () -> player.performCommand("essentials:warp " + name)));
            }
            addBedrockPager(buttons, player, current, pages);
            if (clans.clanOf(player.getUniqueId()).isPresent()) {
                buttons.add(new BedrockForms.Button("Clan Warps", () ->
                        clanWarps.open(player, viewer -> openServerWarps(viewer, current))));
            }
            if (!forms.menu(player, "Warps",
                    names.isEmpty() ? "No warps yet."
                            : "Page " + current + " of " + pages + ".",
                    buttons)) {
                warps.openWarps(player, current);
            }
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (int index = first; index < last; index++) {
            String name = names.get(index);
            buttons.add(ActionButton.builder(Component.empty()
                            .append(MenuText.sprite("block/lodestone_top"))
                            .append(Component.text(" " + name, NamedTextColor.WHITE)))
                    .tooltip(Component.text("Travel to this warp.", MenuText.LABEL))
                    .width(150)
                    .action(callback((response, audience) -> {
                        audience.closeDialog();
                        audience.performCommand("essentials:warp " + name);
                    }))
                    .build());
        }
        if (current > 1) {
            buttons.add(plain("Previous", audience -> openServerWarps(audience, current - 1)));
        }
        if (current < pages) {
            buttons.add(plain("Next", audience -> openServerWarps(audience, current + 1)));
        }
        if (clans.clanOf(player.getUniqueId()).isPresent()) {
            buttons.add(Screens.button("item/ender_pearl", "Clan Warps",
                    "Places your clan has set.", audience ->
                            clanWarps.open(audience,
                                    viewer -> openServerWarps(viewer, current))));
        }
        Screens.show(player, "Warps",
                Screens.body(names.isEmpty() ? "No warps yet."
                        : "Page " + current + " of " + pages + "."),
                buttons, 2, null);
    }

    private void addBedrockPager(
            List<BedrockForms.Button> buttons, Player player, int page, int pages
    ) {
        if (page > 1) {
            buttons.add(new BedrockForms.Button(
                    "Previous Page", () -> openServerWarps(player, page - 1)));
        }
        if (page < pages) {
            buttons.add(new BedrockForms.Button(
                    "Next Page", () -> openServerWarps(player, page + 1)));
        }
    }

    private ActionButton plain(String label, java.util.function.Consumer<Player> run) {
        return ActionButton.builder(MenuText.buttonLabel(label, NamedTextColor.WHITE))
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
