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
        // Checked per enclosing method, not per file and not per character window.
        // Per file let a screen with no exit pass because a sibling screen in the same
        // class had a Back button, which is how the clan warps list shipped with no way
        // out; a fixed window then mis-flagged pickers whose Back sat further up.
        List<String> offenders = new ArrayList<>();
        for (Path file : dialogSources()) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            for (String method : methods(text)) {
                if (!method.contains("DialogType.multiAction(")) {
                    continue;
                }
                boolean hasExit = method.contains("\"Back\"")
                        || method.contains("\"Close\"")
                        || method.contains("Screens.show");
                if (!hasExit) {
                    offenders.add(file.getFileName() + " -> " + signature(method));
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "hand-built screens a player cannot leave: " + offenders);
    }

    /** Splits a source file on top-level method starts, which is enough to scope this. */
    private static List<String> methods(String text) {
        List<String> found = new ArrayList<>();
        Matcher starts = Pattern.compile(
                "\n    (?:private |public |static |void |boolean |[A-Z])[^\n]*\\{\n"
        ).matcher(text);
        List<Integer> offsets = new ArrayList<>();
        while (starts.find()) {
            offsets.add(starts.start());
        }
        for (int index = 0; index < offsets.size(); index++) {
            int from = offsets.get(index);
            int to = index + 1 < offsets.size() ? offsets.get(index + 1) : text.length();
            found.add(text.substring(from, to));
        }
        return found;
    }

    private static String signature(String method) {
        String first = method.strip().split("\n")[0];
        return first.length() > 70 ? first.substring(0, 70) : first;
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
