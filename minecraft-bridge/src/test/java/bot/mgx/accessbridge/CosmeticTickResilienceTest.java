package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cosmetic tick draws for everybody in one loop, so a throw inside it is not one
 * broken cosmetic — it is every cosmetic after it on the server, silently.
 *
 * <p>This is what a null {@code getBossBar()} on a costume Ender Dragon actually did:
 * the wearer of the Amethyst Dragon Ascendant heard the music and saw nothing, because
 * the music is synced one line before the drawing. It read as a missing effect rather
 * than a crash, which is why it survived a release.
 *
 * <p>Asserted against the source: exercising the real loop needs a running server.
 */
final class CosmeticTickResilienceTest {
    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/bot/mgx/accessbridge/" + name));
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start > 0, "could not find " + signature);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            if (source.charAt(i) == '{') {
                depth++;
            } else if (source.charAt(i) == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced braces after " + signature);
    }

    @Test
    void everyPlayersCosmeticsAreRenderedInsideAFaultBoundary() throws Exception {
        String tick = method(source("CosmeticEffectService.java"), "private void tick()");
        int loop = tick.indexOf("for (Player player : plugin.getServer().getOnlinePlayers())");
        assertTrue(loop > 0, "the per-player render loop moved");
        String body = tick.substring(loop);
        assertTrue(body.contains("try {"),
                "the per-player render loop must not let one player's effect throw");
        assertTrue(body.contains("catch (RuntimeException"),
                "the boundary has to catch what a Bukkit call actually throws");
    }

    /**
     * A costume entity is decoration, and decoration is never allowed to be the reason
     * an aura stops drawing.
     */
    @Test
    void theEscortNeverDereferencesABossBarItWasNotGiven() throws Exception {
        String escort = source("MiniDragonEscort.java");
        assertTrue(escort.contains("BossBar bar = dragon.getBossBar();"),
                "the boss bar must be held before it is used");
        assertTrue(escort.contains("if (bar != null) {"),
                "getBossBar() is null outside the End and must be checked");
        assertTrue(!escort.contains("dragon.getBossBar().set")
                        && !escort.contains("dragon.getBossBar().remove"),
                "the boss bar must never be dereferenced inline");
        assertTrue(method(escort, "private EnderDragon spawn(Player owner)")
                        .contains("catch (RuntimeException"),
                "a failed escort spawn must degrade rather than propagate");
    }

    /**
     * Two cosmetics share the GENUINE_SECRET tier and only the first can be found by it,
     * so the Dragon's Secret has to be reachable by name or it cannot be tested at all.
     */
    @Test
    void theDragonSecretRevealCanBeSelectedOnItsOwn() {
        CrateCatalog.Reward dragon = CrateCatalog.dragonSecretExample().orElseThrow();
        assertNotNull(dragon.cosmeticId());
        assertTrue(CosmeticCatalog.DRAGON_SECRET_COSMETIC_ID.equals(dragon.cosmeticId()));
        assertTrue(dragon.revealTier() == CrateCatalog.RevealTier.GENUINE_SECRET);

        CrateCatalog.Reward byTier =
                CrateCatalog.revealExample(CrateCatalog.RevealTier.GENUINE_SECRET).orElseThrow();
        assertTrue(!byTier.id().equals(dragon.id()),
                "if the tier lookup already returned the Dragon this test is meaningless");

        String admin = "";
        try {
            admin = source("AdminCommandService.java");
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        assertTrue(admin.contains("\"dragonsecret\""),
                "/mgxadmin testcrate must expose the Dragon's Secret");
        assertTrue(admin.contains("CrateCatalog.dragonSecretExample()"),
                "the command must select it by name, not by tier");
    }
}
