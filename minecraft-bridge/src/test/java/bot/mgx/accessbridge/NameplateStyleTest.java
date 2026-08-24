package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class NameplateStyleTest {
    @Test
    void usesTheCompactMoneyIconThenPlacementLayout() {
        LeaderboardStandings.Standing standing = new LeaderboardStandings.Standing(
                LeaderboardType.KILLS, 1, 42
        );

        Component line = SidebarService.nameplateLine(5_000_000L, Optional.of(standing));

        assertEquals("$ 5M  •  ⚔ #1", PlainTextComponentSerializer.plainText().serialize(line));
        assertEquals(NamedTextColor.GREEN, line.color());
        assertEquals(NamedTextColor.RED, line.children().get(1).color());
        assertEquals(SidebarService.placementColour(1), line.children().get(2).color());
        assertNeverBold(line);
    }

    @Test
    void moneyStaysCompactWithoutALeaderboardPlacement() {
        Component line = SidebarService.nameplateLine(92_230L, Optional.empty());

        assertEquals("$ 92.2K", PlainTextComponentSerializer.plainText().serialize(line));
    }

    @Test
    void boardIconAndPodiumPlacementUseDifferentColours() {
        assertEquals(NamedTextColor.RED, SidebarService.leaderboardIconColour(LeaderboardType.KILLS));
        assertEquals(NamedTextColor.GREEN, SidebarService.leaderboardIconColour(LeaderboardType.WEALTH));
        assertEquals(
                net.kyori.adventure.text.format.TextColor.color(0xFFD700),
                SidebarService.placementColour(1)
        );
    }

    private static void assertNeverBold(Component component) {
        assertNotEquals(TextDecoration.State.TRUE, component.decoration(TextDecoration.BOLD));
        component.children().forEach(NameplateStyleTest::assertNeverBold);
    }
}
