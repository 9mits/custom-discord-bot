package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PvpClanRecordStoreTest {
    @Test
    void clanWinsLossesAndStreaksSurviveReload(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("clan-pvp.json");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        PvpClanRecordStore store = new PvpClanRecordStore(file);
        store.settle(first, second);
        store.settle(first, second);
        store.settle(second, first);

        PvpClanRecordStore reopened = new PvpClanRecordStore(file);
        assertEquals(2, reopened.of(first).wins());
        assertEquals(1, reopened.of(first).losses());
        assertEquals(0, reopened.of(first).streak());
        assertEquals(2, reopened.of(first).bestStreak());
        assertEquals(1, reopened.of(second).wins());
        assertEquals(2, reopened.of(second).losses());
    }
}
