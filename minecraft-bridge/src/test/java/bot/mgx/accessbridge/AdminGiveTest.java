package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminGiveTest {
    @Test
    void moneyNeedsAPositiveAmount() {
        assertEquals(
                new AdminGive.Request(AdminGive.Type.MONEY, 2_500L, null),
                AdminGive.parse("money", "2500")
        );
        assertThrows(IllegalArgumentException.class, () -> AdminGive.parse("money", null));
        assertThrows(IllegalArgumentException.class, () -> AdminGive.parse("money", "0"));
    }

    @Test
    void keysDefaultToOneAndStopAtAStack() {
        // The amount is optional because handing over a single key is the common case.
        assertEquals(new AdminGive.Request(AdminGive.Type.KEY, 1L, null), AdminGive.parse("key", null));
        assertEquals(new AdminGive.Request(AdminGive.Type.KEY, 64L, null), AdminGive.parse("keys", "64"));
        assertThrows(IllegalArgumentException.class, () -> AdminGive.parse("key", "65"));
        assertThrows(IllegalArgumentException.class, () -> AdminGive.parse("key", "0"));
        assertThrows(IllegalArgumentException.class, () -> AdminGive.parse("key", "many"));
    }

    @Test
    void cosmeticsCarryTheirIdNormalised() {
        assertEquals(
                new AdminGive.Request(AdminGive.Type.COSMETIC, 1L, "ember_trail"),
                AdminGive.parse("cosmetic", "  Ember_Trail ")
        );
        assertThrows(IllegalArgumentException.class, () -> AdminGive.parse("cosmetic", null));
        assertThrows(IllegalArgumentException.class, () -> AdminGive.parse("cosmetic", "   "));
    }

    @Test
    void crateRewardsCarryTheirIdNormalised() {
        assertEquals(
                new AdminGive.Request(AdminGive.Type.REWARD, 1L, "enchant_excavation_i"),
                AdminGive.parse("reward", "  Enchant_Excavation_I ")
        );
        assertThrows(IllegalArgumentException.class, () -> AdminGive.parse("reward", null));
        assertThrows(IllegalArgumentException.class, () -> AdminGive.parse("reward", "   "));
    }

    @Test
    void aliasesResolveAndUnknownTypesExplainThemselves() {
        assertEquals(AdminGive.Type.MONEY, AdminGive.parse("CASH", "10").type());
        assertEquals(AdminGive.Type.KEY, AdminGive.parse("Crates", "2").type());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> AdminGive.parse("diamonds", "5")
        );
        assertEquals(AdminGive.usage(), failure.getMessage());
    }
}
