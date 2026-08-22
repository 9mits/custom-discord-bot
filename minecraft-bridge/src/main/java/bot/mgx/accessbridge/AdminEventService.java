package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Safe, theatrical operator events inspired by live-service admin events. */
final class AdminEventService {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private final MGXAccessBridge plugin;
    private final CrateItems crateItems;
    private final ChaosService chaosService;

    AdminEventService(MGXAccessBridge plugin, CrateItems crateItems, ChaosService chaosService) {
        this.plugin = plugin;
        this.crateItems = crateItems;
        this.chaosService = chaosService;
    }

    String run(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            throw new IllegalArgumentException("Admin events must be started in game.");
        }
        if (args.length < 2) {
            throw new IllegalArgumentException(usage());
        }
        String token = args[1].toLowerCase(Locale.ROOT);
        if (token.equals("list") || token.equals("help")) {
            return menu();
        }
        ChaosCatalog effect = ChaosCatalog.resolve(token)
                .orElseThrow(() -> new IllegalArgumentException(usage()));
        return switch (effect) {
            case STOP -> {
                chaosService.stopAll();
                yield "Stopped every running event and restored everything";
            }
            case KEYRAIN -> {
                int amount = bounded(args, 2, 50, 1, 250, "key amount");
                int radius = bounded(args, 3, 12, 4, 30, "radius");
                keyRain(player, amount, radius);
                yield "Started a " + amount + "-key rain within " + radius + " blocks";
            }
            case SKYBURST -> {
                int seconds = bounded(args, 2, 15, 3, 60, "duration");
                skyBurst(player, seconds);
                yield "Started a " + seconds + "-second skyburst";
            }
            default -> chaosService.run(
                    effect,
                    effect.secondsOrThrow(args.length > 2 ? args[2] : null)
            );
        };
    }

    /** The one-line reminder, and the full menu behind {@code abuse list}. */
    private static String usage() {
        return "Usage: /mgxadmin abuse <effect> [seconds] — try /mgxadmin abuse list";
    }

    private static String menu() {
        StringBuilder text = new StringBuilder("Admin events:");
        for (ChaosCatalog effect : ChaosCatalog.menu()) {
            text.append("\n  ").append(effect.id());
            if (effect.timed()) {
                text.append(" [seconds, default ").append(effect.defaultSeconds()).append("]");
            }
            text.append(" - ").append(effect.blurb());
        }
        return text.toString();
    }

    private void keyRain(Player anchor, int amount, int radius) {
        announce(Component.text("CRATE KEY RAIN!", ORANGE, TextDecoration.BOLD)
                .append(Component.text(" Look up — " + amount + " keys are falling!",
                        NamedTextColor.WHITE)));
        World world = anchor.getWorld();
        Location centre = anchor.getLocation().clone();
        for (int index = 0; index < amount; index++) {
            int delay = index * 2;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                double angle = random.nextDouble(Math.PI * 2d);
                double distance = Math.sqrt(random.nextDouble()) * radius;
                Location at = centre.clone().add(
                        Math.cos(angle) * distance,
                        10d + random.nextDouble(7d),
                        Math.sin(angle) * distance
                );
                Item drop = world.dropItem(at, crateItems.key(1));
                drop.setGlowing(true);
                drop.setPickupDelay(20);
                drop.setVelocity(new org.bukkit.util.Vector(
                        random.nextDouble(-0.05d, 0.05d),
                        random.nextDouble(-0.03d, 0.03d),
                        random.nextDouble(-0.05d, 0.05d)
                ));
                world.spawnParticle(Particle.FIREWORK, at, 5, 0.2d, 0.2d, 0.2d, 0.03d);
                if (delay % 20 == 0) {
                    world.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f, 1.25f);
                }
            }, delay);
        }
    }

    private void skyBurst(Player anchor, int seconds) {
        announce(Component.text("ADMIN SKYBURST!", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                .append(Component.text(" The sky is awake.", NamedTextColor.WHITE)));
        World world = anchor.getWorld();
        Location centre = anchor.getLocation().clone();
        int frames = seconds * 4;
        for (int frame = 0; frame < frames; frame++) {
            int step = frame;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                for (int burst = 0; burst < 3; burst++) {
                    Location at = centre.clone().add(
                            random.nextDouble(-18d, 18d),
                            random.nextDouble(7d, 16d),
                            random.nextDouble(-18d, 18d)
                    );
                    Color colour = Color.fromRGB(
                            random.nextInt(70, 256), random.nextInt(40, 220), random.nextInt(90, 256)
                    );
                    world.spawnParticle(Particle.DUST, at, 35, 1.5d, 1.5d, 1.5d, 0.05d,
                            new Particle.DustOptions(colour, 1.6f));
                    world.spawnParticle(Particle.END_ROD, at, 12, 1d, 1d, 1d, 0.08d);
                    world.spawnParticle(Particle.FLASH, at, 1, 0d, 0d, 0d, 0d);
                    world.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 0.8f,
                            0.8f + random.nextFloat() * 0.6f);
                }
            }, step * 5L);
        }
    }

    private void announce(Component message) {
        Component announcement = Component.text("SERVER » ", ORANGE, TextDecoration.BOLD)
                .append(message);
        plugin.getServer().getOnlinePlayers().forEach(player -> player.sendMessage(announcement));
        plugin.getServer().getConsoleSender().sendMessage(announcement);
    }

    private static int bounded(
            String[] args, int index, int fallback, int minimum, int maximum, String label
    ) {
        if (args.length <= index) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(args[index]);
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(
                        label + " must be between " + minimum + " and " + maximum + "."
                );
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be a whole number.");
        }
    }
}
