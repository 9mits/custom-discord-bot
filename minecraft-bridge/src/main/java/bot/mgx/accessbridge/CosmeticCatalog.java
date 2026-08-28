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
    static final int HIDDEN_AMETHYST_ONE_IN = 1_000_000;

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
            if (leaderboardOnly()) {
                return "Leaderboard #" + leaderboardRank;
            }
            if (hiddenAmethystJackpot()) {
                return "Exotic";
            }
            if (secret) {
                return "Secret";
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

        boolean hiddenAmethystJackpot() {
            return id.equals(HIDDEN_AMETHYST_COSMETIC_ID);
        }

        int oneIn() {
            if (hiddenAmethystJackpot()) {
                return HIDDEN_AMETHYST_ONE_IN;
            }
            return Math.max(1, (int) Math.round(CrateCatalog.TOTAL_WEIGHT / (double) weight));
        }

        String oneInDisplay(boolean masked) {
            return masked ? "1 in ???" : String.format(Locale.ROOT, "1 in %,d", oneIn());
        }
    }

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
                    "A couture amethyst heart conducts a living spectrum to the music.",
                    0
            )
    );

    static boolean isLimitedAmethyst(String cosmeticId) {
        return cosmeticId != null && java.util.stream.Stream.concat(
                        AMETHYST_REWARDS.stream(), HIDDEN_AMETHYST_REWARDS.stream()
                )
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

    static Optional<Definition> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_ID.get(id.strip().toLowerCase(Locale.ROOT)));
    }

    static List<Definition> all() {
        return DEFINITIONS;
    }

    static List<Definition> amethystRewards() {
        return AMETHYST_REWARDS;
    }

    static List<Definition> hiddenAmethystRewards() {
        return HIDDEN_AMETHYST_REWARDS;
    }

    /** Crate and leaderboard entries whose item models must ship in the resource pack. */
    static List<Definition> visualEntries() {
        return java.util.stream.Stream.of(
                        DEFINITIONS.stream(), AMETHYST_REWARDS.stream(),
                        HIDDEN_AMETHYST_REWARDS.stream(), LEADERBOARD_REWARDS.stream()
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
