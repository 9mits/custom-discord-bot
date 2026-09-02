package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Runtime control, announcements, and idempotent player rewards for clan battles. */
final class ClanBattleService implements Listener {
    /** Announced once each as the deadline passes them, newest clan battle only. */
    private static final long[] WARNING_MILLIS = {
            86_400_000L, 3_600_000L, 600_000L, 60_000L
    };
    private static final TextColor GOLD = TextColor.color(0xFFD35A);
    private static final TextColor SILVER = TextColor.color(0xC0D4E8);
    private static final TextColor BRONZE = TextColor.color(0xCD7F32);

    private final MGXAccessBridge plugin;
    private final ClanBattleStore store;
    private final ClanStore clans;
    private final CrateItems items;
    private final CosmeticStore cosmetics;
    private final LeaderboardService leaderboards;
    private final PlayerSettingsStore settings;
    /** Warnings already broadcast for the running battle, cleared when one starts. */
    private final java.util.Set<Long> warned = new java.util.HashSet<>();

    private final ServerMessages messages;

    /**
     * One server-wide clan-battle line, in whatever words the owner has chosen.
     *
     * <p>Respects the same per-player announcement toggle the literals did: somebody who
     * turned clan battles off does not start hearing about them because the text moved
     * into configuration.
     */
    private void announce(String key, String... placeholders) {
        if (messages.isSilenced(key)) {
            return;
        }
        PlayerBroadcast.broadcast(
                settings,
                PlayerSettingsStore.Setting.CLAN_BATTLE_ANNOUNCEMENTS,
                prefix().append(messages.render(key, placeholders))
        );
    }

    ClanBattleService(
            MGXAccessBridge plugin,
            ClanBattleStore store,
            ClanStore clans,
            CrateItems items,
            CosmeticStore cosmetics,
            LeaderboardService leaderboards,
            PlayerSettingsStore settings
    ) {
        this.plugin = plugin;
        this.messages = new ServerMessages(plugin.gameVariables());
        this.store = store;
        this.clans = clans;
        this.items = items;
        this.cosmetics = cosmetics;
        this.leaderboards = leaderboards;
        this.settings = settings;
    }

