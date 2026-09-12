package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.generator.ChunkGenerator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the compact portal lobby used to enter the real-world PvP queues. */
final class PvpLobbyBuilder {
    static final String WORLD_NAME = "mgx_pvp";
    static final double PROTECTED_RADIUS = 70d;
    private static final String HOLOGRAM_TAG = "mgx_pvp_lobby_hologram";
    private static final int FLOOR_Y = 80;
    private static final Map<PvpMode, Portal> PORTALS = portals();
    private static final int[] RETURN_PORTAL = {0, 38};

    record Built(World world, PvpLobbyStore.Point lobby) { }
    private record Portal(int x, int z, Material glass, String title, String subtitle) { }

    private PvpLobbyBuilder() { }

    static World load(MGXAccessBridge plugin) {
        World loaded = plugin.getServer().getWorld(WORLD_NAME);
        if (loaded != null) return loaded;
        WorldCreator creator = new WorldCreator(WORLD_NAME);
        creator.generateStructures(false);
        creator.generator(new EmptyGenerator());
        return creator.createWorld();
    }

    static Built build(MGXAccessBridge plugin) {
        World world = load(plugin);
        if (world == null) throw new IllegalStateException("The PvP lobby world could not be created.");
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setTime(18_000L);
        world.setStorm(false);
        world.setThundering(false);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.KEEP_INVENTORY, true);
        world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);

        clearOldLobby(world);
        buildIsland(world);
        buildKeep(world);
        for (Map.Entry<PvpMode, Portal> row : PORTALS.entrySet()) {
            buildPortal(world, row.getValue(), row.getKey());
        }
        buildReturnPortal(world);
        buildHolograms(world);

        Location lobby = new Location(world, 0.5d, FLOOR_Y + 1d, 9.5d, 180f, 0f);
        world.setSpawnLocation(lobby);
        return new Built(world, PvpLobbyStore.Point.of(lobby));
    }

    static PvpMode modePad(PvpLobbyStore.Point lobby, Location at) {
        Location center = lobby == null ? null : lobby.resolve();
        if (center == null || at == null || at.getWorld() == null
                || !center.getWorld().equals(at.getWorld())) return null;
        double originX = center.getX() - 0.5d;
        double originZ = center.getZ() - 9.5d;
        for (Map.Entry<PvpMode, Portal> row : PORTALS.entrySet()) {
            Portal portal = row.getValue();
            if (Math.abs(at.getY() - (FLOOR_Y + 1d)) <= 4d
                    && horizontalSquared(at, originX + portal.x(), originZ + portal.z()) <= 8d) {
                return row.getKey();
            }
        }
        return null;
    }

    static boolean returnPad(PvpLobbyStore.Point lobby, Location at) {
        Location center = lobby == null ? null : lobby.resolve();
        if (center == null || at == null || at.getWorld() == null
                || !center.getWorld().equals(at.getWorld())) return false;
        double originX = center.getX() - 0.5d;
        double originZ = center.getZ() - 9.5d;
        return Math.abs(at.getY() - (FLOOR_Y + 1d)) <= 4d
                && horizontalSquared(at, originX + RETURN_PORTAL[0],
                originZ + RETURN_PORTAL[1]) <= 8d;
    }

    /** A restrained live effect makes every arch read as a portal without Nether travel. */
    static void pulse(PvpLobbyStore.Point lobby) {
        Location center = lobby == null ? null : lobby.resolve();
        if (center == null || center.getWorld() == null) return;
        double originX = center.getX() - 0.5d;
        double originZ = center.getZ() - 9.5d;
        int step = (int) ((System.currentTimeMillis() / 250L) % 5L);
        for (Portal portal : PORTALS.values()) {
            center.getWorld().spawnParticle(Particle.PORTAL,
                    originX + portal.x(), FLOOR_Y + 1.4d + step * 0.9d,
                    originZ + portal.z(), 7, 1.3d, 0.18d, 0.15d, 0.03d);
        }
        center.getWorld().spawnParticle(Particle.REVERSE_PORTAL,
                originX + RETURN_PORTAL[0], FLOOR_Y + 3d,
                originZ + RETURN_PORTAL[1], 8, 1.2d, 1.8d, 0.15d, 0.02d);
    }

    private static Map<PvpMode, Portal> portals() {
        Map<PvpMode, Portal> portals = new LinkedHashMap<>();
        portals.put(PvpMode.RANKED_DUEL, new Portal(0, -30, Material.YELLOW_STAINED_GLASS,
                "RANKED 1v1", "Skill-matched queue"));
        portals.put(PvpMode.CASUAL_DUEL, new Portal(-21, -22, Material.LIME_STAINED_GLASS,
                "CASUAL 1v1", "Fight without rating"));
        portals.put(PvpMode.DOUBLES, new Portal(21, -22, Material.CYAN_STAINED_GLASS,
                "RANKED 2v2", "Bring a teammate or fill"));
        portals.put(PvpMode.TRIPLES, new Portal(-30, -1, Material.BLUE_STAINED_GLASS,
                "RANKED 3v3", "Party or teammate fill"));
        portals.put(PvpMode.CLAN_BATTLE, new Portal(30, -1, Material.RED_STAINED_GLASS,
                "CLAN BATTLE", "Three clan members"));
        portals.put(PvpMode.FFA, new Portal(0, 28, Material.PURPLE_STAINED_GLASS,
                "LAST PLAYER STANDING", "Everyone is an opponent"));
        return Map.copyOf(portals);
    }

    private static void clearOldLobby(World world) {
        for (Entity entity : world.getEntities()) {
            if (entity.getScoreboardTags().contains(HOLOGRAM_TAG)) entity.remove();
        }
        for (int x = -52; x <= 52; x++) {
            for (int z = -52; z <= 52; z++) {
                for (int y = FLOOR_Y - 4; y <= FLOOR_Y + 18; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
    }

    private static void buildIsland(World world) {
        // A chamfered floating citadel, not the old flat circular platform.
        for (int x = -48; x <= 48; x++) {
            for (int z = -46; z <= 46; z++) {
                int ax = Math.abs(x);
                int az = Math.abs(z);
                if (ax > 36 && az > 32 && ax + az > 78) continue;
                int edge = Math.min(48 - ax, 46 - az);
                int underside = edge > 10 ? 5 : edge > 4 ? 4 : 3;
                for (int depth = 1; depth <= underside; depth++) {
                    Material stone = depth == 1 ? Material.POLISHED_DEEPSLATE
                            : ((x + z + depth) & 1) == 0
                            ? Material.DEEPSLATE_BRICKS : Material.BLACKSTONE;
                    world.getBlockAt(x, FLOOR_Y - depth, z).setType(stone, false);
                }
                Material floor;
                if (edge <= 2 || ax + az >= 76) floor = Material.POLISHED_BLACKSTONE_BRICKS;
                else if (((x / 4) + (z / 4)) % 5 == 0) floor = Material.CHISELED_DEEPSLATE;
                else floor = Material.DEEPSLATE_TILES;
                world.getBlockAt(x, FLOOR_Y, z).setType(floor, false);
            }
        }
        buildBattlements(world);
        for (Portal portal : PORTALS.values()) path(world, portal.x(), portal.z());
        path(world, RETURN_PORTAL[0], RETURN_PORTAL[1]);
    }

    private static void buildBattlements(World world) {
        for (int x = -34; x <= 34; x++) {
            wallColumn(world, x, -46, (x & 3) == 0);
            wallColumn(world, x, 46, (x & 3) == 0);
        }
        for (int z = -32; z <= 32; z++) {
            wallColumn(world, -48, z, (z & 3) == 0);
            wallColumn(world, 48, z, (z & 3) == 0);
        }
        for (int step = 0; step <= 12; step++) {
            int x = 36 + step;
            int z = 42 - step;
            for (int sx : new int[]{-1, 1}) {
                for (int sz : new int[]{-1, 1}) {
                    wallColumn(world, sx * x, sz * z, (step & 2) == 0);
                }
            }
        }
    }

    private static void wallColumn(World world, int x, int z, boolean merlon) {
        world.getBlockAt(x, FLOOR_Y + 1, z).setType(Material.POLISHED_BLACKSTONE_BRICKS, false);
        world.getBlockAt(x, FLOOR_Y + 2, z).setType(Material.POLISHED_BLACKSTONE_WALL, false);
        if (merlon) world.getBlockAt(x, FLOOR_Y + 3, z).setType(Material.GILDED_BLACKSTONE, false);
    }

    private static void buildKeep(World world) {
        for (int[] corner : List.of(new int[]{-38, -26}, new int[]{38, -26},
                new int[]{-38, 26}, new int[]{38, 26})) {
            for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 11; y++) {
                int radius = y == FLOOR_Y + 10 ? 3 : 2;
                for (int x = corner[0] - radius; x <= corner[0] + radius; x++) {
                    for (int z = corner[1] - radius; z <= corner[1] + radius; z++) {
                        boolean shell = x == corner[0] - radius || x == corner[0] + radius
                                || z == corner[1] - radius || z == corner[1] + radius;
                        if (shell) world.getBlockAt(x, y, z).setType(
                                (y & 1) == 0 ? Material.POLISHED_BLACKSTONE_BRICKS
                                        : Material.CRACKED_POLISHED_BLACKSTONE_BRICKS, false);
                    }
                }
            }
            world.getBlockAt(corner[0], FLOOR_Y + 12, corner[1])
                    .setType(Material.SOUL_CAMPFIRE, false);
        }
        for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 9; y++) {
            world.getBlockAt(0, y, 0).setType(y % 3 == 0
                    ? Material.SEA_LANTERN : Material.GILDED_BLACKSTONE, false);
        }
        for (int offset = -5; offset <= 5; offset++) {
            world.getBlockAt(offset, FLOOR_Y + 6 + Math.abs(offset) / 2, 0)
                    .setType(Material.IRON_BLOCK, false);
            world.getBlockAt(0, FLOOR_Y + 6 + Math.abs(offset) / 2, offset)
                    .setType(Material.IRON_BLOCK, false);
        }
        for (int[] fire : List.of(new int[]{-8, 8}, new int[]{8, 8},
                new int[]{-8, -8}, new int[]{8, -8})) {
            world.getBlockAt(fire[0], FLOOR_Y + 1, fire[1]).setType(Material.SOUL_CAMPFIRE, false);
        }
        for (int[] garden : List.of(new int[]{-19, 8}, new int[]{19, 8},
                new int[]{-16, -10}, new int[]{16, -10})) {
            buildGarden(world, garden[0], garden[1]);
        }
    }

    private static void buildGarden(World world, int centerX, int centerZ) {
        for (int x = centerX - 3; x <= centerX + 3; x++) {
            for (int z = centerZ - 3; z <= centerZ + 3; z++) {
                boolean edge = x == centerX - 3 || x == centerX + 3
                        || z == centerZ - 3 || z == centerZ + 3;
                world.getBlockAt(x, FLOOR_Y + 1, z).setType(edge
                        ? Material.POLISHED_BLACKSTONE_BRICKS : Material.WARPED_NYLIUM, false);
            }
        }
        for (int y = FLOOR_Y + 2; y <= FLOOR_Y + 6; y++) {
            world.getBlockAt(centerX, y, centerZ).setType(Material.WARPED_STEM, false);
        }
        world.getBlockAt(centerX, FLOOR_Y + 7, centerZ).setType(Material.SHROOMLIGHT, false);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) + Math.abs(dz) <= 3) {
                    world.getBlockAt(centerX + dx, FLOOR_Y + 7, centerZ + dz)
                            .setType(Material.WARPED_WART_BLOCK, false);
                }
            }
        }
        for (int[] lamp : List.of(new int[]{-2, -2}, new int[]{2, -2},
                new int[]{-2, 2}, new int[]{2, 2})) {
            world.getBlockAt(centerX + lamp[0], FLOOR_Y + 2, centerZ + lamp[1])
                    .setType(Material.SOUL_LANTERN, false);
        }
    }

    private static void buildPortal(World world, Portal portal, PvpMode mode) {
        boolean wideX = Math.abs(portal.x()) <= Math.abs(portal.z());
        for (int lateral = -3; lateral <= 3; lateral++) {
            for (int y = 0; y <= 7; y++) {
                boolean frame = lateral == -3 || lateral == 3 || y == 0 || y == 7
                        || (y == 6 && Math.abs(lateral) == 2);
                Material material = frame
                        ? ((lateral + y) & 1) == 0 ? Material.CRYING_OBSIDIAN : Material.OBSIDIAN
                        : portal.glass();
                setPortalBlock(world, portal.x(), portal.z(), wideX, lateral, FLOOR_Y + 1 + y, material);
            }
        }
        for (int lateral : new int[]{-4, 4}) {
            setPortalBlock(world, portal.x(), portal.z(), wideX, lateral, FLOOR_Y + 1,
                    Material.POLISHED_BLACKSTONE_BRICKS);
            setPortalBlock(world, portal.x(), portal.z(), wideX, lateral, FLOOR_Y + 2,
                    Material.SOUL_LANTERN);
        }
        Material emblem = switch (mode) {
            case RANKED_DUEL -> Material.GOLD_BLOCK;
            case CASUAL_DUEL -> Material.IRON_BLOCK;
            case DOUBLES -> Material.DIAMOND_BLOCK;
            case TRIPLES -> Material.PRISMARINE_BRICKS;
            case CLAN_BATTLE -> Material.REDSTONE_BLOCK;
            case FFA -> Material.AMETHYST_BLOCK;
            default -> Material.GILDED_BLACKSTONE;
        };
        setPortalBlock(world, portal.x(), portal.z(), wideX, 0, FLOOR_Y + 9, emblem);
    }

    private static void buildReturnPortal(World world) {
        Portal returnPortal = new Portal(RETURN_PORTAL[0], RETURN_PORTAL[1],
                Material.GRAY_STAINED_GLASS, "RETURN TO SURVIVAL", "Back where you entered");
        buildPortal(world, returnPortal, PvpMode.PRIVATE_DUEL);
    }

    private static void buildHolograms(World world) {
        for (Portal portal : PORTALS.values()) {
            hologram(world, portal.x() + 0.5d, FLOOR_Y + 10.8d, portal.z() + 0.5d,
                    Component.text(portal.title(), NamedTextColor.GOLD, TextDecoration.BOLD));
            hologram(world, portal.x() + 0.5d, FLOOR_Y + 10.45d, portal.z() + 0.5d,
                    Component.text(portal.subtitle(), NamedTextColor.GRAY));
            hologram(world, portal.x() + 0.5d, FLOOR_Y + 10.1d, portal.z() + 0.5d,
                    Component.text("WALK THROUGH TO CHOOSE", NamedTextColor.YELLOW));
        }
        hologram(world, 0.5d, FLOOR_Y + 10.8d, RETURN_PORTAL[1] + 0.5d,
                Component.text("RETURN TO SURVIVAL", NamedTextColor.WHITE, TextDecoration.BOLD));
        hologram(world, 0.5d, FLOOR_Y + 10.4d, RETURN_PORTAL[1] + 0.5d,
                Component.text("Back where you entered", NamedTextColor.GRAY));
        hologram(world, 0.5d, FLOOR_Y + 12.5d, 0.5d,
                Component.text("MGX PVP", NamedTextColor.GOLD, TextDecoration.BOLD));
        hologram(world, 0.5d, FLOOR_Y + 12.1d, 0.5d,
                Component.text("Your gear. Real terrain. Keep inventory.", NamedTextColor.WHITE));
    }

    private static void hologram(World world, double x, double y, double z, Component name) {
        world.spawn(new Location(world, x, y, z), ArmorStand.class, stand -> {
            stand.addScoreboardTag(HOLOGRAM_TAG);
            stand.setInvisible(true);
            stand.setMarker(true);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setPersistent(true);
            stand.customName(name);
            stand.setCustomNameVisible(true);
        });
    }

    private static void path(World world, int toX, int toZ) {
        int steps = Math.max(Math.abs(toX), Math.abs(toZ));
        for (int step = 0; step <= steps; step++) {
            int x = steps == 0 ? 0 : (int) Math.round((double) toX * step / steps);
            int z = steps == 0 ? 0 : (int) Math.round((double) toZ * step / steps);
            for (int width = -1; width <= 1; width++) {
                int px = Math.abs(toX) >= Math.abs(toZ) ? x : x + width;
                int pz = Math.abs(toX) >= Math.abs(toZ) ? z + width : z;
                world.getBlockAt(px, FLOOR_Y, pz).setType(width == 0
                        ? Material.POLISHED_BLACKSTONE : Material.POLISHED_DEEPSLATE, false);
            }
        }
    }

    private static void setPortalBlock(
            World world, int x, int z, boolean wideX, int lateral, int y, Material material
    ) {
        world.getBlockAt(x + (wideX ? lateral : 0), y,
                z + (wideX ? 0 : lateral)).setType(material, false);
    }

    private static double horizontalSquared(Location at, double x, double z) {
        double dx = at.getX() - x;
        double dz = at.getZ() - z;
        return dx * dx + dz * dz;
    }

    private static final class EmptyGenerator extends ChunkGenerator {
        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
        @Override public void generateBedrock(
                org.bukkit.generator.WorldInfo worldInfo, java.util.Random random,
                int chunkX, int chunkZ, ChunkData chunkData
        ) { }
    }
}
