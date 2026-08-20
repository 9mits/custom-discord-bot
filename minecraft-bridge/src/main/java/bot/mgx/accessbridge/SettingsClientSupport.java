package bot.mgx.accessbridge;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.geysermc.floodgate.api.FloodgateApi;

import java.lang.reflect.Method;
import java.util.UUID;

/** Chooses the native dialog only when the connecting client can decode it. */
final class SettingsClientSupport {
    /** Minecraft Java 1.21.6, the first protocol with custom dialogs. */
    static final int FIRST_DIALOG_PROTOCOL = 771;

    boolean supportsDialogs(Player player) {
        boolean bedrock = isBedrock(player.getUniqueId());
        Plugin viaVersion = Bukkit.getPluginManager().getPlugin("ViaVersion");
        boolean viaActive = viaVersion != null && viaVersion.isEnabled();
        Integer viaProtocol = viaActive ? viaProtocol(viaVersion, player.getUniqueId()) : null;
        return supportsDialogsFor(
                bedrock,
                viaActive,
                viaProtocol,
                player.getProtocolVersion()
        );
    }

    static boolean supportsDialogsFor(
            boolean bedrock,
            boolean viaActive,
            Integer viaProtocol,
            int directProtocol
    ) {
        if (bedrock) {
            return false;
        }
        int clientProtocol = viaActive
                ? (viaProtocol == null ? -1 : viaProtocol)
                : directProtocol;
        return clientProtocol >= FIRST_DIALOG_PROTOCOL;
    }

    private static boolean isBedrock(UUID playerId) {
        try {
            return FloodgateApi.getInstance().getPlayer(playerId) != null;
        } catch (RuntimeException | LinkageError ignored) {
            // Floodgate can be briefly unavailable during shutdown. Treating an
            // unknown client conservatively keeps the command usable.
            return true;
        }
    }

    private static Integer viaProtocol(Plugin viaVersion, UUID playerId) {
        try {
            ClassLoader loader = viaVersion.getClass().getClassLoader();
            Class<?> via = Class.forName("com.viaversion.viaversion.api.Via", true, loader);
            Object api = via.getMethod("getAPI").invoke(null);
            Method getPlayerVersion = api.getClass().getMethod("getPlayerVersion", UUID.class);
            Object value = getPlayerVersion.invoke(api, playerId);
            return value instanceof Number number ? number.intValue() : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Via is installed but its original-client version could not be read. A
            // chest menu is safer than sending a packet the client may not know.
            return null;
        }
    }
}
