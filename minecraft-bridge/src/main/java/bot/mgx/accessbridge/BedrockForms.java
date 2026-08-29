package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Bedrock half of every screen.
 *
 * <p>Bedrock cannot render a dialog, and a chest menu cannot take typed text at all —
 * which is why renaming, searching and looking a player up by name were Java-only.
 * Geyser already ships Cumulus and Floodgate already sends it, so a real Bedrock form
 * costs nothing new at runtime and reaches parity rather than imitating it.
 *
 * <p>Every entry point returns false rather than throwing when a form cannot be sent,
 * so a caller falls through to the chest screen it used before. The worst case of this
 * whole layer is the behaviour that shipped without it.
 */
final class BedrockForms {
    /** A button and what pressing it does. */
    record Button(String label, Runnable action) {
    }

    private final MGXAccessBridge plugin;

    BedrockForms(MGXAccessBridge plugin) {
        this.plugin = plugin;
    }

    boolean isBedrock(Player player) {
        try {
            return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * A titled list of buttons: the shape most of these screens actually are.
     *
     * <p>The exit is appended here for the same reason {@link Screens} appends it on
     * Java — so a screen cannot hand itself two ways out, or none. {@code back} is
     * where the caller came from; without one the exit goes to the main menu, which is
     * the only screen a Bedrock player can be sent to with nothing behind it.
     */
    boolean menu(Player player, String title, String body, List<Button> buttons) {
        return menu(player, title, body, buttons, null);
    }

    boolean menu(
            Player player, String title, String body, List<Button> buttons,
            Consumer<Player> back
    ) {
        // An empty list is a real screen, not a failure: "No bounties standing" plus a
        // way out beats falling through to an empty chest.
        List<Button> choices = new ArrayList<>(buttons);
        Consumer<Player> exit = back == null ? Screens::home : back;
        choices.add(new Button("Back", () -> exit.accept(player)));
        choices = List.copyOf(choices);
        SimpleForm.Builder form = SimpleForm.builder().title(title);
        if (body != null && !body.isBlank()) {
            form.content(body);
        }
        for (Button button : choices) {
            form.button(button.label());
        }
        List<Button> sent = choices;
        form.validResultHandler(response -> onMain(() -> {
            int index = response.clickedButtonId();
            if (index >= 0 && index < sent.size()) {
                sent.get(index).action().run();
            }
        }));
        return send(player, form.build());
    }

    /** One free-text answer, which is the thing a chest menu can never ask for. */
    boolean prompt(
            Player player, String title, String label, String initial, Consumer<String> onSubmit
    ) {
        return prompt(player, title, label, initial, onSubmit, null);
    }

    boolean prompt(
            Player player,
            String title,
            String label,
            String initial,
            Consumer<String> onSubmit,
            Runnable onCancel
    ) {
        CustomForm.Builder builder = CustomForm.builder()
                .title(title)
                .input(label, "", initial == null ? "" : initial)
                .validResultHandler(response -> onMain(() -> {
                    String typed = response.asInput(0);
                    onSubmit.accept(typed == null ? "" : typed);
                }));
        if (onCancel != null) {
            builder.closedResultHandler(() -> onMain(onCancel));
        }
        return send(player, builder.build());
    }

    /** A yes or no, for anything that cannot be undone. */
    boolean confirm(
            Player player, String title, String body, String yes, Runnable onYes
    ) {
        return confirm(player, title, body, yes, onYes, null);
    }

    boolean confirm(
            Player player,
            String title,
            String body,
            String yes,
            Runnable onYes,
            Runnable onNo
    ) {
        ModalForm.Builder builder = ModalForm.builder()
                .title(title)
                .content(body)
                .button1(yes)
                .button2("Cancel")
                .validResultHandler(response -> onMain(() -> {
                    if (response.clickedFirst()) {
                        onYes.run();
                    } else if (onNo != null) {
                        onNo.run();
                    }
                }));
        if (onNo != null) {
            builder.closedResultHandler(() -> onMain(onNo));
        }
        return send(player, builder.build());
    }

    /** A list of toggles submitted together, for a permission sheet. */
    boolean toggles(
            Player player,
            String title,
            List<String> labels,
            List<Boolean> initial,
            Consumer<List<Boolean>> onSubmit,
            Runnable onCancel
    ) {
        if (labels.isEmpty()) {
            return false;
        }
        CustomForm.Builder form = CustomForm.builder().title(title);
        for (int index = 0; index < labels.size(); index++) {
            form.toggle(labels.get(index),
                    index < initial.size() && Boolean.TRUE.equals(initial.get(index)));
        }
        form.validResultHandler(response -> onMain(() -> {
            List<Boolean> selected = new ArrayList<>();
            for (int index = 0; index < labels.size(); index++) {
                selected.add(response.asToggle(index));
            }
            onSubmit.accept(List.copyOf(selected));
        }));
        if (onCancel != null) {
            form.closedResultHandler(() -> onMain(onCancel));
        }
        return send(player, form.build());
    }

    static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /**
     * A form's result arrives off the main thread, and everything it then touches —
     * inventories, teleports, stores — expects to be on it.
     */
    private void onMain(Runnable action) {
        plugin.getServer().getScheduler().runTask(plugin, action);
    }

    private boolean send(Player player, Object form) {
        try {
            if (form instanceof org.geysermc.cumulus.form.Form built) {
                return FloodgateApi.getInstance().sendForm(player.getUniqueId(), built);
            }
            return false;
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().warning(
                    "Could not send a Bedrock form to " + player.getName()
                            + "; falling back to the chest screen: " + exception.getMessage()
            );
            return false;
        }
    }
}
