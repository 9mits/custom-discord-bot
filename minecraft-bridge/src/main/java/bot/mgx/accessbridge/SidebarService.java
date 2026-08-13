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
import java.util.UUID;

final class SidebarService {
    private static final int MAX_LINES = 15;
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
            refresh(player);
        }
    }

    void refresh(Player player) {
        PlayerBoard board = boards.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerBoard(player));
        board.update(lines(player));
        if (player.getScoreboard() != board.scoreboard) {
            player.setScoreboard(board.scoreboard);
        }
    }

    private List<Component> lines(Player player) {
        PlayerProfile profile = perks.profile(player.getUniqueId());
        ArrayList<Component> lines = new ArrayList<>();
        lines.add(Component.empty());
        lines.add(valueLine("✦", "Player", player.getName()));
        lines.add(valueLine("⚡", "Level", String.valueOf(profile.level())));
        lines.add(valueLine("♥", "Bonus Hearts", "+" + profile.extraHearts()));
        if (profile.elite()) {
            lines.add(valueLine("✹", "Power", "+5% damage"));
        }
        clans.clanOf(player.getUniqueId()).ifPresent(clan ->
                lines.add(valueLine("◆", "Clan", clan.name()))
        );
        lines.add(Component.empty());
        lines.add(valueLine("⚔", "Kills", String.valueOf(player.getStatistic(Statistic.PLAYER_KILLS))));
        lines.add(valueLine("☠", "Deaths", String.valueOf(player.getStatistic(Statistic.DEATHS))));
        lines.add(valueLine(
                "◉",
                "Online",
                plugin.getServer().getOnlinePlayers().size() + "/" + plugin.getServer().getMaxPlayers()
        ));
        lines.add(valueLine("↔", "Ping", player.getPing() + "ms"));
        lines.add(Component.empty());
        lines.add(Component.text(footer, LIGHT_ORANGE));
        return lines;
    }

    private static Component valueLine(String icon, String label, String value) {
        return Component.text(icon + " ", ORANGE)
                .append(Component.text(label + " ", NamedTextColor.GRAY))
                .append(Component.text(value, NamedTextColor.WHITE));
    }

    private final class PlayerBoard {
        private final Scoreboard scoreboard;
        private final Objective objective;
        private final List<String> entries = new ArrayList<>();
        private final List<Team> teams = new ArrayList<>();

        PlayerBoard(Player player) {
            Scoreboard created = plugin.getServer().getScoreboardManager().getNewScoreboard();
            Component title = Component.text("MYSTERIOUS", ORANGE, TextDecoration.BOLD)
                    .append(Component.text(" SMP X", LIGHT_ORANGE, TextDecoration.BOLD));
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
