package bot.mgx.accessbridge;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Standing buy orders: who wants what, how many, and what they will pay for each.
 *
 * <p>Money is the part that has to survive a crash. The buyer's payment is taken when
 * the order is placed and lives in {@code escrow} on the row itself, so a restart in
 * the middle of a half-filled order still knows exactly how much is owed to whoever
 * finishes it and how much goes back if nobody does.
 *
 * <p>Every mutation writes the file before it returns, and restores the previous state
 * if the write fails. An order board that forgets a fill is an order board that pays
 * twice, and this holds real balances.
 */
final class OrderStore {
    /**
     * One standing order.
     *
     * @param requested how many the buyer asked for
     * @param filled how many have been delivered so far
     * @param escrow money still held against the unfilled remainder
     */
    record Order(
            UUID id,
            UUID buyer,
            String buyerName,
            String material,
            int requested,
            int filled,
            long priceEach,
            long escrow,
            long createdAt,
            long expiresAt
    ) {
        int remaining() {
            return Math.max(0, requested - filled);
        }

        boolean complete() {
            return OrderRules.complete(requested, filled);
        }

        boolean expired(long now) {
            return expiresAt <= now;
        }
    }

    private final Path file;
    private final Map<UUID, Order> orders = new LinkedHashMap<>();

    OrderStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!root.has("orders") || !root.get("orders").isJsonArray()) {
                return;
            }
            for (JsonElement element : root.getAsJsonArray("orders")) {
                Order order = read(element.getAsJsonObject());
                orders.put(order.id(), order);
            }
        } catch (RuntimeException exception) {
            throw new IOException("Order store is unreadable", exception);
        }
    }

    /** Every order still open, best price first — the way a seller wants to read it. */
    synchronized List<Order> open(long now) {
        List<Order> rows = new ArrayList<>();
        for (Order order : orders.values()) {
            if (!order.complete() && !order.expired(now)) {
                rows.add(order);
            }
        }
        rows.sort(Comparator.comparingLong(Order::priceEach).reversed()
                .thenComparingLong(Order::createdAt));
        return List.copyOf(rows);
    }

    /** Open orders for one material, best price first. */
    synchronized List<Order> openFor(String material, long now) {
        String wanted = String.valueOf(material).toUpperCase(Locale.ROOT);
        List<Order> rows = new ArrayList<>();
        for (Order order : open(now)) {
            if (order.material().equalsIgnoreCase(wanted)) {
                rows.add(order);
            }
        }
        return List.copyOf(rows);
    }

    synchronized List<Order> ordersOf(UUID buyer, long now) {
        List<Order> rows = new ArrayList<>();
        for (Order order : orders.values()) {
            if (order.buyer().equals(buyer) && !order.complete() && !order.expired(now)) {
                rows.add(order);
            }
        }
        rows.sort(Comparator.comparingLong(Order::createdAt));
        return List.copyOf(rows);
    }

    synchronized int countOpen(UUID buyer, long now) {
        return ordersOf(buyer, now).size();
    }

    synchronized Optional<Order> find(UUID id) {
        return Optional.ofNullable(orders.get(id));
    }

    /** Records a placed order. The caller has already taken the money. */
    synchronized Order place(
            UUID buyer, String buyerName, String material,
            int quantity, long priceEach, long now
    ) {
        Order order = new Order(
                UUID.randomUUID(), buyer, buyerName,
                material.toUpperCase(Locale.ROOT), quantity, 0, priceEach,
                OrderRules.escrowFor(quantity, priceEach), now,
                now + OrderRules.LIFETIME_MILLIS
        );
        Map<UUID, Order> before = new LinkedHashMap<>(orders);
        orders.put(order.id(), order);
        persistOrRestore(before);
        return order;
    }

    /**
     * Takes items against an order.
     *
     * <p>The escrow is reduced by exactly what is paid out, in the same write as the
     * fill count. The two cannot drift apart, which is what stops a restart between
     * them paying somebody twice for the same stack.
     *
     * @return how many were actually accepted, which may be fewer than offered
     */
    synchronized int fill(UUID id, int offered, long now) {
        Order order = orders.get(id);
        if (order == null || order.complete() || order.expired(now)) {
            return 0;
        }
        int accepted = OrderRules.fillable(order.requested(), order.filled(), offered);
        if (accepted <= 0) {
            return 0;
        }
        Map<UUID, Order> before = new LinkedHashMap<>(orders);
        long paid = OrderRules.payoutFor(accepted, order.priceEach());
        orders.put(id, new Order(
                order.id(), order.buyer(), order.buyerName(), order.material(),
                order.requested(), order.filled() + accepted, order.priceEach(),
                Math.max(0L, order.escrow() - paid), order.createdAt(), order.expiresAt()
        ));
        persistOrRestore(before);
        return accepted;
    }

    /**
     * Closes an order and reports what is owed back to its buyer.
     *
     * @return the refund, or empty when the order was not the actor's to cancel
     */
    synchronized Optional<Order> cancel(UUID actor, UUID id) {
        Order order = orders.get(id);
        if (order == null || !order.buyer().equals(actor)) {
            return Optional.empty();
        }
        Map<UUID, Order> before = new LinkedHashMap<>(orders);
        orders.remove(id);
        persistOrRestore(before);
        return Optional.of(order);
    }

    /** Drops orders whose time is up, handing each one back for its refund. */
    synchronized List<Order> expire(long now) {
        List<Order> lapsed = new ArrayList<>();
        for (Order order : orders.values()) {
            if (order.expired(now) || order.complete()) {
                lapsed.add(order);
            }
        }
        if (lapsed.isEmpty()) {
            return List.of();
        }
        Map<UUID, Order> before = new LinkedHashMap<>(orders);
        for (Order order : lapsed) {
            orders.remove(order.id());
        }
        persistOrRestore(before);
        return List.copyOf(lapsed);
    }

    /** Forgets every order. Only a full server data reset has any business here. */
    synchronized int clearAll() {
        int cleared = orders.size();
        if (cleared == 0) {
            return 0;
        }
        Map<UUID, Order> before = new LinkedHashMap<>(orders);
        orders.clear();
        persistOrRestore(before);
        return cleared;
    }

    private static Order read(JsonObject row) {
        return new Order(
                UUID.fromString(row.get("id").getAsString()),
                UUID.fromString(row.get("buyer").getAsString()),
                row.has("buyer_name") ? row.get("buyer_name").getAsString() : "",
                row.get("material").getAsString(),
                row.get("requested").getAsInt(),
                row.has("filled") ? row.get("filled").getAsInt() : 0,
                row.get("price_each").getAsLong(),
                row.has("escrow") ? row.get("escrow").getAsLong() : 0L,
                row.get("created_at").getAsLong(),
                row.get("expires_at").getAsLong()
        );
    }

    private void persistOrRestore(Map<UUID, Order> before) {
        try {
            persist();
        } catch (IOException exception) {
            orders.clear();
            orders.putAll(before);
            throw new UncheckedIOException(exception);
        }
    }

    private void persist() throws IOException {
        JsonObject root = new JsonObject();
        JsonArray rows = new JsonArray();
        for (Order order : orders.values()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", order.id().toString());
            row.addProperty("buyer", order.buyer().toString());
            row.addProperty("buyer_name", order.buyerName());
            row.addProperty("material", order.material());
            row.addProperty("requested", order.requested());
            row.addProperty("filled", order.filled());
            row.addProperty("price_each", order.priceEach());
            row.addProperty("escrow", order.escrow());
            row.addProperty("created_at", order.createdAt());
            row.addProperty("expires_at", order.expiresAt());
            rows.add(row);
        }
        root.add("orders", rows);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
