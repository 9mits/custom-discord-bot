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
 * Keeps the two clients level.
 *
 * <p>Dialogs are Java-only, so every screen has a second path for Bedrock and older
 * clients. That second path is easy to forget, and forgetting it is invisible from a
 * Java client: the feature simply does not exist for half the server, and nothing
 * says so. This asserts each screen still has one.
 */
final class ClientParityTest {
    private static final Path SOURCE = Path.of("src/main/java/bot/mgx/accessbridge");

    @Test
    void everyDialogScreenAlsoHasANonDialogPath() throws IOException {
        List<String> stranded = new ArrayList<>();
        for (Path file : dialogScreens()) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            // Either it asks what the client can render, or it is only ever reached
            // from a screen that already did.
            boolean checksClient = text.contains("supportsDialogs");
            boolean hasFallback = text.contains("BedrockForms")
                    || text.contains("menus.open")
                    || text.contains("teleports.open")
                    || text.contains("warps.open")
                    || text.contains("openChest")
                    || text.contains("openProfile");
            if (checksClient && !hasFallback) {
                stranded.add(file.getFileName().toString());
            }
        }
        assertTrue(stranded.isEmpty(),
                "screens that leave a Bedrock player with nothing: " + stranded);
    }

    /**
     * The three things a chest menu cannot do at all — free text, a search box, a
     * per-player prompt — are exactly what was Java-only before Bedrock forms, so the
     * services offering them must be reaching for a form rather than a chest.
     */
    @Test
    void screensThatNeedTypedTextOfferItOnBothClients() throws IOException {
        List<String> typed = List.of(
                "HomesDialogService.java",
                "ClanWarpDialogService.java",
                "StatsDialogService.java",
                "TeleportDialogService.java"
        );
        List<String> missing = new ArrayList<>();
        for (String name : typed) {
            String text = Files.readString(SOURCE.resolve(name), StandardCharsets.UTF_8);
            if (text.contains("DialogInput.text") && !text.contains("forms.prompt")) {
                missing.add(name);
            }
        }
        assertTrue(missing.isEmpty(),
                "typed input offered on Java only: " + missing);
    }

    private static List<Path> dialogScreens() throws IOException {
        try (Stream<Path> files = Files.list(SOURCE)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".java"))
                    .filter(ClientParityTest::usesDialogs)
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

    /**
     * A dialog button must not lead back to a chest screen.
     *
     * <p>The Java "My Clan" button pointed at {@code menus::openHub} — a method
     * reference, which is how it survived a sweep that replaced the call form. Landing
     * in a chest is worse than never leaving one, because every button inside it then
     * leads further into the old UI and Back never comes home.
     */
    @Test
    void noDialogButtonLeadsIntoAChestScreen() throws IOException {
        java.util.regex.Pattern leak = java.util.regex.Pattern.compile(
                "(menus|teleports|warps)::open[A-Z]\\w*"
        );
        List<String> leaks = new ArrayList<>();
        for (Path file : dialogScreens()) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            java.util.regex.Matcher match = leak.matcher(text);
            while (match.find()) {
                if (!match.group().endsWith("Preferred")) {
                    leaks.add(file.getFileName() + " -> " + match.group());
                }
            }
        }
        assertTrue(leaks.isEmpty(), "dialog buttons pointing at chest screens: " + leaks);
    }
}
