package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural guardrails for the safety promises player-facing screens make. */
final class PvpDuelSafetyTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/bot/mgx/accessbridge/PvpDuelService.java");

    private String source() throws Exception {
        return Files.readString(SOURCE, StandardCharsets.UTF_8);
    }

    @Test
    void deathAlwaysKeepsInventoryAndLevels() throws Exception {
        String source = source();
        assertTrue(source.contains("event.setKeepInventory(true)"));
        assertTrue(source.contains("event.setKeepLevel(true)"));
        assertTrue(source.contains("event.getDrops().clear()"));
        assertTrue(source.contains("event.setDroppedExp(0)"));
        assertTrue(source.indexOf("winner.saveData()") < source.indexOf(
                "store.settle(playerId, economy.balance(playerId))"));
    }

    @Test
    void spectatorsAreAnchoredWithoutFreeRoamSpectatorMode() throws Exception {
        String source = source();
        assertTrue(source.contains("player.setGameMode(GameMode.ADVENTURE)"));
        assertTrue(source.contains("spectators.containsKey(victim.getUniqueId())"));
        assertTrue(source.contains("restoreSpectatorInventory"));
        assertFalse(source.contains("setGameMode(GameMode.SPECTATOR)"));
    }

    @Test
    void arenaIsUntouchedBoundedAndWorldSafe() throws Exception {
        String source = source();
        assertTrue(source.contains("world.isChunkGenerated"));
        assertTrue(source.contains("getInhabitedTime() > 0L"));
        assertTrue(source.contains("player.setWorldBorder(personalBorder"));
        assertTrue(source.contains("event.blockList().clear()"));
        assertTrue(source.contains("onBucketEmpty"));
        assertTrue(source.contains("onIgnite"));
        assertTrue(source.contains("onEntityPlace"));
        assertTrue(source.contains("onCreatureSpawn"));
    }

    @Test
    void openWorldPvpUsesItsOwnToggleAndDeathSideEffectsOptOut() throws Exception {
        String source = source();
        assertTrue(source.contains("PvP is disabled. Use /pvp to fight."));
        assertTrue(source.contains("plugin.openWorldPvpEnabled()"));
        assertTrue(source.contains("event.setCancelled(!opponent)"));
        assertTrue(source.contains("source instanceof Tameable"));
        assertTrue(source.contains("isOpponentAttack"));
        for (String service : new String[] {
                "BountyService.java", "TrophyHeadService.java", "WardrobeService.java"
        }) {
            String other = Files.readString(SOURCE.getParent().resolve(service),
                    StandardCharsets.UTF_8);
            assertTrue(other.contains("plugin.inPvpDuel(victim)"), service);
        }
    }

    @Test
    void openWorldToggleDoesNotDisableArrangedFights() throws Exception {
        String plugin = Files.readString(SOURCE.getParent().resolve("MGXAccessBridge.java"),
                StandardCharsets.UTF_8);
        String forcePvp = plugin.substring(
                plugin.indexOf("void forcePvp(boolean enabled)"),
                plugin.indexOf("boolean inScreenshotMode")
        );
        assertFalse(forcePvp.contains("pvpDuels.pauseAll"));

        String challengeChecks = source().substring(
                source().indexOf("private boolean canChallenge"),
                source().indexOf("private boolean acceptingChallenges")
        );
        assertFalse(challengeChecks.contains("openWorldPvpEnabled"));
    }

    @Test
    void everyClientHasItemCosmeticAndAcceptancePaths() throws Exception {
        String source = source();
        assertTrue(source.contains("openChestSetup"));
        assertTrue(source.contains("openChestAccept"));
        assertTrue(source.contains("openItemWager"));
        assertTrue(source.contains("openCosmeticWager"));
        assertTrue(source.contains("restoreEscrow"));
        assertFalse(source.contains("Both players must hold the stack"));
        assertFalse(source.contains("maximum-money-wager"));
    }

    /**
     * Back owns the middle of a board's bottom row and is drawn last, so anything
     * written to that slot is painted over and can never be clicked. Send Challenge
     * sat on slot 22 of a 27-slot board, which is exactly that slot: the challenge
     * screen had no way to send a challenge.
     */
    @Test
    void chestBoardsKeepEveryActionOffTheSlotBackOwns() {
        int back = MenuPaging.backSlot(PvpDuelService.SETUP_BOARD_SIZE);
        for (int slot : new int[] {
                PvpDuelService.SETUP_MONEY_SLOT, PvpDuelService.SETUP_ITEMS_SLOT,
                PvpDuelService.SETUP_COSMETICS_SLOT, PvpDuelService.SETUP_CLEAR_SLOT,
                PvpDuelService.SETUP_SEND_SLOT, PvpDuelService.ACCEPT_OFFER_SLOT,
                PvpDuelService.ACCEPT_MONEY_SLOT, PvpDuelService.ACCEPT_ITEMS_SLOT,
                PvpDuelService.ACCEPT_COSMETICS_SLOT, PvpDuelService.ACCEPT_DECLINE_SLOT,
                PvpDuelService.ACCEPT_CONFIRM_SLOT
        }) {
            assertNotEquals(back, slot);
        }
    }

    @Test
    void theCashWagerIsAFieldOnTheScreenRatherThanAChatPrompt() throws Exception {
        String source = source();
        assertTrue(source.contains("DialogInput.text(MONEY_INPUT"));
        assertTrue(source.contains("response.getText(MONEY_INPUT)"));
        assertTrue(source.contains("forms.prompt(player, \"Money Wager\""));
        // Blank and zero are "items only", which the shared parser rejects because
        // every other amount in the economy has to be worth at least a dollar.
        String reader = source.substring(
                source.indexOf("private static String applyMoney"),
                source.indexOf("private void openMoneyPrompt"));
        assertTrue(reader.contains("text.isEmpty() || text.equals(\"0\")"));
    }

    @Test
    void aFighterCanAlwaysReopenTheScreenThatGivesUp() throws Exception {
        String source = source();
        String gate = source.substring(
                source.indexOf("public void onCommand(PlayerCommandPreprocessEvent"),
                source.indexOf("public void onBreak(BlockBreakEvent"));
        assertTrue(gate.contains("typed.length == 1"));
        assertTrue(gate.contains("givingUp(typed[1])"));
        assertFalse(gate.contains("typed.equals(\"/pvp forfeit\")"));
        assertTrue(source.contains("case \"forfeit\", \"surrender\", \"giveup\", \"ff\" -> true;"));
    }

    @Test
    void challengesRespectPrivacyAndDoNotForceOpenARecipientsMenu() throws Exception {
        String source = source();
        assertTrue(source.contains("PlayerSettingsStore.Setting.DUEL_REQUESTS"));
        assertTrue(source.contains("pvp-duels.challenge-cooldown-seconds"));
        String sender = source.substring(
                source.indexOf("private String sendChallenge"),
                source.indexOf("private void openIncoming")
        );
        assertFalse(sender.contains("openInvitation(target, invitation);"));
    }
}
