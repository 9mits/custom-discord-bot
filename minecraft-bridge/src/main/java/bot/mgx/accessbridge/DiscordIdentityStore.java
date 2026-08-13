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

final class DiscordIdentityStore {
    record Identity(String username, boolean visible) {
    }

    private final Path file;
    private final Gson gson = new Gson();
    private final LinkedHashMap<UUID, Identity> identities = new LinkedHashMap<>();

    DiscordIdentityStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                JsonObject value = entry.getValue().getAsJsonObject();
                String username = value.get("username").getAsString().trim();
                boolean visible = !value.has("visible") || value.get("visible").getAsBoolean();
                if (!username.isEmpty()) {
                    identities.put(UUID.fromString(entry.getKey()), new Identity(username, visible));
                }
            }
        } catch (RuntimeException exception) {
            throw new IOException("Discord identity store is unreadable", exception);
        }
    }

    synchronized void sync(UUID minecraftUuid, String username) {
        String normalized = String.valueOf(username == null ? "" : username).trim();
        if (normalized.isEmpty()) {
            return;
        }
        if (normalized.length() > 32) {
            normalized = normalized.substring(0, 32);
        }
        Identity previous = identities.get(minecraftUuid);
        boolean visible = previous == null || previous.visible();
        Identity updated = new Identity(normalized, visible);
        if (updated.equals(previous)) {
            return;
        }
        identities.put(minecraftUuid, updated);
        persistOrRollback(minecraftUuid, previous);
    }

    synchronized Optional<String> visibleUsername(UUID minecraftUuid) {
        Identity identity = identities.get(minecraftUuid);
        if (identity == null || !identity.visible()) {
            return Optional.empty();
        }
        return Optional.of(identity.username());
    }

    synchronized Optional<Identity> identity(UUID minecraftUuid) {
        return Optional.ofNullable(identities.get(minecraftUuid));
    }

    synchronized Identity toggle(UUID minecraftUuid) {
        Identity previous = identities.get(minecraftUuid);
        if (previous == null) {
            throw new IllegalStateException("No linked Discord account is available yet.");
        }
        Identity updated = new Identity(previous.username(), !previous.visible());
        identities.put(minecraftUuid, updated);
        persistOrRollback(minecraftUuid, previous);
        return updated;
    }

    private void persistOrRollback(UUID minecraftUuid, Identity previous) {
        try {
            persist();
        } catch (IOException exception) {
            if (previous == null) {
                identities.remove(minecraftUuid);
            } else {
                identities.put(minecraftUuid, previous);
            }
            throw new UncheckedIOException(exception);
        }
    }

    private void persist() throws IOException {
        JsonObject root = new JsonObject();
        identities.forEach((minecraftUuid, identity) -> {
            JsonObject value = new JsonObject();
            value.addProperty("username", identity.username());
            value.addProperty("visible", identity.visible());
            root.add(minecraftUuid.toString(), value);
        });
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, gson.toJson(root), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
