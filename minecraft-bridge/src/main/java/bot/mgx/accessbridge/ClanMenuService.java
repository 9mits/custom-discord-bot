package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The clan screens. Container menus render through Geyser, so Bedrock players get
 * these too.
 *
 * <p>Everything decided here is read from {@link ClanLevel} and {@link ClanStore};
 * this class only draws and dispatches, because Bukkit-bound code cannot be unit
 * tested in this project.
 */
final class ClanMenuService implements Listener {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private static final int HUB_SIZE = 27;
    private static final int BOARD_SIZE = 54;
    /** Slot layout for the hub, chosen so the row reads left to right. */
    private static final int HUB_DONATE = 11;
    private static final int HUB_BALANCE = 12;
    private static final int HUB_UPGRADE = 14;
    private static final int HUB_DONORS = 15;
    /** Upgrade board: the level track on the left, the roster track on the right. */
    private static final int UPGRADE_LEVEL = 20;
    private static final int UPGRADE_MEMBERS = 24;
    private static final int BACK_SLOT = 49;

    private final MGXAccessBridge plugin;
    private final ClanStore store;

    ClanMenuService(MGXAccessBridge plugin, ClanStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    void openHub(Player player) {
        ClanStore.ClanView clan = requireClan(player);
        Inventory inventory = create(ClanMenu.Kind.HUB, clan, HUB_SIZE, "Clan  " + clan.name());
        inventory.setItem(HUB_DONATE, button(Material.CHEST, "Donate",
                "Give items to the clan.",
                "Donations cannot be taken back."));
        inventory.setItem(HUB_BALANCE, button(Material.GOLD_INGOT, "Balance",
                "Worth " + String.format("%,d", clan.balance()) + ".",
                "See what the clan is holding."));
        inventory.setItem(HUB_UPGRADE, button(Material.NETHER_STAR, "Upgrades",
                describeLevel(clan.level()),
                clan.members().size() + "/" + clan.memberSlots() + " members."));
        inventory.setItem(HUB_DONORS, button(Material.PLAYER_HEAD, "Donors",
                "Who has given what.",
                "Largest first."));
        player.openInventory(inventory);
    }

    void openDonate(Player player) {
        ClanStore.ClanView clan = requireClan(player);
        // Deliberately empty: whatever is inside when the window closes is banked.
        Inventory inventory = create(
                ClanMenu.Kind.DONATE, clan, BOARD_SIZE, "Donate to " + clan.name()
        );
        player.openInventory(inventory);
        player.sendMessage(prefix().append(Component.text(
                "Drop items in, then close the window. Anything worthless comes back.",
                NamedTextColor.WHITE
        )));
    }

    void openBalance(Player player) {
        ClanStore.ClanView clan = requireClan(player);
        Inventory inventory = create(
                ClanMenu.Kind.BALANCE, clan, BOARD_SIZE,
                "Balance  " + String.format("%,d", clan.balance())
        );
        int slot = 0;
        for (Map.Entry<String, Integer> entry : clan.vault().entrySet()) {
            if (slot >= BACK_SLOT) {
                break;
            }
            Material material = Material.matchMaterial(entry.getKey());
            if (material == null) {
                continue;
            }
            long worth = (long) WealthValues.valueOfIncludingVariants(entry.getKey()) * entry.getValue();
            inventory.setItem(slot++, button(
                    material,
                    entry.getValue() + "x " + WealthValues.readable(entry.getKey()),
                    "Worth " + String.format("%,d", worth) + "."
            ));
        }
        if (slot == 0) {
            inventory.setItem(22, button(Material.BARRIER, "Nothing donated yet",
                    "Use Donate to start the clan off."));
        }
        inventory.setItem(BACK_SLOT, button(Material.ARROW, "Back"));
        player.openInventory(inventory);
    }

    void openDonors(Player player) {
        ClanStore.ClanView clan = requireClan(player);
        Inventory inventory = create(ClanMenu.Kind.DONORS, clan, BOARD_SIZE, "Donors  " + clan.name());
        int slot = 0;
        for (Map.Entry<UUID, Long> donor : clan.rankedDonors()) {
            if (slot >= BACK_SLOT) {
                break;
            }
            String name = clan.members().getOrDefault(donor.getKey(), "Former member");
            inventory.setItem(slot, button(Material.PLAYER_HEAD,
                    "#" + (slot + 1) + "  " + name,
                    "Donated " + String.format("%,d", donor.getValue()) + " in all."));
            slot++;
        }
        if (slot == 0) {
            inventory.setItem(22, button(Material.BARRIER, "No donations yet",
                    "Be the first to give something."));
        }
        inventory.setItem(BACK_SLOT, button(Material.ARROW, "Back"));
        player.openInventory(inventory);
    }

    void openUpgrade(Player player) {
        ClanStore.ClanView clan = requireClan(player);
        Inventory inventory = create(ClanMenu.Kind.UPGRADE, clan, BOARD_SIZE, "Upgrades  " + clan.name());
        inventory.setItem(UPGRADE_LEVEL, levelButton(clan));
        inventory.setItem(UPGRADE_MEMBERS, memberButton(clan));
        inventory.setItem(BACK_SLOT, button(Material.ARROW, "Back"));
        player.openInventory(inventory);
    }

    private ItemStack levelButton(ClanStore.ClanView clan) {
        Optional<Integer> next = clan.nextLevel();
        List<String> lore = new ArrayList<>();
        lore.add(describeLevel(clan.level()));
        if (next.isEmpty()) {
            lore.add("Nothing left to buy.");
            return button(Material.NETHER_STAR, "Clan level", lore);
        }
        lore.add("");
        lore.add("Next: level " + next.get());
        addCostLines(lore, clan, ClanLevel.costOf(next.get()));
        lore.add("");
        for (String line : perkLines(ClanLevel.perksFor(next.get()))) {
            lore.add(line);
        }
        lore.add("");
        lore.add(ClanLevel.shortfall(clan.vault(), next.get()).isEmpty()
                ? "Click to buy."
                : "The vault is short.");
        return button(Material.NETHER_STAR, "Clan level", lore);
    }

    private ItemStack memberButton(ClanStore.ClanView clan) {
        Optional<ClanLevel.MemberTier> next = clan.nextMemberTier();
        List<String> lore = new ArrayList<>();
        lore.add(clan.members().size() + "/" + clan.memberSlots() + " members.");
        if (next.isEmpty()) {
            lore.add("Every roster slot is bought.");
            return button(Material.PLAYER_HEAD, "Roster size", lore);
        }
        lore.add("");
        lore.add("Next: " + next.get().slots() + " members");
        addCostLines(lore, clan, List.of(next.get().cost()));
        lore.add("");
        lore.add(ClanLevel.shortfall(clan.vault(), List.of(next.get().cost())).isEmpty()
                ? "Click to buy."
                : "The vault is short.");
        return button(Material.PLAYER_HEAD, "Roster size", lore);
    }

    private static void addCostLines(
            List<String> lore, ClanStore.ClanView clan, List<ClanLevel.Cost> costs
    ) {
        for (ClanLevel.Cost cost : costs) {
            int held = clan.vault().getOrDefault(cost.material(), 0);
            lore.add("  " + Math.min(held, cost.amount()) + "/" + cost.amount()
                    + " " + WealthValues.readable(cost.material()));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ClanMenu menu)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (menu.kind() == ClanMenu.Kind.DONATE) {
            return; // a real container: the player is meant to move items into it
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getInventory()) {
            return;
        }
        try {
            dispatch(player, menu, event.getSlot());
        } catch (ClanStore.ClanException exception) {
            error(player, exception.getMessage());
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save clan data: " + exception.getMessage());
            error(player, "Clan data could not be saved. Contact an administrator before retrying.");
        }
    }

    private void dispatch(Player player, ClanMenu menu, int slot) throws IOException {
        switch (menu.kind()) {
            case HUB -> {
                switch (slot) {
                    case HUB_DONATE -> openDonate(player);
                    case HUB_BALANCE -> openBalance(player);
                    case HUB_UPGRADE -> openUpgrade(player);
                    case HUB_DONORS -> openDonors(player);
                    default -> { }
                }
            }
            case UPGRADE -> {
                switch (slot) {
                    case UPGRADE_LEVEL -> buyLevel(player);
                    case UPGRADE_MEMBERS -> buyMembers(player);
                    case BACK_SLOT -> openHub(player);
                    default -> { }
                }
            }
            case BALANCE, DONORS -> {
                if (slot == BACK_SLOT) {
                    openHub(player);
                }
            }
            default -> { }
        }
    }

    private void buyLevel(Player player) throws IOException {
        ClanStore.ClanView upgraded = store.upgrade(player.getUniqueId());
        plugin.refreshClans();
        announce(upgraded, Component.text(
                "The clan reached level " + upgraded.level() + "!", ORANGE
        ));
        for (String line : perkLines(upgraded.perks())) {
            announce(upgraded, Component.text("  " + line, NamedTextColor.WHITE));
        }
        openUpgrade(player);
    }

    private void buyMembers(Player player) throws IOException {
        ClanStore.ClanView upgraded = store.upgradeMembers(player.getUniqueId());
        plugin.refreshClans();
        announce(upgraded, Component.text(
                "The clan can now hold " + upgraded.memberSlots() + " members.", ORANGE
        ));
        openUpgrade(player);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ClanMenu menu
                && menu.kind() != ClanMenu.Kind.DONATE) {
            event.setCancelled(true);
        }
    }

