package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BadgeIconsTest {
    @Test
    void allBadgeGlyphsOccupyTheReservedE8RangeExactlyOnce() {
        Set<Integer> codePoints = new HashSet<>();
        for (String glyph : new String[] {
                BadgeIcons.PVP_BRONZE, BadgeIcons.PVP_SILVER, BadgeIcons.PVP_GOLD,
                BadgeIcons.PVP_PLATINUM, BadgeIcons.PVP_DIAMOND, BadgeIcons.PVP_ELITE,
                BadgeIcons.PVP_CHAMPION, BadgeIcons.PVP_UNREAL,
                BadgeIcons.CLAN_BATTLE_GOLD, BadgeIcons.CLAN_BATTLE_SILVER,
                BadgeIcons.CLAN_BATTLE_BRONZE, BadgeIcons.CLAN_LEVEL_1,
                BadgeIcons.CLAN_LEVEL_2, BadgeIcons.CLAN_LEVEL_3,
                BadgeIcons.CLAN_LEVEL_4, BadgeIcons.CLAN_LEVEL_5
        }) {
            assertEquals(1, glyph.codePointCount(0, glyph.length()));
            codePoints.add(glyph.codePointAt(0));
        }
        assertEquals(16, codePoints.size());
        assertTrue(codePoints.stream().allMatch(point -> point >= 0xE800 && point <= 0xE80F));
    }
}
