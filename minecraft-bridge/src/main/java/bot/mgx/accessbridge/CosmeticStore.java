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

/** Unique cosmetic-token custody and the token selected in each effect category. */
final class CosmeticStore {
    record Token(
            UUID serial, String cosmeticId, int generation, int serialNumber, UUID storedOwner
    ) {
        boolean stored() {
            return storedOwner != null;
        }
    }

    private final Path file;
    private final LinkedHashMap<UUID, Token> tokens = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, LinkedHashMap<String, UUID>> equipped = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, LinkedHashMap<String, String>> leaderboardEquipped =
            new LinkedHashMap<>();
    private final Set<UUID> previewOwners = new HashSet<>();
    private int generation = 1;

    CosmeticStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            generation = Math.max(1, root.has("generation") ? root.get("generation").getAsInt() : 1);
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
            boolean migratedSecretCategory = false;
            JsonObject savedEquipped = object(root, "equipped");
            for (Map.Entry<String, JsonElement> entry : savedEquipped.entrySet()) {
                LinkedHashMap<String, UUID> selections = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> selected
                        : entry.getValue().getAsJsonObject().entrySet()) {
                    selections.put(selected.getKey(), UUID.fromString(selected.getValue().getAsString()));
                }
                UUID legacySecret = selections.remove("SECRET");
                if (legacySecret != null) {
                    Token token = tokens.get(legacySecret);
                    CosmeticCatalog.find(token == null ? null : token.cosmeticId()).ifPresent(
                            definition -> selections.put(definition.category().name(), legacySecret)
                    );
                    migratedSecretCategory = true;
                }
                if (!selections.isEmpty()) {
                    equipped.put(UUID.fromString(entry.getKey()), selections);
                }
            }
            JsonObject savedLeaderboardEquipped = object(root, "leaderboard_equipped");
            for (Map.Entry<String, JsonElement> entry : savedLeaderboardEquipped.entrySet()) {
                LinkedHashMap<String, String> selections = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> selected
                        : entry.getValue().getAsJsonObject().entrySet()) {
                    String cosmeticId = selected.getValue().getAsString();
                    if (validLeaderboardSelection(selected.getKey(), cosmeticId)) {
                        selections.put(selected.getKey(), cosmeticId);
                    }
                }
                if (!selections.isEmpty()) {
                    leaderboardEquipped.put(UUID.fromString(entry.getKey()), selections);
                }
            }
            if (migratedSerialNumbers || migratedSecretCategory) {
                save();
            }
        } catch (RuntimeException exception) {
            throw new IOException("Cosmetic store is unreadable", exception);
        }
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
        int serialNumber = tokens.values().stream()
                .filter(token -> token.generation() == generation)
                .filter(token -> token.cosmeticId().equals(cosmeticId))
                .mapToInt(Token::serialNumber)
                .max()
                .orElse(0) + 1;
        Token token = new Token(serial, cosmeticId, generation, serialNumber, owner);
        tokens.put(serial, token);
        try {
            save();
        } catch (RuntimeException exception) {
            tokens.remove(serial);
            throw exception;
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
        List<Token> owned = new ArrayList<>();
        for (Token token : tokens.values()) {
            if (token.generation() == generation && owner.equals(token.storedOwner())) {
                owned.add(token);
            }
        }
        return List.copyOf(owned);
    }

    synchronized int inExistence(String cosmeticId) {
        if (cosmeticId == null || cosmeticId.isBlank()) {
            return 0;
        }
        int count = 0;
        for (Token token : tokens.values()) {
            if (token.generation() == generation
                    && token.serialNumber() > 0
                    && token.cosmeticId().equals(cosmeticId)) {
                count++;
            }
        }
        return count;
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
        try {
            save();
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
        tokens.put(serial, new Token(
                serial, cosmeticId, generation, token.serialNumber(), owner
        ));
        try {
            save();
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
        equipped.entrySet().removeIf(entry -> {
            entry.getValue().entrySet().removeIf(selected -> serial.equals(selected.getValue()));
            return entry.getValue().isEmpty();
        });
        LinkedHashMap<String, UUID> selections = equipped.computeIfAbsent(
                playerId, ignored -> new LinkedHashMap<>()
        );
        selections.put(category, serial);
        removeLeaderboardSelection(playerId, category);
        try {
            save();
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
            save();
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
        try {
            save();
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
        return Optional.ofNullable(equipped.getOrDefault(playerId, new LinkedHashMap<>()).get(category));
    }

    synchronized Optional<String> leaderboardEquipped(UUID playerId, String category) {
        return Optional.ofNullable(
                leaderboardEquipped.getOrDefault(playerId, new LinkedHashMap<>()).get(category)
        );
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
            save();
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
            save();
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
        try {
            save();
        } catch (RuntimeException exception) {
            generation = generationBefore;
            tokens.putAll(tokensBefore);
            equipped.putAll(equippedBefore);
            leaderboardEquipped.putAll(leaderboardBefore);
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
        try {
            save();
        } catch (RuntimeException exception) {
            tokens.clear();
            tokens.putAll(tokensBefore);
            equipped.clear();
            equipped.putAll(equippedBefore);
            leaderboardEquipped.clear();
            leaderboardEquipped.putAll(leaderboardBefore);
            throw exception;
        }
        return removed.size();
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
        try {
            save();
        } catch (RuntimeException exception) {
            tokens.clear();
            tokens.putAll(before);
            throw exception;
        }
        return matching.size();
    }

    private void save() {
        JsonObject root = new JsonObject();
        root.addProperty("generation", generation);
        JsonObject savedTokens = new JsonObject();
        tokens.forEach((serial, token) -> {
            if (token.serialNumber() <= 0) {
                return;
            }
            JsonObject value = new JsonObject();
            value.addProperty("cosmetic_id", token.cosmeticId());
            value.addProperty("generation", token.generation());
            value.addProperty("serial_number", token.serialNumber());
            if (token.storedOwner() != null) {
                value.addProperty("stored_owner", token.storedOwner().toString());
            }
            savedTokens.add(serial.toString(), value);
        });
        root.add("tokens", savedTokens);
        JsonObject savedEquipped = new JsonObject();
        equipped.forEach((playerId, selections) -> {
            JsonObject value = new JsonObject();
            selections.forEach((category, serial) -> {
                Token token = tokens.get(serial);
                if (token != null && token.serialNumber() > 0) {
                    value.addProperty(category, serial.toString());
                }
            });
            if (!value.entrySet().isEmpty()) {
                savedEquipped.add(playerId.toString(), value);
            }
        });
        root.add("equipped", savedEquipped);
        JsonObject savedLeaderboardEquipped = new JsonObject();
        leaderboardEquipped.forEach((playerId, selections) -> {
            JsonObject value = new JsonObject();
            selections.forEach(value::addProperty);
            if (!value.entrySet().isEmpty()) {
                savedLeaderboardEquipped.add(playerId.toString(), value);
            }
        });
        root.add("leaderboard_equipped", savedLeaderboardEquipped);
        writeAtomically(root.toString());
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
