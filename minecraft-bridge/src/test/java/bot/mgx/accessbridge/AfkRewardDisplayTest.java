package bot.mgx.accessbridge;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AfkRewardDisplayTest {
    @Test
    void bossBarMakesEveryLiveBoostVisible() {
        AfkRewardDisplay.Status status = new AfkRewardDisplay.Status(
                3, 5, 12, 2, 60, 17 * 60_000L, 4, 0.625f
        );

        assertEquals(
                "AFK T3 • 5 keys/hr (+2 from 12 online) • 17m 0s • T4 63%",
                plain(AfkRewardDisplay.bossBar(status))
        );
    }

    @Test
    void maximumTierAndReadyRewardAreUnmistakable() {
        AfkRewardDisplay.Status status = new AfkRewardDisplay.Status(
                6, 14, 30, 4, 60, 0L, 0, 1f
        );

        assertEquals(
                "AFK T6 • 14 keys/hr (+4 from 30 online) • reward ready • MAX",
                plain(AfkRewardDisplay.bossBar(status))
        );
        assertTrue(plain(AfkRewardDisplay.tierUp(status)).contains(
                "Tier 6 now pays 14 keys per reward, including +4 from 30 online."
        ));
    }

    @Test
    void aCustomIntervalDoesNotPretendThePayoutIsHourly() {
        AfkRewardDisplay.Status status = new AfkRewardDisplay.Status(
                2, 3, 8, 1, 30, 90_000L, 3, 0.25f
        );

        assertTrue(plain(AfkRewardDisplay.bossBar(status)).contains("3 keys/reward"));
    }

    @Test
    void tierProgressStartsAtTheCurrentThresholdAndClamps() {
        assertEquals(0f, AfkRewardDisplay.tierProgress(3 * 3_600L, 3, 6));
        assertEquals(0.5f, AfkRewardDisplay.tierProgress(4 * 3_600L + 1_800L, 3, 6));
        assertEquals(1f, AfkRewardDisplay.tierProgress(20 * 3_600L, 3, 6));
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
