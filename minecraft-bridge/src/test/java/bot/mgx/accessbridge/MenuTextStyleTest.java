package bot.mgx.accessbridge;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No slanted text anywhere in the menus.
 *
 * <p>Leaving italic unset is not enough. An unset decoration inherits, and both item
 * tooltips and dialog text arrive italic already, so every piece of menu text has to
 * state it. This is why the slant kept surviving in screens nobody had touched.
 */
final class MenuTextStyleTest {
    private static final Path MAIN = Path.of("src", "main", "java");

    @Test
    void everyMenuTextBuilderSaysItIsUpright() {
        assertEquals(TextDecoration.State.FALSE,
                MenuText.title("Title").decoration(TextDecoration.ITALIC));
        assertEquals(TextDecoration.State.FALSE,
                MenuText.body("Body").decoration(TextDecoration.ITALIC));
        assertEquals(TextDecoration.State.FALSE,
                MenuText.buttonLabel("Label", NamedTextColor.WHITE)
                        .decoration(TextDecoration.ITALIC));
        assertEquals(TextDecoration.State.FALSE,
                MenuText.actionHint("Hint").decoration(TextDecoration.ITALIC));
        assertEquals(TextDecoration.State.FALSE,
                MenuText.stat("Label", "Value").decoration(TextDecoration.ITALIC));
        assertEquals(TextDecoration.State.FALSE,
                MenuText.stat("Label", "item/diamond", "Value")
                        .decoration(TextDecoration.ITALIC));
    }

    @Test
    void nothingInThePluginTurnsItalicBackOn() throws Exception {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MAIN)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (source.contains("ITALIC, true") || source.contains("ITALIC,true")) {
                    offenders.add(file.toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "these turn italic back on: " + offenders);
    }

    /** The quick-actions datapack builds its dialog as raw JSON, outside Adventure. */
    @Test
    void theDatapackDialogAlsoStatesItIsUpright() throws Exception {
        String source = Files.readString(MAIN.resolve(
                "bot/mgx/accessbridge/QuickMenuDatapack.java"
        ));

        assertTrue(source.contains("component.addProperty(\"italic\", false)"));
    }
}
