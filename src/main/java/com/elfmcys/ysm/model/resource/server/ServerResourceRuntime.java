package com.elfmcys.ysm.model.resource.server;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.buffer.SharedArrayBuffer;
import com.elfmcys.ysm.model.storage.PreviewStorage;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
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
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Retains verified chunk bytes and replaceable encoded preview bytes for server transmissions.
 *
 * <p>
 * <b>Execution:</b> Acquisition returns a caller-owned use immediately and queues a bounded request on cache miss. The
 * server owner drives tick to admit work and publish readiness. Workers read sources and return completions. Close
 * seals requests and waits for worker termination.
 *
 * <p>
 * <b>Ownership:</b> Each BytesLease is independent from admission. Eviction and preview invalidation remove reuse
 * eligibility without revoking existing Ready uses. Detached preview reads still serve their original requests, but
 * cannot replace newer retained bytes. Closing the last pending use revokes flight join and publication eligibility;
 * physical capacity remains occupied until the read terminates.
 *
 * <p>
 * <b>Seam:</b> [server byte retention and old
 * requests](docs/architecture/model-management/server-resources/overview.md#图片失效与旧请求).
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
    private final ArrayDeque<Request> requests = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<Completion> completions = new ConcurrentLinkedQueue<>();
    private final Set<Request> outstanding = Collections.newSetFromMap(new IdentityHashMap<>());
    private final RuntimeContentStore contentStore;
    private final PreviewStorage previewStore;
    private final ExecutorService workers;
    private final LongSupplier nanoTime;
    private final long maxCachedBytes;
    private int requestBacklog;
    private long cachedBytes;
    private boolean closed;

    public ServerResourceRuntime(PreviewStorage previewStore, Consumer<IOException> corruptionReporter) {
        this(previewStore, corruptionReporter, System::nanoTime, MAX_CACHED_BYTES, createWorkers());
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
     * @return one caller-owned use, including while pending; capacity rejection and source failure are observable
     *         through its failed state
     */
    public BytesLease acquireChunk(ModelContent content, AssetContainerView.ChunkInfo chunk) {
        return acquireChunk(content, chunk, null);
    }

    /**
     * Owns readRelease from entry, including rejection. The callback releases an independent caller retention when this
     * request no longer borrows the source. It must not throw, block or call back into this runtime. Ending a pending
     * use does not release a retention already transferred to a flight before that flight's read terminates.
     */
    public BytesLease acquireChunk(ModelContent content, AssetContainerView.ChunkInfo chunk, Runnable readRelease) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(chunk, "chunk");
        var result = new BytesLease(this);
        result.readRelease = readRelease;
        enqueue(new ChunkRequest(ChunkKey.of(content, chunk), content, chunk, result));
        return result;
    }

    /**
     * Requests encoded preview bytes in the content's storage region without decoding image media.
     *
     * @param content the non-null content that supplies storage provenance and container identity
     * @return one caller-owned use; Missing is an observed cache miss, and Failed reports access or capacity failure
     */
    public BytesLease acquirePreview(ModelContent content) {
        var result = new BytesLease(this);
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
        synchronized (lock) {
            var use = request.use();
            if (closed) {
                fail(request, new IllegalStateException("Server resource runtime is closed"));
            } else {
                var cached = ready.get(request.key());
                if (cached != null) {
                    complete(request, cached);
                } else if (requestBacklog >= MAX_REQUEST_BACKLOG) {
                    fail(request, new BusyException("Server resource request capacity is full"));
                } else {
                    use.request = request;
                    requestBacklog++;
                    outstanding.add(request);
                    requests.add(request);
                }
            }
        }
    }

    private void fail(Request request, Throwable failure) {
        var use = request.use();
        if (use.state != State.PENDING)
            return;
        outstanding.remove(request);
        use.request = null;
        use.failure = failure;
        use.state = State.FAILED;
        use.releaseUnstartedRead();
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

    private void processRequests() {
        for (var disposed = 0; disposed < REQUESTS_PER_TICK; disposed++) {
            Flight start = null;
            synchronized (lock) {
                var request = requests.poll();
                if (request == null)
                    return;
                requestBacklog--;
                if (closed) {
                    fail(request, new IllegalStateException("Server resource runtime is closed"));
                    continue;
                }
                var cached = ready.get(request.key());
                if (cached != null) {
                    complete(request, cached);
                    continue;
                }
                var source = request instanceof ChunkRequest chunk ? chunk.content : null;
                var flightKey = new FlightKey(request.key(), source);
                var flight = pending.get(flightKey);
                if (flight == null) {
                    if (activeFlights.size() >= MAX_IN_FLIGHT) {
                        fail(request, new BusyException("Server resource in-flight capacity is full"));
                        continue;
                    }
                    flight = new Flight(flightKey, request);
                    pending.put(flightKey, flight);
                    activeFlights.add(flight);
                    start = flight;
                }
                flight.waiters.add(request);
                request.use().flight = flight;
                if (request.use().readRelease != null) {
                    if (flight.readReleases == null)
                        flight.readReleases = new ArrayList<>();
                    flight.readReleases.add(request.use().readRelease);
                    request.use().readRelease = null;
                }
            }
            if (start != null) {
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
            completion.flight.releaseReads();
            if (!closed && activeFlights.contains(completion.flight)) {
                completions.add(completion);
                return;
            }
            activeFlights.remove(completion.flight);
        }
    }

    private void accept(Completion completion) {
        synchronized (lock) {
            var flight = completion.flight;
            if (!activeFlights.remove(flight))
                return;
            pending.remove(flight.flightKey, flight);
            Entry entry = null;
            if (completion.failure == null && completion.bytes != null) {
                // Detached reads serve their original users without replacing newer ready bytes.
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
            for (var request : flight.waiters) {
                request.use().flight = null;
                if (completion.failure != null) {
                    fail(request, completion.failure);
                } else if (entry == null) {
                    if (request instanceof ChunkRequest) {
                        fail(request, new IllegalStateException("Chunk load produced no bytes"));
                    } else {
                        outstanding.remove(request);
                        request.use().request = null;
                        request.use().state = State.MISSING;
                    }
                } else {
                    complete(request, entry);
                }
            }
            flight.waiters.clear();
        }
    }

    private void complete(Request request, Entry entry) {
        outstanding.remove(request);
        var use = request.use();
        use.request = null;
        use.entry = entry;
        use.state = State.READY;
        entry.lastAcquire = nanoTime.getAsLong();
        use.releaseUnstartedRead();
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
        synchronized (lock) {
            if (closed)
                return;
            closed = true;
            for (var request : List.copyOf(outstanding)) {
                request.use().flight = null;
                fail(request, new IllegalStateException("Server resource runtime is closed"));
            }
            activeFlights.forEach(flight -> flight.waiters.clear());
            pending.clear();
            ready.clear();
            cachedBytes = 0;
            for (Request request; (request = requests.poll()) != null;) {
                requestBacklog--;
            }
        }
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
        synchronized (lock) {
            activeFlights.forEach(Flight::releaseReads);
            activeFlights.clear();
        }
        if (interrupted)
            Thread.currentThread().interrupt();
    }

    public static final class BusyException extends RejectedExecutionException {
        private BusyException(String message) {
            super(message);
        }
    }

    public enum State {
        PENDING, READY, MISSING, FAILED, ENDED
    }

    /**
     * One caller-owned use from request admission through close. Readiness never transfers another lease. The caller
     * polls state and closes this use on exit, even when pending or missing. Ready bytes remain valid across cache
     * eviction, preview invalidation and runtime close. The caller must exclude close from its own in-progress reads.
     * Packet owners obtain an independent Ready use with retain before the acquisition owner closes its use.
     *
     * <p>
     * Seam: implements [byte acquisition](docs/architecture/model-management/server-resources/overview.md#发送字节取得与交付).
     */
    public static final class BytesLease implements AutoCloseable {
        private final ServerResourceRuntime runtime;
        private State state = State.PENDING;
        private Entry entry;
        private Throwable failure;
        private Request request;
        private Flight flight;
        private Runnable readRelease;

        private BytesLease(ServerResourceRuntime runtime) {
            this.runtime = runtime;
        }

        public State state() {
            synchronized (runtime.lock) {
                return state;
            }
        }

        /** Returns the original cause while this use is Failed. */
        public Throwable failure() {
            synchronized (runtime.lock) {
                if (state != State.FAILED)
                    throw new IllegalStateException("Bytes use has no failure");
                return failure;
            }
        }

        /** Establishes the receiver's independent Ready use before the caller releases its use. */
        public BytesLease retain() {
            synchronized (runtime.lock) {
                var retained = new BytesLease(runtime);
                retained.entry = requireReady();
                retained.state = State.READY;
                return retained;
            }
        }

        public int size() {
            return requireReady().bytes.size();
        }

        public SharedArrayBuffer share(int offset, int length) {
            return requireReady().bytes.ownedSlice(offset, length);
        }

        public void copyTo(int offset, ByteBuffer target) {
            var bytes = requireReady().bytes;
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
        public void close() {
            synchronized (runtime.lock) {
                if (request != null) {
                    runtime.outstanding.remove(request);
                    if (runtime.requests.remove(request))
                        runtime.requestBacklog--;
                    if (flight != null) {
                        flight.waiters.remove(request);
                        if (flight.waiters.isEmpty()) {
                            runtime.pending.remove(flight.flightKey, flight);
                            flight.publishEligible = false;
                        }
                    }
                }
                request = null;
                flight = null;
                entry = null;
                failure = null;
                state = State.ENDED;
                releaseUnstartedRead();
            }
        }

        private void releaseUnstartedRead() {
            if (readRelease != null) {
                var release = readRelease;
                readRelease = null;
                release.run();
            }
        }

        private Entry requireReady() {
            synchronized (runtime.lock) {
                if (state != State.READY)
                    throw new IllegalStateException("Bytes use is not Ready: " + state);
                return entry;
            }
        }
    }

    private sealed interface Request permits ChunkRequest, PreviewRequest {
        Key key();

        BytesLease use();
    }

    private record ChunkRequest(ChunkKey key, ModelContent content, AssetContainerView.ChunkInfo chunk,
        BytesLease use) implements Request {
    }

    private record PreviewRequest(PreviewKey key, BytesLease use) implements Request {
    }

    private static final class Flight {
        private final FlightKey flightKey;
        private final Request source;
        private final List<Request> waiters = new ArrayList<>();
        private boolean publishEligible = true;
        private List<Runnable> readReleases;

        private void releaseReads() {
            if (readReleases != null) {
                readReleases.forEach(Runnable::run);
                readReleases = null;
            }
        }

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
