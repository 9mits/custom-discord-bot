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
    private static final int SIDEBAR_WIDTH = 22;
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
    private final String footer;
    private final int updateTicks;
    private final Map<UUID, PlayerBoard> boards = new HashMap<>();
    private int taskId = -1;

    SidebarService(
            MGXAccessBridge plugin,
            PlayerPerkService perks,
            ClanStore clans,
            DiscordIdentityService identities,
            String footer,
            int updateTicks
    ) {
        this.plugin = plugin;
        this.perks = perks;
        this.clans = clans;
        this.identities = identities;
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
        syncClanTeams(board.scoreboard);
        board.update(lines(player));
        if (player.getScoreboard() != board.scoreboard) {
            player.setScoreboard(board.scoreboard);
        }
    }

    private List<Component> lines(Player player) {
        PlayerProfile profile = perks.profile(player.getUniqueId());
        ArrayList<Component> lines = new ArrayList<>();
        lines.add(Component.text(" " + player.getName(), NamedTextColor.AQUA, TextDecoration.BOLD)
                .append(Component.text(" (" + player.getPing() + "ms)", NamedTextColor.GRAY)
                        .decoration(TextDecoration.BOLD, false)));
        if (profile.hasRankLabel()) {
            lines.add(statLine("Rank", profile.rankLabel(), TextColor.color(profile.rankColour())));
        }
        lines.add(statLine(
                "Kills",
                String.valueOf(player.getStatistic(Statistic.PLAYER_KILLS)),
                GOLD
        ));
        lines.add(statLine(
                "Deaths",
                String.valueOf(player.getStatistic(Statistic.DEATHS)),
                NamedTextColor.YELLOW
        ));
        lines.add(statLine("Server Level", String.valueOf(profile.level()), NamedTextColor.GREEN));
        lines.add(statLine("Extra Hearts", String.valueOf(profile.extraHearts()), NamedTextColor.GREEN));
        if (profile.elite()) {
            lines.add(statLine("Power", "+5% damage", NamedTextColor.LIGHT_PURPLE));
        }
        clans.clanOf(player.getUniqueId()).ifPresent(clan ->
                lines.add(statLine("Clan", clan.name(), clanColor(clan)))
        );
        // A fully decorated player fills the sidebar, so the breathing room above
        // the footer yields rather than pushing the footer off the board.
        if (lines.size() + 2 <= MAX_LINES) {
            lines.add(Component.empty());
        }
        lines.add(footerLine());
        return lines;
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

    private Component footerLine() {
        return Component.text(centred(footer), ORANGE, TextDecoration.BOLD);
    }

    /**
     * Minecraft's font is proportional, so the sidebar cannot be centred exactly.
     * Padding against a nominal width lands close enough to read as centred.
     */
    private static String centred(String text) {
        int padding = Math.max(0, (SIDEBAR_WIDTH - text.length()) / 2);
        return " ".repeat(padding) + text;
    }

    private void updateTabName(Player player) {
        PlayerProfile profile = perks.profile(player.getUniqueId());
        ClientPlatform platform = clientPlatform(player);
        Component rendered = rankTag(profile);
        Optional<ClanStore.ClanView> clan = clans.clanOf(player.getUniqueId());
        if (clan.isPresent()) {
            rendered = rendered.append(clanTag(clan.get()));
        }
        rendered = rendered
                .append(identities.tag(player.getUniqueId()))
                .append(Component.text(player.getName(), NamedTextColor.WHITE))
                .append(divider())
                .append(Component.text("Lv" + profile.level(), NamedTextColor.AQUA))
                .append(divider())
                .append(Component.text(platform.edition(), editionColor(platform)))
                .append(Component.text("·" + platform.device(), NamedTextColor.GRAY))
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

    private void syncClanTeams(Scoreboard scoreboard) {
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
            Component prefix = rankTag(profile)
                    .append(clan.map(SidebarService::clanTag).orElse(Component.empty()));
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
            Component title = Component.text("MYSTERIOUS SMP X", ORANGE, TextDecoration.BOLD);
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
        return Component.text("[" + clan.name() + "] ", clanColor(clan), TextDecoration.BOLD);
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
