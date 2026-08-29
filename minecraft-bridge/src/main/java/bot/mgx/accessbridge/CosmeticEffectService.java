package bot.mgx.accessbridge;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.WeatherType;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntConsumer;

/** One bounded task for auras/trails plus the PvP kill-effect hook. */
final class CosmeticEffectService implements Listener {
    /** Ten animation frames a second, with fewer deliberate particles per frame. */
    private static final long PERIOD_TICKS = 2L;
    /**
     * The odds tag follows on every tick while everything else runs on two.
     *
     * <p>It is a separate entity teleported after the player, so the client interpolates
     * its motion: at ten updates a second it visibly trailed anyone sprinting. Riding
     * the player instead would remove the lag outright, but a passenger renders at
     * vanilla's mount point above the head and takes the player's own nameplate with
     * it, so the tag follows at twenty updates a second instead. Teleporting one marker
     * stand per tagged player costs nothing next to the particle work.
     */
    private static final long NAMEPLATE_PERIOD_TICKS = 1L;
    /**
     * How far one main-pass frame moves the odds tag's gradient.
     *
     * <p>The tag is retitled on the main pass, every two ticks, so these are per tenth
     * of a second. Slow on purpose: the point is that it drifts, not that it flickers.
     */
    private static final double SCROLL_SPEED = 0.06d;
    /** Palette entries between one character and the next along the line. */
    private static final double SCROLL_SPREAD = 0.34d;
    private static final double PULSE_SPEED = 0.045d;
    /** Frames the shimmer highlight spends crossing one character. */
    private static final double SHIMMER_FRAMES = 3d;
    /** Characters' worth of stillness between one sweep and the next. */
    private static final double SHIMMER_REST = 5d;
    /**
     * Characters either side of the head that the highlight reaches.
     *
     * <p>Deliberately not the palette size. Tying the width of the glint to how many
     * colours a family happens to have gave the four-colour families a highlight
     * three characters wide, which is a bright spot rather than a sweep.
     */
    private static final double SHIMMER_WIDTH = 5d;
    /** How far the resting colour drifts per frame, under the highlight. */
    private static final double SHIMMER_DRIFT = 0.02d;
    static final double VIEW_DISTANCE_SQUARED = 48d * 48d;
    /**
     * How close you have to be to hear a cosmetic.
     *
     * <p>An aura is visible at 48 blocks, which is right for something you look at
     * and wrong for something you listen to. At that range one player's soundtrack
     * covers most of a base whether or not anyone wanted it, and it says exactly
     * where its owner is standing long before they are in sight. Sixteen blocks is
     * close enough to be part of meeting somebody and short enough not to carry.
     */
    static final double HEARING_DISTANCE_SQUARED = 16d * 16d;
    /**
     * Frames between one ambient note and the next.
     *
     * <p>The main pass runs every two ticks, so this is a little over three seconds.
     * Long enough that it reads as an atmosphere around a player rather than a loop
     * playing at them, which is the difference between rare and irritating.
     */
    private static final long AURA_SOUND_FRAMES = 32L;
    /** Quiet on purpose; the listener's own cosmetic volume scales it further. */
    private static final float AURA_SOUND_VOLUME = 0.45f;
    private static final int TRAIL_HISTORY_SIZE = 14;
    private static final double TRAIL_RESET_DISTANCE_SQUARED = 12d * 12d;
    private static final String MUSIC_AURA_ID = CosmeticCatalog.HIDDEN_AMETHYST_COSMETIC_ID;
    private static final String MUSIC_AURA_SOUND = "mgx:iridescent_imperium";
    private static final String RARITY_NAMEPLATE_TAG = "mgx_cosmetic_rarity_nameplate";
    private static final TextColor SECRET_REVEAL_COLOUR = TextColor.color(0xC77DFF);
    /** Client-side sky the reveals borrow. Dusk for an Exotic, dead midnight for a Secret. */
    private static final long EXOTIC_REVEAL_TIME = 15_500L;
    private static final long SECRET_REVEAL_TIME = 18_000L;
    private static final long REVEAL_FRAME_TICKS = 2L;
    private static final int MYTHIC_REVEAL_FRAMES = 36;
    private static final int SECRET_REVEAL_FRAMES = 70;
    private static final int GENUINE_REVEAL_FRAMES = 250;

    /** Every ten seconds, because a stand nobody owns is a bug rather than the norm. */
    private static final long NAMEPLATE_SWEEP_FRAMES = 100L;
    private final MGXAccessBridge plugin;
    private final CosmeticStore store;
    private final CosmeticItems items;
    private final WardrobeService wardrobe;
    private final PlayerSettingsStore settings;
    private final LeaderboardService leaderboard;
    private final Map<UUID, Location> previousLocations = new HashMap<>();
    private final Map<UUID, Deque<Location>> trailHistories = new HashMap<>();
    private final Set<String> failedSelectionClears = new HashSet<>();
    private final Map<UUID, MusicAuraState> musicAuraStates = new HashMap<>();
    private final Map<UUID, ArmorStand> rarityNameplates = new HashMap<>();
    private final Map<UUID, AtmosphereState> revealAtmospheres = new HashMap<>();
    private final Map<UUID, FloatingPlayerState> floatingPlayers = new HashMap<>();
    private final Set<BossBar> activeRevealBars = new HashSet<>();
    private BukkitTask task;
    private BukkitTask nameplateTask;
    private long frame;

    private static final class MusicAuraState {
        private final long startedAtMillis;
        private final Set<UUID> listeners = new HashSet<>();
        /** Includes muted nearby viewers so raising 0% can restart the synced loop. */
        private final Map<UUID, Integer> observedVolumes = new HashMap<>();
        private long loop = -1L;

        MusicAuraState(long startedAtMillis) {
            this.startedAtMillis = startedAtMillis;
        }
    }

    private record FloatingPlayerState(
            Location returnLocation,
            boolean gravity,
            boolean invulnerable,
            boolean collidable,
            Pose pose,
            boolean fixedPose
    ) {
    }

    CosmeticEffectService(
            MGXAccessBridge plugin,
            CosmeticStore store,
            CosmeticItems items,
            WardrobeService wardrobe,
            PlayerSettingsStore settings,
            LeaderboardService leaderboard
    ) {
        this.plugin = plugin;
        this.store = store;
        this.items = items;
        this.wardrobe = wardrobe;
        this.settings = settings;
        this.leaderboard = leaderboard;
    }

