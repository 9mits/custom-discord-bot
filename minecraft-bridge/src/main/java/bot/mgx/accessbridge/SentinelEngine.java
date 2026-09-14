package bot.mgx.accessbridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Sentinel's accounting core: where valuables came from, and whether a gain adds up.
 *
 * <p>Free of Bukkit so every rule is unit tested. The service feeds it two things: a
 * census of what each online player is holding, and credits for every legitimate way a
 * valuable can arrive (a plugin mint, a pickup, a chest they opened, an auction claim).
 * A gain above the player's own recent peak that the credits cannot account for is the
 * signature of a duplication, a creative copy, or an edited inventory — the case where
 * one player was sitting on 1,401 Shards the reward code never issued.
 *
 * <p>The peak is what keeps ordinary play quiet: items taken away and handed back
 * (a PvP match, screenshot mode, a trip to a chest) never exceed what the player already
 * had, so they never need explaining.
 */
final class SentinelEngine {
    /** What a finding is worth to the risk score, and how loudly it is reported. */
    enum Severity {
        LOW(1), MEDIUM(4), HIGH(12), CRITICAL(30);

        final int weight;

        Severity(int weight) {
            this.weight = weight;
        }

        Severity raised() {
            return this == CRITICAL ? CRITICAL : values()[ordinal() + 1];
        }
    }

    /** The valuables Sentinel counts. Vanilla metals are counted in their smallest unit. */
    enum Kind {
        SHARD("Shards"),
        MYSTERY_KEY("Mystery Keys"),
        AMETHYST_TOKEN("Amethyst Tokens"),
        COSMETIC("cosmetic tokens"),
        /** Quarter-ingots, so scrap (1) and blocks (36) compare with ingots (4). */
        NETHERITE("netherite (quarter ingots)"),
        /** Single diamonds, so blocks (9) and diamonds compare. */
        DIAMOND("diamonds");

        final String label;

        Kind(String label) {
            this.label = label;
        }

        String key() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        /** Vanilla metals have many legitimate sources, so they never alarm above MEDIUM. */
        boolean vanilla() {
            return this == NETHERITE || this == DIAMOND;
        }

        /** Human amount, converting the stored unit back. */
        String amount(long units) {
            if (this == NETHERITE) return String.format(Locale.ROOT, "%,.2f ingots", units / 4d);
            return String.format(Locale.ROOT, "%,d %s", units, label);
        }
    }

    record Finding(
            String rule, Severity severity, UUID player, String playerName,
            String title, List<String> evidence
    ) {
        Finding {
            evidence = List.copyOf(evidence);
        }
    }

    /** Tunables, read from settings by the service each census. */
    record Config(
            long windowMillis,
            Map<Kind, Long> thresholds,
            int largeGainMultiple
    ) {
        long threshold(Kind kind) {
            return Math.max(1L, thresholds.getOrDefault(kind, Long.MAX_VALUE / 4));
        }
    }

    private record Snapshot(long at, EnumMap<Kind, Long> holdings) { }

    private static final class Credit {
        long amount;
        final String source;
        final long expiresAt;

        Credit(long amount, String source, long expiresAt) {
            this.amount = amount;
            this.source = source;
            this.expiresAt = expiresAt;
        }
    }

    private static final class PlayerState {
        final Deque<Snapshot> history = new ArrayDeque<>();
        final Map<Kind, Deque<Credit>> credits = new EnumMap<>(Kind.class);
        EnumMap<Kind, Long> lastKnown;
    }

    private final Map<UUID, PlayerState> players = new HashMap<>();
    /** Mints with no known recipient, such as an item built by a reward factory. */
    private final Map<Kind, Deque<Credit>> pool = new EnumMap<>(Kind.class);

    /** Credits a legitimate arrival. A null player credits the shared mint pool. */
    synchronized void credit(UUID player, Kind kind, long amount, String source, long now, long ttlMillis) {
        if (amount <= 0L || kind == null) return;
        Map<Kind, Deque<Credit>> target = player == null ? pool : state(player).credits;
        target.computeIfAbsent(kind, ignored -> new ArrayDeque<>())
                .addLast(new Credit(amount, source == null ? "unknown" : source, now + ttlMillis));
    }

    synchronized void restoreLastKnown(UUID player, EnumMap<Kind, Long> holdings) {
        state(player).lastKnown = new EnumMap<>(holdings);
    }

    synchronized EnumMap<Kind, Long> lastKnown(UUID player) {
        PlayerState state = players.get(player);
        return state == null || state.lastKnown == null ? null : new EnumMap<>(state.lastKnown);
    }

    /** Forgets the in-session history; the last census survives as the rejoin baseline. */
    synchronized void quit(UUID player) {
        PlayerState state = players.get(player);
        if (state == null) return;
        state.history.clear();
        state.credits.clear();
    }

