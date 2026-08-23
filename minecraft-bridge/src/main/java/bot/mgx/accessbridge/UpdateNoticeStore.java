package bot.mgx.accessbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
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
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One generation per published update. Bumping it clears who has already seen
 * the notice, so the next login shows it again.
 */
final class UpdateNoticeStore {
    private final Path file;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private int generation;
    private String announcedVersion = "";
    private final Set<UUID> seen = new HashSet<>();

    UpdateNoticeStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            generation = root.has("generation") ? Math.max(0, root.get("generation").getAsInt()) : 0;
            announcedVersion = root.has("announced_version")
                    ? root.get("announced_version").getAsString() : "";
            if (root.has("seen") && root.get("seen").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("seen")) {
                    try {
                        seen.add(UUID.fromString(element.getAsString()));
                    } catch (RuntimeException ignored) {
                        // skip a bad id
                    }
                }
            }
        } catch (RuntimeException exception) {
            throw new IOException("Update notice store is unreadable", exception);
        }
    }

    synchronized boolean active() {
        return generation > 0;
    }

    synchronized int generation() {
        return generation;
    }

    synchronized int publish() {
        int beforeGeneration = generation;
        Set<UUID> beforeSeen = Set.copyOf(seen);
        generation++;
        seen.clear();
        try {
            persist();
        } catch (RuntimeException failure) {
            generation = beforeGeneration;
            seen.clear();
            seen.addAll(beforeSeen);
            throw failure;
        }
        return generation;
    }

    /**
     * Publishes automatically when the plugin's feature version moves.
     *
     * <p>A shipped update is a new jar, so the jar is the signal — no command to
     * remember, and nothing to forget after a deploy. Only major.minor counts:
     * a patch release is a fix, not something to interrupt every player about.
     *
     * @return true when this actually announced something
     */
    synchronized boolean publishIfVersionChanged(String pluginVersion) {
        String feature = featureVersion(pluginVersion);
        if (feature.isEmpty() || feature.equals(announcedVersion)) {
            return false;
        }
        String previous = announcedVersion;
        announcedVersion = feature;
        // A first run must not announce: the store is being created now, and
        // every existing player would get a banner for an update they already have.
        if (previous.isEmpty()) {
            persist();
            return false;
        }
        publish();
        return true;
    }

    /** "6.5.1" becomes "6.5"; anything unparseable becomes empty. */
    static String featureVersion(String raw) {
        if (raw == null) {
            return "";
        }
        String[] parts = raw.trim().split("\\.");
        if (parts.length < 2) {
            return "";
        }
        return parts[0] + "." + parts[1];
    }

    synchronized boolean tryClaim(UUID playerId) {
        if (generation <= 0 || !seen.add(playerId)) {
            return false;
        }
        try {
            persist();
        } catch (RuntimeException failure) {
            seen.remove(playerId);
            throw failure;
        }
        return true;
    }

    synchronized void markSeen(Collection<UUID> playerIds) {
        Set<UUID> before = Set.copyOf(seen);
        boolean changed = seen.addAll(playerIds);
        if (!changed) {
            return;
        }
        try {
            persist();
        } catch (RuntimeException failure) {
            seen.clear();
            seen.addAll(before);
            throw failure;
        }
    }

    private void persist() {
        JsonObject root = new JsonObject();
        root.addProperty("generation", generation);
        root.addProperty("announced_version", announcedVersion);
        JsonArray seenJson = new JsonArray();
        seen.forEach(id -> seenJson.add(id.toString()));
        root.add("seen", seenJson);
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, gson.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
