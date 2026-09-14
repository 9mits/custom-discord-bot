package bot.mgx.accessbridge;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
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

/**
 * The Season Pass, drawn in the two places each part reads best.
 *
 * <p>The overview is a dialog: season, tier and XP at a glance, then a short list of the
 * tiers coming up, one clean row each — the headline reward, its icon, and a quiet note
 * of anything else it pays. Every row says it can be clicked.
 *
 * <p>Clicking a tier opens that tier as a small chest: nothing but its rewards, as the
 * real items, so hovering shows exactly what arrives — enchantments, descriptions,
 * stack sizes — with a rarity and whether it is unlocked. Arrows step to the tiers
 * either side, and Back returns to the overview. One job per screen.
 */
final class SeasonPassMenu implements Listener {
    static final int UP_NEXT = 4;
    static final int TIERS_PER_LIST_PAGE = 10;
    private static final int WIDTH = 400;
    private static final int TIER_SIZE = 27;
    private static final int STATUS_SLOT = 4;
    private static final int PREVIOUS_SLOT = 18;
    private static final int BACK_SLOT = 22;
    private static final int NEXT_SLOT = 26;
    private static final TextColor CLAIMED = TextColor.color(0x55FF55);
    private static final TextColor NEXT = TextColor.color(0xFFD35A);
    private static final TextColor QUIET = TextColor.color(0x9AA3B0);

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

    private static final class TierHolder implements InventoryHolder {
        private Inventory inventory;
        private int tier;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // ------------------------------------------------------------------ ranking and naming

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

    private List<SeasonPassRules.Grant> ranked(int tier) {
        List<SeasonPassRules.Grant> grants = new ArrayList<>(pass.grants(tier));
        grants.sort(Comparator.comparingInt(grant -> rarity(grant, rewardOf(grant)).order));
        return grants;
    }

    /** The dialog icon for a grant: its own texture where it has one. */
    String sprite(SeasonPassRules.Grant grant) {
        return switch (grant.kind()) {
            case "hearts" -> "item/red_dye";
            case "shards" -> "mgx:item/shard";
            case "keys" -> "mgx:item/mystery_key";
            case "season_gear" -> pass.seasonGearModel(grant).map(SeasonPassMenu::textureOf).orElse("mgx:item/shard");
            case "season_cosmetic" -> pass.seasonCosmeticDefinition(grant)
                    .map(definition -> textureOf(definition.modelKey())).orElse("mgx:item/shard");
            case "cosmetic" -> CosmeticCatalog.find(grant.id())
                    .map(definition -> textureOf(definition.modelKey())).orElse("item/bundle");
            default -> rewardOf(grant).map(SeasonPassMenu::spriteOf).orElse("item/bundle");
        };
    }

    /** {@code mgx:cosmetic/x} is drawn from {@code mgx:item/cosmetic/x}. */
    static String textureOf(String modelKey) {
        int colon = modelKey.indexOf(':');
        return colon < 0 ? "item/bundle" : modelKey.substring(0, colon) + ":item/" + modelKey.substring(colon + 1);
    }

    private static String spriteOf(CrateCatalog.Reward reward) {
        if (reward.modelKey().startsWith("mgx:")) return textureOf(reward.modelKey());
        return switch (reward.materialName()) {
            case "DIAMOND" -> "item/diamond";
            case "GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE" -> "item/golden_apple";
            case "EXPERIENCE_BOTTLE" -> "item/experience_bottle";
            case "TOTEM_OF_UNDYING" -> "item/totem_of_undying";
            case "NETHERITE_INGOT" -> "item/netherite_ingot";
            case "NETHERITE_SCRAP" -> "item/netherite_scrap";
            case "ANCIENT_DEBRIS" -> "block/ancient_debris_side";
            case "MACE" -> "item/mace";
            case "HEART_OF_THE_SEA" -> "item/heart_of_the_sea";
            case "EMERALD" -> "item/emerald";
            default -> "item/bundle";
        };
    }

