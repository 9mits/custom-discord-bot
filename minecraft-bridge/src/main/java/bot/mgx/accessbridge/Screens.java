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
import java.util.function.Consumer;

/**
 * One way to draw a screen, so every screen leaves the same way.
 *
 * <p>Each service used to build its own dialog and add whatever exit it felt like,
 * which produced screens with Back, screens with Close, screens with both and screens
 * with neither. The exit is not a decision a screen should get to make: a screen
 * opened from somewhere goes Back, a screen opened from a command or a key Closes, and
 * that follows from whether the caller passed an origin.
 *
 * <p>Callers pass their own buttons and the origin, never the exit itself.
 */
final class Screens {
    static final ClickCallback.Options CALLBACKS = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(Duration.ofMinutes(10))
            .build();

    private Screens() {
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
        List<ActionButton> all = new ArrayList<>(buttons);
        all.add(exit(back));
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title(title))
                        .body(body.isEmpty()
                                ? List.of(DialogBody.plainMessage(Component.empty(), 400))
                                : body)
                        // Not a blocking prompt: the screen stays up so a toggle repaints
                        // in place. NONE is only legal on a dialog that does not pause.
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .pause(false)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(all).columns(columns).build()));
        player.showDialog(dialog);
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
        List<ActionButton> all = new ArrayList<>(buttons);
        all.add(exit(back));
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title(title))
                        .body(body.isEmpty()
                                ? List.of(DialogBody.plainMessage(Component.empty(), 400))
                                : body)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(all).columns(columns).build()));
        player.showDialog(dialog);
    }

    static List<DialogBody> body(String text) {
        return List.of(DialogBody.plainMessage(MenuText.body(text), 400));
    }

    /**
     * Back when the caller said where from, Close when it is the first screen. Exactly
     * one of the two, always present.
     */
    private static ActionButton exit(Consumer<Player> back) {
        if (back == null) {
            return ActionButton.builder(Component.text("Close", MenuText.LABEL))
                    .width(150)
                    .action(callback((response, audience) -> audience.closeDialog()))
                    .build();
        }
        return ActionButton.builder(Component.text("Back", MenuText.LABEL))
                .tooltip(Component.text("Return to where you came from.", MenuText.LABEL))
                .width(150)
                .action(callback((response, audience) -> back.accept(audience)))
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
