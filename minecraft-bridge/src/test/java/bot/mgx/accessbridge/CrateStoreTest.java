package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrateStoreTest {
    @Test
    void legacyLootboxDataMigratesToTheCanonicalCrateFile(@TempDir Path directory)
            throws Exception {
        Path legacy = directory.resolve("lootboxes.json");
        UUID player = UUID.randomUUID();
        UUID opening = UUID.randomUUID();
        new CrateStore(legacy).reserve(player, opening, "mace", 12_000L);

        CrateStore migrated = CrateStore.open(directory);

        assertTrue(Files.isRegularFile(directory.resolve("crates.json")));
        assertFalse(Files.exists(legacy));
        assertEquals(opening, migrated.pending(player).orElseThrow().spinId());
    }

    @Test
    void completedOpeningsFillTheRollingWindow(@TempDir Path directory) throws Exception {
        CrateStore store = new CrateStore(directory.resolve("crates.json"));
        UUID player = UUID.randomUUID();
        long firstOpening = 1_000_000L;

        for (int index = 0; index < CrateStore.OPENING_LIMIT; index++) {
            UUID spin = UUID.randomUUID();
            store.reserve(player, spin, "raw_copper", firstOpening + index);
            assertTrue(store.complete(player, spin));
        }

        assertEquals(0, store.remaining(player, firstOpening + 10L));
        CrateStore.LimitReachedException failure = assertThrows(
                CrateStore.LimitReachedException.class,
                () -> store.reserve(
                        player, UUID.randomUUID(), "diamonds", firstOpening + 10L
                )
        );
        assertEquals(firstOpening + CrateStore.WINDOW_MILLIS, failure.nextOpeningAt());
        assertEquals(
                firstOpening + CrateStore.WINDOW_MILLIS,
                store.nextOpeningAt(player, firstOpening + 10L)
        );
    }

    @Test
    void oldestOpeningReopensExactlyAtTwentyFourHours(@TempDir Path directory) throws Exception {
        CrateStore store = new CrateStore(directory.resolve("crates.json"));
        UUID player = UUID.randomUUID();
        long firstOpening = 10_000L;
        for (int index = 0; index < CrateStore.OPENING_LIMIT; index++) {
            UUID spin = UUID.randomUUID();
            store.reserve(player, spin, "raw_iron", firstOpening + index * 1_000L);
            store.complete(player, spin);
        }

        long boundary = firstOpening + CrateStore.WINDOW_MILLIS;
        assertEquals(0, store.remaining(player, boundary - 1L));
        assertEquals(1, store.remaining(player, boundary));

        UUID replacement = UUID.randomUUID();
        CrateStore.Pending pending = store.reserve(
                player, replacement, "cosmetic_blood_burst", boundary
        );
        assertEquals(replacement, pending.spinId());
        assertEquals(0, store.remaining(player, boundary));
    }

    @Test
    void pendingRewardSurvivesReloadAndOnlyItsSpinCanComplete(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("crates.json");
        UUID player = UUID.randomUUID();
        UUID spin = UUID.randomUUID();
        long reservedAt = 42_000L;
        new CrateStore(file).reserve(player, spin, "mace", reservedAt);

        CrateStore reloaded = new CrateStore(file);
        CrateStore.Pending pending = reloaded.pending(player).orElseThrow();
        assertEquals(spin, pending.spinId());
        assertEquals("mace", pending.rewardId());
        assertEquals(reservedAt, pending.reservedAt());
        assertEquals(pending, reloaded.pendingRewards().get(player));

        assertFalse(reloaded.complete(player, UUID.randomUUID()));
        assertTrue(new CrateStore(file).pending(player).isPresent());
        assertTrue(reloaded.complete(player, spin));
        assertTrue(new CrateStore(file).pending(player).isEmpty());
        assertEquals(CrateStore.OPENING_LIMIT - 1, new CrateStore(file).remaining(player, reservedAt));
    }

    @Test
    void aWaitingRewardPreventsASecondReservation(@TempDir Path directory) throws Exception {
        CrateStore store = new CrateStore(directory.resolve("crates.json"));
        UUID player = UUID.randomUUID();
        CrateStore.Pending first = store.reserve(
                player, UUID.randomUUID(), "totem_of_undying", 1_000L
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> store.reserve(player, UUID.randomUUID(), "diamonds", 2_000L)
        );

        assertTrue(failure.getMessage().contains("already waiting"));
        assertEquals(first, store.pending(player).orElseThrow());
        assertEquals(CrateStore.OPENING_LIMIT - 1, store.remaining(player, 2_000L));
    }

    @Test
    void everyFullOnlineHourBanksOneKeyAndKeepsTheRemainder(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("crates.json");
        UUID player = UUID.randomUUID();
        CrateStore store = new CrateStore(file);

        CrateStore.KeyCredit first = store.creditOnline(Map.of(
                player, CrateStore.HOURLY_KEY_MILLIS - 1_000L
        )).get(player);
        assertEquals(0, first.earned());
        assertEquals(0, first.banked());
        assertEquals(1_000L, first.millisUntilNext());

        CrateStore.KeyCredit second = store.creditOnline(Map.of(player, 1_000L)).get(player);
        assertEquals(1, second.earned());
        assertEquals(1, second.banked());
        assertEquals(CrateStore.HOURLY_KEY_MILLIS, second.millisUntilNext());

        CrateStore reloaded = new CrateStore(file);
        assertEquals(1, reloaded.bankedKeys(player));
        assertEquals(CrateStore.HOURLY_KEY_MILLIS, reloaded.millisUntilNextKey(player));
        assertEquals(1, reloaded.claimBankedKeys(player, 64));
        assertEquals(0, new CrateStore(file).bankedKeys(player));
    }

    @Test
    void multipleHoursCreditInOneBatchWithoutLosingProgress(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("crates.json");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CrateStore store = new CrateStore(file);

        Map<UUID, CrateStore.KeyCredit> credits = store.creditOnline(Map.of(
                first, CrateStore.HOURLY_KEY_MILLIS * 3L + 5_000L,
                second, CrateStore.HOURLY_KEY_MILLIS * 2L
        ));

        assertEquals(3, credits.get(first).earned());
        assertEquals(3, credits.get(first).banked());
        assertEquals(CrateStore.HOURLY_KEY_MILLIS - 5_000L,
                credits.get(first).millisUntilNext());
        assertEquals(2, credits.get(second).earned());
        assertEquals(2, credits.get(second).banked());
        assertEquals(2, store.claimBankedKeys(first, 2));
        assertEquals(1, new CrateStore(file).bankedKeys(first));
    }

    @Test
    void clearingRemovesLimitsAndPendingRewardsAcrossReload(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("crates.json");
        CrateStore store = new CrateStore(file);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID completed = UUID.randomUUID();
        store.reserve(first, completed, "raw_gold", 100L);
        store.complete(first, completed);
        store.reserve(second, UUID.randomUUID(), "golden_apple", 200L);
        store.creditOnline(Map.of(first, CrateStore.HOURLY_KEY_MILLIS));

        assertEquals(4, store.clearAll());

        CrateStore reloaded = new CrateStore(file);
        assertEquals(CrateStore.OPENING_LIMIT, reloaded.remaining(first, 200L));
        assertEquals(CrateStore.OPENING_LIMIT, reloaded.remaining(second, 200L));
        assertTrue(reloaded.pendingRewards().isEmpty());
        assertEquals(0, reloaded.bankedKeys(first));
        assertEquals(0, reloaded.clearAll());
    }
}
