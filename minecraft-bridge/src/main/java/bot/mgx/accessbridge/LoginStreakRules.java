package bot.mgx.accessbridge;

/**
 * The daily login streak: one claim per UTC day, a seven-day reward cycle, and streak
 * freezes that forgive a missed day.
 *
 * <p>Free of Bukkit and of the clock, so every day boundary is unit tested. Days are UTC
 * epoch days because every time a player reads is UTC.
 */
final class LoginStreakRules {
    static final int CYCLE_DAYS = 7;

    /** One player's streak as saved. {@code lastClaimDay} is a UTC epoch day, or -1. */
    record State(int streak, int best, long lastClaimDay, int freezes) {
        static final State NEW = new State(0, 0, -1L, 0);
    }

    /** The result of claiming today: the new state and whether freezes were spent. */
    record Claim(State state, boolean claimed, int freezesUsed, boolean freezeEarned, boolean reset) { }

    private LoginStreakRules() {
    }

    /**
     * Claims today. A claim the day after the last one extends the streak; a gap that
     * held freezes can cover is forgiven, spending one freeze per missed day; anything
     * else starts again at day one. Every seventh day earns a freeze up to the maximum.
     */
    static Claim claim(State state, long today, int maximumFreezes) {
        State current = state == null ? State.NEW : state;
        if (current.lastClaimDay() == today) {
            return new Claim(current, false, 0, false, false);
        }
        long missed = current.lastClaimDay() < 0L ? Long.MAX_VALUE : today - current.lastClaimDay() - 1L;
        int streak;
        int freezes = current.freezes();
        int used = 0;
        boolean reset = false;
        if (current.lastClaimDay() >= 0L && missed == 0L) {
            streak = current.streak() + 1;
        } else if (current.lastClaimDay() >= 0L && missed > 0L && missed <= freezes) {
            used = (int) missed;
            freezes -= used;
            streak = current.streak() + 1;
        } else {
            reset = current.streak() > 0;
            streak = 1;
        }
        boolean earned = false;
        if (streak % CYCLE_DAYS == 0 && freezes < Math.max(0, maximumFreezes)) {
            freezes++;
            earned = true;
        }
        State next = new State(streak, Math.max(current.best(), streak), today,
                Math.min(freezes, Math.max(0, maximumFreezes)));
        return new Claim(next, true, used, earned, reset);
    }

    /** The streak a player can still keep today; zero once it can no longer be saved. */
    static int liveStreak(State state, long today) {
        if (state == null || state.lastClaimDay() < 0L) return 0;
        long missed = today - state.lastClaimDay() - 1L;
        return missed <= state.freezes() ? state.streak() : 0;
    }

    /** Which reward of the seven-day cycle a streak day pays: 1 to 7. */
    static int cycleDay(int streak) {
        return Math.floorMod(Math.max(1, streak) - 1, CYCLE_DAYS) + 1;
    }

    /** The streak day today's claim would be. */
    static int nextStreakDay(State state, long today) {
        if (state != null && state.lastClaimDay() == today) return state.streak();
        return liveStreak(state, today) + 1;
    }
}
