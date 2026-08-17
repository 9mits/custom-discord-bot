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

    BountyService(EconomyStore money, BountyStore bounties) {
        this.money = money;
        this.bounties = bounties;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
                list(sender);
                return true;
            }
            String action = args[0].toLowerCase(Locale.ROOT);
            switch (action) {
                case "set", "add", "place" -> set(sender, args);
                case "check" -> check(sender, args);
                default -> set(sender, prepend(args, "set"));
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
                    List.of("set", "list", "check"),
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

    private void set(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            throw new IllegalArgumentException("/bounty is a player command.");
        }
        int nameIndex = args[0].equalsIgnoreCase("set")
                || args[0].equalsIgnoreCase("add")
                || args[0].equalsIgnoreCase("place") ? 1 : 0;
        if (args.length <= nameIndex + 1) {
            throw new IllegalArgumentException("Usage: /bounty set <player> <amount>");
        }
        Player target = Bukkit.getPlayerExact(args[nameIndex]);
        if (target == null) {
            throw new IllegalArgumentException("No player named " + args[nameIndex] + " is online.");
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            throw new IllegalArgumentException("You cannot put a bounty on yourself.");
        }
        long amount = EconomyFormat.parseAmount(args[nameIndex + 1]);
        if (!money.tryWithdraw(player.getUniqueId(), amount)) {
            throw new IllegalArgumentException("You need " + EconomyFormat.dollars(amount) + ".");
        }
        long total = bounties.add(target.getUniqueId(), amount);
        info(player, "Added " + EconomyFormat.dollars(amount) + " to " + target.getName()
                + ". Total: " + EconomyFormat.dollars(total) + ".");
        info(target, player.getName() + " put " + EconomyFormat.dollars(amount)
                + " on your head. Total: " + EconomyFormat.dollars(total) + ".");
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
