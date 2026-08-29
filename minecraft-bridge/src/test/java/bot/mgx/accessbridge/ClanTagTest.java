package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClanTagTest {
    @TempDir
    Path directory;

    @Test
    void medalsStackAndRideAlongsideTheLevelStar() throws Exception {
        ClanStore clans = new ClanStore(directory.resolve("clans.json"));
        UUID leader = UUID.randomUUID();
        ClanStore.ClanView clan = clans.create(leader, "Leader", "STARS");

        String none = plain(ClanTag.of(clan, new ClanBattleStore.Badges(0, 0, 0)));
        assertEquals("[STARS] ", none);

        String stacked = plain(ClanTag.of(clan, new ClanBattleStore.Badges(2, 1, 0)));
        assertTrue(stacked.startsWith("[STARS] "), stacked);
        assertTrue(stacked.contains("x2"), stacked);
        assertEquals(2, stacked.chars().filter(point -> point == '◆').count(), stacked);
    }

    @Test
    void theMeasuredWidthMatchesWhatIsActuallyDrawn() throws Exception {
        ClanStore clans = new ClanStore(directory.resolve("clans.json"));
        ClanStore.ClanView clan = clans.create(UUID.randomUUID(), "Leader", "WIDTH");
        ClanBattleStore.Badges badges = new ClanBattleStore.Badges(1, 0, 3);

        // The player-list column pads to a measured width, so a medal missing from
        // the measurement would push every following column out of line.
        assertEquals(plain(ClanTag.of(clan, badges)), ClanTag.plain(clan, badges));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
