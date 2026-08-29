package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the two dialog mistakes that only show up on a running server.
 *
 * <p>{@code DialogBase.build()} lives in the server jar, not the API, so a screen
 * cannot be constructed on the test bench — which is how an invalid after-action
 * shipped and took {@code /leaderboard}, {@code /stats} and {@code /settings} with it.
 * Reading the source is a poor substitute for building the thing, but it catches
 * exactly the class of error that got through.
 */
final class DialogScreenTest {
    private static final Path SOURCE =
            Path.of("src/main/java/bot/mgx/accessbridge");

    @Test
    void noScreenLeavesTheClientWaitingOrPausedForever() throws IOException {
        for (Path file : dialogSources()) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            // WAIT_FOR_RESPONSE leaves "Waiting for Server" on screen after escape.
            assertTrue(!text.contains("DialogAfterAction.WAIT_FOR_RESPONSE"),
                    file.getFileName() + " uses WAIT_FOR_RESPONSE, which hangs on escape");
            // NONE never unpauses, so Paper refuses to build a pausing dialog that uses
            // it. A screen that stays open has to say pause(false) as well.
            if (text.contains("DialogAfterAction.NONE")) {
                assertTrue(text.contains(".pause(false)"),
                        file.getFileName() + " uses NONE without pause(false), which throws");
            }
        }
    }

    @Test
    void everyMenuSpriteIsAddressedToTheAtlasThatHoldsIt() {
        for (MainMenu entry : MainMenu.entries()) {
            String path = entry.sprite();
            boolean block = path.startsWith("block/");
            // 1.21.11 moved item textures into their own atlas; sending an item sprite
            // to the blocks atlas draws a missing-texture square and nothing else.
            assertTrue(block || path.startsWith("item/"), path);
            assertTrue(MenuText.atlasFor(path).value().equals(block ? "blocks" : "items"), path);
        }
    }

    private static List<Path> dialogSources() throws IOException {
        try (Stream<Path> files = Files.list(SOURCE)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".java"))
                    .filter(DialogScreenTest::usesDialogs)
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
