package bot.mgx.accessbridge;

import java.util.Locale;
import java.util.Set;

/**
 * Grades commands by how much damage they can do, whoever runs them.
 *
 * <p>Free of Bukkit so the table is unit tested. Nothing here exempts an operator, a
 * LuckPerms owner or the console: abuse by somebody with the permission is exactly what
 * a permission check cannot see, so the only question asked is what the command does.
 */
final class SentinelCommands {
    record Grade(SentinelEngine.Severity severity, String category, String reason) { }

    private static final Set<String> OP = Set.of("op", "deop");
    private static final Set<String> PERMISSIONS = Set.of("lp", "luckperms", "perm", "perms",
            "permission", "permissions", "lpb", "lpv", "pex");
    private static final Set<String> ITEMS = Set.of("give", "i", "item", "itemnbt", "give2");
    private static final Set<String> GAMEMODE = Set.of("gamemode", "gm", "gmc", "gms", "gma", "gmsp",
            "creative", "survival", "spectator", "adventure");
    private static final Set<String> ECONOMY = Set.of("eco", "economy", "money", "addmoney",
            "setmoney", "takemoney", "setbal", "setbalance");
    private static final Set<String> WORLD_EDIT_DATA = Set.of("data", "summon", "setblock", "fill",
            "clone", "execute", "attribute", "item_replace", "loot", "place", "function");
    private static final Set<String> INSPECT = Set.of("invsee", "openinv", "enderchest", "ec",
            "endersee", "echest", "inventorysee", "ecsee");
    private static final Set<String> POWERS = Set.of("vanish", "v", "god", "fly", "sudo", "speed",
            "heal", "feed", "repair", "fix", "enchant", "unbreakable", "hat", "more", "xp",
            "experience", "exp", "effect", "kill", "clear", "ci", "clearinventory", "invclear");
    private static final Set<String> SERVER = Set.of("stop", "restart", "reload", "rl",
            "plugman", "plugins", "pl", "timings", "spark");
    private static final Set<String> MODERATION = Set.of("ban", "tempban", "ipban", "banip",
            "pardon", "unban", "kick", "mute", "unmute", "whitelist", "warn");
    private static final Set<String> TELEPORT = Set.of("tp", "teleport", "tphere", "tpo", "tpall",
            "tpohere", "s", "tppos");
    private static final Set<String> MGX_DANGEROUS = Set.of("give", "reset", "wipe", "serials",
            "money", "economy", "crate", "cosmetic", "cosmetics", "set", "variables", "config",
            "sentinel", "keys", "shards", "odds", "catalog", "maintenance", "startserver");

    private SentinelCommands() {
    }

    /**
     * The grade for one command line, or null for everyday commands.
     *
     * @param line the full line, with or without its leading slash
     */
    static Grade grade(String line) {
        if (line == null) return null;
        String trimmed = line.strip();
        boolean worldEdit = trimmed.startsWith("//");
        while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        if (trimmed.isEmpty()) return null;
        String[] parts = trimmed.toLowerCase(Locale.ROOT).split("\\s+");
        String label = parts[0];
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        String rest = trimmed.length() > parts[0].length() ? trimmed.substring(parts[0].length()).strip() : "";
        String lowerRest = rest.toLowerCase(Locale.ROOT);
        String second = parts.length > 1 ? parts[1] : "";

        if (worldEdit) {
            return new Grade(SentinelEngine.Severity.MEDIUM, "WorldEdit", "WorldEdit operation");
        }
        if (OP.contains(label)) {
            return new Grade(SentinelEngine.Severity.CRITICAL, "Operator",
                    label.equals("op") ? "Granting operator" : "Removing operator");
        }
        if (PERMISSIONS.contains(label)) {
            boolean grant = lowerRest.contains(" set") || lowerRest.contains(" add")
                    || lowerRest.contains("parent") || lowerRest.contains("promote")
                    || lowerRest.contains("import") || lowerRest.contains("bulkupdate");
            boolean wildcard = lowerRest.contains(" * ") || lowerRest.endsWith(" *")
                    || lowerRest.contains("*.") || lowerRest.contains("owner") || lowerRest.contains("admin");
            SentinelEngine.Severity severity = grant && wildcard ? SentinelEngine.Severity.CRITICAL
                    : grant ? SentinelEngine.Severity.HIGH : SentinelEngine.Severity.LOW;
            return new Grade(severity, "Permissions", grant ? "Changing permissions" : "Reading permissions");
        }
        if (ITEMS.contains(label)) {
            boolean custom = lowerRest.contains("custom_data") || lowerRest.contains("trial_key")
                    || lowerRest.contains("amethyst_shard") || lowerRest.contains("mgxaccessbridge")
                    || lowerRest.contains("enchantments") || lowerRest.contains("attribute_modifiers");
            return new Grade(custom ? SentinelEngine.Severity.CRITICAL : SentinelEngine.Severity.HIGH,
                    "Items", custom ? "Spawning a custom or modified item" : "Spawning items");
        }
        if (GAMEMODE.contains(label)) {
            String mode = switch (label) {
                case "gmc", "creative" -> "creative";
                case "gmsp", "spectator" -> "spectator";
                case "gms", "survival" -> "survival";
                case "gma", "adventure" -> "adventure";
                default -> second;
            };
            boolean powerful = mode.startsWith("c") || mode.equals("1") || mode.startsWith("sp") || mode.equals("3");
            return new Grade(powerful ? SentinelEngine.Severity.HIGH : SentinelEngine.Severity.LOW,
                    "Game mode", "Switching to " + (mode.isBlank() ? "another game mode" : mode));
        }
        if (ECONOMY.contains(label)) {
            return new Grade(SentinelEngine.Severity.HIGH, "Economy", "Editing balances directly");
        }
        if (label.equals("mgxadmin") || label.equals("mgx") || label.equals("mcadmin")) {
            boolean dangerous = MGX_DANGEROUS.contains(second);
            return new Grade(dangerous ? SentinelEngine.Severity.HIGH : SentinelEngine.Severity.LOW,
                    "MGX admin", dangerous ? "Running /" + label + " " + second : "Server administration");
        }
        if (WORLD_EDIT_DATA.contains(label)) {
            return new Grade(SentinelEngine.Severity.HIGH, "World data", "Editing world or entity data");
        }
        if (SERVER.contains(label)) {
            SentinelEngine.Severity severity = label.equals("plugins") || label.equals("pl")
                    || label.equals("timings") || label.equals("spark")
                    ? SentinelEngine.Severity.LOW : SentinelEngine.Severity.HIGH;
            return new Grade(severity, "Server", "Server control");
        }
        if (INSPECT.contains(label)) {
            return new Grade(second.isBlank() ? SentinelEngine.Severity.LOW : SentinelEngine.Severity.MEDIUM,
                    "Inspection", "Opening another inventory");
        }
        if (POWERS.contains(label)) {
            return new Grade(SentinelEngine.Severity.MEDIUM, "Staff powers", "Using /" + label);
        }
        if (MODERATION.contains(label)) {
            return new Grade(SentinelEngine.Severity.LOW, "Moderation", "Moderation");
        }
        if (TELEPORT.contains(label)) {
            return new Grade(SentinelEngine.Severity.LOW, "Teleport", "Staff teleport");
        }
        return null;
    }
}
