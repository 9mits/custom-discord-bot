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
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
    /** Finishes the clan card's single row. Must stay inside {@link #HUB_SIZE}. */
    private static final int INFO_MEMBERS = 15;

    private static final long[] DONATE_AMOUNTS = {100L, 1_000L, 10_000L, 100_000L, 1_000_000L};
    private static final int[] DONATE_SLOTS = {11, 12, 13, 14, 15};

    private final MGXAccessBridge plugin;
    private final ClanStore store;
    private final DiscordIdentityService identities;
    private final EconomyStore money;

    ClanMenuService(
            MGXAccessBridge plugin,
            ClanStore store,
            DiscordIdentityService identities,
            EconomyStore money
    ) {
        this.plugin = plugin;
        this.store = store;
        this.identities = identities;
        this.money = money;
    }

    void openHub(Player player) {
        ClanStore.ClanView clan = requireOwnClan(player);
        Inventory inventory = create(
                Menu.Kind.CLAN_HUB, clan.id(), 1, HUB_SIZE, "Clan  " + clan.name(), null
        );
        inventory.setItem(HUB_DONATE, button(Material.GOLD_INGOT, "Donate",
                "Give money to the clan.", "Donations cannot be taken back."));
        inventory.setItem(HUB_BALANCE, button(Material.SUNFLOWER, "Balance",
                EconomyFormat.dollars(clan.balance()) + " in the treasury."));
        inventory.setItem(HUB_INFO, button(Material.BOOK, "Clan info",
                describeLevel(clan.level()), "Leader, roster and theme."));
        inventory.setItem(HUB_MEMBERS, head(clan.leader(), "Members",
                List.of(clan.members().size() + "/" + clan.memberSlots() + " members.")));
        inventory.setItem(HUB_UPGRADE, button(Material.NETHER_STAR, "Upgrades",
                describeLevel(clan.level()),
                clan.members().size() + "/" + clan.memberSlots() + " members."));
        inventory.setItem(HUB_DONORS, button(Material.EMERALD, "Donors",
                "Who has given what.", "Largest first."));
        MenuItems.show(plugin, player, inventory);
    }

    void openDonate(Player player) {
        ClanStore.ClanView clan = requireOwnClan(player);
        Inventory inventory = create(
                Menu.Kind.CLAN_DONATE, clan.id(), 1, HUB_SIZE,
                "Donate  •  " + EconomyFormat.dollars(money.balance(player.getUniqueId())),
                Menu.Destination.of(Menu.Kind.CLAN_HUB)
        );
        for (int index = 0; index < DONATE_AMOUNTS.length; index++) {
            long amount = DONATE_AMOUNTS[index];
            inventory.setItem(DONATE_SLOTS[index], button(
                    Material.GOLD_NUGGET,
                    "Donate " + EconomyFormat.dollars(amount),
                    "Taken from your wallet.",
                    "Cannot be withdrawn."
            ));
        }
        inventory.setItem(22, button(
                Material.PAPER, "Custom amount",
                "Use /clans donate <amount>."
        ));
        MenuItems.back(inventory);
        MenuItems.show(plugin, player, inventory);
    }

    void donate(Player player, long amount) throws IOException {
        if (!money.tryWithdraw(player.getUniqueId(), amount)) {
            throw new ClanStore.ClanException("You need " + EconomyFormat.dollars(amount) + ".");
        }
        try {
            store.donate(player.getUniqueId(), amount);
        } catch (RuntimeException | IOException failure) {
            money.deposit(player.getUniqueId(), amount);
            throw failure;
        }
        ClanStore.ClanView clan = store.clanOf(player.getUniqueId()).orElseThrow();
        report(player, "clan_donate",
                "Donated " + EconomyFormat.dollars(amount) + " to " + clan.name())
                .detail("clan", clan.name())
                .detail("value", amount)
                .detail("balance", clan.balance())
                .record();
        announce(clan, Component.text(
                player.getName() + " donated " + EconomyFormat.dollars(amount)
                        + " to the clan.", ORANGE));
        info(player, "Donated " + EconomyFormat.dollars(amount) + ". Treasury is now "
                + EconomyFormat.dollars(clan.balance()) + ".");
    }

    /** The clan card. Works for any clan, not only the viewer's own. */
    void openInfo(Player player, ClanStore.ClanView clan, Menu.Destination back) {
        Inventory inventory = create(
                Menu.Kind.CLAN_INFO, clan.id(), 1, HUB_SIZE, "Clan  " + clan.name(), back
        );
        String leader = clan.members().getOrDefault(clan.leader(), "Unknown");
        long online = clan.members().keySet().stream().filter(id -> Bukkit.getPlayer(id) != null).count();
        inventory.setItem(11, head(clan.leader(), "Leader", List.of(leader)));
        inventory.setItem(12, button(Material.NETHER_STAR, "Level",
                describeLevel(clan.level()),
                "Treasury " + EconomyFormat.dollars(clan.balance()) + "."));
        inventory.setItem(13, button(Material.WHITE_BANNER, "Theme",
                String.format("#%06X", clan.themeColor())));
        inventory.setItem(14, button(Material.CLOCK, "Online",
                online + " of " + clan.members().size() + " here now."));
        List<String> allies = clan.allyNames();
        List<String> allyLore = new ArrayList<>();
        if (allies.isEmpty()) {
            allyLore.add("None.");
            allyLore.add("Staff offer one with /clans ally <clan>.");
        } else {
            allyLore.addAll(allies);
            allyLore.add("No friendly fire with these clans.");
        }
        inventory.setItem(16, button(Material.SHIELD, "Allies", allyLore));
        inventory.setItem(INFO_MEMBERS, head(clan.leader(), "Members",
                List.of(clan.members().size() + "/" + clan.memberSlots() + " — click to see them.")));
        if (back != null) {
            MenuItems.back(inventory);
        }
        MenuItems.show(plugin, player, inventory);
    }

    void openInfo(Player player, String requestedName) {
        openInfo(player, lookup(player, requestedName), null);
    }

    /** Every member, with the Discord name each of them chose to show. */
    void openMembers(Player player, UUID clanId, int page, Menu.Destination back) {
        ClanStore.ClanView clan = requireClan(clanId);
        List<Map.Entry<UUID, String>> roster = new ArrayList<>(clan.members().entrySet());
        roster.sort((left, right) -> {
            int byRole = rank(clan, left.getKey()) - rank(clan, right.getKey());
            return byRole != 0 ? byRole : left.getValue().compareToIgnoreCase(right.getValue());
        });
        Inventory inventory = create(
                Menu.Kind.CLAN_MEMBERS, clan.id(), page, BOARD_SIZE,
                MenuItems.pagedTitle(clan.name() + " members", page, roster.size()),
                back
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
            lore.add("Donated " + EconomyFormat.dollars(given) + ".");
            Long joined = clan.joinedAt().get(member.getKey());
            lore.add(joined == null
                    ? "Joined: unknown"
                    : "Joined " + java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(java.time.Instant.ofEpochMilli(joined)));
            inventory.setItem(index - first, head(member.getKey(), member.getValue(), lore));
        }
        MenuItems.paginate(inventory, page, roster.size(), back != null);
        MenuItems.show(plugin, player, inventory);
    }

    /** The clan directory. */
    void openList(Player player, int page) {
        List<ClanStore.ClanView> clans = store.list();
        Inventory inventory = create(
                Menu.Kind.CLAN_LIST, null, page, BOARD_SIZE,
                MenuItems.pagedTitle("Clans", page, clans.size()),
                null
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
                            "Treasury " + EconomyFormat.dollars(clan.balance()) + ".",
                            "Click to open."
                    )));
        }
        if (clans.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, "No clans yet",
                    "Found one with /clans create <name>."));
        }
        MenuItems.paginate(inventory, page, clans.size(), false);
        MenuItems.show(plugin, player, inventory);
    }

    void openBalance(Player player) {
        ClanStore.ClanView clan = requireOwnClan(player);
        Inventory inventory = create(
                Menu.Kind.CLAN_BALANCE, clan.id(), 1, HUB_SIZE,
                "Treasury  •  " + EconomyFormat.dollars(clan.balance()),
                Menu.Destination.of(Menu.Kind.CLAN_HUB)
        );
        inventory.setItem(13, button(
                Material.SUNFLOWER,
                EconomyFormat.dollars(clan.balance()),
                "Donated money stays here.",
                "It cannot be withdrawn."
        ));
        MenuItems.back(inventory);
        MenuItems.show(plugin, player, inventory);
    }

    void openDonors(Player player) {
        ClanStore.ClanView clan = requireOwnClan(player);
        List<Map.Entry<UUID, Long>> ranked = clan.rankedDonors();
        Inventory inventory = create(
                Menu.Kind.CLAN_DONORS, clan.id(), 1, BOARD_SIZE,
                MenuItems.pagedTitle("Donors", 1, ranked.size()),
                Menu.Destination.of(Menu.Kind.CLAN_HUB)
        );
        int shown = Math.min(ranked.size(), PER_PAGE);
        for (int index = 0; index < shown; index++) {
            Map.Entry<UUID, Long> donor = ranked.get(index);
            String name = clan.members().getOrDefault(donor.getKey(), "Former member");
            inventory.setItem(index, head(donor.getKey(), "#" + (index + 1) + "  " + name,
                    List.of("Donated " + EconomyFormat.dollars(donor.getValue()) + " in all.")));
        }
        if (ranked.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, "No donations yet",
                    "Be the first to give something."));
        }
        MenuItems.back(inventory);
        MenuItems.show(plugin, player, inventory);
    }

    void openUpgrade(Player player) {
        ClanStore.ClanView clan = requireOwnClan(player);
        Inventory inventory = create(
                Menu.Kind.CLAN_UPGRADE, clan.id(), 1, BOARD_SIZE, "Upgrades  " + clan.name(),
                Menu.Destination.of(Menu.Kind.CLAN_HUB)
        );
        inventory.setItem(UPGRADE_LEVEL, levelButton(clan));
        inventory.setItem(UPGRADE_MEMBERS, memberButton(clan));
        MenuItems.back(inventory);
        MenuItems.show(plugin, player, inventory);
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
        ClanLevel.Cost cost = ClanLevel.costOf(next.get()).orElseThrow();
        lore.add("Cost " + EconomyFormat.dollars(cost.dollars()));
        lore.add("Treasury " + EconomyFormat.dollars(clan.balance()));
        lore.add("");
        lore.addAll(perkLines(ClanLevel.perksFor(next.get())));
        lore.add("");
        lore.add(ClanLevel.shortfall(clan.balance(), cost) == 0L
                ? "Click to buy." : "The treasury is short.");
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
        lore.add("Cost " + EconomyFormat.dollars(next.get().cost().dollars()));
        lore.add("Treasury " + EconomyFormat.dollars(clan.balance()));
        lore.add("");
        lore.add(ClanLevel.shortfall(clan.balance(), next.get().cost()) == 0L
                ? "Click to buy." : "The treasury is short.");
        return button(Material.PLAYER_HEAD, "Roster size", lore);
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
        // Back is drawn in the same place on every screen that has one, so it is
        // resolved once here rather than being repeated in each branch below.
        if (menu.hasBack() && slot == MenuItems.backSlot(menu.getInventory().getSize())) {
            openDestination(player, menu.back());
            return;
        }
        switch (menu.kind()) {
            case CLAN_HUB -> {
                Menu.Destination hub = Menu.Destination.of(Menu.Kind.CLAN_HUB);
                switch (slot) {
                    case HUB_DONATE -> openDonate(player);
                    case HUB_BALANCE -> openBalance(player);
                    case HUB_INFO -> openInfo(player, requireOwnClan(player), hub);
                    case HUB_MEMBERS -> openMembers(player, menu.subject(), 1, hub);
                    case HUB_UPGRADE -> openUpgrade(player);
                    case HUB_DONORS -> openDonors(player);
                    default -> { }
                }
            }
            case CLAN_INFO -> {
                if (slot == INFO_MEMBERS) {
                    // Back out of the roster returns to this card, still remembering
                    // whichever screen led here.
                    openMembers(player, menu.subject(), 1,
                            new Menu.Destination(Menu.Kind.CLAN_INFO, menu.subject(), 1));
                }
            }
            case CLAN_MEMBERS -> {
                switch (slot) {
                    case PREVIOUS_SLOT -> openMembers(player, menu.subject(), menu.page() - 1, menu.back());
                    case NEXT_SLOT -> openMembers(player, menu.subject(), menu.page() + 1, menu.back());
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
            case CLAN_DONATE -> donateClicked(player, slot);
            case CLAN_UPGRADE -> {
                switch (slot) {
                    case UPGRADE_LEVEL -> buyLevel(player);
                    case UPGRADE_MEMBERS -> buyMembers(player);
                    default -> { }
                }
            }
            default -> { }
        }
    }

    /** Reopens a remembered screen. Anything no longer reachable falls back to the hub. */
    private void openDestination(Player player, Menu.Destination back) {
        switch (back.kind()) {
            case CLAN_LIST -> openList(player, back.page());
            case CLAN_INFO -> openInfo(player, requireClan(back.subject()), Menu.Destination.of(Menu.Kind.CLAN_HUB));
            case CLAN_MEMBERS -> openMembers(player, back.subject(), back.page(),
                    Menu.Destination.of(Menu.Kind.CLAN_HUB));
            default -> openHub(player);
        }
    }

    private void openClanAt(Player player, int page, int slot) {
        List<ClanStore.ClanView> clans = store.list();
        int index = MenuPaging.firstIndex(page, clans.size(), PER_PAGE) + slot;
        if (slot >= 0 && slot < PER_PAGE && index < clans.size()) {
            openInfo(player, clans.get(index), new Menu.Destination(Menu.Kind.CLAN_LIST, null, page));
        }
    }

    private void buyLevel(Player player) throws IOException {
        ClanStore.ClanView upgraded = store.upgrade(player.getUniqueId());
        plugin.refreshClans();
        report(player, "clan_upgrade",
                "Upgraded " + upgraded.name() + " to level " + upgraded.level())
                .detail("clan", upgraded.name())
                .detail("level", String.valueOf(upgraded.level()))
                .detail("balance_left", upgraded.balance())
                .record();
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
        report(player, "clan_roster_buy",
                "Bought a roster slot for " + upgraded.name())
                .detail("clan", upgraded.name())
                .detail("slots", String.valueOf(upgraded.memberSlots()))
                .detail("balance_left", upgraded.balance())
                .record();
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

    private void donateClicked(Player player, int slot) throws IOException {
        for (int index = 0; index < DONATE_SLOTS.length; index++) {
            if (slot == DONATE_SLOTS[index]) {
                donate(player, DONATE_AMOUNTS[index]);
                openDonate(player);
                return;
            }
        }
        if (slot == 22) {
            info(player, "Type /clans donate <amount> to give a custom sum.");
        }
    }

    /** Reports a clan action taken through the menus to the Discord activity log. */
    private ServerEvent.Builder report(Player actor, String event, String summary) {
        return ServerEvent.of(
                event, ServerEvent.CATEGORY_CLAN, actor.getUniqueId(), actor.getName(),
                plugin::recordServerEvent
        ).summary(summary);
    }

    /** What was donated, in the readable names the Discord log already uses. */
    private static String describeOffer(Map<String, Integer> offered) {
        return offered.entrySet().stream()
                .map(entry -> entry.getValue() + "x " + WealthValues.readable(entry.getKey()))
                .collect(java.util.stream.Collectors.joining(", "));
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

    private Inventory create(
            Menu.Kind kind, UUID subject, int page, int size, String title, Menu.Destination back
    ) {
        Menu menu = new Menu(kind, subject, page, back);
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

    private static void info(Player player, String message) {
        player.sendMessage(prefix().append(Component.text(message, NamedTextColor.GRAY)));
    }

    private static void error(Player player, String message) {
        player.sendMessage(prefix().append(Component.text(message, NamedTextColor.RED)));
    }

    private static Component prefix() {
        return Component.text("CLANS » ", ORANGE, TextDecoration.BOLD);
    }
}
