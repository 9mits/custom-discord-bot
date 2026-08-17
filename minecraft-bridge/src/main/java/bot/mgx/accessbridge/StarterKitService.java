package bot.mgx.accessbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** One starter kit per player, first join only. */
final class StarterKitService implements Listener {
    static final long STARTER_CASH = 2_500L;
    private static final Type SET_TYPE = new TypeToken<Set<String>>() { }.getType();

    private final MGXAccessBridge plugin;
    private final EconomyStore money;
    private final Path path;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Set<String> claimed = new LinkedHashSet<>();

    StarterKitService(MGXAccessBridge plugin, EconomyStore money, Path path) {
        this.plugin = plugin;
        this.money = money;
        this.path = path;
        load();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String id = player.getUniqueId().toString();
        if (claimed.contains(id) || player.hasPlayedBefore()) {
            return;
        }
        claimed.add(id);
        save();
        player.getInventory().addItem(
                new ItemStack(Material.OAK_LOG, 30),
                new ItemStack(Material.STONE_AXE, 1),
                new ItemStack(Material.STONE_PICKAXE, 1),
                new ItemStack(Material.STONE_SWORD, 1),
                new ItemStack(Material.BREAD, 20)
        );
        money.deposit(player.getUniqueId(), STARTER_CASH);
        player.sendMessage(Component.text(
                "Welcome. You received a starter kit and "
                        + EconomyFormat.dollars(STARTER_CASH) + ".",
                NamedTextColor.GOLD
        ));
        player.sendMessage(Component.text(
                "Use /shop to buy something.",
                NamedTextColor.GOLD
        ));
    }

    private void load() {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Set<String> loaded = gson.fromJson(reader, SET_TYPE);
            if (loaded != null) {
                claimed.addAll(loaded);
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not read starter kit claims: " + exception.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                gson.toJson(claimed, writer);
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save starter kit claims: " + exception.getMessage());
        }
    }
}
