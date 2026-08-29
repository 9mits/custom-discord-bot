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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The icon a player chose for each of their homes.
 *
 * <p>Essentials owns the homes themselves and has nowhere to keep this, so it lives
 * beside them rather than inside them — which also means a home renamed or deleted
 * through Essentials directly cannot corrupt anything here; at worst it leaves an
 * entry nothing reads.
 *
 * <p>Only sprites {@link HomeIcons} knows are stored. An unknown one would draw a
 * magenta square on the player's own menu forever, and there is no way for them to
 * tell that it was the value rather than the screen that was wrong.
 */
final class HomeIconStore {
    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Map<String, Map<String, String>> icons;

    HomeIconStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        this.icons = load();
    }

    synchronized String iconOf(UUID playerId, String home) {
        String chosen = icons.getOrDefault(playerId.toString(), Map.of())
                .get(home.toLowerCase(Locale.ROOT));
        return HomeIcons.known(chosen) ? chosen : HomeIcons.DEFAULT;
    }

    synchronized void setIcon(UUID playerId, String home, String sprite) {
        if (!HomeIcons.known(sprite)) {
            throw new IllegalArgumentException("That icon is not one of the choices.");
        }
        Map<String, Map<String, String>> before = copy();
        icons.computeIfAbsent(playerId.toString(), ignored -> new LinkedHashMap<>())
                .put(home.toLowerCase(Locale.ROOT), sprite);
        persistOrRestore(before);
    }

    /** Called when a home is renamed, so its icon follows it. */
    synchronized void rename(UUID playerId, String from, String to) {
        Map<String, String> owned = icons.get(playerId.toString());
        if (owned == null) {
            return;
        }
        String sprite = owned.remove(from.toLowerCase(Locale.ROOT));
        if (sprite == null) {
            return;
        }
        Map<String, Map<String, String>> before = copy();
        owned.put(to.toLowerCase(Locale.ROOT), sprite);
        persistOrRestore(before);
    }

    synchronized void forget(UUID playerId, String home) {
        Map<String, String> owned = icons.get(playerId.toString());
        if (owned == null || owned.remove(home.toLowerCase(Locale.ROOT)) == null) {
            return;
        }
        Map<String, Map<String, String>> before = copy();
        if (owned.isEmpty()) {
            icons.remove(playerId.toString());
        }
        persistOrRestore(before);
    }

    synchronized int clearAll() {
        int count = icons.size();
        Map<String, Map<String, String>> before = copy();
        icons.clear();
        persistOrRestore(before);
        return count;
    }

    private Map<String, Map<String, String>> load() throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Map<String, String>> read = gson.fromJson(
                    Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<LinkedHashMap<String, LinkedHashMap<String, String>>>() { }.getType()
            );
            return read == null ? new LinkedHashMap<>() : read;
        } catch (RuntimeException exception) {
            throw new IOException("Home icon store is unreadable", exception);
        }
    }

    private Map<String, Map<String, String>> copy() {
        Map<String, Map<String, String>> snapshot = new LinkedHashMap<>();
        icons.forEach((player, owned) -> snapshot.put(player, new LinkedHashMap<>(owned)));
        return snapshot;
    }

    private void persistOrRestore(Map<String, Map<String, String>> before) {
        try {
            save();
        } catch (RuntimeException exception) {
            icons.clear();
            icons.putAll(before);
            throw exception;
        }
    }

    private void save() {
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, gson.toJson(icons), StandardCharsets.UTF_8);
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