    private Optional<CrateCatalog.Reward> rewardOf(SeasonPassRules.Grant grant) {
        return grant.kind().equals("reward") ? CrateCatalog.find(grant.id()) : Optional.empty();
    }

    // ------------------------------------------------------------------ overview dialog

    void openHome(Player player) {
        int maximum = pass.maximumTier();
        long xp = pass.xp(player.getUniqueId());
        int tier = SeasonPassRules.tier(xp, pass.xpPerTier(), maximum);
        long into = SeasonPassRules.xpIntoTier(xp, pass.xpPerTier(), maximum);
        TextColor accent = SeasonCosmetics.theme(pass.season())
                .map(theme -> TextColor.color(theme.primary())).orElse(MenuText.ORANGE);
        String themeName = SeasonCosmetics.theme(pass.season()).map(SeasonCosmetics.Theme::name).orElse("");

        List<DialogBody> body = new ArrayList<>();
        body.add(line(Component.text("Tier " + tier, accent, TextDecoration.BOLD)
                .append(Component.text("  of " + maximum, QUIET))));
        body.add(line(progressBar(into, pass.xpPerTier(), tier >= maximum, accent)));
        body.add(line(Component.text("Ends " + pass.endsIn(), QUIET)
                .append(Component.text("   ·   ", QUIET))
                .append(Component.text("Season Hearts " + pass.hearts(player.getUniqueId()) + " / " + pass.heartCap(), QUIET))));
        body.add(DialogBody.plainMessage(Component.empty(), WIDTH));
        body.add(line(Component.text(tier >= maximum ? "COMPLETE" : "UP NEXT", NamedTextColor.WHITE, TextDecoration.BOLD)
                .append(Component.text("   Click a tier to see its rewards up close.", QUIET))));

        List<ActionButton> buttons = new ArrayList<>();
        int first = Math.min(maximum, tier + 1);
        for (int next = first; next <= Math.min(maximum, first + UP_NEXT - 1); next++) {
            buttons.add(tierRow(player, next, tier, xp));
        }
        buttons.add(Screens.row("item/bundle", Component.text("All " + maximum + " Tiers", NamedTextColor.WHITE)
                .append(Component.text("   Browse every reward in the pass", QUIET)),
                "Click to browse every tier.", viewer -> openAllTiers(viewer, SeasonPassMenu.listPageOf(first))));
        buttons.add(Screens.row("item/writable_book", Component.text("Quests", NamedTextColor.WHITE)
                .append(Component.text("   Your progress on every quest", QUIET)),
                "Click to see your quests.", viewer -> pass.openQuests(viewer, this::openHome)));
        buttons.add(Screens.row("item/gold_ingot", Component.text("Season Top", NamedTextColor.WHITE)
                .append(Component.text("   The highest Season XP", QUIET)),
                "Click to see the leaderboard.", pass::openTop));
        buttons.add(Screens.row("item/knowledge_book", Component.text("How It Works", NamedTextColor.WHITE),
                "Where XP comes from, hearts and prizes.", pass::openGuide));
        String title = "Season " + pass.season() + (themeName.isEmpty() ? "" : " · " + themeName);
        if (!pass.supportsDialogs(player)) {
            openTier(player, first);
            return;
        }
        Screens.show(player, title, body, buttons, 1, null);
    }

    static int listPageOf(int tier) {
        return Math.max(0, tier - 1) / TIERS_PER_LIST_PAGE;
    }