    ClanBattleStore.ActiveView startBattle(ClanBattleStore.Kind kind, long endsAt) {
        long now = System.currentTimeMillis();
        ClanBattleStore.ActiveView active = store.start(kind, now, endsAt, clans);
        warned.clear();
        leaderboards.publishNow();
        announce("messages.clanbattle.started",
                "battle", active.kind().displayName(),
                "objective", active.kind().objective());
        announce("messages.clanbattle.ends-in",
                "remaining", ClanBattleCountdown.remaining(active.endsAt() - now));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (settings.isEnabled(
                    player.getUniqueId(), PlayerSettingsStore.Setting.EVENT_SOUNDS
            )) {
                player.playSound(
                        player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.9f
                );
            }
        }
        return active;
    }

    ClanBattleStore.CompletedView endBattle() {
        ClanBattleStore.CompletedView completed = store.end(clans, System.currentTimeMillis());
        warned.clear();
        reconcileGalaxyRewards();
        for (Player player : Bukkit.getOnlinePlayers()) {
            deliverShardGrants(player, true);
        }
        leaderboards.publishNow();
        announceResults(completed);
        return completed;
    }

    void cancelBattle() {
        String name = store.active(clans).map(view -> view.kind().displayName())
                .orElse("Clan Battle");
        store.cancel();
        warned.clear();
        leaderboards.publishNow();
        announce("messages.clanbattle.cancelled", "battle", name);
    }

    String status() {
        ClanBattleStore.ActiveView active = store.active(clans).orElse(null);
        if (active == null) {
            return "No clan battle is running.";
        }
        String leader = active.standings().isEmpty()
                ? "No clan has scored yet."
                : "Leader: " + active.standings().getFirst().clanName() + " with "
                        + active.standings().getFirst().score() + " openings.";
        String left = ClanBattleCountdown.remaining(
                active.endsAt() - System.currentTimeMillis()
        );
        return active.kind().displayName() + " is live, ending in " + left + ". "
                + active.kind().objective() + " " + leader;
    }

    void recordCrateOpening(Player player) {
        try {
            store.recordCrate(player.getUniqueId(), System.currentTimeMillis(), clans);
        } catch (ArithmeticException | UncheckedIOException exception) {
            plugin.getLogger().warning("Could not record a Clan Battle crate opening for "
                    + player.getUniqueId() + ": " + exception.getMessage());
        }
    }

    void start() {
        reconcileGalaxyRewards();
        for (Player player : Bukkit.getOnlinePlayers()) {
            deliverShardGrants(player, false);
        }
        // A battle ends on its own deadline, so a restart or an empty server cannot
        // leave one running past the countdown players were shown.
        plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
                plugin, this::tick, 100L, 20L
        );
    }

    private void tick() {
        ClanBattleStore.ActiveView active = store.active(clans).orElse(null);
        if (active == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (active.expired(now)) {
            try {
                endBattle();
            } catch (IllegalArgumentException | UncheckedIOException exception) {
                plugin.getLogger().warning(
                        "Could not close the expired clan battle: " + exception.getMessage()
                );
            }
            return;
        }
        long left = active.endsAt() - now;
        for (long milestone : WARNING_MILLIS) {
            if (left <= milestone && warned.add(milestone)) {
                announce("messages.clanbattle.warning",
                        "battle", active.kind().displayName(),
                        "remaining", ClanBattleCountdown.remaining(milestone));
                break;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()
                    && !VerificationLobbyService.isLobbyWorld(event.getPlayer().getWorld())) {
                deliverShardGrants(event.getPlayer(), true);
            }
        }, 60L);
    }

    private void reconcileGalaxyRewards() {
        for (ClanBattleStore.CompletedView battle : store.completed()) {
            for (ClanBattleStore.Standing winner : battle.winners()) {
                if (winner.rank() != 1) {
                    continue;
                }
                for (UUID member : winner.members()) {
                    UUID serial = UUID.nameUUIDFromBytes(("clan-battle-aura:"
                            + battle.id() + ":" + member).getBytes(StandardCharsets.UTF_8));
                    try {
                        cosmetics.mint(member, ClanBattleStore.GALACTIC_CONQUEST_ID, serial);
                    } catch (IllegalArgumentException | UncheckedIOException exception) {
                        plugin.getLogger().warning("Could not grant Galactic Conquest to "
                                + member + ": " + exception.getMessage());
                    }
                }
            }
        }
    }

    private void deliverShardGrants(Player player, boolean notify) {
        for (ClanBattleStore.ShardGrant grant : store.shardGrants(player.getUniqueId())) {
            if (items.carriesShardGrant(player, grant.grantId())) {
                completeCarriedGrant(player, grant, notify);
                continue;
            }
            ItemStack marked = items.shard(grant.amount(), grant.grantId());
            if (!canFit(player, marked)) {
                if (notify) {
                    player.sendMessage(prefix().append(Component.text(
                            "Make inventory space to receive " + grant.amount()
                                    + " Shards from " + grant.source() + ".",
                            NamedTextColor.YELLOW
                    )));
                }
                continue;
            }
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(marked);
            if (!leftovers.isEmpty()) {
                items.removeShardGrant(player, grant.grantId());
                continue;
            }
            completeCarriedGrant(player, grant, notify);
        }
    }

    private void completeCarriedGrant(
            Player player, ClanBattleStore.ShardGrant grant, boolean notify
    ) {
        try {
            if (!store.completeShardGrant(player.getUniqueId(), grant.grantId())) {
                return;
            }
        } catch (UncheckedIOException exception) {
            plugin.getLogger().warning("Could not finish Shard grant " + grant.grantId()
                    + ": " + exception.getMessage());
            return;
        }
        items.finishShardGrant(player, grant.grantId());
        if (notify) {
            player.sendMessage(prefix()
                    .append(Component.text("You received ", NamedTextColor.WHITE))
                    .append(Component.text(grant.amount() + " Shards", NamedTextColor.AQUA,
                            TextDecoration.BOLD))
                    .append(Component.text(" from " + grant.source() + ".", NamedTextColor.WHITE)));
            if (settings.isEnabled(
                    player.getUniqueId(), PlayerSettingsStore.Setting.EVENT_SOUNDS
            )) {
                player.playSound(
                        player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f
                );
            }
        }
    }

    private static boolean canFit(Player player, ItemStack item) {
        int remaining = item.getAmount();
        for (ItemStack existing : player.getInventory().getStorageContents()) {
            if (existing == null || existing.getType().isAir()) {
                remaining -= item.getMaxStackSize();
            } else if (existing.isSimilar(item)) {
                remaining -= Math.max(0, existing.getMaxStackSize() - existing.getAmount());
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }

    private void announceResults(ClanBattleStore.CompletedView completed) {
        announce("messages.clanbattle.ended", "battle", completed.kind().displayName());
        if (completed.winners().isEmpty()) {
            announce("messages.clanbattle.no-winner");
            return;
        }
        for (ClanBattleStore.Standing winner : completed.winners()) {
            int shards = switch (winner.rank()) {
                case 1 -> 10;
                case 2 -> 5;
                default -> 3;
            };
            TextColor colour = switch (winner.rank()) {
                case 1 -> GOLD;
                case 2 -> SILVER;
                default -> BRONZE;
            };
            String extra = winner.rank() == 1 ? " + Galactic Conquest aura" : "";
            PlayerBroadcast.broadcast(settings,
                PlayerSettingsStore.Setting.CLAN_BATTLE_ANNOUNCEMENTS, prefix()
                    .append(Component.text("#" + winner.rank() + " [" + winner.clanName() + "]",
                            colour, TextDecoration.BOLD))
                    .append(Component.text(" — " + winner.score() + " openings — "
                            + shards + " Shards per member" + extra, NamedTextColor.WHITE)));
        }
    }

    private static Component prefix() {
        return Component.text("CLAN BATTLE » ", GOLD, TextDecoration.BOLD);
    }
}
