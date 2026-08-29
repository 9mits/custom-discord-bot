package bot.mgx.accessbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The leaderboards as dialogs: a board chooser, then ten ranked rows with the
 * player's own face on each, and a card for whoever is clicked.
 *
 * <p>The rows come from the published snapshot rather than a fresh scan, so opening a
 * board costs nothing and everyone reading it sees the same figures.
 */
final class LeaderboardDialogService {
    private static final ClickCallback.Options CALLBACK_OPTIONS = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(Duration.ofMinutes(10))
            .build();

    /** The boards this screen offers, and how each one names its figure. */
    private enum Board {
        WEALTH("Money", "individual", "wealth"),
        KILLS("Kills", "individual", "kills"),
        AMETHYST_CRATES("Amethyst Crates", "individual", "amethyst_crates"),
        AMETHYST_AIRDROPS("Amethyst Airdrops", "individual", "amethyst_airdrops"),
        CLAN_WEALTH("Clan Treasury", "clan", "wealth"),
        CLAN_KILLS("Clan Kills", "clan", "kills"),
        CLAN_BATTLE("Clan Battle", "clan", "clan_battle");

        private final String label;
        private final String scope;
        private final String key;

        Board(String label, String scope, String key) {
            this.label = label;
            this.scope = scope;
            this.key = key;
        }
    }

    private final LeaderboardService boards;
    private final StatsDialogService stats;
    private final ClanBattleStore clanBattles;
    private final ClanStore clans;

    LeaderboardDialogService(
            LeaderboardService boards,
            StatsDialogService stats,
            ClanBattleStore clanBattles,
            ClanStore clans
    ) {
        this.boards = boards;
        this.stats = stats;
        this.clanBattles = clanBattles;
        this.clans = clans;
    }

    void openHub(Player viewer) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Board board : Board.values()) {
            String label = board == Board.CLAN_BATTLE
                    ? clanBattles.active(clans).map(active -> active.kind().displayName())
                            .orElse("Clan Battle")
                    : board.label;
            buttons.add(ActionButton.builder(Component.text(label, NamedTextColor.WHITE))
                    .tooltip(Component.text("Open this board", MenuText.LABEL))
                    .width(150)
                    .action(callback((response, audience) -> openBoard(audience, board)))
                    .build());
        }
        buttons.add(ActionButton.builder(Component.text("Close", MenuText.LABEL))
                .width(150)
                .action(callback((response, audience) -> audience.closeDialog()))
                .build());
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title("Leaderboards"))
                        .body(List.of(DialogBody.plainMessage(
                                MenuText.body("Pick a board."), 400
                        )))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons).columns(3).build()));
        viewer.showDialog(dialog);
    }

    private void openBoard(Player viewer, Board board) {
        JsonArray rows = rows(board);
        List<DialogBody> body = new ArrayList<>();
        if (rows.isEmpty()) {
            body.add(DialogBody.plainMessage(
                    MenuText.body("No standings yet. Play a little and this fills in."), 400
            ));
        }
        for (int index = 0; index < rows.size(); index++) {
            JsonObject row = rows.get(index).getAsJsonObject();
            int rank = row.has("rank") ? row.get("rank").getAsInt() : index + 1;
            String display = text(row, "display");
            if (board.scope.equals("clan")) {
                body.add(DialogBody.plainMessage(clanRow(rank, row, display), 400));
                continue;
            }
            UUID id = uuid(row);
            String name = text(row, "player");
            Component line = MenuText.rankedRow(
                    rank, id, name, Component.text(display, MenuText.VALUE)
            );
            if (id != null) {
                // The row itself is the link. A separate button per player is a second
                // list of the same names, and it is not how anyone reads a board.
                line = line
                        .hoverEvent(HoverEvent.showText(
                                Component.text("View profile", MenuText.LABEL)))
                        .clickEvent(ClickEvent.callback(
                                audience -> {
                                    if (audience instanceof Player clicker && clicker.isOnline()) {
                                        stats.openCard(clicker, id, name);
                                    }
                                },
                                CALLBACK_OPTIONS
                        ));
            }
            body.add(DialogBody.plainMessage(line, 400));
        }
        List<ActionButton> buttons = List.of(
                ActionButton.builder(Component.text("Back", MenuText.LABEL))
                        .width(150)
                        .action(callback((response, audience) -> openHub(audience)))
                        .build()
        );
        String title = board == Board.CLAN_BATTLE
                ? clanBattles.active(clans).map(active -> active.kind().displayName())
                        .orElse("Clan Battle")
                : "Top " + board.label;
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title(title))
                        .body(List.copyOf(body))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons).columns(1).build()));
        viewer.showDialog(dialog);
    }

    /** A clan row carries its tag and battle medals instead of a face. */
    private Component clanRow(int rank, JsonObject row, String display) {
        String name = text(row, "clan");
        int colour = row.has("colour") ? row.get("colour").getAsInt() : 0xFF9900;
        int level = row.has("level") ? row.get("level").getAsInt() : 0;
        Component tag = Component.text(
                "[" + name + "]" + (level > 0 ? " " + ClanLevel.badge(level) : ""),
                net.kyori.adventure.text.format.TextColor.color(colour)
        );
        String badges = text(row, "badges");
        return Component.text("#" + rank + " ", MenuText.placeColour(rank))
                .append(tag)
                .append(Component.text(badges.isBlank() ? " " : "  " + badges + " ",
                        NamedTextColor.WHITE))
                .append(Component.text("— ", NamedTextColor.DARK_GRAY))
                .append(Component.text(display, MenuText.VALUE));
    }

    private JsonArray rows(Board board) {
        JsonObject snapshot = boards.latest();
        if (snapshot == null || !snapshot.has(board.scope)) {
            return new JsonArray();
        }
        JsonObject scope = snapshot.getAsJsonObject(board.scope);
        return scope.has(board.key) && scope.get(board.key).isJsonArray()
                ? scope.getAsJsonArray(board.key)
                : new JsonArray();
    }

    private static UUID uuid(JsonObject row) {
        try {
            return row.has("minecraft_uuid")
                    ? UUID.fromString(row.get("minecraft_uuid").getAsString())
                    : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String text(JsonObject row, String key) {
        return row.has(key) && !row.get(key).isJsonNull() ? row.get(key).getAsString() : "";
    }

    private DialogAction callback(BiConsumer<DialogResponseView, Player> callback) {
        return DialogAction.customClick((response, audience) -> {
            if (audience instanceof Player player && player.isOnline()) {
                callback.accept(response, player);
            }
        }, CALLBACK_OPTIONS);
    }
}
