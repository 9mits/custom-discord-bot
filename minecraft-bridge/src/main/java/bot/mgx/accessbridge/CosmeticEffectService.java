package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

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
    private static final double VIEW_DISTANCE_SQUARED = 48d * 48d;
    private static final int TRAIL_HISTORY_SIZE = 14;
    private static final double TRAIL_RESET_DISTANCE_SQUARED = 12d * 12d;
    private final MGXAccessBridge plugin;
    private final CosmeticStore store;
    private final CosmeticItems items;
    private final WardrobeService wardrobe;
    private final PlayerSettingsStore settings;
    private final LeaderboardService leaderboard;
    private final Map<UUID, Location> previousLocations = new HashMap<>();
    private final Map<UUID, Deque<Location>> trailHistories = new HashMap<>();
    private final Set<String> failedSelectionClears = new HashSet<>();
    private BukkitTask task;
    private long frame;

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
            task = plugin.getServer().getScheduler().runTaskTimer(
                    plugin, this::tick, PERIOD_TICKS, PERIOD_TICKS
            );
        }
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        previousLocations.clear();
        trailHistories.clear();
        failedSelectionClears.clear();
    }

    private void tick() {
        frame++;
        previousLocations.keySet().removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
        trailHistories.keySet().removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Location now = player.getLocation();
            Location previous = previousLocations.put(player.getUniqueId(), now.clone());
            boolean movedInWorld = previous != null && previous.getWorld() == now.getWorld();
            double movementSquared = movedInWorld ? previous.distanceSquared(now) : 0d;
            boolean moving = movedInWorld && movementSquared > 0.0025d;
            long auraFrame = frame + CosmeticAnimation.playerOffset(player.getUniqueId(), 3);
            if (CosmeticAnimation.renderAuraFrame(moving, auraFrame)) {
                active(player, CosmeticCatalog.Category.AURA).ifPresent(
                        definition -> drawAura(player, definition)
                );
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
                List<Location> trail = List.copyOf(history);
                active(player, CosmeticCatalog.Category.TRAIL).ifPresent(
                        definition -> drawTrail(player, definition, trail)
                );
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player killer = event.getPlayer().getKiller();
        if (killer == null) {
            return;
        }
        Location centre = event.getPlayer().getLocation().add(0d, 1d, 0d);
        active(killer, CosmeticCatalog.Category.KILL_EFFECT).ifPresent(
                definition -> drawKillEffect(killer, definition, centre)
        );
    }

    void playSecretReveal(Player player) {
        player.showTitle(Title.title(
                Component.text("UNKNOWN COSMETIC", NamedTextColor.DARK_PURPLE),
                Component.text("Chance: ???", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofSeconds(1))
        ));
        Location origin = player.getLocation().add(0d, 1d, 0d);
        Color shadow = Color.fromRGB(45, 0, 70);
        Color reveal = Color.fromRGB(210, 80, 255);
        animate(player, origin, 30, 2L, step -> {
            if (!player.isOnline()) {
                return;
            }
            if (step < 9) {
                double pull = CosmeticAnimation.smooth(step / 8d);
                double radius = 2.2d - pull * 1.85d;
                for (int shard = 0; shard < 16; shard++) {
                    double angle = shard * Math.PI / 8d + step * 0.38d;
                    Location at = origin.clone().add(
                            Math.cos(angle) * radius,
                            Math.sin(angle * 3d) * (0.8d - pull * 0.55d),
                            Math.sin(angle) * radius
                    );
                    Vector inward = origin.toVector().subtract(at.toVector());
                    if (inward.lengthSquared() > 0.001d) {
                        inward.normalize().multiply(0.08d);
                    }
                    spawnMoving(player, at,
                            shard % 4 == 0 ? Particle.REVERSE_PORTAL : Particle.DUST,
                            inward, shard % 4 == 0 ? null
                                    : new Particle.DustOptions(shadow, 1.05f), null);
                }
                if (step == 0) {
                    sound(player, origin, Sound.BLOCK_END_PORTAL_SPAWN, 1.1f, 0.58f, null);
                }
                return;
            }
            if (step < 18) {
                double unlock = CosmeticAnimation.easeOutBack((step - 9d) / 8d);
                for (int helix = 0; helix < 12; helix++) {
                    double progress = helix / 11d;
                    double angle = step * 0.55d + progress * Math.PI * 4d;
                    Location at = origin.clone().add(
                            Math.cos(angle) * unlock * 0.65d,
                            -0.8d + progress * 2.3d,
                            Math.sin(angle) * unlock * 0.65d
                    );
                    dust(player, at, helix % 2 == 0 ? shadow : reveal,
                            helix % 2 == 0 ? 1.2f : 0.85f, null);
                }
                if (step == 17) {
                    spawn(player, origin, Particle.SONIC_BOOM, 1,
                            0d, 0d, 0d, 0d, null, null);
                    sound(player, origin, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.8f, 1.35f, null);
                }
                return;
            }
            double breakOpen = CosmeticAnimation.easeOutBack((step - 18d) / 11d);
            for (int ray = 0; ray < 12; ray++) {
                double angle = ray * Math.PI / 6d + step * 0.14d;
                Location tip = origin.clone().add(
                        Math.cos(angle) * breakOpen * 2.25d,
                        Math.sin(angle * 2d) * breakOpen * 1.25d,
                        Math.sin(angle) * breakOpen * 2.25d
                );
                drawLine(player, origin, tip, 5, ray % 2 == 0 ? reveal : shadow,
                        1.05f, null);
                if (ray % 3 == 0) {
                    spawnMoving(player, tip, Particle.END_ROD,
                            new Vector(Math.cos(angle) * 0.07d, 0.045d,
                                    Math.sin(angle) * 0.07d), null, null);
                }
            }
            if (step == 29) {
                spawn(player, origin, Particle.TOTEM_OF_UNDYING,
                        18, 0.4d, 0.65d, 0.4d, 0.08d, null, null);
                sound(player, origin, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.25f, 0.92f, null);
            }
        });
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

    private void drawAura(Player owner, CosmeticCatalog.Definition definition) {
        Location centre = owner.getLocation().add(0d, 1.05d, 0d);
        long animatedFrame = frame + CosmeticAnimation.playerOffset(owner.getUniqueId(), 80);
        int step = CosmeticAnimation.step(animatedFrame, 80);
        double phase = animatedFrame * 0.24d;
        if (definition.leaderboardOnly()) {
            drawLeaderboardAura(owner, definition, centre, phase, step);
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
            default -> { }
        }
    }

    /** A miniature sun rises, gains orbiting bodies, throws a flare, then pulses outward. */
    private void drawSolarAura(Player owner, Location centre, double phase, int step) {
        Color gold = Color.fromRGB(255, 190, 35);
        double rise = CosmeticAnimation.smooth(CosmeticAnimation.phaseProgress(step, 0, 18));
        double sunY = step < 18 ? -0.72d + rise * 1.58d : 0.86d;
        Location sun = centre.clone().add(0d, sunY, 0d);
        dust(owner, sun, gold, 1.35f, PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        spawn(owner, sun, Particle.SMALL_FLAME, 1, 0.08d, 0.08d, 0.08d, 0.01d, null,
                PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);

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
        double firstBeat = Math.max(0d, 1d - Math.abs(step - 10d) / 5d);
        double secondBeat = Math.max(0d, 1d - Math.abs(step - 21d) / 4d);
        double beat = Math.max(firstBeat, secondBeat * 0.8d);
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
            default -> { }
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
        for (int prism = 0; prism < 2; prism++) {
            int index = CosmeticAnimation.trailIndex(frame / 2L, history.size(), prism * 6);
            double turn = frame * (0.24d + prism * 0.05d);
            double scale = 0.48d + CosmeticAnimation.pingPong(
                    frame * 0.08d + prism * 0.41d
            ) * 0.28d;
            Location centre = trailPoint(history, index, 0.52d + prism * 0.18d);
            drawTrailDiamond(owner, centre, side, scale, turn,
                    frame + prism * 13L);
            spawnMoving(owner, centre, Particle.END_ROD,
                    new Vector(0d, 0.055d, 0d), null,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
        }
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
            case "reapers_verdict" -> new KillAccent(Color.fromRGB(185, 235, 245), 18, 35);
            case "divine_rupture" -> new KillAccent(Color.fromRGB(255, 215, 70), 20, 38);
            default -> new KillAccent(Color.WHITE, 14, 26);
        };
    }

    private record KillAccent(Color colour, int impactFrame, int frames) { }

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
            default -> { }
        }
    }

    private void drawSecretTrail(
            Player owner, CosmeticCatalog.Definition definition, List<Location> history
    ) {
        switch (definition.id()) {
            case "galaxy_wake" -> {
                Vector side = trailSide(history);
                for (int galaxy = 0; galaxy < 2; galaxy++) {
                    int index = CosmeticAnimation.trailIndex(
                            frame / 3L, history.size(), galaxy * 7
                    );
                    double radius = 0.42d + CosmeticAnimation.pingPong(
                            frame * 0.09d + galaxy * 0.4d
                    ) * 0.42d;
                    Location core = trailPoint(history, index, 0.55d + galaxy * 0.2d);
                    for (int star = 0; star < 10; star++) {
                        double angle = frame * (0.3d + galaxy * 0.08d)
                                + star * Math.PI / 5d;
                        double lane = star % 2 == 0 ? radius : radius * 0.55d;
                        Location at = core.clone()
                                .add(side.clone().multiply(Math.cos(angle) * lane))
                                .add(0d, Math.sin(angle) * lane * 0.62d, 0d);
                        dust(owner, at, rainbow(frame + star * 5L + galaxy * 17L),
                                star % 2 == 0 ? 1.05f : 0.72f,
                                PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
                    }
                    spawnMoving(owner, core, galaxy == 0 ? Particle.END_ROD : Particle.FIREWORK,
                            new Vector(0d, 0.065d, 0d), null,
                            PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
                }
            }
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
            default -> { }
        }
    }

    private void drawSecretKill(
            Player owner, CosmeticCatalog.Definition definition, Location centre
    ) {
        switch (definition.id()) {
            case "event_horizon" -> animateEventHorizon(owner, centre);
            case "reapers_verdict" -> animateReapersVerdict(owner, centre);
            case "divine_rupture" -> animateDivineRupture(owner, centre);
            default -> { }
        }
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

    private void drawTrailDiamond(
            Player owner, Location centre, Vector side, double scale,
            double rotation, long colourFrame
    ) {
        Location[] points = new Location[4];
        for (int point = 0; point < points.length; point++) {
            double angle = rotation + point * Math.PI / 2d;
            points[point] = centre.clone()
                    .add(side.clone().multiply(Math.cos(angle) * scale))
                    .add(0d, Math.sin(angle) * scale, 0d);
        }
        for (int edge = 0; edge < points.length; edge++) {
            drawLine(owner, points[edge], points[(edge + 1) % points.length],
                    4, rainbow(colourFrame + edge * 5L), 0.82f,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
        }
        drawLine(owner, points[0], points[2], 4, rainbow(colourFrame + 23L),
                0.68f, PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
        drawLine(owner, points[1], points[3], 4, rainbow(colourFrame + 31L),
                0.68f, PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
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

    private static Color rainbow(long value) {
        Color[] colours = {
                Color.fromRGB(255, 45, 45),
                Color.fromRGB(255, 175, 35),
                Color.fromRGB(255, 240, 55),
                Color.fromRGB(45, 230, 95),
                Color.fromRGB(45, 190, 255),
                Color.fromRGB(115, 75, 255),
                Color.fromRGB(235, 65, 255)
        };
        return colours[(int) ((value / 3L) % colours.length)];
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
