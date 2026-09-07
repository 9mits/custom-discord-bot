package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static bot.mgx.accessbridge.MenuItems.BOARD_SIZE;
import static bot.mgx.accessbridge.MenuItems.NEXT_SLOT;
import static bot.mgx.accessbridge.MenuItems.ORANGE;
import static bot.mgx.accessbridge.MenuItems.PER_PAGE;
import static bot.mgx.accessbridge.MenuItems.PREVIOUS_SLOT;
import static bot.mgx.accessbridge.MenuItems.button;

/**
 * {@code /order} — standing buy orders, paid up front.
 *
 * <p>The auction house asks "who wants what I have?". This asks the other question,
 * and it is the one this server needed: money sits in a handful of wallets with
 * nothing to spend it on, while everybody else has time and no way to sell it. An
 * order is somebody with money paying somebody else to go and do the mining.
 *
 * <p>Deliberately a chest board rather than a dialog. Half the players here are on
 * Bedrock through Geyser, where a chest renders identically and a dialog does not,
 * and this is a screen everyone has to be able to read.
 *
 * <p>Filling an order never needs the buyer to be online. The items go to the auction
 * mailbox they already collect from, and the money was taken from them when they
 * placed it — so the market runs at four in the morning with nobody watching, which
 * on a server whose peak is nine players is most of the day.
 */
final class OrderService implements CommandExecutor, TabCompleter, Listener {
    private static final int CREATE_SLOT = 47;
    private static final int MINE_SLOT = 49;
    private static final int REFRESH_SLOT = 51;

    private final MGXAccessBridge plugin;
    private final OrderStore orders;
    private final EconomyStore money;
    private final AuctionStore auctions;
    private final PersonalNotificationService notifications;

    OrderService(
            MGXAccessBridge plugin,
            OrderStore orders,
            EconomyStore money,
            AuctionStore auctions,
            PersonalNotificationService notifications
    ) {
        this.plugin = plugin;
        this.orders = orders;
        this.money = money;
        this.auctions = auctions;
        this.notifications = notifications;
    }

    /** Hands back the money on orders whose week is up. Runs on a timer. */
    void expireOrders() {
        long now = System.currentTimeMillis();
        for (OrderStore.Order order : orders.expire(now)) {
            long refund = OrderRules.refundFor(
                    order.requested(), order.filled(), order.priceEach());
            if (refund <= 0L) {
                continue;
            }
            try {
                money.deposit(order.buyer(), refund);
            } catch (RuntimeException failure) {
                plugin.getLogger().severe("Could not refund a lapsed order to "
                        + order.buyerName() + ": " + failure.getMessage());
                continue;
            }
            Player buyer = Bukkit.getPlayer(order.buyer());
            if (buyer != null) {
                info(buyer, "Your order for " + readable(order.material())
                        + " ran out of time. " + EconomyFormat.dollars(refund)
                        + " came back.");
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        args = CommandArgs.withoutEchoedSender(sender.getName(), args);
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Orders are placed by players.");
            return true;
        }
        if (args.length == 0) {
            openBoard(player, 1);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create", "buy", "place" -> create(player, args);
            case "mine", "list" -> openMine(player);
            case "cancel" -> cancel(player, args);
            default -> info(player, "Use /order, /order create <amount> <price each>,"
                    + " /order mine, or /order cancel <number>.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String label, String[] args
    ) {
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(
                    args[0], List.of("create", "mine", "cancel"), new ArrayList<>());
        }
        return List.of();
    }

    /**
     * Places an order for whatever the player is holding.
     *
     * <p>Held item rather than a typed name on purpose. Bedrock's keyboard is the
     * worst part of that client, and "the thing in my hand" needs no spelling.
     */
    private void create(Player player, String[] args) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            error(player, "Hold an example of what you want to buy, then run"
                    + " /order create <amount> <price each>.");
            return;
        }
        if (held.hasItemMeta() && held.getItemMeta().hasCustomModelData()) {
            // A custom item is not interchangeable with the plain material, and an
            // order that paid out for the wrong one would be a theft with a receipt.
            error(player, "Custom items cannot be ordered. Use the auction house.");
            return;
        }
        if (args.length < 3) {
            error(player, "Use /order create <amount> <price each>.");
            return;
        }
        long quantity;
        long priceEach;
        try {
            // The same parser /pay uses, so "5k" means the same thing everywhere.
            quantity = EconomyFormat.parseAmount(args[1]);
            priceEach = EconomyFormat.parseAmount(args[2]);
        } catch (IllegalArgumentException badNumber) {
            error(player, badNumber.getMessage());
            return;
        }
        if (quantity <= 0L || priceEach <= 0L) {
            error(player, "Both the amount and the price have to be positive.");
            return;
        }
        long now = System.currentTimeMillis();
        String problem = OrderRules.problemWith(
                (int) Math.min(Integer.MAX_VALUE, quantity), priceEach,
                money.balance(player.getUniqueId()),
                orders.countOpen(player.getUniqueId(), now)
        );
        if (problem != null) {
            error(player, problem);
            return;
        }
        long escrow = OrderRules.escrowFor((int) quantity, priceEach);
        if (!money.tryWithdraw(player.getUniqueId(), escrow)) {
            error(player, "That order costs " + EconomyFormat.dollars(escrow)
                    + " up front and your balance moved.");
            return;
        }
        OrderStore.Order placed;
        try {
            placed = orders.place(player.getUniqueId(), player.getName(),
                    held.getType().name(), (int) quantity, priceEach, now);
        } catch (RuntimeException failure) {
            // The money is already gone. Putting it straight back is the only
            // acceptable outcome of a board that could not record the order.
            money.deposit(player.getUniqueId(), escrow);
            error(player, "The order could not be saved. Nothing was charged.");
            plugin.getLogger().warning("Could not place an order: " + failure.getMessage());
            return;
        }
        info(player, "Ordered " + quantity + "x " + readable(placed.material())
                + " at " + EconomyFormat.dollars(priceEach) + " each. "
                + EconomyFormat.dollars(escrow) + " is held until it fills.");
        announce(placed);
        openBoard(player, 1);
    }

