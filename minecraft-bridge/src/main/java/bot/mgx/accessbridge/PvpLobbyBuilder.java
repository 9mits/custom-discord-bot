package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.generator.ChunkGenerator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

/**
 * Builds the Amethyst Terrace: the floating island players stand on between fights.
 *
 * <p>Deliberately small. The lobby it replaced was 96 blocks across and built from
 * nether blackstone, which meant two things on a server whose busiest hour today held
 * seven players: most of it was empty most of the time, and none of it looked like the
 * server it belongs to. Everything here is inside a 56-block circle, so a player who
 * arrives can see every queue, both leaderboards and the way home without walking, and
 * the palette is the Amethyst expansion's own — deepslate, calcite, copper and crystal.
 *
 * <p>Laid out in rings, which is what makes a small space read as designed rather than
 * cramped: a mandala plaza, a planted ring holding the six queue gateways, a moat, and a
 * rim with four pavilions carrying the information a player actually wants before
 * queueing. The underside hangs in shards beneath all of it, because the one thing the
 * old lobby got right was being an island in the sky.
 *
 * <p>Geometry is generated rather than pasted. The lobby is rebuilt from scratch
 * whenever {@link PvpLobbyStore} reports a newer format, so it has to be reproducible
 * from code; a schematic would also have to be shipped, versioned and licensed.
 */
final class PvpLobbyBuilder {
    static final String WORLD_NAME = "mgx_pvp";
    /** Comfortably past the rim and the bridges, and far short of the old 70. */
    static final double PROTECTED_RADIUS = 44d;

    private static final String HOLOGRAM_TAG = "mgx_pvp_lobby_hologram";
    /** Carried by the one line under each gateway that is retitled every second. */
    private static final String STATUS_TAG = "mgx_pvp_lobby_status";
    private static final String RATINGS_TAG = "mgx_pvp_board_ratings";
    private static final String LIVE_TAG = "mgx_pvp_board_live";
    /** Rows reserved on each live board, blank until there is something to say. */
    private static final int BOARD_LINES = 5;
    private static final int FLOOR_Y = 80;

    /** Outer edge of the rim walkway. Everything is inside this. */
    private static final int RIM = 27;
    private static final int MOAT_OUTER = 23;
    private static final int MOAT_INNER = 20;
    private static final int PLAZA = 12;
    /** Where the six gateways stand, in the planted ring between plaza and moat. */
    private static final int GATE_RING = 17;
    private static final int PAVILION_RING = 25;
    private static final double SPAWN_Z = 12.5d;
    private static final int[] RETURN_GATE = {0, 25};
    /** Where the moat is crossed: the four pavilions and the walk home. */
    private static final double[] CROSSINGS = {45d, 135d, 180d, 225d, 315d};

    private static final TextColor AMETHYST = TextColor.color(0xB56CFF);
    private static final TextColor CRYSTAL = TextColor.color(0xE3C6FF);

    private static final Map<PvpMode, Gate> GATES = gates();

    record Built(World world, PvpLobbyStore.Point lobby) { }

