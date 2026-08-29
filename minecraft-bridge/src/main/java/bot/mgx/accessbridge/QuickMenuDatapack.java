package bot.mgx.accessbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Binds the main menu to the vanilla quick-actions key (G by default).
 *
 * <p>Paper can only put a dialog into {@code #minecraft:quick_actions} from a plugin
 * bootstrapper, which needs a {@code paper-plugin.yml}; this is a legacy plugin, so
 * the tag is shipped as a datapack instead. Nothing is lost — the menu is a grid of
 * buttons that each run a command, with no live values to render — and it avoids
 * migrating a hundred classes onto Paper's isolated classloading for one keybind.
 *
 * <p>A datapack is only read when the server loads it, so the files are written on
 * enable and a restart is asked for when they actually changed.
 */
final class QuickMenuDatapack {
    /**
     * 1.21.11's data pack version, as a {@code [major, minor]} pair. Anything above
     * format 81 must declare {@code min_format} and {@code max_format} or the server
     * refuses the metadata outright, and {@code pack_format} no longer stands in for
     * them — Bukkit's own generated pack in the same folder is the reference.
     *
     * <p>The range accepts any minor bump of the same major. A new major can change
     * the dialog schema itself, so the pack should be revisited rather than loaded.
     */
    private static final int PACK_MAJOR = 94;
    private static final int MIN_PACK_MINOR = 0;
    private static final int MAX_PACK_MINOR = 99;
    private static final String NAMESPACE = "mgx";
    private static final String DIALOG_NAME = "menu";
    private static final String PACK_FOLDER = "mgx_menu";
    private static final int COLUMNS = 2;
    private static final int BUTTON_WIDTH = 150;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * Writes the pack under the given world folder.
     *
     * @return true when something changed and the server needs a restart to pick it up
     */
    boolean install(Path worldFolder) throws IOException {
        Path root = worldFolder.resolve("datapacks").resolve(PACK_FOLDER);
        boolean changed = write(root.resolve("pack.mcmeta"), gson.toJson(packMeta()));
        changed |= write(
                root.resolve("data").resolve(NAMESPACE).resolve("dialog")
                        .resolve(DIALOG_NAME + ".json"),
                gson.toJson(menuDialog())
        );
        changed |= write(
                root.resolve("data").resolve("minecraft").resolve("tags").resolve("dialog")
                        .resolve("quick_actions.json"),
                gson.toJson(quickActionsTag())
        );
        return changed;
    }

    private static boolean write(Path file, String content) throws IOException {
        if (Files.isRegularFile(file)
                && Files.readString(file, StandardCharsets.UTF_8).equals(content)) {
            return false;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return true;
    }

    JsonObject packMeta() {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", "Mysterious SMP X quick-actions menu");
        pack.add("min_format", version(PACK_MAJOR, MIN_PACK_MINOR));
        pack.add("max_format", version(PACK_MAJOR, MAX_PACK_MINOR));
        JsonObject meta = new JsonObject();
        meta.add("pack", pack);
        return meta;
    }

    private static JsonArray version(int major, int minor) {
        JsonArray version = new JsonArray();
        version.add(major);
        version.add(minor);
        return version;
    }

    JsonObject menuDialog() {
        JsonObject dialog = new JsonObject();
        dialog.addProperty("type", "minecraft:multi_action");
        dialog.add("title", text("Mysterious SMP X"));
        dialog.add("body", body("Choose where to go."));
        dialog.addProperty("columns", COLUMNS);
        dialog.addProperty("can_close_with_escape", true);
        dialog.addProperty("pause", false);
        // Commands such as /settings replace this dialog synchronously. Closing after
        // the action can then close that replacement as well, which makes some buttons
        // look random or dead depending on whether they open a dialog or an inventory.
        dialog.addProperty("after_action", "none");
        JsonArray actions = new JsonArray();
        for (MainMenu entry : MainMenu.entries()) {
            actions.add(button(entry));
        }
        dialog.add("actions", actions);
        return dialog;
    }

    private JsonObject button(MainMenu entry) {
        JsonObject action = new JsonObject();
        action.addProperty("type", "minecraft:run_command");
        action.addProperty("command", entry.command());

        JsonObject button = new JsonObject();
        button.add("label", labelWithIcon(entry));
        button.add("tooltip", text(entry.tooltip()));
        button.addProperty("width", BUTTON_WIDTH);
        button.add("action", action);
        return button;
    }

    /**
     * The label is the item's sprite followed by the name. 1.21.9's object component
     * is what lets an icon sit inside text, and it is the difference between a grid of
     * words and a menu you can read at a glance.
     */
    private static JsonArray labelWithIcon(MainMenu entry) {
        JsonObject sprite = new JsonObject();
        sprite.addProperty("type", "object");
        sprite.addProperty("object", "atlas");
        sprite.addProperty("atlas", entry.sprite().startsWith("block/")
                ? "minecraft:blocks" : "minecraft:items");
        sprite.addProperty("sprite", entry.sprite());
        JsonArray label = new JsonArray();
        label.add(sprite);
        label.add(text(" " + entry.label()));
        return label;
    }

    private static JsonObject quickActionsTag() {
        JsonObject tag = new JsonObject();
        JsonArray values = new JsonArray();
        values.add(NAMESPACE + ":" + DIALOG_NAME);
        tag.add("values", values);
        return tag;
    }

    private static JsonObject body(String message) {
        JsonObject contents = new JsonObject();
        contents.addProperty("type", "minecraft:plain_message");
        contents.add("contents", text(message));
        return contents;
    }

    private static JsonObject text(String value) {
        JsonObject component = new JsonObject();
        component.addProperty("text", value);
        return component;
    }

    static List<String> commands() {
        return MainMenu.entries().stream().map(MainMenu::command).toList();
    }
}
