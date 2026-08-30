package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Finds a generated, solid overworld landing point without blocking the server thread. */
final class RandomTeleportService implements CommandExecutor {
    private static final Set<Material> DANGEROUS = Set.of(
            Material.WATER,
            Material.LAVA,
            Material.CACTUS,
            Material.MAGMA_BLOCK,
            Material.POWDER_SNOW,
            Material.FIRE,
            Material.SOUL_FIRE,
            Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE,
            Material.SWEET_BERRY_BUSH
    );
    private static final int BORDER_MARGIN = 32;

    private final MGXAccessBridge plugin;
    private final TeleportWarmupService warmups;
    private final int minimumRadius;
    private final int maximumRadius;
    private final int attempts;
    private final Set<UUID> searching = ConcurrentHashMap.newKeySet();

    RandomTeleportService(MGXAccessBridge plugin, TeleportWarmupService warmups) {
        this.plugin = plugin;
        this.warmups = warmups;
        this.minimumRadius = Math.max(0, plugin.getConfig().getInt("rtp.minimum-radius", 500));
        this.maximumRadius = Math.max(
                minimumRadius + 1,
                plugin.getConfig().getInt("rtp.maximum-radius", 25_000)
        );
        this.attempts = Math.max(1, Math.min(100, plugin.getConfig().getInt("rtp.attempts", 24)));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("RTP is available to players only.");
            return true;
        }
        if (player.getWorld().getEnvironment() != World.Environment.NORMAL) {
            error(player, "RTP is available in the overworld only.");
            return true;
        }
        // A teleport out of a fight is a fight nobody can finish. CombatLog's blanket
        // teleport block used to cover this by accident; it does not list /rtp, and it
        // is being relaxed so ender pearls work, so the refusal belongs here where it
        // is a deliberate rule rather than a side effect of somebody else's config.
        AfkService afk = plugin.afkService();
        if (afk != null && afk.inCombat(player)) {
            error(player, "You cannot random teleport while in combat.");
            return true;
        }
        if (!searching.add(player.getUniqueId())) {
            info(player, "Already looking for a safe location.");
            return true;
        }
        info(player, "Looking for a safe location...");
        tryCandidate(player, 0);
        return true;
    }

    private void tryCandidate(Player player, int attempted) {
        if (!player.isOnline()) {
            searching.remove(player.getUniqueId());
            return;
        }
        if (attempted >= attempts) {
            searching.remove(player.getUniqueId());
            error(player, "No safe location was found. Try again in a moment.");
            return;
        }
        World world = player.getWorld();
        WorldBorder border = world.getWorldBorder();
        double borderRadius = Math.max(0d, border.getSize() / 2d - BORDER_MARGIN);
        double usableMaximum = Math.min(maximumRadius, borderRadius);
        if (usableMaximum <= minimumRadius) {
            searching.remove(player.getUniqueId());
            error(player, "The world border is too small for RTP.");
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double radius = radius(minimumRadius, usableMaximum, random.nextDouble());
        double angle = random.nextDouble(0d, Math.PI * 2d);
        int x = (int) Math.floor(border.getCenter().getX() + Math.cos(angle) * radius);
        int z = (int) Math.floor(border.getCenter().getZ() + Math.sin(angle) * radius);
        world.getChunkAtAsync(x >> 4, z >> 4, true).whenComplete((chunk, failure) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (failure != null || chunk == null) {
                        tryCandidate(player, attempted + 1);
                        return;
                    }
                    Location safe = safeLocation(world, x, z);
                    if (safe == null) {
                        tryCandidate(player, attempted + 1);
                        return;
                    }
                    searching.remove(player.getUniqueId());
                    warmups.begin(player, safe);
                })
        );
    }

    private static Location safeLocation(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (y <= world.getMinHeight() || y + 2 >= world.getMaxHeight()) {
            return null;
        }
        Block floor = world.getBlockAt(x, y, z);
        Block feet = world.getBlockAt(x, y + 1, z);
        Block head = world.getBlockAt(x, y + 2, z);
        if (!floor.getType().isSolid()
                || DANGEROUS.contains(floor.getType())
                || !feet.isPassable()
                || !head.isPassable()) {
            return null;
        }
        return new Location(world, x + 0.5d, y + 1d, z + 0.5d);
    }

    /** Uniform by area, so the outer half of the circle is not underrepresented. */
    static double radius(double minimum, double maximum, double sample) {
        double clamped = Math.max(0d, Math.min(Math.nextDown(1d), sample));
        return Math.sqrt(minimum * minimum
                + clamped * (maximum * maximum - minimum * minimum));
    }

    private static void info(Player player, String message) {
        player.sendMessage(prefix().append(Component.text(message, NamedTextColor.GRAY)));
    }

    private static void error(Player player, String message) {
        player.sendMessage(prefix().append(Component.text(message, NamedTextColor.RED)));
    }

    private static Component prefix() {
        return Component.text("RTP » ", NamedTextColor.GOLD, TextDecoration.BOLD);
    }
}
