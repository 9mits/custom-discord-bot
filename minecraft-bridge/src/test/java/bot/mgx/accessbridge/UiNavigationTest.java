package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class UiNavigationTest {
    private static final Path SOURCE = Path.of("src/main/java/bot/mgx/accessbridge");

    @Test
    void aDestinationRetainsThePageAndEveryEarlierOrigin() {
        Menu.Destination home = Menu.Destination.of(Menu.Kind.MAIN_MENU);
        Menu.Destination directory = new Menu.Destination(
                Menu.Kind.CLAN_LIST, null, 4, home);
        UUID clan = UUID.randomUUID();
        Menu.Destination card = new Menu.Destination(
                Menu.Kind.CLAN_INFO, clan, 1, directory);

        assertEquals(clan, card.subject());
        assertEquals(4, card.back().page());
        assertEquals(Menu.Kind.MAIN_MENU, card.back().back().kind());
    }

    @Test
    void readOnlyMenuRoutersIgnoreClicksInThePlayerInventory() throws Exception {
        for (String name : List.of(
                "MainMenuService.java",
                "PlayerMenuService.java",
                "ClanMenuService.java",
                "LeaderboardMenuService.java",
                "TeleportMenuService.java",
                "BountyService.java",
                "StatsDialogService.java"
        )) {
            String source = Files.readString(SOURCE.resolve(name), StandardCharsets.UTF_8);
            assertTrue(source.contains(
                            "event.getClickedInventory() != event.getInventory()"),
                    name + " can mistake a player-inventory slot for a menu button");
        }
    }

    @Test
    void leaderboardBlankAndNavigationSlotsCannotOpenHiddenRows() throws Exception {
        String source = Files.readString(
                SOURCE.resolve("LeaderboardMenuService.java"), StandardCharsets.UTF_8);

        assertTrue(countOf(source, "slot >= PER_PAGE") >= 2,
                "both clan-board row handlers must reject the navigation row");
    }

    @Test
    void whitelistScreensRespectTheLinkedNamePrivacySetting() throws Exception {
        for (String name : List.of("PlayerMenuService.java", "RecordListDialogs.java")) {
            String source = Files.readString(SOURCE.resolve(name), StandardCharsets.UTF_8);
            assertTrue(source.contains("visibleDiscordUsername("),
                    name + " must ask the identity store before showing a Discord name");
            assertTrue(!source.contains("entry.discordUsername()"),
                    name + " exposes the raw directory name without its privacy setting");
        }
    }

    private static int countOf(String text, String value) {
        int count = 0;
        for (int at = text.indexOf(value); at >= 0; at = text.indexOf(value, at + 1)) {
            count++;
        }
        return count;
    }
}
