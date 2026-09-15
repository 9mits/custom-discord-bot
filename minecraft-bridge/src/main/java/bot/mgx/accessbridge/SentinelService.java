package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static bot.mgx.accessbridge.MenuItems.ORANGE;

/**
 * Sentinel: the plugin's watch for duplication, hacking and abuse of power.
 *
 * <p>Nobody is exempt. Operators, LuckPerms owners, the console and command blocks are
 * graded exactly like everybody else, because the damage an abuser with the permission
 * can do is the damage a permission check will never see. The Discord bot receives each
 * incident on a channel the in-game settings cannot mute; a change to Sentinel's own
 * settings is itself an incident.
 *
 * <p>Detectors:
 * <ul>
 *   <li>holdings census — gains above a player's peak that no mint, pickup, container,
 *       claim or return accounts for ({@link SentinelEngine});</li>
 *   <li>duplication signatures — one cosmetic serial in two places, cloned containers,
 *       over-stacked items and impossible enchantments;</li>
 *   <li>creative copies of valuables, and switches into creative or spectator;</li>
 *   <li>high-risk commands from anyone, including the console ({@link SentinelCommands});</li>
 *   <li>LuckPerms permission changes, with who made them;</li>
 *   <li>large or rapid money creation, with the code path that created it;</li>
 *   <li>anticheat flag bursts from Grim, and ore ratios that look like X-ray.</li>
 * </ul>
 */
final class SentinelService implements Listener {
    private static final long MINUTE = 60_000L;
    private static final long POOL_TTL = 2L * MINUTE;
    private static final long PLAYER_TTL = 15L * MINUTE;
    private static final Set<Material> STONE = Set.of(Material.STONE, Material.DEEPSLATE,
            Material.TUFF, Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.NETHERRACK,
            Material.BASALT, Material.BLACKSTONE);

    private final MGXAccessBridge plugin;
    private final SentinelStore store;
    private final GameVariableStore variables;
    private final CrateItems crateItems;
    private final CosmeticItems cosmeticItems;
    private final SentinelEngine engine = new SentinelEngine();
    private final SentinelEngine.RiskLedger risk = new SentinelEngine.RiskLedger(6L * 60L * MINUTE);
    private final SentinelEngine.Deduper deduper = new SentinelEngine.Deduper();
    private final Map<UUID, String> lastCommand = new HashMap<>();
    private final Map<UUID, Long> lastCommandAt = new HashMap<>();
    private final Map<UUID, Long> lastValuableDrop = new HashMap<>();
    private final Map<UUID, Deque<long[]>> mintedMoney = new HashMap<>();
    private final Map<UUID, long[]> mining = new HashMap<>();
    private final Map<String, Deque<Long>> grimFlags = new HashMap<>();
    private boolean dirty;

    SentinelService(MGXAccessBridge plugin, SentinelStore store, GameVariableStore variables,
            CrateItems crateItems, CosmeticItems cosmeticItems) {
        this.plugin = plugin;
        this.store = store;
        this.variables = variables;
        this.crateItems = crateItems;
        this.cosmeticItems = cosmeticItems;
        store.lastKnown().forEach(engine::restoreLastKnown);
        store.risk().forEach((id, value) -> {
            try {
                if (value.length == 2) risk.restore(UUID.fromString(id), value[0], (long) value[1]);
            } catch (IllegalArgumentException ignored) {
                // a malformed id
            }
        });
    }

    private boolean enabled() {
        return variables.bool("sentinel.enabled");
    }

    void start() {
        SentinelHub.install(this);
        long census = Math.max(5L, variables.integer("sentinel.census-seconds")) * 20L;
        plugin.getServer().getScheduler().runTaskTimer(plugin, PerfMonitor.track("sentinel.census", this::census), census, census);
        plugin.getServer().getScheduler().runTaskTimer(plugin, PerfMonitor.track("sentinel.save", this::save), 1_200L, 1_200L);
        hookGrim();
        variables.onChangeSet((actor, changes) -> {
            for (GameVariableStore.Change change : changes) {
                if (change.key().startsWith("sentinel.") || change.key().startsWith("permissions.")) {
                    report(new SentinelEngine.Finding("security_settings", SentinelEngine.Severity.HIGH,
                            null, actor, "Security-relevant setting changed",
                            List.of(change.key() + ": " + change.before() + " → " + change.after(),
                                    "Changed by " + actor)));
                }
            }
        });
    }

    void stop() {
        if (censusPass != null) {
            censusPass.task.cancel();
            censusPass = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) censusOne(player, System.currentTimeMillis(), null);
        save();
        SentinelHub.install(null);
    }

