package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class NameplateStyleTest {
    @Test
    void keepsCompactMoneyAndAddsThePvpRank() {
        Component line = SidebarService.nameplateLine(5_000_000L, PvpRank.DIAMOND_II);

        assertEquals("$ 5M  •  " + BadgeIcons.PVP_DIAMOND + " Diamond II",
                PlainTextComponentSerializer.plainText().serialize(line));
        assertEquals(NamedTextColor.GREEN, line.color());
        assertEquals(NamedTextColor.WHITE, line.children().get(0).color());
        assertEquals(NamedTextColor.WHITE, line.children().get(1).color());
        assertNeverBold(line);
    }

    @Test
    void everyPlayerStillShowsMoneyAtTheBottomRank() {
        Component line = SidebarService.nameplateLine(92_230L, PvpRank.BRONZE_I);

        assertEquals("$ 92.2K  •  " + BadgeIcons.PVP_BRONZE + " Bronze I",
                PlainTextComponentSerializer.plainText().serialize(line));
    }

    @Test
    void genuineSecretGetsAnAnimatedThirdNameplateLine() {
        CosmeticCatalog.Definition imperium = CosmeticCatalog
                .find(CosmeticCatalog.HIDDEN_AMETHYST_COSMETIC_ID).orElseThrow();

        Component first = CosmeticEffectService.rarityNameplate(imperium, 0);
        Component shifted = CosmeticEffectService.rarityNameplate(imperium, 4);

        assertEquals("✦ 1 IN 500,000 ✦",
                PlainTextComponentSerializer.plainText().serialize(first));
        assertNotEquals(first, shifted);
    }

    private static void assertNeverBold(Component component) {
        assertNotEquals(TextDecoration.State.TRUE, component.decoration(TextDecoration.BOLD));
        component.children().forEach(NameplateStyleTest::assertNeverBold);
    }
}
