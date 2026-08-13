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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player display preferences.
 *
 * <p>Every setting defaults to "on", so the store only records the toggles a player has
 * actually turned off. That keeps the file small and means a missing entry is the
 * normal case rather than something to repair.
 */
final class PlayerSettingsStore {
    /** A toggle a player can turn off. Names are persisted, so do not rename casually. */
    enum Setting {
        CLAN_TAGS("Clan tags", "Show other players' clan tags in chat and above their heads."),
        DISCORD_CHAT("Discord chat", "Show messages sent from Discord in Minecraft chat.");

        private final String label;
        private final String description;

        Setting(String label, String description) {
            this.label = label;
            this.description = description;
        }

        String label() {
            return label;
        }

        String description() {
            return description;
        }

        String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        static java.util.Optional<Setting> fromKey(String raw) {
            if (raw == null) {
                return java.util.Optional.empty();
            }
            for (Setting setting : values()) {
                if (setting.key().equalsIgnoreCase(raw.trim())) {
                    return java.util.Optional.of(setting);
                }
            }
            return java.util.Optional.empty();
        }
    }

    private final Path file;
    private final LinkedHashMap<UUID, EnumSet<Setting>> disabled = new LinkedHashMap<>();

    PlayerSettingsStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                EnumSet<Setting> off = EnumSet.noneOf(Setting.class);
                for (JsonElement value : entry.getValue().getAsJsonArray()) {
                    Setting.fromKey(value.getAsString()).ifPresent(off::add);
                }
                disabled.put(UUID.fromString(entry.getKey()), off);
            }
        } catch (RuntimeException exception) {
            throw new IOException("Player settings store is unreadable", exception);
        }
    }

    synchronized boolean isEnabled(UUID playerId, Setting setting) {
        return !disabled.getOrDefault(playerId, EnumSet.noneOf(Setting.class)).contains(setting);
    }

    synchronized Set<Setting> disabledFor(UUID playerId) {
        return EnumSet.copyOf(disabled.getOrDefault(playerId, EnumSet.noneOf(Setting.class)));
    }

    /** Flips one setting and returns its new state. */
    synchronized boolean toggle(UUID playerId, Setting setting) {
        EnumSet<Setting> off = disabled.computeIfAbsent(
                playerId, ignored -> EnumSet.noneOf(Setting.class)
        );
        boolean nowEnabled;
        if (off.contains(setting)) {
            off.remove(setting);
            nowEnabled = true;
        } else {
            off.add(setting);
            nowEnabled = false;
        }
        if (off.isEmpty()) {
            disabled.remove(playerId);
        }
        save();
        return nowEnabled;
    }

    private void save() {
        JsonObject root = new JsonObject();
        disabled.forEach((playerId, off) -> {
            com.google.gson.JsonArray array = new com.google.gson.JsonArray();
            off.forEach(setting -> array.add(setting.key()));
            root.add(playerId.toString(), array);
        });
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
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
