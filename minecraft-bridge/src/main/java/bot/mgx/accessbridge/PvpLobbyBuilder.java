package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Axis;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.util.Transformation;

import java.util.ArrayList;
import java.util.Comparator;
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
 * server it belongs to. The queue hub stays inside a 56-block circle, so a player who
 * arrives can see every queue and the way home without walking. A short north bridge
 * leads to a dedicated six-board leaderboard gallery, all in the Amethyst expansion's
 * own palette — deepslate, calcite, copper and crystal.
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
    /** Covers the main terrace plus the circular north records terrace. */
    static final double PROTECTED_RADIUS = 74d;

    private static final String HOLOGRAM_TAG = "mgx_pvp_lobby_hologram";
    /** Carried by the one line under each gateway that is retitled every second. */
    private static final String STATUS_TAG = "mgx_pvp_lobby_status";
    private static final String LIVE_TAG = "mgx_pvp_board_live";
    private static final String TEXT_LABEL_TAG = "mgx_pvp_text_label";
    private static final String FALLBACK_LABEL_TAG = "mgx_pvp_fallback_label";
    private static final String NEAR_GATE_TAG = "mgx_pvp_near_gate";
    private static final String NEAR_BOARD_TAG = "mgx_pvp_near_board";
    private static final String RECORDS_LABEL_TAG = "mgx_pvp_records_label";
    /** Rows reserved on each live board, blank until there is something to say. */
    private static final int LIVE_BOARD_LINES = 3;
    private static final int LEADERBOARD_LINES = 5;
    private static final int FLOOR_Y = 80;

    /** Outer edge of the rim walkway. Everything is inside this. */
    private static final int RIM = 22;
    private static final int MOAT_OUTER = 20;
    private static final int MOAT_INNER = 18;
    /** The ring walk that runs behind every structure. */
    private static final double PROMENADE = 16.4d;
    /**
     * The one ring every tall thing stands on.
     *
     * <p>Three portals, six leaderboards and the way home are ten structures, and ten
     * structures on one circle at one spacing is the whole organising idea of this
     * island. Nothing stands on a bearing nothing else uses.
     */
    private static final int STRUCTURE_RING = 15;
    /** Every structure on the ring caps here, giving the island one cornice line. */
    private static final int STRUCTURE_TOP = 8;
    private static final double RING_STEP = 36d;
    private static final double[] STRUCTURE_BEARINGS = structureBearings();
    private static final int PLAZA = 9;
    /** The raised disc the monument stands on, inside the sunken plaza. */
    private static final int PLINTH = 5;
    /** The four low consoles, inside the plaza and between the approaches. */
    private static final double PAVILION_RING = 11.5d;
    /** Four of the ten between-structure bearings carry a console instead of a bed. */
    private static final double[] PAVILION_BEARINGS = {54d, 126d, 234d, 306d};
    /** Board text stands this far in front of its wall, so turning never clips it. */
    private static final double BOARD_LABEL_RING = STRUCTURE_RING - 3.5d;
    /** Height of the retitled status line, shared by the build and the lookup. */
    private static final double GATE_STATUS_Y = FLOOR_Y + STRUCTURE_TOP + 2.4d;
    private static final double SPAWN_Z = 10.5d;
    private static final int[] RETURN_GATE = {0, STRUCTURE_RING};
    /** The moat is crossed east and west, clear of every structure. */
    private static final double[] CROSSINGS = {90d, 270d};
    /** Starter arenas generated in this void world by the retired 8.0 PvP system. */
    private static final int[][] LEGACY_FIGHTING_PLATFORMS = {
            {0, -180, 27}, {80, -180, 27},
            {180, -100, 32}, {180, 80, 36},
            {-180, 80, 36}, {-180, -100, 46}
    };

    private static final TextColor AMETHYST = TextColor.color(0xB56CFF);
    private static final TextColor CRYSTAL = TextColor.color(0xE3C6FF);

    private static final Map<PvpMode, Gate> GATES = gates();

    /** Ten evenly spaced bearings, mirror-symmetric about the north-south axis. */
    private static double[] structureBearings() {
        double[] bearings = new double[10];
        for (int index = 0; index < bearings.length; index++) {
            bearings[index] = index * RING_STEP;
        }
        return bearings;
    }

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
            return ringX(angle, STRUCTURE_RING, 0d);
        }

        int z() {
            return ringZ(angle, STRUCTURE_RING, 0d);
        }
    }

    /** One physical board in the north leaderboard gallery. */
    enum LeaderboardBoard {
        RATING("HIGHEST RATING", 288d, "mgx_pvp_board_rating"),
        WINS("MOST WINS", 252d, "mgx_pvp_board_wins"),
        KILLS("MOST KILLS", 216d, "mgx_pvp_board_kills"),
        STREAK("BEST WIN STREAK", 72d, "mgx_pvp_board_streak"),
        CLAN_WINS("CLAN WINS", 108d, "mgx_pvp_board_clan_wins"),
        CLAN_KILLS("CLAN KILLS", 144d, "mgx_pvp_board_clan_kills");

        private final String title;
        private final double angle;
        private final String tag;

        LeaderboardBoard(String title, double angle, String tag) {
            this.title = title;
            this.angle = angle;
            this.tag = tag;
        }

        /** Where this board's text stands: on its bearing, clear of its own wall. */
        double x() {
            return Math.sin(Math.toRadians(angle)) * BOARD_LABEL_RING + 0.5d;
        }

        double z() {
            return -Math.cos(Math.toRadians(angle)) * BOARD_LABEL_RING + 0.5d;
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
        buildCourt(world);
        buildMoat(world);
        buildRim(world);
        for (Map.Entry<PvpMode, Gate> row : GATES.entrySet()) {
            buildGate(world, row.getValue());
        }
        for (LeaderboardBoard board : LeaderboardBoard.values()) {
            buildLeaderboardFrame(world, board);
        }
        buildReturnGate(world);
        buildMonument(world);
        buildArrival(world);
        buildPavilions(world);
        // Last, so a tree is never planted where a gateway or the terrace is about to
        // go and left hanging in the air with its trunk replaced.
        plantGarden(world);
        // The first cleanup can run before a gate chunk has loaded. Geometry touches
        // every lobby chunk, so repeat it here to remove labels left by older formats
        // before the one canonical set is spawned.
        clearLobbyLabels(world);
        buildHolograms(world, plugin.gameVariables());

        Location lobby = new Location(world, 0.5d, FLOOR_Y + 1d, SPAWN_Z, 180f, 0f);
        world.setSpawnLocation(lobby);
        return new Built(world, PvpLobbyStore.Point.of(lobby));
    }

    // ---------------------------------------------------------------- pads

    static PvpMode modePad(PvpLobbyStore.Point lobby, Location at) {
        Location origin = origin(lobby, at);
        if (origin == null || !touchesPortal(at)) return null;
        for (Map.Entry<PvpMode, Gate> row : GATES.entrySet()) {
            if (onPad(at, origin, row.getValue().x(), row.getValue().z())) return row.getKey();
        }
        return null;
    }

    static boolean returnPad(PvpLobbyStore.Point lobby, Location at) {
        Location origin = origin(lobby, at);
        return origin != null && touchesPortal(at)
                && onPad(at, origin, RETURN_GATE[0], RETURN_GATE[1]);
    }

    /** The queue whose portal is close enough to provide a contextual action-bar hint. */
    static PvpMode nearbyMode(PvpLobbyStore.Point lobby, Location at, double radius) {
        Location origin = origin(lobby, at);
        if (origin == null) return null;
        double maximum = Math.max(1d, radius) * Math.max(1d, radius);
        PvpMode nearest = null;
        double nearestDistance = maximum;
        for (Map.Entry<PvpMode, Gate> row : GATES.entrySet()) {
            double distance = horizontalSquared(at, origin.getX() + row.getValue().x(),
                    origin.getZ() + row.getValue().z());
            if (distance <= nearestDistance) {
                nearest = row.getKey();
                nearestDistance = distance;
            }
        }
        return nearest;
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

    /** Moving portal ribbons and a double crystal orbit keep the terrace visibly alive. */
    static void pulse(PvpLobbyStore.Point lobby, int portalParticles) {
        Location centre = lobby == null ? null : lobby.resolve();
        if (centre == null || centre.getWorld() == null) return;
        World world = centre.getWorld();
        if (world.getPlayers().isEmpty()) return;
        double originX = centre.getX() - 0.5d;
        double originZ = centre.getZ() - SPAWN_Z;
        int step = (int) ((System.currentTimeMillis() / 250L) % 8L);
        int particles = Math.max(0, portalParticles);
        for (Gate gate : GATES.values()) {
            world.spawnParticle(Particle.PORTAL,
                    originX + gate.x(), FLOOR_Y + 1.6d + step * 0.55d, originZ + gate.z(),
                    particles, 1.8d, 0.25d, 1.8d, 0.04d);
            world.spawnParticle(Particle.ENCHANT,
                    originX + gate.x(), FLOOR_Y + 5d, originZ + gate.z(),
                    Math.max(0, particles / 2), 2d, 2.8d, 2d, 0.02d);
        }
        world.spawnParticle(Particle.REVERSE_PORTAL,
                originX + RETURN_GATE[0], FLOOR_Y + 3d, originZ + RETURN_GATE[1],
                8, 1.2d, 1.6d, 1.2d, 0.02d);
        // The crystal over the monument, turning on its own axis.
        double spin = (System.currentTimeMillis() % 6_000L) / 6_000d * Math.PI * 2d;
        for (int point = 0; point < 12; point++) {
            double angle = spin + point * (Math.PI * 2d / 12d);
            world.spawnParticle(Particle.END_ROD,
                    originX + Math.cos(angle) * 2.1d, FLOOR_Y + 11.5d + Math.sin(spin) * 0.4d,
                    originZ + Math.sin(angle) * 2.1d, 1, 0d, 0d, 0d, 0d);
            if ((point & 1) == 0) {
                double counter = -spin + point * (Math.PI * 2d / 6d);
                world.spawnParticle(Particle.PORTAL,
                        originX + Math.cos(counter) * 3.4d,
                        FLOOR_Y + 11.5d + Math.sin(counter) * 0.8d,
                        originZ + Math.sin(counter) * 3.4d, 1, 0d, 0d, 0d, 0d);
            }
        }
    }

    /**
     * Shows one label implementation per client and only while its station is nearby.
     * This is what prevents every gateway and pavilion from being legible through every
     * other structure on a compact circular island.
     */
    static void updateLabelViewers(
            PvpLobbyStore.Point lobby,
            MGXAccessBridge plugin,
            SettingsClientSupport clients,
            double gateDistance,
            double boardDistance,
            double leaderboardDistance
    ) {
        Location centre = lobby == null ? null : lobby.resolve();
        if (centre == null || centre.getWorld() == null) return;
        World world = centre.getWorld();
        List<Entity> labels = world.getEntities().stream()
                .filter(entity -> entity.getScoreboardTags().contains(HOLOGRAM_TAG)).toList();
        for (Player player : world.getPlayers()) {
            boolean useText = clients.supportsTextDisplays(player);
            for (Entity label : labels) {
                // Every board now stands on the same ring as the arches, so each label
                // is simply measured from itself rather than from a second island.
                double range = label.getScoreboardTags().contains(RECORDS_LABEL_TAG)
                        ? leaderboardDistance
                        : label.getScoreboardTags().contains(NEAR_BOARD_TAG)
                        ? boardDistance : gateDistance;
                boolean nearby = label.getLocation().distanceSquared(player.getLocation())
                        <= Math.max(1d, range) * Math.max(1d, range);
                boolean correctType = useText
                        ? label.getScoreboardTags().contains(TEXT_LABEL_TAG)
                        : label.getScoreboardTags().contains(FALLBACK_LABEL_TAG);
                if (correctType && nearby) player.showEntity(plugin, label);
                else player.hideEntity(plugin, label);
            }
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
            Gate gate = row.getValue();
            Location at = new Location(world, originX + gate.x() + 0.5d,
                    GATE_STATUS_Y, originZ + gate.z() + 0.5d);
            world.getNearbyEntities(at, 1.2d, 1.2d, 1.2d).stream()
                    .filter(stand -> stand.getScoreboardTags().contains(STATUS_TAG))
                    .forEach(entity -> setLabel(entity, line));
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

    /** Where the walk home stands, so the whole ring can be asserted as one set. */
    static int[] returnGatePosition() {
        return new int[]{RETURN_GATE[0], RETURN_GATE[1]};
    }

    /** The ten bearings every structure on the island is placed on. */
    static double[] structureBearingsView() {
        return STRUCTURE_BEARINGS.clone();
    }

    static int gateRing() {
        return STRUCTURE_RING;
    }

    /**
     * Rewrites the six gallery leaderboards and the live-match pavilion.
     *
     * <p>Rows are matched top down by height, so a shorter list clears the rows below
     * it rather than leaving yesterday's leader hanging under today's.
     */
    static void refreshBoards(
            PvpLobbyStore.Point lobby,
            Map<LeaderboardBoard, List<Component>> leaderboards,
            List<Component> live
    ) {
        Location centre = lobby == null ? null : lobby.resolve();
        if (centre == null || centre.getWorld() == null) return;
        World world = centre.getWorld();
        if (world.getPlayers().isEmpty()) return;
        double originX = centre.getX() - 0.5d;
        double originZ = centre.getZ() - SPAWN_Z;
        for (LeaderboardBoard board : LeaderboardBoard.values()) {
            rewriteBoard(world, new Location(world, originX + board.x(),
                    FLOOR_Y + 5.3d, originZ + board.z()), board.tag,
                    leaderboards.getOrDefault(board, List.of()));
        }
        for (Pavilion pavilion : pavilions()) {
            if (pavilion.board() != Board.LIVE) continue;
            double angle = Math.toRadians(pavilion.angle());
            rewriteBoard(world, new Location(world,
                            originX + symmetric(quantised(Math.sin(angle)) * PAVILION_RING) + 0.5d,
                            FLOOR_Y + 3d,
                            originZ + symmetric(-quantised(Math.cos(angle)) * PAVILION_RING) + 0.5d),
                    LIVE_TAG, live);
        }
    }

    private static void rewriteBoard(
            World world, Location at, String tag, List<Component> lines
    ) {
        List<Double> heights = world.getNearbyEntities(at, 3d, 5d, 3d).stream()
                .filter(entity -> entity.getScoreboardTags().contains(tag))
                .map(entity -> entity.getLocation().getY()).distinct()
                .sorted(Comparator.reverseOrder()).toList();
        for (int row = 0; row < heights.size(); row++) {
            Component line = row < lines.size() ? lines.get(row)
                    : Component.text(" ", NamedTextColor.DARK_GRAY);
            double height = heights.get(row);
            world.getNearbyEntities(new Location(world, at.getX(), height, at.getZ()),
                            0.4d, 0.2d, 0.4d).stream()
                    .filter(entity -> entity.getScoreboardTags().contains(tag))
                    .forEach(entity -> setLabel(entity, line));
        }
    }

    /** How many rows each dedicated leaderboard can show. */
    static int boardLines() {
        return LEADERBOARD_LINES;
    }

    static int liveBoardLines() {
        return LIVE_BOARD_LINES;
    }

    static int[] leaderboardPosition(LeaderboardBoard board) {
        return new int[]{ringX(board.angle, STRUCTURE_RING, 0d),
                ringZ(board.angle, STRUCTURE_RING, 0d)};
    }

    private static Map<PvpMode, Gate> gates() {
        Map<PvpMode, Gate> gates = new LinkedHashMap<>();
        // The three arches take the northern bearings on the structure ring, with the
        // ranked arch dead ahead of an arriving player and the other two flanking it
        // one ring step out. A player who spawns and looks up sees all three at once.
        gates.put(PvpMode.CLAN_BATTLE, new Gate(324d, Material.RED_STAINED_GLASS,
                Material.REDSTONE_BLOCK, "CLAN BATTLE", "Choose 2v2 or 3v3."));
        gates.put(PvpMode.RANKED_DUEL, new Gate(0d, Material.YELLOW_STAINED_GLASS,
                Material.GOLD_BLOCK, "RANKED BATTLE", "Choose 1v1, 2v2 or 3v3."));
        gates.put(PvpMode.FFA, new Gate(36d, Material.PURPLE_STAINED_GLASS,
                Material.AMETHYST_BLOCK, "LAST STANDING", "Choose how many can enter."));
        return Map.copyOf(gates);
    }

    // ----------------------------------------------------------- the island

    private static void clearOldLobby(World world) {
        for (Entity entity : world.getEntities()) {
            if (entity.getScoreboardTags().contains(HOLOGRAM_TAG)) entity.remove();
        }
        clearLegacyFightingPlatforms(world);
        // Circular rather than square, and only as deep as the old citadel reached:
        // the previous lobby was 96 across, so its corners have to go too.
        for (int x = -52; x <= 52; x++) {
            for (int z = -52; z <= 52; z++) {
                for (int y = FLOOR_Y - 30; y <= FLOOR_Y + 24; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
        // The dedicated leaderboard annex extends north of the former square. Keep
        // this second pass narrow so a format upgrade does not clear another huge box.
        for (int x = -22; x <= 22; x++) {
            for (int z = -67; z < -52; z++) {
                for (int y = FLOOR_Y - 30; y <= FLOOR_Y + 24; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
    }

    /**
     * Removes the six generated arena boxes retired when competitive fights moved back
     * to the ordinary overworld. Their exact floor, wall and cover coordinates are
     * deterministic, so this clears every old block without sweeping six huge cuboids.
     */
    private static void clearLegacyFightingPlatforms(World world) {
        int[][] cover = {{-9, -9}, {-9, 9}, {9, -9}, {9, 9}, {0, 0}};
        for (int[] arena : LEGACY_FIGHTING_PLATFORMS) {
            int centreX = arena[0];
            int centreZ = arena[1];
            int radius = arena[2];
            for (int x = centreX - radius; x <= centreX + radius; x++) {
                for (int z = centreZ - radius; z <= centreZ + radius; z++) {
                    world.getBlockAt(x, FLOOR_Y - 1, z).setType(Material.AIR, false);
                    world.getBlockAt(x, FLOOR_Y, z).setType(Material.AIR, false);
                    boolean edge = x == centreX - radius || x == centreX + radius
                            || z == centreZ - radius || z == centreZ + radius;
                    if (!edge) continue;
                    for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 5; y++) {
                        world.getBlockAt(x, y, z).setType(Material.AIR, false);
                    }
                }
            }
            for (int[] offset : cover) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 3; y++) {
                            world.getBlockAt(centreX + offset[0] + dx, y,
                                    centreZ + offset[1] + dz).setType(Material.AIR, false);
                        }
                    }
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
    /**
     * The plaza: a shallow bowl walked down into, around a raised monument plinth.
     *
     * <p>One step is the entire trick. A lobby built on a single plane reads as a
     * drawing of a lobby however it is decorated, because nothing in it has a near
     * side and a far side. Sinking the middle by one block gives the monument a base
     * to stand on and the court an edge to look over.
     */
    private static void buildPlaza(World world) {
        for (int x = -PLAZA - 1; x <= PLAZA + 1; x++) {
            for (int z = -PLAZA - 1; z <= PLAZA + 1; z++) {
                double radius = Math.hypot(x, z);
                if (radius > PLAZA + 0.5d) continue;
                int top = radius <= PLINTH + 0.5d ? FLOOR_Y : FLOOR_Y - 1;
                world.getBlockAt(x, top, z).setType(plazaFloor(x, z, radius), false);
                if (top < FLOOR_Y) {
                    world.getBlockAt(x, FLOOR_Y, z).setType(Material.AIR, false);
                }
                for (int depth = 1; depth <= 4; depth++) {
                    world.getBlockAt(x, top - depth, z).setType(
                            depth == 1 ? Material.DEEPSLATE : Material.COBBLED_DEEPSLATE,
                            false);
                }
                for (int y = 1; y <= 12; y++) {
                    world.getBlockAt(x, FLOOR_Y + y, z).setType(Material.AIR, false);
                }
            }
        }
        // The plinth face, and the kerb the court steps down over.
        ring(world, FLOOR_Y - 1, PLINTH, Material.POLISHED_DEEPSLATE);
        ring(world, FLOOR_Y, PLAZA, Material.POLISHED_DEEPSLATE);
        for (int degrees = 0; degrees < 1440; degrees++) {
            double angle = degrees / 4d;
            world.getBlockAt(ringX(angle, PLAZA - 0.6d, 0d), FLOOR_Y - 1,
                            ringZ(angle, PLAZA - 0.6d, 0d))
                    .setType(Material.DEEPSLATE_BRICK_SLAB, false);
        }
        // Lit inlays in the bowl floor, one on each of the ten bearings.
        for (double spoke : STRUCTURE_BEARINGS) {
            world.getBlockAt(ringX(spoke, PLINTH + 1.6d, 0d), FLOOR_Y - 1,
                            ringZ(spoke, PLINTH + 1.6d, 0d))
                    .setType(Material.SEA_LANTERN, false);
        }
    }

    /**
     * The plaza inlay.
     *
     * <p>Ten spokes and two rings: the same ten bearings the structures stand on, so
     * the floor pattern names where everything is instead of being decoration that
     * happens to be under it.
     */
    private static Material plazaFloor(int x, int z, double radius) {
        double arc = Math.toRadians(spokeGap(bearing(x, z))) * radius;
        if (radius <= PLINTH + 0.5d) {
            return within(radius, PLINTH - 0.6d) ? Material.POLISHED_DEEPSLATE
                    : ((x + z) & 1) == 0 ? Material.CALCITE : Material.POLISHED_DIORITE;
        }
        if (within(radius, 7.6d)) return Material.CALCITE;
        if (arc < 1.1d) return Material.CALCITE;
        if (arc < 1.9d) return Material.POLISHED_DIORITE;
        return ((x * 3 + z * 5) & 7) == 0
                ? Material.CHISELED_DEEPSLATE : Material.DEEPSLATE_TILES;
    }

    /** Half a block either side, so a drawn ring is a line and not a band. */
    private static boolean within(double radius, double target) {
        return Math.abs(radius - target) < 0.45d;
    }

    /** Degrees clockwise from due north, which is how the whole island is laid out. */
    private static double bearing(double x, double z) {
        double degrees = Math.toDegrees(Math.atan2(x, -z));
        return degrees < 0d ? degrees + 360d : degrees;
    }

    /** Degrees from the nearest of the ten structure bearings. */
    private static double spokeGap(double degrees) {
        double best = 360d;
        for (double spoke : STRUCTURE_BEARINGS) {
            best = Math.min(best, angularGap(degrees, spoke));
        }
        return best;
    }

    /** Degrees between two bearings, whichever way round the circle is shorter. */
    private static double angularGap(double left, double right) {
        double gap = Math.abs(left - right) % 360d;
        return gap > 180d ? 360d - gap : gap;
    }

    /**
     * The court: ten processional approaches, planted beds between them, and the
     * promenade that runs behind every structure.
     *
     * <p>Everything tall on this island stands on one ring at one spacing, and the
     * floor is what makes that legible from the ground. The approaches are the only
     * stone out here; the rest is planted, so the walk to a portal is a route rather
     * than an expanse a player crosses at random.
     */
    private static void buildCourt(World world) {
        for (int x = -MOAT_INNER; x <= MOAT_INNER; x++) {
            for (int z = -MOAT_INNER; z <= MOAT_INNER; z++) {
                double radius = Math.hypot(x, z);
                if (radius <= PLAZA + 0.5d || radius > MOAT_INNER - 0.5d) continue;
                double arc = Math.toRadians(spokeGap(bearing(x, z))) * radius;
                Material ground;
                if (radius >= PROMENADE - 0.6d) {
                    ground = within(radius, MOAT_INNER - 1d) ? Material.SMOOTH_BASALT
                            : ((x + z) & 1) == 0 ? Material.CALCITE
                            : Material.POLISHED_DIORITE;
                } else if (arc < 1.9d) {
                    ground = Material.CALCITE;
                } else if (arc < 2.7d) {
                    ground = Material.SMOOTH_BASALT;
                } else {
                    int pattern = (Math.abs(x) * 7 + Math.abs(z) * 11
                            + x * x + z * z) % 31;
                    ground = pattern == 0 ? Material.COARSE_DIRT
                            : pattern < 6 ? Material.MOSS_BLOCK : Material.GRASS_BLOCK;
                }
                world.getBlockAt(x, FLOOR_Y, z).setType(ground, false);
                boolean planted = ground == Material.GRASS_BLOCK
                        || ground == Material.MOSS_BLOCK || ground == Material.COARSE_DIRT;
                world.getBlockAt(x, FLOOR_Y - 1, z).setType(
                        planted ? Material.DIRT : Material.DEEPSLATE, false);
                for (int depth = 2; depth <= 4; depth++) {
                    world.getBlockAt(x, FLOOR_Y - depth, z)
                            .setType(Material.COBBLED_DEEPSLATE, false);
                }
                for (int y = 1; y <= 12; y++) {
                    world.getBlockAt(x, FLOOR_Y + y, z).setType(Material.AIR, false);
                }
            }
        }
    }

    /**
     * One planted bed in each of the ten gaps between structures, and a lamp post on
     * the promenade between every pair.
     *
     * <p>Mirrored across the north-south axis like everything else here, and kept
     * clear of both the approaches and the promenade so no planting is ever something
     * a player has to walk around.
     */
    private static void plantGarden(World world) {
        for (int index = 0; index < STRUCTURE_BEARINGS.length; index++) {
            double between = STRUCTURE_BEARINGS[index] + RING_STEP / 2d;
            if (consoleBearing(between)) continue;
            int cx = ringX(between, 12.6d, 0d);
            int cz = ringZ(between, 12.6d, 0d);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > 1) continue;
                    world.getBlockAt(cx + dx, FLOOR_Y, cz + dz).setType(
                            dx == 0 && dz == 0 ? Material.CALCITE : Material.MOSS_BLOCK,
                            false);
                }
            }
            world.getBlockAt(cx, FLOOR_Y + 1, cz).setType(Material.BUDDING_AMETHYST, false);
            world.getBlockAt(cx, FLOOR_Y + 2, cz).setType(Material.AMETHYST_CLUSTER, false);
            world.getBlockAt(cx - 1, FLOOR_Y + 1, cz)
                    .setType(Material.FLOWERING_AZALEA, false);
            world.getBlockAt(cx + 1, FLOOR_Y + 1, cz)
                    .setType(Material.FLOWERING_AZALEA, false);

            // A lamp post out on the promenade, on the same bearing as the bed.
            int lx = ringX(between, PROMENADE + 0.8d, 0d);
            int lz = ringZ(between, PROMENADE + 0.8d, 0d);
            for (int y = 1; y <= 3; y++) {
                world.getBlockAt(lx, FLOOR_Y + y, lz).setType(
                        y == 2 ? Material.AMETHYST_BLOCK : Material.POLISHED_DEEPSLATE,
                        false);
            }
            world.getBlockAt(lx, FLOOR_Y + 4, lz).setType(Material.SEA_LANTERN, false);
            world.getBlockAt(lx, FLOOR_Y + 5, lz)
                    .setType(Material.DEEPSLATE_BRICK_SLAB, false);
        }
    }

    /** Whether one of the four consoles already stands in this gap. */
    private static boolean consoleBearing(double degrees) {
        for (double pavilion : PAVILION_BEARINGS) {
            if (angularGap(degrees, pavilion) < 1d) return true;
        }
        return false;
    }

    /** The lit water ring, and the bed it is read against from the rim. */
    private static void buildMoat(World world) {
        for (int x = -MOAT_OUTER - 1; x <= MOAT_OUTER + 1; x++) {
            for (int z = -MOAT_OUTER - 1; z <= MOAT_OUTER + 1; z++) {
                double radius = Math.hypot(x, z);
                if (radius <= MOAT_INNER - 0.5d || radius > MOAT_OUTER + 0.5d) continue;
                world.getBlockAt(x, FLOOR_Y - 3, z).setType(
                        ((x + z) & 1) == 0 ? Material.PRISMARINE
                                : Material.DARK_PRISMARINE, false);
                source(world, x, FLOOR_Y - 2, z);
                source(world, x, FLOOR_Y - 1, z);
                source(world, x, FLOOR_Y, z);
                for (int y = 1; y <= 10; y++) {
                    world.getBlockAt(x, FLOOR_Y + y, z).setType(Material.AIR, false);
                }
            }
        }
        for (int lamp = 0; lamp < 20; lamp++) {
            double angle = lamp * 18d;
            world.getBlockAt(ringX(angle, MOAT_INNER + 1.4d, 0d), FLOOR_Y - 3,
                            ringZ(angle, MOAT_INNER + 1.4d, 0d))
                    .setType(Material.SEA_LANTERN, false);
        }
        // The stone lip on each side, so the water is held rather than just stopping.
        ring(world, FLOOR_Y, MOAT_INNER - 1, Material.POLISHED_DEEPSLATE);
        ring(world, FLOOR_Y, MOAT_OUTER + 1, Material.POLISHED_DEEPSLATE);
        for (double angle : CROSSINGS) bridge(world, angle);
    }

    private static void source(World world, int x, int y, int z) {
        world.getBlockAt(x, y, z).setType(Material.WATER, false);
    }

    /** One crossing over the moat, railed on both sides. */
    private static void bridge(World world, double angleDegrees) {
        for (double step = MOAT_INNER - 2; step <= MOAT_OUTER + 2; step += 0.5d) {
            for (double lateral = -2.5d; lateral <= 2.5d; lateral += 0.5d) {
                int x = ringX(angleDegrees, step, lateral);
                int z = ringZ(angleDegrees, step, lateral);
                if (Math.abs(lateral) >= 2.25d) {
                    world.getBlockAt(x, FLOOR_Y, z).setType(Material.SMOOTH_BASALT, false);
                    world.getBlockAt(x, FLOOR_Y + 1, z)
                            .setType(Material.POLISHED_DEEPSLATE_WALL, false);
                } else {
                    world.getBlockAt(x, FLOOR_Y, z).setType(
                            Math.abs(lateral) < 1.25d ? Material.CALCITE
                                    : Material.SMOOTH_BASALT, false);
                    world.getBlockAt(x, FLOOR_Y + 1, z).setType(Material.AIR, false);
                }
            }
        }
    }

    /** The outer promenade and the crenellated wall that finishes the island. */
    private static void buildRim(World world) {
        for (int x = -RIM - 1; x <= RIM + 1; x++) {
            for (int z = -RIM - 1; z <= RIM + 1; z++) {
                double radius = Math.hypot(x, z);
                if (radius <= MOAT_OUTER + 0.5d || radius > RIM + 0.5d) continue;
                world.getBlockAt(x, FLOOR_Y, z).setType(
                        within(radius, MOAT_OUTER + 1.4d) ? Material.POLISHED_DEEPSLATE
                                : ((x + z) & 3) == 0 ? Material.CHISELED_DEEPSLATE
                                : Material.DEEPSLATE_TILES, false);
                for (int depth = 1; depth <= 3; depth++) {
                    world.getBlockAt(x, FLOOR_Y - depth, z).setType(
                            depth == 1 ? Material.DEEPSLATE
                                    : Material.COBBLED_DEEPSLATE, false);
                }
                for (int y = 1; y <= 8; y++) {
                    world.getBlockAt(x, FLOOR_Y + y, z).setType(Material.AIR, false);
                }
            }
        }
        // The parapet: a solid course with a merlon every fourth block.
        for (int degrees = 0; degrees < 1440; degrees++) {
            double angle = degrees / 4d;
            int x = ringX(angle, RIM, 0d);
            int z = ringZ(angle, RIM, 0d);
            world.getBlockAt(x, FLOOR_Y + 1, z).setType(Material.DEEPSLATE_BRICKS, false);
            world.getBlockAt(x, FLOOR_Y + 2, z).setType(
                    (degrees & 15) == 0 ? Material.CHISELED_DEEPSLATE
                            : Material.DEEPSLATE_BRICK_WALL, false);
        }
        // A lantern pylon on each of the four diagonals, which is the only height the
        // rim is given: it frames the island without competing with the structures.
        for (double angle : new double[]{45d, 135d, 225d, 315d}) {
            int x = ringX(angle, RIM - 1.2d, 0d);
            int z = ringZ(angle, RIM - 1.2d, 0d);
            for (int y = 1; y <= 5; y++) {
                world.getBlockAt(x, FLOOR_Y + y, z).setType(
                        y == 5 ? Material.AMETHYST_BLOCK
                                : (y & 1) == 0 ? Material.POLISHED_DEEPSLATE
                                : Material.DEEPSLATE_BRICKS, false);
            }
            world.getBlockAt(x, FLOOR_Y + 6, z).setType(Material.SEA_LANTERN, false);
            world.getBlockAt(x, FLOOR_Y + 7, z).setType(Material.AMETHYST_CLUSTER, false);
        }
    }

    /**
     * One leaderboard: a framed wall standing on the structure ring, facing the plaza.
     *
     * <p>Same ring, same width and same crown as the portal arches, because a records
     * wall that is built to its own dimensions is exactly what made the old lobby look
     * like two unrelated places bolted together.
     */
    private static void buildLeaderboardFrame(World world, LeaderboardBoard board) {
        double angle = board.angle;
        structurePlinth(world, angle);
        for (int lateral = -3; lateral <= 3; lateral++) {
            int x = ringX(angle, STRUCTURE_RING, lateral);
            int z = ringZ(angle, STRUCTURE_RING, lateral);
            for (int y = 1; y <= STRUCTURE_TOP; y++) {
                boolean edge = Math.abs(lateral) == 3;
                boolean cap = y == STRUCTURE_TOP;
                Material material;
                if (cap && Math.abs(lateral) <= 2) material = Material.CHISELED_DEEPSLATE;
                else if (edge && (y == 3 || y == 6)) material = Material.AMETHYST_BLOCK;
                else if (edge || cap) material = Material.POLISHED_DEEPSLATE;
                else material = Material.DEEPSLATE_TILES;
                world.getBlockAt(x, FLOOR_Y + y, z).setType(material, false);
            }
            world.getBlockAt(x, FLOOR_Y, z).setType(
                    Math.abs(lateral) == 3 ? Material.POLISHED_DEEPSLATE
                            : Material.SMOOTH_BASALT, false);
        }
        int crownX = ringX(angle, STRUCTURE_RING, 0d);
        int crownZ = ringZ(angle, STRUCTURE_RING, 0d);
        world.getBlockAt(crownX, FLOOR_Y + STRUCTURE_TOP + 1, crownZ)
                .setType(Material.SEA_LANTERN, false);
        // Two lamps on the approach, level with a reader's eye.
        for (int lateral : new int[]{-4, 4}) {
            int x = ringX(angle, STRUCTURE_RING - 0.6d, lateral);
            int z = ringZ(angle, STRUCTURE_RING - 0.6d, lateral);
            world.getBlockAt(x, FLOOR_Y + 1, z).setType(Material.POLISHED_DEEPSLATE, false);
            world.getBlockAt(x, FLOOR_Y + 2, z).setType(Material.SEA_LANTERN, false);
        }
    }

    /**
     * The stepped base every ring structure stands on.
     *
     * <p>Ten structures sharing one base course is what stops them reading as ten
     * objects placed on a floor. The step also gives the approach somewhere to arrive.
     */
    private static void structurePlinth(World world, double angle) {
        for (int lateral = -4; lateral <= 4; lateral++) {
            for (double step = -1.4d; step <= 1.4d; step += 0.7d) {
                int x = ringX(angle, STRUCTURE_RING + step, lateral);
                int z = ringZ(angle, STRUCTURE_RING + step, lateral);
                world.getBlockAt(x, FLOOR_Y, z).setType(
                        Math.abs(lateral) == 4 ? Material.CHISELED_DEEPSLATE
                                : Material.POLISHED_DEEPSLATE, false);
            }
        }
        // A half step out front, so the base is read as a tread rather than a kerb.
        for (int lateral = -3; lateral <= 3; lateral++) {
            int x = ringX(angle, STRUCTURE_RING - 2.1d, lateral);
            int z = ringZ(angle, STRUCTURE_RING - 2.1d, lateral);
            world.getBlockAt(x, FLOOR_Y, z).setType(Material.DEEPSLATE_BRICK_SLAB, false);
        }
    }

    /** Structure-ring x, measured clockwise from north with an optional sideways step. */
    private static int ringX(double angleDegrees, double radius, double lateral) {
        double angle = Math.toRadians(angleDegrees);
        return symmetric(quantised(Math.sin(angle)) * radius
                + quantised(Math.cos(angle)) * lateral);
    }

    /** Structure-ring z, measured clockwise from north with an optional sideways step. */
    private static int ringZ(double angleDegrees, double radius, double lateral) {
        double angle = Math.toRadians(angleDegrees);
        return symmetric(-quantised(Math.cos(angle)) * radius
                + quantised(Math.sin(angle)) * lateral);
    }

    // ---------------------------------------------------------- the gateways

    /**
     * One queue arch: a real portal, a mode emblem, and an illuminated approach.
     *
     * <p>Seven wide and capped at {@link #STRUCTURE_TOP}, which is exactly a records
     * wall. Ten structures sharing one width, one ring and one cornice is what makes
     * the island read as built rather than as accumulated.
     */
    private static void buildGate(World world, Gate gate) {
        int gx = gate.x();
        int gz = gate.z();
        // Faces the middle, so every arch is read from the plaza.
        boolean wideX = Math.abs(gx) <= Math.abs(gz);
        Orientable portal = (Orientable) Material.NETHER_PORTAL.createBlockData();
        portal.setAxis(wideX ? Axis.X : Axis.Z);
        structurePlinth(world, gate.angle());
        pad(world, gx, gz, gate.emblem());
        for (int lateral = -3; lateral <= 3; lateral++) {
            set(world, gx, gz, wideX, lateral, FLOOR_Y, Material.OBSIDIAN);
            for (int y = 1; y <= STRUCTURE_TOP; y++) {
                boolean post = Math.abs(lateral) == 3;
                boolean lintel = y >= STRUCTURE_TOP - 1;
                if (post || lintel) {
                    Material frame = lintel && Math.abs(lateral) <= 2
                            ? Material.CHISELED_DEEPSLATE
                            : post && (y == 3 || y == 6) ? gate.glass()
                            : Material.OBSIDIAN;
                    set(world, gx, gz, wideX, lateral, FLOOR_Y + y, frame);
                } else {
                    setData(world, gx, gz, wideX, lateral, FLOOR_Y + y, portal);
                }
            }
        }
        // Copper pylons on the approach rather than alongside the posts: at this ring
        // spacing a ninth block of width would touch the structure next door.
        for (int lateral : new int[]{-2, 2}) {
            int px = ringX(gate.angle(), STRUCTURE_RING - 2d, lateral);
            int pz = ringZ(gate.angle(), STRUCTURE_RING - 2d, lateral);
            world.getBlockAt(px, FLOOR_Y, pz).setType(Material.POLISHED_DEEPSLATE, false);
            world.getBlockAt(px, FLOOR_Y + 1, pz).setType(Material.CUT_COPPER, false);
            world.getBlockAt(px, FLOOR_Y + 2, pz).setType(gate.glass(), false);
            world.getBlockAt(px, FLOOR_Y + 3, pz).setType(Material.SOUL_LANTERN, false);
        }
        set(world, gx, gz, wideX, 0, FLOOR_Y + STRUCTURE_TOP + 1, gate.emblem());
    }

    private static void buildReturnGate(World world) {
        int gx = RETURN_GATE[0];
        int gz = RETURN_GATE[1];
        Orientable portal = (Orientable) Material.NETHER_PORTAL.createBlockData();
        portal.setAxis(Axis.X);
        structurePlinth(world, 180d);
        pad(world, gx, gz, Material.LODESTONE);
        for (int lateral = -3; lateral <= 3; lateral++) {
            set(world, gx, gz, true, lateral, FLOOR_Y, Material.OBSIDIAN);
            for (int y = 1; y <= STRUCTURE_TOP; y++) {
                boolean post = Math.abs(lateral) == 3;
                boolean lintel = y >= STRUCTURE_TOP - 1;
                if (post || lintel) {
                    set(world, gx, gz, true, lateral, FLOOR_Y + y,
                            lintel && Math.abs(lateral) <= 2 ? Material.CHISELED_DEEPSLATE
                                    : Material.OBSIDIAN);
                } else {
                    setData(world, gx, gz, true, lateral, FLOOR_Y + y, portal);
                }
            }
        }
        set(world, gx, gz, true, 0, FLOOR_Y + STRUCTURE_TOP + 1, Material.LODESTONE);
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

    /**
     * Where players land: an inlaid pad on the southern approach.
     *
     * <p>Deliberately flat and deliberately small. It sits on the walk between the
     * plaza and the way home, so an arriving player is already standing in the
     * circulation rather than on a terrace they have to leave first.
     */
    private static void buildArrival(World world) {
        for (int x = -3; x <= 3; x++) {
            for (int z = 9; z <= 13; z++) {
                boolean edge = Math.abs(x) == 3 || z == 9 || z == 13;
                world.getBlockAt(x, FLOOR_Y, z).setType(
                        edge ? Material.POLISHED_DEEPSLATE
                                : ((x + z) & 1) == 0 ? Material.CALCITE
                                : Material.AMETHYST_BLOCK, false);
                for (int y = 1; y <= 6; y++) {
                    world.getBlockAt(x, FLOOR_Y + y, z).setType(Material.AIR, false);
                }
            }
        }
        world.getBlockAt(0, FLOOR_Y, 11).setType(Material.SEA_LANTERN, false);
        for (int[] post : List.of(new int[]{-3, 9}, new int[]{3, 9},
                new int[]{-3, 13}, new int[]{3, 13})) {
            world.getBlockAt(post[0], FLOOR_Y + 1, post[1])
                    .setType(Material.CHISELED_DEEPSLATE, false);
            world.getBlockAt(post[0], FLOOR_Y + 2, post[1])
                    .setType(Material.AMETHYST_BLOCK, false);
            world.getBlockAt(post[0], FLOOR_Y + 3, post[1])
                    .setType(Material.SOUL_LANTERN, false);
        }
    }

    // --------------------------------------------------------- the pavilions

    /** The UI opened by a physical lobby console. */
    enum PavilionAction { LADDER, RULES, PLAY, LIVE }

    /** Whether a pavilion's board is written once or rewritten while people play. */
    private enum Board { STATIC, LIVE }

    private record Pavilion(
            double angle,
            String title,
            Board board,
            List<String> body,
            String prompt,
            PavilionAction action
    ) { }

    /**
     * Four open shelters on the rim, each carrying one board.
     *
     * <p>These exist because the questions a player has before queueing — how the ladder
     * works, where the records live, what the rules are, and what is live — were all
     * buried inside menus. On the rim they are things you walk past.
     */
    private static void buildPavilions(World world) {
        for (Pavilion pavilion : pavilions()) {
            int cx = ringX(pavilion.angle(), PAVILION_RING, 0d);
            int cz = ringZ(pavilion.angle(), PAVILION_RING, 0d);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    world.getBlockAt(cx + dx, FLOOR_Y, cz + dz).setType(
                            Material.POLISHED_DEEPSLATE, false);
                    boolean corner = dx != 0 && dz != 0;
                    world.getBlockAt(cx + dx, FLOOR_Y + 1, cz + dz).setType(
                            corner ? Material.DEEPSLATE_BRICK_WALL
                                    : Material.DEEPSLATE_BRICK_SLAB, false);
                }
            }
            // A short lit stem rather than a shelter: these stand inside the court,
            // where a roof would hide the arch behind them from the plaza.
            world.getBlockAt(cx, FLOOR_Y + 1, cz).setType(Material.CHISELED_DEEPSLATE, false);
            world.getBlockAt(cx, FLOOR_Y + 2, cz).setType(Material.AMETHYST_BLOCK, false);
            world.getBlockAt(cx, FLOOR_Y + 3, cz).setType(Material.SEA_LANTERN, false);
            world.getBlockAt(cx, FLOOR_Y + 4, cz).setType(Material.AMETHYST_CLUSTER, false);
        }
    }

    private static List<Pavilion> pavilions() {
        List<Pavilion> boards = new ArrayList<>();
        boards.add(new Pavilion(PAVILION_BEARINGS[0], "RANK PROGRESSION", Board.STATIC,
                List.of("18 ranks • 8 permanent tiers"), "RIGHT-CLICK CONSOLE",
                PavilionAction.LADDER));
        boards.add(new Pavilion(PAVILION_BEARINGS[1], "FIGHT RULES", Board.STATIC,
                List.of("YOUR GEAR • REAL TERRAIN • KEEP INVENTORY"), "RIGHT-CLICK CONSOLE",
                PavilionAction.RULES));
        boards.add(new Pavilion(PAVILION_BEARINGS[2], "CREATE A FIGHT", Board.STATIC,
                List.of("TEAM SIZE • ACCESS • INVITES"),
                "RIGHT-CLICK CONSOLE", PavilionAction.PLAY));
        boards.add(new Pavilion(PAVILION_BEARINGS[3], "LIVE MATCHES", Board.LIVE, List.of(),
                "RIGHT-CLICK CONSOLE", PavilionAction.LIVE));
        return boards;
    }

    /** Which console was clicked, if the block belongs to one of the four pavilions. */
    static PavilionAction pavilionAction(PvpLobbyStore.Point lobby, Location clicked) {
        Location origin = origin(lobby, clicked);
        if (origin == null || Math.abs(clicked.getY() - (FLOOR_Y + 1d)) > 5d) return null;
        for (Pavilion pavilion : pavilions()) {
            double x = origin.getX() + ringX(pavilion.angle(), PAVILION_RING, 0d);
            double z = origin.getZ() + ringZ(pavilion.angle(), PAVILION_RING, 0d);
            if (horizontalSquared(clicked, x, z) <= 6.25d) return pavilion.action();
        }
        return null;
    }

    // ---------------------------------------------------------- the lettering

    static void refreshHolograms(PvpLobbyStore.Point lobby, GameVariableStore variables) {
        Location centre = lobby == null ? null : lobby.resolve();
        if (centre == null || centre.getWorld() == null) return;
        clearLobbyLabels(centre.getWorld());
        buildHolograms(centre.getWorld(), variables);
    }

    private static void buildHolograms(World world, GameVariableStore variables) {
        float titleScale = (float) variables.decimal("pvp-competitive.lobby-title-scale");
        float lineScale = (float) variables.decimal("pvp-competitive.lobby-line-scale");
        float gateTitleScale = (float) variables.decimal(
                "pvp-competitive.lobby-gate-title-scale");
        float leaderboardTitleScale = (float) variables.decimal(
                "pvp-competitive.lobby-leaderboard-title-scale");
        for (Map.Entry<PvpMode, Gate> row : GATES.entrySet()) {
            Gate gate = row.getValue();
            // Stacked above the lintel. Anything hung in the portal mouth reads as
            // painted onto the portal, and anything alongside the arch is what the
            // posts used to clip through when the label turned to face a player.
            double lx = gate.x() + 0.5d;
            double lz = gate.z() + 0.5d;
            hologram(world, lx, FLOOR_Y + STRUCTURE_TOP + 4.8d, lz,
                    Component.text(gate.title(), AMETHYST, TextDecoration.BOLD),
                    gateTitleScale, NEAR_GATE_TAG);
            hologram(world, lx, FLOOR_Y + STRUCTURE_TOP + 3.6d, lz,
                    Component.text(gate.subtitle(), NamedTextColor.WHITE),
                    lineScale, NEAR_GATE_TAG);
            // Retitled every second by refreshStatus.
            hologram(world, lx, GATE_STATUS_Y, lz,
                    Component.text("WALK THROUGH • CHECKING QUEUE", CRYSTAL, TextDecoration.BOLD),
                    lineScale, NEAR_GATE_TAG, STATUS_TAG);
        }
        hologram(world, RETURN_GATE[0] + 0.5d, FLOOR_Y + STRUCTURE_TOP + 3.6d,
                RETURN_GATE[1] + 0.5d,
                Component.text("RETURN TO THE SMP", NamedTextColor.WHITE, TextDecoration.BOLD),
                gateTitleScale, NEAR_GATE_TAG);
        hologram(world, RETURN_GATE[0] + 0.5d, FLOOR_Y + STRUCTURE_TOP + 2.4d,
                RETURN_GATE[1] + 0.5d,
                Component.text("WALK THROUGH • BACK TO YOUR EXACT LOCATION", CRYSTAL),
                lineScale, NEAR_GATE_TAG);

        // Clear of the shard, whose tip reaches FLOOR_Y + 14.
        hologram(world, 0.5d, FLOOR_Y + 17.6d, 0.5d,
                Component.text("THE AMETHYST TERRACE", AMETHYST, TextDecoration.BOLD),
                titleScale, NEAR_GATE_TAG);
        hologram(world, 0.5d, FLOOR_Y + 16.4d, 0.5d,
                Component.text("YOUR GEAR • REAL TERRAIN • NOTHING LOST", CRYSTAL),
                lineScale, NEAR_GATE_TAG);

        for (Pavilion pavilion : pavilions()) {
            double x = ringX(pavilion.angle(), PAVILION_RING, 0d) + 0.5d;
            double z = ringZ(pavilion.angle(), PAVILION_RING, 0d) + 0.5d;
            hologram(world, x, FLOOR_Y + 6.3d, z,
                    Component.text(pavilion.title(), AMETHYST, TextDecoration.BOLD),
                    titleScale, NEAR_BOARD_TAG);
            double line = FLOOR_Y + 5.25d;
            for (String text : pavilion.body()) {
                hologram(world, x, line, z,
                        Component.text(text, NamedTextColor.WHITE),
                        lineScale, NEAR_BOARD_TAG);
                line -= 0.72d;
            }
            if (pavilion.board() == Board.LIVE) {
                // Blank rows, claimed by refreshBoards. Their roomy spacing prevents
                // Java text and the Bedrock fallback from collapsing into one smear.
                for (int row = 0; row < LIVE_BOARD_LINES; row++) {
                    hologram(world, x, line, z,
                            Component.text(" ", NamedTextColor.DARK_GRAY), lineScale,
                            NEAR_BOARD_TAG, LIVE_TAG);
                    line -= 0.72d;
                }
            }
            hologram(world, x, Math.max(FLOOR_Y + 2.4d, line), z,
                    Component.text(pavilion.prompt(), CRYSTAL, TextDecoration.BOLD),
                    lineScale, NEAR_BOARD_TAG);
        }

        for (LeaderboardBoard board : LeaderboardBoard.values()) {
            double x = board.x();
            double z = board.z();
            hologram(world, x, FLOOR_Y + 7.4d, z,
                    Component.text(board.title, AMETHYST, TextDecoration.BOLD),
                    leaderboardTitleScale, RECORDS_LABEL_TAG);
            double line = FLOOR_Y + 5.9d;
            for (int row = 0; row < LEADERBOARD_LINES; row++) {
                hologram(world, x, line, z,
                        Component.text(" ", NamedTextColor.DARK_GRAY), lineScale,
                        RECORDS_LABEL_TAG, board.tag);
                line -= 0.78d;
            }
        }
    }

    /**
     * Every label in the lobby.
     *
     * <p>One billboard mode for all of them: upright, turning to face whoever is
     * reading. A plane fixed to the structure behind it reads as painted onto that
     * structure rather than as a label, and it was the one sign in the lobby that
     * behaved differently from its neighbours.
     */
    private static void hologram(
            World world,
            double x,
            double y,
            double z,
            Component name,
            float scale,
            String distanceTag,
            String... tags
    ) {
        spawnHologram(world, x, y, z, 0d, Display.Billboard.VERTICAL,
                name, scale, distanceTag, tags);
    }

    private static void spawnHologram(
            World world,
            double x,
            double y,
            double z,
            double yaw,
            Display.Billboard billboard,
            Component name,
            float scale,
            String distanceTag,
            String... tags
    ) {
        Location at = new Location(world, x, y, z, (float) yaw, 0f);
        String[] textTags = labelTags(distanceTag, TEXT_LABEL_TAG, tags);
        TextDisplay display = world.spawn(at, TextDisplay.class, text -> {
            text.text(name);
            text.setBillboard(billboard);
            text.setRotation((float) yaw, 0f);
            text.setAlignment(TextDisplay.TextAlignment.CENTER);
            text.setShadowed(true);
            text.setSeeThrough(false);
            text.setLineWidth(340);
            text.setBackgroundColor(Color.fromARGB(225, 5, 3, 10));
            text.setTextOpacity((byte) -1);
            text.setBrightness(new Display.Brightness(15, 15));
            text.setViewRange(4f);
            text.setPersistent(true);
            text.setVisibleByDefault(false);
            text.setTransformation(new Transformation(
                    new org.joml.Vector3f(), new org.joml.AxisAngle4f(),
                    new org.joml.Vector3f(scale), new org.joml.AxisAngle4f()));
            for (String tag : textTags) text.addScoreboardTag(tag);
        });
        display.setInterpolationDuration(3);

        String[] fallbackTags = labelTags(distanceTag, FALLBACK_LABEL_TAG, tags);
        ArmorStand fallback = CrateDisplayService.spawnStyledLabel(
                at, name, fallbackTags);
        fallback.setVisibleByDefault(false);
    }

    private static String[] labelTags(String distanceTag, String implementationTag, String[] extra) {
        String[] all = new String[extra.length + 3];
        all[0] = HOLOGRAM_TAG;
        all[1] = implementationTag;
        all[2] = distanceTag;
        System.arraycopy(extra, 0, all, 3, extra.length);
        return all;
    }

    private static void setLabel(Entity entity, Component value) {
        if (entity instanceof TextDisplay display) display.text(value);
        else if (entity instanceof ArmorStand stand) stand.customName(value);
    }

    private static void clearLobbyLabels(World world) {
        for (Entity entity : world.getEntities()) {
            if (entity.getScoreboardTags().contains(HOLOGRAM_TAG)) entity.remove();
        }
    }

    // --------------------------------------------------------------- helpers

    /** A path from the plaza kerb outward along one bearing. */
    private static void path(World world, double angleDegrees, int toRadius) {
        double angle = Math.toRadians(angleDegrees);
        double dirX = Math.sin(angle);
        double dirZ = -Math.cos(angle);
        for (double step = PLAZA; step <= toRadius; step += 0.5d) {
            for (double lateral = -2; lateral <= 2; lateral += 0.5d) {
                int x = symmetric(quantised(dirX) * step - quantised(dirZ) * lateral);
                int z = symmetric(quantised(dirZ) * step + quantised(dirX) * lateral);
                world.getBlockAt(x, FLOOR_Y, z).setType(Math.abs(lateral) < 1.25d
                        ? Material.CALCITE : Material.SMOOTH_BASALT, false);
                world.getBlockAt(x, FLOOR_Y + 1, z).setType(Material.AIR, false);
            }
        }
    }

    private static void ring(World world, int y, int radius, Material material) {
        for (int degrees = 0; degrees < 720; degrees++) {
            double angle = Math.toRadians(degrees / 2d);
            int x = symmetric(quantised(Math.cos(angle)) * radius);
            int z = symmetric(quantised(Math.sin(angle)) * radius);
            world.getBlockAt(x, y, z).setType(material, false);
        }
    }

    private static void set(
            World world, int x, int z, boolean wideX, int lateral, int y, Material material
    ) {
        world.getBlockAt(x + (wideX ? lateral : 0), y, z + (wideX ? 0 : lateral))
                .setType(material, false);
    }

    private static void setData(
            World world, int x, int z, boolean wideX, int lateral, int y, BlockData data
    ) {
        world.getBlockAt(x + (wideX ? lateral : 0), y, z + (wideX ? 0 : lateral))
                .setBlockData(data.clone(), false);
    }

    private static boolean touchesPortal(Location location) {
        return location != null && (location.getBlock().getType() == Material.NETHER_PORTAL
                || location.clone().add(0, 1, 0).getBlock().getType() == Material.NETHER_PORTAL);
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
