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
    private static final int HUB_AMETHYST_CRATES = 20;
    private static final int HUB_REWARDS = 22;
    private static final int HUB_AMETHYST_AIRDROPS = 24;
    private static final int HUB_CLAN_BATTLE = 4;
    private static final DateTimeFormatter JOINED =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK).withZone(ZoneId.systemDefault());

    private final MGXAccessBridge plugin;
    private final ClanStore clans;
    private final LeaderboardService boards;
    private final DiscordIdentityService identities;
    private final CosmeticItems cosmeticItems;
    private final ClanBattleStore clanBattles;
    private LeaderboardDialogService dialogs;
    private SettingsClientSupport clientSupport;

    LeaderboardMenuService(
            MGXAccessBridge plugin,
            ClanStore clans,
            LeaderboardService boards,
            DiscordIdentityService identities,
            CosmeticItems cosmeticItems,
            ClanBattleStore clanBattles
    ) {
        this.plugin = plugin;
        this.clans = clans;
        this.boards = boards;
        this.identities = identities;
        this.cosmeticItems = cosmeticItems;
        this.clanBattles = clanBattles;
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

    /** Wired after construction because the dialog screens read this service's data. */
    void useDialogs(LeaderboardDialogService dialogs, SettingsClientSupport clientSupport) {
        this.dialogs = dialogs;
        this.clientSupport = clientSupport;
    }

    void openHub(Player player) {
        if (dialogs != null && clientSupport != null && clientSupport.supportsDialogs(player)) {
            dialogs.openHub(player);
            return;
        }
        openChestHub(player);
    }

    private void openChestHub(Player player) {
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
        inventory.setItem(HUB_AMETHYST_CRATES, button(Material.AMETHYST_BLOCK,
                "Most Amethyst Crates Opened", "The Amethyst Event crate race."));
        inventory.setItem(HUB_REWARDS, button(Material.NETHER_STAR, "Podium cosmetics",
                "Exclusive sets for player leaderboard #1, #2 and #3.",
                "They update automatically and can never be traded."));
        inventory.setItem(HUB_AMETHYST_AIRDROPS, button(Material.CHEST,
                "Most Amethyst Airdrops Opened", "First player to open each Airdrop scores."));
        ClanBattleStore.ActiveView battle = clanBattles.active(clans).orElse(null);
        inventory.setItem(HUB_CLAN_BATTLE, button(Material.NETHER_STAR,
                battle == null ? "Current Clan Battle" : battle.kind().displayName(),
                battle == null ? "No Clan Battle is running." : "Open the most crates!",
                battle == null ? "Check back when one starts."
                        : "Ends in " + ClanBattleCountdown.clock(
                                battle.endsAt() - System.currentTimeMillis()) + ".",
                "Clan scores reset per member when they leave."));
        MenuItems.show(plugin, player, inventory);
    }

    void openRewards(Player player) {
        Inventory inventory = create(
                Menu.Kind.LEADERBOARD_REWARDS, null, 1, HUB_SIZE,
                "Podium cosmetics", Menu.Destination.of(Menu.Kind.LEADERBOARD_HUB)
        );
        List<CosmeticCatalog.Definition> rewards = CosmeticCatalog.leaderboardRewards();
        for (int index = 0; index < rewards.size(); index++) {
            inventory.setItem(9 + index, cosmeticItems.preview(rewards.get(index), false));
        }
        MenuItems.back(inventory);
        MenuItems.show(plugin, player, inventory);
    }

    void openPlayers(Player player, String board, int page) {
        List<JsonObject> rows = rows("individual", board);
        String title = switch (board) {
            case "kills" -> "Most kills";
            case "amethyst_crates" -> "Amethyst Crates Opened";
            case "amethyst_airdrops" -> "Amethyst Airdrops Opened";
            default -> "Richest players";
        };
        Inventory inventory = create(
                playerKind(board),
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

    void openClanBattle(Player player, int page) {
        List<JsonObject> ranked = rows("clan", "clan_battle");
        ClanBattleStore.ActiveView battle = clanBattles.active(clans).orElse(null);
        String title = battle == null ? "Clan Battle" : battle.kind().displayName();
        Inventory inventory = create(
                Menu.Kind.LEADERBOARD_CLAN_BATTLE, null, page, BOARD_SIZE,
                MenuItems.pagedTitle(title, page, ranked.size()),
                Menu.Destination.of(Menu.Kind.LEADERBOARD_HUB)
        );
        int first = MenuPaging.firstIndex(page, ranked.size(), PER_PAGE);
        int last = MenuPaging.lastIndex(page, ranked.size(), PER_PAGE);
        for (int index = first; index < last; index++) {
            JsonObject row = ranked.get(index);
            UUID clanId = parseUuid(text(row, "clan_id", ""));
            ClanStore.ClanView clan = clanId == null ? null : clans.findClanById(clanId).orElse(null);
            int rank = row.has("rank") ? row.get("rank").getAsInt() : index + 1;
            String name = text(row, "clan", "?");
            String badges = text(row, "badges", "");
            List<String> lore = new ArrayList<>();
            lore.add(text(row, "display", "0 openings"));
            lore.add("Open the most crates!");
            if (!badges.isBlank()) {
                lore.add("Battle badges: " + badges);
            }
            inventory.setItem(index - first, head(
                    clan == null ? null : clan.leader(),
                    "#" + rank + "  " + name
                            + (clan == null ? "" : "  " + ClanLevel.badge(clan.level())),
                    lore
            ));
        }
        if (ranked.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, "No standings yet",
                    battle == null
                            ? "No Clan Battle is running."
                            : "Open a crate while you are in a clan."));
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
                    case HUB_AMETHYST_CRATES -> openPlayers(player, "amethyst_crates", 1);
                    case HUB_AMETHYST_AIRDROPS -> openPlayers(player, "amethyst_airdrops", 1);
                    case HUB_CLAN_BATTLE -> openClanBattle(player, 1);
                    case HUB_REWARDS -> openRewards(player);
                    default -> { }
                }
            }
            case LEADERBOARD_PLAYERS_WEALTH -> pagePlayers(player, "wealth", menu, slot);
            case LEADERBOARD_PLAYERS_KILLS -> pagePlayers(player, "kills", menu, slot);
            case LEADERBOARD_PLAYERS_AMETHYST_CRATES -> pagePlayers(
                    player, "amethyst_crates", menu, slot
            );
            case LEADERBOARD_PLAYERS_AMETHYST_AIRDROPS -> pagePlayers(
                    player, "amethyst_airdrops", menu, slot
            );
            case LEADERBOARD_CLANS -> pageClans(player, "wealth", menu, slot);
            case LEADERBOARD_CLANS_KILLS -> pageClans(player, "kills", menu, slot);
            case LEADERBOARD_CLAN_BATTLE -> pageClanBattle(player, menu, slot);
            case LEADERBOARD_MEMBERS -> {
                Menu.Kind back = menu.back() == null ? Menu.Kind.LEADERBOARD_CLANS : menu.back().kind();
                switch (slot) {
                    case PREVIOUS_SLOT -> openMembers(player, menu.subject(), menu.page() - 1, back);
                    case NEXT_SLOT -> openMembers(player, menu.subject(), menu.page() + 1, back);
                    default -> { }
                }
            }
            case LEADERBOARD_REWARDS -> { }
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

    private void pageClanBattle(Player player, Menu menu, int slot) {
        switch (slot) {
            case PREVIOUS_SLOT -> openClanBattle(player, menu.page() - 1);
            case NEXT_SLOT -> openClanBattle(player, menu.page() + 1);
            default -> openClanBattleAt(player, menu.page(), slot);
        }
    }

    private void openClanBattleAt(Player player, int page, int slot) {
        List<JsonObject> ranked = rows("clan", "clan_battle");
        int index = MenuPaging.firstIndex(page, ranked.size(), PER_PAGE) + slot;
        if (index < 0 || index >= ranked.size()) {
            return;
        }
        UUID clanId = parseUuid(text(ranked.get(index), "clan_id", ""));
        if (clanId != null) {
            openMembers(player, clanId, 1, Menu.Kind.LEADERBOARD_CLAN_BATTLE);
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
            case LEADERBOARD_CLAN_BATTLE -> openClanBattle(player, Math.max(1, back.page()));
            case LEADERBOARD_PLAYERS_WEALTH -> openPlayers(player, "wealth", 1);
            case LEADERBOARD_PLAYERS_KILLS -> openPlayers(player, "kills", 1);
            case LEADERBOARD_PLAYERS_AMETHYST_CRATES -> openPlayers(player, "amethyst_crates", 1);
            case LEADERBOARD_PLAYERS_AMETHYST_AIRDROPS -> openPlayers(player, "amethyst_airdrops", 1);
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
                || kind == Menu.Kind.LEADERBOARD_PLAYERS_AMETHYST_CRATES
                || kind == Menu.Kind.LEADERBOARD_PLAYERS_AMETHYST_AIRDROPS
                || kind == Menu.Kind.LEADERBOARD_CLANS
                || kind == Menu.Kind.LEADERBOARD_CLANS_KILLS
                || kind == Menu.Kind.LEADERBOARD_CLAN_BATTLE
                || kind == Menu.Kind.LEADERBOARD_MEMBERS
                || kind == Menu.Kind.LEADERBOARD_REWARDS;
    }

    private static Menu.Kind playerKind(String board) {
        return switch (board) {
            case "kills" -> Menu.Kind.LEADERBOARD_PLAYERS_KILLS;
            case "amethyst_crates" -> Menu.Kind.LEADERBOARD_PLAYERS_AMETHYST_CRATES;
            case "amethyst_airdrops" -> Menu.Kind.LEADERBOARD_PLAYERS_AMETHYST_AIRDROPS;
            default -> Menu.Kind.LEADERBOARD_PLAYERS_WEALTH;
        };
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
