package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

import static bot.mgx.accessbridge.PvpLobbyBuilder.AMETHYST;
import static bot.mgx.accessbridge.PvpLobbyBuilder.CRYSTAL;
import static bot.mgx.accessbridge.PvpLobbyBuilder.FLOOR_Y;
import static bot.mgx.accessbridge.PvpLobbyBuilder.STRUCTURE_TOP;

/**
 * The three category islands and their waiting halls, built beside the Proving Grounds.
 *
 * <p>The hub used to open a menu at each arch, and the menu then asked which size and
 * which access. That is the Hypixel and Hive shape turned inside out: there, a hub portal
 * takes you <em>somewhere</em> — a lobby for that one game, where every format is its own
 * portal you can see, count and walk into. This builds that. Each island is a smaller
 * Proving Grounds in the same palette, with the same arches, consoles and lettering, so
 * the three read as districts of one place rather than three new builds.
 *
 * <p>Joining any queue — by portal, by menu or by command — moves the player into the
 * category's waiting hall: an enclosed ring with a live queue board, a Leave Queue arch
 * and two consoles, which is the pre-game lobby those networks put you in while a match
 * is found. Nothing here touches the fight itself; the arena, rating and rollback all
 * stay exactly where {@link PvpDuelService} and {@link PvpCompetitionService} keep them.
 *
 * <p>Generated with the hub and bumped with the hub's format, for the same reason the
 * hub is generated at all: it must be reproducible from code.
 */
final class PvpIslandBuilder {
    /** Outer edge of an island's parapet. */
    private static final int RIM = 24;
    /** The polished line the planted court stops at, inside the rim walk. */
    private static final double EDGE = 22.4d;
    private static final int PLAZA = 6;
    private static final int PLINTH = 3;
    private static final double INNER_WALK = 9.5d;
    /** Arches stand on one ring, which is exactly one ring step nearer than the hub's. */
    private static final int GATE_RING = 14;
    private static final double CONSOLE_RING = 9.5d;
    private static final double BED_RING = 18.5d;
    private static final double ISLAND_SPAWN_Z = 9.5d;
    /** The four between-arch bearings each island's consoles stand on, as on the hub. */
    private static final double[] CONSOLE_BEARINGS = {90d, 126d, 234d, 270d};
    private static final double RING_STEP = 36d;

    /** The waiting hall: a walled ring around its live board. */
    private static final int HALL_WALL = 12;
    private static final int HALL_GATE_RING = 10;
    private static final double HALL_CONSOLE_RING = 7d;
    private static final double[] HALL_CONSOLE_BEARINGS = {90d, 270d};
    private static final double HALL_SPAWN_Z = -7.5d;
    private static final int HALL_WALL_TOP = 6;
    /** How far out from its island a hall is built, along the island's own bearing. */
    private static final int HALL_DISTANCE = 100;
    /** Where a label or pad lookup still counts as belonging to an island or hall. */
    private static final double ZONE_RADIUS = 40d;

    private static final String HALL_BOARD_TAG = "mgx_pvp_hall_board";
    private static final int HALL_BOARD_LINES = 5;

    /** One category district. Centres are relative to the hub's own origin. */
    enum Island {
        RANKED(PvpMode.RANKED_DUEL, "RANKED BATTLE", 0, -320,
                Material.YELLOW_STAINED_GLASS, Material.GOLD_BLOCK),
        CLAN(PvpMode.CLAN_BATTLE, "CLAN BATTLE", -320, 0,
                Material.RED_STAINED_GLASS, Material.REDSTONE_BLOCK),
        LAST_STANDING(PvpMode.FFA, "LAST STANDING", 320, 0,
                Material.PURPLE_STAINED_GLASS, Material.AMETHYST_BLOCK);

        private final PvpMode family;
        private final String title;
        private final int x;
        private final int z;
        private final Material glass;
        private final Material emblem;

        Island(PvpMode family, String title, int x, int z, Material glass, Material emblem) {
            this.family = family;
            this.title = title;
            this.x = x;
            this.z = z;
            this.glass = glass;
            this.emblem = emblem;
        }

        PvpMode family() {
            return family;
        }

        String title() {
            return title;
        }

        int x() {
            return x;
        }

        int z() {
            return z;
        }

        int hallX() {
            return x + Integer.signum(x) * HALL_DISTANCE;
        }

        int hallZ() {
            return z + Integer.signum(z) * HALL_DISTANCE;
        }

        static Island of(PvpMode mode) {
            return switch (PvpMatchSetup.category(mode)) {
                case CLAN_BATTLE -> CLAN;
                case FFA -> LAST_STANDING;
                default -> RANKED;
            };
        }
    }

    /** One queue arch on an island: walking in joins exactly this public setup. */
    record Station(Island island, double angle, PvpMatchSetup setup, String title, String subtitle) {
        int x() {
            return island.x() + PvpLobbyBuilder.ringX(angle, GATE_RING, 0d);
        }

        int z() {
            return island.z() + PvpLobbyBuilder.ringZ(angle, GATE_RING, 0d);
        }
    }

    enum ConsoleAction { TEAM, CUSTOM, SIZES, STATS, RULES, STATUS }

    private record Console(double angle, String title, String body, ConsoleAction action) { }

    enum TouchKind { STATION, ISLAND_RETURN, HALL_LEAVE }

    /** What a player walked into; {@code station} is set only for a queue arch. */
    record Touch(TouchKind kind, Island island, Station station) { }

    /** The district a location belongs to, and whether it is the waiting hall. */
    record Zone(Island island, boolean hall) { }

    record Clicked(Island island, ConsoleAction action) { }

    private static final List<Station> STATIONS = stations();

    private PvpIslandBuilder() { }

    private static List<Station> stations() {
        List<Station> all = new ArrayList<>();
        // The most-played format takes the arch an arriving player faces.
        all.add(new Station(Island.RANKED, 324d, setup(PvpMode.RANKED_DUEL, 2),
                "RANKED 2v2", "Solo or with a partner • rated"));
        all.add(new Station(Island.RANKED, 0d, setup(PvpMode.RANKED_DUEL, 1),
                "RANKED 1v1", "Just you • rated"));
        all.add(new Station(Island.RANKED, 36d, setup(PvpMode.RANKED_DUEL, 3),
                "RANKED 3v3", "Solo or a party of three • rated"));
        all.add(new Station(Island.CLAN, 324d, setup(PvpMode.CLAN_BATTLE, 2),
                "CLAN 2v2", "Bring a same-clan partner"));
        all.add(new Station(Island.CLAN, 36d, setup(PvpMode.CLAN_BATTLE, 3),
                "CLAN 3v3", "Bring two clanmates"));
        all.add(new Station(Island.LAST_STANDING, 324d, setup(PvpMode.FFA, 4),
                "4 PLAYERS", "Small field • quickest start"));
        all.add(new Station(Island.LAST_STANDING, 0d, setup(PvpMode.FFA, 8),
                "8 PLAYERS", "The classic field"));
        all.add(new Station(Island.LAST_STANDING, 36d, setup(PvpMode.FFA, 12),
                "12 PLAYERS", "The full Last Standing"));
        return List.copyOf(all);
    }

