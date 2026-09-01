package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One administrative root, with one grammar.
 *
 * <p>Administration had grown into twenty-three subcommands under {@code /mgxadmin} plus
 * a separate {@code /cratehologram}, each with its own argument order and its own verbs
 * for the same idea — {@code remove}, {@code end}, {@code stop}, {@code expire},
 * {@code cancel}, {@code finish} and {@code delete} all ended something, depending on
 * which subsystem you were in. Placing a leaderboard hologram and placing a crate
 * hologram were different commands with different syntax and different permission checks.
 *
 * <p>Everything is now {@code /mgx <area> <thing> <verb>}, from a closed verb set, and
 * the route table below is the single source for dispatch, tab completion and help. A
 * route cannot exist without being completable and documented, because they are read from
 * the same list.
 *
 * <p>Routes delegate to the handlers that already exist rather than reimplementing them.
 * That keeps behaviour identical to the commands people have been using, and means the
 * old spellings can stay working as aliases rather than being reimplemented twice.
 */
final class MgxCommandRouter implements CommandExecutor, TabCompleter {
    /** Who may run a route. Each tier includes the ones before it. */
    enum Tier {
        STAFF("mgx.admin.staff", "Staff"),
        MANAGE("mgx.admin.manage", "Manage"),
        OWNER("mgx.admin.owner", "Owner");

        private final String node;
        private final String label;

        Tier(String node, String label) {
            this.node = node;
            this.label = label;
        }

        String node() {
            return node;
        }

        String label() {
            return label;
        }
    }

    /**
     * One command path.
     *
     * @param path   what the operator types after {@code /mgx}
     * @param legacy the existing {@code /mgxadmin} arguments this stands for, or empty
     *               when the route is handled here
     * @param hints  completion for the arguments that follow the path
     */
    record Route(String path, String summary, Tier tier, String legacy, List<String> hints) {
        String[] words() {
            return path.split(" ");
        }
    }

    private static final TextColor ORANGE = TextColor.color(0xFF9900);

    private static final List<String> ON_OFF = List.of("on", "off");
    private static final List<String> RARITIES =
            List.of("common", "rare", "legendary", "mythic");
    private static final List<String> BOARDS = List.of(
            "wealth", "kills", "amethyst-crates", "amethyst-airdrops",
            "clans-wealth", "clans-kills", "clan-battle",
            "crate:default", "crate:amethyst", "crate:shard"
    );

