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
    private static final int MAX_LINES = 13;
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
        lines.add(sectionLine("PROFILE"));
        lines.add(valueLine("PLAYER", player.getName(), NamedTextColor.WHITE));
        lines.add(valueLine("LEVEL", String.valueOf(profile.level()), NamedTextColor.AQUA));
        lines.add(valueLine("HEARTS", "+" + profile.extraHearts(), NamedTextColor.RED));
        if (profile.elite()) {
            lines.add(valueLine("POWER", "+5% damage", NamedTextColor.LIGHT_PURPLE));
        }
        clans.clanOf(player.getUniqueId()).ifPresent(clan ->
                lines.add(valueLine("CLAN", "[" + clan.name() + "]", clanColor(clan)))
        );
        lines.add(borderLine());
        lines.add(sectionLine("STATS", false));
        lines.add(valueLine(
                "KILLS",
                String.valueOf(player.getStatistic(Statistic.PLAYER_KILLS)),
                NamedTextColor.RED
        ));
        lines.add(valueLine(
                "DEATHS",
                String.valueOf(player.getStatistic(Statistic.DEATHS)),
                NamedTextColor.GRAY
        ));
        lines.add(valueLine("PING", player.getPing() + "ms", pingColor(player.getPing())));
        lines.add(borderLine());
        lines.add(footerLine());
        return lines;
    }

    private static Component valueLine(String label, String value, TextColor valueColor) {
        return Component.text("│ ", NamedTextColor.DARK_GRAY)
                .append(Component.text(label, NamedTextColor.GRAY))
                .append(Component.text("  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(value, valueColor, TextDecoration.BOLD));
    }

    private static Component sectionLine(String label) {
        return sectionLine(label, true);
    }

    private static Component sectionLine(String label, boolean first) {
        return Component.text(first ? "╭─ " : "├─ ", ORANGE)
                .append(Component.text(label, GOLD, TextDecoration.BOLD))
                .append(Component.text(" ───", NamedTextColor.DARK_GRAY));
    }

    private static Component borderLine() {
        return Component.text("│", NamedTextColor.DARK_GRAY);
    }

    private Component footerLine() {
        return Component.text("╰─ ", ORANGE)
                .append(Component.text(footer, GOLD, TextDecoration.BOLD));
    }

    private void updateTabName(Player player) {
        PlayerProfile profile = perks.profile(player.getUniqueId());
        ClientPlatform platform = clientPlatform(player);
        Component rendered = Component.empty();
        Optional<ClanStore.ClanView> clan = clans.clanOf(player.getUniqueId());
        if (clan.isPresent()) {
            rendered = rendered.append(clanTag(clan.get()));
        }
        rendered = rendered
                .append(Component.text(player.getName(), NamedTextColor.WHITE))
                .append(Component.text("  │  ", NamedTextColor.DARK_GRAY))
                .append(Component.text("LVL " + profile.level(), NamedTextColor.AQUA))
                .append(Component.text("  •  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(platform.edition(), editionColor(platform)))
                .append(Component.text("/" + platform.device(), NamedTextColor.GRAY))
                .append(Component.text("  •  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(player.getPing() + "ms", pingColor(player.getPing())));
        player.playerListName(rendered);
    }

    private void updateTabHeaderAndFooter(Player player) {
        int online = plugin.getServer().getOnlinePlayers().size();
        Component header = Component.empty()
                .append(Component.text("MYSTERIOUS", ORANGE, TextDecoration.BOLD))
                .append(Component.text(" SMP X", GOLD, TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.text("Survival  •  Progression  •  Clans", NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text(
                        online + "/" + plugin.getServer().getMaxPlayers() + " players online",
                        NamedTextColor.DARK_GRAY
                ));
        Component footerComponent = Component.empty()
                .append(Component.text("Use /guide for server help", NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text("discord.gg/mgx", ORANGE, TextDecoration.BOLD));
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
            team.prefix(clanTag(clan));
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
            Component title = Component.text("MYSTERIOUS", ORANGE, TextDecoration.BOLD)
                    .append(Component.text(" SMP X", NamedTextColor.WHITE, TextDecoration.BOLD));
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
