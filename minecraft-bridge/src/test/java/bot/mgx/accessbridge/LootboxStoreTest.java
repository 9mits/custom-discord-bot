package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LootboxStoreTest {
    @Test
    void threeCompletedSpinsFillTheRollingWindow(@TempDir Path directory) throws Exception {
        LootboxStore store = new LootboxStore(directory.resolve("lootboxes.json"));
        UUID player = UUID.randomUUID();
        long firstOpening = 1_000_000L;

        for (int index = 0; index < LootboxStore.OPENING_LIMIT; index++) {
            UUID spin = UUID.randomUUID();
            store.reserve(player, spin, "raw_copper", firstOpening + index);
            assertTrue(store.complete(player, spin));
        }

        assertEquals(0, store.remaining(player, firstOpening + 10L));
        LootboxStore.LimitReachedException failure = assertThrows(
                LootboxStore.LimitReachedException.class,
                () -> store.reserve(
                        player, UUID.randomUUID(), "diamonds", firstOpening + 10L
                )
        );
        assertEquals(firstOpening + LootboxStore.WINDOW_MILLIS, failure.nextOpeningAt());
        assertEquals(
                firstOpening + LootboxStore.WINDOW_MILLIS,
                store.nextOpeningAt(player, firstOpening + 10L)
        );
    }

    @Test
    void oldestOpeningReopensExactlyAtTwentyFourHours(@TempDir Path directory) throws Exception {
        LootboxStore store = new LootboxStore(directory.resolve("lootboxes.json"));
        UUID player = UUID.randomUUID();
        long firstOpening = 10_000L;
        for (int index = 0; index < LootboxStore.OPENING_LIMIT; index++) {
            UUID spin = UUID.randomUUID();
            store.reserve(player, spin, "raw_iron", firstOpening + index * 1_000L);
            store.complete(player, spin);
        }

        long boundary = firstOpening + LootboxStore.WINDOW_MILLIS;
        assertEquals(0, store.remaining(player, boundary - 1L));
        assertEquals(1, store.remaining(player, boundary));

        UUID replacement = UUID.randomUUID();
        LootboxStore.Pending pending = store.reserve(
                player, replacement, "cosmetic_blood_burst", boundary
        );
        assertEquals(replacement, pending.spinId());
        assertEquals(0, store.remaining(player, boundary));
    }

    @Test
    void pendingRewardSurvivesReloadAndOnlyItsSpinCanComplete(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("lootboxes.json");
        UUID player = UUID.randomUUID();
        UUID spin = UUID.randomUUID();
        long reservedAt = 42_000L;
        new LootboxStore(file).reserve(player, spin, "mace", reservedAt);

        LootboxStore reloaded = new LootboxStore(file);
        LootboxStore.Pending pending = reloaded.pending(player).orElseThrow();
        assertEquals(spin, pending.spinId());
        assertEquals("mace", pending.rewardId());
        assertEquals(reservedAt, pending.reservedAt());
        assertEquals(pending, reloaded.pendingRewards().get(player));

        assertFalse(reloaded.complete(player, UUID.randomUUID()));
        assertTrue(new LootboxStore(file).pending(player).isPresent());
        assertTrue(reloaded.complete(player, spin));
        assertTrue(new LootboxStore(file).pending(player).isEmpty());
        assertEquals(2, new LootboxStore(file).remaining(player, reservedAt));
    }

    @Test
    void aWaitingRewardPreventsASecondReservation(@TempDir Path directory) throws Exception {
        LootboxStore store = new LootboxStore(directory.resolve("lootboxes.json"));
        UUID player = UUID.randomUUID();
        LootboxStore.Pending first = store.reserve(
                player, UUID.randomUUID(), "totem_of_undying", 1_000L
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> store.reserve(player, UUID.randomUUID(), "diamonds", 2_000L)
        );

        assertTrue(failure.getMessage().contains("already waiting"));
        assertEquals(first, store.pending(player).orElseThrow());
        assertEquals(2, store.remaining(player, 2_000L));
    }

    @Test
    void clearingRemovesLimitsAndPendingRewardsAcrossReload(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("lootboxes.json");
        LootboxStore store = new LootboxStore(file);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID completed = UUID.randomUUID();
        store.reserve(first, completed, "raw_gold", 100L);
        store.complete(first, completed);
        store.reserve(second, UUID.randomUUID(), "golden_apple", 200L);

        assertEquals(3, store.clearAll());

        LootboxStore reloaded = new LootboxStore(file);
        assertEquals(LootboxStore.OPENING_LIMIT, reloaded.remaining(first, 200L));
        assertEquals(LootboxStore.OPENING_LIMIT, reloaded.remaining(second, 200L));
        assertTrue(reloaded.pendingRewards().isEmpty());
        assertEquals(0, reloaded.clearAll());
    }
}
