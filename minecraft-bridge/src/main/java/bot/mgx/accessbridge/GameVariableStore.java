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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Persistent, typed game values that take effect without reloading Paper. */
final class GameVariableStore {
    private static final String ONLINE_REWARD_PREFIX = "online-rewards.";
    private static final String LEGACY_REWARD_PREFIX = "afk-rewards.";

    /**
     * What a value is.
     *
     * <p>Numbers and flags were all the registry could hold, which is why the scoreboard
     * footer and every boss-bar colour stayed in code: there was nowhere to put them.
     * CHOICE is one of a fixed set; TEXT is free text with a length cap.
     */
    enum Type { INTEGER, BOOLEAN, CHOICE, TEXT, DECIMAL }

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
            boolean sensitive,
            List<String> choices,
            Double minimumDecimal,
            Double maximumDecimal
    ) {
        Definition {
            choices = choices == null ? List.of() : List.copyOf(choices);
        }
    }

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
    private final ConfigHistory history;
    private final CustomCatalogStore custom;
    /**
     * Overrides whose definition is not currently in the catalogue.
     *
     * <p>Removing a reward takes its weight variable away with it. Discarding the tuning
     * at that moment would mean putting the reward back later silently resets it, so the
     * value waits here instead and is picked up again if the definition returns.
     */
    private final Map<String, Object> parked = new LinkedHashMap<>();

    GameVariableStore(Path file, FileConfiguration config) throws IOException {
        this(file, config, null);
    }

    GameVariableStore(Path file, FileConfiguration config, CustomCatalogStore custom)
            throws IOException {
        this.file = file;
        this.custom = custom;
        Files.createDirectories(file.getParent());
        this.history = new ConfigHistory(
                file.resolveSibling("game-variables-history.json"));
        defineCore(config);
        defineWorldAndMobs(config);
        definePlayerAndWorld(config);
        definePresentationAndItems(config);
        defineShopPricing();
        defineEffectsAndGuards();
        defineRemainingWorldValues();
        defineTail();
        defineLastValues();
        defineTeleportAndBounty();
        defineFinalValues();
        defineMessages();
        defineMoreMessages();
        defineVerificationMessages();
        defineOnlineRewards();
        defineEventRewards(config);
        defineCrateRewards();
        defineAirdropRewards();
        load();
    }

    /** The rewards this crate actually contains, after an owner's additions and removals. */
    List<CrateCatalog.Reward> rewards(CrateKind kind) {
        return CrateCatalog.effectiveRewards(kind, custom);
    }

    /** The Airdrop loot table as it stands. */
    List<AirdropCatalog.LootDefinitionView> loot() {
        return AirdropCatalog.effectiveLoot(custom);
    }

    /**
     * Rebuilds the catalogue-derived variables after an entry is added or removed.
     *
     * <p>Definitions are otherwise fixed at startup, which is what made adding a reward
     * a code change. Only the catalogue families are touched; core, event and reward-tier
     * definitions are untouched, and every override survives — a weight whose reward has
     * just been removed is parked rather than lost.
     */
    synchronized void rebuildCatalogue() {
        definitions.keySet().removeIf(key ->
                (key.startsWith("crate.") && key.endsWith(".weight"))
                        || key.startsWith("airdrop.loot."));
        defineCrateRewards();
        defineAirdropRewards();
        parked.putAll(overrides);
        overrides.keySet().removeIf(key -> !definitions.containsKey(key));
        parked.forEach((key, value) -> {
            if (definitions.containsKey(key)) overrides.putIfAbsent(key, value);
        });
        parked.keySet().removeIf(definitions::containsKey);
        save();
        changeObservers.forEach(observer -> observer.accept(""));
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
        // Named after the thing you actually win. "Hidden Amethyst jackpot" told an
        // owner nothing about what was at stake behind the number.
        String jackpot = CosmeticCatalog.hiddenAmethystRewards().stream()
                .map(CosmeticCatalog.Definition::displayName)
                .findFirst().orElse("the hidden jackpot cosmetic");
        integer("crate.hidden-amethyst-one-in", jackpot + " chance", "Crates",
                "One in this many crate openings wins " + jackpot
                        + ", ahead of the ordinary reward roll. Amethyst and Shard Crates only.",
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

    /**
     * Systems that had no live controls at all.
     *
     * <p>The registry was populated where the work happened to be — crates, Airdrops,
     * the Amethyst event — which left whole systems reachable only by editing Java or
     * config.yml and restarting. Mob spawning in particular read {@code config.yml} once
     * at construction, so changing how often an Amethyst mob appeared meant a restart
     * even though nothing about it needed one.
     */
    private void defineWorldAndMobs(FileConfiguration config) {
        integer("amethyst-mobs.one-in", "Amethyst mob rarity", "Amethyst Mobs",
                "One natural monster spawn in this many becomes an Amethyst mob.",
                config.getLong("amethyst-mobs.one-in", 5), 1, 10_000, "one in", false);
        integer("amethyst-mobs.minimum-keys", "Fewest keys dropped", "Amethyst Mobs",
                "Smallest key drop from killing an Amethyst mob.", 1, 0, 512, "keys", false);
        integer("amethyst-mobs.maximum-keys", "Most keys dropped", "Amethyst Mobs",
                "Largest key drop from killing an Amethyst mob.", 5, 0, 512, "keys", false);

        for (ServerEventType type : ServerEventType.values()) {
            // The factor was fixed because an event advertised as 2x that paid 3x would
            // be worse than none. The name is now derived from the factor instead, so it
            // can be changed and still say what it does.
            integer("events." + type.id() + ".multiplier", type.baseDisplayName() + " factor",
                    "Event Multipliers",
                    "How much the " + type.baseDisplayName()
                            + " event multiplies. Players are told this figure, so the"
                            + " announcement follows whatever it is set to.",
                    type.baseMultiplier(), 2, 100, "x", false);
        }
    }

    /**
     * Values that lived in config.yml and were therefore read once at startup.
     *
     * <p>None of them has a startup dependency: an AFK timeout, a teleport radius, a
     * combat tag. They needed a restart only because nothing had moved them into the
     * live registry. config.yml stays the source of the starting figure, so an existing
     * server keeps whatever it had set.
     */
    private void definePlayerAndWorld(FileConfiguration config) {
        integer("afk.timeout-seconds", "Idle before marked AFK", "Players",
                "Seconds of stillness before a player is marked AFK automatically.",
                config.getLong("afk-timeout-seconds", 300L), 30, 86_400, "seconds", false);
        bool("afk.invincible", "AFK players are protected", "Players",
                "Whether a player marked AFK cannot be damaged.",
                config.getBoolean("afk-invincible", true));
        integer("afk.combat-tag-seconds", "Combat tag", "Players",
                "Seconds after taking or dealing damage during which a player counts as"
                        + " in combat and cannot be marked AFK.",
                config.getLong("afk-combat-tag-seconds", 15L), 0, 3_600, "seconds", false);

        integer("rtp.minimum-radius", "Random teleport minimum", "Players",
                "Nearest distance from spawn a random teleport may land.",
                config.getLong("rtp.minimum-radius", 500), 0, 100_000, "blocks", false);
        integer("rtp.maximum-radius", "Random teleport maximum", "Players",
                "Farthest distance from spawn a random teleport may land.",
                config.getLong("rtp.maximum-radius", 25_000), 1, 100_000, "blocks", false);
        integer("rtp.attempts", "Random teleport attempts", "Players",
                "Safe-ground candidates checked before giving up on a random teleport.",
                config.getLong("rtp.attempts", 24), 1, 100, "attempts", false);

        integer("verification.expiry-seconds", "Verification expiry", "Players",
                "Seconds a pending verification stays valid before it lapses.",
                config.getLong("verification-expiry-seconds", 900L), 60, 604_800, "seconds", false);

        integer("world.border-radius", "World border", "World",
                "Distance from spawn to the Overworld border. The Nether is an eighth of"
                        + " this.",
                (long) config.getDouble("world.border-radius", WorldLimits.OVERWORLD_RADIUS),
                1_000, 29_999_984, "blocks", false);
        integer("world.max-view-distance", "View distance cap", "World",
                "Chunks a client may be sent. Zero leaves the panel's own value alone.",
                config.getLong("world.max-view-distance", WorldMemory.MAX_VIEW_DISTANCE),
                0, WorldMemory.ABSOLUTE_MAX_DISTANCE, "chunks", false);
        integer("world.max-simulation-distance", "Simulation distance cap", "World",
                "Chunks that keep ticking around a player.",
                config.getLong("world.max-simulation-distance", WorldMemory.MAX_SIMULATION_DISTANCE),
                0, WorldMemory.ABSOLUTE_MAX_DISTANCE, "chunks", false);

        bool("spawn.protection.enabled", "Spawn mob barrier", "World",
                "Whether hostile mobs are kept out of the box around spawn.",
                config.getBoolean("spawn.protection.enabled", true));
        integer("spawn.protection.min-x", "Spawn box west edge", "World",
                "Western edge of the protected spawn box.",
                config.getLong("spawn.protection.min-x", -50), -10_000, 10_000, "X", false);
        integer("spawn.protection.max-x", "Spawn box east edge", "World",
                "Eastern edge of the protected spawn box.",
                config.getLong("spawn.protection.max-x", 49), -10_000, 10_000, "X", false);
        integer("spawn.protection.min-z", "Spawn box north edge", "World",
                "Northern edge of the protected spawn box.",
                config.getLong("spawn.protection.min-z", -50), -10_000, 10_000, "Z", false);
        integer("spawn.protection.max-z", "Spawn box south edge", "World",
                "Southern edge of the protected spawn box.",
                config.getLong("spawn.protection.max-z", 49), -10_000, 10_000, "Z", false);

        integer("admin-events.radius", "Admin event radius", "Admin Event Rewards",
                "How far from the operator an admin event reaches.",
                (long) config.getDouble("abuse-radius", 64), 1, 512, "blocks", false);

        integer("clans.maximum-members", "Clan size limit", "Clans",
                "Members one clan may hold, the leader included.",
                ClanStore.MAX_MEMBERS, 2, 200, "members", false);
        integer("clans.maximum-allies", "Alliance limit", "Clans",
                "Alliances one clan may hold at once.", ClanStore.MAX_ALLIES, 0, 50, "allies", false);
        integer("clans.invite-minutes", "Clan invite expiry", "Clans",
                "Minutes a clan invitation stays open.", 5, 1, 1_440, "minutes", false);
        integer("clans.ally-offer-minutes", "Alliance offer expiry", "Clans",
                "Minutes an alliance offer stays open.", 10, 1, 1_440, "minutes", false);

        integer("auction.maximum-listings", "Auction slots per player", "Auction House",
                "Listings one player may have running at once.",
                AuctionStore.MAX_LISTINGS_PER_PLAYER, 1, 200, "listings", false);
        integer("auction.listing-hours", "Listing lifetime", "Auction House",
                "Hours a listing stands before it expires back to its seller.",
                48, 1, 8_760, "hours", false);
        integer("auction.maximum-price", "Highest asking price", "Auction House",
                "Most a player may ask for one listing.",
                AuctionStore.MAX_PRICE, 1, 1_000_000_000, "money", false);

        integer("combat.tag-seconds", "Combat log window", "Players",
                "Seconds after combat during which logging out counts as fleeing.",
                CombatTag.DEFAULT_SECONDS, 0, 3_600, "seconds", false);
    }

    /**
     * Presentation and item strength, which had nowhere to live until the registry
     * learned to hold something other than a number.
     */
    private void definePresentationAndItems(FileConfiguration config) {
        List<String> colours = List.of(
                "PINK", "BLUE", "RED", "GREEN", "YELLOW", "PURPLE", "WHITE"
        );
        choice("bars.airdrop.colour", "Airdrop bar colour", "Boss Bars",
                "Colour of the boss bar shown while an Airdrop is standing.",
                "PURPLE", colours);
        choice("bars.huge-amethyst.colour", "Huge Amethyst bar colour", "Boss Bars",
                "Colour of the boss bar shown while a Huge Amethyst Block is up.",
                "PURPLE", colours);
        choice("bars.broadcast.colour", "Broadcast bar colour", "Boss Bars",
                "Colour of the timed bar used by /broadcast.", "RED", colours);
        choice("bars.event.colour", "Event bar colour", "Boss Bars",
                "Colour of the boss bar shown while a multiplier event runs.",
                "YELLOW", colours);

        text("scoreboard.footer", "Scoreboard footer", "Presentation",
                "The last line of the in-game sidebar.",
                config.getString("scoreboard.footer", "discord.gg/mgx"), 32);

        // Potions were fixed in code: a level and a duration per kind, seven of them.
        definePotion("healing", "Healing", 0, 2);
        definePotion("strength", "Strength", 5, 2);
        definePotion("swiftness", "Swiftness", 5, 2);
        definePotion("regeneration", "Regeneration", 2, 2);
        definePotion("night_vision", "Night Vision", 8, 1);
        definePotion("water_breathing", "Water Breathing", 8, 1);
        definePotion("fire_resistance", "Fire Resistance", 5, 1);

        for (Map.Entry<String, Integer> mark : new java.util.TreeMap<>(
                CustomEnchants.MAX_LEVEL).entrySet()) {
            integer("enchants." + mark.getKey() + ".maximum-level",
                    capitalise(mark.getKey()) + " cap", "Enchantments",
                    "Highest level of " + capitalise(mark.getKey())
                            + " a crate book may carry.",
                    mark.getValue(), 1, 10, "level", false);
        }
    }

    /**
     * Shop pricing as multipliers rather than 400-odd individual prices.
     *
     * <p>One figure for all buying, one for all selling, and one per shelf, each a
     * percentage of the catalogue price. 100 leaves a price exactly as it ships, 50
     * halves it, 200 doubles it. The two stack: a shelf at 50 inside a shop at 200 sells
     * at the catalogue price.
     */
    private void defineShopPricing() {
        integer("shop.buy-percent", "All buy prices", "Shop",
                "What players pay, as a percentage of the listed price.",
                100, 1, 10_000, "percent", false);
        integer("shop.sell-percent", "All sell prices", "Shop",
                "What players receive for selling, as a percentage of the listed price.",
                100, 1, 10_000, "percent", false);
        for (ShopCatalog.Category category : ShopCatalog.Category.values()) {
            integer("shop.category." + category.name().toLowerCase(Locale.ROOT) + ".buy-percent",
                    category.title() + " prices", "Shop Shelves",
                    "Buy prices on the " + category.title()
                            + " shelf, as a percentage. Stacks with the shop-wide figure.",
                    100, 1, 10_000, "percent", false);
        }
    }

    /**
     * How cosmetics move, how Airdrop guards behave, and how the odds balancer judges a
     * crate. All of it was in code, and all of it is the sort of thing an owner tunes by
     * watching rather than by reasoning, which is exactly what a restart makes painful.
     */
    private void defineEffectsAndGuards() {
        decimal("cosmetics.aura.scroll-speed", "Aura scroll speed", "Cosmetics",
                "How fast an aura's pattern travels around the player.", 0.06, 0.001, 1.0, "per frame");
        decimal("cosmetics.aura.scroll-spread", "Aura spread", "Cosmetics",
                "How far an aura's particles sit from the player.", 0.34, 0.01, 3.0, "blocks");
        decimal("cosmetics.aura.pulse-speed", "Aura pulse speed", "Cosmetics",
                "How quickly an aura breathes in and out.", 0.045, 0.001, 1.0, "per frame");
        decimal("cosmetics.shimmer.width", "Shimmer width", "Cosmetics",
                "Width of the moving highlight on a cosmetic nameplate.", 5.0, 1.0, 40.0, "characters");
        decimal("cosmetics.shimmer.drift", "Shimmer drift", "Cosmetics",
                "How fast that highlight travels.", 0.02, 0.001, 1.0, "per frame");
        integer("cosmetics.view-distance", "Cosmetic view distance", "Cosmetics",
                "How far away a player's cosmetics stay visible.", 48, 8, 128, "blocks", false);
        integer("cosmetics.hearing-distance", "Cosmetic hearing distance", "Cosmetics",
                "How far away a cosmetic's sounds carry.", 16, 4, 64, "blocks", false);
        decimal("cosmetics.aura.sound-volume", "Aura sound volume", "Cosmetics",
                "Volume of the sound an aura makes.", 0.45, 0.0, 1.0, "volume");
        integer("cosmetics.trail.history", "Trail length", "Cosmetics",
                "Points of a player's path a trail remembers.", 14, 2, 64, "points", false);

        integer("airdrop.guard.inner-ring", "Guard inner ring", "Airdrop Guards",
                "How close the inner ring of guards stands to the drop.", 6, 1, 64, "blocks", false);
        integer("airdrop.guard.outer-ring", "Guard outer ring", "Airdrop Guards",
                "How far out the outer ring of guards stands.", 14, 2, 128, "blocks", false);
        integer("airdrop.guard.hunt-radius", "Guard hunt radius", "Airdrop Guards",
                "How far a guard looks for someone to chase.", 40, 4, 128, "blocks", false);
        integer("airdrop.guard.follow-range", "Guard follow range", "Airdrop Guards",
                "How far a guard will follow before giving up.", 48, 4, 128, "blocks", false);
        decimal("airdrop.guard.speed", "Guard speed", "Airdrop Guards",
                "Guard movement speed. 1 is an ordinary mob.", 1.2, 0.1, 4.0, "x");

        integer("crates.balance.floor-percent", "Luck floor", "Crate Balance",
                "Lowest the balancer will push a player's rare-reward luck.",
                50, 1, 100, "percent", false);
        integer("crates.balance.ceiling-percent", "Luck ceiling", "Crate Balance",
                "Highest the balancer will push a player's rare-reward luck.",
                200, 100, 1_000, "percent", false);
        integer("crates.balance.minimum-sample", "Balancer sample size", "Crate Balance",
                "Openings observed before the balancer will adjust anything.",
                500, 10, 100_000, "openings", false);
        decimal("crates.balance.tolerance-sigma", "Balancer tolerance", "Crate Balance",
                "How far from the advertised rate a run must drift before the balancer"
                        + " corrects it, in standard deviations.", 2.0, 0.5, 6.0, "sigma");
        integer("crates.balance.window-openings", "Balancer window", "Crate Balance",
                "Openings the balancer looks back over.", 4_000, 100, 1_000_000, "openings", false);
    }

    /**
     * The long tail: spawn, the verification lobby, world geometry, and the last of the
     * event tuning. Individually small, and each one a restart away from being changed.
     */
    private void defineRemainingWorldValues() {
        integer("spawn.x", "Spawn X", "World",
                "Where the Overworld spawn point sits. Anything that moves it is put back here.",
                WorldSpawn.X, -100_000, 100_000, "X", false);
        integer("spawn.y", "Spawn Y", "World", "Height of the Overworld spawn point.",
                WorldSpawn.Y, -64, 320, "Y", false);
        integer("spawn.z", "Spawn Z", "World", "Where the Overworld spawn point sits.",
                WorldSpawn.Z, -100_000, 100_000, "Z", false);
        integer("spawn.radius", "Spawn scatter", "World",
                "How far from the spawn point a join may be placed. Zero pins everyone to"
                        + " the exact block.",
                WorldSpawn.RADIUS, 0, 256, "blocks", false);

        integer("world.nether-scale", "Nether scale", "World",
                "How much smaller the Nether border is than the Overworld's.",
                (long) WorldLimits.NETHER_SCALE, 1, 64, "x", false);
        integer("world.border-warning", "Border warning distance", "World",
                "How far from the border the red fog and warning sounds begin.",
                WorldLimits.WARNING_DISTANCE, 0, 1_000, "blocks", false);

        integer("verification.request-cooldown-seconds", "Verification retry wait", "Players",
                "Seconds a player in the lobby must wait between /verify attempts.",
                10, 1, 3_600, "seconds", false);

        integer("crates.luck.minimum-percent", "Lowest crate luck", "Crate Balance",
                "Floor on a player's rare-reward luck, before the balancer.",
                CrateCatalog.NO_LUCK_PERCENT, 1, 1_000, "percent", false);
        integer("crates.luck.maximum-percent", "Highest crate luck", "Crate Balance",
                "Ceiling on a player's rare-reward luck, including potions.",
                CrateCatalog.MAX_LUCK_PERCENT, 1, 10_000, "percent", false);

        integer("chaos.maximum-swarm", "Largest admin-event swarm", "Admin Event Rewards",
                "Most entities one admin event may put in the world at once.",
                120, 1, 2_000, "entities", false);
        decimal("chaos.alfredo.scale", "Alfredo size", "Admin Event Rewards",
                "How large Alfredo is, against an ordinary mob.", 16.0, 1.0, 64.0, "x");

        integer("huge-amethyst.mine-reach", "Mining reach", "Huge Amethyst",
                "How far a player may stand and still damage the block.",
                7, 2, 32, "blocks", false);
        integer("airdrop.border-margin", "Airdrop border margin", "Airdrops",
                "How far inside the world border an Airdrop must land.",
                24, 0, 1_000, "blocks", false);
    }

    /**
     * The last of it: what perks are worth, what a Clan Battle pays, how long an event
     * may run, and how long a crate reveal takes.
     */
    private void defineTail() {
        decimal("perks.elite.damage-bonus", "Elite damage bonus", "Perks",
                "Extra damage an Elite player deals, as a fraction. 0.15 is 15% more.",
                0.15, 0.0, 5.0, "fraction");
        decimal("perks.booster.damage-bonus", "Booster damage bonus", "Perks",
                "Extra damage a boosting player deals, as a fraction.",
                0.10, 0.0, 5.0, "fraction");
        decimal("perks.booster.exhaustion", "Booster hunger rate", "Perks",
                "How fast a boosting player gets hungry. 0.9 is 10% slower than normal.",
                0.90, 0.1, 2.0, "x");

        integer("clan-battle.gold-shards", "First place Shards", "Clan Battles",
                "Shards paid to each member of the winning clan.", 10, 0, 1_000, "shards", false);
        integer("clan-battle.silver-shards", "Second place Shards", "Clan Battles",
                "Shards paid to each member of the runner-up clan.", 5, 0, 1_000, "shards", false);
        integer("clan-battle.bronze-shards", "Third place Shards", "Clan Battles",
                "Shards paid to each member of the third-placed clan.", 3, 0, 1_000, "shards", false);

        integer("events.minimum-seconds", "Shortest event", "Event Multipliers",
                "Least time a multiplier event may be set to run.",
                60, 10, 86_400, "seconds", false);
        integer("events.maximum-seconds", "Longest event", "Event Multipliers",
                "Most time a multiplier event may be set to run.",
                1_209_600, 60, 31_536_000, "seconds", false);

        integer("cosmetics.reveal.exotic-ms", "Exotic reveal length", "Cosmetics",
                "How long the Exotic crate reveal runs.", 15_500, 1_000, 120_000,
                "milliseconds", false);
        integer("cosmetics.reveal.secret-ms", "Secret reveal length", "Cosmetics",
                "How long the Secret crate reveal runs.", 18_000, 1_000, 120_000,
                "milliseconds", false);
        integer("cosmetics.trail.reset-distance", "Trail reset distance", "Cosmetics",
                "How far a player must move at once before their trail restarts.",
                12, 1, 128, "blocks", false);
        integer("cosmetics.aura.sound-every", "Aura sound interval", "Cosmetics",
                "Frames between an aura's sounds. Higher is quieter.",
                32, 1, 400, "frames", false);
    }

    /** The genuinely last few: the Amethyst shop's daily stock, and the launch sequence. */
    private void defineLastValues() {
        integer("amethyst-shop.price", "Amethyst shop price", "Amethyst Shop",
                "What one Amethyst shop item costs.",
                5_000_000L, 1, 1_000_000_000, "money", false);
        integer("amethyst-shop.minimum-stock", "Fewest items stocked", "Amethyst Shop",
                "Smallest number of items the Amethyst shelf carries each day.",
                2, 1, 27, "items", false);
        integer("amethyst-shop.maximum-stock", "Most items stocked", "Amethyst Shop",
                "Largest number of items the Amethyst shelf carries each day.",
                3, 1, 27, "items", false);

        integer("launch.countdown-seconds", "Launch countdown", "Server",
                "Seconds counted down before the barriers come away.",
                10, 3, 300, "seconds", false);
        integer("launch.pvp-hold-hours", "PvP hold after launch", "Server",
                "Hours PvP stays off after the launch countdown finishes.",
                5, 0, 168, "hours", false);
    }

    /** Teleport warmups, the bounty floor, and the random-teleport border margin. */
    private void defineTeleportAndBounty() {
        integer("teleport.warmup-seconds", "Teleport warmup", "Players",
                "Seconds a player must stand still before a teleport completes.",
                5, 0, 60, "seconds", false);
        integer("rtp.border-margin", "Random teleport margin", "Players",
                "How far inside the world border a random teleport must land.",
                32, 0, 1_000, "blocks", false);
        integer("bounty.minimum", "Smallest bounty", "Economy",
                "Least a player may put on someone's head.",
                100, 1, 100_000_000, "money", false);
    }

    /** The genuine last of it. */
    private void defineFinalValues() {
        integer("give.maximum-keys", "Most keys per give", "Players",
                "Largest number of keys one give may hand over at a time.",
                64, 1, 10_000, "keys", false);
        integer("amethyst-items.active-hours", "Amethyst item lifetime", "Amethyst Shop",
                "Hours an activated Amethyst item stays usable.", 24, 1, 8_760, "hours", false);
        integer("amethyst-items.efficiency-level", "Amethyst tool Efficiency", "Amethyst Shop",
                "Efficiency level Amethyst tools carry.", 5, 1, 10, "level", false);
        integer("autopay.minimum-interval-seconds", "Fastest auto-pay", "Economy",
                "Shortest interval a player may set for automatic payments.",
                5, 1, 86_400, "seconds", false);
        integer("autopay.maximum-interval-seconds", "Slowest auto-pay", "Economy",
                "Longest interval a player may set for automatic payments.",
                3_600, 5, 604_800, "seconds", false);
        integer("admin-events.minimum-radius", "Smallest event radius", "Admin Event Rewards",
                "Least an admin event's radius may be set to.", 4, 1, 512, "blocks", false);
        integer("admin-events.maximum-radius", "Largest event radius", "Admin Event Rewards",
                "Most an admin event's radius may be set to.", 256, 4, 2_048, "blocks", false);
        integer("activity-feed.retained", "Activity log length", "Presentation",
                "In-game actions kept for the panel's activity page.",
                300, 20, 5_000, "entries", false);
    }

    private void definePotion(String id, String label, int minutes, int level) {
        String base = "potions." + id + ".";
        if (minutes > 0) {
            integer(base + "minutes", label + " duration", "Potions",
                    "How long a " + label + " potion lasts.", minutes, 1, 60, "minutes", false);
        }
        integer(base + "level", label + " strength", "Potions",
                "Potion level. 1 is the ordinary effect, 2 is the II variant.",
                level, 1, 5, "level", false);
    }

    private static String capitalise(String word) {
        return word.isEmpty() ? word
                : Character.toUpperCase(word.charAt(0)) + word.substring(1);
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

    /**
     * Player-facing text, editable while the server runs.
     *
     * <p>These were Java string literals, so changing a word meant a build and a
     * restart. MiniMessage keeps the colour and emphasis editable as text, and clearing
     * one to empty is how a message is switched off entirely.
     */
    private void defineMessages() {
        message("messages.amethyst.reward-wave",
                "Amethyst reward wave",
                "A contributor's message at each reward threshold. <keys> is how many they got.",
                "<#b57edc><bold>Reward wave! </bold></#b57edc><white>You received <keys> keys.</white>");
        message("messages.amethyst.block-broken",
                "Amethyst block broken",
                "A contributor's message when the block finally shatters. <keys> is their total.",
                "<#b57edc><bold>Block broken! </bold></#b57edc><white>You received <keys> keys.</white>");
        message("messages.amethyst.shattered",
                "Amethyst shattered broadcast",
                "Shown to the whole server when the Huge Amethyst Block is destroyed.",
                "<#b57edc>The Huge Amethyst Block shattered! Everyone who helped break it was rewarded.</#b57edc>");
        // The landing announcement is a designed banner rather than a line of text, so
        // it has no template to edit; these two are the plain sentences that do.
        message("messages.airdrop.expired",
                "Airdrop expired broadcast",
                "Shown when an Airdrop times out unclaimed. <rarity> is its rarity.",
                "<white>The <rarity> Amethyst Airdrop expired unclaimed.</white>");
        message("messages.airdrop.disturbed",
                "Airdrop disturbed broadcast",
                "Shown when an Airdrop is removed because its chest was interfered with.",
                "<white>The Amethyst Airdrop vanished after its chest was disturbed.</white>");
    }

    private void defineMoreMessages() {
        message("messages.clanbattle.started",
                "Clan battle started",
                "Server-wide when a clan battle begins. <battle> and <objective> are filled in.",
                "<gold><bold><battle></bold></gold><white> has begun! </white><yellow><objective></yellow>");
        message("messages.clanbattle.ends-in",
                "Clan battle time remaining",
                "The countdown line that follows the start announcement. <remaining> is the time left.",
                "<yellow>Ends in <remaining>.</yellow>");
        message("messages.clanbattle.warning",
                "Clan battle ending soon",
                "Server-wide at each countdown milestone. <battle> and <remaining> are filled in.",
                "<gold><bold><battle></bold></gold><white> ends in </white><yellow><bold><remaining></bold></yellow><white>!</white>");
        message("messages.clanbattle.cancelled",
                "Clan battle cancelled",
                "Server-wide when a battle is called off. <battle> is its name.",
                "<gray><battle> was cancelled. No rewards were awarded.</gray>");
        message("messages.clanbattle.ended",
                "Clan battle ended",
                "Server-wide when a battle finishes. <battle> is its name.",
                "<gold><bold><battle> has ended!</bold></gold>");
        message("messages.clanbattle.no-winner",
                "Clan battle with no winner",
                "Server-wide when a battle ends with nobody having scored.",
                "<gray>No clan recorded an opening, so no rewards were awarded.</gray>");
    }

    private void defineVerificationMessages() {
        message("messages.verify.step-one",
                "Verification step one",
                "The line a new player reads in the verification room, telling them what to type.",
                "<gold><bold>STEP 1 OF 2 \u2022 </bold></gold><yellow>/verify <Discord username></yellow>");
        message("messages.verify.step-one-chat",
                "Verification step one, in chat",
                "The same instruction in chat, where it stays readable after the action bar fades.",
                "<yellow>Step 1 of 2: type /verify <your Discord username></yellow>");
        message("messages.verify.step-two",
                "Verification step two",
                "Shown once the request is sent and the player is waiting on their Discord DM.",
                "<gold><bold>STEP 2 OF 2 \u2022 </bold></gold><yellow>Open newest DM \u2192 Yes, This Is Me</yellow>");
        message("messages.bounty.claimed",
                "Bounty claimed",
                "The claimer's message. <amount> is what they were paid.",
                "<green>Bounty claimed \u2022 +<amount></green>");
        message("messages.bounty.taken",
                "Bounty taken from you",
                "Shown to the player whose bounty was collected. <player> is who claimed it.",
                "<red>Your bounty was claimed by <player>.</red>");
    }

    private void message(String key, String label, String description, String value) {
        text(key, label, "Messages", description, value, 240);
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
            for (CrateCatalog.Reward reward : CrateCatalog.effectiveRewards(kind, custom)) {
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
        for (AirdropCatalog.LootDefinitionView loot : AirdropCatalog.effectiveLoot(custom)) {
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
                minimum, maximum, unit, sensitive, List.of(), null, null
        ));
    }

    private void bool(String key, String label, String category, String description, boolean value) {
        definitions.put(key, new Definition(
                key, label, category, description, Type.BOOLEAN, value,
                null, null, "", false, List.of(), null, null
        ));
    }

    /**
     * A fractional value, such as a speed or a multiplier below one.
     *
     * <p>Whole numbers covered most of the catalogue but not the ones that describe how
     * something moves — an aura drifting at 0.06 has no sensible integer form, and
     * rounding it to nothing was the alternative to leaving it in code.
     */
    private void decimal(
            String key, String label, String category, String description,
            double value, double minimum, double maximum, String unit
    ) {
        definitions.put(key, new Definition(
                key, label, category, description, Type.DECIMAL, value,
                null, null, unit, false, List.of(), minimum, maximum
        ));
    }

    /** One of a fixed set of names, such as a boss-bar colour. */
    private void choice(
            String key, String label, String category, String description,
            String value, List<String> options
    ) {
        definitions.put(key, new Definition(
                key, label, category, description, Type.CHOICE, value,
                null, null, "", false, options, null, null
        ));
    }

    /** Free text with a length cap, such as a line shown under the scoreboard. */
    private void text(
            String key, String label, String category, String description,
            String value, int maximumLength
    ) {
        definitions.put(key, new Definition(
                key, label, category, description, Type.TEXT, value,
                0L, (long) maximumLength, "characters", false, List.of(), null, null
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

    /**
     * A configured boss-bar colour, or the built-in one if it no longer resolves.
     *
     * <p>Falling back rather than throwing: a colour that fails to parse should show the
     * wrong shade, not stop the event it belongs to from starting.
     */
    net.kyori.adventure.bossbar.BossBar.Color barColour(
            String key, net.kyori.adventure.bossbar.BossBar.Color fallback
    ) {
        try {
            return net.kyori.adventure.bossbar.BossBar.Color.valueOf(string(key));
        } catch (RuntimeException unknown) {
            return fallback;
        }
    }

    /** A choice or free-text value, as text. */
    synchronized String string(String key) {
        Definition definition = definition(key);
        return String.valueOf(overrides.getOrDefault(definition.key(), definition.defaultValue()));
    }

    /** A fractional value. */
    synchronized double decimal(String key) {
        Definition definition = definition(key);
        Object value = overrides.getOrDefault(definition.key(), definition.defaultValue());
        return ((Number) value).doubleValue();
    }

    synchronized boolean bool(String key) {
        Definition definition = definition(key);
        return (Boolean) overrides.getOrDefault(definition.key(), definition.defaultValue());
    }

    /** One edit in a proposed change set. A reset carries no value. */
    record Edit(String key, String value, boolean reset) {
        static Edit set(String key, String value) {
            return new Edit(key, value, false);
        }

        static Edit reset(String key) {
            return new Edit(key, null, true);
        }
    }

    /** Why a proposed change set cannot be applied. Empty means it can. */
    record Finding(String key, String message) { }

    /** What one key actually moved, for the trail and for rollback. */
    record Change(String key, Object before, Object after) { }

    /** Raised instead of applying a change set that would leave the game inconsistent. */
    static final class InvalidChangeSet extends IllegalArgumentException {
        private final transient List<Finding> findings;

        InvalidChangeSet(List<Finding> findings) {
            super(findings.stream().map(Finding::message).collect(Collectors.joining(" ")));
            this.findings = List.copyOf(findings);
        }

        List<Finding> findings() {
            return findings;
        }
    }

    synchronized String set(String key, String raw) {
        Definition definition = definition(key);
        apply(List.of(Edit.set(definition.key(), raw)), "");
        return describe(definition, resolve(Map.of(), Set.of(), definition.key()));
    }

    synchronized String reset(String key) {
        Definition definition = definition(key);
        apply(List.of(Edit.reset(definition.key())), "");
        return describe(definition, definition.defaultValue());
    }

    /**
     * Checks a whole change set without touching anything.
     *
     * <p>Judging one field at a time cannot see the combinations that matter. Raising a
     * minimum above its current maximum is fine when the maximum moves with it in the
     * same publish, and every weight in a distribution can be lowered safely right up
     * until the last one that keeps the total above zero. Both readings need the state
     * the set would leave behind, not the state it started from.
     */
    synchronized List<Finding> validate(List<Edit> edits) {
        List<Finding> findings = new ArrayList<>();
        Map<String, Object> pending = new LinkedHashMap<>();
        Set<String> resets = new LinkedHashSet<>();
        project(edits, pending, resets, findings);
        if (!findings.isEmpty()) {
            // Ordering and totals are meaningless while a value is unparseable.
            return List.copyOf(findings);
        }
        pending.forEach((key, value) -> validatePair(key, value, pending, resets, findings));
        validateDistributions(pending, resets, findings);
        return List.copyOf(findings);
    }

    /**
     * Applies a validated change set atomically, or nothing at all.
     *
     * <p>One publish, one write, one history entry. Half of a rebalance is worse than
     * none of it: the old per-key path could leave a distribution mid-edit and, because
     * weights may legitimately reach zero, could leave one summing to zero — which is
     * not a bad balance but a crash the next time anything rolls against it.
     */
    synchronized List<Change> apply(List<Edit> edits, String actor) {
        List<Finding> findings = validate(edits);
        if (!findings.isEmpty()) {
            throw new InvalidChangeSet(findings);
        }
        Map<String, Object> pending = new LinkedHashMap<>();
        Set<String> resets = new LinkedHashSet<>();
        project(edits, pending, resets, new ArrayList<>());

        List<Change> changes = new ArrayList<>();
        boolean rewritten = false;
        for (Map.Entry<String, Object> entry : pending.entrySet()) {
            String key = entry.getKey();
            Definition definition = definitions.get(key);
            Object before = overrides.getOrDefault(key, definition.defaultValue());
            Object after = entry.getValue();
            // An override is only worth storing when it differs from the catalogue, so
            // setting a value back to its default clears it rather than recording the
            // default as a deliberate choice. That keeps "overridden" meaning what it
            // says on the panel.
            boolean wantsOverride = !resets.contains(key) && !after.equals(definition.defaultValue());
            if (wantsOverride != overrides.containsKey(key)) {
                rewritten = true;
                if (wantsOverride) {
                    overrides.put(key, after);
                } else {
                    overrides.remove(key);
                }
            } else if (wantsOverride && !before.equals(after)) {
                rewritten = true;
                overrides.put(key, after);
            }
            if (!before.equals(after)) {
                changes.add(new Change(key, before, after));
            }
        }
        if (!rewritten) {
            return List.of();
        }
        save();
        if (!changes.isEmpty()) {
            // Nothing to undo when only the override bookkeeping was normalised.
            history.record(actor, changes);
        }
        changes.forEach(change -> changeObservers.forEach(observer -> observer.accept(change.key())));
        return List.copyOf(changes);
    }

    /**
     * Applies a change set and hands back the publish it produced.
     *
     * <p>Reads the entry straight back under the same monitor, so the identifier belongs
     * to this publish and not to whatever happened to be newest. Empty when nothing
     * moved, because a publish that changed nothing is not one.
     */
    synchronized Optional<ConfigHistory.Publish> publish(List<Edit> edits, String actor) {
        return apply(edits, actor).isEmpty()
                ? Optional.empty()
                : history.recent(1).stream().findFirst();
    }

    /** Puts every value in a publish back the way it was, as one further publish. */
    synchronized List<Change> rollback(String publishId, String actor) {
        List<Change> recorded = history.changesOf(publishId).orElseThrow(
                () -> new IllegalArgumentException("No recorded change '" + publishId + "'.")
        );
        List<Edit> undo = new ArrayList<>();
        for (Change change : recorded) {
            Definition definition = definitions.get(change.key());
            if (definition == null) {
                continue;
            }
            undo.add(definition.defaultValue().equals(change.before())
                    ? Edit.reset(change.key())
                    : Edit.set(change.key(), String.valueOf(change.before())));
        }
        if (undo.isEmpty()) {
            throw new IllegalArgumentException("Nothing in that change can be restored.");
        }
        return apply(undo, actor);
    }

    ConfigHistory history() {
        return history;
    }

    /**
     * Resolves a change set into the values it would leave behind.
     *
     * <p>A reset lands in {@code pending} as the catalogue default so ordering and total
     * checks see the same number the game would, and in {@code resets} so the apply step
     * knows to drop the override rather than store the default as one.
     */
    private void project(
            List<Edit> edits, Map<String, Object> pending, Set<String> resets, List<Finding> findings
    ) {
        for (Edit edit : edits) {
            Definition definition = definitions.get(canonicalKey(edit.key()));
            if (definition == null) {
                findings.add(new Finding(edit.key(), "Unknown variable '" + edit.key() + "'."));
                continue;
            }
            if (edit.reset()) {
                pending.put(definition.key(), definition.defaultValue());
                resets.add(definition.key());
                continue;
            }
            try {
                pending.put(definition.key(), parse(definition, edit.value()));
                resets.remove(definition.key());
            } catch (IllegalArgumentException rejected) {
                findings.add(new Finding(definition.key(), rejected.getMessage()));
            }
        }
    }

    /** A value as the change set would leave it, falling back to what is stored now. */
    private Object resolve(Map<String, Object> pending, Set<String> resets, String key) {
        if (pending.containsKey(key)) {
            return pending.get(key);
        }
        Definition definition = definitions.get(key);
        return overrides.getOrDefault(key, definition.defaultValue());
    }

    private int resolveInt(Map<String, Object> pending, Set<String> resets, String key) {
        return Math.toIntExact(((Number) resolve(pending, resets, key)).longValue());
    }

    /**
     * Refuses any change set that would empty a distribution.
     *
     * <p>Airdrop rarity and material weights may each fall to zero, so nothing stopped
     * the last positive one going too. {@code randomAirdropRarity} and
     * {@code AirdropCatalog.randomLoot} both throw on a zero total, which surfaces as an
     * Airdrop that fails to spawn rather than as anything pointing at the setting that
     * caused it.
     */
    private void validateDistributions(
            Map<String, Object> pending, Set<String> resets, List<Finding> findings
    ) {
        Map<String, Long> totals = new LinkedHashMap<>();
        Map<String, String> firstKeyOf = new LinkedHashMap<>();
        for (String key : definitions.keySet()) {
            String table = SettingMetadata.table(key).orElse(null);
            if (table == null) {
                continue;
            }
            totals.merge(table, (long) resolveInt(pending, resets, key), Long::sum);
            firstKeyOf.putIfAbsent(table, key);
        }
        totals.forEach((table, total) -> {
            if (total <= 0) {
                findings.add(new Finding(firstKeyOf.get(table),
                        "Every weight in " + table + " would be zero, so nothing could be"
                                + " drawn from it. Leave at least one above zero."));
            }
        });
    }

    synchronized Optional<Definition> find(String key) {
        return Optional.ofNullable(definitions.get(canonicalKey(key)));
    }

    /** Set once the plugin is up, so the console gets actions alongside settings. */
    private JsonObject actionCatalogue = new JsonObject();

    void actionCatalogue(JsonObject catalogue) {
        this.actionCatalogue = catalogue == null ? new JsonObject() : catalogue;
    }

    /** Live readings that ride along with the settings rather than needing their own call. */
    private JsonObject activityFeed = new JsonObject();
    private JsonObject auctionListings = new JsonObject();

    void liveReadings(JsonObject activity, JsonObject auction) {
        this.activityFeed = activity == null ? new JsonObject() : activity;
        this.auctionListings = auction == null ? new JsonObject() : auction;
    }

    /** Live server figures, supplied by the plugin because this store owns none of them. */
    private volatile java.util.function.Supplier<JsonObject> metricsSupplier = JsonObject::new;

    void metricsSource(java.util.function.Supplier<JsonObject> source) {
        if (source != null) {
            metricsSupplier = source;
        }
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
            if (definition.minimumDecimal() != null) {
                row.addProperty("minimum", definition.minimumDecimal());
                row.addProperty("maximum", definition.maximumDecimal());
                row.addProperty("step", "any");
            }
            if (!definition.choices().isEmpty()) {
                JsonArray options = new JsonArray();
                definition.choices().forEach(options::add);
                row.add("choices", options);
            }
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
        root.add("history", history.snapshot(ConfigHistory.RETAINED_PUBLISHES));
        // What an owner has added to or taken out of the catalogues, so the console can
        // show removed built-ins as restorable rather than simply absent.
        if (custom != null) {
            root.add("catalog", custom.snapshot());
        }
        root.add("materials", itemMaterials());
        root.add("action_catalogue", actionCatalogue);
        root.add("activity", activityFeed);
        root.add("auction", auctionListings);
        // The figures an owner is actually tuning. The panel samples these on its own
        // schedule and keeps the history, so a change can be read against its effect.
        root.add("metrics", metricsSupplier.get());
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
     * Item materials an owner may add, for the console's picker.
     *
     * <p>Sent with the snapshot rather than looked up per keystroke: the list is fixed
     * for the life of the server, and a picker that has to ask the server on every
     * character is slower and fails differently when the bridge is busy.
     */
    private static JsonArray itemMaterials() {
        JsonArray materials = new JsonArray();
        for (org.bukkit.Material material : org.bukkit.Material.values()) {
            if (material.isLegacy()) {
                continue;
            }
            try {
                if (!material.isItem()) {
                    continue;
                }
            } catch (RuntimeException | LinkageError offServer) {
                // No item registry outside a running server. Offering the enum is better
                // than offering nothing; the add itself still refuses a non-item.
            }
            materials.add(material.name());
        }
        return materials;
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
        long total = rewards(kind).stream().mapToLong(reward -> rewardWeight(kind, reward)).sum();
        long rare = rewards(kind).stream().filter(CrateCatalog.Reward::rare)
                .mapToLong(reward -> rewardWeight(kind, reward)).sum();
        return total <= 0 ? 0d : (double) rare / (double) total;
    }

    String displayedChance(CrateKind kind, CrateCatalog.Reward reward) {
        if (reward.secret()) return "???";
        long total = rewards(kind).stream().mapToLong(value -> rewardWeight(kind, value)).sum();
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
        List<CrateCatalog.Reward> rewards = rewards(kind);
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
        long total = rewards(kind).stream().mapToLong(reward -> rewardWeight(kind, reward)).sum();
        if (total <= 0) return 0;
        String rewardId = parts[3];
        return rewards(kind).stream()
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
        long total = loot().stream()
                .mapToLong(entry -> lootValue(entry.materialName(), suffix)).sum();
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
        if (definition.type() == Type.CHOICE) {
            String wanted = value.toUpperCase(Locale.ROOT).replace(' ', '_');
            return definition.choices().stream()
                    .filter(option -> option.equalsIgnoreCase(wanted))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            definition.label() + " must be one of: "
                                    + String.join(", ", definition.choices()) + "."
                    ));
        }
        if (definition.type() == Type.TEXT) {
            if (value.length() > definition.maximum()) {
                throw new IllegalArgumentException(
                        definition.label() + " must be at most " + definition.maximum()
                                + " characters."
                );
            }
            return value;
        }
        if (definition.type() == Type.DECIMAL) {
            final double parsed;
            try {
                parsed = Double.parseDouble(value.replace(",", ""));
            } catch (NumberFormatException notANumber) {
                throw new IllegalArgumentException(definition.label() + " must be a number.");
            }
            if (!Double.isFinite(parsed)
                    || parsed < definition.minimumDecimal() || parsed > definition.maximumDecimal()) {
                throw new IllegalArgumentException(definition.label() + " must be between "
                        + definition.minimumDecimal() + " and " + definition.maximumDecimal() + ".");
            }
            return parsed;
        }
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

    /**
     * Range and ordering rules, read against the state the change set would leave.
     *
     * <p>The minimum/maximum relation is taken from the catalogue rather than a
     * hand-maintained list of pairs. The old list named eighteen of the thirty-one and
     * covered the rest with a separate suffix branch, so a new pair was only protected
     * if somebody remembered to add it in the right one of the two places.
     */
    private void validatePair(
            String key, Object value, Map<String, Object> pending,
            Set<String> resets, List<Finding> findings
    ) {
        Definition definition = definitions.get(key);
        if (definition == null || definition.type() != Type.INTEGER) {
            return;
        }
        long proposed = ((Number) value).longValue();
        SettingMetadata.partner(key, definitions.keySet()).ifPresent(partner -> {
            long other = resolveInt(pending, resets, partner);
            String otherLabel = definitions.get(partner).label();
            if (isMinimumSide(key)) {
                if (proposed > other) {
                    findings.add(new Finding(key, definition.label() + " (" + proposed
                            + ") cannot be above " + otherLabel + " (" + other + ")."));
                }
            } else if (proposed < other) {
                findings.add(new Finding(key, definition.label() + " (" + proposed
                        + ") cannot be below " + otherLabel + " (" + other + ")."));
            }
        });
        if (key.startsWith("online-rewards.tier.") && key.endsWith(".minimum-hours")) {
            int tier = Integer.parseInt(key.split("\\.")[2]);
            if (tier > 1 && proposed <= resolveInt(
                    pending, resets, "online-rewards.tier." + (tier - 1) + ".minimum-hours"
            )) {
                findings.add(new Finding(key, "Online tier " + tier
                        + " must start later than tier " + (tier - 1) + "."));
            }
            if (tier < 6 && proposed >= resolveInt(
                    pending, resets, "online-rewards.tier." + (tier + 1) + ".minimum-hours"
            )) {
                findings.add(new Finding(key, "Online tier " + tier
                        + " must start earlier than tier " + (tier + 1) + "."));
            }
        }
        if (key.startsWith("huge-amethyst.wave.") && key.endsWith(".health-percent")) {
            int wave = Integer.parseInt(key.split("\\.")[2]);
            if (wave > 1 && proposed >= resolveInt(
                    pending, resets, "huge-amethyst.wave." + (wave - 1) + ".health-percent"
            )) {
                findings.add(new Finding(key, "Reward wave " + wave
                        + " must trigger below wave " + (wave - 1) + "."));
            }
            if (wave < AmethystBlockRewards.REWARD_HEALTH_PERCENTAGES.length
                    && proposed <= resolveInt(
                    pending, resets, "huge-amethyst.wave." + (wave + 1) + ".health-percent"
            )) {
                findings.add(new Finding(key, "Reward wave " + wave
                        + " must trigger above wave " + (wave + 1) + "."));
            }
        }
    }

    private static boolean isMinimumSide(String key) {
        return key.endsWith(".minimum") || key.contains(".minimum-");
    }

    private static String describe(Definition definition, Object value) {
        return definition.key() + " = " + value + (definition.unit().isBlank() ? "" : " " + definition.unit());
    }

    private static void addValue(JsonObject object, String key, Object value) {
        if (value instanceof Boolean bool) object.addProperty(key, bool);
        else if (value instanceof String text) object.addProperty(key, text);
        else if (value instanceof Double decimal) object.addProperty(key, decimal);
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
                if (definition == null) {
                    // A weight whose reward is currently removed. Held, not dropped.
                    parked.put(canonical, entry.getValue().isJsonPrimitive()
                            && entry.getValue().getAsJsonPrimitive().isBoolean()
                            ? entry.getValue().getAsBoolean() : entry.getValue().getAsLong());
                    continue;
                }
                Object value = switch (definition.type()) {
                    case BOOLEAN -> entry.getValue().getAsBoolean();
                    case CHOICE, TEXT -> entry.getValue().getAsString();
                    case DECIMAL -> entry.getValue().getAsDouble();
                    case INTEGER -> entry.getValue().getAsLong();
                };
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
        // Written alongside the live ones so a reward restored after a restart comes
        // back with the weight it had, rather than silently at the catalogue default.
        parked.forEach((key, value) -> addValue(root, key, value));
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
