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
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
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
    private static final int SETTINGS_SLOT = 22;
    private static final int DEPOSIT_SLOT = 26;
    private static final int UNEQUIP_SLOT = 49;

    private enum Screen {
        HUB,
        CATEGORY
    }

    private static final class WardrobeMenu implements InventoryHolder {
        private final Screen screen;
        private final CosmeticCatalog.Category category;
        private final Map<Integer, UUID> tokenSlots = new LinkedHashMap<>();
        private Inventory inventory;

        WardrobeMenu(Screen screen, CosmeticCatalog.Category category) {
            this.screen = screen;
            this.category = category;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record Owned(CosmeticStore.Token token, boolean stored) {
    }

    private final MGXAccessBridge plugin;
    private final CosmeticStore store;
    private final CosmeticItems items;
    private final PlayerSettingsService settings;

    WardrobeService(
            MGXAccessBridge plugin,
            CosmeticStore store,
            CosmeticItems items,
            PlayerSettingsService settings
    ) {
        this.plugin = plugin;
        this.store = store;
        this.items = items;
        this.settings = settings;
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
        if (args[0].equalsIgnoreCase("deposit")) {
            depositHeld(player);
            return true;
        }
        CosmeticCatalog.Category category = parseCategory(args[0]).orElse(null);
        if (category != null) {
            openCategory(player, category);
            return true;
        }
        PlayerMenuService.error(player, "Use /wardrobe, /wardrobe deposit, or /wardrobe settings.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("kill_effects", "auras", "trails", "secret", "deposit", "settings")
                .stream().filter(value -> value.startsWith(prefix)).toList();
    }

    void openHub(Player player) {
        WardrobeMenu holder = new WardrobeMenu(Screen.HUB, null);
        Inventory inventory = Bukkit.createInventory(holder, 27, Component.text("Wardrobe", ORANGE));
        holder.inventory = inventory;
        inventory.setItem(10, categoryButton(
                Material.NETHERITE_SWORD, CosmeticCatalog.Category.KILL_EFFECT, player
        ));
        inventory.setItem(12, categoryButton(
                Material.NETHER_STAR, CosmeticCatalog.Category.AURA, player
        ));
        inventory.setItem(14, categoryButton(
                Material.WIND_CHARGE, CosmeticCatalog.Category.TRAIL, player
        ));
        if (!owned(player, CosmeticCatalog.Category.SECRET).isEmpty()) {
            inventory.setItem(16, categoryButton(
                    Material.BLACK_DYE, CosmeticCatalog.Category.SECRET, player
            ));
        } else {
            inventory.setItem(16, button(
                    Material.BLACK_STAINED_GLASS_PANE,
                    "Unknown",
                    "A black silhouette hides this category.",
                    "Chance: ???",
                    "In existence: " + store.inExistence("event_horizon")
            ));
        }
        inventory.setItem(SETTINGS_SLOT, button(
                Material.COMPARATOR,
                "Cosmetic Settings",
                "Open the same cosmetic controls found in /settings."
        ));
        inventory.setItem(DEPOSIT_SLOT, button(
                Material.ENDER_CHEST,
                "Deposit Held Cosmetic",
                "Hold a cosmetic token, then click here.",
                "Vaulted tokens cannot be dropped or traded."
        ));
        MenuItems.show(plugin, player, inventory);
    }

    private void openCategory(Player player, CosmeticCatalog.Category category) {
        List<Owned> owned = owned(player, category);
        WardrobeMenu holder = new WardrobeMenu(Screen.CATEGORY, category);
        Inventory inventory = Bukkit.createInventory(
                holder, 54, Component.text(category.displayName(), ORANGE)
        );
        holder.inventory = inventory;

        Map<String, List<Owned>> byCosmetic = new LinkedHashMap<>();
        for (Owned token : owned) {
            byCosmetic.computeIfAbsent(token.token().cosmeticId(), ignored -> new ArrayList<>()).add(token);
        }
        List<CosmeticCatalog.Definition> definitions = category == CosmeticCatalog.Category.SECRET
                ? CosmeticCatalog.all().stream().filter(CosmeticCatalog.Definition::secret).toList()
                : CosmeticCatalog.publicEntries().stream()
                        .filter(definition -> definition.category() == category)
                        .toList();
        int slot = 0;
        for (CosmeticCatalog.Definition definition : definitions) {
            List<Owned> copies = byCosmetic.getOrDefault(definition.id(), List.of());
            if (copies.isEmpty()) {
                ItemStack locked = button(
                        Material.GRAY_DYE,
                        definition.secret() ? "???" : definition.displayName(),
                        definition.description(),
                        "Not owned",
                        "Crate chance: " + definition.displayedChance(),
                        "In existence: " + store.inExistence(definition.id())
                );
                inventory.setItem(slot++, locked);
                continue;
            }
            Owned selected = copies.get(0);
            ItemStack icon = items.preview(definition, false);
            ItemMeta meta = icon.getItemMeta();
            List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
            lore.add(Component.empty());
            lore.add(line("In existence: " + store.inExistence(definition.id())));
            lore.add(line("Owned copies: " + copies.size()));
            lore.add(line(selected.stored() ? "Stored in wardrobe" : "Physical token in inventory"));
            lore.add(line("Left-click to equip."));
            lore.add(line(selected.stored()
                    ? "Right-click to withdraw for trading."
                    : "Right-click to deposit for safekeeping."));
            meta.lore(lore);
            icon.setItemMeta(meta);
            inventory.setItem(slot, icon);
            holder.tokenSlots.put(slot, selected.token().serial());
            slot++;
        }
        inventory.setItem(45, button(Material.BARRIER, "Back", "Return to wardrobe categories."));
        UUID equipped = store.equipped(player.getUniqueId(), category.name()).orElse(null);
        String equippedName = equipped == null ? null : store.token(equipped)
                .flatMap(token -> CosmeticCatalog.find(token.cosmeticId()))
                .map(CosmeticCatalog.Definition::displayName)
                .orElse("the current cosmetic");
        inventory.setItem(UNEQUIP_SLOT, equipped == null
                ? button(Material.GRAY_DYE, "Nothing Equipped", "No cosmetic in this category is active.")
                : button(
                        Material.LEVER,
                        "Unequip Current",
                        equippedName + " is active.",
                        "Click to stop using it without moving the token."
                ));
        inventory.setItem(53, button(
                Material.COMPARATOR,
                "Cosmetic Settings",
                "Open your cosmetic visibility controls."
        ));
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
                case 10 -> openCategory(player, CosmeticCatalog.Category.KILL_EFFECT);
                case 12 -> openCategory(player, CosmeticCatalog.Category.AURA);
                case 14 -> openCategory(player, CosmeticCatalog.Category.TRAIL);
                case 16 -> {
                    if (!owned(player, CosmeticCatalog.Category.SECRET).isEmpty()) {
                        openCategory(player, CosmeticCatalog.Category.SECRET);
                    }
                }
                case SETTINGS_SLOT -> settings.openCosmeticSettings(player);
                case DEPOSIT_SLOT -> depositHeld(player);
                default -> { }
            }
            return;
        }
        if (event.getSlot() == 45) {
            openHub(player);
            return;
        }
        if (event.getSlot() == 53) {
            settings.openCosmeticSettings(player);
            return;
        }
        if (event.getSlot() == UNEQUIP_SLOT) {
            unequip(player, menu.category);
            return;
        }
        UUID serial = menu.tokenSlots.get(event.getSlot());
        if (serial == null) {
            return;
        }
        if (event.isRightClick()) {
            transferCustody(player, serial, menu.category);
        } else {
            equip(player, serial, menu.category);
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
        player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                definition.displayName() + " equipped.", NamedTextColor.GREEN
        )));
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
        player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                name + " unequipped.", NamedTextColor.GREEN
        )));
        openCategory(player, category);
    }

    private void transferCustody(Player player, UUID serial, CosmeticCatalog.Category category) {
        Optional<CosmeticStore.Token> found = store.token(serial);
        if (found.isEmpty()) {
            PlayerMenuService.error(player, "That cosmetic token is no longer valid.");
            return;
        }
        CosmeticStore.Token token = found.get();
        CosmeticCatalog.Definition definition = CosmeticCatalog.find(token.cosmeticId()).orElse(null);
        if (definition == null) {
            PlayerMenuService.error(player, "That cosmetic no longer exists.");
            return;
        }
        if (token.stored()) {
            if (!player.getUniqueId().equals(token.storedOwner())) {
                PlayerMenuService.error(player, "That cosmetic is not stored in your wardrobe.");
                return;
            }
            int emptySlot = player.getInventory().firstEmpty();
            if (emptySlot < 0) {
                PlayerMenuService.error(player, "Make one empty inventory slot before withdrawing.");
                return;
            }
            CosmeticStore.Token physical;
            try {
                physical = store.withdraw(player.getUniqueId(), serial).orElse(null);
            } catch (UncheckedIOException exception) {
                plugin.getLogger().warning("Could not withdraw a cosmetic: " + exception.getMessage());
                PlayerMenuService.error(player, "That cosmetic could not be withdrawn. Please try again.");
                return;
            }
            if (physical == null) {
                PlayerMenuService.error(player, "That cosmetic could not be withdrawn.");
                return;
            }
            player.getInventory().setItem(emptySlot, items.token(definition, physical));
            player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                    definition.displayName() + " is now a tradable item.", NamedTextColor.GREEN
            )));
        } else {
            if (!items.removeOne(player, serial)) {
                PlayerMenuService.error(player, "Keep the cosmetic token in your inventory to deposit it.");
                return;
            }
            try {
                if (!store.deposit(player.getUniqueId(), serial, token.cosmeticId(), token.generation())) {
                    refund(player, items.token(definition, token));
                    PlayerMenuService.error(player, "That cosmetic could not be deposited.");
                    return;
                }
            } catch (UncheckedIOException exception) {
                refund(player, items.token(definition, token));
                plugin.getLogger().warning("Could not deposit a cosmetic: " + exception.getMessage());
                PlayerMenuService.error(player, "That cosmetic could not be deposited.");
                return;
            }
            player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                    definition.displayName() + " is protected in your wardrobe.", NamedTextColor.GREEN
            )));
        }
        openCategory(player, category);
    }

    private void depositHeld(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        CosmeticItems.TokenInfo info = items.read(held).orElse(null);
        if (info == null) {
            PlayerMenuService.error(player, "Hold a cosmetic token in your main hand first.");
            return;
        }
        CosmeticStore.Token token = store.token(info.serial()).orElse(null);
        if (token == null || token.stored() || !token.cosmeticId().equals(info.cosmeticId())) {
            PlayerMenuService.error(player, "That cosmetic token is invalid or already stored.");
            return;
        }
        if (!items.removeOne(player, info.serial())) {
            PlayerMenuService.error(player, "That cosmetic token could not be removed.");
            return;
        }
        try {
            if (!store.deposit(player.getUniqueId(), info.serial(), info.cosmeticId(), info.generation())) {
                CosmeticCatalog.find(info.cosmeticId()).ifPresent(
                        definition -> refund(player, items.token(definition, token))
                );
                PlayerMenuService.error(player, "That cosmetic token could not be deposited.");
                return;
            }
        } catch (UncheckedIOException exception) {
            CosmeticCatalog.find(info.cosmeticId()).ifPresent(
                    definition -> refund(player, items.token(definition, token))
            );
            plugin.getLogger().warning("Could not deposit a held cosmetic: "
                    + exception.getMessage());
            PlayerMenuService.error(player, "That cosmetic token could not be deposited.");
            return;
        }
        player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                "Cosmetic stored in your wardrobe.", NamedTextColor.GREEN
        )));
        openHub(player);
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

    private ItemStack categoryButton(
            Material material, CosmeticCatalog.Category category, Player player
    ) {
        int count = owned(player, category).size();
        return button(
                material,
                category.displayName(),
                count + (count == 1 ? " cosmetic owned" : " cosmetics owned"),
                "Click to view and equip."
        );
    }

    private static Optional<CosmeticCatalog.Category> parseCategory(String raw) {
        String cleaned = raw.toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (cleaned) {
            case "kill", "kills", "kill_effect", "kill_effects" ->
                    Optional.of(CosmeticCatalog.Category.KILL_EFFECT);
            case "aura", "auras" -> Optional.of(CosmeticCatalog.Category.AURA);
            case "trail", "trails" -> Optional.of(CosmeticCatalog.Category.TRAIL);
            case "secret" -> Optional.of(CosmeticCatalog.Category.SECRET);
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
