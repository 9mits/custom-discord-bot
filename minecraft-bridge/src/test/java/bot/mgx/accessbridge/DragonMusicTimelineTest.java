package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DragonMusicTimelineTest {
    @Test
    void suppliedDragonSongEnvelopeLoadsAndLoopsAtItsRecordedDuration() {
        MusicAuraTimeline.Sample beginning = DragonMusicTimeline.at(0L);
        MusicAuraTimeline.Sample looped = DragonMusicTimeline.at(DragonMusicTimeline.DURATION_MILLIS);
        MusicAuraTimeline.Sample later = DragonMusicTimeline.at(42_000L);

        assertEquals(1_333, DragonMusicTimeline.SAMPLE_COUNT);
        assertEquals(100, DragonMusicTimeline.SAMPLE_MILLIS);
        assertEquals(beginning, looped);
        assertNotEquals(beginning, later);
        for (double value : new double[]{
                later.bass(), later.mid(), later.high(), later.onset(), later.energy()
        }) {
            assertTrue(value >= 0d && value <= 1d);
        }
    }

    @Test
    void dragonSecretRevealWaitsForTheWholeSong() {
        CrateCatalog.Reward reward = CrateCatalog.everyReward().stream()
                .filter(candidate -> CosmeticCatalog.DRAGON_SECRET_COSMETIC_ID.equals(candidate.cosmeticId()))
                .findFirst().orElseThrow();
        assertEquals(DragonMusicTimeline.SAMPLE_COUNT * 2L,
                CosmeticEffectService.revealDurationTicks(reward));
    }
}