    /** Every tier, ten rows a page, each one a way into that tier's chest. */
    void openAllTiers(Player player, int page) {
        int maximum = pass.maximumTier();
        int pages = Math.max(1, (maximum + TIERS_PER_LIST_PAGE - 1) / TIERS_PER_LIST_PAGE);
        int current = Math.max(0, Math.min(pages - 1, page));
        long xp = pass.xp(player.getUniqueId());
        int tier = SeasonPassRules.tier(xp, pass.xpPerTier(), maximum);
        List<ActionButton> buttons = new ArrayList<>();
        int from = current * TIERS_PER_LIST_PAGE + 1;
        for (int row = from; row <= Math.min(maximum, from + TIERS_PER_LIST_PAGE - 1); row++) {
            buttons.add(tierRow(player, row, tier, xp));
        }
        if (current > 0) {
            buttons.add(Screens.row("item/arrow", Component.text("Tiers " + (from - TIERS_PER_LIST_PAGE) + "–" + (from - 1),
                    NamedTextColor.WHITE), "Previous page.", viewer -> openAllTiers(viewer, current - 1)));
        }
        if (current < pages - 1) {
            int nextFrom = from + TIERS_PER_LIST_PAGE;
            buttons.add(Screens.row("item/arrow", Component.text("Tiers " + nextFrom + "–"
                            + Math.min(maximum, nextFrom + TIERS_PER_LIST_PAGE - 1), NamedTextColor.WHITE),
                    "Next page.", viewer -> openAllTiers(viewer, current + 1)));
        }
        List<DialogBody> body = List.of(line(Component.text("Click any tier to see its rewards up close.", QUIET)));
        Screens.show(player, "All Tiers  " + (current + 1) + "/" + pages, body, buttons, 1, this::openHome);
    }

    private ActionButton tierRow(Player player, int number, int tier, long xp) {
        List<SeasonPassRules.Grant> grants = ranked(number);
        SeasonPassRules.Grant headline = grants.isEmpty() ? null : grants.get(0);
        boolean unlocked = number <= tier;
        boolean next = number == tier + 1;
        TextColor state = unlocked ? CLAIMED : next ? NEXT : NamedTextColor.WHITE;
        Component label = Component.text((unlocked ? "✔ " : "") + "Tier " + number, state, TextDecoration.BOLD);
        if (headline != null) {
            Rarity rarity = rarity(headline, rewardOf(headline));
            label = label.append(Component.text("   " + pass.describe(List.of(headline)),
                    TextColor.color(rarity.colour)).decoration(TextDecoration.BOLD, false));
            if (grants.size() > 1) {
                label = label.append(Component.text("  +" + (grants.size() - 1), QUIET)
                        .decoration(TextDecoration.BOLD, false));
            }
        }
        String hint = (unlocked ? "Unlocked. " : next
                ? String.format(Locale.ROOT, "Next up: %,d XP to go. ", (long) number * pass.xpPerTier() - xp)
                : "") + "Click to view " + (grants.size() == 1 ? "this reward." : "all " + grants.size() + " rewards.");
        return Screens.row(headline == null ? "item/bundle" : sprite(headline), label, hint,
                viewer -> openTier(viewer, number));
    }

    private static Component progressBar(long into, int perTier, boolean complete, TextColor accent) {
        int cells = 20;
        int filled = complete ? cells : (int) Math.min(cells, into * cells / Math.max(1, perTier));
        Component bar = Component.text("█".repeat(filled), accent)
                .append(Component.text("█".repeat(cells - filled), TextColor.color(0x3A3F4B)));
        return bar.append(Component.text(complete ? "   Pass complete"
                : String.format(Locale.ROOT, "   %,d / %,d XP", into, perTier), QUIET));
    }

    private static DialogBody line(Component component) {
        return DialogBody.plainMessage(MenuText.upright(component), WIDTH);
    }

    // ------------------------------------------------------------------ tier chest

    void openTier(Player player, int tierNumber) {
        TierHolder holder = new TierHolder();
        holder.tier = Math.max(1, Math.min(pass.maximumTier(), tierNumber));
        holder.inventory = Bukkit.createInventory(holder, TIER_SIZE,
                Component.text("Tier " + holder.tier + " Rewards", MenuText.ORANGE, TextDecoration.BOLD));
        drawTier(player, holder);
        MenuItems.show(plugin, player, holder.inventory);
    }

