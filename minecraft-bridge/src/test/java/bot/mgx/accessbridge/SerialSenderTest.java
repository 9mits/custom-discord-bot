package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SerialSenderTest {
    /** Behaves like the JDK socket: one frame at a time, "Send pending" otherwise. */
    private static final class FakeSocket implements WebSocket {
        private final ExecutorService wire = Executors.newSingleThreadExecutor();
        private final AtomicBoolean pending = new AtomicBoolean();
        final List<String> delivered = new CopyOnWriteArrayList<>();
        final AtomicLong rejected = new AtomicLong();
        volatile boolean closed;

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            if (!pending.compareAndSet(false, true)) {
                rejected.incrementAndGet();
                return CompletableFuture.failedFuture(new IllegalStateException("Send pending"));
            }
            CompletableFuture<WebSocket> done = new CompletableFuture<>();
            wire.execute(() -> {
                delivered.add(data.toString());
                pending.set(false);
                done.complete(this);
            });
            return done;
        }

        @Override public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<WebSocket> sendPing(ByteBuffer message) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<WebSocket> sendPong(ByteBuffer message) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) { closed = true; return CompletableFuture.completedFuture(this); }
        @Override public void request(long n) { }
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isOutputClosed() { return closed; }
        @Override public boolean isInputClosed() { return closed; }
        @Override public void abort() { closed = true; }
    }

    private static void awaitDrained(SerialSender sender) throws InterruptedException {
        for (int waited = 0; waited < 500 && sender.queued() > 0; waited++) {
            Thread.sleep(10L);
        }
        Thread.sleep(50L);
    }

    @Test
    void aBurstFromManyThreadsArrivesWholeWithoutASinglePendingRejection() throws Exception {
        FakeSocket socket = new FakeSocket();
        ExecutorService network = Executors.newSingleThreadExecutor();
        AtomicLong failures = new AtomicLong();
        SerialSender sender = new SerialSender(network, () -> socket, error -> failures.incrementAndGet(),
                System::currentTimeMillis);
        ExecutorService callers = Executors.newFixedThreadPool(4);
        for (int thread = 0; thread < 4; thread++) {
            int id = thread;
            callers.execute(() -> {
                for (int index = 0; index < 500; index++) sender.send(socket, id + ":" + index);
            });
        }
        callers.shutdown();
        assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS));
        awaitDrained(sender);

        assertEquals(2_000, socket.delivered.size());
        assertEquals(0L, socket.rejected.get(), "a frame was sent while another was on the wire");
        assertEquals(0L, failures.get());
        for (int thread = 0; thread < 4; thread++) {
            String prefix = thread + ":";
            List<String> mine = socket.delivered.stream().filter(text -> text.startsWith(prefix)).toList();
            for (int index = 0; index < mine.size(); index++) {
                assertEquals(prefix + index, mine.get(index), "one caller's frames must stay in order");
            }
        }
        network.shutdownNow();
        socket.wire.shutdownNow();
    }

    @Test
    void framesForAReplacedSocketAreSkippedAndAFullQueueDrops() throws Exception {
        FakeSocket old = new FakeSocket();
        FakeSocket fresh = new FakeSocket();
        WebSocket[] current = {fresh};
        ExecutorService network = Executors.newSingleThreadExecutor();
        SerialSender sender = new SerialSender(network, () -> current[0], error -> { }, System::currentTimeMillis);

        assertTrue(sender.send(old, "stale"));
        assertTrue(sender.send(fresh, "live"));
        awaitDrained(sender);
        assertTrue(old.delivered.isEmpty(), "a frame built for a replaced socket must not be sent");
        assertEquals(List.of("live"), fresh.delivered);

        old.closed = true;
        assertFalse(sender.send(old, "closed"), "a closed socket takes nothing");
        network.shutdownNow();
        old.wire.shutdownNow();
        fresh.wire.shutdownNow();
    }
}
