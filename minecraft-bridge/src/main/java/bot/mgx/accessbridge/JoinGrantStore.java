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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Join-time money and bounty grants. Each toggle-on starts a new pass: a player
 * can receive that grant once until the toggle is turned off and on again.
 */
final class JoinGrantStore {
    enum Kind {
        MONEY,
        BOUNTY
    }

    private final Path file;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Grant money = new Grant();
    private final Grant bounty = new Grant();

    JoinGrantStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            load(money, root.getAsJsonObject("money"));
            load(bounty, root.getAsJsonObject("bounty"));
        } catch (RuntimeException exception) {
            throw new IOException("Join grant store is unreadable", exception);
        }
    }

    synchronized boolean enabled(Kind kind) {
        return grant(kind).enabled;
    }

    synchronized long amount(Kind kind) {
        return grant(kind).amount;
    }

    synchronized void enable(Kind kind, long amount) {
        if (amount <= 0L) {
            throw new IllegalArgumentException("The amount must be at least $1.");
        }
        Grant grant = grant(kind);
        GrantState before = snapshot(grant);
        grant.enabled = true;
        grant.amount = amount;
        grant.claimed.clear();
        persistOrRestore(grant, before);
    }

    synchronized void disable(Kind kind) {
        Grant grant = grant(kind);
        GrantState before = snapshot(grant);
        grant.enabled = false;
        persistOrRestore(grant, before);
    }

    /** True when this player should receive the grant right now. */
    synchronized boolean tryClaim(Kind kind, UUID playerId) {
        Grant grant = grant(kind);
        if (!grant.enabled || grant.amount <= 0L || !grant.claimed.add(playerId)) {
            return false;
        }
        try {
            persist();
        } catch (RuntimeException failure) {
            grant.claimed.remove(playerId);
            throw failure;
        }
        return true;
    }

    synchronized void releaseClaim(Kind kind, UUID playerId) {
        Grant grant = grant(kind);
        if (!grant.claimed.remove(playerId)) {
            return;
        }
        try {
            persist();
        } catch (RuntimeException failure) {
            grant.claimed.add(playerId);
            throw failure;
        }
    }

    private Grant grant(Kind kind) {
        return kind == Kind.MONEY ? money : bounty;
    }

    private static void load(Grant grant, JsonObject json) {
        if (json == null) {
            return;
        }
        grant.enabled = json.has("enabled") && json.get("enabled").getAsBoolean();
        grant.amount = json.has("amount") ? Math.max(0L, json.get("amount").getAsLong()) : 0L;
        if (json.has("claimed") && json.get("claimed").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("claimed")) {
                try {
                    grant.claimed.add(UUID.fromString(element.getAsString()));
                } catch (RuntimeException ignored) {
                    // skip a bad id
                }
            }
        }
    }

    private void persist() {
        JsonObject root = new JsonObject();
        root.add("money", write(money));
        root.add("bounty", write(bounty));
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

    private static JsonObject write(Grant grant) {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", grant.enabled);
        json.addProperty("amount", grant.amount);
        JsonArray claimed = new JsonArray();
        grant.claimed.forEach(id -> claimed.add(id.toString()));
        json.add("claimed", claimed);
        return json;
    }

    private void persistOrRestore(Grant grant, GrantState before) {
        try {
            persist();
        } catch (RuntimeException failure) {
            grant.enabled = before.enabled();
            grant.amount = before.amount();
            grant.claimed.clear();
            grant.claimed.addAll(before.claimed());
            throw failure;
        }
    }

    private static GrantState snapshot(Grant grant) {
        return new GrantState(grant.enabled, grant.amount, Set.copyOf(grant.claimed));
    }

    private record GrantState(boolean enabled, long amount, Set<UUID> claimed) {
    }

    private static final class Grant {
        boolean enabled;
        long amount;
        final Set<UUID> claimed = new HashSet<>();
    }
}
