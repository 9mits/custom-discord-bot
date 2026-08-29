package bot.mgx.accessbridge;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.object.ObjectContents;

import java.util.UUID;

/**
 * The shared look of the dialog screens.
 *
 * <p>The distinctive part is the inline sprite: 1.21.9 added the {@code object} text
 * component, so a player's face or an item icon can sit inside a button label rather
 * than only in an item slot. That is what makes these screens read as a designed menu
 * instead of a list of words, and it is why the dialogs are worth having at all.
 *
 * <p>Sprites are drawn 8x8 and ignore bold and italic, so styling around them has to
 * carry the emphasis instead.
 */
final class MenuText {
    static final TextColor ORANGE = TextColor.color(0xFF9900);
    /** Values are green; the labels in front of them stay quiet. */
    static final TextColor VALUE = TextColor.color(0x55FF55);
    static final TextColor LABEL = NamedTextColor.GRAY;
    static final TextColor GOLD = TextColor.color(0xFFD35A);
    static final TextColor SILVER = TextColor.color(0xC9D6E4);
    static final TextColor BRONZE = TextColor.color(0xCD7F32);
    private static final Key BLOCK_ATLAS = Key.key("minecraft", "blocks");
    /**
     * 1.21.11 moved item textures out of the blocks atlas into their own. Pointing an
     * item sprite at the blocks atlas draws a missing-texture square, which is what
     * every icon in the menu did before this.
     */
    private static final Key ITEM_ATLAS = Key.key("minecraft", "items");

    private MenuText() {
    }

    /** A player's face, 8x8, for a row that names somebody. */
    static Component head(UUID playerId) {
        return Component.object(ObjectContents.playerHead(playerId));
    }

    static Component head(String playerName) {
        return Component.object(ObjectContents.playerHead(playerName));
    }

    /** An icon named by its exact texture path, which also picks the atlas it lives in. */
    static Component sprite(String path) {
        return Component.object(ObjectContents.sprite(
                atlasFor(path), Key.key("minecraft", path)
        ));
    }

    static Key atlasFor(String path) {
        return path.startsWith("block/") ? BLOCK_ATLAS : ITEM_ATLAS;
    }

    /** {@code Label: value} with the value carrying the colour. */
    static Component stat(String label, String value) {
        return Component.text(label + ": ", LABEL)
                .append(Component.text(value, VALUE, TextDecoration.BOLD));
    }

    /** {@code Label: value} with an icon in front of the value. */
    static Component stat(String label, String sprite, String value) {
        return Component.text(label + ": ", LABEL)
                .append(sprite(sprite))
                .append(Component.text(" " + value, VALUE, TextDecoration.BOLD));
    }

    /** Gold, silver and bronze for the podium; everyone else is plain. */
    static TextColor placeColour(int rank) {
        return switch (rank) {
            case 1 -> GOLD;
            case 2 -> SILVER;
            case 3 -> BRONZE;
            default -> NamedTextColor.WHITE;
        };
    }

    /** {@code #3 [face] Name — $ 1.6T}, the row every board uses. */
    static Component rankedRow(int rank, UUID playerId, String name, Component value) {
        TextColor colour = placeColour(rank);
        return Component.text("#" + rank + " ", colour, TextDecoration.BOLD)
                .append(head(playerId))
                .append(Component.text(" " + name + " ", colour))
                .append(Component.text("— ", NamedTextColor.DARK_GRAY))
                .append(value);
    }

    static Component title(String text) {
        return Component.text(text, ORANGE, TextDecoration.BOLD);
    }

    static Component body(String text) {
        return Component.text(text, LABEL);
    }
}
