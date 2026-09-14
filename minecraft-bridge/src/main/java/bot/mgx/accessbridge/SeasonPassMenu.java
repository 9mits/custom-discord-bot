package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

import static bot.mgx.accessbridge.MenuItems.ORANGE;

/**
 * The Season Pass track, as a chest.
 *
 * <p>Laid out the way a battle pass reads in the games players already know: the season
 * and XP bar across the top, a numbered strip of tiers, and under each tier the rewards
 * it pays as the real items. Hovering a tile shows exactly what arrives — the Season
 * Scythe's enchantments, a cosmetic's description, how many Shards — with its rarity and
 * whether it is unlocked, next up, or still locked. Seven tiers a page, opening on the
 * page with the player's next reward.
 *
 * <p>What is read rather than looked at stays in dialogs: the quest list with progress,
 * the Season Top leaderboard and the rules. The bottom row opens each of them, and each
 * of them comes back here.
 */
final class SeasonPassMenu implements Listener {
    static final int TIERS_PER_PAGE = 7;
    private static final int SIZE = 54;
    private static final int FIRST_TIER_COLUMN = 1;
    private static final int REWARD_ROWS = 3;
    private static final int PREVIOUS_SLOT = 45;
    private static final int QUESTS_SLOT = 47;
    private static final int TOP_SLOT = 48;
    private static final int PAGE_SLOT = 49;
    private static final int GUIDE_SLOT = 50;
    private static final int NEXT_SLOT = 53;

    private static final TextColor CLAIMED = TextColor.color(0x55FF55);
    private static final TextColor NEXT = TextColor.color(0xFFD35A);
    private static final TextColor LOCKED = TextColor.color(0x9AA3B0);

    /** Rarity names and colours, by what a reward is rather than what a crate rolls. */
    enum Rarity {
        EXCLUSIVE("SEASON EXCLUSIVE", 0xFF55FF, 0),
        LEGENDARY("LEGENDARY", 0xFFAA00, 1),
        EPIC("EPIC", 0xB56CFF, 2),
        RARE("RARE", 0x55C8FF, 3),
        UNCOMMON("UNCOMMON", 0x62E06A, 4),
        COMMON("COMMON", 0xC6CFDA, 5);

        final String label;
        final int colour;
        final int order;

        Rarity(String label, int colour, int order) {
            this.label = label;
            this.colour = colour;
            this.order = order;
        }
    }

    private final MGXAccessBridge plugin;
    private final SeasonPassService pass;
    private final CrateItems items;
    private final CosmeticItems cosmeticItems;

    SeasonPassMenu(MGXAccessBridge plugin, SeasonPassService pass, CrateItems items, CosmeticItems cosmeticItems) {
        this.plugin = plugin;
        this.pass = pass;
        this.items = items;
        this.cosmeticItems = cosmeticItems;
    }

    private static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private int page;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** The page holding a tier, counting from zero. */
    static int pageOf(int tier) {
        return Math.max(0, tier - 1) / TIERS_PER_PAGE;
    }

    static int pageCount(int maximumTier) {
        return Math.max(1, (maximumTier + TIERS_PER_PAGE - 1) / TIERS_PER_PAGE);
    }

    /** The page with the player's next reward, or the last page once the pass is done. */
    static int homePage(int tier, int maximumTier) {
        return pageOf(Math.min(maximumTier, tier + 1));
    }

    /** How a reward is ranked on its tier: what the player will care about most comes first. */
    static Rarity rarity(SeasonPassRules.Grant grant, Optional<CrateCatalog.Reward> reward) {
        return switch (grant.kind()) {
            case "season_cosmetic", "season_gear" -> Rarity.EXCLUSIVE;
            case "hearts" -> Rarity.LEGENDARY;
            case "shards" -> grant.amount() >= 3 ? Rarity.LEGENDARY : Rarity.EPIC;
            case "cosmetic" -> Rarity.EPIC;
            case "keys" -> Rarity.COMMON;
            default -> reward.map(found -> found.weight() < 200 ? Rarity.LEGENDARY
                    : found.weight() < 1_000 ? Rarity.EPIC
                    : found.weight() < 3_000 ? Rarity.RARE
                    : found.weight() < 7_000 ? Rarity.UNCOMMON : Rarity.COMMON).orElse(Rarity.COMMON);
        };
    }

