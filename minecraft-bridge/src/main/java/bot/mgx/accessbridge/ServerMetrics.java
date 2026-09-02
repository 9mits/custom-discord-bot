package bot.mgx.accessbridge;

import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The numbers an owner is actually tuning, published for the panel to record.
 *
 * <p>The panel had 527 settings, nearly all of them about the economy, and sampled
 * eleven figures — none of which were economic. So every change was made blind and its
 * effect was invisible: there was no way to ask whether raising the key rate did
 * anything, because nothing recorded what the key rate produced.
 *
 * <p>These are the counterparts. State figures (money in circulation, listings standing,
 * cosmetics minted) are counted on demand; event figures (crates opened, airdrops
 * claimed) come from {@link MetricCounters}, because once an event is over nothing
 * remembers it happened. The panel samples both on its own schedule and turns the
 * counters into rates.
 */
final class ServerMetrics {
    /** Counter keys, named the way they are charted. */
    static final String CRATES_OPENED = "crates.opened";
    static final String KEYS_EARNED = "crates.keys_earned";
    static final String AIRDROPS_CLAIMED = "airdrops.claimed";
    static final String AIRDROPS_SPAWNED = "airdrops.spawned";
    static final String AMETHYST_EVENTS = "events.amethyst_completed";
    static final String AUCTION_SALES = "auction.sales";
    static final String COSMETICS_MINTED = "cosmetics.minted";
    static final String MONEY_EARNED = "economy.earned";
    static final String MONEY_SPENT = "economy.spent";

    private ServerMetrics() {
    }

    /**
     * Everything worth charting, as one flat object of numbers.
     *
     * <p>Flat and numeric on purpose: the panel stores each as a time series, and a
     * nested or non-numeric value would need a special case at every layer between here
     * and the chart.
     */
    static JsonObject gather(
            EconomyStore economy,
            CosmeticStore cosmetics,
            ClanStore clans,
            AuctionStore auctions,
            MetricCounters counters
    ) {
        JsonObject root = new JsonObject();

        if (economy != null) {
            Collection<Long> balances = economy.snapshots().values();
            long total = 0L;
            long richest = 0L;
            int holders = 0;
            for (Long balance : balances) {
                long value = balance == null ? 0L : balance;
                total += value;
                richest = Math.max(richest, value);
                if (value > 0L) {
                    holders += 1;
                }
            }
            root.addProperty("economy.total_balance", total);
            root.addProperty("economy.richest_balance", richest);
            root.addProperty("economy.players_with_money", holders);
            // The mean is what moves when the economy inflates; the richest alone can be
            // one lucky player rather than a trend.
            root.addProperty("economy.average_balance", holders == 0 ? 0 : total / holders);
        }

        if (cosmetics != null) {
            root.addProperty("cosmetics.in_circulation", cosmetics.mintedCount());
        }

        if (clans != null) {
            root.addProperty("clans.count", clans.clanCount());
        }

        if (auctions != null) {
            root.addProperty("auction.listings", auctions.listingCount());
            root.addProperty("auction.total_asking", auctions.totalAsking());
        }

        if (counters != null) {
            for (Map.Entry<String, Long> entry : counters.all().entrySet()) {
                root.addProperty(entry.getKey(), entry.getValue());
            }
        }
        return root;
    }

    /** Convenience for call sites that only have a player id to hand. */
    static void countFor(MetricCounters counters, String key, UUID ignored, long amount) {
        if (counters != null) {
            counters.increment(key, amount);
        }
    }
}