    /**
     * Banks whatever was left in a donation window.
     *
     * <p>Items live only in this inventory object while the window is open, so this
     * must run for every close — including the forced closes on plugin disable.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ClanMenu menu)
                || menu.kind() != ClanMenu.Kind.DONATE
                || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory inventory = event.getInventory();
        LinkedHashMap<String, Integer> offered = new LinkedHashMap<>();
        List<ItemStack> returned = new ArrayList<>();
        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (ClanLevel.isDonatable(item.getType().name())) {
                offered.merge(item.getType().name(), item.getAmount(), Integer::sum);
            } else {
                returned.add(item);
            }
        }
        inventory.clear();
        for (ItemStack item : returned) {
            give(player, item);
        }
        if (offered.isEmpty()) {
            if (!returned.isEmpty()) {
                error(player, "None of that is worth anything to the clan, so it came back.");
            }
            return;
        }
        try {
            long value = store.donate(player.getUniqueId(), offered);
            ClanStore.ClanView clan = store.clanOf(player.getUniqueId()).orElseThrow();
            announce(clan, Component.text(
                    player.getName() + " donated " + String.format("%,d", value)
                            + " to the clan.", ORANGE
            ));
        } catch (ClanStore.ClanException exception) {
            // The clan went away mid-window; hand everything straight back.
            offered.forEach((material, amount) -> {
                Material resolved = Material.matchMaterial(material);
                if (resolved != null) {
                    give(player, new ItemStack(resolved, amount));
                }
            });
            error(player, exception.getMessage());
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not bank a clan donation: " + exception.getMessage());
            offered.forEach((material, amount) -> {
                Material resolved = Material.matchMaterial(material);
                if (resolved != null) {
                    give(player, new ItemStack(resolved, amount));
                }
            });
            error(player, "That could not be saved, so nothing was taken. Try again shortly.");
        }
    }

    /** Closes every open clan screen, so a shutdown never eats a pending donation. */
    void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof ClanMenu) {
                player.closeInventory();
            }
        }
    }

    private ClanStore.ClanView requireClan(Player player) {
        return store.clanOf(player.getUniqueId())
                .orElseThrow(() -> new ClanStore.ClanException("You are not in a clan."));
    }

    private Inventory create(ClanMenu.Kind kind, ClanStore.ClanView clan, int size, String title) {
        ClanMenu menu = new ClanMenu(kind, clan.id());
        Inventory inventory = Bukkit.createInventory(menu, size, Component.text(title, ORANGE));
        menu.attach(inventory);
        return inventory;
    }

    private static ItemStack button(Material material, String name, String... lore) {
        return button(material, name, List.of(lore));
    }

    private static ItemStack button(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, ORANGE, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream()
                    .map(line -> (Component) Component.text(line, NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false))
                    .toList());
            item.setItemMeta(meta);
        }
        return item;
    }

    private static void give(Player player, ItemStack item) {
        player.getInventory().addItem(item).values().forEach(overflow ->
                player.getWorld().dropItemNaturally(player.getLocation(), overflow));
    }

    private static void announce(ClanStore.ClanView clan, Component message) {
        Component rendered = prefix().append(message);
        for (UUID memberId : clan.members().keySet()) {
            Player online = Bukkit.getPlayer(memberId);
            if (online != null) {
                online.sendMessage(rendered);
            }
        }
    }

    private static List<String> perkLines(ClanLevel.Perks perks) {
        if (perks.isNone()) {
            return List.of("No perks yet.");
        }
        List<String> lines = new ArrayList<>();
        if (perks.extraHearts() > 0) {
            lines.add("+" + perks.extraHearts()
                    + (perks.extraHearts() == 1 ? " extra heart" : " extra hearts"));
        }
        addPercent(lines, perks.strength(), "strength");
        addPercent(lines, perks.saturation(), "saturation");
        addPercent(lines, perks.diggingSpeed(), "digging speed");
        addPercent(lines, perks.resistance(), "resistance");
        addPercent(lines, perks.speed(), "speed");
        return lines;
    }

    private static void addPercent(List<String> lines, double fraction, String label) {
        if (fraction > 0) {
            lines.add("+" + Math.round(fraction * 100) + "% " + label);
        }
    }

    private static String describeLevel(int level) {
        return level == 0 ? "Unranked." : "Level " + level + ".";
    }

    private static void error(Player player, String message) {
        player.sendMessage(prefix().append(Component.text(message, NamedTextColor.RED)));
    }

    private static Component prefix() {
        return Component.text("CLANS » ", ORANGE, TextDecoration.BOLD);
    }
}
