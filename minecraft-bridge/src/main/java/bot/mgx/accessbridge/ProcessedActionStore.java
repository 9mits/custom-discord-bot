package bot.mgx.accessbridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    }

    synchronized Optional<Result> get(String idempotencyKey) {
        String value = properties.getProperty(idempotencyKey);
        if (value == null) {
            return Optional.empty();
        }
        int separator = value.indexOf(':');
        boolean success = separator < 0 ? Boolean.parseBoolean(value) : Boolean.parseBoolean(value.substring(0, separator));
        String error = separator < 0 ? "" : value.substring(separator + 1);
        return Optional.of(new Result(success, error));
    }

    CompletableFuture<Void> put(String idempotencyKey, Result result) {
        synchronized (this) {
            properties.setProperty(idempotencyKey, result.success() + ":" + result.error().replace('\n', ' '));
        }
        return CompletableFuture.runAsync(this::persist, ioExecutor);
    }

    /**
     * Forgets every recorded action outcome.
     *
     * <p>Safe only as part of a full reset: these records are what stop a replayed
     * bridge action being applied twice, and the bot's own queue is cleared alongside.
     */
    int clearAll() {
        int cleared;
        synchronized (this) {
            cleared = properties.size();
            if (cleared == 0) {
                return 0;
            }
            properties.clear();
        }
        persist();
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
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not persist processed bridge actions", exception);
        }
    }
}
