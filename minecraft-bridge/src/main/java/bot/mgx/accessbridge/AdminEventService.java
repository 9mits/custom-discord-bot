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
        if (token.equals("controls") || token.equals("options")) {
            return controls();
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
            // Alfredo reads "<health> [keys] [diamonds]" rather than the usual
            // duration/radius pair, and takes a live retune while he is fighting.
            case ALFREDO -> {
                // Live controls first: these act on a boss already fighting and
                // never spawn a second one.
                String sub = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
                yield switch (sub) {
                    case "hp", "health" -> chaosService.retuneAlfredo(
                            bounded(args, 3, 2_000, 20, 100_000, "health"));
                    case "keys" -> chaosService.setAlfredoLoot(
                            bounded(args, 3, ChaosService.ALFREDO_DEFAULT_KEYS, 0, 2_000, "keys"),
                            null);
                    case "diamonds" -> chaosService.setAlfredoLoot(null,
                            bounded(args, 3, ChaosService.ALFREDO_DEFAULT_DIAMONDS, 0, 5_000,
                                    "diamonds"));
                    case "add" -> chaosService.addAlfredoLoot(
                            bounded(args, 3, 10, 0, 2_000, "keys"),
                            bounded(args, 4, 0, 0, 5_000, "diamonds"));
                    case "burst" -> chaosService.forceAlfredoBurst();
                    case "kill" -> chaosService.killAlfredo();
                    case "status" -> chaosService.alfredoStatus();
                    default -> {
                        boolean test = sub.equals("test");
                        int health = test
                                ? (int) ChaosService.ALFREDO_DEFAULT_HEALTH
                                : bounded(args, 2, (int) ChaosService.ALFREDO_DEFAULT_HEALTH,
                                        20, 100_000, "health");
                        int keys = test ? ChaosService.ALFREDO_DEFAULT_KEYS
                                : bounded(args, 3, ChaosService.ALFREDO_DEFAULT_KEYS,
                                        0, 2_000, "keys");
                        int diamonds = test ? ChaosService.ALFREDO_DEFAULT_DIAMONDS
                                : bounded(args, 4, ChaosService.ALFREDO_DEFAULT_DIAMONDS,
                                        0, 5_000, "diamonds");
                        yield chaosService.summonAlfredo(
                                player, health, keys, diamonds,
                                ChaosTargeting.radiusOrThrow(null, plugin.getConfig().getDouble(
                                        "abuse-radius", ChaosTargeting.DEFAULT_RADIUS
                                )),
                                test
                        );
                    }
                };
            }
            // Payout events take their key total up front, so an operator can
            // decide how much an event is worth before running it.
            case AIRDROP -> chaosService.run(
                    player, effect, 0,
                    ChaosTargeting.radiusOrThrow(args.length > 3 ? args[3] : null, configuredRadius()),
                    bounded(args, 2, ChaosService.DEFAULT_AIRDROP_KEYS, 1, 2_000, "keys"));
            case JACKPOT -> chaosService.run(
                    player, effect, 0,
                    ChaosTargeting.radiusOrThrow(args.length > 3 ? args[3] : null, configuredRadius()),
                    bounded(args, 2, 0, 0, 2_000, "keys"));
            case PINATA -> chaosService.run(
                    player, effect,
                    effect.secondsOrThrow(args.length > 2 ? args[2] : null),
                    ChaosTargeting.radiusOrThrow(args.length > 4 ? args[4] : null, configuredRadius()),
                    bounded(args, 3, 0, 0, 2_000, "keys"));
            default -> {
                // Timed effects read "<seconds> [radius]"; one-shots read "[radius]".
                int durationIndex = effect.timed() ? 2 : -1;
                int radiusIndex = effect.timed() ? 3 : 2;
                yield chaosService.run(
                        player,
                        effect,
                        effect.secondsOrThrow(
                                durationIndex > 0 && args.length > durationIndex
                                        ? args[durationIndex] : null
                        ),
                        ChaosTargeting.radiusOrThrow(
                                args.length > radiusIndex ? args[radiusIndex] : null,
                                plugin.getConfig().getDouble(
                                        "abuse-radius", ChaosTargeting.DEFAULT_RADIUS
                                )
                        )
                );
            }
        };
    }

    private double configuredRadius() {
        return plugin.getConfig().getDouble("abuse-radius", ChaosTargeting.DEFAULT_RADIUS);
    }

    /** The one-line reminder, and the full menu behind {@code abuse list}. */
    private static String usage() {
        return "Usage: /mgxadmin abuse <effect> [seconds] [radius] - try /mgxadmin abuse list";
    }

    /** Everything an operator can tune, in one place. */
    private static String controls() {
        return "Payout events take a key total:"
                + "\n  /mgxadmin abuse keyrain <keys> [radius]"
                + "\n  /mgxadmin abuse airdrop <keys> [radius]"
                + "\n  /mgxadmin abuse jackpot <keys> [radius]"
                + "\n  /mgxadmin abuse pinata <seconds> <keys> [radius]"
                + "\nAlfredo, on spawn:"
                + "\n  /mgxadmin abuse alfredo <health> <keys> <diamonds>"
                + "\n  /mgxadmin abuse alfredo test   - he dies on his own in about a minute"
                + "\nAlfredo, mid-fight:"
                + "\n  /mgxadmin abuse alfredo hp <n>          retune his health"
                + "\n  /mgxadmin abuse alfredo keys <n>        set what he still owes"
                + "\n  /mgxadmin abuse alfredo diamonds <n>    set what he still owes"
                + "\n  /mgxadmin abuse alfredo add <keys> <diamonds>   top up the finale"
                + "\n  /mgxadmin abuse alfredo burst           make him pay out now"
                + "\n  /mgxadmin abuse alfredo kill            end it with the full finale"
                + "\n  /mgxadmin abuse alfredo status          what is left";
    }

    private static String menu() {
        StringBuilder text = new StringBuilder(
                "Admin events. Reach defaults to " + (int) ChaosTargeting.DEFAULT_RADIUS
                        + " blocks around you; AFK players are always skipped.");
        for (ChaosCatalog effect : ChaosCatalog.menu()) {
            text.append("\n  ").append(effect.id());
            if (effect.timed()) {
                text.append(" [seconds, default ").append(effect.defaultSeconds()).append("]");
            }
            text.append(" [radius]");
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
