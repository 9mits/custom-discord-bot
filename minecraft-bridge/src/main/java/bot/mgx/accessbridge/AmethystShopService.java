package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.scheduler.BukkitTask;

import java.io.UncheckedIOException;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Runs the once-a-day rare listing on the Amethyst shelf.
 *
 * <p>The roll is a clock, not a login reward: it lands at its own time whether anybody
 * is online or not, and a server that was down over that moment rolls once on the way
 * back up rather than catching up on every missed day.
 *
 * <p>Nothing here rolls after the Amethyst Event closes. The shelf leaves with the
 * event, and a listing on a shelf nobody can open would only be sold to whoever
 * happened to have the screen open at the time.
 */
final class AmethystShopService {
    /** Half a minute is finer than any player can perceive a "random time" to be. */
    private static final long CHECK_TICKS = 600L;

    private final MGXAccessBridge plugin;
    private final AmethystDailyStockStore store;
    private final PlayerSettingsStore settings;
    private final RandomGenerator random;
    private final ZoneId zone;
    private BukkitTask task;

    AmethystShopService(
            MGXAccessBridge plugin, AmethystDailyStockStore store, PlayerSettingsStore settings
    ) {
        this(plugin, store, settings, ThreadLocalRandom.current(), ZoneId.systemDefault());
    }

    AmethystShopService(
            MGXAccessBridge plugin,
            AmethystDailyStockStore store,
            PlayerSettingsStore settings,
            RandomGenerator random,
            ZoneId zone
    ) {
        this.plugin = plugin;
        this.store = store;
        this.settings = settings;
        this.random = random;
        this.zone = zone;
    }

    void start() {
        stop();
        task = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::check, 20L, CHECK_TICKS
        );
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Today's listing, or nothing at all once the event that owns the shelf has closed. */
    Optional<AmethystDailyStock> today() {
        if (!ShopCatalog.Category.AMETHYST.available(System.currentTimeMillis())) {
            return Optional.empty();
        }
        return store.current();
    }

    /**
     * Takes one off the shelf, or refuses because the last one has gone.
     *
     * <p>The caller takes the money first and puts it back if this refuses. The other
     * order would let two buyers on the same tick both be handed the last item and only
     * one of them be charged for it.
     */
    boolean sell() {
        Optional<AmethystDailyStock> current = today();
        if (current.isEmpty() || current.get().soldOut()) {
            return false;
        }
        store.put(current.get().sold());
        return true;
    }

    private void check() {
        long now = System.currentTimeMillis();
        if (!ShopCatalog.Category.AMETHYST.available(now)) {
            return;
        }
        Optional<AmethystDailyStock> current = store.current();
        if (current.isPresent() && !current.get().due(now)) {
            return;
        }
        AmethystDailyStock rolled = AmethystDailyStock.roll(now, zone, random);
        try {
            store.put(rolled);
        } catch (UncheckedIOException exception) {
            plugin.getLogger().warning(
                    "Could not save today's Amethyst shop listing: " + exception.getMessage()
            );
            return;
        }
        announce(rolled);
    }

    /**
     * Said out loud because a listing that appears at a random time and sells out at
     * two or three is otherwise only ever found by whoever happened to open the shop.
     */
    private void announce(AmethystDailyStock stock) {
        PlayerBroadcast.broadcast(
                settings,
                PlayerSettingsStore.Setting.AIRDROP_ANNOUNCEMENTS,
                Component.text("AMETHYST SHOP » ", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                        .append(Component.text(stock.stock() + "x ", NamedTextColor.WHITE))
                        .append(Component.text(stock.displayName(), NamedTextColor.LIGHT_PURPLE,
                                TextDecoration.BOLD))
                        .append(Component.text(" in stock at ", NamedTextColor.WHITE))
                        .append(Component.text(
                                EconomyFormat.dollars(AmethystDailyStock.PRICE),
                                NamedTextColor.YELLOW, TextDecoration.BOLD))
                        .append(Component.text(" - /shop, Amethyst.", NamedTextColor.WHITE))
        );
    }
}
