package bot.mgx.accessbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
 * In-game leaderboard, drawn like the clan screens so Bedrock and Java share one UI.
 */
final class LeaderboardMenuService implements CommandExecutor, TabCompleter, Listener {
    private static final int HUB_SIZE = 27;
    private static final int HUB_CLANS_WEALTH = 10;
    private static final int HUB_CLANS_KILLS = 12;
    private static final int HUB_PLAYERS_WEALTH = 14;
    private static final int HUB_PLAYERS_KILLS = 16;
    private static final DateTimeFormatter JOINED =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK).withZone(ZoneId.systemDefault());

    private final MGXAccessBridge plugin;
    private final ClanStore clans;
    private final LeaderboardService boards;
    private final DiscordIdentityService identities;

    LeaderboardMenuService(
            MGXAccessBridge plugin,
            ClanStore clans,
            LeaderboardService boards,
            DiscordIdentityService identities
    ) {
        this.plugin = plugin;
        this.clans = clans;
        this.boards = boards;
        this.identities = identities;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("The leaderboard is a menu. Use it in Minecraft.");
            return true;
        }
        openHub(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args
    ) {
        return List.of();
    }

    void openHub(Player player) {
        Inventory inventory = create(
                Menu.Kind.LEADERBOARD_HUB, null, 1, HUB_SIZE, "Leaderboard", null
        );
        inventory.setItem(HUB_CLANS_WEALTH, button(Material.GOLD_BLOCK, "Richest clan",
                "Donated treasury, not member wallets."));
        inventory.setItem(HUB_CLANS_KILLS, button(Material.IRON_SWORD, "Clan with most kills",
                "Kills summed across members."));
        inventory.setItem(HUB_PLAYERS_WEALTH, button(Material.GOLD_INGOT, "Richest player",
                "Who has the most money."));
        inventory.setItem(HUB_PLAYERS_KILLS, button(Material.DIAMOND_SWORD, "Player with most kills",
                "Player kills, highest first."));
        MenuItems.show(plugin, player, inventory);
    }

    void openPlayers(Player player, String board, int page) {
        List<JsonObject> rows = rows("individual", board);
        String title = board.equals("kills") ? "Most kills" : "Richest players";
        Inventory inventory = create(
                board.equals("kills") ? Menu.Kind.LEADERBOARD_PLAYERS_KILLS : Menu.Kind.LEADERBOARD_PLAYERS_WEALTH,
                null, page, BOARD_SIZE,
                MenuItems.pagedTitle(title, page, rows.size()),
                Menu.Destination.of(Menu.Kind.LEADERBOARD_HUB)
        );
        int first = MenuPaging.firstIndex(page, rows.size(), PER_PAGE);
        int last = MenuPaging.lastIndex(page, rows.size(), PER_PAGE);
        for (int index = first; index < last; index++) {
            JsonObject row = rows.get(index);
            UUID uuid = parseUuid(row.has("minecraft_uuid") ? row.get("minecraft_uuid").getAsString() : "");
            String username = text(row, "username", "?");
            String value = text(row, "display", "0");
            String clan = text(row, "clan", "");
            List<String> lore = new ArrayList<>();
            lore.add("#" + (index + 1));
            if (!clan.isBlank()) {
                lore.add("[" + clan + "]");
            }
            lore.add(value);
            inventory.setItem(index - first, head(uuid, username, lore));
        }
        if (rows.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, "No standings yet",
                    "Play a little and this fills in."));
        }
        MenuItems.paginate(inventory, page, rows.size(), true);
        MenuItems.show(plugin, player, inventory);
    }

    void openClans(Player player, String board, int page) {
        List<ClanStore.ClanView> ranked = rankedClans(board);
        Menu.Kind kind = board.equals("kills")
                ? Menu.Kind.LEADERBOARD_CLANS_KILLS
                : Menu.Kind.LEADERBOARD_CLANS;
        String title = board.equals("kills") ? "Clan kills" : "Richest clans";
        Inventory inventory = create(
                kind, null, page, BOARD_SIZE,
                MenuItems.pagedTitle(title, page, ranked.size()),
                Menu.Destination.of(Menu.Kind.LEADERBOARD_HUB)
        );
        int first = MenuPaging.firstIndex(page, ranked.size(), PER_PAGE);
        int last = MenuPaging.lastIndex(page, ranked.size(), PER_PAGE);
        for (int index = first; index < last; index++) {
            ClanStore.ClanView clan = ranked.get(index);
            inventory.setItem(index - first, head(clan.leader(),
                    "#" + (index + 1) + "  " + clan.name() + "  " + ClanLevel.badge(clan.level()),
                    List.of(
                            clan.level() == 0 ? "Unranked." : "Level " + clan.level() + ".",
                            clan.members().size() + "/" + clan.memberSlots() + " members.",
                            board.equals("kills")
                                    ? "Balance " + String.format("%,d", clan.balance()) + "."
                                    : "Treasury " + EconomyFormat.dollars(clan.balance()) + "."
                    )));
        }
        if (ranked.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, "No clans yet",
                    "Found one with /clans create <name>."));
        }
        MenuItems.paginate(inventory, page, ranked.size(), true);
        MenuItems.show(plugin, player, inventory);
    }

    void openMembers(Player player, UUID clanId, int page, Menu.Kind backKind) {
        Optional<ClanStore.ClanView> found = clans.findClanById(clanId);
        if (found.isEmpty()) {
            openClans(player, "wealth", 1);
            return;
        }
        ClanStore.ClanView clan = found.get();
        List<Map.Entry<UUID, String>> roster = new ArrayList<>(clan.members().entrySet());
        roster.sort((left, right) -> {
            long leftGiven = clan.donations().getOrDefault(left.getKey(), 0L);
            long rightGiven = clan.donations().getOrDefault(right.getKey(), 0L);
            int byGift = Long.compare(rightGiven, leftGiven);
            return byGift != 0 ? byGift : left.getValue().compareToIgnoreCase(right.getValue());
        });
        Inventory inventory = create(
                Menu.Kind.LEADERBOARD_MEMBERS, clan.id(), page, BOARD_SIZE,
                MenuItems.pagedTitle(clan.name() + " members", page, roster.size()),
                Menu.Destination.of(backKind == null ? Menu.Kind.LEADERBOARD_CLANS : backKind)
        );
        int first = MenuPaging.firstIndex(page, roster.size(), PER_PAGE);
        int last = MenuPaging.lastIndex(page, roster.size(), PER_PAGE);
        for (int index = first; index < last; index++) {
            Map.Entry<UUID, String> member = roster.get(index);
            List<String> lore = new ArrayList<>();
            lore.add(clan.roleOf(member.getKey()).name().charAt(0)
                    + clan.roleOf(member.getKey()).name().substring(1).toLowerCase(Locale.ROOT));
            identities.visibleUsername(member.getKey())
                    .ifPresent(username -> lore.add("@" + username));
            lore.add(Bukkit.getPlayer(member.getKey()) != null ? "Online now" : "Offline");
            lore.add("Donated " + String.format("%,d", clan.donations().getOrDefault(member.getKey(), 0L)) + ".");
            Long joined = clan.joinedAt().get(member.getKey());
            lore.add(joined == null ? "Joined: unknown" : "Joined " + JOINED.format(Instant.ofEpochMilli(joined)));
            inventory.setItem(index - first, head(member.getKey(), member.getValue(), lore));
        }
        MenuItems.paginate(inventory, page, roster.size(), true);
        MenuItems.show(plugin, player, inventory);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)
                || !(event.getWhoClicked() instanceof Player player)
                || !isLeaderboard(menu.kind())) {
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
        switch (menu.kind()) {
            case LEADERBOARD_HUB -> {
                switch (slot) {
                    case HUB_CLANS_WEALTH -> openClans(player, "wealth", 1);
                    case HUB_CLANS_KILLS -> openClans(player, "kills", 1);
                    case HUB_PLAYERS_WEALTH -> openPlayers(player, "wealth", 1);
                    case HUB_PLAYERS_KILLS -> openPlayers(player, "kills", 1);
                    default -> { }
                }
            }
            case LEADERBOARD_PLAYERS_WEALTH -> pagePlayers(player, "wealth", menu, slot);
            case LEADERBOARD_PLAYERS_KILLS -> pagePlayers(player, "kills", menu, slot);
            case LEADERBOARD_CLANS -> pageClans(player, "wealth", menu, slot);
            case LEADERBOARD_CLANS_KILLS -> pageClans(player, "kills", menu, slot);
            case LEADERBOARD_MEMBERS -> {
                Menu.Kind back = menu.back() == null ? Menu.Kind.LEADERBOARD_CLANS : menu.back().kind();
                switch (slot) {
                    case PREVIOUS_SLOT -> openMembers(player, menu.subject(), menu.page() - 1, back);
                    case NEXT_SLOT -> openMembers(player, menu.subject(), menu.page() + 1, back);
                    default -> { }
                }
            }
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu && isLeaderboard(menu.kind())) {
            event.setCancelled(true);
        }
    }

    private void pagePlayers(Player player, String board, Menu menu, int slot) {
        switch (slot) {
            case PREVIOUS_SLOT -> openPlayers(player, board, menu.page() - 1);
            case NEXT_SLOT -> openPlayers(player, board, menu.page() + 1);
            default -> { }
        }
    }

    private void pageClans(Player player, String board, Menu menu, int slot) {
        switch (slot) {
            case PREVIOUS_SLOT -> openClans(player, board, menu.page() - 1);
            case NEXT_SLOT -> openClans(player, board, menu.page() + 1);
            default -> openClanAt(player, board, menu.page(), slot);
        }
    }

    private void openClanAt(Player player, String board, int page, int slot) {
        List<ClanStore.ClanView> ranked = rankedClans(board);
        int index = MenuPaging.firstIndex(page, ranked.size(), PER_PAGE) + slot;
        if (index < 0 || index >= ranked.size()) {
            return;
        }
        Menu.Kind back = board.equals("kills")
                ? Menu.Kind.LEADERBOARD_CLANS_KILLS
                : Menu.Kind.LEADERBOARD_CLANS;
        openMembers(player, ranked.get(index).id(), 1, back);
    }

    private List<ClanStore.ClanView> rankedClans(String board) {
        if (!"kills".equals(board)) {
            List<ClanStore.ClanView> ranked = new ArrayList<>(clans.list());
            ranked.sort(Comparator.comparingLong(ClanStore.ClanView::balance).reversed());
            return ranked;
        }
        List<ClanStore.ClanView> ranked = new ArrayList<>();
        for (JsonObject row : rows("clan", "kills")) {
            clans.findClan(text(row, "clan", "")).ifPresent(ranked::add);
        }
        return ranked;
    }

    private void openDestination(Player player, Menu.Destination back) {
        switch (back.kind()) {
            case LEADERBOARD_CLANS -> openClans(player, "wealth", Math.max(1, back.page()));
            case LEADERBOARD_CLANS_KILLS -> openClans(player, "kills", Math.max(1, back.page()));
            case LEADERBOARD_PLAYERS_WEALTH -> openPlayers(player, "wealth", 1);
            case LEADERBOARD_PLAYERS_KILLS -> openPlayers(player, "kills", 1);
            default -> openHub(player);
        }
    }

    private List<JsonObject> rows(String scope, String board) {
        JsonObject snapshot = boards.latest();
        if (snapshot == null || !snapshot.has(scope) || !snapshot.get(scope).isJsonObject()) {
            return List.of();
        }
        JsonObject section = snapshot.getAsJsonObject(scope);
        if (!section.has(board) || !section.get(board).isJsonArray()) {
            return List.of();
        }
        JsonArray array = section.getAsJsonArray(board);
        List<JsonObject> rows = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                rows.add(element.getAsJsonObject());
            }
        }
        return rows;
    }

    private Inventory create(
            Menu.Kind kind, UUID subject, int page, int size, String title, Menu.Destination back
    ) {
        Menu menu = new Menu(kind, subject, page, back);
        Inventory inventory = Bukkit.createInventory(menu, size, Component.text(title, ORANGE));
        menu.attach(inventory);
        return inventory;
    }

    private static boolean isLeaderboard(Menu.Kind kind) {
        return kind == Menu.Kind.LEADERBOARD_HUB
                || kind == Menu.Kind.LEADERBOARD_PLAYERS_WEALTH
                || kind == Menu.Kind.LEADERBOARD_PLAYERS_KILLS
                || kind == Menu.Kind.LEADERBOARD_CLANS
                || kind == Menu.Kind.LEADERBOARD_CLANS_KILLS
                || kind == Menu.Kind.LEADERBOARD_MEMBERS;
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String text(JsonObject row, String key, String fallback) {
        return row.has(key) && !row.get(key).isJsonNull() ? row.get(key).getAsString() : fallback;
    }
}
