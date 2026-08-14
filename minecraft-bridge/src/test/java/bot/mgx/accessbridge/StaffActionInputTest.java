package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StaffActionInputTest {
    @Test
    void plainJavaUsernamesAreAccepted() {
        assertEquals("MinimumOrc", StaffActionInput.sanitizeUsername("MinimumOrc"));
    }

    @Test
    void dotPrefixedBedrockUsernamesAreAccepted() {
        assertEquals(".agentclanmanage", StaffActionInput.sanitizeUsername(".agentclanmanage"));
    }

    @Test
    void whitespaceIsTrimmedBeforeValidating() {
        assertEquals("mits", StaffActionInput.sanitizeUsername("  mits  "));
    }

    @Test
    void namesLongerThanSixteenCharactersAreRejected() {
        assertThrows(
                StaffActionException.class,
                () -> StaffActionInput.sanitizeUsername("waytoolongusernamehere")
        );
    }

    @Test
    void aCommandDisguisedAsAUsernameIsRejected() {
        assertThrows(
                StaffActionException.class,
                () -> StaffActionInput.sanitizeUsername("mits; op mits")
        );
        assertThrows(
                StaffActionException.class,
                () -> StaffActionInput.sanitizeUsername("mits\nop mits")
        );
    }

    @Test
    void anEmptyUsernameIsRejected() {
        assertThrows(StaffActionException.class, () -> StaffActionInput.sanitizeUsername(""));
        assertThrows(StaffActionException.class, () -> StaffActionInput.sanitizeUsername(null));
    }

    @Test
    void freeTextStripsNewlinesRatherThanRejecting() {
        assertEquals("Spamming chat", StaffActionInput.sanitizeFreeText("Spamming\nchat", 200));
    }

    @Test
    void freeTextDropsALeadingSlash() {
        assertEquals("kick everyone", StaffActionInput.sanitizeFreeText("/kick everyone", 200));
    }

    @Test
    void freeTextIsCappedAtTheGivenLimit() {
        String result = StaffActionInput.sanitizeFreeText("x".repeat(500), 10);

        assertEquals(10, result.length());
    }

    @Test
    void nullFreeTextBecomesEmpty() {
        assertEquals("", StaffActionInput.sanitizeFreeText(null, 200));
    }
}