    /**
     * Every route, in the order help prints them.
     *
     * <p>Verbs come from a closed set — create, list, show, edit, move, delete, enable,
     * disable, start, stop, reload, reset — so a verb learned in one area means the same
     * thing in every other. Where a subsystem previously had its own word for ending
     * something, the old spelling survives as an alias rather than as a second concept.
     */
    private static final List<Route> ROUTES = List.of(
            // ---- world -------------------------------------------------------
            new Route("world hologram create", "place a leaderboard or crate hologram",
                    Tier.MANAGE, "", BOARDS),
            new Route("world hologram list", "every hologram, with its id",
                    Tier.MANAGE, "", List.of()),
            new Route("world hologram delete", "remove one by id, or the nearest",
                    Tier.MANAGE, "", List.of("here")),
            new Route("world hologram reload", "redraw them all", Tier.MANAGE, "", List.of()),
            new Route("world spawn show", "where spawn is", Tier.MANAGE, "", List.of()),

            // ---- event -------------------------------------------------------
            new Route("event list", "every event control", Tier.STAFF, "event list", List.of()),
            new Route("event multiplier enable", "start a multiplier",
                    Tier.STAFF, "multiplier", List.of()),
            new Route("event multiplier disable", "end a multiplier",
                    Tier.STAFF, "multiplier", List.of()),
            new Route("event multiplier show", "what is running",
                    Tier.STAFF, "event multiplier", List.of()),
            new Route("event airdrop start", "call in an Airdrop",
                    Tier.STAFF, "airdrop", RARITIES),
            new Route("event airdrop show", "Airdrops standing now",
                    Tier.STAFF, "event airdrop status", List.of()),
            new Route("event airdrop delete", "end the standing Airdrops",
                    Tier.STAFF, "event airdrop end", List.of()),
            new Route("event airdrop schedule", "how often they arrive",
                    Tier.MANAGE, "event schedule", List.of()),
            new Route("event amethyst start", "place a Huge Amethyst Block",
                    Tier.STAFF, "event amethyst-block start", List.of()),
            new Route("event amethyst show", "the block standing now",
                    Tier.STAFF, "event amethyst-block status", List.of()),
            new Route("event amethyst delete", "remove the standing block",
                    Tier.STAFF, "event amethyst-block end", List.of()),
            new Route("event chaos list", "every admin event",
                    Tier.STAFF, "event admin controls", List.of()),
            new Route("event chaos start", "run an admin event",
                    Tier.STAFF, "abuse", List.of()),
            new Route("event clanbattle start", "open a clan contest",
                    Tier.MANAGE, "clanbattle start", List.of("crates")),
            new Route("event clanbattle show", "standings now",
                    Tier.STAFF, "clanbattle status", List.of()),
            new Route("event clanbattle disable", "close it and pay out",
                    Tier.MANAGE, "clanbattle end", List.of()),
            new Route("event clanbattle delete", "abandon it, paying nobody",
                    Tier.OWNER, "clanbattle cancel", List.of("confirm")),

            // ---- economy -----------------------------------------------------
            new Route("economy balance edit", "add, take or set money",
                    Tier.MANAGE, "eco", List.of("give", "take", "set")),
            new Route("economy joinbonus enable", "pay everyone who joins",
                    Tier.MANAGE, "eco join on", List.of()),
            new Route("economy joinbonus disable", "stop paying joiners",
                    Tier.MANAGE, "eco join off", List.of()),
            new Route("economy bounty create", "put money on a head",
                    Tier.MANAGE, "bounty set", List.of()),
            new Route("economy bounty joinbonus", "bounty everyone who joins",
                    Tier.MANAGE, "bounty join", ON_OFF),

            // ---- player ------------------------------------------------------
            new Route("player give", "hand someone a reward", Tier.STAFF, "give", List.of()),
            new Route("player rank list", "everyone held out of rank sync",
                    Tier.MANAGE, "ranks list", List.of()),
            new Route("player rank hold", "stop rank sync touching someone",
                    Tier.MANAGE, "ranks hold", List.of()),
            new Route("player rank release", "hand them back to rank sync",
                    Tier.MANAGE, "ranks release", List.of()),
            new Route("player cosmetic delete", "delete someone's cosmetics",
                    Tier.OWNER, "cosmetics delete", List.of()),
            new Route("player cosmetic reserial", "renumber one cosmetic",
                    Tier.OWNER, "serials reset", List.of()),

            // ---- server ------------------------------------------------------
            new Route("server pvp show", "is PvP on", Tier.STAFF, "pvp status", List.of()),
            new Route("server pvp enable", "pin PvP on", Tier.MANAGE, "pvp on", List.of()),
            new Route("server pvp disable", "pin PvP off", Tier.MANAGE, "pvp off", List.of()),
            new Route("server launch start", "run the launch countdown",
                    Tier.OWNER, "startserver", List.of()),
            new Route("server update publish", "show everyone the update banner",
                    Tier.MANAGE, "update", List.of()),
            new Route("server reset list", "what a reset can clear",
                    Tier.OWNER, "reset", List.of()),
            new Route("server reset run", "clear recorded progress",
                    Tier.OWNER, "reset", List.of("confirm")),

            // ---- config ------------------------------------------------------
            new Route("config list", "browse every live value",
                    Tier.OWNER, "variables list", List.of()),
            new Route("config show", "one value, with its range",
                    Tier.OWNER, "variables get", List.of()),
            new Route("config set", "change one value",
                    Tier.OWNER, "variables set", List.of()),
            new Route("config reset", "put one value back to its default",
                    Tier.OWNER, "variables reset", List.of()),

            // ---- dev ---------------------------------------------------------
            new Route("dev crate reveal", "run a crate reveal without granting",
                    Tier.OWNER, "testcrate", List.of("legendary", "mythic", "exotic", "secret")),
            new Route("dev airdrop", "the local Airdrop harness",
                    Tier.OWNER, "testairdrop", List.of()),
            new Route("dev amethyst start", "exercise a Huge Amethyst Block",
                    Tier.OWNER, "testamethystblock", List.of()),
            new Route("dev verify reset", "unverify yourself", Tier.OWNER, "testverify reset", List.of()),
            new Route("dev screenshot", "screenshot mode", Tier.OWNER, "devblog", List.of())
    );

