package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BedrockTextTest {
    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void aSpriteIsRemovedAndEverythingAroundItSurvives() {
        Component line = MenuText.sprite("item/amethyst_shard")
                .append(Component.text(" "))
                .append(Component.text("[URABE] ", NamedTextColor.GOLD))
                .append(Component.text("hello"));

        assertTrue(BedrockText.hasSprite(line));
        Component stripped = BedrockText.withoutSprites(line);
        assertFalse(BedrockText.hasSprite(stripped), "Bedrock must not be sent a sprite");
        assertEquals(" [URABE] hello", plain(stripped), "only the picture goes");
    }

    @Test
    void aSpriteNestedInAChildIsFoundToo() {
        Component line = Component.text("rank ")
                .append(Component.text("clan ").append(MenuText.sprite("mgx:item/shard")))
                .append(Component.text("name"));

        assertTrue(BedrockText.hasSprite(line));
        assertEquals("rank clan name", plain(BedrockText.withoutSprites(line)));
    }

    @Test
    void textWithoutASpriteIsNotRebuiltAtAll() {
        Component line = Component.text("[URABE] ").append(Component.text("hello"));
        assertFalse(BedrockText.hasSprite(line));
        assertSame(line, BedrockText.withoutSprites(line), "the Java path must not pay for this");
    }

    @Test
    void badgeGlyphsStayBecauseTheBedrockPackShipsThem() {
        Component medals = Component.text("[URABE] ").append(BadgeIcons.glyph(BadgeIcons.CLAN_BATTLE_GOLD));
        assertEquals("[URABE] " + BadgeIcons.CLAN_BATTLE_GOLD, plain(BedrockText.withoutSprites(medals)));
    }
}
