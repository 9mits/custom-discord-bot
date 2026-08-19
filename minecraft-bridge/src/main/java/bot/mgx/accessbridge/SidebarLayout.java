package bot.mgx.accessbridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decides which sidebar lines survive the board's fifteen-row cap.
 *
 * <p>A vanilla sidebar draws fifteen rows and silently discards the rest, so the board
 * used to lose whatever happened to be at the bottom. For a ranked player in a
 * high-level clan that was twenty-three rows, and the eight that fell off were Kills,
 * Deaths and Money — the ones people actually watch — because the clan perk rows above
 * them pushed everything down. Trimming only blank lines, which is what it did before,
 * could give back two.
 *
 * <p>Lines now give up their place in priority order instead. Nothing marked
 * {@link Priority#ESSENTIAL} is ever dropped, so the balance cannot be pushed off the
 * board by anything added above it.
 *
 * <p>Free of Bukkit and Adventure types so the arithmetic is unit tested: those are
 * {@code compileOnly} and absent at test runtime.
 */
final class SidebarLayout {
    /** What a vanilla scoreboard sidebar will draw. Everything past this is dropped. */
    static final int MAX_LINES = 15;

    /** Clan boosts to a row in the tab list, which is far wider than the sidebar. */
    static final int BOOSTS_PER_ROW = 3;

    /** Lowest gives up its line first. */
    enum Priority {
        /** Blank lines. Readability, not information. */
        SPACER,
        /** PROFILE and STATS. The rows below them are labelled either way. */
        HEADING,
        /** Rank, level, hearts, power, clan. */
        IMPORTANT,
        /** Title, name, kills, deaths, money, footer. Never dropped. */
        ESSENTIAL
    }

    private SidebarLayout() {
    }

    /**
     * @return one flag per line, false where the line has to give up its place
     */
    static boolean[] fit(Priority[] lines, int maxLines) {
        boolean[] keep = new boolean[lines.length];
        Arrays.fill(keep, true);
        int over = lines.length - Math.max(0, maxLines);
        // Perk rows go from the bottom up, so the first one listed — the largest —
        // is the last to be given up.
        // Lines go from the top down. The blank above the footer is what separates it
        // from the stats, so it should be the last blank to go rather than the first.
        over = drop(lines, keep, Priority.SPACER, over);
        over = drop(lines, keep, Priority.HEADING, over);
        drop(lines, keep, Priority.IMPORTANT, over);
        return keep;
    }

    /**
     * Clan boosts grouped into rows for the tab list.
     *
     * <p>They live there rather than on the sidebar because the sidebar is capped at
     * fifteen rows and the tab list is not: six boosts spelled out in full is what
     * pushed the balance off the board, and abbreviating them to fit made them hard
     * to read. The tab list has room for their real names.
     */
    static List<String> boostRows(List<String> boosts, int perRow) {
        if (boosts == null || boosts.isEmpty() || perRow < 1) {
            return List.of();
        }
        List<String> rows = new ArrayList<>();
        for (int from = 0; from < boosts.size(); from += perRow) {
            rows.add(String.join("   ", boosts.subList(from, Math.min(boosts.size(), from + perRow))));
        }
        return List.copyOf(rows);
    }

    private static int drop(Priority[] lines, boolean[] keep, Priority level, int over) {
        for (int index = 0; index < lines.length && over > 0; index++) {
            if (keep[index] && lines[index] == level) {
                keep[index] = false;
                over--;
            }
        }
        return over;
    }
}