    private static PvpMatchSetup setup(PvpMode family, int size) {
        boolean ffa = family == PvpMode.FFA;
        return new PvpMatchSetup(family, ffa ? 1 : size, ffa ? size : size * 2,
                PvpMatchSetup.Access.PUBLIC, true);
    }

    static List<Station> stations(Island island) {
        return STATIONS.stream().filter(station -> station.island() == island).toList();
    }

    private static List<Console> consoles(Island island) {
        List<Console> consoles = new ArrayList<>();
        consoles.add(island == Island.LAST_STANDING
                ? new Console(CONSOLE_BEARINGS[0], "EXACT FIELD SIZE",
                "ANY FIELD FROM 2 TO 12", ConsoleAction.SIZES)
                : new Console(CONSOLE_BEARINGS[0], "YOUR TEAM",
                "INVITE • ACCEPT • REMOVE", ConsoleAction.TEAM));
        consoles.add(new Console(CONSOLE_BEARINGS[1], "YOUR RECORD",
                "RANK • WINS • KILLS", ConsoleAction.STATS));
        consoles.add(new Console(CONSOLE_BEARINGS[2], "FIGHT RULES",
                "YOUR GEAR • REAL TERRAIN • KEEP INVENTORY", ConsoleAction.RULES));
        consoles.add(new Console(CONSOLE_BEARINGS[3], "CUSTOM FIGHT",
                "SIZE • ACCESS • INVITES", ConsoleAction.CUSTOM));
        return consoles;
    }

    private static List<Console> hallConsoles() {
        return List.of(
                new Console(HALL_CONSOLE_BEARINGS[0], "QUEUE STATUS",
                        "PLAYERS • WAIT • NEXT STEP", ConsoleAction.STATUS),
                new Console(HALL_CONSOLE_BEARINGS[1], "FIGHT RULES",
                        "YOUR GEAR • REAL TERRAIN • KEEP INVENTORY", ConsoleAction.RULES));
    }

    // ------------------------------------------------------------ build

    static void buildAll(World world, GameVariableStore variables) {
        for (Island island : Island.values()) {
            clearArea(world, island.x(), island.z(), RIM + 4);
            clearArea(world, island.hallX(), island.hallZ(), HALL_WALL + 4);
            Random random = new Random(0x4D_4758_4953_4CL + island.ordinal());
            buildIsland(world, island, random);
            buildHall(world, island, random);
        }
        buildHolograms(world, variables);
    }

