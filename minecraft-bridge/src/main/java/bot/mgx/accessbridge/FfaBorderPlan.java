package bot.mgx.accessbridge;

/**
 * How big a Last Standing border is for the number of players who actually entered.
 *
 * <p>Two players in a ring built for twelve spend the match looking for each other;
 * twelve in a ring built for two are over in seconds. The start and the final zone both
 * grow per player, and the shrink always takes the same number of steps, so a full
 * lobby closes in as long as a small one does.
 */
record FfaBorderPlan(double initial, double fin, double step) {
    static FfaBorderPlan of(
            int players, int base, int perPlayer, int maximum,
            double finalBase, double finalPerPlayer, int steps
    ) {
        int counted = Math.max(2, players);
        double initial = Math.min(Math.max(base, maximum), base + (double) perPlayer * counted);
        double fin = Math.min(initial, finalBase + finalPerPlayer * counted);
        double step = Math.max(1d, (initial - fin) / Math.max(1, steps));
        return new FfaBorderPlan(initial, fin, step);
    }

    static FfaBorderPlan of(int players, GameVariableStore variables) {
        return of(players,
                variables.integer("pvp-competitive.ffa-arena-diameter"),
                variables.integer("pvp-competitive.ffa-diameter-per-player"),
                variables.integer("pvp-competitive.ffa-maximum-diameter"),
                variables.decimal("pvp-competitive.ffa-minimum-border"),
                variables.decimal("pvp-competitive.ffa-final-border-per-player"),
                variables.integer("pvp-competitive.ffa-shrink-steps"));
    }
}
