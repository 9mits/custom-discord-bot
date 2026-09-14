package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SeasonStoreTest {
    @TempDir
    Path directory;

    @Test
    void seasonHeartsRespectTheCapAndExpireWithTheSeason() throws Exception {
        Path file = directory.resolve("season-pass.json");
        SeasonStore store = new SeasonStore(file);
        UUID player = UUID.randomUUID();
        store.startSeason(1, 100, 142);

        assertEquals(2, store.addHearts(player, 2, 3));
        assertEquals(1, store.addHearts(player, 5, 3), "only up to the cap is added");
        assertEquals(0, store.addHearts(player, 1, 3), "a capped player gets nothing more");
        store.persist();
        assertEquals(3, new SeasonStore(file).hearts(player), "hearts survive a restart inside the season");

        store.startSeason(2, 142, 184);
        assertEquals(0, store.hearts(player), "a new season starts everybody on zero hearts");
        assertEquals(1, store.addHearts(player, 1, 3), "and the new season can pay them again");
    }
}
