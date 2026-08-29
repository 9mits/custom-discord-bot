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
            1, Long.MAX_VALUE, CrateCatalog.all()
    ),
    AMETHYST(
            "amethyst", "Limited Amethyst Crate", "Amethyst Crate", Material.AMETHYST_BLOCK,
            TextColor.color(0xB56CFF),
            2,
            // Saturday after next at 3:00 PM JST, resolved when the event was requested.
            1_788_588_000_000L, CrateCatalog.amethyst()
    );

    /** What a limited crate leads with while it is still open. */
    static final String LIMITED_HEADLINE = "LIMITED TIME! LEAVING IN:";
    /** The same banner once the deadline has passed. */
    static final String LIMITED_HEADLINE_CLOSED = "LIMITED TIME EVENT";

    private final String key;
    private final String displayName;
    private final String menuName;
    private final Material icon;
    private final TextColor colour;
    private final int keyCost;
    private final long closesAt;
    private final List<CrateCatalog.Reward> rewards;

    CrateKind(
            String key,
            String displayName,
            String menuName,
            Material icon,
            TextColor colour,
            int keyCost,
            long closesAt,
            List<CrateCatalog.Reward> rewards
    ) {
        this.key = key;
        this.displayName = displayName;
        this.menuName = menuName;
        this.icon = icon;
        this.colour = colour;
        this.keyCost = keyCost;
        this.closesAt = closesAt;
        this.rewards = rewards;
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

    List<CrateCatalog.Reward> rewards() {
        return rewards;
    }

    boolean available(long now) {
        return now < closesAt;
    }

    long closesAt() {
        return closesAt;
    }

    /** Whether this crate closes at all. The permanent crate never does. */
    boolean limited() {
        return closesAt != Long.MAX_VALUE;
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
        long millis = Math.max(0L, closesAt - now);
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
     * The two-line countdown banner every limited-crate surface shares.
     *
     * <p>One source for the wording and the colours, so the crate screens, the
     * physical chest's hologram and anything added later cannot drift apart. Named
     * colours only: hex never reaches a Bedrock client, and this is the line a
     * Bedrock player most needs to be able to read.
     */
    List<Component> countdownLines(long now) {
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
        if (this == DEFAULT) {
            return "Always available";
        }
        long millis = Math.max(0L, closesAt - now);
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

    CrateCatalog.Reward randomReward(int luckPercent) {
        if (this == AMETHYST) {
            int jackpotTicket = java.util.concurrent.ThreadLocalRandom.current()
                    .nextInt(CrateCatalog.HIDDEN_AMETHYST_ONE_IN);
            Optional<CrateCatalog.Reward> jackpot = CrateCatalog.hiddenAmethystAt(jackpotTicket);
            if (jackpot.isPresent()) {
                return jackpot.get();
            }
        }
        int total = CrateCatalog.luckyTotalWeight(rewards, luckPercent);
        int ticket = java.util.concurrent.ThreadLocalRandom.current().nextInt(total);
        return CrateCatalog.rewardAtLucky(rewards, ticket, luckPercent);
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