    // ------------------------------------------------------------------ hub

    void minted(SentinelEngine.Kind kind, long amount, String source) {
        engine.credit(null, kind, amount, "plugin mint (" + source + ")", System.currentTimeMillis(), POOL_TTL);
    }

    void expect(UUID player, Iterable<ItemStack> items, String reason) {
        EnumMap<SentinelEngine.Kind, Long> counts = new EnumMap<>(SentinelEngine.Kind.class);
        for (ItemStack item : items) count(item, counts, 0);
        long now = System.currentTimeMillis();
        counts.forEach((kind, amount) -> engine.credit(player, kind, amount, reason, now, PLAYER_TTL));
    }

    void money(UUID player, long before, long after, String operation, String source) {
        if (!enabled() || after <= before) return;
        long gained = after - before;
        long now = System.currentTimeMillis();
        long large = variables.integer("sentinel.money-large-deposit") * 1_000_000L;
        long windowLimit = variables.integer("sentinel.money-window-limit") * 1_000_000L;
        String name = nameOf(player);
        // A direct set is only suspicious from an administration path. The duel refund
        // also writes a balance back with set(), and that is a return, not a creation.
        boolean setDirectly = operation.equals("set") && (source.startsWith("Admin")
                || source.startsWith("EconomyCommandService") || source.startsWith("ServerDataReset"));
        if (setDirectly && gained >= large / 10L) {
            report(new SentinelEngine.Finding("balance_set", SentinelEngine.Severity.HIGH, player, name,
                    "Balance set directly",
                    List.of(EconomyFormat.dollars(before) + " → " + EconomyFormat.dollars(after),
                            "Code path: " + source)));
        } else if (gained >= large) {
            report(new SentinelEngine.Finding("large_deposit", SentinelEngine.Severity.MEDIUM, player, name,
                    "Large money deposit",
                    List.of("+" + EconomyFormat.dollars(gained) + " (now " + EconomyFormat.dollars(after) + ")",
                            "Code path: " + source)));
        }
        if (operation.equals("payment")) return;
        Deque<long[]> window = mintedMoney.computeIfAbsent(player, ignored -> new ArrayDeque<>());
        window.addLast(new long[]{now, gained});
        while (!window.isEmpty() && now - window.peekFirst()[0] > 10L * MINUTE) window.removeFirst();
        long total = window.stream().mapToLong(row -> row[1]).sum();
        if (total >= windowLimit) {
            report(new SentinelEngine.Finding("money_velocity", SentinelEngine.Severity.HIGH, player, name,
                    "Money created unusually fast",
                    List.of("+" + EconomyFormat.dollars(total) + " created in 10 minutes",
                            "Latest code path: " + source)));
            window.clear();
        }
    }

    // ------------------------------------------------------------------ census

