package bot.mgx.accessbridge;

import java.util.Locale;

/** Pure display-name selection for auction records and search. */
final class AuctionItemName {
    private AuctionItemName() {
    }

    static String resolve(String materialName, String customDisplayName) {
        if (customDisplayName != null && !customDisplayName.isBlank()) {
            return customDisplayName.strip();
        }
        if (materialName == null || materialName.isBlank()) {
            return "Item";
        }
        String[] parts = materialName.strip().toLowerCase(Locale.ROOT).split("_");
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return text.isEmpty() ? "Item" : text.toString();
    }
}
