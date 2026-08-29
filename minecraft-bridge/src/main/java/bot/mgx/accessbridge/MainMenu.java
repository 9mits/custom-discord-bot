package bot.mgx.accessbridge;

import org.bukkit.Material;

import java.util.List;

/**
 * The one list behind the quick-actions menu, in the order it is drawn.
 *
 * <p>The same entries are rendered three ways — the datapack dialog the G key opens,
 * the native dialog {@code /menu} opens on a modern Java client, and the chest menu
 * Bedrock and older clients fall back to. Keeping the list here is what stops the
 * three from drifting apart, since only one of them is easy to test.
 */
enum MainMenu {
    CRATES("Crates", "crate", "Open crates and see every reward's odds.", Material.CHEST),
    WARDROBE("Wardrobe", "wardrobe", "Equip auras, trails and kill effects.", Material.NETHER_STAR),
    AUCTION("Auction House", "ah", "Buy and sell with other players.", Material.GOLD_INGOT),
    SHOP("Shop", "shop", "Buy blocks, gear and supplies.", Material.EMERALD),
    SELL("Sell", "sell", "Turn items into money.", Material.HOPPER),
    ENDER_CHEST("Ender Chest", "echest", "Your storage, anywhere.", Material.ENDER_CHEST),
    HOMES("Homes", "homes", "Travel to a home you have set.", Material.WHITE_BED),
    TELEPORT("Teleport", "tpmenu", "Ask to teleport to another player.", Material.ENDER_PEARL),
    RANDOM_TELEPORT("Random Teleport", "rtp", "Drop somewhere new in the wild.", Material.COMPASS),
    CLANS("Clans", "clans", "Your clan, its treasury and its warps.", Material.SHIELD),
    LEADERBOARDS("Leaderboards", "leaderboard", "Who is winning, and at what.", Material.PLAYER_HEAD),
    STATS("Stats", "stats", "Your numbers, and anyone else's.", Material.BOOK),
    BOUNTIES("Bounties", "bounty", "Claim a bounty, or put one up.", Material.IRON_SWORD),
    PERKS("Level Perks", "perks", "What each level unlocks.", Material.EXPERIENCE_BOTTLE),
    SETTINGS("Settings", "settings", "Change how the server treats you.", Material.COMPARATOR);

    private final String label;
    private final String command;
    private final String tooltip;
    private final Material icon;

    MainMenu(String label, String command, String tooltip, Material icon) {
        this.label = label;
        this.command = command;
        this.tooltip = tooltip;
        this.icon = icon;
    }

    String label() {
        return label;
    }

    /** The command the button runs, without a leading slash. */
    String command() {
        return command;
    }

    String tooltip() {
        return tooltip;
    }

    Material icon() {
        return icon;
    }

    static List<MainMenu> entries() {
        return List.of(values());
    }
}