    private final MGXAccessBridge plugin;
    private final AdminCommandService legacyAdmin;
    private final HologramDirectory holograms;

    MgxCommandRouter(
            MGXAccessBridge plugin, AdminCommandService legacyAdmin, HologramDirectory holograms
    ) {
        this.plugin = plugin;
        this.legacyAdmin = legacyAdmin;
        this.holograms = holograms;
    }

    static List<Route> routes() {
        return ROUTES;
    }

    /** Who has already been told, so the notice is a nudge rather than nagging. */
    private static final Set<java.util.UUID> TOLD = new java.util.HashSet<>();

    /**
     * Points an old spelling at its replacement, once per session per person.
     *
     * <p>The old commands keep working: muscle memory, command blocks and anything
     * written down still do what they did. This only says where the command moved to.
     */
    static void noteDeprecation(CommandSender sender, String oldForm, String[] args) {
        if (!(sender instanceof Player player) || !TOLD.add(player.getUniqueId())) {
            return;
        }
        String replacement = suggest(oldForm, args);
        sender.sendMessage(Component.text(
                oldForm + " still works, but everything now lives under /mgx.",
                NamedTextColor.GRAY));
        if (!replacement.isEmpty()) {
            sender.sendMessage(Component.text("  Try " + replacement, ORANGE)
                    .append(Component.text("   (/mgx help for the rest)", NamedTextColor.GRAY)));
        }
    }

    /** The new path for an old invocation, found by matching what it delegates to. */
    private static String suggest(String oldForm, String[] args) {
        if (oldForm.startsWith("/cratehologram")) {
            return "/mgx world hologram create crate:<kind>";
        }
        if (args.length == 0) {
            return "/mgx help";
        }
        String head = args[0].toLowerCase(Locale.ROOT);
        for (Route route : ROUTES) {
            if (!route.legacy().isBlank() && route.legacy().split(" ")[0].equals(head)) {
                return "/mgx " + route.path();
            }
        }
        return "/mgx help";
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String[] clean = CommandArgs.withoutEchoedSender(sender.getName(), args);
        if (clean.length == 0 || clean[0].equalsIgnoreCase("help")) {
            sendHelp(sender, clean.length > 1
                    ? String.join(" ", java.util.Arrays.copyOfRange(clean, 1, clean.length))
                    : "");
            return true;
        }
        Route route = longestMatch(clean);
        if (route == null) {
            error(sender, "No such command. Try /mgx help"
                    + (clean.length > 0 ? " " + clean[0] : "") + ".");
            return true;
        }
        if (!allowed(sender, route.tier())) {
            error(sender, "That needs " + route.tier().label() + " access.");
            return true;
        }
        String[] rest = java.util.Arrays.copyOfRange(clean, route.words().length, clean.length);
        try {
            if (route.legacy().isBlank()) {
                handleHere(sender, route, rest);
            } else {
                // Delegated so behaviour matches the command people already know, and so
                // one implementation serves both spellings during the migration.
                legacyAdmin.onCommand(sender, command, "mgxadmin",
                        concat(route.legacy().split(" "), rest));
            }
        } catch (IllegalArgumentException refused) {
            error(sender, refused.getMessage());
        }
        return true;
    }

    /** Routes with no legacy equivalent, because they did not exist before. */
    private void handleHere(CommandSender sender, Route route, String[] rest) {
        switch (route.path()) {
            case "world hologram create" -> {
                requirePlayer(sender);
                if (rest.length == 0) {
                    throw new IllegalArgumentException(
                            "Say which: " + String.join(", ", BOARDS) + ".");
                }
                success(sender, holograms.create((Player) sender, rest[0]));
            }
            case "world hologram list" -> holograms.list().forEach(line -> info(sender, line));
            case "world hologram delete" -> {
                requirePlayer(sender);
                success(sender, holograms.delete((Player) sender,
                        rest.length > 0 ? rest[0] : "here"));
            }
            case "world hologram reload" -> {
                holograms.reload();
                success(sender, "Redrew every hologram.");
            }
            case "world spawn show" -> info(sender, "Spawn is "
                    + plugin.gameVariables().integer("spawn.x") + " "
                    + plugin.gameVariables().integer("spawn.y") + " "
                    + plugin.gameVariables().integer("spawn.z")
                    + ". Change it on the control panel under World.");
            default -> throw new IllegalArgumentException("That command is not wired up.");
        }
    }

