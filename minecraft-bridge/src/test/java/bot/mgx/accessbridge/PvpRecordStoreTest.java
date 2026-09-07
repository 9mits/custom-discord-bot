package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The permanent duelling record the Kills boards are now ranked on. */
final class PvpRecordStoreTest {
    private static final UUID WINNER = UUID.randomUUID();
    private static final UUID LOSER = UUID.randomUUID();

    /**
     * Somebody who surrenders is beaten but not killed. A board that cannot tell
     * the difference rewards pressing a button over winning a fight.
     */
    @Test
    void surrenderIsAWinButNotAKill(@TempDir Path folder) throws Exception {
        PvpRecordStore store = new PvpRecordStore(folder.resolve("records.json"));
        store.settle(WINNER, LOSER, false);

        assertEquals(1, store.of(WINNER).wins());
        assertEquals(0, store.of(WINNER).kills());
        assertEquals(1, store.of(LOSER).losses());
        assertEquals(0, store.of(LOSER).deaths());

        store.settle(WINNER, LOSER, true);
        assertEquals(2, store.of(WINNER).wins());
        assertEquals(1, store.of(WINNER).kills());
        assertEquals(1, store.of(LOSER).deaths());
    }

    @Test
    void aStreakSurvivesADrawAndDiesOnALoss(@TempDir Path folder) throws Exception {
        PvpRecordStore store = new PvpRecordStore(folder.resolve("records.json"));
        store.settle(WINNER, LOSER, true);
        store.settle(WINNER, LOSER, true);
        assertEquals(2, store.of(WINNER).streak());

        // A draw settles nothing, so it ends nothing.
        store.drew(WINNER, LOSER);
        assertEquals(2, store.of(WINNER).streak());
        assertEquals(1, store.of(WINNER).draws());

        store.settle(LOSER, WINNER, true);
        assertEquals(0, store.of(WINNER).streak());
        assertEquals(2, store.of(WINNER).bestStreak());
    }

    @Test
    void aRecordSurvivesARestart(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("records.json");
        PvpRecordStore store = new PvpRecordStore(file);
        store.settle(WINNER, LOSER, true);
        store.drew(WINNER, LOSER);
        long rating = store.of(WINNER).rating();

        PvpRecordStore reopened = new PvpRecordStore(file);
        assertEquals(1, reopened.of(WINNER).kills());
        assertEquals(1, reopened.of(WINNER).draws());
        assertEquals(1, reopened.of(WINNER).bestStreak());
        assertEquals(1, reopened.of(LOSER).losses());
        // A rank that resets on restart is not a rank.
        assertEquals(rating, reopened.of(WINNER).rating());
        assertEquals(store.of(WINNER).bestRank(), reopened.of(WINNER).bestRank());
    }

    /**
     * Both ratings are read before either is written. Scoring the winner against an
     * opponent who has already lost points to them inflates every result.
     */
    @Test
    void oneFightIsScoredOnTheRatingsBothPlayersBroughtToIt(@TempDir Path folder)
            throws Exception {
        PvpRecordStore store = new PvpRecordStore(folder.resolve("records.json"));
        java.util.Map<UUID, PvpRecordStore.RatingChange> changes =
                store.settle(WINNER, LOSER, true);

        PvpRecordStore.RatingChange won = changes.get(WINNER);
        PvpRecordStore.RatingChange lost = changes.get(LOSER);
        assertEquals(0, won.before());
        assertEquals(0, lost.before());
        // Evenly matched at 0 RP each, so the pair is symmetrical.
        assertEquals(PvpRank.K_FACTOR / 2, won.delta());
        assertEquals(0, lost.after(), "a rating cannot fall below the bottom rung");
        assertEquals(won.after(), store.of(WINNER).rating());
    }

    @Test
    void reachingATierIsNotUndoneByLosing(@TempDir Path folder) throws Exception {
        PvpRecordStore store = new PvpRecordStore(folder.resolve("records.json"));
        for (int fight = 0; fight < 40; fight++) {
            store.settle(WINNER, LOSER, true);
        }
        PvpRank reached = store.of(WINNER).rank();
        assertTrue(reached.ordinal() > PvpRank.BRONZE_I.ordinal(), "should have climbed");

        for (int fight = 0; fight < 60; fight++) {
            store.settle(LOSER, WINNER, true);
        }
        assertEquals(reached.tier(), store.of(WINNER).rank().tier());
        assertEquals(store.of(WINNER).bestRank().tierFloor(), store.of(WINNER).rating());
    }

    /** Both halves of a fight land together, so a board never sees half of one. */
    @Test
    void oneFightIsOneNotification(@TempDir Path folder) throws Exception {
        PvpRecordStore store = new PvpRecordStore(folder.resolve("records.json"));
        AtomicInteger published = new AtomicInteger();
        store.onChange(published::incrementAndGet);

        store.settle(WINNER, LOSER, true);
        assertEquals(1, published.get());
        store.drew(WINNER, LOSER);
        assertEquals(2, published.get());
    }

    @Test
    void winRateIgnoresDrawsAndSurvivesAnEmptyRecord(@TempDir Path folder) throws Exception {
        PvpRecordStore store = new PvpRecordStore(folder.resolve("records.json"));
        assertEquals(0d, store.of(WINNER).winRate());
        assertTrue(store.of(WINNER).isEmpty());

        store.settle(WINNER, LOSER, true);
        store.drew(WINNER, LOSER);
        store.settle(LOSER, WINNER, true);
        assertEquals(0.5d, store.of(WINNER).winRate());
    }
}
