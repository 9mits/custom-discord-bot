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
import java.util.UUID;

/**
 * Per-player display preferences.
 *
 * <p>The file records only the settings a player has moved <em>away from their
 * default</em>, so a missing entry is the normal case rather than something to repair.
 * Which way that deviation points depends on the setting: for a display toggle that is
 * on for everyone it means "hidden", and for an opt-in behaviour it means "switched on".
 */
final class PlayerSettingsStore {
    /**
     * A toggle a player controls. Keys are persisted, so do not rename casually.
     *
     * <p>{@code enabledByDefault} is the part that has to be right. Auto sell empties
     * an inventory into the shop every couple of seconds; defaulting it to on — which
     * is what a single shared "everything starts enabled" rule did — meant every ore a
     * player mined was sold out from under them before they noticed. Its key is
     * deliberately not {@code auto_sell}: rows written under that name meant "turned
     * off", which is now the default, so being unable to read them is the migration.
     */
    enum Setting {
        CLAN_TAGS("clan_tags", "Clan tags",
                "Show other players' clan tags in chat and above their heads.", true, true),
        DISCORD_CHAT("discord_chat", "Discord chat",
                "Show messages sent from Discord in Minecraft chat.", true, true),
        // Lives on the sell screen, not in /settings: it is a shop behaviour, and the
        // panel is for what you can see.
        AUTO_SELL("auto_sell_on", "Auto sell",
                "Sell anything the shop buys as soon as it reaches your inventory.", false, false);

        private final String key;
        private final String label;
        private final String description;
        private final boolean enabledByDefault;
        private final boolean inSettingsPanel;

        Setting(
                String key,
                String label,
                String description,
                boolean enabledByDefault,
                boolean inSettingsPanel
        ) {
            this.key = key;
            this.label = label;
            this.description = description;
            this.enabledByDefault = enabledByDefault;
            this.inSettingsPanel = inSettingsPanel;
        }

        String label() {
            return label;
        }

        String description() {
            return description;
        }

        boolean enabledByDefault() {
            return enabledByDefault;
        }

        /** Whether {@code /settings} offers this one. */
        boolean inSettingsPanel() {
            return inSettingsPanel;
        }

        String key() {
            return key;
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
    /** Per player, the settings they have moved away from {@link Setting#enabledByDefault}. */
    private final LinkedHashMap<UUID, EnumSet<Setting>> overrides = new LinkedHashMap<>();

    PlayerSettingsStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                EnumSet<Setting> moved = EnumSet.noneOf(Setting.class);
                for (JsonElement value : entry.getValue().getAsJsonArray()) {
                    Setting.fromKey(value.getAsString()).ifPresent(moved::add);
                }
                if (!moved.isEmpty()) {
                    overrides.put(UUID.fromString(entry.getKey()), moved);
                }
            }
        } catch (RuntimeException exception) {
            throw new IOException("Player settings store is unreadable", exception);
        }
    }

    synchronized boolean isEnabled(UUID playerId, Setting setting) {
        boolean moved = overrides.getOrDefault(playerId, EnumSet.noneOf(Setting.class))
                .contains(setting);
        return moved != setting.enabledByDefault();
    }

    /** Flips one setting and returns its new state. */
    synchronized boolean toggle(UUID playerId, Setting setting) {
        EnumSet<Setting> moved = overrides.computeIfAbsent(
                playerId, ignored -> EnumSet.noneOf(Setting.class)
        );
        boolean nowEnabled;
        if (moved.contains(setting)) {
            moved.remove(setting);
            nowEnabled = setting.enabledByDefault();
        } else {
            moved.add(setting);
            nowEnabled = !setting.enabledByDefault();
        }
        if (moved.isEmpty()) {
            overrides.remove(playerId);
        }
        save();
        return nowEnabled;
    }

    /** Forgets every player's toggles, so everyone starts back on the defaults. */
    synchronized int clearAll() {
        int cleared = overrides.size();
        if (cleared == 0) {
            return 0;
        }
        overrides.clear();
        save();
        return cleared;
    }

    private void save() {
        JsonObject root = new JsonObject();
        overrides.forEach((playerId, moved) -> {
            com.google.gson.JsonArray array = new com.google.gson.JsonArray();
            moved.forEach(setting -> array.add(setting.key()));
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
