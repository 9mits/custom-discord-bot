package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The odds tag's colour movement, and the ambience around the auras that carry one. */
final class CosmeticSoundAndTagTest {
    private static CosmeticCatalog.Definition auraWith(CosmeticCatalog.OddsMotion motion) {
        return CosmeticCatalog.all().stream()
                .filter(CosmeticCatalog.Definition::nameplateWorthy)
                .filter(definition -> definition.oddsFamily().motion() == motion)
                .findFirst()
                .orElseGet(() -> CosmeticCatalog.hiddenAmethystRewards().stream()
                        .filter(definition -> definition.oddsFamily().motion() == motion)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no aura uses " + motion)));
    }

    private static List<TextColor> colours(CosmeticCatalog.Definition aura, long frame) {
        List<TextColor> colours = new ArrayList<>();
        for (Component child : CosmeticEffectService.rarityNameplate(aura, frame).children()) {
            colours.add(child.color());
        }
        return colours;
    }

    private static double difference(TextColor from, TextColor to) {
        return Math.abs(from.red() - to.red())
                + Math.abs(from.green() - to.green())
                + Math.abs(from.blue() - to.blue());
    }

    /**
     * The tag used to snap between whole palette entries, so a four-colour palette was
     * four hard edges marching along the text and the eye read the steps rather than
     * the movement. Every motion now samples the palette continuously.
     */
    @Test
    void everyMotionBlendsRatherThanSnapping() {
        for (CosmeticCatalog.OddsMotion motion : CosmeticCatalog.OddsMotion.values()) {
            CosmeticCatalog.Definition aura = auraWith(motion);
            Set<TextColor> distinct = new HashSet<>();
            for (long frame = 0; frame < 240; frame++) {
                distinct.addAll(colours(aura, frame));
            }
            // A palette has at most seven entries; blending produces far more.
            assertTrue(distinct.size() > 60, motion + " produced only " + distinct.size());
        }
    }

    /**
     * No visible jump from one frame to the next.
     *
     * <p>This is what "smooth" actually means here: the largest step any character
     * takes between consecutive frames stays small enough that the movement reads as
     * a drift rather than a switch.
     */
    @Test
    void consecutiveFramesStayCloseTogether() {
        for (CosmeticCatalog.OddsMotion motion : CosmeticCatalog.OddsMotion.values()) {
            CosmeticCatalog.Definition aura = auraWith(motion);
            double worst = 0d;
            for (long frame = 0; frame < 400; frame++) {
                List<TextColor> before = colours(aura, frame);
                List<TextColor> after = colours(aura, frame + 1);
                assertEquals(before.size(), after.size());
                for (int index = 0; index < before.size(); index++) {
                    worst = Math.max(worst, difference(before.get(index), after.get(index)));
                }
            }
            // A hard palette switch is typically 300+ across the three channels.
            assertTrue(worst < 120d, motion + " jumped by " + worst);
        }
    }

    /**
     * It still has to move, or it is a static line rather than a smooth one.
     *
     * <p>Checked across a whole cycle rather than between two chosen frames: a
     * shimmer rests between sweeps, so any particular pair of frames may legitimately
     * match.
     */
    @Test
    void theTagStillAnimates() {
        for (CosmeticCatalog.OddsMotion motion : CosmeticCatalog.OddsMotion.values()) {
            CosmeticCatalog.Definition aura = auraWith(motion);
            List<TextColor> first = colours(aura, 0);
            boolean moved = false;
            for (long frame = 1; frame <= 200 && !moved; frame++) {
                moved = !colours(aura, frame).equals(first);
            }
            assertTrue(moved, motion + " never changes");
        }
    }

    /** Blending in linear light: the midpoint of a ramp must not sag darker than its ends. */
    @Test
    void blendingDoesNotDarkenTheMiddle() {
        CosmeticCatalog.Definition aura = auraWith(CosmeticCatalog.OddsMotion.SCROLL);
        int[] palette = aura.oddsFamily().colours();
        int darkestEnd = 255 * 3;
        for (int colour : palette) {
            TextColor entry = TextColor.color(colour);
            darkestEnd = Math.min(darkestEnd, entry.red() + entry.green() + entry.blue());
        }
        int darkestSeen = 255 * 3;
        for (long frame = 0; frame < 200; frame++) {
            for (TextColor colour : colours(aura, frame)) {
                darkestSeen = Math.min(darkestSeen, colour.red() + colour.green() + colour.blue());
            }
        }
        // Naive sRGB averaging drops well below the darkest palette entry.
        assertTrue(darkestSeen >= darkestEnd - 30, "blend darkened to " + darkestSeen);
    }

    @Test
    void theTagStillReadsTheOddsItIsThereToReport() {
        CosmeticCatalog.Definition aura = auraWith(CosmeticCatalog.OddsMotion.SCROLL);
        String text = ((TextComponent) CosmeticEffectService.rarityNameplate(aura, 7L))
                .children().stream()
                .map(child -> ((TextComponent) child).content())
                .reduce("", String::concat);

        assertTrue(text.contains("1 IN "), text);
        assertTrue(text.contains(String.format(java.util.Locale.ROOT, "%,d", aura.oneIn())), text);
    }

    /**
     * One rule, not two: an aura rare enough to announce its odds is rare enough to
     * be heard, and nothing else makes a sound at all.
     */
    @Test
    void everyTaggedAuraHasAnAmbienceAndNothingElseDoes() {
        List<CosmeticCatalog.Definition> everything = new ArrayList<>(CosmeticCatalog.all());
        everything.addAll(CosmeticCatalog.hiddenAmethystRewards());
        int tagged = 0;
        for (CosmeticCatalog.Definition definition : everything) {
            String sound = CosmeticEffectService.auraAmbience(definition);
            if (definition.nameplateWorthy()) {
                tagged++;
                assertNotNull(sound, definition.id());
            } else {
                assertNull(sound, definition.id());
            }
        }
        assertTrue(tagged >= 4, "expected several tagged auras, found " + tagged);
    }

    /**
     * You have to be close to hear a cosmetic.
     *
     * <p>An aura is visible at 48 blocks, which is right for something you look at and
     * wrong for something you listen to: at that range one player's soundtrack covered
     * most of a base, and it said where its owner was standing long before they were
     * in sight.
     */
    @Test
    void cosmeticsAreHeardMuchCloserThanTheyAreSeen() {
        // Both are configurable now; these are the values they ship with.
        double hearing = Math.sqrt(CosmeticEffectService.hearingDistanceSquared());
        double sight = Math.sqrt(CosmeticEffectService.viewDistanceSquared());

        assertEquals(16d, hearing);
        assertEquals(48d, sight);
        assertTrue(hearing * 2d < sight, "hearing range must be well inside sight range");
    }

    @Test
    void eachRarityIsADifferentSound() {
        CosmeticCatalog.Definition secret = CosmeticCatalog
                .find(CosmeticCatalog.HIDDEN_AMETHYST_COSMETIC_ID).orElseThrow();
        CosmeticCatalog.Definition exotic = CosmeticCatalog.all().stream()
                .filter(CosmeticCatalog.Definition::nameplateWorthy)
                .filter(CosmeticCatalog.Definition::secret)
                .findFirst().orElseThrow();
        CosmeticCatalog.Definition mythic = CosmeticCatalog.all().stream()
                .filter(CosmeticCatalog.Definition::nameplateWorthy)
                .filter(definition -> definition.rarityDisplay().equals("Mythic"))
                .findFirst().orElseThrow();

        Set<String> sounds = new HashSet<>(List.of(
                CosmeticEffectService.auraAmbience(secret),
                CosmeticEffectService.auraAmbience(exotic),
                CosmeticEffectService.auraAmbience(mythic)
        ));
        assertEquals(3, sounds.size());
    }
}
