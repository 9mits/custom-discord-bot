package bot.mgx.accessbridge;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * How one live game value should be presented, and what a change to it reaches.
 *
 * <p>The control panel could only ever draw a number box because a definition carried
 * no more than a minimum, a maximum and a free-text unit. A weight of {@code 8} is not
 * a fact about the world — it is one row of a distribution — and nothing in the old
 * shape could say so. This is the missing half: what kind of quantity a value is, which
 * page it belongs on, which other values move with it, and whether a change is felt now
 * or at the next spawn.
 *
 * <p>Derived from the definition rather than declared at all 380 call sites. Derivation
 * is total — every definition resolves to a control, a group and a reload class, and
 * {@code SettingMetadataTest} fails if any stops doing so. That keeps a newly added
 * variable inside the same standards automatically instead of quietly arriving without
 * metadata.
 */
record SettingMetadata(
        Control control,
        Group group,
        Reload reload,
        String table,
        String partner,
        String restartReason
) {
    /** What the panel should draw. Chosen so a value is never shown as a bare integer. */
    enum Control {
        /** One row of a distribution. Edited as a share of its table, never alone. */
        WEIGHT_ROW,
        /** A count of something the player receives. */
        QUANTITY,
        /** A "one in N" rate, shown as both the odds and the percentage. */
        ODDS,
        /** A span of time, stored in the unit the store already uses. */
        DURATION,
        /** A distance in blocks. */
        DISTANCE,
        /** A percentage of something, 1-99. */
        PERCENT,
        /** A rate expressed per fixed denominator, such as per 10,000. */
        RATE,
        /** On or off. */
        TOGGLE,
        /** Entity or structure health. */
        HEALTH,
        /** A number of players. */
        POPULATION,
        /** A bare count with no player-facing unit, such as placement attempts. */
        COUNT,
        /** How much an event multiplies by, shown as the factor players are told. */
        MULTIPLIER,
        /** One of a fixed set of names, such as a boss-bar colour. */
        CHOICE,
        /** Free text with a length cap. */
        TEXT,
        /** A potion or enchantment level. */
        LEVEL
    }

    /** The page a value belongs on, keyed off what it configures rather than its prefix depth. */
    enum Group {
        CRATES("Crates"),
        AIRDROPS("Airdrops"),
        ONLINE_REWARDS("Online Rewards"),
        HUGE_AMETHYST("Huge Amethyst"),
        ADMIN_EVENTS("Admin Events"),
        EVENT_SCHEDULE("Event Schedule"),
        AMETHYST_MOBS("Amethyst Mobs"),
        EVENT_MULTIPLIERS("Event Multipliers"),
        PLAYERS("Players"),
        WORLD("World"),
        CLANS("Clans"),
        AUCTION_HOUSE("Auction House"),
        BOSS_BARS("Boss Bars"),
        POTIONS("Potions"),
        ENCHANTMENTS("Enchantments"),
        PRESENTATION("Presentation"),
        SHOP("Shop"),
        COSMETICS("Cosmetics"),
        CRATE_BALANCE("Crate Balance"),
        /**
         * A value whose prefix nothing here recognises.
         *
         * <p>Deliberately a bucket rather than an exception. The Java suite is not part
         * of the required CI checks — those run Python only — so a throw here would
         * first be felt as a live server losing its entire variable snapshot, not as a
         * red build. {@code SettingMetadataTest} asserts this stays empty, which catches
         * it locally; the bucket is what keeps the failure proportionate if it ever gets
         * past that.
         */
        UNCLASSIFIED("Unclassified");

        private final String displayName;

        Group(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }
    }

    /**
     * When a change is felt.
     *
     * <p>Nearly everything is {@link #LIVE}, because services read through
     * {@code variables.integer(key)} at the moment of use rather than caching at
     * startup. {@link #NEXT_EVENT} is reserved for the few values copied into live
     * state when something spawns, where an instance already standing in the world
     * keeps the number it was born with.
     */
    enum Reload {
        /** The next read uses the new value. Nothing is cached. */
        LIVE,
        /** Copied into an event when it spawns; anything already standing keeps the old value. */
        NEXT_EVENT,
        /** Cannot take effect until Paper restarts. Must carry a reason. */
        RESTART
    }

    /**
     * Values baked into live state at spawn.
     *
     * <p>Verified by reading each consumer, not inferred from the name: an Airdrop's
     * expiry and a Huge Amethyst Block's expiry and maximum health are all computed once
     * and stored on the active instance. The scheduler window is deliberately not here —
     * {@code AmethystEventCoordinator} subscribes to changes and recomputes.
     */
    private static final Set<String> CAPTURED_AT_SPAWN = Set.of(
            "airdrop.lifetime-minutes",
            "huge-amethyst.lifetime-minutes",
            "huge-amethyst.maximum-health"
    );

    /** Derives the metadata for one definition, given every key the store knows. */
    static SettingMetadata of(GameVariableStore.Definition definition, Set<String> everyKey) {
        String key = definition.key();
        return new SettingMetadata(
                control(definition),
                group(key),
                CAPTURED_AT_SPAWN.contains(key) ? Reload.NEXT_EVENT : Reload.LIVE,
                table(key).orElse(null),
                partner(key, everyKey).orElse(null),
                null
        );
    }

    private static Control control(GameVariableStore.Definition definition) {
        // Kind before unit: a choice and a flag both declare no unit, so deciding on the
        // unit alone made one of them look like the other.
        switch (definition.type()) {
            case DECIMAL:
                // Falls through to the unit switch: a decimal is still a speed, a volume
                // or a multiplier, and those already have controls.
                break;
            case BOOLEAN:
                return Control.TOGGLE;
            case CHOICE:
                return Control.CHOICE;
            case TEXT:
                return Control.TEXT;
            default:
                break;
        }
        String key = definition.key();
        if (key.endsWith("one-in")) {
            return Control.ODDS;
        }
        if (isWeight(key)) {
            // Cosmetic chance is a weight by name only: it is a rate out of a fixed
            // denominator, not a share of a table that has to sum.
            return "per 10,000".equals(definition.unit()) ? Control.RATE : Control.WEIGHT_ROW;
        }
        return switch (definition.unit()) {
            case "minutes", "hours" -> Control.DURATION;
            case "blocks" -> Control.DISTANCE;
            case "percent" -> Control.PERCENT;
            case "players" -> Control.POPULATION;
            case "health" -> Control.HEALTH;
            case "attempts" -> Control.COUNT;
            case "x" -> Control.MULTIPLIER;
            case "seconds" -> Control.DURATION;
            case "chunks", "X", "Z" -> Control.COUNT;
            case "level" -> Control.LEVEL;
            case "per frame", "volume", "sigma" -> Control.RATE;
            default -> Control.QUANTITY;
        };
    }

    private static Group group(String key) {
        if (key.startsWith("crate.")) {
            return Group.CRATES;
        }
        if (key.startsWith("airdrop.")) {
            return Group.AIRDROPS;
        }
        if (key.startsWith("online-rewards.")) {
            return Group.ONLINE_REWARDS;
        }
        if (key.startsWith("huge-amethyst.")) {
            return Group.HUGE_AMETHYST;
        }
        if (key.startsWith("chaos.")) {
            return Group.ADMIN_EVENTS;
        }
        if (key.startsWith("amethyst-events.")) {
            return Group.EVENT_SCHEDULE;
        }
        if (key.startsWith("amethyst-mobs.")) {
            return Group.AMETHYST_MOBS;
        }
        if (key.startsWith("events.")) {
            return Group.EVENT_MULTIPLIERS;
        }
        if (key.startsWith("afk.") || key.startsWith("rtp.")
                || key.startsWith("verification.") || key.startsWith("combat.")) {
            return Group.PLAYERS;
        }
        if (key.startsWith("world.") || key.startsWith("spawn.")) {
            return Group.WORLD;
        }
        if (key.startsWith("bars.")) {
            return Group.BOSS_BARS;
        }
        if (key.startsWith("potions.")) {
            return Group.POTIONS;
        }
        if (key.startsWith("enchants.")) {
            return Group.ENCHANTMENTS;
        }
        if (key.startsWith("scoreboard.")) {
            return Group.PRESENTATION;
        }
        if (key.startsWith("cosmetics.")) {
            return Group.COSMETICS;
        }
        if (key.startsWith("crates.")) {
            // Balance and luck both describe how generous a crate is, so they share a page.
            return Group.CRATE_BALANCE;
        }
        if (key.startsWith("shop.")) {
            return Group.SHOP;
        }
        if (key.startsWith("clans.")) {
            return Group.CLANS;
        }
        if (key.startsWith("auction.")) {
            return Group.AUCTION_HOUSE;
        }
        if (key.startsWith("admin-events.")) {
            return Group.ADMIN_EVENTS;
        }
        return Group.UNCLASSIFIED;
    }

    private static boolean isWeight(String key) {
        return key.endsWith(".weight") || key.endsWith("-weight");
    }

    /**
     * The distribution this row belongs to, if any.
     *
     * <p>Every row sharing a table competes for the same total, so editing one moves the
     * printed chance of all the others. That relationship is what the old panel could
     * not express, and it is why a weight was meaningless on its own.
     */
    static Optional<String> table(String key) {
        if (!isWeight(key)) {
            return Optional.empty();
        }
        String[] parts = key.split("\\.");
        // crate.<kind>.reward.<id>.weight
        if (parts.length == 5 && parts[0].equals("crate") && parts[2].equals("reward")) {
            return Optional.of("crate." + parts[1]);
        }
        // airdrop.rarity.<rarity>.weight — the suffix must be exactly "weight".
        // airdrop.rarity.<rarity>.cosmetic-weight sits beside it and is a rate out of a
        // fixed denominator, not a competitor for the same total; folding it in here
        // made the rarity table look non-empty when every real weight was zero.
        if (parts.length == 4 && parts[0].equals("airdrop") && parts[1].equals("rarity")
                && parts[3].equals("weight")) {
            return Optional.of("airdrop.rarity");
        }
        // airdrop.loot.<material>.<rarity>-weight — one table per rarity, across materials.
        if (parts.length == 4 && parts[0].equals("airdrop") && parts[1].equals("loot")) {
            String rarity = parts[3].substring(0, parts[3].length() - "-weight".length());
            return isAirdropRarity(rarity) ? Optional.of("airdrop.loot." + rarity) : Optional.empty();
        }
        return Optional.empty();
    }

    private static boolean isAirdropRarity(String name) {
        for (AirdropCatalog.Rarity rarity : AirdropCatalog.Rarity.values()) {
            if (rarity.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The other half of a minimum/maximum pair, when one exists.
     *
     * <p>Twelve keys are named {@code minimum-} or {@code maximum-} without a twin — a
     * lone floor or ceiling — so the key set decides rather than the name.
     */
    static Optional<String> partner(String key, Set<String> everyKey) {
        String candidate = twin(key);
        return candidate != null && everyKey.contains(candidate)
                ? Optional.of(candidate)
                : Optional.empty();
    }

    private static String twin(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".minimum")) {
            return lower.substring(0, lower.length() - ".minimum".length()) + ".maximum";
        }
        if (lower.endsWith(".maximum")) {
            return lower.substring(0, lower.length() - ".maximum".length()) + ".minimum";
        }
        int minimum = lower.lastIndexOf(".minimum-");
        if (minimum >= 0) {
            return lower.substring(0, minimum) + ".maximum-"
                    + lower.substring(minimum + ".minimum-".length());
        }
        int maximum = lower.lastIndexOf(".maximum-");
        if (maximum >= 0) {
            return lower.substring(0, maximum) + ".minimum-"
                    + lower.substring(maximum + ".maximum-".length());
        }
        return null;
    }
}
