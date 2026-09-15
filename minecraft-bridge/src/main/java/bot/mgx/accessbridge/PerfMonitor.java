package bot.mgx.accessbridge;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * What the plugin's own background work costs, in the log where it can be read later.
 *
 * <p>The production server has no console anyone can reach and no API, so a profiler
 * cannot be started when the server feels slow. The log can always be fetched. Every
 * repeating task is wrapped by {@link #track}, which adds two clock reads per run, and
 * once per report interval one line records tick time, heap, garbage collection and the
 * tasks that spent the most main-thread time. A single run slow enough to cost a visible
 * stutter is logged as it happens, at most once per task per five minutes.
 *
 * <p>Deliberately not a profiler. It answers "is it us, and which part" cheaply enough
 * to leave on, and says nothing about Paper, other plugins or the world.
 */
final class PerfMonitor implements Listener {
    private static final Map<String, Stat> TASKS = new ConcurrentHashMap<>();
    private static final Map<String, LongSupplier> GAUGES = new ConcurrentHashMap<>();
    private static final long SLOW_WARNING_INTERVAL_NANOS = 5L * 60L * 1_000_000_000L;
    private static volatile long slowTaskNanos = 50L * 1_000_000L;
    private static volatile Logger logger;

    /** Accumulated cost of one named task since the last report. */
    static final class Stat {
        private final String name;
        private final AtomicLong runs = new AtomicLong();
        private final AtomicLong nanos = new AtomicLong();
        private final AtomicLong maxNanos = new AtomicLong();
        private final AtomicLong failures = new AtomicLong();
        private final AtomicLong totalRuns = new AtomicLong();
        private volatile long lastSlowWarning;

        private Stat(String name) {
            this.name = name;
        }

        void record(long elapsed) {
            runs.incrementAndGet();
            totalRuns.incrementAndGet();
            nanos.addAndGet(elapsed);
            maxNanos.accumulateAndGet(elapsed, Math::max);
            if (elapsed >= slowTaskNanos) {
                long now = System.nanoTime();
                Logger log = logger;
                if (log != null && now - lastSlowWarning >= SLOW_WARNING_INTERVAL_NANOS) {
                    lastSlowWarning = now;
                    log.warning(String.format(Locale.ROOT,
                            "[Perf] %s took %.1f ms in one run", name, elapsed / 1e6));
                }
            }
        }
    }

    /** One report row: a task's name and what it cost over the window. */
    record Row(String name, long runs, long nanos, long maxNanos, long failures) {
    }

    private final MGXAccessBridge plugin;
    private BukkitTask reporter;
    private long windowStartNanos = System.nanoTime();
    private long ticks;
    private double tickMillisTotal;
    private double tickMillisMax;
    private long overlongTicks;
    private long gcCountBefore;
    private long gcMillisBefore;

    PerfMonitor(MGXAccessBridge plugin) {
        this.plugin = plugin;
    }

    /** Wraps a repeating task so its cost is counted under {@code name}. */
    static Runnable track(String name, Runnable task) {
        Stat stat = TASKS.computeIfAbsent(name, Stat::new);
        return () -> {
            long start = System.nanoTime();
            try {
                task.run();
            } catch (RuntimeException | Error failure) {
                stat.failures.incrementAndGet();
                throw failure;
            } finally {
                stat.record(System.nanoTime() - start);
            }
        };
    }

    /** Records work that is not a scheduled task, such as one expensive listener path. */
    static void record(String name, long startNanos) {
        TASKS.computeIfAbsent(name, Stat::new).record(System.nanoTime() - startNanos);
    }

    /** A size worth watching, such as a queue or cache, read only when a report is made. */
    static void gauge(String name, IntSupplier size) {
        GAUGES.put(name, size::getAsInt);
    }

    static void gaugeLong(String name, LongSupplier size) {
        GAUGES.put(name, size);
    }

    void start() {
        logger = plugin.getLogger();
        GAUGES.put("players", () -> plugin.getServer().getOnlinePlayers().size());
        long[] gc = gcTotals();
        gcCountBefore = gc[0];
        gcMillisBefore = gc[1];
        // Checked each minute, so changing the interval needs no restart.
        reporter = plugin.getServer().getScheduler().runTaskTimer(plugin, this::maybeReport, 1_200L, 1_200L);
    }

    void stop() {
        if (reporter != null) {
            reporter.cancel();
            reporter = null;
        }
        logger = null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        double millis = event.getTickDuration();
        ticks++;
        tickMillisTotal += millis;
        if (millis > tickMillisMax) {
            tickMillisMax = millis;
        }
        if (millis > 50d) {
            overlongTicks++;
        }
    }

    private void maybeReport() {
        GameVariableStore variables = plugin.gameVariables();
        if (variables != null) {
            slowTaskNanos = Math.max(1L, variables.integer("performance.slow-task-ms")) * 1_000_000L;
        }
        long minutes = variables == null ? 15L : variables.integer("performance.report-minutes");
        if (minutes <= 0L) {
            return;
        }
        long elapsed = System.nanoTime() - windowStartNanos;
        if (elapsed < minutes * 60L * 1_000_000_000L - 30L * 1_000_000_000L) {
            return;
        }
        plugin.getLogger().info(report(elapsed));
    }

    /** Builds the current window's line and starts a new window. Main thread. */
    String report(long elapsedNanos) {
        List<Row> rows = drain();
        double seconds = Math.max(1d, elapsedNanos / 1e9);
        StringBuilder line = new StringBuilder("[Perf] ")
                .append(Math.round(seconds / 60d)).append("m");
        if (ticks > 0) {
            line.append(String.format(Locale.ROOT, " | MSPT avg %.1f max %.0f, %d ticks over 50ms",
                    tickMillisTotal / ticks, tickMillisMax, overlongTicks));
        }
        line.append(String.format(Locale.ROOT, " | TPS %.2f", Math.min(20d, plugin.getServer().getTPS()[0])));
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        line.append(" | heap ").append(megabytes(heap.getUsed()))
                .append("/").append(megabytes(heap.getCommitted()))
                .append(" max ").append(megabytes(heap.getMax()));
        long retained = oldGenerationAfterCollection();
        if (retained >= 0L) {
            line.append(" old-gen after GC ").append(megabytes(retained));
        }
        long[] gc = gcTotals();
        line.append(String.format(Locale.ROOT, " | GC %d in %.1fs",
                gc[0] - gcCountBefore, (gc[1] - gcMillisBefore) / 1000d));
        gcCountBefore = gc[0];
        gcMillisBefore = gc[1];
        rows.sort(Comparator.comparingLong(Row::nanos).reversed());
        line.append(" | top tasks:");
        int shown = 0;
        long totalNanos = 0L;
        for (Row row : rows) {
            totalNanos += row.nanos();
        }
        for (Row row : rows) {
            if (shown++ == 6 || row.runs() == 0) {
                break;
            }
            line.append(String.format(Locale.ROOT, " %s %.2fms/s (avg %.2f max %.1f%s)",
                    row.name(), row.nanos() / 1e6 / seconds,
                    row.nanos() / 1e6 / row.runs(), row.maxNanos() / 1e6,
                    row.failures() > 0 ? ", " + row.failures() + " failed" : ""));
        }
        line.append(String.format(Locale.ROOT, " | all tracked %.2fms/s", totalNanos / 1e6 / seconds));
        if (!GAUGES.isEmpty()) {
            line.append(" | sizes:");
            GAUGES.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                try {
                    line.append(' ').append(entry.getKey()).append('=').append(entry.getValue().getAsLong());
                } catch (RuntimeException ignored) {
                    line.append(' ').append(entry.getKey()).append("=?");
                }
            });
        }
        windowStartNanos = System.nanoTime();
        ticks = 0L;
        tickMillisTotal = 0d;
        tickMillisMax = 0d;
        overlongTicks = 0L;
        return line.toString();
    }

    /** The window's rows, most expensive first, resetting each counter. */
    static List<Row> drain() {
        List<Row> rows = new ArrayList<>();
        for (Stat stat : TASKS.values()) {
            rows.add(new Row(stat.name, stat.runs.getAndSet(0L), stat.nanos.getAndSet(0L),
                    stat.maxNanos.getAndSet(0L), stat.failures.getAndSet(0L)));
        }
        return rows;
    }

    /** Lifetime totals without resetting the window, for an operator asking right now. */
    static List<Row> snapshot() {
        List<Row> rows = new ArrayList<>();
        for (Stat stat : TASKS.values()) {
            rows.add(new Row(stat.name, stat.runs.get(), stat.nanos.get(), stat.maxNanos.get(),
                    stat.failures.get()));
        }
        rows.sort(Comparator.comparingLong(Row::nanos).reversed());
        return rows;
    }

    private static String megabytes(long bytes) {
        return bytes < 0L ? "?" : (bytes / (1024L * 1024L)) + "M";
    }

    /**
     * What the old generation still held after its last collection: the closest cheap
     * reading of how much memory is actually live, as opposed to how much the JVM has
     * been allowed to claim.
     */
    private static long oldGenerationAfterCollection() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            String name = pool.getName();
            if (name.contains("Old Gen") || name.contains("Tenured")) {
                MemoryUsage usage = pool.getCollectionUsage();
                return usage == null ? -1L : usage.getUsed();
            }
        }
        return -1L;
    }

    private static long[] gcTotals() {
        long count = 0L;
        long millis = 0L;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0L, collector.getCollectionCount());
            millis += Math.max(0L, collector.getCollectionTime());
        }
        return new long[]{count, millis};
    }
}