    private static void requirePlayer(CommandSender sender) {
        if (!(sender instanceof Player)) {
            throw new IllegalArgumentException("Run that in game, where you are standing.");
        }
    }

    /** The longest route whose words all match, so deeper paths win over shallower ones. */
    private static Route longestMatch(String[] args) {
        Route best = null;
        for (Route route : ROUTES) {
            String[] words = route.words();
            if (words.length > args.length) {
                continue;
            }
            boolean matches = true;
            for (int index = 0; index < words.length; index++) {
                if (!words[index].equalsIgnoreCase(args[index])) {
                    matches = false;
                    break;
                }
            }
            if (matches && (best == null || words.length > best.words().length)) {
                best = route;
            }
        }
        return best;
    }

    /**
     * Whether this sender may run a route.
     *
     * <p>Operators are deliberately not administrators here. The tier node is the only
     * thing consulted, so access is always something somebody granted rather than
     * something inherited — the console keeps full access because it is the server.
     */
    boolean allowed(CommandSender sender, Tier tier) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        for (Tier candidate : Tier.values()) {
            if (candidate.ordinal() >= tier.ordinal()
                    && plugin.hasExplicitPermission(player, candidate.node())) {
                return true;
            }
        }
        // The single legacy node keeps working, so nobody loses access on upgrade.
        return plugin.mayAdminister(sender);
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args
    ) {
        String[] clean = CommandArgs.withoutEchoedSender(sender.getName(), args);
        Set<String> options = new LinkedHashSet<>();
        int depth = clean.length - 1;
        String typed = clean.length == 0 ? "" : clean[depth].toLowerCase(Locale.ROOT);

        for (Route route : ROUTES) {
            if (!allowed(sender, route.tier())) {
                continue;
            }
            String[] words = route.words();
            if (words.length > depth) {
                // Still inside the path: offer the next word, if the ones before match.
                boolean prefixMatches = true;
                for (int index = 0; index < depth; index++) {
                    if (!words[index].equalsIgnoreCase(clean[index])) {
                        prefixMatches = false;
                        break;
                    }
                }
                if (prefixMatches) {
                    options.add(words[depth]);
                }
            } else if (longestMatch(clean) == route) {
                // Past the path: offer whatever the route says its arguments look like.
                options.addAll(route.hints());
            }
        }
        if (depth == 0) {
            options.add("help");
        }
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed))
                .sorted()
                .toList();
    }

    /**
     * Help built from the same list that dispatches, so it cannot describe a command that
     * does not exist or miss one that does.
     */
    private void sendHelp(CommandSender sender, String path) {
        String prefix = path.strip().toLowerCase(Locale.ROOT);
        List<Route> shown = ROUTES.stream()
                .filter(route -> allowed(sender, route.tier()))
                .filter(route -> prefix.isEmpty() || route.path().startsWith(prefix))
                .toList();
        if (shown.isEmpty()) {
            error(sender, "Nothing under /mgx " + prefix + ".");
            return;
        }
        sender.sendMessage(Component.text(
                prefix.isEmpty() ? "Mysterious SMP X administration" : "/mgx " + prefix,
                ORANGE, TextDecoration.BOLD));
        if (prefix.isEmpty()) {
            sender.sendMessage(Component.text(
                    "  Everything is /mgx <area> <thing> <verb>. Try /mgx help world.",
                    NamedTextColor.GRAY));
            Set<String> areas = new LinkedHashSet<>();
            shown.forEach(route -> areas.add(route.words()[0]));
            areas.forEach(area -> {
                long count = shown.stream().filter(r -> r.words()[0].equals(area)).count();
                sender.sendMessage(Component.text("  /mgx " + area, ORANGE)
                        .append(Component.text("  " + count + " command(s)", NamedTextColor.GRAY)));
            });
            return;
        }
        for (Route route : shown) {
            sender.sendMessage(Component.text("  /mgx " + route.path(), ORANGE)
                    .append(Component.text("  " + route.summary(), NamedTextColor.GRAY)));
        }
    }

    private static String[] concat(String[] first, String[] second) {
        List<String> all = new ArrayList<>(List.of(first));
        all.addAll(List.of(second));
        return all.toArray(new String[0]);
    }

    private static void info(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.GRAY));
    }

    private static void success(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.GREEN));
    }

    private static void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
    }
}
