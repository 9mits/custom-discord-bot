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
    private static final TextColor GOLD = TextColor.color(0xFFD35A);
    private static final TextColor SILVER = TextColor.color(0xC0D4E8);
    private static final TextColor BRONZE = TextColor.color(0xCD7F32);

    private final MGXAccessBridge plugin;
    private final ClanBattleStore store;
    private final ClanStore clans;
    private final CrateItems items;
    private final CosmeticStore cosmetics;
    private final LeaderboardService leaderboards;

    ClanBattleService(
            MGXAccessBridge plugin,
            ClanBattleStore store,
            ClanStore clans,
            CrateItems items,
            CosmeticStore cosmetics,
            LeaderboardService leaderboards
    ) {
        this.plugin = plugin;
        this.store = store;
        this.clans = clans;
        this.items = items;
        this.cosmetics = cosmetics;
        this.leaderboards = leaderboards;
    }

    ClanBattleStore.ActiveView startBattle(ClanBattleStore.Kind kind) {
        ClanBattleStore.ActiveView active = store.start(kind, System.currentTimeMillis(), clans);
        leaderboards.publishNow();
        Bukkit.broadcast(prefix()
                .append(Component.text(active.kind().displayName(), GOLD, TextDecoration.BOLD))
                .append(Component.text(" has begun! ", NamedTextColor.WHITE))
                .append(Component.text(active.kind().objective(), NamedTextColor.YELLOW)));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.9f);
        }
        return active;
    }

    ClanBattleStore.CompletedView endBattle() {
        ClanBattleStore.CompletedView completed = store.end(clans, System.currentTimeMillis());
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
        leaderboards.publishNow();
        Bukkit.broadcast(prefix().append(Component.text(
                name + " was cancelled. No rewards were awarded.", NamedTextColor.GRAY
        )));
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
        return active.kind().displayName() + " is live. " + active.kind().objective() + " " + leader;
    }

    void recordCrateOpening(Player player) {
        try {
            store.recordCrate(player.getUniqueId(), clans);
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
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
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

    private static void announceResults(ClanBattleStore.CompletedView completed) {
        Bukkit.broadcast(prefix().append(Component.text(
                completed.kind().displayName() + " has ended!", GOLD, TextDecoration.BOLD
        )));
        if (completed.winners().isEmpty()) {
            Bukkit.broadcast(prefix().append(Component.text(
                    "No clan recorded an opening, so no rewards were awarded.", NamedTextColor.GRAY
            )));
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
            Bukkit.broadcast(prefix()
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
