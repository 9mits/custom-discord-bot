package bot.mgx.accessbridge;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * Marks an inventory as one of ours, so click handling can tell a menu apart from a
 * chest a player happens to have open.
 *
 * <p>{@link Kind#CLAN_DONATE} is the only screen that accepts item movement; every
 * other screen is a button board whose clicks are cancelled and dispatched by slot.
 */
final class Menu implements InventoryHolder {
    enum Kind {
        CLAN_HUB,
        CLAN_DONATE,
        CLAN_BALANCE,
        CLAN_DONORS,
        CLAN_UPGRADE,
        CLAN_INFO,
        CLAN_MEMBERS,
        CLAN_LIST,
        SETTINGS,
        WHITELIST,
        PERKS;

        boolean acceptsItems() {
            return this == CLAN_DONATE;
        }
    }

    private final Kind kind;
    /** The clan being looked at, which is not always the viewer's own. */
    private final UUID subject;
    private final int page;
    private Inventory inventory;

    Menu(Kind kind, UUID subject, int page) {
        this.kind = kind;
        this.subject = subject;
        this.page = page;
    }

    Kind kind() {
        return kind;
    }

    UUID subject() {
        return subject;
    }

    int page() {
        return page;
    }

    void attach(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
