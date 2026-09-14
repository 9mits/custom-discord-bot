package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SentinelEngineTest {
    private static final UUID PLAYER = UUID.randomUUID();
    private static final long MIN = 60_000L;
    private static final SentinelEngine.Config CONFIG = new SentinelEngine.Config(60 * MIN,
            Map.of(SentinelEngine.Kind.SHARD, 16L, SentinelEngine.Kind.DIAMOND, 512L,
                    SentinelEngine.Kind.MYSTERY_KEY, 128L), 10);

    private static EnumMap<SentinelEngine.Kind, Long> shards(long amount) {
        EnumMap<SentinelEngine.Kind, Long> holdings = new EnumMap<>(SentinelEngine.Kind.class);
        holdings.put(SentinelEngine.Kind.SHARD, amount);
        return holdings;
    }

    @Test
    void aThousandShardsFromNowhereIsCritical() {
        SentinelEngine engine = new SentinelEngine();
        assertTrue(engine.census(PLAYER, "Orc", shards(2), 0, CONFIG).isEmpty(), "first sighting is a baseline");
        List<SentinelEngine.Finding> findings = engine.census(PLAYER, "Orc", shards(1_403), MIN, CONFIG);
        assertEquals(1, findings.size());
        assertEquals("unexplained_gain", findings.get(0).rule());
        assertEquals(SentinelEngine.Severity.CRITICAL, findings.get(0).severity());
        assertTrue(findings.get(0).evidence().get(1).contains("1,401"));
        assertTrue(engine.census(PLAYER, "Orc", shards(1_403), 2 * MIN, CONFIG).isEmpty(),
                "the new level becomes the baseline, so it is reported once");
    }

    @Test
    void mintsPickupsAndClaimsExplainGains() {
        SentinelEngine engine = new SentinelEngine();
        engine.census(PLAYER, "Player", shards(0), 0, CONFIG);
        engine.credit(null, SentinelEngine.Kind.SHARD, 5, "plugin mint (CrateService.open)", MIN, 2 * MIN);
        engine.credit(PLAYER, SentinelEngine.Kind.SHARD, 30, "a pickup of Friend's drop", MIN, 15 * MIN);
        assertTrue(engine.census(PLAYER, "Player", shards(35), MIN + 1, CONFIG).isEmpty());
    }

    @Test
    void itemsTakenAwayAndReturnedNeverNeedExplaining() {
        SentinelEngine engine = new SentinelEngine();
        engine.census(PLAYER, "Player", shards(500), 0, CONFIG);
        engine.census(PLAYER, "Player", shards(0), MIN, CONFIG);
        assertTrue(engine.census(PLAYER, "Player", shards(500), 20 * MIN, CONFIG).isEmpty(),
                "a PvP match or a trip to a chest returns to the old peak");
    }

    @Test
    void aRejoinIsComparedWithTheLastCheckBeforeLeaving() {
        SentinelEngine engine = new SentinelEngine();
        engine.census(PLAYER, "Player", shards(10), 0, CONFIG);
        engine.quit(PLAYER);
        List<SentinelEngine.Finding> findings = engine.census(PLAYER, "Player", shards(400), 5 * MIN, CONFIG);
        assertEquals(1, findings.size(), "items that appeared while offline still need a source");
    }

    @Test
    void expiredCreditsExplainNothingAndVanillaNeverGoesAboveMedium() {
        SentinelEngine engine = new SentinelEngine();
        EnumMap<SentinelEngine.Kind, Long> diamonds = new EnumMap<>(SentinelEngine.Kind.class);
        diamonds.put(SentinelEngine.Kind.DIAMOND, 0L);
        engine.census(PLAYER, "Player", diamonds, 0, CONFIG);
        engine.credit(PLAYER, SentinelEngine.Kind.DIAMOND, 10_000, "mining", 0, MIN);
        diamonds.put(SentinelEngine.Kind.DIAMOND, 9_000L);
        List<SentinelEngine.Finding> findings = engine.census(PLAYER, "Player", diamonds, 2 * MIN, CONFIG);
        assertEquals(SentinelEngine.Severity.MEDIUM, findings.get(0).severity());
    }

    @Test
    void largeLegitimateGainsAreStillSurfaced() {
        SentinelEngine engine = new SentinelEngine();
        engine.census(PLAYER, "Admin", shards(0), 0, CONFIG);
        engine.credit(null, SentinelEngine.Kind.SHARD, 400, "plugin mint (AdminCommandService.give)", MIN, 2 * MIN);
        List<SentinelEngine.Finding> findings = engine.census(PLAYER, "Admin", shards(400), MIN + 1, CONFIG);
        assertEquals("large_gain", findings.get(0).rule());
        assertTrue(findings.get(0).evidence().get(1).contains("AdminCommandService"));
    }

    @Test
    void anOldHoardIsFlaggedOnFirstSight() {
        SentinelEngine engine = new SentinelEngine();
        List<SentinelEngine.Finding> findings = engine.census(PLAYER, "Orc", shards(1_401), 0, CONFIG);
        assertEquals("large_holdings", findings.get(0).rule());
    }

    @Test
    void riskDecaysAndRepeatsAreCounted() {
        SentinelEngine.RiskLedger ledger = new SentinelEngine.RiskLedger(60 * MIN);
        ledger.add(PLAYER, SentinelEngine.Severity.CRITICAL, 0);
        assertEquals(15d, ledger.score(PLAYER, 60 * MIN), 0.01);
        SentinelEngine.Deduper deduper = new SentinelEngine.Deduper();
        assertEquals(1, deduper.admit("k", 0, 10 * MIN));
        assertEquals(0, deduper.admit("k", MIN, 10 * MIN));
        assertEquals(0, deduper.admit("k", 2 * MIN, 10 * MIN));
        assertEquals(3, deduper.admit("k", 11 * MIN, 10 * MIN), "the next report carries the repeats");
    }
}
