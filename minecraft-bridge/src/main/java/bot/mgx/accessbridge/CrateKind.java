package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The independently selectable physical crates served by one reusable crate engine. */
enum CrateKind {
    DEFAULT(
            "default", "Default Crate", "Default Crate", Material.CHEST,
            TextColor.color(0xFF9900),
            Currency.KEY, 1, Long.MAX_VALUE, CrateCatalog.all()
    ),
    AMETHYST(
            "amethyst", "Limited Amethyst Crate", "Amethyst Crate", Material.AMETHYST_BLOCK,
            TextColor.color(0xB56CFF),
            Currency.KEY, 2,
            // Saturday after next at 3:00 PM JST, resolved when the event was requested.
            1_789_192_800_000L, CrateCatalog.amethyst()
    ),
    SHARD(
            "shard", "Shard Crate", "Shard Crate", Material.ECHO_SHARD,
            TextColor.color(0x53E5FF),
            Currency.SHARD, 1, Long.MAX_VALUE, CrateCatalog.shard()
    ),
    DRAGON(
            "dragon", "Amethyst Dragon Crate", "Dragon Crate", Material.DRAGON_HEAD,
            TextColor.color(0xD98BFF),
            Currency.KEY, 1, Long.MAX_VALUE, CrateCatalog.dragon()
    );

    enum Currency {
        KEY("key", "keys", "Mysterious Crate Key", "Mysterious Crate Keys"),
        SHARD("Shard", "Shards", "Shard", "Shards");

        private final String singular;
        private final String plural;
        private final String fullSingular;
        private final String fullPlural;

        Currency(String singular, String plural, String fullSingular, String fullPlural) {
            this.singular = singular;
            this.plural = plural;
            this.fullSingular = fullSingular;
            this.fullPlural = fullPlural;
        }

        String shortName(long amount) {
            return amount == 1 ? singular : plural;
        }

        String fullName(long amount) {
            return amount == 1 ? fullSingular : fullPlural;
        }
    }

    /** What a limited crate leads with while it is still open. */
    static final String LIMITED_HEADLINE = "LIMITED TIME! LEAVING IN:";
    /** The same banner once the deadline has passed. */
    static final String LIMITED_HEADLINE_CLOSED = "LIMITED TIME EVENT";

    private final String key;
    private final String displayName;
    private final String menuName;
    private final Material icon;
    private final TextColor colour;
    private final Currency currency;
    private final int keyCost;
    private final long closesAt;
    private final List<CrateCatalog.Reward> rewards;

    CrateKind(
            String key,
            String displayName,
            String menuName,
            Material icon,
            TextColor colour,
            Currency currency,
            int keyCost,
            long closesAt,
            List<CrateCatalog.Reward> rewards
    ) {
        this.key = key;
        this.displayName = displayName;
        this.menuName = menuName;
        this.icon = icon;
        this.colour = colour;
        this.currency = currency;
        this.keyCost = keyCost;
        this.closesAt = closesAt;
        this.rewards = rewards;
    }

    private static volatile java.util.function.LongSupplier eventEnd = () -> 1_789_192_800_000L;
    private static volatile java.util.function.BooleanSupplier dragonAvailable = () -> false;
    private static volatile java.util.function.LongSupplier dragonEnd = () -> 0L;

    static void eventEndSource(java.util.function.LongSupplier source) {
        eventEnd = source;
    }

    static void dragonAvailableSource(java.util.function.BooleanSupplier source) {
        dragonAvailable = source == null ? () -> false : source;
    }

    static void dragonEndSource(java.util.function.LongSupplier source) {
        dragonEnd = source == null ? () -> 0L : source;
    }

    String key() {
        return key;
    }

    String displayName() {
        return displayName;
    }

    /**
     * The name the crate screens use.
     *
     * <p>Shorter on purpose: "Opening 3x Limited Amethyst Crate" is a title bar's worth
     * of qualifier before it reaches the crate. Chat announcements, the hologram and the
     * key lore keep the full name, which is where "Limited" is actually telling somebody
     * something they did not already know.
     */
    String menuName() {
        return menuName;
    }

    Material icon() {
        return icon;
    }

    TextColor colour() {
        return colour;
    }

    int keyCost() {
        return keyCost;
    }

    Currency currency() {
        return currency;
    }

    List<CrateCatalog.Reward> rewards() {
        return rewards;
    }

