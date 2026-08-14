package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClanIconTest {
    @Test
    void keepsADirectImageAddress() {
        assertEquals(
                "https://example.com/shield.png",
                ClanIcon.normalize("  https://example.com/shield.png  ")
        );
    }

    @Test
    void acceptsEveryFormatDiscordCanRender() {
        for (String extension : new String[]{"png", "jpg", "jpeg", "gif", "webp"}) {
            String url = "https://example.com/shield." + extension;
            assertEquals(url, ClanIcon.normalize(url));
        }
    }

    @Test
    void looksPastTheQueryStringForTheExtension() {
        // Signed CDN addresses carry their expiry and signature after the '?', so
        // testing the whole string for an extension would reject every one of them.
        String url = "https://cdn.example.com/a/shield.png?ex=6a80ab37&hm=abc";
        assertEquals(url, ClanIcon.normalize(url));
    }

    @Test
    void rejectsAddressesThatAreNotImages() {
        assertThrows(
                ClanStore.ClanException.class,
                () -> ClanIcon.normalize("https://example.com/gallery")
        );
    }

    @Test
    void rejectsInsecureAndEmptyAddresses() {
        assertThrows(
                ClanStore.ClanException.class,
                () -> ClanIcon.normalize("http://example.com/shield.png")
        );
        assertThrows(ClanStore.ClanException.class, () -> ClanIcon.normalize("  "));
        assertThrows(ClanStore.ClanException.class, () -> ClanIcon.normalize(null));
    }

    @Test
    void rejectsAddressesWithSpacesOrRunawayLength() {
        assertThrows(
                ClanStore.ClanException.class,
                () -> ClanIcon.normalize("https://example.com/my shield.png")
        );
        assertThrows(
                ClanStore.ClanException.class,
                () -> ClanIcon.normalize("https://example.com/" + "a".repeat(ClanIcon.MAX_LENGTH) + ".png")
        );
    }

    @Test
    void spotsDiscordLinksThatWillExpire() {
        // These stop resolving after roughly a day, which would silently strip a
        // clan of the icon it thought it had set.
        assertTrue(ClanIcon.isExpiringDiscordLink(
                "https://media.discordapp.net/attachments/1/2/shield.png?ex=6a80ab37&hm=abc"
        ));
        assertTrue(ClanIcon.isExpiringDiscordLink(
                "https://cdn.discordapp.com/attachments/1/2/shield.png?ex=6a80ab37"
        ));
        assertFalse(ClanIcon.isExpiringDiscordLink("https://example.com/shield.png"));
        assertFalse(ClanIcon.isExpiringDiscordLink(null));
    }
}
