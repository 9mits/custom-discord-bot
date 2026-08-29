package bot.mgx.accessbridge;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ActivityLogServiceTest {
    private static ActivityLogService serviceFor(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        return new ActivityLogService(null, config.getConfigurationSection("activity-log"));
    }

    /**
     * A deny list, not an allow list.
     *
     * <p>An allow list means a topic added in a later build goes silent on every
     * server that already has a config file, and nobody notices a log that simply
     * never mentions the new thing.
     */
    @Test
    void everyTopicReportsUntilOneIsSwitchedOff() {
        ActivityLogService service = serviceFor("""
                activity-log:
                  topics:
                    mining: false
                """);

        assertFalse(service.reports(ServerEvent.CATEGORY_MINING));
        assertTrue(service.reports(ServerEvent.CATEGORY_COMBAT));
        assertTrue(service.reports(ServerEvent.CATEGORY_CRATE));
        assertTrue(service.reports(ServerEvent.CATEGORY_CLAN));
        // A topic this build has never heard of is reported, not dropped.
        assertTrue(service.reports("invented_later"));
        assertTrue(service.reports(null));
    }

    @Test
    void aServerWithNoActivityLogSectionReportsEverything() {
        ActivityLogService service = serviceFor("debug: false\n");

        for (String topic : List.of(
                ServerEvent.CATEGORY_COMBAT, ServerEvent.CATEGORY_MINING,
                ServerEvent.CATEGORY_CRATE, ServerEvent.CATEGORY_PROGRESSION,
                ServerEvent.CATEGORY_ECONOMY, ServerEvent.CATEGORY_ADMIN
        )) {
            assertTrue(service.reports(topic), topic);
        }
    }

    @Test
    void topicsAreMatchedWithoutRegardToCase() {
        ActivityLogService service = serviceFor("""
                activity-log:
                  topics:
                    COMBAT: false
                """);

        assertFalse(service.reports("combat"));
        assertFalse(service.reports("Combat"));
    }

    /** The shipped config must not silence anything it also documents. */
    @Test
    void theShippedConfigLeavesEveryTopicOn() {
        String config;
        try {
            config = java.nio.file.Files.readString(
                    java.nio.file.Path.of("src/main/resources/config.yml")
            );
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
        ActivityLogService service = serviceFor(config);
        for (String topic : List.of(
                ServerEvent.CATEGORY_COMBAT, ServerEvent.CATEGORY_MINING,
                ServerEvent.CATEGORY_CRATE, ServerEvent.CATEGORY_PROGRESSION,
                ServerEvent.CATEGORY_COSMETIC, ServerEvent.CATEGORY_CLAN,
                ServerEvent.CATEGORY_ECONOMY, ServerEvent.CATEGORY_STAFF,
                ServerEvent.CATEGORY_ADMIN
        )) {
            assertTrue(service.reports(topic), topic);
        }
    }

    /**
     * A tally is trimmed from the tail.
     *
     * <p>An event carries ten details and one of them is the total, so a player who
     * mined nine kinds of ore reports all nine and a player who mined more reports
     * the biggest nine. Dropping the headline instead would make the line useless.
     */
    @Test
    void theTallyKeepsItsBiggestRowsAndBreaksTiesByName() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("Coal Ore", 400L);
        counts.put("Iron Ore", 400L);
        counts.put("Gold Ore", 12L);
        counts.put("Copper Ore", 900L);

        assertEquals(
                List.of("Copper Ore", "Coal Ore", "Iron Ore", "Gold Ore"),
                ActivityLogService.ranked(counts, 9).stream().map(Map.Entry::getKey).toList()
        );
        assertEquals(
                List.of("Copper Ore", "Coal Ore"),
                ActivityLogService.ranked(counts, 2).stream().map(Map.Entry::getKey).toList()
        );
        assertEquals(1_712L, ActivityLogService.total(counts));
        assertEquals(List.of(), ActivityLogService.ranked(counts, 0));
    }

    @Test
    void theDetailBudgetLeavesRoomForTheTotal() {
        assertEquals(9, ActivityLogService.DETAIL_ROWS);
    }

    /**
     * Two blocks anywhere in the world must not share a key.
     *
     * <p>A collision means a mined ore going silently unreported because some other
     * block was placed there once, which is the kind of gap nobody would ever chase.
     */
    @Test
    void everyReachableBlockHasItsOwnKey() {
        Set<Long> seen = new HashSet<>();
        for (int x : new int[]{-100_000, -4096, -1, 0, 1, 4096, 100_000}) {
            for (int z : new int[]{-100_000, -4096, -1, 0, 1, 4096, 100_000}) {
                for (int y : new int[]{-64, -1, 0, 63, 319, 320}) {
                    assertTrue(seen.add(ActivityLogService.key(x, y, z)), x + "," + y + "," + z);
                }
            }
        }
        assertEquals(7 * 7 * 6, seen.size());
        // Neighbours differ, including across the axis each field is packed into.
        assertFalse(ActivityLogService.key(0, 0, 0) == ActivityLogService.key(0, 0, 1));
        assertFalse(ActivityLogService.key(0, 0, 0) == ActivityLogService.key(1, 0, 0));
        assertFalse(ActivityLogService.key(0, 0, 0) == ActivityLogService.key(0, 1, 0));
    }
}
