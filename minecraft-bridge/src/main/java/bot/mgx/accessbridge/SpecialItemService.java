package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Custom crate books and potions, including their live gameplay effects. */
final class SpecialItemService implements Listener {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private static final long POTION_DURATION_MILLIS = Duration.ofMinutes(15).toMillis();
    private static final Set<Material> FORTUNE_BLOCKS = EnumSet.of(
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.NETHER_GOLD_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.NETHER_QUARTZ_ORE, Material.ANCIENT_DEBRIS,
            Material.AMETHYST_CLUSTER, Material.GRAVEL, Material.GLOWSTONE,
            Material.MELON, Material.SEA_LANTERN, Material.SWEET_BERRY_BUSH,
            Material.NETHER_WART, Material.WHEAT, Material.CARROTS,
            Material.POTATOES, Material.BEETROOTS, Material.SHORT_GRASS,
            Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN,
            Material.WEEPING_VINES, Material.TWISTING_VINES
    );

    private final MGXAccessBridge plugin;
    private final NamespacedKey bookKey;
    private final NamespacedKey excavationKey;
    private final NamespacedKey potionKindKey;
    private final NamespacedKey potionLevelKey;
    private final NamespacedKey fortuneMultiplierKey;
    private final NamespacedKey fortuneUntilKey;
    private final NamespacedKey crateLuckKey;
    private final NamespacedKey crateLuckUntilKey;
    private final Set<UUID> excavating = new HashSet<>();

    SpecialItemService(MGXAccessBridge plugin) {
        this.plugin = plugin;
        bookKey = new NamespacedKey(plugin, "special_enchant_book");
        excavationKey = new NamespacedKey(plugin, "excavation_level");
        potionKindKey = new NamespacedKey(plugin, "custom_potion");
        potionLevelKey = new NamespacedKey(plugin, "custom_potion_level");
        fortuneMultiplierKey = new NamespacedKey(plugin, "fortune_multiplier");
        fortuneUntilKey = new NamespacedKey(plugin, "fortune_until");
        crateLuckKey = new NamespacedKey(plugin, "crate_luck_multiplier");
        crateLuckUntilKey = new NamespacedKey(plugin, "crate_luck_until");
    }

    Optional<ItemStack> create(CrateCatalog.Reward reward) {
        String id = reward.id();
        if (id.startsWith("potion_")) {
            return Optional.of(vanillaPotion(id));
        }
        if (id.startsWith("enchant_")) {
            return Optional.of(enchantmentBook(id));
        }
        if (id.startsWith("fortune_potion_")) {
            int level = romanLevel(id);
            double multiplier = switch (level) {
                case 1 -> 1.5d;
                case 2 -> 2d;
                case 3 -> 3d;
                case 4 -> 4d;
                default -> 5d;
            };
            return Optional.of(customPotion(
                    "fortune", level, multiplier, "Fortune Potion " + roman(level),
                    Color.fromRGB(40, 205, 95), "mgx:fortune_potion",
                    trimMultiplier(multiplier) + "x eligible ore drops for 15 minutes."
            ));
        }
        if (id.startsWith("crate_luck_")) {
            int multiplier = romanLevel(id);
            return Optional.of(customPotion(
                    "crate_luck", multiplier, multiplier,
                    "Crate Luck " + roman(multiplier),
                    Color.fromRGB(215, 70, 255), "mgx:crate_luck_potion",
                    multiplier + "x rare crate-reward weight for 15 minutes."
            ));
        }
        return Optional.empty();
    }

