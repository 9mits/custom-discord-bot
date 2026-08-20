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
import java.util.UUID;

/** Unique cosmetic-token custody and the token selected in each effect category. */
final class CosmeticStore {
    record Token(UUID serial, String cosmeticId, int generation, UUID storedOwner) {
        boolean stored() {
            return storedOwner != null;
        }
    }

    private final Path file;
    private final LinkedHashMap<UUID, Token> tokens = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, LinkedHashMap<String, UUID>> equipped = new LinkedHashMap<>();
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
            for (Map.Entry<String, JsonElement> entry : savedTokens.entrySet()) {
                UUID serial = UUID.fromString(entry.getKey());
                JsonObject value = entry.getValue().getAsJsonObject();
                UUID owner = value.has("stored_owner")
                        ? UUID.fromString(value.get("stored_owner").getAsString())
                        : null;
                tokens.put(serial, new Token(
                        serial,
                        value.get("cosmetic_id").getAsString(),
                        value.get("generation").getAsInt(),
                        owner
                ));
            }
            JsonObject savedEquipped = object(root, "equipped");
            for (Map.Entry<String, JsonElement> entry : savedEquipped.entrySet()) {
                LinkedHashMap<String, UUID> selections = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> selected
                        : entry.getValue().getAsJsonObject().entrySet()) {
                    selections.put(selected.getKey(), UUID.fromString(selected.getValue().getAsString()));
                }
                if (!selections.isEmpty()) {
                    equipped.put(UUID.fromString(entry.getKey()), selections);
                }
            }
        } catch (RuntimeException exception) {
            throw new IOException("Cosmetic store is unreadable", exception);
        }
    }

    synchronized int generation() {
        return generation;
    }

    synchronized Token mint(UUID owner, String cosmeticId, UUID serial) {
        Token existing = tokens.get(serial);
        if (existing != null) {
            if (!existing.cosmeticId().equals(cosmeticId) || existing.generation() != generation) {
                throw new IllegalArgumentException("That cosmetic serial already means something else.");
            }
            return existing;
        }
        Token token = new Token(serial, cosmeticId, generation, owner);
        tokens.put(serial, token);
        try {
            save();
        } catch (RuntimeException exception) {
            tokens.remove(serial);
            throw exception;
        }
        return token;
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
            if (token.generation() == generation && token.cosmeticId().equals(cosmeticId)) {
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
        Token physical = new Token(serial, token.cosmeticId(), token.generation(), null);
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
        tokens.put(serial, new Token(serial, cosmeticId, generation, owner));
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
        equipped.entrySet().removeIf(entry -> {
            entry.getValue().entrySet().removeIf(selected -> serial.equals(selected.getValue()));
            return entry.getValue().isEmpty();
        });
        LinkedHashMap<String, UUID> selections = equipped.computeIfAbsent(
                playerId, ignored -> new LinkedHashMap<>()
        );
        selections.put(category, serial);
        try {
            save();
        } catch (RuntimeException exception) {
            equipped.clear();
            equipped.putAll(equippedBefore);
            throw exception;
        }
    }

    synchronized Optional<UUID> equipped(UUID playerId, String category) {
        return Optional.ofNullable(equipped.getOrDefault(playerId, new LinkedHashMap<>()).get(category));
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
        int cleared = tokens.size() + equipped.size();
        int generationBefore = generation;
        LinkedHashMap<UUID, Token> tokensBefore = new LinkedHashMap<>(tokens);
        LinkedHashMap<UUID, LinkedHashMap<String, UUID>> equippedBefore = copyEquipped();
        generation = generation == Integer.MAX_VALUE ? 1 : generation + 1;
        tokens.clear();
        equipped.clear();
        try {
            save();
        } catch (RuntimeException exception) {
            generation = generationBefore;
            tokens.putAll(tokensBefore);
            equipped.putAll(equippedBefore);
            throw exception;
        }
        return cleared;
    }

    private void save() {
        JsonObject root = new JsonObject();
        root.addProperty("generation", generation);
        JsonObject savedTokens = new JsonObject();
        tokens.forEach((serial, token) -> {
            JsonObject value = new JsonObject();
            value.addProperty("cosmetic_id", token.cosmeticId());
            value.addProperty("generation", token.generation());
            if (token.storedOwner() != null) {
                value.addProperty("stored_owner", token.storedOwner().toString());
            }
            savedTokens.add(serial.toString(), value);
        });
        root.add("tokens", savedTokens);
        JsonObject savedEquipped = new JsonObject();
        equipped.forEach((playerId, selections) -> {
            JsonObject value = new JsonObject();
            selections.forEach((category, serial) -> value.addProperty(category, serial.toString()));
            savedEquipped.add(playerId.toString(), value);
        });
        root.add("equipped", savedEquipped);
        writeAtomically(root.toString());
    }

    private LinkedHashMap<UUID, LinkedHashMap<String, UUID>> copyEquipped() {
        LinkedHashMap<UUID, LinkedHashMap<String, UUID>> copy = new LinkedHashMap<>();
        equipped.forEach((playerId, selections) -> copy.put(
                playerId, new LinkedHashMap<>(selections)
        ));
        return copy;
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
