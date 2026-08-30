package bot.mgx.accessbridge;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Every clan, A to Z, in the same shape as a leaderboard.
 *
 * <p>The chest directory paged eleven at a time and made a name something you read
 * rather than something you press. Here the row is the link, exactly as a player's
 * name is on the boards, and clicking one opens the page {@code /claninfo} opens.
 */
final class ClanDirectoryService {
    private static final ClickCallback.Options CALLBACK_OPTIONS = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(Duration.ofMinutes(10))
            .build();
    /** Enough that most servers never page, few enough that the dialog still fits. */
    private static final int PER_PAGE = 14;

    private final ClanStore clans;
    private final ClanMenuService menus;
    private final ClanBattleStore clanBattles;
    private final SettingsClientSupport clientSupport;
    private ClanDialogService clanDialogs;

    ClanDirectoryService(
            ClanStore clans,
            ClanMenuService menus,
            ClanBattleStore clanBattles,
            SettingsClientSupport clientSupport
    ) {
        this.clans = clans;
        this.menus = menus;
        this.clanBattles = clanBattles;
        this.clientSupport = clientSupport;
    }

    void useClanDialogs(ClanDialogService clanDialogs) {
        this.clanDialogs = clanDialogs;
    }

    void open(Player viewer, int page) {
        open(viewer, page, null);
    }

    void open(Player viewer, int page, Consumer<Player> back) {
        if (!clientSupport.supportsDialogs(viewer)) {
            menus.openList(viewer, page, back == null
                    ? null : Menu.Destination.of(Menu.Kind.CLAN_HUB));
            return;
        }
        // ClanStore.list() is already sorted by name, case-insensitively.
        List<ClanStore.ClanView> all = clans.list();
        if (all.isEmpty()) {
            show(viewer, List.of(DialogBody.plainMessage(
                    MenuText.body("No clans yet. Found one with /clans create <name>."), 400
            )), List.of(), back);
            return;
        }
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        int current = Math.clamp(page, 1, pages);
        int first = (current - 1) * PER_PAGE;
        int last = Math.min(all.size(), first + PER_PAGE);

        List<DialogBody> body = new ArrayList<>();
        for (int index = first; index < last; index++) {
            body.add(DialogBody.plainMessage(row(all.get(index), current, back), 400));
        }
        List<ActionButton> buttons = new ArrayList<>();
        if (current > 1) {
            buttons.add(button("Previous", audience -> open(audience, current - 1, back)));
        }
        if (current < pages) {
            buttons.add(button("Next", audience -> open(audience, current + 1, back)));
        }
        show(viewer, body, buttons, back);
    }

    /** {@code [NAME] ★ ◆  —  12 members} , the whole line clickable. */
    private Component row(ClanStore.ClanView clan, int page, Consumer<Player> back) {
        Component tag = Component.text("[" + clan.name() + "]",
                TextColor.color(clan.themeColor()));
        if (clan.level() > 0) {
            tag = tag.append(Component.text(" " + ClanLevel.badge(clan.level()),
                    TextColor.color(ClanLevel.badgeColor(clan.level()))));
        }
        String medals = ClanTag.plainMedals(clanBattles.badges(clan.id())).strip();
        if (!medals.isBlank()) {
            tag = tag.append(Component.text("  " + medals, MenuText.GOLD));
        }
        UUID clanId = clan.id();
        return tag
                .append(Component.text("  —  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(
                        clan.members().size() + " member" + (clan.members().size() == 1 ? "" : "s"),
                        MenuText.VALUE
                ))
                .hoverEvent(HoverEvent.showText(Component.text("View this clan", MenuText.LABEL)))
                .clickEvent(ClickEvent.callback(audience -> {
                    if (audience instanceof Player clicker && clicker.isOnline()) {
                        if (clanDialogs != null) {
                            clanDialogs.openInfo(
                                    clicker, clanId, viewer -> open(viewer, page, back));
                        } else {
                            clans.findClanById(clanId).ifPresentOrElse(
                                    found -> menus.openInfo(clicker, found, null),
                                    () -> PlayerMenuService.error(clicker, "That clan is gone.")
                            );
                        }
                    }
                }, CALLBACK_OPTIONS));
    }

    private ActionButton button(String label, java.util.function.Consumer<Player> run) {
        return ActionButton.builder(MenuText.buttonLabel(label, NamedTextColor.WHITE))
                .width(150)
                .action(callback((response, audience) -> run.accept(audience)))
                .build();
    }

    private void show(
            Player viewer,
            List<DialogBody> body,
            List<ActionButton> buttons,
            Consumer<Player> back
    ) {
        Screens.show(viewer, "Clans",
                body.isEmpty()
                        ? Screens.body("Click a clan to open it.")
                        : body,
                buttons, 2, back);
    }

    private DialogAction callback(BiConsumer<DialogResponseView, Player> callback) {
        return DialogAction.customClick((response, audience) -> {
            if (audience instanceof Player player && player.isOnline()) {
                callback.accept(response, player);
            }
        }, CALLBACK_OPTIONS);
    }
}
