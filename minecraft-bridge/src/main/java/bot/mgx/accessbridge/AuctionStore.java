package bot.mgx.accessbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Player-to-player listings and the mailbox expired items return to.
 *
 * <p>Item stacks are stored as already-serialised blobs so this class never touches
 * Bukkit. Opening a page deserialises only the 45 rows on screen.
 */
final class AuctionStore {
    static final int MAX_LISTINGS_PER_PLAYER = 14;
    /**
     * Where the live limits come from.
     *
     * <p>Set once the registry exists so these can move without a build. The constants
     * remain the defaults, and stand alone in tests, which construct the store directly.
     */
    private volatile java.util.function.ToLongFunction<String> limits = key -> -1L;

    void limitSource(java.util.function.ToLongFunction<String> source) {
        if (source != null) {
            this.limits = source;
        }
    }

    private long limit(String key, long fallback) {
        long value = limits.applyAsLong(key);
        return value < 0 ? fallback : value;
    }

    int maxListings() {
        return (int) limit("auction.maximum-listings", MAX_LISTINGS_PER_PLAYER);
    }

    long maxPrice() {
        return limit("auction.maximum-price", MAX_PRICE);
    }

    private long listingDuration() {
        return limit("auction.listing-hours", LISTING_DURATION_MILLIS / 3_600_000L) * 3_600_000L;
    }

    static final long LISTING_DURATION_MILLIS = 48L * 60L * 60L * 1000L;
    static final long MIN_PRICE = 1L;
    static final long MAX_PRICE = 100_000_000L;
    private static final int FORMAT_VERSION = 1;

    record Listing(
            UUID id,
            UUID seller,
            String sellerName,
            long price,
            long createdAt,
            long expiresAt,
            String material,
            int amount,
            String displayName,
            String itemData
    ) {
        boolean expired(long now) {
            return expiresAt <= now;
        }

        boolean matches(String query) {
            if (query == null || query.isBlank()) {
                return true;
            }
            String needle = query.strip().toLowerCase(Locale.ROOT);
            return material.toLowerCase(Locale.ROOT).contains(needle)
                    || displayName.toLowerCase(Locale.ROOT).contains(needle)
                    || sellerName.toLowerCase(Locale.ROOT).contains(needle);
        }
    }

    record Mail(UUID owner, String itemData, String reason, long createdAt) {
    }

    record Purchase(Listing listing, long paid, long received) {
    }

    private final Path file;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final List<Listing> listings = new ArrayList<>();
    private final List<Mail> mailbox = new ArrayList<>();

    AuctionStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.has("listings") && root.get("listings").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("listings")) {
                    listingFrom(element.getAsJsonObject()).ifPresent(listings::add);
                }
            }
            if (root.has("mailbox") && root.get("mailbox").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("mailbox")) {
                    mailFrom(element.getAsJsonObject()).ifPresent(mailbox::add);
                }
            }
        } catch (RuntimeException exception) {
            throw new IOException("Auction store is unreadable", exception);
        }
    }

    synchronized Listing list(
            UUID seller,
            String sellerName,
            long price,
            String material,
            int amount,
            String displayName,
            String itemData,
            long now
    ) {
        if (price < MIN_PRICE || price > maxPrice()) {
            throw new IllegalArgumentException(
                    "Price must be between " + EconomyFormat.dollars(MIN_PRICE)
                            + " and " + EconomyFormat.dollars(maxPrice()) + "."
            );
        }
        if (itemData == null || itemData.isBlank()) {
            throw new IllegalArgumentException("That item could not be listed.");
        }
        if (countBySeller(seller) >= maxListings()) {
            throw new IllegalArgumentException(
                    "You already have " + maxListings() + " listings."
            );
        }
        Listing listing = new Listing(
                UUID.randomUUID(),
                seller,
                sellerName == null ? "" : sellerName,
                price,
                now,
                now + listingDuration(),
                material == null ? "" : material,
                Math.max(1, amount),
                displayName == null || displayName.isBlank() ? material : displayName,
                itemData
        );
        listings.add(listing);
        try {
            persist();
        } catch (RuntimeException failure) {
            listings.remove(listing);
            throw failure;
        }
        return listing;
    }

    synchronized Optional<Listing> find(UUID listingId) {
        return listings.stream().filter(listing -> listing.id().equals(listingId)).findFirst();
    }

    /** What is on sale right now, for reading from outside the game. */
    synchronized com.google.gson.JsonObject snapshot(long now) {
        expire(now);
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        com.google.gson.JsonArray rows = new com.google.gson.JsonArray();
        long total = 0L;
        for (Listing listing : browse("", now)) {
            com.google.gson.JsonObject row = new com.google.gson.JsonObject();
            row.addProperty("seller", listing.sellerName());
            row.addProperty("material", listing.material());
            row.addProperty("amount", listing.amount());
            row.addProperty("display_name", listing.displayName());
            row.addProperty("price", listing.price());
            row.addProperty("expires_at", listing.expiresAt());
            rows.add(row);
            total += listing.price();
        }
        root.add("listings", rows);
        root.addProperty("count", rows.size());
        root.addProperty("total_value", total);
        return root;
    }

    synchronized List<Listing> browse(String query, long now) {
        expire(now);
        List<Listing> visible = new ArrayList<>();
        for (Listing listing : listings) {
            if (listing.matches(query)) {
                visible.add(listing);
            }
        }
        visible.sort(Comparator.comparingLong(Listing::createdAt).reversed());
        return visible;
    }

    synchronized List<Listing> listingsOf(UUID seller, long now) {
        expire(now);
        List<Listing> own = new ArrayList<>();
        for (Listing listing : listings) {
            if (listing.seller().equals(seller)) {
                own.add(listing);
            }
        }
        own.sort(Comparator.comparingLong(Listing::createdAt).reversed());
        return own;
    }

    synchronized int countBySeller(UUID seller) {
        int count = 0;
        for (Listing listing : listings) {
            if (listing.seller().equals(seller)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Takes the listing off the board and pays the seller. The caller then hands the
     * item to the buyer. Money moves first; a failed item hand-off drops at their feet
     * rather than reprinting cash.
     */
    synchronized Purchase buy(UUID buyer, UUID listingId, EconomyStore economy, long now) {
        expire(now);
        Listing listing = find(listingId).orElseThrow(
                () -> new IllegalArgumentException("That listing is gone.")
        );
        if (listing.seller().equals(buyer)) {
            throw new IllegalArgumentException("You cannot buy your own listing.");
        }
        if (economy.balance(buyer) < listing.price()) {
            throw new IllegalArgumentException(
                    "You need " + EconomyFormat.dollars(listing.price()) + "."
            );
        }
        long received = listing.price() - taxOn(listing.price());
        if (received > 0L && !economy.canDeposit(listing.seller(), received)) {
            throw new IllegalArgumentException("The seller's wallet cannot receive this payment.");
        }
        listings.removeIf(row -> row.id().equals(listingId));
        try {
            persist();
        } catch (RuntimeException failure) {
            listings.add(listing);
            throw failure;
        }
        boolean paid;
        try {
            paid = economy.tryPayment(buyer, listing.seller(), listing.price(), received);
        } catch (RuntimeException failure) {
            throw restoreListing(listing, failure);
        }
        if (!paid) {
            throw restoreListing(listing, new IllegalArgumentException(
                    "You need " + EconomyFormat.dollars(listing.price()) + "."
            ));
        }
        return new Purchase(listing, listing.price(), received);
    }

    synchronized Optional<Listing> cancel(UUID actor, UUID listingId, long now) {
        expire(now);
        Listing listing = find(listingId).orElse(null);
        if (listing == null) {
            return Optional.empty();
        }
        if (!listing.seller().equals(actor)) {
            throw new IllegalArgumentException("That listing is not yours.");
        }
        List<Listing> listingsBefore = List.copyOf(listings);
        List<Mail> mailboxBefore = List.copyOf(mailbox);
        listings.removeIf(row -> row.id().equals(listingId));
        mailbox.add(new Mail(actor, listing.itemData(), "cancelled", now));
        persistOrRestore(listingsBefore, mailboxBefore);
        return Optional.of(listing);
    }

    synchronized List<Mail> mailboxOf(UUID owner) {
        List<Mail> own = new ArrayList<>();
        for (Mail mail : mailbox) {
            if (mail.owner().equals(owner)) {
                own.add(mail);
            }
        }
        return own;
    }

    /**
     * Hands back at most {@code limit} of an owner's mail, oldest first, and leaves the
     * rest where it is.
     *
     * <p>Emptying the whole mailbox regardless of what the player could carry meant the
     * overflow was dropped on the floor — into lava, off a ledge, or to despawn while
     * they were sorting out the first stack. Mail that stays in the box is safe
     * indefinitely, so refusing to hand it over is the kinder failure.
     */
    synchronized List<Mail> collect(UUID owner, int limit) {
        List<Mail> mailboxBefore = List.copyOf(mailbox);
        List<Mail> collected = new ArrayList<>();
        if (limit <= 0) {
            return collected;
        }
        Iterator<Mail> iterator = mailbox.iterator();
        while (iterator.hasNext() && collected.size() < limit) {
            Mail mail = iterator.next();
            if (mail.owner().equals(owner)) {
                collected.add(mail);
                iterator.remove();
            }
        }
        if (!collected.isEmpty()) {
            persistOrRestore(List.copyOf(listings), mailboxBefore);
        }
        return collected;
    }

    synchronized int expire(long now) {
        List<Listing> listingsBefore = List.copyOf(listings);
        List<Mail> mailboxBefore = List.copyOf(mailbox);
        int moved = 0;
        Iterator<Listing> iterator = listings.iterator();
        while (iterator.hasNext()) {
            Listing listing = iterator.next();
            if (listing.expired(now)) {
                mailbox.add(new Mail(listing.seller(), listing.itemData(), "expired", now));
                iterator.remove();
                moved++;
            }
        }
        if (moved > 0) {
            persistOrRestore(listingsBefore, mailboxBefore);
        }
        return moved;
    }

    /**
     * Deletes every listing and mailbox entry whose item matches, rather than returning
     * them to their owners. Used when the thing they hold has stopped existing: handing
     * a wiped cosmetic back to its seller would only move the dead item somewhere else.
     */
    synchronized int listingCount() {
        return listings.size();
    }

    /** What every standing listing would fetch if it all sold at the asking price. */
    synchronized long totalAsking() {
        return listings.stream().mapToLong(Listing::price).sum();
    }

    synchronized int removeMatching(Predicate<String> matches) {
        List<Listing> listingsBefore = List.copyOf(listings);
        List<Mail> mailboxBefore = List.copyOf(mailbox);
        int removed = 0;
        Iterator<Listing> listingIterator = listings.iterator();
        while (listingIterator.hasNext()) {
            if (matches.test(listingIterator.next().itemData())) {
                listingIterator.remove();
                removed++;
            }
        }
        Iterator<Mail> mailIterator = mailbox.iterator();
        while (mailIterator.hasNext()) {
            if (matches.test(mailIterator.next().itemData())) {
                mailIterator.remove();
                removed++;
            }
        }
        if (removed > 0) {
            persistOrRestore(listingsBefore, mailboxBefore);
        }
        return removed;
    }

    synchronized int returnRestrictedListings(Predicate<String> restricted, long now) {
        List<Listing> listingsBefore = List.copyOf(listings);
        List<Mail> mailboxBefore = List.copyOf(mailbox);
        int moved = 0;
        Iterator<Listing> iterator = listings.iterator();
        while (iterator.hasNext()) {
            Listing listing = iterator.next();
            if (restricted.test(listing.itemData())) {
                mailbox.add(new Mail(listing.seller(), listing.itemData(), "restricted", now));
                iterator.remove();
                moved++;
            }
        }
        if (moved > 0) {
            persistOrRestore(listingsBefore, mailboxBefore);
        }
        return moved;
    }

    synchronized int clearAll() {
        int cleared = listings.size() + mailbox.size();
        if (cleared == 0) {
            return 0;
        }
        List<Listing> listingsBefore = List.copyOf(listings);
        List<Mail> mailboxBefore = List.copyOf(mailbox);
        listings.clear();
        mailbox.clear();
        persistOrRestore(listingsBefore, mailboxBefore);
        return cleared;
    }

    static long taxOn(long price) {
        return price / 20L;
    }

    private static Optional<Listing> listingFrom(JsonObject json) {
        try {
            return Optional.of(new Listing(
                    UUID.fromString(json.get("id").getAsString()),
                    UUID.fromString(json.get("seller").getAsString()),
                    json.has("seller_name") ? json.get("seller_name").getAsString() : "",
                    json.get("price").getAsLong(),
                    json.get("created_at").getAsLong(),
                    json.get("expires_at").getAsLong(),
                    json.get("material").getAsString(),
                    json.get("amount").getAsInt(),
                    json.has("display_name") ? json.get("display_name").getAsString() : "",
                    json.get("item").getAsString()
            ));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Mail> mailFrom(JsonObject json) {
        try {
            return Optional.of(new Mail(
                    UUID.fromString(json.get("owner").getAsString()),
                    json.get("item").getAsString(),
                    json.has("reason") ? json.get("reason").getAsString() : "expired",
                    json.get("created_at").getAsLong()
            ));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private RuntimeException restoreListing(Listing listing, RuntimeException failure) {
        if (find(listing.id()).isEmpty()) {
            listings.add(listing);
        }
        try {
            persist();
        } catch (RuntimeException restoreFailure) {
            failure.addSuppressed(restoreFailure);
            return new IllegalStateException(
                    "The auction payment failed and its listing could not be restored.",
                    failure
            );
        }
        return failure;
    }

    private void persistOrRestore(List<Listing> listingsBefore, List<Mail> mailboxBefore) {
        try {
            persist();
        } catch (RuntimeException failure) {
            listings.clear();
            listings.addAll(listingsBefore);
            mailbox.clear();
            mailbox.addAll(mailboxBefore);
            throw failure;
        }
    }

    private void persist() {
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        JsonArray listed = new JsonArray();
        for (Listing listing : listings) {
            JsonObject row = new JsonObject();
            row.addProperty("id", listing.id().toString());
            row.addProperty("seller", listing.seller().toString());
            row.addProperty("seller_name", listing.sellerName());
            row.addProperty("price", listing.price());
            row.addProperty("created_at", listing.createdAt());
            row.addProperty("expires_at", listing.expiresAt());
            row.addProperty("material", listing.material());
            row.addProperty("amount", listing.amount());
            row.addProperty("display_name", listing.displayName());
            row.addProperty("item", listing.itemData());
            listed.add(row);
        }
        root.add("listings", listed);
        JsonArray mail = new JsonArray();
        for (Mail entry : mailbox) {
            JsonObject row = new JsonObject();
            row.addProperty("owner", entry.owner().toString());
            row.addProperty("item", entry.itemData());
            row.addProperty("reason", entry.reason());
            row.addProperty("created_at", entry.createdAt());
            mail.add(row);
        }
        root.add("mailbox", mail);
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, gson.toJson(root), StandardCharsets.UTF_8);
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
