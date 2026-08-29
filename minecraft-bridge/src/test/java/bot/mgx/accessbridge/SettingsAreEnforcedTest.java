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
 * A setting nobody reads is worse than no setting: it tells the player they turned
 * something off and then keeps doing it. {@code CHAT_NOTIFICATIONS} sat in the panel
 * unread for a long time, which is what this exists to stop happening again.
 */
final class SettingsAreEnforcedTest {
    private static final Path SOURCE = Path.of("src/main/java/bot/mgx/accessbridge");

    @Test
    void everySettingIsReadSomewhereOutsideTheStore() throws IOException {
        List<String> unread = new ArrayList<>();
        for (PlayerSettingsStore.Setting setting : PlayerSettingsStore.Setting.values()) {
            if (!isRead(setting)) {
                unread.add(setting.name());
            }
        }
        assertTrue(unread.isEmpty(), "settings nothing enforces: " + unread);
    }

    private static boolean isRead(PlayerSettingsStore.Setting setting) throws IOException {
        String needle = "Setting." + setting.name();
        try (Stream<Path> files = Files.list(SOURCE)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".java"))
                    .filter(file -> !file.getFileName().toString().equals("PlayerSettingsStore.java"))
                    .anyMatch(file -> contains(file, needle));
        }
    }

    private static boolean contains(Path file, String needle) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).contains(needle);
        } catch (IOException ignored) {
            return false;
        }
    }
}