    private void drawTier(Player player, TierHolder holder) {
        Inventory inventory = holder.inventory;
        ItemStack frame = MenuItems.detailed(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < TIER_SIZE; slot++) inventory.setItem(slot, frame);
        long xp = pass.xp(player.getUniqueId());
        int tier = SeasonPassRules.tier(xp, pass.xpPerTier(), pass.maximumTier());
        int number = holder.tier;
        boolean unlocked = number <= tier;
        boolean next = number == tier + 1;
        inventory.setItem(STATUS_SLOT, MenuItems.detailed(
                unlocked ? Material.LIME_DYE : next ? Material.YELLOW_DYE : Material.GRAY_DYE,
                "Tier " + number, List.of(status(number, tier, xp))));

        List<SeasonPassRules.Grant> grants = ranked(number);
        int shown = Math.min(7, grants.size());
        int first = 9 + (9 - shown) / 2;
        for (int index = 0; index < shown; index++) {
            inventory.setItem(first + index, tile(grants.get(index), number, tier, xp));
        }
        if (number > 1) {
            inventory.setItem(PREVIOUS_SLOT, MenuItems.button(Material.ARROW, "Tier " + (number - 1)));
        }
        inventory.setItem(BACK_SLOT, MenuItems.button(Material.NETHER_STAR, "Back to Season Pass"));
        if (number < pass.maximumTier()) {
            inventory.setItem(NEXT_SLOT, MenuItems.button(Material.ARROW, "Tier " + (number + 1)));
        }
    }

    private Component status(int number, int tier, long xp) {
        if (number <= tier) return text("✔ Unlocked", CLAIMED, true);
        long needed = (long) number * pass.xpPerTier() - xp;
        return text(String.format(Locale.ROOT, number == tier + 1 ? "Next up  ·  %,d XP to go" : "Locked  ·  %,d XP to go",
                needed), number == tier + 1 ? NEXT : QUIET, true);
    }

    /** The real item a grant pays, with its rarity and state underneath. */
    private ItemStack tile(SeasonPassRules.Grant grant, int number, int tier, long xp) {
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
        lore.add(text(rarity.label, TextColor.color(rarity.colour), true));
        lore.add(status(number, tier, xp));
        meta.lore(lore.stream().map(component -> component.decoration(TextDecoration.ITALIC, false)).toList());
        if (meta.displayName() != null) meta.displayName(meta.displayName().decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack preview(SeasonPassRules.Grant grant, Optional<CrateCatalog.Reward> reward) {
        return switch (grant.kind()) {
            case "hearts" -> {
                ItemStack heart = MenuItems.detailed(Material.RED_DYE,
                        "+" + grant.amount() + " Season " + (grant.amount() == 1 ? "Heart" : "Hearts"), List.of(
                                text("Extra max health, applied instantly.", NamedTextColor.GRAY, false),
                                text("Lasts until this season ends.", NamedTextColor.GRAY, false)));
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

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof TierHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getInventory()) {
            return;
        }
        switch (event.getSlot()) {
            case PREVIOUS_SLOT -> step(player, holder, holder.tier - 1);
            case NEXT_SLOT -> step(player, holder, holder.tier + 1);
            case BACK_SLOT -> handOff(player, this::openHome);
            default -> { }
        }
    }

    private void step(Player player, TierHolder holder, int tier) {
        if (tier < 1 || tier > pass.maximumTier()) return;
        // A new chest rather than a redraw, because the title names the tier.
        player.playSound(player, Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1.1f);
        openTier(player, tier);
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
        if (event.getInventory().getHolder() instanceof TierHolder) event.setCancelled(true);
    }

    private static Component text(String text, TextColor colour, boolean bold) {
        Component component = Component.text(text, colour).decoration(TextDecoration.ITALIC, false);
        return bold ? component.decoration(TextDecoration.BOLD, true) : component;
    }
}
