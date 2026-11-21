package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.natives.NativeCancellation;
import com.elfmcys.ysm.natives.storage.StorageDeliveryException;
import com.elfmcys.ysm.natives.storage.StorageResult;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * Orders player facts and remote usage at the main-thread admission point. Other threads only enqueue immutable work.
 * The owner calls tick; closeAndDrain runs the same completion acceptance without requiring another game tick.
 *
 * <p>
 * Queue reservation is not logical admission. Failed latest values remain visible until replaced; ordinary ticks never
 * retry them. Queries publish directly to their weakly queued handles. DatabaseWork owns all physical work.
 *
 * <p>
 * Seam: implements [ordered storage](docs/architecture/model-management/storage/preferences.md#周期提交与关闭).
 */
public final class StorageWriteBuffer {
    public static final int MAXIMUM_PENDING = 256;
    public static final int MAXIMUM_BYTES = 1024 * 1024;
    public static final int MAXIMUM_BATCH = 64;
    public static final int MAXIMUM_BATCH_BYTES = MAXIMUM_BYTES;
    private static final long PERIOD_NANOS = 1_000_000_000L;

    public sealed interface Key permits RoamingKey, SelectionKey, FavouriteKey, UsageKey {}

    public record RoamingKey(UUID player, PublishedModelId identity) implements Key {
    }

    public record SelectionKey(UUID player) implements Key {
    }

    public record FavouriteKey(UUID player, ModelId identity) implements Key {
    }

    /** The capability pins the actual remote region. */
    public record UsageKey(StorageIndex<com.elfmcys.ysm.natives.storage.NativeCacheRemote> index,
        Hash256 container) implements Key {
    }

    public enum Admission {
        QUEUED, ACCEPTED, FULL, CLOSED
    }

    /** Side effects retain their result owner even when a consumer retires. */
    public static final class Operation<T> {
        private volatile Admission admitted;
        private volatile boolean cancelled;
        private final boolean cancellable;
        private final CompletableFuture<StorageResult<T>> completion = new CompletableFuture<>();
        private volatile List<WriteResult> report = List.of();

        private Operation(boolean cancellable) {
            this.cancellable = cancellable;
        }

        public Admission admission() {
            return admitted == null ? Admission.QUEUED : admitted;
        }

        public CompletionStage<StorageResult<T>> result() {
            return completion.minimalCompletionStage();
        }

        /** Flush reports its prefix's independent commit facts, including unresolved unknown effects. */
        public List<WriteResult> report() {
            return report;
        }

        /** Only a restore that has not begun may skip its first-fill effect. */
        public void cancel() {
            if (cancellable)
                cancelled = true;
        }
    }

    public record WriteResult(@Nullable Key key, @Nullable StorageResult<?> storage,
        @Nullable StorageDeliveryException deliveryFailure) {
        public boolean succeeded() {
            return storage != null && storage.succeeded();
        }
    }

    public record Metrics(int operations, int bytes, int peakOperations, int peakBytes, long executed) {
    }

    @FunctionalInterface
    interface Work<T> {
        StorageResult<T> run(NativeCancellation cancelled);
    }

    private static final class Node<T> {
        final Key key;
        Object value;
        final Operation<T> operation;
        final WeakReference<StorageQuery<T>> query;
        final boolean boundary;
        final boolean independent;
        final Function<Map<Key, Object>, Work<T>> prepare;
        Work<T> work;
        int bytes;
        List<Node<?>> prefix;
        List<Node<?>> replacedPrefix;
        StorageResult<T> result;
        StorageDeliveryException failure;
        boolean finished;
        boolean released;

        Node(Key key, Object value, int bytes, boolean boundary, boolean independent, Operation<T> operation,
            WeakReference<StorageQuery<T>> query, Function<Map<Key, Object>, Work<T>> prepare) {
            this.key = key;
            this.value = value;
            this.bytes = bytes;
            this.boundary = boundary;
            this.independent = independent;
            this.operation = operation;
            this.query = query;
            this.prepare = prepare;
        }

        boolean committed() {
            return result != null && result.committed()
                || failure != null && failure.commitState() == StorageDeliveryException.CommitState.COMMITTED;
        }

        boolean unknown() {
            return failure != null && failure.commitState() == StorageDeliveryException.CommitState.UNKNOWN;
        }

        boolean retryable() {
            return finished && !committed()
                && (failure != null ? failure.commitState() == StorageDeliveryException.CommitState.NOT_COMMITTED
                    : result != null && !result.succeeded() && result.failure() != StorageResult.Failure.CLOSED
                        && result.failure() != StorageResult.Failure.INCOMPATIBLE
                        && result.failure() != StorageResult.Failure.CANCELLED);
        }

        WriteResult observation() {
            return new WriteResult(key, result, failure);
        }
    }

    private final PlayerPreferences preferences;
    private final DatabaseWork database;
    // This monitor is the existing producer ingress; the overlay has only the logical owner as writer.
    private final ArrayDeque<Node<?>> ingress = new ArrayDeque<>();
    private final ArrayDeque<Node<?>> pending = new ArrayDeque<>();
    private final Map<Key, Node<?>> overlay = new LinkedHashMap<>();
    private final Map<Key, Node<?>> coalescing = new HashMap<>();
    private final List<Node<?>> independentFailures = new ArrayList<>();
    private final ConcurrentLinkedQueue<Node<?>> completed = new ConcurrentLinkedQueue<>();
    private DatabaseWork.Job<Void> inFlight;
    private List<Node<?>> active = List.of();
    private int operations, bytes, peakOperations, peakBytes;
    private long executed;
    private long firstPending;
    private boolean sealed;
    private List<WriteResult> closedReport;

    StorageWriteBuffer(PlayerPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences);
        database = preferences.databaseWork();
    }

    synchronized <T> Operation<T> write(Key key, T value, int size, Work<T> work, boolean boundary) {
        var operation = new Operation<T>(false);
        enqueue(new Node<>(key, value, size, boundary, false, operation, null, ignored -> work));
        return operation;
    }

    synchronized <T> Operation<T> restore(Key key, int size, Function<Map<Key, Object>, Work<T>> prepare) {
        var operation = new Operation<T>(true);
        enqueue(new Node<>(key, null, size, true, true, operation, null, prepare));
        return operation;
    }

    synchronized <T> StorageQuery<T> query(Key key, int size, Function<Map<Key, Object>, Work<T>> prepare) {
        var handle = new StorageQuery<T>();
        enqueue(new Node<>(key, null, size, true, false, null, new WeakReference<>(handle), prepare));
        return handle;
    }

    private Admission enqueue(Node<?> node) {
        var admission = sealed ? Admission.CLOSED
            : operations >= MAXIMUM_PENDING || bytes + node.bytes > MAXIMUM_BYTES ? Admission.FULL : Admission.QUEUED;
        if (admission == Admission.QUEUED) {
            operations++;
            bytes += node.bytes;
            peakOperations = Math.max(peakOperations, operations);
            peakBytes = Math.max(peakBytes, bytes);
            ingress.addLast(node);
        } else {
            if (node.operation != null)
                node.operation.admitted = admission;
            refuse(node, admission == Admission.CLOSED ? StorageResult.Failure.CLOSED : StorageResult.Failure.CAPACITY);
        }
        return admission;
    }

    /** Preserves the existing animation producer's single ingress monitor; no handle polling occurs here. */
    public synchronized Admission offerRoaming(UUID player, PublishedModelId identity, NamedRoamingSnapshot payload) {
        var snapshot = PreferenceCodec.snapshot(payload);
        var key = new RoamingKey(player, identity);
        return enqueue(new Node<>(key, snapshot, preferences.size(key, 2 + snapshot.names().length * 37), false, false,
            null, null, ignored -> preferences.roamingWrite(player, identity, snapshot)));
    }

    public synchronized Admission offerUsage(StorageIndex<com.elfmcys.ysm.natives.storage.NativeCacheRemote> index,
        Hash256 container, long utcSeconds) {
        if (utcSeconds < 0)
            throw new IllegalArgumentException("Storage event time must be nonnegative");
        var key = new UsageKey(Objects.requireNonNull(index), Objects.requireNonNull(container));
        return enqueue(new Node<>(key, utcSeconds, 256, false, false, null, null, ignored -> cancelled -> index
            .run((nativeIndex, token) -> nativeIndex.maxUsage(container, utcSeconds, token), cancelled)));
    }

    public Operation<NamedRoamingSnapshot> clearRoaming(UUID player, PublishedModelId identity) {
        return preferences.clearRoaming(player, identity);
    }

    /** Captures a prefix at logical admission, including only already failed latest values as retry candidates. */
    public synchronized Operation<Void> flush() {
        var operation = new Operation<Void>(false);
        var marker = new Node<Void>(null, null, 256, true, true, operation, null, null);
        enqueue(marker);
        return operation;
    }

    /** Only the process main-thread owner calls this method. */
    public Optional<CompletionStage<List<WriteResult>>> tick(long elapsedNanos) {
        acceptCompletions();
        if (inFlight != null && inFlight.result().toCompletableFuture().isDone()) {
            inFlight = null;
            active = List.of();
        }
        List<Node<?>> incoming;
        synchronized (this) {
            incoming = new ArrayList<>(ingress);
            ingress.clear();
        }
        for (var node : incoming)
            admit(node, elapsedNanos);
        if (inFlight != null || pending.isEmpty())
            return Optional.empty();
        boolean urgent = pending.stream().anyMatch(node -> node.boundary);
        if (!urgent && pending.size() < MAXIMUM_BATCH && elapsedNanos - firstPending < PERIOD_NANOS)
            return Optional.empty();
        return Optional.of(startBatch());
    }

    private <T> void admit(Node<T> node, long now) {
        boolean hadPending = !pending.isEmpty();
        if (node.boundary)
            coalescing.clear();
        if (node.prepare == null) {
            var prefix = new LinkedHashSet<Node<?>>();
            prefix.addAll(overlay.values());
            prefix.addAll(independentFailures);
            active.stream().filter(previous -> previous.value == null && previous.prepare != null).forEach(prefix::add);
            pending.stream().filter(previous -> previous.value == null && previous.prepare != null)
                .forEach(prefix::add);
            for (var failed : new ArrayList<>(overlay.values()))
                if (failed.retryable()) {
                    var retry = retry(failed);
                    overlay.put(failed.key, retry);
                    release(failed);
                    reserveRetry(retry);
                    pending.addLast(retry);
                    prefix.remove(failed);
                    prefix.add(retry);
                }
            node.prefix = List.copyOf(prefix);
            // A replaced write can still finish UNKNOWN after this prefix is captured.
            var replaced = new LinkedHashSet<>(active);
            replaced.addAll(pending);
            replaced.removeAll(prefix);
            node.replacedPrefix = replaced.stream().filter(previous -> previous.value != null).toList();
        } else {
            var snapshot = new LinkedHashMap<Key, Object>();
            int snapshotBytes = 0;
            if (node.value == null) {
                for (var entry : overlay.entrySet()) {
                    if (node.key != null && !node.independent && !node.key.equals(entry.getKey()))
                        continue;
                    snapshot.put(entry.getKey(), entry.getValue().value);
                    snapshotBytes += entry.getValue().bytes;
                }
                synchronized (this) {
                    if (bytes + snapshotBytes > MAXIMUM_BYTES) {
                        if (node.operation != null)
                            node.operation.admitted = Admission.FULL;
                        refuse(node, StorageResult.Failure.CAPACITY);
                        release(node);
                        return;
                    }
                    bytes += snapshotBytes;
                    node.bytes += snapshotBytes;
                    peakBytes = Math.max(peakBytes, bytes);
                }
            }
            node.work = node.prepare.apply(Collections.unmodifiableMap(snapshot));
            if (node.value != null) {
                var old = overlay.put(node.key, node);
                var merge = node.boundary ? null : coalescing.put(node.key, node);
                if (node.key instanceof UsageKey && old != null) {
                    node.value = Math.max((Long)old.value, (Long)node.value);
                    var key = (UsageKey)node.key;
                    var maximum = (Long)node.value;
                    node.work = cancelled -> (StorageResult<T>)key.index()
                        .run((index, token) -> index.maxUsage(key.container(), maximum, token), cancelled);
                }
                if (merge != null) {
                    pending.remove(merge);
                    supersede(merge);
                }
                if (old != null && old.finished && !old.unknown())
                    release(old);
            }
        }
        if (node.operation != null)
            node.operation.admitted = Admission.ACCEPTED;
        if (node.key != null && node.value == null && node.prepare != null && overlay.containsKey(node.key)) {
            execute(node, null);
            return;
        }
        if (!hadPending && pending.isEmpty())
            firstPending = now;
        pending.addLast(node);
    }

    private <T> Node<T> retry(Node<T> failed) {
        var retry = new Node<T>(failed.key, failed.value, failed.bytes, false, false, new Operation<>(false), null,
            failed.prepare);
        retry.operation.admitted = Admission.ACCEPTED;
        retry.work = failed.work;
        return retry;
    }

    private synchronized void reserveRetry(Node<?> node) {
        operations++;
        bytes += node.bytes;
        peakOperations = Math.max(peakOperations, operations);
        peakBytes = Math.max(peakBytes, bytes);
    }

    private CompletionStage<List<WriteResult>> startBatch() {
        var batch = new ArrayList<Node<?>>();
        int size = 0;
        while (!pending.isEmpty() && batch.size() < MAXIMUM_BATCH) {
            var next = pending.peekFirst();
            if (!batch.isEmpty() && size + next.bytes > MAXIMUM_BATCH_BYTES)
                break;
            batch.add(pending.removeFirst());
            size += next.bytes;
            if (next.prepare == null)
                break;
        }
        coalescing.clear();
        active = List.copyOf(batch);
        try {
            inFlight = database.submitTask(cancelled -> {
                for (var node : batch)
                    execute(node, cancelled);
                return new StorageResult<>(StorageResult.Outcome.FOUND, StorageResult.Failure.NONE, false, List.of());
            });
        } catch (RuntimeException | Error failure) {
            // Scheduling can fail before the batch has a result owner in DatabaseWork.
            for (var node : batch) {
                fail(node, failure);
                publishFailure(node);
                completed.add(node);
            }
            active = List.of();
            return CompletableFuture
                .completedFuture(batch.stream().filter(node -> node.query == null).map(Node::observation).toList());
        }
        var report = new CompletableFuture<List<WriteResult>>();
        inFlight.result().whenComplete((result, failure) -> {
            for (var node : batch)
                if (node.result == null && node.failure == null) {
                    if (failure != null)
                        fail(node, failure);
                    else
                        refuse(node, result.failure());
                    if (node.failure != null)
                        publishFailure(node);
                    completed.add(node);
                }
            report.complete(batch.stream().filter(node -> node.query == null && node.prepare != null)
                .map(Node::observation).toList());
        });
        return report.minimalCompletionStage();
    }

    private <T> void execute(Node<T> node, NativeCancellation cancelled) {
        StorageQuery<T> query = node.query == null ? null : node.query.get();
        try {
            if (node.query != null && (query == null || !query.active())
                || node.operation != null && node.operation.cancelled) {
                node.result = StorageResult.refused(StorageResult.Failure.CANCELLED);
            } else if (node.prepare == null) {
                // Prefix effects run before this marker, including any explicit retries selected at admission.
                boolean saved = node.prefix.stream()
                    .allMatch(previous -> previous.query != null || previous.committed()
                        || previous.result != null && previous.result.succeeded() && previous.value == null)
                    && node.replacedPrefix.stream().noneMatch(Node::unknown);
                var observations = new ArrayList<>(node.prefix);
                node.replacedPrefix.stream().filter(Node::unknown).forEach(observations::add);
                node.operation.report =
                    observations.stream().filter(previous -> previous.query == null).map(Node::observation).toList();
                node.result =
                    new StorageResult<>(saved ? StorageResult.Outcome.COMMITTED : StorageResult.Outcome.FAILED,
                        saved ? StorageResult.Failure.NONE : StorageResult.Failure.INTERNAL, saved, List.of());
            } else {
                if (cancelled != null)
                    executed++;
                node.result = node.work.run(cancelled);
            }
        } catch (StorageResult.OpenException failure) {
            var result = failure.result();
            node.result = new StorageResult<>(result.outcome(), result.failure(), result.committed(), List.of());
        } catch (RuntimeException | Error failure) {
            fail(node, failure);
        } finally {
            if (query != null)
                query.publish(node.result, node.failure);
            if (node.operation != null)
                publish(node);
            completed.add(node);
        }
    }

    private static <T> void publishFailure(Node<T> node) {
        publish(node);
        var query = node.query == null ? null : node.query.get();
        if (query != null)
            query.publish(null, node.failure);
    }

    private static <T> void publish(Node<T> node) {
        if (node.operation == null)
            return;
        if (node.failure == null)
            node.operation.completion.complete(node.result);
        else
            node.operation.completion.completeExceptionally(node.failure);
    }

    private static void fail(Node<?> node, Throwable failure) {
        node.failure = failure instanceof StorageDeliveryException delivery ? delivery
            : new StorageDeliveryException(StorageDeliveryException.CommitState.UNKNOWN, failure);
    }

    private static <T> void refuse(Node<T> node, StorageResult.Failure failure) {
        node.result = StorageResult.refused(failure);
        var query = node.query == null ? null : node.query.get();
        if (query != null)
            query.publish(node.result, null);
        if (node.operation != null)
            publish(node);
    }

    private <T> void supersede(Node<T> node) {
        node.result =
            new StorageResult<>(StorageResult.Outcome.SUPERSEDED, StorageResult.Failure.NONE, false, List.of());
        node.finished = true;
        publish(node);
        release(node);
    }

    private void acceptCompletions() {
        Node<?> node;
        while ((node = completed.poll()) != null) {
            node.finished = true;
            // These commit facts survive later values and keep their capacity until close reports them.
            if (node.value != null && node.unknown()
                || node.independent && node.prepare != null && !node.committed() && (node.failure != null
                    || !node.result.succeeded() && node.result.failure() != StorageResult.Failure.CANCELLED)) {
                independentFailures.add(node);
                continue;
            }
            if (node.value != null && overlay.get(node.key) == node && !node.committed())
                continue;
            if (node.value != null)
                overlay.remove(node.key, node);
            release(node);
        }
    }

    private synchronized void release(Node<?> node) {
        if (!node.released) {
            node.released = true;
            operations--;
            bytes -= node.bytes;
        }
    }

    public synchronized Metrics metrics() {
        return new Metrics(operations, bytes, peakOperations, peakBytes, executed);
    }

    /** Blocking lifecycle drain at the owner's exclusive shutdown window; no ordinary tick or callback is required. */
    public List<WriteResult> closeAndDrain() {
        var reported = new LinkedHashSet<Node<?>>();
        reported.addAll(active);
        reported.addAll(pending);
        reported.addAll(overlay.values());
        reported.addAll(independentFailures);
        synchronized (this) {
            if (closedReport != null)
                return closedReport;
            sealed = true;
            reported.addAll(ingress);
            for (var node : ingress) {
                if (node.operation != null)
                    node.operation.admitted = Admission.CLOSED;
                refuse(node, StorageResult.Failure.CLOSED);
                release(node);
            }
            ingress.clear();
        }
        do {
            tick(Long.MAX_VALUE);
            if (inFlight != null)
                inFlight.result().toCompletableFuture().handle((value, failure) -> null).join();
        } while (inFlight != null || !pending.isEmpty());
        acceptCompletions();
        var report = reported.stream().filter(node -> node.query == null).map(Node::observation).toList();
        overlay.values().forEach(this::release);
        independentFailures.forEach(this::release);
        overlay.clear();
        independentFailures.clear();
        closedReport = List.copyOf(report);
        return closedReport;
    }
}
