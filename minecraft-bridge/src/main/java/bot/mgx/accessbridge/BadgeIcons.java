package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Private-use glyphs shared by Java's bitmap font and Bedrock's E8 glyph page. */
final class BadgeIcons {
    static final String PVP_BRONZE = "\uE800";
    static final String PVP_SILVER = "\uE801";
    static final String PVP_GOLD = "\uE802";
    static final String PVP_PLATINUM = "\uE803";
    static final String PVP_DIAMOND = "\uE804";
    static final String PVP_ELITE = "\uE805";
    static final String PVP_CHAMPION = "\uE806";
    static final String PVP_UNREAL = "\uE807";
    static final String CLAN_BATTLE_GOLD = "\uE808";
    static final String CLAN_BATTLE_SILVER = "\uE809";
    static final String CLAN_BATTLE_BRONZE = "\uE80A";
    static final String CLAN_LEVEL_1 = "\uE80B";
    static final String CLAN_LEVEL_2 = "\uE80C";
    static final String CLAN_LEVEL_3 = "\uE80D";
    static final String CLAN_LEVEL_4 = "\uE80E";
    static final String CLAN_LEVEL_5 = "\uE80F";

    private BadgeIcons() {
    }

    static Component glyph(String glyph) {
        // White preserves the source texture's colours instead of tinting its pixels.
        return Component.text(glyph, NamedTextColor.WHITE)
                .decoration(TextDecoration.BOLD, false)
                .decoration(TextDecoration.ITALIC, false);
    }

    static String pvp(PvpRank rank) {
        return switch (rank.tier()) {
            case "Bronze" -> PVP_BRONZE;
            case "Silver" -> PVP_SILVER;
            case "Gold" -> PVP_GOLD;
            case "Platinum" -> PVP_PLATINUM;
            case "Diamond" -> PVP_DIAMOND;
            case "Elite" -> PVP_ELITE;
            case "Champion" -> PVP_CHAMPION;
            default -> PVP_UNREAL;
        };
    }

    static String clanLevel(int level) {
        return switch (level) {
            case 1 -> CLAN_LEVEL_1;
            case 2 -> CLAN_LEVEL_2;
            case 3 -> CLAN_LEVEL_3;
            case 4 -> CLAN_LEVEL_4;
            case 5 -> CLAN_LEVEL_5;
            default -> "";
        };
    }
}
