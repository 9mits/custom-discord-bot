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
    private static final String GALLERY_LABEL_TAG = "mgx_pvp_gallery_label";
    /** Rows reserved on each live board, blank until there is something to say. */
    private static final int LIVE_BOARD_LINES = 3;
    private static final int LEADERBOARD_LINES = 5;
    private static final int FLOOR_Y = 80;

    /** Outer edge of the rim walkway. Everything is inside this. */
    private static final int RIM = 27;
    private static final int MOAT_OUTER = 23;
    private static final int MOAT_INNER = 20;
    private static final int PLAZA = 12;
    /** Where the six gateways stand, in the planted ring between plaza and moat. */
    private static final int GATE_RING = 17;
    private static final int PAVILION_RING = 25;
    /** Overlaps the main rim by three blocks, making the two circles one connected lobby. */
    private static final int GALLERY_CENTRE_Z = -40;
    private static final int GALLERY_RADIUS = 16;
    /** Fixed labels sit safely in front of their backing at every viewing angle. */
    private static final double GALLERY_BOARD_RING = 13d;
    private static final double GALLERY_FRAME_RING = 14.8d;
    private static final double SPAWN_Z = 12.5d;
    private static final int[] RETURN_GATE = {0, 25};
    /** Where the moat is crossed: the four pavilions and the walk home. */
    private static final double[] CROSSINGS = {45d, 135d, 180d, 225d, 315d};
    /** Starter arenas generated in this void world by the retired 8.0 PvP system. */
    private static final int[][] LEGACY_FIGHTING_PLATFORMS = {
            {0, -180, 27}, {80, -180, 27},
            {180, -100, 32}, {180, 80, 36},
            {-180, 80, 36}, {-180, -100, 46}
    };

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

    /** One physical board in the north leaderboard gallery. */
    enum LeaderboardBoard {
        RATING("HIGHEST RATING", 250d, "mgx_pvp_board_rating"),
        WINS("MOST WINS", 290d, "mgx_pvp_board_wins"),
        KILLS("MOST KILLS", 330d, "mgx_pvp_board_kills"),
        STREAK("BEST WIN STREAK", 30d, "mgx_pvp_board_streak"),
        CLAN_WINS("CLAN WINS", 70d, "mgx_pvp_board_clan_wins"),
        CLAN_KILLS("CLAN KILLS", 110d, "mgx_pvp_board_clan_kills");

        private final String title;
        private final double angle;
        private final String tag;

        LeaderboardBoard(String title, double angle, String tag) {
            this.title = title;
            this.angle = angle;
            this.tag = tag;
        }

        double x() {
            return Math.sin(Math.toRadians(angle)) * GALLERY_BOARD_RING + 0.5d;
        }

        double z() {
            return GALLERY_CENTRE_Z
                    - Math.cos(Math.toRadians(angle)) * GALLERY_BOARD_RING + 0.5d;
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
        buildGardenRing(world);
        buildMoat(world);
        buildRim(world);
        buildLeaderboardGallery(world);
        for (Map.Entry<PvpMode, Gate> row : GATES.entrySet()) {
            buildGate(world, row.getValue());
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
        double originX = centre.getX() - 0.5d;
        double originZ = centre.getZ() - SPAWN_Z;
        List<Entity> labels = world.getEntities().stream()
                .filter(entity -> entity.getScoreboardTags().contains(HOLOGRAM_TAG)).toList();
        for (Player player : world.getPlayers()) {
            boolean useText = clients.supportsTextDisplays(player);
            for (Entity label : labels) {
                boolean gallery = label.getScoreboardTags().contains(GALLERY_LABEL_TAG);
                double range = gallery ? leaderboardDistance
                        : label.getScoreboardTags().contains(NEAR_BOARD_TAG)
                        ? boardDistance : gateDistance;
                boolean nearby = gallery
                        ? horizontalSquared(player.getLocation(), originX + 0.5d,
                                originZ + GALLERY_CENTRE_Z + 0.5d)
                                <= Math.max(1d, range) * Math.max(1d, range)
                        : label.getLocation().distanceSquared(player.getLocation())
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
            Location at = gateLabelLocation(world, originX, originZ, row.getValue(),
                    FLOOR_Y + 3.8d);
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

    static int gateRing() {
        return GATE_RING;
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
        return new int[]{galleryX(board.angle, GALLERY_FRAME_RING, 0d),
                galleryZ(board.angle, GALLERY_FRAME_RING, 0d)};
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
            int x = symmetric(quantised(Math.sin(angle)) * (PLAZA + 1));
            int z = symmetric(-quantised(Math.cos(angle)) * (PLAZA + 1));
            // Flush lights preserve the twelve-fold rhythm without placing a pillar
            // across the circulation ring.
            world.getBlockAt(x, FLOOR_Y, z).setType(Material.SEA_LANTERN, false);
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
     * The planted ring: mirrored low geode beds and the six gateways standing in it.
     *
     * <p>Green between the stone and the water is what stops a lobby this compact from
     * feeling like a car park. Its material variation is mirrored across both axes so
     * the natural section still reads as one deliberately composed lobby.
     */
    private static void buildGardenRing(World world) {
        for (int x = -MOAT_INNER; x <= MOAT_INNER; x++) {
            for (int z = -MOAT_INNER; z <= MOAT_INNER; z++) {
                double radius = Math.hypot(x, z);
                if (radius <= PLAZA + 1.5d || radius > MOAT_INNER - 0.5d) continue;
                int pattern = (Math.abs(x) * 7 + Math.abs(z) * 11 + x * x + z * z) % 31;
                Material ground = pattern == 0 ? Material.COARSE_DIRT
                        : pattern < 5 ? Material.MOSS_BLOCK : Material.GRASS_BLOCK;
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

    private static void plantGarden(World world) {
        // Four mirrored, low geode beds sit between the six queue paths. Nothing grows
        // into a path and there are no random trees or lamp posts to obstruct sightlines.
        for (double angleDegrees : new double[]{60d, 120d, 240d, 300d}) {
            double angle = Math.toRadians(angleDegrees);
            int centreX = symmetric(quantised(Math.sin(angle)) * 16d);
            int centreZ = symmetric(-quantised(Math.cos(angle)) * 16d);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > 1) continue;
                    world.getBlockAt(centreX + dx, FLOOR_Y, centreZ + dz).setType(
                            dx == 0 && dz == 0 ? Material.CALCITE : Material.MOSS_BLOCK, false);
                }
            }
            world.getBlockAt(centreX, FLOOR_Y + 1, centreZ)
                    .setType(Material.BUDDING_AMETHYST, false);
            world.getBlockAt(centreX, FLOOR_Y + 2, centreZ)
                    .setType(Material.AMETHYST_CLUSTER, false);
            world.getBlockAt(centreX - 1, FLOOR_Y + 1, centreZ)
                    .setType(Material.FLOWERING_AZALEA, false);
            world.getBlockAt(centreX + 1, FLOOR_Y + 1, centreZ)
                    .setType(Material.FLOWERING_AZALEA, false);
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
            int x = symmetric(quantised(Math.cos(angle)) * (MOAT_INNER + 2));
            int z = symmetric(quantised(Math.sin(angle)) * (MOAT_INNER + 2));
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
            int x = symmetric(quantised(Math.cos(angle)) * RIM);
            int z = symmetric(quantised(Math.sin(angle)) * RIM);
            world.getBlockAt(x, FLOOR_Y + 1, z).setType(Material.DEEPSLATE_BRICKS, false);
            world.getBlockAt(x, FLOOR_Y + 2, z).setType(
                    (degrees & 3) == 0 ? Material.DEEPSLATE_BRICK_WALL
                            : Material.COBBLED_DEEPSLATE_WALL, false);
        }
        for (double angle : CROSSINGS) {
            bridge(world, angle);
        }
    }

    /** A round sister terrace that continues the main lobby's rings and palette. */
    private static void buildLeaderboardGallery(World world) {
        for (int x = -GALLERY_RADIUS; x <= GALLERY_RADIUS; x++) {
            for (int localZ = -GALLERY_RADIUS; localZ <= GALLERY_RADIUS; localZ++) {
                double radius = Math.hypot(x, localZ);
                if (radius > GALLERY_RADIUS + 0.5d) continue;
                int z = GALLERY_CENTRE_Z + localZ;
                world.getBlockAt(x, FLOOR_Y, z).setType(
                        galleryFloor(x, localZ, radius), false);
                int thickness = 2 + (int) Math.round(
                        (1d - radius / GALLERY_RADIUS) * 5d);
                for (int depth = 1; depth <= Math.max(1, thickness); depth++) {
                    Material rock = depth == 1 ? Material.DEEPSLATE
                            : ((x + localZ + depth) & 3) == 0 ? Material.SMOOTH_BASALT
                            : Material.COBBLED_DEEPSLATE;
                    world.getBlockAt(x, FLOOR_Y - depth, z).setType(rock, false);
                }
            }
        }

        // A two-deep illuminated moat and its crossings deliberately repeat the main
        // island. This is a smaller sister terrace, not a separate flat platform.
        buildGalleryMoat(world);

        // A crenellated circular rim, with one deliberate opening back to the lobby.
        for (int degrees = 0; degrees < 720; degrees++) {
            double angle = Math.toRadians(degrees / 2d);
            int x = symmetric(quantised(Math.cos(angle)) * GALLERY_RADIUS);
            int z = GALLERY_CENTRE_Z
                    + symmetric(quantised(Math.sin(angle)) * GALLERY_RADIUS);
            boolean entrance = z >= GALLERY_CENTRE_Z + GALLERY_RADIUS - 3
                    && Math.abs(x) <= 10;
            if (entrance) continue;
            world.getBlockAt(x, FLOOR_Y + 1, z).setType(Material.DEEPSLATE_BRICKS, false);
            if ((degrees & 7) == 0) {
                world.getBlockAt(x, FLOOR_Y + 2, z)
                        .setType(Material.DEEPSLATE_BRICK_WALL, false);
            }
        }

        for (LeaderboardBoard board : LeaderboardBoard.values()) {
            buildLeaderboardFrame(world, board);
        }

        // A low central geode repeats the main monument without blocking the boards.
        for (int x = -3; x <= 3; x++) {
            for (int localZ = -3; localZ <= 3; localZ++) {
                double radius = Math.hypot(x, localZ);
                if (radius > 3.4d) continue;
                int z = GALLERY_CENTRE_Z + localZ;
                world.getBlockAt(x, FLOOR_Y + 1, z).setType(
                        radius > 2.2d ? Material.CALCITE : Material.AMETHYST_BLOCK, false);
                if (radius <= 1.4d) {
                    world.getBlockAt(x, FLOOR_Y + 2, z).setType(
                            x == 0 && localZ == 0 ? Material.SEA_LANTERN
                                    : Material.AMETHYST_BLOCK, false);
                }
            }
        }
        world.getBlockAt(0, FLOOR_Y + 3, GALLERY_CENTRE_Z)
                .setType(Material.AMETHYST_CLUSTER, false);

        // Low marker pylons frame the shared concourse without turning it into another
        // gate or putting columns in the route between the two halves of the lobby.
        int entranceZ = GALLERY_CENTRE_Z + GALLERY_RADIUS - 1;
        for (int x : new int[]{-11, 11}) {
            for (int y = 1; y <= 4; y++) {
                world.getBlockAt(x, FLOOR_Y + y, entranceZ).setType(
                        y == 3 ? Material.AMETHYST_BLOCK
                                : y % 2 == 0 ? Material.POLISHED_DEEPSLATE
                                : Material.DEEPSLATE_BRICKS, false);
            }
            world.getBlockAt(x, FLOOR_Y + 5, entranceZ)
                    .setType(Material.AMETHYST_CLUSTER, false);
        }

        // Uneven crystal roots keep the underside in the same floating-geode language
        // as the main terrace when this smaller circle is seen from below.
        for (int spoke = 0; spoke < 8; spoke++) {
            double angle = Math.toRadians(spoke * 45d + 22.5d);
            int x = symmetric(quantised(Math.cos(angle)) * 11d);
            int z = GALLERY_CENTRE_Z + symmetric(quantised(Math.sin(angle)) * 11d);
            int length = 3 + (spoke % 3);
            for (int depth = 2; depth <= length + 2; depth++) {
                world.getBlockAt(x, FLOOR_Y - depth, z).setType(
                        depth == length + 2 ? Material.AMETHYST_BLOCK
                                : Material.COBBLED_DEEPSLATE, false);
            }
        }
        buildSharedConcourse(world);
    }

    /** A broad solid neck that turns the two overlapping circles into one lobby. */
    private static void buildSharedConcourse(World world) {
        for (int x = -10; x <= 10; x++) {
            for (int z = -32; z <= -21; z++) {
                Material floor = Math.abs(x) <= 2 ? Material.CALCITE
                        : Math.abs(x) >= 9 ? Material.SMOOTH_BASALT
                        : ((Math.abs(x) + Math.abs(z)) & 3) == 0
                        ? Material.CHISELED_DEEPSLATE : Material.DEEPSLATE_TILES;
                world.getBlockAt(x, FLOOR_Y, z).setType(floor, false);
                for (int depth = 1; depth <= 4; depth++) {
                    world.getBlockAt(x, FLOOR_Y - depth, z).setType(
                            depth == 1 ? Material.DEEPSLATE
                                    : Material.COBBLED_DEEPSLATE, false);
                }
                for (int y = 1; y <= 8; y++) {
                    world.getBlockAt(x, FLOOR_Y + y, z).setType(Material.AIR, false);
                }
            }
        }
        // Flush edge lights define the route without railings or pillars.
        for (int z : new int[]{-31, -27, -23}) {
            world.getBlockAt(-8, FLOOR_Y, z).setType(Material.SEA_LANTERN, false);
            world.getBlockAt(8, FLOOR_Y, z).setType(Material.SEA_LANTERN, false);
        }
    }

    private static void buildGalleryMoat(World world) {
        for (int x = -10; x <= 10; x++) {
            for (int localZ = -10; localZ <= 10; localZ++) {
                double radius = Math.hypot(x, localZ);
                if (radius <= 6.2d || radius > 9.2d) continue;
                int z = GALLERY_CENTRE_Z + localZ;
                world.getBlockAt(x, FLOOR_Y - 2, z).setType(
                        ((x + localZ) & 1) == 0
                                ? Material.PRISMARINE : Material.DARK_PRISMARINE, false);
                source(world, x, FLOOR_Y - 1, z);
                source(world, x, FLOOR_Y, z);
            }
        }
        for (int lamp = 0; lamp < 16; lamp++) {
            double angle = Math.toRadians(lamp * 22.5d);
            int x = symmetric(quantised(Math.cos(angle)) * 7.8d);
            int z = GALLERY_CENTRE_Z + symmetric(quantised(Math.sin(angle)) * 7.8d);
            world.getBlockAt(x, FLOOR_Y - 2, z).setType(Material.SEA_LANTERN, false);
        }
        galleryBridge(world, 180d);
        for (LeaderboardBoard board : LeaderboardBoard.values()) {
            galleryBridge(world, board.angle);
        }
    }

    private static void galleryBridge(World world, double angleDegrees) {
        double angle = Math.toRadians(angleDegrees);
        double dirX = Math.sin(angle);
        double dirZ = -Math.cos(angle);
        for (double step = 5.2d; step <= 10.2d; step += 0.4d) {
            for (double lateral = -1.5d; lateral <= 1.5d; lateral += 0.5d) {
                int x = symmetric(quantised(dirX) * step - quantised(dirZ) * lateral);
                int z = GALLERY_CENTRE_Z
                        + symmetric(quantised(dirZ) * step + quantised(dirX) * lateral);
                world.getBlockAt(x, FLOOR_Y, z).setType(
                        Math.abs(lateral) < 0.75d ? Material.CALCITE
                                : Material.SMOOTH_BASALT, false);
                world.getBlockAt(x, FLOOR_Y + 1, z).setType(Material.AIR, false);
            }
        }
    }

    private static void buildLeaderboardFrame(World world, LeaderboardBoard board) {
        for (int lateral = -4; lateral <= 4; lateral++) {
            for (int y = 1; y <= 8; y++) {
                boolean frame = Math.abs(lateral) == 4 || y == 1 || y == 8;
                int x = galleryX(board.angle, GALLERY_FRAME_RING, lateral);
                int z = galleryZ(board.angle, GALLERY_FRAME_RING, lateral);
                Material material;
                if (!frame) material = Material.DEEPSLATE_TILES;
                else if (y == 8 && Math.abs(lateral) <= 1) material = Material.CALCITE;
                else if (Math.abs(lateral) == 4 && (y == 3 || y == 6)) {
                    material = Material.AMETHYST_BLOCK;
                } else material = Material.POLISHED_DEEPSLATE;
                world.getBlockAt(x, FLOOR_Y + y, z).setType(material, false);
            }
        }
        int topX = galleryX(board.angle, GALLERY_FRAME_RING, 0d);
        int topZ = galleryZ(board.angle, GALLERY_FRAME_RING, 0d);
        world.getBlockAt(topX, FLOOR_Y + 9, topZ)
                .setType(Material.SEA_LANTERN, false);
        world.getBlockAt(topX, FLOOR_Y + 10, topZ)
                .setType(Material.AMETHYST_CLUSTER, false);
    }

    private static Material galleryFloor(int x, int z, double radius) {
        if (radius > GALLERY_RADIUS - 1.2d) return Material.SMOOTH_BASALT;
        double angle = Math.atan2(z, x) + Math.PI;
        double sector = angle / (Math.PI * 2d) * 12d;
        double intoSector = sector - Math.floor(sector);
        double fromSpoke = Math.min(intoSector, 1d - intoSector);
        if (within(radius, 2.2d)) return Material.AMETHYST_BLOCK;
        if (within(radius, 5.5d)) return ((x + z) & 1) == 0
                ? Material.CALCITE : Material.POLISHED_DIORITE;
        if (within(radius, 10.2d)) return Material.CALCITE;
        if (fromSpoke * radius < 0.42d) return Material.CALCITE;
        return ((x * 3 + z * 5) & 7) == 0
                ? Material.CHISELED_DEEPSLATE : Material.DEEPSLATE_TILES;
    }

    private static int galleryX(double angleDegrees, double radius, double lateral) {
        double angle = Math.toRadians(angleDegrees);
        return symmetric(quantised(Math.sin(angle)) * radius
                + quantised(Math.cos(angle)) * lateral);
    }

    private static int galleryZ(double angleDegrees, double radius, double lateral) {
        double angle = Math.toRadians(angleDegrees);
        return GALLERY_CENTRE_Z + symmetric(-quantised(Math.cos(angle)) * radius
                + quantised(Math.sin(angle)) * lateral);
    }

    static int galleryCentreZ() {
        return GALLERY_CENTRE_Z;
    }

    static int galleryRadius() {
        return GALLERY_RADIUS;
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
                int x = symmetric(quantised(dirX) * step - quantised(dirZ) * lateral);
                int z = symmetric(quantised(dirZ) * step + quantised(dirX) * lateral);
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

    /** One queue arch: a real portal, a mode emblem, and an illuminated approach. */
    private static void buildGate(World world, Gate gate) {
        int gx = gate.x();
        int gz = gate.z();
        // Faces the middle, so every arch is read from the plaza.
        boolean wideX = Math.abs(gx) <= Math.abs(gz);
        Orientable portal = (Orientable) Material.NETHER_PORTAL.createBlockData();
        portal.setAxis(wideX ? Axis.X : Axis.Z);
        pad(world, gx, gz, gate.emblem());
        for (int lateral = -3; lateral <= 3; lateral++) {
            set(world, gx, gz, wideX, lateral, FLOOR_Y, Material.OBSIDIAN);
        }
        for (int lateral = -3; lateral <= 3; lateral++) {
            for (int y = 0; y <= 8; y++) {
                boolean post = Math.abs(lateral) == 3;
                boolean lintel = y == 8;
                if (post || lintel) {
                    set(world, gx, gz, wideX, lateral, FLOOR_Y + 1 + y, Material.OBSIDIAN);
                } else {
                    setData(world, gx, gz, wideX, lateral, FLOOR_Y + 1 + y, portal);
                }
            }
        }
        // Coloured fins and copper pylons keep six purple portals distinct at a glance.
        for (int lateral : new int[]{-4, 4}) {
            set(world, gx, gz, wideX, lateral, FLOOR_Y + 1, Material.CUT_COPPER);
            set(world, gx, gz, wideX, lateral, FLOOR_Y + 2, Material.EXPOSED_CUT_COPPER);
            set(world, gx, gz, wideX, lateral, FLOOR_Y + 3, Material.WEATHERED_CUT_COPPER);
            set(world, gx, gz, wideX, lateral, FLOOR_Y + 4, gate.glass());
            set(world, gx, gz, wideX, lateral, FLOOR_Y + 5, Material.CRYING_OBSIDIAN);
            set(world, gx, gz, wideX, lateral, FLOOR_Y + 6, Material.SOUL_LANTERN);
        }
        set(world, gx, gz, wideX, 0, FLOOR_Y + 10, gate.emblem());
        set(world, gx, gz, wideX, -1, FLOOR_Y + 10, Material.CHISELED_DEEPSLATE);
        set(world, gx, gz, wideX, 1, FLOOR_Y + 10, Material.CHISELED_DEEPSLATE);
        set(world, gx, gz, wideX, 0, FLOOR_Y + 9, Material.DEEPSLATE_BRICK_SLAB);
    }

    private static void buildReturnGate(World world) {
        int gx = RETURN_GATE[0];
        int gz = RETURN_GATE[1];
        Orientable portal = (Orientable) Material.NETHER_PORTAL.createBlockData();
        portal.setAxis(Axis.X);
        pad(world, gx, gz, Material.LODESTONE);
        for (int lateral = -2; lateral <= 2; lateral++) {
            set(world, gx, gz, true, lateral, FLOOR_Y, Material.OBSIDIAN);
        }
        for (int lateral = -2; lateral <= 2; lateral++) {
            for (int y = 0; y <= 6; y++) {
                boolean frame = Math.abs(lateral) == 2 || y == 6;
                if (frame) {
                    set(world, gx, gz, true, lateral, FLOOR_Y + 1 + y, Material.OBSIDIAN);
                } else {
                    setData(world, gx, gz, true, lateral, FLOOR_Y + 1 + y, portal);
                }
            }
        }
        set(world, gx, gz, true, 0, FLOOR_Y + 8, Material.LODESTONE);
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

    /** The UI opened by a physical lobby console. */
    enum PavilionAction { LADDER, RULES, RATINGS, LIVE }

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
            // The entire pavilion is the click target; the centre stays open instead
            // of putting a lectern in the walkway and through the live-match text.
            world.getBlockAt(cx, FLOOR_Y, cz).setType(Material.SEA_LANTERN, false);
            for (int[] light : List.of(new int[]{-2, -2}, new int[]{2, -2},
                    new int[]{-2, 2}, new int[]{2, 2})) {
                world.getBlockAt(cx + light[0], FLOOR_Y + 4, cz + light[1])
                        .setType(Material.SEA_LANTERN, false);
            }
        }
    }

    private static List<Pavilion> pavilions() {
        List<Pavilion> boards = new ArrayList<>();
        boards.add(new Pavilion(45d, "RANK PROGRESSION", Board.STATIC,
                List.of("18 ranks • 8 permanent tiers"), "RIGHT-CLICK PAVILION",
                PavilionAction.LADDER));
        boards.add(new Pavilion(135d, "FIGHT RULES", Board.STATIC,
                List.of("YOUR GEAR • REAL TERRAIN • KEEP INVENTORY"), "RIGHT-CLICK PAVILION",
                PavilionAction.RULES));
        boards.add(new Pavilion(225d, "PVP LEADERBOARDS", Board.STATIC,
                List.of("6 LIVE PLAYER & CLAN BOARDS"),
                "FOLLOW THE NORTH CONCOURSE", PavilionAction.RATINGS));
        boards.add(new Pavilion(315d, "LIVE MATCHES", Board.LIVE, List.of(),
                "RIGHT-CLICK PAVILION", PavilionAction.LIVE));
        return boards;
    }

    /** Which console was clicked, if the block belongs to one of the four pavilions. */
    static PavilionAction pavilionAction(PvpLobbyStore.Point lobby, Location clicked) {
        Location origin = origin(lobby, clicked);
        if (origin == null || Math.abs(clicked.getY() - (FLOOR_Y + 1d)) > 5d) return null;
        for (Pavilion pavilion : pavilions()) {
            double angle = Math.toRadians(pavilion.angle());
            double x = origin.getX()
                    + symmetric(quantised(Math.sin(angle)) * PAVILION_RING);
            double z = origin.getZ()
                    + symmetric(-quantised(Math.cos(angle)) * PAVILION_RING);
            if (horizontalSquared(clicked, x, z) <= 16d) return pavilion.action();
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
            Location title = gateLabelLocation(world, 0d, 0d, gate, FLOOR_Y + 6.8d);
            Location subtitle = gateLabelLocation(world, 0d, 0d, gate, FLOOR_Y + 5.3d);
            Location status = gateLabelLocation(world, 0d, 0d, gate, FLOOR_Y + 3.8d);
            fixedHologram(world, title.getX(), title.getY(), title.getZ(), gate.angle(),
                    Component.text(gate.title(), AMETHYST, TextDecoration.BOLD),
                    gateTitleScale, NEAR_GATE_TAG);
            fixedHologram(world, subtitle.getX(), subtitle.getY(), subtitle.getZ(), gate.angle(),
                    Component.text(gate.subtitle(), NamedTextColor.WHITE),
                    lineScale, NEAR_GATE_TAG);
            // Retitled every second by refreshStatus.
            fixedHologram(world, status.getX(), status.getY(), status.getZ(), gate.angle(),
                    Component.text("WALK THROUGH • CHECKING QUEUE", CRYSTAL, TextDecoration.BOLD),
                    lineScale, NEAR_GATE_TAG, STATUS_TAG);
        }
        fixedHologram(world, RETURN_GATE[0] + 0.5d, FLOOR_Y + 5.8d,
                RETURN_GATE[1] - 1.8d, 180d,
                Component.text("RETURN TO THE SMP", NamedTextColor.WHITE, TextDecoration.BOLD),
                gateTitleScale, NEAR_GATE_TAG);
        fixedHologram(world, RETURN_GATE[0] + 0.5d, FLOOR_Y + 4.3d,
                RETURN_GATE[1] - 1.8d, 180d,
                Component.text("WALK THROUGH • BACK TO YOUR EXACT LOCATION", CRYSTAL),
                lineScale, NEAR_GATE_TAG);

        hologram(world, 0.5d, FLOOR_Y + 15.6d, 0.5d,
                Component.text("THE AMETHYST TERRACE", AMETHYST, TextDecoration.BOLD),
                titleScale, NEAR_GATE_TAG);
        hologram(world, 0.5d, FLOOR_Y + 14.45d, 0.5d,
                Component.text("YOUR GEAR • REAL TERRAIN • NOTHING LOST", CRYSTAL),
                lineScale, NEAR_GATE_TAG);

        for (Pavilion pavilion : pavilions()) {
            double angle = Math.toRadians(pavilion.angle());
            double x = symmetric(quantised(Math.sin(angle)) * PAVILION_RING) + 0.5d;
            double z = symmetric(-quantised(Math.cos(angle)) * PAVILION_RING) + 0.5d;
            fixedHologram(world, x, FLOOR_Y + 4.25d, z, pavilion.angle(),
                    Component.text(pavilion.title(), AMETHYST, TextDecoration.BOLD),
                    titleScale, NEAR_BOARD_TAG);
            double line = FLOOR_Y + 3.2d;
            for (String text : pavilion.body()) {
                fixedHologram(world, x, line, z, pavilion.angle(),
                        Component.text(text, NamedTextColor.WHITE),
                        lineScale, NEAR_BOARD_TAG);
                line -= 0.72d;
            }
            if (pavilion.board() == Board.LIVE) {
                // Blank rows, claimed by refreshBoards. Their roomy spacing prevents
                // Java text and the Bedrock fallback from collapsing into one smear.
                for (int row = 0; row < LIVE_BOARD_LINES; row++) {
                    fixedHologram(world, x, line, z, pavilion.angle(),
                            Component.text(" ", NamedTextColor.DARK_GRAY), lineScale,
                            NEAR_BOARD_TAG, LIVE_TAG);
                    line -= 0.72d;
                }
            }
            fixedHologram(world, x, Math.max(FLOOR_Y + 1.45d, line), z,
                    pavilion.angle(),
                    Component.text(pavilion.prompt(), CRYSTAL, TextDecoration.BOLD),
                    lineScale, NEAR_BOARD_TAG);
        }

        double entranceZ = GALLERY_CENTRE_Z + GALLERY_RADIUS - 1.3d;
        fixedHologram(world, 0.5d, FLOOR_Y + 5.7d, entranceZ, 0d,
                Component.text("PVP LEADERBOARDS", AMETHYST, TextDecoration.BOLD),
                gateTitleScale, GALLERY_LABEL_TAG);
        fixedHologram(world, 0.5d, FLOOR_Y + 4.25d, entranceZ, 0d,
                Component.text("LIVE PLAYER & CLAN RECORDS", CRYSTAL, TextDecoration.BOLD),
                lineScale, GALLERY_LABEL_TAG);
        for (LeaderboardBoard board : LeaderboardBoard.values()) {
            double x = board.x();
            double z = board.z();
            fixedHologram(world, x, FLOOR_Y + 7d, z, board.angle,
                    Component.text(board.title, AMETHYST, TextDecoration.BOLD),
                    leaderboardTitleScale, GALLERY_LABEL_TAG);
            double line = FLOOR_Y + 5.4d;
            for (int row = 0; row < LEADERBOARD_LINES; row++) {
                fixedHologram(world, x, line, z, board.angle,
                        Component.text(" ", NamedTextColor.DARK_GRAY), lineScale,
                        GALLERY_LABEL_TAG, board.tag);
                line -= 0.78d;
            }
        }
    }

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

    /** A backed label that keeps its plane parallel to the wall or portal behind it. */
    private static void fixedHologram(
            World world,
            double x,
            double y,
            double z,
            double yaw,
            Component name,
            float scale,
            String distanceTag,
            String... tags
    ) {
        spawnHologram(world, x, y, z, yaw, Display.Billboard.FIXED,
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

    private static Location gateLabelLocation(
            World world, double originX, double originZ, Gate gate, double y
    ) {
        double radius = Math.max(1d, Math.hypot(gate.x(), gate.z()));
        double inward = 2d;
        return new Location(world,
                originX + gate.x() + 0.5d - gate.x() / radius * inward,
                y,
                originZ + gate.z() + 0.5d - gate.z() / radius * inward);
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