    /** Compares one player's holdings with their baseline and the credits that explain gains. */
    synchronized List<Finding> census(
            UUID player, String name, EnumMap<Kind, Long> holdings, long now, Config config
    ) {
        PlayerState state = state(player);
        expire(state.credits, now);
        expire(pool, now);
        while (!state.history.isEmpty() && now - state.history.peekFirst().at() > config.windowMillis()) {
            state.history.removeFirst();
        }
        List<Finding> findings = new ArrayList<>();
        boolean firstSighting = state.history.isEmpty() && state.lastKnown == null;
        if (firstSighting) {
            // Nothing to compare with yet, which is exactly when an old hoard slips past.
            // A first sighting far above any threshold is worth a look on its own.
            for (Kind kind : Kind.values()) {
                long current = holdings.getOrDefault(kind, 0L);
                if (!kind.vanilla() && current >= config.threshold(kind) * 20L) {
                    findings.add(new Finding("large_holdings", Severity.MEDIUM, player, name,
                            "Large existing " + kind.label + " holdings",
                            List.of("First check found " + kind.amount(current),
                                    "Sentinel has no history for this player yet, so the source is unknown")));
                }
            }
        } else {
            for (Kind kind : Kind.values()) {
                long current = holdings.getOrDefault(kind, 0L);
                long baseline = baseline(state, kind);
                long gain = current - baseline;
                if (gain <= 0L) continue;
                Map<String, Long> sources = new LinkedHashMap<>();
                long remaining = consume(state.credits.get(kind), gain, sources);
                remaining = consume(pool.get(kind), remaining, sources);
                long explained = gain - remaining;
                long threshold = config.threshold(kind);
                if (remaining >= threshold) {
                    Severity severity = kind.vanilla() ? Severity.MEDIUM
                            : remaining >= threshold * 10L ? Severity.CRITICAL : Severity.HIGH;
                    List<String> evidence = new ArrayList<>();
                    evidence.add("Holding " + kind.amount(current) + ", up from a recent peak of "
                            + kind.amount(baseline));
                    evidence.add("Unexplained: " + kind.amount(remaining));
                    if (explained > 0L) evidence.add("Explained: " + describe(kind, sources));
                    evidence.add("No mint, pickup, container, trade or claim accounts for the rest");
                    findings.add(new Finding("unexplained_gain", severity, player, name,
                            "Unexplained " + kind.label + " gain", evidence));
                } else if (!kind.vanilla() && explained >= threshold * Math.max(2, config.largeGainMultiple())) {
                    findings.add(new Finding("large_gain", Severity.MEDIUM, player, name,
                            "Large " + kind.label + " gain",
                            List.of("Received " + kind.amount(explained) + " in one census window",
                                    "Sources: " + describe(kind, sources))));
                }
            }
        }
        state.history.addLast(new Snapshot(now, new EnumMap<>(holdings)));
        state.lastKnown = new EnumMap<>(holdings);
        return findings;
    }

    private static long baseline(PlayerState state, Kind kind) {
        long peak = Long.MIN_VALUE;
        for (Snapshot snapshot : state.history) {
            peak = Math.max(peak, snapshot.holdings().getOrDefault(kind, 0L));
        }
        if (peak == Long.MIN_VALUE) {
            return state.lastKnown == null ? 0L : state.lastKnown.getOrDefault(kind, 0L);
        }
        return peak;
    }

    private static long consume(Deque<Credit> credits, long needed, Map<String, Long> sources) {
        if (credits == null || needed <= 0L) return Math.max(0L, needed);
        Iterator<Credit> iterator = credits.iterator();
        while (needed > 0L && iterator.hasNext()) {
            Credit credit = iterator.next();
            long used = Math.min(needed, credit.amount);
            credit.amount -= used;
            needed -= used;
            sources.merge(credit.source, used, Long::sum);
            if (credit.amount <= 0L) iterator.remove();
        }
        return needed;
    }

    private static void expire(Map<Kind, Deque<Credit>> credits, long now) {
        for (Deque<Credit> queue : credits.values()) {
            queue.removeIf(credit -> credit.expiresAt <= now || credit.amount <= 0L);
        }
    }

    private static String describe(Kind kind, Map<String, Long> sources) {
        List<String> parts = new ArrayList<>();
        sources.forEach((source, amount) -> parts.add(kind.amount(amount) + " from " + source));
        return String.join("; ", parts);
    }

    private PlayerState state(UUID player) {
        return players.computeIfAbsent(player, ignored -> new PlayerState());
    }

    // ------------------------------------------------------------------ risk

    /** A per-player score that halves every few hours, so one old mistake fades. */
    static final class RiskLedger {
        private record Score(double value, long at) { }

        private final Map<UUID, Score> scores = new HashMap<>();
        private final long halfLifeMillis;

        RiskLedger(long halfLifeMillis) {
            this.halfLifeMillis = Math.max(1L, halfLifeMillis);
        }

        synchronized double add(UUID player, Severity severity, long now) {
            double next = score(player, now) + severity.weight;
            scores.put(player, new Score(next, now));
            return next;
        }

        synchronized double score(UUID player, long now) {
            Score score = scores.get(player);
            if (score == null) return 0d;
            return score.value() * Math.pow(0.5d, (now - score.at()) / (double) halfLifeMillis);
        }

        synchronized Map<UUID, double[]> export() {
            Map<UUID, double[]> out = new LinkedHashMap<>();
            scores.forEach((id, score) -> out.put(id, new double[]{score.value(), score.at()}));
            return out;
        }

        synchronized void restore(UUID player, double value, long at) {
            scores.put(player, new Score(value, at));
        }
    }

    /** Repeats of the same finding within the cooldown are counted, not re-sent. */
    static final class Deduper {
        private final Map<String, long[]> seen = new HashMap<>();

        /** Returns 0 when the finding should be suppressed, else how many times it occurred. */
        synchronized int admit(String key, long now, long cooldownMillis) {
            long[] entry = seen.get(key);
            if (entry != null && now - entry[0] < cooldownMillis) {
                entry[1]++;
                return 0;
            }
            int occurrences = entry == null ? 1 : (int) Math.max(1L, entry[1] + 1L);
            seen.put(key, new long[]{now, 0L});
            seen.entrySet().removeIf(row -> now - row.getValue()[0] > cooldownMillis * 4L);
            return occurrences;
        }
    }
}
