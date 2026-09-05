package bot.mgx.accessbridge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The cosmetics a physical token can represent.
 *
 * <p>IDs and model keys are persisted on items, so changing either one is a data
 * migration rather than a copy edit. Chance weights use the same 100,000-part scale
 * as {@link CrateCatalog}: one point is {@code 0.001%}.
 */
final class CosmeticCatalog {
    static final int SECRET_WEIGHT = 3;
    static final String HIDDEN_AMETHYST_COSMETIC_ID = "iridescent_imperium";
    static final int HIDDEN_AMETHYST_ONE_IN = 500_000;
    static final String DRAGON_SECRET_COSMETIC_ID = "amethyst_dragon_ascendant";

    enum Category {
        KILL_EFFECT("Kill Effects"),
        AURA("Auras"),
        TRAIL("Trails");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }
    }


    /** How an odds tag animates its family palette. */
    enum OddsMotion {
        /** The palette scrolls along the text, one step every two frames. */
        SCROLL,
        /** Every character shares one colour and the whole line steps through the palette. */
        PULSE,
        /** A bright highlight travels across a steady base colour. */
        SHIMMER
    }

    /**
     * The visual family an odds tag borrows its colours from.
     *
     * <p>One palette for every Mythic, one for every Secret and one for the jackpot said
     * nothing about what the player is actually wearing. Cosmetics that read as the same
     * effect share a family instead, so the floating line matches the particles above it.
     * Colours are plain RGB so this file stays free of server types.
     */
    enum OddsFamily {
        AMETHYST("\u25c6", OddsMotion.SCROLL,
                0xB44CEE, 0xD98BFF, 0x8A3FD1, 0xF0C4FF),
        VOID("\u25c6", OddsMotion.PULSE,
                0x8B3BD6, 0x4A1E7A, 0xC26BFF, 0x2B1140),
        SOUL("\u2726", OddsMotion.SHIMMER,
                0x3FA9C9, 0x9BF6FF, 0x4FE3E0, 0x1F5F82),
        CELESTIAL("\u2605", OddsMotion.SHIMMER,
                0xC9E3FF, 0xFFFFFF, 0xFFF3C4, 0xFFD35A),
        INFERNAL("\u2726", OddsMotion.PULSE,
                0xFF6A1A, 0xFFB347, 0xD62828, 0xFFE066),
        ABYSSAL("\u25c6", OddsMotion.SCROLL,
                0x1FB6A6, 0x0E5C63, 0x7FFFD4, 0x134E4A),
        DIVINE("\u2605", OddsMotion.PULSE,
                0xFFE9A3, 0xFFFFFF, 0xFFC93C, 0xBFE8FF),
        PRISMATIC("\u2726", OddsMotion.SCROLL,
                0xFF4D4D, 0xFFA64D, 0xFFF34D, 0x4DFF7A, 0x4DD2FF, 0x8A4DFF, 0xFF4DE1),
        /** The Iridescent Imperium keeps the six-colour jackpot spectrum of its own aura. */
        GENUINE("\u2726", OddsMotion.SCROLL,
                0xE95CFF, 0x705CFF, 0x36CFFF, 0x42E59B, 0xF3D56B, 0xFF8055),
        /** What an unassigned cosmetic falls back to: the palette every Mythic used to share. */
        ROYAL("\u2726", OddsMotion.SCROLL,
                0xFF416C, 0xFF8B3D, 0xFFD35A, 0xFF4FB7);

        private final String glyph;
        private final OddsMotion motion;
        private final int[] colours;

        OddsFamily(String glyph, OddsMotion motion, int... colours) {
            this.glyph = glyph;
            this.motion = motion;
            this.colours = colours;
        }

        String glyph() {
            return glyph;
        }

        OddsMotion motion() {
            return motion;
        }

        int[] colours() {
            return colours.clone();
        }
    }

    record Definition(
            String id,
            String displayName,
            Category category,
            int weight,
            boolean secret,
            String materialName,
            String modelKey,
            String description,
            int leaderboardRank
    ) {
        Definition {
            id = requireText(id, "Cosmetic ID").toLowerCase(Locale.ROOT);
            displayName = requireText(displayName, "Cosmetic display name");
            if (category == null) {
                throw new IllegalArgumentException("Cosmetic category is required");
            }
            if (weight <= 0) {
                throw new IllegalArgumentException("Cosmetic weight must be positive");
            }
            materialName = requireText(materialName, "Cosmetic material")
                    .toUpperCase(Locale.ROOT);
            modelKey = requireText(modelKey, "Cosmetic model key").toLowerCase(Locale.ROOT);
            description = requireText(description, "Cosmetic description");
            if (leaderboardRank < 0 || leaderboardRank > 3) {
                throw new IllegalArgumentException("Leaderboard rank must be 0-3");
            }
        }

        String displayedChance() {
            return secret ? "???" : percentage(weight);
        }

        String rarityDisplay() {
            if (clanBattleOnly()) {
                return "Clan Battle Champion";
            }
            if (leaderboardOnly()) {
                return "Leaderboard #" + leaderboardRank;
            }
            if (hiddenAmethystJackpot()) {
                return "Secret";
            }
            if (secret) {
                return "Exotic";
            }
            if (weight >= 2_000) {
                return "Rare";
            }
            if (weight >= 500) {
                return "Epic";
            }
            if (weight >= 100) {
                return "Legendary";
            }
            return "Mythic";
        }

        boolean leaderboardOnly() {
            return leaderboardRank > 0;
        }

        boolean clanBattleOnly() {
            return CosmeticCatalog.isClanBattleReward(id);
        }

        boolean hiddenAmethystJackpot() {
            return id.equals(HIDDEN_AMETHYST_COSMETIC_ID);
        }

        int oneIn() {
            if (hiddenAmethystJackpot()) {
                // The live setting, not the shipped default: this number is shown.
                return CrateCatalog.hiddenAmethystOneIn();
            }
            return Math.max(1, (int) Math.round(CrateCatalog.TOTAL_WEIGHT / (double) weight));
        }

        String oneInDisplay(boolean masked) {
            return masked ? "1 in ???" : String.format(Locale.ROOT, "1 in %,d", oneIn());
        }

        /**
         * Auras only. A trail is invisible while its owner stands still and a kill
         * effect is invisible until somebody dies, so tagging either one put a
         * permanent odds line over a player wearing nothing anyone could see.
         */
        boolean nameplateWorthy() {
            return category == Category.AURA
                    && !CosmeticCatalog.isAmethystAirdrop(id)
                    && !clanBattleOnly()
                    && (hiddenAmethystJackpot() || secret || rarityDisplay().equals("Mythic"));
        }

        OddsFamily oddsFamily() {
            OddsFamily assigned = ODDS_FAMILIES.get(id);
            return assigned == null ? OddsFamily.ROYAL : assigned;
        }
    }

    /**
     * Which family each tag-worthy cosmetic belongs to. Grouped by what the effect
     * actually looks like, not by which crate it came from — Ender Trail and Event
     * Horizon are both violet tears in space, so both read as VOID.
     *
     * <p>{@code oddsFamiliesCoverEveryTaggedCosmetic} fails when a new Mythic or Secret
     * is added without a line here, so the fallback stays a safety net rather than a
     * silent default.
     */
    private static final Map<String, OddsFamily> ODDS_FAMILIES = Map.ofEntries(
            Map.entry("void_collapse", OddsFamily.VOID),
            Map.entry("ender_trail", OddsFamily.VOID),
            Map.entry("event_horizon", OddsFamily.VOID),
            Map.entry("reality_fracture", OddsFamily.VOID),
            Map.entry("soul_requiem", OddsFamily.SOUL),
            Map.entry("reapers_verdict", OddsFamily.SOUL),
            Map.entry("phantom_chains", OddsFamily.SOUL),
            Map.entry("celestial_crown", OddsFamily.CELESTIAL),
            Map.entry("astral_sovereign", OddsFamily.CELESTIAL),
            Map.entry("galaxy_wake", OddsFamily.CELESTIAL),
            Map.entry("infernal_dominion", OddsFamily.INFERNAL),
            Map.entry("divine_rupture", OddsFamily.DIVINE),
            Map.entry("abyssal_seraph", OddsFamily.ABYSSAL),
            Map.entry("prismatic_trail", OddsFamily.PRISMATIC),
            Map.entry("amethyst_orbit", OddsFamily.AMETHYST),
            Map.entry("amethyst_ascension", OddsFamily.AMETHYST),
            Map.entry("geode_cathedral", OddsFamily.AMETHYST),
            Map.entry("crystal_guillotine", OddsFamily.AMETHYST),
            Map.entry("violet_detonation", OddsFamily.AMETHYST),
            Map.entry("shardstorm_wake", OddsFamily.AMETHYST),
            Map.entry("geode_bloom", OddsFamily.AMETHYST),
            Map.entry("crystalline_extinction", OddsFamily.AMETHYST),
            Map.entry("resonant_apotheosis", OddsFamily.AMETHYST),
            Map.entry("shattered_continuum", OddsFamily.AMETHYST),
            Map.entry("resonant_shatter", OddsFamily.AMETHYST),
            Map.entry("crystalfall_wake", OddsFamily.AMETHYST),
            Map.entry("airdrop_apotheosis", OddsFamily.AMETHYST),
            Map.entry(HIDDEN_AMETHYST_COSMETIC_ID, OddsFamily.GENUINE),
            Map.entry(DRAGON_SECRET_COSMETIC_ID, OddsFamily.GENUINE)
    );

    private static final List<Definition> DEFINITIONS = List.of(
            cosmetic(
                    "blood_burst", "Blood Burst", Category.KILL_EFFECT, 2_500,
                    "RED_DYE", "A crimson spray erupts where your opponent falls."
            ),
            cosmetic(
                    "frozen_shatter", "Frozen Shatter", Category.KILL_EFFECT, 1_000,
                    "LIGHT_BLUE_DYE", "Ice crystals burst outward with a brittle crack."
            ),
            cosmetic(
                    "shining_light", "Shining Light", Category.KILL_EFFECT, 275,
                    "GLOWSTONE_DUST", "White-gold rays and sparks mark the final hit."
            ),
            cosmetic(
                    "void_collapse", "Void Collapse", Category.KILL_EFFECT, 82,
                    "ENDER_EYE", "A dark portal folds in on the defeated player."
            ),
            cosmetic(
                    "soul_requiem", "Soul Requiem", Category.KILL_EFFECT, 28,
                    "SOUL_LANTERN", "Blue souls spiral upward and fade into silence."
            ),
            cosmetic(
                    "solar_orbit", "Solar Orbit", Category.AURA, 2_000,
                    "BLAZE_POWDER", "Warm gold motes circle the player like a small sun."
            ),
            cosmetic(
                    "crimson_orbit", "Crimson Orbit", Category.AURA, 413,
                    "REDSTONE", "Three crimson lights trace a steady orbit."
            ),
            cosmetic(
                    "emerald_orbit", "Emerald Orbit", Category.AURA, 220,
                    "EMERALD", "Green sparks weave around the player in two rings."
            ),
            cosmetic(
                    "amethyst_orbit", "Amethyst Orbit", Category.AURA, 82,
                    "AMETHYST_SHARD", "Violet shards glimmer along a slow helix."
            ),
            cosmetic(
                    "celestial_crown", "Celestial Crown", Category.AURA, 16,
                    "NETHER_STAR", "A crown of cold starlight turns above the player."
            ),
            cosmetic(
                    "ember_trail", "Ember Trail", Category.TRAIL, 5_000,
                    "BLAZE_POWDER", "Small orange embers linger behind every step."
            ),
            cosmetic(
                    "blood_trail", "Blood Trail", Category.TRAIL, 1_000,
                    "RED_DYE", "Dark red droplets briefly mark the path behind you."
            ),
            cosmetic(
                    "frost_trail", "Frost Trail", Category.TRAIL, 413,
                    "SNOWBALL", "Pale snow and ice dust follow your movement."
            ),
            cosmetic(
                    "cherry_blossom_trail", "Cherry Blossom Trail", Category.TRAIL, 275,
                    "PINK_PETALS", "Pink petals drift and settle behind you."
            ),
            cosmetic(
                    "drool_trail", "Drool Trail", Category.TRAIL, 220,
                    "SLIME_BALL", "Glossy aqua droplets inspired by Mysterious Girlfriend X."
            ),
            cosmetic(
                    "ender_trail", "Ender Trail", Category.TRAIL, 82,
                    "ENDER_PEARL", "Purple motes blink in and out along your path."
            ),
            cosmetic(
                    "prismatic_trail", "Prismatic Trail", Category.TRAIL, 8,
                    "PRISMARINE_CRYSTALS", "A shifting ribbon cycles through the full spectrum."
            ),
            secretCosmetic(
                    "event_horizon", "Event Horizon", Category.KILL_EFFECT,
                    "BLACK_DYE", "Space folds inward before the defeated player disappears."
            ),
            secretCosmetic(
                    "reapers_verdict", "Reaper's Verdict", Category.KILL_EFFECT,
                    "WITHER_SKELETON_SKULL", "A spectral scythe cuts through a storm of stolen souls."
            ),
            secretCosmetic(
                    "divine_rupture", "Divine Rupture", Category.KILL_EFFECT,
                    "LIGHTNING_ROD", "A pillar of judgment splits the sky at the final blow."
            ),
            secretCosmetic(
                    "astral_sovereign", "Astral Sovereign", Category.AURA,
                    "ECHO_SHARD", "Constellations and miniature stars orbit their sovereign."
            ),
            secretCosmetic(
                    "infernal_dominion", "Infernal Dominion", Category.AURA,
                    "MAGMA_CREAM", "A burning crown and molten rings command the ground nearby."
            ),
            secretCosmetic(
                    "abyssal_seraph", "Abyssal Seraph", Category.AURA,
                    "PHANTOM_MEMBRANE", "Six void-lit wings unfold behind the wearer."
            ),
            secretCosmetic(
                    "galaxy_wake", "Galaxy Wake", Category.TRAIL,
                    "AMETHYST_SHARD", "A river of newborn stars stretches behind every step."
            ),
            secretCosmetic(
                    "phantom_chains", "Phantom Chains", Category.TRAIL,
                    "IRON_CHAIN", "Spectral chain links drag through the air and fade into souls."
            ),
            secretCosmetic(
                    "reality_fracture", "Reality Fracture", Category.TRAIL,
                    "CHORUS_FRUIT", "Bright cracks split reality along the path travelled."
            )
    );
    /** Limited Amethyst Crate cosmetics. Kept out of the permanent crate pool. */
    private static final List<Definition> AMETHYST_REWARDS = List.of(
            cosmetic(
                    "amethyst_ascension", "Amethyst Ascension", Category.AURA, 100,
                    "AMETHYST_SHARD",
                    "A living crystal crown rises while faceted rings bloom around you."
            ),
            cosmetic(
                    "geode_cathedral", "Geode Cathedral", Category.AURA, 60,
                    "BUDDING_AMETHYST",
                    "Rotating geode arches grow, chime, and collapse into violet light."
            ),
            cosmetic(
                    "crystal_guillotine", "Crystal Guillotine", Category.KILL_EFFECT, 90,
                    "AMETHYST_CLUSTER",
                    "A descending crystal blade shatters the final blow into lethal shards."
            ),
            cosmetic(
                    "violet_detonation", "Violet Detonation", Category.KILL_EFFECT, 50,
                    "END_CRYSTAL",
                    "A compressed amethyst core violently detonates in expanding rings."
            ),
            cosmetic(
                    "shardstorm_wake", "Shardstorm Wake", Category.TRAIL, 80,
                    "AMETHYST_SHARD",
                    "Sweeping crystal crescents chase your steps and burst behind you."
            ),
            cosmetic(
                    "geode_bloom", "Geode Bloom", Category.TRAIL, 66,
                    "SMALL_AMETHYST_BUD",
                    "Tiny geodes sprout, open, and dissolve along the path you travelled."
            ),
            secretCosmetic(
                    "crystalline_extinction", "Crystalline Extinction", Category.KILL_EFFECT,
                    "CRYING_OBSIDIAN",
                    "A crystal maw crushes the fallen into a violent violet singularity."
            ),
            secretCosmetic(
                    "resonant_apotheosis", "Resonant Apotheosis", Category.AURA,
                    "AMETHYST_CLUSTER",
                    "A resonant crystal crown unfolds through ever-changing royal formations."
            ),
            secretCosmetic(
                    "shattered_continuum", "Shattered Continuum", Category.TRAIL,
                    "ECHO_SHARD",
                    "Geode gates tear open, fracture, and chase every step through reality."
            )
    );

    /**
     * The event's true chase reward. It is indexed for ownership, admin testing, and
     * resource-pack validation, but deliberately never enters the visible crate pool.
     */
    private static final List<Definition> HIDDEN_AMETHYST_REWARDS = List.of(
            new Definition(
                    HIDDEN_AMETHYST_COSMETIC_ID,
                    "Iridescent Imperium",
                    Category.AURA,
                    1,
                    true,
                    "AMETHYST_CLUSTER",
                    "mgx:cosmetic/" + HIDDEN_AMETHYST_COSMETIC_ID,
                    "A music-synced couture amethyst heart conducts a living spectrum to every beat.",
                    0
            )
    );

    /** Amethyst Airdrop cosmetics. Their actual drop chance is intentionally private. */
    private static final List<Definition> AMETHYST_AIRDROP_REWARDS = List.of(
            cosmetic(
                    "resonant_shatter", "Resonant Shatter", Category.KILL_EFFECT, 25,
                    "AMETHYST_CLUSTER",
                    "A crystal meteor splits the final blow into a violent resonant shockwave."
            ),
            cosmetic(
                    "crystalfall_wake", "Crystalfall Wake", Category.TRAIL, 25,
                    "AMETHYST_SHARD",
                    "Falling amethyst comets fracture into bright shards behind every step."
            ),
            cosmetic(
                    "airdrop_apotheosis", "Airdrop Apotheosis", Category.AURA, 25,
                    "BUDDING_AMETHYST",
                    "A beacon crown suspends a royal drop-crystal inside broken violet halos."
            )
    );

    /** Nine animated Dragon Exotics, three in every wardrobe category. */
    private static final List<Definition> DRAGON_REWARDS = List.of(
            cosmetic("dragonheart_rupture", "Dragonheart Rupture", Category.KILL_EFFECT, 156,
                    "END_CRYSTAL", "A dragon-heart crystal erupts into violet shockwaves."),
            cosmetic("crystal_wingfall", "Crystal Wingfall", Category.KILL_EFFECT, 156,
                    "AMETHYST_CLUSTER", "Spectral crystal wings close over the final blow."),
            cosmetic("endscale_cataclysm", "Endscale Cataclysm", Category.KILL_EFFECT, 156,
                    "DRAGON_BREATH", "Amethyst scales spiral into a resonant blast."),
            cosmetic("amethyst_dragon_crown", "Amethyst Dragon Crown", Category.AURA, 156,
                    "DRAGON_HEAD", "A living crown and crystal wings orbit their champion."),
            cosmetic("violet_wyrm_orbit", "Violet Wyrm Orbit", Category.AURA, 156,
                    "ENDER_EYE", "Twin violet wyrms coil through a bright crystal orbit."),
            cosmetic("geode_sovereignty", "Geode Sovereignty", Category.AURA, 155,
                    "BUDDING_AMETHYST", "A royal geode throne unfolds around its wearer."),
            cosmetic("dragonflight_wake", "Dragonflight Wake", Category.TRAIL, 155,
                    "ELYTRA", "Wingbeats and violet embers chase every step."),
            cosmetic("shardwing_procession", "Shardwing Procession", Category.TRAIL, 155,
                    "AMETHYST_SHARD", "Paired crystal wings sweep through the travelled path."),
            cosmetic("crystalfire_trail", "Crystalfire Trail", Category.TRAIL, 155,
                    "END_ROD", "Purple dragonfire dances and fractures behind the wearer.")
    );

    private static final List<Definition> HIDDEN_DRAGON_REWARDS = List.of(
            new Definition(
                    DRAGON_SECRET_COSMETIC_ID, "Amethyst Dragon Ascendant", Category.AURA,
                    1, true, "DRAGON_EGG", "mgx:cosmetic/" + DRAGON_SECRET_COSMETIC_ID,
                    "A music-synced Amethyst Dragon circles a living crystal throne.", 0
            )
    );

    private static final List<Definition> DRAGON_LEADERBOARD_REWARDS = List.of(
            leaderboardCosmetic("dragon_podium_1", "Dragon's First Crown", Category.AURA, 1,
                    "DRAGON_HEAD", "Held by #1 on either Amethyst Dragon leaderboard."),
            leaderboardCosmetic("dragon_podium_2", "Dragon's Silver Fang", Category.AURA, 2,
                    "GHAST_TEAR", "Held by #2 on either Amethyst Dragon leaderboard."),
            leaderboardCosmetic("dragon_podium_3", "Dragon's Bronze Scale", Category.AURA, 3,
                    "COPPER_INGOT", "Held by #3 on either Amethyst Dragon leaderboard.")
    );

    private static final List<Definition> DRAGON_CLAN_REWARDS = List.of(
            cosmetic("dragon_clan_1", "Sovereign Brood", Category.AURA, 1,
                    "DRAGON_EGG", "Awarded to the #1 clan in the Dragon Egg Clan Battle."),
            cosmetic("dragon_clan_2", "Crystal Vanguard", Category.AURA, 1,
                    "AMETHYST_CLUSTER", "Awarded to the #2 clan in the Dragon Egg Clan Battle."),
            cosmetic("dragon_clan_3", "Violet Kin", Category.AURA, 1,
                    "AMETHYST_SHARD", "Awarded to the #3 clan in the Dragon Egg Clan Battle.")
    );

    /** Permanent ownership awarded to every member of a first-place clan battle roster. */
    private static final List<Definition> CLAN_BATTLE_REWARDS = List.of(
            cosmetic(
                    ClanBattleStore.GALACTIC_CONQUEST_ID,
                    "Galactic Conquest",
                    Category.AURA,
                    1,
                    "NETHER_STAR",
                    "Only obtainable from the Crates Clan Battle. A living galaxy and royal "
                            + "stellar crown orbit the champion."
            )
    );

    static boolean isLimitedAmethyst(String cosmeticId) {
        return cosmeticId != null && java.util.stream.Stream.concat(
                        AMETHYST_REWARDS.stream(), HIDDEN_AMETHYST_REWARDS.stream()
                )
                .anyMatch(definition -> definition.id().equalsIgnoreCase(cosmeticId));
    }

    static boolean isAmethystAirdrop(String cosmeticId) {
        return cosmeticId != null && AMETHYST_AIRDROP_REWARDS.stream()
                .anyMatch(definition -> definition.id().equalsIgnoreCase(cosmeticId));
    }

    static boolean isClanBattleReward(String cosmeticId) {
        return cosmeticId != null && java.util.stream.Stream.concat(
                        CLAN_BATTLE_REWARDS.stream(), DRAGON_CLAN_REWARDS.stream())
                .anyMatch(definition -> definition.id().equalsIgnoreCase(cosmeticId));
    }
    private static final List<Definition> LEADERBOARD_REWARDS = List.of(
            leaderboardCosmetic(
                    "golden_finality", "Golden Finality", Category.KILL_EFFECT, 1,
                    "GOLDEN_SWORD",
                    "Only obtainable by holding #1 on an individual player leaderboard. "
                            + "A royal blade ends the fight in sunlight."
            ),
            leaderboardCosmetic(
                    "solar_imperium", "Solar Imperium", Category.AURA, 1,
                    "GOLDEN_HELMET",
                    "Only obtainable by holding #1 on an individual player leaderboard. "
                            + "A radiant crown marks the champion."
            ),
            leaderboardCosmetic(
                    "kingmakers_wake", "Kingmaker's Wake", Category.TRAIL, 1,
                    "GOLD_INGOT",
                    "Only obtainable by holding #1 on an individual player leaderboard. "
                            + "A molten royal mantle follows every step."
            ),
            leaderboardCosmetic(
                    "silver_reckoning", "Silver Reckoning", Category.KILL_EFFECT, 2,
                    "IRON_SWORD",
                    "Only obtainable by holding #2 on an individual player leaderboard. "
                            + "A moonlit blade shatters into silver."
            ),
            leaderboardCosmetic(
                    "argent_dominion", "Argent Dominion", Category.AURA, 2,
                    "IRON_HELMET",
                    "Only obtainable by holding #2 on an individual player leaderboard. "
                            + "Twin lunar halos crown the runner-up."
            ),
            leaderboardCosmetic(
                    "moonlit_procession", "Moonlit Procession", Category.TRAIL, 2,
                    "IRON_INGOT",
                    "Only obtainable by holding #2 on an individual player leaderboard. "
                            + "Cold comet ribbons follow the wearer."
            ),
            leaderboardCosmetic(
                    "bronze_cataclysm", "Bronze Cataclysm", Category.KILL_EFFECT, 3,
                    "COPPER_INGOT",
                    "Only obtainable by holding #3 on an individual player leaderboard. "
                            + "A war axe shatters a burning bronze medal."
            ),
            leaderboardCosmetic(
                    "bronze_vanguard", "Bronze Vanguard", Category.AURA, 3,
                    "COPPER_BLOCK",
                    "Only obtainable by holding #3 on an individual player leaderboard. "
                            + "A blazing laurel crowns the contender."
            ),
            leaderboardCosmetic(
                    "conquerors_march", "Conqueror's March", Category.TRAIL, 3,
                    "RAW_COPPER",
                    "Only obtainable by holding #3 on an individual player leaderboard. "
                            + "A bronze banner scatters ember medals."
            )
    );
    private static final Map<String, Definition> BY_ID = indexDefinitions();

    private CosmeticCatalog() {
    }

    /** What the crate shows in place of a secret nobody owns yet. */
    static final String MASKED_NAME = "???";
    static final String MASKED_DESCRIPTION = "A black silhouette conceals its true effect.";
    static final String MASKED_MODEL_KEY = "mgx:cosmetic/secret_silhouette";

    /**
     * Cosmetics the owner added, and which built-in effect each one wears.
     *
     * <p>A cosmetic's visual is dispatched by its id, so an invented id would be a name
     * with nothing behind it. An added cosmetic therefore names an existing effect to
     * wear: it can have its own name, category, rarity and description, and it can be put
     * in a crate, but what a player sees is one of the effects that already ships. New
     * artwork still needs a build, which is honest — the alternative is a control that
     * adds an invisible cosmetic.
     */
    private static volatile java.util.function.Supplier<Map<Definition, String>> additions =
            Map::of;
    private static final Map<String, String> EFFECT_ALIASES = Map.ofEntries(
            Map.entry("dragonheart_rupture", "violet_detonation"),
            Map.entry("crystal_wingfall", "crystal_guillotine"),
            Map.entry("endscale_cataclysm", "resonant_shatter"),
            Map.entry("amethyst_dragon_crown", "amethyst_ascension"),
            Map.entry("violet_wyrm_orbit", "airdrop_apotheosis"),
            Map.entry("geode_sovereignty", "geode_cathedral"),
            Map.entry("dragonflight_wake", "crystalfall_wake"),
            Map.entry("shardwing_procession", "shardstorm_wake"),
            Map.entry("crystalfire_trail", "geode_bloom"),
            Map.entry("dragon_clan_1", "galactic_conquest"),
            Map.entry("dragon_clan_2", "amethyst_ascension"),
            Map.entry("dragon_clan_3", "geode_cathedral")
    );

    static void additionSource(java.util.function.Supplier<Map<Definition, String>> source) {
        if (source != null) {
            additions = source;
        }
    }

    /** The built-in effect an added cosmetic wears, or its own id when it is built in. */
    static String effectId(Definition definition) {
        if (definition == null) {
            return "";
        }
        String worn = additions.get().get(definition);
        if (worn != null && !worn.isBlank()) {
            return worn;
        }
        return EFFECT_ALIASES.getOrDefault(definition.id(), definition.id());
    }

    static Optional<Definition> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String wanted = id.strip().toLowerCase(Locale.ROOT);
        Definition builtIn = BY_ID.get(wanted);
        if (builtIn != null) {
            return Optional.of(builtIn);
        }
        return additions.get().keySet().stream()
                .filter(definition -> definition.id().equals(wanted))
                .findFirst();
    }

    static List<Definition> all() {
        Map<Definition, String> added = additions.get();
        if (added.isEmpty()) {
            return DEFINITIONS;
        }
        List<Definition> everything = new java.util.ArrayList<>(DEFINITIONS);
        everything.addAll(added.keySet());
        return List.copyOf(everything);
    }

    static List<Definition> amethystRewards() {
        return AMETHYST_REWARDS;
    }

    static List<Definition> hiddenAmethystRewards() {
        return HIDDEN_AMETHYST_REWARDS;
    }

    static List<Definition> dragonRewards() {
        return DRAGON_REWARDS;
    }

    static List<Definition> hiddenDragonRewards() {
        return HIDDEN_DRAGON_REWARDS;
    }

    static List<Definition> amethystAirdropRewards() {
        return AMETHYST_AIRDROP_REWARDS;
    }

    /** Crate and leaderboard entries whose item models must ship in the resource pack. */
    static List<Definition> visualEntries() {
        return java.util.stream.Stream.of(
                        DEFINITIONS.stream(), AMETHYST_REWARDS.stream(),
                        HIDDEN_AMETHYST_REWARDS.stream(), AMETHYST_AIRDROP_REWARDS.stream(),
                        DRAGON_REWARDS.stream(), HIDDEN_DRAGON_REWARDS.stream(),
                        CLAN_BATTLE_REWARDS.stream(), DRAGON_CLAN_REWARDS.stream(),
                        LEADERBOARD_REWARDS.stream(), DRAGON_LEADERBOARD_REWARDS.stream()
                )
                .flatMap(stream -> stream)
                .toList();
    }

    static List<Definition> leaderboardRewards() {
        return LEADERBOARD_REWARDS;
    }

    static Optional<Definition> leaderboardReward(int rank, Category category) {
        return LEADERBOARD_REWARDS.stream()
                .filter(definition -> definition.leaderboardRank() == rank)
                .filter(definition -> definition.category() == category)
                .findFirst();
    }

    static Optional<Definition> dragonLeaderboardReward(int rank, Category category) {
        return DRAGON_LEADERBOARD_REWARDS.stream()
                .filter(definition -> definition.leaderboardRank() == rank)
                .filter(definition -> definition.category() == category)
                .findFirst();
    }

    static Optional<Definition> dragonClanReward(int rank, Category category) {
        return DRAGON_CLAN_REWARDS.stream()
                .filter(definition -> definition.leaderboardRank() == rank)
                .filter(definition -> definition.category() == category)
                .findFirst();
    }

    /** The public crate index; secrets are represented separately by silhouettes. */
    static List<Definition> publicEntries() {
        return DEFINITIONS.stream().filter(definition -> !definition.secret()).toList();
    }

    static String percentage(int weight) {
        return String.format(Locale.ROOT, "%.3f%%", weight / 1_000.0d);
    }

    private static Definition cosmetic(
            String id,
            String displayName,
            Category category,
            int weight,
            String material,
            String description
    ) {
        return new Definition(
                id,
                displayName,
                category,
                weight,
                false,
                material,
                "mgx:cosmetic/" + id,
                description,
                0
        );
    }

    private static Definition secretCosmetic(
            String id,
            String displayName,
            Category category,
            String material,
            String description
    ) {
        return new Definition(
                id,
                displayName,
                category,
                SECRET_WEIGHT,
                true,
                material,
                "mgx:cosmetic/" + id,
                description,
                0
        );
    }

    private static Definition leaderboardCosmetic(
            String id,
            String displayName,
            Category category,
            int rank,
            String material,
            String description
    ) {
        return new Definition(
                id, displayName, category, 1, false, material,
                "mgx:cosmetic/" + id, description, rank
        );
    }

    private static Map<String, Definition> indexDefinitions() {
        Map<String, Definition> indexed = new LinkedHashMap<>();
        for (Definition definition : visualEntries()) {
            if (indexed.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalStateException("Duplicate cosmetic ID " + definition.id());
            }
        }
        return Map.copyOf(indexed);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.strip();
    }
}
