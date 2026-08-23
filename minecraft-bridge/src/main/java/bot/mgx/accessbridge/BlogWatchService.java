package bot.mgx.accessbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Watches the dev blog and raises the in-game NEW UPDATE banner when a new post
 * appears.
 *
 * <p>The banner tells players to go and read the blog, so the blog decides when
 * it shows. Earlier this was tied to the plugin version, which meant a release
 * with nothing new to read still sent everybody to a page that had not changed.
 *
 * <p>The site publishes {@code /latest.json} naming its newest post. Polling one
 * small file beats scraping the index HTML, which would break every time the
 * page layout moved.
 */
final class BlogWatchService {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final MGXAccessBridge plugin;
    private final UpdateNoticeStore store;
    private final UpdateNoticeService notices;
    private final String manifestUrl;
    private final long pollTicks;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private BukkitTask task;
    /** Kept so a site that is down for an hour does not fill the log with it. */
    private boolean warned;

    BlogWatchService(
            MGXAccessBridge plugin,
            UpdateNoticeStore store,
            UpdateNoticeService notices,
            String manifestUrl,
            long pollMinutes
    ) {
        this.plugin = plugin;
        this.store = store;
        this.notices = notices;
        this.manifestUrl = manifestUrl;
        this.pollTicks = Math.max(1L, pollMinutes) * 60L * 20L;
    }

    void start() {
        if (manifestUrl == null || manifestUrl.isBlank()) {
            return;
        }
        // The first check is delayed: on a fresh boot the world is still loading
        // and nobody is online to read a banner anyway.
        task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, this::poll, 20L * 30L, pollTicks
        );
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Runs off the main thread; the publish hops back on. */
    private void poll() {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(manifestUrl))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "MGXAccessBridge")
                    .GET()
                    .build();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("blog-latest-url is not a valid URL; not watching the blog.");
            stop();
            return;
        }
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(this::handle)
                .exceptionally(failure -> {
                    if (!warned) {
                        warned = true;
                        plugin.getLogger().info("Could not reach the dev blog; will keep trying.");
                    }
                    return null;
                });
    }

    private void handle(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            return;
        }
        String slug;
        try {
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            slug = root.has("slug") ? root.get("slug").getAsString() : "";
        } catch (RuntimeException exception) {
            return;
        }
        if (slug.isBlank()) {
            return;
        }
        warned = false;
        // Back to the main thread: publishing touches every online player.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                if (store.publishIfPostChanged(slug)) {
                    notices.showTo(plugin.getServer().getOnlinePlayers());
                    plugin.getLogger().info(
                            "The dev blog published '" + slug + "'; showed the NEW UPDATE notice."
                    );
                }
            } catch (RuntimeException failure) {
                plugin.getLogger().warning("Could not show the update notice: "
                        + failure.getMessage());
            }
        });
    }
}
