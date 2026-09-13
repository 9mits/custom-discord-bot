package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PvpMatchSetupTest {
    @Test
    void rankedBattleCyclesOneSharedPopulationAcrossThreeTeamSizes() {
        PvpMatchSetup setup = PvpMatchSetup.defaults(PvpMode.RANKED_DUEL);
        assertEquals("Ranked 1v1", setup.matchLabel());
        assertEquals(PvpMode.RANKED_DUEL, setup.mode());
        setup = setup.nextSize();
        assertEquals("Ranked 2v2", setup.matchLabel());
        assertEquals(PvpMode.DOUBLES, setup.mode());
        setup = setup.nextSize();
        assertEquals(PvpMode.TRIPLES, setup.mode());
        assertEquals(PvpMode.RANKED_DUEL, setup.nextSize().mode());
    }

    @Test
    void clanBattleOffersTwoOrThreePlayersPerSide() {
        PvpMatchSetup setup = PvpMatchSetup.defaults(PvpMode.CLAN_BATTLE);
        assertEquals("2v2 Clan Battle", setup.matchLabel());
        assertEquals(4, setup.requiredPlayers());
        assertEquals("3v3 Clan Battle", setup.nextSize().matchLabel());
        assertEquals("2v2 Clan Battle", setup.nextSize().nextSize().matchLabel());
    }

    @Test
    void accessAndFillAreIndependentMatchChoices() {
        PvpMatchSetup setup = PvpMatchSetup.defaults(PvpMode.DOUBLES);
        assertEquals(PvpMatchSetup.Access.PUBLIC, setup.access());
        assertTrue(setup.fill());
        setup = setup.toggleAccess().toggleFill();
        assertEquals(PvpMatchSetup.Access.INVITE_ONLY, setup.access());
        assertFalse(setup.fill());
    }

    @Test
    void lastStandingCyclesAConfigurableTwoToTwelvePlayerTarget() {
        PvpMatchSetup setup = PvpMatchSetup.defaults(PvpMode.FFA);
        assertEquals(4, setup.targetPlayers());
        for (int ignored = 0; ignored < 8; ignored++) setup = setup.nextSize();
        assertEquals(12, setup.targetPlayers());
        assertEquals(2, setup.nextSize().targetPlayers());
    }
}
