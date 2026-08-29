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
    CRATES("Crates", "crate", "Open crates and see every reward's odds.", Material.CHEST, "item/trial_key"),
    WARDROBE("Wardrobe", "wardrobe", "Equip auras, trails and kill effects.", Material.NETHER_STAR, "item/nether_star"),
    AUCTION("Auction House", "ah", "Buy and sell with other players.", Material.GOLD_INGOT, "item/gold_ingot"),
    SHOP("Shop", "shop", "Buy blocks, gear and supplies.", Material.EMERALD, "item/emerald"),
    SELL("Sell", "sell", "Turn items into money.", Material.DIAMOND, "item/diamond"),
    ENDER_CHEST("Ender Chest", "echest", "Your storage, anywhere.", Material.ENDER_CHEST, "item/ender_eye"),
    HOMES("Homes", "homes", "Travel to a home you have set.", Material.WHITE_BED, "item/oak_door"),
    WARPS("Warps", "warp", "Public places worth knowing.", Material.LODESTONE, "block/lodestone_top"),
    TELEPORT("Teleport", "tpmenu", "Ask to teleport to another player.", Material.ENDER_PEARL, "item/ender_pearl"),
    RTP("Random Teleport", "rtp", "Drop somewhere new in the wild.", Material.COMPASS, "item/compass_00"),
    CLANS("Clans", "clans", "Browse clans, or manage your own clan.", Material.IRON_CHESTPLATE, "item/iron_chestplate"),
    LEADERBOARDS("Leaderboards", "leaderboard", "Who is winning, and at what.", Material.GOLD_BLOCK, "block/gold_block"),
    STATS("Stats", "stats", "Your numbers, and anyone else's.", Material.BOOK, "item/book"),
    BOUNTIES("Bounties", "bounty", "Claim a bounty, or put one up.", Material.IRON_SWORD, "item/iron_sword"),
    PERKS("Level Perks", "perks", "What each level unlocks.", Material.EXPERIENCE_BOTTLE, "item/experience_bottle"),
    SETTINGS("Settings", "settings", "Change how the server treats you.", Material.COMPARATOR, "item/comparator");

    private final String label;
    private final String command;
    private final String tooltip;
    private final Material icon;
    private final String sprite;

    MainMenu(String label, String command, String tooltip, Material icon, String sprite) {
        this.label = label;
        this.command = command;
        this.tooltip = tooltip;
        this.icon = icon;
        this.sprite = sprite;
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

    /**
     * The texture this icon draws in a label.
     *
     * <p>Written out rather than derived from the material, for two reasons: the folder
     * split is not a rule ({@code lodestone_top}, {@code compass_00}), and several
     * materials have no flat texture at all — a chest, hopper or shield is an entity
     * model — so those carry a stand-in that does exist.
     */
    String sprite() {
        return sprite;
    }

    static List<MainMenu> entries() {
        return List.of(values());
    }
}
