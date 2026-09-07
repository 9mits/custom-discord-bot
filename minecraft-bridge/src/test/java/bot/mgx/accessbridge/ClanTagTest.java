package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClanTagTest {
    @TempDir
    Path directory;

    @Test
    void customBattleBadgesStackAlongsideTheClan() throws Exception {
        ClanStore clans = new ClanStore(directory.resolve("clans.json"));
        UUID leader = UUID.randomUUID();
        ClanStore.ClanView clan = clans.create(leader, "Leader", "STARS");

        String none = plain(ClanTag.of(clan, new ClanBattleStore.Badges(0, 0, 0)));
        assertEquals("[item/amethyst_shard@items] [STARS] ", none);

        String stacked = plain(ClanTag.of(clan, new ClanBattleStore.Badges(2, 1, 0)));
        assertTrue(stacked.startsWith("[item/amethyst_shard@items] [STARS] "), stacked);
        assertFalse(stacked.contains("x2"), stacked);
        assertEquals(1, stacked.chars()
                .filter(point -> point == BadgeIcons.CLAN_BATTLE_GOLD.charAt(0)).count(), stacked);
        assertEquals(1, stacked.chars()
                .filter(point -> point == BadgeIcons.CLAN_BATTLE_SILVER.charAt(0)).count(), stacked);
    }

    @Test
    void overheadTagOmitsTheLevelBadgeButTabTagKeepsIt() throws Exception {
        ClanStore clans = new ClanStore(directory.resolve("level-clans.json"));
        UUID leader = UUID.randomUUID();
        clans.create(leader, "Leader", "LEVEL");
        clans.donate(leader, ClanLevel.costOf(1).orElseThrow().dollars());
        ClanStore.ClanView clan = clans.upgrade(leader);
        ClanBattleStore.Badges none = new ClanBattleStore.Badges(0, 0, 0);

        assertTrue(plain(ClanTag.of(clan, none)).contains(ClanLevel.badge(clan.level())));
        assertFalse(plain(ClanTag.overhead(clan, none)).contains(ClanLevel.badge(clan.level())));
    }

    @Test
    void theMeasuredWidthMatchesWhatIsActuallyDrawn() throws Exception {
        ClanStore clans = new ClanStore(directory.resolve("clans.json"));
        ClanStore.ClanView clan = clans.create(UUID.randomUUID(), "Leader", "WIDTH");
        ClanBattleStore.Badges badges = new ClanBattleStore.Badges(1, 0, 3);

        // The player-list column pads to a measured width, so a medal missing from
        // the measurement would push every following column out of line.
        assertTrue(plain(ClanTag.of(clan, badges)).endsWith(ClanTag.plain(clan, badges)));
        assertEquals(12, ClanTag.iconWidth());
    }

    @Test
    void battleWinCountsNeverAddTagMultipliers() throws Exception {
        ClanStore clans = new ClanStore(directory.resolve("multipliers.json"));
        ClanStore.ClanView clan = clans.create(UUID.randomUUID(), "Leader", "WINS");
        ClanBattleStore.Badges many = new ClanBattleStore.Badges(12, 8, 4);

        String tab = plain(ClanTag.of(clan, many));
        String overhead = plain(ClanTag.overhead(clan, many));
        assertFalse(tab.contains("x12") || tab.contains("x8") || tab.contains("x4"));
        assertFalse(overhead.contains("x12") || overhead.contains("x8") || overhead.contains("x4"));
        assertEquals(3, ClanTag.plainMedals(many).codePoints()
                .filter(point -> point >= 0xE808 && point <= 0xE80A).count());
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
