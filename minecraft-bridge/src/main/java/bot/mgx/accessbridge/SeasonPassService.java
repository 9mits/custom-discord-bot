package bot.mgx.accessbridge;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
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
 * <p>XP comes from three kinds of quest, each with a job. Daily and weekly boards bring
 * players back and together (see {@link SeasonQuestRules}); season ladders, each level
 * harder and worth more than the last, reward long-term mastery; a weekly community goal
 * gives the whole server one shared target. A little XP also comes from every active
 * minute, and all of it is raised during a Rally or for a player behind the season's pace.
 * Every tier pays automatically the
 * moment it is reached, so nothing is ever left unclaimed; the last tiers pay chase
 * rewards: Season Hearts, Shards, a Mythic Giftbag, permanent gear, and an aura, trail and kill effect that
 * only this season's pass ever pays. A season runs for a fixed number of days, then its top
 * three are paid, remembered, and everybody starts the next one from tier zero.
 *
 * <p>Keys and money are not rewards. Both are minted in such volume that a tier paying
 * them meant nothing; what a tier pays now is scarce, permanent, or both. Season Hearts
 * are the exception to permanent: they last until the season ends, so a veteran cannot
 * stack a lead that a new player can never close. A tier reached at the cap pays Shards.
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
    private final ClanStore clans;
    private boolean dirty;
    /** Whether a Rally is running; recomputed every pulse from who is actually playing. */
    private boolean rally;
    private int rallyThreshold = 2;
    private int activeCount;

    SeasonPassService(
            MGXAccessBridge plugin, SeasonStore store, GameVariableStore variables, CrateItems items,
            SettingsClientSupport clientSupport, BedrockForms forms, ClanStore clans
    ) {
        this.plugin = plugin;
        this.store = store;
        this.variables = variables;
        this.items = items;
        this.clientSupport = clientSupport;
        this.forms = forms;
        this.clans = clans;
    }

    void start() {
        SeasonPassRules.ladderSource(
                type -> variables.string("season.quest." + type.key() + ".targets"),
                () -> variables.string("season.quest.level-xp"));
        // Saved at once: a season held only in memory would restart, with a new end
        // date, every time the server did.
        if (ensureSeason(today())) save();
        ensureCommunity(today());
        plugin.getServer().getScheduler().runTaskTimer(plugin, PerfMonitor.track("season-pass.pulse", this::pulse), PULSE_TICKS, PULSE_TICKS);
        // A plugin reload leaves players online who would otherwise lose their hearts.
        plugin.getServer().getOnlinePlayers().forEach(this::applyHearts);
    }

    private boolean enabled() {
        return variables.bool("season.enabled");
    }

    static long today() {
        return LocalDate.now(ZoneOffset.UTC).toEpochDay();
    }

    int xpPerTier() {
        return variables.integer("season.xp-per-tier");
    }

    int maximumTier() {
        return variables.integer("season.tiers");
    }

    int heartCap() {
        return Math.max(0, variables.integer("season.hearts.cap"));
    }

    /** Season Hearts are a perk like any other: applied on join and after every death. */
    void applyHearts(Player player) {
        if (plugin.perks() != null) plugin.perks().applySeasonHearts(player, store.hearts(player.getUniqueId()));
    }

    private SeasonPassMenu menu;

    void useMenu(SeasonPassMenu menu) {
        this.menu = menu;
    }

    int season() {
        return store.season();
    }

    long xp(UUID playerId) {
        return store.row(playerId).xp;
    }

    int hearts(UUID playerId) {
        return store.hearts(playerId);
    }

    java.util.Optional<CosmeticCatalog.Definition> seasonCosmeticDefinition(SeasonPassRules.Grant grant) {
        return seasonCosmetic(grant);
    }

    /** The real gear a tier pays, built for a menu tile and never handed out. */
    java.util.Optional<ItemStack> seasonGearPreview(SeasonPassRules.Grant grant) {
        var piece = seasonGear(grant);
        var theme = SeasonCosmetics.theme(store.season());
        if (piece.isEmpty() || theme.isEmpty() || plugin.amethystItems() == null) return java.util.Optional.empty();
        return java.util.Optional.of(plugin.amethystItems().createSeasonGear(theme.get(), piece.get()));
    }

    /** "silence_armor_trim_smithing_template" reads as "Silence Armor Trim". */
    static String vanillaName(String id) {
        return java.util.Arrays.stream(id.replace("_smithing_template", "").split("_"))
                .filter(word -> !word.isEmpty())
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                .collect(java.util.stream.Collectors.joining(" "));
    }

    /**
     * One copy of a vanilla or enchanted-book reward, or empty when the id is not real.
     * Books are clamped to the enchantment's own maximum, so no tier pays past vanilla.
     */
    java.util.Optional<ItemStack> vanillaItem(SeasonPassRules.Grant grant) {
        if (grant.kind().equals("book")) {
            org.bukkit.enchantments.Enchantment enchantment = io.papermc.paper.registry.RegistryAccess.registryAccess()
                    .getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT)
                    .get(org.bukkit.NamespacedKey.minecraft(grant.id()));
            if (enchantment == null) return java.util.Optional.empty();
            ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
            if (book.getItemMeta() instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta meta) {
                meta.addStoredEnchant(enchantment, (int) Math.min(grant.amount(), enchantment.getMaxLevel()), false);
                book.setItemMeta(meta);
            }
            return java.util.Optional.of(book);
        }
        Material material = Material.matchMaterial(grant.id());
        return material == null || !material.isItem() || material.isAir()
                ? java.util.Optional.empty() : java.util.Optional.of(new ItemStack(material));
    }

    /** The real season-bound Giftbag a tier pays, built quietly for a menu tile. */
    java.util.Optional<ItemStack> giftbagPreview() {
        return plugin.giftbags() == null ? java.util.Optional.empty()
                : java.util.Optional.of(plugin.giftbags().preview(store.season()));
    }

    /** The tier a player holds this season, for the sidebar. */
    int tier(UUID playerId) {
        return SeasonPassRules.tier(store.row(playerId).xp, xpPerTier(), maximumTier());
    }

    // ------------------------------------------------------------------ progress

    /**
     * Adds to a quest line's season total and pays every level that total clears. Totals
     * only ever grow, so a level is paid exactly once however the amount arrives.
     */
    void progress(Player player, SeasonPassRules.QuestType type, long amount) {
        if (!enabled() || amount <= 0L || player == null
                || VerificationLobbyService.isLobbyWorld(player.getWorld())) return;
        mirror(type).ifPresent(objective -> record(player, objective, amount));
        SeasonStore.Row row = rowFor(player);
        long before = row.quests.getOrDefault(type.key(), 0L);
        long after = before + amount;
        row.quests.put(type.key(), after);
        dirty = true;
        // Paid levels are remembered rather than re-derived, so an owner lowering a target
        // pays the levels it newly clears, and raising one never pays a level twice.
        int from = row.questPaid.getOrDefault(type.key(), SeasonPassRules.levelFor(type, before));
        int to = SeasonPassRules.levelFor(type, after);
        if (to > from) row.questPaid.put(type.key(), to);
        for (int level = from; level < to; level++) {
            SeasonPassRules.Quest quest = SeasonPassRules.quest(type, level).orElseThrow();
            long xp = questXp(row, quest.xp());
            player.showTitle(Title.title(
                    Component.text(type.title().toUpperCase(Locale.ROOT) + " " + roman(level + 1),
                            NamedTextColor.GREEN, TextDecoration.BOLD),
                    Component.text(quest.label() + "  •  +" + xp + " XP", NamedTextColor.GOLD),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(1800), Duration.ofMillis(300))));
            player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.4f);
            String nextGoal = SeasonPassRules.quest(type, level + 1).map(next -> " Next: " + next.label() + ".")
                    .orElse(" That line is mastered.");
            info(player, "Milestone complete: " + quest.label() + ". +" + xp + " Season XP" + boostNote(row, true)
                    + "." + nextGoal);
            addXp(player, row, xp);
        }
    }

    /** The board objective a season ladder's work also counts toward. */
    private static java.util.Optional<SeasonQuestRules.Objective> mirror(SeasonPassRules.QuestType type) {
        return switch (type) {
            case KILL_MOBS -> java.util.Optional.of(SeasonQuestRules.Objective.KILL_MOBS);
            case MINE_ORES -> java.util.Optional.of(SeasonQuestRules.Objective.MINE_ORES);
            case HARVEST_CROPS -> java.util.Optional.of(SeasonQuestRules.Objective.HARVEST_CROPS);
            default -> java.util.Optional.empty();
        };
    }

    // ------------------------------------------------------------------ boards

    /**
     * Counts work toward the daily and weekly boards, the community goal and the history
     * that deals future boards. Season ladders are counted separately by {@link #progress}.
     */
    void record(Player player, SeasonQuestRules.Objective objective, long amount) {
        if (!enabled() || amount <= 0L || player == null
                || VerificationLobbyService.isLobbyWorld(player.getWorld())) return;
        SeasonStore.Row row = rowFor(player);
        row.activity.merge(objective.key(), amount, Long::sum);
        store.addWeekTotal(objective.key(), amount);
        dirty = true;
        ensureBoards(player, row);
        for (SeasonStore.Slot slot : SeasonQuestRules.advance(row.daily, objective, amount)) {
            completeSlot(player, row, slot, "DAILY", row.daily, variables.integer("season.daily.xp"));
        }
        if (SeasonQuestRules.takeSweep(row.daily)) {
            sweep(player, row, "Daily board cleared", variables.integer("season.daily.sweep-xp"),
                    "New quests at 00:00 UTC, " + untilTomorrow() + " from now.");
        }
        for (SeasonStore.Slot slot : SeasonQuestRules.advance(row.weekly, objective, amount)) {
            completeSlot(player, row, slot, "WEEKLY", row.weekly, variables.integer("season.weekly.xp"));
        }
        if (SeasonQuestRules.takeSweep(row.weekly)) {
            sweep(player, row, "Weekly board cleared", variables.integer("season.weekly.sweep-xp"),
                    "New quests on Monday, " + untilNextWeek() + " from now.");
        }
        contribute(player, objective, amount);
    }

    /** Deals a fresh board for any period that has rolled over since the last one. */
    private void ensureBoards(Player player, SeasonStore.Row row) {
        long today = today();
        long week = SeasonQuestRules.weeklyPeriod(today);
        boolean dailyStale = row.daily == null || row.daily.period != SeasonQuestRules.dailyPeriod(today);
        boolean weeklyStale = row.weekly == null || row.weekly.period != week;
        if (!dailyStale && !weeklyStale) return;
        SeasonQuestRules.Profile profile = new SeasonQuestRules.Profile(row.activity,
                inClanWithOthers(player.getUniqueId()),
                plugin.pvpCompetition() != null && plugin.pvpCompetition().open());
        if (dailyStale) {
            row.daily = board(SeasonQuestRules.dailyPeriod(today),
                    SeasonQuestRules.dealDaily(player.getUniqueId(), today, profile));
        }
        if (weeklyStale) {
            // Nobody can play on four days of a week that has two left in it.
            long daysLeft = SeasonQuestRules.nextWeekStartDay(week) - today;
            long days = Math.min(daysLeft, variables.integer("season.weekly.play-days"));
            row.weekly = board(week, SeasonQuestRules.dealWeekly(player.getUniqueId(), week, profile, days));
        }
        dirty = true;
    }

    private static SeasonStore.Board board(long period, List<SeasonStore.Slot> slots) {
        SeasonStore.Board board = new SeasonStore.Board();
        board.period = period;
        board.slots = new ArrayList<>(slots);
        return board;
    }

    private boolean inClanWithOthers(UUID playerId) {
        return clans != null && clans.clanOf(playerId).map(clan -> clan.members().size() > 1).orElse(false);
    }

    private void completeSlot(
            Player player, SeasonStore.Row row, SeasonStore.Slot slot, String board,
            SeasonStore.Board owner, long base
    ) {
        var objective = SeasonQuestRules.Objective.of(slot.objective);
        if (objective.isEmpty()) return;
        long xp = questXp(row, base);
        String label = objective.get().label(slot.target);
        player.showTitle(Title.title(
                Component.text(board + " QUEST COMPLETE", NamedTextColor.GREEN, TextDecoration.BOLD),
                Component.text(label + "  •  +" + xp + " XP", NamedTextColor.GOLD),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1800), Duration.ofMillis(300))));
        player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.4f);
        long left = owner.slots.size() - SeasonQuestRules.done(owner);
        info(player, board.charAt(0) + board.substring(1).toLowerCase(Locale.ROOT) + " quest complete: " + label
                + ". +" + xp + " Season XP" + boostNote(row, true) + "."
                + (left > 0 ? " " + left + " left on this board." : ""));
        addXp(player, row, xp);
    }

    private void sweep(Player player, SeasonStore.Row row, String headline, long base, String next) {
        if (base <= 0L) return;
        long xp = questXp(row, base);
        player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.0f);
        info(player, headline + "! +" + xp + " bonus Season XP" + boostNote(row, true) + ". " + next);
        addXp(player, row, xp);
    }

    /** Minutes played, and the Together and Come Back quests that only minutes can move. */
    private void countMinute(Player player, List<Player> active, long today) {
        SeasonStore.Row row = rowFor(player);
        row.activity.merge(SeasonQuestRules.ACTIVE_MINUTES, 1L, Long::sum);
        store.markWeekPlayer(player.getUniqueId());
        ensureBoards(player, row);
        if (SeasonQuestRules.countActiveMinute(row, today, variables.integer("season.daily.active-day-minutes"))) {
            record(player, SeasonQuestRules.Objective.PLAY_DAYS, 1L);
        }
        double radius = variables.integer("season.together.radius");
        double reach = radius * radius;
        boolean near = false;
        for (Player other : active) {
            if (other != player && other.getWorld().equals(player.getWorld())
                    && other.getLocation().distanceSquared(player.getLocation()) <= reach) {
                near = true;
                break;
            }
        }
        if (near) record(player, SeasonQuestRules.Objective.TOGETHER_MINUTES, 1L);
        boolean clanmate = clans != null && clans.clanOf(player.getUniqueId())
                .map(clan -> active.stream().anyMatch(other -> other != player
                        && clan.members().containsKey(other.getUniqueId())))
                .orElse(false);
        if (clanmate) record(player, SeasonQuestRules.Objective.CLAN_MINUTES, 1L);
    }

    // ------------------------------------------------------------------ boosts

    /** Quest XP with catch-up and a live Rally applied. */
    private long questXp(SeasonStore.Row row, long base) {
        return SeasonQuestRules.boosted(base, catchUpPercent(row), rallyPercent());
    }

    int rallyPercent() {
        return rally ? variables.integer("season.rally.xp-percent") : 100;
    }

    boolean rallyLive() {
        return rally;
    }

    int rallyThreshold() {
        return rallyThreshold;
    }

    int activePlayers() {
        return activeCount;
    }

    private int catchUpPercent(SeasonStore.Row row) {
        int tier = SeasonPassRules.tier(row.xp, xpPerTier(), maximumTier());
        return SeasonQuestRules.catchingUp(tier, paceTier()) ? variables.integer("season.catch-up.xp-percent") : 100;
    }

    /** The tier the season's pace line has reached today. */
    int paceTier() {
        return SeasonQuestRules.paceTier(today(), store.startedDay(), store.endsDay(), maximumTier(),
                variables.integer("season.catch-up.pace-percent"));
    }

    boolean catchingUp(UUID playerId) {
        return catchUpPercent(store.row(playerId)) > 100;
    }

    private String boostNote(SeasonStore.Row row, boolean quest) {
        List<String> notes = new ArrayList<>();
        if (quest && catchUpPercent(row) > 100) notes.add("catch-up " + multiplier(catchUpPercent(row)));
        if (rallyPercent() > 100) notes.add("Rally " + multiplier(rallyPercent()));
        return notes.isEmpty() ? "" : " (" + String.join(", ", notes) + ")";
    }

    static String multiplier(int percent) {
        return "x" + java.math.BigDecimal.valueOf(percent, 2).stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------------ community goal

    /** Sets this week's server-wide goal once, from last week's totals. */
    private void ensureCommunity(long today) {
        long week = SeasonQuestRules.weeklyPeriod(today);
        boolean rolled = store.rollWeek(week);
        SeasonStore.Community current = store.community();
        if (!rolled && current.week == week) return;
        SeasonStore.Community next = new SeasonStore.Community();
        SeasonQuestRules.Objective objective = SeasonQuestRules.communityObjective(week);
        next.week = week;
        next.objective = objective.key();
        next.target = SeasonQuestRules.communityTarget(objective, store.lastWeekTotal(objective.key()),
                store.lastWeekPlayers(), variables.integer("season.community.growth-percent"));
        store.community(next);
        dirty = true;
        if (enabled() && variables.bool("season.community.enabled")) {
            Component line = Component.text("COMMUNITY GOAL » ", ORANGE, TextDecoration.BOLD)
                    .append(Component.text("This week, together: " + objective.label(next.target)
                            + ". Everyone who helps earns " + variables.integer("season.community.xp")
                            + " Season XP. /quests", NamedTextColor.WHITE));
            plugin.getServer().getOnlinePlayers().forEach(player -> player.sendMessage(line));
        }
    }

    private void contribute(Player player, SeasonQuestRules.Objective objective, long amount) {
        if (!variables.bool("season.community.enabled")) return;
        SeasonStore.Community goal = store.community();
        if (goal.completed || goal.week != SeasonQuestRules.weeklyPeriod(today())
                || !objective.key().equals(goal.objective)) return;
        goal.progress = Math.min(goal.target, goal.progress + amount);
        goal.contributions.merge(player.getUniqueId().toString(), amount, Long::sum);
        int quarter = (int) Math.min(4L, goal.progress * 4L / Math.max(1L, goal.target));
        if (quarter > goal.announcedQuarter) {
            goal.announcedQuarter = quarter;
            if (quarter < 4) {
                Component line = Component.text("COMMUNITY GOAL » ", ORANGE, TextDecoration.BOLD)
                        .append(Component.text((quarter * 25) + "% done: " + objective.label(goal.target)
                                + ". Help before Monday to share " + variables.integer("season.community.xp")
                                + " Season XP.", NamedTextColor.WHITE));
                plugin.getServer().getOnlinePlayers().forEach(online -> online.sendMessage(line));
            }
        }
        if (goal.progress >= goal.target) completeCommunity(goal, objective);
    }

    private void completeCommunity(SeasonStore.Community goal, SeasonQuestRules.Objective objective) {
        goal.completed = true;
        long minimum = SeasonQuestRules.contributorMinimum(objective, goal.target);
        long xp = Math.max(0, variables.integer("season.community.xp"));
        int helpers = 0;
        for (Map.Entry<String, Long> entry : goal.contributions.entrySet()) {
            if (entry.getValue() < minimum) continue;
            UUID id;
            try {
                id = UUID.fromString(entry.getKey());
            } catch (IllegalArgumentException invalid) {
                continue;
            }
            helpers++;
            Player online = plugin.getServer().getPlayer(id);
            SeasonStore.Row row = store.row(id);
            if (online != null) {
                info(online, "Community goal reached! +" + xp + " Season XP for helping.");
                addXp(online, row, xp);
            } else {
                row.owedXp += xp;
            }
        }
        dirty = true;
        Component line = Component.text("COMMUNITY GOAL REACHED » ", ORANGE, TextDecoration.BOLD)
                .append(Component.text(helpers + (helpers == 1 ? " player" : " players") + " finished "
                        + objective.label(goal.target) + " together. Everyone who helped earned "
                        + xp + " Season XP.", NamedTextColor.WHITE));
        plugin.getServer().getOnlinePlayers().forEach(player -> player.sendMessage(line));
    }

    /** Season XP for bringing somebody to the server, paid now or at the inviter's next join. */
    void referralXp(UUID referrerId, String refereeName) {
        long xp = variables.integer("season.referral-xp");
        if (!enabled() || xp <= 0L || referrerId == null) return;
        Player online = plugin.getServer().getPlayer(referrerId);
        SeasonStore.Row row = store.row(referrerId);
        if (online != null) {
            info(online, "+" + xp + " Season XP for bringing " + refereeName + " to the server.");
            addXp(online, row, xp);
        } else {
            row.owedXp += xp;
        }
        dirty = true;
        save();
    }

    static String roman(int number) {
        String[] numerals = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return number >= 0 && number < numerals.length ? numerals[number] : String.valueOf(number);
    }

    /** Season XP from outside the quests, such as a claimed login streak day. */
    void bonusXp(Player player, long xp) {
        if (!enabled() || xp <= 0L) return;
        addXp(player, rowFor(player), xp);
        dirty = true;
    }

    /** Local-test control: add XP through the real tier-payment and presentation path. */
    void addTestXp(Player player, long xp) {
        long maximum = Math.multiplyExact((long) maximumTier(), xpPerTier());
        if (xp < 1L || xp > maximum) {
            throw new IllegalArgumentException("XP must be between 1 and " + maximum + ".");
        }
        addXp(player, rowFor(player), xp);
        dirty = true;
        save();
    }

    /** Local-test control: move to an exact tier; moving down arms that tier-up to be tested again. */
    void setTestTier(Player player, int tier) {
        if (tier < 0 || tier > maximumTier()) {
            throw new IllegalArgumentException("Tier must be between 0 and " + maximumTier() + ".");
        }
        SeasonStore.Row row = rowFor(player);
        row.xp = Math.multiplyExact((long) tier, xpPerTier());
        row.grantedTier = Math.min(row.grantedTier, tier);
        grantReachedTiers(player, row);
        dirty = true;
        save();
    }

    private SeasonStore.Row rowFor(Player player) {
        SeasonStore.Row row = store.row(player.getUniqueId());
        row.name = player.getName();
        return row;
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
            List<SeasonPassRules.Grant> grants = grants(tier);
            if (plugin.giftbags() == null && grants.stream().anyMatch(grant -> grant.kind().equals("giftbag"))) {
                plugin.getLogger().warning("Tier " + tier + " is waiting because Season Giftbags are unavailable.");
                return;
            }
            row.grantedTier = tier;
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
        ensureCommunity(today);
        if (!enabled()) return;
        List<Player> active = new ArrayList<>();
        for (Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            if (VerificationLobbyService.isLobbyWorld(player.getWorld()) || afk(player)) continue;
            active.add(player);
        }
        updateRally(active.size());
        int perMinute = variables.integer("season.xp-per-active-minute");
        for (Player player : active) {
            countMinute(player, active, today);
            progress(player, SeasonPassRules.QuestType.PLAY_MINUTES, 1L);
            SeasonStore.Row row = rowFor(player);
            if (perMinute > 0) {
                addXp(player, row, SeasonQuestRules.boosted(perMinute, rallyPercent()));
                dirty = true;
            } else {
                grantReachedTiers(player, row);
            }
        }
        if (dirty) save();
    }

    /**
     * Starts or ends a Rally from how many people are actually playing. The threshold is
     * the busiest quarter of the last week's hours, so it always asks for a busier server
     * than usual, and the start pings Discord's Event Pings role to fill it further.
     */
    private void updateRally(int active) {
        activeCount = active;
        store.recordActive(System.currentTimeMillis() / 3_600_000L, active);
        dirty = true;
        rallyThreshold = SeasonQuestRules.rallyThreshold(store.hourlyPeaks(),
                variables.integer("season.rally.minimum-players"));
        boolean live = variables.bool("season.rally.enabled")
                && SeasonQuestRules.rallyLive(rally, active, rallyThreshold);
        if (live == rally) return;
        rally = live;
        String boost = multiplier(variables.integer("season.rally.xp-percent"));
        Component line = live
                ? Component.text("RALLY » ", ORANGE, TextDecoration.BOLD)
                        .append(Component.text(active + " players are online: " + boost
                                + " Season XP while the server stays this busy.", NamedTextColor.WHITE))
                : Component.text("RALLY » ", ORANGE, TextDecoration.BOLD)
                        .append(Component.text("The Rally has ended. It starts again at " + rallyThreshold
                                + " active players.", NamedTextColor.WHITE));
        plugin.getServer().getOnlinePlayers().forEach(player -> player.sendMessage(line));
        long now = System.currentTimeMillis();
        long cooldown = variables.integer("season.rally.ping-cooldown-minutes") * 60_000L;
        if (live && now - store.lastRallyPingAt() >= cooldown) {
            store.lastRallyPingAt(now);
            plugin.pingDiscord("ping_event_live", "A Season XP Rally started with " + active + " players online",
                    Map.of("event", boost + " Season XP Rally"));
        }
    }

    // ------------------------------------------------------------------ rewards

    List<SeasonPassRules.Grant> grants(int tier) {
        String entry = SeasonPassRules.trackEntry(variables.string("season.reward.track"), tier);
        return SeasonPassRules.parse(entry.isEmpty() ? variables.string("season.reward.fallback") : entry);
    }

    String describe(List<SeasonPassRules.Grant> grants) {
        List<String> parts = new ArrayList<>();
        for (SeasonPassRules.Grant grant : grants) {
            switch (grant.kind()) {
                case "hearts" -> parts.add("+" + grant.amount() + " Season "
                        + (grant.amount() == 1 ? "Heart" : "Hearts"));
                case "season_gear" -> parts.add(seasonGear(grant)
                        .map(piece -> SeasonCosmetics.theme(store.season())
                                .map(theme -> SeasonGear.displayName(theme, piece)).orElse(piece.label)
                                + " (Season " + store.season() + " Exclusive)")
                        .orElse(exclusiveFallbackShards() + " Shards"));
                case "season_cosmetic" -> parts.add(seasonCosmetic(grant)
                        .map(definition -> definition.displayName() + " (Season " + store.season() + " Exclusive)")
                        .orElse(exclusiveFallbackShards() + " Shards"));
                case "vanilla" -> parts.add((grant.amount() > 1 ? grant.amount() + "x " : "") + vanillaName(grant.id()));
                case "book" -> parts.add(vanillaName(grant.id()) + (grant.amount() > 1 ? " " + roman((int) grant.amount()) : "")
                        + " Book");
                case "keys" -> parts.add(grant.amount() + (grant.amount() == 1 ? " Key" : " Keys"));
                case "shards" -> parts.add(grant.amount() + (grant.amount() == 1 ? " Shard" : " Shards"));
                case "giftbag" -> parts.add(grant.amount() + (grant.amount() == 1
                        ? " Mythic Giftbag" : " Mythic Giftbags"));
                case "cosmetic" -> parts.add(CosmeticCatalog.find(grant.id())
                        .map(CosmeticCatalog.Definition::displayName).orElse(grant.id()));
                case "reward" -> parts.add((grant.amount() > 1 ? grant.amount() + "x " : "")
                        + CrateCatalog.find(grant.id()).map(CrateCatalog.Reward::displayName).orElse(grant.id()));
                default -> { }
            }
        }
        return parts.isEmpty() ? "Season progress" : String.join(" + ", parts);
    }

    private java.util.Optional<CosmeticCatalog.Definition> seasonCosmetic(SeasonPassRules.Grant grant) {
        try {
            return SeasonCosmetics.forSeason(store.season(), CosmeticCatalog.Category.valueOf(grant.id()));
        } catch (IllegalArgumentException unknown) {
            return java.util.Optional.empty();
        }
    }

    /** A gear grant for this season, empty when the season has no theme to paint it in. */
    private java.util.Optional<SeasonGear.Piece> seasonGear(SeasonPassRules.Grant grant) {
        if (SeasonCosmetics.theme(store.season()).isEmpty()) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(SeasonGear.Piece.valueOf(grant.id()));
        } catch (IllegalArgumentException unknown) {
            return java.util.Optional.empty();
        }
    }

    private int exclusiveFallbackShards() {
        return Math.max(0, variables.integer("season.exclusive-fallback-shards"));
    }

    private void pay(Player player, List<SeasonPassRules.Grant> grants) {
        for (SeasonPassRules.Grant grant : grants) {
            try {
                switch (grant.kind()) {
                    case "hearts" -> {
                        int wanted = (int) grant.amount();
                        int added = store.addHearts(player.getUniqueId(), wanted, heartCap());
                        if (added > 0) {
                            applyHearts(player);
                            dirty = true;
                            player.playSound(player, Sound.ITEM_TOTEM_USE, 0.6f, 1.3f);
                            info(player, "+" + added + " Season " + (added == 1 ? "Heart" : "Hearts")
                                    + ". You hold " + store.hearts(player.getUniqueId()) + " of "
                                    + heartCap() + " until Season " + store.season() + " ends " + endsIn() + ".");
                        }
                        int substitute = (wanted - added) * Math.max(0, variables.integer("season.hearts.capped-shards"));
                        if (substitute > 0) {
                            giveShards(player, substitute);
                            info(player, "You already hold every Season Heart this season, so this tier paid "
                                    + substitute + " Shards instead.");
                        }
                    }
                    case "season_gear" -> {
                        var piece = seasonGear(grant);
                        var theme = SeasonCosmetics.theme(store.season());
                        if (piece.isPresent() && theme.isPresent() && plugin.amethystItems() != null) {
                            give(player, plugin.amethystItems().createSeasonGear(theme.get(), piece.get()));
                            info(player, SeasonGear.displayName(theme.get(), piece.get())
                                    + " is yours for good. Only Season " + store.season() + " ever pays it.");
                        } else if (exclusiveFallbackShards() > 0) {
                            giveShards(player, exclusiveFallbackShards());
                        }
                    }
                    case "season_cosmetic" -> {
                        var definition = seasonCosmetic(grant);
                        if (definition.isPresent()) {
                            plugin.cosmetics().mint(player.getUniqueId(), definition.get().id(), UUID.randomUUID());
                            info(player, definition.get().displayName() + " is in your /wardrobe. Only Season "
                                    + store.season() + " ever pays it.");
                        } else if (exclusiveFallbackShards() > 0) {
                            giveShards(player, exclusiveFallbackShards());
                        }
                    }
                    case "vanilla", "book" -> vanillaItem(grant).ifPresentOrElse(item -> {
                        for (long left = grant.amount(); left > 0; ) {
                            ItemStack stack = item.clone();
                            stack.setAmount((int) Math.min(left, item.getMaxStackSize()));
                            give(player, stack);
                            left -= stack.getAmount();
                        }
                    }, () -> plugin.getLogger().warning("Season Pass " + grant.kind() + " reward "
                            + grant.id() + " is not a real item or enchantment."));
                    case "keys" -> {
                        if (plugin.crateService() != null) {
                            plugin.crateService().grantKeys(player, (int) Math.min(256, grant.amount()));
                        }
                    }
                    case "shards" -> giveShards(player, (int) Math.min(640, grant.amount()));
                    case "giftbag" -> {
                        if (plugin.giftbags() != null) {
                            for (long copy = 0; copy < grant.amount(); copy++) {
                                give(player, plugin.giftbags().create(store.season()));
                            }
                            info(player, "You found a Season " + store.season()
                                    + " Mythic Giftbag. Right-click it when you are ready.");
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
                            for (long copy = 0; copy < grant.amount(); copy++) give(player, items.reward(reward));
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
        // The store has already forgotten last season's hearts; take the modifier off
        // everybody online now rather than at their next join or death.
        plugin.getServer().getOnlinePlayers().forEach(this::applyHearts);
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
        int championGiftbags = Math.max(0, variables.integer("season.first-place-giftbags"));
        for (int place = 0; place < top.size(); place++) {
            SeasonStore.Row row = top.get(place).getValue();
            podium.names.add(row.name);
            podium.xp.add(row.xp);
            Player online = plugin.getServer().getPlayer(top.get(place).getKey());
            if (online != null) {
                giveShards(online, prizes[place]);
                if (place == 0 && plugin.giftbags() != null) {
                    for (int copy = 0; copy < championGiftbags; copy++) {
                        give(online, plugin.giftbags().create(podium.season));
                    }
                } else if (place == 0) {
                    for (int copy = 0; copy < championGiftbags; copy++) {
                        row.owedGiftbagSeasons.add(podium.season);
                    }
                }
                info(online, "You finished #" + (place + 1) + " in Season " + podium.season
                        + " and earned " + prizes[place] + " Shards"
                        + (place == 0 && championGiftbags > 0
                        ? " plus " + championGiftbags + " Mythic Giftbag" + (championGiftbags == 1 ? "" : "s") : "")
                        + ".");
            } else {
                row.owedShards += prizes[place];
                if (place == 0) {
                    for (int copy = 0; copy < championGiftbags; copy++) {
                        row.owedGiftbagSeasons.add(podium.season);
                    }
                }
            }
        }
        store.archive(podium);
        Component header = Component.text("SEASON " + podium.season + " IS OVER", ORANGE, TextDecoration.BOLD)
                .append(Component.text("  •  Season Hearts have expired", NamedTextColor.WHITE));
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
        // Hearts already earned this season apply even while the pass itself is switched off.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) applyHearts(player);
        }, 2L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !enabled()) return;
            SeasonStore.Row row = rowFor(player);
            if (row.owedShards > 0) {
                giveShards(player, row.owedShards);
                info(player, "Your Season podium prize arrived: " + row.owedShards + " Shards.");
                row.owedShards = 0;
                dirty = true;
            }
            if (!row.owedGiftbagSeasons.isEmpty() && plugin.giftbags() != null) {
                List<Integer> seasons = new ArrayList<>(row.owedGiftbagSeasons);
                row.owedGiftbagSeasons.clear();
                for (int giftbagSeason : seasons) give(player, plugin.giftbags().create(giftbagSeason));
                info(player, "Your Season podium prize arrived: " + seasons.size() + " Mythic Giftbag"
                        + (seasons.size() == 1 ? "" : "s") + ".");
                dirty = true;
            }
            if (row.owedXp > 0L) {
                long owed = row.owedXp;
                row.owedXp = 0L;
                info(player, "+" + owed + " Season XP arrived while you were away.");
                addXp(player, row, owed);
                dirty = true;
            }
            if (VerificationLobbyService.isLobbyWorld(player.getWorld())) {
                if (dirty) save();
                return;
            }
            ensureBoards(player, row);
            greet(player, row);
            if (dirty) save();
        }, 120L);
    }

    /** One line with today's open quests, so nobody has to remember the board exists. */
    private void greet(Player player, SeasonStore.Row row) {
        List<String> open = new ArrayList<>();
        if (row.daily != null) {
            for (SeasonStore.Slot slot : row.daily.slots) {
                if (slot.done) continue;
                SeasonQuestRules.Objective.of(slot.objective).ifPresent(objective ->
                        open.add(objective.label(slot.target)));
            }
        }
        Component line = Component.text("SEASON » ", ORANGE, TextDecoration.BOLD)
                .append(Component.text(open.isEmpty()
                        ? "Today's quests are done. New ones in " + untilTomorrow() + "."
                        : "Today: " + String.join("  ·  ", open) + ".", NamedTextColor.WHITE))
                .append(Component.text("  [Quests]", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/quests"))
                        .hoverEvent(HoverEvent.showText(Component.text("Open your quests"))));
        player.sendMessage(line);
        int tier = SeasonPassRules.tier(row.xp, xpPerTier(), maximumTier());
        int pace = paceTier();
        if (SeasonQuestRules.catchingUp(tier, pace)) {
            info(player, "Catch-up is on: quest XP " + multiplier(variables.integer("season.catch-up.xp-percent"))
                    + " until you reach Tier " + pace + ".");
        }
        if (rally) {
            info(player, "A Rally is live: " + multiplier(variables.integer("season.rally.xp-percent"))
                    + " Season XP while " + (rallyThreshold - 1) + "+ players stay on.");
        }
    }

    String untilTomorrow() {
        return compact(LocalDate.ofEpochDay(today() + 1L).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                - System.currentTimeMillis());
    }

    String untilNextWeek() {
        long day = SeasonQuestRules.nextWeekStartDay(SeasonQuestRules.weeklyPeriod(today()));
        return compact(LocalDate.ofEpochDay(day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                - System.currentTimeMillis());
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

    /** The track itself is a chest: rewards are items to hover, not lines to read. */
    void openPass(Player player) {
        if (menu != null && clientSupport.supportsDialogs(player)) {
            menu.openHome(player);
        } else if (menu != null) {
            openPassForms(player);
        } else {
            openGuide(player);
        }
    }

    /** How the pass works: the reading that does not belong on a grid of reward tiles. */
    void openGuide(Player player) {
        long today = today();
        SeasonStore.Row row = rowFor(player);
        int tier = SeasonPassRules.tier(row.xp, xpPerTier(), maximumTier());
        List<DialogBody> page = new ArrayList<>(List.of(
                DialogBody.plainMessage(MenuText.stat("Season", "Season " + store.season()
                        + "  •  ends " + endsIn()), RULE_WIDTH),
                DialogBody.plainMessage(MenuText.stat("Your tier", tier + " / " + maximumTier()), RULE_WIDTH),
                DialogBody.plainMessage(Component.empty(), RULE_WIDTH),
                DialogBody.plainMessage(MenuText.rule("item/writable_book", "Daily and weekly quests",
                        "Three new quests every day and every week: your own game, one to play"
                                + " together, and something to try. Finish a board for bonus XP."), RULE_WIDTH),
                DialogBody.plainMessage(MenuText.rule("item/cake", "Community goal",
                        "One goal for the whole server each week. Everyone who helps earns "
                                + variables.integer("season.community.xp") + " XP."), RULE_WIDTH),
                DialogBody.plainMessage(MenuText.rule("item/bell", "Rallies",
                        "When " + rallyThreshold + "+ players are active, Season XP is "
                                + multiplier(variables.integer("season.rally.xp-percent")) + "."), RULE_WIDTH),
                DialogBody.plainMessage(MenuText.rule("item/compass_16", "Catch-up",
                        "Behind the season's pace? Quest XP is "
                                + multiplier(variables.integer("season.catch-up.xp-percent"))
                                + " until you catch up."), RULE_WIDTH),
                DialogBody.plainMessage(MenuText.rule("item/iron_sword", "Milestones",
                        "Long-term goals for the whole season. Each level is bigger and pays more."), RULE_WIDTH),
                DialogBody.plainMessage(MenuText.rule("item/clock_00", "Playtime",
                        variables.integer("season.xp-per-active-minute") + " XP every active minute. AFK time earns none."), RULE_WIDTH),
                DialogBody.plainMessage(MenuText.rule("item/firework_star", "Daily streak",
                        variables.integer("season.streak-xp") + " XP each time you claim a streak day."), RULE_WIDTH),
                DialogBody.plainMessage(MenuText.rule("item/name_tag", "Invite friends",
                        variables.integer("season.referral-xp") + " XP when a friend you invite qualifies. /referrals"),
                        RULE_WIDTH),
                DialogBody.plainMessage(Component.empty(), RULE_WIDTH),
                DialogBody.plainMessage(MenuText.rule("item/nether_star", "Season exclusives",
                        "Gear and cosmetics only this season pays. They never come back."), RULE_WIDTH),
                DialogBody.plainMessage(MenuText.rule("mgx:item/mythic_giftbag", "Mythic Giftbag",
                        "The final tier's one-roll reward. It can contain this season's rarest rewards"
                                + " or one of three exceptionally rare mythic items."), RULE_WIDTH),
                DialogBody.plainMessage(MenuText.rule(SeasonPassMenu.HEART_SPRITE, "Season Hearts",
                        "Up to " + heartCap() + " extra hearts that expire when the season ends."), RULE_WIDTH),
                DialogBody.plainMessage(MenuText.rule("item/gold_ingot", "Season Top",
                        "The top three when the season ends win "
                                + variables.integer("season.first-place-shards") + ", "
                                + variables.integer("season.second-place-shards") + " and "
                                + variables.integer("season.third-place-shards") + " Shards. First also wins "
                                + variables.integer("season.first-place-giftbags") + " Mythic Giftbag."), RULE_WIDTH),
                DialogBody.plainMessage(Component.empty(), RULE_WIDTH),
                DialogBody.plainMessage(MenuText.muted("Every tier pays the moment you reach it."
                        + " Rewards wait while you are in PvP or screenshot mode."), RULE_WIDTH)));
        String plain = "Season " + store.season() + " ends " + endsIn() + ". Tier " + tier + "/" + maximumTier()
                + ".\nDaily and weekly quests, the community goal and milestones are most of your XP, plus "
                + variables.integer("season.xp-per-active-minute") + " XP per active minute and "
                + variables.integer("season.streak-xp") + " per streak day."
                + "\nRallies (" + rallyThreshold + "+ active players) and catch-up boost your XP."
                + "\nInvite a friend: " + variables.integer("season.referral-xp") + " XP when they qualify."
                + "\nSeason exclusives never come back. The final tier pays one Mythic Giftbag."
                + " Season Hearts expire when the season ends.";
        show(player, "How The Pass Works", page, plain, List.of(
                new Action("item/writable_book", "Quests", "Today's and this week's quests.",
                        viewer -> openQuests(viewer, this::openPass)),
                new Action("item/gold_ingot", "Season Top", "The highest Season XP right now.",
                        this::openTop)), this::openPass);
    }

    /**
     * Every quest, in the order a player should care about it: what boosts are running,
     * today's board, this week's board, the community goal, then the season milestones.
     */
    void openQuests(Player player, Consumer<Player> back) {
        SeasonStore.Row row = rowFor(player);
        ensureBoards(player, row);
        List<DialogBody> page = new ArrayList<>();
        StringBuilder plain = new StringBuilder();

        boosts(row).forEach(boost -> {
            page.add(DialogBody.plainMessage(MenuText.upright(boost), RULE_WIDTH));
            plain.append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(boost)).append('\n');
        });
        page.add(DialogBody.plainMessage(Component.empty(), RULE_WIDTH));

        board(page, plain, "DAILY QUESTS", "New quests in " + untilTomorrow(), row.daily,
                variables.integer("season.daily.xp"), variables.integer("season.daily.sweep-xp"), row);
        board(page, plain, "WEEKLY QUESTS", "New quests in " + untilNextWeek(), row.weekly,
                variables.integer("season.weekly.xp"), variables.integer("season.weekly.sweep-xp"), row);
        community(page, plain, player);

        header(page, plain, "SEASON MILESTONES", "Long-term goals, bigger every level");
        for (SeasonPassRules.QuestType type : SeasonPassRules.QuestType.values()) {
            long total = row.quests.getOrDefault(type.key(), 0L);
            int level = SeasonPassRules.levelFor(type, total);
            var quest = SeasonPassRules.quest(type, level);
            if (quest.isEmpty()) {
                page.add(DialogBody.plainMessage(MenuText.upright(Component.empty()
                        .append(MenuText.sprite("item/lime_dye")).append(Component.text(" "))
                        .append(Component.text(type.title() + " mastered  ✔", MenuText.VALUE))), RULE_WIDTH));
                plain.append(type.title()).append(": mastered\n");
                page.add(DialogBody.plainMessage(Component.empty(), RULE_WIDTH));
                continue;
            }
            long previous = level == 0 ? 0L : SeasonPassRules.quest(type, level - 1).orElseThrow().target();
            String amount = type == SeasonPassRules.QuestType.SELL_MONEY
                    ? EconomyFormat.dollars(total) + " / " + EconomyFormat.dollars(quest.get().target())
                    : String.format(Locale.ROOT, "%,d / %,d", total, quest.get().target());
            page.add(DialogBody.plainMessage(MenuText.upright(Component.empty()
                    .append(MenuText.sprite(type.sprite())).append(Component.text(" "))
                    .append(Component.text(quest.get().label(), NamedTextColor.WHITE))
                    .append(Component.text("   +" + quest.get().xp() + " XP", MenuText.GOLD))), RULE_WIDTH));
            page.add(DialogBody.plainMessage(MenuText.upright(bar(total - previous, quest.get().target() - previous)
                    .append(Component.text("  " + amount + "   ·   Level " + (level + 1) + " of " + type.levels(),
                            MenuText.MUTED))), RULE_WIDTH));
            plain.append(quest.get().label()).append(" (").append(amount).append(")\n");
            page.add(DialogBody.plainMessage(Component.empty(), RULE_WIDTH));
        }
        show(player, "Quests", page, plain.toString().strip(), List.of(), back == null ? this::openPass : back);
    }

    /** The boosts running for this player right now, or what would start one. */
    List<Component> boosts(SeasonStore.Row row) {
        List<Component> lines = new ArrayList<>();
        if (variables.bool("season.rally.enabled")) {
            lines.add(rally
                    ? Component.text("RALLY LIVE  ", ORANGE, TextDecoration.BOLD)
                            .append(Component.text(multiplier(rallyPercent()) + " Season XP while "
                                    + (rallyThreshold - 1) + "+ players stay on", NamedTextColor.WHITE)
                                    .decoration(TextDecoration.BOLD, false))
                    : Component.text("Rally at " + rallyThreshold + " active players", NamedTextColor.WHITE)
                            .append(Component.text("   " + activeCount + " now  ·  "
                                    + multiplier(variables.integer("season.rally.xp-percent"))
                                    + " Season XP. Bring friends.", MenuText.MUTED)));
        }
        int tier = SeasonPassRules.tier(row.xp, xpPerTier(), maximumTier());
        int pace = paceTier();
        if (SeasonQuestRules.catchingUp(tier, pace)) {
            lines.add(Component.text("CATCH-UP  ", NamedTextColor.GREEN, TextDecoration.BOLD)
                    .append(Component.text("Quest XP " + multiplier(variables.integer("season.catch-up.xp-percent"))
                            + " until Tier " + pace, NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false)));
        }
        return lines;
    }

    /** Pixel width of a section divider, rule and title together. */
    static final int DIVIDER_WIDTH = 300;

    /**
     * A centred {@code ───── WEEKLY QUESTS ─────} divider with its note underneath, so each
     * section reads as its own block instead of one long list. The rule is struck-through
     * spaces: a solid line on every client, where dash glyphs leave gaps.
     */
    private void header(List<DialogBody> page, StringBuilder plain, String title, String note) {
        String rule = " ".repeat(dividerSpaces(title));
        page.add(DialogBody.plainMessage(MenuText.upright(Component.empty()
                .append(Component.text(rule, DIVIDER).decoration(TextDecoration.STRIKETHROUGH, true))
                .append(Component.text("  " + title + "  ", ORANGE, TextDecoration.BOLD))
                .append(Component.text(rule, DIVIDER).decoration(TextDecoration.STRIKETHROUGH, true))),
                RULE_WIDTH));
        page.add(DialogBody.plainMessage(MenuText.muted(note), RULE_WIDTH));
        page.add(DialogBody.plainMessage(Component.empty(), RULE_WIDTH));
        plain.append("\n----- ").append(title).append(" -----\n").append(note).append("\n\n");
    }

    private static final TextColor DIVIDER = TextColor.color(0x7A8494);

    /** Spaces of rule on each side of a title so every divider is the same width. */
    static int dividerSpaces(String title) {
        int titleWidth = SidebarText.textWidth("  " + title + "  ", true);
        return Math.max(3, (DIVIDER_WIDTH - titleWidth) / 2 / SidebarText.SPACE_WIDTH);
    }

    private void board(
            List<DialogBody> page, StringBuilder plain, String title, String note, SeasonStore.Board board,
            long base, long sweepXp, SeasonStore.Row row
    ) {
        header(page, plain, title, note);
        if (board == null) return;
        long xp = questXp(row, base);
        for (SeasonStore.Slot slot : board.slots) {
            var objective = SeasonQuestRules.Objective.of(slot.objective);
            if (objective.isEmpty()) continue;
            String goal = goalLabel(slot.goal);
            String label = objective.get().label(slot.target);
            if (slot.done) {
                page.add(DialogBody.plainMessage(MenuText.upright(Component.empty()
                        .append(MenuText.sprite("item/lime_dye")).append(Component.text(" "))
                        .append(Component.text(label + "  ✔", MenuText.VALUE))
                        .append(Component.text("   " + goal, MenuText.MUTED))), RULE_WIDTH));
                plain.append("✔ ").append(label).append('\n');
                page.add(DialogBody.plainMessage(Component.empty(), RULE_WIDTH));
                continue;
            }
            page.add(DialogBody.plainMessage(MenuText.upright(Component.empty()
                    .append(MenuText.sprite(objective.get().sprite)).append(Component.text(" "))
                    .append(Component.text(label, NamedTextColor.WHITE))
                    .append(Component.text("   +" + xp + " XP", MenuText.GOLD))), RULE_WIDTH));
            page.add(DialogBody.plainMessage(MenuText.upright(bar(slot.progress, slot.target)
                    .append(Component.text(String.format(Locale.ROOT, "  %,d / %,d   ·   %s",
                            slot.progress, slot.target, goal), MenuText.MUTED))), RULE_WIDTH));
            plain.append(goal).append(": ").append(label)
                    .append(String.format(Locale.ROOT, " (%,d/%,d) +%d XP%n", slot.progress, slot.target, xp));
            page.add(DialogBody.plainMessage(Component.empty(), RULE_WIDTH));
        }
        if (sweepXp > 0L) {
            boolean swept = board.swept;
            page.add(DialogBody.plainMessage(swept
                    ? MenuText.upright(Component.text("Board cleared  ✔", MenuText.VALUE, TextDecoration.BOLD))
                    : MenuText.upright(Component.text("Finish all three: ", MenuText.MUTED)
                            .append(Component.text("+" + questXp(row, sweepXp) + " bonus XP", MenuText.GOLD,
                                    TextDecoration.BOLD))), RULE_WIDTH));
            plain.append(swept ? "Board cleared\n" : "All three: +" + questXp(row, sweepXp) + " bonus XP\n");
        }
        page.add(DialogBody.plainMessage(Component.empty(), RULE_WIDTH));
    }

    private void community(List<DialogBody> page, StringBuilder plain, Player player) {
        if (!variables.bool("season.community.enabled")) return;
        SeasonStore.Community goal = store.community();
        var objective = SeasonQuestRules.Objective.of(goal.objective);
        if (objective.isEmpty() || goal.week != SeasonQuestRules.weeklyPeriod(today())) return;
        header(page, plain, "COMMUNITY GOAL", "The whole server, together, this week");
        long mine = goal.contributions.getOrDefault(player.getUniqueId().toString(), 0L);
        long minimum = SeasonQuestRules.contributorMinimum(objective.get(), goal.target);
        String label = objective.get().label(goal.target);
        page.add(DialogBody.plainMessage(MenuText.upright(Component.empty()
                .append(MenuText.sprite(goal.completed ? "item/lime_dye" : objective.get().sprite))
                .append(Component.text(" "))
                .append(Component.text(label + (goal.completed ? "  ✔" : ""),
                        goal.completed ? MenuText.VALUE : NamedTextColor.WHITE))
                .append(Component.text("   +" + variables.integer("season.community.xp") + " XP each",
                        MenuText.GOLD))), RULE_WIDTH));
        String share = mine >= minimum ? String.format(Locale.ROOT, "you helped: %,d", mine)
                : String.format(Locale.ROOT, "you: %,d of %,d to share the reward", mine, minimum);
        page.add(DialogBody.plainMessage(MenuText.upright(bar(goal.progress, goal.target)
                .append(Component.text(String.format(Locale.ROOT, "  %,d / %,d   ·   %s",
                        goal.progress, goal.target, share), MenuText.MUTED))), RULE_WIDTH));
        plain.append("Community: ").append(label)
                .append(String.format(Locale.ROOT, " (%,d/%,d), %s%n", goal.progress, goal.target, share));
        page.add(DialogBody.plainMessage(Component.empty(), RULE_WIDTH));
    }

    private static String goalLabel(String goal) {
        try {
            return SeasonQuestRules.Goal.valueOf(goal).label;
        } catch (IllegalArgumentException unknown) {
            return "Quest";
        }
    }

    private static Component bar(long into, long span) {
        int filled = (int) Math.max(0L, Math.min(12L, into * 12L / Math.max(1L, span)));
        return Component.text("█".repeat(filled), MenuText.VALUE)
                .append(Component.text("█".repeat(12 - filled), TextColor.color(0x3A3F4B)));
    }

    /** Open quests on each board, for the pass overview. */
    Component questSummary(UUID playerId) {
        SeasonStore.Row row = store.row(playerId);
        long today = today();
        long daily = row.daily != null && row.daily.period == today ? SeasonQuestRules.done(row.daily) : 0L;
        long weekly = row.weekly != null && row.weekly.period == SeasonQuestRules.weeklyPeriod(today)
                ? SeasonQuestRules.done(row.weekly) : 0L;
        return Component.text("Daily " + daily + "/3   ·   Weekly " + weekly + "/3", NamedTextColor.WHITE);
    }

    List<Component> boosts(UUID playerId) {
        return boosts(store.row(playerId));
    }

    /** Bedrock has no dialogs: the same overview as a form, and each tier still opens its chest. */
    private void openPassForms(Player player) {
        int tier = tier(player.getUniqueId());
        int first = Math.min(maximumTier(), tier + 1);
        StringBuilder text = new StringBuilder("Tier " + tier + " of " + maximumTier() + ". Season ends " + endsIn()
                + ".\nSeason Hearts " + store.hearts(player.getUniqueId()) + " / " + heartCap()
                + "\n" + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(questSummary(player.getUniqueId()))
                + "\n\nTap a tier to see its rewards.");
        List<BedrockForms.Button> buttons = new ArrayList<>();
        for (int next = first; next <= Math.min(maximumTier(), first + 5); next++) {
            int chosen = next;
            buttons.add(new BedrockForms.Button("Tier " + next + ": " + describe(grants(next)),
                    () -> menu.openTier(player, chosen)));
        }
        buttons.add(new BedrockForms.Button("Quests", () -> openQuests(player, this::openPass)));
        buttons.add(new BedrockForms.Button("Season Top", () -> openTop(player)));
        if (!forms.menu(player, "Season " + store.season() + " Pass", text.toString(), buttons, null)) {
            menu.openTier(player, first);
        }
    }

    boolean supportsDialogs(Player player) {
        return clientSupport.supportsDialogs(player);
    }

    /** The model a gear grant would wear this season, for its icon. */
    java.util.Optional<String> seasonGearModel(SeasonPassRules.Grant grant) {
        return seasonGear(grant).map(piece -> SeasonGear.modelKey(store.season(), piece));
    }

    void openTop(Player player) {
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
                + variables.integer("season.third-place-shards") + " Shards. First place also wins "
                + variables.integer("season.first-place-giftbags") + " Mythic Giftbag."), RULE_WIDTH));
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

    String endsIn() {
        long millis = LocalDate.ofEpochDay(store.endsDay()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                - System.currentTimeMillis();
        return "in " + compact(millis);
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
