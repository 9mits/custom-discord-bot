package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Locale;

/**
 * How a world event announces itself, in chat and on the boss bar.
 *
 * <p>Free of Bukkit so both shapes can be asserted on. Adventure is on the test classpath,
 * so these are real components in a test rather than strings that look like them.
 *
 * <p>Two different problems, deliberately solved two different ways.
 *
 * <p><strong>Chat</strong> had become a sentence: the block event ran the name, the
 * coordinates, the world and the instruction into one line that wrapped twice and read as
 * prose. Nobody parses prose mid-fight. It is a card now — a title line, a location line
 * whose axes are labelled, and one line saying what to do about it — because the three
 * things a player wants are what, where, and how long, and each deserves its own row.
 *
 * <p><strong>The boss bar</strong> cannot wrap, so the fix there is the opposite: fewer
 * characters, not more rows. It used to separate every field with a spaced bullet, which
 * is three characters of punctuation per join and turned six short values into one long
 * string. Colour separates the fields now and the coordinates lose their axis letters, so
 * the same information reads as columns and takes roughly half the width. The axes stay
 * labelled in chat, where there is room and where a player is reading rather than glancing.
 */
final class EventBanner {
    /** Grey enough to recede, light enough to read against the bar's own shading. */
    private static final TextColor LABEL = NamedTextColor.GRAY;
    private static final TextColor VALUE = NamedTextColor.WHITE;
    private static final TextColor AXIS = NamedTextColor.DARK_GRAY;

    private EventBanner() {
    }

    /**
     * The chat card a world event opens with.
     *
     * @param title    the event's name, already in its own colour
     * @param colour   the colour that name is drawn in
     * @param world    "Overworld" or "Nether"
     * @param call     what the player should do about it, without the countdown
     * @param countdown the time it is said in, already formatted
     */
    static Component chat(
            String title, TextColor colour, String world,
            int x, int y, int z, String call, String countdown
    ) {
        return Component.text()
                .append(Component.newline())
                .append(Component.text("  " + title.toUpperCase(Locale.ROOT), colour,
                        TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.text("  " + world + "   ", LABEL))
                .append(axis("X", x))
                .append(Component.text("  "))
                .append(axis("Y", y))
                .append(Component.text("  "))
                .append(axis("Z", z))
                .append(Component.newline())
                .append(Component.text("  " + call + " ", LABEL))
                .append(Component.text(countdown, VALUE, TextDecoration.BOLD))
                .append(Component.newline())
                .build();
    }

    private static Component axis(String name, int value) {
        return Component.text(name + " ", AXIS)
                .append(Component.text(Integer.toString(value), VALUE));
    }

    /**
     * The boss bar line. Fields are separated by colour and two spaces rather than by a
     * spaced bullet, and the coordinates carry no axis letters — on one unwrappable line
     * every character spent on punctuation is a character not spent on a number.
     *
     * @param detail an extra field between the position and the clock, or null for none
     */
    static Component bossBar(
            String title, TextColor colour, int x, int y, int z,
            String detail, String countdown
    ) {
        Component line = Component.text(title.toUpperCase(Locale.ROOT), colour, TextDecoration.BOLD)
                .append(Component.text("  " + x + " " + y + " " + z, LABEL));
        if (detail != null && !detail.isBlank()) {
            line = line.append(Component.text("  " + detail, LABEL));
        }
        return line.append(Component.text("  " + countdown, VALUE, TextDecoration.BOLD));
    }

    /** Thousands separators, because an unpunctuated five-digit HP figure is a smear. */
    static String number(long value) {
        return String.format(Locale.US, "%,d", value);
    }
}
