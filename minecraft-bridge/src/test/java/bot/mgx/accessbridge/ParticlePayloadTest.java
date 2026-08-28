package bot.mgx.accessbridge;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class ParticlePayloadTest {
    @Test
    void suppliesRequiredColourForFlashParticles() {
        assertEquals(Color.class, Particle.FLASH.getDataType());
        assertEquals(Color.WHITE, CosmeticEffectService.particleData(Particle.FLASH, null));
    }

    @Test
    void suppliesRequiredMotionDataForAnimatedParticles() {
        assertEquals(Float.class, Particle.DRAGON_BREATH.getDataType());
        assertEquals(1.0f, CosmeticEffectService.particleData(Particle.DRAGON_BREATH, null));
    }

    @Test
    void preservesExplicitParticleDataAndLeavesUntypedParticlesEmpty() {
        Color colour = Color.fromRGB(255, 179, 0);

        assertSame(colour, CosmeticEffectService.particleData(Particle.FLASH, colour));
        assertNull(CosmeticEffectService.particleData(Particle.END_ROD, null));
    }
}
