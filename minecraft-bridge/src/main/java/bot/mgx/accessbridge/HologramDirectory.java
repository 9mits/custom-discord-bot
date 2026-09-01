package bot.mgx.accessbridge;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One way to manage everything the server draws in the world.
 *
 * <p>There were two: leaderboard boards went through {@code /mgxadmin hologram <board>},
 * which placed one where you stood, and crate chests went through
 * {@code /cratehologram set <kind>}, which placed one on the block you were looking at.
 * They disagreed on the verb, the anchor, the wording of success and failure, whether the
 * console could use them, and which permission check they made — and neither could tell
 * you what was already placed, so the only way to find a hologram was to walk to it.
 *
 * <p>This is the single front door. It routes to whichever store owns the thing, keeps
 * the differences that are real — a board hangs above you, a crate has to be a chest you
 * are looking at — and adds the listing that neither had.
 */
final class HologramDirectory {
    private final HologramService boards;
    private final CrateDisplayService crates;

    HologramDirectory(HologramService boards, CrateDisplayService crates) {
        this.boards = boards;
        this.crates = crates;
    }

    /**
     * Places one, whichever kind it is.
     *
     * @param what a board name, or {@code crate:<kind>} for a physical crate
     */
    String create(Player player, String what) {
        String wanted = String.valueOf(what).strip().toLowerCase(Locale.ROOT);
        try {
            if (wanted.startsWith("crate:")) {
                CrateKind kind = CrateKind.from(wanted.substring("crate:".length()))
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Use crate:default, crate:amethyst or crate:shard."));
                crates.place(player, kind);
                return "Placed the " + kind.displayName()
                        + " on the chest you are looking at.";
            }
            boards.place(player, HologramService.Board.fromKey(wanted));
            return "Placed that leaderboard where you are standing.";
        } catch (IOException unwritable) {
            throw new IllegalArgumentException("That could not be saved. Try again.");
        }
    }

    /**
     * Everything placed, with an id you can delete by.
     *
     * <p>The listing is the part neither system had. Without it the only way to find a
     * hologram was to remember where you put it and walk there.
     */
    List<String> list() {
        List<String> lines = new ArrayList<>();
        List<String> placedBoards = boards.describeAll();
        List<String> placedCrates = crates.describeAll();
        if (placedBoards.isEmpty() && placedCrates.isEmpty()) {
            lines.add("Nothing is placed. /mgx world hologram create <what> puts one down.");
            return lines;
        }
        lines.add("Leaderboards (" + placedBoards.size() + "):");
        if (placedBoards.isEmpty()) {
            lines.add("  none");
        } else {
            placedBoards.forEach(line -> lines.add("  " + line));
        }
        lines.add("Physical crates (" + placedCrates.size() + "):");
        if (placedCrates.isEmpty()) {
            lines.add("  none");
        } else {
            placedCrates.forEach(line -> lines.add("  " + line));
        }
        return lines;
    }

    /**
     * Removes one.
     *
     * <p>{@code here} keeps what both old commands did — the nearest board, or the crate
     * you are looking at — because standing next to the thing you want gone is how people
     * actually use this. An explicit id is there for when you are not standing next to it.
     */
    String delete(Player player, String target) {
        String wanted = String.valueOf(target).strip().toLowerCase(Locale.ROOT);
        try {
            if (wanted.isEmpty() || wanted.equals("here")) {
                if (crates.removeLookedAt(player)) {
                    return "Removed that physical crate.";
                }
                boards.removeNearby(player);
                return "Removed the nearby leaderboard.";
            }
            if (wanted.startsWith("crate:")) {
                CrateKind kind = CrateKind.from(wanted.substring("crate:".length()))
                        .orElseThrow(() -> new IllegalArgumentException("Unknown crate."));
                if (!crates.removeKind(kind)) {
                    throw new IllegalArgumentException(
                            "No " + kind.displayName() + " is placed.");
                }
                return "Removed the " + kind.displayName() + ".";
            }
            if (!boards.removeBoard(HologramService.Board.fromKey(wanted))) {
                throw new IllegalArgumentException("That leaderboard is not placed.");
            }
            return "Removed that leaderboard.";
        } catch (IOException unwritable) {
            throw new IllegalArgumentException("That could not be saved. Try again.");
        }
    }

    void reload() {
        boards.refresh();
        crates.refresh();
    }

    /** A short, stable description of one placement. */
    static String describe(String id, Location at) {
        return id + "  " + (at.getWorld() == null ? "?" : at.getWorld().getName())
                + " " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ();
    }
}
