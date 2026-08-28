package bot.mgx.accessbridge;

import net.kyori.adventure.text.format.TextColor;
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
