package bot.mgx.accessbridge;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

/** Shared, version-tolerant encoding for inventory and effect recovery. */
final class PvpStateCodec {
    private record Effect(
            String type,
            int duration,
            int amplifier,
            boolean ambient,
            boolean particles,
            boolean icon
    ) {
    }

    private static final Gson GSON = new Gson();
    private static final Type EFFECT_LIST = new TypeToken<List<Effect>>() { }.getType();

    private PvpStateCodec() {
    }

    static String encodeItem(ItemStack item) {
        return item == null || item.getType().isAir() ? ""
                : Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    static ItemStack decodeItem(String encoded) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
    }

    static String encodeItems(ItemStack[] items) {
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items));
    }

    static ItemStack[] decodeItems(String encoded) {
        return ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(encoded));
    }

    static ItemStack[] sized(ItemStack[] items, int size) {
        if (items.length == size) return items;
        ItemStack[] result = new ItemStack[size];
        System.arraycopy(items, 0, result, 0, Math.min(items.length, size));
        return result;
    }

    static String encodeEffects(Collection<PotionEffect> effects) {
        List<Effect> saved = effects.stream().map(effect -> new Effect(
                effect.getType().getKey().asString(), effect.getDuration(), effect.getAmplifier(),
                effect.isAmbient(), effect.hasParticles(), effect.hasIcon()
        )).toList();
        if (saved.isEmpty()) return "";
        return Base64.getEncoder().encodeToString(
                GSON.toJson(saved).getBytes(StandardCharsets.UTF_8));
    }

    static List<PotionEffect> decodeEffects(String encoded) {
        if (encoded == null || encoded.isBlank()) return List.of();
        try {
            String json = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            List<Effect> saved = GSON.fromJson(json, EFFECT_LIST);
            List<PotionEffect> result = new ArrayList<>();
            if (saved == null) return List.of();
            for (Effect row : saved) {
                NamespacedKey key = NamespacedKey.fromString(row.type());
                PotionEffectType type = key == null ? null : Registry.MOB_EFFECT.get(key);
                if (type != null && row.duration() > 0) {
                    result.add(new PotionEffect(type, row.duration(), row.amplifier(),
                            row.ambient(), row.particles(), row.icon()));
                }
            }
            return List.copyOf(result);
        } catch (RuntimeException malformed) {
            return List.of();
        }
    }
}