    private static void clearArea(World world, int cx, int cz, int radius) {
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                for (int y = FLOOR_Y - 30; y <= FLOOR_Y + 24; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
    }

    private static void buildIsland(World world, Island island, Random random) {
        int cx = island.x();
        int cz = island.z();
        buildUnderside(world, cx, cz, RIM, 10, random);
        for (int x = -RIM; x <= RIM; x++) {
            for (int z = -RIM; z <= RIM; z++) {
                double radius = Math.hypot(x, z);
                if (radius > RIM + 0.5d) continue;
                world.getBlockAt(cx + x, FLOOR_Y, cz + z)
                        .setType(islandFloor(x, z, radius), false);
                boolean planted = world.getBlockAt(cx + x, FLOOR_Y, cz + z).getType()
                        == Material.GRASS_BLOCK
                        || world.getBlockAt(cx + x, FLOOR_Y, cz + z).getType()
                        == Material.MOSS_BLOCK;
                world.getBlockAt(cx + x, FLOOR_Y - 1, cz + z).setType(
                        planted ? Material.DIRT : Material.DEEPSLATE, false);
            }
        }
        // The plinth and plaza kerb, the same one-step bowl as the hub.
        ring(world, cx, cz, FLOOR_Y, PLAZA, Material.POLISHED_DEEPSLATE);
        for (double spoke = 0d; spoke < 360d; spoke += RING_STEP) {
            world.getBlockAt(cx + PvpLobbyBuilder.ringX(spoke, PLINTH + 1.6d, 0d), FLOOR_Y,
                            cz + PvpLobbyBuilder.ringZ(spoke, PLINTH + 1.6d, 0d))
                    .setType(Material.SEA_LANTERN, false);
        }
        buildParapet(world, cx, cz, RIM, FLOOR_Y);
        buildMonument(world, island);
        buildArrival(world, cx, cz);
        for (Console console : consoles(island)) {
            buildConsole(world, cx, cz, console.angle(), CONSOLE_RING);
        }
        for (double between = 18d; between < 360d; between += RING_STEP) {
            // Nothing on the south approach: the arrival pad and the way back own it.
            if (PvpLobbyBuilder.angularGap(between, 180d) < 30d) continue;
            if (!consoleBearing(between, CONSOLE_BEARINGS)) {
                lamp(world, cx, cz, between, INNER_WALK);
            }
            bed(world, cx, cz, between, BED_RING);
        }
        if (stations(island).size() == 2) buildPylon(world, cx, cz, 0d, GATE_RING);
        // Portals last, exactly as on the hub, so no decoration can leave one invalid.
        for (Station station : stations(island)) {
            buildArch(world, cx, cz, station.angle(), GATE_RING, island.glass, island.emblem);
        }
        buildReturnArch(world, cx, cz, 180d, GATE_RING);
    }

    private static Material islandFloor(int x, int z, double radius) {
        double degrees = PvpLobbyBuilder.bearing(x, z);
        double arc = Math.toRadians(spokeGap(degrees)) * radius;
        if (radius <= PLINTH + 0.5d) {
            return PvpLobbyBuilder.within(radius, PLINTH - 0.6d) ? Material.POLISHED_DEEPSLATE
                    : ((x + z) & 1) == 0 ? Material.CALCITE : Material.POLISHED_DIORITE;
        }
        if (radius <= PLAZA + 0.5d) {
            return arc < 1.1d ? Material.CALCITE
                    : ((x * 3 + z * 5) & 7) == 0 ? Material.CHISELED_DEEPSLATE
                    : Material.DEEPSLATE_TILES;
        }
        if (radius > EDGE + 0.5d) {
            return ((x + z) & 3) == 0 ? Material.CHISELED_DEEPSLATE : Material.DEEPSLATE_TILES;
        }
        if (PvpLobbyBuilder.within(radius, EDGE)) return Material.POLISHED_DEEPSLATE;
        if (Math.abs(radius - INNER_WALK) <= 1.2d) {
            return Math.abs(radius - INNER_WALK) > 0.8d ? Material.SMOOTH_BASALT
                    : Material.CALCITE;
        }
        // The south approach runs from the arrival pad to the way back.
        double southGap = Math.toRadians(PvpLobbyBuilder.angularGap(degrees, 180d)) * radius;
        if (arc < 1.9d || southGap < 1.9d) return Material.CALCITE;
        if (arc < 2.7d || southGap < 2.7d) return Material.SMOOTH_BASALT;
        int pattern = (Math.abs(x) * 7 + Math.abs(z) * 11 + x * x + z * z) % 31;
        return pattern == 0 ? Material.COARSE_DIRT
                : pattern < 6 ? Material.MOSS_BLOCK : Material.GRASS_BLOCK;
    }

    /** Degrees from the nearest ten-step bearing, the spacing both builds share. */
    private static double spokeGap(double degrees) {
        double best = 360d;
        for (double spoke = 0d; spoke < 360d; spoke += RING_STEP) {
            best = Math.min(best, PvpLobbyBuilder.angularGap(degrees, spoke));
        }
        return best;
    }

    private static boolean consoleBearing(double degrees, double[] bearings) {
        for (double bearing : bearings) {
            if (PvpLobbyBuilder.angularGap(degrees, bearing) < 1d) return true;
        }
        return false;
    }

    /** The hub's hanging shards, sized to whatever this platform is. */
    private static void buildUnderside(
            World world, int cx, int cz, int rim, int depth, Random random
    ) {
        for (int x = -rim; x <= rim; x++) {
            for (int z = -rim; z <= rim; z++) {
                double radius = Math.hypot(x, z);
                if (radius > rim + 0.5d) continue;
                int thickness = (int) Math.round(2 + (1d - radius / rim) * (depth - 3));
                for (int down = 1; down <= thickness; down++) {
                    world.getBlockAt(cx + x, FLOOR_Y - down, cz + z).setType(
                            PvpLobbyBuilder.undersideMaterial(x, z, down, random), false);
                }
                if (random.nextInt(6) != 0) continue;
                int length = (int) Math.round((1d - radius / rim) * depth * 1.4d)
                        + random.nextInt(4);
                for (int down = thickness + 1; down <= thickness + length; down++) {
                    if (random.nextInt(14) == 0) break;
                    world.getBlockAt(cx + x, FLOOR_Y - down, cz + z).setType(
                            PvpLobbyBuilder.undersideMaterial(x, z, down, random), false);
                }
            }
        }
    }

    /** The hub rim's crenellated course, on this platform's own circle. */
    private static void buildParapet(World world, int cx, int cz, int radius, int floor) {
        for (int degrees = 0; degrees < 1440; degrees++) {
            double angle = degrees / 4d;
            int x = cx + PvpLobbyBuilder.ringX(angle, radius, 0d);
            int z = cz + PvpLobbyBuilder.ringZ(angle, radius, 0d);
            world.getBlockAt(x, floor + 1, z).setType(Material.DEEPSLATE_BRICKS, false);
            world.getBlockAt(x, floor + 2, z).setType(
                    (degrees & 15) == 0 ? Material.CHISELED_DEEPSLATE
                            : Material.DEEPSLATE_BRICK_WALL, false);
        }
        for (double angle : new double[]{45d, 135d, 225d, 315d}) {
            int x = cx + PvpLobbyBuilder.ringX(angle, radius - 1.2d, 0d);
            int z = cz + PvpLobbyBuilder.ringZ(angle, radius - 1.2d, 0d);
            for (int y = 1; y <= 5; y++) {
                world.getBlockAt(x, floor + y, z).setType(
                        y == 5 ? Material.AMETHYST_BLOCK
                                : (y & 1) == 0 ? Material.POLISHED_DEEPSLATE
                                : Material.DEEPSLATE_BRICKS, false);
            }
            world.getBlockAt(x, floor + 6, z).setType(Material.SEA_LANTERN, false);
            world.getBlockAt(x, floor + 7, z).setType(Material.AMETHYST_CLUSTER, false);
        }
    }

    /**
     * The island's centrepiece: the hub's four buttresses around a column of the
     * category's own emblem, so the district is named in stone as well as in text.
     */
    private static void buildMonument(World world, Island island) {
        int cx = island.x();
        int cz = island.z();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                double radius = Math.hypot(x, z);
                if (radius > 2.4d) continue;
                world.getBlockAt(cx + x, FLOOR_Y + 1, cz + z).setType(
                        radius > 1.6d ? Material.DEEPSLATE_BRICK_SLAB
                                : Material.CHISELED_DEEPSLATE, false);
            }
        }
        for (int y = 2; y <= 5; y++) {
            world.getBlockAt(cx, FLOOR_Y + y, cz).setType(
                    y == 5 ? Material.SEA_LANTERN
                            : y == 3 ? island.emblem : Material.POLISHED_DEEPSLATE, false);
        }
        for (int[] corner : List.of(new int[]{-2, -2}, new int[]{2, -2},
                new int[]{-2, 2}, new int[]{2, 2})) {
            for (int y = 1; y <= 4; y++) {
                world.getBlockAt(cx + corner[0], FLOOR_Y + y, cz + corner[1]).setType(
                        y == 4 ? island.glass
                                : y % 2 == 0 ? Material.POLISHED_DEEPSLATE
                                : Material.DEEPSLATE_BRICKS, false);
            }
            world.getBlockAt(cx + corner[0], FLOOR_Y + 5, cz + corner[1])
                    .setType(Material.SOUL_LANTERN, false);
        }
        // The floating emblem: a small version of the hub's crystal, in the category colour.
        for (int y = -2; y <= 2; y++) {
            int span = 1 - Math.abs(y) / 2;
            for (int x = -span; x <= span; x++) {
                for (int z = -span; z <= span; z++) {
                    if (Math.abs(x) + Math.abs(z) > span) continue;
                    boolean core = x == 0 && z == 0 && y == 0;
                    world.getBlockAt(cx + x, FLOOR_Y + 9 + y, cz + z).setType(
                            core ? Material.SEA_LANTERN : island.emblem, false);
                }
            }
        }
    }

    /** The hub's arrival pad, on the south walk in front of the way back. */
    private static void buildArrival(World world, int cx, int cz) {
        for (int x = -3; x <= 3; x++) {
            for (int z = 7; z <= 11; z++) {
                boolean edge = Math.abs(x) == 3 || z == 7 || z == 11;
                world.getBlockAt(cx + x, FLOOR_Y, cz + z).setType(
                        edge ? Material.POLISHED_DEEPSLATE
                                : ((x + z) & 1) == 0 ? Material.CALCITE
                                : Material.AMETHYST_BLOCK, false);
            }
        }
        world.getBlockAt(cx, FLOOR_Y, cz + 9).setType(Material.SEA_LANTERN, false);
        for (int[] post : List.of(new int[]{-3, 7}, new int[]{3, 7},
                new int[]{-3, 11}, new int[]{3, 11})) {
            world.getBlockAt(cx + post[0], FLOOR_Y + 1, cz + post[1])
                    .setType(Material.CHISELED_DEEPSLATE, false);
            world.getBlockAt(cx + post[0], FLOOR_Y + 2, cz + post[1])
                    .setType(Material.AMETHYST_BLOCK, false);
            world.getBlockAt(cx + post[0], FLOOR_Y + 3, cz + post[1])
                    .setType(Material.SOUL_LANTERN, false);
        }
    }

    /** The hub console, unchanged: a desk and a lit screen in a copper surround. */
    private static void buildConsole(World world, int cx, int cz, double angle, double ring) {
        for (int lateral = -1; lateral <= 1; lateral++) {
            for (double step = -1d; step <= 1d; step += 1d) {
                world.getBlockAt(cx + PvpLobbyBuilder.ringX(angle, ring + step, lateral), FLOOR_Y,
                                cz + PvpLobbyBuilder.ringZ(angle, ring + step, lateral))
                        .setType(Math.abs(lateral) == 1 ? Material.CHISELED_DEEPSLATE
                                : Material.POLISHED_DEEPSLATE, false);
            }
            world.getBlockAt(cx + PvpLobbyBuilder.ringX(angle, ring - 1d, lateral), FLOOR_Y + 1,
                            cz + PvpLobbyBuilder.ringZ(angle, ring - 1d, lateral))
                    .setType(Material.DEEPSLATE_BRICK_SLAB, false);
        }
        for (int y = 1; y <= 2; y++) {
            world.getBlockAt(cx + PvpLobbyBuilder.ringX(angle, ring + 1d, 0d), FLOOR_Y + y,
                            cz + PvpLobbyBuilder.ringZ(angle, ring + 1d, 0d))
                    .setType(Material.SEA_LANTERN, false);
            for (int lateral : new int[]{-1, 1}) {
                world.getBlockAt(cx + PvpLobbyBuilder.ringX(angle, ring + 1d, lateral), FLOOR_Y + y,
                                cz + PvpLobbyBuilder.ringZ(angle, ring + 1d, lateral))
                        .setType(y == 1 ? Material.CUT_COPPER : Material.EXPOSED_CUT_COPPER, false);
            }
        }
        for (int lateral = -1; lateral <= 1; lateral++) {
            world.getBlockAt(cx + PvpLobbyBuilder.ringX(angle, ring + 1d, lateral), FLOOR_Y + 3,
                            cz + PvpLobbyBuilder.ringZ(angle, ring + 1d, lateral))
                    .setType(Material.CHISELED_DEEPSLATE, false);
        }
        world.getBlockAt(cx + PvpLobbyBuilder.ringX(angle, ring + 1d, 0d), FLOOR_Y + 4,
                        cz + PvpLobbyBuilder.ringZ(angle, ring + 1d, 0d))
                .setType(Material.AMETHYST_BLOCK, false);
    }

    private static void lamp(World world, int cx, int cz, double angle, double radius) {
        int x = cx + PvpLobbyBuilder.ringX(angle, radius, 0d);
        int z = cz + PvpLobbyBuilder.ringZ(angle, radius, 0d);
        for (int y = 1; y <= 3; y++) {
            world.getBlockAt(x, FLOOR_Y + y, z).setType(
                    y == 2 ? Material.AMETHYST_BLOCK : Material.POLISHED_DEEPSLATE, false);
        }
        world.getBlockAt(x, FLOOR_Y + 4, z).setType(Material.SEA_LANTERN, false);
        world.getBlockAt(x, FLOOR_Y + 5, z).setType(Material.DEEPSLATE_BRICK_SLAB, false);
    }

    private static void bed(World world, int cx, int cz, double angle, double radius) {
        int bx = cx + PvpLobbyBuilder.ringX(angle, radius, 0d);
        int bz = cz + PvpLobbyBuilder.ringZ(angle, radius, 0d);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > 1) continue;
                world.getBlockAt(bx + dx, FLOOR_Y, bz + dz).setType(
                        dx == 0 && dz == 0 ? Material.CALCITE : Material.MOSS_BLOCK, false);
            }
        }
        world.getBlockAt(bx, FLOOR_Y + 1, bz).setType(Material.CHISELED_DEEPSLATE, false);
        world.getBlockAt(bx, FLOOR_Y + 2, bz).setType(Material.AMETHYST_BLOCK, false);
        world.getBlockAt(bx, FLOOR_Y + 3, bz).setType(Material.SEA_LANTERN, false);
        for (int[] offset : List.of(new int[]{-1, 0}, new int[]{1, 0},
                new int[]{0, -1}, new int[]{0, 1})) {
            world.getBlockAt(bx + offset[0], FLOOR_Y + 1, bz + offset[1])
                    .setType(Material.FLOWERING_AZALEA, false);
        }
    }

    /** The hub's lantern pylon, standing in an island's one empty arch position. */
    private static void buildPylon(World world, int cx, int cz, double angle, double radius) {
        plinth(world, cx, cz, angle, radius, 3);
        int x = cx + PvpLobbyBuilder.ringX(angle, radius, 0d);
        int z = cz + PvpLobbyBuilder.ringZ(angle, radius, 0d);
        for (int y = 1; y <= STRUCTURE_TOP + 1; y++) {
            Material course = y == STRUCTURE_TOP + 1 ? Material.CHISELED_DEEPSLATE
                    : y % 4 == 0 ? Material.REDSTONE_BLOCK
                    : (y & 1) == 0 ? Material.POLISHED_DEEPSLATE
                    : Material.DEEPSLATE_BRICKS;
            world.getBlockAt(x, FLOOR_Y + y, z).setType(course, false);
        }
        world.getBlockAt(x, FLOOR_Y + STRUCTURE_TOP + 2, z).setType(Material.SEA_LANTERN, false);
    }

    private static void plinth(World world, int cx, int cz, double angle, double radius, int half) {
        for (int lateral = -half; lateral <= half; lateral++) {
            for (double step = -1.4d; step <= 1.4d; step += 0.7d) {
                world.getBlockAt(cx + PvpLobbyBuilder.ringX(angle, radius + step, lateral), FLOOR_Y,
                                cz + PvpLobbyBuilder.ringZ(angle, radius + step, lateral))
                        .setType(Math.abs(lateral) == half ? Material.CHISELED_DEEPSLATE
                                : Material.POLISHED_DEEPSLATE, false);
            }
        }
        for (int lateral = 1 - half; lateral <= half - 1; lateral++) {
            world.getBlockAt(cx + PvpLobbyBuilder.ringX(angle, radius - 2.1d, lateral), FLOOR_Y,
                            cz + PvpLobbyBuilder.ringZ(angle, radius - 2.1d, lateral))
                    .setType(Material.DEEPSLATE_BRICK_SLAB, false);
        }
    }

    /**
     * One queue arch, built exactly like a hub gateway: plinth, lit pad, coloured
     * accents in front of the posts, copper pylons, and the vanilla-valid portal plane
     * written after everything around it.
     */
    private static void buildArch(
            World world, int cx, int cz, double angle, int ring, Material glass, Material emblem
    ) {
        int rx = PvpLobbyBuilder.ringX(angle, ring, 0d);
        int rz = PvpLobbyBuilder.ringZ(angle, ring, 0d);
        int gx = cx + rx;
        int gz = cz + rz;
        boolean wideX = Math.abs(rx) <= Math.abs(rz);
        plinth(world, cx, cz, angle, ring, 4);
        PvpLobbyBuilder.pad(world, gx, gz, emblem);
        for (int y : new int[]{3, 6}) {
            for (int lateral : new int[]{-3, 3}) {
                int[] at = decoration(rx, rz, wideX, lateral, 1);
                world.getBlockAt(cx + at[0], FLOOR_Y + y, cz + at[1]).setType(glass, false);
            }
        }
        for (int lateral : new int[]{-2, 2}) {
            int[] at = decoration(rx, rz, wideX, lateral, 2);
            int px = cx + at[0];
            int pz = cz + at[1];
            world.getBlockAt(px, FLOOR_Y, pz).setType(Material.POLISHED_DEEPSLATE, false);
            world.getBlockAt(px, FLOOR_Y + 1, pz).setType(Material.CUT_COPPER, false);
            world.getBlockAt(px, FLOOR_Y + 2, pz).setType(glass, false);
            world.getBlockAt(px, FLOOR_Y + 3, pz).setType(Material.SOUL_LANTERN, false);
        }
        PvpLobbyBuilder.buildGatewayPlane(world, gx, gz, wideX);
        PvpLobbyBuilder.set(world, gx, gz, wideX, 0, FLOOR_Y + STRUCTURE_TOP + 1, emblem);
    }

    private static void buildReturnArch(World world, int cx, int cz, double angle, int ring) {
        int gx = cx + PvpLobbyBuilder.ringX(angle, ring, 0d);
        int gz = cz + PvpLobbyBuilder.ringZ(angle, ring, 0d);
        plinth(world, cx, cz, angle, ring, 4);
        PvpLobbyBuilder.pad(world, gx, gz, Material.LODESTONE);
        PvpLobbyBuilder.buildGatewayPlane(world, gx, gz, true);
        PvpLobbyBuilder.set(world, gx, gz, true, 0, FLOOR_Y + STRUCTURE_TOP + 1, Material.LODESTONE);
    }

    /** A decoration in front of an arch, on the arch's own block axis (see the hub). */
    static int[] decoration(int rx, int rz, boolean wideX, int lateral, int depth) {
        int inward = wideX ? Integer.compare(0, rz) : Integer.compare(0, rx);
        if (inward == 0) inward = 1;
        return wideX
                ? new int[]{rx + lateral, rz + inward * depth}
                : new int[]{rx + inward * depth, rz + lateral};
    }

    /**
     * The waiting hall.
     *
     * <p>Walled, because a pre-game lobby is a room you are held in rather than a place
     * you wander off from, and glazed in the category colour so it still reads as part
     * of the same district. The board at the middle is the thing everybody waiting
     * looks at; the Leave Queue arch is directly behind it, never hidden.
     */
    private static void buildHall(World world, Island island, Random random) {
        int cx = island.hallX();
        int cz = island.hallZ();
        buildUnderside(world, cx, cz, HALL_WALL + 1, 6, random);
        for (int x = -HALL_WALL - 1; x <= HALL_WALL + 1; x++) {
            for (int z = -HALL_WALL - 1; z <= HALL_WALL + 1; z++) {
                double radius = Math.hypot(x, z);
                if (radius > HALL_WALL + 1.5d) continue;
                world.getBlockAt(cx + x, FLOOR_Y, cz + z).setType(hallFloor(x, z, radius), false);
                world.getBlockAt(cx + x, FLOOR_Y - 1, cz + z).setType(Material.DEEPSLATE, false);
            }
        }
        for (int degrees = 0; degrees < 1440; degrees++) {
            double angle = degrees / 4d;
            int x = cx + PvpLobbyBuilder.ringX(angle, HALL_WALL, 0d);
            int z = cz + PvpLobbyBuilder.ringZ(angle, HALL_WALL, 0d);
            boolean pillar = PvpLobbyBuilder.angularGap(angle, Math.round(angle / 30d) * 30d) < 3d;
            for (int y = 1; y <= HALL_WALL_TOP; y++) {
                Material course;
                if (y == 1 || y == HALL_WALL_TOP - 1) course = Material.DEEPSLATE_BRICKS;
                else if (y == HALL_WALL_TOP) course = (degrees & 15) == 0
                        ? Material.CHISELED_DEEPSLATE : Material.DEEPSLATE_BRICK_WALL;
                else course = pillar ? Material.POLISHED_DEEPSLATE : island.glass;
                world.getBlockAt(x, FLOOR_Y + y, z).setType(course, false);
            }
        }
        for (double angle = 15d; angle < 360d; angle += 30d) {
            if (PvpLobbyBuilder.angularGap(angle, 180d) < 20d) continue;
            int x = cx + PvpLobbyBuilder.ringX(angle, HALL_WALL - 1, 0d);
            int z = cz + PvpLobbyBuilder.ringZ(angle, HALL_WALL - 1, 0d);
            world.getBlockAt(x, FLOOR_Y + 1, z).setType(Material.CHISELED_DEEPSLATE, false);
            world.getBlockAt(x, FLOOR_Y + 2, z).setType(Material.AMETHYST_BLOCK, false);
            world.getBlockAt(x, FLOOR_Y + 3, z).setType(Material.SOUL_LANTERN, false);
        }
        // The board's pedestal, low enough that every row stands clear of it.
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                world.getBlockAt(cx + x, FLOOR_Y + 1, cz + z).setType(
                        x == 0 && z == 0 ? Material.CHISELED_DEEPSLATE
                                : Material.DEEPSLATE_BRICK_SLAB, false);
            }
        }
        world.getBlockAt(cx, FLOOR_Y + 2, cz).setType(island.emblem, false);
        for (Console console : hallConsoles()) {
            buildConsole(world, cx, cz, console.angle(), HALL_CONSOLE_RING);
        }
        buildReturnArch(world, cx, cz, 180d, HALL_GATE_RING);
    }

    private static Material hallFloor(int x, int z, double radius) {
        if (radius > HALL_WALL - 0.5d) return Material.POLISHED_DEEPSLATE;
        double arc = Math.toRadians(spokeGap(PvpLobbyBuilder.bearing(x, z))) * radius;
        if (radius <= 2.5d) {
            return ((x + z) & 1) == 0 ? Material.CALCITE : Material.POLISHED_DIORITE;
        }
        if (PvpLobbyBuilder.within(radius, 3.4d) || PvpLobbyBuilder.within(radius, 8.6d)) {
            return Material.POLISHED_DEEPSLATE;
        }
        if (PvpLobbyBuilder.within(radius, 6d) && ((x + z) & 3) == 0) return Material.SEA_LANTERN;
        if (arc < 1.1d) return Material.CALCITE;
        if (arc < 1.9d) return Material.POLISHED_DIORITE;
        return ((x * 3 + z * 5) & 7) == 0 ? Material.CHISELED_DEEPSLATE : Material.DEEPSLATE_TILES;
    }

    private static void ring(World world, int cx, int cz, int y, int radius, Material material) {
        for (int degrees = 0; degrees < 720; degrees++) {
            double angle = Math.toRadians(degrees / 2d);
            world.getBlockAt(cx + PvpLobbyBuilder.symmetric(
                                    PvpLobbyBuilder.quantised(Math.cos(angle)) * radius), y,
                            cz + PvpLobbyBuilder.symmetric(
                                    PvpLobbyBuilder.quantised(Math.sin(angle)) * radius))
                    .setType(material, false);
        }
    }

    // ------------------------------------------------------------ lettering

    static void buildHolograms(World world, GameVariableStore variables) {
        float titleScale = (float) variables.decimal("pvp-competitive.lobby-title-scale");
        float lineScale = (float) variables.decimal("pvp-competitive.lobby-line-scale");
        float gateTitleScale = (float) variables.decimal("pvp-competitive.lobby-gate-title-scale");
        for (Island island : Island.values()) {
            clearLabels(world, island.x(), island.z());
            clearLabels(world, island.hallX(), island.hallZ());
            double cx = island.x();
            double cz = island.z();
            PvpLobbyBuilder.hologram(world, cx + 0.5d, FLOOR_Y + 13.2d, cz + 0.5d,
                    Component.text(island.title(), AMETHYST, TextDecoration.BOLD),
                    titleScale, PvpLobbyBuilder.NEAR_GATE_TAG);
            PvpLobbyBuilder.hologram(world, cx + 0.5d, FLOOR_Y + 12.1d, cz + 0.5d,
                    Component.text("Walk into a portal to join its queue", NamedTextColor.WHITE),
                    lineScale, PvpLobbyBuilder.NEAR_GATE_TAG);
            for (Station station : stations(island)) {
                double lx = station.x() + 0.5d;
                double lz = station.z() + 0.5d;
                PvpLobbyBuilder.hologram(world, lx, FLOOR_Y + STRUCTURE_TOP + 4.8d, lz,
                        Component.text(station.title(), AMETHYST, TextDecoration.BOLD),
                        gateTitleScale, PvpLobbyBuilder.NEAR_GATE_TAG);
                PvpLobbyBuilder.hologram(world, lx, FLOOR_Y + STRUCTURE_TOP + 3.6d, lz,
                        Component.text(station.subtitle(), NamedTextColor.WHITE),
                        lineScale, PvpLobbyBuilder.NEAR_GATE_TAG);
                PvpLobbyBuilder.hologram(world, lx, FLOOR_Y + STRUCTURE_TOP + 2.4d, lz,
                        Component.text("WALK IN • CHECKING QUEUE", CRYSTAL, TextDecoration.BOLD),
                        lineScale, PvpLobbyBuilder.NEAR_GATE_TAG, PvpLobbyBuilder.STATUS_TAG);
            }
            double returnZ = cz + PvpLobbyBuilder.ringZ(180d, GATE_RING, 0d) + 0.5d;
            PvpLobbyBuilder.hologram(world, cx + 0.5d, FLOOR_Y + STRUCTURE_TOP + 3.6d, returnZ,
                    Component.text("BACK TO THE PROVING GROUNDS", NamedTextColor.WHITE,
                            TextDecoration.BOLD), gateTitleScale, PvpLobbyBuilder.NEAR_GATE_TAG);
            PvpLobbyBuilder.hologram(world, cx + 0.5d, FLOOR_Y + STRUCTURE_TOP + 2.4d, returnZ,
                    Component.text("WALK THROUGH • ALL THREE CATEGORIES", CRYSTAL),
                    lineScale, PvpLobbyBuilder.NEAR_GATE_TAG);
            for (Console console : consoles(island)) {
                consoleLabels(world, cx, cz, console, CONSOLE_RING, titleScale, lineScale);
            }

            double hx = island.hallX();
            double hz = island.hallZ();
            PvpLobbyBuilder.hologram(world, hx + 0.5d, FLOOR_Y + 7.4d, hz + 0.5d,
                    Component.text(island.title() + " • QUEUE LOBBY", AMETHYST, TextDecoration.BOLD),
                    titleScale, PvpLobbyBuilder.NEAR_GATE_TAG);
            double line = FLOOR_Y + 6.3d;
            for (int row = 0; row < HALL_BOARD_LINES; row++) {
                PvpLobbyBuilder.hologram(world, hx + 0.5d, line, hz + 0.5d,
                        Component.text(" ", NamedTextColor.DARK_GRAY), lineScale,
                        PvpLobbyBuilder.NEAR_GATE_TAG, HALL_BOARD_TAG);
                line -= 0.72d;
            }
            double leaveZ = hz + PvpLobbyBuilder.ringZ(180d, HALL_GATE_RING, 0d) + 0.5d;
            PvpLobbyBuilder.hologram(world, hx + 0.5d, FLOOR_Y + STRUCTURE_TOP + 3.6d, leaveZ,
                    Component.text("LEAVE QUEUE", NamedTextColor.WHITE, TextDecoration.BOLD),
                    gateTitleScale, PvpLobbyBuilder.NEAR_GATE_TAG);
            PvpLobbyBuilder.hologram(world, hx + 0.5d, FLOOR_Y + STRUCTURE_TOP + 2.4d, leaveZ,
                    Component.text("WALK THROUGH • BACK TO THE ISLAND", CRYSTAL),
                    lineScale, PvpLobbyBuilder.NEAR_GATE_TAG);
            for (Console console : hallConsoles()) {
                consoleLabels(world, hx, hz, console, HALL_CONSOLE_RING, titleScale, lineScale);
            }
        }
    }

    private static void consoleLabels(
            World world, double cx, double cz, Console console, double ring,
            float titleScale, float lineScale
    ) {
        double x = cx + PvpLobbyBuilder.ringX(console.angle(), ring - 0.5d, 0d) + 0.5d;
        double z = cz + PvpLobbyBuilder.ringZ(console.angle(), ring - 0.5d, 0d) + 0.5d;
        PvpLobbyBuilder.hologram(world, x, FLOOR_Y + 7.2d, z,
                Component.text(console.title(), AMETHYST, TextDecoration.BOLD),
                titleScale, PvpLobbyBuilder.NEAR_BOARD_TAG);
        PvpLobbyBuilder.hologram(world, x, FLOOR_Y + 6.1d, z,
                Component.text(console.body(), NamedTextColor.WHITE),
                lineScale, PvpLobbyBuilder.NEAR_BOARD_TAG);
        PvpLobbyBuilder.hologram(world, x, FLOOR_Y + 5.3d, z,
                Component.text("► RIGHT-CLICK TO OPEN", CRYSTAL, TextDecoration.BOLD),
                lineScale, PvpLobbyBuilder.NEAR_BOARD_TAG);
    }

    private static void clearLabels(World world, int cx, int cz) {
        Location centre = new Location(world, cx + 0.5d, FLOOR_Y + 8d, cz + 0.5d);
        for (Entity entity : world.getNearbyEntities(centre, ZONE_RADIUS, 30d, ZONE_RADIUS)) {
            if (entity.getScoreboardTags().contains(PvpLobbyBuilder.HOLOGRAM_TAG)) entity.remove();
        }
    }

    // ------------------------------------------------------------ lookups

    /** The hub's own 0,0, from which every island and hall is placed. */
    private static double[] origin(PvpLobbyStore.Point lobby) {
        return new double[]{lobby.x() - 0.5d, lobby.z() - PvpLobbyBuilder.SPAWN_Z};
    }

    private static boolean inWorld(PvpLobbyStore.Point lobby, Location at) {
        Location centre = lobby == null ? null : lobby.resolve();
        return centre != null && at != null && at.getWorld() != null
                && centre.getWorld().equals(at.getWorld());
    }

    static Location islandSpawn(PvpLobbyStore.Point lobby, Island island) {
        Location centre = lobby == null ? null : lobby.resolve();
        if (centre == null) return null;
        double[] origin = origin(lobby);
        return new Location(centre.getWorld(), origin[0] + island.x() + 0.5d, FLOOR_Y + 1d,
                origin[1] + island.z() + ISLAND_SPAWN_Z, 180f, 0f);
    }

    static Location hallSpawn(PvpLobbyStore.Point lobby, Island island) {
        Location centre = lobby == null ? null : lobby.resolve();
        if (centre == null) return null;
        double[] origin = origin(lobby);
        return new Location(centre.getWorld(), origin[0] + island.hallX() + 0.5d, FLOOR_Y + 1d,
                origin[1] + island.hallZ() + HALL_SPAWN_Z, 0f, 0f);
    }

    static Zone zone(PvpLobbyStore.Point lobby, Location at) {
        if (!inWorld(lobby, at)) return null;
        double[] origin = origin(lobby);
        for (Island island : Island.values()) {
            if (PvpLobbyBuilder.horizontalSquared(at, origin[0] + island.hallX() + 0.5d,
                    origin[1] + island.hallZ() + 0.5d) <= ZONE_RADIUS * ZONE_RADIUS) {
                return new Zone(island, true);
            }
            if (PvpLobbyBuilder.horizontalSquared(at, origin[0] + island.x() + 0.5d,
                    origin[1] + island.z() + 0.5d) <= ZONE_RADIUS * ZONE_RADIUS) {
                return new Zone(island, false);
            }
        }
        return null;
    }

    /** Which portal on an island or in a hall the player is standing in, if any. */
    static Touch touch(PvpLobbyStore.Point lobby, Location at) {
        Zone zone = zone(lobby, at);
        if (zone == null || !PvpLobbyBuilder.touchesPortal(at)) return null;
        double[] origin = origin(lobby);
        Island island = zone.island();
        if (zone.hall()) {
            return onPad(at, origin[0] + island.hallX(),
                    origin[1] + island.hallZ() + PvpLobbyBuilder.ringZ(180d, HALL_GATE_RING, 0d))
                    ? new Touch(TouchKind.HALL_LEAVE, island, null) : null;
        }
        for (Station station : stations(island)) {
            if (onPad(at, origin[0] + station.x(), origin[1] + station.z())) {
                return new Touch(TouchKind.STATION, island, station);
            }
        }
        return onPad(at, origin[0] + island.x(),
                origin[1] + island.z() + PvpLobbyBuilder.ringZ(180d, GATE_RING, 0d))
                ? new Touch(TouchKind.ISLAND_RETURN, island, null) : null;
    }

    /** The queue arch a player is walking up to, for the action-bar hint on approach. */
    static Station nearbyStation(PvpLobbyStore.Point lobby, Location at, double radius) {
        Zone zone = zone(lobby, at);
        if (zone == null || zone.hall()) return null;
        double[] origin = origin(lobby);
        Station nearest = null;
        double best = radius * radius;
        for (Station station : stations(zone.island())) {
            double distance = PvpLobbyBuilder.horizontalSquared(at,
                    origin[0] + station.x() + 0.5d, origin[1] + station.z() + 0.5d);
            if (distance <= best) {
                nearest = station;
                best = distance;
            }
        }
        return nearest;
    }

    /**
     * Where a player lands if walking into a queue arch did not queue them.
     *
     * <p>On the plaza side, facing the monument: an arch here sends a successful player
     * to the hall, so this landing only ever holds somebody reading why they were refused,
     * and held forward movement then carries them away from the portal.
     */
    static Location stationExit(PvpLobbyStore.Point lobby, Station station) {
        Location centre = lobby == null ? null : lobby.resolve();
        if (centre == null) return null;
        double[] origin = origin(lobby);
        double standX = PvpLobbyBuilder.ringX(station.angle(), GATE_RING - 4d, 0d) + 0.5d;
        double standZ = PvpLobbyBuilder.ringZ(station.angle(), GATE_RING - 4d, 0d) + 0.5d;
        Location at = new Location(centre.getWorld(),
                origin[0] + station.island().x() + standX, FLOOR_Y + 1d,
                origin[1] + station.island().z() + standZ);
        at.setDirection(new org.bukkit.util.Vector(-standX, 0d, -standZ));
        return at;
    }

    private static boolean onPad(Location at, double x, double z) {
        return Math.abs(at.getY() - (FLOOR_Y + 1d)) <= 4d
                && PvpLobbyBuilder.horizontalSquared(at, x, z) <= 6.25d;
    }

    static Clicked console(PvpLobbyStore.Point lobby, Location clicked) {
        Zone zone = zone(lobby, clicked);
        if (zone == null || Math.abs(clicked.getY() - (FLOOR_Y + 1d)) > 5d) return null;
        double[] origin = origin(lobby);
        Island island = zone.island();
        double cx = origin[0] + (zone.hall() ? island.hallX() : island.x());
        double cz = origin[1] + (zone.hall() ? island.hallZ() : island.z());
        double ring = zone.hall() ? HALL_CONSOLE_RING : CONSOLE_RING;
        for (Console console : zone.hall() ? hallConsoles() : consoles(island)) {
            double x = cx + PvpLobbyBuilder.ringX(console.angle(), ring, 0d);
            double z = cz + PvpLobbyBuilder.ringZ(console.angle(), ring, 0d);
            if (PvpLobbyBuilder.horizontalSquared(clicked, x, z) <= 6.25d) {
                return new Clicked(island, console.action());
            }
        }
        return null;
    }

    // ------------------------------------------------------------ live view

    static void pulse(PvpLobbyStore.Point lobby, int portalParticles) {
        Location centre = lobby == null ? null : lobby.resolve();
        if (centre == null || centre.getWorld() == null) return;
        World world = centre.getWorld();
        if (world.getPlayers().isEmpty()) return;
        double[] origin = origin(lobby);
        int step = (int) ((System.currentTimeMillis() / 250L) % 8L);
        int particles = Math.max(0, portalParticles);
        for (Island island : Island.values()) {
            double ix = origin[0] + island.x();
            double iz = origin[1] + island.z();
            if (nobodyNear(world, ix, iz)) continue;
            for (Station station : stations(island)) {
                world.spawnParticle(Particle.PORTAL, origin[0] + station.x(),
                        FLOOR_Y + 1.6d + step * 0.55d, origin[1] + station.z(),
                        particles, 1.8d, 0.25d, 1.8d, 0.04d);
                world.spawnParticle(Particle.ENCHANT, origin[0] + station.x(), FLOOR_Y + 5d,
                        origin[1] + station.z(), Math.max(0, particles / 2), 2d, 2.8d, 2d, 0.02d);
            }
            world.spawnParticle(Particle.REVERSE_PORTAL, ix,
                    FLOOR_Y + 3d, iz + PvpLobbyBuilder.ringZ(180d, GATE_RING, 0d),
                    8, 1.2d, 1.6d, 1.2d, 0.02d);
            orbit(world, ix, FLOOR_Y + 9.5d, iz, 1.8d);
        }
        for (Island island : Island.values()) {
            double hx = origin[0] + island.hallX();
            double hz = origin[1] + island.hallZ();
            if (nobodyNear(world, hx, hz)) continue;
            world.spawnParticle(Particle.REVERSE_PORTAL, hx, FLOOR_Y + 3d,
                    hz + PvpLobbyBuilder.ringZ(180d, HALL_GATE_RING, 0d), 8, 1.2d, 1.6d, 1.2d, 0.02d);
            orbit(world, hx, FLOOR_Y + 2.8d, hz, 1.6d);
        }
    }

    /** The hub's turning crystal ring, at island scale. */
    private static void orbit(World world, double x, double y, double z, double radius) {
        double spin = (System.currentTimeMillis() % 6_000L) / 6_000d * Math.PI * 2d;
        for (int point = 0; point < 8; point++) {
            double angle = spin + point * (Math.PI * 2d / 8d);
            world.spawnParticle(Particle.END_ROD, x + Math.cos(angle) * radius,
                    y + Math.sin(spin) * 0.3d, z + Math.sin(angle) * radius, 1, 0d, 0d, 0d, 0d);
        }
    }

    private static boolean nobodyNear(World world, double x, double z) {
        for (Player player : world.getPlayers()) {
            if (PvpLobbyBuilder.horizontalSquared(player.getLocation(), x, z) <= 64d * 64d) {
                return false;
            }
        }
        return true;
    }

    /** Retitles the live line under every queue arch, as the hub does under its gateways. */
    static void refreshStatus(PvpLobbyStore.Point lobby, Function<Station, Component> status) {
        Location centre = lobby == null ? null : lobby.resolve();
        if (centre == null || centre.getWorld() == null) return;
        World world = centre.getWorld();
        double[] origin = origin(lobby);
        for (Station station : STATIONS) {
            double sx = origin[0] + station.x();
            double sz = origin[1] + station.z();
            if (nobodyNear(world, sx, sz)) continue;
            Component line = status.apply(station);
            Location at = new Location(world, sx + 0.5d, FLOOR_Y + STRUCTURE_TOP + 2.4d, sz + 0.5d);
            world.getNearbyEntities(at, 1.2d, 1.2d, 1.2d).stream()
                    .filter(entity -> entity.getScoreboardTags().contains(PvpLobbyBuilder.STATUS_TAG))
                    .forEach(entity -> PvpLobbyBuilder.setLabel(entity, line));
        }
    }

    /** Rewrites each waiting hall's board, top down, blank rows below a shorter list. */
    static void refreshHallBoards(
            PvpLobbyStore.Point lobby, Function<Island, List<Component>> rows
    ) {
        Location centre = lobby == null ? null : lobby.resolve();
        if (centre == null || centre.getWorld() == null) return;
        World world = centre.getWorld();
        double[] origin = origin(lobby);
        for (Island island : Island.values()) {
            double hx = origin[0] + island.hallX();
            double hz = origin[1] + island.hallZ();
            if (nobodyNear(world, hx, hz)) continue;
            PvpLobbyBuilder.rewriteBoard(world,
                    new Location(world, hx + 0.5d, FLOOR_Y + 4.8d, hz + 0.5d),
                    HALL_BOARD_TAG, rows.apply(island));
        }
    }

    static int hallBoardLines() {
        return HALL_BOARD_LINES;
    }

    /** Repairs any island or hall portal whose frame or plane was disturbed. */
    static int repairPortals(PvpLobbyStore.Point lobby) {
        Location centre = lobby == null ? null : lobby.resolve();
        if (centre == null || centre.getWorld() == null) return 0;
        World world = centre.getWorld();
        int originX = (int) Math.round(centre.getX() - 0.5d);
        int originZ = (int) Math.round(centre.getZ() - PvpLobbyBuilder.SPAWN_Z);
        int repaired = 0;
        List<int[]> planes = new ArrayList<>();
        for (Station station : STATIONS) {
            int rx = station.x() - station.island().x();
            int rz = station.z() - station.island().z();
            planes.add(new int[]{station.x(), station.z(), Math.abs(rx) <= Math.abs(rz) ? 1 : 0});
        }
        for (Island island : Island.values()) {
            planes.add(new int[]{island.x(),
                    island.z() + PvpLobbyBuilder.ringZ(180d, GATE_RING, 0d), 1});
            planes.add(new int[]{island.hallX(),
                    island.hallZ() + PvpLobbyBuilder.ringZ(180d, HALL_GATE_RING, 0d), 1});
        }
        for (int[] plane : planes) {
            int gx = originX + plane[0];
            int gz = originZ + plane[1];
            // Unloaded chunks are left alone: nobody is standing at a portal nobody can see.
            if (!world.isChunkLoaded(gx >> 4, gz >> 4)) continue;
            boolean wideX = plane[2] == 1;
            if (!PvpLobbyBuilder.gatewayPortalIntact(world, gx, gz, wideX)) {
                PvpLobbyBuilder.buildGatewayPlane(world, gx, gz, wideX);
                repaired++;
            }
        }
        return repaired;
    }

    /** Every portal position relative to the hub origin, so a test can check the plan. */
    static Map<String, int[]> portalPositions() {
        Map<String, int[]> positions = new java.util.LinkedHashMap<>();
        for (Station station : STATIONS) {
            positions.put(station.island() + ":" + station.title(),
                    new int[]{station.x(), station.z()});
        }
        for (Island island : Island.values()) {
            positions.put(island + ":return", new int[]{island.x(),
                    island.z() + PvpLobbyBuilder.ringZ(180d, GATE_RING, 0d)});
            positions.put(island + ":leave", new int[]{island.hallX(),
                    island.hallZ() + PvpLobbyBuilder.ringZ(180d, HALL_GATE_RING, 0d)});
        }
        return positions;
    }

    /** Every lamp and bed bearing, so a test can keep the south approach clear. */
    static List<int[]> furniturePositions(Island island) {
        List<int[]> spots = new ArrayList<>();
        for (double between = 18d; between < 360d; between += RING_STEP) {
            if (PvpLobbyBuilder.angularGap(between, 180d) < 30d) continue;
            if (!consoleBearing(between, CONSOLE_BEARINGS)) {
                spots.add(new int[]{PvpLobbyBuilder.ringX(between, INNER_WALK, 0d),
                        PvpLobbyBuilder.ringZ(between, INNER_WALK, 0d)});
            }
            spots.add(new int[]{PvpLobbyBuilder.ringX(between, BED_RING, 0d),
                    PvpLobbyBuilder.ringZ(between, BED_RING, 0d)});
        }
        return spots;
    }

    static int gateRing() {
        return GATE_RING;
    }

    static int islandRim() {
        return RIM;
    }

    static int hallWall() {
        return HALL_WALL;
    }
}
