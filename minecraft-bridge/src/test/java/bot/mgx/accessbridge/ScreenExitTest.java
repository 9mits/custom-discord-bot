package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every screen leaves the same way, and the way out points inwards-to-outwards.
 *
 * <p>Screens used to add whatever exit each felt like, which produced some with Back,
 * some with Close, some with both — the bounty board shipped with two Closes and the
 * home screen with two Backs to different places — and one with neither. Checking the
 * <em>shape</em> of a hand-built screen was never enough, because the next hand-built
 * screen invents a new shape. So the rule is structural instead: a screen may not be
 * built by hand at all.
 *
 * <p>{@link Screens} draws every multi-action dialog and appends exactly one exit,
 * {@link BedrockForms} does the same for Bedrock, and the choice between Back and
 * Close is depth, never preference: Close belongs to the main menu, which is the only
 * screen with nothing behind it.
 *
 * <p>A confirmation dialog is exempt: its Confirm and Cancel pair <em>is</em> the exit.
 */
final class ScreenExitTest {
    private static final Path SOURCE = Path.of("src/main/java/bot/mgx/accessbridge");
    private static final String RENDERER = "Screens.java";
    private static final String BEDROCK = "BedrockForms.java";
    private static final String HOME = "MainMenuService.java";

    @Test
    void everyScreenIsDrawnByTheSharedRenderer() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : dialogSources()) {
            if (file.getFileName().toString().equals(RENDERER)) {
                continue;
            }
            if (Files.readString(file, StandardCharsets.UTF_8)
                    .contains("DialogType.multiAction(")) {
                offenders.add(file.getFileName().toString());
            }
        }
        assertTrue(offenders.isEmpty(),
                "screens built outside Screens, which is how an exit goes missing or "
                        + "gets drawn twice: " + offenders);
    }

    @Test
    void everyDialogIncludingConfirmationsUsesTheSharedRenderer() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(SOURCE)) {
            for (Path file : files.filter(ScreenExitTest::isJava).toList()) {
                if (file.getFileName().toString().equals(RENDERER)) {
                    continue;
                }
                if (Files.readString(file, StandardCharsets.UTF_8)
                        .contains("Dialog.create(")) {
                    offenders.add(file.getFileName().toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "dialogs built outside Screens can invent their own labels or exit policy: "
                        + offenders);
    }

    @Test
    void noScreenBuildsItsOwnExit() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : dialogSources()) {
            String name = file.getFileName().toString();
            if (name.equals(RENDERER) || name.equals(BEDROCK)) {
                continue;
            }
            String text = withoutChestTiles(Files.readString(file, StandardCharsets.UTF_8));
            if (text.contains("\"Back\"") || text.contains("\"Close\"")) {
                offenders.add(name);
            }
        }
        assertTrue(offenders.isEmpty(),
                "these name their own exit, so it can duplicate or contradict the one "
                        + "Screens adds: " + offenders);
    }

    @Test
    void onlyTheMainMenuOffersClose() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(SOURCE)) {
            for (Path file : files.filter(ScreenExitTest::isJava).toList()) {
                String name = file.getFileName().toString();
                if (name.equals(RENDERER) || name.equals(HOME)) {
                    continue;
                }
                if (Files.readString(file, StandardCharsets.UTF_8).contains("Screens.showHome")) {
                    offenders.add(name);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "Close belongs to the one screen with nothing behind it: " + offenders);
    }

    @Test
    void theSharedRendererOffersExactlyOneExit() throws IOException {
        String text = Files.readString(SOURCE.resolve(RENDERER), StandardCharsets.UTF_8);
        // A missing origin is not a reason to offer Close; it means the main menu is
        // where Back goes, because a command opened this directly.
        assertTrue(text.contains("back == null ? Screens::home : back"),
                "a screen with no origin must go Back to the main menu, not Close");
        assertTrue(text.contains("withExit(buttons, close())"),
                "Close must be reachable only through the home screen's own renderer");
    }

    @Test
    void bedrockGetsTheSameSingleExit() throws IOException {
        String text = Files.readString(SOURCE.resolve(BEDROCK), StandardCharsets.UTF_8);
        assertTrue(text.contains("choices.add(new Button(\"Back\""),
                "Bedrock forms must append the exit rather than trusting each caller");
        assertTrue(text.contains("back == null ? Screens::home : back"),
                "Bedrock must fall back to the same home as Java");
    }

    /**
     * Chest tiles are a different surface with their own Back slot, and two services
     * draw both. Removing those calls keeps this test about dialogs.
     */
    private static String withoutChestTiles(String text) {
        StringBuilder kept = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            int start = text.indexOf("MenuItems.button(", index);
            if (start < 0) {
                kept.append(text, index, text.length());
                break;
            }
            kept.append(text, index, start);
            index = endOfCall(text, start + "MenuItems.button(".length());
        }
        return kept.toString();
    }

    private static int endOfCall(String text, int afterOpenParen) {
        int depth = 1;
        for (int at = afterOpenParen; at < text.length(); at++) {
            char character = text.charAt(at);
            if (character == '(') {
                depth++;
            } else if (character == ')' && --depth == 0) {
                return at + 1;
            }
        }
        return text.length();
    }

    private static List<Path> dialogSources() throws IOException {
        try (Stream<Path> files = Files.list(SOURCE)) {
            return files.filter(ScreenExitTest::isJava)
                    .filter(ScreenExitTest::usesDialogs)
                    .toList();
        }
    }

    private static boolean isJava(Path file) {
        return file.getFileName().toString().endsWith(".java");
    }

    private static boolean usesDialogs(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return text.contains("io.papermc.paper.dialog.Dialog")
                    || text.contains("BedrockForms.Button")
                    || text.contains("Screens.show");
        } catch (IOException ignored) {
            return false;
        }
    }
}
