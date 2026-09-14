package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AfkRewardShareTest {
    @Test
    void activeTimeIsPaidInFullAndAfkTimeAtItsShare() {
        assertEquals(60_000L, AfkRewardShare.creditedMillis(60_000L, false, 25));
        assertEquals(15_000L, AfkRewardShare.creditedMillis(60_000L, true, 25));
        assertEquals(0L, AfkRewardShare.creditedMillis(60_000L, true, 0));
    }

    @Test
    void ladderKeysScaleWithTheAfkPartOfTheHour() {
        assertEquals(8, AfkRewardShare.keys(8, 0d, 25));
        assertEquals(2, AfkRewardShare.keys(8, 1d, 25), "a fully AFK hour keeps a quarter");
        assertEquals(5, AfkRewardShare.keys(8, 0.5d, 25));
        assertEquals(0.5d, AfkRewardShare.share(30 * 60_000L, 60 * 60_000L));
        assertEquals(1d, AfkRewardShare.share(90 * 60_000L, 60 * 60_000L));
    }
}
