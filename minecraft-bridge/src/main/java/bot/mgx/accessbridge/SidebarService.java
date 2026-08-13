package bot.mgx.accessbridge;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

final class SidebarService {
    private static final int MAX_LINES = 11;
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private static final TextColor LIGHT_ORANGE = TextColor.color(0xFFC266);
    private static final ChatColor[] ENTRY_COLOURS = {
            ChatColor.BLACK, ChatColor.DARK_BLUE, ChatColor.DARK_GREEN, ChatColor.DARK_AQUA,
            ChatColor.DARK_RED, ChatColor.DARK_PURPLE, ChatColor.GOLD, ChatColor.GRAY,
            ChatColor.DARK_GRAY, ChatColor.BLUE, ChatColor.GREEN, ChatColor.AQUA,
            ChatColor.RED, ChatColor.LIGHT_PURPLE, ChatColor.WHITE
    };

    private final MGXAccessBridge plugin;
    private final PlayerPerkService perks;
    private final ClanStore clans;
    private final String footer;
    private final int updateTicks;
    private final Map<UUID, PlayerBoard> boards = new HashMap<>();
    private int taskId = -1;

    SidebarService(
            MGXAccessBridge plugin,
            PlayerPerkService perks,
            ClanStore clans,
            String footer,
            int updateTicks
    ) {
        this.plugin = plugin;
        this.perks = perks;
        this.clans = clans;
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
        });
        boards.clear();
    }

    void refreshAll() {
        boards.keySet().removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            updateTabName(player);
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
        lines.add(valueLine("LVL", String.valueOf(profile.level())));
        lines.add(valueLine("HEARTS", "+" + profile.extraHearts()));
        if (profile.elite()) {
            lines.add(valueLine("POWER", "+5% DMG"));
        }
        clans.clanOf(player.getUniqueId()).ifPresent(clan ->
                lines.add(valueLine("CLAN", "[" + clan.name() + "]"))
        );
        lines.add(Component.empty());
        lines.add(valueLine("KILLS", String.valueOf(player.getStatistic(Statistic.PLAYER_KILLS))));
        lines.add(valueLine("DEATHS", String.valueOf(player.getStatistic(Statistic.DEATHS))));
        lines.add(valueLine("PING", player.getPing() + "ms"));
        lines.add(Component.empty());
        lines.add(Component.text(footer, LIGHT_ORANGE));
        return lines;
    }

    private static Component valueLine(String label, String value) {
        return Component.text("» ", ORANGE, TextDecoration.BOLD)
                .append(Component.text(label + " ", LIGHT_ORANGE, TextDecoration.BOLD))
                .append(Component.text(value, NamedTextColor.WHITE));
    }

    private void updateTabName(Player player) {
        Component name = Component.text(player.getName(), NamedTextColor.WHITE);
        Component rendered = clans.clanOf(player.getUniqueId())
                .<Component>map(clan -> Component.text("[" + clan.name() + "] ", ORANGE, TextDecoration.BOLD)
                        .append(name))
                .orElse(name);
        player.playerListName(rendered);
    }

    private void syncClanTeams(Scoreboard scoreboard) {
        Map<String, ClanStore.ClanView> expected = new LinkedHashMap<>();
        Map<String, Set<String>> entries = new LinkedHashMap<>();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            clans.clanOf(online.getUniqueId()).ifPresent(clan -> {
                String teamName = "mgxc_" + clan.id().toString().replace("-", "").substring(0, 11);
                expected.put(teamName, clan);
                entries.computeIfAbsent(teamName, ignored -> new LinkedHashSet<>()).add(online.getName());
            });
        }
        for (Team team : new ArrayList<>(scoreboard.getTeams())) {
            if (team.getName().startsWith("mgxc_") && !expected.containsKey(team.getName())) {
                team.unregister();
            }
        }
        expected.forEach((teamName, clan) -> {
            Team team = scoreboard.getTeam(teamName);
            if (team == null) {
                team = scoreboard.registerNewTeam(teamName);
            }
            team.prefix(Component.text("[" + clan.name() + "] ", ORANGE, TextDecoration.BOLD));
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
            Component title = Component.text("MYSTERIOUS", ORANGE, TextDecoration.BOLD)
                    .append(Component.text(" X", LIGHT_ORANGE, TextDecoration.BOLD));
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
}
