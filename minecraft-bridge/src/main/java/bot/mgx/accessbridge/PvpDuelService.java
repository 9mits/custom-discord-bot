package bot.mgx.accessbridge;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Player-arranged PvP in a temporary, untouched part of the real overworld.
 *
 * <p>Nothing here creates a kit or a world arena. Both players bring their actual
 * inventory, accept the same terms, and return to the exact place and condition they
 * left. Death keeps inventory and levels. Only the agreed cash, items and cosmetics
 * move to the winner; ordinary bounty and equipped-cosmetic death transfers are
 * deliberately bypassed by the services that own those systems.
 *
 * <p>Spectators use an anchored Adventure-mode viewing point. Their inventory is
 * stashed to disk, they cannot interact or relay chat, and they never receive free-roam
 * spectator mode. Fighter and spectator recoveries are disk-backed so a Paper crash is
 * resolved as a draw on the next join.
 */
final class PvpDuelService implements CommandExecutor, TabCompleter, Listener {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private static final int BOARD_SIZE = 54;
    private static final int PAGE_SIZE = 45;
    private static final int HUB_START = 11;
    private static final int HUB_INCOMING = 13;
    private static final int HUB_LIVE = 15;
    // Kept off the middle of the bottom row, which every board gives to Back. A
    // Send Challenge tile written to slot 22 of a 27-slot board was drawn over by
    // Back and could never be pressed, so a challenge could not be sent at all.
    static final int SETUP_MONEY_SLOT = 11;
    static final int SETUP_ITEMS_SLOT = 13;
    static final int SETUP_COSMETICS_SLOT = 15;
    static final int SETUP_CLEAR_SLOT = 21;
    static final int SETUP_SEND_SLOT = 23;
    static final int ACCEPT_OFFER_SLOT = 10;
    static final int ACCEPT_MONEY_SLOT = 12;
    static final int ACCEPT_ITEMS_SLOT = 14;
    static final int ACCEPT_COSMETICS_SLOT = 16;
    static final int ACCEPT_DECLINE_SLOT = 21;
    static final int ACCEPT_CONFIRM_SLOT = 23;
    static final int SETUP_BOARD_SIZE = 27;
    private static final String MONEY_INPUT = "money";
    private static final long NOTICE_COOLDOWN_MILLIS = 2_000L;
    private static final Set<Material> DANGEROUS = Set.of(
            Material.WATER, Material.LAVA, Material.CACTUS, Material.MAGMA_BLOCK,
            Material.POWDER_SNOW, Material.FIRE, Material.SOUL_FIRE,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH
    );

    private enum Phase { COUNTDOWN, FIGHTING, ENDING }
    private enum Board {
        HUB, TARGETS, SETUP, SETUP_ITEMS, SETUP_COSMETICS,
        INCOMING, ACCEPT, ACCEPT_ITEMS, ACCEPT_COSMETICS, LIVE
    }
    private enum Prompt { MONEY }

    private record Invitation(
            UUID id,
            UUID challenger,
            UUID target,
            long moneyEach,
            List<ItemStack> challengerItems,
            List<UUID> challengerCosmetics,
            long expiresAt
    ) {
        Invitation {
            challengerItems = cloneItems(challengerItems);
            challengerCosmetics = List.copyOf(challengerCosmetics);
        }
    }

    private record AcceptedTerms(
            List<ItemStack> challengerItems,
            List<ItemStack> targetItems,
            List<UUID> challengerCosmetics,
            List<UUID> targetCosmetics
    ) {
        AcceptedTerms {
            challengerItems = cloneItems(challengerItems);
            targetItems = cloneItems(targetItems);
            challengerCosmetics = List.copyOf(challengerCosmetics);
            targetCosmetics = List.copyOf(targetCosmetics);
        }
    }

    private record Arena(Location center, Location first, Location second, double diameter) {
    }

    private record PlayerState(PvpDuelStore.Recovery recovery, WorldBorder previousBorder) {
    }

    private static final class Fight {
        final UUID id;
        final UUID first;
        final UUID second;
        final String firstName;
        final String secondName;
        final long moneyEach;
        final Arena arena;
        final Map<UUID, PlayerState> states;
        final Set<UUID> spectators = new HashSet<>();
        Phase phase = Phase.COUNTDOWN;
        BukkitTask countdownTask;
        BukkitTask timeoutTask;

        Fight(
                UUID id,
                Player first,
                Player second,
                long moneyEach,
                Arena arena,
                Map<UUID, PlayerState> states
        ) {
            this.id = id;
            this.first = first.getUniqueId();
            this.second = second.getUniqueId();
            this.firstName = first.getName();
            this.secondName = second.getName();
            this.moneyEach = moneyEach;
            this.arena = arena;
            this.states = states;
        }

        UUID opponent(UUID playerId) {
            return first.equals(playerId) ? second : first;
        }

        String label() {
            return firstName + " vs " + secondName;
        }
    }

    private record SpectatorState(Fight fight, PlayerState player, Location anchor) {
    }

    private static final class DuelDraft {
        final UUID subject;
        long money;
        List<ItemStack> items = new ArrayList<>();
        final Set<UUID> cosmetics = new HashSet<>();

        DuelDraft(UUID subject) {
            this.subject = subject;
        }
    }

    private static final class DuelBoard implements InventoryHolder {
        final Board board;
        final UUID subject;
        final Map<Integer, UUID> choices = new HashMap<>();
        boolean depositsReturned;
        Inventory inventory;

