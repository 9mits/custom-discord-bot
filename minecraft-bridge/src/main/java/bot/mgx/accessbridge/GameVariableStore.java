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
    private static final String ONLINE_REWARD_PREFIX = "online-rewards.";
    private static final String LEGACY_REWARD_PREFIX = "afk-rewards.";

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

    record OnlineRewardTier(
            int number,
            int minimumHours,
            int bonusKeys,
            int emeralds,
            int emeraldOneIn,
            int diamonds,
            int diamondOneIn,
            int netheriteIngots,
            int netheriteOneIn,
            int shards,
            int shardOneIn
    ) { }

    private final Path file;
    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final Map<String, Object> overrides = new LinkedHashMap<>();
    private final List<Consumer<String>> changeObservers = new ArrayList<>();

    GameVariableStore(Path file, FileConfiguration config) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        defineCore(config);
        defineOnlineRewards();
        defineEventRewards(config);
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

        integer("amethyst-events.minimum-delay-minutes", "Minimum event delay", "Amethyst Events",
                "Shortest cooldown after an Amethyst world event ends.",
                config.getLong("amethyst-events.minimum-delay-minutes", 15), 1, 1_440, "minutes", false);
        integer("amethyst-events.maximum-delay-minutes", "Maximum event delay", "Amethyst Events",
                "Longest cooldown after an Amethyst world event ends.",
                config.getLong("amethyst-events.maximum-delay-minutes", 30), 1, 1_440, "minutes", false);
        bool("airdrop.enabled", "Airdrops enabled", "Airdrops",
                "Whether the shared scheduler may choose an Airdrop.",
                config.getBoolean("airdrop.enabled", true));
        integer("airdrop.lifetime-minutes", "Airdrop lifetime", "Airdrops",
                "Time before an unclaimed Airdrop is removed.",
                config.getLong("airdrop.lifetime-minutes", 30), 1, 1_440, "minutes", false);
        defineAirdropRadius(config, "common", "Common", 1_000, 2_000);
        defineAirdropRadius(config, "rare", "Rare", 1_000, 2_000);
        defineAirdropRadius(config, "legendary", "Legendary", 5_000, 10_000);
        defineAirdropRadius(config, "mythic", "Mythic", 10_000, 25_000);
        integer("airdrop.maximum-active", "Airdrops at once", "Airdrops",
                "How many Airdrops may stand at the same time. The scheduler still calls"
                        + " one at a time; this is the ceiling on staff-called drops.",
                config.getLong("airdrop.maximum-active", 5), 1, 20, "Airdrops", false);
        integer("airdrop.location-attempts", "Airdrop location attempts", "Airdrops",
                "Safe-ground candidates checked before the scheduler retries later.",
                config.getLong("airdrop.location-attempts", 24), 1, 100, "attempts", false);
        integer("airdrop.shard-one-in", "Airdrop Shard chance", "Airdrops",
                "One Shard roll succeeds in this many Airdrops.", 2_000, 1, 10_000_000, "one in", true);
        integer("airdrop.shard-amount", "Airdrop Shard amount", "Airdrops",
                "Shards placed when the exceptionally rare Shard roll succeeds.",
                1, 0, 64, "shards", false);
        integer("airdrop.bonus-loot-rolls", "Maximum bonus loot rolls", "Airdrops",
                "Random extra material rolls added above the rarity's base rolls.",
                2, 0, 54, "rolls", false);
    }

    private void defineAirdropRadius(
            FileConfiguration config, String id, String label, int minimum, int maximum
    ) {
        String base = "airdrop.rarity-radius." + id + ".";
        integer(base + "minimum", label + " minimum distance", "Airdrop Distance",
                "Nearest scheduled " + label + " Airdrop distance from 0,0.",
                config.getLong(base + "minimum", minimum), 0, 100_000, "blocks", false);
        integer(base + "maximum", label + " maximum distance", "Airdrop Distance",
                "Farthest scheduled " + label + " Airdrop distance from 0,0.",
                config.getLong(base + "maximum", maximum), 1, 100_000, "blocks", false);
    }

    private void defineOnlineRewards() {
        bool("online-rewards.enabled", "Online stay rewards", "Online Rewards",
                "Whether connected players receive the escalating stay-online reward ladder.", true);
        integer("online-rewards.interval-minutes", "Online reward interval", "Online Rewards",
                "Continuous connected minutes required for each reward. Disconnecting resets the interval.",
                60, 5, 1_440, "minutes", false);
        integer("online-rewards.population.minimum-players", "Population boost starts at", "Online Rewards",
                "Eligible online players required before stay rewards gain bonus keys.",
                5, 1, 1_000, "players", false);
        integer("online-rewards.population.players-per-step", "Players per population step", "Online Rewards",
                "Additional online players required for each further bonus-key step.",
                5, 1, 1_000, "players", false);
        integer("online-rewards.population.keys-per-step", "Keys per population step", "Online Rewards",
                "Bonus keys added to every stay reward for each reached player-count step.",
                1, 0, 256, "keys", false);
        integer("online-rewards.population.maximum-bonus-keys", "Maximum population bonus", "Online Rewards",
                "Ceiling on bonus keys supplied by the current online player count.",
                4, 0, 1_024, "keys", false);
        bool("online-rewards.key-events-multiply-bonus", "Key events multiply stay rewards", "Online Rewards",
                "Whether 2x/4x key events also multiply stay-ladder and population bonus keys.", false);

        defineOnlineRewardTier(1, 1, 1, 0, 1, 0, 1, 0, 1, 0, 1);
        defineOnlineRewardTier(2, 3, 2, 1, 2, 0, 1, 0, 1, 0, 1);
        defineOnlineRewardTier(3, 6, 3, 1, 1, 1, 4, 0, 1, 0, 1);
        defineOnlineRewardTier(4, 12, 4, 2, 1, 1, 2, 0, 1, 0, 1);
        defineOnlineRewardTier(5, 24, 6, 3, 1, 1, 1, 1, 24, 0, 1);
        // Passive Shards must remain far rarer than active event rewards. They start
        // only after 72 lifetime online hours, then average one per 5,000 hourly rolls.
        defineOnlineRewardTier(6, 72, 10, 4, 1, 2, 1, 1, 24, 1, 5_000);
    }

    private void defineOnlineRewardTier(
            int tier, int minimumHours, int bonusKeys,
            int emeralds, int emeraldOneIn, int diamonds, int diamondOneIn,
            int netherite, int netheriteOneIn, int shards, int shardOneIn
    ) {
        String base = "online-rewards.tier." + tier + ".";
        String category = "Online Tier " + tier;
        integer(base + "minimum-hours", "Minimum lifetime playtime", category,
                "Lifetime online hours required before this tier becomes the stay reward.",
                minimumHours, 1, 100_000, "hours", false);
        integer(base + "bonus-keys", "Bonus keys", category,
                "Keys added by this online tier before the population bonus.",
                bonusKeys, 0, 1_024, "keys", false);
        rewardRoll(base, category, "emerald", "Emeralds", emeralds, emeraldOneIn, 2_304);
        rewardRoll(base, category, "diamond", "Diamonds", diamonds, diamondOneIn, 2_304);
        rewardRoll(base, category, "netherite", "Netherite Ingots", netherite, netheriteOneIn, 64);
        rewardRoll(base, category, "shard", "Shards", shards, shardOneIn, 64);
    }

    private void rewardRoll(
            String base, String category, String key, String label,
            int amount, int oneIn, int maximumAmount
    ) {
        integer(base + key + "-amount", label, category,
                label + " delivered when this tier's roll succeeds. Zero disables the reward.",
                amount, 0, maximumAmount, "items", false);
        integer(base + key + "-one-in", label + " chance", category,
                "One successful " + label + " roll in this many online stay rewards.",
                oneIn, 1, 100_000_000, "one in", key.equals("shard"));
    }

    private void defineEventRewards(FileConfiguration config) {
        bool("huge-amethyst.enabled", "Huge Amethyst enabled", "Huge Amethyst",
                "Whether the shared scheduler may choose a Huge Amethyst Block.",
                config.getBoolean("amethyst-block-event.enabled", true));
        integer("huge-amethyst.lifetime-minutes", "Huge Amethyst lifetime", "Huge Amethyst",
                "Minutes before an unfinished Huge Amethyst Block dissolves.",
                config.getLong("amethyst-block-event.lifetime-minutes", 30),
                1, 1_440, "minutes", false);
        integer("huge-amethyst.minimum-radius", "Huge Amethyst minimum radius", "Huge Amethyst",
                "Minimum Overworld distance from spawn for scheduled blocks.",
                config.getLong("amethyst-block-event.minimum-radius", 500),
                0, 100_000, "blocks", false);
        integer("huge-amethyst.location-attempts", "Huge Amethyst location attempts", "Huge Amethyst",
                "Safe-ground candidates checked before the scheduler retries later.",
                config.getLong("amethyst-block-event.location-attempts", 24),
                1, 100, "attempts", false);
        integer("huge-amethyst.maximum-health", "Huge Amethyst health", "Huge Amethyst",
                "Total health of the cooperative Huge Amethyst Block.",
                (long) AmethystBlockRewards.MAX_HEALTH, 100, 10_000_000, "health", false);
        for (int wave = 1; wave <= AmethystBlockRewards.REWARD_HEALTH_PERCENTAGES.length; wave++) {
            integer("huge-amethyst.wave." + wave + ".health-percent",
                    "Reward wave " + wave + " health", "Huge Amethyst",
                    "Remaining-health percentage that triggers this reward wave.",
                    AmethystBlockRewards.REWARD_HEALTH_PERCENTAGES[wave - 1],
                    1, 99, "percent", false);
        }
        defineHugeBundle("milestone", "Reward Wave", 3, 5, 1, 3, 2, 5, 4, 8);
        defineHugeBundle("completion", "Completion", 8, 12, 3, 6, 5, 9, 8, 16);
        integer("huge-amethyst.shard-one-in", "Huge Amethyst Shard chance", "Huge Amethyst",
                "One Shard roll succeeds in this many individual reward bundles.",
                2_500, 1, 100_000_000, "one in", true);
        integer("huge-amethyst.shard-amount", "Huge Amethyst Shard amount", "Huge Amethyst",
                "Shards delivered when the rare bundle roll succeeds.",
                1, 0, 64, "shards", false);
        integer("huge-amethyst.contribution-base-keys", "Contribution base keys", "Huge Amethyst",
                "Completion keys guaranteed to every player who damaged the block.",
                5, 0, 10_000, "keys", false);
        integer("huge-amethyst.contribution-pool-keys", "Contribution key pool", "Huge Amethyst",
                "Completion keys divided proportionally by damage dealt.",
                45, 0, 100_000, "keys", false);

        integer("chaos.supply-drop.keys", "Supply Drop keys", "Admin Event Rewards",
                "Default crate-key payout for the theatrical Supply Drop.",
                ChaosService.DEFAULT_AIRDROP_KEYS, 0, 100_000, "keys", false);
        integer("chaos.key-rain.keys", "Key Rain keys", "Admin Event Rewards",
                "Default number of crate keys dropped by a Key Rain.",
                50, 1, 250, "keys", false);
        integer("chaos.pinata.base-keys", "Pinata base keys", "Admin Event Rewards",
                "Default Pinata payout before its per-player addition.",
                30, 0, 100_000, "keys", false);
        integer("chaos.pinata.keys-per-player", "Pinata keys per player", "Admin Event Rewards",
                "Keys added to the Pinata payout for each eligible player.",
                5, 0, 10_000, "keys", false);
        integer("chaos.pinata.minimum-hits", "Pinata minimum hits", "Admin Event Rewards",
                "Fewest hits required to break a Pinata.", 20, 1, 100_000, "hits", false);
        integer("chaos.pinata.hits-per-player", "Pinata hits per player", "Admin Event Rewards",
                "Hit requirement contributed by each eligible player.",
                15, 0, 10_000, "hits", false);
        integer("chaos.jackpot.minimum-keys", "Jackpot minimum keys", "Admin Event Rewards",
                "Smallest random default Jackpot payout.", 5, 0, 100_000, "keys", false);
        integer("chaos.jackpot.maximum-keys", "Jackpot maximum keys", "Admin Event Rewards",
                "Largest random default Jackpot payout.", 20, 0, 100_000, "keys", false);
        integer("chaos.alfredo.health", "Alfredo health", "Admin Event Rewards",
                "Default maximum health of Alfredo.",
                (long) ChaosService.ALFREDO_DEFAULT_HEALTH, 20, 10_000_000, "health", false);
        integer("chaos.alfredo.keys", "Alfredo keys", "Admin Event Rewards",
                "Default total key payout carried by Alfredo.",
                ChaosService.ALFREDO_DEFAULT_KEYS, 0, 100_000, "keys", false);
        integer("chaos.alfredo.diamonds", "Alfredo diamonds", "Admin Event Rewards",
                "Default total diamond payout carried by Alfredo.",
                ChaosService.ALFREDO_DEFAULT_DIAMONDS, 0, 100_000, "diamonds", false);
        integer("chaos.alfredo.bursts", "Alfredo reward bursts", "Admin Event Rewards",
                "Reward bursts paid before Alfredo's final eruption.",
                10, 1, 100, "bursts", false);
    }

    private void defineHugeBundle(
            String key, String label,
            int minKeys, int maxKeys, int minDiamonds, int maxDiamonds,
            int minEmeralds, int maxEmeralds, int minGold, int maxGold
    ) {
        String base = "huge-amethyst." + key + ".";
        for (Object[] value : List.of(
                new Object[]{"keys", minKeys, maxKeys},
                new Object[]{"diamonds", minDiamonds, maxDiamonds},
                new Object[]{"emeralds", minEmeralds, maxEmeralds},
                new Object[]{"gold", minGold, maxGold}
        )) {
            String item = (String) value[0];
            integer(base + "minimum-" + item, label + " minimum " + item, "Huge Amethyst",
                    "Fewest " + item + " in each " + label.toLowerCase(Locale.ROOT) + " bundle.",
                    (int) value[1], 0, 100_000, item, false);
            integer(base + "maximum-" + item, label + " maximum " + item, "Huge Amethyst",
                    "Most " + item + " in each " + label.toLowerCase(Locale.ROOT) + " bundle.",
                    (int) value[2], 0, 100_000, item, false);
        }
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
        Definition definition = definition(key);
        Object value = overrides.getOrDefault(definition.key(), definition.defaultValue());
        return Math.toIntExact(((Number) value).longValue());
    }

    synchronized boolean bool(String key) {
        Definition definition = definition(key);
        return (Boolean) overrides.getOrDefault(definition.key(), definition.defaultValue());
    }

    synchronized String set(String key, String raw) {
        Definition definition = definition(key);
        Object value = parse(definition, raw);
        validatePair(definition.key(), value);
        overrides.put(definition.key(), value);
        save();
        changeObservers.forEach(observer -> observer.accept(definition.key()));
        return describe(definition, value);
    }

    synchronized String reset(String key) {
        Definition definition = definition(key);
        overrides.remove(definition.key());
        save();
        changeObservers.forEach(observer -> observer.accept(definition.key()));
        return describe(definition, definition.defaultValue());
    }

    synchronized Optional<Definition> find(String key) {
        return Optional.ofNullable(definitions.get(canonicalKey(key)));
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
            addMetadata(row, definition);
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
            } else if (definition.key().endsWith("one-in")) {
                addChance(row, 100d / ((Number) value).doubleValue());
            }
            variables.add(row);
        }
        root.add("variables", variables);
        root.add("tables", tableSummary());
        return root;
    }

    /**
     * Adds the presentation and dependency fields alongside the original ones.
     *
     * <p>Purely additive: every field the existing panel reads is still written above,
     * so a panel that has not been rebuilt yet keeps working unchanged.
     */
    private void addMetadata(JsonObject row, Definition definition) {
        SettingMetadata metadata = SettingMetadata.of(definition, definitions.keySet());
        row.addProperty("control", metadata.control().name().toLowerCase(Locale.ROOT));
        row.addProperty("group", metadata.group().name().toLowerCase(Locale.ROOT));
        row.addProperty("group_label", metadata.group().displayName());
        row.addProperty("reload", metadata.reload().name().toLowerCase(Locale.ROOT));
        if (metadata.table() != null) row.addProperty("table", metadata.table());
        if (metadata.partner() != null) row.addProperty("partner", metadata.partner());
        if (metadata.restartReason() != null) {
            row.addProperty("restart_reason", metadata.restartReason());
        }
    }

    /**
     * Every distribution, with the total its rows share.
     *
     * <p>A weight only means something against this total, and the panel needs it before
     * it can turn one into a percentage or show what an edit does to the rest of the
     * table. Sending it once beside the rows saves the browser rebuilding it per row and
     * keeps the arithmetic on the side that owns the numbers.
     */
    private JsonArray tableSummary() {
        Map<String, long[]> totals = new LinkedHashMap<>();
        Map<String, Integer> rows = new LinkedHashMap<>();
        for (Definition definition : definitions.values()) {
            String table = SettingMetadata.table(definition.key()).orElse(null);
            if (table == null) {
                continue;
            }
            Object value = overrides.getOrDefault(definition.key(), definition.defaultValue());
            totals.computeIfAbsent(table, ignored -> new long[1])[0] += ((Number) value).longValue();
            rows.merge(table, 1, Integer::sum);
        }
        JsonArray summary = new JsonArray();
        totals.forEach((table, total) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("table", table);
            entry.addProperty("total_weight", total[0]);
            entry.addProperty("entries", rows.get(table));
            summary.add(entry);
        });
        return summary;
    }

    int keyCost(CrateKind kind) {
        return integer("crate." + kind.key() + ".key-cost");
    }

    int keysPerHour(boolean booster) {
        return integer(booster ? "crate.booster-keys-per-hour" : "crate.keys-per-hour");
    }

    OnlineRewardTier onlineRewardTier(long lifetimeOnlineSeconds) {
        OnlineRewardTier selected = onlineTier(1);
        long safeSeconds = Math.max(0L, lifetimeOnlineSeconds);
        for (int tier = 2; tier <= 6; tier++) {
            OnlineRewardTier candidate = onlineTier(tier);
            if (safeSeconds < candidate.minimumHours() * 3_600L) {
                break;
            }
            selected = candidate;
        }
        return selected;
    }

    Optional<OnlineRewardTier> nextOnlineRewardTier(long lifetimeOnlineSeconds) {
        int next = onlineRewardTier(lifetimeOnlineSeconds).number() + 1;
        return next > 6 ? Optional.empty() : Optional.of(onlineTier(next));
    }

    private OnlineRewardTier onlineTier(int tier) {
        String base = "online-rewards.tier." + tier + ".";
        return new OnlineRewardTier(
                tier,
                integer(base + "minimum-hours"),
                integer(base + "bonus-keys"),
                integer(base + "emerald-amount"),
                integer(base + "emerald-one-in"),
                integer(base + "diamond-amount"),
                integer(base + "diamond-one-in"),
                integer(base + "netherite-amount"),
                integer(base + "netherite-one-in"),
                integer(base + "shard-amount"),
                integer(base + "shard-one-in")
        );
    }

    int onlinePopulationBonusKeys(int onlinePlayers) {
        int minimum = integer("online-rewards.population.minimum-players");
        int safeOnline = Math.max(0, onlinePlayers);
        if (safeOnline < minimum) {
            return 0;
        }
        int stepSize = integer("online-rewards.population.players-per-step");
        int steps = 1 + (safeOnline - minimum) / stepSize;
        long bonus = (long) steps * integer("online-rewards.population.keys-per-step");
        return (int) Math.min(integer("online-rewards.population.maximum-bonus-keys"), bonus);
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
        Definition definition = definitions.get(canonicalKey(key));
        if (definition == null) throw new IllegalArgumentException("Unknown variable '" + key + "'.");
        return definition;
    }

    private static String canonicalKey(String key) {
        String normalized = String.valueOf(key).strip().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith(LEGACY_REWARD_PREFIX)) {
            return normalized;
        }
        String suffix = normalized.substring(LEGACY_REWARD_PREFIX.length());
        if (suffix.startsWith("online.")) {
            suffix = "population." + suffix.substring("online.".length());
        }
        return ONLINE_REWARD_PREFIX + suffix;
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
        Map<String, String> pairs = Map.ofEntries(
                Map.entry("amethyst-events.minimum-delay-minutes", "amethyst-events.maximum-delay-minutes"),
                Map.entry("airdrop.rarity-radius.common.minimum", "airdrop.rarity-radius.common.maximum"),
                Map.entry("airdrop.rarity-radius.rare.minimum", "airdrop.rarity-radius.rare.maximum"),
                Map.entry("airdrop.rarity-radius.legendary.minimum", "airdrop.rarity-radius.legendary.maximum"),
                Map.entry("airdrop.rarity-radius.mythic.minimum", "airdrop.rarity-radius.mythic.maximum"),
                Map.entry("airdrop.rarity.common.minimum-keys", "airdrop.rarity.common.maximum-keys"),
                Map.entry("airdrop.rarity.rare.minimum-keys", "airdrop.rarity.rare.maximum-keys"),
                Map.entry("airdrop.rarity.legendary.minimum-keys", "airdrop.rarity.legendary.maximum-keys"),
                Map.entry("airdrop.rarity.mythic.minimum-keys", "airdrop.rarity.mythic.maximum-keys"),
                Map.entry("huge-amethyst.milestone.minimum-keys", "huge-amethyst.milestone.maximum-keys"),
                Map.entry("huge-amethyst.milestone.minimum-diamonds", "huge-amethyst.milestone.maximum-diamonds"),
                Map.entry("huge-amethyst.milestone.minimum-emeralds", "huge-amethyst.milestone.maximum-emeralds"),
                Map.entry("huge-amethyst.milestone.minimum-gold", "huge-amethyst.milestone.maximum-gold"),
                Map.entry("huge-amethyst.completion.minimum-keys", "huge-amethyst.completion.maximum-keys"),
                Map.entry("huge-amethyst.completion.minimum-diamonds", "huge-amethyst.completion.maximum-diamonds"),
                Map.entry("huge-amethyst.completion.minimum-emeralds", "huge-amethyst.completion.maximum-emeralds"),
                Map.entry("huge-amethyst.completion.minimum-gold", "huge-amethyst.completion.maximum-gold"),
                Map.entry("chaos.jackpot.minimum-keys", "chaos.jackpot.maximum-keys")
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
        if (key.startsWith("online-rewards.tier.") && key.endsWith(".minimum-hours")) {
            int tier = Integer.parseInt(key.split("\\.")[2]);
            long hours = ((Number) value).longValue();
            if (tier > 1 && hours <= integer(
                    "online-rewards.tier." + (tier - 1) + ".minimum-hours"
            )) {
                throw new IllegalArgumentException(key + " must exceed the previous online tier.");
            }
            if (tier < 6 && hours >= integer(
                    "online-rewards.tier." + (tier + 1) + ".minimum-hours"
            )) {
                throw new IllegalArgumentException(key + " must stay below the next online tier.");
            }
        }
        if (key.startsWith("huge-amethyst.wave.") && key.endsWith(".health-percent")) {
            int wave = Integer.parseInt(key.split("\\.")[2]);
            long percent = ((Number) value).longValue();
            if (wave > 1 && percent >= integer(
                    "huge-amethyst.wave." + (wave - 1) + ".health-percent"
            )) {
                throw new IllegalArgumentException(key + " must stay below the previous wave.");
            }
            if (wave < AmethystBlockRewards.REWARD_HEALTH_PERCENTAGES.length
                    && percent <= integer(
                    "huge-amethyst.wave." + (wave + 1) + ".health-percent"
            )) {
                throw new IllegalArgumentException(key + " must stay above the next wave.");
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
            boolean migrated = false;
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                String canonical = canonicalKey(entry.getKey());
                Definition definition = definitions.get(canonical);
                if (definition == null) continue;
                Object value = definition.type() == Type.BOOLEAN
                        ? entry.getValue().getAsBoolean() : entry.getValue().getAsLong();
                parse(definition, String.valueOf(value));
                if (canonical.equals(entry.getKey())) {
                    overrides.put(canonical, value);
                } else {
                    overrides.putIfAbsent(canonical, value);
                    migrated = true;
                }
            }
            if (migrated) save();
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
