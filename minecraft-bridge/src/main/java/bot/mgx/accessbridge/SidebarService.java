package bot.mgx.accessbridge;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

final class SidebarService {
    private static final int MAX_LINES = SidebarLayout.MAX_LINES;
    /**
     * Glyphs supplied by the Mysterious SMP X resource pack, which
     * {@code require-resource-pack} makes mandatory for Java. Bedrock never has
     * them: Geyser does not convert Java packs, and a font provider is a Java-only
     * concept, so a Bedrock client draws U+E000 as a missing-glyph box. Anything
     * using this font has to have a text form for Bedrock — see {@code wordmark}.
     */
    private static final Key BRAND_FONT = Key.key("minecraft", "mgx");
    private static final String LOGO_LARGE = "\uE000";
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private static final TextColor GOLD = TextColor.color(0xFFB52E);
    // Columns are measured against whoever is actually online, not a fixed maximum:
    // padding every row out to a worst-case width leaves a lone player with short
    // tags staring at a tab list of empty space.
    private static final int TAB_COLUMN_GUTTER = SidebarText.SPACE_WIDTH * 2;
    private static final String[] ENTRY_KEYS = {
            "\u00A70", "\u00A71", "\u00A72", "\u00A73", "\u00A74", "\u00A75", "\u00A76", "\u00A77",
            "\u00A78", "\u00A79", "\u00A7a", "\u00A7b", "\u00A7c", "\u00A7d", "\u00A7f"
    };

    private final MGXAccessBridge plugin;
    private final PlayerPerkService perks;
    private final ClanStore clans;
    private final DiscordIdentityService identities;
    private final PlayerSettingsStore settings;
    private final EconomyStore money;
    private final String footer;
    private final int updateTicks;
    private final Map<UUID, PlayerBoard> boards = new HashMap<>();
    private final Map<UUID, ClientPlatform> platforms = new ConcurrentHashMap<>();
    /**
     * What each player's tab header and footer last said. Per player, not one shared
     * key: the footer carries their own clan boosts now, so a global "did the online
     * count change" gate would leave someone who just joined a clan looking at an
     * empty block until an unrelated player logged in.
     */
    private final Map<UUID, String> tabKeys = new HashMap<>();
    /** Players whose sidebar is hidden for a reason other than their setting. */
    private final java.util.Set<UUID> suppressed = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private String lastTeamKey = "";
    private int taskId = -1;
    private AfkService afkService;
    private LeaderboardService leaderboard;

    SidebarService(
            MGXAccessBridge plugin,
            PlayerPerkService perks,
            ClanStore clans,
            DiscordIdentityService identities,
            PlayerSettingsStore settings,
            EconomyStore money,
            String footer,
            int updateTicks
    ) {
        this.plugin = plugin;
        this.perks = perks;
        this.clans = clans;
        this.identities = identities;
        this.settings = settings;
        this.money = money;
        this.footer = footer;
        this.updateTicks = updateTicks;
    }

