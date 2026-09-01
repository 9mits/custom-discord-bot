package bot.mgx.accessbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static bot.mgx.accessbridge.MenuItems.ORANGE;

/**
 * In-world leaderboard holograms. Armor stands, not text displays, so Bedrock
 * through Geyser can read them.
 */
final class HologramService {
    private static final String TAG = "mgx_hologram";
    private static final double LINE_GAP = 0.28;
    private static final int ROWS = 10;

    enum Board {
        PLAYERS_WEALTH("individual", "wealth", "TOP WEALTH"),
        PLAYERS_KILLS("individual", "kills", "TOP KILLS"),
        AMETHYST_CRATES("individual", "amethyst_crates", "MOST AMETHYST CRATES OPENED"),
        AMETHYST_AIRDROPS("individual", "amethyst_airdrops", "MOST AMETHYST AIRDROPS OPENED"),
        CLANS_WEALTH("clan", "wealth", "TOP CLAN WEALTH"),
        CLANS_KILLS("clan", "kills", "TOP CLAN KILLS"),
        CLAN_BATTLE("clan", "clan_battle", "CLAN BATTLE");

        private final String scope;
        private final String key;
        private final String title;

        Board(String scope, String key, String title) {
            this.scope = scope;
            this.key = key;
            this.title = title;
        }

        static Board fromKey(String raw) {
            if (raw == null) {
                throw new IllegalArgumentException(usage());
            }
            String token = raw.strip().toLowerCase(Locale.ROOT).replace('_', '-');
            return switch (token) {
                case "wealth", "players-wealth", "richest" -> PLAYERS_WEALTH;
                case "kills", "players-kills" -> PLAYERS_KILLS;
                case "amethyst-crates", "event-crates", "crates-opened" -> AMETHYST_CRATES;
                case "amethyst-airdrops", "event-airdrops", "airdrops-opened" -> AMETHYST_AIRDROPS;
                case "clans-wealth", "clan-wealth", "clans" -> CLANS_WEALTH;
                case "clans-kills", "clan-kills" -> CLANS_KILLS;
                case "clan-battle", "clanbattle", "battle" -> CLAN_BATTLE;
                default -> throw new IllegalArgumentException(usage());
            };
        }

        String key() {
            return key;
        }

        static String usage() {
            return "Usage: /mgxadmin hologram <wealth|kills|amethyst-crates|"
                    + "amethyst-airdrops|clans-wealth|clans-kills|clan-battle|remove>";
        }
    }

    private record Placement(Board board, UUID worldId, double x, double y, double z) {
    }

    private final Path file;
    private final LeaderboardService boards;
    private final ClanStore clans;
    private final DiscordIdentityService identities;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final List<Placement> placements = new ArrayList<>();

    HologramService(
            Path file,
            LeaderboardService boards,
            ClanStore clans,
            DiscordIdentityService identities
    ) throws IOException {
        this.file = file;
        this.boards = boards;
        this.clans = clans;
        this.identities = identities;
        load();
    }

