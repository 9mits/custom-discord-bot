package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class SentinelCommandsTest {
    private static SentinelEngine.Severity severity(String line) {
        SentinelCommands.Grade grade = SentinelCommands.grade(line);
        return grade == null ? null : grade.severity();
    }

    @Test
    void theMostDangerousCommandsAreCriticalWhoeverRunsThem() {
        assertEquals(SentinelEngine.Severity.CRITICAL, severity("/op Steve"));
        assertEquals(SentinelEngine.Severity.CRITICAL, severity("lp user Steve permission set * true"));
        assertEquals(SentinelEngine.Severity.CRITICAL, severity("/lp user Steve parent add owner"));
        assertEquals(SentinelEngine.Severity.CRITICAL,
                severity("/give Steve amethyst_shard[custom_data={PublicBukkitValues:{}}] 64"));
        assertEquals(SentinelEngine.Severity.CRITICAL, severity("/minecraft:op Steve"));
    }

    @Test
    void powerfulButOrdinaryAdminCommandsAreHigh() {
        assertEquals(SentinelEngine.Severity.HIGH, severity("/gmc"));
        assertEquals(SentinelEngine.Severity.HIGH, severity("/gamemode creative Steve"));
        assertEquals(SentinelEngine.Severity.HIGH, severity("/give Steve diamond 64"));
        assertEquals(SentinelEngine.Severity.HIGH, severity("/eco give Steve 1000000"));
        assertEquals(SentinelEngine.Severity.HIGH, severity("/mgxadmin give Steve shard 64"));
        assertEquals(SentinelEngine.Severity.HIGH, severity("/data modify entity @s Inventory"));
    }

    @Test
    void everydayCommandsAreLowOrIgnored() {
        assertEquals(SentinelEngine.Severity.LOW, severity("/gamemode survival"));
        assertEquals(SentinelEngine.Severity.LOW, severity("/lp user Steve info"));
        assertEquals(SentinelEngine.Severity.LOW, severity("/mgxadmin help"));
        assertEquals(SentinelEngine.Severity.LOW, severity("/ec"));
        assertEquals(SentinelEngine.Severity.MEDIUM, severity("/ec Steve"));
        assertEquals(SentinelEngine.Severity.MEDIUM, severity("//set stone"));
        assertNull(severity("/spawn"));
        assertNull(severity("/pvp"));
        assertNull(severity("/msg Steve hi"));
    }
}
