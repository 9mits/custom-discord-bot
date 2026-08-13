package bot.mgx.accessbridge;

record PlayerProfile(
        int level,
        int extraHearts,
        boolean elite,
        String rankGroup,
        String rankLabel,
        int rankColour
) {
    static final PlayerProfile NONE = new PlayerProfile(0, 0, false, "", "", 0);

    PlayerProfile {
        level = Math.max(0, Math.min(level, 50));
        extraHearts = Math.max(0, Math.min(extraHearts, 5));
        elite = elite && level >= 50;
        rankGroup = rankGroup == null ? "" : rankGroup.trim();
        rankLabel = rankLabel == null ? "" : rankLabel.trim();
        rankColour = Math.max(0, Math.min(rankColour, 0xFFFFFF));
    }

    PlayerProfile(int level, int extraHearts, boolean elite) {
        this(level, extraHearts, elite, "", "", 0);
    }

    boolean hasRank() {
        return !rankGroup.isEmpty();
    }

    /** A rank is only displayable when the bot also sent a label to render. */
    boolean hasRankLabel() {
        return !rankLabel.isEmpty();
    }
}
