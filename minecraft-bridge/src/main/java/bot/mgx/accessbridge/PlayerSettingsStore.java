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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player experience preferences.
 *
 * <p>The file records only the settings a player has moved <em>away from their
 * default</em>, so a missing entry is the normal case rather than something to repair.
 * Which way that deviation points depends on the setting: for a display toggle that is
 * on for everyone it means "hidden", and for an opt-in behaviour it means "switched on".
 */
final class PlayerSettingsStore {
    static final String MUSIC_VOLUME_KEY = "music_volume";
    static final int DEFAULT_MUSIC_VOLUME = 100;
    private static final String MUSIC_VOLUMES_JSON_KEY = "_music_volumes";
    enum Category {
        CHAT("Chat", "Choose which conversations and chat highlights reach you."),
        NOTIFICATIONS("Notifications", "Choose which server notices appear in chat."),
        PVP("PvP", "Control combat-related messages and effects."),
        VISUALS("Visuals / Cosmetics", "Choose which equipped cosmetics are rendered."),
        PRIVACY("Privacy", "Control which linked account details other players can see."),
        SCOREBOARD("Scoreboard", "Choose whether the sidebar appears and which sections it shows."),
        GENERAL("General", "Other presentation settings that apply across the server.");

        private final String label;
        private final String description;

        Category(String label, String description) {
            this.label = label;
            this.description = description;
        }

        String label() {
            return label;
        }

        String description() {
            return description;
        }

