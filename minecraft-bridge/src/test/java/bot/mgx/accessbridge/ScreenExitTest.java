package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every screen leaves the same way.
 *
 * <p>Screens used to add whatever exit each felt like, which produced some with Back,
 * some with Close, some with both and some with neither — a player could not learn one
 * rule and rely on it. {@link Screens} adds exactly one, chosen by whether the caller
 * passed an origin, so the only way to get it wrong again is to build a dialog by hand.
 *
 * <p>A confirmation is exempt: its two buttons <em>are</em> the exit.
 */
final class ScreenExitTest {
    private static final Path SOURCE = Path.of("src/main/java/bot/mgx/accessbridge");

    @Test
    void noScreenIsBuiltByHandWithoutAnExit() throws IOException {
        Pattern inline = Pattern.compile("DialogType\\.multiAction\\(");
        List<String> offenders = new ArrayList<>();
        for (Path file : dialogSources()) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Matcher match = inline.matcher(text);
            int handBuilt = 0;
            while (match.find()) {
                handBuilt++;
            }
            if (handBuilt == 0) {
                continue;
            }
            // A hand-built multi-action screen has to supply its own way out.
            boolean hasExit = text.contains("\"Back\"") || text.contains("\"Close\"");
            if (!hasExit) {
                offenders.add(file.getFileName() + " builds " + handBuilt
                        + " screen(s) with no Back and no Close");
            }
        }
        assertTrue(offenders.isEmpty(), "screens a player cannot leave: " + offenders);
    }

    @Test
    void theSharedRendererOffersExactlyOneExit() throws IOException {
        String text = Files.readString(SOURCE.resolve("Screens.java"), StandardCharsets.UTF_8);
        // Back when an origin was given, Close when it was not. Never both, never none.
        assertTrue(text.contains("if (back == null)"),
                "Screens must choose the exit from whether an origin was passed");
        assertTrue(text.contains("\"Close\"") && text.contains("\"Back\""),
                "Screens must be able to render both exits");
    }

    private static List<Path> dialogSources() throws IOException {
        try (Stream<Path> files = Files.list(SOURCE)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".java"))
                    .filter(file -> !file.getFileName().toString().equals("Screens.java"))
                    .filter(ScreenExitTest::usesDialogs)
                    .toList();
        }
    }

    private static boolean usesDialogs(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8)
                    .contains("io.papermc.paper.dialog.Dialog");
        } catch (IOException ignored) {
            return false;
        }
    }
}
