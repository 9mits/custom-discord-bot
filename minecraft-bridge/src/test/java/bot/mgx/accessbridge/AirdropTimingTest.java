package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AirdropTimingTest {
    @Test
    void defaultsUseMoreFrequentRandomDelaysAndThirtyMinuteExpiry() {
        long minimum = AirdropService.DEFAULT_MINIMUM_DELAY_MILLIS;
        long maximum = AirdropService.DEFAULT_MAXIMUM_DELAY_MILLIS;
        Set<Long> observed = new HashSet<>();
        Random random = new Random(82L);
        for (int index = 0; index < 200; index++) {
            long delay = AirdropService.randomDelayMillis(random, minimum, maximum);
            assertTrue(delay >= minimum);
            assertTrue(delay <= maximum);
            observed.add(delay);
        }

        assertEquals(Duration.ofMinutes(15).toMillis(), minimum);
        assertEquals(Duration.ofMinutes(30).toMillis(), maximum);
        assertEquals(Duration.ofMinutes(30).toMillis(), AirdropService.DEFAULT_LIFETIME_MILLIS);
        assertTrue(observed.size() > 190, "each interval should be independently randomized");
    }

    @Test
    void everyRarityOffsetStaysInsideItsSpawnRing() {
        int[][] rings = {
                {1_000, 2_000},
                {1_000, 2_000},
                {5_000, 10_000},
                {10_000, 25_000}
        };
        Random random = new Random(2645L);
        for (int[] ring : rings) {
            long minimumSquared = (long) ring[0] * ring[0];
            long maximumSquared = (long) ring[1] * ring[1];
            for (int attempt = 0; attempt < 200; attempt++) {
                AirdropService.Offset offset = AirdropService.randomOffset(
                        random, ring[0], ring[1]
                );
                assertTrue(offset.distanceSquared() >= minimumSquared);
                assertTrue(offset.distanceSquared() <= maximumSquared);
            }
        }
    }

    @Test
    void countdownDrainsContinuouslyAndRoundsTheVisibleSecondUp() {
        long spawnedAt = 1_000L;
        long expiresAt = spawnedAt + Duration.ofMinutes(30).toMillis();

        assertEquals(1f, AirdropService.remainingProgress(spawnedAt, spawnedAt, expiresAt));
        assertEquals(0.5f, AirdropService.remainingProgress(
                spawnedAt + Duration.ofMinutes(15).toMillis(), spawnedAt, expiresAt
        ));
        assertEquals(0f, AirdropService.remainingProgress(expiresAt, spawnedAt, expiresAt));
        assertEquals(0f, AirdropService.remainingProgress(expiresAt + 10_000L, spawnedAt, expiresAt));
        assertEquals("30:00", AirdropService.formatCountdown(Duration.ofMinutes(30).toMillis()));
        assertEquals("00:01", AirdropService.formatCountdown(1L));
        assertEquals("00:00", AirdropService.formatCountdown(0L));
    }

    @Test
    void chestTitlesFitWhileTheFullRarityLivesInTheWorldLabels() {
        for (AirdropCatalog.Rarity rarity : AirdropCatalog.Rarity.values()) {
            String title = AirdropService.chestTitle(rarity);
            assertEquals(rarity.displayName() + " Airdrop", title);
            assertTrue(title.length() <= 18, title);
        }
    }

    @Test
    void evenTheAllFeaturesTestSelectsOnlyOneCosmetic() {
        Random random = new Random(44L);
        Set<String> observed = new HashSet<>();
        for (int roll = 0; roll < 100; roll++) {
            Optional<String> selected = AirdropService.selectCosmetic(
                    Optional.of("resonant_shatter"),
                    AirdropCatalog.cosmeticIds(),
                    random
            );
            assertTrue(selected.isPresent());
            assertTrue(AirdropCatalog.cosmeticIds().contains(selected.orElseThrow()));
            observed.add(selected.orElseThrow());
        }
        assertEquals(Set.copyOf(AirdropCatalog.cosmeticIds()), observed);
        assertEquals(
                Optional.of("crystalfall_wake"),
                AirdropService.selectCosmetic(
                        Optional.of("resonant_shatter"), List.of("crystalfall_wake"), random
                )
        );
        assertEquals(
                Optional.of("resonant_shatter"),
                AirdropService.selectCosmetic(
                        Optional.of("resonant_shatter"), List.of(), random
                )
        );
    }
}
