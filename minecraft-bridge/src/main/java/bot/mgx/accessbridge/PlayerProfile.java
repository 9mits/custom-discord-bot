package bot.mgx.accessbridge;

record PlayerProfile(int level, int extraHearts, boolean elite) {
    static final PlayerProfile NONE = new PlayerProfile(0, 0, false);

    PlayerProfile {
        level = Math.max(0, Math.min(level, 50));
        extraHearts = Math.max(0, Math.min(extraHearts, 5));
        elite = elite && level >= 50;
    }
}
