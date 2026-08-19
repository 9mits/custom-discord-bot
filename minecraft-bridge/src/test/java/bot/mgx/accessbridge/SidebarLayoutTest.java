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
                IMPORTANT,  // boosts, one rotating row
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
        // Rank, level, hearts, power, clan and the boost row all fit: blank lines pay
        // for them, which is the trade the six perk rows used to make impossible.
        boolean[] keep = SidebarLayout.fit(fullBoard(), SidebarLayout.MAX_LINES);
        SidebarLayout.Priority[] board = fullBoard();
        int importantKept = 0;
        for (int index = 0; index < board.length; index++) {
            if (keep[index] && board[index] == IMPORTANT) {
                importantKept++;
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(6, importantKept);
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
    void boostsSitStillUntilThereAreMoreThanFitOnOneLine() {
        // A page counter that never counts reads as broken, so a clan with two or
        // fewer boosts gets a plain label and no rotation.
        SidebarLayout.Boosts one = SidebarLayout.boostsFor(List.of("+3HP"), 0);
        org.junit.jupiter.api.Assertions.assertEquals("Boosts", one.label());
        org.junit.jupiter.api.Assertions.assertEquals("+3HP", one.value());

        SidebarLayout.Boosts two = SidebarLayout.boostsFor(List.of("+3HP", "10%STR"), 7);
        org.junit.jupiter.api.Assertions.assertEquals("Boosts", two.label());
        org.junit.jupiter.api.Assertions.assertEquals("+3HP 10%STR", two.value());
    }

    @Test
    void sixBoostsCycleThroughThreePagesAndWrap() {
        List<String> boosts = List.of("+3HP", "10%STR", "15%SAT", "25%DIG", "15%RES", "15%SPD");
        org.junit.jupiter.api.Assertions.assertEquals(
                "Boosts 1/3", SidebarLayout.boostsFor(boosts, 0).label());
        org.junit.jupiter.api.Assertions.assertEquals(
                "+3HP 10%STR", SidebarLayout.boostsFor(boosts, 0).value());
        org.junit.jupiter.api.Assertions.assertEquals(
                "15%SAT 25%DIG", SidebarLayout.boostsFor(boosts, 1).value());
        org.junit.jupiter.api.Assertions.assertEquals(
                "15%RES 15%SPD", SidebarLayout.boostsFor(boosts, 2).value());
        // Wraps back round rather than running off the end.
        org.junit.jupiter.api.Assertions.assertEquals(
                "+3HP 10%STR", SidebarLayout.boostsFor(boosts, 3).value());
    }

    @Test
    void anOddBoostCountDoesNotOverrunTheLastPage() {
        List<String> boosts = List.of("5%STR", "5%SAT", "10%DIG");
        org.junit.jupiter.api.Assertions.assertEquals(
                "5%STR 5%SAT", SidebarLayout.boostsFor(boosts, 0).value());
        org.junit.jupiter.api.Assertions.assertEquals(
                "10%DIG", SidebarLayout.boostsFor(boosts, 1).value());
    }

    @Test
    void aClanWithNoBoostsGetsNoRow() {
        org.junit.jupiter.api.Assertions.assertNull(SidebarLayout.boostsFor(List.of(), 0));
        org.junit.jupiter.api.Assertions.assertNull(SidebarLayout.boostsFor(null, 0));
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
