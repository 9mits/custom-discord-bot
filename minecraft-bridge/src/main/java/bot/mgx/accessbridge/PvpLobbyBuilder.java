package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.generator.ChunkGenerator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the isolated, dependency-free PvP hub and its starter arena set. */
final class PvpLobbyBuilder {
    static final String WORLD_NAME = "mgx_pvp";
    private static final int FLOOR_Y = 80;
    private static final Map<PvpMode, int[]> PADS = padOffsets();
    private static final int[] RETURN_PAD = {0, 17};

    record Built(World world, PvpArenaStore.Point lobby, List<PvpArenaStore.Arena> arenas) {
        Built {
            arenas = List.copyOf(arenas);
        }
    }

    private PvpLobbyBuilder() {
    }

    /** Loads an already-generated hub without rebuilding its blocks. */
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
        if (world == null) throw new IllegalStateException("The PvP world could not be created.");
        world.setDifficulty(Difficulty.NORMAL);
        world.setTime(6_000L);
        world.setStorm(false);
        world.setThundering(false);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.KEEP_INVENTORY, true);
        world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);

        Location lobby = new Location(world, 0.5d, FLOOR_Y + 1d, 0.5d, 180f, 0f);
        buildLobby(world);
        world.setSpawnLocation(lobby);

        List<PvpArenaStore.Arena> arenas = new ArrayList<>();
        arenas.add(buildArena(world, "duel-a", Set.of("duel"), 0, -180, 27, 2, false));
        arenas.add(buildArena(world, "duel-b", Set.of("duel"), 80, -180, 27, 2, false));
        arenas.add(buildArena(world, "doubles-a", Set.of("2v2"), 180, -100, 32, 4, false));
        arenas.add(buildArena(world, "triples-a", Set.of("3v3"), 180, 80, 36, 6, false));
        arenas.add(buildArena(world, "clan-a", Set.of("clan"), -180, 80, 36, 6, false));
        arenas.add(buildArena(world, "ffa-a", Set.of("ffa"), -180, -100, 46, 12, true));
        return new Built(world, PvpArenaStore.Point.of(lobby), arenas);
    }

    static PvpMode modePad(PvpArenaStore.Point lobby, Location at) {
        Location center = lobby == null ? null : lobby.resolve();
        if (center == null || at == null || at.getWorld() == null
                || !center.getWorld().equals(at.getWorld())
                || Math.abs(at.getY() - center.getY()) > 2d) return null;
        for (Map.Entry<PvpMode, int[]> row : PADS.entrySet()) {
            double x = center.getX() + row.getValue()[0];
            double z = center.getZ() + row.getValue()[1];
            if (horizontalSquared(at, x, z) <= 4d) return row.getKey();
        }
        return null;
    }

    static boolean returnPad(PvpArenaStore.Point lobby, Location at) {
        Location center = lobby == null ? null : lobby.resolve();
        return center != null && at != null && at.getWorld() != null
                && center.getWorld().equals(at.getWorld())
                && Math.abs(at.getY() - center.getY()) <= 2d
                && horizontalSquared(at, center.getX() + RETURN_PAD[0],
                center.getZ() + RETURN_PAD[1]) <= 4d;
    }

    private static double horizontalSquared(Location at, double x, double z) {
        double dx = at.getX() - x;
        double dz = at.getZ() - z;
        return dx * dx + dz * dz;
    }

    private static Map<PvpMode, int[]> padOffsets() {
        Map<PvpMode, int[]> pads = new LinkedHashMap<>();
        pads.put(PvpMode.RANKED_DUEL, new int[]{0, -17});
        pads.put(PvpMode.CASUAL_DUEL, new int[]{12, -12});
        pads.put(PvpMode.DOUBLES, new int[]{17, 0});
        pads.put(PvpMode.TRIPLES, new int[]{12, 12});
        pads.put(PvpMode.CLAN_BATTLE, new int[]{-12, 12});
        pads.put(PvpMode.FFA, new int[]{-17, 0});
        return Map.copyOf(pads);
    }

    private static void buildLobby(World world) {
        int radius = 23;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                double distance = Math.sqrt(x * x + z * z);
                Block floor = world.getBlockAt(x, FLOOR_Y, z);
                if (distance <= radius) {
                    floor.setType(distance > radius - 2 ? Material.POLISHED_BLACKSTONE_BRICKS
                            : ((x + z) & 1) == 0 ? Material.SMOOTH_QUARTZ : Material.QUARTZ_BRICKS,
                            false);
                    world.getBlockAt(x, FLOOR_Y - 1, z).setType(Material.DEEPSLATE_TILES, false);
                }
                if (distance >= radius - 0.75d && distance <= radius + 0.25d) {
                    world.getBlockAt(x, FLOOR_Y + 1, z).setType(Material.TINTED_GLASS, false);
                }
            }
        }
        for (Map.Entry<PvpMode, int[]> row : PADS.entrySet()) {
            Material accent = switch (row.getKey()) {
                case RANKED_DUEL -> Material.GOLD_BLOCK;
                case CASUAL_DUEL -> Material.LIME_CONCRETE;
                case DOUBLES -> Material.CYAN_CONCRETE;
                case TRIPLES -> Material.BLUE_CONCRETE;
                case CLAN_BATTLE -> Material.RED_CONCRETE;
                case FFA -> Material.PURPLE_CONCRETE;
                default -> Material.GRAY_CONCRETE;
            };
            buildPad(world, row.getValue()[0], row.getValue()[1], accent,
                    row.getKey().display());
        }
        buildPad(world, RETURN_PAD[0], RETURN_PAD[1], Material.GRAY_CONCRETE,
                "Return to SMP");
        for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 8; y++) {
            world.getBlockAt(0, y, 0).setType(y % 3 == 0
                    ? Material.SEA_LANTERN : Material.POLISHED_BLACKSTONE, false);
        }
    }

    private static void buildPad(World world, int centerX, int centerZ, Material accent, String label) {
        for (int x = centerX - 2; x <= centerX + 2; x++) {
            for (int z = centerZ - 2; z <= centerZ + 2; z++) {
                world.getBlockAt(x, FLOOR_Y, z).setType(accent, false);
            }
        }
        Block signBlock = world.getBlockAt(centerX, FLOOR_Y + 1, centerZ);
        signBlock.setType(Material.OAK_SIGN, false);
        if (signBlock.getState() instanceof Sign sign) {
            org.bukkit.block.sign.SignSide front = sign.getSide(org.bukkit.block.sign.Side.FRONT);
            front.line(0, Component.text("PvP", NamedTextColor.GOLD));
            front.line(1, Component.text(label, NamedTextColor.BLACK));
            front.line(3, Component.text("Step here", NamedTextColor.DARK_GRAY));
            sign.update(true, false);
        }
    }

    private static PvpArenaStore.Arena buildArena(
            World world,
            String id,
            Set<String> groups,
            int centerX,
            int centerZ,
            int radius,
            int spawnCount,
            boolean openCenter
    ) {
        int y = FLOOR_Y;
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                boolean edge = x == centerX - radius || x == centerX + radius
                        || z == centerZ - radius || z == centerZ + radius;
                world.getBlockAt(x, y, z).setType(((x + z) & 1) == 0
                        ? Material.POLISHED_BLACKSTONE_BRICKS : Material.DEEPSLATE_TILES, false);
                world.getBlockAt(x, y - 1, z).setType(Material.REINFORCED_DEEPSLATE, false);
                if (edge) {
                    for (int wallY = y + 1; wallY <= y + 5; wallY++) {
                        world.getBlockAt(x, wallY, z).setType(wallY == y + 5
                                ? Material.SEA_LANTERN : Material.POLISHED_BLACKSTONE_BRICKS, false);
                    }
                }
            }
        }
        if (!openCenter) {
            int[][] cover = {{-9, -9}, {-9, 9}, {9, -9}, {9, 9}, {0, 0}};
            for (int[] offset : cover) {
                int height = offset[0] == 0 && offset[1] == 0 ? 3 : 2;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dy = 1; dy <= height; dy++) {
                            world.getBlockAt(centerX + offset[0] + dx, y + dy,
                                    centerZ + offset[1] + dz).setType(Material.CRIMSON_HYPHAE, false);
                        }
                    }
                }
            }
        }
        List<PvpArenaStore.Point> spawns = new ArrayList<>();
        double spawnRadius = Math.max(8d, radius - 8d);
        for (int index = 0; index < spawnCount; index++) {
            double angle = Math.PI * 2d * index / spawnCount - Math.PI / 2d;
            double x = centerX + 0.5d + Math.cos(angle) * spawnRadius;
            double z = centerZ + 0.5d + Math.sin(angle) * spawnRadius;
            float yaw = (float) Math.toDegrees(Math.atan2(centerZ + 0.5d - z,
                    centerX + 0.5d - x)) - 90f;
            spawns.add(PvpArenaStore.Point.of(new Location(world, x, y + 1d, z, yaw, 0f)));
        }
        PvpArenaStore.Point first = PvpArenaStore.Point.of(new Location(
                world, centerX - radius, y, centerZ - radius));
        PvpArenaStore.Point second = PvpArenaStore.Point.of(new Location(
                world, centerX + radius, y + 8, centerZ + radius));
        PvpArenaStore.Point spectator = PvpArenaStore.Point.of(new Location(
                world, centerX + 0.5d, y + 7d, centerZ + 0.5d, 0f, 75f));
        return new PvpArenaStore.Arena(id, groups, true, first, second, spectator, spawns);
    }

    private static final class EmptyGenerator extends ChunkGenerator {
        @Override
        public boolean shouldGenerateNoise() {
            return false;
        }

        @Override
        public boolean shouldGenerateSurface() {
            return false;
        }

        @Override
        public void generateBedrock(
                org.bukkit.generator.WorldInfo worldInfo, java.util.Random random,
                int chunkX, int chunkZ, ChunkData chunkData
        ) {
            // The lobby builder places every intentional block in this void world.
        }

        @Override
        public boolean shouldGenerateCaves() {
            return false;
        }

        @Override
        public boolean shouldGenerateDecorations() {
            return false;
        }

        @Override
        public boolean shouldGenerateMobs() {
            return false;
        }

        @Override
        public boolean shouldGenerateStructures() {
            return false;
        }
    }
}
