package bot.mgx.accessbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Persistent, reusable physical crate chests and Bedrock-visible floating labels. */
final class CrateDisplayService implements CommandExecutor, TabCompleter, Listener {
    private static final String TAG = "mgx_crate_display";
    /** The ticking line, retitled in place every second. */
    private static final String COUNTDOWN_TAG = "mgx_crate_countdown";
    /** The banner directly above it, which changes wording when the event closes. */
    private static final String HEADLINE_TAG = "mgx_crate_countdown_headline";
    private static final double LINE_HEIGHT = 0.28d;
    /** Where the lowest label hangs; the stack grows upward from here. */
    private static final double BOTTOM_LINE = 1.47d;
    /** Particles tick every four; five of those is one second. */
    private static final int COUNTDOWN_FRAMES = 5;
    private static final Particle.DustOptions AMETHYST_BRIGHT = new Particle.DustOptions(
            Color.fromRGB(196, 105, 255), 1.05f
    );
    private static final Particle.DustOptions AMETHYST_DEEP = new Particle.DustOptions(
            Color.fromRGB(112, 48, 220), 0.9f
    );
    private static final Particle.DustOptions DEFAULT_ORANGE = new Particle.DustOptions(
            Color.fromRGB(255, 139, 20), 0.95f
    );
    private static final Particle.DustOptions DEFAULT_GOLD = new Particle.DustOptions(
            Color.fromRGB(255, 205, 55), 0.8f
    );
    private record Placement(CrateKind kind, UUID worldId, int x, int y, int z) { }

    private final MGXAccessBridge plugin;
    private final Path file;
    private final CrateService crates;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final List<Placement> placements = new ArrayList<>();
    private BukkitTask particleTask;
    private int particleFrame;

