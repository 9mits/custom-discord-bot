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
        try {
            DiscordIdentityStore.Identity identity = store.toggle(player.getUniqueId());
            plugin.refreshClans();
            player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━", BLURPLE));
            player.sendMessage(Component.text("DISCORD NAME", BLURPLE, TextDecoration.BOLD));
            if (identity.visible()) {
                player.sendMessage(Component.text(
                        "Your linked name (@" + identity.username() + ") is now visible beside your Minecraft name.",
                        NamedTextColor.WHITE
                ));
            } else {
                player.sendMessage(Component.text(
                        "Your linked Discord name is now hidden from Minecraft chat, nametags, and the player list.",
                        NamedTextColor.GRAY
                ));
            }
            player.sendMessage(Component.text("Use /discordnames again to change this setting.", NamedTextColor.DARK_GRAY));
            player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━", BLURPLE));
        } catch (IllegalStateException exception) {
            player.sendMessage(Component.text("DISCORD NAME » ", BLURPLE, TextDecoration.BOLD)
                    .append(Component.text(exception.getMessage(), NamedTextColor.RED)));
        } catch (UncheckedIOException exception) {
            plugin.getLogger().warning("Could not save a Discord-name preference: " + exception.getMessage());
            player.sendMessage(Component.text("DISCORD NAME » ", BLURPLE, TextDecoration.BOLD)
                    .append(Component.text("Your setting could not be saved. Please try again.", NamedTextColor.RED)));
        }
        return true;
    }
}
