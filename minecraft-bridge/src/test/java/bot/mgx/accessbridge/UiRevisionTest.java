package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class UiRevisionTest {
    @Test
    void compactBitmapFontIsGoneFromSourceAndGeneratedPack() throws Exception {
        Path packRoot = Path.of("..", "assets", "resourcepack");
        assertFalse(Files.exists(packRoot.resolve("src/assets/mgx/font/compact.json")));
        assertFalse(Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/MenuText.java"
        )).contains("COMPACT_FONT"));
        assertFalse(Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/MenuItems.java"
        )).contains("COMPACT_FONT"));
        try (ZipFile pack = new ZipFile(packRoot.resolve("MysteriousSMPX.zip").toFile())) {
            assertFalse(pack.stream().anyMatch(entry ->
                    entry.getName().equals("assets/mgx/font/compact.json")));
        }
    }

    @Test
    void clanHubExposesDedicatedPromotionPickerAndConfirmation() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/ClanDialogService.java"
        ));

        assertTrue(source.contains("\"Promote Member\""));
        assertTrue(source.contains("openPromoteMembers"));
        assertTrue(source.contains("confirmPromotion"));
        assertTrue(source.contains("clans.setStaff(player.getUniqueId(), memberId, true)"));
    }
}
