package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedApplicationStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void verifiedApplicationsPersistUntilTheirDecisionArrives() throws Exception {
        Path path = temporaryDirectory.resolve("verified-applications.json");
        UUID minecraftUuid = UUID.randomUUID();
        VerifiedApplicationStore store = new VerifiedApplicationStore(path);

        store.put(42, minecraftUuid, "PlayerOne");
        VerifiedApplicationStore reloaded = new VerifiedApplicationStore(path);

        assertEquals("PlayerOne", reloaded.find(minecraftUuid).orElseThrow().username());
        reloaded.remove(42);
        assertTrue(new VerifiedApplicationStore(path).find(minecraftUuid).isEmpty());
    }
}
