package bot.mgx.accessbridge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether the server is closed to everyone but staff.
 *
 * <p>Persisted rather than held in memory. The bot pushes the current state when
 * the bridge connects, but the server accepts logins from the moment it finishes
 * starting — which is before that connection exists. A hold that forgot itself
 * across a restart would quietly open the server during exactly the window
 * nobody is watching.
 *
 * <p>Free of Bukkit imports so it can be unit tested.
 */
final class MaintenanceStore {
    private final Path file;
    private volatile boolean enabled;

    MaintenanceStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file)) {
            return;
        }
        // Anything unreadable reads as open. The alternative — assuming a hold
        // nobody asked for — locks the server against its owner with no way in.
        this.enabled = "true".equalsIgnoreCase(Files.readString(file).trim());
    }

    boolean enabled() {
        return enabled;
    }

    /** @return true when this actually changed the state. */
    boolean set(boolean value) {
        if (enabled == value) {
            return false;
        }
        enabled = value;
        try {
            Files.writeString(file, Boolean.toString(value), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return true;
    }
}