    void open(Player player) {
        Holder holder = new Holder();
        holder.inventory = Bukkit.createInventory(holder, SIZE,
                Component.text("Season " + pass.season() + " Pass", ORANGE, TextDecoration.BOLD));
        int tier = pass.tier(player.getUniqueId());
        holder.page = homePage(tier, pass.maximumTier());
        draw(player, holder);
        MenuItems.show(plugin, player, holder.inventory);
    }

    private void draw(Player player, Holder holder) {
        Inventory inventory = holder.inventory;
        int maximum = pass.maximumTier();
        int pages = pageCount(maximum);
        holder.page = Math.max(0, Math.min(pages - 1, holder.page));
        long xp = pass.xp(player.getUniqueId());
        int tier = SeasonPassRules.tier(xp, pass.xpPerTier(), maximum);
        long into = SeasonPassRules.xpIntoTier(xp, pass.xpPerTier(), maximum);

        ItemStack frame = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 0; slot < SIZE; slot++) inventory.setItem(slot, frame);

        inventory.setItem(0, MenuItems.detailed(Material.NETHER_STAR, "Season " + pass.season() + " Pass", List.of(
                line("Tier " + tier + " of " + maximum, NamedTextColor.WHITE),
                line(String.format(Locale.ROOT, "%,d Season XP", xp), NamedTextColor.GRAY),
                line("Season ends " + pass.endsIn(), NamedTextColor.GRAY),
                Component.empty(),
                line("Every tier pays the moment you reach it.", LOCKED))));
        drawXpBar(inventory, tier, into, maximum);
        inventory.setItem(8, MenuItems.detailed(Material.RED_DYE, "Season Hearts", List.of(
                line(pass.hearts(player.getUniqueId()) + " / " + pass.heartCap() + " extra hearts", NamedTextColor.WHITE),
                line("Expire when the season ends " + pass.endsIn(), NamedTextColor.GRAY))));

        int firstTier = holder.page * TIERS_PER_PAGE + 1;
        for (int column = 0; column < TIERS_PER_PAGE; column++) {
            int tierNumber = firstTier + column;
            int slotColumn = FIRST_TIER_COLUMN + column;
            if (tierNumber > maximum) continue;
            inventory.setItem(9 + slotColumn, marker(tierNumber, tier, xp));
            List<SeasonPassRules.Grant> grants = new ArrayList<>(pass.grants(tierNumber));
            grants.sort(Comparator.comparingInt(grant -> rarity(grant, rewardOf(grant)).order));
            for (int row = 0; row < REWARD_ROWS; row++) {
                int slot = (2 + row) * 9 + slotColumn;
                if (row >= grants.size()) {
                    inventory.setItem(slot, pane(Material.GRAY_STAINED_GLASS_PANE));
                } else if (row == REWARD_ROWS - 1 && grants.size() > REWARD_ROWS) {
                    inventory.setItem(slot, bundle(grants.subList(row, grants.size()), tierNumber, tier, xp));
                } else {
                    inventory.setItem(slot, tile(grants.get(row), tierNumber, tier, xp));
                }
            }
        }

