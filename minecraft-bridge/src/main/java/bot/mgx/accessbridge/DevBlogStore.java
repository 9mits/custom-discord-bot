package bot.mgx.accessbridge;

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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Holds the belongings of anyone currently in a screenshot session.
 *
 * <p>Written to disk the moment a session starts, because the alternative is
 * keeping somebody's entire inventory in memory and handing it back only if the
 * server happens to shut down politely. A crash mid-session would eat it. With
 * the stash on disk the items survive anything, and the plugin hands them back
 * on the next join.
 */
final class DevBlogStore {

    /** One player's belongings and the state to put back when they are done. */
    record Session(
            String encodedContents,
            String encodedArmour,
            String previousGameMode,
            boolean keptArmour,
            long startedAt
    ) {
    }

    private final Path file;
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();

    DevBlogStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                JsonObject value = entry.getValue().getAsJsonObject();
                sessions.put(
                        UUID.fromString(entry.getKey()),
                        new Session(
                                value.get("contents").getAsString(),
                                value.has("armour") ? value.get("armour").getAsString() : "",
                                value.has("gamemode") ? value.get("gamemode").getAsString() : "SURVIVAL",
                                value.has("keptArmour") && value.get("keptArmour").getAsBoolean(),
                                value.has("startedAt") ? value.get("startedAt").getAsLong() : 0L
                        )
                );
            }
        } catch (RuntimeException exception) {
            // Refusing to start is the right answer: silently continuing would
            // present an empty stash and the items would be gone for good.
            throw new IOException("Dev blog session store is unreadable", exception);
        }
    }

    synchronized boolean isActive(UUID player) {
        return sessions.containsKey(player);
    }

    synchronized Optional<Session> find(UUID player) {
        return Optional.ofNullable(sessions.get(player));
    }

    synchronized java.util.Set<UUID> everyone() {
        return java.util.Set.copyOf(sessions.keySet());
    }

    synchronized void open(UUID player, Session session) {
        sessions.put(player, session);
        save();
    }

    /** Removes and returns the session, so a restore can never run twice. */
    synchronized Optional<Session> close(UUID player) {
        Session session = sessions.remove(player);
        if (session != null) {
            save();
        }
        return Optional.ofNullable(session);
    }

    static String encode(byte[] raw) {
        return Base64.getEncoder().encodeToString(raw);
    }

    static byte[] decode(String encoded) {
        return Base64.getDecoder().decode(encoded);
    }

    private void save() {
        JsonObject root = new JsonObject();
        sessions.forEach((player, session) -> {
            JsonObject value = new JsonObject();
            value.addProperty("contents", session.encodedContents());
            value.addProperty("armour", session.encodedArmour());
            value.addProperty("gamemode", session.previousGameMode());
            value.addProperty("keptArmour", session.keptArmour());
            value.addProperty("startedAt", session.startedAt());
            root.add(player.toString(), value);
        });
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException fallback) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not save the dev blog session store", exception);
        }
    }
}
