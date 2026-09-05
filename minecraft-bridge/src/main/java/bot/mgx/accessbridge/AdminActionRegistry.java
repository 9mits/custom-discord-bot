package bot.mgx.accessbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The things an owner can <em>do</em>, as opposed to the values they can change.
 *
 * <p>Starting an event, calling in an Airdrop, handing someone a reward: none of these is
 * a setting, so none of them had anywhere to live in a panel built entirely around
 * settings. They existed only as {@code /mgxadmin} subcommands, which means they were
 * reachable only from inside the game.
 *
 * <p>Declared rather than dispatched as text. The panel gets each action's arguments,
 * their types and their options, so it can draw a real form and reject a bad argument
 * before anything is sent — and nothing here can be turned into an arbitrary console
 * command by an unexpected payload.
 */
final class AdminActionRegistry {
    /** One argument an action takes. */
    record Param(
            String name,
            String label,
            String type,
            List<String> choices,
            boolean required,
            String help
    ) {
        static Param choice(String name, String label, List<String> options, String help) {
            return new Param(name, label, "choice", List.copyOf(options), true, help);
        }

        static Param number(String name, String label, String help) {
            return new Param(name, label, "number", List.of(), true, help);
        }

        static Param optionalNumber(String name, String label, String help) {
            return new Param(name, label, "number", List.of(), false, help);
        }

        static Param player(String name, String label, String help) {
            return new Param(name, label, "player", List.of(), true, help);
        }
    }

    /**
     * One action.
     *
     * @param confirm what the panel must have the owner acknowledge first, or empty when
     *                the action is ordinary. Reserved for the ones a mistake is felt by
     *                every player at once.
     */
    record Action(
            String id,
            String label,
            String group,
            String description,
            String confirm,
            List<Param> params
    ) { }

    private final MGXAccessBridge plugin;

    AdminActionRegistry(MGXAccessBridge plugin) {
        this.plugin = plugin;
    }

    private static List<String> clanBattleKinds() {
        return java.util.Arrays.stream(ClanBattleStore.Kind.values())
                .map(ClanBattleStore.Kind::id).toList();
    }

    private static List<String> cosmeticIds() {
        return CosmeticCatalog.visualEntries().stream()
                .map(CosmeticCatalog.Definition::id).sorted().toList();
    }

    private static List<String> eventIds() {
        return java.util.Arrays.stream(ServerEventType.values()).map(ServerEventType::id).toList();
    }

    List<Action> catalogue() {
        return List.of(
                new Action("event.start", "Start a multiplier event", "Events",
                        "Turns on one of the server-wide multipliers. Leave the length"
                                + " empty to run it until you turn it off.",
                        "", List.of(
                        Param.choice("event", "Event", eventIds(), "Which multiplier to run."),
                        Param.optionalNumber("minutes", "Length",
                                "Minutes to run for. Empty means until you stop it.")
                )),
                new Action("event.stop", "Stop a multiplier event", "Events",
                        "Ends a running multiplier immediately.", "", List.of(
                        Param.choice("event", "Event", eventIds(), "Which multiplier to end.")
                )),
                new Action("airdrop.spawn", "Call in an Airdrop", "Events",
                        "Starts an Airdrop the way the scheduler would, announced to"
                                + " everyone. Its rarity and contents are rolled normally.",
                        "", List.of()),
                new Action("amethyst.spawn", "Start a Huge Amethyst Block", "Events",
                        "Places a cooperative Huge Amethyst Block, announced to everyone.",
                        "", List.of()),
                new Action("pvp.set", "Pin PvP on or off", "Server",
                        "Forces PvP either way everywhere, overriding the launch hold."
                                + " Players are told, because it changes whether they can"
                                + " be hit where they stand.",
                        "Every player is told, and this survives a restart.", List.of(
                        Param.choice("state", "PvP", List.of("on", "off"), "")
                )),
                new Action("maintenance.set", "Hold the server closed", "Server",
                        "Closes the server to everyone but operators and explicitly"
                                + " authorised staff. Verification keeps working.",
                        "Anyone online who is not exempt is removed.", List.of(
                        Param.choice("state", "Maintenance", List.of("on", "off"), "")
                )),
                new Action("update.publish", "Announce an update", "Server",
                        "Shows every player the NEW UPDATE banner on their next login.",
                        "", List.of()),
                new Action("clanbattle.start", "Start a Clan Battle", "Events",
                        "Opens a clan competition. Standings show on the leaderboards and"
                                + " the winners are paid when it ends.",
                        "", List.of(
                        Param.choice("kind", "Contest", clanBattleKinds(), "What clans compete on."),
                        Param.optionalNumber("hours", "Length",
                                "Hours to run for. Empty means seven days.")
                )),
                new Action("clanbattle.end", "End the Clan Battle", "Events",
                        "Closes the current battle and pays the winning clans.",
                        "Winners are paid immediately and the battle cannot be reopened.",
                        List.of()),
                new Action("cosmetic.reserial", "Renumber a cosmetic", "Players",
                        "Renumbers every copy of one cosmetic from 1 upwards. Custody and"
                                + " equipped selections are kept; only the serials change.",
                        "Every serial for this cosmetic changes. Tokens sitting in chests"
                                + " show their old number until they are picked up.",
                        List.of(Param.choice("cosmetic", "Cosmetic", cosmeticIds(), ""))),
                new Action("give", "Give something to a player", "Players",
                        "Hands a reward straight to one player.", "", List.of(
                        Param.player("player", "Player", "Who receives it."),
                        Param.choice("what", "What", List.of("money", "key", "shard"), ""),
                        Param.number("amount", "How many", "")
                ))
        );
    }

