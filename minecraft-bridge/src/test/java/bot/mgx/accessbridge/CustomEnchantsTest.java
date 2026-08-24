package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomEnchantsTest {
    @Test
    void theOriginalSingleMarkStillReads() {
        assertEquals(Map.of("unbreaking", 4), CustomEnchants.parse("unbreaking:4"));
        assertEquals(Map.of("excavation", 1), CustomEnchants.parse("excavation:1"));
    }

    @Test
    void aBookMayCarryEveryMarkItHasCollected() {
        assertEquals(
                Map.of("unbreaking", 5, "fortune", 4, "excavation", 1),
                CustomEnchants.parse("unbreaking:5,fortune:4,excavation:1")
        );
        assertEquals(
                "excavation:1,fortune:4,unbreaking:5",
                CustomEnchants.format(CustomEnchants.parse("unbreaking:5,fortune:4,excavation:1"))
        );
    }

    @Test
    void nonsenseIsIgnoredRatherThanTrusted() {
        assertTrue(CustomEnchants.parse(null).isEmpty());
        assertTrue(CustomEnchants.parse("").isEmpty());
        assertTrue(CustomEnchants.parse("sharpness:9").isEmpty());
        assertTrue(CustomEnchants.parse("unbreaking:zero").isEmpty());
        assertTrue(CustomEnchants.parse("unbreaking:0").isEmpty());
        assertEquals(Map.of("unbreaking", 5), CustomEnchants.parse("unbreaking:40"));
    }

    @Test
    void twoOfTheSameLevelMakeTheNextOneUp() {
        assertEquals(
                Map.of("unbreaking", 5),
                CustomEnchants.merge(Map.of("unbreaking", 4), Map.of("unbreaking", 4))
        );
    }

    @Test
    void aWeakerBookNeverDragsTheStrongerOneDown() {
        // The report that started this: Unbreaking IV came out of the anvil as III.
        assertEquals(
                Map.of("unbreaking", 4),
                CustomEnchants.merge(Map.of("unbreaking", 4), Map.of("unbreaking", 3))
        );
        assertEquals(
                Map.of("unbreaking", 4),
                CustomEnchants.merge(Map.of("unbreaking", 3), Map.of("unbreaking", 4))
        );
    }

    @Test
    void theCrateCeilingHolds() {
        assertEquals(
                Map.of("unbreaking", 5),
                CustomEnchants.merge(Map.of("unbreaking", 5), Map.of("unbreaking", 5))
        );
        assertEquals(
                Map.of("excavation", 1),
                CustomEnchants.merge(Map.of("excavation", 1), Map.of("excavation", 1))
        );
    }

    @Test
    void mergingKeepsEveryMarkFromBothSides() {
        // The second report: the custom enchantment arrived and everything else went.
        assertEquals(
                Map.of("unbreaking", 4, "fortune", 5, "excavation", 1),
                CustomEnchants.merge(
                        Map.of("unbreaking", 4, "fortune", 5),
                        Map.of("excavation", 1)
                )
        );
    }

    @Test
    void everyMarkHasACeilingAndNothingElseIsAccepted() {
        assertEquals(
                Map.of("unbreaking", 5, "protection", 5, "fortune", 5, "excavation", 1),
                CustomEnchants.MAX_LEVEL
        );
    }
}
