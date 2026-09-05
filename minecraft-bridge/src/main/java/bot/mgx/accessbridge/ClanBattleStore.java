package bot.mgx.accessbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistent, reusable clan events whose score belongs to the member's current clan stay. */
final class ClanBattleStore {
    static final String GALACTIC_CONQUEST_ID = "galactic_conquest";
    private static final int FORMAT_VERSION = 1;
    private static final int GOLD_SHARDS = 10;
    private static final int SILVER_SHARDS = 5;
    private static final int BRONZE_SHARDS = 3;
    /** Live tuning; the constants above stay the defaults and stand alone in tests. */
    private static volatile java.util.function.ToDoubleFunction<String> tuning = key -> Double.NaN;

    static void tuningSource(java.util.function.ToDoubleFunction<String> source) {
        if (source != null) {
            tuning = source;
        }
    }

    private static double tuned(String key, double fallback) {
        double value = tuning.applyAsDouble(key);
        return Double.isNaN(value) ? fallback : value;
    }


    enum Kind {
        CRATES("crates", "Crates Clan Battle", "Open the most crates!"),
        DRAGON_EGGS("dragon-eggs", "Amethyst Dragon Egg Clan Battle",
                "Claim the most Amethyst Dragon Eggs!");

        private final String id;
        private final String displayName;
        private final String objective;

        Kind(String id, String displayName, String objective) {
            this.id = id;
            this.displayName = displayName;
            this.objective = objective;
        }

        String id() {
            return id;
        }

        String displayName() {
            return displayName;
        }

        String objective() {
            return objective;
        }

