package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AirdropTestPlanTest {
    @Test
    void allRunsAMythicDropWithEveryAirdropCosmetic() {
        AirdropTestPlan.Request request = AirdropTestPlan.parse(
                new String[]{"testairdrop", "all"}
        );

        assertEquals(AirdropTestPlan.Action.SPAWN, request.action());
        assertEquals(AirdropCatalog.Rarity.MYTHIC, request.rarity());
        assertEquals(AirdropCatalog.cosmeticIds(), request.cosmeticIds());
    }

    @Test
    void eachRarityUsesRealLootWithoutAForcedCosmetic() {
        for (AirdropCatalog.Rarity rarity : AirdropCatalog.Rarity.values()) {
            AirdropTestPlan.Request request = AirdropTestPlan.parse(new String[]{
                    "testairdrop", rarity.name().toLowerCase(java.util.Locale.ROOT)
            });

            assertEquals(AirdropTestPlan.Action.SPAWN, request.action());
            assertEquals(rarity, request.rarity());
            assertTrue(request.cosmeticIds().isEmpty());
        }
    }

    @Test
    void cosmeticTestsGuaranteeOnlyTheRequestedType() {
        assertEquals(
                List.of("resonant_shatter"),
                AirdropTestPlan.parse(new String[]{"testairdrop", "cosmetic", "kill"})
                        .cosmeticIds()
        );
        assertEquals(
                List.of("crystalfall_wake"),
                AirdropTestPlan.parse(new String[]{"testairdrop", "cosmetic", "trail"})
                        .cosmeticIds()
        );
        assertEquals(
                List.of("airdrop_apotheosis"),
                AirdropTestPlan.parse(new String[]{"testairdrop", "cosmetic", "aura"})
                        .cosmeticIds()
        );
    }

    @Test
    void progressCanBeSetOrResetForAnOptionalPlayer() {
        AirdropTestPlan.Request set = AirdropTestPlan.parse(
                new String[]{"testairdrop", "progress", "12", "8", "Tester"}
        );
        assertEquals(AirdropTestPlan.Action.PROGRESS_SET, set.action());
        assertEquals(12L, set.cratesOpened());
        assertEquals(8L, set.airdropsOpened());
        assertEquals("Tester", set.targetName());

        AirdropTestPlan.Request reset = AirdropTestPlan.parse(
                new String[]{"testairdrop", "progress", "reset"}
        );
        assertEquals(AirdropTestPlan.Action.PROGRESS_RESET, reset.action());
        assertNull(reset.targetName());
    }

    @Test
    void maintenanceActionsAndDefaultHelpParseWithoutSideEffects() {
        assertEquals(
                AirdropTestPlan.Action.HELP,
                AirdropTestPlan.parse(new String[]{"testairdrop"}).action()
        );
        assertEquals(
                AirdropTestPlan.Action.STATUS,
                AirdropTestPlan.parse(new String[]{"testairdrop", "status"}).action()
        );
        assertEquals(
                AirdropTestPlan.Action.EXPIRE,
                AirdropTestPlan.parse(new String[]{"testairdrop", "expire"}).action()
        );
        assertEquals(
                AirdropTestPlan.Action.REMOVE,
                AirdropTestPlan.parse(new String[]{"testairdrop", "remove"}).action()
        );
    }

    @Test
    void unsafeOrAmbiguousTestRequestsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AirdropTestPlan.parse(
                new String[]{"testairdrop", "progress", "-1", "2"}
        ));
        assertThrows(IllegalArgumentException.class, () -> AirdropTestPlan.parse(
                new String[]{"testairdrop", "progress", "1", "1000001"}
        ));
        assertThrows(IllegalArgumentException.class, () -> AirdropTestPlan.parse(
                new String[]{"testairdrop", "cosmetic", "unknown"}
        ));
        assertThrows(IllegalArgumentException.class, () -> AirdropTestPlan.parse(
                new String[]{"testairdrop", "mythic", "extra"}
        ));
    }
}
