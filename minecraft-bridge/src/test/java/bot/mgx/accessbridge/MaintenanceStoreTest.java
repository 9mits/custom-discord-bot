package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaintenanceStoreTest {
    @TempDir
    Path directory;

    private MaintenanceStore store() throws IOException {
        return new MaintenanceStore(directory.resolve("maintenance.flag"));
    }

    @Test
    void aServerWithNoFlagIsOpen() throws IOException {
        assertFalse(store().enabled());
    }

    @Test
    void closingSurvivesARestart() throws IOException {
        // The server accepts logins from the moment it starts, which is before the
        // bridge connects to be told anything. A hold that forgot itself would open
        // the server during exactly the window nobody is watching.
        store().set(true);

        assertTrue(store().enabled());
    }

    @Test
    void openingAgainSurvivesARestart() throws IOException {
        store().set(true);
        store().set(false);

        assertFalse(store().enabled());
    }

    @Test
    void settingTheSameValueReportsNoChange() throws IOException {
        MaintenanceStore store = store();

        assertTrue(store.set(true));
        assertFalse(store.set(true));
        assertTrue(store.set(false));
    }

    @Test
    void anUnreadableFlagLeavesTheServerOpen() throws IOException {
        // Assuming a hold nobody asked for locks the owner out of their own server
        // with no way back in; assuming it is open is recoverable in one command.
        Path file = directory.resolve("maintenance.flag");
        Files.writeString(file, "not a boolean");

        assertFalse(new MaintenanceStore(file).enabled());
    }
}
