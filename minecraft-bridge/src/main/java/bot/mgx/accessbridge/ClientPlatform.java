package bot.mgx.accessbridge;

import java.util.Locale;

record ClientPlatform(String edition, String device) {
    static final ClientPlatform JAVA = new ClientPlatform("JAVA", "PC");

    static ClientPlatform bedrock(String deviceOs) {
        String normalized = deviceOs == null ? "UNKNOWN" : deviceOs.toUpperCase(Locale.ROOT);
        String device = switch (normalized) {
            case "GOOGLE", "IOS", "AMAZON", "WINDOWS_PHONE" -> "MOBILE";
            case "OSX", "UWP", "WIN32" -> "PC";
            case "PS4", "NX", "XBOX" -> "CONSOLE";
            case "GEARVR", "HOLOLENS" -> "VR";
            case "TVOS" -> "TV";
            default -> "OTHER";
        };
        return new ClientPlatform("BEDROCK", device);
    }
}
