package bot.mgx.accessbridge;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import java.util.UUID;

final class ChatRelayService implements Listener {
    private static final TextColor BLURPLE = TextColor.color(0x5865F2);
    private final BridgeClient bridge;

    ChatRelayService(BridgeClient bridge) {
        this.bridge = bridge;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMinecraftChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (message.isBlank()) {
            return;
        }
        MinecraftEdition edition = MinecraftEdition.JAVA;
        String username = player.getName();
        try {
            FloodgatePlayer floodgatePlayer = FloodgateApi.getInstance().getPlayer(player.getUniqueId());
            if (floodgatePlayer != null) {
                edition = MinecraftEdition.BEDROCK;
                username = floodgatePlayer.getUsername();
            }
        } catch (RuntimeException ignored) {
            // Floodgate may be briefly unavailable while the server shuts down.
        }
        bridge.queueMinecraftChat(
                edition,
                player.getUniqueId(),
                username,
                message.length() > 2_000 ? message.substring(0, 2_000) : message
        );
    }

    void broadcastDiscordChat(
            String discordUsername,
            String minecraftUsername,
            String message,
            int attachmentCount,
            String attachmentUrl
    ) {
        Component rendered = Component.text("◆ DISCORD", BLURPLE, TextDecoration.BOLD)
                .append(Component.text("  (@" + discordUsername + ")", BLURPLE));
        if (minecraftUsername != null && !minecraftUsername.isBlank()) {
            rendered = rendered
                    .append(Component.text(" ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(minecraftUsername, NamedTextColor.WHITE));
        }
        rendered = rendered
                .append(Component.text(": ", NamedTextColor.DARK_GRAY))
                .append(Component.text(message, NamedTextColor.WHITE));
        if (attachmentCount > 0 && attachmentUrl != null && !attachmentUrl.isBlank()) {
            String label = attachmentCount == 1 ? "View attachment on Discord" : "View attachments on Discord";
            rendered = rendered
                    .append(Component.text("  ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("[" + label + "]", BLURPLE, TextDecoration.UNDERLINED)
                            .clickEvent(ClickEvent.openUrl(attachmentUrl))
                            .hoverEvent(HoverEvent.showText(Component.text("Open the Discord message"))));
        }
        Bukkit.broadcast(rendered);
    }
}
