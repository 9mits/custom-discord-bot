package bot.mgx.accessbridge;

import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AmethystMobAssetsTest {
    private static final Path VANILLA = Path.of(
            "..", "assets", "resourcepack", "src", "assets", "minecraft", "textures", "entity"
    );

    /**
     * The husk and stray skins are the mod art unmodified. They are the only zombie and
     * skeleton subtypes whose vanilla texture matches the mod's layout, which is what lets
     * the pack drop the art straight in instead of remapping it.
     */
    @Test
    void theHuskAndStraySkinsAreTheModArtByteForByte() throws Exception {
        Path zombie = VANILLA.resolve("zombie/husk.png");
        Path skeleton = VANILLA.resolve("skeleton/stray.png");

        assertEquals("a7442c4f38a47711bf7a5483b49c06947ae31361f6c4f26a570f2f2cb42f80f0",
                sha256(zombie));
        assertEquals("8f5128db8272693c8b8d236cebea6e4317cd28676a0b7ea36d4abccaf22b38cc",
                sha256(skeleton));
        assertEquals(64, ImageIO.read(zombie.toFile()).getWidth());
        assertEquals(64, ImageIO.read(zombie.toFile()).getHeight());
        assertEquals(64, ImageIO.read(skeleton.toFile()).getWidth());
        assertEquals(32, ImageIO.read(skeleton.toFile()).getHeight());
    }

    /** The stray's icy clothing layer would otherwise draw over the amethyst skin. */
    @Test
    void theStrayClothingLayerIsBlanked() throws Exception {
        var overlay = ImageIO.read(VANILLA.resolve("skeleton/stray_overlay.png").toFile());
        assertEquals(64, overlay.getWidth());
        assertEquals(32, overlay.getHeight());
        for (int x = 0; x < overlay.getWidth(); x++) {
            for (int y = 0; y < overlay.getHeight(); y++) {
                assertEquals(0, overlay.getRGB(x, y) >>> 24,
                        "stray_overlay is opaque at " + x + "," + y);
            }
        }
    }

    /**
     * The Amethyst Golem is a retextured iron golem, derived from the mod art by
     * import_amethyst_golem.py. Minecraft has no iron golem variant registry, so a pack
     * holds exactly one iron_golem.png and every iron golem wears this skin; only the
     * ones the airdrop guard deploys are actual amethyst mobs.
     */
    @Test
    void theGolemSkinIsTheDerivedAmethystOne() throws Exception {
        Path golem = VANILLA.resolve("iron_golem/iron_golem.png");

        assertEquals("9b4517e704a86e52f18202e7507a41c7b1dcc57dbd378e47122d32cac36310e0",
                sha256(golem));
        assertEquals(128, ImageIO.read(golem.toFile()).getWidth());
        assertEquals(128, ImageIO.read(golem.toFile()).getHeight());
    }

    /** The rarer the drop, the heavier the guard; the Mythic garrison is the top end. */
    @Test
    void theGarrisonGrowsWithRarity() {
        int previous = -1;
        for (AirdropCatalog.Rarity rarity : AirdropCatalog.Rarity.values()) {
            int total = AirdropGuardService.garrisonFor(rarity).total();
            assertTrue(total > previous, rarity + " must be guarded more heavily");
            previous = total;
        }
        assertEquals(0, AirdropGuardService.garrisonFor(
                AirdropCatalog.Rarity.COMMON).golems());
        AirdropGuardService.Garrison mythic =
                AirdropGuardService.garrisonFor(AirdropCatalog.Rarity.MYTHIC);
        assertEquals(15, mythic.zombies());
        assertEquals(15, mythic.skeletons());
        assertEquals(5, mythic.golems());
    }

    /** The tag above an amethyst mob is what tells it apart from a plain iron golem. */
    @Test
    void theVariantsCarryAVisibleNameTag() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/AmethystMobService.java"
        ));

        assertTrue(source.contains("setCustomNameVisible(true)"));
        assertFalse(source.contains("setCustomNameVisible(false)"));
    }

    @Test
    void onlyOrdinaryZombieAndSkeletonSpawnsAreEligible() {
        assertTrue(AmethystMobService.eligible(EntityType.ZOMBIE,
                CreatureSpawnEvent.SpawnReason.NATURAL));
        assertTrue(AmethystMobService.eligible(EntityType.SKELETON,
                CreatureSpawnEvent.SpawnReason.SPAWNER));
        assertFalse(AmethystMobService.eligible(EntityType.HUSK,
                CreatureSpawnEvent.SpawnReason.NATURAL));
        assertFalse(AmethystMobService.eligible(EntityType.ZOMBIE,
                CreatureSpawnEvent.SpawnReason.CUSTOM));
    }

    @Test
    void ordinarySpawnsBecomeTheRetexturedSubtypeAndBack() {
        assertEquals(EntityType.HUSK, AmethystMobService.variantOf(EntityType.ZOMBIE));
        assertEquals(EntityType.STRAY, AmethystMobService.variantOf(EntityType.SKELETON));
        assertNull(AmethystMobService.variantOf(EntityType.DROWNED));

        assertEquals(EntityType.ZOMBIE, AmethystMobService.reclaimed(EntityType.HUSK));
        assertEquals(EntityType.SKELETON, AmethystMobService.reclaimed(EntityType.STRAY));
        assertNull(AmethystMobService.reclaimed(EntityType.ZOMBIE));
    }

    /**
     * The mobs must be real husks and strays rendered by the vanilla mob renderer. The
     * moment any of this becomes a display prop again it stops animating, which is the
     * bug this replaced.
     */
    @Test
    void theVariantsAreRealMobsRatherThanDisplayProps() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/AmethystMobService.java"
        ));

        assertFalse(source.contains("import org.bukkit.entity.ItemDisplay;"));
        assertFalse(source.contains("setItemModel"));
        assertFalse(source.contains("setInvisible(true)"));
        assertFalse(source.contains("runTaskTimer"));
        assertTrue(source.contains("setShouldBurnInDay(true)"));
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        );
    }
}
