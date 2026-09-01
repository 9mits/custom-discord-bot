package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cosmetic bought from the auction has to end up in the buyer's wardrobe.
 *
 * <p>Ownership of a cosmetic lives in {@link CosmeticStore} custody, not in the item.
 * Listing one calls {@code withdraw}, which clears its stored owner so the token can
 * travel; the buyer then receives that token through {@code give}, which writes straight
 * into the inventory and fires no {@code EntityPickupItemEvent}. Nothing claimed it back,
 * so the buyer held a token nobody owned — missing from their wardrobe, and gone from
 * their inventory as soon as anything tidied up. Four serials were stranded that way on
 * the live server, three of them the first ever minted of their cosmetic.
 *
 * <p>There is no server in a unit test, so this asserts the wiring by reading the source:
 * the purchase must claim the token in the same breath as handing it over.
 */
final class AuctionCosmeticDeliveryTest {
    private static String source(String name) throws Exception {
        return Files.readString(
                Path.of("src/main/java/bot/mgx/accessbridge/" + name), StandardCharsets.UTF_8);
    }

    @Test
    void buyingClaimsTheCosmeticIntoTheBuyersWardrobe() throws Exception {
        String economy = source("EconomyMenuService.java");
        int confirm = economy.indexOf("private void clickConfirm(");
        assertTrue(confirm > 0, "clickConfirm is where a purchase completes");
        String body = economy.substring(confirm, economy.indexOf("private void buy(", confirm));
        int handed = body.indexOf("give(player, item)");
        int claimed = body.indexOf("wardrobe.vaultCarried(player)");
        assertTrue(handed > 0, "the purchase must still hand the item over");
        assertTrue(claimed > handed,
                "a bought cosmetic must be claimed into the wardrobe right after it is "
                        + "handed over, or the buyer holds a token nobody owns");
    }

    @Test
    void listingStillClearsCustodySoTheTokenCanTravel() throws Exception {
        String wardrobe = source("WardrobeService.java");
        assertTrue(wardrobe.contains("store.withdraw(player.getUniqueId(), serial)"),
                "listing must withdraw the cosmetic, or two people could own one serial");
    }

    /** Vaulting refuses a token that someone already owns, which is what made this silent. */
    @Test
    void vaultingSkipsATokenThatIsAlreadyOwned() throws Exception {
        String wardrobe = source("WardrobeService.java");
        int vault = wardrobe.indexOf("void vaultCarried(");
        assertTrue(vault > 0, "vaultCarried is the only path from carried token to wardrobe");
        String body = wardrobe.substring(vault, vault + 700);
        assertTrue(body.contains("token.stored()"),
                "vaultCarried must keep refusing a token that already has an owner");
    }
}
