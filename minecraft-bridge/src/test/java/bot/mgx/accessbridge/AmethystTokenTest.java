package bot.mgx.accessbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The rename that had to reach keys nobody could find.
 *
 * <p>Keys were physical items scattered through inventories, ender chests and every
 * chest in the world, so nothing could sweep them up. The token therefore *is* the
 * key: same persistent marker, same item model id, retextured by the resource pack
 * in place. That is what these guard.
 */
class AmethystTokenTest {

    private static String source(String name) throws IOException {
        return Files.readString(
                Path.of("src/main/java/bot/mgx/accessbridge/" + name), StandardCharsets.UTF_8);
    }

    @Test
    void theTokenKeepsTheMarkerEveryExistingBalanceIsStoredUnder() throws IOException {
        String items = source("CrateItems.java");
        assertTrue(items.contains("keyMarker = new NamespacedKey(plugin, \"crate_key\")"),
                "renaming this marker orphans every key already in the world");
        assertTrue(items.contains("meta.getPersistentDataContainer().set(keyMarker,"),
                "a minted token has to carry the marker the old keys carry");
        assertTrue(items.contains("NamespacedKey.fromString(\"mgx:crate_key\")"),
                "the model id is what lets the pack retexture keys that already exist");
    }

    @Test
    void theKeyBecomesItsOwnItemRatherThanBorrowingTheTokenIdentity() throws IOException {
        String items = source("CrateItems.java");
        assertTrue(items.contains("mysteryKeyMarker = new NamespacedKey(plugin, \"mystery_key\")"));
        assertTrue(items.contains("NamespacedKey.fromString(\"mgx:mystery_key\")"));
        // Sharing a marker would make every old key spend as a key again, undoing it.
        assertNotEquals("crate_key", "mystery_key");
    }

    @Test
    void everyCurrencyGetsItsOwnPurseRatherThanFallingThroughToTokens() throws IOException {
        // The binary shard-or-else dispatch would have had the Default Crate quietly
        // spending Amethyst Tokens, since tokens inherited the key's marker.
        String crates = source("CrateService.java");
        for (String required : new String[]{
                "case KEY -> items.countMysteryKeys(player)",
                "case TOKEN -> items.count(player)",
                "case KEY -> items.removeMysteryKeys(player, count)",
                "case TOKEN -> items.remove(player, count)",
                "case KEY -> items.mysteryKey(count)",
                "case TOKEN -> items.token(count)"}) {
            assertTrue(crates.contains(required), "missing dispatch: " + required);
        }
    }

    @Test
    void theAmethystEventPaysTokensAndStayingOnlineStillPaysKeys() throws IOException {
        assertTrue(source("AmethystDragonService.java").contains("items.token(1)"));
        assertTrue(source("AmethystBlockEventService.java").contains("crateItems.token(1)"));
        assertTrue(source("ChaosService.java").contains("crateItems.token(1)"));
        assertTrue(source("AdminEventService.java").contains("crateItems.token(1)"));

        String crates = source("CrateService.java");
        assertTrue(crates.contains("items.giveMysteryKeys(player, banked)"),
                "the stay reward is the one the event did not take over");
        assertTrue(crates.contains("online-rewards.token-bonus"),
                "and it hands out tokens beside the keys");
    }

    @Test
    void theAmethystAndDragonCratesCostTokensWhileTheDefaultOneCostsKeys()
            throws IOException {
        assertEquals(CrateKind.Currency.TOKEN, CrateKind.AMETHYST.currency());
        assertEquals(CrateKind.Currency.TOKEN, CrateKind.DRAGON.currency());
        assertEquals(CrateKind.Currency.KEY, CrateKind.DEFAULT.currency());
        assertEquals(CrateKind.Currency.SHARD, CrateKind.SHARD.currency());

        assertEquals("Amethyst Token", CrateKind.Currency.TOKEN.fullName(1));
        assertEquals("Amethyst Tokens", CrateKind.Currency.TOKEN.fullName(2));
    }

    @Test
    void theDefaultCrateCanBeStoodDownFromTheControlPanel() {
        try {
            CrateKind.defaultOpenSource(() -> false);
            assertFalse(CrateKind.DEFAULT.available(System.currentTimeMillis()));
            CrateKind.defaultOpenSource(() -> true);
            assertTrue(CrateKind.DEFAULT.available(System.currentTimeMillis()));
            // A null source must not close the crate; that would hide it on any startup
            // that wired the panel up late.
            CrateKind.defaultOpenSource(null);
            assertTrue(CrateKind.DEFAULT.available(System.currentTimeMillis()));
        } finally {
            CrateKind.defaultOpenSource(() -> true);
        }
    }

    @Test
    void aStackMintedBeforeTheRenameIsRewrittenSoItMergesWithNewOnes()
            throws IOException {
        String items = source("CrateItems.java");
        int refresh = items.indexOf("boolean refreshToken(ItemStack item) {");
        assertTrue(refresh > 0, "the refresh has moved");
        String body = items.substring(refresh, items.indexOf("\n    }", refresh));
        assertTrue(body.contains("applyTokenSkin(meta)"),
                "a refreshed stack must end up identical to a minted one, or it will not"
                        + " stack with them");
        assertTrue(body.contains("if (wanted.equals(current))"),
                "an already-current stack must be left alone rather than rewritten");

        String crates = source("CrateService.java");
        assertTrue(crates.contains("items.refreshToken(event.getItem().getItemStack())"),
                "a token picked up off the floor is the commonest way an old one surfaces");
        assertTrue(crates.contains("refreshTokens(event.getPlayer())"),
                "and joining sweeps the inventory and ender chest");
    }
}
