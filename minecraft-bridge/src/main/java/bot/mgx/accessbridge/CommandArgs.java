package bot.mgx.accessbridge;

import java.util.Arrays;
import java.util.Locale;

/**
 * Bedrock's command UI (Geyser) appends the sender's own name to plugin commands
 * that are not Brigadier trees. {@code /shop} arrives as {@code /shop Steve} and
 * every handler that treats the first word as a subcommand then breaks.
 */
final class CommandArgs {
    private CommandArgs() {
    }

    static String[] withoutEchoedSender(String senderName, String[] args) {
        if (args == null || args.length == 0 || senderName == null || senderName.isBlank()) {
            return args == null ? new String[0] : args;
        }
        if (!samePlayer(args[0], senderName)) {
            return args;
        }
        return Arrays.copyOfRange(args, 1, args.length);
    }

    private static boolean samePlayer(String first, String sender) {
        return fold(first).equals(fold(sender));
    }

    private static String fold(String name) {
        String trimmed = name.strip();
        if (trimmed.startsWith(".")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