    boolean available(long now) {
        if (this == DRAGON) return dragonAvailable.getAsBoolean();
        return !limited() || now < closesAt();
    }

    long closesAt() {
        if (this == AMETHYST) return eventEnd.getAsLong();
        return this == DRAGON ? dragonEnd.getAsLong() : closesAt;
    }

    /** Whether this crate closes at all. The permanent crate never does. */
    boolean limited() {
        return this == DRAGON || closesAt != Long.MAX_VALUE;
    }

    /**
     * The live countdown, to the second.
     *
     * <p>Separate from {@link #remaining(long)}, which rounds to the two coarsest
     * units a line rendered once can honestly show. A ticking display has to move
     * every second or it reads as a frozen timestamp, so seconds are always present
     * and the larger units drop away as they empty.
     */
    String countdown(long now) {
        long millis = Math.max(0L, closesAt() - now);
        if (millis <= 0L) {
            return "ENDED";
        }
        long totalSeconds = millis / 1_000L;
        long days = totalSeconds / 86_400L;
        long hours = (totalSeconds % 86_400L) / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;
        StringBuilder text = new StringBuilder();
        if (days > 0L) {
            text.append(days).append("d ");
        }
        if (days > 0L || hours > 0L) {
            text.append(hours).append("h ");
        }
        if (days > 0L || hours > 0L || minutes > 0L) {
            text.append(minutes).append("m ");
        }
        return text.append(seconds).append('s').toString();
    }

    /**
     * The two-line countdown banner every limited-crate surface shares, or nothing
     * at all for a crate that is not going anywhere.
     *
     * <p>One source for the wording and the colours, so the crate screens, the
     * physical chest's hologram and anything added later cannot drift apart — and
     * the permanent crate is refused here rather than at each call site, because a
     * caller that forgets is a Default Crate advertising a deadline it does not
     * have. Named colours only: hex never reaches a Bedrock client, and this is the
     * line a Bedrock player most needs to be able to read.
     */
    List<Component> countdownLines(long now) {
        if (!limited()) {
            return List.of();
        }
        if (!available(now)) {
            return List.of(
                    Component.text(LIMITED_HEADLINE_CLOSED, NamedTextColor.DARK_GRAY, TextDecoration.BOLD),
                    Component.text("NOW CLOSED", NamedTextColor.GRAY, TextDecoration.BOLD)
            );
        }
        return List.of(
                Component.text(LIMITED_HEADLINE, NamedTextColor.RED, TextDecoration.BOLD),
                Component.text(countdown(now), NamedTextColor.YELLOW, TextDecoration.BOLD)
        );
    }

    String remaining(long now) {
        if (!limited()) {
            return "Always available";
        }
        long millis = Math.max(0L, closesAt() - now);
        Duration duration = Duration.ofMillis(millis);
        long days = duration.toDays();
        long hours = duration.minusDays(days).toHours();
        long minutes = duration.minusDays(days).minusHours(hours).toMinutes();
        if (millis <= 0L) {
            return "Event ended";
        }
        if (days > 0L) {
            return days + "d " + hours + "h remaining";
        }
        if (hours > 0L) {
            return hours + "h " + minutes + "m remaining";
        }
        return Math.max(1L, minutes) + "m remaining";
    }

    /**
     * The share of this crate's table that is rare, which is the rate the published odds
     * advertise and therefore the rate {@link CrateOddsBalance} steers back towards.
     */
    double advertisedRareRate() {
        int total = rewards.stream().mapToInt(CrateCatalog.Reward::weight).sum();
        if (total <= 0) {
            return 0d;
        }
        int rare = rewards.stream()
                .filter(CrateCatalog.Reward::rare)
                .mapToInt(CrateCatalog.Reward::weight)
                .sum();
        return (double) rare / (double) total;
    }

    CrateCatalog.Reward randomPreview() {
        int ticket = java.util.concurrent.ThreadLocalRandom.current()
                .nextInt(CrateCatalog.TOTAL_WEIGHT);
        return CrateCatalog.rewardAt(rewards, ticket);
    }

    static Optional<CrateKind> from(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String needle = raw.strip().toLowerCase(Locale.ROOT);
        for (CrateKind kind : values()) {
            if (kind.key.equals(needle) || kind.name().toLowerCase(Locale.ROOT).equals(needle)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
