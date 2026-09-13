package bot.mgx.accessbridge;

/** The small set of choices that turns one PvP category into a specific match. */
record PvpMatchSetup(
        PvpMode family,
        int teamSize,
        int targetPlayers,
        Access access,
        boolean fill
) {
    enum Access {
        PUBLIC("Public matchmaking"),
        INVITE_ONLY("Invite only");

        private final String display;

        Access(String display) {
            this.display = display;
        }

        String display() {
            return display;
        }
    }

    PvpMatchSetup {
        family = category(family);
        access = access == null ? Access.PUBLIC : access;
        if (family == PvpMode.FFA) {
            teamSize = 1;
            targetPlayers = Math.max(2, Math.min(12, targetPlayers));
            fill = true;
        } else {
            int minimum = family == PvpMode.CLAN_BATTLE ? 2 : 1;
            teamSize = Math.max(minimum, Math.min(3, teamSize));
            targetPlayers = teamSize * 2;
        }
    }

    static PvpMatchSetup defaults(PvpMode selected) {
        PvpMode family = category(selected);
        int teamSize = switch (selected) {
            case DOUBLES -> 2;
            case TRIPLES -> 3;
            case CLAN_BATTLE -> 2;
            default -> 1;
        };
        return new PvpMatchSetup(family, teamSize,
                family == PvpMode.FFA ? 4 : teamSize * 2, Access.PUBLIC, true);
    }

    static PvpMode category(PvpMode mode) {
        if (mode == PvpMode.CLAN_BATTLE) return PvpMode.CLAN_BATTLE;
        if (mode == PvpMode.FFA) return PvpMode.FFA;
        return PvpMode.RANKED_DUEL;
    }

    PvpMode mode() {
        if (family != PvpMode.RANKED_DUEL) return family;
        return switch (teamSize) {
            case 2 -> PvpMode.DOUBLES;
            case 3 -> PvpMode.TRIPLES;
            default -> PvpMode.RANKED_DUEL;
        };
    }

    boolean freeForAll() {
        return family == PvpMode.FFA;
    }

    boolean clan() {
        return family == PvpMode.CLAN_BATTLE;
    }

    int requiredPlayers() {
        return freeForAll() ? targetPlayers : teamSize * 2;
    }

    String sizeLabel() {
        if (freeForAll()) return targetPlayers + " players";
        return teamSize + "v" + teamSize;
    }

    String matchLabel() {
        if (clan()) return sizeLabel() + " Clan Battle";
        if (freeForAll()) return targetPlayers + " Player Last Standing";
        return "Ranked " + sizeLabel();
    }

    PvpMatchSetup nextSize() {
        if (freeForAll()) {
            return new PvpMatchSetup(family, 1,
                    targetPlayers >= 12 ? 2 : targetPlayers + 1, access, true);
        }
        int minimum = clan() ? 2 : 1;
        int next = teamSize >= 3 ? minimum : teamSize + 1;
        return new PvpMatchSetup(family, next, next * 2, access, fill);
    }

    PvpMatchSetup toggleAccess() {
        return new PvpMatchSetup(family, teamSize, targetPlayers,
                access == Access.PUBLIC ? Access.INVITE_ONLY : Access.PUBLIC, fill);
    }

    PvpMatchSetup toggleFill() {
        return new PvpMatchSetup(family, teamSize, targetPlayers, access, !fill);
    }
}
