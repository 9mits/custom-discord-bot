package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static bot.mgx.accessbridge.MenuItems.ORANGE;

/** `/bounty` plus paying the killer when a marked player dies. */
final class BountyService implements CommandExecutor, TabCompleter, Listener {
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
                list(sender);
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

    private static String nameOf(java.util.UUID uuid) {
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
