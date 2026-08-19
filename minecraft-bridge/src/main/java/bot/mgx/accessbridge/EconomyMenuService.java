package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.StringUtil;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static bot.mgx.accessbridge.MenuItems.BOARD_SIZE;
import static bot.mgx.accessbridge.MenuItems.NEXT_SLOT;
import static bot.mgx.accessbridge.MenuItems.ORANGE;
import static bot.mgx.accessbridge.MenuItems.PER_PAGE;
import static bot.mgx.accessbridge.MenuItems.PREVIOUS_SLOT;
import static bot.mgx.accessbridge.MenuItems.button;

/**
 * Shop, instant-sell and auction house, drawn like the other boards so Bedrock and
 * Java share one chest UI.
 *
 * <p>Layout follows Donut-style economy screens: category hub, priced stacks with
 * left/right/shift buy amounts, a deposit chest for {@code /sell}, and an auction
 * board of the listed items themselves.
 */
final class EconomyMenuService implements CommandExecutor, TabCompleter, Listener {
    private static final int SHOP_HUB_SIZE = 54;
    private static final int[] CATEGORY_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    private static final int WALLET_SLOT = 49;
    private static final int AH_SEARCH_SLOT = 46;
    private static final int AH_OWN_SLOT = 47;
    private static final int AH_MAIL_SLOT = 48;
    private static final int AH_REFRESH_SLOT = 50;
    private static final int CONFIRM_YES = 11;
    private static final int CONFIRM_ITEM = 13;
    private static final int CONFIRM_NO = 15;
    private static final int CONFIRM_SIZE = 27;
    private static final int MAIL_COLLECT_SLOT = EconomySlots.MAIL_COLLECT;
    private static final int BUY_SIZE = 54;
    private static final int BUY_ITEM_SLOT = 22;
    private static final int BUY_CONFIRM_SLOT = 40;
    /** Green above the item adds, red below subtracts, in the same order. */
    private static final int[] BUY_ADD_SLOTS = {10, 11, 12, 13, 14};
    private static final int[] BUY_TAKE_SLOTS = {28, 29, 30, 31, 32};
    private static final int[] BUY_STEPS = {1, 8, 16, 32, 64};
    /** The right-hand column of the picker: fill, spend, start over. */
    private static final int BUY_MAX_SLOT = EconomySlots.BUY_MAX;
    private static final int AUTOBUY_SLOT = EconomySlots.AUTOBUY;
    private static final int AUTO_INTERVAL_SLOT = EconomySlots.AUTO_INTERVAL;
    private static final int AUTO_DROP_SLOT = EconomySlots.AUTO_DROP;
    private static final int AUTO_START_SLOT = EconomySlots.AUTO_START;
    /** The item preview sits a row higher on the standing-order screen. */
    private static final int BUY_ITEM_SLOT_TOP = 13;
    private static final int BUY_RESET_SLOT = EconomySlots.BUY_RESET;

    private final MGXAccessBridge plugin;
    private final EconomyStore money;
    private final AuctionStore auctions;
    private final Map<UUID, String> auctionSearch = new ConcurrentHashMap<>();
    /** What each player is part-way through buying, cleared when the screen closes. */
    private final Map<UUID, PendingBuy> pendingBuys = new ConcurrentHashMap<>();
    /** Orders still being handed over, a few stacks a tick. */
    private final Map<UUID, Delivery> deliveries = new ConcurrentHashMap<>();
    /** Standing orders that keep buying on a timer. */
    private final Map<UUID, AutoOrder> autoOrders = new ConcurrentHashMap<>();
    /** What each open standing-order screen is configuring. */
    private final Map<UUID, PendingAuto> pendingAuto = new ConcurrentHashMap<>();

    private final PlayerSettingsStore settings;

