package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        store.settle(first, second, 3, 1);
        store.settle(first, second, 2, 0);
        store.settle(second, first, 4, 1);

        PvpClanRecordStore reopened = new PvpClanRecordStore(file);
        assertEquals(2, reopened.of(first).wins());
        assertEquals(1, reopened.of(first).losses());
        assertEquals(0, reopened.of(first).streak());
        assertEquals(2, reopened.of(first).bestStreak());
        assertEquals(6, reopened.of(first).kills());
        assertEquals(1, reopened.of(second).wins());
        assertEquals(2, reopened.of(second).losses());
        assertEquals(5, reopened.of(second).kills());
    }

    @Test
    void legacyClanRecordsWithoutKillsMigrateAsZero(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("clan-pvp.json");
        UUID clan = UUID.randomUUID();
        Files.writeString(file, "{\"" + clan + "\":{\"wins\":2,\"losses\":1,"
                + "\"streak\":1,\"best_streak\":2}}", StandardCharsets.UTF_8);

        PvpClanRecordStore reopened = new PvpClanRecordStore(file);
        assertEquals(0, reopened.of(clan).kills());
    }
}
