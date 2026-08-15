package bot.mgx.accessbridge;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * Marks an inventory as one of the clan screens, so click handling can tell ours
 * apart from a chest a player happens to have open.
 *
 * <p>{@link Kind#DONATE} is the only screen that accepts item movement; the rest are
 * button boards whose clicks are cancelled and dispatched by slot.
 */
final class ClanMenu implements InventoryHolder {
    enum Kind {
        HUB,
        DONATE,
        BALANCE,
        DONORS,
        UPGRADE
    }

    private final Kind kind;
    private final UUID clanId;
    private Inventory inventory;

    ClanMenu(Kind kind, UUID clanId) {
        this.kind = kind;
        this.clanId = clanId;
    }

    Kind kind() {
        return kind;
    }

    UUID clanId() {
        return clanId;
    }

    void attach(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
