package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static bot.mgx.accessbridge.MenuItems.BACK_SLOT;
import static bot.mgx.accessbridge.MenuItems.BOARD_SIZE;
import static bot.mgx.accessbridge.MenuItems.NEXT_SLOT;
import static bot.mgx.accessbridge.MenuItems.ORANGE;
import static bot.mgx.accessbridge.MenuItems.PER_PAGE;
import static bot.mgx.accessbridge.MenuItems.PREVIOUS_SLOT;
import static bot.mgx.accessbridge.MenuItems.button;
import static bot.mgx.accessbridge.MenuItems.head;

/**
 * The clan screens. Container menus render as native UI on Bedrock through Geyser,
 * so these work on mobile as well as desktop.
 *
 * <p>Everything decided here is read from {@link ClanLevel}, {@link ClanStore} and
 * {@link MenuPaging}; this class only draws and dispatches, because Bukkit-bound code
 * cannot be unit tested in this project.
 */
final class ClanMenuService implements Listener {
    private static final int HUB_SIZE = 27;
    private static final int HUB_DONATE = 10;
    private static final int HUB_BALANCE = 11;
    private static final int HUB_INFO = 12;
    private static final int HUB_MEMBERS = 14;
    private static final int HUB_UPGRADE = 15;
    private static final int HUB_DONORS = 16;
    private static final int UPGRADE_LEVEL = 20;
    private static final int UPGRADE_MEMBERS = 24;
    private static final int INFO_MEMBERS = 31;

    private final MGXAccessBridge plugin;
    private final ClanStore store;
    private final DiscordIdentityService identities;

    ClanMenuService(MGXAccessBridge plugin, ClanStore store, DiscordIdentityService identities) {
        this.plugin = plugin;
        this.store = store;
        this.identities = identities;
    }

    void openHub(Player player) {
        ClanStore.ClanView clan = requireOwnClan(player);
        Inventory inventory = create(Menu.Kind.CLAN_HUB, clan.id(), 1, HUB_SIZE, "Clan  " + clan.name());
        inventory.setItem(HUB_DONATE, button(Material.CHEST, "Donate",
                "Give items to the clan.", "Donations cannot be taken back."));
        inventory.setItem(HUB_BALANCE, button(Material.GOLD_INGOT, "Balance",
                "Worth " + String.format("%,d", clan.balance()) + "."));
        inventory.setItem(HUB_INFO, button(Material.BOOK, "Clan info",
                describeLevel(clan.level()), "Leader, roster and theme."));
        inventory.setItem(HUB_MEMBERS, head(clan.leader(), "Members",
                List.of(clan.members().size() + "/" + clan.memberSlots() + " members.")));
        inventory.setItem(HUB_UPGRADE, button(Material.NETHER_STAR, "Upgrades",
                describeLevel(clan.level()),
                clan.members().size() + "/" + clan.memberSlots() + " members."));
        inventory.setItem(HUB_DONORS, button(Material.EMERALD, "Donors",
                "Who has given what.", "Largest first."));
        player.openInventory(inventory);
    }

    void openDonate(Player player) {
        ClanStore.ClanView clan = requireOwnClan(player);
        // Deliberately empty: whatever is inside when the window closes is banked.
        Inventory inventory = create(
                Menu.Kind.CLAN_DONATE, clan.id(), 1, BOARD_SIZE, "Donate to " + clan.name()
        );
        player.openInventory(inventory);
        player.sendMessage(prefix().append(Component.text(
                "Drop items in, then close the window. Anything worthless comes back.",
                NamedTextColor.WHITE
        )));
    }

