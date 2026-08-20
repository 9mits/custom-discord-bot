package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrophyHeadStoreTest {
    @Test
    void pairIsBlockedUntilTheExactTwentyFourHourBoundary(@TempDir Path directory)
            throws Exception {
        TrophyHeadStore store = new TrophyHeadStore(directory.resolve("trophies.json"));
        UUID killer = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        long first = 50_000L;

        assertTrue(store.claim(killer, victim, first));
        assertFalse(store.claim(killer, victim, first));
        assertFalse(store.claim(
                killer, victim, first + TrophyHeadStore.PAIR_COOLDOWN_MILLIS - 1L
        ));
        assertTrue(store.claim(
                killer, victim, first + TrophyHeadStore.PAIR_COOLDOWN_MILLIS
        ));
        assertFalse(store.claim(killer, killer, first));
    }

    @Test
    void reverseAndOtherVictimPairsAreIndependent(@TempDir Path directory) throws Exception {
        TrophyHeadStore store = new TrophyHeadStore(directory.resolve("trophies.json"));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        long now = 1_000L;

        assertTrue(store.claim(first, second, now));
        assertFalse(store.claim(first, second, now + 1L));
        assertTrue(store.claim(second, first, now + 1L));
        assertTrue(store.claim(first, third, now + 1L));
        assertTrue(store.claim(third, second, now + 1L));
    }

    @Test
    void cooldownSurvivesReload(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("trophies.json");
        UUID killer = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        long first = 5_000L;
        assertTrue(new TrophyHeadStore(file).claim(killer, victim, first));

        TrophyHeadStore reloaded = new TrophyHeadStore(file);

        assertFalse(reloaded.claim(killer, victim, first + 10L));
        assertTrue(reloaded.claim(
                killer, victim, first + TrophyHeadStore.PAIR_COOLDOWN_MILLIS
        ));
        assertFalse(new TrophyHeadStore(file).claim(
                killer, victim, first + TrophyHeadStore.PAIR_COOLDOWN_MILLIS + 1L
        ));
    }

    @Test
    void clearingForgetsEveryPairAcrossReload(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("trophies.json");
        TrophyHeadStore store = new TrophyHeadStore(file);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        store.claim(first, second, 100L);
        store.claim(first, third, 100L);

        assertEquals(2, store.clearAll());
        assertEquals(0, store.clearAll());

        TrophyHeadStore reloaded = new TrophyHeadStore(file);
        assertTrue(reloaded.claim(first, second, 101L));
        assertFalse(reloaded.claim(first, second, 102L));
    }

    @Test
    void failedWriteRestoresPrunedAndNewClaims(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("trophies.json");
        TrophyHeadStore store = new TrophyHeadStore(file);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        store.claim(first, second, 100L);
        java.nio.file.Files.createDirectory(directory.resolve("trophies.json.tmp"));

        assertThrows(RuntimeException.class, () -> store.claim(
                third, second, 100L + TrophyHeadStore.PAIR_COOLDOWN_MILLIS
        ));

        assertFalse(store.claim(first, second, 101L));
    }
}
