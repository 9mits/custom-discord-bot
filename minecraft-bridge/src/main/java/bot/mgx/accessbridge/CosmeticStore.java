package bot.mgx.accessbridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashMap;
import java.util.UUID;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Unique cosmetic-token custody and the token selected in each effect category.
 *
 * <p><b>Persistence is a snapshot plus a journal.</b> Every token ever minted lives here
 * (twelve thousand on the live server and only ever growing), and rewriting that whole
 * snapshot on every mint, equip and deposit cost roughly ten milliseconds of main thread
 * each time: a crate opening several cosmetics at once was a visible hitch. Each change
 * is now appended to {@code cosmetics.json.journal} as one line that sets absolute state,
 * written before the call returns, so a hard kill loses nothing it did not lose before.
 * The snapshot is rewritten only at startup, once the journal grows large, and for the
 * rare bulk operations that renumber many tokens at once.
 *
 * <p>A journal belongs to exactly one snapshot, named by {@code journal_epoch}. Rewriting
 * the snapshot advances the epoch before the old journal is deleted, so a crash between
 * the two can never replay an older journal over a newer snapshot.
 */
final class CosmeticStore {
    record Token(
            UUID serial, String cosmeticId, int generation, int serialNumber, UUID storedOwner
    ) {
        boolean stored() {
            return storedOwner != null;
        }
    }

    /** Journal size that triggers a snapshot rewrite: roughly ten thousand changes. */
    private static final long COMPACT_AT_BYTES = 2L * 1024L * 1024L;

    private final Path file;
    private final Path journal;
    private final LinkedHashMap<UUID, Token> tokens = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, LinkedHashMap<String, UUID>> equipped = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, LinkedHashMap<String, String>> leaderboardEquipped =
            new LinkedHashMap<>();
    private final Set<UUID> previewOwners = new HashSet<>();
    private int generation = 1;
    private long journalEpoch;
    private long journalBytes;
    private long compactAtBytes = COMPACT_AT_BYTES;

    /**
     * Per-cosmetic count and highest serial in the current generation, rebuilt lazily.
     *
     * <p>Minting used to scan every token to find the next serial, and every crate
     * preview and wardrobe tile asked for an existence count that scanned them again.
     */
    private final Map<String, int[]> stats = new HashMap<>();
    private boolean statsValid;
    /** Tokens held in each wardrobe, in custody order, rebuilt lazily. */
    private final Map<UUID, List<Token>> byOwner = new HashMap<>();
    private boolean ownersValid;

    CosmeticStore(Path file) throws IOException {
        this.file = file;
        this.journal = file.resolveSibling(file.getFileName() + ".journal");
        Files.createDirectories(file.getParent());
        boolean migrated = false;
        try {
            if (Files.isRegularFile(file) && Files.size(file) > 0L) {
                migrated = loadSnapshot(JsonParser.parseString(Files.readString(file)).getAsJsonObject());
            }
            boolean replayed = replayJournal();
            migrated |= migrateSecretCategory();
            if (migrated) {
                snapshot();
            } else if (replayed) {
                try {
                    snapshot();
                } catch (UncheckedIOException failure) {
                    // Everything is still in the journal; keep extending it.
                    journalBytes = Files.size(journal);
                }
            }
        } catch (RuntimeException exception) {
            throw new IOException("Cosmetic store is unreadable", exception);
        }
    }

