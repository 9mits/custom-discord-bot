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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
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
import static bot.mgx.accessbridge.MenuItems.head;

/**
 * `/bounty` board — player heads ranked by pot, like Donut. Chat still places
 * ({@code /bounty set}, {@code /bounty clan}).
 */
final class BountyService implements CommandExecutor, TabCompleter, Listener {
    private static final int YOUR_BOUNTY_SLOT = 49;
    private static final int HOW_SLOT = 47;
    private static final int REFRESH_SLOT = 50;

    private final EconomyStore money;
    private final BountyStore bounties;
    private final ClanStore clans;

    BountyService(EconomyStore money, BountyStore bounties, ClanStore clans) {
        this.money = money;
        this.bounties = bounties;
        this.clans = clans;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        args = CommandArgs.withoutEchoedSender(sender.getName(), args);
        try {
            if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
                if (sender instanceof Player player) {
                    openBoard(player, 1);
                } else {
                    list(sender);
                }
                return true;
            }
            String action = args[0].toLowerCase(Locale.ROOT);
            switch (action) {
                case "set", "add", "place" -> set(sender, args, false);
                case "clan" -> set(sender, args, true);
                case "check" -> check(sender, args);
                default -> set(sender, prepend(args, "set"), false);
            }
        } catch (IllegalArgumentException exception) {
            error(sender, exception.getMessage());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args
    ) {
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(
                    args[0],
                    List.of("set", "clan", "list", "check"),
                    new ArrayList<>()
            );
        }
        if (args.length == 2) {
            return StringUtil.copyPartialMatches(
                    args[1],
                    Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(),
                    new ArrayList<>()
            );
        }
        return List.of();
    }

    void openBoard(Player player, int page) {
        List<BountyStore.Entry> ranked = bounties.ranked();
        Inventory inventory = create(
                Menu.Kind.BOUNTY_BOARD,
                null,
                page,
                "Bounties  •  " + EconomyFormat.dollars(money.balance(player.getUniqueId())),
                ranked.size()
        );
        int first = MenuPaging.firstIndex(page, ranked.size(), PER_PAGE);
        int last = MenuPaging.lastIndex(page, ranked.size(), PER_PAGE);
        for (int index = first; index < last; index++) {
            BountyStore.Entry entry = ranked.get(index);
            String name = nameOf(entry.target());
            boolean online = Bukkit.getPlayer(entry.target()) != null;
            inventory.setItem(index - first, head(
                    entry.target(),
                    "#" + (index + 1) + "  " + name,
                    List.of(
                            EconomyFormat.dollars(entry.amount()),
                            online ? "Online now." : "Offline.",
                            "Kill them to claim the pot."
                    )
            ));
        }
        if (ranked.isEmpty()) {
            inventory.setItem(22, button(
                    Material.BARRIER, "No bounties",
                    "Use /bounty set <player> <amount>."
            ));
        }
        long mine = bounties.amountOn(player.getUniqueId());
        inventory.setItem(YOUR_BOUNTY_SLOT, head(
                player.getUniqueId(),
                "Your bounty",
                List.of(mine > 0L
                        ? EconomyFormat.dollars(mine) + " on your head."
                        : "Nobody has marked you.")
        ));
        inventory.setItem(HOW_SLOT, button(
                Material.PAPER, "Place a bounty",
                " /bounty set <player> <amount>",
                " /bounty clan <player> <amount>",
                "Minimum " + EconomyFormat.dollars(BountyStore.MIN_BOUNTY) + "."
        ));
        inventory.setItem(REFRESH_SLOT, button(Material.SUNFLOWER, "Refresh"));
        MenuItems.paginate(inventory, page, ranked.size(), false);
        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)
                || menu.kind() != Menu.Kind.BOUNTY_BOARD
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getInventory()) {
            return;
        }
        int slot = event.getSlot();
        if (slot == PREVIOUS_SLOT) {
            openBoard(player, menu.page() - 1);
            return;
        }
        if (slot == NEXT_SLOT) {
            openBoard(player, menu.page() + 1);
            return;
        }
        if (slot == REFRESH_SLOT || slot == YOUR_BOUNTY_SLOT) {
            openBoard(player, menu.page());
            return;
        }
        if (slot == HOW_SLOT) {
            info(player, "Use /bounty set <player> <amount> or /bounty clan <player> <amount>.");
            return;
        }
        List<BountyStore.Entry> ranked = bounties.ranked();
        int index = MenuPaging.firstIndex(menu.page(), ranked.size(), PER_PAGE) + slot;
        if (slot < 0 || slot >= PER_PAGE || index >= ranked.size()) {
            return;
        }
        BountyStore.Entry entry = ranked.get(index);
        info(player, nameOf(entry.target()) + " has a "
                + EconomyFormat.dollars(entry.amount()) + " bounty. Kill them to claim it.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu
                && menu.kind() == Menu.Kind.BOUNTY_BOARD) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        long payout = bounties.collect(victim.getUniqueId());
        if (payout <= 0L) {
            return;
        }
        money.deposit(killer.getUniqueId(), payout);
        info(killer, "Collected " + EconomyFormat.dollars(payout) + " bounty on " + victim.getName() + ".");
        info(victim, killer.getName() + " claimed the " + EconomyFormat.dollars(payout) + " bounty on you.");
        Bukkit.broadcast(prefix().append(Component.text(
                killer.getName() + " claimed " + EconomyFormat.dollars(payout)
                        + " on " + victim.getName() + ".",
                NamedTextColor.GRAY
        )));
    }

    private void list(CommandSender sender) {
        List<BountyStore.Entry> ranked = bounties.ranked();
        if (ranked.isEmpty()) {
            info(sender, "Nobody has a bounty.");
            return;
        }
        info(sender, "Active bounties:");
        int shown = Math.min(10, ranked.size());
        for (int index = 0; index < shown; index++) {
            BountyStore.Entry entry = ranked.get(index);
            String name = nameOf(entry.target());
            sender.sendMessage(Component.text(
                    "  #" + (index + 1) + "  " + name + "  " + EconomyFormat.dollars(entry.amount()),
                    NamedTextColor.WHITE
            ));
        }
    }

    private void set(CommandSender sender, String[] args, boolean fromClan) {
        if (!(sender instanceof Player player)) {
            throw new IllegalArgumentException("/bounty is a player command.");
        }
        int nameIndex = args[0].equalsIgnoreCase("set")
                || args[0].equalsIgnoreCase("add")
                || args[0].equalsIgnoreCase("place")
                || args[0].equalsIgnoreCase("clan") ? 1 : 0;
        if (args.length <= nameIndex + 1) {
            throw new IllegalArgumentException(
                    fromClan
                            ? "Usage: /bounty clan <player> <amount>"
                            : "Usage: /bounty set <player> <amount>"
            );
        }
        Player target = Bukkit.getPlayerExact(args[nameIndex]);
        if (target == null) {
            throw new IllegalArgumentException("No player named " + args[nameIndex] + " is online.");
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            throw new IllegalArgumentException("You cannot put a bounty on yourself.");
        }
        long amount = EconomyFormat.parseAmount(args[nameIndex + 1]);
        if (amount < BountyStore.MIN_BOUNTY) {
            throw new IllegalArgumentException(
                    "Bounties start at " + EconomyFormat.dollars(BountyStore.MIN_BOUNTY) + "."
            );
        }
        String source;
        if (fromClan) {
            ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElseThrow(
                    () -> new IllegalArgumentException("You are not in a clan.")
            );
            if (clan.roleOf(player.getUniqueId()) != ClanStore.ClanRole.LEADER) {
                throw new IllegalArgumentException("Only the clan owner can spend the treasury on a bounty.");
            }
            if (clan.members().containsKey(target.getUniqueId())) {
                throw new IllegalArgumentException("You cannot put a bounty on a clan member.");
            }
            try {
                clans.spendTreasury(player.getUniqueId(), amount);
            } catch (ClanStore.ClanException exception) {
                throw new IllegalArgumentException(exception.getMessage());
            } catch (java.io.IOException exception) {
                throw new IllegalArgumentException("The clan treasury could not be saved. Try again.");
            }
            source = clan.name();
        } else {
            if (!money.tryWithdraw(player.getUniqueId(), amount)) {
                throw new IllegalArgumentException("You need " + EconomyFormat.dollars(amount) + ".");
            }
            source = player.getName();
        }
        long total = bounties.add(target.getUniqueId(), amount);
        info(player, source + " added " + EconomyFormat.dollars(amount) + " to " + target.getName()
                + ". Total: " + EconomyFormat.dollars(total) + ".");
        info(target, source + " put " + EconomyFormat.dollars(amount)
                + " on your head. Total: " + EconomyFormat.dollars(total) + ".");
        if (fromClan) {
            Bukkit.broadcast(prefix().append(Component.text(
                    "[" + source + "] put " + EconomyFormat.dollars(amount)
                            + " on " + target.getName() + ".",
                    NamedTextColor.GRAY
            )));
        }
    }

    private void check(CommandSender sender, String[] args) {
        Player target;
        if (args.length < 2) {
            if (!(sender instanceof Player player)) {
                throw new IllegalArgumentException("Usage: /bounty check <player>");
            }
            target = player;
        } else {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                throw new IllegalArgumentException("No player named " + args[1] + " is online.");
            }
        }
        long amount = bounties.amountOn(target.getUniqueId());
        if (amount <= 0L) {
            info(sender, target.getName() + " has no bounty.");
            return;
        }
        info(sender, target.getName() + " has a " + EconomyFormat.dollars(amount) + " bounty.");
    }

    private Inventory create(Menu.Kind kind, UUID subject, int page, String title, int total) {
        Menu menu = new Menu(kind, subject, page, null);
        Inventory inventory = Bukkit.createInventory(
                menu, BOARD_SIZE, Component.text(MenuItems.pagedTitle(title, page, total), ORANGE)
        );
        menu.attach(inventory);
        return inventory;
    }

    private static String nameOf(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }

    private static String[] prepend(String[] args, String first) {
        String[] next = new String[args.length + 1];
        next[0] = first;
        System.arraycopy(args, 0, next, 1, args.length);
        return next;
    }

    private static void info(CommandSender sender, String message) {
        sender.sendMessage(prefix().append(Component.text(message, NamedTextColor.GRAY)));
    }

    private static void error(CommandSender sender, String message) {
        sender.sendMessage(prefix().append(Component.text(message, NamedTextColor.RED)));
    }

    private static Component prefix() {
        return Component.text("BOUNTY » ", ORANGE, TextDecoration.BOLD);
    }
}
