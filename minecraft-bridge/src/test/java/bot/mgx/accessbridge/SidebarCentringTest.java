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

        // Space quantisation means we cannot hit dead centre; half a space is close enough.
        assertTrue(Math.abs(centre - board / 2) <= 2, "off-centre by " + (centre - board / 2) + "px");
    }

    @Test
    void textWiderThanTheBoardIsNotPadded() {
        assertEquals("a very wide line", SidebarText.centredToWidth("a very wide line", 4, false));
    }
}
