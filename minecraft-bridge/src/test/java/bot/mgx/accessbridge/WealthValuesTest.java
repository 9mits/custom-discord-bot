package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WealthValuesTest {
    @Test
    void ordersTheThingsPlayersFightOver() {
        // One netherite ingot outranks a stack of diamonds, which is the whole point
        // of the table; a regression here quietly rewrites the richest board.
        assertTrue(WealthValues.valueOf("NETHERITE_INGOT") > WealthValues.valueOf("DIAMOND") * 9);
        assertTrue(WealthValues.valueOf("DRAGON_EGG") > WealthValues.valueOf("BEACON"));
        assertTrue(WealthValues.valueOf("DIAMOND_BLOCK") >= WealthValues.valueOf("DIAMOND") * 9);
    }

    @Test
    void unlistedThingsAreWorthlessAndSoUndonatable() {
        assertEquals(0, WealthValues.valueOf("DIRT"));
        assertEquals(0, WealthValues.valueOf("COBBLESTONE"));
        assertFalse(WealthValues.isValuable("DIRT"));
        assertFalse(WealthValues.isValuable(""));
        assertFalse(WealthValues.isValuable(null));
        assertTrue(WealthValues.isValuable("DIAMOND"));
    }

    @Test
    void dyedShulkerBoxesCountAsPlainOnes() {
        assertEquals(WealthValues.valueOf("SHULKER_BOX"),
                WealthValues.valueOfIncludingVariants("LIME_SHULKER_BOX"));
        assertTrue(WealthValues.isValuable("PINK_SHULKER_BOX"));
        assertEquals(0, WealthValues.valueOfIncludingVariants("SHULKER_SHELL"));
    }

    @Test
    void namesAreCaseAndSpaceInsensitive() {
        assertEquals(WealthValues.valueOf("DIAMOND"), WealthValues.valueOf(" diamond "));
        assertEquals("Diamond Block", WealthValues.readable("DIAMOND_BLOCK"));
        assertEquals("", WealthValues.readable(null));
    }

    @Test
    void aVaultIsWorthTheSumOfWhatIsInIt() {
        Map<String, Integer> vault = new LinkedHashMap<>();
        vault.put("DIAMOND", 10);
        vault.put("NETHERITE_INGOT", 2);
        vault.put("DIRT", 64);

        assertEquals(
                WealthValues.valueOf("DIAMOND") * 10L + WealthValues.valueOf("NETHERITE_INGOT") * 2L,
                WealthValues.totalOf(vault)
        );
        assertEquals(0, WealthValues.totalOf(null));
        assertEquals(0, WealthValues.totalOf(Map.of()));
    }

    @Test
    void negativeAmountsCannotInflateABalance() {
        assertEquals(0, WealthValues.totalOf(Map.of("DIAMOND", -100)));
    }
}