    CrateDisplayService(MGXAccessBridge plugin, Path file, CrateService crates) throws IOException {
        this.plugin = plugin;
        this.file = file;
        this.crates = crates;
        Files.createDirectories(file.getParent());
        load();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // One admin gate for the whole plugin. Bukkit's own `permission:` check on this
        // command currently makes the difference invisible, but the moment that line
        // moves — as the administrative redesign requires — a second, differently
        // spelled check would start answering differently from every other command.
        // Bukkit hasPermission also honours `default:`, which Floodgate players can
        // satisfy before their attachments exist; mayAdminister is isOp plus an
        // explicit LuckPerms node, and is what the rest of the plugin asks.
        if (!plugin.mayAdminister(sender)) {
            if (sender instanceof Player player) {
                PlayerMenuService.error(player, "You do not have permission to place crates.");
            } else {
                sender.sendMessage("You do not have permission to place crates.");
            }
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Place physical crates in game.");
            return true;
        }
        try {
            if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
                CrateKind kind = CrateKind.from(args[1]).orElseThrow(
                        () -> new IllegalArgumentException("Use default, amethyst, or shard.")
                );
                place(player, kind);
                player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                        "Placed the " + kind.displayName() + ".", NamedTextColor.GREEN
                )));
            } else if (args.length == 1 && args[0].equalsIgnoreCase("remove")) {
                remove(player);
                player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                        "Removed that physical crate.", NamedTextColor.GREEN
                )));
            } else {
                throw new IllegalArgumentException(
                        "Use /cratehologram set <default|amethyst|shard> while looking at a chest, "
                                + "or remove."
                );
            }
        } catch (IllegalArgumentException | IOException exception) {
            PlayerMenuService.error(player, exception.getMessage());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return prefix(args[0], List.of("set", "remove"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            return prefix(args[1], List.of("default", "amethyst", "shard"));
        }
        return List.of();
    }

    private void place(Player player, CrateKind kind) throws IOException {
        org.bukkit.block.Block block = player.getTargetBlockExact(7);
        if (block == null || block.getType() != Material.CHEST) {
            throw new IllegalArgumentException("Look directly at the chest to turn into a crate.");
        }
        Placement placement = new Placement(
                kind, block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()
        );
        List<Placement> before = List.copyOf(placements);
        placements.removeIf(row -> sameBlock(row, block.getLocation()) || row.kind() == kind);
        placements.add(placement);
        persistOrRestore(before);
        refresh();
    }

    private void remove(Player player) throws IOException {
        org.bukkit.block.Block block = player.getTargetBlockExact(7);
        if (block == null) {
            throw new IllegalArgumentException("Look at the crate chest you want to remove.");
        }
        List<Placement> before = List.copyOf(placements);
        if (!placements.removeIf(row -> sameBlock(row, block.getLocation()))) {
            throw new IllegalArgumentException("That chest is not a physical crate.");
        }
        persistOrRestore(before);
        refresh();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Placement placement = placementAt(event.getClickedBlock().getLocation());
        if (placement == null) {
            return;
        }
        event.setCancelled(true);
        crates.openFor(event.getPlayer(), placement.kind());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        if (placementAt(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
            PlayerMenuService.error(event.getPlayer(), "Physical crate chests cannot be broken.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> placementAt(block.getLocation()) != null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> placementAt(block.getLocation()) != null);
    }

    void refresh() {
        clearLabels();
        long now = System.currentTimeMillis();
        for (Placement placement : List.copyOf(placements)) {
            World world = Bukkit.getWorld(placement.worldId());
            if (world == null) {
                continue;
            }
            CrateKind kind = placement.kind();
            List<Component> lines = new ArrayList<>();
            lines.add(Component.text(kind.displayName(), kind.colour(), TextDecoration.BOLD));
            lines.add(Component.text(keyLine(kind), NamedTextColor.WHITE));
            lines.addAll(kind.countdownLines(now));
            // The stack hangs from a fixed bottom line and grows upward, so the two
            // extra lines a limited crate carries cannot push anything into the chest.
            for (int index = 0; index < lines.size(); index++) {
                double height = BOTTOM_LINE + LINE_HEIGHT * (lines.size() - 1 - index);
                ArmorStand stand = spawnLabel(
                        new Location(world, placement.x() + 0.5d,
                                placement.y() + height, placement.z() + 0.5d),
                        lines.get(index)
                );
                if (kind.limited() && index == lines.size() - 1) {
                    stand.addScoreboardTag(COUNTDOWN_TAG);
                } else if (kind.limited() && index == lines.size() - 2) {
                    stand.addScoreboardTag(HEADLINE_TAG);
                }
            }
        }
        startParticles();
    }

    /** The key line, which is not the same sentence for a crate that costs two. */
    private String keyLine(CrateKind kind) {
        int cost = crates.keyCost(kind);
        return cost + " " + kind.currency().fullName(cost);
    }

    /**
     * Retitles the hanging countdown once a second.
     *
     * <p>The stands are found by tag at their known position rather than held as
     * references: they are persistent, so a chunk unload and reload replaces the
     * entity object and any reference kept here would leave the timer frozen.
     */
    private void refreshCountdownLabels() {
        long now = System.currentTimeMillis();
        for (Placement placement : List.copyOf(placements)) {
            CrateKind kind = placement.kind();
            World world = Bukkit.getWorld(placement.worldId());
            if (!kind.limited() || world == null
                    || !world.isChunkLoaded(placement.x() >> 4, placement.z() >> 4)) {
                continue;
            }
            List<Component> lines = kind.countdownLines(now);
            // One query spanning both limited lines, centred between them.
            Location centre = new Location(world, placement.x() + 0.5d,
                    placement.y() + BOTTOM_LINE + LINE_HEIGHT / 2d, placement.z() + 0.5d);
            for (Entity entity : world.getNearbyEntities(centre, 0.4d, 0.4d, 0.4d)) {
                if (!(entity instanceof ArmorStand stand)) {
                    continue;
                }
                if (stand.getScoreboardTags().contains(COUNTDOWN_TAG)) {
                    stand.customName(lines.get(1));
                } else if (stand.getScoreboardTags().contains(HEADLINE_TAG)) {
                    stand.customName(lines.get(0));
                }
            }
        }
    }

    void stop() {
        if (particleTask != null) {
            particleTask.cancel();
            particleTask = null;
        }
        clearLabels();
    }

    private void startParticles() {
        if (particleTask != null) {
            return;
        }
        particleTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::tickParticles, 1L, 4L
        );
    }

    private void tickParticles() {
        int frame = particleFrame++;
        if (frame % COUNTDOWN_FRAMES == 0) {
            refreshCountdownLabels();
        }
        double phase = frame * 0.3d;
        for (Placement placement : List.copyOf(placements)) {
            World world = Bukkit.getWorld(placement.worldId());
            if (world == null || !world.isChunkLoaded(placement.x() >> 4, placement.z() >> 4)) {
                continue;
            }
            Location centre = new Location(
                    world, placement.x() + 0.5d, placement.y() + 0.9d, placement.z() + 0.5d
            );
            if (!hasNearbyViewer(centre)) {
                continue;
            }
            if (placement.kind() == CrateKind.AMETHYST) {
                drawAmethyst(centre, phase, frame);
            } else {
                drawDefault(centre, phase, frame);
            }
        }
    }

    private static void drawAmethyst(Location centre, double phase, int frame) {
        World world = centre.getWorld();
        for (int shard = 0; shard < 3; shard++) {
            double angle = phase + shard * Math.PI * 2d / 3d;
            double rise = (frame * 0.055d + shard * 0.28d) % 0.85d;
            double radius = 0.3d - rise * 0.12d;
            Location point = centre.clone().add(
                    Math.cos(angle) * radius, 0.08d + rise, Math.sin(angle) * radius
            );
            world.spawnParticle(
                    Particle.DUST, point, 1, 0d, 0d, 0d, 0d,
                    shard % 2 == 0 ? AMETHYST_BRIGHT : AMETHYST_DEEP
            );
        }
        world.spawnParticle(
                Particle.WITCH, centre.clone().add(0d, 0.25d, 0d),
                1, 0.22d, 0.08d, 0.22d, 0.01d
        );
        if (frame % 8 == 0) {
            world.spawnParticle(
                    Particle.END_ROD, centre.clone().add(0d, 0.68d, 0d),
                    1, 0.08d, 0.05d, 0.08d, 0.01d
            );
        }
    }

    private static void drawDefault(Location centre, double phase, int frame) {
        World world = centre.getWorld();
        for (int ember = 0; ember < 3; ember++) {
            double angle = -phase * 0.75d + ember * Math.PI * 2d / 3d;
            double radius = 0.36d + Math.sin(phase + ember) * 0.04d;
            Location point = centre.clone().add(
                    Math.cos(angle) * radius, 0.16d, Math.sin(angle) * radius
            );
            world.spawnParticle(
                    Particle.DUST, point, 1, 0d, 0d, 0d, 0d,
                    ember == 0 ? DEFAULT_GOLD : DEFAULT_ORANGE
            );
        }
        if (frame % 3 == 0) {
            world.spawnParticle(
                    Particle.SMALL_FLAME, centre.clone().add(0d, 0.22d, 0d),
                    1, 0.18d, 0.06d, 0.18d, 0.005d
            );
        }
    }

    private static boolean hasNearbyViewer(Location centre) {
        return centre.getWorld().getPlayers().stream().anyMatch(player ->
                player.getLocation().distanceSquared(centre) <= 48d * 48d
        );
    }

    private ArmorStand spawnLabel(Location at, Component name) {
        return at.getWorld().spawn(at, ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setMarker(true);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setCustomNameVisible(true);
            stand.customName(name);
            stand.addScoreboardTag(TAG);
            stand.setPersistent(true);
        });
    }

    private void clearLabels() {
        for (World world : Bukkit.getWorlds()) {
            for (ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
                if (stand.getScoreboardTags().contains(TAG)) {
                    stand.remove();
                }
            }
        }
    }

    private Placement placementAt(Location location) {
        return placements.stream().filter(row -> sameBlock(row, location)).findFirst().orElse(null);
    }

    private static boolean sameBlock(Placement placement, Location location) {
        return location.getWorld() != null
                && placement.worldId().equals(location.getWorld().getUID())
                && placement.x() == location.getBlockX()
                && placement.y() == location.getBlockY()
                && placement.z() == location.getBlockZ();
    }

    private void load() throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            JsonArray rows = root.has("placements") ? root.getAsJsonArray("placements") : new JsonArray();
            for (JsonElement element : rows) {
                JsonObject row = element.getAsJsonObject();
                CrateKind kind = CrateKind.from(row.get("kind").getAsString()).orElse(null);
                if (kind != null) {
                    placements.add(new Placement(
                            kind, UUID.fromString(row.get("world").getAsString()),
                            row.get("x").getAsInt(), row.get("y").getAsInt(), row.get("z").getAsInt()
                    ));
                }
            }
        } catch (RuntimeException exception) {
            throw new IOException("Physical crate store is unreadable", exception);
        }
    }

    private void persistOrRestore(List<Placement> before) throws IOException {
        try {
            persist();
        } catch (IOException exception) {
            placements.clear();
            placements.addAll(before);
            throw exception;
        }
    }

    private void persist() throws IOException {
        JsonObject root = new JsonObject();
        JsonArray rows = new JsonArray();
        for (Placement placement : placements) {
            JsonObject row = new JsonObject();
            row.addProperty("kind", placement.kind().key());
            row.addProperty("world", placement.worldId().toString());
            row.addProperty("x", placement.x());
            row.addProperty("y", placement.y());
            row.addProperty("z", placement.z());
            rows.add(row);
        }
        root.add("placements", rows);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, gson.toJson(root));
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<String> prefix(String raw, List<String> values) {
        String needle = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(needle)).toList();
    }
}
