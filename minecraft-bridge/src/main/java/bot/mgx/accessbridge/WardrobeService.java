package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.io.UncheckedIOException;

/** The virtual wardrobe and the deposit/withdraw bridge to tradable bearer items. */
final class WardrobeService implements CommandExecutor, TabCompleter, Listener {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private static final int HUB_SIZE = 27;
    // Three categories centred on the middle row: 11/13/15 straddles slot 13, which
    // is the column the settings button sits under. The old 10/12/14 spacing is a
    // leftover from the fourth Secret tile and now sits a slot left of centre.
    private static final int KILL_EFFECT_SLOT = 11;
    private static final int AURA_SLOT = 13;
    private static final int TRAIL_SLOT = 15;
    private static final int SETTINGS_SLOT = 22;

    private enum Screen {
        HUB,
        CATEGORY
    }

    private static final class WardrobeMenu implements InventoryHolder {
        private final Screen screen;
        private final CosmeticCatalog.Category category;
        private final boolean saleMode;
        private final Map<Integer, UUID> tokenSlots = new LinkedHashMap<>();
        private final Map<Integer, PodiumReward> podiumSlots = new LinkedHashMap<>();
        private Inventory inventory;

        WardrobeMenu(Screen screen, CosmeticCatalog.Category category, boolean saleMode) {
            this.screen = screen;
            this.category = category;
            this.saleMode = saleMode;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record Owned(CosmeticStore.Token token, boolean stored) {
    }

    private record PodiumReward(
            LeaderboardStandings.Standing standing,
            CosmeticCatalog.Definition definition
    ) {
    }

    private final MGXAccessBridge plugin;
    private final CosmeticStore store;
    private final CosmeticItems items;
    private final PlayerSettingsService settings;
    private final LeaderboardService leaderboard;
    private EconomyMenuService economyMenus;
    /** Players who shift-clicked a cosmetic and owe a price in chat. */
    private final Map<UUID, UUID> awaitingPrice = new LinkedHashMap<>();

    WardrobeService(
            MGXAccessBridge plugin,
            CosmeticStore store,
            CosmeticItems items,
            PlayerSettingsService settings,
            LeaderboardService leaderboard
    ) {
        this.plugin = plugin;
        this.store = store;
        this.items = items;
        this.settings = settings;
        this.leaderboard = leaderboard;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        args = CommandArgs.withoutEchoedSender(sender.getName(), args);
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is available to players only.");
            return true;
        }
        if (args.length == 0) {
            openHub(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("settings")) {
            settings.openCosmeticSettings(player);
            return true;
        }
        CosmeticCatalog.Category category = parseCategory(args[0]).orElse(null);
        if (category != null) {
            openCategory(player, category);
            return true;
        }
        PlayerMenuService.error(player, "Use /wardrobe or /wardrobe settings.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("kill_effects", "auras", "trails", "settings").stream()
                .filter(value -> value.startsWith(prefix))
                .toList();
    }

    void openHub(Player player) {
        openHub(player, false);
    }

    void openSaleHub(Player player) {
        openHub(player, true);
    }

    private void openHub(Player player, boolean saleMode) {
        vaultCarried(player);
        WardrobeMenu holder = new WardrobeMenu(Screen.HUB, null, saleMode);
        Inventory inventory = Bukkit.createInventory(
                holder, HUB_SIZE, Component.text(saleMode ? "List a cosmetic" : "Wardrobe", ORANGE)
        );
        holder.inventory = inventory;
        inventory.setItem(KILL_EFFECT_SLOT, categoryButton(
                Material.NETHERITE_SWORD, CosmeticCatalog.Category.KILL_EFFECT, player, saleMode
        ));
        inventory.setItem(AURA_SLOT, categoryButton(
                Material.NETHER_STAR, CosmeticCatalog.Category.AURA, player, saleMode
        ));
        inventory.setItem(TRAIL_SLOT, categoryButton(
                Material.WIND_CHARGE, CosmeticCatalog.Category.TRAIL, player, saleMode
        ));
        inventory.setItem(SETTINGS_SLOT, saleMode
                ? button(Material.BARRIER, "Back to your listings")
                : button(Material.COMPARATOR, "Cosmetic Settings"));
        MenuItems.show(plugin, player, inventory);
    }

    private void openCategory(Player player, CosmeticCatalog.Category category) {
        openCategory(player, category, false);
    }

    private void openCategory(
            Player player, CosmeticCatalog.Category category, boolean saleMode
    ) {
        vaultCarried(player);
        List<Owned> owned = owned(player, category);
        WardrobeMenu holder = new WardrobeMenu(Screen.CATEGORY, category, saleMode);
        Inventory inventory = Bukkit.createInventory(
                holder, MenuItems.BOARD_SIZE,
                Component.text(saleMode ? "List: " + category.displayName() : category.displayName(), ORANGE)
        );
        holder.inventory = inventory;

        // Token cosmetics only appear when owned. The current virtual podium reward
        // appears separately because it can never have a tradable serial.
        Map<String, List<Owned>> byCosmetic = new LinkedHashMap<>();
        for (Owned entry : owned) {
            byCosmetic.computeIfAbsent(entry.token().cosmeticId(), ignored -> new ArrayList<>())
                    .add(entry);
        }
        int slot = 0;
        Optional<PodiumReward> podium = podiumReward(player, category, saleMode);
        if (podium.isPresent()) {
            inventory.setItem(slot, podiumIcon(podium.get()));
            holder.podiumSlots.put(slot, podium.get());
            slot++;
        }
        for (CosmeticCatalog.Definition definition : CosmeticCatalog.visualEntries()) {
            if (definition.category() != category) {
                continue;
            }
            if (saleMode && definition.leaderboardOnly()) {
                continue;
            }
            if (slot >= MenuItems.PER_PAGE) {
                break;
            }
            List<Owned> copies = byCosmetic.getOrDefault(definition.id(), List.of());
            if (copies.isEmpty()) {
                continue;
            }
            Owned selected = copies.get(0);
            boolean selectedForUse = store.equipped(player.getUniqueId(), category.name())
                    .map(current -> current.equals(selected.token().serial()))
                    .orElse(false);
            ItemStack icon = items.preview(definition, false);
            ItemMeta meta = icon.getItemMeta();
            List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
            lore.add(Component.empty());
            if (selected.token().serialNumber() > 0) {
                lore.add(line("Serial #" + selected.token().serialNumber()));
                lore.add(line("In existence: " + store.inExistence(definition.id())));
            } else {
                lore.add(line("Admin preview — session only"));
                lore.add(line("Cannot be traded or listed"));
            }
            if (copies.size() > 1) {
                lore.add(line("You own " + copies.size() + " of these."));
            }
            if (selectedForUse) {
                lore.add(line(podium.isPresent()
                        ? "Selected — resumes when the podium reward ends"
                        : "Equipped"));
            }
            if (saleMode && selected.token().serialNumber() > 0) {
                lore.add(line("Click to list on the Auction House."));
            }
            meta.lore(lore);
            icon.setItemMeta(meta);
            inventory.setItem(slot, icon);
            holder.tokenSlots.put(slot, selected.token().serial());
            slot++;
        }
        if (owned.isEmpty() && podium.isEmpty()) {
            inventory.setItem(22, button(Material.GRAY_DYE, "No cosmetics yet"));
        }
        MenuItems.back(inventory);
        MenuItems.show(plugin, player, inventory);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof WardrobeMenu menu)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getInventory()) {
            return;
        }
        if (menu.screen == Screen.HUB) {
            switch (event.getSlot()) {
                case KILL_EFFECT_SLOT -> openCategory(
                        player, CosmeticCatalog.Category.KILL_EFFECT, menu.saleMode
                );
                case AURA_SLOT -> openCategory(player, CosmeticCatalog.Category.AURA, menu.saleMode);
                case TRAIL_SLOT -> openCategory(player, CosmeticCatalog.Category.TRAIL, menu.saleMode);
                case SETTINGS_SLOT -> {
                    if (menu.saleMode && economyMenus != null) {
                        economyMenus.openOwn(player, 1);
                    } else {
                        settings.openCosmeticSettings(player);
                    }
                }
                default -> { }
            }
            return;
        }
        if (event.getSlot() == MenuItems.backSlot(event.getInventory().getSize())) {
            openHub(player, menu.saleMode);
            return;
        }
        PodiumReward podium = menu.podiumSlots.get(event.getSlot());
        if (podium != null) {
            player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                    podium.definition().displayName()
                            + " is equipped automatically while you hold #"
                            + podium.standing().placement() + " on the "
                            + boardName(podium.standing().type()) + " leaderboard.",
                    NamedTextColor.GOLD
            )));
            return;
        }
        UUID serial = menu.tokenSlots.get(event.getSlot());
        if (serial == null) {
            return;
        }
        CosmeticCatalog.Category category = menu.category;
        if (category == null) {
            PlayerMenuService.error(player, "That cosmetic is no longer yours.");
            openHub(player);
            return;
        }
        if (menu.saleMode || event.isShiftClick()) {
            promptSale(player, serial, category);
            return;
        }
        boolean active = store.equipped(player.getUniqueId(), category.name())
                .map(current -> current.equals(serial))
                .orElse(false);
        if (active) {
            unequip(player, category);
        } else {
            equip(player, serial, category);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof WardrobeMenu) {
            event.setCancelled(true);
        }
    }

    private void equip(Player player, UUID serial, CosmeticCatalog.Category category) {
        Optional<CosmeticStore.Token> token = store.token(serial);
        if (token.isEmpty() || !hasAccess(player, token.get())) {
            PlayerMenuService.error(player, "That cosmetic token is no longer yours.");
            openCategory(player, category);
            return;
        }
        CosmeticCatalog.Definition definition = CosmeticCatalog.find(token.get().cosmeticId()).orElse(null);
        if (definition == null || definition.category() != category) {
            PlayerMenuService.error(player, "That cosmetic token is invalid.");
            return;
        }
        try {
            store.equip(player.getUniqueId(), category.name(), serial);
        } catch (UncheckedIOException exception) {
            plugin.getLogger().warning("Could not save an equipped cosmetic: "
                    + exception.getMessage());
            PlayerMenuService.error(player, "That cosmetic could not be equipped. Please try again.");
            return;
        }
        boolean podiumActive = podiumReward(player, category, false).isPresent();
        player.sendMessage(PlayerMenuService.prefix().append(Component.text(podiumActive
                ? definition.displayName()
                        + " selected. It will resume when your podium reward ends."
                : definition.displayName() + " equipped.", NamedTextColor.GREEN)));
        openCategory(player, category);
    }

    private void unequip(Player player, CosmeticCatalog.Category category) {
        UUID serial = store.equipped(player.getUniqueId(), category.name()).orElse(null);
        if (serial == null) {
            player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                    "Nothing in this category is equipped.", NamedTextColor.GRAY
            )));
            return;
        }
        String name = store.token(serial)
                .flatMap(token -> CosmeticCatalog.find(token.cosmeticId()))
                .map(CosmeticCatalog.Definition::displayName)
                .orElse("Cosmetic");
        try {
            if (!store.clearEquipped(player.getUniqueId(), category.name(), serial)) {
                PlayerMenuService.error(player, "That cosmetic selection changed. Please try again.");
                openCategory(player, category);
                return;
            }
        } catch (UncheckedIOException exception) {
            plugin.getLogger().warning("Could not save an unequipped cosmetic: "
                    + exception.getMessage());
            PlayerMenuService.error(player, "That cosmetic could not be unequipped. Please try again.");
            return;
        }
        boolean podiumActive = podiumReward(player, category, false).isPresent();
        player.sendMessage(PlayerMenuService.prefix().append(Component.text(podiumActive
                ? name + " selection cleared. Your podium reward remains equipped."
                : name + " unequipped.", NamedTextColor.GREEN)));
        openCategory(player, category);
    }



    void useAuctionHouse(EconomyMenuService economyMenus) {
        this.economyMenus = economyMenus;
    }

    private ItemStack categoryButton(
            Material material,
            CosmeticCatalog.Category category,
            Player player,
            boolean saleMode
    ) {
        Optional<PodiumReward> podium = podiumReward(player, category, saleMode);
        int count = owned(player, category).size() + (podium.isPresent() ? 1 : 0);
        List<String> lore = new ArrayList<>();
        lore.add(count + (count == 1 ? " cosmetic available" : " cosmetics available"));
        if (podium.isPresent()) {
            lore.add("Equipped: " + podium.get().definition().displayName());
            lore.add("#" + podium.get().standing().placement() + " "
                    + boardName(podium.get().standing().type()) + " leaderboard reward");
        } else {
            store.equipped(player.getUniqueId(), category.name())
                    .flatMap(store::token)
                    .flatMap(token -> CosmeticCatalog.find(token.cosmeticId()))
                    .ifPresent(definition -> lore.add("Equipped: " + definition.displayName()));
        }
        return MenuItems.button(material, category.displayName(), lore);
    }

    private Optional<PodiumReward> podiumReward(
            Player player, CosmeticCatalog.Category category, boolean saleMode
    ) {
        if (saleMode) {
            return Optional.empty();
        }
        return leaderboard.standing(player.getUniqueId())
                .flatMap(standing -> podiumRewardForMenu(standing, category, false)
                        .map(definition -> new PodiumReward(standing, definition)));
    }

    static Optional<CosmeticCatalog.Definition> podiumRewardForMenu(
            LeaderboardStandings.Standing standing,
            CosmeticCatalog.Category category,
            boolean saleMode
    ) {
        if (saleMode || standing == null || standing.placement() > 3) {
            return Optional.empty();
        }
        return CosmeticCatalog.leaderboardReward(standing.placement(), category);
    }

    private ItemStack podiumIcon(PodiumReward reward) {
        ItemStack icon = items.preview(reward.definition(), false);
        ItemMeta meta = icon.getItemMeta();
        List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
        lore.add(Component.empty());
        lore.add(line("Automatically equipped"));
        lore.add(line("#" + reward.standing().placement() + " on the "
                + boardName(reward.standing().type()) + " leaderboard"));
        lore.add(line("Available only while you hold this placement"));
        lore.add(line("Cannot be traded or listed"));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private static String boardName(LeaderboardType type) {
        return switch (type) {
            case WEALTH -> "Money $";
            case KILLS -> "Kills " + type.icon();
            case PLAYTIME -> "Playtime";
            case BLOCKS_MINED -> "Blocks mined";
            case BLOCKS_WALKED -> "Blocks walked";
        };
    }

    /**
     * Takes any cosmetic token sitting in the inventory into the wardrobe. Tokens still
     * exist as items in transit — auction mail and drops from older versions produce
     * them — but a player never has to deal with one.
     */
    void vaultCarried(Player player) {
        for (CosmeticItems.TokenInfo info : items.carried(player)) {
            CosmeticStore.Token token = store.token(info.serial()).orElse(null);
            if (token == null || token.stored() || !token.cosmeticId().equals(info.cosmeticId())) {
                continue;
            }
            try {
                if (store.deposit(player.getUniqueId(), info.serial(), info.cosmeticId(), info.generation())
                        && items.removeOne(player, info.serial())) {
                    player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                            "Stored in your wardrobe.", NamedTextColor.GREEN
                    )));
                }
            } catch (UncheckedIOException exception) {
                plugin.getLogger().warning(
                        "Could not vault a cosmetic token: " + exception.getMessage()
                );
            }
        }
    }

    private void promptSale(Player player, UUID serial, CosmeticCatalog.Category category) {
        CosmeticStore.Token token = store.token(serial).orElse(null);
        if (economyMenus == null
                || token == null
                || token.serialNumber() <= 0
                || !store.isStoredBy(player.getUniqueId(), serial)) {
            PlayerMenuService.error(player, "That cosmetic cannot be listed right now.");
            openCategory(player, category);
            return;
        }
        awaitingPrice.put(player.getUniqueId(), serial);
        player.closeInventory();
        player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                "Type a price in chat to list it, or type cancel.", NamedTextColor.GRAY
        )));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID serial = awaitingPrice.get(player.getUniqueId());
        if (serial == null) {
            return;
        }
        event.setCancelled(true);
        awaitingPrice.remove(player.getUniqueId());
        String typed = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        // Chat arrives off the main thread; every inventory and store write below has to
        // run back on it.
        plugin.getServer().getScheduler().runTask(plugin, () -> completeSale(player, serial, typed));
    }

    private void completeSale(Player player, UUID serial, String typed) {
        if (!player.isOnline()) {
            return;
        }
        if (typed.equalsIgnoreCase("cancel")) {
            player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                    "Listing cancelled.", NamedTextColor.GRAY
            )));
            return;
        }
        long price;
        try {
            price = EconomyFormat.parseAmount(typed);
        } catch (IllegalArgumentException exception) {
            PlayerMenuService.error(player, "That is not a price. Nothing was listed.");
            return;
        }
        CosmeticStore.Token token = store.token(serial).orElse(null);
        CosmeticCatalog.Definition definition = token == null
                ? null
                : CosmeticCatalog.find(token.cosmeticId()).orElse(null);
        if (definition == null || !store.isStoredBy(player.getUniqueId(), serial)) {
            PlayerMenuService.error(player, "That cosmetic is no longer yours.");
            return;
        }
        CosmeticStore.Token physical = store.withdraw(player.getUniqueId(), serial).orElse(null);
        if (physical == null) {
            PlayerMenuService.error(player, "That cosmetic could not be listed. Please try again.");
            return;
        }
        try {
            economyMenus.listCosmetic(player, items.token(definition, physical), price);
            player.sendMessage(PlayerMenuService.prefix()
                    .append(Component.text("Listed ", NamedTextColor.WHITE))
                    .append(Component.text(definition.displayName(), NamedTextColor.GOLD))
                    .append(Component.text(" for " + EconomyFormat.dollars(price) + ".",
                            NamedTextColor.WHITE)));
            economyMenus.openOwn(player, 1);
        } catch (IllegalArgumentException | UncheckedIOException exception) {
            // The token is out of the wardrobe at this point, so it has to go back or
            // the player loses a unique cosmetic to a rejected price.
            store.deposit(player.getUniqueId(), serial, definition.id(), physical.generation());
            PlayerMenuService.error(player, exception.getMessage() == null
                    ? "That cosmetic could not be listed." : exception.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) {
                vaultCarried(event.getPlayer());
            }
        }, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || items.read(event.getItem().getItemStack()).isEmpty()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                vaultCarried(player);
            }
        });
    }

    /** A kill hands the loser's equipped cosmetics to whoever killed them. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        List<CosmeticStore.Token> moved;
        try {
            moved = store.transferEquipped(victim.getUniqueId(), killer.getUniqueId());
        } catch (UncheckedIOException exception) {
            plugin.getLogger().warning(
                    "Could not transfer cosmetics on death: " + exception.getMessage()
            );
            return;
        }
        if (moved.isEmpty()) {
            return;
        }
        String names = moved.stream()
                .map(token -> CosmeticCatalog.find(token.cosmeticId())
                        .map(CosmeticCatalog.Definition::displayName)
                        .orElse(token.cosmeticId()))
                .collect(java.util.stream.Collectors.joining(", "));
        victim.sendMessage(PlayerMenuService.prefix().append(Component.text(
                "You lost " + names + " to " + killer.getName() + ".", NamedTextColor.RED
        )));
        killer.sendMessage(PlayerMenuService.prefix().append(Component.text(
                "You took " + names + " from " + victim.getName() + ".", NamedTextColor.GREEN
        )));
    }

    private List<Owned> owned(Player player, CosmeticCatalog.Category category) {
        List<Owned> result = new ArrayList<>();
        for (CosmeticStore.Token token : store.stored(player.getUniqueId())) {
            CosmeticCatalog.find(token.cosmeticId())
                    .filter(definition -> definition.category() == category)
                    .ifPresent(ignored -> result.add(new Owned(token, true)));
        }
        for (CosmeticItems.TokenInfo info : items.carried(player)) {
            CosmeticStore.Token token = store.token(info.serial()).orElse(null);
            if (token == null
                    || token.stored()
                    || token.generation() != info.generation()
                    || !token.cosmeticId().equals(info.cosmeticId())) {
                continue;
            }
            CosmeticCatalog.find(token.cosmeticId())
                    .filter(definition -> definition.category() == category)
                    .ifPresent(ignored -> result.add(new Owned(token, false)));
        }
        result.sort(Comparator.comparing(owned -> owned.token().cosmeticId()));
        return List.copyOf(result);
    }

    boolean hasAccess(Player player, CosmeticStore.Token token) {
        return store.isStoredBy(player.getUniqueId(), token.serial())
                || (!token.stored() && items.carries(player, token.serial()));
    }


    private static Optional<CosmeticCatalog.Category> parseCategory(String raw) {
        String cleaned = raw.toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (cleaned) {
            case "kill", "kills", "kill_effect", "kill_effects" ->
                    Optional.of(CosmeticCatalog.Category.KILL_EFFECT);
            case "aura", "auras" -> Optional.of(CosmeticCatalog.Category.AURA);
            case "trail", "trails" -> Optional.of(CosmeticCatalog.Category.TRAIL);
            default -> Optional.empty();
        };
    }

    private static ItemStack button(Material material, String name, String... lore) {
        return MenuItems.button(material, name, List.of(lore));
    }

    private static Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static void refund(Player player, ItemStack token) {
        player.getInventory().addItem(token).values().forEach(item ->
                player.getWorld().dropItemNaturally(player.getLocation(), item)
        );
    }
}
