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
    private String announcedPost = "";
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
            announcedPost = root.has("announced_post")
                    ? root.get("announced_post").getAsString() : "";
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
     * Publishes when the blog's newest post changes.
     *
     * <p>The banner tells players to go and read the blog, so the blog is what
     * should decide when it appears. Tying it to the plugin version instead
     * meant a release with nothing to read still sent everybody to a page that
     * had not changed.
     *
     * @param slug the newest post's slug, straight from the site's manifest
     * @return true when this actually announced something
     */
    synchronized boolean publishIfPostChanged(String slug) {
        if (slug == null || slug.isBlank() || slug.equals(announcedPost)) {
            return false;
        }
        String previous = announcedPost;
        announcedPost = slug;
        // A first sighting must not announce. The server has only just learned
        // which post is newest; everyone has almost certainly already seen it.
        if (previous.isEmpty()) {
            persist();
            return false;
        }
        publish();
        return true;
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
        root.addProperty("announced_post", announcedPost);
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
