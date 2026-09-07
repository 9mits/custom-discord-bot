package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.UUID;

final class DiscordIdentityService implements CommandExecutor {
    private static final TextColor BLURPLE = TextColor.color(0x5865F2);
    private final MGXAccessBridge plugin;
    private final DiscordIdentityStore store;

    DiscordIdentityService(MGXAccessBridge plugin, DiscordIdentityStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    void sync(UUID minecraftUuid, String discordUsername) {
        store.sync(minecraftUuid, discordUsername);
        plugin.refreshClans();
    }

    /**
     * Forgets a linked name. Called when the bot reports no linked account, which
     * happens after an unlink or a data wipe — the name would otherwise sit in chat
     * and nametags indefinitely with nothing on the Discord side backing it.
     */
    void forget(UUID minecraftUuid) {
        try {
            if (store.clear(minecraftUuid)) {
                plugin.refreshClans();
            }
        } catch (UncheckedIOException exception) {
            plugin.getLogger().warning(
                    "Could not forget a linked Discord name: " + exception.getMessage()
            );
        }
    }

    Optional<String> visibleUsername(UUID minecraftUuid) {
        return store.visibleUsername(minecraftUuid);
    }

    /**
     * Whether two Minecraft accounts belong to the same Discord account.
     *
     * <p>This is how a Java account and a Bedrock account owned by one person are
     * recognised as one person: verification links both to the same Discord user, so
     * both carry the same linked name. Two accounts with no link, or with only one
     * link between them, are treated as two different people — an unlinked account is
     * unknown, not proven separate, but refusing everybody who has not verified would
     * punish the wrong players.
     */
    boolean sameOwner(UUID first, UUID second) {
        if (first.equals(second)) {
            return true;
        }
        Optional<String> one = store.visibleUsername(first);
        Optional<String> two = store.visibleUsername(second);
        return one.isPresent() && two.isPresent() && one.get().equalsIgnoreCase(two.get());
    }

    Component tag(UUID minecraftUuid) {
        return visibleUsername(minecraftUuid)
                .map(username -> Component.text("(@" + username + ") ", BLURPLE))
                .orElse(Component.empty());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is available to players only.");
            return true;
        }
        // Reports rather than toggles. Hiding a linked name made a verified player
        // indistinguishable from an unverified one, so the name is always shown now.
        Optional<String> linked = store.visibleUsername(player.getUniqueId());
        player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━", BLURPLE));
        player.sendMessage(Component.text("DISCORD NAME", BLURPLE, TextDecoration.BOLD));
        if (linked.isPresent()) {
            player.sendMessage(Component.text(
                    "Your linked name (@" + linked.get() + ") is shown beside your Minecraft name.",
                    NamedTextColor.WHITE
            ));
        } else {
            player.sendMessage(Component.text(
                    "No Discord account is linked to this Minecraft account yet.",
                    NamedTextColor.GRAY
            ));
        }
        player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━", BLURPLE));
        return true;
    }
}
