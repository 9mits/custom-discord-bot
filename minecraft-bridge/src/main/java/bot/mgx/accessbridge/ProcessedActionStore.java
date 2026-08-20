package bot.mgx.accessbridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

final class ProcessedActionStore {
    record Result(boolean success, String error) {}

    private final Path path;
    private final Executor ioExecutor;
    private final Properties properties = new Properties();

    ProcessedActionStore(Path path, Executor ioExecutor) throws IOException {
        this.path = path;
        this.ioExecutor = ioExecutor;
        if (Files.isRegularFile(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
            }
        }
        boolean interrupted = false;
        for (String key : properties.stringPropertyNames()) {
            if (IN_PROGRESS.equals(properties.getProperty(key))) {
                properties.setProperty(
                        key,
                        "false:Previous attempt was interrupted and was not replayed automatically."
                );
                interrupted = true;
            }
        }
        if (interrupted) {
            persist();
        }
    }

    private static final String IN_PROGRESS = "IN_PROGRESS";

    synchronized Optional<Result> get(String idempotencyKey) {
        String value = properties.getProperty(idempotencyKey);
        if (value == null || value.startsWith(IN_PROGRESS)) {
            return Optional.empty();
        }
        int separator = value.indexOf(':');
        boolean success = separator < 0 ? Boolean.parseBoolean(value) : Boolean.parseBoolean(value.substring(0, separator));
        String error = separator < 0 ? "" : value.substring(separator + 1);
        return Optional.of(new Result(success, error));
    }

    /** Durably claims a key before its side effect, preventing replay after a crash. */
    synchronized boolean reserve(String idempotencyKey) {
        if (properties.containsKey(idempotencyKey)) {
            return false;
        }
        properties.setProperty(idempotencyKey, IN_PROGRESS);
        try {
            persist();
        } catch (RuntimeException failure) {
            properties.remove(idempotencyKey);
            throw failure;
        }
        return true;
    }

    CompletableFuture<Void> put(String idempotencyKey, Result result) {
        String encoded = result.success() + ":" + result.error().replace('\n', ' ');
        String before;
        synchronized (this) {
            before = properties.getProperty(idempotencyKey);
            properties.setProperty(idempotencyKey, encoded);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                persist();
            } catch (RuntimeException failure) {
                synchronized (this) {
                    if (encoded.equals(properties.getProperty(idempotencyKey))) {
                        if (before == null) {
                            properties.remove(idempotencyKey);
                        } else {
                            properties.setProperty(idempotencyKey, before);
                        }
                    }
                }
                throw failure;
            }
        }, ioExecutor);
    }

    /**
     * Forgets every recorded action outcome.
     *
     * <p>Safe only as part of a full reset: these records are what stop a replayed
     * bridge action being applied twice, and the bot's own queue is cleared alongside.
     */
    int clearAll() {
        int cleared;
        Properties before = new Properties();
        synchronized (this) {
            cleared = properties.size();
            if (cleared == 0) {
                return 0;
            }
            before.putAll(properties);
            properties.clear();
        }
        try {
            persist();
        } catch (RuntimeException failure) {
            synchronized (this) {
                properties.putAll(before);
            }
            throw failure;
        }
        return cleared;
    }

    private void persist() {
        Properties snapshot = new Properties();
        synchronized (this) {
            snapshot.putAll(properties);
        }
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                snapshot.store(output, "MGXAccessBridge idempotency results");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupportedAtomicMove) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not persist processed bridge actions", exception);
        }
    }
}