        if (holder.page > 0) {
            inventory.setItem(PREVIOUS_SLOT, MenuItems.button(Material.ARROW, "Previous Tiers",
                    "Tiers " + ((holder.page - 1) * TIERS_PER_PAGE + 1) + "–" + (holder.page * TIERS_PER_PAGE)));
        }
        if (holder.page < pages - 1) {
            int from = (holder.page + 1) * TIERS_PER_PAGE + 1;
            inventory.setItem(NEXT_SLOT, MenuItems.button(Material.ARROW, "Next Tiers",
                    "Tiers " + from + "–" + Math.min(maximum, from + TIERS_PER_PAGE - 1)));
        }
        inventory.setItem(QUESTS_SLOT, MenuItems.button(Material.WRITABLE_BOOK, "Quests",
                "Today's and this week's quests,", "with your progress on each."));
        inventory.setItem(TOP_SLOT, MenuItems.button(Material.GOLD_INGOT, "Season Top",
                "The highest Season XP right now."));
        inventory.setItem(PAGE_SLOT, MenuItems.button(Material.COMPASS,
                "Page " + (holder.page + 1) + " / " + pages,
                "Tiers " + firstTier + "–" + Math.min(maximum, firstTier + TIERS_PER_PAGE - 1),
                "Click to jump to your next reward."));
        inventory.setItem(GUIDE_SLOT, MenuItems.button(Material.KNOWLEDGE_BOOK, "How The Pass Works",
                "Where Season XP comes from,", "hearts, exclusives and prizes."));
    }

    /** Seven panes across the top that fill as the current tier's XP does. */
    private void drawXpBar(Inventory inventory, int tier, long into, int maximum) {
        boolean complete = tier >= maximum;
        int filled = complete ? 7 : (int) Math.min(7L, into * 7L / Math.max(1, pass.xpPerTier()));
        String label = complete ? "Pass complete"
                : String.format(Locale.ROOT, "%,d / %,d XP to Tier %d", into, pass.xpPerTier(), tier + 1);
        for (int index = 0; index < 7; index++) {
            boolean lit = index < filled;
            inventory.setItem(1 + index, MenuItems.detailed(
                    lit ? Material.LIME_STAINED_GLASS_PANE : Material.WHITE_STAINED_GLASS_PANE,
                    "Season XP", List.of(line(label, lit ? CLAIMED : NamedTextColor.GRAY))));
        }
    }

    /** The tier's number, shown as the stack count on a pane coloured by its state. */
    private ItemStack marker(int tierNumber, int tier, long xp) {
        boolean claimed = tierNumber <= tier;
        boolean next = tierNumber == tier + 1;
        Material material = claimed ? Material.LIME_STAINED_GLASS_PANE
                : next ? Material.ORANGE_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
        ItemStack marker = MenuItems.detailed(material, "Tier " + tierNumber, List.of(
                status(tierNumber, tier, xp)));
        marker.setAmount(Math.max(1, Math.min(99, tierNumber)));
        return marker;
    }

    private Component status(int tierNumber, int tier, long xp) {
        if (tierNumber <= tier) return line("✔ UNLOCKED", CLAIMED, true);
        long needed = (long) tierNumber * pass.xpPerTier() - xp;
        if (tierNumber == tier + 1) {
            return line(String.format(Locale.ROOT, "★ NEXT UP  •  %,d XP to go", needed), NEXT, true);
        }
        return line(String.format(Locale.ROOT, "LOCKED  •  %,d XP to go", needed), LOCKED, true);
    }

    /** The real item a grant pays, with its rarity and unlock state underneath. */
    private ItemStack tile(SeasonPassRules.Grant grant, int tierNumber, int tier, long xp) {
        Optional<CrateCatalog.Reward> reward = rewardOf(grant);
        ItemStack item;
        try {
            item = SentinelHub.quietly(() -> preview(grant, reward));
        } catch (RuntimeException failure) {
            item = new ItemStack(Material.BUNDLE);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        Rarity rarity = rarity(grant, reward);
        List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
        if (!lore.isEmpty()) lore.add(Component.empty());
        lore.add(line(rarity.label, TextColor.color(rarity.colour), true));
        lore.add(line("Tier " + tierNumber + " reward", NamedTextColor.GRAY));
        lore.add(status(tierNumber, tier, xp));
        meta.lore(lore.stream().map(component -> component.decoration(TextDecoration.ITALIC, false)).toList());
        if (meta.hasDisplayName() && meta.displayName() != null) {
            meta.displayName(meta.displayName().decoration(TextDecoration.ITALIC, false));
        }
        item.setItemMeta(meta);
        return item;
    }

    /** Several leftovers on one tile, so a big tier never pushes its best reward off screen. */
    private ItemStack bundle(List<SeasonPassRules.Grant> rest, int tierNumber, int tier, long xp) {
        List<Component> lore = new ArrayList<>();
        for (SeasonPassRules.Grant grant : rest) {
            Rarity rarity = rarity(grant, rewardOf(grant));
            lore.add(line("• " + pass.describe(List.of(grant)), TextColor.color(rarity.colour)));
        }
        lore.add(Component.empty());
        lore.add(line("Tier " + tierNumber + " reward", NamedTextColor.GRAY));
        lore.add(status(tierNumber, tier, xp));
        ItemStack bundle = MenuItems.detailed(Material.BUNDLE, "+" + rest.size() + " More Rewards", lore);
        bundle.setAmount(Math.min(99, rest.size()));
        return bundle;
    }

    private Optional<CrateCatalog.Reward> rewardOf(SeasonPassRules.Grant grant) {
        return grant.kind().equals("reward") ? CrateCatalog.find(grant.id()) : Optional.empty();
    }

    private ItemStack preview(SeasonPassRules.Grant grant, Optional<CrateCatalog.Reward> reward) {
        return switch (grant.kind()) {
            case "hearts" -> {
                ItemStack heart = MenuItems.detailed(Material.RED_DYE,
                        "+" + grant.amount() + " Season " + (grant.amount() == 1 ? "Heart" : "Hearts"), List.of(
                                line("Extra max health, applied instantly.", NamedTextColor.GRAY),
                                line("Lasts until this season ends.", NamedTextColor.GRAY)));
                heart.setAmount((int) Math.min(99, grant.amount()));
                yield heart;
            }
            case "shards" -> items.shard((int) Math.min(64, grant.amount()));
            case "keys" -> items.mysteryKey(Math.min(64, grant.amount()));
            case "season_gear" -> pass.seasonGearPreview(grant).orElseGet(() -> items.shard(3));
            case "season_cosmetic" -> pass.seasonCosmeticDefinition(grant)
                    .map(definition -> cosmeticItems.preview(definition, false))
                    .orElseGet(() -> items.shard(3));
            case "cosmetic" -> CosmeticCatalog.find(grant.id())
                    .map(definition -> cosmeticItems.preview(definition, false))
                    .orElseGet(() -> new ItemStack(Material.BARRIER));
            default -> reward.map(found -> {
                ItemStack built = found.cosmetic()
                        ? CosmeticCatalog.find(found.cosmeticId())
                                .map(definition -> cosmeticItems.preview(definition, false))
                                .orElseGet(() -> new ItemStack(Material.BARRIER))
                        : items.reward(found);
                built.setAmount((int) Math.min(built.getMaxStackSize(),
                        Math.max(1L, built.getAmount() * grant.amount())));
                return built;
            }).orElseGet(() -> new ItemStack(Material.BARRIER));
        };
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getInventory()) {
            return;
        }
        int slot = event.getSlot();
        int pages = pageCount(pass.maximumTier());
        switch (slot) {
            case PREVIOUS_SLOT -> turn(player, holder, holder.page - 1, pages);
            case NEXT_SLOT -> turn(player, holder, holder.page + 1, pages);
            case PAGE_SLOT -> turn(player, holder,
                    homePage(pass.tier(player.getUniqueId()), pass.maximumTier()), pages);
            case QUESTS_SLOT -> handOff(player, viewer -> pass.openQuests(viewer, this::open));
            case TOP_SLOT -> handOff(player, pass::openTop);
            case GUIDE_SLOT -> handOff(player, pass::openGuide);
            default -> {
                if (slot >= 18 && slot < 45 && event.getCurrentItem() != null
                        && event.getCurrentItem().getType() != Material.GRAY_STAINED_GLASS_PANE
                        && event.getCurrentItem().getType() != Material.BLACK_STAINED_GLASS_PANE) {
                    player.playSound(player, Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
                }
            }
        }
    }

    private void turn(Player player, Holder holder, int page, int pages) {
        if (page < 0 || page >= pages || page == holder.page) return;
        holder.page = page;
        draw(player, holder);
        player.playSound(player, Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1.1f);
    }

    /** Dialogs open a tick after the chest closes; Bedrock drops anything opened inside a click. */
    private void handOff(Player player, Consumer<Player> next) {
        player.closeInventory();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) next.accept(player);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    private static ItemStack pane(Material material) {
        return MenuItems.detailed(material, " ", List.of());
    }

    private static Component line(String text, TextColor colour) {
        return line(text, colour, false);
    }

    private static Component line(String text, TextColor colour, boolean bold) {
        Component component = Component.text(text, colour).decoration(TextDecoration.ITALIC, false);
        return bold ? component.decoration(TextDecoration.BOLD, true) : component;
    }
}
