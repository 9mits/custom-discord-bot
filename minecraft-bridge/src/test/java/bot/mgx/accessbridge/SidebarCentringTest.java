package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidebarCentringTest {
    @Test
    void boldTextIsWiderThanPlain() {
        assertTrue(SidebarText.textWidth("STATS", true) > SidebarText.textWidth("STATS", false));
    }

    @Test
    void narrowGlyphsCostLessThanWideOnes() {
        assertTrue(SidebarText.textWidth("iii", false) < SidebarText.textWidth("MMM", false));
    }

    @Test
    void centringLandsTextNearTheMiddle() {
        int board = SidebarText.textWidth(" » Power: ", false)
                + SidebarText.textWidth("+5% damage", true);
        String padded = SidebarText.centredToWidth("SMP X", board, true);

        int leading = padded.length() - padded.stripLeading().length();
        int start = leading * 4;
        int centre = start + SidebarText.textWidth("SMP X", true) / 2;

        // Padding moves in whole spaces, so dead centre is unreachable. The contract is
        // "never past centre, and never more than one space short of it".
        int offset = centre - board / 2;
        assertTrue(offset <= 0, "padded past centre by " + offset + "px");
        assertTrue(offset >= -SidebarText.SPACE_WIDTH, "more than a space short: " + offset + "px");
    }

    @Test
    void textWiderThanTheBoardIsNotPadded() {
        assertEquals("a very wide line", SidebarText.centredToWidth("a very wide line", 4, false));
    }

    @Test
    void centringErrsLeftRatherThanRight() {
        // Sidebar rows carry a small left inset, so overshooting right is visible
        // while a space short of centre reads fine.
        int board = SidebarText.textWidth(" » Power: ", false)
                + SidebarText.textWidth("+15% damage", true);
        String padded = SidebarText.centredToWidth("PROFILE", board, true);

        int leading = padded.length() - padded.stripLeading().length();
        int centre = leading * SidebarText.SPACE_WIDTH
                + SidebarText.textWidth("PROFILE", true) / 2;

        assertTrue(centre <= board / 2, "padded past centre by " + (centre - board / 2) + "px");
    }

    @Test
    void tabColumnsPadToOnePixelTarget() {
        String shortValue = SidebarText.padRightToWidth("JAVA", 80, false);
        String longValue = SidebarText.padRightToWidth("BEDROCK · ANDROID", 80, false);

        assertTrue(SidebarText.textWidth(shortValue, false) >= 80);
        assertTrue(SidebarText.textWidth(shortValue, false) < 80 + SidebarText.SPACE_WIDTH);
        assertEquals("BEDROCK · ANDROID", longValue);
    }
}
