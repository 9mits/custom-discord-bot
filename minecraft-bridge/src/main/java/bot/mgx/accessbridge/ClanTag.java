package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * The one clan tag every surface shares: chat, clan chat, the player list and
 * nametags. Level stars and Clan Battle medals are rendered together here so a
 * clan is badged identically everywhere it is named, and so the player-list
 * column measurement cannot drift from what is actually drawn.
 */
final class ClanTag {
    /**
     * Medals reuse the level star's approach: one glyph, told apart by colour and
     * a count. A second shape would have to survive Bedrock's font, and the star
     * has already proven colour is the part that carries.
     */
    private static final String MEDAL = "◆";
    private static final int GOLD = 0xFFD35A;
    private static final int SILVER = 0xC9D6E4;
    private static final int BRONZE = 0xCD7F32;

    private ClanTag() {
    }

    /** The bracketed name, its level star, and any Clan Battle medals. */
    static Component of(ClanStore.ClanView clan, ClanBattleStore.Badges badges) {
        Component tag = MenuText.sprite(ClanIcon.resolve(clan.icon()).sprite())
                .append(Component.text(" "))
                .append(Component.text(
                "[" + clan.name() + "] ", TextColor.color(clan.themeColor()), TextDecoration.BOLD
        ));
        if (clan.level() > 0) {
            tag = tag.append(Component.text(
                    ClanLevel.badge(clan.level()),
                    TextColor.color(ClanLevel.badgeColor(clan.level())),
                    TextDecoration.BOLD
            )).append(Component.text(" "));
        }
        return tag.append(medals(badges));
    }

    /** Exactly what {@link #of} draws, unstyled, for player-list width maths. */
    static String plain(ClanStore.ClanView clan, ClanBattleStore.Badges badges) {
        StringBuilder text = new StringBuilder("[").append(clan.name()).append("] ");
        if (clan.level() > 0) {
            text.append(ClanLevel.badge(clan.level())).append(' ');
        }
        return text.append(plainMedals(badges)).toString();
    }

    /** An inline object sprite is 8px wide, followed by the normal 4px space. */
    static int iconWidth() {
        return 12;
    }

    static Component medals(ClanBattleStore.Badges badges) {
        Component rendered = Component.empty();
        rendered = append(rendered, badges.gold(), GOLD);
        rendered = append(rendered, badges.silver(), SILVER);
        rendered = append(rendered, badges.bronze(), BRONZE);
        return rendered;
    }

    static String plainMedals(ClanBattleStore.Badges badges) {
        return glyph(badges.gold()) + glyph(badges.silver()) + glyph(badges.bronze());
    }

    private static Component append(Component target, int count, int colour) {
        if (count <= 0) {
            return target;
        }
        return target.append(Component.text(
                        MEDAL + (count == 1 ? "" : "x" + count),
                        TextColor.color(colour), TextDecoration.BOLD
                ))
                .append(Component.text(" "));
    }

    private static String glyph(int count) {
        return count <= 0 ? "" : MEDAL + (count == 1 ? "" : "x" + count) + " ";
    }
}
