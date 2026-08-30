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
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AmethystMobAssetsTest {
    private static final Path PACK = Path.of("..", "assets", "resourcepack", "src", "assets", "mgx");

    @Test
    void importedTexturesAreTheExactTwoApprovedFiles() throws Exception {
        Path skeleton = PACK.resolve("textures/entity/amethyst_skeleton.png");
        Path zombie = PACK.resolve("textures/entity/amethyst_zombie.png");

        assertEquals("8f5128db8272693c8b8d236cebea6e4317cd28676a0b7ea36d4abccaf22b38cc",
                sha256(skeleton));
        assertEquals("a7442c4f38a47711bf7a5483b49c06947ae31361f6c4f26a570f2f2cb42f80f0",
                sha256(zombie));
        assertEquals(64, ImageIO.read(skeleton.toFile()).getWidth());
        assertEquals(32, ImageIO.read(skeleton.toFile()).getHeight());
        assertEquals(64, ImageIO.read(zombie.toFile()).getWidth());
        assertEquals(64, ImageIO.read(zombie.toFile()).getHeight());
        assertTrue(Files.isRegularFile(PACK.resolve("models/entity/amethyst_skeleton.json")));
        assertTrue(Files.isRegularFile(PACK.resolve("models/entity/amethyst_zombie.json")));
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
    void variantsAreUnlabelledUnarmouredAndNotHighlighted() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/AmethystMobService.java"
        ));

        assertTrue(source.contains("entity.setInvisible(true)"));
        assertTrue(source.contains("removeLegacyFallback(entity)"));
        assertFalse(source.contains("setCustomNameVisible(true)"));
        assertFalse(source.contains("setGlowing(true)"));
        assertFalse(source.contains("equipFallback"));
        assertFalse(source.contains("Particle.END_ROD"));
    }

    @Test
    void displayModelsAlignWithTheMobAndUseVanillaBodyProportions() throws Exception {
        String skeleton = Files.readString(PACK.resolve("models/entity/amethyst_skeleton.json"));
        String zombie = Files.readString(PACK.resolve("models/entity/amethyst_zombie.json"));

        assertTrue(skeleton.contains("\"translation\": [0, 8, 0]"));
        assertTrue(zombie.contains("\"translation\": [0, 8, 0]"));
        assertTrue(skeleton.contains("\"from\": [4,12,6], \"to\": [12,24,10]"));
        assertTrue(zombie.contains("\"from\": [4,12,6], \"to\": [12,24,10]"));
        assertFalse(skeleton.contains("\"translation\": [0, -16, 0]"));
        assertFalse(zombie.contains("\"translation\": [0, -16, 0]"));
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        );
    }
}
