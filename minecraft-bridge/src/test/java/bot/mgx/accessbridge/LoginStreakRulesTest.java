package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LoginStreakRulesTest {
    private static final long MONDAY = 20_000L;

    @Test
    void consecutiveDaysExtendTheStreakAndTheSameDayClaimsOnce() {
        LoginStreakRules.Claim first = LoginStreakRules.claim(LoginStreakRules.State.NEW, MONDAY, 2);
        assertTrue(first.claimed());
        assertEquals(1, first.state().streak());
        assertFalse(LoginStreakRules.claim(first.state(), MONDAY, 2).claimed(),
                "one claim per UTC day");
        LoginStreakRules.Claim second = LoginStreakRules.claim(first.state(), MONDAY + 1, 2);
        assertEquals(2, second.state().streak());
        assertEquals(2, second.state().best());
    }

    @Test
    void everySeventhDayEarnsAFreezeThatCoversAMissedDay() {
        LoginStreakRules.State state = LoginStreakRules.State.NEW;
        for (int day = 0; day < 7; day++) {
            state = LoginStreakRules.claim(state, MONDAY + day, 2).state();
        }
        assertEquals(7, state.streak());
        assertEquals(1, state.freezes());
        assertEquals(7, LoginStreakRules.liveStreak(state, MONDAY + 8),
                "one missed day is still savable with a freeze");
        LoginStreakRules.Claim afterGap = LoginStreakRules.claim(state, MONDAY + 8, 2);
        assertEquals(1, afterGap.freezesUsed());
        assertEquals(8, afterGap.state().streak());
        assertEquals(0, afterGap.state().freezes());
    }

    @Test
    void aGapWithoutEnoughFreezesStartsAgainButKeepsTheBest() {
        LoginStreakRules.State state = new LoginStreakRules.State(5, 5, MONDAY, 0);
        assertEquals(0, LoginStreakRules.liveStreak(state, MONDAY + 2));
        LoginStreakRules.Claim claim = LoginStreakRules.claim(state, MONDAY + 2, 2);
        assertTrue(claim.reset());
        assertEquals(1, claim.state().streak());
        assertEquals(5, claim.state().best());
    }

    @Test
    void freezesNeverExceedTheMaximumAndTheCycleRepeats() {
        LoginStreakRules.State state = new LoginStreakRules.State(13, 13, MONDAY, 2);
        LoginStreakRules.Claim claim = LoginStreakRules.claim(state, MONDAY + 1, 2);
        assertEquals(14, claim.state().streak());
        assertEquals(2, claim.state().freezes());
        assertFalse(claim.freezeEarned());
        assertEquals(7, LoginStreakRules.cycleDay(7));
        assertEquals(1, LoginStreakRules.cycleDay(8));
        assertEquals(7, LoginStreakRules.cycleDay(14));
        assertEquals(6, LoginStreakRules.nextStreakDay(new LoginStreakRules.State(5, 5, MONDAY, 0), MONDAY + 1));
    }
}
