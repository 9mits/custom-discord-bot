package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Creates and recognises the unique bearer item for a cosmetic. */
final class CosmeticItems {
    static final String AMETHYST_AIRDROP_SOURCE =
            "Part of the limited-time Amethyst Airdrop";
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private static final TextColor AMETHYST = TextColor.color(0xB56CFF);
    private static final int DESCRIPTION_LINE_LENGTH = 46;
    private static final TextColor SERIAL_GOLD = TextColor.color(0xFFD700);
    private static final TextColor SERIAL_SILVER = TextColor.color(0xC0C0C0);
    private static final TextColor SERIAL_BRONZE = TextColor.color(0xCD7F32);
    private static final TextColor[] IMPERIUM_RAINBOW = {
            TextColor.color(0xE95CFF), TextColor.color(0x8B6CFF),
            TextColor.color(0x4DA6FF), TextColor.color(0x43E0C0),
            TextColor.color(0xF5D76E), TextColor.color(0xFF9D57),
            TextColor.color(0xFF5E8A)
    };
    record TokenInfo(UUID serial, String cosmeticId, int generation) {
    }

    private final NamespacedKey cosmeticIdKey;
    private final NamespacedKey serialKey;
    private final NamespacedKey generationKey;

    CosmeticItems(MGXAccessBridge plugin) {
        cosmeticIdKey = new NamespacedKey(plugin, "cosmetic_id");
        serialKey = new NamespacedKey(plugin, "cosmetic_serial");
        generationKey = new NamespacedKey(plugin, "cosmetic_generation");
    }

    ItemStack token(CosmeticCatalog.Definition definition, CosmeticStore.Token token) {
        ItemStack item = preview(definition, false);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            throw new IllegalStateException("Cosmetic carrier has no item metadata.");
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(cosmeticIdKey, PersistentDataType.STRING, definition.id());
        data.set(serialKey, PersistentDataType.STRING, token.serial().toString());
        data.set(generationKey, PersistentDataType.INTEGER, token.generation());
        List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
        if (token.serialNumber() > 0) {
            lore.add(Component.empty());
            lore.add(serialLine(token.serialNumber()));
        }
        meta.lore(lore);
        meta.setMaxStackSize(1);
        item.setItemMeta(meta);
        return item;
    }

    ItemStack preview(CosmeticCatalog.Definition definition, boolean oddsScreen) {
        return preview(definition, oddsScreen, null);
    }

