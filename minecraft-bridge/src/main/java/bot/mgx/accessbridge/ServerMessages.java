package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * Player-facing text the owner can rewrite while the server runs.
 *
 * <p>Every one of these used to be a Java string literal, so changing "Block broken!" to
 * anything else meant a build, a merge and a restart. They are ordinary configuration
 * values now, read at the moment they are shown, which is what makes an edit in the
 * control panel land on the next message rather than the next restart.
 *
 * <p>Templates are MiniMessage, so colour and emphasis survive being edited as text:
 * {@code <gold><bold>Block broken!</bold></gold> You received <keys> keys.} Placeholders
 * are supplied by the caller and inserted as plain text, never parsed — a player name
 * containing something that looks like a tag cannot inject formatting or a click action
 * into a broadcast everybody sees.
 */
final class ServerMessages {
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final GameVariableStore variables;

    ServerMessages(GameVariableStore variables) {
        this.variables = variables;
    }

    /**
     * Renders one message.
     *
     * @param key            the {@code messages.*} variable holding the template
     * @param placeholders   alternating name and value, e.g. {@code "keys", "12"}
     */
    Component render(String key, String... placeholders) {
        String template = variables.string(key);
        if (template == null || template.isBlank()) {
            return Component.empty();
        }
        List<TagResolver> resolvers = new ArrayList<>();
        for (int index = 0; index + 1 < placeholders.length; index += 2) {
            // unparsed(), not parsed(): a value is content, never markup.
            resolvers.add(Placeholder.unparsed(
                    placeholders[index], String.valueOf(placeholders[index + 1])));
        }
        try {
            return MINI.deserialize(template, TagResolver.resolver(resolvers));
        } catch (RuntimeException malformed) {
            // A half-typed tag must not silence the message or break the event that was
            // trying to send it; showing the raw template is louder than showing nothing.
            return Component.text(template);
        }
    }

    /** Whether the owner has emptied this message, which is how one is switched off. */
    boolean isSilenced(String key) {
        String template = variables.string(key);
        return template == null || template.isBlank();
    }
}