    /** One line in chat, because an order nobody hears about is an order nobody fills. */
    private void announce(OrderStore.Order order) {
        Component line = PlayerMenuService.prefix()
                .append(Component.text(order.buyerName(), NamedTextColor.GOLD))
                .append(Component.text(" wants ", NamedTextColor.WHITE))
                .append(Component.text(order.requested() + "x "
                        + readable(order.material()), ORANGE))
                .append(Component.text(" at ", NamedTextColor.WHITE))
                .append(Component.text(EconomyFormat.dollars(order.priceEach())
                        + " each", NamedTextColor.GREEN))
                .append(Component.text("  —  /order", NamedTextColor.GRAY));
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            online.sendMessage(line);
        }
    }

    private void cancel(Player player, String[] args) {
        long now = System.currentTimeMillis();
        List<OrderStore.Order> mine = orders.ordersOf(player.getUniqueId(), now);
        if (mine.isEmpty()) {
            info(player, "You have no orders up.");
            return;
        }
        int index = args.length > 1 ? parseIndex(args[1]) : 1;
        if (index < 1 || index > mine.size()) {
            error(player, "Pick a number between 1 and " + mine.size() + ".");
            return;
        }
        refundCancelled(player, mine.get(index - 1));
    }

    private void refundCancelled(Player player, OrderStore.Order order) {
        long refund = OrderRules.refundFor(
                order.requested(), order.filled(), order.priceEach());
        if (orders.cancel(player.getUniqueId(), order.id()).isEmpty()) {
            error(player, "That order is no longer yours to cancel.");
            return;
        }
        if (refund > 0L) {
            try {
                money.deposit(player.getUniqueId(), refund);
            } catch (RuntimeException failure) {
                plugin.getLogger().severe("Could not refund a cancelled order to "
                        + player.getName() + ": " + failure.getMessage());
                error(player, "The order closed but the refund failed. Tell an operator.");
                return;
            }
        }
        info(player, "Order cancelled. " + EconomyFormat.dollars(refund) + " came back.");
    }

    void openBoard(Player player, int page) {
        long now = System.currentTimeMillis();
        List<OrderStore.Order> open = orders.open(now);
        Inventory inventory = create(Menu.Kind.ORDER_BOARD, page,
                "Orders  •  " + EconomyFormat.dollars(money.balance(player.getUniqueId())),
                open.size());
        int first = MenuPaging.firstIndex(page, open.size(), PER_PAGE);
        int last = MenuPaging.lastIndex(page, open.size(), PER_PAGE);
        for (int index = first; index < last; index++) {
            OrderStore.Order order = open.get(index);
            inventory.setItem(index - first, orderTile(player, order));
        }
        if (open.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, "No orders yet",
                    "Hold what you want to buy and run",
                    "/order create <amount> <price each>."));
        }
        inventory.setItem(CREATE_SLOT, button(Material.WRITABLE_BOOK, "Place an order",
                "Hold what you want to buy, then",
                "/order create <amount> <price each>",
                "The money is held until it fills."));
        inventory.setItem(MINE_SLOT, button(Material.CHEST, "Your orders",
                orders.countOpen(player.getUniqueId(), now) + " open"));
        inventory.setItem(REFRESH_SLOT, button(Material.SUNFLOWER, "Refresh"));
        MenuItems.paginate(inventory, page, open.size(), true);
        MenuItems.show(plugin, player, inventory);
    }

    private ItemStack orderTile(Player viewer, OrderStore.Order order) {
        int carried = countMatching(viewer, order.material());
        int couldFill = OrderRules.fillable(
                order.requested(), order.filled(), carried);
        List<String> lore = new ArrayList<>();
        lore.add(EconomyFormat.dollars(order.priceEach()) + " each");
        lore.add(order.filled() + " / " + order.requested() + " delivered");
        lore.add("Wanted by " + order.buyerName());
        lore.add("");
        if (order.buyer().equals(viewer.getUniqueId())) {
            lore.add("Your own order. Click to cancel.");
        } else if (couldFill > 0) {
            lore.add("Click to sell " + couldFill + " for "
                    + EconomyFormat.dollars(OrderRules.payoutFor(couldFill, order.priceEach())));
        } else {
            lore.add("You are not carrying any.");
        }
        return MenuItems.button(materialOf(order.material()),
                order.remaining() + "x " + readable(order.material()), lore);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)
                || menu.kind() != Menu.Kind.ORDER_BOARD
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getInventory()) {
            return;
        }
        int slot = event.getSlot();
        if (slot == MenuItems.backSlot(event.getInventory().getSize())) {
            player.closeInventory();
            Screens.home(player);
            return;
        }
        if (slot == PREVIOUS_SLOT) {
            openBoard(player, menu.page() - 1);
            return;
        }
        if (slot == NEXT_SLOT) {
            openBoard(player, menu.page() + 1);
            return;
        }
        if (slot == REFRESH_SLOT || slot == CREATE_SLOT) {
            if (slot == CREATE_SLOT) {
                info(player, "Hold what you want to buy, then run"
                        + " /order create <amount> <price each>.");
            }
            openBoard(player, menu.page());
            return;
        }
        if (slot == MINE_SLOT) {
            openMine(player);
            return;
        }
        long now = System.currentTimeMillis();
        List<OrderStore.Order> open = orders.open(now);
        int index = MenuPaging.firstIndex(menu.page(), open.size(), PER_PAGE) + slot;
        if (slot < 0 || slot >= PER_PAGE || index >= open.size()) {
            return;
        }
        OrderStore.Order order = open.get(index);
        if (order.buyer().equals(player.getUniqueId())) {
            refundCancelled(player, order);
        } else {
            fulfil(player, order);
        }
        openBoard(player, menu.page());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu
                && menu.kind() == Menu.Kind.ORDER_BOARD) {
            event.setCancelled(true);
        }
    }

    /**
     * Hands over what the seller is carrying and pays them for it.
     *
     * <p>Order of operations is the whole safety of this: the store's fill is written
     * to disk first and reports exactly how many it accepted, the items are then taken
     * for that number, and the money is paid last. A crash anywhere leaves the seller
     * holding their items, never the buyer paying for nothing.
     */
    private void fulfil(Player seller, OrderStore.Order order) {
        int carried = countMatching(seller, order.material());
        if (carried <= 0) {
            error(seller, "You are not carrying any " + readable(order.material()) + ".");
            return;
        }
        int wanted = OrderRules.fillable(order.requested(), order.filled(), carried);
        if (wanted <= 0) {
            error(seller, "That order is already full.");
            return;
        }
        int accepted = orders.fill(order.id(), wanted, System.currentTimeMillis());
        if (accepted <= 0) {
            error(seller, "Somebody filled that order first.");
            return;
        }
        int taken = takeMatching(seller, order.material(), accepted);
        if (taken < accepted) {
            // Should not happen — the count was taken from the same inventory a tick
            // ago — but paying for items that were not handed over is the one outcome
            // worth an explicit guard.
            plugin.getLogger().warning("Order fill took " + taken + " of " + accepted
                    + " from " + seller.getName() + "; paying only for what was taken.");
            accepted = taken;
        }
        if (accepted <= 0) {
            return;
        }
        deliver(order, accepted);
        long payout = OrderRules.payoutFor(accepted, order.priceEach());
        try {
            money.deposit(seller.getUniqueId(), payout);
        } catch (RuntimeException failure) {
            plugin.getLogger().severe("Could not pay " + seller.getName()
                    + " for an order fill: " + failure.getMessage());
            error(seller, "The payment failed. Tell an operator before selling more.");
            return;
        }
        notifications.notify(seller,
                PlayerMenuService.prefix().append(Component.text(
                        "Sold " + accepted + "x " + readable(order.material()) + " to "
                                + order.buyerName() + " for "
                                + EconomyFormat.dollars(payout) + ".",
                        NamedTextColor.GREEN)),
                Component.text("ORDER FILLED  •  " + EconomyFormat.dollars(payout),
                        NamedTextColor.GREEN));
        Player buyer = Bukkit.getPlayer(order.buyer());
        if (buyer != null) {
            info(buyer, seller.getName() + " delivered " + accepted + "x "
                    + readable(order.material()) + ". Collect it from /ah expired.");
        }
    }

    /** Items go to the mailbox the auction house already uses, online or not. */
    private void deliver(OrderStore.Order order, int amount) {
        Material material = materialOf(order.material());
        long now = System.currentTimeMillis();
        int remaining = amount;
        while (remaining > 0) {
            int stack = Math.min(remaining, material.getMaxStackSize());
            auctions.mail(order.buyer(),
                    EconomyMenuService.encodeItem(new ItemStack(material, stack)),
                    "order", now);
            remaining -= stack;
        }
    }

    private void openMine(Player player) {
        long now = System.currentTimeMillis();
        List<OrderStore.Order> mine = orders.ordersOf(player.getUniqueId(), now);
        if (mine.isEmpty()) {
            info(player, "You have no orders up. Hold an item and run"
                    + " /order create <amount> <price each>.");
            return;
        }
        player.sendMessage(Component.empty());
        int index = 1;
        for (OrderStore.Order order : mine) {
            info(player, index + ". " + order.filled() + "/" + order.requested() + " "
                    + readable(order.material()) + " at "
                    + EconomyFormat.dollars(order.priceEach()) + " each  —  "
                    + EconomyFormat.dollars(order.escrow()) + " still held");
            index++;
        }
        info(player, "Cancel one with /order cancel <number>.");
    }

    private Inventory create(Menu.Kind kind, int page, String title, int total) {
        Menu menu = new Menu(kind, null, page, null);
        Inventory inventory = Bukkit.createInventory(menu, BOARD_SIZE,
                Component.text(MenuItems.pagedTitle(title, page, total), ORANGE));
        menu.attach(inventory);
        return inventory;
    }

    /** Plain stacks only: a named or enchanted item is not the material it looks like. */
    private static boolean plain(ItemStack item, String material) {
        if (item == null || item.getType().isAir()
                || !item.getType().name().equals(material)) {
            return false;
        }
        return !item.hasItemMeta()
                || (!item.getItemMeta().hasDisplayName()
                        && !item.getItemMeta().hasEnchants()
                        && !item.getItemMeta().hasCustomModelData());
    }

    private static int countMatching(Player player, String material) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (plain(item, material)) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private static int takeMatching(Player player, String material, int amount) {
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack item = contents[slot];
            if (!plain(item, material)) {
                continue;
            }
            int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            contents[slot] = item.getAmount() <= 0 ? null : item;
            remaining -= take;
        }
        player.getInventory().setStorageContents(contents);
        return amount - remaining;
    }

    private static int parseIndex(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    private static Material materialOf(String name) {
        Material material = Material.matchMaterial(name);
        return material == null ? Material.BARRIER : material;
    }

    private static String readable(String material) {
        return material.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static void info(Player player, String text) {
        player.sendMessage(PlayerMenuService.prefix()
                .append(Component.text(text, NamedTextColor.WHITE)));
    }

    private static void error(Player player, String text) {
        player.sendMessage(PlayerMenuService.prefix()
                .append(Component.text(text, NamedTextColor.RED)));
    }
}