    private void census() {
        if (!enabled() || censusPass != null) return;
        java.util.ArrayDeque<UUID> queue = new java.util.ArrayDeque<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!VerificationLobbyService.isLobbyWorld(player.getWorld())) queue.add(player.getUniqueId());
        }
        if (queue.isEmpty()) return;
        CensusPass pass = new CensusPass(queue);
        censusPass = pass;
        pass.task = plugin.getServer().getScheduler().runTaskTimer(
                plugin, PerfMonitor.track("sentinel.census-step", this::censusStep), 1L, 1L);
    }

    /** Players examined per tick while a census pass runs. */
    private static final int CENSUS_PLAYERS_PER_TICK = 2;
    private CensusPass censusPass;

    /**
     * One walk over everybody online, a couple of players a tick.
     *
     * <p>It used to happen in a single tick: every inventory, ender chest and shulker on
     * the server read in one go, with several item-meta copies per stack. That is a
     * stall that grows with both the player count and how much each of them carries,
     * every thirty seconds. Spread out, a pass over fifty players takes about a second.
     */
    private static final class CensusPass {
        private final java.util.ArrayDeque<UUID> queue;
        private final Map<String, List<String>> serials = new HashMap<>();
        private final Map<String, List<String>> containers = new HashMap<>();
        /** Who held each serial or container fingerprint, for the recheck. */
        private final Map<String, java.util.Set<UUID>> owners = new HashMap<>();
        private org.bukkit.scheduler.BukkitTask task;

        private CensusPass(java.util.ArrayDeque<UUID> queue) {
            this.queue = queue;
        }
    }

    private void censusStep() {
        CensusPass pass = censusPass;
        if (pass == null) return;
        long now = System.currentTimeMillis();
        for (int examined = 0; examined < CENSUS_PLAYERS_PER_TICK && !pass.queue.isEmpty(); examined++) {
            Player player = Bukkit.getPlayer(pass.queue.poll());
            if (player == null || VerificationLobbyService.isLobbyWorld(player.getWorld())) continue;
            censusOne(player, now, pass);
        }
        if (!pass.queue.isEmpty()) return;
        pass.task.cancel();
        censusPass = null;
        finishCensus(pass);
    }

    /**
     * Reports what the pass found in two places at once, after checking it still is.
     *
     * <p>A pass spans several ticks, so an item handed from one player to another
     * between their turns was seen with both. Every suspect is looked at again in one
     * tick before anything is reported, which only ever costs the players involved.
     */
    private void finishCensus(CensusPass pass) {
        java.util.Set<UUID> suspects = new java.util.HashSet<>();
        pass.serials.forEach((serial, holders) -> {
            if (holders.size() >= 2) suspects.addAll(pass.owners.getOrDefault(serial, java.util.Set.of()));
        });
        pass.containers.forEach((fingerprint, holders) -> {
            if (holders.size() >= 2) suspects.addAll(pass.owners.getOrDefault(fingerprint, java.util.Set.of()));
        });
        if (suspects.isEmpty()) {
            dirty = true;
            return;
        }
        Map<String, List<String>> serials = new HashMap<>();
        Map<String, List<String>> containers = new HashMap<>();
        for (UUID suspect : suspects) {
            Player player = Bukkit.getPlayer(suspect);
            if (player == null) continue;
            for (ItemStack item : holdingsOf(player)) inspect(player, item, serials, containers, null, false);
        }
        serials.forEach((serial, holders) -> {
            if (holders.size() < 2) return;
            report(new SentinelEngine.Finding("duplicate_serial", SentinelEngine.Severity.CRITICAL, null,
                    String.join(", ", holders.stream().distinct().toList()),
                    "One cosmetic serial exists in two places",
                    List.of("Serial " + serial, "Seen at: " + String.join("; ", holders),
                            "A serialised cosmetic can only exist once; this is a duplicate")));
        });
        containers.forEach((fingerprint, holders) -> {
            if (holders.size() < 2) return;
            report(new SentinelEngine.Finding("cloned_container", SentinelEngine.Severity.HIGH, null,
                    String.join(", ", holders.stream().distinct().toList()),
                    "Identical containers full of valuables",
                    List.of(holders.size() + " shulker boxes with byte-identical contents",
                            "Holders: " + String.join("; ", holders),
                            "Contents include currency or serialised items")));
        });
        dirty = true;
    }

    private void censusOne(Player player, long now, CensusPass pass) {
        EnumMap<SentinelEngine.Kind, Long> holdings = new EnumMap<>(SentinelEngine.Kind.class);
        for (ItemStack item : holdingsOf(player)) {
            count(item, holdings, 0);
            if (pass != null) inspect(player, item, pass.serials, pass.containers, pass.owners, true);
        }
        List<SentinelEngine.Finding> findings = engine.census(player.getUniqueId(), player.getName(),
                holdings, now, config());
        store.setLastKnown(player.getUniqueId(), holdings);
        findings.forEach(this::report);
    }

    /** Everything a player is carrying or has put away in their ender chest. */
    private static List<ItemStack> holdingsOf(Player player) {
        List<ItemStack> everything = new ArrayList<>(80);
        java.util.Collections.addAll(everything, player.getInventory().getContents());
        java.util.Collections.addAll(everything, player.getEnderChest().getContents());
        everything.add(player.getItemOnCursor());
        var top = player.getOpenInventory().getTopInventory();
        if (top.getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) {
            java.util.Collections.addAll(everything, top.getContents());
        }
        return everything;
    }

    private SentinelEngine.Config config() {
        Map<SentinelEngine.Kind, Long> thresholds = new EnumMap<>(SentinelEngine.Kind.class);
        for (SentinelEngine.Kind kind : SentinelEngine.Kind.values()) {
            thresholds.put(kind, (long) variables.integer("sentinel.threshold." + kind.key()));
        }
        return new SentinelEngine.Config(variables.integer("sentinel.window-minutes") * MINUTE,
                thresholds, 10);
    }

    /**
     * Adds up the valuables in one stack, and in anything packed inside it.
     *
     * <p>Every read here goes to the stack's own data rather than through item meta,
     * which copies the whole item per call: this runs for every slot of every inventory
     * the census, a container opening or a pickup looks at.
     */
    private void count(ItemStack item, EnumMap<SentinelEngine.Kind, Long> counts, int depth) {
        if (item == null || item.getType().isAir() || depth > 3) return;
        long amount = item.getAmount();
        if (!item.getPersistentDataContainer().isEmpty()) {
            if (crateItems.isShard(item)) counts.merge(SentinelEngine.Kind.SHARD, amount, Long::sum);
            else if (crateItems.isMysteryKey(item)) counts.merge(SentinelEngine.Kind.MYSTERY_KEY, amount, Long::sum);
            else if (crateItems.isKey(item)) counts.merge(SentinelEngine.Kind.AMETHYST_TOKEN, amount, Long::sum);
            else if (cosmeticItems.read(item).isPresent()) counts.merge(SentinelEngine.Kind.COSMETIC, amount, Long::sum);
        }
        switch (item.getType()) {
            case NETHERITE_INGOT -> counts.merge(SentinelEngine.Kind.NETHERITE, amount * 4L, Long::sum);
            case NETHERITE_BLOCK -> counts.merge(SentinelEngine.Kind.NETHERITE, amount * 36L, Long::sum);
            case NETHERITE_SCRAP, ANCIENT_DEBRIS -> counts.merge(SentinelEngine.Kind.NETHERITE, amount, Long::sum);
            case DIAMOND -> counts.merge(SentinelEngine.Kind.DIAMOND, amount, Long::sum);
            case DIAMOND_BLOCK -> counts.merge(SentinelEngine.Kind.DIAMOND, amount * 9L, Long::sum);
            default -> { }
        }
        for (ItemStack inner : packedInside(item)) count(inner, counts, depth + 1);
    }

    /** What a shulker box, bundle or other container item holds, read straight from its data. */
    private static List<ItemStack> packedInside(ItemStack item) {
        io.papermc.paper.datacomponent.item.ItemContainerContents container =
                item.getData(io.papermc.paper.datacomponent.DataComponentTypes.CONTAINER);
        if (container != null) return container.contents();
        io.papermc.paper.datacomponent.item.BundleContents bundle =
                item.getData(io.papermc.paper.datacomponent.DataComponentTypes.BUNDLE_CONTENTS);
        return bundle == null ? List.of() : bundle.contents();
    }

    private void inspect(
            Player player, ItemStack item, Map<String, List<String>> serials,
            Map<String, List<String>> containers, Map<String, java.util.Set<UUID>> owners,
            boolean reportItems
    ) {
        if (item == null || item.getType().isAir()) return;
        String holder = player.getName();
        cosmeticItems.read(item).ifPresent(token -> {
            String key = token.cosmeticId() + "#" + token.serial();
            serials.computeIfAbsent(key, ignored -> new ArrayList<>()).add(holder);
            if (owners != null) {
                owners.computeIfAbsent(key, ignored -> new java.util.HashSet<>()).add(player.getUniqueId());
            }
        });
        if (reportItems && item.getAmount() > item.getMaxStackSize()) {
            report(new SentinelEngine.Finding("illegal_stack", SentinelEngine.Severity.HIGH,
                    player.getUniqueId(), holder, "Over-stacked item",
                    List.of(item.getAmount() + "x " + item.getType() + " in one slot (maximum "
                            + item.getMaxStackSize() + ")", "Stacks this large cannot be made in survival")));
        }
        if (reportItems) {
            String namespace = plugin.getName().toLowerCase(Locale.ROOT);
            boolean ours = item.getPersistentDataContainer().getKeys().stream()
                    .anyMatch(key -> key.getNamespace().equals(namespace));
            if (!ours) {
                for (Map.Entry<Enchantment, Integer> enchant : item.getEnchantments().entrySet()) {
                    if (enchant.getValue() > Math.max(10, enchant.getKey().getMaxLevel() + 5)) {
                        report(new SentinelEngine.Finding("illegal_enchant", SentinelEngine.Severity.HIGH,
                                player.getUniqueId(), holder, "Impossible enchantment",
                                List.of(item.getType() + " with " + enchant.getKey().getKey().getKey()
                                        + " " + enchant.getValue(), "No server source grants this level")));
                        break;
                    }
                }
            }
        }
        if (!org.bukkit.Tag.SHULKER_BOXES.isTagged(item.getType())) return;
        List<ItemStack> contents = packedInside(item);
        if (contents.isEmpty()) return;
        EnumMap<SentinelEngine.Kind, Long> inside = new EnumMap<>(SentinelEngine.Kind.class);
        for (ItemStack inner : contents) count(inner, inside, 1);
        long currency = inside.entrySet().stream().filter(entry -> !entry.getKey().vanilla())
                .mapToLong(Map.Entry::getValue).sum();
        if (currency >= 64L) {
            try {
                byte[] bytes = ItemStack.serializeItemsAsBytes(contents);
                String fingerprint = java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                containers.computeIfAbsent(fingerprint, ignored -> new ArrayList<>()).add(holder);
                if (owners != null) {
                    owners.computeIfAbsent(fingerprint, ignored -> new java.util.HashSet<>())
                            .add(player.getUniqueId());
                }
            } catch (java.security.NoSuchAlgorithmException | RuntimeException ignored) {
                // a box that cannot be fingerprinted is simply not compared
            }
        }
    }

    // ------------------------------------------------------------------ credits

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Item item = event.getItem();
        UUID thrower = item.getThrower();
        String source = thrower == null ? "a ground pickup at " + place(item.getLocation())
                : "a pickup of " + nameOf(thrower) + "'s drop";
        expect(player.getUniqueId(), List.of(item.getItemStack()), source);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof BlockState) && !(holder instanceof DoubleChest) && !(holder instanceof Entity)) {
            return;
        }
        Location at = event.getInventory().getLocation();
        List<ItemStack> contents = new ArrayList<>();
        for (ItemStack item : event.getInventory().getContents()) contents.add(item);
        expect(player.getUniqueId(), contents, "a container at " + (at == null ? "an entity" : place(at)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Material type = event.getBlock().getType();
        // Not a snapshot: this runs for every block broken on the server, and a snapshot
        // copies the block entity's whole contents first.
        if (event.getBlock().getState(false) instanceof Container container) {
            List<ItemStack> contents = new ArrayList<>();
            for (ItemStack item : container.getInventory().getContents()) contents.add(item);
            expect(player.getUniqueId(), contents, "a broken container at " + place(event.getBlock().getLocation()));
            if (container instanceof ShulkerBox) {
                expect(player.getUniqueId(), event.getBlock().getDrops(player.getInventory().getItemInMainHand()),
                        "a broken shulker box");
            }
        }
        long now = System.currentTimeMillis();
        if (type == Material.DIAMOND_ORE || type == Material.DEEPSLATE_DIAMOND_ORE) {
            engine.credit(player.getUniqueId(), SentinelEngine.Kind.DIAMOND, 5L, "mining", now, PLAYER_TTL);
            xray(player, 1, 0, 0);
        } else if (type == Material.ANCIENT_DEBRIS) {
            engine.credit(player.getUniqueId(), SentinelEngine.Kind.NETHERITE, 1L, "mining", now, PLAYER_TTL);
            xray(player, 0, 1, 0);
        } else if (STONE.contains(type)) {
            xray(player, 0, 0, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFurnace(FurnaceExtractEvent event) {
        Material type = event.getItemType();
        long amount = event.getItemAmount();
        long now = System.currentTimeMillis();
        if (type == Material.NETHERITE_SCRAP) {
            engine.credit(event.getPlayer().getUniqueId(), SentinelEngine.Kind.NETHERITE, amount, "smelting", now, PLAYER_TTL);
        } else if (type == Material.DIAMOND) {
            engine.credit(event.getPlayer().getUniqueId(), SentinelEngine.Kind.DIAMOND, amount, "smelting", now, PLAYER_TTL);
        }
    }

    // ------------------------------------------------------------------ privilege

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreative(InventoryCreativeEvent event) {
        if (!enabled() || !(event.getWhoClicked() instanceof Player player)) return;
        ItemStack cursor = event.getCursor();
        EnumMap<SentinelEngine.Kind, Long> counts = new EnumMap<>(SentinelEngine.Kind.class);
        count(cursor, counts, 0);
        if (counts.isEmpty()) return;
        boolean currency = counts.keySet().stream().anyMatch(kind -> !kind.vanilla());
        List<String> evidence = new ArrayList<>();
        counts.forEach((kind, amount) -> evidence.add(kind.amount(amount)));
        evidence.add("Taken from the creative inventory at " + place(player.getLocation()));
        report(new SentinelEngine.Finding("creative_copy",
                currency ? SentinelEngine.Severity.CRITICAL : SentinelEngine.Severity.HIGH,
                player.getUniqueId(), player.getName(),
                currency ? "Creative copy of a valuable" : "Creative spawn of valuables", evidence));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        if (!enabled()) return;
        GameMode mode = event.getNewGameMode();
        if (mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR) return;
        Player player = event.getPlayer();
        Long at = lastCommandAt.get(player.getUniqueId());
        String recent = at != null && System.currentTimeMillis() - at < 3_000L
                ? lastCommand.get(player.getUniqueId()) : null;
        boolean screenshot = plugin.inScreenshotMode(player)
                || (recent != null && recent.toLowerCase(Locale.ROOT).contains("devblog"));
        String cause = screenshot ? "Screenshot mode (/mgxadmin devblog)"
                : recent != null ? "After running " + recent
                : "Changed by another player, the console or a plugin";
        report(new SentinelEngine.Finding("gamemode", screenshot ? SentinelEngine.Severity.MEDIUM
                : SentinelEngine.Severity.HIGH, player.getUniqueId(),
                player.getName(), "Switched to " + mode.name().toLowerCase(Locale.ROOT),
                List.of(cause, "At " + place(player.getLocation()))));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String line = event.getMessage();
        lastCommand.put(player.getUniqueId(), line.length() > 120 ? line.substring(0, 120) : line);
        lastCommandAt.put(player.getUniqueId(), System.currentTimeMillis());
        SentinelCommands.Grade grade = SentinelCommands.grade(line);
        if (grade == null || !enabled()) return;
        String label = line.strip().replaceFirst("^/+", "").split("\\s+")[0];
        Command command = Bukkit.getCommandMap().getCommand(label);
        boolean allowed = command == null || command.testPermissionSilent(player);
        if (!allowed) return;
        graded(grade, player.getUniqueId(), player.getName(), line, event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsole(ServerCommandEvent event) {
        SentinelCommands.Grade grade = SentinelCommands.grade(event.getCommand());
        if (grade == null || !enabled()) return;
        CommandSender sender = event.getSender();
        String actor;
        SentinelCommands.Grade effective = grade;
        if (sender instanceof BlockCommandSender block) {
            actor = "Command block at " + place(block.getBlock().getLocation());
            effective = new SentinelCommands.Grade(grade.severity().raised(), grade.category(), grade.reason());
        } else if (sender instanceof RemoteConsoleCommandSender) {
            actor = "RCON";
        } else if (sender instanceof ConsoleCommandSender) {
            actor = "Console";
        } else {
            actor = sender.getName();
        }
        graded(effective, null, actor, event.getCommand(), event.isCancelled());
    }

    private void graded(SentinelCommands.Grade grade, UUID player, String actor, String line, boolean cancelled) {
        String shown = line.length() > 180 ? line.substring(0, 180) + "…" : line;
        if (grade.severity().ordinal() >= SentinelEngine.Severity.HIGH.ordinal()) {
            report(new SentinelEngine.Finding("command_" + grade.category().toLowerCase(Locale.ROOT)
                    .replace(' ', '_'), grade.severity(), player, actor, grade.reason(),
                    List.of("Command: " + shown, cancelled ? "Blocked before it ran" : "Executed")));
        } else {
            // Everyday staff commands go to the normal admin log, not the security feed.
            ServerEvent.of("staff_command", ServerEvent.CATEGORY_ADMIN, player, actor, plugin::recordServerEvent)
                    .summary(actor + " used " + shown)
                    .detail("category", grade.category())
                    .detail("risk", grade.severity().name().toLowerCase(Locale.ROOT))
                    .record();
        }
    }

    /** LuckPerms' own action log: every permission change and who made it. */
    void luckPermsAction(String source, String target, String description) {
        if (!enabled()) return;
        String lower = description.toLowerCase(Locale.ROOT);
        boolean grant = lower.contains("permission set") || lower.contains("parent add")
                || lower.contains("parent set") || lower.contains("promote") || lower.contains("setinherit")
                || lower.contains("permission settemp") || lower.contains("parent addtemp");
        if (!grant && !lower.contains("clear") && !lower.contains("delete")) {
            ServerEvent.of("luckperms_action", ServerEvent.CATEGORY_ADMIN, null, source, plugin::recordServerEvent)
                    .summary(source + " → " + target + ": " + description)
                    .record();
            return;
        }
        boolean powerful = lower.contains("*") || lower.contains("admin") || lower.contains("owner")
                || lower.contains("op") || lower.contains("mgxaccessbridge") || lower.contains("luckperms");
        report(new SentinelEngine.Finding("luckperms", powerful ? SentinelEngine.Severity.CRITICAL
                : SentinelEngine.Severity.HIGH, null, source,
                powerful ? "Powerful permission granted" : "Permissions changed",
                List.of("By " + source + " on " + target, "Action: " + description)));
    }

    // ------------------------------------------------------------------ hacking

    private void xray(Player player, int diamonds, int debris, int stone) {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        long[] row = mining.computeIfAbsent(player.getUniqueId(), ignored -> new long[]{now, 0, 0, 0});
        if (now - row[0] > 30L * MINUTE) {
            row[0] = now;
            row[1] = row[2] = row[3] = 0;
        }
        row[1] += diamonds;
        row[2] += debris;
        row[3] += stone;
        int minimum = variables.integer("sentinel.xray-minimum-finds");
        if (diamonds > 0 && row[1] >= minimum && row[3] < row[1] * 25L) {
            xrayFinding(player, row[1] + " diamond ore", row[3]);
            row[1] = 0;
        } else if (debris > 0 && row[2] >= Math.max(4, minimum / 2) && row[3] < row[2] * 30L) {
            xrayFinding(player, row[2] + " ancient debris", row[3]);
            row[2] = 0;
        }
    }

    private void xrayFinding(Player player, String finds, long stone) {
        report(new SentinelEngine.Finding("xray_pattern", SentinelEngine.Severity.MEDIUM, player.getUniqueId(),
                player.getName(), "Ore finds look like X-ray",
                List.of(finds + " in 30 minutes while breaking only " + stone + " stone-type blocks",
                        "Normal mining breaks far more rock per find",
                        "Last at " + place(player.getLocation()))));
    }

    /** Subscribes to Grim's flag event by reflection, so Grim stays an optional plugin. */
    private void hookGrim() {
        Plugin grim = Bukkit.getPluginManager().getPlugin("GrimAC");
        if (grim == null) return;
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Event> flagClass = (Class<? extends Event>) Class.forName(
                    "ac.grim.grimac.api.events.FlagEvent", true, grim.getClass().getClassLoader());
            Method user = flagClass.getMethod("getUser");
            Method check = flagClass.getMethod("getCheck");
            Bukkit.getPluginManager().registerEvent(flagClass, this, EventPriority.MONITOR, (listener, event) -> {
                if (!flagClass.isInstance(event)) return;
                try {
                    Object grimUser = user.invoke(event);
                    Object grimCheck = check.invoke(event);
                    UUID id = (UUID) grimUser.getClass().getMethod("getUniqueId").invoke(grimUser);
                    String name = String.valueOf(grimUser.getClass().getMethod("getName").invoke(grimUser));
                    String checkName = String.valueOf(grimCheck.getClass().getMethod("getCheckName").invoke(grimCheck));
                    boolean experimental = Boolean.TRUE.equals(
                            grimCheck.getClass().getMethod("isExperimental").invoke(grimCheck));
                    if (!experimental) grimFlag(id, name, checkName);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // an API change degrades to no Grim alerts, never to an exception in Grim
                }
            }, plugin, true);
            plugin.getLogger().info("Sentinel is watching GrimAC flags.");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            plugin.getLogger().warning("Sentinel could not hook GrimAC: " + failure.getMessage());
        }
    }

    private void grimFlag(UUID player, String name, String check) {
        if (!enabled()) return;
        String lower = check.toLowerCase(Locale.ROOT);
        if (lower.contains("place") || lower.contains("printer")) return;
        boolean combat = lower.contains("reach") || lower.contains("aim") || lower.contains("killaura")
                || lower.contains("autoclicker") || lower.contains("hitbox") || lower.contains("multiinteract");
        long now = System.currentTimeMillis();
        Deque<Long> flags = grimFlags.computeIfAbsent(player + ":" + check, ignored -> new ArrayDeque<>());
        flags.addLast(now);
        while (!flags.isEmpty() && now - flags.peekFirst() > 5L * MINUTE) flags.removeFirst();
        int limit = variables.integer(combat ? "sentinel.grim-combat-flags" : "sentinel.grim-other-flags");
        if (flags.size() >= limit) {
            report(new SentinelEngine.Finding("anticheat", combat ? SentinelEngine.Severity.HIGH
                    : SentinelEngine.Severity.MEDIUM, player, name, "Anticheat flag burst: " + check,
                    List.of(flags.size() + " GrimAC " + check + " flags in 5 minutes",
                            combat ? "Combat checks rarely false-flag in bursts" : "Movement or packet check")));
            flags.clear();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        EnumMap<SentinelEngine.Kind, Long> counts = new EnumMap<>(SentinelEngine.Kind.class);
        count(event.getItemDrop().getItemStack(), counts, 0);
        if (counts.keySet().stream().anyMatch(kind -> !kind.vanilla())) {
            lastValuableDrop.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && enabled()) censusOne(player, System.currentTimeMillis(), null);
        }, 60L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (enabled()) {
            censusOne(player, System.currentTimeMillis(), null);
            Long dropped = lastValuableDrop.remove(id);
            if (dropped != null && System.currentTimeMillis() - dropped < 2_000L) {
                report(new SentinelEngine.Finding("disconnect_after_drop", SentinelEngine.Severity.MEDIUM, id,
                        player.getName(), "Disconnected right after dropping valuables",
                        List.of("Left within 2 seconds of dropping currency or a cosmetic",
                                "This is the timing a disconnect duplication relies on",
                                "At " + place(player.getLocation()))));
            }
        }
        engine.quit(id);
        mining.remove(id);
        mintedMoney.remove(id);
        lastCommand.remove(id);
        lastCommandAt.remove(id);
    }

    // ------------------------------------------------------------------ reporting

    void report(SentinelEngine.Finding finding) {
        if (!enabled() && !finding.rule().equals("security_settings")) return;
        long now = System.currentTimeMillis();
        String subject = finding.player() == null ? finding.playerName() : finding.player().toString();
        int occurrences = deduper.admit(finding.rule() + "|" + subject + "|" + finding.title(), now,
                variables.integer("sentinel.repeat-cooldown-minutes") * MINUTE);
        if (occurrences == 0) return;
        SentinelEngine.Severity severity = finding.severity();
        double score = 0d;
        if (finding.player() != null) {
            score = risk.add(finding.player(), severity, now);
            if (score >= variables.integer("sentinel.escalate-risk") && severity != SentinelEngine.Severity.CRITICAL) {
                severity = severity.raised();
            }
        }
        SentinelStore.Incident incident = new SentinelStore.Incident();
        incident.id = UUID.randomUUID().toString().substring(0, 8);
        incident.rule = finding.rule();
        incident.severity = severity.name();
        incident.player = finding.player() == null ? "" : finding.player().toString();
        incident.playerName = finding.playerName();
        incident.title = finding.title();
        incident.evidence = new ArrayList<>(finding.evidence());
        incident.at = now;
        incident.risk = Math.round(score * 10d) / 10d;
        store.add(incident);
        dirty = true;

        ServerEvent.Builder builder = ServerEvent.of("security_" + finding.rule(), ServerEvent.CATEGORY_SECURITY,
                finding.player(), finding.playerName(), plugin::recordServerEvent)
                .summary(finding.title())
                .detail("severity", severity.name())
                .detail("incident", incident.id)
                .detail("risk", String.format(Locale.ROOT, "%.1f", score));
        if (occurrences > 1) builder.detail("repeats", String.valueOf(occurrences - 1));
        int index = 1;
        for (String line : finding.evidence()) {
            if (index > 8) break;
            builder.detail("evidence_" + index++, line);
        }
        builder.record();
        plugin.getLogger().warning("[Sentinel] " + severity + " " + finding.title() + " — "
                + finding.playerName() + " — " + String.join(" | ", finding.evidence()));

        if (variables.bool("sentinel.in-game-alerts") && severity.ordinal() >= SentinelEngine.Severity.HIGH.ordinal()) {
            Component line = Component.text("SENTINEL » ", ORANGE, TextDecoration.BOLD)
                    .append(Component.text(severity.name() + " ", severity == SentinelEngine.Severity.CRITICAL
                            ? NamedTextColor.RED : NamedTextColor.GOLD, TextDecoration.BOLD))
                    .append(Component.text(finding.title() + " — " + finding.playerName(), NamedTextColor.WHITE));
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getUniqueId().equals(finding.player())) continue;
                if (online.hasPermission("mgxaccessbridge.sentinel.alerts")) online.sendMessage(line);
            }
        }
    }

    List<SentinelStore.Incident> recentIncidents(int limit) {
        return store.recent(limit);
    }

    private void save() {
        if (!dirty) return;
        try {
            store.setRisk(risk.export());
            store.persist();
            dirty = false;
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Could not save Sentinel state: " + failure.getMessage());
        }
    }

    private static String nameOf(UUID player) {
        String name = Bukkit.getOfflinePlayer(player).getName();
        return name == null ? player.toString().substring(0, 8) : name;
    }

    private static String place(Location at) {
        if (at == null || at.getWorld() == null) return "an unknown place";
        return at.getWorld().getName() + " " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ();
    }
}
