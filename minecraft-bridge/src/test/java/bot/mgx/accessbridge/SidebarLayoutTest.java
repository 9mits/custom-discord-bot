package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static bot.mgx.accessbridge.SidebarLayout.Priority.ESSENTIAL;
import static bot.mgx.accessbridge.SidebarLayout.Priority.HEADING;
import static bot.mgx.accessbridge.SidebarLayout.Priority.IMPORTANT;
import static bot.mgx.accessbridge.SidebarLayout.Priority.SPACER;

class SidebarLayoutTest {
    /** Exactly what a ranked player in a level-five clan builds: twenty-three lines. */
    private static SidebarLayout.Priority[] fullBoard() {
        return new SidebarLayout.Priority[] {
                ESSENTIAL,  // title
                SPACER,
                ESSENTIAL,  // name and ping
                SPACER,
                HEADING,    // PROFILE
                IMPORTANT,  // rank
                IMPORTANT,  // server level
                IMPORTANT,  // extra hearts
                IMPORTANT,  // power
                IMPORTANT,  // clan
                SPACER,
                HEADING,    // STATS
                ESSENTIAL,  // kills
                ESSENTIAL,  // deaths
                ESSENTIAL,  // money
                SPACER,
                ESSENTIAL,  // footer
        };
    }

    private static int kept(boolean[] keep) {
        int count = 0;
        for (boolean flag : keep) {
            if (flag) {
                count++;
            }
        }
        return count;
    }

    private static List<Integer> keptIndexes(boolean[] keep) {
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < keep.length; index++) {
            if (keep[index]) {
                indexes.add(index);
            }
        }
        return indexes;
    }

    @Test
    void aFullBoardIsTrimmedToTheCap() {
        boolean[] keep = SidebarLayout.fit(fullBoard(), SidebarLayout.MAX_LINES);
        org.junit.jupiter.api.Assertions.assertEquals(SidebarLayout.MAX_LINES, kept(keep));
    }

    @Test
    void moneyAndTheStatsAlwaysSurvive() {
        // The reported bug: a clan at a high level pushed kills, deaths and money off
        // the bottom, because the board only ever gave up blank lines.
        boolean[] keep = SidebarLayout.fit(fullBoard(), SidebarLayout.MAX_LINES);
        SidebarLayout.Priority[] board = fullBoard();
        for (int index = 0; index < board.length; index++) {
            if (board[index] == ESSENTIAL) {
                org.junit.jupiter.api.Assertions.assertTrue(
                        keep[index], "essential line " + index + " was dropped");
            }
        }
    }

    @Test
    void theWholeProfileSurvivesOnAMaxedBoard() {
        // Rank, level, hearts, power and clan all fit: blank lines pay for them, which
        // is the trade the six perk rows used to make impossible.
        boolean[] keep = SidebarLayout.fit(fullBoard(), SidebarLayout.MAX_LINES);
        SidebarLayout.Priority[] board = fullBoard();
        int importantKept = 0;
        for (int index = 0; index < board.length; index++) {
            if (keep[index] && board[index] == IMPORTANT) {
                importantKept++;
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(5, importantKept);
    }

    @Test
    void theBlankAboveTheFooterIsTheLastBlankToGo() {
        SidebarLayout.Priority[] board = {
                ESSENTIAL, SPACER, ESSENTIAL, SPACER, ESSENTIAL,
        };
        boolean[] keep = SidebarLayout.fit(board, 4);
        org.junit.jupiter.api.Assertions.assertEquals(List.of(0, 2, 3, 4), keptIndexes(keep));
    }

    @Test
    void headingsGoBeforeTheRowsTheyLabel() {
        SidebarLayout.Priority[] board = {HEADING, IMPORTANT, IMPORTANT, ESSENTIAL};
        boolean[] keep = SidebarLayout.fit(board, 3);
        org.junit.jupiter.api.Assertions.assertEquals(List.of(1, 2, 3), keptIndexes(keep));
    }

    @Test
    void aBoardThatAlreadyFitsIsLeftAlone() {
        SidebarLayout.Priority[] board = {ESSENTIAL, SPACER, HEADING, IMPORTANT, ESSENTIAL};
        boolean[] keep = SidebarLayout.fit(board, SidebarLayout.MAX_LINES);
        org.junit.jupiter.api.Assertions.assertEquals(List.of(0, 1, 2, 3, 4), keptIndexes(keep));
    }

    @Test
    void boostsAreLaidOutThreeToARowForTheTabList() {
        List<String> boosts = List.of(
                "+3 Hearts", "Strength +10%", "Saturation +15%",
                "Digging +25%", "Resistance +15%", "Speed +15%");
        List<String> rows = SidebarLayout.boostRows(boosts, SidebarLayout.BOOSTS_PER_ROW);
        org.junit.jupiter.api.Assertions.assertEquals(2, rows.size());
        org.junit.jupiter.api.Assertions.assertEquals(
                "+3 Hearts   Strength +10%   Saturation +15%", rows.get(0));
        org.junit.jupiter.api.Assertions.assertEquals(
                "Digging +25%   Resistance +15%   Speed +15%", rows.get(1));
    }

    @Test
    void aPartialLastRowIsNotPaddedOrOverrun() {
        List<String> rows = SidebarLayout.boostRows(
                List.of("Strength +5%", "Saturation +5%", "Digging +10%", "Speed +5%"), 3);
        org.junit.jupiter.api.Assertions.assertEquals(2, rows.size());
        org.junit.jupiter.api.Assertions.assertEquals("Speed +5%", rows.get(1));
    }

    @Test
    void aClanWithNoBoostsGetsNoRows() {
        org.junit.jupiter.api.Assertions.assertEquals(List.of(), SidebarLayout.boostRows(List.of(), 3));
        org.junit.jupiter.api.Assertions.assertEquals(List.of(), SidebarLayout.boostRows(null, 3));
        // A nonsense width must not spin forever adding empty rows.
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of(), SidebarLayout.boostRows(List.of("Speed +5%"), 0));
    }

    @Test
    void essentialsSurviveEvenABoardWithNoRoomForThem() {
        // Nothing can drop an essential line, so a cap below their count is exceeded
        // rather than honoured — losing the balance is never the right answer.
        SidebarLayout.Priority[] board = {ESSENTIAL, ESSENTIAL, ESSENTIAL, SPACER};
        boolean[] keep = SidebarLayout.fit(board, 1);
        org.junit.jupiter.api.Assertions.assertEquals(List.of(0, 1, 2), keptIndexes(keep));
    }
}
