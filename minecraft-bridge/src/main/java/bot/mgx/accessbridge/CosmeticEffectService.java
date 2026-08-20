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
            active(player, CosmeticCatalog.Category.SECRET).ifPresent(
                    definition -> drawSecretAura(player)
            );
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
        if (active(killer, CosmeticCatalog.Category.SECRET).isPresent()) {
            drawSecretKill(killer, centre);
        }
    }

    void playSecretReveal(Player player) {
        player.showTitle(Title.title(
                Component.text("UNKNOWN COSMETIC", NamedTextColor.DARK_PURPLE),
                Component.text("Chance: ???", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofSeconds(1))
        ));
        Component announcement = PlayerMenuService.prefix()
                .append(Component.text(player.getName(), NamedTextColor.LIGHT_PURPLE))
                .append(Component.text(" uncovered an unknown cosmetic.", NamedTextColor.WHITE));
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (settings.isEnabled(
                    viewer.getUniqueId(), PlayerSettingsStore.Setting.CHAT_NOTIFICATIONS
            )) {
                viewer.sendMessage(announcement);
            }
        }
        plugin.getServer().getConsoleSender().sendMessage(announcement);
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
        for (int point = 0; point < 3; point++) {
            double angle = phase + point * Math.PI * 2d / 3d;
            Location at = centre.clone().add(
                    Math.cos(angle) * 0.85d,
                    Math.sin(phase * 0.55d + point) * 0.38d,
                    Math.sin(angle) * 0.85d
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
                case "celestial_crown" -> {
                    dust(owner, at.clone().add(0d, 1d, 0d),
                            Color.fromRGB(210, 235, 255), 1.3f,
                            PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                    spawn(owner, at.clone().add(0d, 1d, 0d), Particle.END_ROD,
                            1, 0d, 0d, 0d, 0d, null,
                            PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
                }
                default -> { }
            }
        }
    }

    private void drawTrail(Player owner, CosmeticCatalog.Definition definition, Location previous) {
        Location at = previous.clone().add(0d, 0.18d, 0d);
        switch (definition.id()) {
            case "ember_trail" -> spawn(owner, at, Particle.SMALL_FLAME,
                    4, 0.18d, 0.08d, 0.18d, 0.01d, null,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            case "blood_trail" -> dust(owner, at, Color.fromRGB(135, 0, 20), 1.1f,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            case "frost_trail" -> spawn(owner, at, Particle.SNOWFLAKE,
                    4, 0.2d, 0.1d, 0.2d, 0.01d, null,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            case "cherry_blossom_trail" -> spawn(owner, at, Particle.CHERRY_LEAVES,
                    3, 0.22d, 0.12d, 0.22d, 0.01d, null,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            case "drool_trail" -> {
                dust(owner, at, Color.fromRGB(45, 220, 210), 1.05f,
                        PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
                spawn(owner, at, Particle.FALLING_WATER,
                        2, 0.12d, 0.1d, 0.12d, 0d, null,
                        PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            }
            case "ender_trail" -> spawn(owner, at, Particle.PORTAL,
                    5, 0.18d, 0.12d, 0.18d, 0.15d, null,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            case "prismatic_trail" -> dust(owner, at, rainbow(frame), 1.25f,
                    PlayerSettingsStore.Setting.OWN_TRAIL_VISIBLE);
            default -> { }
        }
    }

    private void drawKillEffect(
            Player owner, CosmeticCatalog.Definition definition, Location centre
    ) {
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
    }

    private void drawSecretAura(Player owner) {
        if (frame % 2L != 0L) {
            return;
        }
        Location centre = owner.getLocation().add(0d, 1d, 0d);
        double angle = frame * 0.22d;
        for (int point = 0; point < 6; point++) {
            double atAngle = angle + point * Math.PI / 3d;
            Location at = centre.clone().add(
                    Math.cos(atAngle) * 1.15d,
                    Math.sin(angle + point) * 0.65d,
                    Math.sin(atAngle) * 1.15d
            );
            dust(owner, at, Color.fromRGB(25, 0, 40), 1.35f,
                    PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
        }
        spawn(owner, centre, Particle.REVERSE_PORTAL,
                4, 0.45d, 0.8d, 0.45d, 0.08d, null,
                PlayerSettingsStore.Setting.OWN_AURA_VISIBLE);
    }

    private void drawSecretKill(Player owner, Location centre) {
        spawn(owner, centre, Particle.FLASH, 2, 0d, 0d, 0d, 0d, null,
                PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
        spawn(owner, centre, Particle.SONIC_BOOM, 1, 0d, 0d, 0d, 0d, null,
                PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
        spawn(owner, centre, Particle.TOTEM_OF_UNDYING,
                65, 1.3d, 1.5d, 1.3d, 0.18d, null,
                PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
        spawn(owner, centre, Particle.DRAGON_BREATH,
                55, 1.1d, 1.3d, 1.1d, 0.08d, null,
                PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
        spawn(owner, centre, Particle.END_ROD,
                50, 1d, 1.6d, 1d, 0.2d, null,
                PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
        sound(owner, centre, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.4f, 0.65f,
                PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
        sound(owner, centre, Sound.BLOCK_END_PORTAL_SPAWN, 1.2f, 1.25f,
                PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE);
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
}
