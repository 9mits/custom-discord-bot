package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Strips the decorations a Bedrock client cannot draw, for the surfaces that are rendered
 * per viewer.
 *
 * <p>An atlas sprite — {@code MenuText.sprite}, the clan icon in chat and over a head — is
 * a 1.21.9 object component. Geyser has no Bedrock equivalent, so it sends the component's
 * plain form instead and a Bedrock player reads {@code [item/amethyst_shard@items]} where
 * Java shows a picture. That is worse than no icon at all, so Bedrock gets no icon.
 *
 * <p>Badges are deliberately not stripped: they are private-use glyphs, and the Bedrock
 * pack ships the same artwork as its {@code glyph_E8} page, so both editions draw them.
 */
final class BedrockText {
    private BedrockText() {
    }

    /**
     * The same message with every sprite removed, keeping its styling and anything that
     * followed it. A component with no sprites is returned unchanged, so the common Java
     * path costs one walk and no allocation.
     */
    static Component withoutSprites(Component source) {
        if (!hasSprite(source)) {
            return source;
        }
        List<Component> children = new ArrayList<>(source.children().size());
        for (Component child : source.children()) {
            children.add(withoutSprites(child));
        }
        Component rebuilt = source instanceof ObjectComponent
                ? Component.empty().style(source.style())
                : source.children(List.of());
        return rebuilt.children(children);
    }

    static boolean hasSprite(Component source) {
        if (source instanceof ObjectComponent) {
            return true;
        }
        for (Component child : source.children()) {
            if (hasSprite(child)) {
                return true;
            }
        }
        return false;
    }
}
