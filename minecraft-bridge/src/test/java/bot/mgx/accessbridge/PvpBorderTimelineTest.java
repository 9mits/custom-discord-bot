package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PvpBorderTimelineTest {
    @Test
    void borderMovesContinuouslyAcrossTheWholeConfiguredInterval() {
        assertEquals(288d,
                PvpCompetitionService.interpolatedBorderSize(
                        288d, 278d, 1_000L, 31_000L, 1_000L));
        assertEquals(283d,
                PvpCompetitionService.interpolatedBorderSize(
                        288d, 278d, 1_000L, 31_000L, 16_000L));
        assertEquals(278d,
                PvpCompetitionService.interpolatedBorderSize(
                        288d, 278d, 1_000L, 31_000L, 31_000L));
    }

    @Test
    void delayedTicksClampRatherThanOvershootingTheSafeZone() {
        assertEquals(278d,
                PvpCompetitionService.interpolatedBorderSize(
                        288d, 278d, 1_000L, 31_000L, 60_000L));
        assertEquals(288d,
                PvpCompetitionService.interpolatedBorderSize(
                        288d, 278d, 1_000L, 31_000L, 0L));
    }
}