    /** The clan card. Works for any clan, not only the viewer's own. */
    void openInfo(Player player, ClanStore.ClanView clan) {
        Inventory inventory = create(
                Menu.Kind.CLAN_INFO, clan.id(), 1, HUB_SIZE, "Clan  " + clan.name()
        );
        String leader = clan.members().getOrDefault(clan.leader(), "Unknown");
        long online = clan.members().keySet().stream().filter(id -> Bukkit.getPlayer(id) != null).count();
        inventory.setItem(11, head(clan.leader(), "Leader", List.of(leader)));
        inventory.setItem(12, button(Material.NETHER_STAR, "Level",
                describeLevel(clan.level()),
                "Balance " + String.format("%,d", clan.balance()) + "."));
        inventory.setItem(13, button(Material.WHITE_BANNER, "Theme",
                String.format("#%06X", clan.themeColor())));
        inventory.setItem(14, button(Material.CLOCK, "Online",
                online + " of " + clan.members().size() + " here now."));
        inventory.setItem(INFO_MEMBERS, head(clan.leader(), "Members",
                List.of(clan.members().size() + "/" + clan.memberSlots() + " — click to see them.")));
        player.openInventory(inventory);
    }

    void openInfo(Player player, String requestedName) {
        openInfo(player, lookup(player, requestedName));
    }

    /** Every member, with the Discord name each of them chose to show. */
    void openMembers(Player player, UUID clanId, int page) {
        ClanStore.ClanView clan = requireClan(clanId);
        List<Map.Entry<UUID, String>> roster = new ArrayList<>(clan.members().entrySet());
        roster.sort((left, right) -> {
            int byRole = rank(clan, left.getKey()) - rank(clan, right.getKey());
            return byRole != 0 ? byRole : left.getValue().compareToIgnoreCase(right.getValue());
        });
        Inventory inventory = create(
                Menu.Kind.CLAN_MEMBERS, clan.id(), page, BOARD_SIZE,
                MenuItems.pagedTitle(clan.name() + " members", page, roster.size())
        );
        int first = MenuPaging.firstIndex(page, roster.size(), PER_PAGE);
        int last = MenuPaging.lastIndex(page, roster.size(), PER_PAGE);
        for (int index = first; index < last; index++) {
            Map.Entry<UUID, String> member = roster.get(index);
            List<String> lore = new ArrayList<>();
            lore.add(roleName(clan, member.getKey()));
            // Honours each player's own visibility choice, so this cannot surface a
            // Discord name somebody has hidden.
            identities.visibleUsername(member.getKey())
                    .ifPresent(username -> lore.add("@" + username));
            lore.add(Bukkit.getPlayer(member.getKey()) != null ? "Online now" : "Offline");
            long given = clan.donations().getOrDefault(member.getKey(), 0L);
            if (given > 0) {
                lore.add("Donated " + String.format("%,d", given) + " in all.");
            }
            inventory.setItem(index - first, head(member.getKey(), member.getValue(), lore));
        }
        MenuItems.paginate(inventory, page, roster.size(), true);
        player.openInventory(inventory);
    }

