package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BountyStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void bountiesStackAndSurviveReload() throws Exception {
        Path path = temporaryDirectory.resolve("bounties.json");
        UUID target = UUID.randomUUID();
        BountyStore store = new BountyStore(path);
        assertEquals(250L, store.add(target, 250L));
        assertEquals(400L, store.add(target, 150L));

        BountyStore reloaded = new BountyStore(path);
        assertEquals(400L, reloaded.amountOn(target));
        assertEquals(400L, reloaded.collect(target));
        assertEquals(0L, reloaded.amountOn(target));
    }

    @Test
    void aBountyBelowTheFloorIsRefused() throws Exception {
        BountyStore store = new BountyStore(temporaryDirectory.resolve("bounties.json"));
        assertThrows(IllegalArgumentException.class, () -> store.add(UUID.randomUUID(), 50L));
    }
}
