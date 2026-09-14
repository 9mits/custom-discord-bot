package bot.mgx.accessbridge;

import org.bukkit.inventory.ItemStack;

import java.util.Set;
import java.util.UUID;

/**
 * The one place the rest of the plugin tells Sentinel something happened.
 *
 * <p>Static so a reward factory or the economy store can report without every
 * constructor in the plugin gaining another parameter. Every call is a no-op until the
 * service is installed, and none of them can throw into the code that called them.
 */
final class SentinelHub {
    private static volatile SentinelService service;
    private static final Set<String> PLUMBING = Set.of(
            SentinelHub.class.getName(), CrateItems.class.getName(), CosmeticItems.class.getName(),
            EconomyStore.class.getName(), SentinelService.class.getName()
    );
    private static final StackWalker WALKER = StackWalker.getInstance();

    private SentinelHub() {
    }

    static void install(SentinelService installed) {
        service = installed;
    }

    /** A valuable created by plugin code. The recipient is not known here, so it credits the pool. */
    static void minted(SentinelEngine.Kind kind, long amount) {
        SentinelService current = service;
        if (current == null || amount <= 0L) return;
        try {
            current.minted(kind, amount, caller());
        } catch (RuntimeException ignored) {
            // Auditing must never break the reward that triggered it.
        }
    }

    /** Items handed to a known player that did not come from a factory: a claim, a return, a restore. */
    static void expect(UUID player, Iterable<ItemStack> items, String reason) {
        SentinelService current = service;
        if (current == null || player == null || items == null) return;
        try {
            current.expect(player, items, reason);
        } catch (RuntimeException ignored) {
            // Auditing must never break the delivery that triggered it.
        }
    }

    /** A balance change, with the code path that made it. */
    static void money(UUID player, long before, long after, String operation) {
        SentinelService current = service;
        if (current == null || player == null || before == after) return;
        try {
            current.money(player, before, after, operation, caller());
        } catch (RuntimeException ignored) {
            // Auditing must never break the payment that triggered it.
        }
    }

    /** The first stack frame outside the factories and stores, as {@code Class.method}. */
    static String caller() {
        return WALKER.walk(frames -> frames
                .filter(frame -> !PLUMBING.contains(frame.getClassName()))
                .findFirst()
                .map(frame -> {
                    String type = frame.getClassName();
                    int dot = type.lastIndexOf('.');
                    String simple = dot >= 0 ? type.substring(dot + 1) : type;
                    int inner = simple.indexOf('$');
                    if (inner >= 0) simple = simple.substring(0, inner);
                    return simple + "." + frame.getMethodName().replace("lambda$", "");
                })
                .orElse("unknown"));
    }
}