    void start() {
        if (task == null) {
            plugin.getServer().getWorlds().forEach(world -> world.getEntities().stream()
                    .filter(entity -> entity.getScoreboardTags().contains(RARITY_NAMEPLATE_TAG))
                    .forEach(Entity::remove));
            task = plugin.getServer().getScheduler().runTaskTimer(
                    plugin, this::tick, PERIOD_TICKS, PERIOD_TICKS
            );
            nameplateTask = plugin.getServer().getScheduler().runTaskTimer(
                    plugin, this::followWithNameplates,
                    NAMEPLATE_PERIOD_TICKS, NAMEPLATE_PERIOD_TICKS
            );
        }
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (nameplateTask != null) {
            nameplateTask.cancel();
            nameplateTask = null;
        }
        previousLocations.clear();
        trailHistories.clear();
        failedSelectionClears.clear();
        for (UUID ownerId : List.copyOf(musicAuraStates.keySet())) {
            stopMusicAura(ownerId);
        }
        rarityNameplates.values().forEach(ArmorStand::remove);
        rarityNameplates.clear();
        for (UUID playerId : List.copyOf(floatingPlayers.keySet())) {
            restoreFloatingPlayer(playerId);
        }
        for (UUID playerId : List.copyOf(revealAtmospheres.keySet())) {
            Player holder = plugin.getServer().getPlayer(playerId);
            if (holder == null) {
                revealAtmospheres.remove(playerId);
            } else {
                endRevealAtmosphere(holder);
            }
        }
        for (BossBar bar : activeRevealBars) {
            for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                viewer.hideBossBar(bar);
            }
        }
        activeRevealBars.clear();
    }

    private void tick() {
        frame++;
        previousLocations.keySet().removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
        trailHistories.keySet().removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
        rarityNameplates.entrySet().removeIf(entry -> {
            if (plugin.getServer().getPlayer(entry.getKey()) != null && entry.getValue().isValid()) {
                return false;
            }
            entry.getValue().remove();
            return true;
        });
        for (UUID ownerId : List.copyOf(musicAuraStates.keySet())) {
            if (plugin.getServer().getPlayer(ownerId) == null) {
                stopMusicAura(ownerId);
            }
        }
        if (frame % NAMEPLATE_SWEEP_FRAMES == 0L) {
            sweepOrphanedNameplates();
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (VerificationLobbyService.isLobbyWorld(player.getWorld())) {
                previousLocations.remove(player.getUniqueId());
                trailHistories.remove(player.getUniqueId());
                stopMusicAura(player.getUniqueId());
                removeRarityNameplate(player.getUniqueId());
                continue;
            }
            Location now = player.getLocation();
            Location previous = previousLocations.put(player.getUniqueId(), now.clone());
            boolean movedInWorld = previous != null && previous.getWorld() == now.getWorld();
            double movementSquared = movedInWorld ? previous.distanceSquared(now) : 0d;
            boolean moving = movedInWorld && movementSquared > 0.0025d;
            long auraFrame = frame + CosmeticAnimation.playerOffset(player.getUniqueId(), 3);
            Optional<CosmeticCatalog.Definition> aura = active(
                    player, CosmeticCatalog.Category.AURA
            );
            Optional<CosmeticCatalog.Definition> trailDefinition = active(
                    player, CosmeticCatalog.Category.TRAIL
            );
            syncRarityNameplate(player, aura);
            if (aura.map(CosmeticCatalog.Definition::id).filter(MUSIC_AURA_ID::equals).isPresent()) {
                syncMusicAura(player);
            } else {
                stopMusicAura(player.getUniqueId());
            }
            aura.ifPresent(definition -> playAuraAmbience(player, definition));
            // The music aura used to render every tick while every other aura thinned
            // out. At sprint speed that is one formation per block travelled, so the
            // ring smeared into a trail instead of orbiting the player.
            if (CosmeticAnimation.renderAuraFrame(moving, auraFrame)) {
                aura.ifPresent(definition -> drawAura(player, definition, moving));
            }
            Deque<Location> history = trailHistories.computeIfAbsent(
                    player.getUniqueId(), ignored -> new ArrayDeque<>()
            );
            if (previous == null
                    || previous.getWorld() != now.getWorld()
                    || previous.distanceSquared(now) > TRAIL_RESET_DISTANCE_SQUARED) {
                history.clear();
            }
            history.addFirst(now.clone());
            while (history.size() > TRAIL_HISTORY_SIZE) {
                history.removeLast();
            }
            if (moving) {
                List<Location> trailPoints = List.copyOf(history);
                trailDefinition.ifPresent(
                        definition -> drawTrail(player, definition, trailPoints)
                );
            }
        }
    }

    /**
     * The floating odds line, driven by the equipped aura alone.
     *
     * <p>It used to take the rarest of the aura, trail and kill effect, so a player who
     * had unequipped their aura still wore a tag for a trail that only appears when they
     * move and a kill effect nobody had seen. The aura is the effect actually standing
     * next to the line, and it is the only thing the line now reports.
     */
    private void syncRarityNameplate(Player player, Optional<CosmeticCatalog.Definition> aura) {
        if (!settings.isEnabled(
                player.getUniqueId(), PlayerSettingsStore.Setting.RARITY_TAG_VISIBLE
        )) {
            removeRarityNameplate(player.getUniqueId());
            return;
        }
        CosmeticCatalog.Definition rarest = aura
                .filter(CosmeticCatalog.Definition::nameplateWorthy)
                .orElse(null);
        if (rarest == null) {
            removeRarityNameplate(player.getUniqueId());
            return;
        }
        ArmorStand plate = rarityNameplates.get(player.getUniqueId());
        if (plate == null || !plate.isValid() || plate.getWorld() != player.getWorld()) {
            if (plate != null) {
                plate.remove();
            }
            plate = player.getWorld().spawn(
                    player.getLocation().add(0d, -0.35d, 0d), ArmorStand.class,
                    entity -> {
                        entity.setVisible(false);
                        entity.setMarker(true);
                        entity.setGravity(false);
                        entity.setInvulnerable(true);
                        entity.setSilent(true);
                        entity.setCollidable(false);
                        entity.setPersistent(false);
                        entity.setCustomNameVisible(true);
                        entity.addScoreboardTag(RARITY_NAMEPLATE_TAG);
                        entity.addScoreboardTag(
                                RARITY_NAMEPLATE_TAG + ":" + player.getUniqueId()
                        );
                    }
            );
            rarityNameplates.put(player.getUniqueId(), plate);
        }
        plate.customName(rarityNameplate(rarest, frame));
        plate.teleport(nameplateLocation(player));
    }

    /**
     * Keeps every live odds tag on its owner.
     *
     * <p>Deliberately does nothing else: whether a tag should exist at all, and what it
     * says, is decided by the main pass. This one only closes the gap between where the
     * player is and where their tag was last told to be.
     */
    private void followWithNameplates() {
        for (Map.Entry<UUID, ArmorStand> entry : rarityNameplates.entrySet()) {
            Player owner = plugin.getServer().getPlayer(entry.getKey());
            ArmorStand plate = entry.getValue();
            if (owner == null || !plate.isValid() || plate.getWorld() != owner.getWorld()) {
                continue;
            }
            plate.teleport(nameplateLocation(owner));
        }
    }

    private static Location nameplateLocation(Player owner) {
        Location target = owner.getLocation().add(0d, -0.35d, 0d);
        target.setYaw(0f);
        target.setPitch(0f);
        return target;
    }

    /**
     * Deletes any odds tag the service is not currently driving.
     *
     * <p>The map is the only handle on a stand, so anything that loses an entry — a
     * crash between spawning and registering, a stand left by an older build — leaves a
     * line floating in the world that nothing will ever remove. Each stand carries its
     * owner's ID, so this can tell a live tag from a ghost without guessing.
     */
    private void sweepOrphanedNameplates() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!entity.getScoreboardTags().contains(RARITY_NAMEPLATE_TAG)) {
                    continue;
                }
                UUID owner = nameplateOwner(entity);
                if (owner != null && rarityNameplates.get(owner) == entity) {
                    continue;
                }
                entity.remove();
            }
        }
    }

    private static UUID nameplateOwner(Entity stand) {
        String prefix = RARITY_NAMEPLATE_TAG + ":";
        for (String tag : stand.getScoreboardTags()) {
            if (!tag.startsWith(prefix)) {
                continue;
            }
            try {
                return UUID.fromString(tag.substring(prefix.length()));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private void removeRarityNameplate(UUID playerId) {
        ArmorStand plate = rarityNameplates.remove(playerId);
        if (plate != null) {
            plate.remove();
        }
    }

    /**
     * The floating odds line under a player, coloured by the family of the cosmetic it
     * is reporting rather than by rarity alone, so a violet crystal set and a
     * starlight set never share a tag.
     */
    static Component rarityNameplate(CosmeticCatalog.Definition definition, long animationFrame) {
        CosmeticCatalog.OddsFamily family = definition.oddsFamily();
        int[] rgb = family.colours();
        TextColor[] palette = new TextColor[rgb.length];
        for (int index = 0; index < rgb.length; index++) {
            palette[index] = TextColor.color(rgb[index]);
        }
        String glyph = family.glyph();
        String text = glyph + " 1 IN "
                + String.format(java.util.Locale.ROOT, "%,d", definition.oneIn()) + " " + glyph;
        Component result = Component.empty();
        for (int index = 0; index < text.length(); index++) {
            result = result.append(Component.text(
                    Character.toString(text.charAt(index)),
                    oddsColour(palette, family.motion(), animationFrame, index, text.length()),
                    TextDecoration.BOLD
            ));
        }
        return result;
    }

    /**
     * The colour one character of the odds tag is drawn in.
     *
     * <p>Every motion samples a continuous position along the palette and blends the
     * two entries either side of it. Snapping to whole palette entries was what made
     * the tag look cheap: a four-colour palette stepping once every few frames is
     * four hard edges marching along the text, and the eye reads the steps rather
     * than the movement. Blending turns the same palette into a gradient that slides.
     */
    private static TextColor oddsColour(
            TextColor[] palette,
            CosmeticCatalog.OddsMotion motion,
            long frame,
            int index,
            int length
    ) {
        double position = switch (motion) {
            // A gradient the length of the palette, drifting along the text.
            case SCROLL -> frame * SCROLL_SPEED + index * SCROLL_SPREAD;
            // The whole line breathes together, so there is no spatial term.
            case PULSE -> frame * PULSE_SPEED;
            // One highlight sweeps the line and then rests: the travel past the end
            // is the pause between passes, which is what makes it read as a glint.
            // Eased so it swells and fades rather than switching on, and it enters
            // and leaves a full reach beyond either end — a head that reappears at
            // the first character is a jump however smooth the rest of the pass is.
            case SHIMMER -> {
                double span = length + SHIMMER_REST + SHIMMER_WIDTH * 2d;
                double head = Math.floorMod(frame, (long) Math.ceil(span * SHIMMER_FRAMES))
                        / SHIMMER_FRAMES - SHIMMER_WIDTH;
                double distance = Math.min(SHIMMER_WIDTH, Math.abs(head - index));
                // 1 under the head, 0 at the edge of the highlight, eased at both.
                double glow = (1d + Math.cos(Math.PI * distance / SHIMMER_WIDTH)) / 2d;
                // The line breathes underneath, so the glint travels over moving
                // colour rather than over a base that sits on one palette entry
                // between passes. The head lands on whole characters, so without
                // this the whole motion samples the palette at a handful of fixed
                // points and the glint is the only thing that is not static.
                yield frame * SHIMMER_DRIFT + (palette.length - 1) * glow;
            }
        };
        return blend(palette, position);
    }

    /**
     * Samples a palette at a fractional position, wrapping, mixing in linear RGB.
     *
     * <p>sRGB values are gamma-encoded, so averaging them directly darkens the middle
     * of every blend and a violet-to-white ramp sags grey halfway across. Squaring
     * into linear light, mixing, and taking the root back is what keeps the midpoint
     * as bright as the ends.
     */
    private static TextColor blend(TextColor[] palette, double position) {
        int size = palette.length;
        double wrapped = ((position % size) + size) % size;
        int first = (int) Math.floor(wrapped);
        double mix = wrapped - first;
        TextColor from = palette[first % size];
        TextColor to = palette[(first + 1) % size];
        return TextColor.color(
                channel(from.red(), to.red(), mix),
                channel(from.green(), to.green(), mix),
                channel(from.blue(), to.blue(), mix)
        );
    }

    private static int channel(int from, int to, double mix) {
        double linear = from * from * (1d - mix) + to * to * mix;
        return Math.clamp((int) Math.round(Math.sqrt(linear)), 0, 255);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        removeRarityNameplate(playerId);
        stopMusicAura(playerId);
        restoreFloatingPlayer(playerId);
        endRevealAtmosphere(event.getPlayer());
    }

    /** What the player's sky was before a reveal borrowed it. */
    private record AtmosphereState(long timeOffset, boolean relative, WeatherType weather) {
    }

    /**
     * Turns the world itself into part of the reveal.
     *
     * <p>Floating in sparkles under a normal midday sky reads as a particle effect. The
     * sky, the weather and the light are the difference between an effect playing and
     * something having gone wrong with the world. All of it is per-player and
     * client-side, so nobody else's day changes and the real weather is untouched.
     *
     * <p>The previous override is saved rather than reset afterwards, because
     * {@code /mgxadmin devblog} holds a sky of its own and a reveal must not clear it.
     */
    private void beginRevealAtmosphere(Player player, boolean intense) {
        revealAtmospheres.putIfAbsent(player.getUniqueId(), new AtmosphereState(
                player.getPlayerTimeOffset(), player.isPlayerTimeRelative(),
                player.getPlayerWeather()
        ));
        player.setPlayerTime(intense ? SECRET_REVEAL_TIME : EXOTIC_REVEAL_TIME, false);
        if (!intense) {
            return;
        }
        player.setPlayerWeather(WeatherType.DOWNFALL);
        // Three seconds of the world going out before it comes back wrong. Short on
        // purpose: darkness that outlasts the opening hides the effect it introduces.
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.DARKNESS, 60, 0, false, false, false
        ));
    }

    private void endRevealAtmosphere(Player player) {
        AtmosphereState previous = revealAtmospheres.remove(player.getUniqueId());
        if (previous == null) {
            return;
        }
        player.setPlayerTime(previous.timeOffset(), previous.relative());
        if (previous.weather() == null) {
            player.resetPlayerWeather();
        } else {
            player.setPlayerWeather(previous.weather());
        }
        player.removePotionEffect(PotionEffectType.DARKNESS);
    }

    /** Visual-only: no fire, no damage, and everybody nearby sees the same storm. */
    private static void revealLightning(Player player, double radius, double angle) {
        Location at = player.getLocation().add(
                Math.cos(angle) * radius, 0d, Math.sin(angle) * radius
        );
        player.getWorld().strikeLightningEffect(at);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player killer = event.getPlayer().getKiller();
        if (killer == null || VerificationLobbyService.isLobbyWorld(killer.getWorld())) {
            return;
        }
        Location centre = event.getPlayer().getLocation().add(0d, 1d, 0d);
        active(killer, CosmeticCatalog.Category.KILL_EFFECT).ifPresent(
                definition -> drawKillEffect(killer, definition, centre)
        );
    }

    /**
     * How long each reveal runs, in ticks. Published so the crate menu can stay out of
     * the way for exactly as long as there is something to watch, then carry on.
     */
    static long revealDurationTicks(CrateCatalog.RevealTier tier) {
        return switch (tier) {
            case NONE, LEGENDARY -> 0L;
            case MYTHIC -> MYTHIC_REVEAL_FRAMES * REVEAL_FRAME_TICKS;
            case SECRET -> SECRET_REVEAL_FRAMES * REVEAL_FRAME_TICKS;
            case GENUINE_SECRET -> GENUINE_REVEAL_FRAMES * REVEAL_FRAME_TICKS;
        };
    }

    void playCrateReveal(Player player, CrateCatalog.Reward reward) {
        switch (reward.revealTier()) {
            case NONE, LEGENDARY -> { }
            case MYTHIC -> playMythicReveal(player, reward);
            case SECRET -> playSecretReveal(player, reward);
            case GENUINE_SECRET -> playGenuineSecretReveal(player, reward);
        }
    }

    private void playMythicReveal(Player player, CrateCatalog.Reward reward) {
        player.showTitle(Title.title(
                Component.text("✦ MYTHIC DROP ✦", TextColor.color(0xFF4FD8), TextDecoration.BOLD),
                Component.text(reward.displayName(), NamedTextColor.GOLD, TextDecoration.BOLD),
                Title.Times.times(Duration.ofMillis(120), Duration.ofSeconds(3),
                        Duration.ofMillis(650))
        ));
        playServerwideRevealSound(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.15f);
        Location origin = player.getLocation().add(0d, 1d, 0d);
        Color pink = Color.fromRGB(255, 70, 190);
        Color gold = Color.fromRGB(255, 195, 50);
        animate(player, origin, MYTHIC_REVEAL_FRAMES, REVEAL_FRAME_TICKS, step -> {
            if (!player.isOnline()) {
                return;
            }
            double burst = CosmeticAnimation.easeOutBack(Math.min(1d, step / 14d));
            drawRing(player, origin.clone().add(0d, (step % 12) * 0.08d - 0.45d, 0d),
                    0.3d + burst * 2.2d, 24, step * 0.28d,
                    step % 2 == 0 ? pink : gold, 1.05f, null);
            for (int ray = 0; ray < 8; ray++) {
                double angle = ray * Math.PI / 4d + step * 0.15d;
                Location tip = origin.clone().add(
                        Math.cos(angle) * burst * 1.6d,
                        Math.sin(step * 0.35d + ray) * 0.65d,
                        Math.sin(angle) * burst * 1.6d
                );
                dust(player, tip, ray % 2 == 0 ? pink : gold, 1.15f, null);
            }
            if (step == 0 || step == 14 || step == 28) {
                spawn(player, origin, Particle.FLASH, 1, 0d, 0d, 0d, 0d, null, null);
            }
            if (step == 35) {
                spawn(player, origin, Particle.TOTEM_OF_UNDYING,
                        30, 0.55d, 0.8d, 0.55d, 0.12d, null, null);
            }
        });
    }

    private static Sound[] exoticRevealSounds() {
        return new Sound[]{
                Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                Sound.BLOCK_CONDUIT_ACTIVATE,
                Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR,
                Sound.BLOCK_ENCHANTMENT_TABLE_USE,
                Sound.BLOCK_AMETHYST_CLUSTER_BREAK
        };
    }

    private static Sound[] chaosRevealSounds() {
        return new Sound[]{
                Sound.ENTITY_WARDEN_SONIC_BOOM,
                Sound.ENTITY_ENDER_DRAGON_GROWL,
                Sound.BLOCK_END_PORTAL_SPAWN,
                Sound.ENTITY_WITHER_SPAWN,
                Sound.ENTITY_ELDER_GUARDIAN_CURSE,
                Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE,
                Sound.ENTITY_ENDER_DRAGON_FLAP,
                Sound.BLOCK_BEACON_POWER_SELECT,
                Sound.ENTITY_EVOKER_CAST_SPELL,
                Sound.ENTITY_WITHER_BREAK_BLOCK
        };
    }

    private void playSecretReveal(Player player, CrateCatalog.Reward reward) {
        player.showTitle(Title.title(
                Component.text("⚠ EXOTIC UNSEALED ⚠", TextColor.color(0xC24CFF),
                        TextDecoration.BOLD),
                Component.text(reward.displayName(), NamedTextColor.LIGHT_PURPLE,
                        TextDecoration.BOLD),
                Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(5),
                        Duration.ofSeconds(1))
        ));
        playServerwideRevealSound(Sound.ENTITY_ENDER_DRAGON_GROWL, 0.85f, 1.25f);
        // The sound Minecraft itself keeps for a rare advancement. Nobody has to be told
        // what it means.
        playServerwideRevealSound(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        beginRevealAtmosphere(player, false);
        Location origin = player.getLocation().add(0d, 1d, 0d);
        Color voidColour = Color.fromRGB(38, 0, 70);
        Color violet = Color.fromRGB(210, 70, 255);
        Color cyan = Color.fromRGB(55, 220, 255);
        Sound[] unsealing = exoticRevealSounds();
        animate(player, origin, SECRET_REVEAL_FRAMES, REVEAL_FRAME_TICKS, step -> {
            if (!player.isOnline()) {
                return;
            }
            double pulse = 0.55d + Math.sin(step * 0.42d) * 0.35d;
            double radius = 0.7d + pulse + (step / 69d) * 1.7d;
            drawRing(player, origin.clone().add(0d, -0.75d + (step % 20) * 0.09d, 0d),
                    radius, 28, step * 0.3d, step % 3 == 0 ? cyan : violet, 1.1f, null);
            for (int shard = 0; shard < 16; shard++) {
                double angle = shard * Math.PI / 8d + step * 0.31d;
                Location at = origin.clone().add(
                        Math.cos(angle) * radius,
                        Math.sin(angle * 3d + step * 0.22d) * 1.25d,
                        Math.sin(angle) * radius
                );
                Vector inward = origin.toVector().subtract(at.toVector());
                if (inward.lengthSquared() > 0.001d) {
                    inward.normalize().multiply(step < 38 ? 0.12d : -0.1d);
                }
                spawnMoving(player, at, shard % 4 == 0 ? Particle.REVERSE_PORTAL : Particle.DUST,
                        inward, shard % 4 == 0 ? null
                                : new Particle.DustOptions(shard % 2 == 0 ? violet : voidColour, 1.2f),
                        null);
            }
            if (step % 18 == 0) {
                spawn(player, origin, Particle.SONIC_BOOM, 1,
                        0d, 0d, 0d, 0d, null, null);
                spawn(player, origin, Particle.FLASH, 1,
                        0d, 0d, 0d, 0d, null, null);
            }
            // A single growl at the start and a boom at the end left the middle silent.
            // The pitch climbs with the animation so the unsealing is something you can
            // hear building rather than two unrelated noises.
            if (step % 6 == 0) {
                float rising = 0.7f + (step / (float) SECRET_REVEAL_FRAMES) * 0.8f;
                sound(player, origin, unsealing[(step / 6) % unsealing.length],
                        0.9f, rising, null);
            }
            if (step == 12) {
                playServerwideRevealSound(Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.4f);
            }
            if (step == 40) {
                sound(player, origin, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.9f, 1.3f, null);
                playServerwideRevealSound(Sound.BLOCK_PORTAL_TRIGGER, 0.55f, 1.5f);
            }
            if (step == SECRET_REVEAL_FRAMES - 12) {
                revealLightning(player, 9d, step * 0.4d);
            }
            if (step == SECRET_REVEAL_FRAMES - 1) {
                spawn(player, origin, Particle.TOTEM_OF_UNDYING,
                        48, 0.7d, 1d, 0.7d, 0.16d, null, null);
                sound(player, origin, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.2f, 0.75f, null);
                endRevealAtmosphere(player);
            }
        });
    }

    private void playGenuineSecretReveal(Player player, CrateCatalog.Reward reward) {
        player.closeInventory();
        beginFloatingPlayer(player);
        player.showTitle(Title.title(
                Component.text("✦ SECRET ✦", TextColor.color(0x53E5FF),
                        TextDecoration.BOLD),
                Component.text("IRIDESCENT IMPERIUM • 1 IN 500,000", NamedTextColor.GOLD,
                        TextDecoration.BOLD),
                Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(8),
                        Duration.ofSeconds(2))
        ));
        BossBar bar = BossBar.bossBar(
                genuineBossbarName(player.getName(), 0), 1f,
                BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_20
        );
        activeRevealBars.add(bar);
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            viewer.showBossBar(bar);
            viewer.showTitle(Title.title(
                    Component.text("✦ SECRET ✦", NamedTextColor.LIGHT_PURPLE,
                            TextDecoration.BOLD),
                    Component.text(player.getName() + " found " + reward.displayName(),
                            NamedTextColor.GOLD),
                    Title.Times.times(Duration.ofMillis(120), Duration.ofSeconds(3),
                            Duration.ofMillis(600))
            ));
            globalPlayerPulse(viewer, true);
        }
        playServerwideRevealSound(Sound.ENTITY_WITHER_SPAWN, 1.25f, 0.62f);
        playServerwideRevealSound(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.78f);
        beginRevealAtmosphere(player, true);
        plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> playServerwideRevealSound(Sound.BLOCK_END_PORTAL_SPAWN, 1.1f, 0.72f),
                45L
        );

        Color amethyst = Color.fromRGB(186, 74, 255);
        Color sapphire = Color.fromRGB(55, 110, 255);
        Color emerald = Color.fromRGB(45, 220, 145);
        Color ruby = Color.fromRGB(235, 55, 110);
        Color champagne = Color.fromRGB(255, 205, 95);
        Color[] jewels = {amethyst, sapphire, emerald, ruby, champagne};
        Sound[] chaosPool = chaosRevealSounds();
        Location base = floatingPlayers.get(player.getUniqueId()).returnLocation().clone();
        animate(player, base.clone().add(0d, 1d, 0d), GENUINE_REVEAL_FRAMES,
                REVEAL_FRAME_TICKS, step -> {
            floatGenuineWinner(player, step);
            bar.progress(Math.max(0f, 1f - step / 249f));
            if (step % 4 == 0) {
                bar.name(genuineBossbarName(player.getName(), step));
            }
            if (player.isOnline()) {
                Location centre = player.getLocation().add(0d, 0.9d, 0d);
                double beat = 0.55d + Math.sin(step * 0.52d) * 0.35d;
                double expanding = 0.9d + (step % 25) / 25d * 3.2d;
                drawRing(player, centre.clone().add(0d, -0.85d, 0d), expanding,
                        32, step * 0.24d, jewels[(step / 8) % jewels.length], 1.18f, null);
                drawRing(player, centre.clone().add(0d, 0.55d, 0d),
                        0.8d + beat * 1.25d, 24, -step * 0.33d,
                        jewels[(step / 5 + 2) % jewels.length], 1.05f, null);
                for (int pillar = 0; pillar < 12; pillar++) {
                    double angle = pillar * Math.PI / 6d + step * 0.19d;
                    double height = 0.55d + ((pillar + step) % 5) * 0.42d + beat;
                    Location root = centre.clone().add(
                            Math.cos(angle) * (1.15d + beat * 0.4d), -0.9d,
                            Math.sin(angle) * (1.15d + beat * 0.4d)
                    );
                    drawLine(player, root, root.clone().add(0d, height, 0d), 5,
                            jewels[pillar % jewels.length], 1.08f, null);
                }
                if (step % 20 == 0) {
                    spawn(player, centre, Particle.FLASH, 1,
                            0d, 0d, 0d, 0d, null, null);
                    spawn(player, centre, Particle.SONIC_BOOM, 1,
                            0d, 0d, 0d, 0d, null, null);
                }
                // Chaos, but built rather than random: a dense overlapping stack around
                // the winner, and only the landmark beats go out to the whole server, so
                // twenty-five seconds of this does not become twenty-five seconds of
                // noise in everybody else's ears.
                if (step % 3 == 0) {
                    Sound chaos = chaosPool[(step / 3) % chaosPool.length];
                    float pitch = 0.55f + ((step * 7) % 13) / 13f * 1.35f;
                    sound(player, centre, chaos, 1f, pitch, null);
                }
                if (step % 9 == 0) {
                    sound(player, centre, Sound.ENTITY_WITHER_SHOOT, 0.8f,
                            0.5f + ((step * 5) % 11) / 11f, null);
                    sound(player, centre, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f,
                            0.6f + ((step * 3) % 17) / 17f * 1.4f, null);
                }
                if (step % 25 == 0) {
                    sound(player, centre, Sound.ENTITY_GENERIC_EXPLODE, 0.9f,
                            0.6f + (step % 50) / 50f, null);
                    sound(player, centre, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.7f, 1.4f, null);
                }
                // Real strikes walking a ring around the winner. The storm is the point:
                // an Exotic borrows a dusk sky, a Secret tears the weather open.
                if (step % 14 == 0) {
                    revealLightning(player, 6d + (step % 3) * 2.5d, step * 0.77d);
                }
                if (step == 40 || step == 120 || step == 200) {
                    playServerwideRevealSound(Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.8f, 0.8f);
                }
                if (step == 200) {
                    playServerwideRevealSound(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.4f);
                }
                if (step == 80 || step == 160 || step == 230) {
                    spawn(player, centre, Particle.TOTEM_OF_UNDYING,
                            80, 1d, 1.5d, 1d, 0.2d, null, null);
                    playServerwideRevealSound(
                            step == 230 ? Sound.ENTITY_ENDER_DRAGON_GROWL
                                    : Sound.ENTITY_WARDEN_SONIC_BOOM,
                            1.1f, step == 230 ? 0.72f : 1.15f
                    );
                    for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                        globalPlayerPulse(viewer, false);
                    }
                }
            }
            if (step == GENUINE_REVEAL_FRAMES - 1) {
                endRevealAtmosphere(player);
                restoreFloatingPlayer(player.getUniqueId());
                for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                    viewer.hideBossBar(bar);
                }
                activeRevealBars.remove(bar);
            }
        });
    }

    private void playServerwideRevealSound(Sound sound, float volume, float pitch) {
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (settings.isEnabled(
                    viewer.getUniqueId(), PlayerSettingsStore.Setting.COSMETIC_SOUNDS
            )) {
                viewer.playSound(viewer.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
            }
        }
    }

    private static void globalPlayerPulse(Player viewer, boolean opening) {
        Location at = viewer.getLocation().add(0d, 1d, 0d);
        // FLASH takes a required Color in 1.21. Without one the spawn throws
        // "missing required data class org.bukkit.Color" and aborts the pulse, which is
        // what broke the genuine-secret reveal for every player watching it.
        viewer.spawnParticle(Particle.FLASH, at, 1, 0d, 0d, 0d, 0d,
                particleData(Particle.FLASH, null));
        viewer.spawnParticle(
                Particle.TOTEM_OF_UNDYING, at,
                opening ? 22 : 12,
                opening ? 0.55d : 0.35d,
                opening ? 0.8d : 0.45d,
                opening ? 0.55d : 0.35d,
                opening ? 0.1d : 0.05d
        );
        viewer.sendActionBar(Component.text(
                opening ? "✦ A SECRET HAS ENTERED THE SERVER ✦"
                        : "✦ THE IMPERIUM RESONATES ✦",
                opening ? TextColor.color(0xE95CFF) : TextColor.color(0x53E5FF),
                TextDecoration.BOLD
        ));
    }

    /**
     * One colour, held steady.
     *
     * <p>A seven-colour gradient scrolling through the rarest announcement on the server
     * read as cheap rather than rare, so the bar states the fact and lets the effect
     * around it do the work. The {@code step} is kept because the caller redraws the bar
     * as its progress falls.
     */
    static Component genuineBossbarName(String playerName, int step) {
        return Component.text(
                "✦ " + playerName + " FOUND A SECRET • 1 IN 500,000 ✦",
                SECRET_REVEAL_COLOUR, TextDecoration.BOLD
        );
    }

    private void beginFloatingPlayer(Player player) {
        restoreFloatingPlayer(player.getUniqueId());
        floatingPlayers.put(player.getUniqueId(), new FloatingPlayerState(
                player.getLocation().clone(), player.hasGravity(), player.isInvulnerable(),
                player.isCollidable(), player.getPose(), player.hasFixedPose()
        ));
        player.setVelocity(new Vector());
        player.setFallDistance(0f);
        player.setGravity(false);
        player.setInvulnerable(true);
        player.setCollidable(false);
        player.setPose(Pose.FALL_FLYING, true);
    }

    private void floatGenuineWinner(Player player, int step) {
        FloatingPlayerState state = floatingPlayers.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        if (!player.isOnline() || player.getWorld() != state.returnLocation().getWorld()) {
            restoreFloatingPlayer(player.getUniqueId());
            return;
        }
        if (step >= 150) {
            restoreFloatingPlayer(player.getUniqueId());
            return;
        }
        double progress = step / 149d;
        double lift = Math.sin(progress * Math.PI);
        double orbit = Math.sin(progress * Math.PI) * 0.22d;
        double angle = step * 0.12d;
        Location target = state.returnLocation().clone().add(
                Math.cos(angle) * orbit,
                lift * 1.75d + Math.sin(step * 0.3d) * 0.08d * lift,
                Math.sin(angle) * orbit
        );
        // Their camera is theirs. Spinning it for them made the best drop on the
        // server the one moment a player cannot look at what they won.
        Location looking = player.getLocation();
        target.setYaw(looking.getYaw());
        target.setPitch(looking.getPitch());
        player.setPose(Pose.FALL_FLYING, true);
        player.setVelocity(new Vector());
        player.setFallDistance(0f);
        player.teleport(target);
    }

    private void restoreFloatingPlayer(UUID playerId) {
        FloatingPlayerState state = floatingPlayers.remove(playerId);
        if (state == null) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null) {
            return;
        }
        player.setGravity(state.gravity());
        player.setInvulnerable(state.invulnerable());
        player.setCollidable(state.collidable());
        player.setPose(state.pose(), state.fixedPose());
        player.setVelocity(new Vector());
        player.setFallDistance(0f);
        if (player.getWorld() == state.returnLocation().getWorld()) {
            player.teleport(state.returnLocation());
        }
    }

    private Optional<CosmeticCatalog.Definition> active(
            Player player, CosmeticCatalog.Category category
    ) {
        UUID playerId = player.getUniqueId();
        String selectedLeaderboard = store.leaderboardEquipped(
                playerId, category.name()
        ).orElse(null);
        if (selectedLeaderboard != null) {
            Optional<CosmeticCatalog.Definition> entitled = selectedLeaderboardReward(
                    leaderboard.standing(playerId).orElse(null), category, selectedLeaderboard
            );
            if (entitled.isPresent()) {
                failedSelectionClears.remove(playerId + ":LEADERBOARD:" + category.name());
                return entitled;
            }
            String leaderboardSelectionKey = playerId + ":LEADERBOARD:" + category.name();
            try {
                store.clearLeaderboardEquipped(
                        playerId, category.name(), selectedLeaderboard
                );
                failedSelectionClears.remove(leaderboardSelectionKey);
            } catch (UncheckedIOException exception) {
                if (failedSelectionClears.add(leaderboardSelectionKey)) {
                    plugin.getLogger().warning("Could not clear an expired leaderboard cosmetic: "
                            + exception.getMessage());
                }
            }
        }
        UUID serial = store.equipped(playerId, category.name()).orElse(null);
        if (serial == null) {
            return Optional.empty();
        }
        CosmeticStore.Token token = store.token(serial).orElse(null);
        CosmeticCatalog.Definition definition = token == null
                ? null
                : CosmeticCatalog.find(token.cosmeticId()).orElse(null);
        String selectionKey = playerId + ":" + category.name();
        if (token == null
                || definition == null
                || definition.category() != category
                || !wardrobe.hasAccess(player, token)) {
            try {
                store.clearEquipped(playerId, category.name(), serial);
                failedSelectionClears.remove(selectionKey);
            } catch (UncheckedIOException exception) {
                if (failedSelectionClears.add(selectionKey)) {
                    plugin.getLogger().warning("Could not clear an inaccessible cosmetic: "
                            + exception.getMessage());
                }
            }
            return Optional.empty();
        }
        failedSelectionClears.remove(selectionKey);
        return Optional.of(definition);
    }

    static Optional<CosmeticCatalog.Definition> selectedLeaderboardReward(
            LeaderboardStandings.Standing standing,
            CosmeticCatalog.Category category,
            String selectedCosmeticId
    ) {
        if (standing == null
                || standing.placement() > 3
                || selectedCosmeticId == null
                || selectedCosmeticId.isBlank()) {
            return Optional.empty();
        }
        return CosmeticCatalog.leaderboardReward(standing.placement(), category)
                .filter(definition -> definition.id().equals(selectedCosmeticId));
    }

    private void drawAura(Player owner, CosmeticCatalog.Definition definition, boolean moving) {
        Location centre = owner.getLocation().add(0d, 1.05d, 0d);
        long animatedFrame = frame + CosmeticAnimation.playerOffset(owner.getUniqueId(), 80);
        int step = CosmeticAnimation.step(animatedFrame, 80);
        double phase = animatedFrame * 0.24d;
        if (definition.leaderboardOnly()) {
            drawLeaderboardAura(owner, definition, centre, phase, step);
            return;
        }
        if (definition.id().equals(MUSIC_AURA_ID)) {
            drawIridescentImperium(owner, centre, moving);
            return;
        }
        if (definition.secret()) {
            drawSecretAura(owner, definition, centre, phase, step);
            return;
        }
        switch (definition.id()) {
            case "solar_orbit" -> drawSolarAura(owner, centre, phase, step);
            case "crimson_orbit" -> drawCrimsonAura(owner, centre, phase, step);
            case "emerald_orbit" -> drawEmeraldAura(owner, centre, phase, step);
            case "amethyst_orbit" -> drawAmethystAura(owner, centre, phase, step);
            case "celestial_crown" -> drawCelestialCrown(owner, centre, phase, step);
            case "amethyst_ascension" -> drawAmethystAscension(owner, centre, phase, step);
            case "geode_cathedral" -> drawGeodeCathedral(owner, centre, phase, step);
            default -> { }
        }
    }

    private void syncMusicAura(Player owner) {
        long now = System.currentTimeMillis();
        MusicAuraState state = musicAuraStates.computeIfAbsent(
                owner.getUniqueId(), ignored -> new MusicAuraState(now)
        );
        List<Player> currentViewers = listeners(owner);
        Set<UUID> currentViewerIds = new HashSet<>();
        boolean volumeChanged = false;
        for (Player viewer : currentViewers) {
            UUID viewerId = viewer.getUniqueId();
            currentViewerIds.add(viewerId);
            int volume = settings.musicVolume(viewerId);
            Integer previousVolume = state.observedVolumes.put(viewerId, volume);
            if (previousVolume != null && previousVolume != volume) {
                volumeChanged = true;
            }
        }
        for (UUID listenerId : List.copyOf(state.listeners)) {
            if (currentViewerIds.contains(listenerId)) {
                continue;
            }
            Player listener = plugin.getServer().getPlayer(listenerId);
            if (listener != null) {
                listener.stopSound(MUSIC_AURA_SOUND, SoundCategory.MASTER);
            }
            state.listeners.remove(listenerId);
        }
        state.observedVolumes.keySet().retainAll(currentViewerIds);

        // Sound volume is fixed when Minecraft receives the play packet. Restart the
        // shared timeline as soon as any nearby viewer changes their server setting,
        // keeping both the song and every beat-driven visual on the same timestamp.
        if (volumeChanged) {
            stopMusicAura(owner.getUniqueId());
            state = new MusicAuraState(now);
            musicAuraStates.put(owner.getUniqueId(), state);
        }
        long loop = Math.max(0L, now - state.startedAtMillis) / MusicAuraTimeline.DURATION_MILLIS;
        if (state.loop == loop) {
            return;
        }
        for (UUID listenerId : List.copyOf(state.listeners)) {
            Player listener = plugin.getServer().getPlayer(listenerId);
            if (listener != null) {
                listener.stopSound(MUSIC_AURA_SOUND, SoundCategory.MASTER);
            }
        }
        state.listeners.clear();
        state.observedVolumes.clear();
        state.loop = loop;
        for (Player viewer : currentViewers) {
            int volume = settings.musicVolume(viewer.getUniqueId());
            state.observedVolumes.put(viewer.getUniqueId(), volume);
            if (volume <= 0) {
                continue;
            }
            viewer.playSound(
                    net.kyori.adventure.sound.Sound.sound(
                            net.kyori.adventure.key.Key.key(MUSIC_AURA_SOUND),
                            net.kyori.adventure.sound.Sound.Source.MASTER,
                            volume / 100.0f,
                            1f
                    ),
                    net.kyori.adventure.sound.Sound.Emitter.self()
            );
            state.listeners.add(viewer.getUniqueId());
        }
    }

    private void stopMusicAura(UUID ownerId) {
        MusicAuraState state = musicAuraStates.remove(ownerId);
        if (state == null) {
            return;
        }
        for (UUID listenerId : state.listeners) {
            Player listener = plugin.getServer().getPlayer(listenerId);
            if (listener != null) {
                listener.stopSound(MUSIC_AURA_SOUND, SoundCategory.MASTER);
            }
        }
    }

    /**
     * A faceted amethyst conductor changes formation and luxury-jewel palette with the
     * supplied recording. Bass lifts the crown, mids widen the orbit, highs sharpen the
     * facets, and measured transients create the visible beat hits.
     */
    private void drawIridescentImperium(Player owner, Location centre, boolean moving) {
        MusicAuraState state = musicAuraStates.get(owner.getUniqueId());
        if (state == null) {
            return;
        }
        // Thinning the frames alone still leaves a two-block formation to drag through
        // the world, so a moving wearer also gets a tighter one and none of the
        // single-frame flourishes that read as scattered debris at speed.
        double spread = moving ? 0.55d : 1d;
        long elapsed = Math.max(0L, System.currentTimeMillis() - state.startedAtMillis);
        long phaseMillis = elapsed % MusicAuraTimeline.DURATION_MILLIS;
        MusicAuraTimeline.Sample sample = MusicAuraTimeline.at(phaseMillis);
        double bass = sample.bass();
        double mid = sample.mid();
        double high = sample.high();
        // The source envelope is intentionally smooth enough to avoid visual noise.
        // Expand its transient range here so ordinary beats visibly punch and the
        // strongest attacks detonate instead of reading as a soft breathing motion.
        double hit = Math.max(0d, Math.min(1d, (sample.onset() - 0.12d) * 1.7d));
        double time = phaseMillis / 1_000.0d;
        int formation = (int) (phaseMillis / 6_000L) % 4;

        Color amethyst = Color.fromRGB(174, 77, 238);
        Color lilac = Color.fromRGB(236, 188, 255);
        Color ruby = Color.fromRGB(176, 31, 78);
        Color sapphire = Color.fromRGB(52, 92, 205);
        Color emerald = Color.fromRGB(28, 151, 96);
        Color champagne = Color.fromRGB(242, 190, 92);
        Color[] couture = {ruby, sapphire, emerald, champagne};
        int band = bass >= mid && bass >= high ? 0 : mid >= high ? 2 : 1;
        Color accent = couture[(band + formation) % couture.length];

        Vector side = horizontalSide(owner);
        Vector forward = new Vector(-side.getZ(), 0d, side.getX());
        Location heart = centre.clone().add(
                0d, -0.18d + bass * 0.62d + hit * 0.82d, 0d
        );
        drawVerticalGem(
                owner, heart, side, 0.4d + bass * 0.28d + hit * 0.24d,
                time * (0.9d + high * 0.9d), amethyst, accent
        );

        int jewels = 12 + formation * 2;
        double orbitRadius = (0.62d + mid * 1.05d + hit * 0.72d) * spread;
        for (int jewel = 0; jewel < jewels; jewel++) {
            double angle = time * (1.15d + high * 1.05d)
                    + jewel * Math.PI * 2d / jewels;
            double vertical = switch (formation) {
                case 0 -> Math.sin(angle * 2d) * (0.35d + high * 0.55d);
                case 1 -> Math.sin(jewel * Math.PI / Math.max(1, jewels - 1)) * 1.8d - 0.75d;
                case 2 -> -1.05d + jewel * (2.15d / Math.max(1, jewels - 1));
                default -> Math.cos(angle * 3d) * (0.55d + mid * 0.48d);
            } * spread;
            Location at = heart.clone()
                    .add(side.clone().multiply(Math.cos(angle) * orbitRadius))
                    .add(forward.clone().multiply(Math.sin(angle) * orbitRadius * 0.72d))
                    .add(0d, vertical + hit * (jewel % 2 == 0 ? 0.55d : -0.3d), 0d);
            Color jewelColour = jewel % 3 == 0
                    ? accent : jewel % 2 == 0 ? amethyst : lilac;
            dust(owner, at, jewelColour, jewel % 3 == 0 ? 1.28f : 0.9f,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
            if (!moving && high > 0.58d && jewel % 3 == 0) {
                spawnMoving(owner, at, Particle.END_ROD,
                        new Vector(0d, 0.04d + high * 0.07d + hit * 0.05d, 0d), null,
                        PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
            }
        }

        Location crown = centre.clone().add(0d, 1.18d + bass * 0.32d + hit * 0.72d, 0d);
        for (int point = 0; point < 12; point++) {
            double angle = -time * 0.72d + point * Math.PI * 2d / 12d;
            double pointHeight = point % 3 == 0 ? 0.45d + high * 0.55d + hit * 0.45d : 0.1d;
            Location at = crown.clone()
                    .add(side.clone().multiply(Math.cos(angle) * (0.55d + bass * 0.32d) * spread))
                    .add(forward.clone().multiply(Math.sin(angle) * (0.55d + bass * 0.32d) * spread))
                    .add(0d, pointHeight, 0d);
            dust(owner, at, point % 3 == 0 ? champagne : amethyst,
                    point % 3 == 0 ? 1.05f : 0.78f,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }

        // Ten columns are the literal audio visualizer: bass, mid and high energy
        // independently change their height every 100 ms sample.
        for (int bar = 0; bar < 10; bar++) {
            double angle = bar * Math.PI * 2d / 10d - time * 0.35d;
            double bandEnergy = switch (bar % 3) {
                case 0 -> bass;
                case 1 -> mid;
                default -> high;
            };
            Vector radial = side.clone().multiply(Math.cos(angle))
                    .add(forward.clone().multiply(Math.sin(angle)));
            Location root = centre.clone()
                    .add(radial.clone().multiply((1.08d + hit * 0.45d) * spread))
                    .add(0d, -0.88d, 0d);
            Location tip = root.clone()
                    .add(0d, (0.25d + bandEnergy * 1.65d + hit * 0.8d) * spread, 0d);
            drawLine(owner, root, tip, 4, couture[(bar + formation) % couture.length],
                    0.92f + (float) hit * 0.35f,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }

        double[] bands = {bass, mid, high};
        for (int ring = 0; ring < bands.length; ring++) {
            drawRing(owner, centre.clone().add(0d, -0.55d + ring * 0.55d, 0d),
                    (0.38d + bands[ring] * 1.18d + hit * 0.55d) * spread,
                    18, time * (ring % 2 == 0 ? 1.8d : -1.8d),
                    ring == 0 ? amethyst : ring == 1 ? accent : lilac,
                    0.84f + (float) hit * 0.3f,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }

        double groundPulse = (0.78d + sample.energy() * 1.05d + hit * 1.65d) * spread;
        drawRing(owner, centre.clone().add(0d, -0.88d, 0d), groundPulse,
                30, time * (formation % 2 == 0 ? 1.7d : -1.7d), accent, 1.02f,
                PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        if (!moving && hit >= 0.35d) {
            drawRing(owner, heart, 0.3d + hit * 2.45d, 28,
                    -time * 2.4d, lilac, 1.15f,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
            spawn(owner, crown, hit > 0.78d ? Particle.FLASH : Particle.FIREWORK,
                    1, 0d, 0d, 0d, 0d, null,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
            spawn(owner, heart, Particle.ELECTRIC_SPARK,
                    3 + (int) Math.round(hit * 5d),
                    0.25d + hit * 0.45d, 0.4d + hit * 0.6d, 0.25d + hit * 0.45d,
                    0.02d + hit * 0.04d, null,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }
    }

    /** A crystal grows from the floor, unfolds into a crown, then ascends through two rings. */
    private void drawAmethystAscension(Player owner, Location centre, double phase, int step) {
        Color violet = Color.fromRGB(176, 92, 255);
        Color shine = Color.fromRGB(238, 205, 255);
        double grow = CosmeticAnimation.smooth(CosmeticAnimation.phaseProgress(step, 0, 24));
        double lift = step < 48 ? 0d : CosmeticAnimation.smooth(
                CosmeticAnimation.phaseProgress(step, 48, 79)) * 0.9d;
        for (int shard = 0; shard < 6; shard++) {
            double angle = phase * 0.45d + shard * Math.PI / 3d;
            double radius = 0.22d + grow * 0.68d;
            Location root = centre.clone().add(Math.cos(angle) * radius, -0.9d + lift,
                    Math.sin(angle) * radius);
            Location tip = root.clone().add(Math.cos(angle) * 0.18d,
                    (0.25d + grow * 0.85d) * (shard % 2 == 0 ? 1d : 0.72d),
                    Math.sin(angle) * 0.18d);
            drawLine(owner, root, tip, 4, shard % 2 == 0 ? shine : violet, 0.92f,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }
        drawRing(owner, centre.clone().add(0d, -0.65d + lift, 0d),
                0.3d + grow * 0.8d, 14, phase, violet, 0.9f,
                PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        drawRing(owner, centre.clone().add(0d, 0.75d + lift * 0.35d, 0d),
                0.85d - grow * 0.3d, 10, -phase * 1.4d, shine, 0.72f,
                PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        if (step == 24 || step == 48) {
            sound(owner, centre, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7f,
                    step == 24 ? 1.15f : 1.55f, PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }
    }

    /** Four animated geode arches open like cathedral doors and collapse into a new layout. */
    private void drawGeodeCathedral(Player owner, Location centre, double phase, int step) {
        Color deep = Color.fromRGB(92, 34, 155);
        Color crystal = Color.fromRGB(210, 145, 255);
        double open = CosmeticAnimation.pingPong(step / 79d);
        Vector side = horizontalSide(owner);
        Vector forward = new Vector(-side.getZ(), 0d, side.getX());
        for (int arch = 0; arch < 4; arch++) {
            double angle = arch * Math.PI / 2d + phase * 0.18d;
            Vector radial = side.clone().multiply(Math.cos(angle))
                    .add(forward.clone().multiply(Math.sin(angle)));
            Location base = centre.clone().add(radial.clone().multiply(0.45d + open * 0.7d))
                    .add(0d, -0.85d, 0d);
            Location peak = centre.clone().add(radial.clone().multiply(0.22d))
                    .add(0d, 0.85d + open * 0.35d, 0d);
            drawLine(owner, base, peak, 6, arch % 2 == 0 ? crystal : deep, 1.0f,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
            Location mirror = base.clone().subtract(radial.clone().multiply(0.34d));
            drawLine(owner, mirror, peak, 5, crystal, 0.72f,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }
        if (step % 20 == 0) {
            spawn(owner, centre.clone().add(0d, 0.2d, 0d), Particle.END_ROD, 5,
                    0.35d, 0.65d, 0.35d, 0.02d, null,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
            sound(owner, centre, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.45f,
                    0.8f + (step / 20) * 0.18f, PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }
    }

    /** A miniature sun rises, gains orbiting bodies, throws a flare, then pulses outward. */
    private void drawSolarAura(Player owner, Location centre, double phase, int step) {
        Color gold = Color.fromRGB(255, 190, 35);
        Color whiteGold = Color.fromRGB(255, 247, 178);
        double rise = CosmeticAnimation.smooth(CosmeticAnimation.phaseProgress(step, 0, 18));
        double sunY = step < 18 ? -0.72d + rise * 1.58d : 0.86d;
        Location sun = centre.clone().add(0d, sunY, 0d);
        dust(owner, sun, gold, 1.35f, PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        spawn(owner, sun, Particle.SMALL_FLAME, 1, 0.08d, 0.08d, 0.08d, 0.01d, null,
                PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        drawVerticalStar(owner, sun, horizontalSide(owner), 0.38d,
                phase * 0.38d, gold, whiteGold);

        double orbitOpen = CosmeticAnimation.smooth(
                CosmeticAnimation.phaseProgress(step, 12, 32)
        );
        for (int body = 0; body < 3; body++) {
            double angle = phase * (0.8d + body * 0.17d) + body * Math.PI * 2d / 3d;
            double radius = (0.26d + body * 0.22d) * orbitOpen;
            Location planet = sun.clone().add(
                    Math.cos(angle) * radius,
                    Math.sin(angle * 1.7d + body) * 0.18d * orbitOpen,
                    Math.sin(angle) * radius
            );
            dust(owner, planet, body == 1 ? Color.fromRGB(255, 245, 170) : gold,
                    0.75f + body * 0.12f, PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }

        if (step >= 34 && step < 60) {
            double flare = CosmeticAnimation.easeOutBack(
                    CosmeticAnimation.phaseProgress(step, 34, 55)
            );
            for (int point = 0; point < 7; point++) {
                double progress = point / 6d;
                double angle = phase + progress * Math.PI * 1.3d;
                Location at = sun.clone().add(
                        Math.cos(angle) * progress * 0.9d * flare,
                        Math.sin(progress * Math.PI) * 0.48d * flare,
                        Math.sin(angle) * progress * 0.9d * flare
                );
                spawn(owner, at, point == 6 ? Particle.FIREWORK : Particle.SMALL_FLAME,
                        1, 0d, 0d, 0d, 0d, null,
                        PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
            }
        }
        if (step >= 58) {
            double release = CosmeticAnimation.smooth(
                    CosmeticAnimation.phaseProgress(step, 58, 79)
            );
            drawRing(owner, centre.clone().add(0d, -0.9d, 0d),
                    0.25d + release * 1.5d, 14, -phase, gold, 0.9f,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }
    }

    /** Two heartbeats launch three crimson blades through a changing clover orbit. */
    private void drawCrimsonAura(Player owner, Location centre, double phase, int step) {
        Color crimson = Color.fromRGB(195, 8, 32);
        Color rubyLight = Color.fromRGB(255, 78, 105);
        double firstBeat = Math.max(0d, 1d - Math.abs(step - 10d) / 5d);
        double secondBeat = Math.max(0d, 1d - Math.abs(step - 21d) / 4d);
        double beat = Math.max(firstBeat, secondBeat * 0.8d);
        drawVerticalGem(owner, centre.clone().add(0d, 0.08d, 0d), horizontalSide(owner),
                0.46d + beat * 0.1d, -phase * 0.48d, crimson, rubyLight);
        drawRing(owner, centre.clone().add(0d, 0.05d, 0d),
                0.45d + beat * 0.85d, 12, phase, crimson, 1.05f,
                PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        for (int blade = 0; blade < 3; blade++) {
            double angle = -phase * 1.35d + blade * Math.PI * 2d / 3d;
            double sweep = 0.72d + 0.28d * Math.sin((step + blade * 9d) * 0.16d);
            Location root = centre.clone().add(
                    Math.cos(angle) * sweep, -0.55d + blade * 0.48d,
                    Math.sin(angle) * sweep
            );
            Location tip = root.clone().add(
                    Math.cos(angle + Math.PI / 2d) * 0.5d,
                    0.35d + beat * 0.2d,
                    Math.sin(angle + Math.PI / 2d) * 0.5d
            );
            drawLine(owner, root, tip, 4, crimson, 1.15f,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }
        if (step >= 42) {
            double rain = CosmeticAnimation.phaseProgress(step, 42, 79);
            for (int drop = 0; drop < 5; drop++) {
                double angle = phase + drop * 2.399963d;
                double y = 1.05d - ((rain + drop * 0.17d) % 1d) * 2.05d;
                Location at = centre.clone().add(
                        Math.cos(angle) * (0.45d + drop * 0.1d), y,
                        Math.sin(angle) * (0.45d + drop * 0.1d)
                );
                spawnMoving(owner, at, Particle.DUST, new Vector(0d, -0.08d, 0d),
                        new Particle.DustOptions(crimson, 0.85f),
                        PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
            }
        }
    }

    /** Twin living vines climb, cross, bloom, and sink before beginning again. */
    private void drawEmeraldAura(Player owner, Location centre, double phase, int step) {
        Color emerald = Color.fromRGB(30, 225, 125);
        Color lime = Color.fromRGB(165, 255, 85);
        double growth = step < 48
                ? CosmeticAnimation.smooth(CosmeticAnimation.phaseProgress(step, 0, 36))
                : 1d - CosmeticAnimation.smooth(CosmeticAnimation.phaseProgress(step, 62, 79));
        int visible = Math.max(2, (int) Math.round(12d * growth));
        for (int vine = 0; vine < 2; vine++) {
            for (int point = 0; point < visible; point++) {
                double progress = point / 11d;
                double angle = phase * 0.45d + progress * Math.PI * 3d + vine * Math.PI;
                Location at = centre.clone().add(
                        Math.cos(angle) * (0.48d + progress * 0.18d),
                        -0.88d + progress * 1.85d,
                        Math.sin(angle) * (0.48d + progress * 0.18d)
                );
                dust(owner, at, vine == 0 ? emerald : lime, 0.85f,
                        PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
            }
        }
        double crystalForm = CosmeticAnimation.easeOutBack(
                CosmeticAnimation.phaseProgress(step, 12, 34)
        );
        for (int crystal = 0; crystal < 3; crystal++) {
            double angle = -phase * 0.72d + crystal * Math.PI * 2d / 3d;
            double height = -0.48d + crystal * 0.48d
                    + Math.sin(phase * 0.7d + crystal) * 0.12d;
            drawOrbitingCrystal(owner, centre, angle, 0.92d * crystalForm,
                    height, 0.48d * crystalForm,
                    crystal % 2 == 0 ? emerald : lime);
        }
        if (step >= 32 && step < 65) {
            double bloom = CosmeticAnimation.pingPong((step - 32d) / 33d);
            for (int leaf = 0; leaf < 6; leaf++) {
                double angle = phase + leaf * Math.PI / 3d;
                Location at = centre.clone().add(
                        Math.cos(angle) * (0.45d + bloom * 0.85d),
                        0.75d + Math.sin(angle * 2d) * 0.18d,
                        Math.sin(angle) * (0.45d + bloom * 0.85d)
                );
                spawnMoving(owner, at, Particle.HAPPY_VILLAGER,
                        new Vector(0d, 0.025d, 0d), null,
                        PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
            }
        }
    }

    /** Six crystals assemble, rotate as a prism, then break outward and reform. */
    private void drawAmethystAura(Player owner, Location centre, double phase, int step) {
        Color violet = Color.fromRGB(165, 75, 240);
        Color highlight = Color.fromRGB(235, 185, 255);
        double assemble = CosmeticAnimation.easeOutBack(
                CosmeticAnimation.phaseProgress(step, 0, 24)
        );
        double shatter = step < 56 ? 0d : CosmeticAnimation.smooth(
                CosmeticAnimation.phaseProgress(step, 56, 76)
        );
        for (int shard = 0; shard < 6; shard++) {
            double angle = phase * 0.65d + shard * Math.PI / 3d;
            double radius = 1.05d * assemble + shatter * 0.95d;
            double y = -0.55d + (shard % 3) * 0.55d + shatter * (shard - 2.5d) * 0.22d;
            Location base = centre.clone().add(
                    Math.cos(angle) * radius, y, Math.sin(angle) * radius
            );
            Location tip = base.clone().add(
                    Math.cos(angle) * 0.16d, 0.42d, Math.sin(angle) * 0.16d
            );
            drawLine(owner, base, tip, 4, shard % 2 == 0 ? highlight : violet,
                    1.05f, PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
            if (shatter > 0.25d) {
                spawnMoving(owner, tip, Particle.ELECTRIC_SPARK,
                        new Vector(Math.cos(angle) * 0.07d, 0.045d, Math.sin(angle) * 0.07d),
                        null, PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
            }
        }
        if (step >= 28 && step < 55) {
            drawRing(owner, centre, 0.45d + (step - 28d) * 0.018d,
                    10, -phase * 1.4d, highlight, 0.75f,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }
    }

    /** Crown points rise independently, lock together, launch a star, then dissolve. */
    private void drawCelestialCrown(Player owner, Location centre, double phase, int step) {
        Color ice = Color.fromRGB(210, 235, 255);
        Color star = Color.fromRGB(255, 250, 190);
        Location crown = centre.clone().add(0d, 0.92d, 0d);
        double assemble = CosmeticAnimation.easeOutBack(
                CosmeticAnimation.phaseProgress(step, 0, 24)
        );
        double dissolve = CosmeticAnimation.smooth(
                CosmeticAnimation.phaseProgress(step, 66, 79)
        );
        for (int point = 0; point < 10; point++) {
            double angle = phase * 0.35d + point * Math.PI * 2d / 10d;
            double peak = point % 2 == 0 ? 0.24d : 0d;
            Location at = crown.clone().add(
                    Math.cos(angle) * (0.5d + dissolve * 0.65d),
                    peak - (1d - assemble) * (0.8d + point * 0.08d) + dissolve * 0.45d,
                    Math.sin(angle) * (0.5d + dissolve * 0.65d)
            );
            dust(owner, at, point % 2 == 0 ? star : ice, point % 2 == 0 ? 1.05f : 0.8f,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }
        drawVerticalStar(owner, crown.clone().add(0d, 0.14d, 0d), horizontalSide(owner),
                0.33d * assemble, -phase * 0.58d, ice, star);
        if (step >= 28 && step < 63) {
            double flight = CosmeticAnimation.phaseProgress(step, 28, 63);
            double angle = phase * 1.9d;
            Location comet = crown.clone().add(
                    Math.cos(angle) * (0.55d + flight * 0.9d),
                    -0.25d + Math.sin(flight * Math.PI) * 1.15d,
                    Math.sin(angle) * (0.55d + flight * 0.9d)
            );
            spawnMoving(owner, comet, Particle.END_ROD,
                    new Vector(Math.cos(angle) * 0.03d, 0.05d, Math.sin(angle) * 0.03d),
                    null, PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }
        if (step >= 58) {
            double halo = CosmeticAnimation.smooth(
                    CosmeticAnimation.phaseProgress(step, 58, 78)
            );
            drawRing(owner, crown, 0.2d + halo * 1.25d, 14, phase, ice, 0.75f,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }
    }

    /** A faceted ruby/medallion that visibly turns instead of reading as loose dust. */
    private void drawVerticalGem(
            Player owner, Location centre, Vector side, double scale,
            double rotation, Color edge, Color highlight
    ) {
        Location[] points = new Location[4];
        for (int point = 0; point < points.length; point++) {
            double angle = rotation + point * Math.PI / 2d;
            points[point] = centre.clone()
                    .add(side.clone().multiply(Math.cos(angle) * scale))
                    .add(0d, Math.sin(angle) * scale, 0d);
        }
        for (int edgeIndex = 0; edgeIndex < points.length; edgeIndex++) {
            drawLine(owner, points[edgeIndex], points[(edgeIndex + 1) % points.length],
                    3, edgeIndex % 2 == 0 ? highlight : edge, 0.92f,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }
        drawLine(owner, points[0], points[2], 3, edge, 0.72f,
                PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        drawLine(owner, points[1], points[3], 3, highlight, 0.72f,
                PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
    }

    /** Alternating long and short rays form a readable rotating relic. */
    private void drawVerticalStar(
            Player owner, Location centre, Vector side, double radius,
            double rotation, Color edge, Color highlight
    ) {
        for (int ray = 0; ray < 8; ray++) {
            double angle = rotation + ray * Math.PI / 4d;
            double length = radius * (ray % 2 == 0 ? 1d : 0.58d);
            Location tip = centre.clone()
                    .add(side.clone().multiply(Math.cos(angle) * length))
                    .add(0d, Math.sin(angle) * length, 0d);
            drawLine(owner, centre, tip, ray % 2 == 0 ? 4 : 3,
                    ray % 2 == 0 ? highlight : edge, ray % 2 == 0 ? 0.9f : 0.72f,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }
    }

    /** A pointed crystal with a moving facet orbits in the same spirit as Amethyst Orbit. */
    private void drawOrbitingCrystal(
            Player owner, Location centre, double angle, double radius,
            double height, double length, Color colour
    ) {
        Vector radial = new Vector(Math.cos(angle), 0d, Math.sin(angle));
        Vector tangent = new Vector(-Math.sin(angle), 0d, Math.cos(angle));
        Location middle = centre.clone().add(radial.clone().multiply(radius)).add(0d, height, 0d);
        Location base = middle.clone().add(radial.clone().multiply(-length * 0.16d))
                .add(0d, -length * 0.42d, 0d);
        Location tip = middle.clone().add(radial.clone().multiply(length * 0.22d))
                .add(0d, length * 0.58d, 0d);
        drawLine(owner, base, tip, 5, colour, 0.98f,
                PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        drawLine(owner,
                middle.clone().add(tangent.clone().multiply(-length * 0.24d)),
                middle.clone().add(tangent.clone().multiply(length * 0.24d)),
                3, Color.fromRGB(210, 255, 185), 0.76f,
                PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        dust(owner, tip, Color.fromRGB(235, 255, 215), 0.72f,
                PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
    }

    private void drawTrail(
            Player owner, CosmeticCatalog.Definition definition, List<Location> history
    ) {
        if (history.size() < 2) {
            return;
        }
        if (definition.leaderboardOnly()) {
            drawLeaderboardTrail(owner, definition, history);
            return;
        }
        if (definition.secret()) {
            drawSecretTrail(owner, definition, history);
            return;
        }
        switch (definition.id()) {
            case "ember_trail" -> drawEmberTrail(owner, history);
            case "blood_trail" -> drawBloodTrail(owner, history);
            case "frost_trail" -> drawFrostTrail(owner, history);
            case "cherry_blossom_trail" -> drawCherryTrail(owner, history);
            case "drool_trail" -> drawDroolTrail(owner, history);
            case "ender_trail" -> drawEnderTrail(owner, history);
            case "prismatic_trail" -> drawPrismaticTrail(owner, history);
            case "shardstorm_wake" -> drawShardstormWake(owner, history);
            case "geode_bloom" -> drawGeodeBloom(owner, history);
            default -> { }
        }
    }

    /** Two chasing crescent blades cross the trail, then throw their tips outward. */
    private void drawShardstormWake(Player owner, List<Location> history) {
        Vector side = trailSide(history);
        Color violet = Color.fromRGB(159, 60, 255);
        Color bright = Color.fromRGB(225, 180, 255);
        for (int blade = 0; blade < 2; blade++) {
            int index = CosmeticAnimation.trailIndex(frame / 2L, history.size(), blade * 6);
            double sweep = CosmeticAnimation.pingPong(frame * 0.14d + blade * 0.5d);
            Location centre = trailPoint(history, index, 0.28d + blade * 0.34d);
            for (int point = -3; point <= 3; point++) {
                double t = point / 3d;
                Location at = centre.clone().add(side.clone().multiply(t * (0.45d + sweep * 0.55d)))
                        .add(0d, (1d - t * t) * (0.18d + sweep * 0.55d), 0d);
                dust(owner, at, point == 0 ? bright : violet, point == 0 ? 1.0f : 0.72f,
                        PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            }
            if (sweep > 0.9d) {
                spawnMoving(owner, centre.clone().add(side.clone().multiply(blade == 0 ? 0.9d : -0.9d)),
                        Particle.END_ROD, side.clone().multiply(blade == 0 ? 0.06d : -0.06d),
                        null, PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            }
        }
    }

    /** Geodes visibly sprout, open into six points, then dissolve as the next one grows. */
    private void drawGeodeBloom(Player owner, List<Location> history) {
        Vector side = trailSide(history);
        Color core = Color.fromRGB(116, 45, 185);
        Color tip = Color.fromRGB(234, 202, 255);
        for (int bloom = 0; bloom < 3; bloom++) {
            int index = CosmeticAnimation.trailIndex(frame / 3L, history.size(), bloom * 4);
            double open = CosmeticAnimation.pingPong(frame * 0.11d + bloom * 0.34d);
            Location centre = trailPoint(history, index, 0.12d + bloom * 0.08d);
            dust(owner, centre, core, 0.85f, PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            for (int shard = 0; shard < 6; shard++) {
                double angle = shard * Math.PI / 3d + frame * 0.08d;
                Location point = centre.clone()
                        .add(side.clone().multiply(Math.cos(angle) * open * 0.48d))
                        .add(0d, Math.sin(angle) * open * 0.32d + open * 0.2d, 0d);
                drawLine(owner, centre, point, 3, tip, 0.7f,
                        PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            }
        }
    }

    private void drawEmberTrail(Player owner, List<Location> history) {
        Vector side = trailSide(history);
        Color ember = Color.fromRGB(255, 95, 12);
        Color hot = Color.fromRGB(255, 215, 65);
        for (int comet = 0; comet < 3; comet++) {
            int index = CosmeticAnimation.trailIndex(frame / 2L, history.size(), comet * 4);
            int tailIndex = Math.min(history.size() - 1, index + 2);
            double pulse = 0.8d + CosmeticAnimation.pingPong(
                    frame * 0.12d + comet * 0.24d
            ) * 0.45d;
            Location head = trailPoint(history, index, 0.24d + comet * 0.07d);
            Location tail = trailPoint(history, tailIndex, 0.14d);
            Location left = tail.clone().add(side.clone().multiply(0.38d * pulse));
            Location right = tail.clone().add(side.clone().multiply(-0.38d * pulse));
            drawLine(owner, left, head, 4, comet == 0 ? hot : ember, 0.9f,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            drawLine(owner, right, head, 4, comet == 0 ? hot : ember, 0.9f,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            spawnMoving(owner, head, comet == 0 ? Particle.FIREWORK : Particle.SMALL_FLAME,
                    new Vector(0d, 0.055d + comet * 0.012d, 0d), null,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
        }
    }

    private void drawBloodTrail(Player owner, List<Location> history) {
        Vector side = trailSide(history);
        Color dark = Color.fromRGB(125, 0, 18);
        Color bright = Color.fromRGB(225, 15, 42);
        for (int claw = 0; claw < 3; claw++) {
            int index = CosmeticAnimation.trailIndex(frame / 2L, history.size(), claw * 4);
            double slash = CosmeticAnimation.pingPong(frame * 0.16d + claw * 0.23d);
            Location centre = trailPoint(history, index, 0.28d + claw * 0.16d)
                    .add(side.clone().multiply((slash - 0.5d) * 0.5d));
            Vector diagonal = side.clone().multiply(0.48d + slash * 0.28d)
                    .setY(claw % 2 == 0 ? 0.58d : -0.58d);
            drawLine(owner, centre.clone().subtract(diagonal), centre.clone().add(diagonal),
                    6, claw == 1 ? bright : dark, 1.05f,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            Location cut = centre.clone().add(diagonal.clone().multiply(0.55d));
            spawnMoving(owner, cut, claw == 1 ? Particle.SWEEP_ATTACK : Particle.DUST,
                    new Vector(0d, -0.035d, 0d),
                    claw == 1 ? null : new Particle.DustOptions(bright, 0.75f),
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
        }
    }

    private void drawFrostTrail(Player owner, List<Location> history) {
        Vector side = trailSide(history);
        Color ice = Color.fromRGB(175, 225, 255);
        for (int crystal = 0; crystal < 2; crystal++) {
            int index = CosmeticAnimation.trailIndex(frame / 3L, history.size(), crystal * 6);
            double bloom = CosmeticAnimation.pingPong(frame * 0.11d + crystal * 0.37d);
            Location centre = trailPoint(history, index, 0.42d + crystal * 0.16d);
            drawTrailSnowflake(owner, centre, side, ice,
                    0.28d + bloom * 0.42d, frame * 0.22d + crystal);
            spawnMoving(owner, centre, Particle.SNOWFLAKE,
                    new Vector(0d, 0.035d, 0d), null,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
        }
    }

    private void drawCherryTrail(Player owner, List<Location> history) {
        Vector side = trailSide(history);
        Color pink = Color.fromRGB(255, 145, 205);
        for (int bloom = 0; bloom < 3; bloom++) {
            int index = CosmeticAnimation.trailIndex(frame / 2L, history.size(), bloom * 4);
            double rotation = frame * 0.42d + bloom * Math.PI * 2d / 3d;
            double open = 0.22d + CosmeticAnimation.pingPong(
                    frame * 0.13d + bloom * 0.31d
            ) * 0.34d;
            Location centre = trailPoint(history, index, 0.38d + bloom * 0.15d);
            for (int petal = 0; petal < 4; petal++) {
                double angle = rotation + petal * Math.PI / 2d;
                Location at = centre.clone()
                        .add(side.clone().multiply(Math.cos(angle) * open))
                        .add(0d, Math.sin(angle) * open, 0d);
                spawnMoving(owner, at, Particle.CHERRY_LEAVES,
                        side.clone().multiply(Math.cos(angle) * 0.025d)
                                .setY(Math.sin(angle) * 0.025d - 0.012d),
                        null, PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            }
            dust(owner, centre, pink, 0.7f,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
        }
    }

    private void drawDroolTrail(Player owner, List<Location> history) {
        Color aqua = Color.fromRGB(45, 220, 210);
        Vector side = trailSide(history);
        for (int bubble = 0; bubble < 2; bubble++) {
            int index = CosmeticAnimation.trailIndex(frame / 2L, history.size(), bubble * 6);
            double cycle = CosmeticAnimation.progress(frame + bubble * 4L, 10);
            double bounce = Math.sin(cycle * Math.PI);
            Location centre = trailPoint(history, index, 0.12d + bounce * 0.78d)
                    .add(side.clone().multiply(bubble == 0 ? -0.24d : 0.24d));
            double radius = 0.18d + bounce * 0.18d;
            for (int point = 0; point < 6; point++) {
                double angle = point * Math.PI / 3d + frame * 0.18d;
                Location at = centre.clone()
                        .add(side.clone().multiply(Math.cos(angle) * radius))
                        .add(0d, Math.sin(angle) * radius, 0d);
                dust(owner, at, aqua, 0.72f,
                        PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            }
            if (cycle > 0.82d) {
                drawRing(owner, trailPoint(history, index, 0.06d),
                        0.25d + (cycle - 0.82d) * 2.2d, 10,
                        frame * 0.2d, aqua, 0.65f,
                        PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
                spawnMoving(owner, centre, Particle.FALLING_WATER,
                        new Vector(0d, -0.08d, 0d), null,
                        PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            }
        }
    }

    private void drawEnderTrail(Player owner, List<Location> history) {
        Vector side = trailSide(history);
        Color purple = Color.fromRGB(145, 35, 220);
        for (int rift = 0; rift < 2; rift++) {
            int index = CosmeticAnimation.trailIndex(frame / 3L, history.size(), rift * 6);
            double open = 0.2d + CosmeticAnimation.pingPong(
                    frame * 0.09d + rift * 0.43d
            ) * 0.52d;
            Location centre = trailPoint(history, index, 0.68d);
            for (int point = 0; point < 12; point++) {
                double angle = point * Math.PI / 6d + frame * 0.16d;
                Location at = centre.clone()
                        .add(side.clone().multiply(Math.cos(angle) * open * 0.62d))
                        .add(0d, Math.sin(angle) * open, 0d);
                if ((point + frame) % 3L == 0L) {
                    spawnMoving(owner, at, Particle.REVERSE_PORTAL,
                            centre.toVector().subtract(at.toVector()).multiply(0.04d),
                            null, PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
                } else {
                    dust(owner, at, purple, 0.78f,
                            PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
                }
            }
            if (rift == 0) {
                spawnMoving(owner, centre, Particle.END_ROD,
                        history.get(0).toVector().subtract(centre.toVector())
                                .normalize().multiply(0.075d),
                        null, PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            }
        }
    }

    private void drawPrismaticTrail(Player owner, List<Location> history) {
        Vector side = trailSide(history);
        int index = CosmeticAnimation.trailIndex(frame / 3L, history.size(), 0);
        Location prism = trailPoint(history, index, 0.68d);
        double turn = Math.sin(frame * 0.18d) * 0.16d;
        double scale = 0.58d + CosmeticAnimation.pingPong(frame * 0.075d) * 0.16d;
        Location top = prism.clone().add(side.clone().multiply(Math.sin(turn) * scale * 0.2d))
                .add(0d, scale, 0d);
        Location left = prism.clone().add(side.clone().multiply(-scale * Math.cos(turn)))
                .add(0d, -scale * 0.5d, 0d);
        Location right = prism.clone().add(side.clone().multiply(scale * Math.cos(turn)))
                .add(0d, -scale * 0.5d, 0d);
        Color glass = Color.fromRGB(220, 250, 255);
        Color glassEdge = Color.fromRGB(135, 210, 255);
        drawLine(owner, top, left, 4, glass, 0.86f,
                PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
        drawLine(owner, left, right, 5, glassEdge, 0.78f,
                PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
        drawLine(owner, right, top, 4, glass, 0.86f,
                PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);

        Location incoming = trailPoint(history, index + 2, 0.68d);
        drawLine(owner, incoming, prism, 4, Color.fromRGB(255, 255, 235), 0.7f,
                PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
        Color[] spectrum = {
                Color.fromRGB(255, 65, 70),
                Color.fromRGB(255, 205, 45),
                Color.fromRGB(45, 220, 255),
                Color.fromRGB(185, 75, 255)
        };
        double fan = 0.24d + CosmeticAnimation.pingPong(frame * 0.08d) * 0.18d;
        for (int beam = 0; beam < spectrum.length; beam++) {
            Location target = trailPoint(history, index + 5 + beam / 2, 0.56d)
                    .add(side.clone().multiply((beam - 1.5d) * fan))
                    .add(0d, (beam % 2 == 0 ? -0.12d : 0.12d), 0d);
            drawLine(owner, prism, target, 5, spectrum[beam], 0.76f,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            dust(owner, target, spectrum[beam], 0.7f,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
        }
        spawnMoving(owner, prism, Particle.ELECTRIC_SPARK,
                new Vector(0d, 0.045d, 0d), null,
                PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
    }

    private void drawKillEffect(
            Player owner, CosmeticCatalog.Definition definition, Location centre
    ) {
        KillAccent accent = killAccent(definition);
        animateAggressiveKillAccent(
                owner, centre, accent.colour(), accent.impactFrame(), accent.frames()
        );
        if (definition.leaderboardOnly()) {
            drawLeaderboardKill(owner, definition, centre);
            return;
        }
        if (definition.secret()) {
            drawSecretKill(owner, definition, centre);
            return;
        }
        switch (definition.id()) {
            case "blood_burst" -> animateBloodKill(owner, centre);
            case "frozen_shatter" -> animateFrozenKill(owner, centre);
            case "shining_light" -> animateShiningKill(owner, centre);
            case "void_collapse" -> animateVoidKill(owner, centre);
            case "soul_requiem" -> animateSoulKill(owner, centre);
            case "crystal_guillotine" -> animateCrystalGuillotine(owner, centre);
            case "violet_detonation" -> animateVioletDetonation(owner, centre);
            default -> { }
        }
    }

    /**
     * Every execution gets a readable attack: closing blades, a hard impact, then
     * an expanding shrapnel wave. The cosmetic's own sequence still supplies its identity.
     */
    private void animateAggressiveKillAccent(
            Player owner, Location centre, Color colour, int impactFrame, int frames
    ) {
        int chargeStart = Math.max(0, impactFrame - 7);
        animate(owner, centre, frames, 2L, step -> {
            if (step < chargeStart) {
                return;
            }
            if (step < impactFrame) {
                double charge = CosmeticAnimation.smooth(
                        (step - chargeStart) / (double) (impactFrame - chargeStart)
                );
                double radius = 2.65d - charge * 2.05d;
                for (int blade = 0; blade < 4; blade++) {
                    double angle = blade * Math.PI / 2d + step * 0.2d;
                    Location tip = centre.clone().add(
                            Math.cos(angle) * radius,
                            -0.55d + blade % 2 * 1.75d,
                            Math.sin(angle) * radius
                    );
                    Location edge = tip.clone().add(
                            Math.cos(angle + Math.PI / 2d) * (0.65d + charge * 0.45d),
                            blade % 2 == 0 ? 0.75d : -0.75d,
                            Math.sin(angle + Math.PI / 2d) * (0.65d + charge * 0.45d)
                    );
                    drawLine(owner, tip, edge, 5, colour, 1.15f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                if ((step - chargeStart) % 2 == 0) {
                    drawRing(owner, centre.clone().add(0d, -0.62d, 0d), radius,
                            16, -step * 0.35d, colour, 0.9f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                if (step == chargeStart) {
                    sound(owner, centre, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1.15f, 0.45f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                return;
            }
            if (step == impactFrame) {
                spawn(owner, centre, Particle.SONIC_BOOM, 1,
                        0d, 0d, 0d, 0d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                spawn(owner, centre, Particle.FLASH, 2,
                        0.15d, 0.2d, 0.15d, 0d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                spawn(owner, centre, Particle.SWEEP_ATTACK, 4,
                        0.55d, 0.65d, 0.55d, 0d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                sound(owner, centre, Sound.ENTITY_GENERIC_EXPLODE, 1.45f, 0.62f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                sound(owner, centre, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.25f, 0.55f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
            int aftermathFrames = Math.max(1, frames - impactFrame - 1);
            double blast = CosmeticAnimation.easeOutBack(
                    (step - impactFrame) / (double) aftermathFrames
            );
            for (int shard = 0; shard < 10; shard++) {
                double angle = shard * Math.PI / 5d + step * 0.11d;
                Location at = centre.clone().add(
                        Math.cos(angle) * blast * (1.7d + shard % 2 * 0.55d),
                        -0.35d + (shard % 5) * 0.42d + blast * 0.45d,
                        Math.sin(angle) * blast * (1.7d + shard % 2 * 0.55d)
                );
                spawnMoving(owner, at, shard % 3 == 0 ? Particle.FIREWORK : Particle.DUST,
                        new Vector(Math.cos(angle) * 0.075d, 0.035d,
                                Math.sin(angle) * 0.075d),
                        shard % 3 == 0 ? null : new Particle.DustOptions(colour, 0.95f),
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
            if ((step - impactFrame) % 2 == 0) {
                drawRing(owner, centre.clone().add(0d, -0.5d, 0d),
                        0.35d + blast * 2.8d, 20, step * 0.31d, colour, 1.15f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
            if (step == frames - 1) {
                drawRing(owner, centre.clone().add(0d, 0.15d, 0d),
                        3.1d, 28, -step * 0.25d, colour, 1.3f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                sound(owner, centre, Sound.ENTITY_WITHER_BREAK_BLOCK, 1f, 0.72f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
        });
    }

    private static KillAccent killAccent(CosmeticCatalog.Definition definition) {
        if (definition.leaderboardOnly()) {
            return new KillAccent(podiumColour(definition.leaderboardRank()), 21, 34);
        }
        return switch (definition.id()) {
            case "blood_burst" -> new KillAccent(Color.fromRGB(225, 12, 38), 10, 24);
            case "frozen_shatter" -> new KillAccent(Color.fromRGB(175, 225, 255), 13, 25);
            case "shining_light" -> new KillAccent(Color.fromRGB(255, 205, 70), 20, 28);
            case "void_collapse", "event_horizon" ->
                    new KillAccent(Color.fromRGB(135, 20, 190),
                            definition.secret() ? 23 : 16, definition.secret() ? 38 : 27);
            case "soul_requiem" -> new KillAccent(Color.fromRGB(30, 210, 225), 20, 29);
            case "crystal_guillotine" -> new KillAccent(Color.fromRGB(205, 135, 255), 18, 34);
            case "violet_detonation" -> new KillAccent(Color.fromRGB(160, 45, 255), 16, 36);
            case "reapers_verdict" -> new KillAccent(Color.fromRGB(185, 235, 245), 18, 35);
            case "divine_rupture" -> new KillAccent(Color.fromRGB(255, 215, 70), 20, 38);
            case "crystalline_extinction" ->
                    new KillAccent(Color.fromRGB(185, 105, 255), 22, 40);
            default -> new KillAccent(Color.WHITE, 14, 26);
        };
    }

    private record KillAccent(Color colour, int impactFrame, int frames) { }

    /** Builds an enormous falling crystal blade, slams it, then fractures it sideways. */
    private void animateCrystalGuillotine(Player owner, Location centre) {
        Color edge = Color.fromRGB(235, 205, 255);
        Color core = Color.fromRGB(128, 44, 205);
        animate(owner, centre, 34, 2L, step -> {
            if (step <= 18) {
                double fall = CosmeticAnimation.smooth(step / 18d);
                Location tip = centre.clone().add(0d, 4.2d - fall * 4.0d, 0d);
                Location hilt = tip.clone().add(0d, 2.0d, 0d);
                drawLine(owner, tip, hilt, 10, edge, 1.25f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                drawLine(owner, tip.clone().add(-0.22d, 0.45d, 0d), hilt, 8, core, 0.9f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                if (step == 18) {
                    sound(owner, centre, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.4f, 0.45f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                return;
            }
            double fracture = CosmeticAnimation.easeOutBack((step - 18d) / 15d);
            for (int shard = 0; shard < 12; shard++) {
                double angle = shard * Math.PI / 6d;
                Location at = centre.clone().add(Math.cos(angle) * fracture * 2.8d,
                        -0.35d + (shard % 4) * 0.55d + fracture * 0.7d,
                        Math.sin(angle) * fracture * 2.8d);
                spawnMoving(owner, at, shard % 3 == 0 ? Particle.END_ROD : Particle.DUST,
                        new Vector(Math.cos(angle) * 0.08d, 0.04d, Math.sin(angle) * 0.08d),
                        shard % 3 == 0 ? null : new Particle.DustOptions(core, 0.95f),
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
        });
    }

    /** Crushes three counter-rotating cores together before a multi-stage violet blast. */
    private void animateVioletDetonation(Player owner, Location centre) {
        Color violet = Color.fromRGB(177, 46, 255);
        Color hot = Color.fromRGB(245, 205, 255);
        animate(owner, centre, 36, 2L, step -> {
            if (step < 16) {
                double crush = 1d - CosmeticAnimation.smooth(step / 15d);
                for (int coreIndex = 0; coreIndex < 3; coreIndex++) {
                    double angle = coreIndex * Math.PI * 2d / 3d + step * (0.45d + coreIndex * 0.06d);
                    Location coreAt = centre.clone().add(Math.cos(angle) * (0.3d + crush * 2d),
                            Math.sin(angle * 2d) * 0.65d,
                            Math.sin(angle) * (0.3d + crush * 2d));
                    drawVerticalGem(owner, coreAt, horizontalSide(owner), 0.28d + crush * 0.3d,
                            angle, violet, hot);
                }
                return;
            }
            double blast = CosmeticAnimation.easeOutBack((step - 16d) / 19d);
            drawRing(owner, centre.clone().add(0d, -0.45d, 0d), 0.3d + blast * 3.7d,
                    24, step * 0.28d, step % 2 == 0 ? hot : violet, 1.15f,
                    PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            drawRing(owner, centre.clone().add(0d, 0.45d, 0d), 0.2d + blast * 2.5d,
                    18, -step * 0.4d, violet, 0.9f,
                    PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            if (step == 16 || step == 23 || step == 31) {
                spawn(owner, centre, step == 16 ? Particle.FLASH : Particle.SONIC_BOOM,
                        1, 0d, 0d, 0d, 0d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                sound(owner, centre, Sound.ENTITY_GENERIC_EXPLODE, 1.2f,
                        0.55f + (step - 16) * 0.035f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
        });
    }

    /** A contracting heartbeat becomes crossed blades, then launches a blood fountain. */
    private void animateBloodKill(Player owner, Location centre) {
        Color blood = Color.fromRGB(190, 0, 28);
        animate(owner, centre, 22, 2L, step -> {
            if (step < 6) {
                double contraction = 1d - CosmeticAnimation.smooth(step / 5d);
                drawRing(owner, centre, 0.35d + contraction * 1.35d,
                        16, step * 0.35d, blood, 1.1f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                if (step == 0) {
                    sound(owner, centre, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.9f, 0.55f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                return;
            }
            if (step < 12) {
                double slash = CosmeticAnimation.easeOutBack((step - 6d) / 5d);
                for (double direction : new double[]{-1d, 1d}) {
                    Location start = centre.clone().add(-1.25d * direction, -0.55d, 0d);
                    Location end = centre.clone().add(
                            1.25d * direction * slash, 1.35d * slash, 0d
                    );
                    drawLine(owner, start, end, 9, blood, 1.2f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                if (step == 9) {
                    spawn(owner, centre, Particle.SWEEP_ATTACK, 2,
                            0.1d, 0.25d, 0.1d, 0d, null,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                    sound(owner, centre, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.1f, 0.65f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                return;
            }
            double release = CosmeticAnimation.phaseProgress(step, 12, 21);
            for (int jet = 0; jet < 8; jet++) {
                double angle = jet * Math.PI / 4d + step * 0.13d;
                double distance = 0.25d + release * (0.85d + (jet % 2) * 0.45d);
                Location drop = centre.clone().add(
                        Math.cos(angle) * distance,
                        Math.sin(release * Math.PI) * (1.15d + jet % 3 * 0.18d),
                        Math.sin(angle) * distance
                );
                spawnMoving(owner, drop, Particle.DUST,
                        new Vector(Math.cos(angle) * 0.04d, -0.07d, Math.sin(angle) * 0.04d),
                        new Particle.DustOptions(blood, 0.9f),
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
            if (step == 20) {
                drawRing(owner, centre, 2.2d, 24, 0d, blood, 1.25f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                sound(owner, centre, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.2f, 0.7f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
        });
    }

    /** Ice bars grow into a rotating prison, stop dead, and explode as aimed shards. */
    private void animateFrozenKill(Player owner, Location centre) {
        Color ice = Color.fromRGB(175, 225, 255);
        animate(owner, centre, 24, 2L, step -> {
            if (step < 9) {
                double growth = CosmeticAnimation.smooth(step / 8d);
                for (int pillar = 0; pillar < 6; pillar++) {
                    double angle = pillar * Math.PI / 3d + step * 0.08d;
                    Location bottom = centre.clone().add(
                            Math.cos(angle) * 0.9d, -0.65d, Math.sin(angle) * 0.9d
                    );
                    Location top = bottom.clone().add(0d, growth * 2.2d, 0d);
                    drawLine(owner, bottom, top, 6, ice, 0.9f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                return;
            }
            if (step < 14) {
                drawRing(owner, centre.clone().add(0d, -0.58d, 0d), 0.9d,
                        18, step * 0.32d, ice, 1f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                drawRing(owner, centre.clone().add(0d, 1.55d, 0d), 0.9d,
                        18, -step * 0.32d, ice, 1f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                if (step == 13) {
                    spawn(owner, centre, Particle.FLASH, 1, 0d, 0d, 0d, 0d, null,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                    sound(owner, centre, Sound.BLOCK_GLASS_BREAK, 1.25f, 0.55f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                return;
            }
            double shatter = CosmeticAnimation.easeOutBack((step - 14d) / 9d);
            for (int shard = 0; shard < 12; shard++) {
                double angle = shard * Math.PI / 6d + (shard % 2) * 0.2d;
                double distance = shatter * (1d + shard % 3 * 0.35d);
                Location at = centre.clone().add(
                        Math.cos(angle) * distance,
                        -0.35d + (shard % 4) * 0.52d + shatter * 0.35d,
                        Math.sin(angle) * distance
                );
                spawnMoving(owner, at, shard % 3 == 0 ? Particle.SNOWFLAKE : Particle.DUST,
                        new Vector(Math.cos(angle) * 0.065d, 0.02d, Math.sin(angle) * 0.065d),
                        shard % 3 == 0 ? null : new Particle.DustOptions(ice, 0.8f),
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
        });
    }

    /** Beams descend in order, unfold into wings, and carry a final star upward. */
    private void animateShiningKill(Player owner, Location centre) {
        Color gold = Color.fromRGB(255, 205, 70);
        Color white = Color.fromRGB(255, 250, 220);
        animate(owner, centre, 26, 2L, step -> {
            if (step < 9) {
                for (int ray = 0; ray < 6; ray++) {
                    double delay = Math.max(0d, step - ray * 0.8d);
                    double descent = CosmeticAnimation.smooth(delay / 5d);
                    double angle = ray * Math.PI / 3d;
                    Location at = centre.clone().add(
                            Math.cos(angle) * 0.72d,
                            3d - descent * 2.8d,
                            Math.sin(angle) * 0.72d
                    );
                    spawnMoving(owner, at, Particle.END_ROD, new Vector(0d, -0.08d, 0d),
                            null, PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                return;
            }
            if (step < 18) {
                double unfold = CosmeticAnimation.easeOutBack((step - 9d) / 8d);
                for (double side : new double[]{-1d, 1d}) {
                    for (int feather = 0; feather < 7; feather++) {
                        double progress = feather / 6d;
                        Location at = centre.clone().add(
                                side * unfold * progress * 1.8d,
                                0.15d + Math.sin(progress * Math.PI) * 1.25d,
                                0.18d + progress * 0.22d
                        );
                        dust(owner, at, feather % 2 == 0 ? white : gold, 0.95f,
                                PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                    }
                }
                if (step == 15) {
                    sound(owner, centre, Sound.BLOCK_BEACON_ACTIVATE, 1.15f, 1.35f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                return;
            }
            double ascend = CosmeticAnimation.smooth((step - 18d) / 7d);
            Location star = centre.clone().add(0d, ascend * 3.4d, 0d);
            spawnMoving(owner, star, Particle.FIREWORK, new Vector(0d, 0.09d, 0d), null,
                    PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            drawRing(owner, centre, 0.25d + ascend * 2d, 20,
                    step * 0.22d, gold, 0.9f,
                    PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            if (step == 25) {
                spawn(owner, star, Particle.FLASH, 1, 0d, 0d, 0d, 0d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                sound(owner, centre, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.1f, 1.25f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
        });
    }

    /** An accretion disk visibly consumes its orbit, pauses, then fires an inverted wave. */
    private void animateVoidKill(Player owner, Location centre) {
        Color voidPurple = Color.fromRGB(85, 10, 125);
        animate(owner, centre, 26, 2L, step -> {
            if (step < 13) {
                double collapse = CosmeticAnimation.smooth(step / 12d);
                double radius = 1.9d - collapse * 1.6d;
                for (int point = 0; point < 18; point++) {
                    double angle = point * Math.PI * 2d / 18d + step * 0.48d;
                    Location at = centre.clone().add(
                            Math.cos(angle) * radius,
                            Math.sin(angle * 3d) * 0.18d * (1d - collapse),
                            Math.sin(angle) * radius * 0.42d
                    );
                    Vector inward = centre.toVector().subtract(at.toVector());
                    if (inward.lengthSquared() > 0.001d) {
                        inward.normalize().multiply(0.075d);
                    }
                    spawnMoving(owner, at, point % 4 == 0 ? Particle.REVERSE_PORTAL : Particle.DUST,
                            inward, point % 4 == 0 ? null
                                    : new Particle.DustOptions(voidPurple, 0.85f),
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                if (step == 0) {
                    sound(owner, centre, Sound.BLOCK_END_PORTAL_SPAWN, 0.85f, 0.55f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                return;
            }
            if (step < 16) {
                spawn(owner, centre, Particle.SQUID_INK, 2, 0.08d, 0.08d, 0.08d,
                        0d, null, PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                return;
            }
            double rupture = CosmeticAnimation.easeOutBack((step - 16d) / 9d);
            drawRing(owner, centre, 0.15d + rupture * 2.5d,
                    24, -step * 0.4d, voidPurple, 1.25f,
                    PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            if (step == 16) {
                spawn(owner, centre, Particle.SONIC_BOOM, 1, 0d, 0d, 0d, 0d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                sound(owner, centre, Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 0.72f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
            for (int jet = 0; jet < 4; jet++) {
                double angle = jet * Math.PI / 2d + step * 0.16d;
                Location at = centre.clone().add(
                        Math.cos(angle) * rupture * 1.4d,
                        (jet - 1.5d) * rupture * 0.7d,
                        Math.sin(angle) * rupture * 1.4d
                );
                spawnMoving(owner, at, Particle.DRAGON_BREATH,
                        new Vector(Math.cos(angle) * 0.06d, jet * 0.015d,
                                Math.sin(angle) * 0.06d), null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
        });
    }

    /** A soul chain coils around the victim, lifts five spirits, then opens a lantern gate. */
    private void animateSoulKill(Player owner, Location centre) {
        Color cyan = Color.fromRGB(30, 210, 225);
        animate(owner, centre, 28, 2L, step -> {
            if (step < 11) {
                double height = CosmeticAnimation.smooth(step / 10d) * 2.3d;
                for (int link = 0; link < 12; link++) {
                    double progress = link / 11d;
                    if (progress * 2.3d > height) {
                        continue;
                    }
                    double angle = step * 0.36d + progress * Math.PI * 4d;
                    Location at = centre.clone().add(
                            Math.cos(angle) * 0.72d,
                            -0.55d + progress * 2.3d,
                            Math.sin(angle) * 0.72d
                    );
                    dust(owner, at, cyan, link % 2 == 0 ? 1f : 0.7f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                return;
            }
            if (step < 20) {
                double lift = CosmeticAnimation.smooth((step - 11d) / 8d);
                for (int soul = 0; soul < 5; soul++) {
                    double angle = step * 0.42d + soul * Math.PI * 2d / 5d;
                    Location at = centre.clone().add(
                            Math.cos(angle) * (0.75d - lift * 0.35d),
                            -0.25d + lift * (2d + soul * 0.18d),
                            Math.sin(angle) * (0.75d - lift * 0.35d)
                    );
                    spawnMoving(owner, at, soul % 2 == 0 ? Particle.SCULK_SOUL : Particle.SOUL,
                            new Vector(0d, 0.06d, 0d), null,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                if (step == 12) {
                    sound(owner, centre, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 0.75f, 1.3f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                return;
            }
            double gate = CosmeticAnimation.easeOutBack((step - 20d) / 7d);
            drawRing(owner, centre.clone().add(0d, 1.15d, 0d),
                    gate * 1.25d, 20, step * 0.28d, cyan, 1.05f,
                    PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            if (step == 26) {
                spawn(owner, centre.clone().add(0d, 1.15d, 0d), Particle.SCULK_SOUL,
                        12, 0.35d, 0.35d, 0.35d, 0.04d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                sound(owner, centre, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1f, 1.5f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
        });
    }

    /** A crown, two royal rings, and wings make podium holders unmistakable at range. */
    private void drawLeaderboardAura(
            Player owner, CosmeticCatalog.Definition definition, Location centre,
            double phase, int step
    ) {
        Color colour = podiumColour(definition.leaderboardRank());
        int rank = definition.leaderboardRank();
        if (step < 24) {
            double seal = CosmeticAnimation.easeOutBack(step / 23d);
            drawRing(owner, centre.clone().add(0d, -0.9d, 0d),
                    seal * (1.65d - rank * 0.12d), rank == 1 ? 24 : 18,
                    phase, colour, rank == 1 ? 1.2f : 1f,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
            for (int ray = 0; ray < 6; ray++) {
                double angle = ray * Math.PI / 3d + phase * 0.18d;
                Location start = centre.clone().add(0d, -0.89d, 0d);
                Location end = start.clone().add(
                        Math.cos(angle) * seal * 1.25d, 0d,
                        Math.sin(angle) * seal * 1.25d
                );
                drawLine(owner, start, end, 4, colour, 0.75f,
                        PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
            }
        }

        Location crown = centre.clone().add(0d, 1.15d, 0d);
        double crownAssembly = CosmeticAnimation.easeOutBack(
                CosmeticAnimation.phaseProgress(step, 10, 34)
        );
        double crownRelease = CosmeticAnimation.smooth(
                CosmeticAnimation.phaseProgress(step, 68, 79)
        );
        for (int point = 0; point < 12; point++) {
            double angle = phase * 0.42d + point * Math.PI * 2d / 12d;
            double peak = point % 3 == 0 ? 0.26d : 0d;
            Location at = crown.clone().add(
                    Math.cos(angle) * (0.52d + crownRelease * 0.75d),
                    peak - (1d - crownAssembly) * (1.3d + point * 0.06d)
                            + crownRelease * 0.7d,
                    Math.sin(angle) * (0.52d + crownRelease * 0.75d)
            );
            dust(owner, at, colour, rank == 1 ? 1.55f : 1.3f,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
            if (rank == 1 && point % 3 == 0) {
                spawn(owner, at, Particle.END_ROD, 1, 0d, 0d, 0d, 0d, null,
                        PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
            }
        }

        double unfold = CosmeticAnimation.easeOutBack(
                CosmeticAnimation.phaseProgress(step, 24, 52)
        );
        if (step >= 24 && step < 72) {
            double flap = Math.sin((step - 24d) * 0.2d) * 0.14d;
            drawPodiumWings(owner, centre, colour, phase, rank, unfold, flap);
        }
        if (step >= 48 && step < 70) {
            double ascent = CosmeticAnimation.phaseProgress(step, 48, 70);
            Location regalia = centre.clone().add(
                    Math.sin(phase * 1.7d) * 0.42d,
                    -0.7d + ascent * 2.9d,
                    Math.cos(phase * 1.7d) * 0.42d
            );
            spawnMoving(owner, regalia,
                    rank == 1 ? Particle.TOTEM_OF_UNDYING : Particle.FIREWORK,
                    new Vector(0d, 0.055d, 0d), null,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }
        if (step >= 62) {
            double wave = CosmeticAnimation.smooth(
                    CosmeticAnimation.phaseProgress(step, 62, 79)
            );
            drawRing(owner, centre.clone().add(0d, 0.15d, 0d),
                    0.2d + wave * (2.1d - rank * 0.16d), rank == 1 ? 28 : 20,
                    -phase, colour, 1.05f,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }
    }

    private void drawPodiumWings(
            Player owner, Location centre, Color colour, double phase, int rank,
            double unfold, double flap
    ) {
        Vector backwards = owner.getLocation().getDirection().setY(0d);
        if (backwards.lengthSquared() < 0.001d) {
            backwards = new Vector(0d, 0d, 1d);
        }
        backwards.normalize().multiply(-1d);
        Vector side = new Vector(-backwards.getZ(), 0d, backwards.getX()).normalize();
        int feathers = 9 - rank;
        for (double direction : new double[]{-1d, 1d}) {
            for (int point = 0; point < feathers; point++) {
                double progress = point / (double) (feathers - 1);
                Location at = centre.clone()
                        .add(backwards.clone().multiply(0.22d + progress * 0.45d))
                        .add(side.clone().multiply(
                                direction * progress * (1.75d - rank * 0.15d) * unfold
                        ))
                        .add(0d, 0.12d + Math.sin(progress * Math.PI) * 1.25d * unfold
                                + flap * (1d - progress) + Math.sin(phase + point) * 0.04d, 0d);
                dust(owner, at, colour, 1.2f,
                        PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                if (rank == 1 && point == feathers - 1) {
                    spawnMoving(owner, at, Particle.END_ROD,
                            side.clone().multiply(direction * 0.035d).setY(0.025d), null,
                            PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                }
            }
        }
    }

    /** Royal crests chase one another down the path instead of forming a particle ribbon. */
    private void drawLeaderboardTrail(
            Player owner, CosmeticCatalog.Definition definition, List<Location> history
    ) {
        int rank = definition.leaderboardRank();
        Color colour = podiumColour(rank);
        Vector side = trailSide(history);
        for (int crest = 0; crest < 3; crest++) {
            int index = CosmeticAnimation.trailIndex(frame / 3L, history.size(), crest * 4);
            double pulse = 0.72d + CosmeticAnimation.pingPong(
                    frame * 0.1d + crest * 0.28d
            ) * 0.4d;
            Location centre = trailPoint(history, index, 0.34d + crest * 0.2d);
            drawTrailCrown(owner, centre, side, colour,
                    pulse * (1.05d - rank * 0.08d));
            if (crest == 0) {
                spawnMoving(owner, centre,
                        rank == 1 ? Particle.TOTEM_OF_UNDYING : Particle.FIREWORK,
                        new Vector(0d, 0.06d, 0d), null,
                        PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            }
        }
    }

    /** A seal, descending crown, royal execution, and winged trophy play in sequence. */
    private void drawLeaderboardKill(
            Player owner, CosmeticCatalog.Definition definition, Location centre
    ) {
        int rank = definition.leaderboardRank();
        Color colour = podiumColour(rank);
        animate(owner, centre, 32, 2L, step -> {
            if (step < 9) {
                double seal = CosmeticAnimation.easeOutBack(step / 8d);
                drawRing(owner, centre.clone().add(0d, -0.65d, 0d),
                        seal * (1.8d - rank * 0.12d), rank == 1 ? 28 : 20,
                        step * 0.4d, colour, rank == 1 ? 1.35f : 1.1f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                for (int spoke = 0; spoke < 6; spoke++) {
                    double angle = spoke * Math.PI / 3d + step * 0.12d;
                    Location edge = centre.clone().add(
                            Math.cos(angle) * seal * 1.35d, -0.64d,
                            Math.sin(angle) * seal * 1.35d
                    );
                    drawLine(owner, centre.clone().add(0d, -0.64d, 0d), edge,
                            4, colour, 0.8f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                return;
            }
            if (step < 18) {
                double descend = CosmeticAnimation.smooth((step - 9d) / 8d);
                Location crown = centre.clone().add(0d, 3.15d - descend * 1.85d, 0d);
                for (int point = 0; point < 10; point++) {
                    double angle = point * Math.PI / 5d + step * 0.18d;
                    Location at = crown.clone().add(
                            Math.cos(angle) * 0.58d,
                            point % 2 == 0 ? 0.28d : 0d,
                            Math.sin(angle) * 0.58d
                    );
                    dust(owner, at, colour, rank == 1 ? 1.45f : 1.2f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                Location bladeTop = centre.clone().add(0d, 3.8d - descend * 0.8d, 0d);
                Location bladeBottom = bladeTop.clone().add(0d, -1.25d, 0d);
                drawLine(owner, bladeTop, bladeBottom, 8, colour, 1.25f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                return;
            }
            if (step < 22) {
                double strike = CosmeticAnimation.smooth((step - 18d) / 3d);
                Location blade = centre.clone().add(0d, 2.5d - strike * 3.05d, 0d);
                spawnMoving(owner, blade, Particle.END_ROD, new Vector(0d, -0.14d, 0d), null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                if (step == 21) {
                    spawn(owner, centre.clone().add(0d, -0.45d, 0d),
                            rank == 1 ? Particle.SONIC_BOOM : Particle.FLASH,
                            1, 0d, 0d, 0d, 0d, null,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                    sound(owner, centre, Sound.ENTITY_LIGHTNING_BOLT_THUNDER,
                            1.15f, 0.62f + rank * 0.1f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                return;
            }
            double triumph = CosmeticAnimation.easeOutBack((step - 22d) / 9d);
            drawRing(owner, centre, 0.25d + triumph * (2.65d - rank * 0.18d),
                    rank == 1 ? 32 : 24, step * 0.24d, colour, 1.3f,
                    PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            for (double direction : new double[]{-1d, 1d}) {
                for (int feather = 0; feather < 8 - rank; feather++) {
                    double progress = feather / (double) (7 - rank);
                    Location at = centre.clone().add(
                            direction * progress * triumph * (2.1d - rank * 0.12d),
                            0.1d + Math.sin(progress * Math.PI) * 1.45d * triumph,
                            0.3d + progress * 0.25d
                    );
                    dust(owner, at, colour, 1.15f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
            }
            Location trophy = centre.clone().add(0d, triumph * 3d, 0d);
            spawnMoving(owner, trophy,
                    rank == 1 ? Particle.TOTEM_OF_UNDYING : Particle.FIREWORK,
                    new Vector(0d, 0.08d, 0d), null,
                    PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            if (step == 31) {
                sound(owner, centre, Sound.UI_TOAST_CHALLENGE_COMPLETE,
                        1.35f, 0.72f + rank * 0.13f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
        });
    }

    private void drawSecretAura(
            Player owner, CosmeticCatalog.Definition definition, Location centre,
            double phase, int step
    ) {
        switch (definition.id()) {
            case "astral_sovereign" -> {
                Color violet = Color.fromRGB(120, 85, 255);
                Color starlight = Color.fromRGB(120, 235, 255);
                double formation = CosmeticAnimation.easeOutBack(
                        CosmeticAnimation.phaseProgress(step, 0, 24)
                );
                drawVerticalStar(owner, centre.clone().add(0d, 0.16d, 0d),
                        horizontalSide(owner), 0.42d * formation,
                        phase * 0.46d, violet, starlight);
                for (int orbit = 0; orbit < 3; orbit++) {
                    int planets = 3 + orbit;
                    for (int body = 0; body < planets; body++) {
                        double angle = phase * (0.35d + orbit * 0.18d)
                                + body * Math.PI * 2d / planets;
                        double radius = formation * (0.65d + orbit * 0.42d);
                        Location at = centre.clone().add(
                                Math.cos(angle) * radius,
                                Math.sin(angle + orbit * 0.8d) * radius * (0.25d + orbit * 0.12d),
                                Math.sin(angle) * radius * (1d - orbit * 0.12d)
                        );
                        dust(owner, at, (body + orbit) % 2 == 0 ? violet : starlight,
                                orbit == 2 ? 1.25f : 0.85f,
                                PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                    }
                }
                if (step >= 38 && step < 67) {
                    double flight = CosmeticAnimation.phaseProgress(step, 38, 67);
                    Vector side = horizontalSide(owner);
                    Location start = centre.clone().add(side.clone().multiply(-2.2d))
                            .add(0d, -0.35d, 0d);
                    Location comet = start.add(side.clone().multiply(flight * 4.4d))
                            .add(0d, Math.sin(flight * Math.PI) * 2.3d, 0d);
                    spawnMoving(owner, comet, Particle.END_ROD,
                            side.clone().multiply(0.09d).setY(0.045d), null,
                            PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                }
                if (step >= 62) {
                    double nova = CosmeticAnimation.smooth(
                            CosmeticAnimation.phaseProgress(step, 62, 79)
                    );
                    drawRing(owner, centre, 0.15d + nova * 2.25d,
                            28, -phase, starlight, 0.85f,
                            PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                }
            }
            case "infernal_dominion" -> {
                Color fire = Color.fromRGB(255, 65, 8);
                Color molten = Color.fromRGB(255, 165, 18);
                double crackGrowth = CosmeticAnimation.smooth(
                        CosmeticAnimation.phaseProgress(step, 0, 27)
                );
                Location ground = centre.clone().add(0d, -0.9d, 0d);
                for (int crack = 0; crack < 7; crack++) {
                    double angle = crack * Math.PI * 2d / 7d + phase * 0.08d;
                    Location end = ground.clone().add(
                            Math.cos(angle) * crackGrowth * (1.3d + crack % 2 * 0.45d),
                            0d,
                            Math.sin(angle) * crackGrowth * (1.3d + crack % 2 * 0.45d)
                    );
                    drawLine(owner, ground, end, 5, crack % 2 == 0 ? fire : molten,
                            1f, PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                }
                Location crown = centre.clone().add(0d, 1.05d, 0d);
                double ignite = CosmeticAnimation.easeOutBack(
                        CosmeticAnimation.phaseProgress(step, 18, 42)
                );
                drawVerticalGem(owner, crown.clone().add(0d, 0.08d, 0d),
                        horizontalSide(owner), 0.4d * ignite,
                        -phase * 0.5d, fire, molten);
                for (int point = 0; point < 9; point++) {
                    double angle = -phase * 0.45d + point * Math.PI * 2d / 9d;
                    Location at = crown.clone().add(
                            Math.cos(angle) * ignite * 0.55d,
                            (point % 3 == 0 ? 0.38d : 0d) * ignite
                                    - (1d - ignite) * 0.9d,
                            Math.sin(angle) * ignite * 0.55d
                    );
                    dust(owner, at, point % 3 == 0 ? molten : fire, 1.2f,
                            PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                }
                if (step >= 40 && step < 70) {
                    double vortex = CosmeticAnimation.phaseProgress(step, 40, 70);
                    for (int flame = 0; flame < 8; flame++) {
                        double angle = phase * 1.15d + flame * Math.PI / 4d;
                        Location at = centre.clone().add(
                                Math.cos(angle) * (0.85d - vortex * 0.42d),
                                -0.72d + ((vortex + flame / 8d) % 1d) * 2.35d,
                                Math.sin(angle) * (0.85d - vortex * 0.42d)
                        );
                        spawnMoving(owner, at, Particle.SMALL_FLAME,
                                new Vector(0d, 0.06d, 0d), null,
                                PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                    }
                }
                if (step >= 66) {
                    double eruption = CosmeticAnimation.phaseProgress(step, 66, 79);
                    drawRing(owner, ground, 0.3d + eruption * 2d, 22,
                            phase, fire, 1.1f, PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                }
            }
            case "abyssal_seraph" -> {
                Vector backwards = owner.getLocation().getDirection().setY(0d);
                if (backwards.lengthSquared() < 0.001d) {
                    backwards = new Vector(0d, 0d, 1d);
                }
                backwards.normalize().multiply(-1d);
                Vector side = new Vector(-backwards.getZ(), 0d, backwards.getX()).normalize();
                double unfold = CosmeticAnimation.easeOutBack(
                        CosmeticAnimation.phaseProgress(step, 0, 28)
                );
                double flap = Math.sin(step * 0.22d) * 0.28d;
                drawVerticalGem(owner,
                        centre.clone().add(backwards.clone().multiply(0.38d))
                                .add(0d, 0.14d, 0d),
                        side, 0.38d * unfold, phase * 0.34d,
                        Color.fromRGB(28, 0, 48), Color.fromRGB(155, 65, 245));
                for (int wing = 0; wing < 3; wing++) {
                    for (double direction : new double[]{-1d, 1d}) {
                        for (int point = 0; point < 6; point++) {
                            double progress = point / 5d;
                            Location at = centre.clone()
                                    .add(backwards.clone().multiply(0.25d + progress * 0.6d))
                                    .add(side.clone().multiply(
                                            direction * progress * (0.8d + wing * 0.4d) * unfold
                                    ))
                                    .add(0d, -0.25d + wing * 0.58d * unfold
                                            + flap * (1d - progress), 0d);
                            dust(owner, at, wing == 1
                                            ? Color.fromRGB(105, 35, 205) : Color.fromRGB(25, 0, 45),
                                    1.25f, PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                            if (step >= 42 && step < 68 && point == 5
                                    && (step + wing) % 4 == 0) {
                                spawnMoving(owner, at, Particle.DRAGON_BREATH,
                                        side.clone().multiply(direction * 0.07d).setY(0.025d),
                                        null, PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                            }
                        }
                    }
                }
                if (step >= 55) {
                    double tear = CosmeticAnimation.pingPong((step - 55d) / 25d);
                    drawRing(owner, centre.clone().add(backwards.clone().multiply(0.75d)),
                            0.2d + tear * 1.35d, 20, phase * 1.4d,
                            Color.fromRGB(110, 20, 190), 1.15f,
                            PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                }
            }
            case "resonant_apotheosis" -> drawResonantApotheosis(owner, centre, phase, step);
            default -> { }
        }
    }

    /** A crystal crown awakens, changes formation, then opens into resonant wings. */
    private void drawResonantApotheosis(
            Player owner, Location centre, double phase, int step
    ) {
        Color royal = Color.fromRGB(132, 45, 225);
        Color crystal = Color.fromRGB(218, 170, 255);
        Color resonance = Color.fromRGB(125, 225, 255);
        Vector side = horizontalSide(owner);
        Vector forward = new Vector(-side.getZ(), 0d, side.getX());
        double awaken = CosmeticAnimation.easeOutBack(
                CosmeticAnimation.phaseProgress(step, 0, 18)
        );
        Location heart = centre.clone().add(0d, 0.18d, 0d);
        drawVerticalGem(owner, heart, side, 0.42d * awaken,
                phase * 0.34d, royal, crystal);

        int formation = Math.min(2, step / 27);
        int gems = 5 + formation * 2;
        double radius = 0.62d + formation * 0.28d;
        for (int gem = 0; gem < gems; gem++) {
            double angle = phase * (formation % 2 == 0 ? 0.48d : -0.62d)
                    + gem * Math.PI * 2d / gems;
            double wave = Math.sin(angle * (2d + formation)) * (0.12d + formation * 0.09d);
            Location at = heart.clone()
                    .add(side.clone().multiply(Math.cos(angle) * radius * awaken))
                    .add(forward.clone().multiply(Math.sin(angle) * radius * 0.7d * awaken))
                    .add(0d, wave + (formation == 2 ? Math.cos(angle) * 0.38d : 0d), 0d);
            dust(owner, at, gem % 3 == 0 ? resonance : crystal,
                    formation == 2 ? 1.12f : 0.9f,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }

        double pulse = CosmeticAnimation.pingPong(step / 13d);
        drawRing(owner, centre.clone().add(0d, -0.78d, 0d),
                0.55d + pulse * 0.95d, 18, -phase * 0.7d,
                formation == 2 ? resonance : royal, 0.78f,
                PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);

        if (formation == 2) {
            double open = CosmeticAnimation.smooth(
                    CosmeticAnimation.phaseProgress(step, 54, 72)
            );
            Vector backwards = owner.getLocation().getDirection().setY(0d);
            if (backwards.lengthSquared() < 0.001d) {
                backwards = new Vector(0d, 0d, 1d);
            }
            backwards.normalize().multiply(-1d);
            for (double direction : new double[]{-1d, 1d}) {
                for (int shard = 1; shard <= 6; shard++) {
                    double progress = shard / 6d;
                    Location wing = centre.clone()
                            .add(backwards.clone().multiply(0.3d + progress * 0.4d))
                            .add(side.clone().multiply(direction * progress * open * 1.65d))
                            .add(0d, -0.35d + Math.sin(progress * Math.PI) * open * 1.6d, 0d);
                    dust(owner, wing, shard % 2 == 0 ? crystal : royal,
                            1.05f, PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                }
            }
        }
        if (step == 18 || step == 54 || step == 72) {
            sound(owner, centre, Sound.BLOCK_AMETHYST_BLOCK_RESONATE,
                    0.55f, step == 18 ? 0.82f : step == 54 ? 1.18f : 1.58f,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }
    }

    private void drawSecretTrail(
            Player owner, CosmeticCatalog.Definition definition, List<Location> history
    ) {
        switch (definition.id()) {
            case "galaxy_wake" -> drawGalaxyWake(owner, history);
            case "phantom_chains" -> {
                Vector side = trailSide(history);
                Color chainColour = Color.fromRGB(55, 220, 235);
                for (int chain = 0; chain < 3; chain++) {
                    int index = CosmeticAnimation.trailIndex(
                            frame / 2L, history.size(), chain * 4
                    );
                    double turn = frame * 0.28d + chain * Math.PI / 3d;
                    double radius = 0.28d + CosmeticAnimation.pingPong(
                            frame * 0.12d + chain * 0.3d
                    ) * 0.16d;
                    Location linkCentre = trailPoint(history, index, 0.42d + chain * 0.15d);
                    for (int point = 0; point < 10; point++) {
                        double angle = point * Math.PI / 5d + turn;
                        Location at = linkCentre.clone();
                        if (chain % 2 == 0) {
                            at.add(side.clone().multiply(Math.cos(angle) * radius))
                                    .add(0d, Math.sin(angle) * radius * 1.35d, 0d);
                        } else {
                            at.add(side.clone().multiply(Math.cos(angle) * radius * 0.55d))
                                    .add(0d, Math.sin(angle) * radius,
                                            Math.cos(angle) * radius);
                        }
                        dust(owner, at, chainColour, 0.8f,
                                PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
                    }
                    if (chain == CosmeticAnimation.step(frame / 4L, 3)) {
                        spawnMoving(owner, linkCentre, Particle.SOUL_FIRE_FLAME,
                                new Vector(0d, 0.045d, 0d), null,
                                PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
                    }
                }
            }
            case "reality_fracture" -> {
                Vector side = trailSide(history);
                for (int rift = 0; rift < 2; rift++) {
                    int index = CosmeticAnimation.trailIndex(
                            frame / 3L, history.size(), rift * 7
                    );
                    double tear = 0.25d + CosmeticAnimation.pingPong(
                            frame * 0.1d + rift * 0.47d
                    ) * 0.72d;
                    Location centre = trailPoint(history, index, 0.54d + rift * 0.18d);
                    Location previous = null;
                    for (int crack = 0; crack < 9; crack++) {
                        double progress = crack / 8d;
                        double jag = ((crack * 17 + frame / 2L) % 5L - 2d) * 0.09d;
                        Location fracture = centre.clone()
                                .add(side.clone().multiply(jag + Math.sin(progress * Math.PI)
                                        * tear * (rift == 0 ? 0.35d : -0.35d)))
                                .add(0d, (progress - 0.5d) * tear * 2d, 0d);
                        Color colour = crack % 2 == 0
                                ? Color.fromRGB(255, 35, 220) : Color.fromRGB(45, 220, 255);
                        if (previous != null) {
                            drawLine(owner, previous, fracture, 3, colour, 1.05f,
                                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
                        }
                        previous = fracture;
                    }
                    spawnMoving(owner, centre, Particle.ELECTRIC_SPARK,
                            side.clone().multiply(rift == 0 ? -0.08d : 0.08d).setY(0.04d),
                            null, PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
                }
            }
            case "shattered_continuum" -> drawShatteredContinuum(owner, history);
            default -> { }
        }
    }

    /** Vertical geode gates travel down the trail, snap shut, and scatter forward. */
    private void drawShatteredContinuum(Player owner, List<Location> history) {
        Vector side = trailSide(history);
        Color violet = Color.fromRGB(145, 58, 235);
        Color edge = Color.fromRGB(225, 185, 255);
        Color fracture = Color.fromRGB(115, 225, 255);
        for (int gate = 0; gate < 3; gate++) {
            int index = CosmeticAnimation.trailIndex(frame / 2L, history.size(), gate * 5);
            double life = CosmeticAnimation.pingPong(frame * 0.09d + gate * 0.31d);
            double radius = 0.24d + life * 0.62d;
            Location centre = trailPoint(history, index, 0.72d + gate * 0.12d);
            for (int point = 0; point < 12; point++) {
                double angle = point * Math.PI / 6d
                        + frame * 0.07d * (gate % 2 == 0 ? 1d : -1d);
                double broken = point % 4 == gate % 4 ? 0.78d : 1d;
                Location at = centre.clone()
                        .add(side.clone().multiply(Math.cos(angle) * radius * broken))
                        .add(0d, Math.sin(angle) * radius * 1.22d, 0d);
                dust(owner, at, point % 3 == 0 ? edge : violet,
                        point % 3 == 0 ? 0.92f : 0.72f,
                        PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            }
            Location splitTop = centre.clone().add(side.clone().multiply(-radius * 0.35d))
                    .add(0d, radius * 0.85d, 0d);
            Location splitBottom = centre.clone().add(side.clone().multiply(radius * 0.28d))
                    .add(0d, -radius * 0.82d, 0d);
            drawLine(owner, splitBottom, splitTop, 4, fracture, 0.8f,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            if (life < 0.16d) {
                spawnMoving(owner, centre, Particle.END_ROD,
                        side.clone().multiply(gate % 2 == 0 ? 0.085d : -0.085d)
                                .setY(0.045d),
                        null, PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            }
        }
    }

    /** Twin spiral galaxies and a shooting star; deliberately never a rainbow prism. */
    private void drawGalaxyWake(Player owner, List<Location> history) {
        Vector side = trailSide(history);
        Color[] cosmic = {
                Color.fromRGB(58, 40, 145),
                Color.fromRGB(105, 70, 235),
                Color.fromRGB(55, 185, 255),
                Color.fromRGB(220, 240, 255)
        };
        for (int galaxy = 0; galaxy < 2; galaxy++) {
            int index = CosmeticAnimation.trailIndex(frame / 3L, history.size(), galaxy * 7);
            Location core = trailPoint(history, index, 0.58d + galaxy * 0.18d);
            double pulse = 0.78d + CosmeticAnimation.pingPong(
                    frame * 0.065d + galaxy * 0.43d
            ) * 0.3d;
            double rotation = frame * (galaxy == 0 ? 0.24d : -0.2d);
            for (int arm = 0; arm < 2; arm++) {
                for (int star = 0; star < 7; star++) {
                    double progress = (star + 1d) / 7d;
                    double radius = progress * pulse;
                    double angle = rotation + arm * Math.PI + progress * Math.PI * 2.2d;
                    Location at = core.clone()
                            .add(side.clone().multiply(Math.cos(angle) * radius))
                            .add(0d, Math.sin(angle) * radius * 0.58d, 0d);
                    Color colour = cosmic[(star + arm + galaxy) % cosmic.length];
                    dust(owner, at, colour, star == 6 ? 0.95f : 0.7f,
                            PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
                }
            }
            dust(owner, core, cosmic[3], galaxy == 0 ? 1.05f : 0.88f,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            spawnMoving(owner, core, Particle.REVERSE_PORTAL,
                    new Vector(0d, 0.035d, 0d), null,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
        }

        int cometIndex = CosmeticAnimation.trailIndex(frame / 2L, history.size(), 4);
        Location comet = trailPoint(history, cometIndex, 1.05d);
        Location cometTail = trailPoint(history, cometIndex + 3, 0.72d)
                .add(side.clone().multiply(Math.sin(frame * 0.22d) * 0.26d));
        drawLine(owner, comet, cometTail, 5, cosmic[2], 0.78f,
                PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
        spawnMoving(owner, comet, Particle.END_ROD,
                new Vector(0d, 0.065d, 0d), null,
                PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
    }

    private void drawSecretKill(
            Player owner, CosmeticCatalog.Definition definition, Location centre
    ) {
        switch (definition.id()) {
            case "event_horizon" -> animateEventHorizon(owner, centre);
            case "reapers_verdict" -> animateReapersVerdict(owner, centre);
            case "divine_rupture" -> animateDivineRupture(owner, centre);
            case "crystalline_extinction" -> animateCrystallineExtinction(owner, centre);
            default -> { }
        }
    }

    /** Six crystal jaws converge, erase the centre, and burst back as lethal shrapnel. */
    private void animateCrystallineExtinction(Player owner, Location centre) {
        Color voidViolet = Color.fromRGB(70, 12, 105);
        Color amethyst = Color.fromRGB(165, 72, 245);
        Color core = Color.fromRGB(235, 205, 255);
        animate(owner, centre, 40, 2L, step -> {
            if (step < 23) {
                double close = CosmeticAnimation.smooth(step / 22d);
                for (int jaw = 0; jaw < 6; jaw++) {
                    double angle = jaw * Math.PI / 3d + step * 0.055d;
                    double outerRadius = 2.75d - close * 1.85d;
                    Location outer = centre.clone().add(
                            Math.cos(angle) * outerRadius,
                            -0.25d + (jaw % 2 == 0 ? 1.35d : -0.35d),
                            Math.sin(angle) * outerRadius
                    );
                    Location fang = centre.clone().add(
                            Math.cos(angle) * (0.35d + (1d - close) * 0.5d),
                            0.25d + (jaw % 2 == 0 ? 0.3d : -0.15d),
                            Math.sin(angle) * (0.35d + (1d - close) * 0.5d)
                    );
                    drawLine(owner, outer, fang, 7,
                            jaw % 2 == 0 ? core : amethyst, 1.2f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                drawRing(owner, centre, 1.8d - close * 1.55d, 22,
                        step * 0.42d, close > 0.75d ? core : voidViolet,
                        0.9f + (float) close * 0.55f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                if (step == 2) {
                    sound(owner, centre, Sound.BLOCK_AMETHYST_BLOCK_RESONATE,
                            1.15f, 0.5f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                if (step == 22) {
                    spawn(owner, centre, Particle.SONIC_BOOM, 1,
                            0d, 0d, 0d, 0d, null,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                    spawn(owner, centre, Particle.EXPLOSION_EMITTER, 1,
                            0d, 0d, 0d, 0d, null,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                    sound(owner, centre, Sound.ENTITY_GENERIC_EXPLODE, 1.7f, 0.58f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                return;
            }
            double rupture = CosmeticAnimation.easeOutBack((step - 23d) / 16d);
            for (int shard = 0; shard < 14; shard++) {
                double angle = shard * 2.399963d + step * 0.16d;
                double radius = rupture * (1.15d + (shard % 4) * 0.48d);
                Location tip = centre.clone().add(
                        Math.cos(angle) * radius,
                        -0.2d + Math.sin(angle * 1.7d) * rupture * 1.45d,
                        Math.sin(angle) * radius
                );
                Location root = centre.clone().add(
                        Math.cos(angle) * Math.max(0d, radius - 0.75d),
                        0.05d,
                        Math.sin(angle) * Math.max(0d, radius - 0.75d)
                );
                drawLine(owner, root, tip, 4,
                        shard % 3 == 0 ? core : amethyst, 0.95f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
            drawRing(owner, centre.clone().add(0d, -0.45d, 0d),
                    0.2d + rupture * 3.1d, 30, -step * 0.38d,
                    voidViolet, 1.2f,
                    PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            if (step == 39) {
                sound(owner, centre, Sound.BLOCK_AMETHYST_CLUSTER_BREAK,
                        1.4f, 0.65f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
        });
    }

    /** A tilted accretion disk accelerates, consumes its stars, and erupts in polar jets. */
    private void animateEventHorizon(Player owner, Location centre) {
        Color violet = Color.fromRGB(105, 15, 155);
        Color hot = Color.fromRGB(255, 95, 210);
        animate(owner, centre, 36, 2L, step -> {
            if (step < 20) {
                double collapse = CosmeticAnimation.smooth(step / 19d);
                double radius = 2.35d - collapse * 2d;
                for (int star = 0; star < 24; star++) {
                    double angle = star * Math.PI / 12d + step * (0.22d + collapse * 0.48d);
                    double lane = 0.65d + (star % 4) * 0.14d;
                    Location at = centre.clone().add(
                            Math.cos(angle) * radius * lane,
                            Math.sin(angle * 2d) * 0.12d,
                            Math.sin(angle) * radius * lane * 0.34d
                    );
                    Vector inward = centre.toVector().subtract(at.toVector());
                    if (inward.lengthSquared() > 0.001d) {
                        inward.normalize().multiply(0.055d + collapse * 0.07d);
                    }
                    spawnMoving(owner, at, star % 5 == 0 ? Particle.REVERSE_PORTAL : Particle.DUST,
                            inward, star % 5 == 0 ? null
                                    : new Particle.DustOptions(star % 2 == 0 ? violet : hot, 0.85f),
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                if (step == 1) {
                    sound(owner, centre, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.25f, 0.55f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                return;
            }
            if (step < 23) {
                spawn(owner, centre, Particle.SQUID_INK, 2, 0.04d, 0.04d, 0.04d,
                        0d, null, PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                return;
            }
            double jet = CosmeticAnimation.easeOutBack((step - 23d) / 12d);
            for (double direction : new double[]{-1d, 1d}) {
                Location tip = centre.clone().add(0d, direction * jet * 3.8d, 0d);
                drawLine(owner, centre, tip, 12, direction > 0d ? hot : violet,
                        1.25f, PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                spawnMoving(owner, tip, Particle.DRAGON_BREATH,
                        new Vector(0d, direction * 0.13d, 0d), null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
            drawRing(owner, centre, 0.2d + jet * 2.8d, 30,
                    step * 0.5d, violet, 1.3f,
                    PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            if (step == 23) {
                spawn(owner, centre, Particle.SONIC_BOOM, 1, 0d, 0d, 0d, 0d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                sound(owner, centre, Sound.BLOCK_END_PORTAL_SPAWN, 1.4f, 1.25f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
        });
    }

    /** Souls are marked, a full scythe arc travels through them, then the harvest is pulled away. */
    private void animateReapersVerdict(Player owner, Location centre) {
        Color steel = Color.fromRGB(175, 225, 235);
        Color shadow = Color.fromRGB(35, 5, 50);
        animate(owner, centre, 34, 2L, step -> {
            if (step < 11) {
                double summon = CosmeticAnimation.easeOutBack(step / 10d);
                for (int soul = 0; soul < 7; soul++) {
                    double angle = soul * Math.PI * 2d / 7d + step * 0.12d;
                    Location at = centre.clone().add(
                            Math.cos(angle) * summon * 1.25d,
                            -0.4d + soul % 3 * 0.62d,
                            Math.sin(angle) * summon * 1.25d
                    );
                    spawnMoving(owner, at, Particle.SOUL,
                            new Vector(0d, 0.035d, 0d), null,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                return;
            }
            if (step < 22) {
                double swing = CosmeticAnimation.smooth((step - 11d) / 10d);
                double leadingAngle = -Math.PI * 0.8d + swing * Math.PI * 1.6d;
                for (int edge = 0; edge < 13; edge++) {
                    double trail = edge / 12d;
                    double angle = leadingAngle - trail * 0.9d;
                    double radius = 1d + trail * 1.35d;
                    Location blade = centre.clone().add(
                            Math.cos(angle) * radius,
                            -0.2d + Math.sin(swing * Math.PI) * 1.45d + trail * 0.35d,
                            Math.sin(angle) * radius
                    );
                    dust(owner, blade, edge < 4 ? steel : shadow,
                            edge < 4 ? 1.25f : 0.85f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                if (step == 18) {
                    spawn(owner, centre, Particle.SWEEP_ATTACK, 3,
                            0.55d, 0.45d, 0.55d, 0d, null,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                    sound(owner, centre, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 0.48f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                return;
            }
            double harvest = CosmeticAnimation.smooth((step - 22d) / 11d);
            for (int soul = 0; soul < 9; soul++) {
                double angle = soul * 2.399963d + step * 0.25d;
                Location at = centre.clone().add(
                        Math.cos(angle) * (1.4d - harvest * 1.15d),
                        -0.45d + harvest * (2.6d + soul * 0.1d),
                        Math.sin(angle) * (1.4d - harvest * 1.15d)
                );
                spawnMoving(owner, at, soul % 3 == 0 ? Particle.SCULK_SOUL : Particle.SOUL,
                        new Vector(0d, 0.075d, 0d), null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
            if (step == 33) {
                spawn(owner, centre.clone().add(0d, 2.8d, 0d), Particle.SQUID_INK,
                        8, 0.25d, 0.25d, 0.25d, 0.03d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                sound(owner, centre, Sound.ENTITY_WITHER_SPAWN, 1.1f, 1.5f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
        });
    }

    /** A six-point judgment seal charges before a descending bolt splits into radiant wings. */
    private void animateDivineRupture(Player owner, Location centre) {
        Color gold = Color.fromRGB(255, 210, 65);
        Color white = Color.fromRGB(255, 255, 235);
        animate(owner, centre, 36, 2L, step -> {
            if (step < 13) {
                double charge = CosmeticAnimation.easeOutBack(step / 12d);
                Location ground = centre.clone().add(0d, -0.65d, 0d);
                drawRing(owner, ground, charge * 1.85d, 30,
                        step * 0.25d, gold, 1.05f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                for (int ray = 0; ray < 6; ray++) {
                    double angle = ray * Math.PI / 3d;
                    Location outer = ground.clone().add(
                            Math.cos(angle) * charge * 1.55d, 0d,
                            Math.sin(angle) * charge * 1.55d
                    );
                    drawLine(owner, ground, outer, 5, ray % 2 == 0 ? white : gold,
                            0.9f, PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                if (step == 9) {
                    sound(owner, centre, Sound.BLOCK_BEACON_POWER_SELECT, 1.2f, 1.65f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                return;
            }
            if (step < 21) {
                double descent = CosmeticAnimation.smooth((step - 13d) / 7d);
                Location bolt = centre.clone().add(0d, 5d - descent * 5d, 0d);
                Location previous = bolt.clone().add(0d, 0.7d, 0d);
                for (int segment = 0; segment < 7; segment++) {
                    Location next = previous.clone().add(
                            (segment % 2 == 0 ? 0.18d : -0.18d), -0.65d, 0.1d
                    );
                    drawLine(owner, previous, next, 3, segment % 2 == 0 ? white : gold,
                            1.25f, PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                    previous = next;
                }
                spawnMoving(owner, bolt, Particle.ELECTRIC_SPARK,
                        new Vector(0d, -0.15d, 0d), null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                if (step == 20) {
                    spawn(owner, centre, Particle.FLASH, 2, 0d, 0d, 0d, 0d, null,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                    sound(owner, centre, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.6f, 0.72f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                return;
            }
            double revelation = CosmeticAnimation.easeOutBack((step - 21d) / 14d);
            for (double direction : new double[]{-1d, 1d}) {
                for (int feather = 0; feather < 9; feather++) {
                    double progress = feather / 8d;
                    Location at = centre.clone().add(
                            direction * progress * revelation * 2.55d,
                            0.1d + Math.sin(progress * Math.PI) * revelation * 1.8d,
                            0.25d + progress * 0.28d
                    );
                    spawnMoving(owner, at, feather % 3 == 0 ? Particle.END_ROD : Particle.DUST,
                            new Vector(direction * 0.025d, 0.035d, 0d),
                            feather % 3 == 0 ? null
                                    : new Particle.DustOptions(feather % 2 == 0 ? white : gold, 1f),
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
            }
            drawRing(owner, centre, revelation * 2.8d, 32,
                    -step * 0.3d, gold, 1.2f,
                    PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            if (step == 35) {
                spawn(owner, centre.clone().add(0d, 1.2d, 0d), Particle.TOTEM_OF_UNDYING,
                        16, 0.4d, 0.7d, 0.4d, 0.08d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                sound(owner, centre, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.35f, 1.05f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
        });
    }

    private void animate(
            Player owner, Location centre, int frames, long intervalTicks, IntConsumer renderer
    ) {
        Location origin = centre.clone();
        for (int frameStep = 0; frameStep < frames; frameStep++) {
            int step = frameStep;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (origin.getWorld() != null) {
                    renderer.accept(step);
                }
            }, frameStep * intervalTicks);
        }
    }

    private void drawRing(
            Player owner,
            Location centre,
            double radius,
            int points,
            double rotation,
            Color colour,
            float size,
            PlayerSettingsStore.Setting ownSetting
    ) {
        for (int point = 0; point < points; point++) {
            double angle = rotation + point * Math.PI * 2d / points;
            Location at = centre.clone().add(
                    Math.cos(angle) * radius, 0d, Math.sin(angle) * radius
            );
            spawn(owner, at, Particle.DUST, 1, 0d, 0d, 0d, 0d,
                    new Particle.DustOptions(colour, size), ownSetting);
        }
    }

    private void drawLine(
            Player owner,
            Location start,
            Location end,
            int points,
            Color colour,
            float size,
            PlayerSettingsStore.Setting ownSetting
    ) {
        Vector difference = end.toVector().subtract(start.toVector());
        int divisions = Math.max(1, points - 1);
        for (int point = 0; point < points; point++) {
            Location at = start.clone().add(difference.clone().multiply(point / (double) divisions));
            spawn(owner, at, Particle.DUST, 1, 0d, 0d, 0d, 0d,
                    new Particle.DustOptions(colour, size), ownSetting);
        }
    }

    private void drawTrailSnowflake(
            Player owner, Location centre, Vector side, Color colour,
            double radius, double rotation
    ) {
        for (int arm = 0; arm < 6; arm++) {
            double angle = rotation + arm * Math.PI / 3d;
            Location tip = centre.clone()
                    .add(side.clone().multiply(Math.cos(angle) * radius))
                    .add(0d, Math.sin(angle) * radius, 0d);
            drawLine(owner, centre, tip, 4, colour, 0.72f,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
        }
    }

    private void drawTrailCrown(
            Player owner, Location centre, Vector side, Color colour, double scale
    ) {
        Location left = centre.clone().add(side.clone().multiply(-0.62d * scale));
        Location leftPeak = centre.clone().add(side.clone().multiply(-0.34d * scale))
                .add(0d, 0.58d * scale, 0d);
        Location middle = centre.clone().add(0d, 0.28d * scale, 0d);
        Location crownPeak = centre.clone().add(0d, 0.82d * scale, 0d);
        Location rightPeak = centre.clone().add(side.clone().multiply(0.34d * scale))
                .add(0d, 0.58d * scale, 0d);
        Location right = centre.clone().add(side.clone().multiply(0.62d * scale));
        drawLine(owner, left, leftPeak, 3, colour, 1.02f,
                PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
        drawLine(owner, leftPeak, middle, 3, colour, 1.02f,
                PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
        drawLine(owner, middle, crownPeak, 3, colour, 1.12f,
                PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
        drawLine(owner, crownPeak, rightPeak, 3, colour, 1.12f,
                PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
        drawLine(owner, rightPeak, right, 3, colour, 1.02f,
                PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
        drawLine(owner, left, right, 5, colour, 0.86f,
                PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
    }

    private void spawnMoving(
            Player owner,
            Location location,
            Particle particle,
            Vector velocity,
            Object data,
            PlayerSettingsStore.Setting ownSetting
    ) {
        spawn(owner, location, particle, 0,
                velocity.getX(), velocity.getY(), velocity.getZ(), 1d, data, ownSetting);
    }

    private static Location trailPoint(List<Location> history, int index, double height) {
        int safeIndex = Math.max(0, Math.min(index, history.size() - 1));
        return history.get(safeIndex).clone().add(0d, height, 0d);
    }

    private static Vector trailSide(List<Location> history) {
        Vector movement = history.get(0).toVector()
                .subtract(history.get(history.size() - 1).toVector())
                .setY(0d);
        if (movement.lengthSquared() < 0.001d) {
            movement = new Vector(0d, 0d, 1d);
        }
        movement.normalize();
        return new Vector(-movement.getZ(), 0d, movement.getX());
    }

    private static Vector horizontalSide(Player owner) {
        Vector forward = owner.getLocation().getDirection().setY(0d);
        if (forward.lengthSquared() < 0.001d) {
            forward = new Vector(0d, 0d, 1d);
        }
        forward.normalize();
        return new Vector(-forward.getZ(), 0d, forward.getX());
    }

    private void dust(
            Player owner,
            Location location,
            Color colour,
            float size,
            PlayerSettingsStore.Setting ownSetting
    ) {
        spawn(owner, location, Particle.DUST, 1, 0d, 0d, 0d, 0d,
                new Particle.DustOptions(colour, size), ownSetting);
    }

    private void spawn(
            Player owner,
            Location location,
            Particle particle,
            int count,
            double offsetX,
            double offsetY,
            double offsetZ,
            double extra,
            Object data,
            PlayerSettingsStore.Setting ownSetting
    ) {
        Object particleData = particleData(particle, data);
        for (Player viewer : viewers(owner, location, ownSetting)) {
            if (particleData == null) {
                viewer.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, extra);
            } else {
                viewer.spawnParticle(
                        particle, location, count, offsetX, offsetY, offsetZ, extra, particleData
                );
            }
        }
    }

    static Object particleData(Particle particle, Object data) {
        if (data == null && particle.getDataType() == Color.class) {
            return Color.WHITE;
        }
        if (data == null && particle.getDataType() == Float.class) {
            return 1.0f;
        }
        return data;
    }

    private void sound(
            Player owner,
            Location location,
            Sound sound,
            float volume,
            float pitch,
            PlayerSettingsStore.Setting ownSetting
    ) {
        for (Player viewer : viewers(owner, location, ownSetting)) {
            if (settings.isEnabled(
                    viewer.getUniqueId(), PlayerSettingsStore.Setting.COSMETIC_SOUNDS
            )) {
                viewer.playSound(location, sound, volume, pitch);
            }
        }
    }

    /**
     * Who is close enough to hear one player's cosmetic.
     *
     * <p>The same permission rules as seeing it — somebody who has turned cosmetics
     * off is not serenaded by them either — inside a much smaller radius. Walking
     * out of it drops the listener, and every caller stops whatever it had started
     * for anybody who leaves the list.
     */
    private List<Player> listeners(Player owner) {
        Location centre = owner.getLocation();
        return viewers(owner, centre, PlayerSettingsStore.Setting.OWN_AURA_VISIBLE).stream()
                .filter(viewer -> viewer.getWorld() == centre.getWorld()
                        && viewer.getLocation().distanceSquared(centre) <= HEARING_DISTANCE_SQUARED)
                .toList();
    }

    /**
     * The ambience around the auras rare enough to carry an odds tag.
     *
     * <p>Tied to exactly the same test as the tag, so the rule is one thing rather
     * than two that can disagree: if an aura announces its odds over somebody's head,
     * it also has a sound. The Iridescent Imperium is the exception and already has
     * one — a chime laid over a composed track is worse than either alone.
     *
     * <p>Played to the listener rather than positionally so the player's own cosmetic
     * volume governs it exactly as it governs the music, and kept quiet and slow: it
     * is meant to be noticed once, not listened to.
     */
    private void playAuraAmbience(Player owner, CosmeticCatalog.Definition aura) {
        if (frame % AURA_SOUND_FRAMES != 0L || aura.id().equals(MUSIC_AURA_ID)) {
            return;
        }
        String sound = auraAmbience(aura);
        if (sound == null) {
            return;
        }
        // A family's own pitch, so two Mythics standing together are still telling
        // you two different things.
        float pitch = 0.85f + (Math.floorMod(aura.oddsFamily().ordinal(), 7)) * 0.06f;
        for (Player listener : listeners(owner)) {
            int volume = settings.musicVolume(listener.getUniqueId());
            if (volume <= 0) {
                continue;
            }
            listener.playSound(
                    listener.getLocation(), sound, SoundCategory.PLAYERS,
                    (volume / 100.0f) * AURA_SOUND_VOLUME, pitch
            );
        }
    }

    /**
     * The sound one aura carries, or null for an aura not rare enough to be heard.
     *
     * <p>Named rather than resolved to a {@link Sound}: that enum reads the server's
     * sound registry the moment it is touched, so a constant here would make the one
     * rule worth pinning — which rarities are audible — impossible to unit test.
     */
    static String auraAmbience(CosmeticCatalog.Definition aura) {
        if (!aura.nameplateWorthy()) {
            return null;
        }
        if (aura.hiddenAmethystJackpot()) {
            return "block.conduit.ambient.short";
        }
        return aura.secret()
                ? "block.beacon.ambient"          // Exotic: deep and wrong-sounding
                : "block.amethyst_block.chime";   // Mythic: crystalline
    }

    private List<Player> viewers(
            Player owner, Location location, PlayerSettingsStore.Setting ownSetting
    ) {
        List<Player> viewers = new ArrayList<>();
        World world = location.getWorld();
        if (world == null) {
            return viewers;
        }
        for (Player viewer : world.getPlayers()) {
            if (viewer.getLocation().distanceSquared(location) > VIEW_DISTANCE_SQUARED) {
                continue;
            }
            boolean allowed;
            if (viewer.getUniqueId().equals(owner.getUniqueId())) {
                allowed = ownSetting == null || settings.isEnabled(viewer.getUniqueId(), ownSetting);
            } else {
                allowed = settings.isEnabled(
                        viewer.getUniqueId(), PlayerSettingsStore.Setting.COSMETICS_VISIBLE
                );
            }
            if (allowed) {
                viewers.add(viewer);
            }
        }
        return viewers;
    }

    private static Color podiumColour(int rank) {
        return switch (rank) {
            case 1 -> Color.fromRGB(255, 205, 35);
            case 2 -> Color.fromRGB(205, 220, 235);
            case 3 -> Color.fromRGB(205, 115, 45);
            default -> Color.WHITE;
        };
    }
}