    /**
     * One gateway.
     *
     * @param angle degrees clockwise from due north, which is how the ring is laid out
     * @param glass the pane the arch is filled with, and the colour players learn it by
     */
    private record Gate(
            double angle, Material glass, Material emblem, String title, String subtitle
    ) {
        int x() {
            return symmetric(quantised(Math.sin(Math.toRadians(angle))) * GATE_RING);
        }

        int z() {
            return symmetric(-quantised(Math.cos(Math.toRadians(angle))) * GATE_RING);
        }
    }

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
        // Late afternoon. A fixed clock means the island always looks the way it was
        // lit, and the long shadows are what the amethyst reads against.
        world.setTime(15_500L);
        world.setStorm(false);
        world.setThundering(false);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.KEEP_INVENTORY, true);
        world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
        world.setGameRule(GameRules.MOB_GRIEFING, false);

        Random random = new Random(0x4D_4758_5056_50L);
        clearOldLobby(world);
        buildUnderside(world, random);
        buildPlaza(world);
        buildGardenRing(world, random);
        buildMoat(world);
        buildRim(world);
        for (Map.Entry<PvpMode, Gate> row : GATES.entrySet()) {
            buildGate(world, row.getValue());
        }
        buildReturnGate(world);
        buildMonument(world);
        buildArrival(world);
        buildPavilions(world);
        // Last, so a tree is never planted where a gateway or the terrace is about to
        // go and left hanging in the air with its trunk replaced.
        plantGarden(world, random);
        buildHolograms(world);

        Location lobby = new Location(world, 0.5d, FLOOR_Y + 1d, SPAWN_Z, 180f, 0f);
        world.setSpawnLocation(lobby);
        return new Built(world, PvpLobbyStore.Point.of(lobby));
    }

    // ---------------------------------------------------------------- pads

    static PvpMode modePad(PvpLobbyStore.Point lobby, Location at) {
        Location origin = origin(lobby, at);
        if (origin == null) return null;
        for (Map.Entry<PvpMode, Gate> row : GATES.entrySet()) {
            if (onPad(at, origin, row.getValue().x(), row.getValue().z())) return row.getKey();
        }
        return null;
    }

    static boolean returnPad(PvpLobbyStore.Point lobby, Location at) {
        Location origin = origin(lobby, at);
        return origin != null && onPad(at, origin, RETURN_GATE[0], RETURN_GATE[1]);
    }

    /**
     * The lobby's own 0,0 in world coordinates, or null when this location is not in it.
     *
     * <p>Everything is placed relative to the stored spawn point rather than to the
     * world origin, so the whole island can be moved without the pads going with it.
     */
    private static Location origin(PvpLobbyStore.Point lobby, Location at) {
        Location centre = lobby == null ? null : lobby.resolve();
        if (centre == null || at == null || at.getWorld() == null
                || !centre.getWorld().equals(at.getWorld())) {
            return null;
        }
        return new Location(centre.getWorld(), centre.getX() - 0.5d, centre.getY(),
                centre.getZ() - SPAWN_Z);
    }

    private static boolean onPad(Location at, Location origin, int x, int z) {
        return Math.abs(at.getY() - (FLOOR_Y + 1d)) <= 4d
                && horizontalSquared(at, origin.getX() + x, origin.getZ() + z) <= 6.25d;
    }

    // ------------------------------------------------------------ live view

    /** A restrained live effect, so every arch reads as a gateway without Nether travel. */
    static void pulse(PvpLobbyStore.Point lobby) {
        Location centre = lobby == null ? null : lobby.resolve();
        if (centre == null || centre.getWorld() == null) return;
        World world = centre.getWorld();
        if (world.getPlayers().isEmpty()) return;
        double originX = centre.getX() - 0.5d;
        double originZ = centre.getZ() - SPAWN_Z;
        int step = (int) ((System.currentTimeMillis() / 250L) % 8L);
        for (Gate gate : GATES.values()) {
            world.spawnParticle(Particle.PORTAL,
                    originX + gate.x(), FLOOR_Y + 1.6d + step * 0.55d, originZ + gate.z(),
                    6, 1.25d, 0.2d, 1.25d, 0.02d);
        }
        world.spawnParticle(Particle.REVERSE_PORTAL,
                originX + RETURN_GATE[0], FLOOR_Y + 3d, originZ + RETURN_GATE[1],
                8, 1.2d, 1.6d, 1.2d, 0.02d);
        // The crystal over the monument, turning on its own axis.
        double spin = (System.currentTimeMillis() % 6_000L) / 6_000d * Math.PI * 2d;
        for (int point = 0; point < 3; point++) {
            double angle = spin + point * (Math.PI * 2d / 3d);
            world.spawnParticle(Particle.END_ROD,
                    originX + Math.cos(angle) * 2.1d, FLOOR_Y + 11.5d + Math.sin(spin) * 0.4d,
                    originZ + Math.sin(angle) * 2.1d, 1, 0d, 0d, 0d, 0d);
        }
    }

    /**
     * Retitles the status line under each gateway.
     *
     * <p>The queue a player can actually fill is the single most useful thing the lobby
     * can tell them, and on a server this size it changes constantly. It is written on
     * the arch itself rather than left inside a menu, so nobody walks into a 3v3 that
     * cannot start.
     */
    static void refreshStatus(PvpLobbyStore.Point lobby, Function<PvpMode, Component> status) {
        Location centre = lobby == null ? null : lobby.resolve();
        if (centre == null || centre.getWorld() == null) return;
        World world = centre.getWorld();
        if (world.getPlayers().isEmpty()) return;
        double originX = centre.getX() - 0.5d;
        double originZ = centre.getZ() - SPAWN_Z;
        for (Map.Entry<PvpMode, Gate> row : GATES.entrySet()) {
            Component line = status.apply(row.getKey());
            if (line == null) continue;
            Location at = new Location(world, originX + row.getValue().x() + 0.5d,
                    FLOOR_Y + 9.3d, originZ + row.getValue().z() + 0.5d);
            world.getNearbyEntitiesByType(ArmorStand.class, at, 1.2d).stream()
                    .filter(stand -> stand.getScoreboardTags().contains(STATUS_TAG))
                    .findFirst()
                    .ifPresent(stand -> stand.customName(line));
        }
    }

    /**
     * Where a mode's gateway stands, relative to the lobby's own origin.
     *
     * <p>Exposed so the layout can be asserted without a server: a radial plan is all
     * rounding, and the failure it produces — one arch a block off the ring — is
     * obvious in game and invisible in the code.
     */
    static int[] gatePosition(PvpMode mode) {
        Gate gate = GATES.get(mode);
        return gate == null ? null : new int[]{gate.x(), gate.z()};
    }

    static int gateRing() {
        return GATE_RING;
    }

    /**
     * Rewrites the two live pavilion boards.
     *
     * <p>Rows are matched top down by height, so a shorter list clears the rows below
     * it rather than leaving yesterday's leader hanging under today's.
     */
    static void refreshBoards(
            PvpLobbyStore.Point lobby, List<Component> ratings, List<Component> live
    ) {
        Location centre = lobby == null ? null : lobby.resolve();
        if (centre == null || centre.getWorld() == null) return;
        World world = centre.getWorld();
        if (world.getPlayers().isEmpty()) return;
        double originX = centre.getX() - 0.5d;
        double originZ = centre.getZ() - SPAWN_Z;
        for (Pavilion pavilion : pavilions()) {
            if (pavilion.board() == Board.STATIC) continue;
            List<Component> lines = pavilion.board() == Board.RATINGS ? ratings : live;
            String tag = pavilion.board() == Board.RATINGS ? RATINGS_TAG : LIVE_TAG;
            double angle = Math.toRadians(pavilion.angle());
            Location at = new Location(world,
                    originX + symmetric(quantised(Math.sin(angle)) * PAVILION_RING) + 0.5d,
                    FLOOR_Y + 3d,
                    originZ + symmetric(-quantised(Math.cos(angle)) * PAVILION_RING) + 0.5d);
            List<ArmorStand> rows = new ArrayList<>(
                    world.getNearbyEntitiesByType(ArmorStand.class, at, 3d, 4d, 3d).stream()
                            .filter(stand -> stand.getScoreboardTags().contains(tag))
                            .sorted((first, second) -> Double.compare(
                                    second.getLocation().getY(), first.getLocation().getY()))
                            .toList());
            for (int row = 0; row < rows.size(); row++) {
                rows.get(row).customName(row < lines.size() ? lines.get(row)
                        : Component.text(" ", NamedTextColor.DARK_GRAY));
            }
        }
    }

    /** How many rows a live board can show, so a caller does not build more. */
    static int boardLines() {
        return BOARD_LINES;
    }

    private static Map<PvpMode, Gate> gates() {
        Map<PvpMode, Gate> gates = new LinkedHashMap<>();
        // Thirty degrees off north, so the six arches sit evenly and leave due south
        // clear for the walk home.
        gates.put(PvpMode.RANKED_DUEL, new Gate(30d, Material.YELLOW_STAINED_GLASS,
                Material.GOLD_BLOCK, "RANKED 1v1", "Rated. Your gear, real terrain."));
        gates.put(PvpMode.CASUAL_DUEL, new Gate(330d, Material.LIME_STAINED_GLASS,
                Material.IRON_BLOCK, "CASUAL 1v1", "Unrated. Nothing on the line."));
        gates.put(PvpMode.DOUBLES, new Gate(90d, Material.CYAN_STAINED_GLASS,
                Material.DIAMOND_BLOCK, "RANKED 2v2", "Bring a teammate or fill."));
        gates.put(PvpMode.TRIPLES, new Gate(270d, Material.LIGHT_BLUE_STAINED_GLASS,
                Material.PRISMARINE_BRICKS, "RANKED 3v3", "Party stays together."));
        gates.put(PvpMode.CLAN_BATTLE, new Gate(150d, Material.RED_STAINED_GLASS,
                Material.REDSTONE_BLOCK, "CLAN BATTLE", "Three of your clan, three of theirs."));
        gates.put(PvpMode.FFA, new Gate(210d, Material.PURPLE_STAINED_GLASS,
                Material.AMETHYST_BLOCK, "LAST STANDING", "Everyone is an opponent."));
        return Map.copyOf(gates);
    }

    // ----------------------------------------------------------- the island

    private static void clearOldLobby(World world) {
        for (Entity entity : world.getEntities()) {
            if (entity.getScoreboardTags().contains(HOLOGRAM_TAG)) entity.remove();
        }
        // Circular rather than square, and only as deep as the old citadel reached:
        // the previous lobby was 96 across, so its corners have to go too.
        for (int x = -52; x <= 52; x++) {
            for (int z = -52; z <= 52; z++) {
                for (int y = FLOOR_Y - 30; y <= FLOOR_Y + 24; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
    }

    /**
     * The rock beneath, hanging in shards.
     *
     * <p>Thickest under the middle and tapering to the rim, then broken into spires that
     * fall further the closer they are to the centre. An island with a flat bottom reads
     * as a platform; this is the silhouette people actually see on approach.
     */
    private static void buildUnderside(World world, Random random) {
        for (int x = -RIM; x <= RIM; x++) {
            for (int z = -RIM; z <= RIM; z++) {
                double radius = Math.hypot(x, z);
                if (radius > RIM + 0.5d) continue;
                int thickness = (int) Math.round(3 + (1d - radius / RIM) * 7d);
                for (int depth = 1; depth <= thickness; depth++) {
                    world.getBlockAt(x, FLOOR_Y - depth, z).setType(
                            undersideMaterial(x, z, depth, random), false);
                }
                // One shard in six, longer towards the middle, with a little jitter so
                // the underside never reads as a cone.
                if (random.nextInt(6) != 0) continue;
                int length = (int) Math.round((1d - radius / RIM) * 16d) + random.nextInt(5);
                for (int depth = thickness + 1; depth <= thickness + length; depth++) {
                    if (random.nextInt(14) == 0) break;
                    world.getBlockAt(x, FLOOR_Y - depth, z).setType(
                            undersideMaterial(x, z, depth, random), false);
                }
            }
        }
        // A few crystal veins in the rock, visible from below.
        for (int vein = 0; vein < 26; vein++) {
            double angle = random.nextDouble() * Math.PI * 2d;
            double radius = random.nextDouble() * (RIM - 6);
            int x = (int) Math.round(Math.cos(angle) * radius);
            int z = (int) Math.round(Math.sin(angle) * radius);
            int y = FLOOR_Y - 4 - random.nextInt(6);
            for (int block = 0; block < 3 + random.nextInt(4); block++) {
                world.getBlockAt(x, y - block, z).setType(
                        block == 0 ? Material.BUDDING_AMETHYST : Material.AMETHYST_BLOCK, false);
            }
        }
    }

    private static Material undersideMaterial(int x, int z, int depth, Random random) {
        if (depth == 1) return Material.DEEPSLATE;
        int roll = random.nextInt(10);
        if (roll == 0) return Material.SMOOTH_BASALT;
        if (roll == 1) return Material.TUFF;
        if (roll == 2 && depth > 3) return Material.CALCITE;
        return ((x + z + depth) & 1) == 0 ? Material.DEEPSLATE : Material.COBBLED_DEEPSLATE;
    }

    /**
     * The mandala floor.
     *
     * <p>Twelve spokes and three rings, which is the pattern that makes a circle read as
     * designed from the ground as well as from above. The arithmetic is polar: every
     * block asks how far out and how far round it is, so the pattern stays true at the
     * edges instead of turning into stairs.
     */
    private static void buildPlaza(World world) {
        for (int x = -PLAZA; x <= PLAZA; x++) {
            for (int z = -PLAZA; z <= PLAZA; z++) {
                double radius = Math.hypot(x, z);
                if (radius > PLAZA + 0.5d) continue;
                world.getBlockAt(x, FLOOR_Y, z).setType(plazaFloor(x, z, radius), false);
            }
        }
        // The kerb where the plaza meets the planted ring.
        ring(world, FLOOR_Y, PLAZA + 1, Material.SMOOTH_BASALT);
        for (int spoke = 0; spoke < 12; spoke++) {
            double angle = Math.toRadians(spoke * 30d + 15d);
            int x = (int) Math.round(Math.sin(angle) * (PLAZA + 1));
            int z = (int) Math.round(-Math.cos(angle) * (PLAZA + 1));
            world.getBlockAt(x, FLOOR_Y + 1, z).setType(Material.DEEPSLATE_BRICK_WALL, false);
            world.getBlockAt(x, FLOOR_Y + 2, z).setType(Material.AMETHYST_BLOCK, false);
            world.getBlockAt(x, FLOOR_Y + 3, z).setType(Material.SEA_LANTERN, false);
            world.getBlockAt(x, FLOOR_Y + 4, z).setType(Material.CHISELED_DEEPSLATE, false);
        }
    }

    /**
     * One block of the mandala.
     *
     * <p>A geode's own three materials: calcite for the field, smooth basalt for every
     * line drawn on it, amethyst for the figures. Light ground with dark linework is
     * what makes a pattern legible from standing height — the first version of this
     * floor was deepslate on deepslate and read as a grey disc with litter on it.
     */
    private static Material plazaFloor(int x, int z, double radius) {
        double angle = Math.atan2(z, x) + Math.PI;
        double sector = angle / (Math.PI * 2d) * 12d;
        double intoSector = sector - Math.floor(sector);
        double fromSpoke = Math.min(intoSector, 1d - intoSector);
        if (radius < 3.6d) return Material.POLISHED_DEEPSLATE;
        // The three concentric lines.
        if (within(radius, 4.2d) || within(radius, 8d) || within(radius, 11.6d)) {
            return Material.SMOOTH_BASALT;
        }
        // Twelve spokes, widening as they run out so they stay one block wide on screen.
        if (fromSpoke * radius < 0.38d) return Material.SMOOTH_BASALT;
        // A diamond of amethyst in the middle of each inner sector, and a smaller
        // one in each outer sector, so the figure repeats at two scales.
        double fromMid = Math.abs(intoSector - 0.5d) * radius;
        if (fromMid + Math.abs(radius - 6.1d) < 1.7d) {
            return Math.abs(radius - 6.1d) < 0.6d && fromMid < 0.6d
                    ? Material.PURPUR_BLOCK : Material.AMETHYST_BLOCK;
        }
        if (fromMid + Math.abs(radius - 9.9d) < 1.2d) return Material.AMETHYST_BLOCK;
        return ((x * 5 + z * 3) & 7) == 0 ? Material.TUFF : Material.CALCITE;
    }

    /** Half a block either side, so a drawn ring is a line and not a band. */
    private static boolean within(double radius, double target) {
        return Math.abs(radius - target) < 0.45d;
    }

    /**
     * The planted ring: moss, azaleas, small trees and the six gateways standing in it.
     *
     * <p>Green between the stone and the water is what stops a lobby this compact from
     * feeling like a car park, and it is the one place on the island where the geometry
     * is allowed to be irregular.
     */
    private static void buildGardenRing(World world, Random random) {
        for (int x = -MOAT_INNER; x <= MOAT_INNER; x++) {
            for (int z = -MOAT_INNER; z <= MOAT_INNER; z++) {
                double radius = Math.hypot(x, z);
                if (radius <= PLAZA + 1.5d || radius > MOAT_INNER - 0.5d) continue;
                Material ground = random.nextInt(9) == 0 ? Material.MOSS_BLOCK
                        : random.nextInt(7) == 0 ? Material.COARSE_DIRT : Material.GRASS_BLOCK;
                world.getBlockAt(x, FLOOR_Y, z).setType(ground, false);
                world.getBlockAt(x, FLOOR_Y - 1, z).setType(Material.DIRT, false);
            }
        }
        // The stone lip that holds the water back.
        ring(world, FLOOR_Y, MOAT_INNER, Material.POLISHED_DEEPSLATE);
        ring(world, FLOOR_Y + 1, MOAT_INNER, Material.DEEPSLATE_BRICK_WALL);

        // Paths from the plaza out to each gateway, and on to the bridges.
        for (Gate gate : GATES.values()) path(world, gate.angle(), GATE_RING + 2);
        for (double angle : CROSSINGS) {
            path(world, angle, MOAT_INNER);
        }
    }

    private static void plantGarden(World world, Random random) {
        for (int attempt = 0; attempt < 520; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2d;
            double radius = PLAZA + 2.5d + random.nextDouble() * (MOAT_INNER - PLAZA - 4d);
            int x = (int) Math.round(Math.cos(angle) * radius);
            int z = (int) Math.round(Math.sin(angle) * radius);
            if (world.getBlockAt(x, FLOOR_Y, z).getType() != Material.GRASS_BLOCK
                    && world.getBlockAt(x, FLOOR_Y, z).getType() != Material.MOSS_BLOCK) {
                continue;
            }
            if (!clearAround(world, x, z, 1)) continue;
            int roll = random.nextInt(100);
            if (roll < 34) {
                world.getBlockAt(x, FLOOR_Y + 1, z).setType(
                        random.nextBoolean() ? Material.SHORT_GRASS : Material.FERN, false);
            } else if (roll < 48) {
                world.getBlockAt(x, FLOOR_Y + 1, z).setType(flower(random), false);
            } else if (roll < 60) {
                world.getBlockAt(x, FLOOR_Y + 1, z).setType(
                        random.nextBoolean() ? Material.AZALEA : Material.FLOWERING_AZALEA, false);
            } else if (roll < 70 && clearAround(world, x, z, 2)) {
                crystalCluster(world, x, z, random);
            } else if (roll < 80 && clearAround(world, x, z, 2)) {
                tree(world, x, z, random);
            }
        }
        // Lanterns on the path edges, so the ring is lit without a lamp every four blocks.
        for (int lamp = 0; lamp < 12; lamp++) {
            double angle = Math.toRadians(lamp * 30d + 15d);
            int x = (int) Math.round(Math.cos(angle) * (MOAT_INNER - 2));
            int z = (int) Math.round(Math.sin(angle) * (MOAT_INNER - 2));
            if (!clearAround(world, x, z, 0)) continue;
            world.getBlockAt(x, FLOOR_Y + 1, z).setType(Material.DEEPSLATE_BRICK_WALL, false);
            world.getBlockAt(x, FLOOR_Y + 2, z).setType(Material.DEEPSLATE_BRICK_WALL, false);
            world.getBlockAt(x, FLOOR_Y + 3, z).setType(Material.SOUL_LANTERN, false);
        }
    }

    private static Material flower(Random random) {
        return switch (random.nextInt(6)) {
            case 0 -> Material.ALLIUM;
            case 1 -> Material.BLUE_ORCHID;
            case 2 -> Material.CORNFLOWER;
            case 3 -> Material.LILY_OF_THE_VALLEY;
            case 4 -> Material.OXEYE_DAISY;
            default -> Material.AZURE_BLUET;
        };
    }

    /** A small hand-built tree; vanilla's generator would fight the paths and the arches. */
    private static void tree(World world, int x, int z, Random random) {
        boolean pale = random.nextInt(3) == 0;
        Material log = pale ? Material.BIRCH_LOG : Material.OAK_LOG;
        Material leaves = pale ? Material.AZALEA_LEAVES : Material.FLOWERING_AZALEA_LEAVES;
        int height = 4 + random.nextInt(2);
        for (int y = 1; y <= height; y++) {
            world.getBlockAt(x, FLOOR_Y + y, z).setType(log, false);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    double distance = Math.sqrt(dx * dx + dz * dz + dy * dy * 2.2d);
                    if (distance > 2.4d || (dx == 0 && dz == 0 && dy < 1)) continue;
                    if (distance > 1.9d && random.nextInt(3) == 0) continue;
                    setIfAir(world, x + dx, FLOOR_Y + height + dy, z + dz, leaves);
                }
            }
        }
        if (random.nextBoolean()) {
            setIfAir(world, x, FLOOR_Y + height + 2, z, Material.AMETHYST_CLUSTER);
        }
    }

    /** The geode outcrops that tie the garden back to the rest of the expansion. */
    private static void crystalCluster(World world, int x, int z, Random random) {
        world.getBlockAt(x, FLOOR_Y, z).setType(Material.CALCITE, false);
        world.getBlockAt(x, FLOOR_Y + 1, z).setType(Material.BUDDING_AMETHYST, false);
        setIfAir(world, x, FLOOR_Y + 2, z, Material.AMETHYST_CLUSTER);
        for (int[] offset : List.of(new int[]{1, 0}, new int[]{-1, 0},
                new int[]{0, 1}, new int[]{0, -1})) {
            if (random.nextInt(3) == 0) continue;
            world.getBlockAt(x + offset[0], FLOOR_Y, z + offset[1])
                    .setType(Material.CALCITE, false);
            setIfAir(world, x + offset[0], FLOOR_Y + 1, z + offset[1],
                    random.nextBoolean() ? Material.LARGE_AMETHYST_BUD : Material.AMETHYST_CLUSTER);
        }
    }

    /** The water ring, held between two stone lips and two blocks deep. */
    private static void buildMoat(World world) {
        for (int x = -MOAT_OUTER - 1; x <= MOAT_OUTER + 1; x++) {
            for (int z = -MOAT_OUTER - 1; z <= MOAT_OUTER + 1; z++) {
                double radius = Math.hypot(x, z);
                if (radius <= MOAT_INNER + 0.5d || radius > MOAT_OUTER + 0.5d) continue;
                world.getBlockAt(x, FLOOR_Y - 2, z).setType(
                        ((x + z) & 1) == 0 ? Material.PRISMARINE : Material.DARK_PRISMARINE, false);
                // Source blocks, so a neighbour update cannot drain the ring.
                source(world, x, FLOOR_Y - 1, z);
                source(world, x, FLOOR_Y, z);
            }
        }
        // Lights under the water, which is most of why a moat is worth having.
        for (int lamp = 0; lamp < 24; lamp++) {
            double angle = Math.toRadians(lamp * 15d);
            int x = (int) Math.round(Math.cos(angle) * (MOAT_INNER + 2));
            int z = (int) Math.round(Math.sin(angle) * (MOAT_INNER + 2));
            world.getBlockAt(x, FLOOR_Y - 2, z).setType(Material.SEA_LANTERN, false);
        }
    }

    private static void source(World world, int x, int y, int z) {
        world.getBlockAt(x, y, z).setType(Material.WATER, false);
        BlockData data = world.getBlockAt(x, y, z).getBlockData();
        if (data instanceof Levelled levelled) {
            levelled.setLevel(0);
            world.getBlockAt(x, y, z).setBlockData(levelled, false);
        }
    }

    /** The outer walkway, its battlement, the five bridges and the corner braziers. */
    private static void buildRim(World world) {
        for (int x = -RIM; x <= RIM; x++) {
            for (int z = -RIM; z <= RIM; z++) {
                double radius = Math.hypot(x, z);
                if (radius <= MOAT_OUTER + 0.5d || radius > RIM + 0.5d) continue;
                Material floor = radius > RIM - 1.2d ? Material.SMOOTH_BASALT
                        : within(radius, MOAT_OUTER + 2.2d) ? Material.CALCITE
                        : ((x + z) & 3) == 0 ? Material.CHISELED_DEEPSLATE
                        : Material.DEEPSLATE_TILES;
                world.getBlockAt(x, FLOOR_Y, z).setType(floor, false);
            }
        }
        for (int degrees = 0; degrees < 360; degrees++) {
            double angle = Math.toRadians(degrees);
            int x = (int) Math.round(Math.cos(angle) * RIM);
            int z = (int) Math.round(Math.sin(angle) * RIM);
            world.getBlockAt(x, FLOOR_Y + 1, z).setType(Material.DEEPSLATE_BRICKS, false);
            world.getBlockAt(x, FLOOR_Y + 2, z).setType(
                    (degrees & 3) == 0 ? Material.DEEPSLATE_BRICK_WALL
                            : Material.COBBLED_DEEPSLATE_WALL, false);
        }
        for (double angle : CROSSINGS) {
            bridge(world, angle);
        }
    }

    /** One crossing over the moat, with a rail on each side. */
    private static void bridge(World world, double angleDegrees) {
        double angle = Math.toRadians(angleDegrees);
        double dirX = Math.sin(angle);
        double dirZ = -Math.cos(angle);
        // Half steps: on a diagonal, whole steps move the rounded position by one in
        // both axes at once and leave a hole a player can fall through.
        for (double step = MOAT_INNER - 1; step <= MOAT_OUTER + 1; step += 0.5d) {
            for (double lateral = -2; lateral <= 2; lateral += 0.5d) {
                int x = (int) Math.round(dirX * step - dirZ * lateral);
                int z = (int) Math.round(dirZ * step + dirX * lateral);
                if (Math.abs(lateral) >= 1.75d) {
                    world.getBlockAt(x, FLOOR_Y, z).setType(Material.SMOOTH_BASALT, false);
                    world.getBlockAt(x, FLOOR_Y + 1, z).setType(
                            Material.POLISHED_DEEPSLATE_WALL, false);
                } else {
                    world.getBlockAt(x, FLOOR_Y, z).setType(
                            Math.abs(lateral) < 0.75d ? Material.CALCITE
                                    : Material.SMOOTH_BASALT, false);
                    world.getBlockAt(x, FLOOR_Y + 1, z).setType(Material.AIR, false);
                }
            }
        }
    }

    // ---------------------------------------------------------- the gateways

    /**
     * One queue arch: a five-wide frame filled with its own colour, a copper surround,
     * the mode's emblem in the keystone and a lit pad to stand on.
     */
    private static void buildGate(World world, Gate gate) {
        int gx = gate.x();
        int gz = gate.z();
        // Faces the middle, so every arch is read from the plaza.
        boolean wideX = Math.abs(gx) <= Math.abs(gz);
        for (int lateral = -3; lateral <= 3; lateral++) {
            for (int y = 0; y <= 8; y++) {
                boolean post = Math.abs(lateral) == 3;
                boolean lintel = y == 8;
                boolean shoulder = y == 7 && Math.abs(lateral) == 2;
                Material material;
                if (post || lintel || shoulder) {
                    material = ((lateral + y) & 1) == 0
                            ? Material.CALCITE : Material.POLISHED_DEEPSLATE;
                } else if (y >= 6) {
                    // A coloured transom over the opening. The colour is how players
                    // learn which gateway is which from across the island, so it has to
                    // be above head height rather than filling the doorway.
                    material = gate.glass();
                } else {
                    // The doorway itself. This was glass with a stone kerb across the
                    // threshold, which walled in the very pad the arch exists to mark.
                    material = Material.AIR;
                }
                set(world, gx, gz, wideX, lateral, FLOOR_Y + 1 + y, material);
            }
        }
        // Copper posts either side, which is the only warm colour on the island.
        for (int lateral : new int[]{-4, 4}) {
            set(world, gx, gz, wideX, lateral, FLOOR_Y + 1, Material.CUT_COPPER);
            set(world, gx, gz, wideX, lateral, FLOOR_Y + 2, Material.EXPOSED_CUT_COPPER);
            set(world, gx, gz, wideX, lateral, FLOOR_Y + 3, Material.WEATHERED_CUT_COPPER);
            set(world, gx, gz, wideX, lateral, FLOOR_Y + 4, Material.CUT_COPPER);
            set(world, gx, gz, wideX, lateral, FLOOR_Y + 5, Material.LIGHTNING_ROD);
            set(world, gx, gz, wideX, lateral, FLOOR_Y + 6, Material.SOUL_LANTERN);
        }
        set(world, gx, gz, wideX, 0, FLOOR_Y + 10, gate.emblem());
        set(world, gx, gz, wideX, -1, FLOOR_Y + 10, Material.CHISELED_DEEPSLATE);
        set(world, gx, gz, wideX, 1, FLOOR_Y + 10, Material.CHISELED_DEEPSLATE);
        set(world, gx, gz, wideX, 0, FLOOR_Y + 9, Material.DEEPSLATE_BRICK_SLAB);
        pad(world, gx, gz, gate.emblem());
    }

    private static void buildReturnGate(World world) {
        Gate home = new Gate(180d, Material.GRAY_STAINED_GLASS, Material.LODESTONE,
                "RETURN TO THE SMP", "Back exactly where you came from");
        int gx = RETURN_GATE[0];
        int gz = RETURN_GATE[1];
        for (int lateral = -2; lateral <= 2; lateral++) {
            for (int y = 0; y <= 6; y++) {
                boolean frame = Math.abs(lateral) == 2 || y == 6;
                set(world, gx, gz, true, lateral, FLOOR_Y + 1 + y,
                        frame ? Material.POLISHED_DEEPSLATE
                                : y >= 4 ? home.glass() : Material.AIR);
            }
        }
        set(world, gx, gz, true, 0, FLOOR_Y + 8, Material.LODESTONE);
        pad(world, gx, gz, Material.LODESTONE);
    }

    /** The lit square a player stands on to open a queue, so the trigger is visible. */
    private static void pad(World world, int gx, int gz, Material accent) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                boolean corner = dx != 0 && dz != 0;
                world.getBlockAt(gx + dx, FLOOR_Y, gz + dz).setType(
                        corner ? Material.POLISHED_DEEPSLATE : Material.SEA_LANTERN, false);
            }
        }
        world.getBlockAt(gx, FLOOR_Y, gz).setType(accent, false);
    }

    // ---------------------------------------------------------- the monument

    /**
     * The crystal at the middle.
     *
     * <p>Four buttresses carrying a floating amethyst shard, which is the one piece of
     * height on the island and the thing an arriving player looks straight at. The
     * server's own ladder is written around its base.
     */
    private static void buildMonument(World world) {
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                double radius = Math.hypot(x, z);
                if (radius > 4.4d) continue;
                world.getBlockAt(x, FLOOR_Y + 1, z).setType(
                        radius > 3.4d ? Material.DEEPSLATE_BRICK_SLAB
                                : radius > 2.4d ? Material.POLISHED_DEEPSLATE
                                : Material.CHISELED_DEEPSLATE, false);
                if (radius <= 2.4d) {
                    world.getBlockAt(x, FLOOR_Y + 2, z).setType(
                            radius > 1.6d ? Material.DEEPSLATE_BRICK_SLAB
                                    : Material.POLISHED_DEEPSLATE, false);
                }
            }
        }
        for (int[] corner : List.of(new int[]{-2, -2}, new int[]{2, -2},
                new int[]{-2, 2}, new int[]{2, 2})) {
            for (int y = 3; y <= 7; y++) {
                world.getBlockAt(corner[0], FLOOR_Y + y, corner[1]).setType(
                        y == 7 ? Material.CHISELED_DEEPSLATE
                                : y % 2 == 0 ? Material.POLISHED_DEEPSLATE
                                : Material.DEEPSLATE_BRICKS, false);
            }
            world.getBlockAt(corner[0], FLOOR_Y + 8, corner[1])
                    .setType(Material.AMETHYST_BLOCK, false);
            world.getBlockAt(corner[0], FLOOR_Y + 9, corner[1])
                    .setType(Material.SEA_LANTERN, false);
        }
        // The shard: an octahedron of amethyst with a lit core.
        for (int y = -3; y <= 3; y++) {
            int span = 2 - Math.abs(y) / 2;
            for (int x = -span; x <= span; x++) {
                for (int z = -span; z <= span; z++) {
                    if (Math.abs(x) + Math.abs(z) > span) continue;
                    boolean core = x == 0 && z == 0 && Math.abs(y) <= 1;
                    world.getBlockAt(x, FLOOR_Y + 11 + y, z).setType(
                            core ? Material.SEA_LANTERN
                                    : (Math.abs(x) + Math.abs(z) == span
                                    ? Material.PURPUR_BLOCK : Material.AMETHYST_BLOCK), false);
                }
            }
        }
    }

    /** Where players land: a low terrace on the south edge, looking over everything. */
    private static void buildArrival(World world) {
        for (int x = -5; x <= 5; x++) {
            for (int z = 10; z <= 15; z++) {
                double radius = Math.hypot(x, z - 12.5d);
                if (radius > 5.4d) continue;
                world.getBlockAt(x, FLOOR_Y, z).setType(
                        radius > 4.4d ? Material.SMOOTH_BASALT
                                : radius > 3.4d ? Material.CALCITE
                                : ((x + z) & 1) == 0 ? Material.CALCITE
                                : Material.AMETHYST_BLOCK, false);
                for (int y = 1; y <= 6; y++) {
                    world.getBlockAt(x, FLOOR_Y + y, z).setType(Material.AIR, false);
                }
            }
        }
        for (int[] post : List.of(new int[]{-4, 15}, new int[]{4, 15},
                new int[]{-5, 12}, new int[]{5, 12})) {
            world.getBlockAt(post[0], FLOOR_Y + 1, post[1])
                    .setType(Material.CHISELED_DEEPSLATE, false);
            world.getBlockAt(post[0], FLOOR_Y + 2, post[1])
                    .setType(Material.DEEPSLATE_BRICK_WALL, false);
            world.getBlockAt(post[0], FLOOR_Y + 3, post[1])
                    .setType(Material.AMETHYST_BLOCK, false);
            world.getBlockAt(post[0], FLOOR_Y + 4, post[1])
                    .setType(Material.SOUL_LANTERN, false);
        }
    }

    // --------------------------------------------------------- the pavilions

    /** Whether a pavilion's board is written once or rewritten while people play. */
    private enum Board { STATIC, RATINGS, LIVE }

    private record Pavilion(double angle, String title, Board board, List<String> body) { }

    /**
     * Four open shelters on the rim, each carrying one board.
     *
     * <p>These exist because the questions a player has before queueing — how the ladder
     * works, who is at the top, what the rules are, what a win is worth — were all
     * buried inside menus. On the rim they are things you walk past.
     */
    private static void buildPavilions(World world) {
        for (Pavilion pavilion : pavilions()) {
            double angle = Math.toRadians(pavilion.angle());
            int cx = symmetric(quantised(Math.sin(angle)) * PAVILION_RING);
            int cz = symmetric(-quantised(Math.cos(angle)) * PAVILION_RING);
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    boolean corner = Math.abs(dx) == 3 && Math.abs(dz) == 3;
                    if (corner) {
                        for (int y = 1; y <= 4; y++) {
                            world.getBlockAt(cx + dx, FLOOR_Y + y, cz + dz).setType(
                                    y == 4 ? Material.CHISELED_DEEPSLATE
                                            : Material.POLISHED_DEEPSLATE, false);
                        }
                        continue;
                    }
                    world.getBlockAt(cx + dx, FLOOR_Y, cz + dz).setType(
                            (dx + dz) % 2 == 0 ? Material.DEEPSLATE_TILES
                                    : Material.POLISHED_DEEPSLATE, false);
                    boolean roofEdge = Math.abs(dx) == 3 || Math.abs(dz) == 3;
                    world.getBlockAt(cx + dx, FLOOR_Y + 5, cz + dz).setType(
                            roofEdge ? Material.DEEPSLATE_BRICK_SLAB
                                    : Material.DEEPSLATE_TILES, false);
                }
            }
            world.getBlockAt(cx, FLOOR_Y + 6, cz).setType(Material.AMETHYST_BLOCK, false);
            world.getBlockAt(cx, FLOOR_Y + 7, cz).setType(Material.AMETHYST_CLUSTER, false);
            for (int[] light : List.of(new int[]{-2, -2}, new int[]{2, -2},
                    new int[]{-2, 2}, new int[]{2, 2})) {
                world.getBlockAt(cx + light[0], FLOOR_Y + 4, cz + light[1])
                        .setType(Material.SEA_LANTERN, false);
            }
        }
    }

    private static List<Pavilion> pavilions() {
        List<Pavilion> boards = new ArrayList<>();
        boards.add(new Pavilion(45d, "THE LADDER", Board.STATIC, List.of(
                BadgeIcons.PVP_BRONZE + " Bronze   " + BadgeIcons.PVP_SILVER + " Silver   "
                        + BadgeIcons.PVP_GOLD + " Gold",
                BadgeIcons.PVP_PLATINUM + " Platinum   " + BadgeIcons.PVP_DIAMOND + " Diamond",
                BadgeIcons.PVP_ELITE + " Elite   " + BadgeIcons.PVP_CHAMPION + " Champion   "
                        + BadgeIcons.PVP_UNREAL + " Unreal",
                "Ranked 1v1, 2v2 and 3v3 move your rating.",
                "Reaching a tier keeps it: you can drop divisions, never the tier."
        )));
        boards.add(new Pavilion(135d, "HOW A FIGHT WORKS", Board.STATIC, List.of(
                "You fight with the gear you walked in with.",
                "The arena is real overworld terrain, copied and put back after.",
                "You keep your whole inventory however you die.",
                "Leaving early counts as a loss. /pvp forfeit concedes cleanly."
        )));
        boards.add(new Pavilion(225d, "TOP RATINGS", Board.RATINGS, List.of()));
        boards.add(new Pavilion(315d, "LIVE NOW", Board.LIVE, List.of()));
        return boards;
    }

    // ---------------------------------------------------------- the lettering

    private static void buildHolograms(World world) {
        for (Map.Entry<PvpMode, Gate> row : GATES.entrySet()) {
            Gate gate = row.getValue();
            double x = gate.x() + 0.5d;
            double z = gate.z() + 0.5d;
            hologram(world, x, FLOOR_Y + 10.6d, z,
                    Component.text(gate.title(), AMETHYST, TextDecoration.BOLD));
            hologram(world, x, FLOOR_Y + 10.25d, z,
                    Component.text(gate.subtitle(), NamedTextColor.GRAY));
            hologram(world, x, FLOOR_Y + 9.9d, z,
                    Component.text("Stand on the pad", CRYSTAL));
            // Retitled every second by refreshStatus.
            hologram(world, x, FLOOR_Y + 9.3d, z,
                    Component.text("Checking the queue...", NamedTextColor.DARK_GRAY), STATUS_TAG);
        }
        hologram(world, RETURN_GATE[0] + 0.5d, FLOOR_Y + 8.6d, RETURN_GATE[1] + 0.5d,
                Component.text("RETURN TO THE SMP", NamedTextColor.WHITE, TextDecoration.BOLD));
        hologram(world, RETURN_GATE[0] + 0.5d, FLOOR_Y + 8.25d, RETURN_GATE[1] + 0.5d,
                Component.text("Back exactly where you came from", NamedTextColor.GRAY));

        hologram(world, 0.5d, FLOOR_Y + 15.6d, 0.5d,
                Component.text("THE AMETHYST TERRACE", AMETHYST, TextDecoration.BOLD));
        hologram(world, 0.5d, FLOOR_Y + 15.25d, 0.5d,
                Component.text("Your gear. Real terrain. Nothing lost.", CRYSTAL));
        hologram(world, 0.5d, FLOOR_Y + 3.2d, 0.5d,
                Component.text("Walk to a gateway to queue", NamedTextColor.GRAY));

        for (Pavilion pavilion : pavilions()) {
            double angle = Math.toRadians(pavilion.angle());
            double x = symmetric(quantised(Math.sin(angle)) * PAVILION_RING) + 0.5d;
            double z = symmetric(-quantised(Math.cos(angle)) * PAVILION_RING) + 0.5d;
            hologram(world, x, FLOOR_Y + 4.2d, z,
                    Component.text(pavilion.title(), AMETHYST, TextDecoration.BOLD));
            double line = FLOOR_Y + 3.85d;
            for (String text : pavilion.body()) {
                hologram(world, x, line, z, Component.text(text, NamedTextColor.WHITE));
                line -= 0.32d;
            }
            if (pavilion.board() == Board.STATIC) continue;
            // Blank rows, claimed by refreshBoards. Spawning them up front means the
            // board never rearranges itself as results come in.
            String tag = pavilion.board() == Board.RATINGS ? RATINGS_TAG : LIVE_TAG;
            for (int row = 0; row < BOARD_LINES; row++) {
                hologram(world, x, line, z,
                        Component.text(" ", NamedTextColor.DARK_GRAY), tag);
                line -= 0.32d;
            }
        }
    }

    private static void hologram(World world, double x, double y, double z, Component name,
            String... tags) {
        String[] all = new String[tags.length + 1];
        all[0] = HOLOGRAM_TAG;
        System.arraycopy(tags, 0, all, 1, tags.length);
        CrateDisplayService.spawnStyledLabel(new Location(world, x, y, z), name, all);
    }

    // --------------------------------------------------------------- helpers

    /** A path from the plaza kerb outward along one bearing. */
    private static void path(World world, double angleDegrees, int toRadius) {
        double angle = Math.toRadians(angleDegrees);
        double dirX = Math.sin(angle);
        double dirZ = -Math.cos(angle);
        for (double step = PLAZA; step <= toRadius; step += 0.5d) {
            for (double lateral = -1; lateral <= 1; lateral += 0.5d) {
                int x = (int) Math.round(dirX * step - dirZ * lateral);
                int z = (int) Math.round(dirZ * step + dirX * lateral);
                world.getBlockAt(x, FLOOR_Y, z).setType(Math.abs(lateral) < 0.75d
                        ? Material.CALCITE : Material.SMOOTH_BASALT, false);
                world.getBlockAt(x, FLOOR_Y + 1, z).setType(Material.AIR, false);
            }
        }
    }

    private static void ring(World world, int y, int radius, Material material) {
        for (int degrees = 0; degrees < 720; degrees++) {
            double angle = Math.toRadians(degrees / 2d);
            int x = (int) Math.round(Math.cos(angle) * radius);
            int z = (int) Math.round(Math.sin(angle) * radius);
            world.getBlockAt(x, y, z).setType(material, false);
        }
    }

    private static boolean clearAround(World world, int x, int z, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Material ground = world.getBlockAt(x + dx, FLOOR_Y, z + dz).getType();
                if (ground != Material.GRASS_BLOCK && ground != Material.MOSS_BLOCK
                        && ground != Material.COARSE_DIRT) {
                    return false;
                }
                if (!world.getBlockAt(x + dx, FLOOR_Y + 1, z + dz).getType().isAir()) return false;
            }
        }
        return true;
    }

    private static void setIfAir(World world, int x, int y, int z, Material material) {
        if (world.getBlockAt(x, y, z).getType().isAir()) {
            world.getBlockAt(x, y, z).setType(material, false);
        }
    }

    private static void set(
            World world, int x, int z, boolean wideX, int lateral, int y, Material material
    ) {
        world.getBlockAt(x + (wideX ? lateral : 0), y, z + (wideX ? 0 : lateral))
                .setType(material, false);
    }

    /**
     * Rounds away from zero on a tie, so a ring is a mirror of itself.
     *
     * <p>{@code Math.round} rounds half up, which sends -8.5 to -8 and 8.5 to 9. On a
     * radial layout that is a gateway one block out of line with the one opposite it,
     * and on a circle this small it is visible from the middle.
     */
    private static int symmetric(double value) {
        return (int) (value < 0 ? -Math.round(-value) : Math.round(value));
    }

    /**
     * Six decimal places, which is what makes a ring symmetrical.
     *
     * <p>{@code sin(30°)} is 0.49999999999999994 and {@code sin(330°)} is
     * -0.5000000000000004. At radius 17 that is 8.4999 against 8.5000, the two round
     * to 8 and 9, and the gateway on the left ends up a block nearer the middle than
     * the one on the right — which on a circle this small is the first thing you see.
     */
    private static double quantised(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
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
