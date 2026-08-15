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
 *
 * <p>A menu also carries where its Back button leads. Storing the origin rather than
 * hard-coding one parent per screen is what lets the same screen be reached from two
 * places — the members list opens from both the hub and a clan card — and still go
 * back where the player actually came from.
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

    /** A screen to return to: enough to redraw it exactly as it was left. */
    record Destination(Kind kind, UUID subject, int page) {
        static Destination of(Kind kind) {
            return new Destination(kind, null, 1);
        }

        static Destination of(Kind kind, UUID subject) {
            return new Destination(kind, subject, 1);
        }
    }

    private final Kind kind;
    /** The clan being looked at, which is not always the viewer's own. */
    private final UUID subject;
    private final int page;
    /** Where Back leads, or null on a screen that is the start of its own flow. */
    private final Destination back;
    private Inventory inventory;

    Menu(Kind kind, UUID subject, int page, Destination back) {
        this.kind = kind;
        this.subject = subject;
        this.page = page;
        this.back = back;
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

    Destination back() {
        return back;
    }

    boolean hasBack() {
        return back != null;
    }

    void attach(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
