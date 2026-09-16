package bot.mgx.accessbridge;

import java.net.http.WebSocket;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Sends text frames to a WebSocket one at a time, in the order they were queued.
 *
 * <p>Java's WebSocket accepts one outgoing text frame at a time: a second
 * {@code sendText} while the first is still on the wire fails with "Send pending". The
 * bridge sends from the main thread, the chat thread and its own network thread, and
 * every reconnect and heartbeat flushes whole outboxes in a loop, so most of a burst
 * used to fail silently and wait for the next heartbeat to try again, which then failed
 * the same way. Everything now goes through this queue.
 *
 * <p>Bounded, because a peer that stops reading must not become unbounded memory. A
 * dropped frame is still in the outbox it came from and goes again after a reconnect.
 */
final class SerialSender {
    static final int MAX_QUEUED = 5_000;
    /** How long one frame may stay unfinished before the queue is restarted. */
    static final long STALL_MILLIS = 30_000L;

    private record Outgoing(WebSocket target, String text) {
    }

    private final ArrayDeque<Outgoing> queue = new ArrayDeque<>();
    private final Executor executor;
    private final Supplier<WebSocket> current;
    private final Consumer<Throwable> failures;
    private final LongSupplier clock;
    private boolean sending;
    private long sendStartedAt;
    private long dropped;
    /** Bumped when a stalled chain is abandoned, so its late completion cannot resume it. */
    private int generation;

    /**
     * @param executor where each completion continues, so a long queue never recurses
     * @param current the socket frames are still wanted on; anything built for an older
     *                one is skipped
     * @param failures told about each frame that could not be sent
     */
    SerialSender(Executor executor, Supplier<WebSocket> current, Consumer<Throwable> failures,
                 LongSupplier clock) {
        this.executor = executor;
        this.current = current;
        this.failures = failures;
        this.clock = clock;
    }

    /** Queues a frame for {@code target}; false when the queue is full and it was dropped. */
    boolean send(WebSocket target, String text) {
        if (target == null || target.isOutputClosed()) {
            return false;
        }
        synchronized (queue) {
            if (queue.size() >= MAX_QUEUED) {
                dropped++;
                return false;
            }
            queue.addLast(new Outgoing(target, text));
            if (sending) {
                return true;
            }
            sending = true;
            sendStartedAt = clock.getAsLong();
        }
        drain();
        return true;
    }

    int queued() {
        synchronized (queue) {
            return queue.size();
        }
    }

    long dropped() {
        synchronized (queue) {
            return dropped;
        }
    }

    /**
     * Gives up on a queue whose last frame never completed.
     *
     * <p>A pending {@code sendText} cannot be cancelled, and a second one on the same
     * socket fails with "Send pending" — so simply draining again would fail every frame
     * in turn and empty the queue, which is what this class exists to prevent. The socket
     * is what has to go: aborting it completes the pending send exceptionally and the
     * bridge reconnects, and each dropped frame is still in the outbox it came from.
     *
     * @return whether it had to
     */
    boolean unstick() {
        WebSocket stalled;
        synchronized (queue) {
            if (!sending || clock.getAsLong() - sendStartedAt <= STALL_MILLIS) {
                return false;
            }
            stalled = current.get();
            dropped += queue.size();
            queue.clear();
            sending = false;
            generation++;
        }
        if (stalled != null) {
            stalled.abort();
        }
        return true;
    }

    private void drain() {
        int mine;
        synchronized (queue) {
            mine = generation;
        }
        drain(mine);
    }

    private void drain(int chain) {
        Outgoing next;
        while (true) {
            synchronized (queue) {
                if (chain != generation) {
                    // An abandoned chain: unstick() already cleared the queue and the
                    // socket it was writing to is gone.
                    return;
                }
                next = queue.pollFirst();
                if (next == null) {
                    sending = false;
                    return;
                }
                sendStartedAt = clock.getAsLong();
            }
            if (next.target() == current.get() && !next.target().isOutputClosed()) {
                break;
            }
        }
        try {
            next.target().sendText(next.text(), true).whenCompleteAsync((ignored, error) -> {
                if (error != null) {
                    failures.accept(error);
                }
                drain(chain);
            }, executor);
        } catch (RuntimeException exception) {
            failures.accept(exception);
            try {
                executor.execute(() -> drain(chain));
            } catch (RejectedExecutionException stopping) {
                synchronized (queue) {
                    queue.clear();
                    sending = false;
                }
            }
        }
    }
}