    /** The clan directory. */
    void openList(Player player, int page) {
        List<ClanStore.ClanView> clans = store.list();
        Inventory inventory = create(
                Menu.Kind.CLAN_LIST, null, page, BOARD_SIZE,
                MenuItems.pagedTitle("Clans", page, clans.size())
        );
        int first = MenuPaging.firstIndex(page, clans.size(), PER_PAGE);
        int last = MenuPaging.lastIndex(page, clans.size(), PER_PAGE);
        for (int index = first; index < last; index++) {
            ClanStore.ClanView clan = clans.get(index);
            inventory.setItem(index - first, head(clan.leader(),
                    clan.name() + "  " + ClanLevel.badge(clan.level()),
                    List.of(
                            describeLevel(clan.level()),
                            clan.members().size() + "/" + clan.memberSlots() + " members.",
                            "Balance " + String.format("%,d", clan.balance()) + ".",
                            "Click to open."
                    )));
        }
        if (clans.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, "No clans yet",
                    "Found one with /clans create <name>."));
        }
        MenuItems.paginate(inventory, page, clans.size(), false);
        player.openInventory(inventory);
    }

    void openBalance(Player player) {
        ClanStore.ClanView clan = requireOwnClan(player);
        Inventory inventory = create(
                Menu.Kind.CLAN_BALANCE, clan.id(), 1, BOARD_SIZE,
                "Balance  " + String.format("%,d", clan.balance())
        );
        int slot = 0;
        for (Map.Entry<String, Integer> entry : clan.vault().entrySet()) {
            if (slot >= PER_PAGE) {
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
        inventory.setItem(BACK_SLOT, button(Material.BARRIER, "Back"));
        player.openInventory(inventory);
    }

    void openDonors(Player player) {
        ClanStore.ClanView clan = requireOwnClan(player);
        List<Map.Entry<UUID, Long>> ranked = clan.rankedDonors();
        Inventory inventory = create(
                Menu.Kind.CLAN_DONORS, clan.id(), 1, BOARD_SIZE,
                MenuItems.pagedTitle("Donors", 1, ranked.size())
        );
        int shown = Math.min(ranked.size(), PER_PAGE);
        for (int index = 0; index < shown; index++) {
            Map.Entry<UUID, Long> donor = ranked.get(index);
            String name = clan.members().getOrDefault(donor.getKey(), "Former member");
            inventory.setItem(index, head(donor.getKey(), "#" + (index + 1) + "  " + name,
                    List.of("Donated " + String.format("%,d", donor.getValue()) + " in all.")));
        }
        if (ranked.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, "No donations yet",
                    "Be the first to give something."));
        }
        inventory.setItem(BACK_SLOT, button(Material.BARRIER, "Back"));
        player.openInventory(inventory);
    }

    void openUpgrade(Player player) {
        ClanStore.ClanView clan = requireOwnClan(player);
        Inventory inventory = create(
                Menu.Kind.CLAN_UPGRADE, clan.id(), 1, BOARD_SIZE, "Upgrades  " + clan.name()
        );
        inventory.setItem(UPGRADE_LEVEL, levelButton(clan));
        inventory.setItem(UPGRADE_MEMBERS, memberButton(clan));
        inventory.setItem(BACK_SLOT, button(Material.BARRIER, "Back"));
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
        lore.addAll(perkLines(ClanLevel.perksFor(next.get())));
        lore.add("");
        lore.add(ClanLevel.shortfall(clan.vault(), next.get()).isEmpty()
                ? "Click to buy." : "The vault is short.");
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
                ? "Click to buy." : "The vault is short.");
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
        if (!(event.getInventory().getHolder() instanceof Menu menu)
                || !(event.getWhoClicked() instanceof Player player)
                || !isClanMenu(menu)) {
            return;
        }
        if (menu.kind().acceptsItems()) {
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

    private void dispatch(Player player, Menu menu, int slot) throws IOException {
        switch (menu.kind()) {
            case CLAN_HUB -> {
                switch (slot) {
                    case HUB_DONATE -> openDonate(player);
                    case HUB_BALANCE -> openBalance(player);
                    case HUB_INFO -> openInfo(player, requireOwnClan(player));
                    case HUB_MEMBERS -> openMembers(player, menu.subject(), 1);
                    case HUB_UPGRADE -> openUpgrade(player);
                    case HUB_DONORS -> openDonors(player);
                    default -> { }
                }
            }
            case CLAN_INFO -> {
                if (slot == INFO_MEMBERS) {
                    openMembers(player, menu.subject(), 1);
                }
            }
            case CLAN_MEMBERS -> {
                switch (slot) {
                    case PREVIOUS_SLOT -> openMembers(player, menu.subject(), menu.page() - 1);
                    case NEXT_SLOT -> openMembers(player, menu.subject(), menu.page() + 1);
                    case BACK_SLOT -> openInfo(player, requireClan(menu.subject()));
                    default -> { }
                }
            }
            case CLAN_LIST -> {
                switch (slot) {
                    case PREVIOUS_SLOT -> openList(player, menu.page() - 1);
                    case NEXT_SLOT -> openList(player, menu.page() + 1);
                    default -> openClanAt(player, menu.page(), slot);
                }
            }
            case CLAN_UPGRADE -> {
                switch (slot) {
                    case UPGRADE_LEVEL -> buyLevel(player);
                    case UPGRADE_MEMBERS -> buyMembers(player);
                    case BACK_SLOT -> openHub(player);
                    default -> { }
                }
            }
            case CLAN_BALANCE, CLAN_DONORS -> {
                if (slot == BACK_SLOT) {
                    openHub(player);
                }
            }
            default -> { }
        }
    }

    private void openClanAt(Player player, int page, int slot) {
        List<ClanStore.ClanView> clans = store.list();
        int index = MenuPaging.firstIndex(page, clans.size(), PER_PAGE) + slot;
        if (slot >= 0 && slot < PER_PAGE && index < clans.size()) {
            openInfo(player, clans.get(index));
        }
    }

    private void buyLevel(Player player) throws IOException {
        ClanStore.ClanView upgraded = store.upgrade(player.getUniqueId());
        plugin.refreshClans();
        announce(upgraded, Component.text(
                "The clan reached level " + upgraded.level() + "!", ORANGE));
        for (String line : perkLines(upgraded.perks())) {
            announce(upgraded, Component.text("  " + line, NamedTextColor.WHITE));
        }
        openUpgrade(player);
    }

    private void buyMembers(Player player) throws IOException {
        ClanStore.ClanView upgraded = store.upgradeMembers(player.getUniqueId());
        plugin.refreshClans();
        announce(upgraded, Component.text(
                "The clan can now hold " + upgraded.memberSlots() + " members.", ORANGE));
        openUpgrade(player);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu
                && isClanMenu(menu)
                && !menu.kind().acceptsItems()) {
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
        if (!(event.getInventory().getHolder() instanceof Menu menu)
                || menu.kind() != Menu.Kind.CLAN_DONATE
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
                            + " to the clan.", ORANGE));
        } catch (ClanStore.ClanException | IOException failure) {
            // Nothing was banked, so nothing may be kept: hand it all straight back.
            returnAll(player, offered);
            error(player, failure instanceof ClanStore.ClanException
                    ? failure.getMessage()
                    : "That could not be saved, so nothing was taken. Try again shortly.");
            if (failure instanceof IOException) {
                plugin.getLogger().severe("Could not bank a clan donation: " + failure.getMessage());
            }
        }
    }

    private static void returnAll(Player player, Map<String, Integer> offered) {
        offered.forEach((material, amount) -> {
            Material resolved = Material.matchMaterial(material);
            if (resolved == null) {
                return;
            }
            int remaining = amount;
            while (remaining > 0) {
                int stack = Math.min(remaining, resolved.getMaxStackSize());
                give(player, new ItemStack(resolved, stack));
                remaining -= stack;
            }
        });
    }

    /** Closes every open clan screen, so a shutdown never eats a pending donation. */
    void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu menu
                    && isClanMenu(menu)) {
                player.closeInventory();
            }
        }
    }

    private static boolean isClanMenu(Menu menu) {
        return menu.kind().name().startsWith("CLAN_");
    }

    private ClanStore.ClanView requireOwnClan(Player player) {
        return store.clanOf(player.getUniqueId())
                .orElseThrow(() -> new ClanStore.ClanException("You are not in a clan."));
    }

    private ClanStore.ClanView requireClan(UUID clanId) {
        return store.list().stream()
                .filter(clan -> clan.id().equals(clanId))
                .findFirst()
                .orElseThrow(() -> new ClanStore.ClanException("That clan no longer exists."));
    }

    private ClanStore.ClanView lookup(Player player, String requestedName) {
        return (requestedName == null || requestedName.isBlank()
                ? store.clanOf(player.getUniqueId())
                : store.findClan(requestedName))
                .orElseThrow(() -> new ClanStore.ClanException(
                        requestedName == null || requestedName.isBlank()
                                ? "You are not in a clan."
                                : "No clan has that name."));
    }

    private static int rank(ClanStore.ClanView clan, UUID playerId) {
        return switch (clan.roleOf(playerId)) {
            case LEADER -> 0;
            case STAFF -> 1;
            case MEMBER -> 2;
        };
    }

    private static String roleName(ClanStore.ClanView clan, UUID playerId) {
        return switch (clan.roleOf(playerId)) {
            case LEADER -> "Leader";
            case STAFF -> "Clan staff";
            case MEMBER -> "Member";
        };
    }

    private Inventory create(Menu.Kind kind, UUID subject, int page, int size, String title) {
        Menu menu = new Menu(kind, subject, page);
        Inventory inventory = Bukkit.createInventory(menu, size, Component.text(title, ORANGE));
        menu.attach(inventory);
        return inventory;
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
