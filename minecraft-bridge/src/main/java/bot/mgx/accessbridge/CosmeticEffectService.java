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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** One bounded task for auras/trails plus the PvP kill-effect hook. */
final class CosmeticEffectService implements Listener {
    private static final long PERIOD_TICKS = 4L;
    private static final double VIEW_DISTANCE_SQUARED = 48d * 48d;
    private final MGXAccessBridge plugin;
    private final CosmeticStore store;
    private final CosmeticItems items;
    private final WardrobeService wardrobe;
    private final PlayerSettingsStore settings;
    private final Map<UUID, Location> previousLocations = new HashMap<>();
    private final Set<String> failedSelectionClears = new HashSet<>();
    private BukkitTask task;
    private long frame;

    CosmeticEffectService(
            MGXAccessBridge plugin,
            CosmeticStore store,
            CosmeticItems items,
            WardrobeService wardrobe,
            PlayerSettingsStore settings
    ) {
        this.plugin = plugin;
        this.store = store;
        this.items = items;
        this.wardrobe = wardrobe;
        this.settings = settings;
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
        failedSelectionClears.clear();
    }

    private void tick() {
        frame++;
        previousLocations.keySet().removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Location now = player.getLocation();
            Location previous = previousLocations.put(player.getUniqueId(), now.clone());
            active(player, CosmeticCatalog.Category.AURA).ifPresent(
                    definition -> drawAura(player, definition)
            );
            if (previous != null
                    && previous.getWorld() == now.getWorld()
                    && previous.distanceSquared(now) > 0.0025d) {
                active(player, CosmeticCatalog.Category.TRAIL).ifPresent(
                        definition -> drawTrail(player, definition, previous)
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
        for (int pulse = 0; pulse < 8; pulse++) {
            int step = pulse;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                Location centre = player.getLocation().add(0d, 1d, 0d);
                double radius = 0.6d + step * 0.22d;
                for (int point = 0; point < 24; point++) {
                    double angle = point * Math.PI * 2d / 24d;
                    Location at = centre.clone().add(
                            Math.cos(angle) * radius,
                            (step % 3) * 0.25d,
                            Math.sin(angle) * radius
                    );
                    spawn(player, at, Particle.DUST, 1, 0d, 0d, 0d, 0d,
                            new Particle.DustOptions(Color.fromRGB(45, 0, 70), 1.5f), null);
                    spawn(player, at, Particle.END_ROD, 1, 0d, 0d, 0d, 0.01d, null, null);
                }
                spawn(player, centre, Particle.DRAGON_BREATH,
                        18, 0.8d, 1d, 0.8d, 0.03d, null, null);
                spawn(player, centre, Particle.TOTEM_OF_UNDYING,
                        20, 0.8d, 1d, 0.8d, 0.1d, null, null);
                if (step == 0 || step == 4 || step == 7) {
                    sound(player, centre, step == 7
                            ? Sound.UI_TOAST_CHALLENGE_COMPLETE
                            : Sound.BLOCK_END_PORTAL_SPAWN, 1.2f, 0.65f + step * 0.06f, null);
                }
            }, pulse * 8L);
        }
    }