        static Optional<Kind> from(String raw) {
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            for (Kind kind : values()) {
                if (kind.id.equalsIgnoreCase(raw.strip())
                        || kind.name().equalsIgnoreCase(raw.strip())) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    record Badges(int gold, int silver, int bronze) {
        Badges {
            gold = Math.max(0, gold);
            silver = Math.max(0, silver);
            bronze = Math.max(0, bronze);
        }

        boolean empty() {
            return gold == 0 && silver == 0 && bronze == 0;
        }

        String compact() {
            List<String> parts = new ArrayList<>();
            add(parts, "G", gold);
            add(parts, "S", silver);
            add(parts, "B", bronze);
            return String.join(" ", parts);
        }

        List<String> lore() {
            if (empty()) {
                return List.of("Clan Battle badges: none yet.");
            }
            List<String> lines = new ArrayList<>();
            lines.add("Clan Battle badges:");
            add(lines, "  Gold", gold);
            add(lines, "  Silver", silver);
            add(lines, "  Bronze", bronze);
            return List.copyOf(lines);
        }

        private static void add(List<String> target, String label, int count) {
            if (count > 0) {
                target.add(label + (count == 1 ? "" : " x" + count));
            }
        }
    }

    record Standing(
            int rank,
            UUID clanId,
            String clanName,
            int colour,
            int level,
            long score,
            List<UUID> members
    ) {
    }

    record ActiveView(
            UUID id, Kind kind, long startedAt, long endsAt, List<Standing> standings
    ) {
        boolean expired(long now) {
            return now >= endsAt;
        }
    }

    record CompletedView(UUID id, Kind kind, long startedAt, long endedAt, List<Standing> winners) {
    }

    record ShardGrant(UUID grantId, int amount, String source) {
    }

    private static final class SavedState {
        int version = FORMAT_VERSION;
        SavedActive active;
        List<SavedCompleted> completed = new ArrayList<>();
        Map<String, SavedBadges> badges = new LinkedHashMap<>();
        Map<String, List<SavedShardGrant>> shardGrants = new LinkedHashMap<>();
        List<String> completedRewardSets = new ArrayList<>();
    }

    private static final class SavedActive {
        String id;
        String kind;
        long startedAt;
        long endsAt;
        Map<String, SavedContribution> contributions = new LinkedHashMap<>();
    }

    private static final class SavedContribution {
        String clanId;
        long joinedAt;
        long score;
    }

    private static final class SavedCompleted {
        String id;
        String kind;
        long startedAt;
        long endedAt;
        List<SavedWinner> winners = new ArrayList<>();
    }

    private static final class SavedWinner {
        int rank;
        String clanId;
        String clanName;
        int colour;
        int level;
        long score;
        List<String> members = new ArrayList<>();
    }

    private static final class SavedBadges {
        int gold;
        int silver;
        int bronze;
    }

    private static final class SavedShardGrant {
        String id;
        int amount;
        String source;
    }

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private SavedState state;
    private Runnable observer = () -> { };

    ClanBattleStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        state = load(file);
    }

    synchronized void onChange(Runnable observer) {
        this.observer = observer == null ? () -> { } : observer;
    }

    synchronized ActiveView start(Kind kind, long now, long endsAt, ClanStore clans) {
        if (kind == null) {
            throw new IllegalArgumentException("Choose a clan battle type.");
        }
        if (endsAt <= now) {
            throw new IllegalArgumentException("That clan battle deadline has already passed.");
        }
        if (state.active != null) {
            throw new IllegalArgumentException(activeView(clans).kind().displayName()
                    + " is already running.");
        }
        SavedState before = copyState();
        SavedActive active = new SavedActive();
        active.id = UUID.randomUUID().toString();
        active.kind = kind.id();
        active.startedAt = now;
        active.endsAt = endsAt;
        state.active = active;
        persistOrRestore(before);
        return activeView(clans);
    }

    synchronized Optional<ActiveView> active(ClanStore clans) {
        return state.active == null ? Optional.empty() : Optional.of(activeView(clans));
    }

    /** Retires the previous crate race and starts the Dragon Egg battle with a clean score. */
    synchronized boolean ensureDragonEggBattle(long now, long endsAt) {
        if (endsAt <= now || (state.active != null && kindOf(state.active) == Kind.DRAGON_EGGS)) return false;
        if (state.active != null && kindOf(state.active) != Kind.CRATES) return false;
        SavedState before = copyState();
        SavedActive replacement = new SavedActive();
        replacement.id = UUID.randomUUID().toString();
        replacement.kind = Kind.DRAGON_EGGS.id();
        replacement.startedAt = now;
        replacement.endsAt = endsAt;
        state.active = replacement;
        persistOrRestore(before);
        return true;
    }

    synchronized long recordCrate(UUID playerId, long now, ClanStore clans) {
        if (state.active == null || kindOf(state.active) != Kind.CRATES) {
            return 0L;
        }
        // The sweep that ends an expired battle runs on a tick; without this an
        // opening in that gap would score after the deadline players were shown.
        if (now >= state.active.endsAt) {
            return 0L;
        }
        ClanStore.ClanView clan = clans.clanOf(playerId).orElse(null);
        if (clan == null) {
            return 0L;
        }
        long joinedAt = clan.joinedAt().getOrDefault(playerId, 0L);
        SavedContribution priorContribution = state.active.contributions.get(playerId.toString());
        long previous = valid(priorContribution, clan, joinedAt) ? priorContribution.score : 0L;
        SavedState before = copyState();
        SavedContribution contribution = new SavedContribution();
        contribution.clanId = clan.id().toString();
        contribution.joinedAt = joinedAt;
        contribution.score = Math.addExact(previous, 1L);
        state.active.contributions.put(playerId.toString(), contribution);
        persistOrRestore(before);
        return contribution.score;
    }

    synchronized long recordDragonEgg(UUID playerId, long now, ClanStore clans) {
        if (state.active == null || kindOf(state.active) != Kind.DRAGON_EGGS || now >= state.active.endsAt) {
            return 0L;
        }
        ClanStore.ClanView clan = clans.clanOf(playerId).orElse(null);
        if (clan == null) return 0L;
        long joinedAt = clan.joinedAt().getOrDefault(playerId, 0L);
        SavedContribution prior = state.active.contributions.get(playerId.toString());
        long previous = valid(prior, clan, joinedAt) ? prior.score : 0L;
        SavedState before = copyState();
        SavedContribution contribution = new SavedContribution();
        contribution.clanId = clan.id().toString();
        contribution.joinedAt = joinedAt;
        contribution.score = Math.addExact(previous, 1L);
        state.active.contributions.put(playerId.toString(), contribution);
        persistOrRestore(before);
        return contribution.score;
    }

    synchronized CompletedView end(ClanStore clans, long now) {
        if (state.active == null) {
            throw new IllegalArgumentException("No clan battle is running.");
        }
        SavedState before = copyState();
        SavedActive active = state.active;
        List<Standing> standings = standings(active, clans);
        List<Standing> winners = standings.stream().filter(row -> row.rank() <= 3).toList();

        SavedCompleted completed = new SavedCompleted();
        completed.id = active.id;
        completed.kind = active.kind;
        completed.startedAt = active.startedAt;
        completed.endedAt = now;
        for (Standing winner : winners) {
            completed.winners.add(saveWinner(winner));
            addBadge(winner.clanId(), winner.rank());
            int shards = shardReward(kindOf(active), winner.rank());
            for (UUID member : winner.members()) {
                addShardGrant(
                        member,
                        UUID.nameUUIDFromBytes(("clan-battle-shards:" + active.id + ":" + member)
                                .getBytes(StandardCharsets.UTF_8)),
                        shards,
                        kindOf(active).displayName()
                );
            }
        }
        state.completed.add(completed);
        state.active = null;
        persistOrRestore(before);
        return completedView(completed);
    }

    synchronized void cancel() {
        if (state.active == null) {
            throw new IllegalArgumentException("No clan battle is running.");
        }
        SavedState before = copyState();
        state.active = null;
        persistOrRestore(before);
    }

    synchronized List<CompletedView> completed() {
        return state.completed.stream().map(this::completedView).toList();
    }

    synchronized Badges badges(UUID clanId) {
        SavedBadges saved = state.badges.get(clanId.toString());
        return saved == null ? new Badges(0, 0, 0)
                : new Badges(saved.gold, saved.silver, saved.bronze);
    }

    synchronized List<ShardGrant> shardGrants(UUID playerId) {
        return state.shardGrants.getOrDefault(playerId.toString(), List.of()).stream()
                .map(saved -> new ShardGrant(
                        UUID.fromString(saved.id), saved.amount, saved.source == null ? "Clan Battle" : saved.source
                ))
                .toList();
    }

    synchronized boolean completeShardGrant(UUID playerId, UUID grantId) {
        List<SavedShardGrant> grants = state.shardGrants.get(playerId.toString());
        if (grants == null) {
            return false;
        }
        List<SavedShardGrant> before = new ArrayList<>(grants);
        boolean removed = grants.removeIf(grant -> grant.id.equals(grantId.toString()));
        if (!removed) {
            return false;
        }
        if (grants.isEmpty()) {
            state.shardGrants.remove(playerId.toString());
        }
        try {
            save();
        } catch (RuntimeException exception) {
            state.shardGrants.put(playerId.toString(), before);
            throw exception;
        }
        return true;
    }

    /** Queues one exactly-once set of individual leaderboard rewards. */
    synchronized boolean queueShardRewardsOnce(
            String rewardSet, Map<UUID, Integer> rewards, String source
    ) {
        if (rewardSet == null || rewardSet.isBlank()
                || rewards == null || rewards.isEmpty()
                || state.completedRewardSets.contains(rewardSet)) {
            return false;
        }
        SavedState before = copyState();
        rewards.forEach((playerId, amount) -> addShardGrant(
                playerId,
                UUID.nameUUIDFromBytes(("individual-leaderboard-shards:"
                        + rewardSet + ":" + playerId).getBytes(StandardCharsets.UTF_8)),
                amount,
                source
        ));
        state.completedRewardSets.add(rewardSet);
        persistOrRestore(before);
        return true;
    }

    synchronized int clearAll() {
        int count = (state.active == null ? 0 : 1)
                + state.completed.size() + state.badges.size() + state.shardGrants.size()
                + state.completedRewardSets.size();
        SavedState before = state;
        state = new SavedState();
        persistOrRestore(before);
        return count;
    }

    private ActiveView activeView(ClanStore clans) {
        return new ActiveView(
                UUID.fromString(state.active.id), kindOf(state.active), state.active.startedAt,
                state.active.endsAt, standings(state.active, clans)
        );
    }

    private List<Standing> standings(SavedActive active, ClanStore clans) {
        Map<UUID, Long> totals = new LinkedHashMap<>();
        for (Map.Entry<String, SavedContribution> entry : active.contributions.entrySet()) {
            UUID playerId;
            try {
                playerId = UUID.fromString(entry.getKey());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            ClanStore.ClanView clan = clans.clanOf(playerId).orElse(null);
            long joinedAt = clan == null ? -1L : clan.joinedAt().getOrDefault(playerId, 0L);
            if (!valid(entry.getValue(), clan, joinedAt)) {
                continue;
            }
            totals.merge(clan.id(), Math.max(0L, entry.getValue().score), Math::addExact);
        }
        List<Standing> sorted = new ArrayList<>();
        totals.forEach((clanId, score) -> clans.findClanById(clanId).ifPresent(clan -> sorted.add(
                new Standing(
                        0, clan.id(), clan.name(), clan.themeColor(), clan.level(), score,
                        clan.members().keySet().stream().sorted().toList()
                )
        )));
        sorted.sort(Comparator.comparingLong(Standing::score).reversed()
                .thenComparing(Standing::clanName, String.CASE_INSENSITIVE_ORDER));
        List<Standing> ranked = new ArrayList<>();
        long previousScore = Long.MIN_VALUE;
        int rank = 0;
        for (int index = 0; index < sorted.size(); index++) {
            Standing row = sorted.get(index);
            if (row.score() != previousScore) {
                rank = index + 1;
                previousScore = row.score();
            }
            ranked.add(new Standing(
                    rank, row.clanId(), row.clanName(), row.colour(), row.level(),
                    row.score(), row.members()
            ));
        }
        return List.copyOf(ranked);
    }

    private static boolean valid(
            SavedContribution contribution, ClanStore.ClanView clan, long joinedAt
    ) {
        return contribution != null && clan != null
                && contribution.clanId.equals(clan.id().toString())
                && contribution.joinedAt == joinedAt;
    }

    private void addBadge(UUID clanId, int rank) {
        SavedBadges badges = state.badges.computeIfAbsent(
                clanId.toString(), ignored -> new SavedBadges()
        );
        switch (rank) {
            case 1 -> badges.gold = Math.addExact(badges.gold, 1);
            case 2 -> badges.silver = Math.addExact(badges.silver, 1);
            case 3 -> badges.bronze = Math.addExact(badges.bronze, 1);
            default -> { }
        }
    }

    private void addShardGrant(UUID playerId, UUID grantId, int amount, String source) {
        if (amount <= 0) {
            return;
        }
        List<SavedShardGrant> grants = state.shardGrants.computeIfAbsent(
                playerId.toString(), ignored -> new ArrayList<>()
        );
        if (grants.stream().anyMatch(grant -> grant.id.equals(grantId.toString()))) {
            return;
        }
        SavedShardGrant grant = new SavedShardGrant();
        grant.id = grantId.toString();
        grant.amount = amount;
        grant.source = source;
        grants.add(grant);
    }

    static int shardReward(Kind kind, int rank) {
        if (kind == Kind.DRAGON_EGGS) {
            return switch (rank) {
                case 1 -> (int) tuned("clan-battle.dragon.first-shards", 15);
                case 2 -> (int) tuned("clan-battle.dragon.second-shards", 10);
                case 3 -> (int) tuned("clan-battle.dragon.third-shards", 5);
                default -> 0;
            };
        }
        return switch (rank) {
            case 1 -> (int) tuned("clan-battle.gold-shards", GOLD_SHARDS);
            case 2 -> (int) tuned("clan-battle.silver-shards", SILVER_SHARDS);
            case 3 -> (int) tuned("clan-battle.bronze-shards", BRONZE_SHARDS);
            default -> 0;
        };
    }

    private static SavedWinner saveWinner(Standing standing) {
        SavedWinner saved = new SavedWinner();
        saved.rank = standing.rank();
        saved.clanId = standing.clanId().toString();
        saved.clanName = standing.clanName();
        saved.colour = standing.colour();
        saved.level = standing.level();
        saved.score = standing.score();
        saved.members = standing.members().stream().map(UUID::toString).toList();
        return saved;
    }

    private CompletedView completedView(SavedCompleted saved) {
        List<Standing> winners = saved.winners.stream().map(winner -> new Standing(
                winner.rank,
                UUID.fromString(winner.clanId),
                winner.clanName,
                winner.colour,
                winner.level,
                winner.score,
                winner.members.stream().map(UUID::fromString).toList()
        )).toList();
        return new CompletedView(
                UUID.fromString(saved.id), Kind.from(saved.kind).orElse(Kind.CRATES),
                saved.startedAt, saved.endedAt, winners
        );
    }

    private static Kind kindOf(SavedActive active) {
        return Kind.from(active.kind).orElse(Kind.CRATES);
    }

    private SavedState load(Path source) throws IOException {
        if (!Files.isRegularFile(source) || Files.size(source) == 0L) {
            return new SavedState();
        }
        try {
            SavedState loaded = gson.fromJson(Files.readString(source, StandardCharsets.UTF_8), SavedState.class);
            if (loaded == null || loaded.version != FORMAT_VERSION) {
                throw new IOException("Unsupported clan-battles.json format version");
            }
            if (loaded.completed == null) {
                loaded.completed = new ArrayList<>();
            }
            if (loaded.badges == null) {
                loaded.badges = new LinkedHashMap<>();
            }
            if (loaded.shardGrants == null) {
                loaded.shardGrants = new LinkedHashMap<>();
            }
            if (loaded.completedRewardSets == null) {
                loaded.completedRewardSets = new ArrayList<>();
            }
            if (loaded.active != null && loaded.active.contributions == null) {
                loaded.active.contributions = new LinkedHashMap<>();
            }
            return loaded;
        } catch (RuntimeException exception) {
            throw new IOException("Clan battle store is unreadable", exception);
        }
    }

    private void persistOrRestore(SavedState before) {
        try {
            save();
        } catch (RuntimeException exception) {
            state = before;
            throw exception;
        }
        observer.run();
    }

    private SavedState copyState() {
        return gson.fromJson(gson.toJson(state), SavedState.class);
    }

    private void save() {
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, gson.toJson(state), StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary, file,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
