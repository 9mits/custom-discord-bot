package bot.mgx.accessbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.function.Consumer;

/** Persistent, typed game values that take effect without reloading Paper. */
final class GameVariableStore {
    enum Type { INTEGER, BOOLEAN }

    record Definition(
            String key,
            String label,
            String category,
            String description,
            Type type,
            Object defaultValue,
            Long minimum,
            Long maximum,
            String unit,
            boolean sensitive
    ) { }

    private final Path file;
    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final Map<String, Object> overrides = new LinkedHashMap<>();
    private final List<Consumer<String>> changeObservers = new ArrayList<>();

    GameVariableStore(Path file, FileConfiguration config) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        defineCore(config);
        defineCrateRewards();
        defineAirdropRewards();
        load();
    }

    private void defineCore(FileConfiguration config) {
        integer("crate.default.key-cost", "Default crate key cost", "Crates",
                "Keys consumed by one Default Crate opening.", CrateKind.DEFAULT.keyCost(), 1, 64, "keys", false);
        integer("crate.amethyst.key-cost", "Amethyst crate key cost", "Crates",
                "Keys consumed by one Amethyst Crate opening.", CrateKind.AMETHYST.keyCost(), 1, 64, "keys", false);
        integer("crate.shard.key-cost", "Shard crate cost", "Crates",
                "Shards consumed by one Shard Crate opening.", CrateKind.SHARD.keyCost(), 1, 64, "shards", false);
        integer("crate.keys-per-hour", "Keys per online hour", "Crates",
                "Ordinary keys earned for each completed online hour.", CrateService.KEYS_PER_HOUR, 1, 256, "keys", false);
        integer("crate.booster-keys-per-hour", "Booster keys per online hour", "Crates",
                "Keys earned per online hour while the linked member is boosting.", CrateService.BOOSTER_KEYS_PER_HOUR, 1, 256, "keys", false);
        integer("crate.hidden-amethyst-one-in", "Hidden Amethyst jackpot", "Crates",
                "One winning hidden-jackpot ticket in this many crate openings.",
                CrateCatalog.HIDDEN_AMETHYST_ONE_IN, 1, 100_000_000, "one in", true);

        integer("amethyst-events.minimum-delay-minutes", "Minimum event delay", "Airdrops",
                "Shortest cooldown after an Amethyst world event ends.",
                config.getLong("amethyst-events.minimum-delay-minutes", 30), 1, 1_440, "minutes", false);
        integer("amethyst-events.maximum-delay-minutes", "Maximum event delay", "Airdrops",
                "Longest cooldown after an Amethyst world event ends.",
                config.getLong("amethyst-events.maximum-delay-minutes", 90), 1, 1_440, "minutes", false);
        bool("airdrop.enabled", "Airdrops enabled", "Airdrops",
                "Whether the shared scheduler may choose an Airdrop.",
                config.getBoolean("airdrop.enabled", true));
        integer("airdrop.lifetime-minutes", "Airdrop lifetime", "Airdrops",
                "Time before an unclaimed Airdrop is removed.",
                config.getLong("airdrop.lifetime-minutes", 30), 1, 1_440, "minutes", false);
        integer("airdrop.minimum-radius", "Airdrop minimum radius", "Airdrops",
                "Minimum Overworld distance from spawn for scheduled Airdrops.",
                config.getLong("airdrop.minimum-radius", 500), 0, 100_000, "blocks", false);
        integer("airdrop.maximum-active", "Airdrops at once", "Airdrops",
                "How many Airdrops may stand at the same time. The scheduler still calls"
                        + " one at a time; this is the ceiling on staff-called drops.",
                config.getLong("airdrop.maximum-active", 5), 1, 20, "Airdrops", false);
        integer("airdrop.location-attempts", "Airdrop location attempts", "Airdrops",
                "Safe-ground candidates checked before the scheduler retries later.",
                config.getLong("airdrop.location-attempts", 24), 1, 100, "attempts", false);
        integer("airdrop.shard-one-in", "Airdrop Shard chance", "Airdrops",
                "One Shard roll succeeds in this many Airdrops.", 2_000, 1, 10_000_000, "one in", true);
    }

    private void defineCrateRewards() {
        for (CrateKind kind : CrateKind.values()) {
            for (CrateCatalog.Reward reward : kind.rewards()) {
                integer(
                        "crate." + kind.key() + ".reward." + reward.id() + ".weight",
                        reward.displayName(),
                        kind.displayName() + " Odds",
                        "Relative selection weight for " + reward.displayName() + ".",
                        reward.weight(), 1, 10_000_000, "weight", true
                );
            }
        }
    }

    private void defineAirdropRewards() {
        for (AirdropCatalog.Rarity rarity : AirdropCatalog.Rarity.values()) {
            String key = rarity.name().toLowerCase(Locale.ROOT);
            String category = "Airdrop " + rarity.displayName();
            integer("airdrop.rarity." + key + ".weight", rarity.displayName() + " rarity weight",
                    "Airdrop Odds", "Relative chance that an Airdrop has this rarity.",
                    rarity.weight(), 0, 10_000_000, "weight", true);
            integer("airdrop.rarity." + key + ".minimum-keys", "Minimum keys", category,
                    "Fewest crate keys placed in this rarity.", rarity.minimumKeys(), 0, 10_000, "keys", false);
            integer("airdrop.rarity." + key + ".maximum-keys", "Maximum keys", category,
                    "Most crate keys placed in this rarity.", rarity.maximumKeys(), 0, 10_000, "keys", false);
            integer("airdrop.rarity." + key + ".loot-rolls", "Base loot rolls", category,
                    "Material rolls before the existing zero-to-two roll bonus.", rarity.lootRolls(), 0, 54, "rolls", false);
            integer("airdrop.rarity." + key + ".cosmetic-weight", "Cosmetic chance", category,
                    "Winning cosmetic tickets out of 10,000.", rarity.cosmeticWeight(), 0, 10_000, "per 10,000", true);
        }
        for (AirdropCatalog.LootDefinitionView loot : AirdropCatalog.lootDefinitions()) {
            String base = "airdrop.loot." + loot.materialName().toLowerCase(Locale.ROOT);
            integer(base + ".minimum-amount", loot.materialName() + " minimum amount", "Airdrop Loot",
                    "Base amount before the rarity multiplier.", loot.minimumAmount(), 1, 1_000, "items", false);
            integer(base + ".maximum-amount", loot.materialName() + " maximum amount", "Airdrop Loot",
                    "Maximum base amount before the rarity multiplier.", loot.maximumAmount(), 1, 1_000, "items", false);
            for (AirdropCatalog.Rarity rarity : AirdropCatalog.Rarity.values()) {
                String rarityKey = rarity.name().toLowerCase(Locale.ROOT);
                integer(base + "." + rarityKey + "-weight",
                        loot.materialName() + " " + rarity.displayName() + " weight", "Airdrop Loot Odds",
                        "Relative material-loot weight at this Airdrop rarity.",
                        loot.weight(rarity), 0, 10_000_000, "weight", true);
            }
        }
    }

    private void integer(
            String key, String label, String category, String description,
            long value, long minimum, long maximum, String unit, boolean sensitive
    ) {
        definitions.put(key, new Definition(
                key, label, category, description, Type.INTEGER, value,
                minimum, maximum, unit, sensitive
        ));
    }

    private void bool(String key, String label, String category, String description, boolean value) {
        definitions.put(key, new Definition(
                key, label, category, description, Type.BOOLEAN, value,
                null, null, "", false
        ));
    }

    synchronized void onChange(Runnable observer) {
        if (observer != null) changeObservers.add(_key -> observer.run());
    }

    synchronized void onChange(Consumer<String> observer) {
        if (observer != null) changeObservers.add(observer);
    }

    synchronized int integer(String key) {
        Object value = overrides.getOrDefault(key, definition(key).defaultValue());
        return Math.toIntExact(((Number) value).longValue());
    }

    synchronized boolean bool(String key) {
        return (Boolean) overrides.getOrDefault(key, definition(key).defaultValue());
    }

    synchronized String set(String key, String raw) {
        Definition definition = definition(key);
        Object value = parse(definition, raw);
        validatePair(key, value);
        overrides.put(key, value);
        save();
        changeObservers.forEach(observer -> observer.accept(definition.key()));
        return describe(definition, value);
    }

    synchronized String reset(String key) {
        Definition definition = definition(key);
        overrides.remove(key);
        save();
        changeObservers.forEach(observer -> observer.accept(definition.key()));
        return describe(definition, definition.defaultValue());
    }

    synchronized Optional<Definition> find(String key) {
        return Optional.ofNullable(definitions.get(normalize(key)));
    }

    synchronized JsonObject snapshot() {
        JsonObject root = new JsonObject();
        root.addProperty("generated_at", System.currentTimeMillis());
        JsonArray variables = new JsonArray();
        for (Definition definition : definitions.values()) {
            JsonObject row = new JsonObject();
            Object value = overrides.getOrDefault(definition.key(), definition.defaultValue());
            row.addProperty("key", definition.key());
            row.addProperty("label", definition.label());
            row.addProperty("category", definition.category());
            row.addProperty("description", definition.description());
            row.addProperty("type", definition.type().name().toLowerCase(Locale.ROOT));
            addValue(row, "value", value);
            addValue(row, "default", definition.defaultValue());
            if (definition.minimum() != null) row.addProperty("minimum", definition.minimum());
            if (definition.maximum() != null) row.addProperty("maximum", definition.maximum());
            row.addProperty("unit", definition.unit());
            row.addProperty("sensitive", definition.sensitive());
            row.addProperty("overridden", overrides.containsKey(definition.key()));
            if (definition.key().startsWith("crate.") && definition.key().endsWith(".weight")) {
                addChance(row, crateChance(definition.key()));
            } else if (definition.key().startsWith("airdrop.rarity.")
                    && definition.key().endsWith(".weight")) {
                addChance(row, airdropRarityChance(definition.key()));
            } else if (definition.key().startsWith("airdrop.loot.")
                    && definition.key().endsWith("-weight")) {
                addChance(row, airdropLootChance(definition.key()));
            } else if (definition.key().endsWith(".cosmetic-weight")) {
                addChance(row, ((Number) value).doubleValue() / 100d);
            } else if (definition.key().equals("airdrop.shard-one-in")
                    || definition.key().equals("crate.hidden-amethyst-one-in")) {
                addChance(row, 100d / ((Number) value).doubleValue());
            }
            variables.add(row);
        }
        root.add("variables", variables);
        return root;
    }

    int keyCost(CrateKind kind) {
        return integer("crate." + kind.key() + ".key-cost");
    }

    int keysPerHour(boolean booster) {
        return integer(booster ? "crate.booster-keys-per-hour" : "crate.keys-per-hour");
    }

    int rewardWeight(CrateKind kind, CrateCatalog.Reward reward) {
        return integer("crate." + kind.key() + ".reward." + reward.id() + ".weight");
    }

    double advertisedRareRate(CrateKind kind) {
        long total = kind.rewards().stream().mapToLong(reward -> rewardWeight(kind, reward)).sum();
        long rare = kind.rewards().stream().filter(CrateCatalog.Reward::rare)
                .mapToLong(reward -> rewardWeight(kind, reward)).sum();
        return total <= 0 ? 0d : (double) rare / (double) total;
    }

    String displayedChance(CrateKind kind, CrateCatalog.Reward reward) {
        if (reward.secret()) return "???";
        long total = kind.rewards().stream().mapToLong(value -> rewardWeight(kind, value)).sum();
        return total <= 0 ? "0%" : String.format(
                Locale.ROOT, "%.6f%%", rewardWeight(kind, reward) * 100d / total
        ).replaceAll("0+%$", "%").replaceAll("\\.%$", "%");
    }

    CrateCatalog.Reward randomReward(CrateKind kind, int luckPercent, RandomGenerator random) {
        if (kind != CrateKind.DEFAULT
                && random.nextInt(integer("crate.hidden-amethyst-one-in")) == 0) {
            return CrateCatalog.hiddenAmethystAt(0).orElseThrow();
        }
        int safeLuck = CrateCatalog.clampRollPercent(luckPercent);
        List<CrateCatalog.Reward> rewards = kind.rewards();
        long total = 0;
        for (CrateCatalog.Reward reward : rewards) {
            total += effectiveWeight(kind, reward, safeLuck);
        }
        long ticket = random.nextLong(total);
        long cursor = 0;
        for (CrateCatalog.Reward reward : rewards) {
            cursor += effectiveWeight(kind, reward, safeLuck);
            if (ticket < cursor) return reward;
        }
        throw new IllegalStateException("Dynamic crate reward table is empty");
    }

    AirdropCatalog.Rarity randomAirdropRarity(RandomGenerator random) {
        long total = 0;
        for (AirdropCatalog.Rarity rarity : AirdropCatalog.Rarity.values()) {
            total += rarityWeight(rarity);
        }
        if (total <= 0) throw new IllegalStateException("At least one Airdrop rarity weight must be positive");
        long ticket = random.nextLong(total);
        long cursor = 0;
        for (AirdropCatalog.Rarity rarity : AirdropCatalog.Rarity.values()) {
            cursor += rarityWeight(rarity);
            if (ticket < cursor) return rarity;
        }
        throw new IllegalStateException("Dynamic Airdrop rarity table is empty");
    }

    int rarityWeight(AirdropCatalog.Rarity rarity) {
        return integer("airdrop.rarity." + rarity.name().toLowerCase(Locale.ROOT) + ".weight");
    }

    int rarityValue(AirdropCatalog.Rarity rarity, String suffix) {
        return integer("airdrop.rarity." + rarity.name().toLowerCase(Locale.ROOT) + "." + suffix);
    }

    int lootValue(String material, String suffix) {
        return integer("airdrop.loot." + material.toLowerCase(Locale.ROOT) + "." + suffix);
    }

    private long effectiveWeight(CrateKind kind, CrateCatalog.Reward reward, int luckPercent) {
        long weight = rewardWeight(kind, reward);
        return reward.rare() ? Math.max(1, (weight * luckPercent + 50) / 100) : weight;
    }

    private double crateChance(String variableKey) {
        String[] parts = variableKey.split("\\.", 5);
        if (parts.length < 5) return 0;
        CrateKind kind = CrateKind.from(parts[1]).orElse(null);
        if (kind == null) return 0;
        long total = kind.rewards().stream().mapToLong(reward -> rewardWeight(kind, reward)).sum();
        if (total <= 0) return 0;
        String rewardId = parts[3];
        return kind.rewards().stream()
                .filter(reward -> reward.id().equals(rewardId))
                .findFirst()
                .map(reward -> rewardWeight(kind, reward) * 100d / total)
                .orElse(0d);
    }

    private double airdropRarityChance(String variableKey) {
        String[] parts = variableKey.split("\\.");
        if (parts.length != 4) return 0d;
        long total = java.util.Arrays.stream(AirdropCatalog.Rarity.values())
                .mapToLong(this::rarityWeight).sum();
        if (total <= 0) return 0d;
        return java.util.Arrays.stream(AirdropCatalog.Rarity.values())
                .filter(rarity -> rarity.name().equalsIgnoreCase(parts[2]))
                .findFirst().map(rarity -> rarityWeight(rarity) * 100d / total).orElse(0d);
    }

    private double airdropLootChance(String variableKey) {
        String[] parts = variableKey.split("\\.");
        if (parts.length != 4) return 0d;
        String suffix = parts[3];
        String rarity = suffix.substring(0, suffix.length() - "-weight".length());
        long total = AirdropCatalog.lootDefinitions().stream()
                .mapToLong(loot -> lootValue(loot.materialName(), suffix)).sum();
        if (total <= 0) return 0d;
        return lootValue(parts[2], rarity + "-weight") * 100d / total;
    }

    private Definition definition(String key) {
        Definition definition = definitions.get(normalize(key));
        if (definition == null) throw new IllegalArgumentException("Unknown variable '" + key + "'.");
        return definition;
    }

    private static String normalize(String key) {
        return String.valueOf(key).strip().toLowerCase(Locale.ROOT);
    }

    private static Object parse(Definition definition, String raw) {
        String value = String.valueOf(raw).strip();
        if (definition.type() == Type.BOOLEAN) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "true", "on", "yes", "1" -> true;
                case "false", "off", "no", "0" -> false;
                default -> throw new IllegalArgumentException("Use true or false for " + definition.key() + ".");
            };
        }
        final long parsed;
        try {
            parsed = Long.parseLong(value.replace(",", ""));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(definition.key() + " must be a whole number.");
        }
        if (parsed < definition.minimum() || parsed > definition.maximum()) {
            throw new IllegalArgumentException(definition.key() + " must be between "
                    + definition.minimum() + " and " + definition.maximum() + ".");
        }
        return parsed;
    }

    private void validatePair(String key, Object value) {
        Map<String, String> pairs = Map.of(
                "amethyst-events.minimum-delay-minutes", "amethyst-events.maximum-delay-minutes",
                "airdrop.rarity.common.minimum-keys", "airdrop.rarity.common.maximum-keys",
                "airdrop.rarity.rare.minimum-keys", "airdrop.rarity.rare.maximum-keys",
                "airdrop.rarity.legendary.minimum-keys", "airdrop.rarity.legendary.maximum-keys",
                "airdrop.rarity.mythic.minimum-keys", "airdrop.rarity.mythic.maximum-keys"
        );
        String maximum = pairs.get(key);
        if (maximum != null && ((Number) value).longValue() > integer(maximum)) {
            throw new IllegalArgumentException(key + " cannot be greater than " + maximum + ".");
        }
        for (Map.Entry<String, String> pair : pairs.entrySet()) {
            if (pair.getValue().equals(key) && ((Number) value).longValue() < integer(pair.getKey())) {
                throw new IllegalArgumentException(key + " cannot be less than " + pair.getKey() + ".");
            }
        }
        if (key.endsWith(".minimum-amount")) {
            String other = key.replace(".minimum-amount", ".maximum-amount");
            if (((Number) value).longValue() > integer(other)) {
                throw new IllegalArgumentException(key + " cannot exceed " + other + ".");
            }
        } else if (key.endsWith(".maximum-amount")) {
            String other = key.replace(".maximum-amount", ".minimum-amount");
            if (((Number) value).longValue() < integer(other)) {
                throw new IllegalArgumentException(key + " cannot be below " + other + ".");
            }
        }
    }

    private static String describe(Definition definition, Object value) {
        return definition.key() + " = " + value + (definition.unit().isBlank() ? "" : " " + definition.unit());
    }

    private static void addValue(JsonObject object, String key, Object value) {
        if (value instanceof Boolean bool) object.addProperty(key, bool);
        else object.addProperty(key, ((Number) value).longValue());
    }

    private static void addChance(JsonObject object, double chance) {
        // Signed bridge envelopes are canonicalized independently by Gson and
        // Python's json module. Decimal strings avoid language-specific float
        // rendering while remaining directly consumable by the dashboard.
        object.addProperty(
                "chance_percent",
                BigDecimal.valueOf(chance).stripTrailingZeros().toPlainString()
        );
    }

    private void load() throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                Definition definition = definitions.get(entry.getKey());
                if (definition == null) continue;
                Object value = definition.type() == Type.BOOLEAN
                        ? entry.getValue().getAsBoolean() : entry.getValue().getAsLong();
                parse(definition, String.valueOf(value));
                overrides.put(entry.getKey(), value);
            }
        } catch (RuntimeException malformed) {
            throw new IOException("game-variables.json is unreadable", malformed);
        }
    }

    private void save() {
        JsonObject root = new JsonObject();
        overrides.forEach((key, value) -> addValue(root, key, value));
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
