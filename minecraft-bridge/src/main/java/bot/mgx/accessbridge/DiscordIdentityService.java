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
