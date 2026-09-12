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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Persistent lobby, entrance, and reusable competitive-arena definitions. */
final class PvpArenaStore {
    private static final int FORMAT_VERSION = 1;
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9_-]{2,24}");

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
            return new Point(
                    location.getWorld().getUID().toString(), location.getWorld().getName(),
                    location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch()
            );
        }

        Location resolve() {
            World world = null;
            try {
                if (worldId != null && !worldId.isBlank()) {
                    world = Bukkit.getWorld(UUID.fromString(worldId));
                }
            } catch (IllegalArgumentException ignored) {
                // A renamed/corrupt UUID may still have a usable saved world name.
            }
            if (world == null && worldName != null) world = Bukkit.getWorld(worldName);
            return world == null ? null : new Location(world, x, y, z, yaw, pitch);
        }

        boolean sameWorld(Point other) {
            if (other == null) return false;
            if (worldId != null && !worldId.isBlank() && other.worldId != null) {
                return worldId.equals(other.worldId);
            }
            return worldName != null && worldName.equals(other.worldName);
        }
    }

    record Arena(
            String id,
            Set<String> groups,
            boolean enabled,
            Point firstCorner,
            Point secondCorner,
            Point spectator,
            List<Point> spawns
    ) {
        Arena {
            groups = groups == null ? Set.of() : Set.copyOf(groups);
            spawns = spawns == null ? List.of() : List.copyOf(spawns);
        }

        boolean supports(PvpMode mode) {
            return mode != null && groups.contains(mode.arenaGroup());
        }

        boolean readyFor(PvpMode mode) {
            if (!enabled || !supports(mode) || firstCorner == null || secondCorner == null
                    || spectator == null || spawns.size() < mode.maximumPlayers()) {
                return false;
            }
            if (!firstCorner.sameWorld(secondCorner) || !firstCorner.sameWorld(spectator)) {
                return false;
            }
            return spawns.stream().limit(mode.maximumPlayers()).allMatch(firstCorner::sameWorld);
        }

        Point center() {
            if (firstCorner == null || secondCorner == null || !firstCorner.sameWorld(secondCorner)) {
                return null;
            }
            return new Point(firstCorner.worldId(), firstCorner.worldName(),
                    (firstCorner.x() + secondCorner.x()) / 2d,
                    (firstCorner.y() + secondCorner.y()) / 2d,
                    (firstCorner.z() + secondCorner.z()) / 2d,
                    0f, 0f);
        }

        double diameter() {
            if (firstCorner == null || secondCorner == null) return 1d;
            return Math.max(1d, Math.max(
                    Math.abs(firstCorner.x() - secondCorner.x()),
                    Math.abs(firstCorner.z() - secondCorner.z())) + 1d);
        }

        boolean contains(Location location) {
            if (location == null || location.getWorld() == null || firstCorner == null
                    || secondCorner == null) return false;
            Location resolved = firstCorner.resolve();
            if (resolved == null || !resolved.getWorld().equals(location.getWorld())) return false;
            double padding = 0.75d;
            return location.getX() >= Math.min(firstCorner.x(), secondCorner.x()) - padding
                    && location.getX() <= Math.max(firstCorner.x(), secondCorner.x()) + padding
                    && location.getY() >= Math.min(firstCorner.y(), secondCorner.y()) - 8d
                    && location.getY() <= Math.max(firstCorner.y(), secondCorner.y()) + 16d
                    && location.getZ() >= Math.min(firstCorner.z(), secondCorner.z()) - padding
                    && location.getZ() <= Math.max(firstCorner.z(), secondCorner.z()) + padding;
        }
    }

    private static final class SavedState {
        int version = FORMAT_VERSION;
        Point lobby;
        Point portal;
        double portalRadius = 2.5d;
        List<Arena> arenas = new ArrayList<>();
    }

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private SavedState state;

    PvpArenaStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        state = load(file);
        validate(state);
    }

    synchronized Optional<Point> lobby() {
        return Optional.ofNullable(state.lobby);
    }

    synchronized Optional<Point> portal() {
        return Optional.ofNullable(state.portal);
    }

    synchronized double portalRadius() {
        return Math.max(1d, Math.min(12d, state.portalRadius));
    }

    synchronized void setLobby(Point point) {
        state.lobby = point;
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

    synchronized List<Arena> all() {
        return state.arenas.stream()
                .sorted(Comparator.comparing(Arena::id))
                .toList();
    }

    synchronized Optional<Arena> find(String id) {
        String wanted = cleanId(id);
        return state.arenas.stream().filter(arena -> arena.id().equals(wanted)).findFirst();
    }

    synchronized List<Arena> ready(PvpMode mode) {
        return state.arenas.stream().filter(arena -> arena.readyFor(mode))
                .sorted(Comparator.comparing(Arena::id)).toList();
    }

    synchronized Arena create(String id, PvpMode mode) {
        String clean = cleanId(id);
        if (!VALID_ID.matcher(clean).matches()) {
            throw new IllegalArgumentException("Arena ids use 2-24 lowercase letters, numbers, _ or -.");
        }
        if (find(clean).isPresent()) throw new IllegalArgumentException("That arena already exists.");
        Arena arena = new Arena(clean, Set.of(mode.arenaGroup()), false,
                null, null, null, List.of());
        state.arenas.add(arena);
        persist();
        return arena;
    }

    synchronized Arena setCorner(String id, int corner, Point point) {
        Arena arena = require(id);
        Arena updated = new Arena(arena.id(), arena.groups(), arena.enabled(),
                corner == 1 ? point : arena.firstCorner(),
                corner == 2 ? point : arena.secondCorner(),
                arena.spectator(), arena.spawns());
        return replace(updated);
    }

    synchronized Arena setSpectator(String id, Point point) {
        Arena arena = require(id);
        return replace(new Arena(arena.id(), arena.groups(), arena.enabled(),
                arena.firstCorner(), arena.secondCorner(), point, arena.spawns()));
    }

    synchronized Arena setSpawn(String id, int slot, Point point) {
        if (slot < 1 || slot > 16) throw new IllegalArgumentException("Spawn slot must be 1-16.");
        Arena arena = require(id);
        List<Point> spawns = new ArrayList<>(arena.spawns());
        if (slot > spawns.size() + 1) {
            throw new IllegalArgumentException("Set spawn " + (spawns.size() + 1) + " first.");
        }
        if (slot == spawns.size() + 1) spawns.add(point);
        else spawns.set(slot - 1, point);
        return replace(new Arena(arena.id(), arena.groups(), arena.enabled(),
                arena.firstCorner(), arena.secondCorner(), arena.spectator(), spawns));
    }

    synchronized Arena setEnabled(String id, boolean enabled) {
        Arena arena = require(id);
        return replace(new Arena(arena.id(), arena.groups(), enabled,
                arena.firstCorner(), arena.secondCorner(), arena.spectator(), arena.spawns()));
    }

    synchronized Arena addMode(String id, PvpMode mode) {
        Arena arena = require(id);
        Set<String> groups = new LinkedHashSet<>(arena.groups());
        groups.add(mode.arenaGroup());
        return replace(new Arena(arena.id(), groups, arena.enabled(), arena.firstCorner(),
                arena.secondCorner(), arena.spectator(), arena.spawns()));
    }

    synchronized void delete(String id) {
        Arena arena = require(id);
        state.arenas.removeIf(row -> row.id().equals(arena.id()));
        persist();
    }

    synchronized void installGenerated(Point lobby, List<Arena> arenas) {
        state.lobby = lobby;
        state.arenas = new ArrayList<>(arenas);
        try {
            validate(state);
        } catch (IOException impossible) {
            throw new IllegalArgumentException(impossible.getMessage(), impossible);
        }
        persist();
    }

    private Arena require(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("No arena is called " + id + "."));
    }

    private Arena replace(Arena updated) {
        for (int index = 0; index < state.arenas.size(); index++) {
            if (state.arenas.get(index).id().equals(updated.id())) {
                state.arenas.set(index, updated);
                persist();
                return updated;
            }
        }
        throw new IllegalArgumentException("No arena is called " + updated.id() + ".");
    }

    private static String cleanId(String id) {
        return id == null ? "" : id.strip().toLowerCase(Locale.ROOT);
    }

    private SavedState load(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) == 0L) return new SavedState();
        try {
            SavedState loaded = gson.fromJson(Files.readString(path), SavedState.class);
            return loaded == null ? new SavedState() : loaded;
        } catch (RuntimeException malformed) {
            throw new IOException("PvP arena configuration is unreadable", malformed);
        }
    }

    private static void validate(SavedState state) throws IOException {
        if (state.arenas == null) state.arenas = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Arena arena : state.arenas) {
            if (arena == null || !VALID_ID.matcher(cleanId(arena.id())).matches()) {
                throw new IOException("PvP arena id is invalid");
            }
            if (!ids.add(arena.id())) throw new IOException("Duplicate PvP arena " + arena.id());
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
            throw new UncheckedIOException("Could not save PvP arenas", failure);
        }
    }
}