        DuelBoard(Board board, UUID subject) {
            this.board = board;
            this.subject = subject;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final MGXAccessBridge plugin;
    private final EconomyStore economy;
    private final PlayerSettingsStore settings;
    private final SettingsClientSupport clientSupport;
    private final BedrockForms forms;
    private final CosmeticStore cosmetics;
    private final CosmeticItems cosmeticItems;
    private WardrobeService wardrobe;
    private final PvpDuelStore store;
    private final Map<UUID, Invitation> invitations = new LinkedHashMap<>();
    private final Map<UUID, Fight> fights = new LinkedHashMap<>();
    private final Map<UUID, Fight> fighting = new HashMap<>();
    private final Map<UUID, SpectatorState> spectators = new HashMap<>();
    private final Map<UUID, PlayerState> pendingDeathRestore = new HashMap<>();
    private final Set<UUID> starting = new HashSet<>();
    private final Set<UUID> internalTeleports = new HashSet<>();
    private final Map<UUID, DuelDraft> setupDrafts = new HashMap<>();
    private final Map<UUID, DuelDraft> acceptDrafts = new HashMap<>();
    private final Map<UUID, Prompt> prompts = new HashMap<>();
    private final Map<UUID, Long> notices = new HashMap<>();
    private final Map<UUID, Long> lastChallenges = new HashMap<>();
    private final org.bukkit.NamespacedKey victimKey;

    PvpDuelService(
            MGXAccessBridge plugin,
            EconomyStore economy,
            PlayerSettingsStore settings,
            SettingsClientSupport clientSupport,
            BedrockForms forms,
            CosmeticStore cosmetics,
            CosmeticItems cosmeticItems,
            java.nio.file.Path recoveryFile
    ) throws IOException {
        this.plugin = plugin;
        this.economy = economy;
        this.settings = settings;
        this.clientSupport = clientSupport;
        this.forms = forms;
        this.cosmetics = cosmetics;
        this.cosmeticItems = cosmeticItems;
        this.store = new PvpDuelStore(recoveryFile);
        this.victimKey = new org.bukkit.NamespacedKey(plugin, "trophy_victim");
        // Money can be repaired while its owner is offline. Items and locations wait
        // for join, but raising to the pre-duel value is idempotent and never removes
        // anything the player earned before recovery ran.
        for (Map.Entry<UUID, PvpDuelStore.Recovery> entry : store.all().entrySet()) {
            long before = entry.getValue().balanceBefore();
            if (economy.balance(entry.getKey()) < before) {
                economy.set(entry.getKey(), before);
            }
        }
    }

    private boolean enabled() {
        return plugin.gameVariables().bool("pvp-duels.enabled");
    }

    private int inviteSeconds() {
        return plugin.gameVariables().integer("pvp-duels.invite-seconds");
    }

    private int countdownSeconds() {
        return plugin.gameVariables().integer("pvp-duels.countdown-seconds");
    }

    private int durationMinutes() {
        return plugin.gameVariables().integer("pvp-duels.duration-minutes");
    }

    private int arenaDiameter() {
        return plugin.gameVariables().integer("pvp-duels.arena-diameter");
    }

    private int challengeCooldownSeconds() {
        return plugin.gameVariables().integer("pvp-duels.challenge-cooldown-seconds");
    }

    boolean isFighter(UUID playerId) {
        return fighting.containsKey(playerId);
    }

    boolean isParticipant(UUID playerId) {
        return isFighter(playerId) || spectators.containsKey(playerId);
    }

    void useWardrobe(WardrobeService wardrobe) {
        this.wardrobe = wardrobe;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        args = CommandArgs.withoutEchoedSender(sender.getName(), args);
        if (!(sender instanceof Player player)) {
            sender.sendMessage("/pvp is available to players only.");
            return true;
        }
        if (store.find(player.getUniqueId()).isPresent() && !isParticipant(player.getUniqueId())) {
            recover(player, true);
            return true;
        }
        if (isFighter(player.getUniqueId())) {
            if (args.length > 0 && givingUp(args[0])) {
                forfeit(player);
            } else {
                openFightStatus(player, fighting.get(player.getUniqueId()));
            }
            return true;
        }
        if (spectators.containsKey(player.getUniqueId())) {
            if (args.length == 0 || args[0].equalsIgnoreCase("leave")) {
                leaveSpectator(player, true);
            } else {
                error(player, "Use /pvp leave to return to where you were.");
            }
            return true;
        }
        if (args.length == 0) {
            openHub(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "accept" -> acceptByName(player, args.length >= 2 ? args[1] : "");
            case "decline", "deny" -> declineByName(player, args.length >= 2 ? args[1] : "");
            case "spectate", "watch" -> {
                if (args.length >= 2) {
                    spectateByName(player, args[1]);
                } else {
                    openLive(player);
                }
            }
            case "challenge", "fight" -> {
                Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null) {
                    error(player, "Choose an online player from /pvp.");
                } else {
                    openSetup(player, target);
                }
            }
            case "leave" -> error(player, "You are not watching a fight.");
            case "forfeit", "surrender", "giveup", "ff" ->
                    error(player, "You are not in a fight, so there is nothing to give up.");
            default -> {
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null) {
                    error(player, "Use /pvp and choose an online player.");
                } else {
                    openSetup(player, target);
                }
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args
    ) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("challenge", "accept", "decline", "spectate", "leave",
                            "forfeit", "giveup")
                    .stream().filter(option -> option.startsWith(prefix)).toList();
        }
        if (args.length == 2 && List.of("challenge", "accept", "decline", "spectate")
                .contains(args[0].toLowerCase(Locale.ROOT))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
        return List.of();
    }

    private void openHub(Player player) {
        expireInvitations();
        int incoming = incoming(player.getUniqueId()).size();
        if (!clientSupport.supportsDialogs(player)) {
            List<BedrockForms.Button> buttons = List.of(
                    new BedrockForms.Button("Start a Fight", () -> openTargets(player)),
                    new BedrockForms.Button("Incoming (" + incoming + ")", () -> openIncoming(player)),
                    new BedrockForms.Button("Watch Live Fights (" + fights.size() + ")", () -> openLive(player))
            );
            if (!forms.menu(player, "PvP", hubBody(), buttons)) {
                openChestHub(player);
            }
            return;
        }
        List<ActionButton> buttons = List.of(
                Screens.button("item/diamond_sword", "Start a Fight",
                        "Choose a player.", this::openTargets),
                Screens.button("item/writable_book", "Incoming (" + incoming + ")",
                        "View your challenges.", this::openIncoming),
                Screens.button("item/spyglass", "Watch Live Fights (" + fights.size() + ")",
                        "Watch a fight.", this::openLive)
        );
        Screens.show(player, "PvP", Screens.body(hubBody()), buttons, 1, null);
    }

    private String hubBody() {
        return "Fight anywhere. KEEP INVENTORY is always on.";
    }

    private void openTargets(Player player) {
        List<Player> targets = targets(player);
        if (!clientSupport.supportsDialogs(player)) {
            List<BedrockForms.Button> buttons = targets.stream()
                    .map(target -> new BedrockForms.Button(target.getName(),
                            () -> openSetup(player, target)))
                    .toList();
            if (!forms.menu(player, "Choose a Player", "Who do you want to fight?",
                    buttons, this::openHub)) {
                openChestList(player, Board.TARGETS, targets, "Choose a Player");
            }
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (Player target : targets) {
            buttons.add(Screens.button(null, target.getName(),
                    "Challenge this player.", viewer -> {
                        Player current = Bukkit.getPlayer(target.getUniqueId());
                        if (current == null) {
                            error(viewer, "They went offline.");
                        } else {
                            openSetup(viewer, current);
                        }
                    }));
        }
        Screens.show(player, "Choose a Player",
                Screens.body(targets.isEmpty()
                        ? "Nobody available is online right now."
                        : "Who do you want to fight?"),
                buttons, 2, this::openHub);
    }

    private void openSetup(Player challenger, Player target) {
        if (!canChallenge(challenger, target, true)
                || !acceptingChallenges(challenger, target, true)) {
            return;
        }
        DuelDraft draft = setupDrafts.get(challenger.getUniqueId());
        if (draft == null || !draft.subject.equals(target.getUniqueId())) {
            draft = new DuelDraft(target.getUniqueId());
            setupDrafts.put(challenger.getUniqueId(), draft);
        }
        openSetupScreen(challenger, target, draft);
    }

    /**
     * The challenge screen: cash is a field on it, and only items and cosmetics
     * open a chest.
     *
     * <p>Money is a number, not a stack, so making the player leave the menu and type
     * it into chat was asking a container to do a text box's job. A dialog has the box,
     * and reads it back on whichever button the player presses — including the two that
     * navigate away — so an amount typed before "Items" is still staked afterwards.
     * Bedrock cannot draw a dialog and gets the same field as its own form; only a
     * client with neither falls back to the chest and the chat prompt.
     */
    private void openSetupScreen(Player player, Player target, DuelDraft draft) {
        openSetupScreen(player, target, draft, null);
    }

    private void openSetupScreen(
            Player player, Player target, DuelDraft draft, String problem
    ) {
        UUID targetId = target.getUniqueId();
        String body = setupBody(player, draft, problem);
        if (!clientSupport.supportsDialogs(player)) {
            List<BedrockForms.Button> buttons = List.of(
                    new BedrockForms.Button("Money: " + EconomyFormat.dollars(draft.money),
                            () -> openMoneyPrompt(player, draft)),
                    new BedrockForms.Button("Items: " + itemCount(draft.items),
                            () -> openItemWager(player, draft, false)),
                    new BedrockForms.Button("Cosmetics: " + draft.cosmetics.size(),
                            () -> openCosmeticWager(player, draft, false)),
                    new BedrockForms.Button("Clear Wager", () -> {
                        clearWager(draft);
                        reopenSetup(player, targetId, null);
                    }),
                    new BedrockForms.Button("Send Challenge", () -> reopenSetup(
                            player, targetId, sendChallenge(player, targetId, draft)))
            );
            if (!forms.menu(player, "Fight " + target.getName(), body, buttons,
                    this::openTargets)) {
                openChestSetup(player, target, draft);
            }
            return;
        }
        List<ActionButton> buttons = List.of(
                Screens.button("item/shulker_shell", "Items: " + itemCount(draft.items),
                        "Stake real items. Opens a chest to put them in.",
                        (response, viewer) -> withTypedMoney(viewer, draft, response,
                                () -> openItemWager(viewer, draft, false))),
                Screens.button("item/nether_star", "Cosmetics: " + draft.cosmetics.size(),
                        "Stake cosmetics from your wardrobe.",
                        (response, viewer) -> withTypedMoney(viewer, draft, response,
                                () -> openCosmeticWager(viewer, draft, false))),
                Screens.button("item/barrier", "Clear Wager", "Remove every stake.",
                        (response, viewer) -> {
                            clearWager(draft);
                            reopenSetup(viewer, targetId, null);
                        }),
                Screens.button("item/diamond_sword", "Send Challenge",
                        "They see your stake and answer with their own.",
                        (response, viewer) -> withTypedMoney(viewer, draft, response,
                                () -> reopenSetup(viewer, targetId,
                                        sendChallenge(viewer, targetId, draft))))
        );
        Screens.show(player, "Fight " + target.getName(), Screens.body(body),
                List.of(DialogInput.text(MONEY_INPUT, Component.text(
                                "Money each  (0, 500, 2.5k, 1.4m)", MenuText.LABEL))
                        .initial(draft.money <= 0L ? "" : String.valueOf(draft.money))
                        .maxLength(20)
                        .build()),
                buttons, 2, this::openTargets);
    }

    private String setupBody(Player player, DuelDraft draft, String problem) {
        return (problem == null ? "" : problem + "\n")
                + "KEEP INVENTORY is always on. The winner takes both stakes.\n"
                + "Your wallet: " + EconomyFormat.dollars(economy.balance(player.getUniqueId()))
                + "\nStaked now: " + stakeSummary(
                        draft.money, draft.items.size(), draft.cosmetics.size());
    }

    private static void clearWager(DuelDraft draft) {
        draft.money = 0L;
        draft.items = new ArrayList<>();
        draft.cosmetics.clear();
    }

    /** Saves the amount the field is holding before a button leaves the screen. */
    private void withTypedMoney(
            Player player, DuelDraft draft, DialogResponseView response, Runnable next
    ) {
        String problem = applyMoney(draft, response.getText(MONEY_INPUT));
        if (problem == null) {
            next.run();
        } else {
            reopenSetup(player, draft.subject, problem);
        }
    }

    /**
     * Reads a typed wager, returning null or the reason it was refused.
     *
     * <p>Blank and zero are a real answer — "no cash, items only" — which
     * {@link EconomyFormat#parseAmount} rejects, because every other amount in the
     * economy has to be worth at least a dollar.
     */
    private static String applyMoney(DuelDraft draft, String typed) {
        String text = typed == null ? "" : typed.strip();
        if (text.isEmpty() || text.equals("0") || text.equals("$0")) {
            draft.money = 0L;
            return null;
        }
        try {
            draft.money = EconomyFormat.parseAmount(text);
            return null;
        } catch (IllegalArgumentException failure) {
            return failure.getMessage();
        }
    }

    /** The amount on its own screen, for the clients that cannot show it beside the buttons. */
    private void openMoneyPrompt(Player player, DuelDraft draft) {
        if (forms.prompt(player, "Money Wager", "Amount each (0 for none)",
                draft.money <= 0L ? "" : String.valueOf(draft.money),
                typed -> reopenSetup(player, draft.subject, applyMoney(draft, typed)),
                () -> reopenSetup(player, draft.subject, null))) {
            return;
        }
        prompts.put(player.getUniqueId(), Prompt.MONEY);
        player.closeInventory();
        info(player, "Type the money wager in chat, 0 for none, or cancel.");
    }

    /**
     * Sends the challenge, or says why it could not go.
     *
     * <p>The reason is returned rather than sent to chat because the screen this is
     * pressed from covers it: a dialog is drawn over the chat box, so a Send Challenge
     * that failed on the cooldown or a wager the player can no longer cover looked
     * like a button that simply did nothing. The caller puts the answer back on the
     * screen the player is still looking at.
     */
    private String sendChallenge(Player challenger, UUID targetId, DuelDraft draft) {
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            return "They went offline.";
        }
        String problem = challengeProblem(challenger, target);
        if (problem == null) {
            problem = acceptingProblem(target);
        }
        if (problem != null) {
            return problem;
        }
        long now = System.currentTimeMillis();
        long cooldown = challengeCooldownSeconds() * 1_000L;
        long remaining = cooldown - (now - lastChallenges.getOrDefault(
                challenger.getUniqueId(), 0L));
        if (remaining > 0L) {
            return "Wait " + ((remaining + 999L) / 1_000L)
                    + " seconds before sending another challenge.";
        }
        long money = draft.money;
        if (economy.balance(challenger.getUniqueId()) < money) {
            return "You do not currently have " + EconomyFormat.dollars(money) + ".";
        }
        if (!hasItems(challenger, draft.items)) {
            return "Your wager items changed. Set them again.";
        }
        if (!ownsCosmetics(challenger, draft.cosmetics)) {
            return "Your wager cosmetics changed. Choose them again.";
        }
        invitations.values().removeIf(invitation -> invitation.challenger().equals(
                challenger.getUniqueId()));
        Invitation invitation = new Invitation(
                UUID.randomUUID(), challenger.getUniqueId(), targetId, money,
                draft.items, List.copyOf(draft.cosmetics),
                System.currentTimeMillis() + inviteSeconds() * 1000L
        );
        invitations.put(invitation.id(), invitation);
        lastChallenges.put(challenger.getUniqueId(), now);
        setupDrafts.remove(challenger.getUniqueId());
        challenger.closeInventory();
        challenger.closeDialog();
        info(challenger, "Challenge sent to " + target.getName() + ".");
        target.sendMessage(prefix()
                .append(Component.text(challenger.getName(), NamedTextColor.GOLD))
                .append(Component.text(" challenged you to PvP. ", NamedTextColor.WHITE))
                .append(Component.text("Open", ORANGE, TextDecoration.BOLD)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(
                                "/pvp accept " + challenger.getName())))
                .append(Component.text(" or use /pvp.", NamedTextColor.GRAY))
        );
        return null;
    }

