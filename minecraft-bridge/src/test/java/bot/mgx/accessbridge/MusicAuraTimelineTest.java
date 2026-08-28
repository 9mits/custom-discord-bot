package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void beatMapIsSparseAndCarriesAnAttackThenVisibleRecoil() {
        int strikes = 0;
        long previousStrike = -1L;
        long firstStrike = -1L;
        for (long millis = 0L; millis < MusicAuraTimeline.DURATION_MILLIS;
                millis += MusicAuraTimeline.SAMPLE_MILLIS) {
            MusicAuraTimeline.Beat beat = MusicAuraTimeline.beatAt(millis);
            if (!beat.strike()) {
                continue;
            }
            strikes++;
            assertTrue(beat.pulse() >= 0.82d);
            if (previousStrike >= 0L) {
                assertTrue(millis - previousStrike >= 400L);
            }
            previousStrike = millis;
            if (firstStrike < 0L) {
                firstStrike = millis;
            }
        }

        assertEquals(MusicAuraTimeline.BEAT_COUNT, strikes);
        assertTrue(strikes > 300 && strikes < 500);
        MusicAuraTimeline.Beat attack = MusicAuraTimeline.beatAt(firstStrike);
        MusicAuraTimeline.Beat release = MusicAuraTimeline.beatAt(firstStrike + 100L);
        MusicAuraTimeline.Beat recoil = MusicAuraTimeline.beatAt(firstStrike + 200L);
        assertTrue(attack.strike());
        assertFalse(release.strike());
        assertTrue(attack.pulse() > release.pulse());
        assertTrue(recoil.recoil() > release.recoil());
    }
}
