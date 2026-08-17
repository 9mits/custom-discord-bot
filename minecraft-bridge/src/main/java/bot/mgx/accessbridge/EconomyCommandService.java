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
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static bot.mgx.accessbridge.MenuItems.ORANGE;

/** Text money commands: {@code /bal} and {@code /pay}. */
final class EconomyCommandService implements CommandExecutor, TabCompleter {
    private final EconomyStore money;

    EconomyCommandService(EconomyStore money) {
        this.money = money;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        try {
            if (name.equals("pay")) {
                pay(sender, args);
            } else {
                balance(sender, args);
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
                    Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(),
                    new ArrayList<>()
            );
        }
        return List.of();
    }

    private void balance(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                throw new IllegalArgumentException("Usage: /bal <player>");
            }
            info(sender, "Balance: " + EconomyFormat.dollars(money.balance(player.getUniqueId())));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            throw new IllegalArgumentException("No player named " + args[0] + " is online.");
        }
        info(sender, target.getName() + " has " + EconomyFormat.dollars(money.balance(target.getUniqueId())) + ".");
    }

    private void pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            throw new IllegalArgumentException("/pay is a player command.");
        }
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: /pay <player> <amount>");
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            throw new IllegalArgumentException("No player named " + args[0] + " is online.");
        }
        long amount = EconomyFormat.parseAmount(args[1]);
        if (!money.transfer(player.getUniqueId(), target.getUniqueId(), amount)) {
            throw new IllegalArgumentException(
                    "You need " + EconomyFormat.dollars(amount) + "."
            );
        }
        info(player, "Paid " + target.getName() + " " + EconomyFormat.dollars(amount) + ".");
        info(target, player.getName() + " paid you " + EconomyFormat.dollars(amount) + ".");
    }

    private static void info(CommandSender sender, String message) {
        sender.sendMessage(prefix().append(Component.text(message, NamedTextColor.GRAY)));
    }

    private static void error(CommandSender sender, String message) {
        sender.sendMessage(prefix().append(Component.text(message, NamedTextColor.RED)));
    }

    private static Component prefix() {
        return Component.text("ECO » ", ORANGE, TextDecoration.BOLD);
    }
}
