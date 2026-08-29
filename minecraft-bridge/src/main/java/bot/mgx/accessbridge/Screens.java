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
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * One way to draw a screen, so every screen leaves the same way.
 *
 * <p>Each service used to build its own dialog and add whatever exit it felt like,
 * which produced screens with Back, screens with Close, screens with both, and one
 * with two Closes. The exit is not a decision a screen gets to make.
 *
 * <p>The rule is depth, not preference. <strong>Close belongs to the main menu and
 * nowhere else</strong>, because it is the only screen with nothing behind it.
 * Everything else is somewhere further in, so everything else goes Back: to the
 * caller that opened it, or — when a command opened it directly and there is no
 * caller — to the main menu, which is home. A screen therefore never has to know
 * whether it was reached by key, command or button; it passes an origin or it passes
 * nothing, and either way the player gets exactly one way out that goes the
 * direction they expect.
 *
 * <p>Escape is deliberately not the Back button. {@code canCloseWithEscape} leaves
 * the whole stack in one press, so a player four screens deep is never trapped
 * pressing it four times; no {@code exitAction} is set, because that would rebind
 * escape to a single step back.
 */
final class Screens {
    static final ClickCallback.Options CALLBACKS = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(Duration.ofMinutes(10))
            .build();

    /**
     * How to open the main menu, installed once at enable. Closing is the fallback so
     * that a Back is never a dead button if the menu service is unavailable.
     */
    private static volatile Consumer<Player> home = Player::closeDialog;

    private Screens() {
    }

    static void installHome(Consumer<Player> opener) {
        home = opener == null ? Player::closeDialog : opener;
    }

    static void home(Player player) {
        home.accept(player);
    }

    /** A screen that stays open while the player works in it. */
    static void show(
            Player player,
            String title,
            List<DialogBody> body,
            List<ActionButton> buttons,
            int columns,
            Consumer<Player> back
    ) {
        show(player, title, body, List.of(), buttons, columns, back);
    }

    /** The same, with a text field — searching and naming, which a chest cannot ask. */
    static void show(
            Player player,
            String title,
            List<DialogBody> body,
            List<DialogInput> inputs,
            List<ActionButton> buttons,
            int columns,
            Consumer<Player> back
    ) {
        // Not a blocking prompt: the screen stays up so a toggle repaints in place.
        draw(player, title, body, inputs, withExit(buttons, back(back)), columns,
                DialogBase.DialogAfterAction.NONE);
    }

    /** A screen whose buttons all lead elsewhere, so it should get out of the way. */
    static void showAndClose(
            Player player,
            String title,
            List<DialogBody> body,
            List<ActionButton> buttons,
            int columns,
            Consumer<Player> back
    ) {
        draw(player, title, body, List.of(), withExit(buttons, back(back)), columns,
                DialogBase.DialogAfterAction.CLOSE);
    }

    /**
     * The main menu itself: the only screen the player cannot go back from, and so the
     * only one that offers Close. Nothing else may call this.
     */
    static void showHome(
            Player player,
            String title,
            List<DialogBody> body,
            List<ActionButton> buttons,
            int columns
    ) {
        draw(player, title, body, List.of(), withExit(buttons, close()), columns,
                DialogBase.DialogAfterAction.CLOSE);
    }

    static List<DialogBody> body(String text) {
        return List.of(DialogBody.plainMessage(MenuText.body(text), 400));
    }

    private static void draw(
            Player player,
            String title,
            List<DialogBody> body,
            List<DialogInput> inputs,
            List<ActionButton> buttons,
            int columns,
            DialogBase.DialogAfterAction afterAction
    ) {
        // Paper refuses to build a dialog that pauses the game and then never unpauses
        // it, which is how an invalid after-action once took /leaderboard, /stats and
        // /settings down together. Deriving it here means the pair cannot be got wrong.
        boolean pause = afterAction != DialogBase.DialogAfterAction.NONE;
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title(title))
                        .body(body.isEmpty()
                                ? List.of(DialogBody.plainMessage(Component.empty(), 400))
                                : body)
                        .inputs(inputs)
                        .afterAction(afterAction)
                        .pause(pause)
                        .canCloseWithEscape(true)
                        .build())
                // No exitAction: escape leaves the menu entirely rather than stepping
                // back one screen, and the grid holds the single Back or Close.
                .type(DialogType.multiAction(buttons).columns(columns).build()));
        player.showDialog(dialog);
    }

    private static List<ActionButton> withExit(List<ActionButton> buttons, ActionButton exit) {
        List<ActionButton> all = new ArrayList<>(buttons);
        all.add(exit);
        return all;
    }

    /** Back to the caller, or to the main menu when a command opened this directly. */
    private static ActionButton back(Consumer<Player> back) {
        Consumer<Player> target = back == null ? Screens::home : back;
        return ActionButton.builder(Component.text("Back", MenuText.LABEL))
                .tooltip(Component.text(back == null
                        ? "Return to the main menu."
                        : "Return to where you came from.", MenuText.LABEL))
                .width(150)
                .action(callback((response, audience) -> target.accept(audience)))
                .build();
    }

    private static ActionButton close() {
        return ActionButton.builder(Component.text("Close", MenuText.LABEL))
                .width(150)
                .action(callback((response, audience) -> audience.closeDialog()))
                .build();
    }

    static ActionButton button(String sprite, String label, String tooltip, Consumer<Player> run) {
        ActionButton.Builder builder = ActionButton.builder(sprite == null
                        ? Component.text(label, NamedTextColor.WHITE)
                        : Component.empty()
                                .append(MenuText.sprite(sprite))
                                .append(Component.text(" " + label, NamedTextColor.WHITE)))
                .width(150)
                .action(callback((response, audience) -> run.accept(audience)));
        return tooltip == null || tooltip.isBlank()
                ? builder.build()
                : builder.tooltip(Component.text(tooltip, MenuText.LABEL)).build();
    }

    static DialogAction callback(BiConsumer<DialogResponseView, Player> callback) {
        return DialogAction.customClick((response, audience) -> {
            if (audience instanceof Player player && player.isOnline()) {
                callback.accept(response, player);
            }
        }, CALLBACKS);
    }
}
