package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MusicAuraTimelineTest {
    @Test
    void exactSongEnvelopeLoadsAndLoopsAtItsRecordedDuration() {
        MusicAuraTimeline.Sample beginning = MusicAuraTimeline.at(0L);
        MusicAuraTimeline.Sample looped = MusicAuraTimeline.at(MusicAuraTimeline.DURATION_MILLIS);
        MusicAuraTimeline.Sample later = MusicAuraTimeline.at(31_700L);

        assertEquals(2_947, MusicAuraTimeline.SAMPLE_COUNT);
        assertEquals(100, MusicAuraTimeline.SAMPLE_MILLIS);
        assertEquals(beginning, looped);
        assertNotEquals(beginning, later);
        for (double value : new double[]{
                later.bass(), later.mid(), later.high(), later.onset(), later.energy()
        }) {
            assertTrue(value >= 0d && value <= 1d);
        }
    }
}
