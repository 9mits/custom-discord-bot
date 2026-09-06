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
     * A cosmetic must never put a living entity in the world, least of all a boss.
     *
     * <p>The escort was a real {@code EnderDragon} shrunk with the generic scale
     * attribute. That attribute has no effect on it — the Ender Dragon has its own boss
     * renderer rather than the one every scalable mob uses — so what a player actually
     * got was a full-size Ender Dragon parked over their base. A display entity has no
     * AI, no health, no hitbox and no boss bar, so the worst a bug here can leave is a
     * floating model.
     */
    @Test
    void theEscortIsAModelAndNeverALivingEntity() throws Exception {
        String escort = source("MiniDragonEscort.java");
        assertTrue(escort.contains("ItemDisplay.class"),
                "the escort must be a display entity");
        assertTrue(!escort.contains("EnderDragon.class"),
                "a cosmetic must never spawn an Ender Dragon");
        assertTrue(!escort.contains("Attribute.SCALE"),
                "the scale attribute does not shrink an Ender Dragon; do not rely on it");
        assertTrue(!escort.contains("getBossBar"),
                "a display entity has no boss bar and must not reach for one");
        assertTrue(method(escort, "private ItemDisplay spawn(Player owner)")
                        .contains("catch (RuntimeException"),
                "a failed escort spawn must degrade rather than propagate");
    }

    /**
     * The full-size dragons an earlier build left in the world have to be cleared, and
     * their chunks are not necessarily loaded when the plugin starts.
     */
    @Test
    void orphanedEscortsAreSweptAsTheirChunksArrive() throws Exception {
        String escort = source("MiniDragonEscort.java");
        assertTrue(escort.contains("EntitiesLoadEvent"),
                "an orphan in an unloaded chunk must be swept when it loads");
        assertTrue(method(escort, "void start()").contains("sweep("),
                "start must sweep what is already loaded");
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
