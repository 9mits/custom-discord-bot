package bot.mgx.accessbridge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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
    synchronized boolean set(boolean value) {
        if (enabled == value) {
            return false;
        }
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, Boolean.toString(value), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupportedAtomicMove) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        enabled = value;
        return true;
    }
}
