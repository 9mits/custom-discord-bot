package bot.mgx.accessbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.function.ToLongFunction;

/** The public, season-bound prize table inside the Mythic Giftbag. */
final class GiftbagCatalog {
    static final long DEFAULT_TOTAL_WEIGHT = 1_000_000L;

    enum Kind {
        SHARDS, SEASON_ITEM, SEASON_GEAR, SEASON_COSMETIC, MYTHIC_ITEM
    }

    enum Rarity {
        RARE("Rare", 0x55C8FF),
        EXCLUSIVE("Season Exclusive", 0xFF55FF),
        MYTHIC("Mythic", 0xB56CFF),
        MYTHIC_ITEM("Mythic Item", 0x53E5FF);

        final String label;
        final int colour;

        Rarity(String label, int colour) {
            this.label = label;
            this.colour = colour;
        }
    }

    record Entry(
            String id, Kind kind, String value, int amount, long defaultWeight,
            String displayName, Rarity rarity
    ) {
        String weightKey() {
            return "season.giftbag.reward." + id + ".weight";
        }
    }

    private static final List<Entry> ENTRIES = List.of(
            new Entry("shards_4", Kind.SHARDS, "", 4, 400_000, "4 Shards", Rarity.RARE),
            new Entry("shards_8", Kind.SHARDS, "", 8, 250_000, "8 Shards", Rarity.RARE),
            new Entry("shards_16", Kind.SHARDS, "", 16, 100_000, "16 Shards", Rarity.MYTHIC),
            new Entry("shards_32", Kind.SHARDS, "", 32, 5_200, "32 Shards", Rarity.MYTHIC),
            new Entry("rally_horn", Kind.SEASON_ITEM, "rally_horn", 1, 100_000,
                    "Rally Horn", Rarity.RARE),
            new Entry("season_pickaxe", Kind.SEASON_GEAR, "pickaxe", 1, 20_000,
                    "Season Pickaxe", Rarity.EXCLUSIVE),
            new Entry("season_axe", Kind.SEASON_GEAR, "axe", 1, 20_000,
                    "Season Axe", Rarity.EXCLUSIVE),
            new Entry("season_hoe", Kind.SEASON_GEAR, "hoe", 1, 20_000,
                    "Season Hoe", Rarity.EXCLUSIVE),
            new Entry("season_helmet", Kind.SEASON_GEAR, "helmet", 1, 20_000,
                    "Season Helmet", Rarity.EXCLUSIVE),
            new Entry("season_scythe", Kind.SEASON_GEAR, "scythe", 1, 15_000,
                    "Season Scythe", Rarity.EXCLUSIVE),
            new Entry("season_wings", Kind.SEASON_GEAR, "wings", 1, 10_000,
                    "Season Wings", Rarity.EXCLUSIVE),
            new Entry("season_trail", Kind.SEASON_COSMETIC, "trail", 1, 20_000,
                    "Season Trail", Rarity.EXCLUSIVE),
            new Entry("season_kill", Kind.SEASON_COSMETIC, "kill", 1, 12_000,
                    "Season Kill Effect", Rarity.EXCLUSIVE),
            new Entry("season_aura", Kind.SEASON_COSMETIC, "aura", 1, 6_000,
                    "Season Aura", Rarity.EXCLUSIVE),
            new Entry("riftcleaver", Kind.MYTHIC_ITEM, "riftcleaver", 1, 1_000,
                    "Riftcleaver", Rarity.MYTHIC_ITEM),
            new Entry("worldcarver", Kind.MYTHIC_ITEM, "worldcarver", 1, 600,
                    "Worldcarver", Rarity.MYTHIC_ITEM),
            new Entry("fatebound_idol", Kind.MYTHIC_ITEM, "fatebound_idol", 1, 200,
                    "Fatebound Idol", Rarity.MYTHIC_ITEM)
    );

    private GiftbagCatalog() {
    }

    static List<Entry> all() {
        return ENTRIES;
    }

    static List<Entry> forSeason(int season) {
        if (SeasonCosmetics.theme(season).isPresent()) return ENTRIES;
        return ENTRIES.stream()
                .filter(entry -> entry.kind != Kind.SEASON_GEAR && entry.kind != Kind.SEASON_COSMETIC)
                .toList();
    }

    static Optional<Entry> find(String id) {
        if (id == null) return Optional.empty();
        return ENTRIES.stream().filter(entry -> entry.id.equals(id)).findFirst();
    }

    static List<String> mythicItemIds() {
        return ENTRIES.stream().filter(entry -> entry.kind == Kind.MYTHIC_ITEM)
                .map(Entry::id).toList();
    }

    static Entry roll(int season, ToLongFunction<Entry> weights, RandomGenerator random) {
        List<Entry> entries = forSeason(season);
        List<Long> live = new ArrayList<>(entries.size());
        long total = 0L;
        for (Entry entry : entries) {
            long weight = Math.max(0L, weights.applyAsLong(entry));
            live.add(weight);
            total = Math.addExact(total, weight);
        }
        if (total <= 0L) throw new IllegalStateException("The Giftbag has no enabled rewards.");
        long needle = random.nextLong(total);
        for (int index = 0; index < entries.size(); index++) {
            needle -= live.get(index);
            if (needle < 0L) return entries.get(index);
        }
        return entries.get(entries.size() - 1);
    }
}
