package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * The clan tag shared by chat, the player list and nametags. TAB and chat carry
 * the level mark; the tighter overhead variant omits it. Clan Battle badges stay
 * on both, and the player-list width is measured from the same full renderer.
 */
final class ClanTag {
    private ClanTag() {
    }

    /** The bracketed name, its level mark, and any Clan Battle badges. */
    static Component of(ClanStore.ClanView clan, ClanBattleStore.Badges badges) {
        return render(clan, badges, true);
    }

    /** The tighter overhead variant leaves the level badge to the TAB list. */
    static Component overhead(ClanStore.ClanView clan, ClanBattleStore.Badges badges) {
        return render(clan, badges, false);
    }

    /**
     * The tag without its atlas sprite — and without the space that separated it, so the
     * row still measures as {@link #plain} says it does.
     */
    static Component textOnly(ClanStore.ClanView clan, ClanBattleStore.Badges badges) {
        return render(clan, badges, true, false);
    }

    private static Component render(
            ClanStore.ClanView clan, ClanBattleStore.Badges badges, boolean includeLevel
    ) {
        return render(clan, badges, includeLevel, true);
    }

    private static Component render(
            ClanStore.ClanView clan, ClanBattleStore.Badges badges, boolean includeLevel, boolean icon
    ) {
        Component tag = (icon
                ? MenuText.sprite(ClanIcon.resolve(clan.icon()).sprite()).append(Component.text(" "))
                : Component.empty())
                .append(Component.text(
                "[" + clan.name() + "] ",
                net.kyori.adventure.text.format.TextColor.color(clan.themeColor()),
                TextDecoration.BOLD
        ));
        if (includeLevel && clan.level() > 0) {
            tag = tag.append(BadgeIcons.glyph(ClanLevel.badge(clan.level())))
                    .append(Component.text(" "));
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

    static Component medals(ClanBattleStore.Badges badges) {
        Component rendered = Component.empty();
        rendered = append(rendered, badges.gold(), BadgeIcons.CLAN_BATTLE_GOLD);
        rendered = append(rendered, badges.silver(), BadgeIcons.CLAN_BATTLE_SILVER);
        rendered = append(rendered, badges.bronze(), BadgeIcons.CLAN_BATTLE_BRONZE);
        return rendered;
    }

    static String plainMedals(ClanBattleStore.Badges badges) {
        return glyph(badges.gold(), BadgeIcons.CLAN_BATTLE_GOLD)
                + glyph(badges.silver(), BadgeIcons.CLAN_BATTLE_SILVER)
                + glyph(badges.bronze(), BadgeIcons.CLAN_BATTLE_BRONZE);
    }

    private static Component append(Component target, int count, String glyph) {
        if (count <= 0) {
            return target;
        }
        return target.append(BadgeIcons.glyph(glyph))
                .append(Component.text(" "));
    }

    private static String glyph(int count, String glyph) {
        return count <= 0 ? "" : glyph + " ";
    }
}
