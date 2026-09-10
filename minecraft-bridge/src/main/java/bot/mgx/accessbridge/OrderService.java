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
    /**
     * The board's own buttons, in the bottom row and clear of what paging owns.
     *
     * <p>45 is Previous, 49 is Back and 53 is Next on a double chest, and a button
     * placed on one of those is drawn over and then never reachable — the click
     * handler checks Back first. {@code OrderSlotsTest} keeps them apart.
     */
    static final int CREATE_SLOT = 47;
    static final int MINE_SLOT = 48;
    static final int REFRESH_SLOT = 50;
    /**
     * The create screen, laid out like the shop's buy screen because it asks the
     * same question: how many, and for how much. A player who can already work one
     * of those screens can work this one without being told anything.
     */
    static final int[] QUANTITY_STEPS = {1, 8, 16, 32, 64};
    static final int[] QUANTITY_ADD_SLOTS = {10, 11, 12, 13, 14};
    static final int[] QUANTITY_TAKE_SLOTS = {19, 20, 21, 22, 23};
    static final long[] PRICE_STEPS = {100L, 1_000L, 10_000L, 100_000L, 1_000_000L};
    static final int[] PRICE_ADD_SLOTS = {28, 29, 30, 31, 32};
    static final int[] PRICE_TAKE_SLOTS = {37, 38, 39, 40, 41};
    static final int PREVIEW_SLOT = 16;
    static final int CONFIRM_SLOT = 25;
    static final int STACK_SLOT = 34;

    private final MGXAccessBridge plugin;
    private final OrderStore orders;
    private final EconomyStore money;
    private final AuctionStore auctions;
    private final PersonalNotificationService notifications;
    /** What each player is part-way through ordering, until they confirm or leave. */
    private final java.util.Map<UUID, Draft> drafts = new java.util.concurrent.ConcurrentHashMap<>();

    /** An order being built on screen. Nothing is charged until it is confirmed. */
    private record Draft(String material, int quantity, long priceEach) {
        Draft withQuantity(int updated) {
            return new Draft(material, Math.max(1,
                    Math.min(OrderRules.MAXIMUM_QUANTITY, updated)), priceEach);
        }

        Draft withPrice(long updated) {
            return new Draft(material, quantity, Math.max(1L,
                    Math.min(OrderRules.MAXIMUM_PRICE_EACH, updated)));
        }

        long total() {
            return OrderRules.escrowFor(quantity, priceEach);
        }
    }

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
            // Typing the numbers still works for anybody who prefers it, but the
            // bare verb opens the screen rather than printing usage at them.
            case "create", "buy", "place" -> {
                if (args.length >= 3) {
                    create(player, args);
                } else {
                    openCreate(player);
                }
            }
            case "mine", "list" -> openMine(player, 1);
            case "cancel" -> cancel(player, args);
            default -> info(player, "Use /order, or /order create <amount> <price each>.");
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
        if (!plain(held, held.getType().name())) {
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
        if (place(player, held.getType().name(), (int) quantity, priceEach, now)) {
            openBoard(player, 1);
        }
    }

    /**
     * Takes the money and records the order, or puts the money straight back.
     *
     * <p>The one place either of those happens, so the screen and the command cannot
     * drift into charging differently for the same order.
     *
     * @return whether the order actually went up
     */
    private boolean place(Player player, String material, int quantity, long priceEach, long now) {
        long escrow = OrderRules.escrowFor(quantity, priceEach);
        if (!money.tryWithdraw(player.getUniqueId(), escrow)) {
            error(player, "That order costs " + EconomyFormat.dollars(escrow)
                    + " up front and your balance moved.");
            return false;
        }
        OrderStore.Order placed;
        try {
            placed = orders.place(player.getUniqueId(), player.getName(),
                    material, quantity, priceEach, now);
        } catch (RuntimeException failure) {
            // The money is already gone. Putting it straight back is the only
            // acceptable outcome of a board that could not record the order.
            money.deposit(player.getUniqueId(), escrow);
            error(player, "The order could not be saved. Nothing was charged.");
            plugin.getLogger().warning("Could not place an order: " + failure.getMessage());
            return false;
        }
        info(player, "Ordered " + quantity + "x " + readable(placed.material())
                + " at " + EconomyFormat.dollars(priceEach) + " each. "
                + EconomyFormat.dollars(escrow) + " is held until it fills.");
        announce(placed);
        return true;
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
                "Hold what you want to buy,",
                "then click here.",
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
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Menu.Kind kind = menu.kind();
        if (kind != Menu.Kind.ORDER_BOARD && kind != Menu.Kind.ORDER_CREATE
                && kind != Menu.Kind.ORDER_MINE) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getInventory()) {
            return;
        }
        if (kind == Menu.Kind.ORDER_CREATE) {
            clickCreate(player, event.getSlot(), event.getInventory().getSize());
            return;
        }
        if (kind == Menu.Kind.ORDER_MINE) {
            clickMine(player, menu, event.getSlot(), event.getInventory().getSize());
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
        if (slot == REFRESH_SLOT) {
            openBoard(player, menu.page());
            return;
        }
        if (slot == CREATE_SLOT) {
            openCreate(player);
            return;
        }
        if (slot == MINE_SLOT) {
            openMine(player, 1);
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

    private void clickCreate(Player player, int slot, int size) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) {
            openBoard(player, 1);
            return;
        }
        if (slot == MenuItems.backSlot(size)) {
            drafts.remove(player.getUniqueId());
            openBoard(player, 1);
            return;
        }
        if (slot == CONFIRM_SLOT) {
            confirmDraft(player);
            return;
        }
        if (slot == STACK_SLOT) {
            drawCreate(player, draft.withQuantity(64));
            return;
        }
        for (int index = 0; index < QUANTITY_STEPS.length; index++) {
            if (slot == QUANTITY_ADD_SLOTS[index]) {
                drawCreate(player, draft.withQuantity(draft.quantity() + QUANTITY_STEPS[index]));
                return;
            }
            if (slot == QUANTITY_TAKE_SLOTS[index]) {
                drawCreate(player, draft.withQuantity(draft.quantity() - QUANTITY_STEPS[index]));
                return;
            }
        }
        for (int index = 0; index < PRICE_STEPS.length; index++) {
            if (slot == PRICE_ADD_SLOTS[index]) {
                drawCreate(player, draft.withPrice(draft.priceEach() + PRICE_STEPS[index]));
                return;
            }
            if (slot == PRICE_TAKE_SLOTS[index]) {
                drawCreate(player, draft.withPrice(draft.priceEach() - PRICE_STEPS[index]));
                return;
            }
        }
    }

    private void clickMine(Player player, Menu menu, int slot, int size) {
        if (slot == MenuItems.backSlot(size)) {
            openBoard(player, 1);
            return;
        }
        if (slot == PREVIOUS_SLOT) {
            openMine(player, menu.page() - 1);
            return;
        }
        if (slot == NEXT_SLOT) {
            openMine(player, menu.page() + 1);
            return;
        }
        List<OrderStore.Order> mine = orders.ordersOf(
                player.getUniqueId(), System.currentTimeMillis());
        int index = MenuPaging.firstIndex(menu.page(), mine.size(), PER_PAGE) + slot;
        if (slot < 0 || slot >= PER_PAGE || index >= mine.size()) {
            return;
        }
        refundCancelled(player, mine.get(index));
        openMine(player, menu.page());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu
                && (menu.kind() == Menu.Kind.ORDER_BOARD
                        || menu.kind() == Menu.Kind.ORDER_CREATE
                        || menu.kind() == Menu.Kind.ORDER_MINE)) {
            event.setCancelled(true);
        }
    }

    /** A draft is a screen's worth of state, and it leaves with the screen. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        drafts.remove(event.getPlayer().getUniqueId());
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

    /**
     * The screen that places an order, with nothing to type.
     *
     * <p>The item is whatever the player is holding, and the amount and the price are
     * both built by clicking. That matters more here than on most screens: roughly
     * half the people on this server are on Bedrock through Geyser, where typing two
     * numbers into chat is the single most annoying thing the client asks of anybody.
     */
    void openCreate(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            error(player, "Hold an example of what you want to buy, then press this again.");
            openBoard(player, 1);
            return;
        }
        if (!plain(held, held.getType().name())) {
            error(player, "Custom items cannot be ordered. Use the auction house.");
            openBoard(player, 1);
            return;
        }
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null || !draft.material().equals(held.getType().name())) {
            draft = new Draft(held.getType().name(), held.getAmount(), 100L);
        }
        drawCreate(player, draft);
    }

    private void drawCreate(Player player, Draft draft) {
        drafts.put(player.getUniqueId(), draft);
        long balance = money.balance(player.getUniqueId());
        Inventory inventory = create(Menu.Kind.ORDER_CREATE, 1,
                "Order  \u2022  " + readable(draft.material()), 0);
        for (int index = 0; index < QUANTITY_STEPS.length; index++) {
            int step = QUANTITY_STEPS[index];
            inventory.setItem(QUANTITY_ADD_SLOTS[index], pane(
                    Material.LIME_STAINED_GLASS_PANE, step, "Add " + step,
                    "Order " + step + " more"));
            inventory.setItem(QUANTITY_TAKE_SLOTS[index], pane(
                    Material.RED_STAINED_GLASS_PANE, step, "Take off " + step,
                    draft.quantity() <= step ? "Back to 1" : "Order " + step + " fewer"));
        }
        for (int index = 0; index < PRICE_STEPS.length; index++) {
            long step = PRICE_STEPS[index];
            inventory.setItem(PRICE_ADD_SLOTS[index], pane(
                    Material.YELLOW_STAINED_GLASS_PANE, index + 1,
                    "+" + EconomyFormat.dollars(step) + " each",
                    "Offer more per item"));
            inventory.setItem(PRICE_TAKE_SLOTS[index], pane(
                    Material.ORANGE_STAINED_GLASS_PANE, index + 1,
                    "-" + EconomyFormat.dollars(step) + " each",
                    draft.priceEach() <= step ? "Back to $1" : "Offer less per item"));
        }
        inventory.setItem(STACK_SLOT, button(Material.HOPPER, "One stack",
                "Set the amount to 64."));
        ItemStack preview = new ItemStack(materialOf(draft.material()),
                Math.max(1, Math.min(64, draft.quantity())));
        inventory.setItem(PREVIEW_SLOT, MenuItems.detailed(preview,
                draft.quantity() + "x " + readable(draft.material()),
                List.of(
                        line(EconomyFormat.dollars(draft.priceEach()) + " each"),
                        line(EconomyFormat.dollars(draft.total()) + " in total"),
                        line(""),
                        line("You have " + EconomyFormat.dollars(balance))
                )));
        String problem = OrderRules.problemWith(draft.quantity(), draft.priceEach(),
                balance, orders.countOpen(player.getUniqueId(), System.currentTimeMillis()));
        inventory.setItem(CONFIRM_SLOT, problem == null
                ? button(Material.LIME_CONCRETE, "Place the order",
                        List.of(EconomyFormat.dollars(draft.total()) + " is held now.",
                                "It comes back if nobody fills it."))
                // The reason lives on the button that would have refused, rather than
                // in a chat line drawn behind the screen the player is looking at.
                : button(Material.GRAY_CONCRETE, "Cannot place this", List.of(problem)));
        MenuItems.back(inventory);
        MenuItems.show(plugin, player, inventory);
    }

    private void confirmDraft(Player player) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) {
            openBoard(player, 1);
            return;
        }
        long now = System.currentTimeMillis();
        String problem = OrderRules.problemWith(draft.quantity(), draft.priceEach(),
                money.balance(player.getUniqueId()),
                orders.countOpen(player.getUniqueId(), now));
        if (problem != null) {
            error(player, problem);
            drawCreate(player, draft);
            return;
        }
        if (place(player, draft.material(), draft.quantity(), draft.priceEach(), now)) {
            drafts.remove(player.getUniqueId());
            openBoard(player, 1);
        } else {
            drawCreate(player, draft);
        }
    }

    /** Your own orders, as a board you can cancel from rather than a list in chat. */
    void openMine(Player player, int page) {
        long now = System.currentTimeMillis();
        List<OrderStore.Order> mine = orders.ordersOf(player.getUniqueId(), now);
        Inventory inventory = create(Menu.Kind.ORDER_MINE, page,
                "Your orders  \u2022  " + mine.size() + " open", mine.size());
        int first = MenuPaging.firstIndex(page, mine.size(), PER_PAGE);
        int last = MenuPaging.lastIndex(page, mine.size(), PER_PAGE);
        for (int index = first; index < last; index++) {
            OrderStore.Order order = mine.get(index);
            inventory.setItem(index - first, MenuItems.button(
                    materialOf(order.material()),
                    order.remaining() + "x " + readable(order.material()),
                    List.of(
                            EconomyFormat.dollars(order.priceEach()) + " each",
                            order.filled() + " / " + order.requested() + " delivered",
                            EconomyFormat.dollars(order.escrow()) + " still held",
                            "",
                            "Click to cancel and take it back."
                    )));
        }
        if (mine.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, "No orders up",
                    "Hold what you want to buy and", "press Place an order."));
        }
        MenuItems.paginate(inventory, page, mine.size(), true);
        MenuItems.show(plugin, player, inventory);
    }

    private static ItemStack pane(Material material, int amount, String name, String detail) {
        ItemStack pane = new ItemStack(material, Math.max(1, Math.min(64, amount)));
        return MenuItems.detailed(pane, name, List.of(line(detail)));
    }

    private static net.kyori.adventure.text.Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
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
        // Comparing with a fresh stack covers every kind of item metadata, not only
        // names and enchants. Potion contents, written books, filled maps, dyed gear,
        // bundle contents and plugin PDC are all economically different items even
        // when their Material is identical.
        return item.isSimilar(new ItemStack(item.getType()));
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
