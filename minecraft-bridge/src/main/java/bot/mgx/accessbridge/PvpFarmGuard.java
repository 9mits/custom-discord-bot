package bot.mgx.accessbridge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stops two people turning {@code /pvp} into a wins tap.
 *
 * <p>Elo already makes farming somebody far below you nearly worthless in rating
 * terms, but wins, kills and streaks are counted whoever the opponent was — so a pair
 * of friends, or one person on a Java and a Bedrock account, can still take turns
 * dying to each other and climb every board that is not rating.
 *
 * <p>Two measures, deliberately different in severity. Fighting the same person over
 * and over is normal for a rivalry and only suspicious in volume, so it earns a rest
 * on that pairing rather than a refusal. Fighting <em>yourself</em> across two linked
 * accounts has no legitimate reading at all, so the duel service refuses it outright
 * — that check lives with the Discord link and not here.
 *
 * <p>Free of Bukkit, and the clock is passed in, so the counting is unit tested.
 */
final class PvpFarmGuard {
    /** Two players, whichever way round they were named. */
    record Pair(UUID low, UUID high) {
        static Pair of(UUID first, UUID second) {
            return first.compareTo(second) <= 0
                    ? new Pair(first, second)
                    : new Pair(second, first);
        }
    }

    /** One player's current run of fights against the same opponent. */
    private record Run(UUID opponent, int length) {
    }

    private final Map<UUID, Run> runs = new HashMap<>();
    private final Map<Pair, Long> restingUntil = new HashMap<>();

    /**
     * Records a finished fight and reports whether it started a rest.
     *
     * <p>Counted per player rather than per pair, so a player who goes off and fights
     * somebody else starts a fresh run — the measure is consecutive fights, not fights
     * ever, and a long-running rivalry with other fights in between is not farming.
     *
     * @param limit consecutive fights against one opponent before a rest begins
     * @param restMillis how long that pairing then waits
     * @return true when this fight was the one that tripped the limit
     */
    boolean recordFight(UUID first, UUID second, long now, int limit, long restMillis) {
        if (first.equals(second) || limit <= 0) {
            return false;
        }
        int firstRun = extend(first, second);
        int secondRun = extend(second, first);
        if (Math.max(firstRun, secondRun) < limit) {
            return false;
        }
        // The run is spent, not merely paused: after the rest they start from one
        // again rather than tripping the limit on their very next fight.
        runs.remove(first);
        runs.remove(second);
        restingUntil.put(Pair.of(first, second), now + Math.max(0L, restMillis));
        return restMillis > 0L;
    }

    /** Milliseconds before these two may fight each other again, or 0. */
    long restRemaining(UUID first, UUID second, long now) {
        Long until = restingUntil.get(Pair.of(first, second));
        if (until == null) {
            return 0L;
        }
        if (until <= now) {
            restingUntil.remove(Pair.of(first, second));
            return 0L;
        }
        return until - now;
    }

    /** How many fights in a row this player has had against that opponent. */
    int runLength(UUID player, UUID opponent) {
        Run run = runs.get(player);
        return run != null && run.opponent().equals(opponent) ? run.length() : 0;
    }

    /**
     * Deliberately no per-player forget: a rest that a relog cleared would not be a
     * rest at all. Expired pairings are dropped as they are read, and one entry per
     * player is not an amount of memory worth a bypass.
     */
    void clear() {
        runs.clear();
        restingUntil.clear();
    }

    private int extend(UUID player, UUID opponent) {
        Run run = runs.get(player);
        int length = run != null && run.opponent().equals(opponent) ? run.length() + 1 : 1;
        runs.put(player, new Run(opponent, length));
        return length;
    }
}
