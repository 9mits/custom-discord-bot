package bot.mgx.accessbridge;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The operator event menu: what each effect is called and how long it may run.
 *
 * <p>Free of Bukkit so the contract can be unit tested. Every entry is
 * temporary and self-reverting by construction — nothing here writes a block,
 * an item, or a saved attribute that outlives the effect. The bounds are the
 * safety rail: an operator cannot ask for a ten-hour blackout.
 */
enum ChaosCatalog {
    KEYRAIN("keyrain", "Crate keys rain from the sky.", 0, 0, 0, "keys"),
    SKYBURST("skyburst", "Fireworks tear up the sky.", 15, 3, 60, "fireworks"),
    DISCO("disco", "The sky strobes through a full day every second.", 20, 5, 120),
    BLACKOUT("blackout", "Midnight and blindness for everyone.", 15, 5, 60, "night"),
    THUNDERDOME("thunderdome", "A storm and harmless lightning everywhere.", 20, 5, 60, "storm"),
    LAVAFLOOR("lavafloor", "The floor looks like lava. It is not.", 12, 3, 45, "lava"),
    VOIDFLOOR("voidfloor", "The floor disappears. It is still there.", 12, 3, 45, "void"),
    GIANTS("giants", "Everyone becomes enormous.", 20, 5, 120, "big"),
    TINY("tiny", "Everyone becomes very small.", 20, 5, 120, "small"),
    YOYO("yoyo", "Everyone grows and shrinks on a loop.", 20, 5, 60),
    LAUNCH("launch", "Everyone is punted into the sky, safely.", 0, 0, 0, "yeet"),
    FLOAT("float", "Everyone drifts upward, then lands softly.", 10, 3, 30, "levitate"),
    SPIN("spin", "Everyone's view spins.", 10, 3, 30),
    DRUNK("drunk", "The world tilts and slows.", 20, 5, 90),
    GHOSTS("ghosts", "Everyone turns invisible but glows.", 20, 5, 90, "invisible"),
    RAVE("rave", "Glowing outlines cycle colour to a beat.", 20, 5, 120),
    SWAP("swap", "Everyone swaps places with somebody else.", 0, 0, 0, "shuffle"),
    MOBSTORM("mobstorm", "Harmless creatures pour in, then vanish.", 20, 5, 60, "zoo"),
    METEORS("meteors", "Meteors fall and burst without a scratch.", 15, 5, 60),
    CONFETTI("confetti", "A dense burst of colour.", 0, 0, 0),
    HEADS("heads", "Everyone wears a random mob head.", 30, 5, 300),
    AIRDROP("airdrop", "A supply crate falls from the sky and bursts open.", 0, 0, 0, "drop", "supply"),
    PINATA("pinata", "A giant pinata. Hit it until it breaks.", 45, 15, 180, "boss"),
    JACKPOT("jackpot", "A drumroll, a spinning reel, and a payout.", 0, 0, 0, "roll"),
    ALFREDO("alfredo", "A colossal zombie. Beat him for everything he is carrying.", 0, 0, 0, "boss2"),
    CHAOS("chaos", "Several of the above at once.", 25, 10, 60),
    STOP("stop", "Ends every running effect and restores everything.", 0, 0, 0, "reset", "clear");

    private final String id;
    private final String blurb;
    private boolean physical;
    private final int defaultSeconds;
    private final int minimumSeconds;
    private final int maximumSeconds;
    private final Set<String> aliases;

    ChaosCatalog(
            String id,
            String blurb,
            int defaultSeconds,
            int minimumSeconds,
            int maximumSeconds,
            String... aliases
    ) {
        this.id = id;
        this.blurb = blurb;
        this.defaultSeconds = defaultSeconds;
        this.minimumSeconds = minimumSeconds;
        this.maximumSeconds = maximumSeconds;
        this.aliases = Set.copyOf(new LinkedHashSet<>(Arrays.asList(aliases)));
    }

    static {
        // Effects that pick players up or put them somewhere else. These skip
        // anybody riding a boat or minecart, because moving a rider ejects them.
        for (ChaosCatalog effect : new ChaosCatalog[]{LAUNCH, FLOAT, SWAP}) {
            effect.physical = true;
        }
    }

    /** Whether this effect physically moves the players it touches. */
    boolean physical() {
        return physical;
    }

    String id() {
        return id;
    }

    String blurb() {
        return blurb;
    }

    int defaultSeconds() {
        return defaultSeconds;
    }

    Set<String> aliases() {
        return aliases;
    }

    /** Whether this effect runs for a while, as opposed to firing once. */
    boolean timed() {
        return maximumSeconds > 0;
    }

    static Optional<ChaosCatalog> resolve(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String needle = token.toLowerCase(Locale.ROOT).trim();
        return Arrays.stream(values())
                .filter(effect -> effect.id.equals(needle) || effect.aliases.contains(needle))
                .findFirst();
    }

    /**
     * Clamps a requested duration to this effect's rail.
     *
     * @throws IllegalArgumentException if the operator asked for something outside it
     */
    int secondsOrThrow(String requested) {
        if (requested == null || requested.isBlank()) {
            return defaultSeconds;
        }
        if (!timed()) {
            throw new IllegalArgumentException(id + " does not take a duration.");
        }
        int value;
        try {
            value = Integer.parseInt(requested.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Duration must be a whole number of seconds.");
        }
        if (value < minimumSeconds || value > maximumSeconds) {
            throw new IllegalArgumentException(
                    id + " must run for between " + minimumSeconds + " and " + maximumSeconds + " seconds."
            );
        }
        return value;
    }

    /** Everything an operator can pick, in menu order. */
    static List<ChaosCatalog> menu() {
        return List.of(values());
    }

    /** What {@link #CHAOS} draws from. Excludes itself and the off switch. */
    static List<ChaosCatalog> chaosPool() {
        return List.of(
                SKYBURST, DISCO, THUNDERDOME, LAVAFLOOR, GIANTS, TINY, YOYO,
                FLOAT, SPIN, DRUNK, GHOSTS, RAVE, MOBSTORM, METEORS, HEADS
        );
    }

    /** Events that hand out rewards, and so should never fire unannounced. */
    static List<ChaosCatalog> payouts() {
        return List.of(KEYRAIN, AIRDROP, PINATA, JACKPOT, ALFREDO);
    }
}
