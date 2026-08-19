package bot.mgx.accessbridge;

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

    /**
     * How many clan boosts share the one line before it starts rotating.
     *
     * <p>Two keeps the row at roughly the width of the longest row already on the
     * board. All six on one line is two and a half times that, and since the widest
     * row is what the centred title and footer pad against, one long line stretches
     * the whole sidebar across the screen.
     */
    static final int BOOSTS_PER_LINE = 2;

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

    /** The label and value for the boost row, or null when a clan has no boosts yet. */
    record Boosts(String label, String value) {
    }

    /**
     * The slice of a clan's boosts to draw this refresh.
     *
     * <p>Rotates only when there are more boosts than fit: a clan with two or fewer
     * sees a still line with no page number, because a counter that never counts reads
     * as broken. {@code tick} advances once per sidebar refresh, so each page holds for
     * one refresh interval.
     */
    static Boosts boostsFor(List<String> boosts, long tick) {
        if (boosts == null || boosts.isEmpty()) {
            return null;
        }
        int pages = (boosts.size() + BOOSTS_PER_LINE - 1) / BOOSTS_PER_LINE;
        int page = pages <= 1 ? 0 : (int) Math.floorMod(tick, pages);
        int from = page * BOOSTS_PER_LINE;
        int to = Math.min(boosts.size(), from + BOOSTS_PER_LINE);
        String label = pages <= 1 ? "Boosts" : "Boosts " + (page + 1) + "/" + pages;
        return new Boosts(label, String.join(" ", boosts.subList(from, to)));
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
