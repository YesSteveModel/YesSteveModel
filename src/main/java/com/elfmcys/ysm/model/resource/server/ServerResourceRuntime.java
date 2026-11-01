package com.elfmcys.ysm.model.resource.server;

import com.elfmcys.ysm.buffer.SharedArrayBuffer;
import com.elfmcys.ysm.model.storage.PreviewStorage;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.resource.RuntimeContentStore;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.natives.NativeRuntime;
import com.elfmcys.ysm.util.ThreadScheduling;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadPolicy;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadRole;

import java.io.IOException;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Retains verified chunk bytes and replaceable encoded preview bytes for server transmissions.
 *
 * <p><b>Execution:</b> Acquisition enqueues a bounded request. The server owner drives tick to admit
 * work and deliver results; callers must not block that owner waiting for a returned future.
 * Workers read sources and return completions. Close seals requests and waits for worker termination.
 *
 * <p><b>Ownership:</b> Each delivered BytesLease is independent. Eviction and preview invalidation
 * remove reuse eligibility without revoking delivered leases. Detached preview reads can still serve
 * their original requests, but cannot replace newer retained bytes. Future cancellation relinquishes
 * only that result; source work can continue for other requests.
 *
 * <p><b>Seam:</b> [server byte retention and old requests](docs/architecture/model-management/server-resources/overview.md#图片失效与旧请求).
 */
public final class ServerResourceRuntime implements AutoCloseable {
    static final long IDLE_NANOS = Duration.ofSeconds(30).toNanos();
    static final long MAX_CACHED_BYTES = 64L * 1024 * 1024;
    static final int MAX_IN_FLIGHT = 256;
    static final int MAX_REQUEST_BACKLOG = 4_096;
    private static final int DISPOSITIONS_PER_TICK = 4;
    private static final int REQUESTS_PER_TICK = 256;

    private final Object lock = new Object();
    private final LinkedHashMap<Key, Entry> ready = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<FlightKey, Flight> pending = new LinkedHashMap<>();
    private final Set<Flight> activeFlights = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ConcurrentLinkedQueue<Request> requests = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Completion> completions = new ConcurrentLinkedQueue<>();
    private final Set<Request> outstanding = Collections.newSetFromMap(new IdentityHashMap<>());
    private final RuntimeContentStore contentStore;
    private final PreviewStorage previewStore;
    private final ExecutorService workers;
    private final LongSupplier nanoTime;
    private final long maxCachedBytes;
    private final AtomicInteger requestBacklog = new AtomicInteger();
    private long cachedBytes;
    private boolean closed;

    public ServerResourceRuntime(PreviewStorage previewStore, Consumer<IOException> corruptionReporter) {
        this(previewStore, corruptionReporter, System::nanoTime, MAX_CACHED_BYTES);
    }

    ServerResourceRuntime(PreviewStorage previewStore, Consumer<IOException> corruptionReporter,
        LongSupplier nanoTime) {
        this(previewStore, corruptionReporter, nanoTime, MAX_CACHED_BYTES);
    }

    ServerResourceRuntime(PreviewStorage previewStore, Consumer<IOException> corruptionReporter, LongSupplier nanoTime,
        long maxCachedBytes) {
        this(previewStore, corruptionReporter, nanoTime, maxCachedBytes, createWorkers());
    }

    ServerResourceRuntime(PreviewStorage previewStore, Consumer<IOException> corruptionReporter, LongSupplier nanoTime,
        long maxCachedBytes, ExecutorService workers) {
        contentStore = new RuntimeContentStore(corruptionReporter);
        this.previewStore = Objects.requireNonNull(previewStore, "previewStore");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        if (maxCachedBytes < 1)
            throw new IllegalArgumentException("Server resource cache limit must be positive");
        this.maxCachedBytes = maxCachedBytes;
        this.workers = Objects.requireNonNull(workers, "workers");
    }

    private static ExecutorService createWorkers() {
        var workerCount = (int)Math.max(2, Math.min(8, (NativeRuntime.availableProcessors() + 2L) / 3));
        var workers =
            new ThreadPoolExecutor(2, workerCount, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
                var thread =
                    new Thread(ThreadScheduling.entry(ThreadRole.SERVER_RESOURCE, ThreadPolicy.BELOW_NORMAL, runnable),
                        "YSM Server Resource Worker");
                thread.setDaemon(true);
                return thread;
            });
        workers.allowCoreThreadTimeOut(true);
        return workers;
    }

    /**
     * Requests verified stored bytes without granting business access to the model.
     *
     * @param content the non-null admitted exact content; the request owner must already check authorization
     * @param chunk the non-null chunk descriptor from that content's established chunk table
     * @return a non-null future that transfers one open lease on success; capacity rejection is BusyException,
     *     and source I/O or verification failure completes exceptionally
     */
    public CompletableFuture<BytesLease> acquireChunk(ModelContent content, AssetContainerView.ChunkInfo chunk) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(chunk, "chunk");
        var result = new CompletableFuture<BytesLease>();
        enqueue(new ChunkRequest(ChunkKey.of(content, chunk), content, chunk, result));
        return result;
    }

    private PreviewStore fixedPreviewStore() {
        if (previewStore instanceof PreviewStore fixed)
            return fixed;
        throw new IllegalStateException("A regional preview request requires content provenance");
    }

    public CompletableFuture<Optional<BytesLease>> acquirePreview(Hash256 containerId) {
        var result = new CompletableFuture<Optional<BytesLease>>();
        enqueue(new PreviewRequest(
            new PreviewKey(fixedPreviewStore(), Objects.requireNonNull(containerId, "containerId")), result));
        return result;
    }

    /**
     * Requests encoded preview bytes in the content's storage region without decoding image media.
     *
     * @param content the non-null content that supplies storage provenance and container identity
     * @return a non-null future; empty means an observed cache miss, present transfers one open lease,
     *     and access failure or capacity rejection completes exceptionally
     */
    public CompletableFuture<Optional<BytesLease>> acquirePreview(ModelContent content) {
        var result = new CompletableFuture<Optional<BytesLease>>();
        enqueue(new PreviewRequest(
            new PreviewKey(previewStore.forContent(content), content.representation().containerId()), result));
        return result;
    }

    public void invalidatePreview(PreviewStore store, Hash256 containerId) {
        synchronized (lock) {
            if (!closed)
                invalidatePreviewKey(new PreviewKey(store, containerId));
        }
    }

    private void enqueue(Request request) {
        Throwable failure = null;
        synchronized (lock) {
            if (closed) {
                failure = new IllegalStateException("Server resource runtime is closed");
            } else if (requestBacklog.get() >= MAX_REQUEST_BACKLOG) {
                failure = new BusyException("Server resource request capacity is full");
            } else {
                requestBacklog.incrementAndGet();
                outstanding.add(request);
                requests.add(request);
                request.result().whenComplete((value, terminal) -> {
                    synchronized (lock) {
                        outstanding.remove(request);
                    }
                });
            }
        }
        if (failure != null)
            request.result().completeExceptionally(failure);
    }

    public void invalidatePreview(Hash256 containerId) {
        Objects.requireNonNull(containerId, "containerId");
        synchronized (lock) {
            if (!closed)
                invalidatePreviewKey(new PreviewKey(fixedPreviewStore(), containerId));
        }
    }

    public void invalidatePreviews() {
        synchronized (lock) {
            if (closed)
                return;
            var iterator = ready.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next().getValue();
                if (entry.key instanceof PreviewKey) {
                    iterator.remove();
                    cachedBytes -= entry.bytes.size();
                }
            }
            pending.entrySet().removeIf(entry -> {
                if (!(entry.getKey().key instanceof PreviewKey))
                    return false;
                entry.getValue().publishEligible = false;
                return true;
            });
        }
    }

    private void invalidatePreviewKey(PreviewKey key) {
        var entry = ready.remove(key);
        if (entry != null)
            cachedBytes -= entry.bytes.size();
        var flight = pending.remove(new FlightKey(key, null));
        if (flight != null)
            flight.publishEligible = false;
    }

    public void tick() {
        contentStore.tick();
        processRequests();
        for (var disposed = 0; disposed < DISPOSITIONS_PER_TICK; disposed++) {
            var completion = completions.poll();
            if (completion == null)
                break;
            accept(completion);
        }
        evict();
    }

    int cachedCount() {
        synchronized (lock) {
            return ready.size();
        }
    }

    long cachedBytes() {
        synchronized (lock) {
            return cachedBytes;
        }
    }

    int activeFlightCount() {
        synchronized (lock) {
            return activeFlights.size();
        }
    }

    private void processRequests() {
        for (var disposed = 0; disposed < REQUESTS_PER_TICK; disposed++) {
            final Request request;
            Flight start = null;
            Entry cached = null;
            Throwable failure = null;
            synchronized (lock) {
                request = requests.poll();
                if (request == null)
                    return;
                requestBacklog.decrementAndGet();
                if (request.result().isDone())
                    continue;
                if (closed) {
                    failure = new IllegalStateException("Server resource runtime is closed");
                } else {
                    cached = ready.get(request.key());
                    if (cached == null) {
                        var source = request instanceof ChunkRequest chunk ? chunk.content : null;
                        var flightKey = new FlightKey(request.key(), source);
                        var flight = pending.get(flightKey);
                        if (flight == null) {
                            if (activeFlights.size() >= MAX_IN_FLIGHT) {
                                failure = new BusyException("Server resource in-flight capacity is full");
                            } else {
                                flight = new Flight(flightKey, request);
                                pending.put(flightKey, flight);
                                activeFlights.add(flight);
                                start = flight;
                            }
                        }
                        if (flight != null)
                            flight.waiters.add(request);
                    }
                }
            }
            if (failure != null) {
                request.result().completeExceptionally(failure);
            } else if (cached != null) {
                complete(request, cached);
            } else if (start != null) {
                var flight = start;
                try {
                    workers.execute(() -> load(flight));
                } catch (RuntimeException rejected) {
                    enqueueCompletion(new Completion(flight, null, rejected));
                }
            }
        }
    }

    private void load(Flight flight) {
        SharedArrayBuffer bytes = null;
        Throwable failure = null;
        try {
            if (flight.source instanceof ChunkRequest chunk) {
                bytes = contentStore.exact(chunk.content).chunks().readStoredVerified(chunk.chunk, BufferType.ARRAY)
                    .shareArray();
            } else {
                var preview = (PreviewRequest)flight.source;
                bytes =
                    preview.key.store.readEncoded(preview.key.containerId).map(SharedArrayBuffer::adopt).orElse(null);
            }
        } catch (Throwable loadingFailure) {
            bytes = null;
            failure = loadingFailure;
        }
        // Source handles and their close work must finish before the flight can return its capacity.
        enqueueCompletion(new Completion(flight, bytes, failure));
    }

    private void enqueueCompletion(Completion completion) {
        synchronized (lock) {
            if (!closed && activeFlights.contains(completion.flight)) {
                completions.add(completion);
                return;
            }
        }
    }

    private void accept(Completion completion) {
        final List<Request> waiters;
        Entry entry = null;
        synchronized (lock) {
            var flight = completion.flight;
            if (!activeFlights.remove(flight)) {
                return;
            }
            if (pending.get(flight.flightKey) == flight)
                pending.remove(flight.flightKey);
            waiters = List.copyOf(flight.waiters);
            if (completion.failure == null && completion.bytes != null) {
                // Detached reads can serve their original waiters, but never reuse or replace newer ready bytes.
                if (flight.publishEligible)
                    entry = ready.get(flight.flightKey.key);
                if (entry == null) {
                    entry = new Entry(flight.flightKey.key, completion.bytes, nanoTime.getAsLong());
                    if (flight.publishEligible && entry.bytes.size() <= maxCachedBytes) {
                        ready.put(entry.key, entry);
                        cachedBytes += entry.bytes.size();
                    }
                }
            }
        }
        for (var waiter : waiters) {
            if (completion.failure != null)
                waiter.result().completeExceptionally(completion.failure);
            else if (entry == null)
                waiter.absent();
            else
                complete(waiter, entry);
        }
    }

    private void complete(Request result, Entry entry) {
        var acquiredAt = nanoTime.getAsLong();
        var lease = new BytesLease(entry);
        if (result.deliver(lease)) {
            synchronized (lock) {
                if (acquiredAt - entry.lastAcquire > 0)
                    entry.lastAcquire = acquiredAt;
            }
        } else {
            lease.close();
        }
    }

    private void evict() {
        var now = nanoTime.getAsLong();
        synchronized (lock) {
            var iterator = ready.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next().getValue();
                if (now - entry.lastAcquire >= IDLE_NANOS) {
                    iterator.remove();
                    cachedBytes -= entry.bytes.size();
                }
            }
            iterator = ready.entrySet().iterator();
            while (cachedBytes > maxCachedBytes && iterator.hasNext()) {
                var entry = iterator.next().getValue();
                iterator.remove();
                cachedBytes -= entry.bytes.size();
            }
        }
    }

    @Override
    public void close() {
        var failures = new ArrayList<CompletableFuture<?>>();
        synchronized (lock) {
            if (closed)
                return;
            closed = true;
            outstanding.forEach(request -> failures.add(request.result()));
            outstanding.clear();
            activeFlights.clear();
            pending.clear();
            ready.clear();
            cachedBytes = 0;
            for (Request request; (request = requests.poll()) != null;) {
                requestBacklog.decrementAndGet();
            }
        }
        failures.forEach(
            result -> result.completeExceptionally(new IllegalStateException("Server resource runtime is closed")));
        completions.clear();
        workers.shutdownNow();
        var interrupted = false;
        while (!workers.isTerminated()) {
            try {
                workers.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();
    }

    public static final class BusyException extends RejectedExecutionException {
        private BusyException(String message) {
            super(message);
        }
    }

    /**
     * Retains one immutable encoded byte entry independently of cache membership.
     * The receiver owns idempotent close and must exclude close from an in-progress read.
     * Size, share and copy require an open lease. A shared slice retains its own backing beyond lease close;
     * logical close does not promise immediate pooled-buffer reclamation.
     */
    public static final class BytesLease implements AutoCloseable {
        private Entry entry;

        private BytesLease(Entry entry) {
            this.entry = entry;
        }

        public int size() {
            return requireOpen().bytes.size();
        }

        public SharedArrayBuffer share(int offset, int length) {
            return requireOpen().bytes.ownedSlice(offset, length);
        }

        public void copyTo(int offset, ByteBuffer target) {
            var bytes = requireOpen().bytes;
            if (offset < 0 || target.remaining() > bytes.size() - offset)
                throw new IndexOutOfBoundsException("Bytes lease range is invalid");
            try {
                var source = bytes.nio();
                source.position(offset).limit(offset + target.remaining());
                target.put(source);
            } finally {
                // The NIO view keeps the array alive, but not its pooled-buffer ownership.
                Reference.reachabilityFence(bytes);
            }
        }

        @Override
        public synchronized void close() {
            entry = null;
        }

        private synchronized Entry requireOpen() {
            if (entry == null)
                throw new IllegalStateException("Bytes lease is closed");
            return entry;
        }
    }

    private sealed interface Request permits ChunkRequest, PreviewRequest {
        Key key();

        CompletableFuture<?> result();

        boolean deliver(BytesLease lease);

        void absent();
    }

    private record ChunkRequest(ChunkKey key, ModelContent content, AssetContainerView.ChunkInfo chunk,
        CompletableFuture<BytesLease> result) implements Request {
        @Override
        public boolean deliver(BytesLease lease) {
            return result.complete(lease);
        }

        @Override
        public void absent() {
            result.completeExceptionally(new IllegalStateException("Chunk load produced no bytes"));
        }
    }

    private record PreviewRequest(PreviewKey key, CompletableFuture<Optional<BytesLease>> result) implements Request {
        @Override
        public boolean deliver(BytesLease lease) {
            return result.complete(Optional.of(lease));
        }

        @Override
        public void absent() {
            result.complete(Optional.empty());
        }
    }

    private static final class Flight {
        private final FlightKey flightKey;
        private final Request source;
        private final List<Request> waiters = new ArrayList<>();
        private boolean publishEligible = true;

        private Flight(FlightKey flightKey, Request source) {
            this.flightKey = flightKey;
            this.source = source;
        }
    }

    private static final class Entry {
        private final Key key;
        private final SharedArrayBuffer bytes;
        private long lastAcquire;

        private Entry(Key key, SharedArrayBuffer bytes, long lastAcquire) {
            this.key = key;
            this.bytes = bytes;
            this.lastAcquire = lastAcquire;
        }
    }

    private record Completion(Flight flight, SharedArrayBuffer bytes, Throwable failure) {
    }

    private sealed interface Key permits ChunkKey, PreviewKey {}

    private record PreviewKey(PreviewStore store, Hash256 containerId) implements Key {
    }

    private record ChunkKey(ModelFileIdentity identity, String type, Hash256 hash, String encoding, int storedSize,
        int decodedSize) implements Key {
        private static ChunkKey of(ModelContent content, AssetContainerView.ChunkInfo chunk) {
            return new ChunkKey(content.representation().identity(), chunk.type(), new Hash256(chunk.hash()),
                chunk.encoding(), chunk.size(), chunk.decodeSize());
        }
    }

    private static final class FlightKey {
        private final Key key;
        private final ModelContent source;

        private FlightKey(Key key, ModelContent source) {
            this.key = key;
            this.source = source;
        }

        @Override
        public boolean equals(Object value) {
            return this == value || value instanceof FlightKey other && key.equals(other.key) && source == other.source;
        }

        @Override
        public int hashCode() {
            return 31 * key.hashCode() + System.identityHashCode(source);
        }
    }
}
