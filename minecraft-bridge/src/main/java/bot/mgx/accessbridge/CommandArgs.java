package bot.mgx.accessbridge;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Bedrock's command UI (Geyser) appends the sender's own name to plugin commands
 * that are not Brigadier trees. {@code /shop} arrives as {@code /shop Steve} and
 * every handler that treats the first word as a subcommand then breaks.
 */
final class CommandArgs {
    /**
     * First tokens that are real subcommands. A player named {@code clan} must still
     * be able to run {@code /bounty clan}.
     */
    private static final Set<String> RESERVED = Set.of(
            "hand", "all",
            "sell", "listings", "listed", "expired", "collect", "mailbox", "search",
            "set", "add", "place", "clan", "list", "check",
            "create", "invite", "accept", "deny", "leave", "kick", "promote", "demote",
            "rename", "color", "colour", "transfer", "disband", "donate", "upgrade",
            "members", "balance", "donors", "info", "menu", "help",
            "startserver", "teststart", "ranks", "eco", "hologram", "holograms", "lb",
            "reset", "hold", "release", "bounty", "join", "everyone", "on", "off",
            "levels", "clans", "commands", "staff",
            "clan_tags", "discord_chat", "discord_names"
    );

    private CommandArgs() {
    }

    static String[] withoutEchoedSender(String senderName, String[] args) {
        if (args == null || args.length == 0 || senderName == null || senderName.isBlank()) {
            return args == null ? new String[0] : args;
        }
        if (!samePlayer(args[0], senderName) || reserved(args[0])) {
            return args;
        }
        return Arrays.copyOfRange(args, 1, args.length);
    }

    private static boolean reserved(String token) {
        return RESERVED.contains(fold(token));
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