    void start() {
        taskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
                plugin,
                this::refreshAll,
                1L,
                updateTicks
        );
    }

    void useAfkService(AfkService service) {
        this.afkService = service;
    }

    void useLeaderboardService(LeaderboardService service) {
        this.leaderboard = service;
    }

    void stop() {
        if (taskId >= 0) {
            plugin.getServer().getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        Scoreboard main = plugin.getServer().getScoreboardManager().getMainScoreboard();
        boards.forEach((uuid, board) -> {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && player.getScoreboard() == board.scoreboard) {
                player.setScoreboard(main);
            }
            if (player != null) {
                player.playerListName(null);
                player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            }
        });
        boards.clear();
        tabKeys.clear();
        lastTeamKey = "";
    }

    void refreshAll() {
        boards.keySet().removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
        tabKeys.keySet().removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
        Collection<? extends Player> online = plugin.getServer().getOnlinePlayers();
        String teamKey = teamFingerprint(online);
        boolean teamsChanged = !teamKey.equals(lastTeamKey);
        lastTeamKey = teamKey;
        int nameColumn = 0;
        int platformColumn = 0;
        for (Player player : online) {
            nameColumn = Math.max(nameColumn, tabNameWidth(player));
            platformColumn = Math.max(
                    platformColumn, SidebarText.textWidth(platformLabel(player), false)
            );
        }
        nameColumn += TAB_COLUMN_GUTTER;
        platformColumn += TAB_COLUMN_GUTTER;
        for (Player player : online) {
            updateTabName(player, nameColumn, platformColumn);
            updateTabHeaderAndFooter(player);
            refresh(player, teamsChanged);
        }
    }

    void refresh(Player player) {
        refresh(player, true);
    }

    private void refresh(Player player, boolean syncTeams) {
        PlayerBoard board = boards.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerBoard(player));
        if (syncTeams) {
            syncClanTeams(board.scoreboard, player);
        }
        boolean showSidebar = !suppressed.contains(player.getUniqueId())
                && settings.isEnabled(
                        player.getUniqueId(), PlayerSettingsStore.Setting.SCOREBOARD_ENABLED
                );
        if (showSidebar) {
            board.update(lines(player));
        }
        board.updateNameplates(plugin.getServer().getOnlinePlayers());
        board.setSidebarVisible(showSidebar);
        if (player.getScoreboard() != board.scoreboard) {
            player.setScoreboard(board.scoreboard);
        }
    }

    private String teamFingerprint(Collection<? extends Player> online) {
        StringBuilder key = new StringBuilder();
        for (Player player : online) {
            PlayerProfile profile = perks.profile(player.getUniqueId());
            key.append(player.getUniqueId())
                    .append(':')
                    .append(profile.rankWeight())
                    .append(':')
                    .append(clans.clanOf(player.getUniqueId())
                            .map(clan -> clan.name() + clan.level() + clan.themeColor())
                            .orElse(""))
                    .append(':')
                    .append(identities.visibleUsername(player.getUniqueId()).orElse(""))
                    .append(':')
                    .append(settings.isEnabled(player.getUniqueId(), PlayerSettingsStore.Setting.CLAN_TAGS))
                    .append(';');
        }
        return key.toString();
    }

    private int tpsBucket() {
        double tps = Math.min(plugin.getServer().getTPS()[0], 20.0);
        if (tps < 15.0) {
            return 0;
        }
        if (tps < 18.0) {
            return 1;
        }
        return 2;
    }

    private List<Component> lines(Player player) {
        PlayerProfile profile = perks.profile(player.getUniqueId());
        UUID playerId = player.getUniqueId();

        List<Row> rows = new ArrayList<>();
        if (settings.isEnabled(playerId, PlayerSettingsStore.Setting.SCOREBOARD_PROFILE)) {
            rows.add(Row.heading("PROFILE"));
            if (profile.hasRankLabel()) {
                rows.add(Row.important("Rank", profile.rankLabel(), TextColor.color(profile.rankColour())));
            }
            rows.add(Row.important("Server Level", String.valueOf(profile.level()), NamedTextColor.GREEN));
            // The total, not just the level half: level hearts and clan hearts are two
            // separate attribute modifiers that stack in game, so reporting one of them
            // understated what the player was actually carrying. The tab list breaks out
            // the clan's share. Damage is not on the board at all — /perks covers it.
            rows.add(Row.important(
                    "Extra Hearts",
                    String.valueOf(profile.totalExtraHearts()
                            + perks.clanPerks(playerId).extraHearts()),
                    NamedTextColor.GREEN));
            // The level stands in for the boosts, which are spelled out in the tab list
            // where there is room for their real names.
            clans.clanOf(playerId).ifPresent(clan -> rows.add(Row.important(
                    "Clan",
                    clan.level() > 0 ? clan.name() + " Lv" + clan.level() : clan.name(),
                    clanColor(clan))));
        }
        if (settings.isEnabled(playerId, PlayerSettingsStore.Setting.SCOREBOARD_STATS)) {
            rows.add(Row.heading("STATS"));
            rows.add(Row.essential(
                    "Kills",
                    String.valueOf(player.getStatistic(Statistic.PLAYER_KILLS)),
                    GOLD
            ));
            rows.add(Row.essential(
                    "Deaths",
                    String.valueOf(player.getStatistic(Statistic.DEATHS)),
                    NamedTextColor.YELLOW
            ));
        }
        if (settings.isEnabled(playerId, PlayerSettingsStore.Setting.SCOREBOARD_ECONOMY)) {
            rows.add(Row.heading("ECONOMY"));
            rows.add(Row.essential(
                    "Money",
                    EconomyFormat.dollars(money.balance(playerId)),
                    GOLD
            ));
        }

        String playerLine = " " + player.getName() + " (" + player.getPing() + "ms)";
        Component name = Component.text(" " + player.getName(), NamedTextColor.AQUA, TextDecoration.BOLD)
                .append(Component.text(" (" + player.getPing() + "ms)", NamedTextColor.GRAY)
                        .decoration(TextDecoration.BOLD, false));

        List<Line> board = new ArrayList<>();
        board.add(Line.of(SidebarLayout.Priority.ESSENTIAL, 0,
                width -> Component.text(
                        SidebarText.centredToWidth("SMP X", width, true),
                        NamedTextColor.WHITE,
                        TextDecoration.BOLD)));
        board.add(Line.spacer());
        board.add(Line.of(SidebarLayout.Priority.ESSENTIAL,
                SidebarText.textWidth(playerLine, false), width -> name));
        for (Row row : rows) {
            if (row.isHeading()) {
                board.add(Line.spacer());
            }
            board.add(Line.of(row.priority(), row.width(), row::render));
        }
        board.add(Line.spacer());
        board.add(Line.of(SidebarLayout.Priority.ESSENTIAL, 0,
                width -> Component.text(
                        SidebarText.centredToWidth(footer, width, true), ORANGE, TextDecoration.BOLD)));

        SidebarLayout.Priority[] priorities = new SidebarLayout.Priority[board.size()];
        for (int index = 0; index < board.size(); index++) {
            priorities[index] = board.get(index).priority();
        }
        boolean[] keep = SidebarLayout.fit(priorities, MAX_LINES);

        // Measured after trimming, so a board that gave up its widest row centres to
        // what is actually left rather than to a line nobody can see.
        int width = 0;
        for (int index = 0; index < board.size(); index++) {
            if (keep[index]) {
                width = Math.max(width, board.get(index).width());
            }
        }

        List<Component> drawn = new ArrayList<>();
        for (int index = 0; index < board.size(); index++) {
            if (keep[index]) {
                drawn.add(board.get(index).render(width));
            }
        }
        return drawn;
    }

    /** One drawn row, with what it costs the board and how readily it gives that up. */
    private record Line(SidebarLayout.Priority priority, int width, IntFunction<Component> render) {
        static Line of(SidebarLayout.Priority priority, int width, IntFunction<Component> render) {
            return new Line(priority, width, render);
        }

        static Line spacer() {
            return new Line(SidebarLayout.Priority.SPACER, 0, width -> Component.empty());
        }

        Component render(int boardWidth) {
            return render.apply(boardWidth);
        }
    }

    /** A clan's live boosts by their real names, for the tab list. */
    private static List<String> boostLabels(ClanLevel.Perks clanPerks) {
        List<String> labels = new ArrayList<>();
        if (clanPerks == null || clanPerks.isNone()) {
            return labels;
        }
        if (clanPerks.extraHearts() > 0) {
            labels.add("+" + clanPerks.extraHearts() + " Hearts");
        }
        addBoost(labels, clanPerks.strength(), "Strength");
        addBoost(labels, clanPerks.saturation(), "Saturation");
        addBoost(labels, clanPerks.diggingSpeed(), "Digging");
        addBoost(labels, clanPerks.resistance(), "Resistance");
        addBoost(labels, clanPerks.speed(), "Speed");
        return labels;
    }

    private static void addBoost(List<String> labels, double fraction, String name) {
        if (fraction > 0) {
            labels.add(name + " +" + Math.round(fraction * 100) + "%");
        }
    }

    /** A heading (no value) or a stat row, able to report its own rendered width. */
    private record Row(
            String label, String value, TextColor valueColour, SidebarLayout.Priority priority
    ) {
        static Row heading(String label) {
            return new Row(label, null, null, SidebarLayout.Priority.HEADING);
        }

        static Row important(String label, String value, TextColor colour) {
            return new Row(label, value, colour, SidebarLayout.Priority.IMPORTANT);
        }

        static Row essential(String label, String value, TextColor colour) {
            return new Row(label, value, colour, SidebarLayout.Priority.ESSENTIAL);
        }

        boolean isHeading() {
            return value == null;
        }

        int width() {
            return isHeading()
                    ? SidebarText.textWidth(label, true)
                    : SidebarText.textWidth(" » " + label + ": ", false) + SidebarText.textWidth(value, true);
        }

        Component render(int boardWidth) {
            if (isHeading()) {
                return Component.text(
                        SidebarText.centredToWidth(label, boardWidth, true),
                        ORANGE,
                        TextDecoration.BOLD
                );
            }
            return statLine(label, value, valueColour);
        }
    }

    /**
     * A muted marker, a white label and a coloured value. Nothing is bold: bold is
     * reserved for the sidebar title so it still reads as the heading.
     */
    private static Component statLine(String label, String value, TextColor valueColor) {
        return Component.text(" » ", GOLD)
                .append(Component.text(label + ": ", NamedTextColor.WHITE))
                .append(Component.text(value, valueColor, TextDecoration.BOLD));
    }

    /**
     * Minecraft's font is proportional, so the sidebar cannot be centred exactly.
     * Padding against a nominal width lands close enough to read as centred.
     */

    private String afkLabel(Player player) {
        return afkService != null && afkService.isAfk(player.getUniqueId()) ? " [AFK]" : "";
    }

    private String discordTag(Player player) {
        return identities.visibleUsername(player.getUniqueId())
                .map(username -> "(@" + username + ") ").orElse("");
    }

    private String platformLabel(Player player) {
        ClientPlatform platform = clientPlatform(player);
        return platform.edition() + (platform.showsDevice() ? " · " + platform.device() : "");
    }

    /** Rendered width of everything left of the first divider. */
    private int tabNameWidth(Player player) {
        PlayerProfile profile = perks.profile(player.getUniqueId());
        int width = profile.hasRankLabel()
                ? SidebarText.textWidth("[" + profile.rankLabel() + "] ", true) : 0;
        Optional<ClanStore.ClanView> clan = clans.clanOf(player.getUniqueId());
        if (clan.isPresent()) {
            width += SidebarText.textWidth("[" + clan.get().name() + "] ", true);
            if (clan.get().level() > 0) {
                width += SidebarText.textWidth(ClanLevel.badge(clan.get().level()) + " ", true);
            }
        }
        return width + SidebarText.textWidth(
                discordTag(player) + player.getName() + afkLabel(player), false
        );
    }

    private void updateTabName(Player player, int nameColumn, int platformColumn) {
        PlayerProfile profile = perks.profile(player.getUniqueId());
        ClientPlatform platform = clientPlatform(player);
        Component rendered = rankTag(profile);
        Optional<ClanStore.ClanView> clan = clans.clanOf(player.getUniqueId());
        if (clan.isPresent()) {
            rendered = rendered.append(clanTag(clan.get()));
        }
        // The row carries only what a viewer cannot get elsewhere: level already has
        // its own sidebar row, and a Java client's device is always a desktop.
        rendered = rendered
                .append(identities.tag(player.getUniqueId()))
                .append(Component.text(player.getName(), NamedTextColor.WHITE))
                .append(Component.text(afkLabel(player), NamedTextColor.GRAY))
                .append(Component.text(
                        SidebarText.paddingToWidth(tabNameWidth(player), nameColumn),
                        NamedTextColor.DARK_GRAY
                ))
                .append(Component.text("│ ", NamedTextColor.DARK_GRAY))
                .append(Component.text(platform.edition(), editionColor(platform)));
        if (platform.showsDevice()) {
            rendered = rendered.append(Component.text(" · " + platform.device(), NamedTextColor.GRAY));
        }
        rendered = rendered
                .append(Component.text(
                        SidebarText.paddingToWidth(
                                SidebarText.textWidth(platformLabel(player), false),
                                platformColumn
                        ), NamedTextColor.DARK_GRAY
                ))
                .append(Component.text("│ ", NamedTextColor.DARK_GRAY))
                .append(Component.text(player.getPing() + "ms", pingColor(player.getPing())));
        player.playerListName(rendered);
    }

    /**
     * The wordmark, or its name spelled out.
     *
     * <p>The glyph is a bitmap font in the Java resource pack, and Geyser does not
     * convert Java packs — Bedrock has no such font and draws U+E000 as a
     * missing-glyph box at the top of every Bedrock player's tab list. The clearance
     * newlines go with it: they exist to make room for a 44px-tall glyph Bedrock
     * never renders.
     */
    private Component wordmark(Player player) {
        if (clientPlatform(player).bedrock()) {
            return Component.text("MYSTERIOUS SMP X", ORANGE, TextDecoration.BOLD);
        }
        return Component.empty()
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text(LOGO_LARGE).font(BRAND_FONT));
    }

    private void updateTabHeaderAndFooter(Player player) {
        Optional<ClanStore.ClanView> clan = clans.clanOf(player.getUniqueId());
        List<String> boosts = SidebarLayout.boostRows(
                boostLabels(perks.clanPerks(player.getUniqueId())), SidebarLayout.BOOSTS_PER_ROW);
        // The sidebar shows the totals; this says which part the clan is responsible
        // for, so the two numbers agreeing is not a coincidence the player has to spot.
        int online = plugin.getServer().getOnlinePlayers().size();
        String key = online + ":" + tpsBucket() + ":"
                + clan.map(view -> view.name() + view.level()).orElse("") + ":" + boosts;
        if (key.equals(tabKeys.get(player.getUniqueId()))) {
            return;
        }
        tabKeys.put(player.getUniqueId(), key);
        Component header = Component.empty()
                .append(wordmark(player))
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text("Online ", NamedTextColor.GRAY))
                .append(Component.text(
                        online + "/" + plugin.getServer().getMaxPlayers(),
                        NamedTextColor.WHITE
                ))
                .append(divider())
                .append(Component.text("TPS ", NamedTextColor.GRAY))
                .append(tpsValue())
                .append(Component.newline());
        Component footerComponent = Component.empty().append(Component.newline());
        // The sidebar only has room to name the clan and its level; this is where the
        // boosts that level grants are actually spelled out.
        if (clan.isPresent() && !boosts.isEmpty()) {
            footerComponent = footerComponent
                    .append(Component.text(clan.get().name() + " Lv" + clan.get().level() + " boosts",
                            clanColor(clan.get()), TextDecoration.BOLD))
                    .append(Component.newline());
            for (String row : boosts) {
                footerComponent = footerComponent
                        .append(Component.text(row, NamedTextColor.WHITE))
                        .append(Component.newline());
            }
            footerComponent = footerComponent.append(Component.newline());
        }
        footerComponent = footerComponent
                .append(Component.text("/guide", GOLD))
                .append(divider())
                .append(Component.text("discord.gg/mgx", ORANGE))
                .append(Component.newline());
        player.sendPlayerListHeaderAndFooter(header, footerComponent);
    }

    /**
     * Hides the sidebar without touching the player's own setting, so turning it
     * back on restores whatever they actually chose.
     */
    void setSuppressed(UUID playerId, boolean hidden) {
        if (hidden) {
            suppressed.add(playerId);
        } else {
            suppressed.remove(playerId);
        }
    }

    void forget(UUID playerId) {
        suppressed.remove(playerId);
        boards.remove(playerId);
        platforms.remove(playerId);
        tabKeys.remove(playerId);
    }

    private ClientPlatform clientPlatform(Player player) {
        return platforms.computeIfAbsent(player.getUniqueId(), ignored -> lookupPlatform(player));
    }

    private static ClientPlatform lookupPlatform(Player player) {
        try {
            FloodgatePlayer floodgatePlayer = FloodgateApi.getInstance().getPlayer(player.getUniqueId());
            if (floodgatePlayer != null) {
                String deviceOs = floodgatePlayer.getDeviceOs() == null
                        ? "UNKNOWN"
                        : floodgatePlayer.getDeviceOs().name();
                return ClientPlatform.bedrock(deviceOs);
            }
        } catch (RuntimeException ignored) {
            // Floodgate can be briefly unavailable while the server is shutting down.
        }
        return ClientPlatform.JAVA;
    }

    private void syncClanTeams(Scoreboard scoreboard, Player viewer) {
        Map<String, Component> expected = new LinkedHashMap<>();
        Map<String, Set<String>> entries = new LinkedHashMap<>();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            Optional<ClanStore.ClanView> clan = clans.clanOf(online.getUniqueId());
            Optional<String> discordUsername = identities.visibleUsername(online.getUniqueId());
            PlayerProfile profile = perks.profile(online.getUniqueId());
            // Everyone gets a team now: it is what orders the player list.
            String teamName = teamNameFor(online, profile);
            // Nametags omit the Discord name: it is the tightest surface and the name
            // is already shown in chat and the player list.
            // The scoreboard belongs to one viewer, so their clan-tag choice applies.
            boolean showClan = settings.isEnabled(
                    viewer.getUniqueId(), PlayerSettingsStore.Setting.CLAN_TAGS
            );
            Component prefix = rankTag(profile)
                    .append(showClan
                            ? clan.map(SidebarService::clanTag).orElse(Component.empty())
                            : Component.empty());
            expected.put(teamName, prefix);
            entries.computeIfAbsent(teamName, ignored -> new LinkedHashSet<>()).add(online.getName());
        }
        for (Team team : new ArrayList<>(scoreboard.getTeams())) {
            if (team.getName().startsWith("mgx")
                    && !team.getName().startsWith("line_")
                    && !expected.containsKey(team.getName())) {
                team.unregister();
            }
        }
        expected.forEach((teamName, prefix) -> {
            Team team = scoreboard.getTeam(teamName);
            if (team == null) {
                team = scoreboard.registerNewTeam(teamName);
            }
            team.prefix(prefix);
            team.color(NamedTextColor.WHITE);
            Set<String> expectedEntries = entries.getOrDefault(teamName, Set.of());
            for (String oldEntry : new LinkedHashSet<>(team.getEntries())) {
                if (!expectedEntries.contains(oldEntry)) {
                    team.removeEntry(oldEntry);
                }
            }
            expectedEntries.forEach(team::addEntry);
        });
    }

    private final class PlayerBoard {
        private final Scoreboard scoreboard;
        private final Objective objective;
        private final Objective balanceObjective;
        private final List<String> entries = new ArrayList<>();
        private final List<Team> teams = new ArrayList<>();
        private final Set<String> balanceEntries = new LinkedHashSet<>();
        private boolean sidebarVisible = true;

        PlayerBoard(Player player) {
            Scoreboard created = plugin.getServer().getScoreboardManager().getNewScoreboard();
            // The client centres the objective title for us, on both editions.
            // Sidebar rows are left-aligned, so anything stacked below would have to
            // be space-padded and would never line up; keep the wordmark on one line.
            Component title = Component.text("MYSTERIOUS", ORANGE, TextDecoration.BOLD);
            Objective createdObjective = created.registerNewObjective("mgx", Criteria.DUMMY, title);
            createdObjective.setDisplaySlot(DisplaySlot.SIDEBAR);
            Objective createdBalance = created.registerNewObjective(
                    "mgx_balance", Criteria.DUMMY, Component.empty()
            );
            createdBalance.setDisplaySlot(DisplaySlot.BELOW_NAME);
            for (int index = 0; index < MAX_LINES; index++) {
                String entry = ENTRY_KEYS[index];
                Team team = created.registerNewTeam("line_" + index);
                team.addEntry(entry);
                entries.add(entry);
                teams.add(team);
            }
            this.scoreboard = created;
            this.objective = createdObjective;
            this.balanceObjective = createdBalance;
            player.setScoreboard(created);
        }

        void update(List<Component> renderedLines) {
            int count = Math.min(renderedLines.size(), MAX_LINES);
            for (int index = 0; index < MAX_LINES; index++) {
                String entry = entries.get(index);
                if (index >= count) {
                    scoreboard.resetScores(entry);
                    continue;
                }
                teams.get(index).prefix(renderedLines.get(index));
                Score score = objective.getScore(entry);
                score.setScore(count - index);
                score.numberFormat(NumberFormat.blank());
            }
        }

        void updateNameplates(Collection<? extends Player> online) {
            Set<String> current = new LinkedHashSet<>();
            for (Player shown : online) {
                String entry = shown.getName();
                current.add(entry);
                Score score = balanceObjective.getScore(entry);
                score.setScore(0);
                Optional<LeaderboardStandings.Standing> standing = leaderboard == null
                        ? Optional.empty()
                        : leaderboard.standing(shown.getUniqueId());
                score.numberFormat(NumberFormat.fixed(nameplateLine(
                        money.balance(shown.getUniqueId()), standing
                )));
            }
            for (String old : new LinkedHashSet<>(balanceEntries)) {
                if (!current.contains(old)) {
                    scoreboard.resetScores(old);
                }
            }
            balanceEntries.clear();
            balanceEntries.addAll(current);
        }

        void setSidebarVisible(boolean visible) {
            if (sidebarVisible == visible) {
                return;
            }
            if (visible) {
                objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            } else {
                scoreboard.clearSlot(DisplaySlot.SIDEBAR);
            }
            sidebarVisible = visible;
        }
    }

    static TextColor placementColour(int placement) {
        return switch (placement) {
            case 1 -> TextColor.color(0xFFD700);
            case 2 -> TextColor.color(0xC0C0C0);
            case 3 -> TextColor.color(0xCD7F32);
            default -> NamedTextColor.GRAY;
        };
    }

    static Component nameplateLine(
            long balance, Optional<LeaderboardStandings.Standing> standing
    ) {
        String compact = EconomyFormat.compactDollars(balance);
        String amount = compact.startsWith("$") ? compact.substring(1) : compact;
        Component line = Component.text("$ " + amount, NamedTextColor.GREEN)
                .decoration(TextDecoration.BOLD, false);
        if (standing.isEmpty()) {
            return line;
        }
        LeaderboardStandings.Standing row = standing.get();
        return line
                .append(Component.text("   ", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.BOLD, false))
                .append(Component.text(row.type().icon() + " ", leaderboardIconColour(row.type()))
                        .decoration(TextDecoration.BOLD, false))
                .append(Component.text("#" + row.placement(), placementColour(row.placement()))
                        .decoration(TextDecoration.BOLD, false));
    }

    static TextColor leaderboardIconColour(LeaderboardType type) {
        return switch (type) {
            case WEALTH -> NamedTextColor.GREEN;
            case KILLS -> NamedTextColor.RED;
            case PLAYTIME -> NamedTextColor.AQUA;
            case BLOCKS_MINED -> NamedTextColor.GOLD;
            case BLOCKS_WALKED -> NamedTextColor.WHITE;
        };
    }

    private static TextColor clanColor(ClanStore.ClanView clan) {
        return TextColor.color(clan.themeColor());
    }

    private static Component clanTag(ClanStore.ClanView clan) {
        Component tag = Component.text("[" + clan.name() + "] ", clanColor(clan), TextDecoration.BOLD);
        if (clan.level() <= 0) {
            return tag;
        }
        return tag.append(Component.text(
                        ClanLevel.badge(clan.level()),
                        TextColor.color(ClanLevel.badgeColor(clan.level())),
                        TextDecoration.BOLD))
                .append(Component.text(" "));
    }

    private static Component divider() {
        return Component.text("  │  ", NamedTextColor.DARK_GRAY);
    }

    private Component tpsValue() {
        double tps = Math.min(plugin.getServer().getTPS()[0], 20.0);
        TextColor colour = NamedTextColor.GREEN;
        if (tps < 18.0) {
            colour = NamedTextColor.YELLOW;
        }
        if (tps < 15.0) {
            colour = NamedTextColor.RED;
        }
        return Component.text(String.format("%.1f", tps), colour);
    }

    /**
     * Player-list order is decided by team name, so rows are keyed on the rank
     * weight the bot derived from Discord role position. Higher weight sorts
     * first; unranked players fall to the bottom. Team names are capped at 16
     * characters, which this key just fits.
     */
    private static String teamNameFor(Player player, PlayerProfile profile) {
        int inverted = Math.max(0, 9_999 - profile.rankWeight());
        return "mgx" + String.format("%04d", inverted)
                + player.getUniqueId().toString().replace("-", "").substring(0, 9);
    }

    static Component rankTag(PlayerProfile profile) {
        if (!profile.hasRankLabel()) {
            return Component.empty();
        }
        return Component.text(
                "[" + profile.rankLabel() + "] ",
                TextColor.color(profile.rankColour()),
                TextDecoration.BOLD
        );
    }

    private static TextColor pingColor(int ping) {
        if (ping <= 100) {
            return NamedTextColor.GREEN;
        }
        if (ping <= 200) {
            return NamedTextColor.YELLOW;
        }
        return NamedTextColor.RED;
    }

    private static TextColor editionColor(ClientPlatform platform) {
        return platform.edition().equals("BEDROCK") ? NamedTextColor.GREEN : NamedTextColor.AQUA;
    }
}
