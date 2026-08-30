package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventBannerTest {
    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static Component airdropChat() {
        return EventBanner.chat("Mythic Amethyst Airdrop", NamedTextColor.LIGHT_PURPLE,
                "Overworld", 1234, 64, -567, "Claim it within", "30:00");
    }

    /** The complaint this answers: one sentence carrying four different facts. */
    @Test
    void theChatAnnouncementIsACardRatherThanASentence() {
        String[] lines = plain(airdropChat()).split("\n", -1);
        assertEquals(5, lines.length, "blank, title, location, call, blank");
        assertTrue(lines[0].isEmpty(), "opens on a blank line so it separates from chat");
        assertTrue(lines[4].isEmpty(), "and closes on one");

        assertEquals("  MYTHIC AMETHYST AIRDROP", lines[1]);
        assertEquals("  Overworld   X 1234  Y 64  Z -567", lines[2]);
        assertEquals("  Claim it within 30:00", lines[3]);
    }

    @Test
    void everyLineStaysShortEnoughNotToWrap() {
        for (String line : plain(airdropChat()).split("\n")) {
            assertTrue(line.length() <= 52,
                    "chat wraps past about 53 characters: \"" + line + "\" is " + line.length());
        }
    }

    /** Axes are labelled in chat, where a player is reading rather than glancing. */
    @Test
    void chatLabelsItsAxesAndNegativeCoordinatesSurvive() {
        String line = plain(airdropChat()).split("\n")[2];
        assertTrue(line.contains("X 1234"), line);
        assertTrue(line.contains("Y 64"), line);
        assertTrue(line.contains("Z -567"), line);
    }

    /**
     * The boss bar cannot wrap, so it gets the opposite treatment: no axis letters and no
     * spaced bullets, because every punctuation character is one not spent on a number.
     */
    @Test
    void theBossBarIsShortAndUsesNoBulletSeparators() {
        String bar = plain(EventBanner.bossBar("Mythic Airdrop", NamedTextColor.LIGHT_PURPLE,
                1234, 64, -567, null, "30:00"));

        assertEquals("MYTHIC AIRDROP  1234 64 -567  30:00", bar);
        assertFalse(bar.contains("•"), "bullets were the thing making it a long string");
        assertTrue(bar.length() < 40, "a bar this long still fits: " + bar.length());
    }

    @Test
    void theBossBarCarriesAnExtraFieldWhenThereIsOne() {
        String bar = plain(EventBanner.bossBar("Huge Amethyst Block", NamedTextColor.LIGHT_PURPLE,
                120, 70, -80, EventBanner.number(2750) + " HP", "29:45"));

        assertEquals("HUGE AMETHYST BLOCK  120 70 -80  2,750 HP  29:45", bar);
        assertTrue(bar.contains("2,750 HP"), "a bare 2750 is a smear at a glance");
    }

    /** The old bar was measurably longer for the same facts. */
    @Test
    void theNewBarIsShorterThanTheStringItReplaced() {
        String old = "HUGE AMETHYST BLOCK • X 120 • Y 70 • Z -80 • 2750 HP • 29:45";
        String now = plain(EventBanner.bossBar("Huge Amethyst Block", NamedTextColor.LIGHT_PURPLE,
                120, 70, -80, EventBanner.number(2750) + " HP", "29:45"));
        assertTrue(now.length() < old.length(),
                "was " + old.length() + ", now " + now.length());
    }

    @Test
    void numbersCarryThousandsSeparators() {
        assertEquals("2,750", EventBanner.number(2750L));
        assertEquals("0", EventBanner.number(0L));
        assertEquals("1,000,000", EventBanner.number(1_000_000L));
    }

    /** Colour, not punctuation, is what separates the fields now. */
    @Test
    void theFieldsAreSeparatedByColourRatherThanCharacters() {
        Component bar = EventBanner.bossBar("Mythic Airdrop", NamedTextColor.LIGHT_PURPLE,
                1, 2, 3, "9 HP", "01:00");
        assertEquals(NamedTextColor.LIGHT_PURPLE, bar.color(), "the title keeps the event colour");
        assertTrue(bar.children().size() >= 3, "position, detail and clock are their own runs");
    }
}
