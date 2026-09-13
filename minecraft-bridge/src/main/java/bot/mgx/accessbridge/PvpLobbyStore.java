package bot.mgx.accessbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

/** Persistent PvP lobby and main-world entrance; combat arenas are never stored here. */
final class PvpLobbyStore {
    /**
     * Bumped to 5 for the dedicated leaderboard gallery and its wider protected area.
     * A newer format rebuilds the lobby once on the next start and re-saves the spawn.
     */
    private static final int FORMAT_VERSION = 5;

    record Point(
            String worldId,
            String worldName,
            double x,
            double y,
            double z,
            float yaw,
            float pitch
    ) {
        Point {
            if ((worldId == null || worldId.isBlank())
                    && (worldName == null || worldName.isBlank())) {
                throw new IllegalArgumentException("A PvP point needs a world.");
            }
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("PvP coordinates must be finite.");
            }
        }

        static Point of(Location location) {
            if (location == null || location.getWorld() == null) {
                throw new IllegalArgumentException("Stand in a loaded world first.");
            }
            return new Point(location.getWorld().getUID().toString(),
                    location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch());
        }

        Location resolve() {
            World world = null;
            try {
                if (worldId != null && !worldId.isBlank()) {
                    world = Bukkit.getWorld(UUID.fromString(worldId));
                }
            } catch (IllegalArgumentException ignored) {
                // A renamed world may still resolve through its saved name.
            }
            if (world == null && worldName != null) world = Bukkit.getWorld(worldName);
            return world == null ? null : new Location(world, x, y, z, yaw, pitch);
        }
    }

    /** Old arena arrays are intentionally ignored while Gson reads a version-1 file. */
    private static final class SavedState {
        int version = FORMAT_VERSION;
        Point lobby;
        Point portal;
        double portalRadius = 2.5d;
    }

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private SavedState state;

    PvpLobbyStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        state = load(file);
    }

    synchronized Optional<Point> lobby() { return Optional.ofNullable(state.lobby); }
    synchronized Optional<Point> portal() { return Optional.ofNullable(state.portal); }

    synchronized double portalRadius() {
        return Math.max(1d, Math.min(12d, state.portalRadius));
    }

    synchronized boolean needsLobbyBuild() {
        return state.version < FORMAT_VERSION || state.lobby == null
                || !PvpLobbyBuilder.WORLD_NAME.equals(state.lobby.worldName());
    }

    synchronized void installGenerated(Point lobby) {
        state.lobby = lobby;
        // Stamp the format the lobby was just built to. Without this the file keeps the
        // older number it was read with, needsLobbyBuild stays true, and the island is
        // demolished and rebuilt on every single start. It went unnoticed through the
        // 1 to 2 bump only because version-1 files predated the field entirely and so
        // took the current default when Gson read them.
        state.version = FORMAT_VERSION;
        persist();
    }

    synchronized void setPortal(Point point, double radius) {
        state.portal = point;
        state.portalRadius = Math.max(1d, Math.min(12d, radius));
        persist();
    }

    synchronized void clearPortal() {
        state.portal = null;
        persist();
    }

    private SavedState load(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) == 0L) return new SavedState();
        try {
            SavedState loaded = gson.fromJson(Files.readString(path), SavedState.class);
            return loaded == null ? new SavedState() : loaded;
        } catch (RuntimeException malformed) {
            throw new IOException("PvP lobby configuration is unreadable", malformed);
        }
    }

    private void persist() {
        state.version = FORMAT_VERSION;
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, gson.toJson(state), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException fallback) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not save the PvP lobby", failure);
        }
    }
}