    /** Reads the snapshot; true when legacy tokens were given serial numbers. */
    private boolean loadSnapshot(JsonObject root) {
        generation = Math.max(1, root.has("generation") ? root.get("generation").getAsInt() : 1);
        journalEpoch = root.has("journal_epoch") ? root.get("journal_epoch").getAsLong() : 0L;
        JsonObject savedTokens = object(root, "tokens");
        Map<String, Integer> highestSerials = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : savedTokens.entrySet()) {
            JsonObject value = entry.getValue().getAsJsonObject();
            if (value.has("serial_number")) {
                String cosmeticId = value.get("cosmetic_id").getAsString();
                int serialNumber = Math.max(0, value.get("serial_number").getAsInt());
                highestSerials.merge(cosmeticId, serialNumber, Math::max);
            }
        }
        boolean migratedSerialNumbers = false;
        for (Map.Entry<String, JsonElement> entry : savedTokens.entrySet()) {
            UUID serial = UUID.fromString(entry.getKey());
            JsonObject value = entry.getValue().getAsJsonObject();
            String cosmeticId = value.get("cosmetic_id").getAsString();
            int serialNumber;
            if (value.has("serial_number") && value.get("serial_number").getAsInt() > 0) {
                serialNumber = value.get("serial_number").getAsInt();
            } else {
                serialNumber = highestSerials.merge(cosmeticId, 1, Integer::sum);
                migratedSerialNumbers = true;
            }
            UUID owner = value.has("stored_owner")
                    ? UUID.fromString(value.get("stored_owner").getAsString())
                    : null;
            tokens.put(serial, new Token(
                    serial,
                    cosmeticId,
                    value.get("generation").getAsInt(),
                    serialNumber,
                    owner
            ));
        }
        JsonObject savedEquipped = object(root, "equipped");
        for (Map.Entry<String, JsonElement> entry : savedEquipped.entrySet()) {
            putEquipped(UUID.fromString(entry.getKey()), entry.getValue().getAsJsonObject());
        }
        JsonObject savedLeaderboardEquipped = object(root, "leaderboard_equipped");
        for (Map.Entry<String, JsonElement> entry : savedLeaderboardEquipped.entrySet()) {
            putLeaderboard(UUID.fromString(entry.getKey()), entry.getValue().getAsJsonObject());
        }
        return migratedSerialNumbers;
    }

    /**
     * Applies the journal written against the loaded snapshot.
     *
     * <p>A journal from an older epoch was already folded into this snapshot before a
     * crash could delete it, so it is discarded rather than replayed. A torn final line
     * from a crash mid-append is skipped: it never returned to its caller.
     *
     * @return whether anything was replayed
     */
    private boolean replayJournal() throws IOException {
        if (!Files.isRegularFile(journal)) {
            return false;
        }
        List<String> lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
        boolean applied = false;
        boolean current = false;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject op;
            try {
                op = JsonParser.parseString(line).getAsJsonObject();
            } catch (RuntimeException torn) {
                continue;
            }
            String kind = op.has("op") ? op.get("op").getAsString() : "";
            if (kind.equals("epoch")) {
                current = op.get("value").getAsLong() == journalEpoch;
                continue;
            }
            if (!current) {
                continue;
            }
            switch (kind) {
                case "token" -> {
                    UUID serial = UUID.fromString(op.get("serial").getAsString());
                    tokens.put(serial, new Token(
                            serial,
                            op.get("cosmetic_id").getAsString(),
                            op.get("generation").getAsInt(),
                            op.get("serial_number").getAsInt(),
                            op.has("stored_owner")
                                    ? UUID.fromString(op.get("stored_owner").getAsString())
                                    : null
                    ));
                }
                case "remove" -> tokens.remove(UUID.fromString(op.get("serial").getAsString()));
                case "equipped" -> {
                    UUID player = UUID.fromString(op.get("player").getAsString());
                    equipped.remove(player);
                    putEquipped(player, object(op, "selections"));
                }
                case "leaderboard" -> {
                    UUID player = UUID.fromString(op.get("player").getAsString());
                    leaderboardEquipped.remove(player);
                    putLeaderboard(player, object(op, "selections"));
                }
                default -> {
                    continue;
                }
            }
            applied = true;
        }
        if (!applied) {
            Files.deleteIfExists(journal);
        }
        return applied;
    }

    private void putEquipped(UUID player, JsonObject saved) {
        LinkedHashMap<String, UUID> selections = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> selected : saved.entrySet()) {
            selections.put(selected.getKey(), UUID.fromString(selected.getValue().getAsString()));
        }
        if (!selections.isEmpty()) {
            equipped.put(player, selections);
        }
    }

    private void putLeaderboard(UUID player, JsonObject saved) {
        LinkedHashMap<String, String> selections = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> selected : saved.entrySet()) {
            String cosmeticId = selected.getValue().getAsString();
            if (validLeaderboardSelection(selected.getKey(), cosmeticId)) {
                selections.put(selected.getKey(), cosmeticId);
            }
        }
        if (!selections.isEmpty()) {
            leaderboardEquipped.put(player, selections);
        }
    }

    /** Selections saved under the retired SECRET category move to their real one. */
    private boolean migrateSecretCategory() {
        boolean migrated = false;
        for (Map.Entry<UUID, LinkedHashMap<String, UUID>> entry : List.copyOf(equipped.entrySet())) {
            LinkedHashMap<String, UUID> selections = entry.getValue();
            UUID legacySecret = selections.remove("SECRET");
            if (legacySecret == null) {
                continue;
            }
            Token token = tokens.get(legacySecret);
            CosmeticCatalog.find(token == null ? null : token.cosmeticId()).ifPresent(
                    definition -> selections.put(definition.category().name(), legacySecret)
            );
            if (selections.isEmpty()) {
                equipped.remove(entry.getKey());
            }
            migrated = true;
        }
        return migrated;
    }

    synchronized int generation() {
        return generation;
    }

    synchronized Token mint(UUID owner, String cosmeticId, UUID serial) {
        CosmeticCatalog.find(cosmeticId)
                .filter(CosmeticCatalog.Definition::leaderboardOnly)
                .ifPresent(definition -> {
                    throw new IllegalArgumentException(
                            "Leaderboard cosmetics cannot be minted or traded."
                    );
                });
        if (previewOwners.contains(owner)) {
            return mintPreview(owner, cosmeticId, serial);
        }
        Token existing = tokens.get(serial);
        if (existing != null) {
            if (!existing.cosmeticId().equals(cosmeticId) || existing.generation() != generation) {
                throw new IllegalArgumentException("That cosmetic serial already means something else.");
            }
            return existing;
        }
        int serialNumber = stats().getOrDefault(cosmeticId, new int[2])[1] + 1;
        Token token = new Token(serial, cosmeticId, generation, serialNumber, owner);
        tokens.put(serial, token);
        try {
            append(List.of(tokenLine(token)));
        } catch (RuntimeException exception) {
            tokens.remove(serial);
            throw exception;
        }
        // Kept current rather than invalidated: minting is the common write.
        int[] row = stats.computeIfAbsent(cosmeticId, ignored -> new int[2]);
        row[0]++;
        row[1] = Math.max(row[1], serialNumber);
        if (ownersValid) {
            byOwner.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(token);
        }
        return token;
    }

    /** Creates a session-only wardrobe entry that never consumes or displays a serial number. */
    synchronized Token mintPreview(UUID owner, String cosmeticId) {
        return mintPreview(owner, cosmeticId, UUID.randomUUID());
    }

    private Token mintPreview(UUID owner, String cosmeticId, UUID serial) {
        Token existing = tokens.get(serial);
        if (existing != null) {
            return existing;
        }
        Token token = new Token(serial, cosmeticId, generation, 0, owner);
        tokens.put(serial, token);
        ownersValid = false;
        return token;
    }

    synchronized void beginPreview(UUID owner) {
        previewOwners.add(owner);
    }

    synchronized void endPreview(UUID owner) {
        previewOwners.remove(owner);
        clearPreviews(owner);
    }

    synchronized Optional<Token> token(UUID serial) {
        Token token = tokens.get(serial);
        return token == null || token.generation() != generation
                ? Optional.empty()
                : Optional.of(token);
    }

    synchronized List<Token> stored(UUID owner) {
        if (!ownersValid) {
            byOwner.clear();
            for (Token token : tokens.values()) {
                if (token.generation() == generation && token.storedOwner() != null) {
                    byOwner.computeIfAbsent(token.storedOwner(), ignored -> new ArrayList<>()).add(token);
                }
            }
            ownersValid = true;
        }
        List<Token> owned = byOwner.get(owner);
        return owned == null ? List.of() : List.copyOf(owned);
    }

    synchronized int inExistence(String cosmeticId) {
        if (cosmeticId == null || cosmeticId.isBlank()) {
            return 0;
        }
        int[] row = stats().get(cosmeticId);
        return row == null ? 0 : row[0];
    }

    /** How many real cosmetics exist, ignoring the session-only preview tokens. */
    synchronized int mintedCount() {
        int count = 0;
        for (int[] row : stats().values()) {
            count += row[0];
        }
        return count;
    }

    private Map<String, int[]> stats() {
        if (!statsValid) {
            stats.clear();
            for (Token token : tokens.values()) {
                if (token.generation() == generation && token.serialNumber() > 0) {
                    int[] row = stats.computeIfAbsent(token.cosmeticId(), ignored -> new int[2]);
                    row[0]++;
                    row[1] = Math.max(row[1], token.serialNumber());
                }
            }
            statsValid = true;
        }
        return stats;
    }

    /** Tokens were added, removed or renumbered in a way the indexes cannot follow. */
    private void tokensChanged() {
        statsValid = false;
        ownersValid = false;
    }

    synchronized Optional<Token> withdraw(UUID owner, UUID serial) {
        Token token = tokens.get(serial);
        if (token == null || token.generation() != generation || !owner.equals(token.storedOwner())) {
            return Optional.empty();
        }
        Token physical = new Token(
                serial, token.cosmeticId(), token.generation(), token.serialNumber(), null
        );
        tokens.put(serial, physical);
        ownersValid = false;
        try {
            append(List.of(tokenLine(physical)));
        } catch (RuntimeException exception) {
            tokens.put(serial, token);
            throw exception;
        }
        return Optional.of(physical);
    }

    synchronized boolean deposit(UUID owner, UUID serial, String cosmeticId, int tokenGeneration) {
        Token token = tokens.get(serial);
        if (token == null
                || token.generation() != generation
                || tokenGeneration != generation
                || !token.cosmeticId().equals(cosmeticId)
                || token.stored()) {
            return false;
        }
        Token deposited = new Token(serial, cosmeticId, generation, token.serialNumber(), owner);
        tokens.put(serial, deposited);
        ownersValid = false;
        try {
            append(List.of(tokenLine(deposited)));
        } catch (RuntimeException exception) {
            tokens.put(serial, token);
            throw exception;
        }
        return true;
    }

    synchronized boolean isStoredBy(UUID owner, UUID serial) {
        Token token = tokens.get(serial);
        return token != null
                && token.generation() == generation
                && owner.equals(token.storedOwner());
    }

    synchronized void equip(UUID playerId, String category, UUID serial) {
        Token token = tokens.get(serial);
        if (token == null || token.generation() != generation) {
            throw new IllegalArgumentException("That cosmetic token is no longer valid.");
        }
        LinkedHashMap<UUID, LinkedHashMap<String, UUID>> equippedBefore = copyEquipped();
        LinkedHashMap<UUID, LinkedHashMap<String, String>> leaderboardBefore =
                copyLeaderboardEquipped();
        Set<UUID> changed = new java.util.LinkedHashSet<>();
        equipped.entrySet().removeIf(entry -> {
            if (entry.getValue().entrySet().removeIf(selected -> serial.equals(selected.getValue()))) {
                changed.add(entry.getKey());
            }
            return entry.getValue().isEmpty();
        });
        LinkedHashMap<String, UUID> selections = equipped.computeIfAbsent(
                playerId, ignored -> new LinkedHashMap<>()
        );
        selections.put(category, serial);
        changed.add(playerId);
        removeLeaderboardSelection(playerId, category);
        try {
            List<String> lines = new ArrayList<>();
            changed.forEach(player -> lines.add(equippedLine(player)));
            lines.add(leaderboardLine(playerId));
            append(lines);
        } catch (RuntimeException exception) {
            equipped.clear();
            equipped.putAll(equippedBefore);
            leaderboardEquipped.clear();
            leaderboardEquipped.putAll(leaderboardBefore);
            throw exception;
        }
    }

    synchronized void equipLeaderboard(UUID playerId, String category, String cosmeticId) {
        if (!validLeaderboardSelection(category, cosmeticId)) {
            throw new IllegalArgumentException("That leaderboard cosmetic selection is invalid.");
        }
        LinkedHashMap<UUID, LinkedHashMap<String, UUID>> equippedBefore = copyEquipped();
        LinkedHashMap<UUID, LinkedHashMap<String, String>> leaderboardBefore =
                copyLeaderboardEquipped();
        removeTokenSelection(playerId, category);
        leaderboardEquipped.computeIfAbsent(playerId, ignored -> new LinkedHashMap<>())
                .put(category, cosmeticId);
        try {
            append(List.of(equippedLine(playerId), leaderboardLine(playerId)));
        } catch (RuntimeException exception) {
            equipped.clear();
            equipped.putAll(equippedBefore);
            leaderboardEquipped.clear();
            leaderboardEquipped.putAll(leaderboardBefore);
            throw exception;
        }
    }

    /**
     * Moves every cosmetic the loser had equipped into the winner's wardrobe and takes
     * it off the loser. Custody moves as one write: a half-applied transfer would either
     * duplicate a unique token or destroy one.
     *
     * @return the tokens that changed hands, in the order they were equipped
     */
    synchronized List<Token> transferEquipped(UUID from, UUID to) {
        LinkedHashMap<String, UUID> selections = equipped.get(from);
        if (selections == null || selections.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<UUID, Token> tokensBefore = new LinkedHashMap<>(tokens);
        LinkedHashMap<UUID, LinkedHashMap<String, UUID>> equippedBefore = copyEquipped();
        List<Token> moved = new ArrayList<>();
        for (UUID serial : List.copyOf(selections.values())) {
            Token token = tokens.get(serial);
            if (token == null
                    || token.generation() != generation
                    || token.serialNumber() <= 0
                    || !from.equals(token.storedOwner())) {
                continue;
            }
            Token handed = new Token(
                    serial, token.cosmeticId(), token.generation(), token.serialNumber(), to
            );
            tokens.put(serial, handed);
            moved.add(handed);
        }
        if (moved.isEmpty()) {
            return List.of();
        }
        equipped.remove(from);
        ownersValid = false;
        try {
            // One append, so the custody change and the cleared selection land together.
            List<String> lines = new ArrayList<>();
            moved.forEach(token -> lines.add(tokenLine(token)));
            lines.add(equippedLine(from));
            append(lines);
        } catch (RuntimeException exception) {
            tokens.clear();
            tokens.putAll(tokensBefore);
            equipped.clear();
            equipped.putAll(equippedBefore);
            throw exception;
        }
        return List.copyOf(moved);
    }

    synchronized Optional<UUID> equipped(UUID playerId, String category) {
        LinkedHashMap<String, UUID> selections = equipped.get(playerId);
        return selections == null ? Optional.empty() : Optional.ofNullable(selections.get(category));
    }

    synchronized Optional<String> leaderboardEquipped(UUID playerId, String category) {
        LinkedHashMap<String, String> selections = leaderboardEquipped.get(playerId);
        return selections == null ? Optional.empty() : Optional.ofNullable(selections.get(category));
    }

    synchronized boolean clearLeaderboardEquipped(
            UUID playerId, String category, String expectedCosmeticId
    ) {
        LinkedHashMap<String, String> selections = leaderboardEquipped.get(playerId);
        if (selections == null || !expectedCosmeticId.equals(selections.get(category))) {
            return false;
        }
        LinkedHashMap<UUID, LinkedHashMap<String, String>> before = copyLeaderboardEquipped();
        removeLeaderboardSelection(playerId, category);
        try {
            append(List.of(leaderboardLine(playerId)));
        } catch (RuntimeException exception) {
            leaderboardEquipped.clear();
            leaderboardEquipped.putAll(before);
            throw exception;
        }
        return true;
    }

    synchronized boolean clearEquipped(UUID playerId, String category, UUID expected) {
        LinkedHashMap<String, UUID> selections = equipped.get(playerId);
        if (selections == null || !expected.equals(selections.get(category))) {
            return false;
        }
        selections.remove(category);
        boolean removedPlayer = false;
        if (selections.isEmpty()) {
            equipped.remove(playerId);
            removedPlayer = true;
        }
        try {
            append(List.of(equippedLine(playerId)));
        } catch (RuntimeException exception) {
            if (removedPlayer) {
                equipped.put(playerId, selections);
            }
            selections.put(category, expected);
            throw exception;
        }
        return true;
    }

    synchronized int clearAll() {
        int cleared = tokens.size() + equipped.size() + leaderboardEquipped.size();
        int generationBefore = generation;
        LinkedHashMap<UUID, Token> tokensBefore = new LinkedHashMap<>(tokens);
        LinkedHashMap<UUID, LinkedHashMap<String, UUID>> equippedBefore = copyEquipped();
        LinkedHashMap<UUID, LinkedHashMap<String, String>> leaderboardBefore =
                copyLeaderboardEquipped();
        generation = generation == Integer.MAX_VALUE ? 1 : generation + 1;
        tokens.clear();
        equipped.clear();
        leaderboardEquipped.clear();
        previewOwners.clear();
        tokensChanged();
        try {
            snapshot();
        } catch (RuntimeException exception) {
            generation = generationBefore;
            tokens.putAll(tokensBefore);
            equipped.putAll(equippedBefore);
            leaderboardEquipped.putAll(leaderboardBefore);
            tokensChanged();
            throw exception;
        }
        return cleared;
    }

    /** Removes temporary cosmetics created while an operator was taking screenshots. */
    synchronized int clearPreviews(UUID owner) {
        List<UUID> removed = tokens.values().stream()
                .filter(token -> token.serialNumber() <= 0)
                .filter(token -> owner.equals(token.storedOwner()))
                .map(Token::serial)
                .toList();
        if (removed.isEmpty()) {
            return 0;
        }
        tokens.keySet().removeAll(removed);
        removeSelections(owner, removed, false);
        tokensChanged();
        return removed.size();
    }

    /** Deletes a player's virtual cosmetics and any carried serials supplied by the caller. */
    synchronized int deleteOwned(UUID owner, Collection<UUID> carriedSerials) {
        List<UUID> removed = tokens.values().stream()
                .filter(token -> token.generation() == generation)
                .filter(token -> owner.equals(token.storedOwner())
                        || carriedSerials.contains(token.serial()))
                .map(Token::serial)
                .toList();
        LinkedHashMap<UUID, Token> tokensBefore = new LinkedHashMap<>(tokens);
        LinkedHashMap<UUID, LinkedHashMap<String, UUID>> equippedBefore = copyEquipped();
        LinkedHashMap<UUID, LinkedHashMap<String, String>> leaderboardBefore =
                copyLeaderboardEquipped();
        tokens.keySet().removeAll(removed);
        removeSelections(owner, removed, true);
        tokensChanged();
        try {
            snapshot();
        } catch (RuntimeException exception) {
            tokens.clear();
            tokens.putAll(tokensBefore);
            equipped.clear();
            equipped.putAll(equippedBefore);
            leaderboardEquipped.clear();
            leaderboardEquipped.putAll(leaderboardBefore);
            tokensChanged();
            throw exception;
        }
        return removed.size();
    }

    /** Deletes a stored copy and closes its serial gap in the same durable write. */
    synchronized boolean deleteCopy(UUID owner, UUID serial) {
        Token removed = tokens.get(serial);
        if (removed == null || removed.generation() != generation
                || !owner.equals(removed.storedOwner()) || removed.serialNumber() <= 0) {
            return false;
        }
        LinkedHashMap<UUID, Token> before = new LinkedHashMap<>(tokens);
        LinkedHashMap<UUID, LinkedHashMap<String, UUID>> selections = copyEquipped();
        tokens.remove(serial);
        equipped.values().forEach(values -> values.values().removeIf(serial::equals));
        tokens.replaceAll((id, token) -> token.generation() == generation
                && token.cosmeticId().equals(removed.cosmeticId())
                && token.serialNumber() > removed.serialNumber()
                ? new Token(id, token.cosmeticId(), token.generation(),
                        token.serialNumber() - 1, token.storedOwner()) : token);
        tokensChanged();
        try {
            // Renumbering can touch thousands of tokens, which is a snapshot's job.
            snapshot();
        } catch (RuntimeException failure) {
            tokens.clear();
            tokens.putAll(before);
            equipped.clear();
            equipped.putAll(selections);
            tokensChanged();
            throw failure;
        }
        return true;
    }

    /** Renumbers one cosmetic from #1 without changing custody or equipped selections. */
    synchronized int resetSerials(String cosmeticId) {
        List<Token> matching = tokens.values().stream()
                .filter(token -> token.generation() == generation)
                .filter(token -> token.serialNumber() > 0)
                .filter(token -> token.cosmeticId().equals(cosmeticId))
                .sorted(Comparator.comparingInt(Token::serialNumber)
                        .thenComparing(token -> token.serial().toString()))
                .toList();
        if (matching.isEmpty()) {
            return 0;
        }
        LinkedHashMap<UUID, Token> before = new LinkedHashMap<>(tokens);
        for (int index = 0; index < matching.size(); index++) {
            Token token = matching.get(index);
            tokens.put(token.serial(), new Token(
                    token.serial(), token.cosmeticId(), token.generation(), index + 1, token.storedOwner()
            ));
        }
        tokensChanged();
        try {
            snapshot();
        } catch (RuntimeException exception) {
            tokens.clear();
            tokens.putAll(before);
            tokensChanged();
            throw exception;
        }
        return matching.size();
    }

    /** Folds the journal into a fresh snapshot now, rather than when it next grows large. */
    synchronized void flush() {
        snapshot();
    }

    // ------------------------------------------------------------------ journal

    /**
     * Appends lines to the journal before returning, so the change survives a hard kill.
     *
     * <p>A new journal opens with the epoch of the snapshot it extends. The whole batch
     * goes out in one write, so a multi-part change is never replayed half applied
     * except for a torn final line, which the loader skips.
     */
    private void append(List<String> lines) {
        StringBuilder batch = new StringBuilder();
        if (journalBytes == 0L) {
            batch.append("{\"op\":\"epoch\",\"value\":").append(journalEpoch).append("}\n");
        }
        for (String line : lines) {
            batch.append(line).append('\n');
        }
        byte[] bytes = batch.toString().getBytes(StandardCharsets.UTF_8);
        try {
            Files.write(journal, bytes, journalBytes == 0L
                    ? new StandardOpenOption[]{StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE}
                    : new StandardOpenOption[]{StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND, StandardOpenOption.WRITE});
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        journalBytes += bytes.length;
        if (journalBytes >= compactAtBytes) {
            try {
                snapshot();
                compactAtBytes = COMPACT_AT_BYTES;
            } catch (RuntimeException failure) {
                // The change is already durable in the journal. Try again later rather
                // than on every following write.
                compactAtBytes = journalBytes + COMPACT_AT_BYTES / 4L;
            }
        }
    }

    /** Rewrites the whole snapshot under a new epoch, then retires the journal. */
    private void snapshot() {
        long nextEpoch = journalEpoch + 1L;
        writeAtomically(snapshotJson(nextEpoch));
        journalEpoch = nextEpoch;
        journalBytes = 0L;
        try {
            Files.deleteIfExists(journal);
        } catch (IOException ignored) {
            // An old-epoch journal is ignored on load, and truncated by the next append.
        }
    }

    private static String tokenLine(Token token) {
        StringBuilder line = new StringBuilder(160).append("{\"op\":\"token\",\"serial\":");
        quote(line, token.serial().toString());
        appendTokenFields(line.append(','), token);
        return line.append('}').toString();
    }

    private String equippedLine(UUID playerId) {
        StringBuilder line = new StringBuilder(160).append("{\"op\":\"equipped\",\"player\":");
        quote(line, playerId.toString());
        line.append(",\"selections\":");
        appendSelections(line, equipped.get(playerId));
        return line.append('}').toString();
    }

    private String leaderboardLine(UUID playerId) {
        StringBuilder line = new StringBuilder(160).append("{\"op\":\"leaderboard\",\"player\":");
        quote(line, playerId.toString());
        line.append(",\"selections\":{");
        LinkedHashMap<String, String> selections = leaderboardEquipped.get(playerId);
        if (selections != null) {
            boolean first = true;
            for (Map.Entry<String, String> selected : selections.entrySet()) {
                if (!first) {
                    line.append(',');
                }
                first = false;
                quote(line, selected.getKey());
                line.append(':');
                quote(line, selected.getValue());
            }
        }
        return line.append("}}").toString();
    }

    /** Only persisted tokens are written: a preview selection lasts the session. */
    private void appendSelections(StringBuilder out, Map<String, UUID> selections) {
        out.append('{');
        if (selections != null) {
            boolean first = true;
            for (Map.Entry<String, UUID> selected : selections.entrySet()) {
                Token token = tokens.get(selected.getValue());
                if (token == null || token.serialNumber() <= 0) {
                    continue;
                }
                if (!first) {
                    out.append(',');
                }
                first = false;
                quote(out, selected.getKey());
                out.append(':');
                quote(out, selected.getValue().toString());
            }
        }
        out.append('}');
    }

    private static void appendTokenFields(StringBuilder out, Token token) {
        out.append("\"cosmetic_id\":");
        quote(out, token.cosmeticId());
        out.append(",\"generation\":").append(token.generation())
                .append(",\"serial_number\":").append(token.serialNumber());
        if (token.storedOwner() != null) {
            out.append(",\"stored_owner\":");
            quote(out, token.storedOwner().toString());
        }
    }

    /**
     * The snapshot, written straight to text.
     *
     * <p>Building a Gson tree of twelve thousand objects first and then printing it was
     * most of what a save cost.
     */
    private String snapshotJson(long epoch) {
        StringBuilder out = new StringBuilder(Math.max(1_024, tokens.size() * 150));
        out.append("{\"generation\":").append(generation)
                .append(",\"journal_epoch\":").append(epoch)
                .append(",\"tokens\":{");
        boolean first = true;
        for (Token token : tokens.values()) {
            if (token.serialNumber() <= 0) {
                continue;
            }
            if (!first) {
                out.append(',');
            }
            first = false;
            quote(out, token.serial().toString());
            out.append(":{");
            appendTokenFields(out, token);
            out.append('}');
        }
        out.append("},\"equipped\":{");
        first = true;
        for (Map.Entry<UUID, LinkedHashMap<String, UUID>> entry : equipped.entrySet()) {
            StringBuilder selections = new StringBuilder();
            appendSelections(selections, entry.getValue());
            if (selections.length() <= 2) {
                continue;
            }
            if (!first) {
                out.append(',');
            }
            first = false;
            quote(out, entry.getKey().toString());
            out.append(':').append(selections);
        }
        out.append("},\"leaderboard_equipped\":{");
        first = true;
        for (Map.Entry<UUID, LinkedHashMap<String, String>> entry : leaderboardEquipped.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            if (!first) {
                out.append(',');
            }
            first = false;
            quote(out, entry.getKey().toString());
            out.append(":{");
            boolean firstSelection = true;
            for (Map.Entry<String, String> selected : entry.getValue().entrySet()) {
                if (!firstSelection) {
                    out.append(',');
                }
                firstSelection = false;
                quote(out, selected.getKey());
                out.append(':');
                quote(out, selected.getValue());
            }
            out.append('}');
        }
        return out.append("}}").toString();
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) {
                        out.append(String.format("\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        out.append('"');
    }

    private LinkedHashMap<UUID, LinkedHashMap<String, UUID>> copyEquipped() {
        LinkedHashMap<UUID, LinkedHashMap<String, UUID>> copy = new LinkedHashMap<>();
        equipped.forEach((playerId, selections) -> copy.put(
                playerId, new LinkedHashMap<>(selections)
        ));
        return copy;
    }

    private LinkedHashMap<UUID, LinkedHashMap<String, String>> copyLeaderboardEquipped() {
        LinkedHashMap<UUID, LinkedHashMap<String, String>> copy = new LinkedHashMap<>();
        leaderboardEquipped.forEach((playerId, selections) -> copy.put(
                playerId, new LinkedHashMap<>(selections)
        ));
        return copy;
    }

    private void removeTokenSelection(UUID playerId, String category) {
        LinkedHashMap<String, UUID> selections = equipped.get(playerId);
        if (selections == null) {
            return;
        }
        selections.remove(category);
        if (selections.isEmpty()) {
            equipped.remove(playerId);
        }
    }

    private void removeLeaderboardSelection(UUID playerId, String category) {
        LinkedHashMap<String, String> selections = leaderboardEquipped.get(playerId);
        if (selections == null) {
            return;
        }
        selections.remove(category);
        if (selections.isEmpty()) {
            leaderboardEquipped.remove(playerId);
        }
    }

    private void removeSelections(
            UUID owner, Collection<UUID> serials, boolean clearEverySelectionForOwner
    ) {
        if (clearEverySelectionForOwner) {
            equipped.remove(owner);
            leaderboardEquipped.remove(owner);
        }
        equipped.entrySet().removeIf(entry -> {
            entry.getValue().values().removeIf(serials::contains);
            return entry.getValue().isEmpty();
        });
    }

    private static boolean validLeaderboardSelection(String category, String cosmeticId) {
        return CosmeticCatalog.find(cosmeticId)
                .filter(CosmeticCatalog.Definition::leaderboardOnly)
                .filter(definition -> definition.category().name().equals(category))
                .isPresent();
    }

    private void writeAtomically(String json) {
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value == null || !value.isJsonObject() ? new JsonObject() : value.getAsJsonObject();
    }
}