        List<Setting> settings() {
            List<Setting> found = new ArrayList<>();
            for (Setting setting : Setting.values()) {
                if (setting.category() == this) {
                    found.add(setting);
                }
            }
            return List.copyOf(found);
        }
    }

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
                "Show other players' clan tags in chat and above their heads.", true, Category.GENERAL),
        DISCORD_CHAT("discord_chat", "Discord chat",
                "Show messages sent from Discord in Minecraft chat.", true, Category.CHAT),
        CHAT_MENTIONS("chat_mentions", "Chat mentions",
                "Highlight relayed Discord messages that mention your Minecraft name.", true, Category.CHAT),
        CHAT_NOTIFICATIONS("chat_notifications", "Chat notifications",
                "Show hourly crate-key notices. Rare wins always announce.", true,
                Category.NOTIFICATIONS),
        TROPHY_MESSAGES("trophy_messages", "Trophy messages",
                "Show messages when a player earns a trophy head.", true, Category.PVP),
        COSMETICS_VISIBLE("cosmetics_visible", "Other players' cosmetics",
                "Render cosmetics equipped by other players.", true, Category.VISUALS),
        OWN_AURA_VISIBLE("own_aura_visible", "Your aura",
                "Render your equipped aura for you.", true, Category.VISUALS),
        OWN_TRAIL_VISIBLE("own_trail_visible", "Your trail",
                "Render your equipped trail for you.", true, Category.VISUALS),
        OWN_KILL_EFFECTS_VISIBLE("own_kill_effects_visible", "Your kill effects",
                "Render your equipped kill effects for you.", true, Category.VISUALS),
        RARITY_TAG_VISIBLE("rarity_tag_visible", "Your odds tag",
                "Show the odds behind your rarest equipped cosmetic under your name. "
                        + "Mythic and rarer only.",
                true, Category.VISUALS),
        COSMETIC_SOUNDS("cosmetic_sounds", "Cosmetic sounds",
                "Play sounds from cosmetic reveals and kill effects.", true, Category.VISUALS),
        SCOREBOARD_ENABLED("scoreboard_enabled", "Scoreboard",
                "Show the Mysterious SMP X sidebar.", true, Category.SCOREBOARD),
        SCOREBOARD_PROFILE("scoreboard_profile", "Profile section",
                "Show your rank, level, hearts, and clan on the scoreboard.", true, Category.SCOREBOARD),
        SCOREBOARD_STATS("scoreboard_stats", "Stats section",
                "Show your kills and deaths on the scoreboard.", true, Category.SCOREBOARD),
        SCOREBOARD_ECONOMY("scoreboard_economy", "Economy section",
                "Show your money on the scoreboard.", true, Category.SCOREBOARD),
        KEY_TIMER_BAR("key_timer_bar", "Key timer",
                "Show a bar counting down to your next crate key.", true, Category.GENERAL),
        // Lives on the sell screen, not in /settings: it is a shop behaviour, and the
        // panel is for what you can see.
        AUTO_SELL("auto_sell_on", "Auto sell",
                "Sell anything the shop buys as soon as it reaches your inventory.", false, null),
        // Same reasoning: it changes what a crate costs to open, so it belongs on the
        // crate screen where the keys are counted, not in the display panel.
        CRATE_TRIPLE("crate_triple_on", "Open three at a time",
                "Spin three reels per opening instead of one.", false, null);

        private final String key;
        private final String label;
        private final String description;
        private final boolean enabledByDefault;
        private final Category category;

        Setting(
                String key,
                String label,
                String description,
                boolean enabledByDefault,
                Category category
        ) {
            this.key = key;
            this.label = label;
            this.description = description;
            this.enabledByDefault = enabledByDefault;
            this.category = category;
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
            return category != null;
        }

        Category category() {
            return category;
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
    /** Percent volume for the music-synced aura; absent is the 100% default. */
    private final LinkedHashMap<UUID, Integer> musicVolumes = new LinkedHashMap<>();

    PlayerSettingsStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (entry.getKey().equals(MUSIC_VOLUMES_JSON_KEY)) {
                    for (Map.Entry<String, JsonElement> volume
                            : entry.getValue().getAsJsonObject().entrySet()) {
                        int percent = normalizeMusicVolume(volume.getValue().getAsInt());
                        if (percent != DEFAULT_MUSIC_VOLUME) {
                            musicVolumes.put(UUID.fromString(volume.getKey()), percent);
                        }
                    }
                    continue;
                }
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

    synchronized int musicVolume(UUID playerId) {
        return musicVolumes.getOrDefault(playerId, DEFAULT_MUSIC_VOLUME);
    }

    /** Cycles downward for quick menu clicks: 100, 75, 50, 25, 0, then 100. */
    synchronized int cycleMusicVolume(UUID playerId) {
        int current = musicVolume(playerId);
        int next = current >= 100 ? 75
                : current >= 75 ? 50
                : current >= 50 ? 25
                : current >= 25 ? 0
                : 100;
        setMusicVolume(playerId, next);
        return next;
    }

    synchronized int setMusicVolume(UUID playerId, int percent) {
        int normalized = normalizeMusicVolume(percent);
        Integer before = musicVolumes.get(playerId);
        if (normalized == DEFAULT_MUSIC_VOLUME) {
            musicVolumes.remove(playerId);
        } else {
            musicVolumes.put(playerId, normalized);
        }
        try {
            save();
        } catch (RuntimeException exception) {
            if (before == null) {
                musicVolumes.remove(playerId);
            } else {
                musicVolumes.put(playerId, before);
            }
            throw exception;
        }
        return normalized;
    }

    private static int normalizeMusicVolume(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        return Math.round(clamped / 25.0f) * 25;
    }

    /** Flips one setting and returns its new state. */
    synchronized boolean toggle(UUID playerId, Setting setting) {
        boolean nowEnabled = !isEnabled(playerId, setting);
        setEnabled(playerId, setting, nowEnabled);
        return nowEnabled;
    }

    /** Stores an explicit form value and returns the resulting state. */
    synchronized boolean setEnabled(UUID playerId, Setting setting, boolean enabled) {
        setEnabled(playerId, Map.of(setting, enabled));
        return enabled;
    }

    /** Applies one dialog submission in memory and on disk as a single update. */
    synchronized void setEnabled(UUID playerId, Map<Setting, Boolean> requested) {
        EnumSet<Setting> before = overrides.containsKey(playerId)
                ? EnumSet.copyOf(overrides.get(playerId))
                : EnumSet.noneOf(Setting.class);
        EnumSet<Setting> after = EnumSet.copyOf(before);
        requested.forEach((setting, enabled) -> {
            if (enabled == setting.enabledByDefault()) {
                after.remove(setting);
            } else {
                after.add(setting);
            }
        });
        if (after.equals(before)) {
            return;
        }
        if (after.isEmpty()) {
            overrides.remove(playerId);
        } else {
            overrides.put(playerId, after);
        }
        try {
            save();
        } catch (RuntimeException exception) {
            if (before.isEmpty()) {
                overrides.remove(playerId);
            } else {
                overrides.put(playerId, before);
            }
            throw exception;
        }
    }

    /** Forgets every player's toggles, so everyone starts back on the defaults. */
    synchronized int clearAll() {
        int cleared = overrides.size() + musicVolumes.size();
        if (cleared == 0) {
            return 0;
        }
        LinkedHashMap<UUID, EnumSet<Setting>> before = new LinkedHashMap<>(overrides);
        LinkedHashMap<UUID, Integer> volumesBefore = new LinkedHashMap<>(musicVolumes);
        overrides.clear();
        musicVolumes.clear();
        try {
            save();
        } catch (RuntimeException exception) {
            overrides.putAll(before);
            musicVolumes.putAll(volumesBefore);
            throw exception;
        }
        return cleared;
    }

    private void save() {
        JsonObject root = new JsonObject();
        overrides.forEach((playerId, moved) -> {
            com.google.gson.JsonArray array = new com.google.gson.JsonArray();
            moved.forEach(setting -> array.add(setting.key()));
            root.add(playerId.toString(), array);
        });
        if (!musicVolumes.isEmpty()) {
            JsonObject volumes = new JsonObject();
            musicVolumes.forEach((playerId, percent) -> volumes.addProperty(
                    playerId.toString(), percent
            ));
            root.add(MUSIC_VOLUMES_JSON_KEY, volumes);
        }
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
