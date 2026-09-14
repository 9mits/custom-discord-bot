package bot.mgx.accessbridge;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static bot.mgx.accessbridge.MenuItems.ORANGE;

/**
 * The Season Pass and its quests.
 *
 * <p>XP comes mostly from three daily and three weekly quests that are the same for
 * everyone, plus a little for every active minute. Every tier pays automatically the
 * moment it is reached, so nothing is ever left unclaimed; the last tiers pay chase
 * cosmetics. A season runs for a fixed number of days, then its top three are paid,
 * remembered, and everybody starts the next one from tier zero.
 *
 * <p>Progress that is cheap to fake does not count: AFK time, ore mined with Silk Touch
 * (which can be placed and mined again forever), and private duels (which two friends
 * could trade wins in). Competitive PvP already refuses linked accounts and repeated
 * pairings, so its results are what the PvP quests read.
 */
final class SeasonPassService implements Listener, CommandExecutor {
    private static final long PULSE_TICKS = 20L * 60L;
    private static final int RULE_WIDTH = 480;
    private static final Set<Material> ORES = Set.of(
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE, Material.IRON_ORE,
            Material.DEEPSLATE_IRON_ORE, Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.REDSTONE_ORE,
            Material.DEEPSLATE_REDSTONE_ORE, Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE, Material.DIAMOND_ORE,
            Material.DEEPSLATE_DIAMOND_ORE, Material.NETHER_GOLD_ORE, Material.NETHER_QUARTZ_ORE,
            Material.ANCIENT_DEBRIS
    );
    private static final Set<Material> CROPS = Set.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
            Material.NETHER_WART, Material.COCOA
    );

    private final MGXAccessBridge plugin;
    private final SeasonStore store;
    private final GameVariableStore variables;
    private final CrateItems items;
    private final SettingsClientSupport clientSupport;
    private final BedrockForms forms;
    private boolean dirty;

    SeasonPassService(
            MGXAccessBridge plugin, SeasonStore store, GameVariableStore variables, CrateItems items,
            SettingsClientSupport clientSupport, BedrockForms forms
    ) {
        this.plugin = plugin;
        this.store = store;
        this.variables = variables;
        this.items = items;
        this.clientSupport = clientSupport;
        this.forms = forms;
    }

    void start() {
        ensureSeason(today());
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::pulse, PULSE_TICKS, PULSE_TICKS);
    }

    private boolean enabled() {
        return variables.bool("season.enabled");
    }

    static long today() {
        return LocalDate.now(ZoneOffset.UTC).toEpochDay();
    }

    private int xpPerTier() {
        return variables.integer("season.xp-per-tier");
    }

    private int maximumTier() {
        return variables.integer("season.tiers");
    }

    /** The tier a player holds this season, for the sidebar. */
    int tier(UUID playerId) {
        return SeasonPassRules.tier(store.row(playerId).xp, xpPerTier(), maximumTier());
    }

    // ------------------------------------------------------------------ progress

    /** Records progress towards any quest of this type on today's and this week's board. */
    void progress(Player player, SeasonPassRules.QuestType type, long amount) {
        if (!enabled() || amount <= 0L || player == null
                || VerificationLobbyService.isLobbyWorld(player.getWorld())) return;
        long today = today();
        SeasonStore.Row row = rowFor(player, today);
        for (SeasonPassRules.Quest quest : board(today)) {
            if (quest.type() != type) continue;
            Map<String, Long> progress = quest.weekly() ? row.weekly : row.daily;
            Set<String> done = quest.weekly() ? row.weeklyDone : row.dailyDone;
            if (done.contains(quest.id())) continue;
            long now = Math.min(quest.target(), progress.getOrDefault(quest.id(), 0L) + amount);
            progress.put(quest.id(), now);
            if (now >= quest.target()) {
                done.add(quest.id());
                player.showTitle(Title.title(
                        Component.text("QUEST COMPLETE", NamedTextColor.GREEN, TextDecoration.BOLD),
                        Component.text(quest.label() + "  •  +" + quest.xp() + " XP", NamedTextColor.GOLD),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1800), Duration.ofMillis(300))));
                player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.4f);
                info(player, (quest.weekly() ? "Weekly" : "Daily") + " quest complete: "
                        + quest.label() + ". +" + quest.xp() + " Season XP.");
                addXp(player, row, quest.xp());
            }
            dirty = true;
        }
    }

    /** Season XP from outside the quests, such as a claimed login streak day. */
    void bonusXp(Player player, long xp) {
        if (!enabled() || xp <= 0L) return;
        addXp(player, rowFor(player, today()), xp);
        dirty = true;
    }

    private SeasonStore.Row rowFor(Player player, long today) {
        SeasonStore.Row row = store.row(player.getUniqueId());
        row.name = player.getName();
        if (row.dailyPeriod != today) {
            row.dailyPeriod = today;
            row.daily.clear();
            row.dailyDone.clear();
        }
        long week = SeasonPassRules.weekStart(today);
        if (row.weeklyPeriod != week) {
            row.weeklyPeriod = week;
            row.weekly.clear();
            row.weeklyDone.clear();
        }
        return row;
    }

    private List<SeasonPassRules.Quest> board(long today) {
        List<SeasonPassRules.Quest> all = new ArrayList<>(
                SeasonPassRules.quests(today, false, SeasonPassRules.DAILY_QUESTS));
        all.addAll(SeasonPassRules.quests(SeasonPassRules.weekStart(today), true,
                SeasonPassRules.WEEKLY_QUESTS));
        return all;
    }

    private void addXp(Player player, SeasonStore.Row row, long xp) {
        row.xp += xp;
        grantReachedTiers(player, row);
    }

    /**
     * Pays every tier reached but not yet paid. Rewards land in the inventory, so they
     * wait while PvP or screenshot mode holds it; the next pulse catches up.
     */
    private void grantReachedTiers(Player player, SeasonStore.Row row) {
        if (plugin.inPvpDuel(player) || plugin.inScreenshotMode(player)
                || (plugin.pvpCompetition() != null
                && plugin.pvpCompetition().isParticipant(player.getUniqueId()))) {
            return;
        }
        int after = SeasonPassRules.tier(row.xp, xpPerTier(), maximumTier());
        for (int tier = row.grantedTier + 1; tier <= after; tier++) {
            row.grantedTier = tier;
            List<SeasonPassRules.Grant> grants = grants(tier);
            pay(player, grants);
            player.showTitle(Title.title(
                    Component.text("SEASON TIER " + tier, ORANGE, TextDecoration.BOLD),
                    Component.text(describe(grants), NamedTextColor.GOLD),
                    Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(2200), Duration.ofMillis(400))));
            player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.1f);
            info(player, "Tier " + tier + " reached: " + describe(grants) + ".");
            if (tier == maximumTier()) {
                Component shout = Component.text("★ ", ORANGE)
                        .append(Component.text(player.getName(), NamedTextColor.WHITE, TextDecoration.BOLD))
                        .append(Component.text(" completed the Season " + store.season() + " Pass!",
                                NamedTextColor.GOLD));
                plugin.getServer().getOnlinePlayers().forEach(online -> online.sendMessage(shout));
            }
        }
    }

    private void pulse() {
        long today = today();
        if (ensureSeason(today)) save();
        if (!enabled()) return;
        int perMinute = variables.integer("season.xp-per-active-minute");
        for (Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            if (VerificationLobbyService.isLobbyWorld(player.getWorld())) continue;
            if (plugin.afkService() != null && plugin.afkService().isAfk(player.getUniqueId())) continue;
            progress(player, SeasonPassRules.QuestType.PLAY_MINUTES, 1L);
            SeasonStore.Row row = rowFor(player, today);
            if (perMinute > 0) {
                addXp(player, row, perMinute);
                dirty = true;
            } else {
                grantReachedTiers(player, row);
            }
        }
        if (dirty) save();
    }

    // ------------------------------------------------------------------ rewards

    List<SeasonPassRules.Grant> grants(int tier) {
        String key = SeasonPassRules.rewardKey(tier, candidate -> variables.find(candidate).isPresent());
        return SeasonPassRules.parse(variables.string(key));
    }

    static String describe(List<SeasonPassRules.Grant> grants) {
        List<String> parts = new ArrayList<>();
        for (SeasonPassRules.Grant grant : grants) {
            switch (grant.kind()) {
                case "keys" -> parts.add(grant.amount() + (grant.amount() == 1 ? " Key" : " Keys"));
                case "shards" -> parts.add(grant.amount() + (grant.amount() == 1 ? " Shard" : " Shards"));
                case "money" -> parts.add(EconomyFormat.dollars(grant.amount()));
                case "cosmetic" -> parts.add(CosmeticCatalog.find(grant.id())
                        .map(CosmeticCatalog.Definition::displayName).orElse(grant.id()));
                case "reward" -> parts.add(CrateCatalog.find(grant.id())
                        .map(CrateCatalog.Reward::displayName).orElse(grant.id()));
                default -> { }
            }
        }
        return parts.isEmpty() ? "Season progress" : String.join(" + ", parts);
    }

    private void pay(Player player, List<SeasonPassRules.Grant> grants) {
        for (SeasonPassRules.Grant grant : grants) {
            try {
                switch (grant.kind()) {
                    case "keys" -> {
                        if (plugin.crateService() != null) {
                            plugin.crateService().grantKeys(player, (int) Math.min(256, grant.amount()));
                        }
                    }
                    case "shards" -> giveShards(player, (int) Math.min(640, grant.amount()));
                    case "money" -> {
                        if (plugin.economy().canDeposit(player.getUniqueId(), grant.amount())) {
                            plugin.economy().deposit(player.getUniqueId(), grant.amount());
                        }
                    }
                    case "cosmetic" -> CosmeticCatalog.find(grant.id()).ifPresent(definition -> {
                        plugin.cosmetics().mint(player.getUniqueId(), definition.id(), UUID.randomUUID());
                        info(player, definition.displayName() + " is in your /wardrobe.");
                    });
                    case "reward" -> CrateCatalog.find(grant.id()).ifPresent(reward -> {
                        if (reward.cosmetic()) {
                            plugin.cosmetics().mint(player.getUniqueId(), reward.cosmeticId(), UUID.randomUUID());
                        } else {
                            give(player, items.reward(reward));
                        }
                    });
                    default -> { }
                }
            } catch (RuntimeException failure) {
                plugin.getLogger().warning("Could not pay a Season Pass " + grant.kind()
                        + " reward to " + player.getName() + ": " + failure.getMessage());
            }
        }
    }

    private void giveShards(Player player, int shards) {
        for (int left = shards; left > 0; left -= 64) give(player, items.shard(Math.min(64, left)));
    }

    private static void give(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(spill -> player.getWorld().dropItemNaturally(player.getLocation(), spill));
    }

    // ------------------------------------------------------------------ seasons

    /** Starts the first season, or ends a finished one and starts the next. */
    private boolean ensureSeason(long today) {
        long length = Math.max(1, variables.integer("season.length-days"));
        if (store.season() <= 0) {
            store.startSeason(1, today, today + length);
            return true;
        }
        if (today < store.endsDay()) return false;
        endSeason(today);
        store.startSeason(store.season() + 1, today, today + length);
        Component line = Component.text("SEASON " + store.season() + " HAS STARTED", ORANGE, TextDecoration.BOLD)
                .append(Component.text("  •  Every tier is back on the table. /pass", NamedTextColor.WHITE));
        plugin.getServer().getOnlinePlayers().forEach(player -> player.sendMessage(line));
        plugin.pingDiscord("ping_event_live", "Season " + store.season() + " has started",
                Map.of("event", "Season " + store.season() + " Pass"));
        return true;
    }

    private void endSeason(long today) {
        List<Map.Entry<UUID, SeasonStore.Row>> top = store.ranking(3);
        SeasonStore.Podium podium = new SeasonStore.Podium();
        podium.season = store.season();
        podium.endedDay = today;
        int[] prizes = {
                variables.integer("season.first-place-shards"),
                variables.integer("season.second-place-shards"),
                variables.integer("season.third-place-shards")
        };
        for (int place = 0; place < top.size(); place++) {
            SeasonStore.Row row = top.get(place).getValue();
            podium.names.add(row.name);
            podium.xp.add(row.xp);
            Player online = plugin.getServer().getPlayer(top.get(place).getKey());
            if (online != null) {
                giveShards(online, prizes[place]);
                info(online, "You finished #" + (place + 1) + " in Season " + podium.season
                        + " and earned " + prizes[place] + " Shards.");
            } else {
                row.owedShards += prizes[place];
            }
        }
        store.archive(podium);
        Component header = Component.text("SEASON " + podium.season + " IS OVER", ORANGE, TextDecoration.BOLD);
        plugin.getServer().getOnlinePlayers().forEach(player -> {
            player.sendMessage(Component.empty());
            player.sendMessage(header);
            for (int place = 0; place < podium.names.size(); place++) {
                player.sendMessage(Component.text("#" + (place + 1) + " " + podium.names.get(place)
                        + "  •  " + String.format(Locale.ROOT, "%,d", podium.xp.get(place)) + " XP",
                        NamedTextColor.WHITE));
            }
            player.sendMessage(Component.empty());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !enabled()) return;
            SeasonStore.Row row = store.row(player.getUniqueId());
            if (row.owedShards > 0) {
                giveShards(player, row.owedShards);
                info(player, "Your Season podium prize arrived: " + row.owedShards + " Shards.");
                row.owedShards = 0;
                dirty = true;
                save();
            }
        }, 120L);
    }

    // ------------------------------------------------------------------ hooks

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null && event.getEntity() instanceof Enemy && !afk(killer)) {
            progress(killer, SeasonPassRules.QuestType.KILL_MOBS, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Material type = block.getType();
        if (ORES.contains(type)) {
            ItemStack tool = player.getInventory().getItemInMainHand();
            // Silk Touch ore can be placed and mined again for ever, so it never counts.
            if (!tool.containsEnchantment(Enchantment.SILK_TOUCH)) {
                progress(player, SeasonPassRules.QuestType.MINE_ORES, 1L);
            }
        } else if (CROPS.contains(type) && block.getBlockData() instanceof Ageable crop
                && crop.getAge() >= crop.getMaximumAge()) {
            progress(player, SeasonPassRules.QuestType.HARVEST_CROPS, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            progress(event.getPlayer(), SeasonPassRules.QuestType.CATCH_FISH, 1L);
        }
    }

    private boolean afk(Player player) {
        return plugin.afkService() != null && plugin.afkService().isAfk(player.getUniqueId());
    }

    // ------------------------------------------------------------------ pages

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is available to players only.");
            return true;
        }
        if (!enabled()) {
            player.sendMessage(Component.text("The Season Pass is switched off right now.", NamedTextColor.RED));
            return true;
        }
        if (command.getName().equalsIgnoreCase("quests")) openQuests(player, null);
        else openPass(player);
        return true;
    }

    void openPass(Player player) {
        long today = today();
        SeasonStore.Row row = rowFor(player, today);
        int tier = SeasonPassRules.tier(row.xp, xpPerTier(), maximumTier());
        long into = SeasonPassRules.xpIntoTier(row.xp, xpPerTier(), maximumTier());
        String progress = tier >= maximumTier() ? "Pass complete"
                : String.format(Locale.ROOT, "%,d / %,d XP to Tier %d", into, xpPerTier(), tier + 1);
        List<DialogBody> page = new ArrayList<>(List.of(
                DialogBody.plainMessage(MenuText.stat("Season", "Season " + store.season()
                        + "  •  ends " + endsIn()), RULE_WIDTH),
                DialogBody.plainMessage(MenuText.stat("Tier", tier + " / " + maximumTier()), RULE_WIDTH),
                DialogBody.plainMessage(MenuText.stat("Progress", progress), RULE_WIDTH),
                DialogBody.plainMessage(Component.empty(), RULE_WIDTH)));
        StringBuilder plain = new StringBuilder("Season " + store.season() + " • Tier " + tier + "/"
                + maximumTier() + " • " + progress + "\n\nNext rewards:\n");
        for (int next = tier + 1; next <= Math.min(maximumTier(), tier + 5); next++) {
            String reward = describe(grants(next));
            page.add(DialogBody.plainMessage(MenuText.stat("Tier " + next,
                    next % 10 == 0 ? "item/nether_star" : "item/bundle", reward), RULE_WIDTH));
            plain.append("Tier ").append(next).append(": ").append(reward).append('\n');
        }
        page.add(DialogBody.plainMessage(Component.empty(), RULE_WIDTH));
        page.add(DialogBody.plainMessage(MenuText.muted("Every tier pays the moment you reach it."
                + " Earn XP from daily and weekly quests and every active minute."), RULE_WIDTH));
        List<Action> actions = List.of(
                new Action("item/writable_book", "Quests", "Today's and this week's quests.",
                        viewer -> openQuests(viewer, this::openPass)),
                new Action("item/bundle", "Every Reward", "All " + maximumTier() + " tiers.",
                        viewer -> openRewards(viewer, 1)),
                new Action("item/gold_ingot", "Season Top", "The highest Season XP right now.",
                        this::openTop));
        show(player, "Season " + store.season() + " Pass", page, plain.toString(), actions, null);
    }

    void openQuests(Player player, Consumer<Player> back) {
        long today = today();
        SeasonStore.Row row = rowFor(player, today);
        List<DialogBody> page = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        for (boolean weekly : new boolean[]{false, true}) {
            String heading = weekly ? "Weekly  •  resets " + weeklyResetIn() : "Daily  •  resets " + dailyResetIn();
            page.add(DialogBody.plainMessage(MenuText.rule(weekly ? "item/clock_00" : "item/filled_map",
                    heading, weekly ? "Bigger goals, bigger XP." : "Three new quests every day."), RULE_WIDTH));
            plain.append(heading).append('\n');
            for (SeasonPassRules.Quest quest : weekly
                    ? SeasonPassRules.quests(SeasonPassRules.weekStart(today), true, SeasonPassRules.WEEKLY_QUESTS)
                    : SeasonPassRules.quests(today, false, SeasonPassRules.DAILY_QUESTS)) {
                Map<String, Long> progress = weekly ? row.weekly : row.daily;
                boolean done = (weekly ? row.weeklyDone : row.dailyDone).contains(quest.id());
                long value = progress.getOrDefault(quest.id(), 0L);
                String state = done ? "Complete ✔" : String.format(Locale.ROOT, "%,d / %,d  •  +%d XP",
                        value, quest.target(), quest.xp());
                page.add(DialogBody.plainMessage(MenuText.stat(quest.label(),
                        done ? "item/lime_dye" : quest.type().sprite(), state), RULE_WIDTH));
                plain.append("• ").append(quest.label()).append(": ").append(state).append('\n');
            }
            page.add(DialogBody.plainMessage(Component.empty(), RULE_WIDTH));
            plain.append('\n');
        }
        show(player, "Quests", page, plain.toString().strip(), List.of(
                new Action("item/nether_star", "Season Pass", "Your tier and the rewards ahead.",
                        this::openPass)), back);
    }

    private void openRewards(Player player, int pageNumber) {
        int perPage = 10;
        int pages = Math.max(1, (maximumTier() + perPage - 1) / perPage);
        int current = Math.max(1, Math.min(pages, pageNumber));
        int reached = tier(player.getUniqueId());
        List<DialogBody> page = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        for (int tier = (current - 1) * perPage + 1; tier <= Math.min(maximumTier(), current * perPage); tier++) {
            String reward = describe(grants(tier));
            boolean earned = tier <= reached;
            page.add(DialogBody.plainMessage(MenuText.stat("Tier " + tier,
                    earned ? "item/lime_dye" : tier % 10 == 0 ? "item/nether_star" : "item/gray_dye",
                    reward + (earned ? "  ✔" : "")), RULE_WIDTH));
            plain.append("Tier ").append(tier).append(": ").append(reward).append(earned ? " ✔" : "").append('\n');
        }
        List<Action> actions = new ArrayList<>();
        if (current > 1) actions.add(new Action("item/arrow", "Previous", "Tiers before these.",
                viewer -> openRewards(viewer, current - 1)));
        if (current < pages) actions.add(new Action("item/arrow", "Next", "Tiers after these.",
                viewer -> openRewards(viewer, current + 1)));
        show(player, "Season Rewards " + current + "/" + pages, page, plain.toString(), actions, this::openPass);
    }

    private void openTop(Player player) {
        List<DialogBody> page = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        List<Map.Entry<UUID, SeasonStore.Row>> top = store.ranking(10);
        if (top.isEmpty()) {
            page.add(DialogBody.plainMessage(MenuText.muted("Nobody has earned Season XP yet. Be first."), RULE_WIDTH));
            plain.append("Nobody has earned Season XP yet.");
        }
        for (int index = 0; index < top.size(); index++) {
            SeasonStore.Row row = top.get(index).getValue();
            String value = String.format(Locale.ROOT, "Tier %d  •  %,d XP",
                    SeasonPassRules.tier(row.xp, xpPerTier(), maximumTier()), row.xp);
            page.add(DialogBody.plainMessage(MenuText.stat("#" + (index + 1) + " " + row.name,
                    index == 0 ? "item/gold_ingot" : index < 3 ? "item/iron_ingot" : "item/paper", value), RULE_WIDTH));
            plain.append('#').append(index + 1).append(' ').append(row.name).append(": ").append(value).append('\n');
        }
        page.add(DialogBody.plainMessage(Component.empty(), RULE_WIDTH));
        page.add(DialogBody.plainMessage(MenuText.muted("The top three when the season ends win "
                + variables.integer("season.first-place-shards") + ", "
                + variables.integer("season.second-place-shards") + " and "
                + variables.integer("season.third-place-shards") + " Shards."), RULE_WIDTH));
        show(player, "Season " + store.season() + " Top", page, plain.toString(), List.of(), this::openPass);
    }

    private record Action(String sprite, String label, String hint, Consumer<Player> run) { }

    private void show(
            Player player, String title, List<DialogBody> page, String plain,
            List<Action> actions, Consumer<Player> back
    ) {
        if (!clientSupport.supportsDialogs(player)) {
            List<BedrockForms.Button> buttons = actions.stream()
                    .map(action -> new BedrockForms.Button(action.label(), () -> action.run().accept(player)))
                    .toList();
            if (!forms.menu(player, title, plain, buttons, back)) {
                player.sendMessage(Component.text(title + "\n" + plain, NamedTextColor.GRAY));
            }
            return;
        }
        List<ActionButton> buttons = actions.stream()
                .map(action -> Screens.button(action.sprite(), action.label(), action.hint(), action.run()))
                .toList();
        int columns = Math.min(2, Math.max(1, buttons.size()));
        if (back == null) Screens.showStandalone(player, title, page, buttons, columns);
        else Screens.show(player, title, page, buttons, columns, back);
    }

    private String endsIn() {
        long millis = LocalDate.ofEpochDay(store.endsDay()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                - System.currentTimeMillis();
        return "in " + compact(millis);
    }

    private static String dailyResetIn() {
        long next = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        return "in " + compact(next - Instant.now().toEpochMilli());
    }

    private static String weeklyResetIn() {
        long next = LocalDate.ofEpochDay(SeasonPassRules.weekStart(today()) + 7L)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        return "in " + compact(next - Instant.now().toEpochMilli());
    }

    private static String compact(long millis) {
        long minutes = Math.max(1L, millis / 60_000L);
        long days = minutes / 1_440L;
        long hours = (minutes % 1_440L) / 60L;
        if (days > 0L) return days + "d " + hours + "h";
        return hours > 0L ? hours + "h " + (minutes % 60L) + "m" : minutes + "m";
    }

    private void save() {
        try {
            store.persist();
            dirty = false;
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Could not save the Season Pass: " + failure.getMessage());
        }
    }

    private static void info(Player player, String text) {
        player.sendMessage(Component.text("SEASON » ", ORANGE, TextDecoration.BOLD)
                .append(Component.text(text, NamedTextColor.WHITE)));
    }
}
