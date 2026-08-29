package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks every icon against a real client jar, when one is on this machine.
 *
 * <p>A sprite that names no texture draws a magenta square and says nothing about
 * why. Beds and shields are the trap: they look like ordinary items but are drawn
 * from an entity model, so {@code item/white_bed} and {@code item/shield} do not
 * exist while almost every neighbouring path does.
 *
 * <p>Skipped where no client jar is present, which is the case on CI. It is a
 * developer-machine guard, not a gate.
 */
final class SpriteExistsTest {
    private static final List<Path> CANDIDATES = List.of(
            Path.of(System.getProperty("user.home"),
                    "Library/Application Support/minecraft/versions/1.21.11/1.21.11.jar"),
            Path.of(System.getProperty("user.home"),
                    ".minecraft/versions/1.21.11/1.21.11.jar")
    );

    @Test
    void everyIconNamesATextureThatExists() throws IOException {
        Path jar = CANDIDATES.stream().filter(Files::isRegularFile).findFirst().orElse(null);
        Assumptions.assumeTrue(jar != null, "no client jar on this machine");

        List<String> missing = new ArrayList<>();
        try (ZipFile client = new ZipFile(jar.toFile())) {
            for (MainMenu entry : MainMenu.entries()) {
                if (client.getEntry(texture(entry.sprite())) == null) {
                    missing.add("menu " + entry.name() + " -> " + entry.sprite());
                }
            }
            for (PlayerSettingsStore.Category category : PlayerSettingsStore.Category.values()) {
                if (client.getEntry(texture(category.sprite())) == null) {
                    missing.add("settings " + category + " -> " + category.sprite());
                }
            }
        }
        assertTrue(missing.isEmpty(), "icons pointing at nothing: " + missing);
    }

    private static String texture(String sprite) {
        return "assets/minecraft/textures/" + sprite + ".png";
    }

    /**
     * Sweeps the source for every {@code "item/..."} and {@code "block/..."} literal.
     *
     * <p>Checking only the two enums missed the ones written inline, which is how a
     * bed icon survived in the homes screen after the same path was fixed in the menu.
     */
    @Test
    void noSpriteLiteralAnywhereNamesAMissingTexture() throws IOException {
        Path jar = CANDIDATES.stream().filter(Files::isRegularFile).findFirst().orElse(null);
        Assumptions.assumeTrue(jar != null, "no client jar on this machine");

        java.util.regex.Pattern literal =
                java.util.regex.Pattern.compile("\"((?:item|block)/[a-z0-9_]+)\"");
        List<String> missing = new ArrayList<>();
        try (ZipFile client = new ZipFile(jar.toFile());
                java.util.stream.Stream<Path> files =
                        Files.list(Path.of("src/main/java/bot/mgx/accessbridge"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                java.util.regex.Matcher match =
                        literal.matcher(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
                while (match.find()) {
                    if (client.getEntry(texture(match.group(1))) == null) {
                        missing.add(file.getFileName() + " -> " + match.group(1));
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "sprites pointing at nothing: " + missing);
    }
}