    ItemStack preview(
            CosmeticCatalog.Definition definition, boolean oddsScreen, String chanceOverride
    ) {
        boolean masked = masksSecret(definition, oddsScreen);
        Material material = Material.matchMaterial(previewMaterialName(definition, oddsScreen));
        if (material == null) {
            material = Material.BARRIER;
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        // Crate previews hide a secret completely. Won tokens and wardrobe entries
        // use the real name and model so ownership is a genuine reveal.
        meta.displayName(itemName(definition, masked));
        List<Component> lore = new ArrayList<>();
        lore.add(masked
                ? obfuscated("Unknown Cosmetic", NamedTextColor.DARK_PURPLE)
                : line(definition.category().displayName()));
        for (String descriptionLine : wrapDescription(
                masked ? CosmeticCatalog.MASKED_DESCRIPTION : definition.description()
        )) {
            lore.add(line(descriptionLine));
        }
        if (CosmeticCatalog.isLimitedAmethyst(definition.id())) {
            lore.add(Component.empty());
            lore.add(Component.text(
                    "Part of the Limited-Time Amethyst Crate", AMETHYST
            ).decoration(TextDecoration.ITALIC, false));
        }
        if (CosmeticCatalog.isAmethystAirdrop(definition.id())) {
            lore.add(Component.empty());
            lore.add(Component.text(
                    AMETHYST_AIRDROP_SOURCE, AMETHYST
            ).decoration(TextDecoration.ITALIC, false));
        }
        if (!masked && definition.hiddenAmethystJackpot()) {
            lore.add(Component.text("♫ MUSIC-SYNCED COSMETIC ♫", NamedTextColor.AQUA,
                            TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(masked
                ? line("Rarity: ").append(obfuscated("Exotic", NamedTextColor.DARK_PURPLE))
                : line("Rarity: " + definition.rarityDisplay()));
        if (showsReciprocalOdds(definition)) {
            lore.add(masked
                    ? line("Odds: 1 in ").append(obfuscated("100,000", NamedTextColor.DARK_PURPLE))
                    : line("Odds: " + definition.oneInDisplay(false)));
        }
        if (showsExactChance(definition, oddsScreen)) {
            lore.add(masked
                    ? line("Chance: ").append(obfuscated("0.001%", NamedTextColor.DARK_PURPLE))
                    : line("Chance: " + (chanceOverride == null
                            ? definition.displayedChance() : chanceOverride)));
        }
        meta.lore(lore);
        NamespacedKey model = NamespacedKey.fromString(previewModelKey(definition, oddsScreen));
        if (model != null) {
            meta.setItemModel(model);
        }
        meta.setEnchantmentGlintOverride(true);
        meta.setRarity(rarity(definition));
        meta.addItemFlags(previewFlags());
        item.setItemMeta(meta);
        return item;
    }

    static boolean showsReciprocalOdds(CosmeticCatalog.Definition definition) {
        return !definition.leaderboardOnly()
                && !CosmeticCatalog.isAmethystAirdrop(definition.id())
                && !definition.clanBattleOnly();
    }

    static boolean showsExactChance(
            CosmeticCatalog.Definition definition, boolean oddsScreen
    ) {
        return oddsScreen
                && !CosmeticCatalog.isAmethystAirdrop(definition.id())
                && !definition.clanBattleOnly();
    }

    static List<String> wrapDescription(String description) {
        if (description == null || description.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : description.strip().split("\\s+")) {
            if (!current.isEmpty()
                    && current.length() + 1 + word.length() > DESCRIPTION_LINE_LENGTH) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(word);
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return List.copyOf(lines);
    }

    static ItemFlag[] previewFlags() {
        return new ItemFlag[] {ItemFlag.HIDE_ATTRIBUTES};
    }

    static boolean masksSecret(CosmeticCatalog.Definition definition, boolean oddsScreen) {
        return oddsScreen && definition.secret();
    }

    static Component serialLine(int serialNumber) {
        TextColor numberColour = switch (serialNumber) {
            case 1 -> SERIAL_GOLD;
            case 2 -> SERIAL_SILVER;
            case 3 -> SERIAL_BRONZE;
            default -> NamedTextColor.GRAY;
        };
        Component number = Component.text(Integer.toString(serialNumber), numberColour)
                .decoration(TextDecoration.ITALIC, false);
        if (serialNumber <= 3) {
            number = number.decoration(TextDecoration.BOLD, true);
        }
        return line("Serial #").append(number);
    }

    static Component itemName(CosmeticCatalog.Definition definition, boolean masked) {
        if (masked) {
            return obfuscated("????????????", NamedTextColor.DARK_PURPLE)
                    .decoration(TextDecoration.BOLD, true);
        }
        if (!definition.hiddenAmethystJackpot()) {
            return Component.text(definition.displayName(), colour(definition), TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false);
        }
        Component name = Component.empty();
        int index = 0;
        for (char character : definition.displayName().toCharArray()) {
            if (character == ' ') {
                name = name.append(Component.space());
                continue;
            }
            name = name.append(Component.text(
                    Character.toString(character),
                    IMPERIUM_RAINBOW[index++ % IMPERIUM_RAINBOW.length],
                    TextDecoration.BOLD
            ));
        }
        return name.decoration(TextDecoration.ITALIC, false);
    }

    private static Component obfuscated(String text, TextColor colour) {
        return Component.text(text, colour, TextDecoration.OBFUSCATED)
                .decoration(TextDecoration.ITALIC, false);
    }

    static String previewMaterialName(
            CosmeticCatalog.Definition definition,
            boolean oddsScreen
    ) {
        return masksSecret(definition, oddsScreen) ? "BLACK_DYE" : definition.materialName();
    }

    static String previewModelKey(CosmeticCatalog.Definition definition, boolean oddsScreen) {
        return masksSecret(definition, oddsScreen)
                ? CosmeticCatalog.MASKED_MODEL_KEY
                : definition.modelKey();
    }

    Optional<TokenInfo> read(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return Optional.empty();
        }
        PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        String cosmeticId = data.get(cosmeticIdKey, PersistentDataType.STRING);
        String rawSerial = data.get(serialKey, PersistentDataType.STRING);
        Integer generation = data.get(generationKey, PersistentDataType.INTEGER);
        if (cosmeticId == null || rawSerial == null || generation == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new TokenInfo(UUID.fromString(rawSerial), cosmeticId, generation));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    boolean carries(Player player, UUID serial) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (matches(item, serial)) {
                return true;
            }
        }
        return matches(player.getInventory().getItemInOffHand(), serial);
    }

    Optional<ItemStack> carried(Player player, UUID serial) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (matches(item, serial)) {
                return Optional.of(item);
            }
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        return matches(offhand, serial) ? Optional.of(offhand) : Optional.empty();
    }

    List<TokenInfo> carried(Player player) {
        List<TokenInfo> found = new ArrayList<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            read(item).ifPresent(found::add);
        }
        read(player.getInventory().getItemInOffHand()).ifPresent(found::add);
        return List.copyOf(found);
    }

    int refreshCarried(Player player, CosmeticStore store, String cosmeticId) {
        CosmeticCatalog.Definition definition = CosmeticCatalog.find(cosmeticId).orElse(null);
        if (definition == null) {
            return 0;
        }
        int refreshed = 0;
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int index = 0; index < storage.length; index++) {
            TokenInfo info = read(storage[index]).orElse(null);
            CosmeticStore.Token canonical = info == null ? null : store.token(info.serial()).orElse(null);
            if (canonical == null || !canonical.cosmeticId().equals(cosmeticId)) {
                continue;
            }
            storage[index] = token(definition, canonical);
            refreshed++;
        }
        player.getInventory().setStorageContents(storage);
        ItemStack offhand = player.getInventory().getItemInOffHand();
        TokenInfo info = read(offhand).orElse(null);
        CosmeticStore.Token canonical = info == null ? null : store.token(info.serial()).orElse(null);
        if (canonical != null && canonical.cosmeticId().equals(cosmeticId)) {
            player.getInventory().setItemInOffHand(token(definition, canonical));
            refreshed++;
        }
        return refreshed;
    }

    boolean removeOne(Player player, UUID serial) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int index = 0; index < storage.length; index++) {
            if (!matches(storage[index], serial)) {
                continue;
            }
            storage[index] = null;
            player.getInventory().setStorageContents(storage);
            return true;
        }
        if (matches(player.getInventory().getItemInOffHand(), serial)) {
            player.getInventory().setItemInOffHand(null);
            return true;
        }
        return false;
    }

    private boolean matches(ItemStack item, UUID serial) {
        return read(item).map(info -> info.serial().equals(serial)).orElse(false);
    }

    private static Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static TextColor colour(CosmeticCatalog.Definition definition) {
        if (definition.secret()) {
            return NamedTextColor.DARK_PURPLE;
        }
        return switch (definition.category()) {
            case KILL_EFFECT -> NamedTextColor.RED;
            case AURA -> NamedTextColor.GOLD;
            case TRAIL -> NamedTextColor.AQUA;
        };
    }

    private static ItemRarity rarity(CosmeticCatalog.Definition definition) {
        if (definition.secret() || definition.weight() <= 100) {
            return ItemRarity.EPIC;
        }
        if (definition.weight() <= 750) {
            return ItemRarity.RARE;
        }
        return ItemRarity.UNCOMMON;
    }
}
