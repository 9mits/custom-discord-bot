package bot.mgx.accessbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Sentinel's memory across restarts, in {@code sentinel.json}. */
final class SentinelStore {
    static final class Incident {
        String id;
        String rule;
        String severity;
        String player;
        String playerName;
        String title;
        List<String> evidence = new ArrayList<>();
        long at;
        double risk;
    }

    private static final class Data {
        Map<String, Map<String, Long>> lastKnown = new LinkedHashMap<>();
        Map<String, double[]> risk = new LinkedHashMap<>();
        List<Incident> incidents = new ArrayList<>();
    }

    private static final int INCIDENT_LIMIT = 500;

    private final Path file;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private Data data = new Data();

    SentinelStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (Files.isRegularFile(file) && Files.size(file) > 0) {
            try {
                Data loaded = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
                if (loaded != null) data = loaded;
            } catch (RuntimeException exception) {
                throw new IOException("Sentinel store is unreadable", exception);
            }
        }
        if (data.lastKnown == null) data.lastKnown = new LinkedHashMap<>();
        if (data.risk == null) data.risk = new LinkedHashMap<>();
        if (data.incidents == null) data.incidents = new ArrayList<>();
    }

    synchronized Map<UUID, EnumMap<SentinelEngine.Kind, Long>> lastKnown() {
        Map<UUID, EnumMap<SentinelEngine.Kind, Long>> out = new LinkedHashMap<>();
        data.lastKnown.forEach((id, holdings) -> {
            EnumMap<SentinelEngine.Kind, Long> parsed = new EnumMap<>(SentinelEngine.Kind.class);
            holdings.forEach((kind, amount) -> {
                try {
                    parsed.put(SentinelEngine.Kind.valueOf(kind), amount);
                } catch (IllegalArgumentException ignored) {
                    // a retired kind
                }
            });
            try {
                out.put(UUID.fromString(id), parsed);
            } catch (IllegalArgumentException ignored) {
                // a malformed id
            }
        });
        return out;
    }

    synchronized void setLastKnown(UUID player, EnumMap<SentinelEngine.Kind, Long> holdings) {
        Map<String, Long> plain = new LinkedHashMap<>();
        holdings.forEach((kind, amount) -> plain.put(kind.name(), amount));
        data.lastKnown.put(player.toString(), plain);
    }

    synchronized Map<String, double[]> risk() {
        return new LinkedHashMap<>(data.risk);
    }

    synchronized void setRisk(Map<UUID, double[]> risk) {
        data.risk.clear();
        risk.forEach((id, value) -> data.risk.put(id.toString(), value));
    }

    synchronized void add(Incident incident) {
        data.incidents.add(incident);
        while (data.incidents.size() > INCIDENT_LIMIT) data.incidents.remove(0);
    }

    synchronized List<Incident> recent(int limit) {
        int from = Math.max(0, data.incidents.size() - limit);
        List<Incident> recent = new ArrayList<>(data.incidents.subList(from, data.incidents.size()));
        java.util.Collections.reverse(recent);
        return recent;
    }

    synchronized void persist() {
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, gson.toJson(data), StandardCharsets.UTF_8);
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