    int crateLuckMultiplier(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        Long until = data.get(crateLuckUntilKey, PersistentDataType.LONG);
        Integer multiplier = data.get(crateLuckKey, PersistentDataType.INTEGER);
        if (until == null || multiplier == null || until <= System.currentTimeMillis()) {
            data.remove(crateLuckUntilKey);
            data.remove(crateLuckKey);
            return 1;
        }
        return Math.max(1, Math.min(5, multiplier));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack base = event.getInventory().getItem(0);
        ItemStack book = event.getInventory().getItem(1);
        String enchant = specialBook(book).orElse(null);
        if (base == null || base.getType().isAir() || enchant == null) {
            return;
        }
        String[] parts = enchant.split(":", 2);
        if (parts.length != 2) {
            return;
        }
        int level;
        try {
            level = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ignored) {
            return;
        }
        ItemStack result = base.clone();
        result.setAmount(1);
        ItemMeta meta = result.getItemMeta();
        if (meta == null || !applyEnchantment(result, meta, parts[0], level)) {
            return;
        }
        result.setItemMeta(meta);
        event.getView().setRepairItemCountCost(1);
        event.getView().setRepairCost(Math.max(5, level * 4));
        event.setResult(result);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExcavation(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (excavating.contains(player.getUniqueId()) || !hasExcavation(player.getInventory().getItemInMainHand())) {
            return;
        }
        Block centre = event.getBlock();
        List<Block> plane = excavationPlane(player, centre);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !hasExcavation(player.getInventory().getItemInMainHand())) {
                return;
            }
            excavating.add(player.getUniqueId());
            try {
                for (Block block : plane) {
                    if (!block.getType().isAir()
                            && block.getType().getHardness() >= 0f
                            && Tag.MINEABLE_PICKAXE.isTagged(block.getType())) {
                        player.breakBlock(block);
                    }
                }
            } finally {
                excavating.remove(player.getUniqueId());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFortuneDrops(BlockDropItemEvent event) {
        Material broken = event.getBlockState().getType();
        if (!FORTUNE_BLOCKS.contains(broken) && !Tag.LEAVES.isTagged(broken)) {
            return;
        }
        double multiplier = fortuneMultiplier(event.getPlayer());
        if (multiplier <= 1d) {
            return;
        }
        for (Item original : List.copyOf(event.getItems())) {
            ItemStack stack = original.getItemStack();
            double extraExact = stack.getAmount() * (multiplier - 1d);
            int extra = (int) Math.floor(extraExact);
            if (ThreadLocalRandom.current().nextDouble() < extraExact - extra) {
                extra++;
            }
            while (extra > 0) {
                ItemStack copy = stack.clone();
                int amount = Math.min(copy.getMaxStackSize(), extra);
                copy.setAmount(amount);
                event.getBlock().getWorld().dropItemNaturally(
                        event.getBlock().getLocation().add(0.5d, 0.5d, 0.5d), copy
                );
                extra -= amount;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionConsumed(PlayerItemConsumeEvent event) {
        ItemMeta meta = event.getItem().getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer itemData = meta.getPersistentDataContainer();
        String kind = itemData.get(potionKindKey, PersistentDataType.STRING);
        Integer level = itemData.get(potionLevelKey, PersistentDataType.INTEGER);
        if (kind == null || level == null) {
            return;
        }
        Player player = event.getPlayer();
        long until = System.currentTimeMillis() + POTION_DURATION_MILLIS;
        if (kind.equals("fortune")) {
            double multiplier = switch (level) {
                case 1 -> 1.5d;
                case 2 -> 2d;
                case 3 -> 3d;
                case 4 -> 4d;
                default -> 5d;
            };
            player.getPersistentDataContainer().set(
                    fortuneMultiplierKey, PersistentDataType.DOUBLE, multiplier
            );
            player.getPersistentDataContainer().set(fortuneUntilKey, PersistentDataType.LONG, until);
            activated(player, trimMultiplier(multiplier) + "x Fortune");
        } else if (kind.equals("crate_luck")) {
            player.getPersistentDataContainer().set(
                    crateLuckKey, PersistentDataType.INTEGER, Math.max(2, Math.min(5, level))
            );
            player.getPersistentDataContainer().set(crateLuckUntilKey, PersistentDataType.LONG, until);
            activated(player, level + "x Crate Luck");
        }
    }

    private boolean applyEnchantment(ItemStack item, ItemMeta meta, String enchant, int level) {
        if (enchant.equals("excavation")) {
            if (!item.getType().name().endsWith("_PICKAXE")) {
                return false;
            }
            meta.getPersistentDataContainer().set(excavationKey, PersistentDataType.INTEGER, 1);
            List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
            lore.removeIf(line -> line.equals(Component.text("Excavation I", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            lore.add(0, line("Excavation I"));
            meta.lore(lore);
            meta.setEnchantmentGlintOverride(true);
            return true;
        }
        Enchantment vanilla = switch (enchant) {
            case "unbreaking" -> Enchantment.UNBREAKING;
            case "protection" -> Enchantment.PROTECTION;
            case "fortune" -> Enchantment.FORTUNE;
            default -> null;
        };
        if (vanilla == null || !vanilla.canEnchantItem(item)) {
            return false;
        }
        return meta.addEnchant(vanilla, level, true);
    }

    private ItemStack vanillaPotion(String id) {
        ItemStack item = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        String name;
        PotionEffect effect;
        Color color;
        switch (id) {
            case "potion_healing_ii" -> {
                name = "Potion of Healing II";
                effect = new PotionEffect(PotionEffectType.INSTANT_HEALTH, 1, 1);
                color = Color.fromRGB(245, 55, 90);
            }
            case "potion_strength_ii" -> {
                name = "Potion of Strength II";
                effect = new PotionEffect(PotionEffectType.STRENGTH, 3 * 60 * 20, 1);
                color = Color.fromRGB(175, 45, 45);
            }
            case "potion_swiftness_ii" -> {
                name = "Potion of Swiftness II";
                effect = new PotionEffect(PotionEffectType.SPEED, 3 * 60 * 20, 1);
                color = Color.fromRGB(85, 175, 245);
            }
            default -> {
                name = "Potion of Fire Resistance";
                effect = new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 8 * 60 * 20, 0);
                color = Color.fromRGB(245, 135, 35);
            }
        }
        meta.displayName(Component.text(name, NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
        meta.addCustomEffect(effect, true);
        meta.setColor(color);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack enchantmentBook(String id) {
        ItemStack item = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
        String enchant;
        int level = romanLevel(id);
        if (id.startsWith("enchant_excavation")) {
            enchant = "excavation";
        } else if (id.startsWith("enchant_unbreaking")) {
            enchant = "unbreaking";
            meta.addStoredEnchant(Enchantment.UNBREAKING, level, true);
        } else if (id.startsWith("enchant_protection")) {
            enchant = "protection";
            meta.addStoredEnchant(Enchantment.PROTECTION, level, true);
        } else {
            enchant = "fortune";
            meta.addStoredEnchant(Enchantment.FORTUNE, level, true);
        }
        String name = title(enchant) + " " + roman(level);
        meta.displayName(Component.text(name, NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(
                bookKey, PersistentDataType.STRING, enchant + ":" + level
        );
        if (enchant.equals("excavation")) {
            meta.lore(List.of(line("Pickaxe only"), line("Breaks a 3x3 mining face")));
            meta.setEnchantmentGlintOverride(true);
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack customPotion(
            String kind, int level, double multiplier, String name, Color color,
            String modelKey, String description
    ) {
        ItemStack item = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        meta.displayName(Component.text(name, ORANGE, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.setColor(color);
        meta.lore(List.of(line(description), line("Persists through reconnects.")));
        meta.getPersistentDataContainer().set(potionKindKey, PersistentDataType.STRING, kind);
        meta.getPersistentDataContainer().set(potionLevelKey, PersistentDataType.INTEGER, level);
        NamespacedKey model = NamespacedKey.fromString(modelKey);
        if (model != null) {
            meta.setItemModel(model);
        }
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    private Optional<String> specialBook(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return Optional.empty();
        }
        return Optional.ofNullable(item.getItemMeta().getPersistentDataContainer().get(
                bookKey, PersistentDataType.STRING
        ));
    }

    private boolean hasExcavation(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(
                        excavationKey, PersistentDataType.INTEGER
                );
    }

    private double fortuneMultiplier(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        Long until = data.get(fortuneUntilKey, PersistentDataType.LONG);
        Double multiplier = data.get(fortuneMultiplierKey, PersistentDataType.DOUBLE);
        if (until == null || multiplier == null || until <= System.currentTimeMillis()) {
            data.remove(fortuneUntilKey);
            data.remove(fortuneMultiplierKey);
            return 1d;
        }
        return Math.max(1d, Math.min(5d, multiplier));
    }

    private List<Block> excavationPlane(Player player, Block centre) {
        Vector direction = player.getEyeLocation().getDirection();
        List<Block> blocks = new ArrayList<>(8);
        for (int first = -1; first <= 1; first++) {
            for (int second = -1; second <= 1; second++) {
                if (first == 0 && second == 0) {
                    continue;
                }
                if (Math.abs(direction.getY()) > 0.65d) {
                    blocks.add(centre.getRelative(first, 0, second));
                } else if (Math.abs(direction.getX()) > Math.abs(direction.getZ())) {
                    blocks.add(centre.getRelative(0, second, first));
                } else {
                    blocks.add(centre.getRelative(first, second, 0));
                }
            }
        }
        return blocks;
    }

    private void activated(Player player, String effect) {
        player.sendMessage(PlayerMenuService.prefix()
                .append(Component.text(effect, NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
                .append(Component.text(" active for 15 minutes.", NamedTextColor.WHITE)));
        player.getWorld().spawnParticle(
                Particle.WITCH, player.getLocation().add(0d, 1d, 0d),
                30, 0.45d, 0.8d, 0.45d, 0.05d
        );
        player.playSound(player.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 1f, 1.25f);
    }

    private static int romanLevel(String id) {
        String suffix = id.substring(id.lastIndexOf('_') + 1).toUpperCase(Locale.ROOT);
        return switch (suffix) {
            case "I" -> 1;
            case "II" -> 2;
            case "III" -> 3;
            case "IV" -> 4;
            case "V" -> 5;
            default -> throw new IllegalArgumentException("Unknown item level " + id);
        };
    }

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> "V";
        };
    }

    private static String title(String value) {
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private static String trimMultiplier(double value) {
        return value == Math.rint(value) ? String.valueOf((int) value) : String.valueOf(value);
    }

    private static Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }
}
