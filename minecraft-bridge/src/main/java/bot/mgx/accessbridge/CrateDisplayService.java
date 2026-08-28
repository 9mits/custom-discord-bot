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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;

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
    private record Placement(CrateKind kind, UUID worldId, int x, int y, int z) { }

    private final Path file;
    private final CrateService crates;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final List<Placement> placements = new ArrayList<>();

    CrateDisplayService(Path file, CrateService crates) throws IOException {
        this.file = file;
        this.crates = crates;
        Files.createDirectories(file.getParent());
        load();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Place physical crates in game.");
            return true;
        }
        if (!player.hasPermission("mgxaccessbridge.admin")) {
            PlayerMenuService.error(player, "You do not have permission to place crates.");
            return true;
        }
        try {
            if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
                CrateKind kind = CrateKind.from(args[1]).orElseThrow(
                        () -> new IllegalArgumentException("Use default or amethyst.")
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
                        "Use /cratehologram set <default|amethyst> while looking at a chest, or remove."
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
            return prefix(args[1], List.of("default", "amethyst"));
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
        for (Placement placement : List.copyOf(placements)) {
            World world = Bukkit.getWorld(placement.worldId());
            if (world == null) {
                continue;
            }
            Location base = new Location(world, placement.x() + 0.5d,
                    placement.y() + 1.75d, placement.z() + 0.5d);
            spawnLabel(base, Component.text(
                    placement.kind().displayName(), placement.kind().colour(), TextDecoration.BOLD
            ));
            spawnLabel(base.clone().subtract(0d, 0.28d, 0d), Component.text(
                    "1 Mysterious Crate Key", NamedTextColor.WHITE
            ));
        }
    }

    void stop() {
        clearLabels();
    }

    private void spawnLabel(Location at, Component name) {
        at.getWorld().spawn(at, ArmorStand.class, stand -> {
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
