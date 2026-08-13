package bot.mgx.accessbridge;

/**
 * Text measurement for the sidebar.
 *
 * <p>Minecraft's default font is proportional, so centring by character count drifts
 * badly — a bold "M" is roughly three spaces wide. These are the vanilla glyph
 * advances in pixels; bold adds one pixel per character.
 *
 * <p>Deliberately free of Bukkit and Adventure types so it stays unit-testable:
 * those are {@code compileOnly} and are absent at test runtime.
 */
final class SidebarText {
    static final int SPACE_WIDTH = 4;

    private SidebarText() {
    }

    static int glyphWidth(char character) {
        return switch (character) {
            case '!', ',', '.', ':', ';', 'i', '|', '\'' -> 2;
            case 'l', '`' -> 3;
            case ' ', '"', '(', ')', '*', 'I', '[', ']', 't', '{', '}' -> 4;
            case '<', '>', 'f', 'k', 'v' -> 5;
            default -> 6;
        };
    }

    static int textWidth(String text, boolean bold) {
        int width = 0;
        for (char character : text.toCharArray()) {
            width += glyphWidth(character) + (bold ? 1 : 0);
        }
        return width;
    }

    /** Pads {@code text} with spaces so it sits centred inside {@code targetWidth} pixels. */
    static String centredToWidth(String text, int targetWidth, boolean bold) {
        int deficit = targetWidth - textWidth(text, bold);
        return " ".repeat(Math.max(0, Math.round(deficit / 2f / SPACE_WIDTH))) + text;
    }
}