    private void openIncoming(Player player) {
        expireInvitations();
        List<Invitation> incoming = incoming(player.getUniqueId());
        if (!clientSupport.supportsDialogs(player)) {
            List<BedrockForms.Button> buttons = new ArrayList<>();
            for (Invitation invitation : incoming) {
                buttons.add(new BedrockForms.Button(name(invitation.challenger()),
                        () -> openInvitation(player, invitation)));
            }
            if (!forms.menu(player, "Challenges",
                    incoming.isEmpty() ? "No challenges are waiting." : "Choose a challenge.",
                    buttons, this::openHub)) {
                openChestInvitations(player, incoming);
            }
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (Invitation invitation : incoming) {
            buttons.add(Screens.button(null, name(invitation.challenger()),
                    stakeSummary(invitation.moneyEach(), invitation.challengerItems().size(),
                            invitation.challengerCosmetics().size()),
                    viewer -> openInvitation(viewer, invitation)));
        }
        Screens.show(player, "Challenges", Screens.body(incoming.isEmpty()
                        ? "No challenges are waiting."
                        : "Choose a challenge."),
                buttons, 2, this::openHub);
    }

    private void openInvitation(Player target, Invitation invitation) {
        if (!validInvitation(target, invitation, true)) {
            return;
        }
        Player challenger = Bukkit.getPlayer(invitation.challenger());
        if (challenger == null) {
            invitations.remove(invitation.id());
            error(target, "The challenger went offline.");
            return;
        }
        DuelDraft draft = acceptDrafts.get(target.getUniqueId());
        if (draft == null || !draft.subject.equals(invitation.id())) {
            draft = new DuelDraft(invitation.id());
            acceptDrafts.put(target.getUniqueId(), draft);
        }
        openAcceptScreen(target, invitation, draft);
    }

    /**
     * The answer to a challenge, drawn like the screen that sent it.
     *
     * <p>The cash is the challenger's number and both players stake it, so there is
     * nothing to type here; only the recipient's own items and cosmetics need a chest.
     */
    private void openAcceptScreen(Player player, Invitation invitation, DuelDraft draft) {
        UUID invitationId = invitation.id();
        if (!clientSupport.supportsDialogs(player)) {
            List<BedrockForms.Button> buttons = List.of(
                    new BedrockForms.Button("Your Items: " + itemCount(draft.items),
                            () -> openItemWager(player, draft, true)),
                    new BedrockForms.Button("Your Cosmetics: " + draft.cosmetics.size(),
                            () -> openCosmeticWager(player, draft, true)),
                    new BedrockForms.Button("Accept & Fight",
                            () -> acceptWith(player, invitationId, draft)),
                    new BedrockForms.Button("Decline", () -> {
                        declineByName(player, name(invitation.challenger()));
                        openIncoming(player);
                    })
            );
            if (!forms.menu(player, "Fight " + name(invitation.challenger()) + "?",
                    acceptBody(invitation, draft), buttons, this::openIncoming)) {
                openChestAccept(player, invitation, draft);
            }
            return;
        }
        List<ActionButton> buttons = List.of(
                Screens.button("item/shulker_shell", "Your Items: " + itemCount(draft.items),
                        "Stake real items. Opens a chest to put them in.",
                        viewer -> openItemWager(viewer, draft, true)),
                Screens.button("item/nether_star", "Your Cosmetics: " + draft.cosmetics.size(),
                        "Stake cosmetics from your wardrobe.",
                        viewer -> openCosmeticWager(viewer, draft, true)),
                Screens.button("item/diamond_sword", "Accept & Fight",
                        "Both of you are moved to an empty part of the world.",
                        viewer -> acceptWith(viewer, invitationId, draft)),
                Screens.button("item/barrier", "Decline", "Turn this challenge down.",
                        viewer -> {
                            declineByName(viewer, name(invitation.challenger()));
                            openIncoming(viewer);
                        })
        );
        Screens.show(player, "Fight " + name(invitation.challenger()) + "?",
                Screens.body(acceptBody(invitation, draft)), buttons, 2, this::openIncoming);
    }

    private String acceptBody(Invitation invitation, DuelDraft draft) {
        return "They stake " + stakeSummary(
                        invitation.moneyEach(), invitation.challengerItems().size(),
                        invitation.challengerCosmetics().size())
                + "\nYou stake " + stakeSummary(
                        invitation.moneyEach(), draft.items.size(), draft.cosmetics.size())
                + "\nThe cash is matched. The winner takes both stakes.";
    }

    /** Accepts the invitation as it stands right now, not as it looked when drawn. */
    private void acceptWith(Player player, UUID invitationId, DuelDraft draft) {
        Invitation invitation = invitations.get(invitationId);
        if (invitation == null) {
            error(player, "That challenge expired.");
            return;
        }
        accept(player, invitation, new AcceptedTerms(
                invitation.challengerItems(), draft.items,
                invitation.challengerCosmetics(), List.copyOf(draft.cosmetics)));
    }

    private void accept(Player target, Invitation invitation, AcceptedTerms terms) {
        if (!validInvitation(target, invitation, true)) {
            return;
        }
        Player challenger = Bukkit.getPlayer(invitation.challenger());
        if (challenger == null || !canChallenge(challenger, target, true)) {
            invitations.remove(invitation.id());
            return;
        }
        if (!hasItems(challenger, terms.challengerItems())
                || !hasItems(target, terms.targetItems())
                || !ownsCosmetics(challenger, terms.challengerCosmetics())
                || !ownsCosmetics(target, terms.targetCosmetics())) {
            error(target, "A wager changed. Review it again.");
            openInvitation(target, invitation);
            return;
        }
        invitations.remove(invitation.id());
        acceptDrafts.remove(target.getUniqueId());
        starting.add(challenger.getUniqueId());
        starting.add(target.getUniqueId());
        challenger.closeDialog();
        target.closeDialog();
        info(challenger, target.getName() + " accepted. Finding a fight location...");
        info(target, "Finding a fight location...");
        World world = overworld();
        if (world == null) {
            failStart(challenger, target, "The overworld is unavailable.");
            return;
        }
        findArena(world, challenger, target, arenaDiameter(), 0, arena -> beginFight(
                challenger, target, invitation, terms, arena
        ));
    }

    private void findArena(
            World world, Player firstPlayer, Player secondPlayer,
            int diameter, int attempted, Consumer<Arena> found
    ) {
        int attempts = plugin.gameVariables().integer("pvp-duels.location-attempts");
        if (attempted >= attempts) {
            failStart(firstPlayer, secondPlayer,
                    "No fight location was found. Try again shortly.");
            return;
        }
        Candidate candidate = candidate(world, diameter);
        if (candidate == null) {
            findArena(world, firstPlayer, secondPlayer, diameter, attempted + 1, found);
            return;
        }
        int separation = Math.max(8, Math.min(20, diameter / 4));
        int x1 = candidate.x() - separation;
        int x2 = candidate.x() + separation;
        int z = candidate.z();
        CompletableFuture<Chunk> center = world.getChunkAtAsync(candidate.x() >> 4, z >> 4, true);
        CompletableFuture<Chunk> first = world.getChunkAtAsync(x1 >> 4, z >> 4, true);
        CompletableFuture<Chunk> second = world.getChunkAtAsync(x2 >> 4, z >> 4, true);
        CompletableFuture.allOf(center, first, second).whenComplete((ignored, failure) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (failure != null) {
                        findArena(world, firstPlayer, secondPlayer, diameter, attempted + 1, found);
                        return;
                    }
                    Location one = safeLocation(world, x1, z, 90f);
                    Location two = safeLocation(world, x2, z, -90f);
                    Location middle = safeLocation(world, candidate.x(), z, 0f);
                    if (one == null || two == null || middle == null
                            || center.join().getInhabitedTime() > 0L
                            || first.join().getInhabitedTime() > 0L
                            || second.join().getInhabitedTime() > 0L) {
                        findArena(world, firstPlayer, secondPlayer, diameter, attempted + 1, found);
                        return;
                    }
                    found.accept(new Arena(middle, one, two, diameter));
                })
        );
    }

    private record Candidate(int x, int z) {
    }

    private Candidate candidate(World world, int diameter) {
        WorldBorder border = world.getWorldBorder();
        double edge = Math.max(64d, diameter / 2d + 32d);
        double borderRadius = Math.max(0d, border.getSize() / 2d - edge);
        double minimum = plugin.gameVariables().integer("pvp-duels.minimum-radius");
        double maximum = Math.min(
                plugin.gameVariables().integer("pvp-duels.maximum-radius"), borderRadius
        );
        if (maximum <= minimum) {
            return null;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int sample = 0; sample < 12; sample++) {
            double radius = PvpDuelRules.radius(minimum, maximum, random.nextDouble());
            double angle = random.nextDouble(0d, Math.PI * 2d);
            int x = (int) Math.floor(border.getCenter().getX() + Math.cos(angle) * radius);
            int z = (int) Math.floor(border.getCenter().getZ() + Math.sin(angle) * radius);
            if (!separatedFromFights(world, x, z, diameter * 3d)
                    || !untouched(world, x, z, diameter)) {
                continue;
            }
            return new Candidate(x, z);
        }
        return null;
    }

