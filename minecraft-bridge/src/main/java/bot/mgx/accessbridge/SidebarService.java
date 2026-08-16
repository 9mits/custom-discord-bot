package bot.mgx.accessbridge;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.ChatColor;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class SidebarService {
    private static final int MAX_LINES = 15;
    /**
     * Glyphs supplied by the optional Mysterious SMP X resource pack. Players who
     * decline the pack see the wordmark fall back to plain text instead.
     */
    private static final Key BRAND_FONT = Key.key("minecraft", "mgx");
    private static final String LOGO_LARGE = "\uE000";
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private static final TextColor GOLD = TextColor.color(0xFFB52E);
    private static final ChatColor[] ENTRY_COLOURS = {
            ChatColor.BLACK, ChatColor.DARK_BLUE, ChatColor.DARK_GREEN, ChatColor.DARK_AQUA,
            ChatColor.DARK_RED, ChatColor.DARK_PURPLE, ChatColor.GOLD, ChatColor.GRAY,
            ChatColor.DARK_GRAY, ChatColor.BLUE, ChatColor.GREEN, ChatColor.AQUA,
            ChatColor.RED, ChatColor.LIGHT_PURPLE, ChatColor.WHITE
    };

    private final MGXAccessBridge plugin;
    private final PlayerPerkService perks;
    private final ClanStore clans;
    private final DiscordIdentityService identities;
    private final PlayerSettingsStore settings;
    private final String footer;
    private final int updateTicks;
    private final Map<UUID, PlayerBoard> boards = new HashMap<>();
    private int taskId = -1;

    SidebarService(
            MGXAccessBridge plugin,
            PlayerPerkService perks,
            ClanStore clans,
            DiscordIdentityService identities,
            PlayerSettingsStore settings,
            String footer,
            int updateTicks
    ) {
        this.plugin = plugin;
        this.perks = perks;
        this.clans = clans;
        this.identities = identities;
        this.settings = settings;
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
    }

    void refreshAll() {
        boards.keySet().removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            updateTabName(player);
            updateTabHeaderAndFooter(player);
            refresh(player);
        }
    }

    void refresh(Player player) {
        PlayerBoard board = boards.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerBoard(player));
        syncClanTeams(board.scoreboard, player);
        board.update(lines(player));
        if (player.getScoreboard() != board.scoreboard) {
            player.setScoreboard(board.scoreboard);
        }
    }

    private List<Component> lines(Player player) {
        PlayerProfile profile = perks.profile(player.getUniqueId());

        // Build the stat rows first: their widest line decides what "centred" means
        // for the headings, the wordmark tail and the footer.
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("PROFILE", null, null));
        if (profile.hasRankLabel()) {
            rows.add(new Row("Rank", profile.rankLabel(), TextColor.color(profile.rankColour())));
        }
        rows.add(new Row("Server Level", String.valueOf(profile.level()), NamedTextColor.GREEN));
        rows.add(new Row("Extra Hearts", String.valueOf(profile.totalExtraHearts()), NamedTextColor.GREEN));
        int damageBonus = (int) Math.round((profile.damageMultiplier() - 1.0) * 100);
        if (damageBonus > 0) {
            rows.add(new Row("Power", "+" + damageBonus + "% damage", NamedTextColor.LIGHT_PURPLE));
        }
        clans.clanOf(player.getUniqueId()).ifPresent(clan -> {
            rows.add(new Row("Clan", clan.name(), clanColor(clan)));
            addClanPerkRows(rows, perks.clanPerks(player.getUniqueId()));
        });
        rows.add(new Row("STATS", null, null));
        rows.add(new Row(
                "Kills",
                String.valueOf(player.getStatistic(Statistic.PLAYER_KILLS)),
                GOLD
        ));
        rows.add(new Row(
                "Deaths",
                String.valueOf(player.getStatistic(Statistic.DEATHS)),
                NamedTextColor.YELLOW
        ));

        String playerLine = " " + player.getName() + " (" + player.getPing() + "ms)";
        int width = SidebarText.textWidth(playerLine, false);
        for (Row row : rows) {
            width = Math.max(width, row.width());
        }

        ArrayList<Component> lines = new ArrayList<>();
        lines.add(Component.text(
                SidebarText.centredToWidth("SMP X", width, true),
                NamedTextColor.WHITE,
                TextDecoration.BOLD
        ));
        lines.add(Component.empty());
        lines.add(Component.text(" " + player.getName(), NamedTextColor.AQUA, TextDecoration.BOLD)
                .append(Component.text(" (" + player.getPing() + "ms)", NamedTextColor.GRAY)
                        .decoration(TextDecoration.BOLD, false)));
        for (Row row : rows) {
            if (row.isHeading()) {
                lines.add(Component.empty());
            }
            lines.add(row.render(width));
        }

        lines.add(Component.empty());
        lines.add(Component.text(
                SidebarText.centredToWidth(footer, width, true), ORANGE, TextDecoration.BOLD
        ));
        // A player with every extra overflows the 15-row sidebar. Give up spacers from
        // the top down: the gap above the footer is what separates it from the stats,
        // so it is the last thing to go rather than the first.
        while (lines.size() > MAX_LINES) {
            int spacer = firstSpacerAfter(lines, 2);
            if (spacer < 0) {
                break;
            }
            lines.remove(spacer);
        }
        return lines;
    }

    private static void addClanPerkRows(List<Row> rows, ClanLevel.Perks clanPerks) {
        if (clanPerks == null || clanPerks.isNone()) {
            return;
        }
        if (clanPerks.extraHearts() > 0) {
            rows.add(new Row("Clan Hearts", "+" + clanPerks.extraHearts(), NamedTextColor.RED));
        }
        addClanPercent(rows, "Strength", clanPerks.strength());
        addClanPercent(rows, "Saturation", clanPerks.saturation());
        addClanPercent(rows, "Digging", clanPerks.diggingSpeed());
        addClanPercent(rows, "Resistance", clanPerks.resistance());
        addClanPercent(rows, "Speed", clanPerks.speed());
    }

    private static void addClanPercent(List<Row> rows, String label, double fraction) {
        if (fraction > 0) {
            rows.add(new Row(label, "+" + Math.round(fraction * 100) + "%", GOLD));
        }
    }

    /** A heading (no value) or a stat row, able to report its own rendered width. */
    private record Row(String label, String value, TextColor valueColour) {
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

    /** Finds a blank line to sacrifice, never the gap immediately above the footer. */
    private static int firstSpacerAfter(List<Component> lines, int from) {
        for (int index = from; index < lines.size() - 2; index++) {
            if (lines.get(index).equals(Component.empty())) {
                return index;
            }
        }
        return -1;
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

    private void updateTabName(Player player) {
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
                .append(divider())
                .append(Component.text(platform.edition(), editionColor(platform)));
        if (platform.showsDevice()) {
            rendered = rendered.append(Component.text("·" + platform.device(), NamedTextColor.GRAY));
        }
        rendered = rendered
                .append(divider())
                .append(Component.text(player.getPing() + "ms", pingColor(player.getPing())));
        player.playerListName(rendered);
    }

    private void updateTabHeaderAndFooter(Player player) {
        int online = plugin.getServer().getOnlinePlayers().size();
        // The wordmark glyph is drawn well above its baseline, so it needs more
        // clearance above than below to avoid crowding whatever sits around it.
        Component header = Component.empty()
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text(LOGO_LARGE).font(BRAND_FONT))
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
        Component footerComponent = Component.empty()
                .append(Component.newline())
                .append(Component.text("/guide", GOLD))
                .append(divider())
                .append(Component.text("discord.gg/mgx", ORANGE))
                .append(Component.newline());
        player.sendPlayerListHeaderAndFooter(header, footerComponent);
    }

    private static ClientPlatform clientPlatform(Player player) {
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
        private final List<String> entries = new ArrayList<>();
        private final List<Team> teams = new ArrayList<>();

        PlayerBoard(Player player) {
            Scoreboard created = plugin.getServer().getScoreboardManager().getNewScoreboard();
            // The client centres the objective title for us, on both editions.
            // Sidebar rows are left-aligned, so anything stacked below would have to
            // be space-padded and would never line up; keep the wordmark on one line.
            Component title = Component.text("MYSTERIOUS", ORANGE, TextDecoration.BOLD);
            Objective createdObjective = created.registerNewObjective("mgx", Criteria.DUMMY, title);
            createdObjective.setDisplaySlot(DisplaySlot.SIDEBAR);
            for (int index = 0; index < MAX_LINES; index++) {
                String entry = ENTRY_COLOURS[index].toString();
                Team team = created.registerNewTeam("line_" + index);
                team.addEntry(entry);
                entries.add(entry);
                teams.add(team);
            }
            this.scoreboard = created;
            this.objective = createdObjective;
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
