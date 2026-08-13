package bot.mgx.accessbridge;

record PlayerProfile(
        int level,
        int extraHearts,
        boolean elite,
        String rankGroup,
        String rankLabel,
        int rankColour,
        int rankWeight,
        boolean booster
) {
    static final PlayerProfile NONE = new PlayerProfile(0, 0, false, "", "", 0, 0, false);

    PlayerProfile {
        level = Math.max(0, Math.min(level, 50));
        extraHearts = Math.max(0, Math.min(extraHearts, 5));
        elite = elite && level >= 50;
        rankGroup = rankGroup == null ? "" : rankGroup.trim();
        rankLabel = rankLabel == null ? "" : rankLabel.trim();
        rankColour = Math.max(0, Math.min(rankColour, 0xFFFFFF));
        rankWeight = Math.max(0, Math.min(rankWeight, 9_999));
    }

    PlayerProfile(int level, int extraHearts, boolean elite) {
        this(level, extraHearts, elite, "", "", 0, 0, false);
    }

    /** Total extra hearts, including the one a booster earns on top of level rewards. */
    int totalExtraHearts() {
        return extraHearts + (booster ? 1 : 0);
    }

    /**
     * Damage multiplier. Elite and boost stack additively, so a level 50 booster
     * hits for 25% more rather than 26.5%.
     */
    double damageMultiplier() {
        return 1.0
                + (elite ? PlayerPerkService.ELITE_DAMAGE_BONUS : 0.0)
                + (booster ? PlayerPerkService.BOOSTER_DAMAGE_BONUS : 0.0);
    }

    boolean hasRank() {
        return !rankGroup.isEmpty();
    }

    /** A rank is only displayable when the bot also sent a label to render. */
    boolean hasRankLabel() {
        return !rankLabel.isEmpty();
    }
}
