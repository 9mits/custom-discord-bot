package bot.mgx.accessbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The icon and the guest list for each clan warp.
 *
 * <p>Kept beside {@link ClanStore} rather than inside it: warps are part of a clan's
 * saved shape and this is presentation and access on top of them, which a clan can
 * live entirely without.
 *
 * <p><strong>An empty allow-list means everyone.</strong> That is the difference
 * between "nobody has decided yet" and "the leader decided nobody" — the second needs
 * an explicit act, so a warp is never silently unreachable because a list was created
 * and then emptied by a member leaving.
 */
final class ClanWarpMetaStore {
    private static final class Saved {
        String icon;
        Set<String> allowed = new LinkedHashSet<>();
    }

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Map<String, Map<String, Saved>> warps;

    ClanWarpMetaStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        this.warps = load();
    }

    synchronized String iconOf(UUID clanId, String warp) {
        Saved saved = find(clanId, warp);
        return saved != null && HomeIcons.known(saved.icon) ? saved.icon : "item/ender_pearl";
    }

    synchronized void setIcon(UUID clanId, String warp, String sprite) {
        if (!HomeIcons.known(sprite)) {
            throw new IllegalArgumentException("That icon is not one of the choices.");
        }
        Map<String, Map<String, Saved>> before = copy();
        entry(clanId, warp).icon = sprite;
        persistOrRestore(before);
    }

    /** Empty means every member; see the class note. */
    synchronized Set<UUID> allowed(UUID clanId, String warp) {
        Saved saved = find(clanId, warp);
        if (saved == null || saved.allowed.isEmpty()) {
            return Set.of();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (String raw : saved.allowed) {
            try {
                ids.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                // A hand-edited file should not stop the warp from opening.
            }
        }
        return ids;
    }

    synchronized boolean mayUse(UUID clanId, String warp, UUID playerId) {
        Set<UUID> allowed = allowed(clanId, warp);
        return allowed.isEmpty() || allowed.contains(playerId);
    }

    synchronized void toggleAllowed(UUID clanId, String warp, UUID playerId) {
        Map<String, Map<String, Saved>> before = copy();
        Saved saved = entry(clanId, warp);
        String key = playerId.toString();
        if (!saved.allowed.remove(key)) {
            saved.allowed.add(key);
        }
        persistOrRestore(before);
    }

    /** Replaces the restricted list in one save. An empty set restores everyone. */
    synchronized void allowOnly(UUID clanId, String warp, Set<UUID> playerIds) {
        Map<String, Map<String, Saved>> before = copy();
        Saved saved = entry(clanId, warp);
        saved.allowed.clear();
        if (playerIds != null) {
            playerIds.forEach(playerId -> saved.allowed.add(playerId.toString()));
        }
        persistOrRestore(before);
    }

    /** Clears the list, which restores the everyone default. */
    synchronized void allowEveryone(UUID clanId, String warp) {
        Map<String, Map<String, Saved>> before = copy();
        entry(clanId, warp).allowed.clear();
        persistOrRestore(before);
    }

    synchronized void rename(UUID clanId, String from, String to) {
        Map<String, Saved> owned = warps.get(clanId.toString());
        if (owned == null) {
            return;
        }
        String fromKey = key(from);
        String toKey = key(to);
        if (fromKey.equals(toKey)) {
            return;
        }
        Map<String, Map<String, Saved>> before = copy();
        Saved saved = owned.remove(fromKey);
        if (saved == null) {
            return;
        }
        owned.put(toKey, saved);
        persistOrRestore(before);
    }

    synchronized void forget(UUID clanId, String warp) {
        Map<String, Saved> owned = warps.get(clanId.toString());
        if (owned == null || !owned.containsKey(key(warp))) {
            return;
        }
        Map<String, Map<String, Saved>> before = copy();
        owned.remove(key(warp));
        if (owned.isEmpty()) {
            warps.remove(clanId.toString());
        }
        persistOrRestore(before);
    }

    synchronized int clearAll() {
        int count = warps.size();
        Map<String, Map<String, Saved>> before = copy();
        warps.clear();
        persistOrRestore(before);
        return count;
    }

    private Saved find(UUID clanId, String warp) {
        return warps.getOrDefault(clanId.toString(), Map.of()).get(key(warp));
    }

    private Saved entry(UUID clanId, String warp) {
        return warps.computeIfAbsent(clanId.toString(), ignored -> new LinkedHashMap<>())
                .computeIfAbsent(key(warp), ignored -> new Saved());
    }

    private static String key(String warp) {
        return warp.toLowerCase(Locale.ROOT);
    }

    private Map<String, Map<String, Saved>> load() throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Map<String, Saved>> read = gson.fromJson(
                    Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<LinkedHashMap<String, LinkedHashMap<String, Saved>>>() { }.getType()
            );
            if (read == null) {
                return new LinkedHashMap<>();
            }
            read.values().forEach(owned -> owned.values().forEach(saved -> {
                if (saved.allowed == null) {
                    saved.allowed = new LinkedHashSet<>();
                }
            }));
            return read;
        } catch (RuntimeException exception) {
            throw new IOException("Clan warp store is unreadable", exception);
        }
    }

    private Map<String, Map<String, Saved>> copy() {
        return gson.fromJson(
                gson.toJson(warps),
                new TypeToken<LinkedHashMap<String, LinkedHashMap<String, Saved>>>() { }.getType()
        );
    }

    private void persistOrRestore(Map<String, Map<String, Saved>> before) {
        try {
            save();
        } catch (RuntimeException exception) {
            warps.clear();
            warps.putAll(before);
            throw exception;
        }
    }

    private void save() {
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, gson.toJson(warps), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