    private Optional<CosmeticCatalog.Definition> active(
            Player player, CosmeticCatalog.Category category
    ) {
        UUID serial = store.equipped(player.getUniqueId(), category.name()).orElse(null);
        if (serial == null) {
            return Optional.empty();
        }
        CosmeticStore.Token token = store.token(serial).orElse(null);
        CosmeticCatalog.Definition definition = token == null
                ? null
                : CosmeticCatalog.find(token.cosmeticId()).orElse(null);
        String selectionKey = player.getUniqueId() + ":" + category.name();
        if (token == null
                || definition == null
                || definition.category() != category
                || !wardrobe.hasAccess(player, token)) {
            try {
                store.clearEquipped(player.getUniqueId(), category.name(), serial);
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

    private void drawAura(Player owner, CosmeticCatalog.Definition definition) {
        Location centre = owner.getLocation().add(0d, 1.05d, 0d);
        double phase = frame * 0.32d;
        if (definition.secret()) {
            drawSecretAura(owner, definition, centre, phase);
            return;
        }
        if (definition.id().equals("celestial_crown")) {
            drawCelestialCrown(owner, centre, phase);
            return;
        }
        int tier = visualTier(definition);
        int points = 3 + tier * 2;
        for (int point = 0; point < points; point++) {
            double angle = phase + point * Math.PI * 2d / points;
            Location at = centre.clone().add(
                    Math.cos(angle) * (0.75d + tier * 0.08d),
                    Math.sin(phase * 0.55d + point) * 0.38d,
                    Math.sin(angle) * (0.75d + tier * 0.08d)
            );
            switch (definition.id()) {
                case "solar_orbit" -> dust(owner, at, Color.fromRGB(255, 190, 35), 1.15f,
                        PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                case "crimson_orbit" -> dust(owner, at, Color.fromRGB(190, 12, 32), 1.15f,
                        PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                case "emerald_orbit" -> dust(owner, at, Color.fromRGB(25, 220, 95), 1.05f,
                        PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                case "amethyst_orbit" -> dust(owner, at, Color.fromRGB(165, 75, 240), 1.1f,
                        PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                default -> { }
            }
        }
        if (tier >= 3 && frame % 2L == 0L) {
            spawn(owner, centre.clone().add(0d, 0.65d, 0d),
                    Particle.ENCHANT,
                    2 + tier, 0.45d, 0.7d, 0.45d, 0.03d, null,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }
        drawAuraLayers(owner, definition, centre, phase, tier);
        if (tier >= 4) {
            drawRareAura(owner, definition, centre, phase, tier);
        }
    }

    /** A small crown silhouette without the wings and large rings used by other rare auras. */
    private void drawCelestialCrown(Player owner, Location centre, double phase) {
        Location crown = centre.clone().add(0d, 0.9d, 0d);
        int points = 8;
        for (int point = 0; point < points; point++) {
            double angle = phase * 0.45d + point * Math.PI * 2d / points;
            double peak = point % 2 == 0 ? 0.12d : 0d;
            Location at = crown.clone().add(
                    Math.cos(angle) * 0.46d,
                    peak,
                    Math.sin(angle) * 0.46d
            );
            dust(owner, at, Color.fromRGB(210, 235, 255), 0.9f,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
            if (point % 4 == 0) {
                spawn(owner, at, Particle.END_ROD, 1, 0d, 0d, 0d, 0d, null,
                        PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
            }
        }
        if (frame % 5L == 0L) {
            spawn(owner, crown, Particle.ENCHANT,
                    3, 0.3d, 0.12d, 0.3d, 0.02d, null,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }
    }

    /** Large animated wings and pulse rings reserved for the rarest auras. */
    private void drawRareAura(
            Player owner, CosmeticCatalog.Definition definition, Location centre,
            double phase, int tier
    ) {
        Vector backwards = owner.getLocation().getDirection().setY(0d);
        if (backwards.lengthSquared() < 0.001d) {
            backwards = new Vector(0d, 0d, 1d);
        }
        backwards.normalize().multiply(-1d);
        Vector side = new Vector(-backwards.getZ(), 0d, backwards.getX()).normalize();
        Color colour = effectColour(definition);
        for (double direction : new double[]{-1d, 1d}) {
            for (int point = 0; point < 9; point++) {
                double progress = point / 8d;
                double spread = Math.sin(progress * Math.PI) * (1.15d + tier * 0.12d);
                double lift = 0.15d + progress * 1.65d + Math.sin(phase + point) * 0.10d;
                Location at = centre.clone()
                        .add(backwards.clone().multiply(0.28d + progress * 0.42d))
                        .add(side.clone().multiply(direction * spread))
                        .add(0d, lift, 0d);
                spawn(owner, at, Particle.DUST, 1, 0d, 0d, 0d, 0d,
                        new Particle.DustOptions(colour, 1.3f),
                        PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                if (point % 3 == 0) {
                    spawn(owner, at, Particle.END_ROD, 1, 0d, 0d, 0d, 0d, null,
                            PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                }
            }
        }
        if (frame % 5L == 0L) {
            double radius = 1.35d + (frame % 15L) * 0.08d;
            for (int point = 0; point < 28; point++) {
                double angle = point * Math.PI * 2d / 28d;
                Location at = centre.clone().add(
                        Math.cos(angle) * radius, -0.85d, Math.sin(angle) * radius
                );
                spawn(owner, at, Particle.DUST, 1, 0d, 0d, 0d, 0d,
                        new Particle.DustOptions(colour, 1.15f),
                        PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
            }
        }
    }

    /** Counter-rotating sigils and a helix make every aura read as a full effect, not dots. */
    private void drawAuraLayers(
            Player owner,
            CosmeticCatalog.Definition definition,
            Location centre,
            double phase,
            int tier
    ) {
        Color primary = effectColour(definition);
        int points = 8 + tier * 4;
        int rings = tier >= 3 ? 2 : 1;
        for (int ring = 0; ring < rings; ring++) {
            double radius = 0.9d + ring * 0.38d + tier * 0.04d;
            double direction = ring == 0 ? 1d : -1d;
            for (int point = 0; point < points; point += tier == 1 ? 2 : 1) {
                double angle = direction * phase * (0.7d + ring * 0.25d)
                        + point * Math.PI * 2d / points;
                double y = ring == 0
                        ? -0.72d + Math.sin(angle * 2d) * 0.10d
                        : 0.55d + Math.cos(angle * 3d) * 0.14d;
                Location at = centre.clone().add(
                        Math.cos(angle) * radius, y, Math.sin(angle) * radius
                );
                spawn(owner, at, Particle.DUST, 1, 0d, 0d, 0d, 0d,
                        new Particle.DustOptions(
                                definition.id().equals("prismatic_trail") ? rainbow(frame + point) : primary,
                                0.9f + tier * 0.12f
                        ), PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
            }
        }
        int helixPoints = 2 + tier;
        for (int point = 0; point < helixPoints; point++) {
            double angle = -phase * 1.35d + point * Math.PI * 2d / helixPoints;
            Location at = centre.clone().add(
                    Math.cos(angle) * 0.48d,
                    -0.65d + ((frame + point * 3L) % 12L) / 6d,
                    Math.sin(angle) * 0.48d
            );
            spawn(owner, at, tier >= 4 ? Particle.END_ROD : Particle.ENCHANT,
                    1, 0d, 0d, 0d, 0d, null,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }
        if (tier >= 4 && frame % 5L == 0L) {
            spawn(owner, centre.clone().add(0d, 1.25d, 0d), Particle.FIREWORK,
                    4, 0.45d, 0.2d, 0.45d, 0.04d, null,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }
    }

    private void drawTrail(Player owner, CosmeticCatalog.Definition definition, Location previous) {
        Location at = previous.clone().add(0d, 0.18d, 0d);
        if (definition.secret()) {
            drawSecretTrail(owner, definition, at);
            return;
        }
        int tier = visualTier(definition);
        switch (definition.id()) {
            case "ember_trail" -> spawn(owner, at, Particle.SMALL_FLAME,
                    3 + tier * 2, 0.18d, 0.08d, 0.18d, 0.01d, null,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            case "blood_trail" -> dust(owner, at, Color.fromRGB(135, 0, 20), 1.1f,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            case "frost_trail" -> spawn(owner, at, Particle.SNOWFLAKE,
                    3 + tier * 2, 0.2d, 0.1d, 0.2d, 0.01d, null,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            case "cherry_blossom_trail" -> spawn(owner, at, Particle.CHERRY_LEAVES,
                    2 + tier * 2, 0.22d, 0.12d, 0.22d, 0.01d, null,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            case "drool_trail" -> {
                dust(owner, at, Color.fromRGB(45, 220, 210), 1.05f,
                        PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
                spawn(owner, at, Particle.FALLING_WATER,
                        1 + tier, 0.12d, 0.1d, 0.12d, 0d, null,
                        PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            }
            case "ender_trail" -> spawn(owner, at, Particle.PORTAL,
                    3 + tier * 3, 0.18d, 0.12d, 0.18d, 0.15d, null,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            case "prismatic_trail" -> dust(owner, at, rainbow(frame), 1.25f,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            default -> { }
        }
        if (tier >= 4) {
            Location ribbon = at.clone().add(0d, 0.35d + Math.sin(frame * 0.4d) * 0.2d, 0d);
            dust(owner, ribbon, definition.id().equals("prismatic_trail")
                            ? rainbow(frame + 6L) : effectColour(definition),
                    1.4f, PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            spawn(owner, ribbon, Particle.END_ROD, 1, 0.1d, 0.1d, 0.1d, 0d, null,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
        }
        Vector movement = owner.getLocation().toVector().subtract(previous.toVector());
        if (movement.lengthSquared() > 0.0001d) {
            Vector side = new Vector(-movement.getZ(), 0d, movement.getX()).normalize();
            int ribbons = 1 + tier / 2;
            for (int ribbon = 1; ribbon <= ribbons; ribbon++) {
                double offset = 0.22d * ribbon;
                for (double direction : new double[]{-1d, 1d}) {
                    Location sideAt = at.clone().add(side.clone().multiply(offset * direction));
                    sideAt.add(0d, 0.08d + Math.sin(frame * 0.55d + ribbon) * 0.16d, 0d);
                    spawn(owner, sideAt, Particle.DUST, 1, 0d, 0d, 0d, 0d,
                            new Particle.DustOptions(
                                    definition.id().equals("prismatic_trail")
                                            ? rainbow(frame + ribbon * 4L) : effectColour(definition),
                                    0.9f + tier * 0.1f
                            ), PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
                }
            }
        }
        if (tier >= 3 && frame % 3L == 0L) {
            spawn(owner, at.clone().add(0d, 0.4d, 0d), Particle.FIREWORK,
                    tier, 0.18d, 0.25d, 0.18d, 0.04d, null,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
        }
        if (tier >= 4) {
            drawRareTrail(owner, definition, at, movement);
        }
    }

    /** Orbiting comets make top-tier trails visible as an animation, not a floor stain. */
    private void drawRareTrail(
            Player owner, CosmeticCatalog.Definition definition, Location at, Vector movement
    ) {
        Color colour = effectColour(definition);
        Vector backwards = movement.clone();
        if (backwards.lengthSquared() < 0.001d) {
            backwards = owner.getLocation().getDirection();
        }
        backwards.setY(0d);
        if (backwards.lengthSquared() < 0.001d) {
            backwards = new Vector(0d, 0d, 1d);
        }
        backwards.normalize().multiply(-1d);
        Vector side = new Vector(-backwards.getZ(), 0d, backwards.getX()).normalize();
        for (int comet = 0; comet < 3; comet++) {
            double angle = frame * 0.55d + comet * Math.PI * 2d / 3d;
            Location head = at.clone()
                    .add(backwards.clone().multiply(0.45d + comet * 0.25d))
                    .add(side.clone().multiply(Math.cos(angle) * 0.8d))
                    .add(0d, 0.7d + Math.sin(angle) * 0.65d, 0d);
            spawn(owner, head, Particle.DUST, 3, 0.05d, 0.05d, 0.05d, 0d,
                    new Particle.DustOptions(
                            definition.id().equals("prismatic_trail")
                                    ? rainbow(frame + comet * 5L) : colour,
                            1.45f
                    ), PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            spawn(owner, head, Particle.FIREWORK, 1, 0.04d, 0.04d, 0.04d, 0d, null,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
        }
    }

    private void drawKillEffect(
            Player owner, CosmeticCatalog.Definition definition, Location centre
    ) {
        if (definition.secret()) {
            drawSecretKill(owner, definition, centre);
            return;
        }
        switch (definition.id()) {
            case "blood_burst" -> {
                dustBurst(owner, centre, Color.fromRGB(190, 0, 28), 38);
                spawn(owner, centre, Particle.DAMAGE_INDICATOR,
                        14, 0.65d, 0.8d, 0.65d, 0.12d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
            case "frozen_shatter" -> {
                spawn(owner, centre, Particle.SNOWFLAKE,
                        45, 0.8d, 0.9d, 0.8d, 0.12d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                spawn(owner, centre, Particle.CLOUD,
                        15, 0.6d, 0.5d, 0.6d, 0.04d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
            case "shining_light" -> {
                spawn(owner, centre, Particle.END_ROD,
                        42, 0.75d, 1d, 0.75d, 0.16d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                spawn(owner, centre, Particle.FIREWORK,
                        24, 0.55d, 0.8d, 0.55d, 0.08d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
            case "void_collapse" -> {
                spawn(owner, centre, Particle.REVERSE_PORTAL,
                        55, 1d, 1d, 1d, 0.12d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                spawn(owner, centre, Particle.DRAGON_BREATH,
                        18, 0.55d, 0.7d, 0.55d, 0.03d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
            case "soul_requiem" -> {
                spawn(owner, centre, Particle.SOUL,
                        34, 0.7d, 1.2d, 0.7d, 0.08d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                spawn(owner, centre, Particle.SCULK_SOUL,
                        18, 0.45d, 0.9d, 0.45d, 0.04d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
            default -> { }
        }
        sound(owner, centre, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9f, 0.8f,
                PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
        drawKillRings(owner, definition, centre, visualTier(definition));
        drawKillFinale(owner, definition, centre, visualTier(definition));
        if (visualTier(definition) >= 4) {
            drawRareKill(owner, definition, centre, visualTier(definition));
        }
    }

    /** A short vertical vortex and three expanding shockwaves for top-tier kills. */
    private void drawRareKill(
            Player owner, CosmeticCatalog.Definition definition, Location centre, int tier
    ) {
        Color colour = effectColour(definition);
        for (int step = 0; step < 10; step++) {
            int frameStep = step;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                double radius = 0.35d + frameStep * 0.28d;
                for (int point = 0; point < 32; point++) {
                    double angle = point * Math.PI * 2d / 32d + frameStep * 0.22d;
                    double y = Math.sin(angle * 3d) * 0.28d + frameStep * 0.08d;
                    Location at = centre.clone().add(
                            Math.cos(angle) * radius, y, Math.sin(angle) * radius
                    );
                    spawn(owner, at, Particle.DUST, 1, 0d, 0d, 0d, 0d,
                            new Particle.DustOptions(colour, 1.2f + tier * 0.08f),
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                for (int helix = 0; helix < 5; helix++) {
                    double angle = frameStep * 0.65d + helix * Math.PI * 2d / 5d;
                    Location at = centre.clone().add(
                            Math.cos(angle) * 0.7d,
                            frameStep * 0.38d,
                            Math.sin(angle) * 0.7d
                    );
                    spawn(owner, at, Particle.END_ROD, 1, 0d, 0d, 0d, 0d, null,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                if (frameStep == 4 || frameStep == 9) {
                    spawn(owner, centre.clone().add(0d, 1.2d, 0d),
                            frameStep == 9 ? Particle.SONIC_BOOM : Particle.FLASH,
                            1, 0d, 0d, 0d, 0d, null,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                    sound(owner, centre, Sound.ENTITY_WARDEN_SONIC_BOOM,
                            0.75f, frameStep == 9 ? 0.8f : 1.15f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
            }, step * 2L);
        }
    }

    private void drawKillFinale(
            Player owner, CosmeticCatalog.Definition definition, Location centre, int tier
    ) {
        Color colour = effectColour(definition);
        int pulses = 2 + tier;
        for (int pulse = 0; pulse < pulses; pulse++) {
            int step = pulse;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                double radius = 0.55d + step * 0.32d;
                int points = 10 + tier * 4;
                for (int point = 0; point < points; point++) {
                    double longitude = point * Math.PI * 2d / points + step * 0.35d;
                    double latitude = Math.sin(point * 2.399963d + step) * 0.9d;
                    Location at = centre.clone().add(
                            Math.cos(longitude) * Math.cos(latitude) * radius,
                            Math.sin(latitude) * radius + step * 0.12d,
                            Math.sin(longitude) * Math.cos(latitude) * radius
                    );
                    spawn(owner, at, Particle.DUST, 1, 0d, 0d, 0d, 0d,
                            new Particle.DustOptions(colour, 1.05f + tier * 0.12f),
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
                spawn(owner, centre.clone().add(0d, step * 0.22d, 0d),
                        tier >= 4 ? Particle.END_ROD : Particle.ENCHANT,
                        4 + tier * 2, 0.28d, 0.6d + step * 0.08d, 0.28d, 0.05d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                if (step == pulses - 1) {
                    spawn(owner, centre.clone().add(0d, 0.8d, 0d), Particle.FLASH,
                            1, 0d, 0d, 0d, 0d, null,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                    sound(owner, centre, Sound.UI_TOAST_CHALLENGE_COMPLETE,
                            0.7f + tier * 0.08f, 0.8f + tier * 0.08f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
            }, step * 3L);
        }
    }

    private void drawKillRings(
            Player owner, CosmeticCatalog.Definition definition, Location centre, int tier
    ) {
        Color colour = effectColour(definition);
        int pulses = Math.max(1, tier - 1);
        for (int pulse = 0; pulse < pulses; pulse++) {
            int step = pulse;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                double radius = 0.8d + step * 0.45d;
                int points = 12 + tier * 5;
                for (int point = 0; point < points; point++) {
                    double angle = point * Math.PI * 2d / points + step * 0.3d;
                    Location at = centre.clone().add(
                            Math.cos(angle) * radius,
                            Math.sin(angle * 2d + step) * 0.35d,
                            Math.sin(angle) * radius
                    );
                    spawn(owner, at, Particle.DUST, 1, 0d, 0d, 0d, 0d,
                            new Particle.DustOptions(colour, 1.2f + tier * 0.1f),
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                    if (tier >= 4 && point % 4 == 0) {
                        spawn(owner, at, Particle.END_ROD, 1, 0d, 0d, 0d, 0d, null,
                                PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                    }
                }
                if (tier >= 3) {
                    sound(owner, centre, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE,
                            0.65f + tier * 0.08f, 0.8f + step * 0.15f,
                            PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                }
            }, step * 4L);
        }
    }

    private void drawSecretAura(
            Player owner, CosmeticCatalog.Definition definition, Location centre, double phase
    ) {
        switch (definition.id()) {
            case "astral_sovereign" -> {
                for (int point = 0; point < 12; point++) {
                    double angle = phase * 0.55d + point * Math.PI * 2d / 12d;
                    double radius = point % 3 == 0 ? 1.55d : 1.05d;
                    Location at = centre.clone().add(
                            Math.cos(angle) * radius,
                            Math.sin(angle * 2d + phase) * 0.75d,
                            Math.sin(angle) * radius
                    );
                    dust(owner, at, point % 2 == 0
                                    ? Color.fromRGB(120, 85, 255) : Color.fromRGB(120, 235, 255),
                            point % 3 == 0 ? 1.45f : 1.05f,
                            PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                    if (point % 4 == 0) {
                        spawn(owner, at, Particle.END_ROD, 1, 0d, 0d, 0d, 0d, null,
                                PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                    }
                }
                spawn(owner, centre, Particle.ENCHANT,
                        5, 0.65d, 1d, 0.65d, 0.05d, null,
                        PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
            }
            case "infernal_dominion" -> {
                for (int point = 0; point < 16; point++) {
                    double angle = -phase * 0.8d + point * Math.PI * 2d / 16d;
                    double radius = 1.15d + Math.sin(phase + point) * 0.12d;
                    Location at = centre.clone().add(
                            Math.cos(angle) * radius, -0.85d, Math.sin(angle) * radius
                    );
                    dust(owner, at, Color.fromRGB(255, point % 2 == 0 ? 55 : 145, 8),
                            1.25f, PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                }
                Location crown = centre.clone().add(0d, 1.05d, 0d);
                spawn(owner, crown, Particle.FLAME,
                        7, 0.38d, 0.16d, 0.38d, 0.015d, null,
                        PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                spawn(owner, centre, Particle.LAVA,
                        2, 0.75d, 0.5d, 0.75d, 0d, null,
                        PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
            }
            case "abyssal_seraph" -> {
                Vector backwards = owner.getLocation().getDirection().setY(0d);
                if (backwards.lengthSquared() < 0.001d) {
                    backwards = new Vector(0d, 0d, 1d);
                }
                backwards.normalize().multiply(-1d);
                Vector side = new Vector(-backwards.getZ(), 0d, backwards.getX()).normalize();
                for (int wing = 0; wing < 3; wing++) {
                    for (double direction : new double[]{-1d, 1d}) {
                        for (int point = 0; point < 6; point++) {
                            double progress = point / 5d;
                            Location at = centre.clone()
                                    .add(backwards.clone().multiply(0.25d + progress * 0.6d))
                                    .add(side.clone().multiply(direction * progress * (0.8d + wing * 0.4d)))
                                    .add(0d, -0.25d + wing * 0.58d + Math.sin(phase + point) * 0.08d, 0d);
                            dust(owner, at, wing == 1
                                            ? Color.fromRGB(105, 35, 205) : Color.fromRGB(25, 0, 45),
                                    1.25f, PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                        }
                    }
                }
                spawn(owner, centre, Particle.DRAGON_BREATH,
                        5, 0.45d, 0.8d, 0.45d, 0.025d, null,
                        PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
            }
            default -> { }
        }
    }

    private void drawSecretTrail(
            Player owner, CosmeticCatalog.Definition definition, Location at
    ) {
        switch (definition.id()) {
            case "galaxy_wake" -> {
                dust(owner, at.clone().add(0d, 0.35d, 0d), rainbow(frame), 1.55f,
                        PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
                spawn(owner, at, Particle.REVERSE_PORTAL,
                        9, 0.35d, 0.3d, 0.35d, 0.08d, null,
                        PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
                spawn(owner, at.clone().add(0d, 0.5d, 0d), Particle.END_ROD,
                        2, 0.25d, 0.25d, 0.25d, 0d, null,
                        PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            }
            case "phantom_chains" -> {
                Vector backwards = owner.getLocation().getDirection().setY(0d);
                if (backwards.lengthSquared() < 0.001d) {
                    backwards = new Vector(0d, 0d, 1d);
                }
                backwards.normalize().multiply(-1d);
                for (int link = 0; link < 4; link++) {
                    Location chain = at.clone().add(backwards.clone().multiply(link * 0.38d))
                            .add(0d, 0.25d + Math.sin(frame * 0.6d + link) * 0.18d, 0d);
                    dust(owner, chain, Color.fromRGB(55, 220, 235), 1.15f,
                            PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
                }
                spawn(owner, at, Particle.SOUL_FIRE_FLAME,
                        3, 0.24d, 0.18d, 0.24d, 0.01d, null,
                        PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            }
            case "reality_fracture" -> {
                Vector side = owner.getLocation().getDirection().setY(0d);
                if (side.lengthSquared() < 0.001d) {
                    side = new Vector(1d, 0d, 0d);
                }
                side = new Vector(-side.getZ(), 0d, side.getX()).normalize();
                for (int crack = -2; crack <= 2; crack++) {
                    Location fracture = at.clone().add(side.clone().multiply(crack * 0.24d))
                            .add(0d, 0.18d + Math.abs(crack) * 0.16d, 0d);
                    dust(owner, fracture, crack % 2 == 0
                                    ? Color.fromRGB(255, 35, 220) : Color.fromRGB(45, 220, 255),
                            1.35f, PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
                }
                spawn(owner, at, Particle.ELECTRIC_SPARK,
                        4, 0.45d, 0.35d, 0.45d, 0.06d, null,
                        PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            }
            default -> { }
        }
    }

    private void drawSecretKill(
            Player owner, CosmeticCatalog.Definition definition, Location centre
    ) {
        switch (definition.id()) {
            case "event_horizon" -> {
                spawn(owner, centre, Particle.FLASH, 2, 0d, 0d, 0d, 0d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                spawn(owner, centre, Particle.SONIC_BOOM, 1, 0d, 0d, 0d, 0d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                spawn(owner, centre, Particle.DRAGON_BREATH,
                        65, 1.2d, 1.5d, 1.2d, 0.1d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                spawn(owner, centre, Particle.REVERSE_PORTAL,
                        80, 1.4d, 1.6d, 1.4d, 0.18d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                sound(owner, centre, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.4f, 0.65f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                sound(owner, centre, Sound.BLOCK_END_PORTAL_SPAWN, 1.2f, 1.25f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
            case "reapers_verdict" -> {
                spawn(owner, centre, Particle.SWEEP_ATTACK,
                        14, 1.25d, 1.1d, 1.25d, 0d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                spawn(owner, centre, Particle.SOUL,
                        70, 1.1d, 1.7d, 1.1d, 0.12d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                spawn(owner, centre, Particle.SQUID_INK,
                        45, 0.9d, 1.1d, 0.9d, 0.08d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                sound(owner, centre, Sound.ENTITY_WITHER_SPAWN, 1.15f, 1.45f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                sound(owner, centre, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.4f, 0.55f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
            case "divine_rupture" -> {
                spawn(owner, centre, Particle.FLASH, 3, 0d, 0.6d, 0d, 0d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                spawn(owner, centre, Particle.ELECTRIC_SPARK,
                        85, 1.25d, 2.1d, 1.25d, 0.18d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                spawn(owner, centre, Particle.END_ROD,
                        65, 0.65d, 2.6d, 0.65d, 0.14d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                spawn(owner, centre, Particle.TOTEM_OF_UNDYING,
                        55, 1.15d, 1.6d, 1.15d, 0.16d, null,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                sound(owner, centre, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.5f, 0.75f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
                sound(owner, centre, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.1f, 1.15f,
                        PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
            }
            default -> { }
        }
    }

    private void dust(
            Player owner,
            Location location,
            Color colour,
            float size,
            PlayerSettingsStore.Setting ownSetting
    ) {
        spawn(owner, location, Particle.DUST, 2, 0.05d, 0.05d, 0.05d, 0d,
                new Particle.DustOptions(colour, size), ownSetting);
    }

    private void dustBurst(Player owner, Location location, Color colour, int count) {
        spawn(owner, location, Particle.DUST, count, 0.75d, 0.9d, 0.75d, 0.08d,
                new Particle.DustOptions(colour, 1.35f),
                PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
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
        for (Player viewer : viewers(owner, location, ownSetting)) {
            if (data == null) {
                viewer.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, extra);
            } else {
                viewer.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, extra, data);
            }
        }
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

    private static int visualTier(CosmeticCatalog.Definition definition) {
        if (definition.secret()) {
            return 5;
        }
        if (definition.weight() >= 2_000) {
            return 1;
        }
        if (definition.weight() >= 500) {
            return 2;
        }
        if (definition.weight() >= 100) {
            return 3;
        }
        return 4;
    }

    private static Color effectColour(CosmeticCatalog.Definition definition) {
        return switch (definition.id()) {
            case "blood_burst", "blood_trail", "crimson_orbit" -> Color.fromRGB(195, 8, 32);
            case "frozen_shatter", "frost_trail", "celestial_crown" ->
                    Color.fromRGB(175, 225, 255);
            case "shining_light", "solar_orbit", "ember_trail" -> Color.fromRGB(255, 190, 35);
            case "emerald_orbit", "drool_trail" -> Color.fromRGB(30, 225, 125);
            case "soul_requiem" -> Color.fromRGB(30, 210, 225);
            default -> Color.fromRGB(165, 55, 240);
        };
    }
}