    void place(Player player, Board board) throws IOException {
        Location at = player.getLocation().add(0, 2.4, 0);
        World world = at.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("Stand in a world first.");
        }
        List<Placement> before = List.copyOf(placements);
        placements.removeIf(row -> row.board() == board);
        placements.add(new Placement(board, world.getUID(), at.getX(), at.getY(), at.getZ()));
        persistOrRestore(before);
        refresh();
    }

    void removeNearby(Player player) throws IOException {
        Location at = player.getLocation();
        List<Placement> before = List.copyOf(placements);
        boolean removed = placements.removeIf(row ->
                row.worldId().equals(at.getWorld() == null ? null : at.getWorld().getUID())
                        && distanceSquared(row, at) <= 16
        );
        if (!removed) {
            throw new IllegalArgumentException("No hologram within 4 blocks.");
        }
        persistOrRestore(before);
        refresh();
    }

    /** Every placed board, for the directory listing. */
    synchronized List<String> describeAll() {
        List<String> lines = new ArrayList<>();
        for (Placement row : placements) {
            org.bukkit.World world = org.bukkit.Bukkit.getWorld(row.worldId());
            lines.add(row.board().key() + "  "
                    + (world == null ? "?" : world.getName()) + " "
                    + (int) row.x() + " " + (int) row.y() + " " + (int) row.z());
        }
        return lines;
    }

    /** Removes one by name rather than by standing next to it. */
    synchronized boolean removeBoard(Board board) throws IOException {
        List<Placement> before = List.copyOf(placements);
        if (!placements.removeIf(row -> row.board() == board)) {
            return false;
        }
        persistOrRestore(before);
        refresh();
        return true;
    }

    void refresh() {
        clearStands();
        Map<String, Integer> colours = clanColours();
        for (Placement placement : List.copyOf(placements)) {
            World world = Bukkit.getWorld(placement.worldId());
            if (world == null) {
                continue;
            }
            spawn(world, placement, colours);
        }
    }

    /**
     * Retitles just the clan battle countdown line. A full {@link #refresh()} respawns
     * every stand, which at one second apart would flicker the whole board; the boards
     * themselves only change on a leaderboard publish, so only this line needs the tick.
     */
    void tickCountdown() {
        for (Placement placement : List.copyOf(placements)) {
            if (placement.board() != Board.CLAN_BATTLE) {
                continue;
            }
            World world = Bukkit.getWorld(placement.worldId());
            if (world == null) {
                continue;
            }
            Location at = new Location(
                    world, placement.x(), placement.y() - LINE_GAP, placement.z()
            );
            if (!world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
                continue;
            }
            Component line = subtitle(Board.CLAN_BATTLE);
            for (ArmorStand stand
                    : world.getNearbyEntitiesByType(ArmorStand.class, at, 0.12d)) {
                if (stand.getScoreboardTags().contains(TAG)) {
                    stand.customName(line);
                }
            }
        }
    }

    private void spawn(World world, Placement placement, Map<String, Integer> colours) {
        List<Component> lines = lines(placement.board(), colours);
        for (int index = 0; index < lines.size(); index++) {
            Location at = new Location(
                    world,
                    placement.x(),
                    placement.y() - (index * LINE_GAP),
                    placement.z()
            );
            Component line = lines.get(index);
            world.spawn(at, ArmorStand.class, stand -> {
                stand.setInvisible(true);
                stand.setMarker(true);
                stand.setGravity(false);
                stand.setInvulnerable(true);
                stand.setSilent(true);
                stand.setCustomNameVisible(true);
                stand.customName(line);
                stand.addScoreboardTag(TAG);
                stand.setPersistent(true);
            });
        }
    }

    private List<Component> lines(Board board, Map<String, Integer> colours) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text(title(board), ORANGE, TextDecoration.BOLD));
        lines.add(subtitle(board));
        JsonArray rows = rows(board);
        for (int index = 0; index < ROWS; index++) {
            if (index < rows.size()) {
                lines.add(rowLine(board, index + 1, rows.get(index).getAsJsonObject(), colours));
            } else {
                lines.add(Component.text("#" + (index + 1) + " | ---", NamedTextColor.DARK_GRAY));
            }
        }
        return lines;
    }

    private String title(Board board) {
        if (board != Board.CLAN_BATTLE) {
            return board.title;
        }
        JsonObject snapshot = boards.latest();
        if (snapshot != null && snapshot.has("clan_battle")
                && snapshot.get("clan_battle").isJsonObject()) {
            return text(snapshot.getAsJsonObject("clan_battle"), "name").toUpperCase(Locale.ROOT);
        }
        return board.title;
    }

    private Component subtitle(Board board) {
        if (board != Board.CLAN_BATTLE) {
            return Component.empty();
        }
        JsonObject snapshot = boards.latest();
        if (snapshot != null && snapshot.has("clan_battle")
                && snapshot.get("clan_battle").isJsonObject()) {
            JsonObject event = snapshot.getAsJsonObject("clan_battle");
            String objective = text(event, "objective");
            long endsAt = event.has("ends_at") ? event.get("ends_at").getAsLong() : 0L;
            if (endsAt > 0L) {
                return Component.text(
                        objective + "  —  ENDS IN "
                                + ClanBattleCountdown.clock(endsAt - System.currentTimeMillis()),
                        NamedTextColor.YELLOW
                );
            }
            if (!objective.isBlank()) {
                return Component.text(objective, NamedTextColor.YELLOW);
            }
        }
        return Component.text("No event running", NamedTextColor.DARK_GRAY);
    }

    private Component rowLine(Board board, int place, JsonObject row, Map<String, Integer> colours) {
        if (row.has("rank")) {
            place = row.get("rank").getAsInt();
        }
        Component prefix = Component.text("#" + place + " | ", NamedTextColor.WHITE);
        if (board.scope.equals("clan")) {
            String name = text(row, "clan");
            int colour = row.has("colour") ? row.get("colour").getAsInt() : 0xFF9900;
            int level = row.has("level") ? row.get("level").getAsInt() : 0;
            String tag = level > 0 ? "[" + name + "] Lv" + level : "[" + name + "]";
            String badges = text(row, "badges");
            if (!badges.isBlank()) {
                tag += " " + badges;
            }
            String display = text(row, "display");
            return prefix
                    .append(MenuText.sprite(ClanIcon.resolve(text(row, "icon")).sprite()))
                    .append(Component.text(" "))
                    .append(Component.text(tag, TextColor.color(colour), TextDecoration.BOLD))
                    .append(Component.text(": " + display, NamedTextColor.WHITE));
        }
        UUID uuid = parseUuid(text(row, "minecraft_uuid"));
        String minecraft = text(row, "username");
        String display = text(row, "display");
        String clanName = text(row, "clan");
        Component line = prefix;
        if (!clanName.isBlank()) {
            int colour = colours.getOrDefault(clanName.toLowerCase(Locale.ROOT), 0xFF9900);
            line = line.append(Component.text("[" + clanName + "] ", TextColor.color(colour), TextDecoration.BOLD));
        }
        if (uuid != null) {
            String discord = identities.visibleUsername(uuid).orElse("");
            if (!discord.isBlank()) {
                line = line.append(Component.text("(" + discord + ") ", TextColor.color(0x5865F2)));
            }
        }
        return line.append(Component.text(minecraft + ": " + display, NamedTextColor.WHITE));
    }

    private JsonArray rows(Board board) {
        JsonObject snapshot = boards.latest();
        if (snapshot == null || !snapshot.has(board.scope)) {
            return new JsonArray();
        }
        JsonObject section = snapshot.getAsJsonObject(board.scope);
        if (section == null || !section.has(board.key) || !section.get(board.key).isJsonArray()) {
            return new JsonArray();
        }
        return section.getAsJsonArray(board.key);
    }

    private Map<String, Integer> clanColours() {
        Map<String, Integer> colours = new HashMap<>();
        for (ClanStore.ClanView clan : clans.list()) {
            colours.put(clan.name().toLowerCase(Locale.ROOT), clan.themeColor());
        }
        return colours;
    }

    private void clearStands() {
        for (World world : Bukkit.getWorlds()) {
            for (ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
                if (stand.getScoreboardTags().contains(TAG)) {
                    stand.remove();
                }
            }
        }
    }

    private void load() throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!root.has("placements") || !root.get("placements").isJsonArray()) {
                return;
            }
            for (JsonElement element : root.getAsJsonArray("placements")) {
                JsonObject row = element.getAsJsonObject();
                placements.add(new Placement(
                        Board.fromKey(row.get("board").getAsString()),
                        UUID.fromString(row.get("world").getAsString()),
                        row.get("x").getAsDouble(),
                        row.get("y").getAsDouble(),
                        row.get("z").getAsDouble()
                ));
            }
        } catch (RuntimeException exception) {
            throw new IOException("Hologram store is unreadable", exception);
        }
    }

    private void persist() throws IOException {
        JsonObject root = new JsonObject();
        JsonArray listed = new JsonArray();
        for (Placement placement : placements) {
            JsonObject row = new JsonObject();
            row.addProperty("board", placement.board().name().toLowerCase(Locale.ROOT).replace('_', '-'));
            row.addProperty("world", placement.worldId().toString());
            row.addProperty("x", placement.x());
            row.addProperty("y", placement.y());
            row.addProperty("z", placement.z());
            listed.add(row);
        }
        root.add("placements", listed);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, gson.toJson(root), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
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

    private static double distanceSquared(Placement placement, Location at) {
        double dx = placement.x() - at.getX();
        double dy = placement.y() - at.getY();
        double dz = placement.z() - at.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static String text(JsonObject row, String key) {
        return row.has(key) ? row.get(key).getAsString() : "";
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
