package bot.mgx.accessbridge;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Durable verified applications that are still waiting for a Discord decision. */
final class VerifiedAccountStore {
    record VerifiedAccount(long applicationId, UUID minecraftUuid, String username) {
    }

    private final Path file;
    private final Gson gson = new Gson();
    private final LinkedHashMap<Long, VerifiedAccount> applications = new LinkedHashMap<>();

    VerifiedAccountStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                JsonObject value = entry.getValue().getAsJsonObject();
                long applicationId = Long.parseLong(entry.getKey());
                applications.put(applicationId, new VerifiedAccount(
                        applicationId,
                        UUID.fromString(value.get("minecraft_uuid").getAsString()),
                        value.get("username").getAsString()
                ));
            }
        } catch (RuntimeException exception) {
            throw new IOException("Verified application store is unreadable", exception);
        }
    }

    synchronized void put(long applicationId, UUID minecraftUuid, String username) {
        VerifiedAccount previous = applications.put(
                applicationId,
                new VerifiedAccount(applicationId, minecraftUuid, username)
        );
        persistOrRollback(applicationId, previous);
    }

    synchronized Optional<VerifiedAccount> find(UUID minecraftUuid) {
        return applications.values().stream()
                .filter(application -> application.minecraftUuid().equals(minecraftUuid))
                .findFirst();
    }

    synchronized Optional<VerifiedAccount> get(long applicationId) {
        return Optional.ofNullable(applications.get(applicationId));
    }

    synchronized void remove(long applicationId) {
        VerifiedAccount previous = applications.remove(applicationId);
        if (previous != null) {
            persistOrRollback(applicationId, previous);
        }
    }

    /** Forgets every verified application, so nobody reads as already verified. */
    synchronized int clearAll() {
        int cleared = applications.size();
        if (cleared == 0) {
            return 0;
        }
        LinkedHashMap<Long, VerifiedAccount> previous = new LinkedHashMap<>(applications);
        applications.clear();
        try {
            persist();
        } catch (IOException exception) {
            applications.putAll(previous);
            throw new UncheckedIOException(exception);
        }
        return cleared;
    }

    private void persistOrRollback(long applicationId, VerifiedAccount previous) {
        try {
            persist();
        } catch (IOException exception) {
            if (previous == null) {
                applications.remove(applicationId);
            } else {
                applications.put(applicationId, previous);
            }
            throw new UncheckedIOException(exception);
        }
    }

    private void persist() throws IOException {
        JsonObject root = new JsonObject();
        for (VerifiedAccount application : applications.values()) {
            JsonObject value = new JsonObject();
            value.addProperty("minecraft_uuid", application.minecraftUuid().toString());
            value.addProperty("username", application.username());
            root.add(String.valueOf(application.applicationId()), value);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, gson.toJson(root), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