    EconomyMenuService(
            MGXAccessBridge plugin,
            EconomyStore money,
            AuctionStore auctions,
            PlayerSettingsStore settings
    ) {
        this.plugin = plugin;
        this.money = money;
        this.auctions = auctions;
        this.settings = settings;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        args = CommandArgs.withoutEchoedSender(sender.getName(), args);
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Shop, sell and the auction house are menus. Use them in Minecraft.");
            return true;
        }
        String name = command.getName().toLowerCase(Locale.ROOT);
        try {
            switch (name) {
                case "shop" -> openShopHub(player);
                case "autosell" -> autoSellCommand(player);
                case "sell" -> sellCommand(player, args);
                case "ah" -> auctionCommand(player, args);
                default -> openShopHub(player);
            }
        } catch (IllegalArgumentException exception) {
            error(player, exception.getMessage());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args
    ) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("sell") && args.length == 1) {
            return partial(args[0], List.of("hand", "all"));
        }
        if (name.equals("ah") && args.length == 1) {
            return partial(args[0], List.of("sell", "listings", "expired", "search"));
        }
        return List.of();
    }

    void expireListings() {
        auctions.expire(System.currentTimeMillis());
    }

    /**
     * Releases a few stacks of every order in progress.
     *
     * <p>Runs every tick, so the first thing it does is notice there is nothing to do.
     * A delivery waits while the ground under its owner is still busy, which is what
     * lets an order be any size: the hoppers set the pace, and the floor never fills.
     */
    void deliverOrders() {
        runAutoOrders();
        if (deliveries.isEmpty()) {
            return;
        }
        deliveries.entrySet().removeIf(entry -> {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            Delivery delivery = entry.getValue();
            if (player == null || !player.isOnline()) {
                // Nowhere to put the rest, so it was never really sold.
                refund(entry.getKey(), delivery);
                return true;
            }
            Material material = materialOf(delivery.material());
            int release = BulkBuy.releaseThisTick(
                    delivery.remaining(), material.getMaxStackSize(), itemsAround(player));
            if (release <= 0) {
                return false;
            }
            int carried = Math.min(release, spaceFor(player, material));
            if (carried > 0) {
                give(player, new ItemStack(material, carried));
            }
            dropAtFeet(player, material, release - carried);
            int left = delivery.remaining() - release;
            if (left > 0) {
                entry.setValue(delivery.withRemaining(left));
                return false;
            }
            info(player, "Delivered the last of your "
                    + readable(delivery.material()) + ".");
            return true;
        });
    }

    /** Gives back what an order could not hand over. */
    private void refund(UUID playerId, Delivery delivery) {
        long owed = BulkBuy.refundFor(delivery.remaining(), delivery.unitPrice());
        if (owed > 0L) {
            money.deposit(playerId, owed);
        }
    }

    /** Refunds every order in progress, for a shutdown that would otherwise eat them. */
    void refundOrders() {
        deliveries.forEach(this::refund);
        deliveries.clear();
        // Standing orders have already paid for everything they handed over, so there
        // is nothing to give back — they simply stop.
        autoOrders.clear();
    }

    private static int itemsAround(Player player) {
        return player.getWorld()
                .getNearbyEntities(player.getLocation(), 6, 4, 6, entity -> entity instanceof Item)
                .size();
    }

    private record Delivery(String material, int remaining, long unitPrice) {
        Delivery withRemaining(int left) {
            return new Delivery(material, left, unitPrice);
        }
    }

    void closeAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu menu
                    && menu.kind() == Menu.Kind.SELL) {
                player.closeInventory();
            }
        }
    }

    private void sellCommand(Player player, String[] args) {
        if (args.length == 0) {
            openSellPreview(player);
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "hand" -> sellHand(player);
            case "all" -> sellInventory(player);
            default -> throw new IllegalArgumentException("Use /sell, /sell hand or /sell all.");
        }
    }

    private void autoSellCommand(Player player) {
        boolean on = settings.toggle(player.getUniqueId(), PlayerSettingsStore.Setting.AUTO_SELL);
        if (on) {
            info(player, "Auto sell is on. Anything the shop buys will be sold as it "
                    + "reaches your inventory. Run /autosell again to stop.");
            return;
        }
        info(player, "Auto sell is off.");
    }

    /**
     * Sells what the shop buys out of the inventory of everybody who asked for it.
     *
     * <p>On a timer rather than on pickup: a pickup event fires per item and would
     * mean a sale, a balance write and a message for every single drop off a farm.
     * A sweep sells whatever arrived since the last one in a single transaction.
     */
    void sweepAutoSell() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!settings.isEnabled(player.getUniqueId(), PlayerSettingsStore.Setting.AUTO_SELL)) {
                continue;
            }
            PlayerInventory inventory = player.getInventory();
            List<ItemStack> offered = new ArrayList<>();
            for (int slot = 0; slot < 36; slot++) {
                if (isInstantSellable(inventory.getItem(slot))) {
                    offered.add(inventory.getItem(slot));
                }
            }
            if (offered.isEmpty()) {
                continue;
            }
            Sold sold = sellStacks(player, offered);
            if (sold.count() == 0) {
                continue;
            }
            for (int slot = 0; slot < 36; slot++) {
                if (isInstantSellable(inventory.getItem(slot))) {
                    inventory.setItem(slot, null);
                }
            }
            money.deposit(player.getUniqueId(), sold.credit());
            // The action bar rather than chat: this fires every few seconds while a
            // farm is running, and chat would be unreadable within a minute.
            player.sendActionBar(Component.text(
                    "Auto sold " + sold.count() + " for "
                            + EconomyFormat.dollars(sold.credit()), ORANGE));
        }
    }

    private void auctionCommand(Player player, String[] args) {
        if (args.length == 0) {
            auctionSearch.remove(player.getUniqueId());
            openAuction(player, 1);
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "sell" -> {
                if (args.length < 2) {
                    throw new IllegalArgumentException("Usage: /ah sell <price>");
                }
                listHeld(player, EconomyFormat.parseAmount(args[1]));
            }
            case "listings", "listed" -> openOwn(player, 1);
            case "expired", "collect", "mailbox" -> openMail(player);
            case "search" -> {
                if (args.length < 2) {
                    throw new IllegalArgumentException("Usage: /ah search <name>");
                }
                auctionSearch.put(player.getUniqueId(), String.join(" ", List.of(args).subList(1, args.length)));
                openAuction(player, 1);
            }
            default -> throw new IllegalArgumentException(
                    "Use /ah, /ah sell <price>, /ah listings, /ah expired or /ah search <name>."
            );
        }
    }

    void openShopHub(Player player) {
        Inventory inventory = create(
                Menu.Kind.SHOP_HUB, null, 1, SHOP_HUB_SIZE,
                "Shop  •  " + EconomyFormat.dollars(money.balance(player.getUniqueId())),
                null
        );
        List<ShopCatalog.Category> categories = ShopCatalog.categories();
        for (int index = 0; index < categories.size() && index < CATEGORY_SLOTS.length; index++) {
            ShopCatalog.Category category = categories.get(index);
            inventory.setItem(CATEGORY_SLOTS[index], button(
                    materialOf(category.icon()),
                    category.title(),
                    List.of(
                            ShopCatalog.offers(category).size() + " items.",
                            "Click to browse."
                    )
            ));
        }
        inventory.setItem(WALLET_SLOT, button(
                Material.GOLD_INGOT,
                "Your balance",
                List.of(EconomyFormat.dollars(money.balance(player.getUniqueId())))
        ));
        MenuItems.show(plugin, player, inventory);
    }

    void openShopCategory(Player player, ShopCatalog.Category category, int page) {
        List<ShopCatalog.Offer> offers = ShopCatalog.offers(category);
        Inventory inventory = create(
                Menu.Kind.SHOP_CATEGORY,
                categoryId(category),
                page,
                BOARD_SIZE,
                MenuItems.pagedTitle(category.title() + "  •  "
                        + EconomyFormat.dollars(money.balance(player.getUniqueId())), page, offers.size()),
                Menu.Destination.of(Menu.Kind.SHOP_HUB)
        );
        int first = MenuPaging.firstIndex(page, offers.size(), PER_PAGE);
        int last = MenuPaging.lastIndex(page, offers.size(), PER_PAGE);
        for (int index = first; index < last; index++) {
            ShopCatalog.Offer offer = offers.get(index);
            Material material = materialOf(offer.material());
            ItemStack item = new ItemStack(material, 1);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text(readable(offer.material()), ORANGE, TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false));
                long each = offer.unitPrice();
                meta.lore(List.of(
                        line(EconomyFormat.dollars(each) + " each"),
                        line(EconomyFormat.dollars(offer.costOfItems(64)) + " a stack"),
                        line(""),
                        line("Click to choose an amount.")
                ));
                item.setItemMeta(meta);
            }
            inventory.setItem(index - first, item);
        }
        if (offers.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, "Nothing here"));
        }
        MenuItems.paginate(inventory, page, offers.size(), true);
        MenuItems.show(plugin, player, inventory);
    }

    /**
     * The amount picker: green panes above add, red panes below take away, and the
     * item in the middle always shows what the current amount costs.
     *
     * <p>Replaces the per-click amount toggle that used to sit on the category page.
     * That existed because Geyser gives the server no right or shift click from a
     * Bedrock container, so the modifier shortcuts were Java-only — a screen with real
     * buttons is the same on both editions and says the price out loud before anyone
     * spends anything.
     */
    void openBuy(Player player, ShopCatalog.Offer offer, ShopCatalog.Category category, int categoryPage, int quantity) {
        Material material = materialOf(offer.material());
        int space = spaceFor(player, material);
        int stackSize = material.getMaxStackSize();
        int wanted = Math.max(1, Math.min(BulkBuy.ceiling(space, stackSize), quantity));
        int spill = BulkBuy.overflow(wanted, space);
        // Changing the amount disarms it, so the second click always confirms the
        // figure that was on screen when the first one was made.
        boolean armed = armedFor(player, offer, wanted);
        pendingBuys.put(player.getUniqueId(),
                new PendingBuy(offer, category, categoryPage, wanted, armed));
        long balance = money.balance(player.getUniqueId());
        long total = offer.costOfItems(wanted);
        boolean affordable = total <= balance;
        Inventory inventory = create(
                Menu.Kind.SHOP_BUY, categoryId(category), categoryPage, BUY_SIZE,
                "Buy  •  " + readable(offer.material()),
                new Menu.Destination(Menu.Kind.SHOP_CATEGORY, categoryId(category), categoryPage)
        );
        for (int index = 0; index < BUY_STEPS.length; index++) {
            int step = BUY_STEPS[index];
            inventory.setItem(BUY_ADD_SLOTS[index], pane(
                    Material.LIME_STAINED_GLASS_PANE, step, "Add " + step,
                    EconomyFormat.dollars(offer.costOfItems(step)) + " more"
            ));
            inventory.setItem(BUY_TAKE_SLOTS[index], pane(
                    Material.RED_STAINED_GLASS_PANE, step, "Take off " + step,
                    wanted <= step ? "Back to 1" : EconomyFormat.dollars(offer.costOfItems(step)) + " less"
            ));
        }
        int carryable = Math.max(1, offer.maxItems(balance, space));
        inventory.setItem(BUY_MAX_SLOT, button(
                Material.GOLD_INGOT, "Fill my inventory",
                List.of(
                        carryable + " " + readable(offer.material()),
                        EconomyFormat.dollars(offer.costOfItems(carryable)),
                        "Nothing dropped."
                )
        ));
        AutoOrder standing = autoOrders.get(player.getUniqueId());
        boolean running = standing != null && standing.material().equals(offer.material());
        inventory.setItem(AUTOBUY_SLOT, button(
                running ? Material.CLOCK : Material.REPEATER,
                running ? "Auto buying" : "Auto buy",
                running
                        ? List.of(
                                standing.quantity() + " every " + standing.intervalSeconds() + "s",
                                standing.drop() ? "Dropped at your feet" : "Into your inventory",
                                "Click to change or stop.")
                        : List.of(
                                "Keep buying " + wanted + " on a timer.",
                                "For farms — restock without",
                                "coming back to the shop.")
        ));
        inventory.setItem(BUY_RESET_SLOT, button(
                Material.BARRIER, "Back to 1", List.of("Start the amount over.")
        ));

        ItemStack preview = new ItemStack(materialOf(offer.material()), Math.max(1, Math.min(64, wanted)));
        ItemMeta meta = preview.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(readable(offer.material()), ORANGE, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    line("Buying " + wanted),
                    line(EconomyFormat.dollars(offer.unitPrice()) + " each"),
                    line(""),
                    line("Total " + EconomyFormat.dollars(total)),
                    line("You have " + EconomyFormat.dollars(balance))
            ));
            preview.setItemMeta(meta);
        }
        inventory.setItem(BUY_ITEM_SLOT, preview);

        List<String> confirmLore = new ArrayList<>();
        confirmLore.add(wanted + " " + readable(offer.material()));
        confirmLore.add("Total " + EconomyFormat.dollars(total));
        if (!affordable) {
            confirmLore.add("");
            confirmLore.add("You are short " + EconomyFormat.dollars(total - balance) + ".");
        } else if (spill > 0) {
            // A note, not a refusal: delivering the remainder is the point of
            // "spend it all", and saying so beforehand is what keeps it from
            // surprising somebody who only wanted what they could carry.
            confirmLore.add("");
            confirmLore.add(spill + " delivered over "
                    + BulkBuy.deliverySeconds(spill, stackSize) + "s.");
            confirmLore.add("Log off and it is refunded.");
        }
        // An order big enough to be delivered spends most of a balance and cannot be
        // undone, so it takes two clicks. A pocketful takes one.
        boolean twoStep = spill > 0;
        Material face = Material.GRAY_CONCRETE;
        String label = "You cannot afford that";
        if (affordable && twoStep && !armed) {
            face = Material.YELLOW_CONCRETE;
            label = "Click to confirm";
            confirmLore.add("");
            confirmLore.add("Large order — click twice.");
        } else if (affordable && twoStep) {
            face = Material.LIME_CONCRETE;
            label = "Click again to buy";
            confirmLore.add("");
            confirmLore.add("Sure? This spends "
                    + EconomyFormat.dollars(total) + ".");
        } else if (affordable) {
            face = Material.LIME_CONCRETE;
            label = "Buy it";
        }
        inventory.setItem(BUY_CONFIRM_SLOT, button(face, label, confirmLore));
        MenuItems.back(inventory);
        MenuItems.show(plugin, player, inventory);
    }

    /**
     * The standing-order screen: how many, how often, and where they land.
     *
     * <p>Reached from an item's amount picker, so the quantity is whatever was chosen
     * there — pick sixty-four bone meal, then set it to repeat.
     */
    void openAutoBuy(Player player, ShopCatalog.Offer offer, ShopCatalog.Category category, int categoryPage, int quantity, int intervalSeconds, boolean drop) {
        Material material = materialOf(offer.material());
        int wanted = Math.max(1, quantity);
        pendingAuto.put(player.getUniqueId(),
                new PendingAuto(offer, category, categoryPage, wanted, intervalSeconds, drop));
        AutoOrder standing = autoOrders.get(player.getUniqueId());
        boolean running = standing != null && standing.material().equals(offer.material());
        long each = offer.costOfItems(wanted);
        Inventory inventory = create(
                Menu.Kind.SHOP_AUTOBUY, categoryId(category), categoryPage, BUY_SIZE,
                "Auto buy  •  " + readable(offer.material()),
                new Menu.Destination(Menu.Kind.SHOP_CATEGORY, categoryId(category), categoryPage)
        );
        inventory.setItem(AUTO_INTERVAL_SLOT, button(
                Material.CLOCK, "Every " + intervalSeconds + "s",
                List.of(
                        "Click to cycle 1, 2, 5, 10, 30.",
                        EconomyFormat.dollars(each * (60 / Math.max(1, intervalSeconds))) + " a minute"
                )
        ));
        ItemStack preview = new ItemStack(material, Math.max(1, Math.min(64, wanted)));
        ItemMeta meta = preview.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(readable(offer.material()), ORANGE, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    line(wanted + " every " + intervalSeconds + "s"),
                    line(EconomyFormat.dollars(each) + " each time"),
                    line(""),
                    line("You have " + EconomyFormat.dollars(money.balance(player.getUniqueId())))
            ));
            preview.setItemMeta(meta);
        }
        inventory.setItem(BUY_ITEM_SLOT_TOP, preview);
        inventory.setItem(AUTO_DROP_SLOT, button(
                drop ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                drop ? "Dropping at your feet" : "Into your inventory",
                drop
                        ? List.of("Straight to the ground for", "hoppers to pick up.", "", "Click to keep them instead.")
                        : List.of("Kept until your inventory", "is full, then stops.", "", "Click to drop them instead.")
        ));
        inventory.setItem(AUTO_START_SLOT, button(
                running ? Material.RED_CONCRETE : Material.LIME_CONCRETE,
                running ? "Stop" : "Start",
                running
                        ? List.of("Buying " + standing.quantity() + " every "
                                + standing.intervalSeconds() + "s.")
                        : List.of(
                                "Runs until you stop it,",
                                "run out of money, or log off.")
        ));
        MenuItems.back(inventory);
        MenuItems.show(plugin, player, inventory);
    }

    private void clickAutoBuy(Player player, int slot) {
        PendingAuto pending = pendingAuto.get(player.getUniqueId());
        if (pending == null) {
            openShopHub(player);
            return;
        }
        if (slot == AUTO_INTERVAL_SLOT) {
            openAutoBuy(player, pending.offer(), pending.category(), pending.categoryPage(),
                    pending.quantity(), AutoBuy.nextInterval(pending.intervalSeconds()), pending.drop());
            return;
        }
        if (slot == AUTO_DROP_SLOT) {
            openAutoBuy(player, pending.offer(), pending.category(), pending.categoryPage(),
                    pending.quantity(), pending.intervalSeconds(), !pending.drop());
            return;
        }
        if (slot != AUTO_START_SLOT) {
            return;
        }
        AutoOrder standing = autoOrders.get(player.getUniqueId());
        if (standing != null && standing.material().equals(pending.offer().material())) {
            stopAutoBuy(player, "Auto buy stopped.");
            openAutoBuy(player, pending.offer(), pending.category(), pending.categoryPage(),
                    pending.quantity(), pending.intervalSeconds(), pending.drop());
            return;
        }
        autoOrders.put(player.getUniqueId(), new AutoOrder(
                pending.offer().material(), pending.offer().unitPrice(), pending.quantity(),
                pending.intervalSeconds(), pending.drop(), plugin.getServer().getCurrentTick()));
        info(player, "Auto buying " + pending.quantity() + " "
                + readable(pending.offer().material()) + " every "
                + pending.intervalSeconds() + "s"
                + (pending.drop() ? ", dropped at your feet." : ", into your inventory.")
                + " Stop it from the same screen.");
        openAutoBuy(player, pending.offer(), pending.category(), pending.categoryPage(),
                pending.quantity(), pending.intervalSeconds(), pending.drop());
    }

    private void stopAutoBuy(Player player, String reason) {
        if (autoOrders.remove(player.getUniqueId()) != null && reason != null) {
            info(player, reason);
        }
    }

    /**
     * Runs every standing order that is due.
     *
     * <p>Shares the delivery tick, and like it waits while the ground under the player
     * is still busy — a farm that has fallen behind should not have more thrown at it.
     */
    private void runAutoOrders() {
        if (autoOrders.isEmpty()) {
            return;
        }
        long now = plugin.getServer().getCurrentTick();
        autoOrders.entrySet().removeIf(entry -> {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            AutoOrder order = entry.getValue();
            if (player == null || !player.isOnline()) {
                return true;
            }
            if (!AutoBuy.due(now, order.lastRun(), order.intervalSeconds())) {
                return false;
            }
            if (!AutoBuy.affordable(money.balance(entry.getKey()), order.unitPrice(), order.quantity())) {
                info(player, "Auto buy stopped: you ran out of money.");
                return true;
            }
            Material material = materialOf(order.material());
            if (order.drop() && itemsAround(player) >= BulkBuy.GROUND_LIMIT) {
                // The floor has not been cleared yet; try again next tick rather than
                // stacking more on top of it.
                return false;
            }
            if (!order.drop() && spaceFor(player, material) < order.quantity()) {
                info(player, "Auto buy stopped: your inventory is full.");
                return true;
            }
            long cost = order.unitPrice() * (long) order.quantity();
            if (!money.tryWithdraw(entry.getKey(), cost)) {
                info(player, "Auto buy stopped: you ran out of money.");
                return true;
            }
            if (order.drop()) {
                dropAtFeet(player, material, order.quantity());
            } else {
                give(player, new ItemStack(material, order.quantity()));
            }
            entry.setValue(order.withLastRun(now));
            return false;
        });
    }

    private record AutoOrder(
            String material, long unitPrice, int quantity, int intervalSeconds,
            boolean drop, long lastRun
    ) {
        AutoOrder withLastRun(long tick) {
            return new AutoOrder(material, unitPrice, quantity, intervalSeconds, drop, tick);
        }
    }

    private record PendingAuto(
            ShopCatalog.Offer offer, ShopCatalog.Category category, int categoryPage,
            int quantity, int intervalSeconds, boolean drop
    ) {
    }

    /** A glass pane counted to its own step, so the number reads off the stack too. */
    private static ItemStack pane(Material material, int step, String name, String detail) {
        ItemStack item = new ItemStack(material, Math.max(1, Math.min(64, step)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, ORANGE, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(line(detail)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private record PendingBuy(
            ShopCatalog.Offer offer,
            ShopCatalog.Category category,
            int categoryPage,
            int quantity,
            boolean armed
    ) {
        PendingBuy armed(boolean value) {
            return new PendingBuy(offer, category, categoryPage, quantity, value);
        }
    }

    void openSell(Player player) {
        Inventory inventory = create(
                Menu.Kind.SELL, null, 1, BOARD_SIZE,
                "Sell  •  " + EconomyFormat.dollars(money.balance(player.getUniqueId())),
                null
        );
        MenuItems.show(plugin, player, inventory);
    }

    void openSellPreview(Player player) {
        List<ItemStack> sellable = sellableIn(player);
        Inventory inventory = create(
                Menu.Kind.SELL_PREVIEW, null, 1, BOARD_SIZE,
                "Sell  •  " + EconomyFormat.dollars(previewTotal(sellable)),
                null
        );
        int shown = Math.min(sellable.size(), 45);
        for (int index = 0; index < shown; index++) {
            ItemStack stack = sellable.get(index).clone();
            long credit = ShopCatalog.sellCredit(stack.getType().name(), stack.getAmount());
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.lore(List.of(
                        line("Sell for " + EconomyFormat.dollars(credit)),
                        line("Click to sell this stack.")
                ));
                stack.setItemMeta(meta);
            }
            inventory.setItem(index, stack);
        }
        if (sellable.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, "Nothing to sell",
                    "Nothing you are holding sells here,",
                    "or open the price list."));
        }
        inventory.setItem(45, button(Material.HOPPER, "Sell all",
                "Sell every listed stack."));
        inventory.setItem(49, button(Material.CHEST, "Put items in",
                "Drop extras in a chest to sell."));
        inventory.setItem(53, button(Material.BOOK, "Price list",
                "What the shop pays for each item."));
        MenuItems.show(plugin, player, inventory);
    }

    void openSellPrices(Player player, int page) {
        List<ShopCatalog.SellQuote> quotes = ShopCatalog.allSellQuotes();
        Inventory inventory = create(
                Menu.Kind.SELL_PRICES,
                null,
                page,
                BOARD_SIZE,
                MenuItems.pagedTitle("Sell prices", page, quotes.size()),
                Menu.Destination.of(Menu.Kind.SELL_PREVIEW)
        );
        int first = MenuPaging.firstIndex(page, quotes.size(), PER_PAGE);
        int last = MenuPaging.lastIndex(page, quotes.size(), PER_PAGE);
        for (int index = first; index < last; index++) {
            ShopCatalog.SellQuote quote = quotes.get(index);
            Material material = materialOf(quote.material());
            ItemStack item = new ItemStack(material, 1);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text(readable(quote.material()), ORANGE, TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false));
                meta.lore(List.of(
                        line(quote.group()),
                        line("Sells for " + EconomyFormat.dollars(quote.unitPrice()) + " each")
                ));
                item.setItemMeta(meta);
            }
            inventory.setItem(index - first, item);
        }
        MenuItems.paginate(inventory, page, quotes.size(), true);
        MenuItems.show(plugin, player, inventory);
    }

    void openAuction(Player player, int page) {
        String query = auctionSearch.getOrDefault(player.getUniqueId(), "");
        List<AuctionStore.Listing> rows = auctions.browse(query, System.currentTimeMillis());
        String title = query.isBlank()
                ? "Auction House  •  " + EconomyFormat.dollars(money.balance(player.getUniqueId()))
                : "Search  •  " + query;
        Inventory inventory = create(
                Menu.Kind.AUCTION_HUB, null, page, BOARD_SIZE,
                MenuItems.pagedTitle(title, page, rows.size()),
                null
        );
        drawListings(inventory, rows, page, false);
        inventory.setItem(AH_SEARCH_SLOT, button(
                Material.COMPASS, "Search",
                "Use /ah search <name>.",
                query.isBlank() ? "Showing every listing." : "Filter: " + query
        ));
        inventory.setItem(AH_OWN_SLOT, button(
                Material.CHEST, "Your listings",
                auctions.countBySeller(player.getUniqueId()) + " active.",
                "Click to manage them."
        ));
        inventory.setItem(AH_MAIL_SLOT, button(
                Material.ENDER_CHEST, "Collect items",
                auctions.mailboxOf(player.getUniqueId()).size() + " waiting.",
                "Expired and cancelled listings."
        ));
        inventory.setItem(AH_REFRESH_SLOT, button(Material.SUNFLOWER, "Refresh"));
        inventory.setItem(WALLET_SLOT, button(
                Material.GOLD_INGOT, "Your balance",
                List.of(EconomyFormat.dollars(money.balance(player.getUniqueId())))
        ));
        MenuItems.paginate(inventory, page, rows.size(), false);
        MenuItems.show(plugin, player, inventory);
    }

    void openOwn(Player player, int page) {
        List<AuctionStore.Listing> rows = auctions.listingsOf(player.getUniqueId(), System.currentTimeMillis());
        Inventory inventory = create(
                Menu.Kind.AUCTION_OWN, player.getUniqueId(), page, BOARD_SIZE,
                MenuItems.pagedTitle("Your listings", page, rows.size()),
                Menu.Destination.of(Menu.Kind.AUCTION_HUB)
        );
        drawListings(inventory, rows, page, true);
        if (rows.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, "No listings",
                    "Hold an item and use /ah sell <price>."));
        }
        MenuItems.paginate(inventory, page, rows.size(), true);
        MenuItems.show(plugin, player, inventory);
    }

    void openMail(Player player) {
        List<AuctionStore.Mail> waiting = auctions.mailboxOf(player.getUniqueId());
        Inventory inventory = create(
                Menu.Kind.AUCTION_MAIL, player.getUniqueId(), 1, BOARD_SIZE,
                "Collect items",
                Menu.Destination.of(Menu.Kind.AUCTION_HUB)
        );
        int shown = Math.min(waiting.size(), PER_PAGE);
        for (int index = 0; index < shown; index++) {
            ItemStack item = decodeItem(waiting.get(index).itemData());
            if (item != null) {
                inventory.setItem(index, item);
            }
        }
        if (waiting.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, "Nothing to collect"));
        } else {
            // Not WALLET_SLOT: that is 49, which is where Back sits on a full board.
            inventory.setItem(MAIL_COLLECT_SLOT, button(
                    Material.LIME_CONCRETE, "Collect all",
                    waiting.size() + " item(s) waiting.",
                    "Everything here goes to your inventory."
            ));
        }
        MenuItems.back(inventory);
        MenuItems.show(plugin, player, inventory);
    }

    void openConfirm(Player player, AuctionStore.Listing listing) {
        Inventory inventory = create(
                Menu.Kind.AUCTION_CONFIRM, listing.id(), 1, CONFIRM_SIZE,
                "Buy for " + EconomyFormat.dollars(listing.price()),
                Menu.Destination.of(Menu.Kind.AUCTION_HUB)
        );
        inventory.setItem(CONFIRM_YES, button(
                Material.LIME_CONCRETE, "Confirm",
                "Pay " + EconomyFormat.dollars(listing.price()) + ".",
                "Your balance: " + EconomyFormat.dollars(money.balance(player.getUniqueId()))
        ));
        ItemStack preview = listingIcon(listing, false);
        if (preview != null) {
            inventory.setItem(CONFIRM_ITEM, preview);
        }
        inventory.setItem(CONFIRM_NO, button(Material.RED_CONCRETE, "Cancel"));
        MenuItems.show(plugin, player, inventory);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)
                || !(event.getWhoClicked() instanceof Player player)
                || !isEconomy(menu.kind())) {
            return;
        }
        if (menu.kind().acceptsItems()) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getInventory()) {
            return;
        }
        int slot = event.getSlot();
        if (menu.hasBack() && slot == MenuItems.backSlot(menu.getInventory().getSize())) {
            openDestination(player, menu.back());
            return;
        }
        try {
            switch (menu.kind()) {
                case SHOP_HUB -> clickShopHub(player, slot);
                case SHOP_CATEGORY -> clickShopCategory(player, menu, slot);
                case SHOP_BUY -> clickBuy(player, slot);
                case SHOP_AUTOBUY -> clickAutoBuy(player, slot);
                case SELL_PREVIEW -> clickSellPreview(player, slot);
                case SELL_PRICES -> clickSellPrices(player, menu, slot);
                case AUCTION_HUB -> clickAuction(player, menu, slot);
                case AUCTION_OWN -> clickOwn(player, menu, slot);
                case AUCTION_MAIL -> clickMail(player, slot);
                case AUCTION_CONFIRM -> clickConfirm(player, menu, slot);
                default -> { }
            }
        } catch (IllegalArgumentException exception) {
            error(player, exception.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu
                && isEconomy(menu.kind())
                && !menu.kind().acceptsItems()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)
                || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (menu.kind() == Menu.Kind.SHOP_BUY) {
            // Only when they are not on their way to another buy screen, which opens a
            // tick later and would otherwise find its own state already discarded.
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof Menu open)
                        || open.kind() != Menu.Kind.SHOP_BUY) {
                    pendingBuys.remove(player.getUniqueId());
                }
            });
            return;
        }
        if (menu.kind() == Menu.Kind.SHOP_AUTOBUY) {
            // The standing order itself keeps running; only the screen state goes.
            pendingAuto.remove(player.getUniqueId());
            return;
        }
        if (menu.kind() == Menu.Kind.SELL) {
            settleSell(player, event.getInventory());
        }
    }

    private void clickSellPreview(Player player, int slot) {
        if (slot == 45) {
            sellInventory(player);
            openSellPreview(player);
            return;
        }
        if (slot == 49) {
            openSell(player);
            return;
        }
        if (slot == 53) {
            openSellPrices(player, 1);
            return;
        }
        List<ItemStack> sellable = sellableIn(player);
        if (slot < 0 || slot >= sellable.size() || slot >= 45) {
            return;
        }
        ItemStack target = sellable.get(slot);
        PlayerInventory inventory = player.getInventory();
        for (int index = 0; index < 36; index++) {
            ItemStack item = inventory.getItem(index);
            if (item == null || !sameSellStack(item, target)) {
                continue;
            }
            Sold sold = sellStacks(player, List.of(item));
            if (sold.count() == 0) {
                throw new IllegalArgumentException("That cannot be sold here.");
            }
            inventory.setItem(index, null);
            money.deposit(player.getUniqueId(), sold.credit());
            info(player, "Sold " + sold.describe() + " for " + EconomyFormat.dollars(sold.credit()) + ".");
            logSale(player, sold);
            openSellPreview(player);
            return;
        }
    }

    private void clickSellPrices(Player player, Menu menu, int slot) {
        if (slot == PREVIOUS_SLOT) {
            openSellPrices(player, menu.page() - 1);
            return;
        }
        if (slot == NEXT_SLOT) {
            openSellPrices(player, menu.page() + 1);
        }
    }

    private static List<ItemStack> sellableIn(Player player) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (isInstantSellable(item)) {
                stacks.add(item);
            }
        }
        return stacks;
    }

    private static long previewTotal(List<ItemStack> stacks) {
        long total = 0L;
        for (ItemStack item : stacks) {
            total += ShopCatalog.sellCredit(item.getType().name(), item.getAmount());
        }
        return total;
    }

    private static boolean sameSellStack(ItemStack left, ItemStack right) {
        return left.getType() == right.getType() && left.getAmount() == right.getAmount();
    }

    private void clickShopHub(Player player, int slot) {
        List<ShopCatalog.Category> categories = ShopCatalog.categories();
        for (int index = 0; index < categories.size() && index < CATEGORY_SLOTS.length; index++) {
            if (slot == CATEGORY_SLOTS[index]) {
                openShopCategory(player, categories.get(index), 1);
                return;
            }
        }
    }

    private void clickShopCategory(Player player, Menu menu, int slot) {
        ShopCatalog.Category category = categoryOf(menu.subject()).orElse(ShopCatalog.Category.STONE);
        if (slot == PREVIOUS_SLOT) {
            openShopCategory(player, category, menu.page() - 1);
            return;
        }
        if (slot == NEXT_SLOT) {
            openShopCategory(player, category, menu.page() + 1);
            return;
        }
        List<ShopCatalog.Offer> offers = ShopCatalog.offers(category);
        int index = MenuPaging.firstIndex(menu.page(), offers.size(), PER_PAGE) + slot;
        if (slot < 0 || slot >= PER_PAGE || index >= offers.size()) {
            return;
        }
        openBuy(player, offers.get(index), category, menu.page(), 1);
    }

    private void clickBuy(Player player, int slot) {
        PendingBuy pending = pendingBuys.get(player.getUniqueId());
        if (pending == null) {
            openShopHub(player);
            return;
        }
        for (int index = 0; index < BUY_STEPS.length; index++) {
            if (slot == BUY_ADD_SLOTS[index]) {
                redrawBuy(player, pending, pending.quantity() + BUY_STEPS[index]);
                return;
            }
            if (slot == BUY_TAKE_SLOTS[index]) {
                redrawBuy(player, pending, pending.quantity() - BUY_STEPS[index]);
                return;
            }
        }
        if (slot == BUY_RESET_SLOT) {
            redrawBuy(player, pending, 1);
            return;
        }
        Material material = materialOf(pending.offer().material());
        if (slot == BUY_MAX_SLOT) {
            redrawBuy(player, pending, pending.offer()
                    .maxItems(money.balance(player.getUniqueId()), spaceFor(player, material)));
            return;
        }
        if (slot == AUTOBUY_SLOT) {
            AutoOrder standing = autoOrders.get(player.getUniqueId());
            boolean running = standing != null
                    && standing.material().equals(pending.offer().material());
            openAutoBuy(player, pending.offer(), pending.category(), pending.categoryPage(),
                    running ? standing.quantity() : pending.quantity(),
                    running ? standing.intervalSeconds() : AutoBuy.firstInterval(),
                    running ? standing.drop() : true);
            return;
        }
        if (slot != BUY_CONFIRM_SLOT) {
            return;
        }
        int space = spaceFor(player, material);
        if (BulkBuy.overflow(pending.quantity(), space) > 0 && !pending.armed()) {
            pendingBuys.put(player.getUniqueId(), pending.armed(true));
            redrawBuy(player, pending, pending.quantity());
            return;
        }
        buy(player, pending.offer(), pending.quantity());
        pendingBuys.remove(player.getUniqueId());
        openShopCategory(player, pending.category(), pending.categoryPage());
    }

    private void redrawBuy(Player player, PendingBuy pending, int quantity) {
        openBuy(player, pending.offer(), pending.category(), pending.categoryPage(), quantity);
    }

    /** Whether the confirm is already half pressed for exactly this order. */
    private boolean armedFor(Player player, ShopCatalog.Offer offer, int quantity) {
        PendingBuy previous = pendingBuys.get(player.getUniqueId());
        return previous != null
                && previous.armed()
                && previous.quantity() == quantity
                && previous.offer().material().equals(offer.material());
    }

    private void clickAuction(Player player, Menu menu, int slot) {
        if (slot == PREVIOUS_SLOT) {
            openAuction(player, menu.page() - 1);
            return;
        }
        if (slot == NEXT_SLOT) {
            openAuction(player, menu.page() + 1);
            return;
        }
        if (slot == AH_SEARCH_SLOT) {
            info(player, "Type /ah search <name> to filter the board.");
            return;
        }
        if (slot == AH_OWN_SLOT) {
            openOwn(player, 1);
            return;
        }
        if (slot == AH_MAIL_SLOT) {
            openMail(player);
            return;
        }
        if (slot == AH_REFRESH_SLOT || slot == WALLET_SLOT) {
            openAuction(player, menu.page());
            return;
        }
        List<AuctionStore.Listing> rows = auctions.browse(
                auctionSearch.getOrDefault(player.getUniqueId(), ""),
                System.currentTimeMillis()
        );
        int index = MenuPaging.firstIndex(menu.page(), rows.size(), PER_PAGE) + slot;
        if (slot < 0 || slot >= PER_PAGE || index >= rows.size()) {
            return;
        }
        openConfirm(player, rows.get(index));
    }

    private void clickOwn(Player player, Menu menu, int slot) {
        if (slot == PREVIOUS_SLOT) {
            openOwn(player, menu.page() - 1);
            return;
        }
        if (slot == NEXT_SLOT) {
            openOwn(player, menu.page() + 1);
            return;
        }
        List<AuctionStore.Listing> rows = auctions.listingsOf(player.getUniqueId(), System.currentTimeMillis());
        int index = MenuPaging.firstIndex(menu.page(), rows.size(), PER_PAGE) + slot;
        if (slot < 0 || slot >= PER_PAGE || index >= rows.size()) {
            return;
        }
        auctions.cancel(player.getUniqueId(), rows.get(index).id(), System.currentTimeMillis());
        info(player, "Listing cancelled. Collect it from the mailbox.");
        openOwn(player, menu.page());
    }

    private void clickMail(Player player, int slot) {
        if (slot != MAIL_COLLECT_SLOT) {
            return;
        }
        collectMail(player);
        openMail(player);
    }

    private void clickConfirm(Player player, Menu menu, int slot) {
        if (slot == CONFIRM_NO) {
            openAuction(player, 1);
            return;
        }
        if (slot != CONFIRM_YES) {
            return;
        }
        AuctionStore.Purchase purchase = auctions.buy(
                player.getUniqueId(), menu.subject(), money, System.currentTimeMillis()
        );
        ItemStack item = decodeItem(purchase.listing().itemData());
        if (item != null) {
            give(player, item);
        }
        info(player, "Bought for " + EconomyFormat.dollars(purchase.paid()) + ".");
        Player seller = plugin.getServer().getPlayer(purchase.listing().seller());
        if (seller != null) {
            info(seller, player.getName() + " bought your listing for "
                    + EconomyFormat.dollars(purchase.received()) + ".");
        }
        openAuction(player, 1);
    }

    private void buy(Player player, ShopCatalog.Offer offer, int items) {
        if (items <= 0) {
            throw new IllegalArgumentException("Choose how many first.");
        }
        Material material = materialOf(offer.material());
        int wanted = Math.min(items, BulkBuy.ceiling(
                spaceFor(player, material), material.getMaxStackSize()));
        long cost = offer.costOfItems(wanted);
        if (!money.tryWithdraw(player.getUniqueId(), cost)) {
            throw new IllegalArgumentException(
                    "You need " + EconomyFormat.dollars(cost) + "."
            );
        }
        int carried = Math.min(wanted, spaceFor(player, material));
        if (carried > 0) {
            give(player, new ItemStack(material, carried));
        }
        int owed = wanted - carried;
        String name = readable(offer.material());
        if (owed > 0) {
            // Queued rather than dropped: the whole point of an order this size is
            // standing over a hopper and letting it arrive.
            deliveries.merge(
                    player.getUniqueId(),
                    new Delivery(offer.material(), owed, offer.unitPrice()),
                    (existing, added) -> existing.material().equals(added.material())
                            ? existing.withRemaining(existing.remaining() + added.remaining())
                            : added);
            info(player, "Bought " + wanted + " " + name + " for "
                    + EconomyFormat.dollars(cost) + ". " + owed
                    + " arriving over about "
                    + BulkBuy.deliverySeconds(owed, material.getMaxStackSize())
                    + "s — stand where you want it.");
        } else {
            info(player, "Bought " + wanted + " " + name
                    + " for " + EconomyFormat.dollars(cost) + ".");
        }
        items = wanted;
        report(player, "shop_buy",
                "Bought " + items + " " + name + " for " + EconomyFormat.dollars(cost))
                .detail("item", name)
                .detail("amount", items)
                .detail("paid", cost)
                .record();
    }

    private void sellHand(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        Sold sold = sellStacks(player, List.of(held));
        if (sold.count() == 0) {
            throw new IllegalArgumentException("That cannot be sold here.");
        }
        player.getInventory().setItemInMainHand(null);
        money.deposit(player.getUniqueId(), sold.credit());
        info(player, "Sold " + sold.describe() + " for " + EconomyFormat.dollars(sold.credit()) + ".");
        logSale(player, sold);
    }

    private void sellInventory(Player player) {
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.getType().isAir()) {
                stacks.add(item);
            }
        }
        Sold sold = sellStacks(player, stacks);
        if (sold.count() == 0) {
            throw new IllegalArgumentException("You are not carrying anything the shop buys.");
        }
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (isInstantSellable(item)) {
                inventory.setItem(slot, null);
            }
        }
        money.deposit(player.getUniqueId(), sold.credit());
        info(player, "Sold " + sold.describe() + " for " + EconomyFormat.dollars(sold.credit()) + ".");
        logSale(player, sold);
    }

    private void settleSell(Player player, Inventory inventory) {
        List<ItemStack> offered = new ArrayList<>();
        List<ItemStack> returned = new ArrayList<>();
        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (isInstantSellable(item)) {
                offered.add(item);
            } else {
                returned.add(item);
            }
        }
        inventory.clear();
        for (ItemStack item : returned) {
            give(player, item);
        }
        Sold sold = sellStacks(player, offered);
        if (sold.count() == 0) {
            if (!returned.isEmpty()) {
                error(player, "None of that can be sold here, so it came back.");
            }
            return;
        }
        money.deposit(player.getUniqueId(), sold.credit());
        info(player, "Sold " + sold.describe() + " for " + EconomyFormat.dollars(sold.credit()) + ".");
        logSale(player, sold);
    }

    private Sold sellStacks(Player player, List<ItemStack> stacks) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        long credit = 0L;
        int sold = 0;
        for (ItemStack item : stacks) {
            if (item == null || item.getType().isAir() || hasPreservedData(item)) {
                continue;
            }
            long value = ShopCatalog.sellCredit(item.getType().name(), item.getAmount());
            if (value <= 0L) {
                continue;
            }
            credit += value;
            sold += item.getAmount();
            counts.merge(item.getType().name(), item.getAmount(), Integer::sum);
        }
        return new Sold(player.getUniqueId(), sold, credit, counts);
    }

    private void listHeld(Player player, long price) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            throw new IllegalArgumentException("Hold the item you want to list.");
        }
        ItemStack listing = held.clone();
        String encoded = encodeItem(listing);
        String name = readable(listing.getType().name());
        player.getInventory().setItemInMainHand(null);
        try {
            auctions.list(
                    player.getUniqueId(),
                    player.getName(),
                    price,
                    listing.getType().name(),
                    listing.getAmount(),
                    name,
                    encoded,
                    System.currentTimeMillis()
            );
        } catch (RuntimeException failure) {
            player.getInventory().setItemInMainHand(listing);
            throw failure;
        }
        info(player, "Listed " + listing.getAmount() + " " + name + " for "
                + EconomyFormat.dollars(price) + ".");
    }

    private void collectMail(Player player) {
        List<AuctionStore.Mail> collected = auctions.collect(player.getUniqueId());
        if (collected.isEmpty()) {
            throw new IllegalArgumentException("Nothing to collect.");
        }
        int given = 0;
        for (AuctionStore.Mail mail : collected) {
            ItemStack item = decodeItem(mail.itemData());
            if (item != null) {
                give(player, item);
                given++;
            }
        }
        info(player, "Collected " + given + " item(s).");
    }

    private void drawListings(
            Inventory inventory, List<AuctionStore.Listing> rows, int page, boolean own
    ) {
        int first = MenuPaging.firstIndex(page, rows.size(), PER_PAGE);
        int last = MenuPaging.lastIndex(page, rows.size(), PER_PAGE);
        for (int index = first; index < last; index++) {
            ItemStack icon = listingIcon(rows.get(index), own);
            if (icon != null) {
                inventory.setItem(index - first, icon);
            }
        }
        if (rows.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, "No listings",
                    "Hold an item and use /ah sell <price>."));
        }
    }

    private ItemStack listingIcon(AuctionStore.Listing listing, boolean own) {
        ItemStack item = decodeItem(listing.itemData());
        if (item == null) {
            item = new ItemStack(materialOf(listing.material()), Math.max(1, listing.amount()));
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(line(""));
        lore.add(line("Seller: " + listing.sellerName()));
        lore.add(line("Price: " + EconomyFormat.dollars(listing.price())));
        lore.add(line("Time left: " + EconomyFormat.remaining(listing.expiresAt() - System.currentTimeMillis())));
        lore.add(line(own ? "Click to cancel." : "Click to buy."));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void openDestination(Player player, Menu.Destination back) {
        if (back.kind() == Menu.Kind.SHOP_HUB) {
            openShopHub(player);
            return;
        }
        if (back.kind() == Menu.Kind.SHOP_CATEGORY) {
            // Back off the amount picker returns to the shelf it was opened from,
            // on the page it was opened from. Falling through to the auction house
            // is what happens if this branch is missing.
            categoryOf(back.subject()).ifPresentOrElse(
                    category -> openShopCategory(player, category, Math.max(1, back.page())),
                    () -> openShopHub(player)
            );
            return;
        }
        if (back.kind() == Menu.Kind.SELL_PREVIEW) {
            openSellPreview(player);
            return;
        }
        openAuction(player, Math.max(1, back.page()));
    }

    private Inventory create(
            Menu.Kind kind, UUID subject, int page, int size, String title, Menu.Destination back
    ) {
        Menu menu = new Menu(kind, subject, page, back);
        Inventory inventory = Bukkit.createInventory(menu, size, Component.text(title, ORANGE));
        menu.attach(inventory);
        return inventory;
    }

    static boolean isEconomy(Menu.Kind kind) {
        return kind == Menu.Kind.SHOP_HUB
                || kind == Menu.Kind.SHOP_CATEGORY
                || kind == Menu.Kind.SHOP_BUY
                || kind == Menu.Kind.SHOP_AUTOBUY
                || kind == Menu.Kind.SELL
                || kind == Menu.Kind.SELL_PREVIEW
                || kind == Menu.Kind.SELL_PRICES
                || kind == Menu.Kind.AUCTION_HUB
                || kind == Menu.Kind.AUCTION_OWN
                || kind == Menu.Kind.AUCTION_MAIL
                || kind == Menu.Kind.AUCTION_CONFIRM;
    }

    private static UUID categoryId(ShopCatalog.Category category) {
        return UUID.nameUUIDFromBytes(("shop:" + category.name()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Optional<ShopCatalog.Category> categoryOf(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        for (ShopCatalog.Category category : ShopCatalog.categories()) {
            if (categoryId(category).equals(id)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }

    private static Material materialOf(String name) {
        try {
            return Material.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return Material.BARRIER;
        }
    }

    private static String readable(String material) {
        String[] parts = material.toLowerCase(Locale.ROOT).split("_");
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            if (text.length() > 0) {
                text.append(' ');
            }
            if (!part.isEmpty()) {
                text.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return text.toString();
    }

    private static Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static int spaceFor(Player player, Material material) {
        int remaining = 0;
        int max = material.getMaxStackSize();
        ItemStack[] storage = player.getInventory().getStorageContents();
        if (storage == null) {
            return 0;
        }
        for (ItemStack stack : storage) {
            if (stack == null || stack.getType().isAir()) {
                remaining += max;
            } else if (stack.getType() == material && !stack.hasItemMeta()) {
                remaining += Math.max(0, max - stack.getAmount());
            }
        }
        return remaining;
    }

    /**
     * Drops items at the player's feet, in legal stacks.
     *
     * <p>Straight to the world rather than back through {@code addItem}: the caller
     * has already put away everything that fits, so every further call would scan all
     * thirty-six slots to find nothing.
     */
    private static void dropAtFeet(Player player, Material material, int items) {
        if (items <= 0) {
            return;
        }
        org.bukkit.World world = player.getWorld();
        org.bukkit.Location at = player.getLocation();
        for (int portion : StackSplit.portions(items, material.getMaxStackSize())) {
            world.dropItemNaturally(at, new ItemStack(material, portion));
        }
    }

    /**
     * Hands items over, in stacks the game can actually hold.
     *
     * <p>Splitting here rather than at the call sites because the overflow path needs
     * it too: whatever will not fit comes back from {@code addItem} at the size it was
     * offered, and {@code dropItemNaturally} drops that as a single item entity. An
     * oversized drop becomes an oversized stack the moment somebody picks it up.
     */
    private static void give(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        for (int portion : StackSplit.portions(item.getAmount(), item.getMaxStackSize())) {
            ItemStack stack = item.clone();
            stack.setAmount(portion);
            player.getInventory().addItem(stack).values().forEach(overflow ->
                    player.getWorld().dropItemNaturally(player.getLocation(), overflow));
        }
    }

    static String encodeItem(ItemStack item) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)) {
            output.writeObject(item);
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalArgumentException("That item could not be listed.");
        }
    }

    static ItemStack decodeItem(String data) {
        if (data == null || data.isBlank()) {
            return null;
        }
        try (ByteArrayInputStream bytes = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream input = new BukkitObjectInputStream(bytes)) {
            Object value = input.readObject();
            return value instanceof ItemStack item ? item : null;
        } catch (IOException | ClassNotFoundException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isInstantSellable(ItemStack item) {
        if (item == null || item.getType().isAir() || hasPreservedData(item)) {
            return false;
        }
        return ShopCatalog.sellCredit(item.getType().name(), item.getAmount()) > 0L;
    }

    private static boolean hasPreservedData(ItemStack item) {
        if (!item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BlockStateMeta blockState && blockState.hasBlockState()) {
            return true;
        }
        return meta.hasEnchants() || meta.hasDisplayName() || meta.hasLore();
    }

    private static List<String> partial(String token, List<String> options) {
        return StringUtil.copyPartialMatches(token, options, new ArrayList<>());
    }

    private void logSale(Player player, Sold sold) {
        report(player, "shop_sell",
                "Sold " + sold.describe() + " for " + EconomyFormat.dollars(sold.credit()))
                .detail("items", sold.describe())
                .detail("received", sold.credit())
                .record();
    }

    private ServerEvent.Builder report(Player actor, String event, String summary) {
        return ServerEvent.of(
                event, ServerEvent.CATEGORY_ECONOMY, actor.getUniqueId(), actor.getName(),
                plugin::recordServerEvent
        ).summary(summary);
    }

    private static void info(Player player, String message) {
        player.sendMessage(prefix().append(Component.text(message, NamedTextColor.GRAY)));
    }

    private static void error(Player player, String message) {
        player.sendMessage(prefix().append(Component.text(message, NamedTextColor.RED)));
    }

    private static Component prefix() {
        return Component.text("SHOP » ", ORANGE, TextDecoration.BOLD);
    }

    private record Sold(UUID playerId, int count, long credit, Map<String, Integer> items) {
        String describe() {
            if (items.isEmpty()) {
                return "nothing";
            }
            List<String> parts = new ArrayList<>();
            items.forEach((material, amount) -> parts.add(amount + " " + readable(material)));
            return String.join(", ", parts);
        }
    }
}
