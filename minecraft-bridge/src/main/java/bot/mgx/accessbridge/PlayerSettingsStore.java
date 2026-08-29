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
        CHAT("Chat", "Choose which conversations and chat highlights reach you.",
                "item/writable_book"),
        NOTIFICATIONS("Notifications", "Choose which server notices appear in chat.",
                "item/bell"),
        VISUALS("Visuals", "World effects, night vision and player labels.",
                "item/ender_eye"),
        COSMETICS("Cosmetics", "Your auras, trails, kill effects and odds tag.",
                "item/nether_star"),
        AUDIO("Audio", "Server sounds and synced cosmetic music.", "block/note_block"),
        PRIVACY("Privacy", "Control which linked account details other players can see.",
                "item/iron_door"),
        HUD("HUD & Scoreboard", "Bars, overlays and sidebar sections.",
                "item/experience_bottle");

        private final String label;
        private final String description;
        private final String sprite;

        Category(String label, String description, String sprite) {
            this.label = label;
            this.description = description;
            this.sprite = sprite;
        }

        /** Texture path for the category's icon; see {@link MenuText#sprite(String)}. */
        String sprite() {
            return sprite;
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
                "Show other players' clan tags in chat and above their heads.", true,
                Category.VISUALS),
        DISCORD_CHAT("discord_chat", "Discord chat",
                "Show messages sent from Discord in Minecraft chat.", true, Category.CHAT),
        CHAT_MENTIONS("chat_mentions", "Chat mentions",
                "Highlight relayed Discord messages that mention your Minecraft name.", true, Category.CHAT),
        CHAT_NOTIFICATIONS("chat_notifications", "Crate key notices",
                "Tell you in chat when an hourly crate key lands.", true,
                Category.NOTIFICATIONS),
        CRATE_ANNOUNCEMENTS("crate_announcements", "Rare crate wins",
                "Announce it server-wide when somebody pulls a rare reward.", true,
                Category.NOTIFICATIONS),
        AIRDROP_ANNOUNCEMENTS("airdrop_announcements", "Airdrop alerts",
                "Announce an Amethyst Airdrop landing and who opened it.", true,
                Category.NOTIFICATIONS),
        CLAN_BATTLE_ANNOUNCEMENTS("clan_battle_announcements", "Clan Battle alerts",
                "Announce a Clan Battle starting, its countdown and its winners.", true,
                Category.NOTIFICATIONS),
        BOUNTY_MESSAGES("bounty_messages", "Bounty alerts",
                "Announce a bounty being placed and claimed.", true, Category.NOTIFICATIONS),
        UPDATE_NOTICES("update_notices", "Update notices",
                "Tell you when a server update has been published.", true,
                Category.NOTIFICATIONS),
        JOIN_LEAVE_MESSAGES("join_leave_messages", "Join and leave messages",
                "Show who is arriving and going.", true, Category.CHAT),
        DEATH_MESSAGES("death_messages", "Death messages",
                "Show other players' deaths in chat. Your own always shows.", true,
                Category.CHAT),
        CLAN_CHAT_VISIBLE("clan_chat_visible", "Clan chat",
                "Show messages sent in your clan's chat.", true, Category.CHAT),
        TROPHY_MESSAGES("trophy_messages", "Trophy messages",
                "Show messages when a player earns a trophy head.", true,
                Category.NOTIFICATIONS),
        COSMETICS_VISIBLE("cosmetics_visible", "Other players' cosmetics",
                "Render cosmetics equipped by other players.", true, Category.COSMETICS),
        OWN_AURA_VISIBLE("own_aura_visible", "Your aura",
                "Render your equipped aura for you.", true, Category.COSMETICS),
        OWN_TRAIL_VISIBLE("own_trail_visible", "Your trail",
                "Render your equipped trail for you.", true, Category.COSMETICS),
        OWN_KILL_EFFECTS_VISIBLE("own_kill_effects_visible", "Your kill effects",
                "Render your equipped kill effects for you.", true, Category.COSMETICS),
        RARITY_TAG_VISIBLE("rarity_tag_visible", "Your odds tag",
                "Show the odds behind your rarest equipped cosmetic under your name. "
                        + "Mythic and rarer only.",
                true, Category.COSMETICS),
        CRATE_SOUNDS("crate_sounds", "Crate sounds",
                "Play the reel, click and reveal sounds while opening a crate.", true,
                Category.AUDIO),
        AIRDROP_SOUNDS("airdrop_sounds", "Airdrop sounds",
                "Play the cue when an Amethyst Airdrop is falling.", true, Category.AUDIO),
        EVENT_SOUNDS("event_sounds", "Event sounds",
                "Play the chime for Clan Battles and other server events.", true,
                Category.AUDIO),
        AIRDROP_PARTICLES("airdrop_particles", "Airdrop beam",
                "Draw the beam and sparks marking a falling Airdrop.", true,
                Category.VISUALS),
        COSMETIC_SOUNDS("cosmetic_sounds", "Cosmetic sounds",
                "Play sounds from cosmetic reveals and kill effects.", true, Category.AUDIO),
        SCOREBOARD_ENABLED("scoreboard_enabled", "Scoreboard",
                "Show the Mysterious SMP X sidebar.", true, Category.HUD),
        SCOREBOARD_PROFILE("scoreboard_profile", "Profile section",
                "Show your rank, level, hearts, and clan on the scoreboard.", true, Category.HUD),
        SCOREBOARD_STATS("scoreboard_stats", "Stats section",
                "Show your kills and deaths on the scoreboard.", true, Category.HUD),
        SCOREBOARD_ECONOMY("scoreboard_economy", "Economy section",
                "Show your money on the scoreboard.", true, Category.HUD),
        KEY_TIMER_BAR("key_timer_bar", "Key timer bar",
                "Show a bar counting down to your next crate key.", true, Category.HUD),
        BROADCAST_BAR("broadcast_bar", "Announcement bar",
                "Show the timed bar used for server announcements.", true, Category.HUD),
        AIRDROP_BAR("airdrop_bar", "Airdrop bar",
                "Show the bar tracking a falling Amethyst Airdrop.", true, Category.HUD),
        ACTION_BAR_TIPS("action_bar_tips", "Action bar tips",
                "Show teleport warmups and short notices above your hotbar.", true, Category.HUD),
        NIGHT_VISION("night_vision", "Night vision",
                "See in the dark without a torch or a potion.", false, Category.VISUALS),
        CRATE_REVEAL_EFFECTS("crate_reveal_effects", "Rare crate effects",
                "Show the fireworks and visual reveal for a rare crate reward.", true,
                Category.VISUALS),
        OTHER_TRAILS_VISIBLE("other_trails_visible", "Other players' trails",
                "Render trails left by other players.", true, Category.COSMETICS),
        TELEPORT_REQUESTS("teleport_requests", "Teleport requests",
                "Let other players send you a teleport request.", true, Category.PRIVACY),
        ALLOW_PAYMENTS("allow_payments", "Allow payments",
                "Let other players send you money with /pay.", true, Category.PRIVACY),
        PRIVATE_TRANSACTIONS("private_transactions", "Private transactions",
                "Hide the amount when someone pays you, so onlookers cannot read it.",
                false, Category.PRIVACY),
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

    private java.util.function.Consumer<UUID> changeObserver = ignored -> { };

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

    /**
     * Told after any change, so a setting that alters the world rather than the screen
     * can take effect on the click instead of at the next sweep.
     */
    synchronized void onChange(java.util.function.Consumer<UUID> observer) {
        this.changeObserver = observer == null ? ignored -> { } : observer;
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
        changeObserver.accept(playerId);
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