    /** The catalogue as the console draws it, with who is online for the player fields. */
    JsonObject snapshot() {
        JsonObject root = new JsonObject();
        JsonArray actions = new JsonArray();
        for (Action action : catalogue()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", action.id());
            entry.addProperty("label", action.label());
            entry.addProperty("group", action.group());
            entry.addProperty("description", action.description());
            entry.addProperty("confirm", action.confirm());
            JsonArray params = new JsonArray();
            for (Param param : action.params()) {
                JsonObject row = new JsonObject();
                row.addProperty("name", param.name());
                row.addProperty("label", param.label());
                row.addProperty("type", param.type());
                row.addProperty("required", param.required());
                row.addProperty("help", param.help());
                if (!param.choices().isEmpty()) {
                    JsonArray options = new JsonArray();
                    param.choices().forEach(options::add);
                    row.add("choices", options);
                }
                params.add(row);
            }
            entry.add("params", params);
            actions.add(entry);
        }
        root.add("actions", actions);
        JsonArray online = new JsonArray();
        Bukkit.getOnlinePlayers().forEach(player -> online.add(player.getName()));
        root.add("online", online);
        return root;
    }

    /** Runs one action. Must be called on the main thread. */
    String run(String id, JsonObject arguments) {
        Action action = catalogue().stream()
                .filter(entry -> entry.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown action '" + id + "'."));
        for (Param param : action.params()) {
            if (param.required() && text(arguments, param.name()).isBlank()) {
                throw new IllegalArgumentException(param.label() + " is required.");
            }
        }
        return switch (id) {
            case "event.start" -> setEvent(arguments, true);
            case "event.stop" -> setEvent(arguments, false);
            case "airdrop.spawn" -> spawnScheduled(true);
            case "amethyst.spawn" -> spawnScheduled(false);
            case "pvp.set" -> {
                boolean on = state(arguments);
                plugin.forcePvp(on);
                yield "PvP is pinned " + (on ? "on" : "off") + " everywhere.";
            }
            case "maintenance.set" -> {
                boolean on = state(arguments);
                plugin.setMaintenance(on);
                yield on
                        ? "The server is closed to everyone but operators and authorised staff."
                        : "The server is open again.";
            }
            case "update.publish" -> {
                plugin.updateNotices().publish();
                yield "Every player will see the update banner on their next login.";
            }
            case "clanbattle.start" -> {
                ClanBattleStore.Kind kind = ClanBattleStore.Kind.from(text(arguments, "kind"))
                        .orElseThrow(() -> new IllegalArgumentException("Unknown contest."));
                long hours = number(arguments, "hours", 24 * 7);
                if (hours < 1 || hours > 24 * 90) {
                    throw new IllegalArgumentException("Length must be between 1 hour and 90 days.");
                }
                ClanBattleStore.ActiveView active = plugin.clanBattles().startBattle(
                        kind, System.currentTimeMillis() + hours * 3_600_000L);
                yield "Started " + active.kind().displayName() + ", ending in "
                        + ClanBattleCountdown.remaining(
                                active.endsAt() - System.currentTimeMillis()) + ".";
            }
            case "clanbattle.end" -> {
                ClanBattleStore.CompletedView completed = plugin.clanBattles().endBattle();
                yield "Ended " + completed.kind().displayName() + " and paid "
                        + completed.winners().size() + " winning placement(s).";
            }
            case "cosmetic.reserial" -> {
                String cosmetic = text(arguments, "cosmetic");
                int renumbered = plugin.cosmetics().resetSerials(cosmetic);
                yield renumbered == 0
                        ? "Nobody owns " + cosmetic + ", so there was nothing to renumber."
                        : "Renumbered " + renumbered + " copy/copies of " + cosmetic + " from 1.";
            }
            case "give" -> give(arguments);
            default -> throw new IllegalArgumentException("Unknown action '" + id + "'.");
        };
    }

    private String setEvent(JsonObject arguments, boolean enabled) {
        ServerEventService events = plugin.serverEvents();
        if (events == null) {
            throw new IllegalArgumentException("Server events are not available right now.");
        }
        ServerEventType type = ServerEventType.resolve(text(arguments, "event")).orElseThrow(
                () -> new IllegalArgumentException("Unknown event.")
        );
        long seconds = 0L;
        if (enabled && !text(arguments, "minutes").isBlank()) {
            long minutes = number(arguments, "minutes", 0);
            if (minutes < 1 || minutes > 20_160) {
                throw new IllegalArgumentException("Length must be between 1 minute and 14 days.");
            }
            seconds = minutes * 60L;
        }
        int factor = plugin.serverEventStore().factor(type);
        if (!events.set(type, enabled, seconds)) {
            return type.displayName(factor) + " was already "
                    + (enabled ? "running" : "off") + ".";
        }
        if (!enabled) {
            return type.displayName(factor) + " has ended.";
        }
        return type.displayName(factor) + " is live"
                + (seconds > 0
                    ? " for " + ServerEventService.humanDuration(seconds * 1_000L)
                    : " until you stop it") + ".";
    }

    /**
     * Starts an event the way the scheduler does, rather than near an operator.
     *
     * <p>The in-game command puts an Airdrop where the person running it is standing.
     * There is nobody standing anywhere when the panel asks, so this takes the path the
     * scheduler already uses, which picks its own site and announces it.
     */
    private String spawnScheduled(boolean airdrop) {
        AmethystEventCoordinator coordinator = plugin.amethystEvents();
        if (coordinator == null) {
            throw new IllegalArgumentException("World events are not available right now.");
        }
        if (!coordinator.startNow(airdrop
                ? AmethystEventCoordinator.Kind.AIRDROP
                : AmethystEventCoordinator.Kind.HUGE_BLOCK)) {
            throw new IllegalArgumentException(
                    "Another world event is already standing. End it first."
            );
        }
        return airdrop
                ? "An Airdrop is on its way; the server has been told where."
                : "A Huge Amethyst Block is on its way.";
    }

    private String give(JsonObject arguments) {
        String name = text(arguments, "player");
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            throw new IllegalArgumentException(name + " is not online.");
        }
        int amount = (int) number(arguments, "amount", 1);
        if (amount < 1 || amount > 1_000_000) {
            throw new IllegalArgumentException("Give between 1 and 1,000,000.");
        }
        String what = text(arguments, "what").toLowerCase(Locale.ROOT);
        return switch (what) {
            case "money" -> {
                plugin.economy().deposit(player.getUniqueId(), amount);
                yield "Gave " + player.getName() + " " + EconomyFormat.dollars(amount) + ".";
            }
            case "key" -> {
                plugin.crateItems().giveKeysOrDrop(player, amount);
                yield "Gave " + player.getName() + " " + amount + " key(s).";
            }
            case "shard" -> {
                hand(player, plugin.crateItems().shard(amount));
                yield "Gave " + player.getName() + " " + amount + " Shard(s).";
            }
            default -> throw new IllegalArgumentException("Give money, key or shard.");
        };
    }

    /** Into the inventory, or onto the floor beside them when it is full. */
    private static void hand(Player player, org.bukkit.inventory.ItemStack stack) {
        player.getInventory().addItem(stack).values().forEach(overflow ->
                player.getWorld().dropItemNaturally(player.getLocation(), overflow));
    }

    private static boolean state(JsonObject arguments) {
        return switch (text(arguments, "state").toLowerCase(Locale.ROOT)) {
            case "on", "true", "enable" -> true;
            case "off", "false", "disable" -> false;
            default -> throw new IllegalArgumentException("Use on or off.");
        };
    }

    private static String text(JsonObject arguments, String name) {
        return arguments.has(name) && arguments.get(name).isJsonPrimitive()
                ? arguments.get(name).getAsString().strip() : "";
    }

    private static long number(JsonObject arguments, String name, long fallback) {
        String raw = text(arguments, name);
        if (raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.replace(",", ""));
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(name + " must be a whole number.");
        }
    }

    /** Action ids, for the tests that hold the console and the plugin together. */
    List<String> ids() {
        List<String> ids = new ArrayList<>();
        catalogue().forEach(action -> ids.add(action.id()));
        return ids;
    }
}
