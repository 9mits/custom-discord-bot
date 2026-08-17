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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
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
    private static final int[] CATEGORY_SLOTS = {11, 13, 15, 20, 22, 24, 29, 31};
    private static final int WALLET_SLOT = 49;
    private static final int AH_SEARCH_SLOT = 46;
    private static final int AH_OWN_SLOT = 47;
    private static final int AH_MAIL_SLOT = 48;
    private static final int AH_REFRESH_SLOT = 50;
    private static final int CONFIRM_YES = 11;
    private static final int CONFIRM_ITEM = 13;
    private static final int CONFIRM_NO = 15;
    private static final int CONFIRM_SIZE = 27;

    private final MGXAccessBridge plugin;
    private final EconomyStore money;
    private final AuctionStore auctions;
    private final Map<UUID, String> auctionSearch = new ConcurrentHashMap<>();

    EconomyMenuService(MGXAccessBridge plugin, EconomyStore money, AuctionStore auctions) {
        this.plugin = plugin;
        this.money = money;
        this.auctions = auctions;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Shop, sell and the auction house are menus. Use them in Minecraft.");
            return true;
        }
        String name = command.getName().toLowerCase(Locale.ROOT);
        try {
            switch (name) {
                case "shop" -> openShopHub(player);
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
            openSell(player);
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "hand" -> sellHand(player);
            case "all" -> sellInventory(player);
            default -> throw new IllegalArgumentException("Use /sell, /sell hand or /sell all.");
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
        player.openInventory(inventory);
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
            ItemStack item = new ItemStack(material, Math.min(offer.amount(), material.getMaxStackSize()));
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text(readable(offer.material()), ORANGE, TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false));
                meta.lore(List.of(
                        line("Buy " + offer.amount() + " for " + EconomyFormat.dollars(offer.price())),
                        line(""),
                        line("Left click: buy one"),
                        line("Right click: buy four"),
                        line("Shift-click: fill")
                ));
                item.setItemMeta(meta);
            }
            inventory.setItem(index - first, item);
        }
        if (offers.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, "Nothing here"));
        }
        MenuItems.paginate(inventory, page, offers.size(), true);
        player.openInventory(inventory);
    }

    void openSell(Player player) {
        Inventory inventory = create(
                Menu.Kind.SELL, null, 1, BOARD_SIZE,
                "Sell  •  " + EconomyFormat.dollars(money.balance(player.getUniqueId())),
                null
        );
        player.openInventory(inventory);
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
        player.openInventory(inventory);
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
        player.openInventory(inventory);
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
            inventory.setItem(WALLET_SLOT, button(
                    Material.LIME_CONCRETE, "Collect all",
                    waiting.size() + " item(s) waiting."
            ));
        }
        MenuItems.back(inventory);
        player.openInventory(inventory);
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
        player.openInventory(inventory);
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
                case SHOP_CATEGORY -> clickShopCategory(player, menu, slot, event.getClick());
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
                || menu.kind() != Menu.Kind.SELL
                || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        settleSell(player, event.getInventory());
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

    private void clickShopCategory(Player player, Menu menu, int slot, ClickType click) {
        ShopCatalog.Category category = categoryOf(menu.subject()).orElse(ShopCatalog.Category.BUILDING);
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
        buy(player, offers.get(index), ordersFor(click, offers.get(index), player));
        openShopCategory(player, category, menu.page());
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
        if (slot != WALLET_SLOT) {
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

    private void buy(Player player, ShopCatalog.Offer offer, int orders) {
        if (orders <= 0) {
            throw new IllegalArgumentException("You cannot afford that.");
        }
        Material material = materialOf(offer.material());
        int total = Math.multiplyExact(offer.amount(), orders);
        if (!canFit(player, material, total)) {
            throw new IllegalArgumentException("Your inventory is full.");
        }
        long cost = offer.costOf(orders);
        if (!money.tryWithdraw(player.getUniqueId(), cost)) {
            throw new IllegalArgumentException(
                    "You need " + EconomyFormat.dollars(cost) + "."
            );
        }
        give(player, new ItemStack(material, total));
        info(player, "Bought " + total + " " + readable(offer.material())
                + " for " + EconomyFormat.dollars(cost) + ".");
    }

    private int ordersFor(ClickType click, ShopCatalog.Offer offer, Player player) {
        int space = spaceFor(player, materialOf(offer.material()));
        int affordable = offer.maxOrders(money.balance(player.getUniqueId()), space);
        if (click.isShiftClick()) {
            return affordable;
        }
        if (click.isRightClick()) {
            return Math.min(4, affordable);
        }
        return Math.min(1, affordable);
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
    }

    private Sold sellStacks(Player player, List<ItemStack> stacks) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        long credit = 0L;
        int sold = 0;
        for (ItemStack item : stacks) {
            if (item == null || item.getType().isAir() || hasPreservedData(item)) {
                continue;
            }
            Optional<ShopCatalog.Offer> offer = ShopCatalog.offer(item.getType().name());
            if (offer.isEmpty()) {
                continue;
            }
            long value = offer.get().creditFor(item.getAmount());
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
        String encoded = encodeItem(held);
        String name = readable(held.getType().name());
        auctions.list(
                player.getUniqueId(),
                player.getName(),
                price,
                held.getType().name(),
                held.getAmount(),
                name,
                encoded,
                System.currentTimeMillis()
        );
        player.getInventory().setItemInMainHand(null);
        info(player, "Listed " + held.getAmount() + " " + name + " for "
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
                || kind == Menu.Kind.SELL
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

    private static boolean canFit(Player player, Material material, int amount) {
        return spaceFor(player, material) >= amount;
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

    private static void give(Player player, ItemStack item) {
        player.getInventory().addItem(item).values().forEach(overflow ->
                player.getWorld().dropItemNaturally(player.getLocation(), overflow));
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
        return ShopCatalog.offer(item.getType().name())
                .map(offer -> offer.creditFor(item.getAmount()) > 0L)
                .orElse(false);
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