    /** Every chunk the arena can reach must be new, not merely quiet right now. */
    private static boolean untouched(World world, int x, int z, double diameter) {
        int chunkRadius = Math.max(1, (int) Math.ceil(diameter / 32d) + 1);
        int centerX = x >> 4;
        int centerZ = z >> 4;
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                if (world.isChunkGenerated(centerX + dx, centerZ + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean separatedFromFights(World world, int x, int z, double distance) {
        for (Fight fight : fights.values()) {
            Location center = fight.arena.center();
            if (center.getWorld() != null && center.getWorld().equals(world)
                    && !PvpDuelRules.separated(x, z, center.getX(), center.getZ(), distance)) {
                return false;
            }
        }
        return true;
    }

    private static Location safeLocation(World world, int x, int z, float yaw) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (y <= world.getMinHeight() || y + 2 >= world.getMaxHeight()) {
            return null;
        }
        Block floor = world.getBlockAt(x, y, z);
        Block feet = world.getBlockAt(x, y + 1, z);
        Block head = world.getBlockAt(x, y + 2, z);
        if (!floor.getType().isSolid() || DANGEROUS.contains(floor.getType())
                || !feet.isPassable() || !head.isPassable()) {
            return null;
        }
        return new Location(world, x + 0.5d, y + 1d, z + 0.5d, yaw, 0f);
    }

    private void beginFight(
            Player first,
            Player second,
            Invitation invitation,
            AcceptedTerms terms,
            Arena arena
    ) {
        if (!starting.contains(first.getUniqueId()) || !starting.contains(second.getUniqueId())
                || !readyToStart(first, second)
                || !hasItems(first, terms.challengerItems())
                || !hasItems(second, terms.targetItems())
                || !ownsCosmetics(first, terms.challengerCosmetics())
                || !ownsCosmetics(second, terms.targetCosmetics())) {
            failStart(first, second, "The fight or a wager changed before teleport.");
            return;
        }
        long wager = invitation.moneyEach();
        if (economy.balance(first.getUniqueId()) < wager
                || economy.balance(second.getUniqueId()) < wager) {
            failStart(first, second, "Both players must still be able to cover the cash wager.");
            return;
        }
        if (!PvpDuelRules.canReceivePool(economy.balance(first.getUniqueId()), wager)
                || !PvpDuelRules.canReceivePool(economy.balance(second.getUniqueId()), wager)) {
            failStart(first, second, "This wager is too large for the winner's wallet.");
            return;
        }
        UUID fightId = UUID.randomUUID();
        List<ItemStack> firstStakes = stakeItems(terms.challengerItems(), terms.challengerCosmetics());
        List<ItemStack> secondStakes = stakeItems(terms.targetItems(), terms.targetCosmetics());
        PvpDuelStore.Recovery firstRecovery = recovery(
                fightId, PvpDuelStore.Role.FIGHTER, first, firstStakes, false
        );
        PvpDuelStore.Recovery secondRecovery = recovery(
                fightId, PvpDuelStore.Role.FIGHTER, second, secondStakes, false
        );
        try {
            store.putAll(Map.of(
                    first.getUniqueId(), firstRecovery,
                    second.getUniqueId(), secondRecovery
            ));
            takeItems(first, terms.challengerItems());
            takeItems(second, terms.targetItems());
            withdrawCosmetics(first, terms.challengerCosmetics());
            withdrawCosmetics(second, terms.targetCosmetics());
            if (wager > 0L && (!economy.tryWithdraw(first.getUniqueId(), wager)
                    || !economy.tryWithdraw(second.getUniqueId(), wager))) {
                throw new IllegalStateException("A wager balance changed while the fight was starting.");
            }
        } catch (RuntimeException failure) {
            restoreStartFailure(first, firstRecovery);
            restoreStartFailure(second, secondRecovery);
            safeRemoveRecovery(first.getUniqueId());
            safeRemoveRecovery(second.getUniqueId());
            failStart(first, second, failure.getMessage() == null
                    ? "The fight could not lock its wagers." : failure.getMessage());
            return;
        }
        Map<UUID, PlayerState> states = new LinkedHashMap<>();
        states.put(first.getUniqueId(), new PlayerState(firstRecovery, first.getWorldBorder()));
        states.put(second.getUniqueId(), new PlayerState(secondRecovery, second.getWorldBorder()));
        Fight fight = new Fight(
                fightId, first, second, wager, arena, Map.copyOf(states)
        );
        fights.put(fight.id, fight);
        fighting.put(fight.first, fight);
        fighting.put(fight.second, fight);
        starting.remove(fight.first);
        starting.remove(fight.second);
        boolean firstMoved = prepareFighter(first, fight, arena.first());
        boolean secondMoved = prepareFighter(second, fight, arena.second());
        if (!firstMoved || !secondMoved) {
            endFight(fight, null, "A protected teleport was refused — stakes returned");
            return;
        }
        record("duel_started", first, fight.label() + " began a PvP fight")
                .detail("opponent", second.getName())
                .detail("money_each", String.valueOf(fight.moneyEach))
                .record();
        tickCountdown(fight, countdownSeconds());
    }

    private boolean prepareFighter(Player player, Fight fight, Location start) {
        player.closeInventory();
        player.closeDialog();
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setInvulnerable(true);
        player.setFireTicks(0);
        player.setFallDistance(0f);
        player.setFoodLevel(20);
        player.setSaturation(20f);
        if (player.getAttribute(Attribute.MAX_HEALTH) != null) {
            player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
        }
        player.setWorldBorder(personalBorder(fight.arena));
        boolean moved = teleport(player, start);
        player.sendMessage(prefix().append(Component.text(
                "KEEP INVENTORY is active. Fight only your opponent. "
                        + "Type /pvp to give up — the stakes go to them.",
                NamedTextColor.GREEN
        )));
        return moved;
    }

    private WorldBorder personalBorder(Arena arena) {
        WorldBorder border = Bukkit.createWorldBorder();
        border.setCenter(arena.center());
        border.setSize(arena.diameter());
        border.setWarningDistance(5);
        border.setDamageBuffer(0d);
        border.setDamageAmount(2d);
        return border;
    }

    private void tickCountdown(Fight fight, int remaining) {
        if (fight.phase != Phase.COUNTDOWN) {
            return;
        }
        if (remaining <= 0) {
            fight.phase = Phase.FIGHTING;
            for (UUID playerId : List.of(fight.first, fight.second)) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    player.setInvulnerable(false);
                    player.showTitle(Title.title(
                            Component.text("FIGHT!", NamedTextColor.RED, TextDecoration.BOLD),
                            Component.text("KEEP INVENTORY", NamedTextColor.GREEN),
                            Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(300))
                    ));
                    player.playSound(player, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.4f);
                }
            }
            fight.timeoutTask = plugin.getServer().getScheduler().runTaskLater(
                    plugin, () -> endFight(fight, null, "Time limit reached — draw"),
                    durationMinutes() * 60L * 20L
            );
            return;
        }
        for (UUID playerId : List.of(fight.first, fight.second)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.showTitle(Title.title(
                        Component.text(String.valueOf(remaining), ORANGE, TextDecoration.BOLD),
                        Component.text("KEEP INVENTORY", NamedTextColor.GREEN),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(800), Duration.ofMillis(100))
                ));
                player.playSound(player, Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.1f);
            }
        }
        fight.countdownTask = plugin.getServer().getScheduler().runTaskLater(
                plugin, () -> tickCountdown(fight, remaining - 1), 20L
        );
    }

    private void openFightStatus(Player player, Fight fight) {
        if (fight == null) {
            return;
        }
        String body = fight.label()
                + "\nKEEP INVENTORY is on, so nothing you carry drops."
                + "\nGiving up ends the fight and hands your opponent"
                + "\nthe money, items and cosmetics you both staked."
                + "\nLeaving the server does the same thing.";
        if (!clientSupport.supportsDialogs(player)) {
            forms.confirm(player, "Fight in Progress", body, "Give Up", () -> forfeit(player));
            return;
        }
        Screens.confirm(player, "Fight in Progress", Screens.body(body), "Give Up",
                NamedTextColor.RED, this::forfeit, Player::closeDialog);
    }

    /** Every spelling a player reaches for when they want out of a fight. */
    private static boolean givingUp(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "forfeit", "surrender", "giveup", "ff" -> true;
            default -> false;
        };
    }

    private void forfeit(Player player) {
        Fight fight = fighting.get(player.getUniqueId());
        if (fight == null) {
            error(player, "You are not in a fight.");
            return;
        }
        endFight(fight, fight.opponent(player.getUniqueId()), player.getName() + " gave up");
    }

    private void endFight(Fight fight, UUID winnerId, String result) {
        if (fight == null || fight.phase == Phase.ENDING) {
            return;
        }
        fight.phase = Phase.ENDING;
        cancel(fight.countdownTask);
        cancel(fight.timeoutTask);
        Player first = Bukkit.getPlayer(fight.first);
        Player second = Bukkit.getPlayer(fight.second);
        // CombatLog is allowed to protect ordinary fights, but it must not cancel the
        // exact return teleport promised by a completed duel.
        clearCombatLog(first, second);
        Player winner = winnerId == null ? null : Bukkit.getPlayer(winnerId);

        // A named winner must still be present to receive physical custody. Falling
        // back to a draw is safer than clearing escrow for an offline inventory.
        if (winnerId != null && winner == null) {
            winnerId = null;
            result = "Winner disconnected — stakes returned as a draw";
        }

        try {
            settleMoney(fight, winnerId);
        } catch (RuntimeException failure) {
            plugin.getLogger().severe("Could not settle duel " + fight.id + ": " + failure.getMessage());
            // Safer than inventing a winner when custody could not be saved.
            winnerId = null;
            winner = null;
            result = "Settlement failed — stakes returned as a draw";
            refundMoney(fight);
        }

        for (UUID spectatorId : List.copyOf(fight.spectators)) {
            Player viewer = Bukkit.getPlayer(spectatorId);
            if (viewer != null) {
                leaveSpectator(viewer, false);
                info(viewer, result + ". " + fight.label() + " is over.");
            }
        }

        fighting.remove(fight.first);
        fighting.remove(fight.second);
        fights.remove(fight.id);
        restoreAtEnd(first, fight.states.get(fight.first));
        restoreAtEnd(second, fight.states.get(fight.second));

        Set<UUID> receivedPhysicalCustody = new HashSet<>();
        if (winnerId == null) {
            returnStake(first, fight.states.get(fight.first));
            returnStake(second, fight.states.get(fight.second));
            if (first != null) {
                receivedPhysicalCustody.add(fight.first);
                first.saveData();
            }
            if (second != null) {
                receivedPhysicalCustody.add(fight.second);
                second.saveData();
            }
        } else if (winner != null) {
            returnStake(winner, fight.states.get(fight.first));
            returnStake(winner, fight.states.get(fight.second));
            UUID loserId = winnerId.equals(fight.first) ? fight.second : fight.first;
            String loserName = winnerId.equals(fight.first) ? fight.secondName : fight.firstName;
            giveSafely(winner, duelHead(loserId, loserName));
            receivedPhysicalCustody.add(fight.first);
            receivedPhysicalCustody.add(fight.second);
            winner.saveData();
        }

        // Clear durable escrow only after physical prizes have reached an online
        // inventory and that player data has been flushed. If Paper stops earlier,
        // recovery favours returning an item over silently destroying it.
        for (UUID playerId : List.of(fight.first, fight.second)) {
            PvpDuelStore.Recovery recovery = fight.states.get(playerId).recovery();
            if (!recovery.encodedStake().isEmpty() && !receivedPhysicalCustody.contains(playerId)) {
                continue;
            }
            try {
                store.settle(playerId, economy.balance(playerId));
            } catch (RuntimeException failure) {
                plugin.getLogger().severe("Could not close duel escrow for " + playerId
                        + ": " + failure.getMessage());
                continue;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && !player.isDead()) {
                safeRemoveRecovery(playerId);
            }
        }
        announceFightEnd(fight, winner, result);
        if (winner != null) {
            record("duel_finished", winner, winner.getName() + " won a PvP fight")
                    .detail("opponent", winnerId.equals(fight.first)
                            ? fight.secondName : fight.firstName)
                    .detail("result", result)
                    .record();
        }
    }

    private void settleMoney(Fight fight, UUID winnerId) {
        if (fight.moneyEach <= 0L) {
            return;
        }
        if (winnerId == null) {
            refundMoney(fight);
            return;
        }
        long pool = Math.multiplyExact(fight.moneyEach, 2L);
        economy.deposit(winnerId, pool);
    }

    private void refundMoney(Fight fight) {
        for (UUID playerId : List.of(fight.first, fight.second)) {
            long before = fight.states.get(playerId).recovery().balanceBefore();
            if (economy.balance(playerId) < before) {
                economy.set(playerId, before);
            }
        }
    }

    private void restoreAtEnd(Player player, PlayerState state) {
        if (player == null || state == null) {
            return;
        }
        if (player.isDead()) {
            pendingDeathRestore.put(player.getUniqueId(), state);
            return;
        }
        restorePlayer(player, state);
    }

    private void announceFightEnd(Fight fight, Player winner, String result) {
        Component message = prefix().append(winner == null
                ? Component.text(fight.label() + " ended in a draw. " + result + ".",
                        NamedTextColor.YELLOW)
                : Component.text(winner.getName() + " won " + fight.label()
                        + ". " + result + ".", NamedTextColor.GOLD));
        for (UUID playerId : List.of(fight.first, fight.second)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.sendMessage(message);
            }
        }
    }

    private void openLive(Player player) {
        List<Fight> live = fights.values().stream()
                .filter(fight -> fight.phase != Phase.ENDING)
                .toList();
        if (!clientSupport.supportsDialogs(player)) {
            List<BedrockForms.Button> buttons = live.stream()
                    .map(fight -> new BedrockForms.Button(fight.label(),
                            () -> joinSpectator(player, fight)))
                    .toList();
            if (!forms.menu(player, "Live Fights",
                    live.isEmpty() ? "No fight is live." : "Watch from the viewing stand.",
                    buttons, this::openHub)) {
                openChestFights(player, live);
            }
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (Fight fight : live) {
            buttons.add(Screens.button(null, fight.label(), "Watch",
                    viewer -> joinSpectator(viewer, fight)));
        }
        Screens.show(player, "Live Fights", Screens.body(live.isEmpty()
                        ? "No fight is live right now."
                        : "Watch from an anchored stand. No items, interaction, free roam, or chat."),
                buttons, 2, this::openHub);
    }

    private void joinSpectator(Player player, Fight fight) {
        if (fight == null || fight.phase == Phase.ENDING || !fights.containsKey(fight.id)) {
            error(player, "That fight has ended.");
            return;
        }
        if (VerificationLobbyService.isLobbyWorld(player.getWorld())) {
            error(player, "Finish verification before watching a fight.");
            return;
        }
        if (plugin.inScreenshotMode(player)) {
            error(player, "Close screenshot mode before watching a fight.");
            return;
        }
        if (isParticipant(player.getUniqueId()) || starting.contains(player.getUniqueId())) {
            error(player, "You are already busy with a fight.");
            return;
        }
        if (fight.spectators.size() >= plugin.gameVariables().integer("pvp-duels.maximum-spectators")) {
            error(player, "That viewing stand is full.");
            return;
        }
        Location anchor = fight.arena.center().clone().add(0d,
                Math.max(8d, Math.max(
                        fight.arena.first().getY() - fight.arena.center().getY(),
                        fight.arena.second().getY() - fight.arena.center().getY()
                ) + 8d), 0d);
        PvpDuelStore.Recovery recovery = recovery(
                fight.id, PvpDuelStore.Role.SPECTATOR, player, null, true
        );
        PlayerState state = new PlayerState(recovery, player.getWorldBorder());
        try {
            store.putAll(Map.of(player.getUniqueId(), recovery));
        } catch (UncheckedIOException failure) {
            error(player, "Your inventory could not be protected for viewing.");
            return;
        }
        SpectatorState spectator = new SpectatorState(fight, state, anchor);
        spectators.put(player.getUniqueId(), spectator);
        fight.spectators.add(player.getUniqueId());
        player.closeDialog();
        player.closeInventory();
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        player.setGameMode(GameMode.ADVENTURE);
        player.setInvulnerable(true);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setWorldBorder(personalBorder(fight.arena));
        if (!teleport(player, anchor)) {
            leaveSpectator(player, false);
            error(player, "The protected viewing teleport was refused. Your items are back.");
            return;
        }
        for (UUID fighterId : List.of(fight.first, fight.second)) {
            Player fighter = Bukkit.getPlayer(fighterId);
            if (fighter != null) {
                fighter.hidePlayer(plugin, player);
            }
        }
        info(player, "Watching " + fight.label()
                + " from a fixed stand. /pvp leave returns you and your items.");
    }

    private void leaveSpectator(Player player, boolean announce) {
        SpectatorState spectator = spectators.remove(player.getUniqueId());
        if (spectator == null) {
            if (announce) {
                error(player, "You are not watching a fight.");
            }
            return;
        }
        spectator.fight().spectators.remove(player.getUniqueId());
        for (UUID fighterId : List.of(spectator.fight().first, spectator.fight().second)) {
            Player fighter = Bukkit.getPlayer(fighterId);
            if (fighter != null) {
                fighter.showPlayer(plugin, player);
            }
        }
        restoreSpectatorInventory(player, spectator.player().recovery());
        restorePlayer(player, spectator.player());
        safeRemoveRecovery(player.getUniqueId());
        if (announce) {
            info(player, "Viewing ended. Your location and belongings are back.");
        }
    }

    private void openChestHub(Player player) {
        DuelBoard holder = board(Board.HUB, null, 27, "PvP");
        holder.inventory.setItem(HUB_START, MenuItems.button(
                Material.DIAMOND_SWORD, "Start a Fight", "Choose a player."));
        holder.inventory.setItem(HUB_INCOMING, MenuItems.button(
                Material.WRITABLE_BOOK, "Incoming", "Review your challenges."));
        holder.inventory.setItem(HUB_LIVE, MenuItems.button(
                Material.SPYGLASS, "Watch Live Fights", "Watch from the viewing stand."));
        MenuItems.back(holder.inventory);
        MenuItems.show(plugin, player, holder.inventory);
    }

    private void openChestList(Player player, Board kind, List<Player> choices, String title) {
        DuelBoard holder = board(kind, null, BOARD_SIZE, title);
        for (int slot = 0; slot < choices.size() && slot < PAGE_SIZE; slot++) {
            Player target = choices.get(slot);
            holder.inventory.setItem(slot, MenuItems.head(
                    target.getUniqueId(), target.getName(), List.of("Challenge")));
            holder.choices.put(slot, target.getUniqueId());
        }
        MenuItems.back(holder.inventory);
        MenuItems.show(plugin, player, holder.inventory);
    }

    private void openChestSetup(Player player, Player target, DuelDraft draft) {
        DuelBoard holder = board(Board.SETUP, target.getUniqueId(), SETUP_BOARD_SIZE,
                "Fight " + target.getName());
        holder.inventory.setItem(SETUP_MONEY_SLOT, MenuItems.button(Material.GOLD_INGOT,
                "Money: " + EconomyFormat.dollars(draft.money), "Staked by each player."));
        holder.inventory.setItem(SETUP_ITEMS_SLOT, MenuItems.button(Material.CHEST,
                "Items: " + itemCount(draft.items), "Add any items."));
        holder.inventory.setItem(SETUP_COSMETICS_SLOT, MenuItems.button(Material.NETHER_STAR,
                "Cosmetics: " + draft.cosmetics.size(), "Choose from your wardrobe."));
        holder.inventory.setItem(SETUP_CLEAR_SLOT, MenuItems.button(Material.BARRIER,
                "Clear Wager", "Remove all stakes."));
        holder.inventory.setItem(SETUP_SEND_SLOT, MenuItems.button(Material.DIAMOND_SWORD,
                "Send Challenge", "KEEP INVENTORY"));
        MenuItems.back(holder.inventory);
        MenuItems.show(plugin, player, holder.inventory);
    }

    private void openItemWager(Player player, DuelDraft draft, boolean accepting) {
        // Reached from a dialog on Java, and a container cannot open behind one.
        player.closeDialog();
        Board kind = accepting ? Board.ACCEPT_ITEMS : Board.SETUP_ITEMS;
        DuelBoard holder = board(kind, draft.subject, BOARD_SIZE, "Add Wager Items");
        holder.inventory.setItem(45, MenuItems.button(Material.CHEST,
                "Staked now: " + itemCount(draft.items),
                "Drag or shift-click items into the space above.",
                "Nothing leaves your inventory until the fight starts."));
        holder.inventory.setItem(48, MenuItems.button(Material.LIME_CONCRETE,
                "Save & Go Back", "Stakes exactly what is above."));
        holder.inventory.setItem(50, MenuItems.button(Material.RED_DYE,
                "Stake No Items", "Clears the wager and empties this screen."));
        MenuItems.back(holder.inventory);
        MenuItems.show(plugin, player, holder.inventory);
    }

    private void openCosmeticWager(Player player, DuelDraft draft, boolean accepting) {
        player.closeDialog();
        Board kind = accepting ? Board.ACCEPT_COSMETICS : Board.SETUP_COSMETICS;
        DuelBoard holder = board(kind, draft.subject, BOARD_SIZE, "Choose Cosmetics");
        int slot = 0;
        for (CosmeticStore.Token token : cosmetics.stored(player.getUniqueId())) {
            if (token.serialNumber() <= 0 || slot >= PAGE_SIZE) continue;
            CosmeticCatalog.Definition definition = CosmeticCatalog.find(token.cosmeticId()).orElse(null);
            if (definition == null || definition.leaderboardOnly()) continue;
            ItemStack icon = cosmeticItems.preview(definition, false);
            org.bukkit.inventory.meta.ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
                lore.add(line(draft.cosmetics.contains(token.serial()) ? "Selected" : "Click to select"));
                meta.lore(lore);
                MenuItems.asButton(meta);
                icon.setItemMeta(meta);
            }
            holder.inventory.setItem(slot, icon);
            holder.choices.put(slot, token.serial());
            slot++;
        }
        MenuItems.back(holder.inventory);
        MenuItems.show(plugin, player, holder.inventory);
    }

    private void openChestInvitations(Player player, List<Invitation> incoming) {
        DuelBoard holder = board(Board.INCOMING, null, BOARD_SIZE, "Challenges");
        for (int slot = 0; slot < incoming.size() && slot < PAGE_SIZE; slot++) {
            Invitation invite = incoming.get(slot);
            holder.inventory.setItem(slot, MenuItems.head(
                    invite.challenger(), name(invite.challenger()), List.of(
                            stakeSummary(invite.moneyEach(), invite.challengerItems().size(),
                                    invite.challengerCosmetics().size()))));
            holder.choices.put(slot, invite.id());
        }
        MenuItems.back(holder.inventory);
        MenuItems.show(plugin, player, holder.inventory);
    }

    private void openChestAccept(Player player, Invitation invitation, DuelDraft draft) {
        DuelBoard holder = board(Board.ACCEPT, invitation.id(), SETUP_BOARD_SIZE,
                "Fight " + name(invitation.challenger()) + "?");
        holder.inventory.setItem(ACCEPT_OFFER_SLOT, MenuItems.button(Material.PLAYER_HEAD,
                name(invitation.challenger()) + " Offers",
                stakeLines(invitation.moneyEach(), invitation.challengerItems(),
                        invitation.challengerCosmetics())));
        holder.inventory.setItem(ACCEPT_MONEY_SLOT, MenuItems.button(Material.GOLD_INGOT,
                "Money: " + EconomyFormat.dollars(invitation.moneyEach()), "Staked by each player."));
        holder.inventory.setItem(ACCEPT_ITEMS_SLOT, MenuItems.button(Material.CHEST,
                "Your Items: " + itemCount(draft.items), "Add any items."));
        holder.inventory.setItem(ACCEPT_COSMETICS_SLOT, MenuItems.button(Material.NETHER_STAR,
                "Your Cosmetics: " + draft.cosmetics.size(), "Choose from your wardrobe."));
        holder.inventory.setItem(ACCEPT_DECLINE_SLOT, MenuItems.button(
                Material.RED_CONCRETE, "Decline"));
        holder.inventory.setItem(ACCEPT_CONFIRM_SLOT, MenuItems.button(Material.LIME_CONCRETE,
                "Accept & Fight", "KEEP INVENTORY"));
        MenuItems.back(holder.inventory);
        MenuItems.show(plugin, player, holder.inventory);
    }

    private void openChestFights(Player player, List<Fight> live) {
        DuelBoard holder = board(Board.LIVE, null, BOARD_SIZE, "Live Fights");
        for (int slot = 0; slot < live.size() && slot < PAGE_SIZE; slot++) {
            Fight fight = live.get(slot);
            holder.inventory.setItem(slot, MenuItems.button(
                    Material.SPYGLASS, fight.label(), "Watch"));
            holder.choices.put(slot, fight.id);
        }
        MenuItems.back(holder.inventory);
        MenuItems.show(plugin, player, holder.inventory);
    }

    private DuelBoard board(Board kind, UUID subject, int size, String title) {
        DuelBoard holder = new DuelBoard(kind, subject);
        holder.inventory = Bukkit.createInventory(holder, size, Component.text(title, ORANGE));
        return holder;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onBoardClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof DuelBoard holder)) {
            return;
        }
        boolean itemWager = holder.board == Board.SETUP_ITEMS
                || holder.board == Board.ACCEPT_ITEMS;
        if (itemWager) {
            int raw = event.getRawSlot();
            if (raw >= 0 && raw < PAGE_SIZE) {
                event.setCancelled(false);
                return;
            }
            if (raw >= event.getInventory().getSize()) {
                if (!event.isShiftClick()) {
                    event.setCancelled(false);
                    return;
                }
                // Vanilla shift-click fills the whole container, which would drop a
                // stack onto the button row. Moving it by hand keeps the navigation
                // intact and still gives players the way they actually load a chest.
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player clicker
                        && !empty(event.getCurrentItem())) {
                    event.setCurrentItem(depositIntoWager(
                            event.getInventory(), event.getCurrentItem()));
                    clicker.updateInventory();
                }
                return;
            }
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getInventory()) {
            return;
        }
        int slot = event.getSlot();
        if (slot == MenuItems.backSlot(event.getInventory().getSize())) {
            returnDeposits(player, holder);
            Consumer<Player> destination = switch (holder.board) {
                case HUB -> Screens::home;
                case SETUP -> this::openTargets;
                case ACCEPT -> this::openIncoming;
                case SETUP_ITEMS, SETUP_COSMETICS -> viewer -> reopenSetup(viewer, holder.subject);
                case ACCEPT_ITEMS, ACCEPT_COSMETICS -> viewer -> reopenAccept(viewer, holder.subject);
                default -> this::openHub;
            };
            runLater(player, destination);
            return;
        }
        switch (holder.board) {
            case HUB -> {
                if (slot == HUB_START) runLater(player, this::openTargets);
                if (slot == HUB_INCOMING) runLater(player, this::openIncoming);
                if (slot == HUB_LIVE) runLater(player, this::openLive);
            }
            case TARGETS -> {
                UUID id = holder.choices.get(slot);
                if (id != null) runLater(player, viewer -> {
                    Player target = Bukkit.getPlayer(id);
                    if (target == null) error(viewer, "They went offline.");
                    else openSetup(viewer, target);
                });
            }
            case SETUP -> runLater(player, viewer -> chestSetupClick(viewer, holder.subject, slot));
            case SETUP_ITEMS, ACCEPT_ITEMS -> {
                DuelDraft draft = holder.board == Board.SETUP_ITEMS
                        ? setupDrafts.get(player.getUniqueId())
                        : acceptDrafts.get(player.getUniqueId());
                if (draft == null) return;
                boolean accepting = holder.board == Board.ACCEPT_ITEMS;
                if (slot == 48) {
                    draft.items = depositedItems(holder.inventory);
                    returnDeposits(player, holder);
                    runLater(player, viewer -> {
                        if (accepting) reopenAccept(viewer, draft.subject);
                        else reopenSetup(viewer, draft.subject);
                    });
                } else if (slot == 50) {
                    draft.items = new ArrayList<>();
                    returnDeposits(player, holder);
                    runLater(player, viewer -> openItemWager(viewer, draft, accepting));
                }
            }
            case SETUP_COSMETICS, ACCEPT_COSMETICS -> {
                DuelDraft draft = holder.board == Board.SETUP_COSMETICS
                        ? setupDrafts.get(player.getUniqueId())
                        : acceptDrafts.get(player.getUniqueId());
                UUID serial = holder.choices.get(slot);
                if (draft != null && serial != null) {
                    if (!draft.cosmetics.add(serial)) draft.cosmetics.remove(serial);
                    runLater(player, viewer -> openCosmeticWager(
                            viewer, draft, holder.board == Board.ACCEPT_COSMETICS));
                }
            }
            case INCOMING -> {
                UUID id = holder.choices.get(slot);
                if (id != null) runLater(player, viewer -> {
                    Invitation invite = invitations.get(id);
                    if (invite == null) error(viewer, "That challenge expired.");
                    else openInvitation(viewer, invite);
                });
            }
            case ACCEPT -> runLater(player, viewer -> {
                Invitation invite = invitations.get(holder.subject);
                if (invite == null) {
                    error(viewer, "That challenge expired.");
                    return;
                }
                DuelDraft draft = acceptDrafts.get(viewer.getUniqueId());
                if (draft == null || !draft.subject.equals(invite.id())) {
                    draft = new DuelDraft(invite.id());
                    acceptDrafts.put(viewer.getUniqueId(), draft);
                }
                if (slot == ACCEPT_ITEMS_SLOT) {
                    openItemWager(viewer, draft, true);
                } else if (slot == ACCEPT_COSMETICS_SLOT) {
                    openCosmeticWager(viewer, draft, true);
                } else if (slot == ACCEPT_CONFIRM_SLOT) {
                    acceptWith(viewer, invite.id(), draft);
                } else if (slot == ACCEPT_DECLINE_SLOT) {
                    declineByName(viewer, name(invite.challenger()));
                }
            });
            case LIVE -> {
                UUID id = holder.choices.get(slot);
                if (id != null) runLater(player, viewer -> {
                    Fight fight = fights.get(id);
                    if (fight == null) error(viewer, "That fight ended.");
                    else joinSpectator(viewer, fight);
                });
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onBoardDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof DuelBoard holder)) return;
        boolean itemWager = holder.board == Board.SETUP_ITEMS
                || holder.board == Board.ACCEPT_ITEMS;
        event.setCancelled(!itemWager || event.getRawSlots().stream().anyMatch(slot -> slot >= PAGE_SIZE));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBoardClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof DuelBoard holder
                && event.getPlayer() instanceof Player player) {
            returnDeposits(player, holder);
        }
    }

    private void chestSetupClick(Player player, UUID targetId, int slot) {
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            error(player, "They went offline.");
            return;
        }
        DuelDraft draft = setupDrafts.computeIfAbsent(
                player.getUniqueId(), ignored -> new DuelDraft(targetId));
        if (slot == SETUP_MONEY_SLOT) {
            openMoneyPrompt(player, draft);
        } else if (slot == SETUP_ITEMS_SLOT) {
            openItemWager(player, draft, false);
        } else if (slot == SETUP_COSMETICS_SLOT) {
            openCosmeticWager(player, draft, false);
        } else if (slot == SETUP_CLEAR_SLOT) {
            clearWager(draft);
            openChestSetup(player, target, draft);
        } else if (slot == SETUP_SEND_SLOT) {
            reopenSetup(player, targetId, sendChallenge(player, targetId, draft));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPromptChat(AsyncChatEvent event) {
        Prompt prompt = prompts.get(event.getPlayer().getUniqueId());
        if (prompt == null) {
            if (spectators.containsKey(event.getPlayer().getUniqueId())) {
                event.setCancelled(true);
                plugin.getServer().getScheduler().runTask(plugin, () -> error(
                        event.getPlayer(),
                        "Viewing chat is disabled so spectators cannot coach a fighter."
                ));
            }
            return;
        }
        event.setCancelled(true);
        String typed = PlainTextComponentSerializer.plainText().serialize(event.message()).strip();
        plugin.getServer().getScheduler().runTask(plugin,
                () -> continueMoneyPrompt(event.getPlayer(), typed));
    }

    private void continueMoneyPrompt(Player player, String typed) {
        DuelDraft draft = setupDrafts.get(player.getUniqueId());
        if (draft == null || typed.equalsIgnoreCase("cancel")) {
            prompts.remove(player.getUniqueId());
            if (draft != null) reopenSetup(player, draft.subject);
            return;
        }
        String problem = applyMoney(draft, typed);
        if (problem != null) {
            // Chat is the screen here, so the prompt stays open for another attempt.
            error(player, problem);
            return;
        }
        prompts.remove(player.getUniqueId());
        reopenSetup(player, draft.subject);
    }

    /** Keeps arranged fights isolated while ordinary PvP follows the server toggle. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            if (event instanceof EntityDamageByEntityEvent byEntity) {
                Player attacker = attackingPlayer(byEntity);
                if (attacker != null && isFighter(attacker.getUniqueId())) {
                    event.setCancelled(true);
                }
            }
            return;
        }
        if (spectators.containsKey(victim.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        Fight victimFight = fighting.get(victim.getUniqueId());
        if (victimFight != null) {
            if (victimFight.phase != Phase.FIGHTING) {
                event.setCancelled(true);
                return;
            }
            if (event instanceof EntityDamageByEntityEvent byEntity) {
                boolean opponent = isOpponentAttack(
                        byEntity, victimFight.opponent(victim.getUniqueId())
                );
                event.setCancelled(!opponent);
            }
            return;
        }
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        Player attacker = attackingPlayer(byEntity);
        if (attacker == null) {
            return;
        }
        if (!isFighter(attacker.getUniqueId()) && plugin.openWorldPvpEnabled()) {
            return;
        }
        event.setCancelled(true);
        maybeExplainBlocked(attacker);
        clearCombatLog(attacker, victim);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        Fight fight = fighting.get(victim.getUniqueId());
        if (fight == null || fight.phase == Phase.ENDING) {
            return;
        }
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        event.getDrops().clear();
        UUID winner = fight.opponent(victim.getUniqueId());
        Player winnerPlayer = Bukkit.getPlayer(winner);
        event.deathMessage(Component.text(
                (winnerPlayer == null ? name(winner) : winnerPlayer.getName())
                        + " defeated " + victim.getName()
                        + " in /pvp — inventory kept.",
                NamedTextColor.GOLD
        ));
        plugin.getServer().getScheduler().runTask(plugin,
                () -> endFight(fight, winner, victim.getName() + " was defeated"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onRespawn(PlayerRespawnEvent event) {
        PlayerState state = pendingDeathRestore.remove(event.getPlayer().getUniqueId());
        if (state == null) {
            return;
        }
        Location origin = origin(state.recovery());
        if (origin != null) {
            event.setRespawnLocation(origin);
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            restorePlayer(event.getPlayer(), state);
            safeRemoveRecovery(event.getPlayer().getUniqueId());
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || samePosition(event.getFrom(), to)) {
            return;
        }
        SpectatorState spectator = spectators.get(event.getPlayer().getUniqueId());
        if (spectator != null) {
            event.setCancelled(true);
            return;
        }
        Fight fight = fighting.get(event.getPlayer().getUniqueId());
        if (fight == null) {
            return;
        }
        if (fight.phase == Phase.COUNTDOWN
                || to.getWorld() == null
                || !to.getWorld().equals(fight.arena.center().getWorld())
                || !PvpDuelRules.inside(to.getX(), to.getZ(),
                        fight.arena.center().getX(), fight.arena.center().getZ(),
                        fight.arena.diameter())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTeleport(PlayerTeleportEvent event) {
        if (isParticipant(event.getPlayer().getUniqueId())
                && !internalTeleports.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            error(event.getPlayer(), "Leave or finish the fight before teleporting.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!isParticipant(event.getPlayer().getUniqueId())) {
            return;
        }
        String[] typed = event.getMessage().strip().toLowerCase(Locale.ROOT).split("\\s+");
        // Bare /pvp is allowed through so the fight screen — and the Give Up button on
        // it — can be reopened. Blocking it left the exact spelling of a subcommand as
        // the only way out of a fight, which is not something a player can guess.
        boolean pvp = typed[0].equals("/pvp") || typed[0].equals("/duel");
        boolean allowed = pvp && (typed.length == 1
                || givingUp(typed[1]) || typed[1].equals("leave"));
        if (!allowed) {
            event.setCancelled(true);
            error(event.getPlayer(), spectators.containsKey(event.getPlayer().getUniqueId())
                    ? "Only /pvp leave is available while you are watching."
                    : "Only /pvp is available during a fight. It is where you give up.");
        } else {
            // CombatLog may have refused it earlier in the same event. These two are
            // the controlled exit routes, so they must reach this service.
            event.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        if (isParticipant(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) {
        if (isParticipant(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (isParticipant(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (isParticipant(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (spectators.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (isFighter(event.getPlayer().getUniqueId()) && event.getClickedBlock() != null) {
            // Doors, containers and terrain stay untouched, while a player pointing
            // at the ground may still eat, shield, draw a bow, or use another item
            // they actually brought. Placement has its own hard cancellation below.
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            event.setUseItemInHand(org.bukkit.event.Event.Result.ALLOW);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (isParticipant(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        if (isParticipant(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isParticipant(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (spectators.containsKey(player.getUniqueId())
                || (isFighter(player.getUniqueId())
                        && event.getInventory().getType() != InventoryType.CRAFTING)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getPlayer() != null && isParticipant(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null && isParticipant(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onVehicleCreate(VehicleCreateEvent event) {
        if (insideAnyArena(event.getVehicle().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (insideAnyArena(event.getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player
                && spectators.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (insideAnyArena(event.getLocation())) event.blockList().clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (insideAnyArena(event.getBlock().getLocation())) event.blockList().clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onIgnite(BlockIgniteEvent event) {
        if (insideAnyArena(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Fight fight = fighting.get(player.getUniqueId());
        if (fight != null && fight.phase != Phase.ENDING) {
            endFight(fight, fight.opponent(player.getUniqueId()),
                    player.getName() + " left and gave up");
        }
        if (spectators.containsKey(player.getUniqueId())) {
            leaveSpectator(player, false);
        }
        invitations.values().removeIf(invitation -> invitation.challenger().equals(player.getUniqueId())
                || invitation.target().equals(player.getUniqueId()));
        prompts.remove(player.getUniqueId());
        setupDrafts.remove(player.getUniqueId());
        acceptDrafts.remove(player.getUniqueId());
        starting.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (store.find(event.getPlayer().getUniqueId()).isEmpty()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> recover(event.getPlayer(), false));
    }

    void stop() {
        for (Fight fight : List.copyOf(fights.values())) {
            endFight(fight, null, "Server restart — stakes returned");
        }
        for (UUID spectatorId : List.copyOf(spectators.keySet())) {
            Player player = Bukkit.getPlayer(spectatorId);
            if (player != null) leaveSpectator(player, false);
        }
        invitations.clear();
        prompts.clear();
        setupDrafts.clear();
        acceptDrafts.clear();
        lastChallenges.clear();
        starting.clear();
    }

    /** An owner pause resolves live contracts as draws instead of freezing fighters. */
    void pauseAll(String reason) {
        for (Fight fight : List.copyOf(fights.values())) {
            endFight(fight, null, reason);
        }
    }

    private void recover(Player player, boolean announce) {
        PvpDuelStore.Recovery recovery = store.find(player.getUniqueId()).orElse(null);
        if (recovery == null) {
            return;
        }
        if (economy.balance(player.getUniqueId()) < recovery.balanceBefore()) {
            economy.set(player.getUniqueId(), recovery.balanceBefore());
        }
        if (recovery.role() == PvpDuelStore.Role.SPECTATOR
                && !recovery.encodedInventory().isEmpty()) {
            restoreSpectatorInventory(player, recovery);
        } else if (!recovery.encodedStake().isEmpty()) {
            restoreEscrow(player, decodeStakeItems(recovery.encodedStake()));
        }
        restorePlayer(player, new PlayerState(recovery, null));
        safeRemoveRecovery(player.getUniqueId());
        if (announce) {
            info(player, "An interrupted fight was a draw. Your state and wagers are back.");
        } else {
            info(player, "Your interrupted fight was recovered. Your state and stakes are back.");
        }
    }

    private static void restoreSpectatorInventory(
            Player player, PvpDuelStore.Recovery recovery
    ) {
        if (recovery.encodedInventory().isEmpty()) {
            return;
        }
        ItemStack[] saved = decodeItems(recovery.encodedInventory());
        player.getInventory().clear();
        player.getInventory().setContents(sized(
                saved, player.getInventory().getContents().length));
        player.getInventory().setHeldItemSlot(Math.max(0, Math.min(8, recovery.heldSlot())));
    }

    private PvpDuelStore.Recovery recovery(
            UUID duelId,
            PvpDuelStore.Role role,
            Player player,
            List<ItemStack> stakes,
            boolean inventory
    ) {
        Location origin = player.getLocation();
        return new PvpDuelStore.Recovery(
                duelId, role, origin.getWorld().getUID(), origin.getWorld().getName(),
                origin.getX(), origin.getY(), origin.getZ(), origin.getYaw(), origin.getPitch(),
                player.getGameMode().name(), player.isInvulnerable(), player.getAllowFlight(),
                player.isFlying(), player.getHealth(), player.getFoodLevel(), player.getSaturation(),
                player.getExhaustion(), player.getFireTicks(), player.getFallDistance(),
                player.getRemainingAir(), player.getInventory().getHeldItemSlot(),
                economy.balance(player.getUniqueId()), encodeStakeItems(stakes),
                inventory ? encodeItems(player.getInventory().getContents()) : ""
        );
    }

    private void restorePlayer(Player player, PlayerState state) {
        PvpDuelStore.Recovery recovery = state.recovery();
        try {
            player.setGameMode(GameMode.valueOf(recovery.gameMode()));
        } catch (IllegalArgumentException ignored) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        player.setInvulnerable(recovery.invulnerable());
        player.setAllowFlight(recovery.allowFlight());
        player.setFlying(recovery.allowFlight() && recovery.flying());
        player.setFoodLevel(recovery.food());
        player.setSaturation(recovery.saturation());
        player.setExhaustion(recovery.exhaustion());
        player.setFireTicks(recovery.fireTicks());
        player.setFallDistance(recovery.fallDistance());
        player.setRemainingAir(recovery.remainingAir());
        if (!player.isDead() && player.getAttribute(Attribute.MAX_HEALTH) != null) {
            player.setHealth(Math.max(0.1d, Math.min(
                    recovery.health(), player.getAttribute(Attribute.MAX_HEALTH).getValue())));
        }
        Location origin = origin(recovery);
        if (origin != null) {
            teleport(player, origin);
        }
        // A previous border may belong to the original world, so put it back only
        // after the cross-world return has completed. Null resets a crash recovery to
        // that world's own border.
        player.setWorldBorder(state.previousBorder());
    }

    private Location origin(PvpDuelStore.Recovery recovery) {
        World world = Bukkit.getWorld(recovery.worldId());
        if (world == null) world = Bukkit.getWorld(recovery.worldName());
        return world == null ? null : new Location(
                world, recovery.x(), recovery.y(), recovery.z(), recovery.yaw(), recovery.pitch()
        );
    }

    private void restoreStartFailure(Player player, PvpDuelStore.Recovery recovery) {
        if (economy.balance(player.getUniqueId()) < recovery.balanceBefore()) {
            economy.set(player.getUniqueId(), recovery.balanceBefore());
        }
        if (!recovery.encodedStake().isEmpty()) {
            restoreEscrow(player, decodeStakeItems(recovery.encodedStake()));
        }
    }

    private void returnStake(Player player, PlayerState state) {
        if (player == null || state == null || state.recovery().encodedStake().isEmpty()) {
            return;
        }
        for (ItemStack item : decodeStakeItems(state.recovery().encodedStake())) {
            giveSafely(player, item);
        }
        vaultLater(player);
    }

    private void giveSafely(Player player, ItemStack item) {
        if (empty(item)) return;
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        for (ItemStack left : overflow.values()) {
            Map<Integer, ItemStack> enderOverflow = player.getEnderChest().addItem(left);
            for (ItemStack last : enderOverflow.values()) {
                org.bukkit.entity.Item dropped = player.getWorld().dropItemNaturally(
                        player.getLocation(), last
                );
                dropped.setOwner(player.getUniqueId());
                dropped.setPickupDelay(20);
            }
        }
    }

    private ItemStack duelHead(UUID victimId, String victimName) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (!(head.getItemMeta() instanceof SkullMeta meta)) {
            return head;
        }
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(victimId));
        meta.displayName(Component.text(victimName + "'s PvP Trophy",
                ORANGE, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                line("Won through /pvp."),
                line("KEEP INVENTORY fight."),
                line("Protected from /sell.")
        ));
        meta.getPersistentDataContainer().set(
                victimKey, PersistentDataType.STRING, victimId.toString()
        );
        head.setItemMeta(meta);
        return head;
    }

    private void failStart(Player first, Player second, String reason) {
        starting.remove(first.getUniqueId());
        starting.remove(second.getUniqueId());
        error(first, reason);
        error(second, reason);
    }

    private boolean canChallenge(Player challenger, Player target, boolean explain) {
        String problem = challengeProblem(challenger, target);
        if (problem != null && explain) error(challenger, problem);
        return problem == null;
    }

    /** Why these two cannot fight, or null. Worded to be shown on the screen itself. */
    private String challengeProblem(Player challenger, Player target) {
        String problem = null;
        if (!enabled()) problem = "/pvp is currently disabled.";
        else if (challenger.equals(target)) problem = "You cannot challenge yourself.";
        else if (!challenger.isOnline() || !target.isOnline()) problem = "Both players must be online.";
        else if (VerificationLobbyService.isLobbyWorld(challenger.getWorld())
                || VerificationLobbyService.isLobbyWorld(target.getWorld())) {
            problem = "Verification-lobby players cannot fight.";
        } else if (plugin.inScreenshotMode(challenger) || plugin.inScreenshotMode(target)) {
            problem = "Close screenshot mode before fighting.";
        } else if (isParticipant(challenger.getUniqueId()) || isParticipant(target.getUniqueId())
                || starting.contains(challenger.getUniqueId()) || starting.contains(target.getUniqueId())) {
            problem = "One of you is already busy with a fight.";
        } else if (store.find(challenger.getUniqueId()).isPresent()
                || store.find(target.getUniqueId()).isPresent()) {
            problem = "One player's fight recovery is still finishing.";
        } else if ((plugin.afkService() != null && plugin.afkService().inCombat(challenger))
                || (plugin.afkService() != null && plugin.afkService().inCombat(target))) {
            problem = "Finish the current combat tag first.";
        }
        return problem;
    }

    private boolean acceptingChallenges(Player challenger, Player target, boolean explain) {
        String problem = acceptingProblem(target);
        if (problem != null && explain) {
            error(challenger, problem);
        }
        return problem == null;
    }

    private String acceptingProblem(Player target) {
        return settings.isEnabled(target.getUniqueId(), PlayerSettingsStore.Setting.DUEL_REQUESTS)
                ? null
                : target.getName() + " is not accepting PvP challenges.";
    }

    private boolean readyToStart(Player first, Player second) {
        return enabled()
                && first.isOnline()
                && second.isOnline()
                && !isParticipant(first.getUniqueId())
                && !isParticipant(second.getUniqueId())
                && store.find(first.getUniqueId()).isEmpty()
                && store.find(second.getUniqueId()).isEmpty()
                && !VerificationLobbyService.isLobbyWorld(first.getWorld())
                && !VerificationLobbyService.isLobbyWorld(second.getWorld())
                && !plugin.inScreenshotMode(first)
                && !plugin.inScreenshotMode(second);
    }

    private boolean validInvitation(Player target, Invitation invitation, boolean explain) {
        expireInvitations();
        boolean valid = invitation != null
                && invitations.containsKey(invitation.id())
                && invitation.target().equals(target.getUniqueId())
                && invitation.expiresAt() > System.currentTimeMillis();
        if (!valid && explain) error(target, "That challenge expired or is no longer available.");
        return valid;
    }

    private void acceptByName(Player target, String challengerName) {
        Invitation invite = incoming(target.getUniqueId()).stream()
                .filter(row -> challengerName.isBlank()
                        || name(row.challenger()).equalsIgnoreCase(challengerName))
                .findFirst().orElse(null);
        if (invite == null) {
            error(target, "No matching challenge is waiting. Open /pvp to review them.");
        } else {
            openInvitation(target, invite);
        }
    }

    private void declineByName(Player target, String challengerName) {
        Invitation invite = incoming(target.getUniqueId()).stream()
                .filter(row -> challengerName.isBlank()
                        || name(row.challenger()).equalsIgnoreCase(challengerName))
                .findFirst().orElse(null);
        if (invite == null) {
            error(target, "No matching challenge is waiting.");
            return;
        }
        invitations.remove(invite.id());
        Player challenger = Bukkit.getPlayer(invite.challenger());
        if (challenger != null) info(challenger, target.getName() + " declined your challenge.");
        info(target, "Challenge declined.");
    }

    private void spectateByName(Player player, String name) {
        Fight fight = fights.values().stream()
                .filter(row -> row.firstName.equalsIgnoreCase(name)
                        || row.secondName.equalsIgnoreCase(name))
                .findFirst().orElse(null);
        if (fight == null) error(player, "That player is not in a live fight.");
        else joinSpectator(player, fight);
    }

    private List<Invitation> incoming(UUID playerId) {
        return invitations.values().stream()
                .filter(invite -> invite.target().equals(playerId))
                .sorted(Comparator.comparingLong(Invitation::expiresAt))
                .toList();
    }

    private void expireInvitations() {
        long now = System.currentTimeMillis();
        invitations.values().removeIf(invitation -> invitation.expiresAt() <= now);
    }

    private List<Player> targets(Player viewer) {
        return Bukkit.getOnlinePlayers().stream()
                .map(player -> (Player) player)
                .filter(player -> !player.equals(viewer))
                .filter(player -> !VerificationLobbyService.isLobbyWorld(player.getWorld()))
                .filter(player -> !isParticipant(player.getUniqueId()))
                .filter(player -> acceptingChallenges(viewer, player, false))
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private boolean insideAnyArena(Location location) {
        if (location.getWorld() == null) return false;
        for (Fight fight : fights.values()) {
            Location center = fight.arena.center();
            if (location.getWorld().equals(center.getWorld())
                    && PvpDuelRules.inside(location.getX(), location.getZ(),
                            center.getX(), center.getZ(), fight.arena.diameter())) {
                return true;
            }
        }
        return false;
    }

    private void maybeExplainBlocked(Player attacker) {
        long now = System.currentTimeMillis();
        if (now - notices.getOrDefault(attacker.getUniqueId(), 0L) < NOTICE_COOLDOWN_MILLIS) {
            return;
        }
        notices.put(attacker.getUniqueId(), now);
        error(attacker, "PvP is disabled. Use /pvp to fight.");
    }

    private boolean teleport(Player player, Location location) {
        internalTeleports.add(player.getUniqueId());
        try {
            return player.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            internalTeleports.remove(player.getUniqueId());
        }
    }

    private void runLater(Player player, Consumer<Player> action) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.closeInventory();
                action.accept(player);
            }
        });
    }

    private void safeRemoveRecovery(UUID playerId) {
        try {
            store.remove(playerId);
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Could not clear recovered PvP state for "
                    + playerId + ": " + failure.getMessage());
        }
    }

    private ServerEvent.Builder record(String action, Player actor, String summary) {
        return ServerEvent.of(action, ServerEvent.CATEGORY_COMBAT,
                actor.getUniqueId(), actor.getName(), plugin::recordServerEvent).summary(summary);
    }

    private World overworld() {
        return Bukkit.getWorlds().stream()
                .filter(world -> world.getEnvironment() == World.Environment.NORMAL)
                .filter(world -> !VerificationLobbyService.isLobbyWorld(world))
                .findFirst().orElse(null);
    }

    private static Player attackingPlayer(EntityDamageByEntityEvent event) {
        Entity source = event.getDamager();
        if (source instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            source = shooter;
        }
        if (source instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Entity owner) {
            source = owner;
        }
        if (source instanceof TNTPrimed tnt && tnt.getSource() != null) {
            source = tnt.getSource();
        }
        if (source instanceof Tameable tameable && tameable.getOwner() instanceof Player owner) {
            source = owner;
        }
        return source instanceof Player player ? player : null;
    }

    private static boolean isOpponentAttack(EntityDamageByEntityEvent event, UUID opponentId) {
        Entity source = event.getDamager();
        if (source instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            source = shooter;
        }
        if (source instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Entity owner) {
            source = owner;
        }
        if (source instanceof TNTPrimed tnt && tnt.getSource() != null) {
            source = tnt.getSource();
        }
        // Tamed animals are deliberately excluded: this is the two accepted players
        // fighting with what is in their inventories, not a pet-assisted ambush.
        return source instanceof Player player && opponentId.equals(player.getUniqueId());
    }

    private static boolean samePosition(Location first, Location second) {
        return first.getWorld() == second.getWorld()
                && first.getX() == second.getX()
                && first.getY() == second.getY()
                && first.getZ() == second.getZ();
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    private static List<ItemStack> cloneItems(List<ItemStack> items) {
        if (items == null || items.isEmpty()) return List.of();
        return items.stream().filter(item -> !empty(item)).map(ItemStack::clone).toList();
    }

    private static int itemCount(List<ItemStack> items) {
        return items == null ? 0 : items.stream().filter(item -> !empty(item))
                .mapToInt(ItemStack::getAmount).sum();
    }

    private static String stakeSummary(long money, int itemStacks, int cosmeticCount) {
        return EconomyFormat.dollars(money) + " • " + itemStacks + " item stack(s) • "
                + cosmeticCount + " cosmetic(s)";
    }

    private static List<String> stakeLines(
            long money, List<ItemStack> items, List<UUID> cosmeticSerials
    ) {
        return List.of(
                "Money: " + EconomyFormat.dollars(money),
                "Items: " + itemCount(items),
                "Cosmetics: " + cosmeticSerials.size()
        );
    }

    private boolean ownsCosmetics(Player player, Iterable<UUID> serials) {
        for (UUID serial : serials) {
            if (!cosmetics.isStoredBy(player.getUniqueId(), serial)) return false;
        }
        return true;
    }

    private List<ItemStack> stakeItems(List<ItemStack> items, List<UUID> cosmeticSerials) {
        List<ItemStack> stakes = new ArrayList<>(cloneItems(items));
        for (UUID serial : cosmeticSerials) {
            CosmeticStore.Token token = cosmetics.token(serial).orElseThrow(
                    () -> new IllegalStateException("A wager cosmetic changed."));
            CosmeticCatalog.Definition definition = CosmeticCatalog.find(token.cosmeticId()).orElseThrow(
                    () -> new IllegalStateException("A wager cosmetic is unavailable."));
            stakes.add(cosmeticItems.token(definition, token));
        }
        return stakes;
    }

    private void withdrawCosmetics(Player player, Iterable<UUID> serials) {
        for (UUID serial : serials) {
            if (cosmetics.withdraw(player.getUniqueId(), serial).isEmpty()) {
                throw new IllegalStateException("A wager cosmetic changed while the fight was starting.");
            }
        }
    }

    private static boolean hasItems(Player player, List<ItemStack> expected) {
        return missingItems(player, expected).isEmpty();
    }

    private static List<ItemStack> missingItems(Player player, List<ItemStack> expected) {
        List<ItemStack> available = new ArrayList<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (!empty(item)) available.add(item.clone());
        }
        List<ItemStack> missing = new ArrayList<>();
        for (ItemStack wanted : cloneItems(expected)) {
            int remaining = wanted.getAmount();
            for (ItemStack held : available) {
                if (remaining <= 0) break;
                if (!empty(held) && held.isSimilar(wanted)) {
                    int used = Math.min(remaining, held.getAmount());
                    remaining -= used;
                    held.setAmount(held.getAmount() - used);
                }
            }
            if (remaining > 0) {
                ItemStack shortage = wanted.clone();
                shortage.setAmount(remaining);
                missing.add(shortage);
            }
        }
        return missing;
    }

    private static void takeItems(Player player, List<ItemStack> expected) {
        if (!hasItems(player, expected)) {
            throw new IllegalStateException("A wager item changed while the fight was starting.");
        }
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (ItemStack wanted : cloneItems(expected)) {
            int remaining = wanted.getAmount();
            for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
                ItemStack held = contents[slot];
                if (empty(held) || !held.isSimilar(wanted)) continue;
                int used = Math.min(remaining, held.getAmount());
                remaining -= used;
                if (used == held.getAmount()) contents[slot] = null;
                else held.setAmount(held.getAmount() - used);
            }
        }
        player.getInventory().setStorageContents(contents);
    }

    /**
     * Puts a stack into the wager area only, and hands back whatever did not fit.
     *
     * <p>Slots 45 and up are the screen's own buttons, so a stack is merged and then
     * placed across the first {@value #PAGE_SIZE} slots rather than by Bukkit's own
     * fill, which would happily bury Back under a shift-clicked stack of cobblestone.
     */
    private static ItemStack depositIntoWager(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.clone();
        for (int slot = 0; slot < PAGE_SIZE && remaining.getAmount() > 0; slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (empty(existing) || !existing.isSimilar(remaining)) continue;
            int room = existing.getMaxStackSize() - existing.getAmount();
            if (room <= 0) continue;
            int moved = Math.min(room, remaining.getAmount());
            existing.setAmount(existing.getAmount() + moved);
            inventory.setItem(slot, existing);
            remaining.setAmount(remaining.getAmount() - moved);
        }
        for (int slot = 0; slot < PAGE_SIZE && remaining.getAmount() > 0; slot++) {
            if (!empty(inventory.getItem(slot))) continue;
            int moved = Math.min(remaining.getMaxStackSize(), remaining.getAmount());
            ItemStack placed = remaining.clone();
            placed.setAmount(moved);
            inventory.setItem(slot, placed);
            remaining.setAmount(remaining.getAmount() - moved);
        }
        return remaining.getAmount() <= 0 ? null : remaining;
    }

    private static List<ItemStack> depositedItems(Inventory inventory) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (!empty(item)) result.add(item.clone());
        }
        return result;
    }

    private void returnDeposits(Player player, DuelBoard holder) {
        if (holder.depositsReturned || (holder.board != Board.SETUP_ITEMS
                && holder.board != Board.ACCEPT_ITEMS)) return;
        holder.depositsReturned = true;
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            ItemStack item = holder.inventory.getItem(slot);
            if (empty(item)) continue;
            holder.inventory.setItem(slot, null);
            giveSafely(player, item);
        }
    }

    private void reopenSetup(Player player, UUID targetId) {
        reopenSetup(player, targetId, null);
    }

    /**
     * Redraws the challenge screen, carrying a problem back onto it.
     *
     * <p>A null problem is the ordinary case — a sent challenge, or simply returning
     * from the item chest. It is also sent to chat, which is where the fallback chest
     * board's player will read it, and is harmless behind a dialog that already shows
     * the same line.
     */
    private void reopenSetup(Player player, UUID targetId, String problem) {
        Player target = Bukkit.getPlayer(targetId);
        DuelDraft draft = setupDrafts.get(player.getUniqueId());
        if (draft == null) {
            // Sent: sendChallenge clears the draft, so there is nothing to go back to.
            return;
        }
        if (problem != null) {
            error(player, problem);
        }
        if (target == null || !draft.subject.equals(targetId)) {
            error(player, "That player is no longer available.");
            openTargets(player);
            return;
        }
        openSetupScreen(player, target, draft, problem);
    }

    private void reopenAccept(Player player, UUID invitationId) {
        Invitation invitation = invitations.get(invitationId);
        if (invitation == null) {
            error(player, "That challenge expired.");
            openIncoming(player);
            return;
        }
        openInvitation(player, invitation);
    }

    private static String itemName(ItemStack item) {
        if (empty(item)) return "nothing (acceptance unavailable)";
        Component custom = item.getItemMeta() == null ? null : item.getItemMeta().displayName();
        String name = custom == null
                ? item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                : PlainTextComponentSerializer.plainText().serialize(custom);
        return item.getAmount() + "x " + name;
    }

    private static String encodeItem(ItemStack item) {
        return empty(item) ? "" : Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    private static ItemStack decodeItem(String encoded) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
    }

    private static String encodeStakeItems(List<ItemStack> items) {
        if (items == null || items.isEmpty()) return "";
        return encodeItems(items.toArray(ItemStack[]::new));
    }

    private static List<ItemStack> decodeStakeItems(String encoded) {
        if (encoded == null || encoded.isEmpty()) return List.of();
        try {
            return cloneItems(List.of(decodeItems(encoded)));
        } catch (RuntimeException legacy) {
            return List.of(decodeItem(encoded));
        }
    }

    private static String encodeItems(ItemStack[] items) {
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items));
    }

    private static ItemStack[] decodeItems(String encoded) {
        return ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(encoded));
    }

    private static ItemStack[] sized(ItemStack[] items, int size) {
        if (items.length == size) return items;
        ItemStack[] result = new ItemStack[size];
        System.arraycopy(items, 0, result, 0, Math.min(items.length, size));
        return result;
    }

    private void restoreEscrow(Player player, List<ItemStack> stakes) {
        List<ItemStack> missing = new ArrayList<>();
        for (ItemStack item : stakes) {
            CosmeticItems.TokenInfo token = cosmeticItems.read(item).orElse(null);
            if (token == null || !cosmetics.isStoredBy(player.getUniqueId(), token.serial())) {
                missing.add(item);
            }
        }
        restoreMissing(player, missing);
    }

    private void restoreMissing(Player player, List<ItemStack> stakes) {
        for (ItemStack missing : missingItems(player, stakes)) {
            CosmeticItems.TokenInfo info = cosmeticItems.read(missing).orElse(null);
            if (info != null && cosmetics.isStoredBy(player.getUniqueId(), info.serial())) continue;
            giveSafely(player, missing);
        }
        vaultLater(player);
    }

    private void vaultLater(Player player) {
        if (wardrobe == null || player == null) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) wardrobe.vaultCarried(player);
        });
    }

    private static void clearCombatLog(Player... players) {
        org.bukkit.plugin.Plugin combatLog = Bukkit.getPluginManager().getPlugin("CombatLog");
        if (combatLog == null || !combatLog.isEnabled()) return;
        for (Player player : players) {
            if (player == null) continue;
            for (String name : List.of("untag", "removeTag", "removeCombat", "endCombat")) {
                try {
                    combatLog.getClass().getMethod(name, Player.class).invoke(combatLog, player);
                    break;
                } catch (ReflectiveOperationException ignored) {
                    // Try the next known API spelling.
                }
            }
        }
    }

    private static void cancel(BukkitTask task) {
        if (task != null) task.cancel();
    }

    private static String name(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) return online.getName();
        String name = Bukkit.getOfflinePlayer(playerId).getName();
        return name == null ? "Unknown player" : name;
    }

    private static Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static Component prefix() {
        return Component.text("PVP » ", ORANGE, TextDecoration.BOLD);
    }

    private static void info(Player player, String message) {
        player.sendMessage(prefix().append(Component.text(message, NamedTextColor.GRAY)));
    }

    private static void error(Player player, String message) {
        player.sendMessage(prefix().append(Component.text(message, NamedTextColor.RED)));
    }
}
