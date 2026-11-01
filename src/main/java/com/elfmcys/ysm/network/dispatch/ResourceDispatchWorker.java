package com.elfmcys.ysm.network.dispatch;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.frame.OutboundFrame;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Sole owner of accepted resource packets, fragment cursors, retries and global rate limiting.
 *
 * <p><b>Invariant:</b> Queue acceptance is neither transmission completion nor client publication.
 * Retrying rebuilds only the current fragment. Session keys are exact ServerModelSession objects;
 * the caller must already establish their connection and business eligibility.
 *
 * <p><b>Execution:</b> The producer serializes fragment production and transport attempts per session.
 * Detached packet close and production-failure callbacks run outside the dispatch session monitor.
 * Close seals queues and joins the producer; the producer must not close its own worker.
 *
 * <p><b>Seam:</b> [dispatch ownership frontier](docs/architecture/network/overview.md#传输接管与发送终态).
 */
public final class ResourceDispatchWorker implements AutoCloseable {
    public static final long RETRY_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    private final Supplier<Settings> settings;
    private final BandwidthLimiter limiter;
    private final Map<ServerModelSession, Session> sessions = new LinkedHashMap<>();
    private final Thread thread;
    private boolean started;
    private boolean closed;
    private int roundRobinIndex;

    public ResourceDispatchWorker(int softLimit, int hardLimit, long bytesPerSecond) {
        this(() -> new Settings(softLimit, hardLimit, bytesPerSecond));
    }

    public ResourceDispatchWorker(Supplier<Settings> settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        settings();
        limiter = new BandwidthLimiter();
        thread = new Thread(this::run, "YSM Resource Dispatch Worker");
        thread.setDaemon(true);
    }

    public synchronized void start() {
        if (!started && !closed) {
            started = true;
            thread.start();
        }
    }

    /**
     * Atomically transfers the complete packet set to one exact session, or transfers none.
     *
     * @param owner the non-null admitted session; this method does not check its business permission
     * @param transport the non-null port that must match this session's already registered port by identity
     * @param packets the non-null stable packet list with non-null, caller-owned entries
     * @return true only when dispatch takes every packet's close duty; false leaves every packet with the caller
     * @throws RuntimeException if settings or packet inputs violate their contract; the caller retains the packets
     */
    public synchronized boolean enqueue(ServerModelSession owner, TransportPort transport,
        List<? extends LogicalPacket> packets) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(packets, "packets");
        if (closed || packets.isEmpty()) {
            return false;
        }
        packets.forEach(packet -> Objects.requireNonNull(packet, "packet"));
        var current = settings();
        var session = sessions.get(owner);
        if (session == null) {
            session = new Session(transport);
            sessions.put(owner, session);
        } else if (session.transport != transport) {
            return false;
        }
        synchronized (session) {
            if (session.closed || packets.size() > current.hardLimit() - session.queue.size()) {
                if (session.queue.isEmpty()) {
                    sessions.remove(owner, session);
                }
                return false;
            }
            session.queue.addAll(packets);
        }
        notifyAll();
        return true;
    }

    public synchronized int queued(ServerModelSession owner) {
        var session = sessions.get(owner);
        if (session == null) {
            return 0;
        }
        synchronized (session) {
            return session.queue.size();
        }
    }

    public void disconnect(ServerModelSession owner) {
        final Session session;
        synchronized (this) {
            session = sessions.remove(owner);
        }
        if (session != null) {
            session.closeAll();
        }
    }

    /** Deterministic scheduling seam used by contract tests. */
    public boolean dispatchOnce() {
        var current = settings();
        final Session selected;
        synchronized (this) {
            selected = nextReadySession(System.nanoTime());
        }
        if (selected == null) {
            return false;
        }
        visit(selected, current);
        return true;
    }

    private void run() {
        while (true) {
            synchronized (this) {
                while (!closed && sessions.values().stream().allMatch(Session::empty)) {
                    waitUninterruptibly(0);
                }
                if (closed) {
                    return;
                }
            }
            final boolean dispatched;
            try {
                dispatched = dispatchOnce();
            } catch (IllegalArgumentException invalidHotConfig) {
                synchronized (this) {
                    if (!closed) {
                        waitUninterruptibly(TimeUnit.MILLISECONDS.toMillis(100));
                    }
                }
                continue;
            }
            if (!dispatched) {
                synchronized (this) {
                    if (!closed) {
                        waitUninterruptibly(TimeUnit.MILLISECONDS.toMillis(100));
                    }
                }
            }
        }
    }

    private Session nextReadySession(long now) {
        var nonEmpty = sessions.values().stream().filter(value -> !value.empty()).toList();
        if (nonEmpty.isEmpty()) {
            return null;
        }
        for (var visited = 0; visited < nonEmpty.size(); visited++) {
            var candidate = nonEmpty.get(Math.floorMod(roundRobinIndex++, nonEmpty.size()));
            if (candidate.ready(now)) {
                return candidate;
            }
        }
        return null;
    }

    private void visit(Session session, Settings current) {
        final int initialBudget;
        synchronized (session) {
            initialBudget = session.queue.size() >= current.softLimit() ? 1 : Integer.MAX_VALUE;
        }
        var budget = initialBudget;
        while (budget-- > 0) {
            long sentBytes = 0;
            var remove = false;
            var stop = false;
            var advance = false;
            LogicalPacket finishedPacket = null;
            List<LogicalPacket> abandonedPackets = List.of();
            CancellablePacket failedPacket = null;
            RuntimeException productionFailure = null;
            synchronized (session) {
                if (session.closed || session.queue.isEmpty()) {
                    return;
                }
                var packet = session.queue.peek();
                var cursor = session.cursor;
                if (!session.transport.isOpen()) {
                    abandonedPackets = session.detachAllLocked();
                    remove = true;
                } else if (packet.isCancelled() || cursor >= packet.fragmentCount()) {
                    finishedPacket = session.detachHeadLocked();
                    budget++;
                    advance = true;
                } else {
                    var estimate = packet.estimateFrameBytes(cursor);
                    if (estimate < 0 || !session.transport.isWritable() || wouldExceed(session.transport.pendingBytes(),
                        estimate, session.transport.highWatermarkBytes())) {
                        return;
                    }
                    OutboundFrame built;
                    try {
                        built = Objects.requireNonNull(packet.buildFragment(cursor), "buildFragment returned null");
                    } catch (RuntimeException failure) {
                        finishedPacket = session.detachHeadLocked();
                        if (packet instanceof CancellablePacket cancellable) {
                            failedPacket = cancellable;
                            productionFailure = failure;
                        }
                        stop = true;
                        built = null;
                    }
                    if (built != null) {
                        var frame = built;
                        try (frame) {
                            var frameBytes = frame.size();
                            var pendingBytes = session.transport.pendingBytes();
                            var highWatermarkBytes = session.transport.highWatermarkBytes();
                            if (frameBytes <= 0 || frameBytes > FrameCodec.MAX_BODY_BYTES + 14 || pendingBytes < 0
                                || highWatermarkBytes < 0 || frameBytes > highWatermarkBytes) {
                                finishedPacket = session.detachHeadLocked();
                                if (packet instanceof CancellablePacket cancellable) {
                                    failedPacket = cancellable;
                                    productionFailure = new IllegalStateException(
                                        "Built resource frame cannot be dispatched: frame=" + frameBytes + ", pending="
                                            + pendingBytes + ", high=" + highWatermarkBytes);
                                }
                                stop = true;
                            } else if (pendingBytes > highWatermarkBytes - frameBytes) {
                                session.retryAtNanos = System.nanoTime() + RETRY_NANOS;
                                stop = true;
                            } else {
                                var result = session.transport.trySend(frame);
                                if (result == SendResult.SUCCESS) {
                                    session.cursor++;
                                    session.retryAtNanos = 0;
                                    sentBytes = frameBytes;
                                    if (session.cursor >= packet.fragmentCount()) {
                                        finishedPacket = session.detachHeadLocked();
                                    }
                                } else if (!session.transport.isOpen()) {
                                    abandonedPackets = session.detachAllLocked();
                                    remove = true;
                                } else {
                                    session.retryAtNanos = System.nanoTime() + RETRY_NANOS;
                                    stop = true;
                                }
                            }
                        }
                    }
                }
            }
            closeDetached(finishedPacket);
            closeDetached(abandonedPackets);
            if (failedPacket != null) {
                reportProductionFailure(failedPacket, productionFailure);
            }
            if (remove) {
                removeSession(session);
                return;
            }
            if (stop) {
                return;
            }
            if (advance) {
                continue;
            }
            if (sentBytes > 0) {
                limiter.afterSuccessfulSend(sentBytes, current.bytesPerSecond());
            }
        }
    }

    private static void reportProductionFailure(CancellablePacket packet, RuntimeException failure) {
        try {
            packet.productionFailed(failure);
        } catch (RuntimeException callbackFailure) {
            YesSteveModel.LOGGER.error("Failed to report resource packet production failure", callbackFailure);
        }
    }

    private static void closeDetached(LogicalPacket packet) {
        if (packet != null) {
            try {
                packet.close();
            } catch (RuntimeException closeFailure) {
                YesSteveModel.LOGGER.error("Failed to close a detached resource packet", closeFailure);
            }
        }
    }

    private static void closeDetached(List<? extends LogicalPacket> packets) {
        packets.forEach(ResourceDispatchWorker::closeDetached);
    }

    private Settings settings() {
        return Objects.requireNonNull(settings.get(), "settings supplier returned null");
    }

    private static boolean wouldExceed(long pending, long next, long high) {
        return pending < 0 || next < 0 || high < 0 || pending > high - next;
    }

    private synchronized void removeSession(Session session) {
        sessions.values().removeIf(value -> value == session);
    }

    private void waitUninterruptibly(long millis) {
        var interrupted = false;
        while (!closed) {
            try {
                if (millis == 0) {
                    wait();
                } else {
                    wait(millis);
                }
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        List<Session> abandoned = null;
        synchronized (this) {
            if (!closed) {
                closed = true;
                abandoned = new ArrayList<>(sessions.values());
                sessions.clear();
                notifyAll();
            }
        }
        try {
            if (abandoned != null) {
                abandoned.forEach(Session::closeAll);
            }
        } finally {
            joinProducer();
        }
    }

    private void joinProducer() {
        var interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Session {
        private final TransportPort transport;
        private final ArrayDeque<LogicalPacket> queue = new ArrayDeque<>();
        private int cursor;
        private long retryAtNanos;
        private boolean closed;

        private Session(TransportPort transport) {
            this.transport = transport;
        }

        private synchronized boolean empty() {
            return closed || queue.isEmpty();
        }

        private synchronized boolean ready(long now) {
            return !closed && !queue.isEmpty() && retryAtNanos <= now;
        }

        private void closeAll() {
            final List<LogicalPacket> abandoned;
            synchronized (this) {
                abandoned = detachAllLocked();
            }
            closeDetached(abandoned);
        }

        private List<LogicalPacket> detachAllLocked() {
            if (closed) {
                return List.of();
            }
            closed = true;
            var abandoned = List.copyOf(queue);
            queue.clear();
            cursor = 0;
            retryAtNanos = 0;
            return abandoned;
        }

        private LogicalPacket detachHeadLocked() {
            var finished = queue.remove();
            cursor = 0;
            retryAtNanos = 0;
            return finished;
        }
    }

    public record Settings(int softLimit, int hardLimit, long bytesPerSecond) {
        public Settings {
            if (softLimit < 1 || softLimit >= hardLimit || hardLimit > 256) {
                throw new IllegalArgumentException("Dispatch limits must satisfy 1 <= soft < hard <= 256");
            }
            if (bytesPerSecond != 0 && bytesPerSecond < 32 * 1024) {
                throw new IllegalArgumentException("Bandwidth must be 0 or at least 32 KiB/s");
            }
        }
    }

    private static final class BandwidthLimiter {
        private long nextNanos;

        private synchronized void afterSuccessfulSend(long bytes, long bytesPerSecond) {
            if (bytesPerSecond == 0 || bytes == 0) {
                return;
            }
            var now = System.nanoTime();
            nextNanos = Math.max(nextNanos, now);
            long delay = (long)Math.ceil((double)bytes * TimeUnit.SECONDS.toNanos(1) / bytesPerSecond);
            nextNanos = saturatingAdd(nextNanos, delay);
            while ((delay = nextNanos - System.nanoTime()) > 0) {
                LockSupport.parkNanos(delay);
            }
        }

        private static long saturatingAdd(long left, long right) {
            return right > 0 && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
        }
    }
}
