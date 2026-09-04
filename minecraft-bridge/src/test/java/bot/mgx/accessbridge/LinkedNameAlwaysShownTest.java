package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A linked Discord name is always shown, so verified status stays visible.
 *
 * <p>Players could hide their linked name from the settings menu. The side effect was
 * that a verified player who had hidden it and an unverified player looked exactly the
 * same in chat, nametags and the player list — there was no way to tell who had actually
 * gone through verification. The toggle is gone from both settings surfaces and from
 * {@code /discordnames}, which now only reports.
 *
 * <p>The stored {@code visible} flag is intentionally left in the data file and ignored,
 * so nothing had to rewrite it and a player who had hidden their name shows it again.
 */
final class LinkedNameAlwaysShownTest {
    @Test
    void lookingUpTheNameDoesNotConsultTheHiddenFlag() throws Exception {
        String source = read("DiscordIdentityStore.java");
        int at = source.indexOf("Optional<String> visibleUsername(");
        assertTrue(at > 0, "visibleUsername must exist");
        String body = source.substring(at, source.indexOf("\n    }", at));
        assertFalse(body.contains("visible()"),
                "visibleUsername must not gate on the stored flag — that is what made a "
                        + "verified player indistinguishable from an unverified one");
    }

    @Test
    void nothingCanFlipTheFlagAnyMore() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path source : mainSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            if (text.contains("toggleVisibility(") || text.contains("toggleDiscordName(")) {
                offenders.add(source.getFileName().toString());
            }
        }
        assertTrue(offenders.isEmpty(),
                "the hide-my-Discord-name toggle is deliberately gone: " + offenders);
    }

    @Test
    void neitherSettingsSurfaceOffersIt() throws Exception {
        for (String file : List.of("PlayerMenuService.java", "PlayerSettingsDialogService.java")) {
            assertFalse(read(file).contains("\"Discord Name\""),
                    file + " must not offer the Discord Name toggle");
        }
    }

    private static String read(String name) throws IOException {
        return Files.readString(
                Path.of("src/main/java/bot/mgx/accessbridge/" + name), StandardCharsets.UTF_8);
    }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
