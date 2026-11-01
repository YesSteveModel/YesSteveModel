package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.model.storage.CacheRegion;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.ResourceFailure;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.util.CleanerUtil;
import com.elfmcys.ysm.util.Closeable;
import com.elfmcys.ysm.util.ResourceDiagnostics;
import java.lang.ref.Cleaner;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * Shares exact preparation Flights and retains host-published Ready targets.
 *
 * <p><b>Invariant:</b> A Ready result follows complete candidate adoption by the texture publisher.
 * A completed loader future alone is not Ready. Each acquisition has a separate lease, including
 * consumers that share one Flight. Cache eviction drops cache references, not live consumer interests.
 *
 * <p><b>Execution:</b> Internal maps use the cache monitor. The host owner drives tick and publication;
 * loaders return asynchronous outcomes without waiting for that owner. CurrentLookup can run under
 * the cache monitor. Lease users must serialize each handle's queries with its close.
 *
 * <p><b>Ownership:</b> The cache disposes each accepted candidate once: publish it or reject it through
 * the supplied closer. Stale and cancelled work cannot publish into a replacement Flight.
 *
 * <p><b>Seam:</b> [Ready publication and resource ownership](docs/architecture/model-management/client-resources/overview.md#seam-contract).
 */
public final class ModelRenderTargetCache implements AutoCloseable {
    static final int MAX_IN_FLIGHT = 256;
    static final int MAX_BACKGROUND_IN_FLIGHT = MAX_IN_FLIGHT - 2;
    private static final int MAX_RELEASES_PER_TICK = 1_024;
    private static final AcquireResult.Pending PENDING = new AcquireResult.Pending();

    private final Object lock = new Object();
    private final ClientResourceScheduler scheduler;
    private final Map<ResourceKey, ReadyEntry> ready = new HashMap<>();
    private final LinkedHashMap<ResourceKey, ReadyEntry> unused = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<ResourceKey, Flight> pending = new HashMap<>();
    private final ArrayDeque<Completion> localCompletions = new ArrayDeque<>();
    private final ArrayDeque<Completion> backgroundCompletions = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<LeaseState> releases = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<LeaseState> acquisitions = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Object> closedOwners = new ConcurrentLinkedQueue<>();
    private final Consumer<ModelRenderTarget> rejectedTargetCloser;
    private final Consumer<Throwable> contractViolationReporter;
    private final HostTexturePublisher texturePublisher;
    private final IntSupplier dispositionLimit;
    private final IntSupplier retentionCount;
    private int backgroundInFlight;
    private boolean closed;

    public ModelRenderTargetCache(ClientResourceScheduler scheduler) {
        this(scheduler, ModelRenderTarget::close, ModelRenderTargetCache::reportContractViolation);
    }

    public ModelRenderTargetCache(ClientResourceScheduler scheduler, Consumer<ModelRenderTarget> rejectedTargetCloser) {
        this(scheduler, rejectedTargetCloser, ModelRenderTargetCache::reportContractViolation);
    }

    ModelRenderTargetCache(ClientResourceScheduler scheduler, Consumer<ModelRenderTarget> rejectedTargetCloser,
        Consumer<Throwable> contractViolationReporter) {
        this(scheduler, rejectedTargetCloser, contractViolationReporter, HostTexturePublisher.production(),
            () -> YesSteveModel.isMobilePlatform() ? 2 : 4, () -> ClientConfig.MODEL_RETENTION_COUNT.get());
    }

    ModelRenderTargetCache(ClientResourceScheduler scheduler, Consumer<ModelRenderTarget> rejectedTargetCloser,
        Consumer<Throwable> contractViolationReporter, HostTexturePublisher texturePublisher,
        IntSupplier dispositionLimit) {
        this(scheduler, rejectedTargetCloser, contractViolationReporter, texturePublisher, dispositionLimit,
            () -> ClientConfig.MODEL_RETENTION_COUNT.get());
    }

    ModelRenderTargetCache(ClientResourceScheduler scheduler, Consumer<ModelRenderTarget> rejectedTargetCloser,
        Consumer<Throwable> contractViolationReporter, HostTexturePublisher texturePublisher,
        IntSupplier dispositionLimit, IntSupplier retentionCount) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.rejectedTargetCloser = Objects.requireNonNull(rejectedTargetCloser, "rejectedTargetCloser");
        this.contractViolationReporter = Objects.requireNonNull(contractViolationReporter, "contractViolationReporter");
        this.texturePublisher = Objects.requireNonNull(texturePublisher, "texturePublisher");
        this.dispositionLimit = Objects.requireNonNull(dispositionLimit, "dispositionLimit");
        this.retentionCount = Objects.requireNonNull(retentionCount, "retentionCount");
    }

    public ResourceLease getOrStart(ResourceRequest request, ModelContent content, RenderTargetKey key, Loader loader,
        CurrentLookup currentLookup, Object workOwner, WorkPriority priority) {
        return acquire(request, content, key, loader, currentLookup, false, false, false, null, workOwner, priority);
    }

    public Optional<ResourceLease> findReady(ResourceRequest request, ModelContent content, RenderTargetKey key,
        CurrentLookup currentLookup) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(currentLookup, "currentLookup");
        if (!request.modelId().equals(content.modelId())) {
            throw new IllegalArgumentException("Resource request and content identity differ");
        }
        var resourceKey = new ResourceKey(content.representation().identity(), key,
            content.storageBinding() == null ? null : content.storageBinding().region());
        synchronized (lock) {
            requireOpen();
            var cached = ready.get(resourceKey);
            if (cached == null || !currentLookup.isCurrent(resourceKey.identity, key, request)) {
                return Optional.empty();
            }
            var lease = new ResourceLeaseImpl(this, cached, request, currentLookup);
            acquisitions.add(lease.state);
            return Optional.of(lease);
        }
    }

    public ResourceLease getOrStartRequired(ResourceRequest request, ModelContent content, RenderTargetKey key,
        Function<BooleanSupplier, ModelRenderTargetLoader.LoadResult> loader, CurrentLookup currentLookup,
        Object workOwner, Executor terminalOwner) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(terminalOwner, "terminalOwner");
        return acquire(request, content, key,
            (cancelled, dispatch) -> CompletableFuture.completedFuture(loader.apply(cancelled)),
            currentLookup, false, false, true, terminalOwner, workOwner, WorkPriority.LOCAL_PLAYER);
    }

    public ResourceLease getOrStartRequiredAsync(ResourceRequest request, ModelContent content, RenderTargetKey key,
        Loader loader, CurrentLookup currentLookup, Object workOwner, Executor terminalOwner) {
        return acquire(request, content, key, loader, currentLookup, false, false, true,
            Objects.requireNonNull(terminalOwner), workOwner, WorkPriority.LOCAL_PLAYER);
    }

    public CompletableFuture<Optional<ResourceLease>> getOrStartOffline(ResourceRequest request, ModelContent content,
        RenderTargetKey key, Loader loader, CurrentLookup currentLookup, Object workOwner, WorkPriority priority) {
        var lease = acquire(request, content, key, loader, currentLookup, true, false, false, null, workOwner, priority);
        return offlineResult(lease, request);
    }

    public CompletableFuture<Optional<ResourceLease>> getOrStartCached(ResourceRequest request, ModelContent content,
        RenderTargetKey key, Loader loader, CurrentLookup currentLookup, Object workOwner, WorkPriority priority) {
        final ResourceLease lease;
        synchronized (lock) {
            var resourceKey = new ResourceKey(content.representation().identity(), key,
                content.storageBinding() == null ? null : content.storageBinding().region());
            var cached = ready.get(resourceKey);
            var readyIsCurrent = cached != null && currentLookup.isCurrent(resourceKey.identity, key, request);
            if (!readyIsCurrent && pending.containsKey(resourceKey)) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            lease = acquire(request, content, key, loader, currentLookup, true, true, false, null, workOwner, priority);
        }
        return offlineResult(lease, request);
    }

    private CompletableFuture<Optional<ResourceLease>> offlineResult(ResourceLease lease, ResourceRequest request) {
        var result = new CompletableFuture<Optional<ResourceLease>>();
        result.whenComplete((ignored, error) -> {
            if (result.isCancelled()) {
                lease.cancelPending();
            }
        });
        terminal(lease).whenComplete((outcome, error) -> {
            if (error != null) {
                lease.close();
                result.completeExceptionally(error);
                return;
            }
            if (outcome instanceof AcquireResult.Ready && !result.isDone() && lease.isCurrent(request)) {
                if (!result.complete(Optional.of(lease))) {
                    lease.close();
                }
            } else if (outcome instanceof AcquireResult.Failed failed) {
                lease.close();
                var failure = failed.failure();
                var terminalFailure = failure.kind() == ResourceFailure.Kind.DETERMINISTIC;
                // Compiler wrappers preserve the allocation failure as a cause, not as the outer exception.
                for (var cause = failure.cause(); cause != null && !terminalFailure; cause = cause.getCause()) {
                    terminalFailure = cause instanceof OutOfMemoryError;
                }
                if (terminalFailure) {
                    result.completeExceptionally(failure.cause());
                } else {
                    result.complete(Optional.empty());
                }
            } else {
                lease.close();
                result.complete(Optional.empty());
            }
        });
        return result;
    }

    private ResourceLease acquire(ResourceRequest request, ModelContent content, RenderTargetKey key, Loader loader,
        CurrentLookup currentLookup, boolean offline, boolean cacheOnly, boolean requiredBootstrap,
        Executor terminalOwner, Object workOwner, WorkPriority priority) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(currentLookup, "currentLookup");
        Objects.requireNonNull(workOwner, "workOwner");
        Objects.requireNonNull(priority, "priority");
        if (!request.modelId().equals(content.modelId())) {
            throw new IllegalArgumentException("Resource request and content identity differ");
        }

        var resourceKey = new ResourceKey(content.representation().identity(), key,
            content.storageBinding() == null ? null : content.storageBinding().region());
        Flight replaced = null;
        Flight overflow = null;
        final Flight flight;
        final ResourceLeaseImpl lease;
        final boolean start;
        synchronized (lock) {
            requireOpen();
            var cached = ready.get(resourceKey);
            if (cached != null && currentLookup.isCurrent(resourceKey.identity, key, request)) {
                var result = new ResourceLeaseImpl(this, cached, request, currentLookup);
                acquisitions.add(result.state);
                return result;
            }
            var existing = pending.get(resourceKey);
            if (existing != null && (existing.workOwner != workOwner || !offline && existing.offline
                || !cacheOnly && existing.cacheOnly)) {
                removeFlight(existing);
                replaced = existing;
                existing = null;
            }
            start = existing == null;
            if (start) {
                existing = new Flight(resourceKey, request, currentLookup, offline, cacheOnly, workOwner,
                    scheduler.dispatch(priority));
                if (!registerFlight(existing)) {
                    overflow = existing;
                }
            }
            flight = existing;
            var interest = new Interest();
            flight.interests.add(interest);
            lease = new ResourceLeaseImpl(this, flight, interest, request, currentLookup);
            if (!start && priority == WorkPriority.LOCAL_PLAYER) {
                promoteFlight(flight);
            }
        }
        if (replaced != null) {
            replaced.cancel(new CancellationException("A resource request replaced an ineligible flight"));
        }
        if (overflow != null) {
            acceptOrEnqueue(overflow,
                new ModelRenderTargetLoader.LoadResult.Failed(new ResourceFailure(ResourceFailure.Kind.TRANSIENT,
                    new IllegalStateException("Resource in-flight capacity is full"))),
                null, requiredBootstrap, terminalOwner);
        } else if (start) {
            start(flight, loader, requiredBootstrap, terminalOwner);
        }
        return lease;
    }

    private boolean registerFlight(Flight flight) {
        var background = flight.dispatch.priority() == WorkPriority.BACKGROUND;
        if (pending.size() >= MAX_IN_FLIGHT || background && backgroundInFlight >= MAX_BACKGROUND_IN_FLIGHT) {
            return false;
        }
        pending.put(flight.key, flight);
        if (background) {
            backgroundInFlight++;
        }
        return true;
    }

    private boolean removeFlight(Flight flight) {
        if (!pending.remove(flight.key, flight)) {
            return false;
        }
        if (flight.dispatch.priority() == WorkPriority.BACKGROUND) {
            backgroundInFlight--;
        }
        return true;
    }

    private void promoteFlight(Flight flight) {
        if (flight.dispatch.priority() == WorkPriority.LOCAL_PLAYER) {
            return;
        }
        flight.dispatch.promoteLocalPlayer();
        if (flight.dispatch.priority() == WorkPriority.LOCAL_PLAYER) {
            backgroundInFlight--;
            var completion = flight.completion;
            if (completion != null && backgroundCompletions.remove(completion)) {
                localCompletions.addLast(completion);
            }
        }
    }

    public void clearSession(Object workOwner) {
        closedOwners.add(Objects.requireNonNull(workOwner, "workOwner"));
    }

    public void tick() {
        processAcquisitions();
        processClosedOwners();
        processReleases();
        var limit = dispositionLimit.getAsInt();
        if (limit < 1) {
            throw new IllegalStateException("Model result disposition limit must be positive");
        }
        var disposedBackground = false;
        for (var disposed = 0; disposed < limit; disposed++) {
            final Completion completion;
            synchronized (lock) {
                if (!disposedBackground && disposed == limit - 1 && !backgroundCompletions.isEmpty()
                    || localCompletions.isEmpty()) {
                    completion = backgroundCompletions.pollFirst();
                    disposedBackground = true;
                } else {
                    completion = localCompletions.pollFirst();
                }
                if (completion != null) {
                    completion.flight.completion = null;
                }
            }
            if (completion == null) {
                break;
            }
            if (completion.guard != null && !completion.guard.claim()) {
                continue;
            }
            acceptTerminal(completion.flight, completion.outcome, completion.error);
        }
        evictUnused();
    }

    public int loadingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    int readyCount() {
        synchronized (lock) {
            return ready.size();
        }
    }

    int unusedCount() {
        synchronized (lock) {
            return unused.size();
        }
    }

    /**
     * Observes terminal preparation for a lease created by this cache.
     * Completion does not transfer or create a lease; the caller must keep its original lease open.
     * The caller must only observe this future, without completing or obtruding its shared result.
     *
     * @param lease an open lease from this cache
     * @return a non-null future whose result the caller borrows under the original lease
     * @throws IllegalArgumentException if the lease belongs to another implementation or cache
     */
    public CompletableFuture<AcquireResult> terminal(ResourceLease lease) {
        var owned = ownedLease(lease);
        var flight = owned.state.flight;
        return flight == null ? CompletableFuture.completedFuture(owned.readyResult()) : flight.terminal;
    }

    @Override
    public void close() {
        var cancellations = new ArrayList<Flight>();
        var queued = new ArrayList<Completion>();
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            ready.clear();
            unused.clear();
            cancellations.addAll(pending.values());
            for (var flight : cancellations) {
                removeFlight(flight);
            }
            queued.addAll(localCompletions);
            queued.addAll(backgroundCompletions);
            localCompletions.clear();
            backgroundCompletions.clear();
            for (var completion : queued) {
                completion.flight.completion = null;
                cancellations.add(completion.flight);
            }
            acquisitions.clear();
            for (LeaseState state; (state = releases.poll()) != null;) {
                state.detach();
            }
        }
        cancellations.forEach(flight -> flight.cancel(new CancellationException("Resource owner was closed")));
        queued.forEach(completion -> {
            if (completion.guard != null && completion.guard.claim()) {
                rejectCandidate(((ModelRenderTargetLoader.LoadResult.Ready)completion.outcome).candidate());
            }
        });
    }

    private void processAcquisitions() {
        for (LeaseState state; (state = acquisitions.poll()) != null;) {
            synchronized (lock) {
                var entry = state.ready;
                if (entry != null && ready.get(entry.key) == entry) {
                    entry.consumers++;
                    unused.remove(entry.key);
                }
            }
        }
    }

    private void processClosedOwners() {
        for (Object owner; (owner = closedOwners.poll()) != null;) {
            var cancellations = new ArrayList<Flight>();
            synchronized (lock) {
                for (var flight : pending.values()) {
                    if (flight.workOwner == owner) {
                        cancellations.add(flight);
                    }
                }
                cancellations.forEach(this::removeFlight);
            }
            cancellations.forEach(flight -> flight.cancel(new CancellationException("Resource session ended")));
        }
    }

    private void processReleases() {
        for (var count = 0; count < MAX_RELEASES_PER_TICK; count++) {
            var state = releases.poll();
            if (state == null) {
                return;
            }
            Flight cancellation = null;
            synchronized (lock) {
                var flight = state.flight;
                var entry = state.ready;
                if (flight != null && flight.interests.remove(state.interest) && flight.result == PENDING
                    && flight.interests.isEmpty() && removeFlight(flight)) {
                    cancellation = flight;
                } else if (entry != null && entry.consumers > 0) {
                    entry.consumers--;
                    if (entry.consumers == 0 && ready.get(entry.key) == entry) {
                        unused.put(entry.key, entry);
                        ResourceDiagnostics.log("render target unused: {} unused={} ready={}", entry.key,
                            unused.size(), ready.size());
                    }
                }
                state.detach();
            }
            if (cancellation != null) {
                cancellation.cancel(new CancellationException("The last resource interest was released"));
            }
        }
    }

    private void evictUnused() {
        var limit = retentionCount.getAsInt();
        if (limit < 0) {
            throw new IllegalStateException("Unused model target limit cannot be negative");
        }
        synchronized (lock) {
            while (unused.size() > limit) {
                var iterator = unused.entrySet().iterator();
                var eldest = iterator.next();
                iterator.remove();
                ready.remove(eldest.getKey(), eldest.getValue());
                ResourceDiagnostics.log("render target evict: {} unused={} ready={} limit={}", eldest.getKey(),
                    unused.size(), ready.size(), limit);
            }
        }
    }

    private ResourceLeaseImpl ownedLease(ResourceLease lease) {
        if (!(lease instanceof ResourceLeaseImpl owned) || owned.cache != this) {
            throw new IllegalArgumentException("Unsupported resource lease");
        }
        return owned;
    }

    private void enqueueRelease(LeaseState state) {
        releases.add(state);
    }

    private void start(Flight flight, Loader loader, boolean requiredBootstrap, Executor terminalOwner) {
        final CompletableFuture<ModelRenderTargetLoader.LoadResult> loading;
        try {
            // Published texture sources retain the probe, so it must not capture the Flight and its result.
            loading = loader.load(flight.cancellation::get, flight.dispatch);
            if (loading == null) {
                acceptOrEnqueue(flight, null, new NullPointerException("Resource loader returned a null future"),
                    requiredBootstrap, terminalOwner);
                return;
            }
        } catch (Throwable error) {
            acceptOrEnqueue(flight, null, error, requiredBootstrap, terminalOwner);
            if (error instanceof Error fatal && !(error instanceof OutOfMemoryError)) {
                throw fatal;
            }
            return;
        }
        var cancellation = flight.guard(() -> loading.cancel(false));
        loading.whenComplete((outcome, error) -> {
            // Cancellation can lose to a successful result before its callback is registered.
            if (cancellation != null) {
                cancellation.close();
            } else if (!(outcome instanceof ModelRenderTargetLoader.LoadResult.Ready)) {
                return;
            }
            acceptOrEnqueue(flight, outcome, error, requiredBootstrap, terminalOwner);
        });
    }

    private void acceptOrEnqueue(Flight flight, ModelRenderTargetLoader.LoadResult outcome, Throwable error,
        boolean requiredBootstrap, Executor terminalOwner) {
        CandidateGuard guard = null;
        if (outcome instanceof ModelRenderTargetLoader.LoadResult.Ready loaded) {
            guard = CandidateGuard.register(flight, () -> rejectCandidate(loaded.candidate()));
            if (guard == null) {
                return;
            }
        }
        if (requiredBootstrap && !flight.cancelled()) {
            dispatchRequiredTerminal(flight, outcome, error, terminalOwner, guard);
            return;
        }
        synchronized (lock) {
            if (!closed) {
                var completion = new Completion(flight, outcome, error, guard);
                flight.completion = completion;
                completionQueue(flight).addLast(completion);
                return;
            }
        }
        flight.cancel(new CancellationException("Resource owner was closed"));
        if (guard != null && guard.claim() && outcome instanceof ModelRenderTargetLoader.LoadResult.Ready loaded) {
            rejectCandidate(loaded.candidate());
        }
    }

    private ArrayDeque<Completion> completionQueue(Flight flight) {
        return flight.dispatch.priority() == WorkPriority.LOCAL_PLAYER ? localCompletions : backgroundCompletions;
    }

    private void dispatchRequiredTerminal(Flight flight, ModelRenderTargetLoader.LoadResult outcome, Throwable error,
        Executor terminalOwner, CandidateGuard guard) {
        try {
            terminalOwner.execute(() -> {
                if (guard == null || guard.claim()) {
                    acceptTerminal(flight, outcome, error);
                }
            });
        } catch (RuntimeException dispatchFailure) {
            if (guard != null && !guard.claim()) {
                return;
            }
            if (outcome instanceof ModelRenderTargetLoader.LoadResult.Ready loaded) {
                rejectCandidate(loaded.candidate());
            }
            acceptTerminal(flight, null, new IllegalStateException(
                "Failed to dispatch required resource terminal to its host", dispatchFailure));
        }
    }

    private void acceptTerminal(Flight flight, ModelRenderTargetLoader.LoadResult outcome, Throwable error) {
        if (error != null) {
            var cause = error;
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof OutOfMemoryError || cause instanceof RejectedExecutionException) {
                finishFailure(flight, new ResourceFailure(ResourceFailure.Kind.TRANSIENT, cause));
            } else if (!(cause instanceof CancellationException && flight.cancelled())) {
                finishContractViolation(flight, error);
            }
        } else if (outcome instanceof ModelRenderTargetLoader.LoadResult.Ready loaded) {
            finishSuccess(flight, loaded.candidate());
        } else if (outcome instanceof ModelRenderTargetLoader.LoadResult.Failed failed) {
            finishFailure(flight, failed.failure());
        } else {
            finishContractViolation(flight, new NullPointerException("Resource loader returned a null outcome"));
        }
    }

    private void finishSuccess(Flight flight, ModelRenderTargetLoader.ModelCandidate candidate) {
        ReadyEntry accepted = null;
        Throwable lookupFailure = null;
        Throwable publicationFailure = null;
        synchronized (lock) {
            if (pending.get(flight.key) == flight) {
                try {
                    if (flight.currentLookup.isCurrent(flight.key.identity, flight.key.key, flight.request)) {
                        try {
                            var target = candidate.publish(texturePublisher);
                            var entry = new ReadyEntry(flight.key, target, flight.interests.size());
                            ready.put(flight.key, entry);
                            for (var interest : flight.interests) {
                                interest.state.attachReady(entry);
                            }
                            flight.interests.clear();
                            if (entry.consumers == 0) {
                                unused.put(flight.key, entry);
                            }
                            removeFlight(flight);
                            accepted = entry;
                        } catch (Throwable failure) {
                            removeFlight(flight);
                            ready.remove(flight.key);
                            unused.remove(flight.key);
                            publicationFailure = failure;
                        }
                    } else {
                        removeFlight(flight);
                    }
                } catch (Throwable failure) {
                    removeFlight(flight);
                    lookupFailure = failure;
                }
            }
        }
        if (accepted != null) {
            flight.finish(new AcquireResult.Ready(accepted.target));
            return;
        }
        rejectCandidate(candidate);
        var resourceFailure = publicationFailure instanceof OutOfMemoryError ? publicationFailure
            : lookupFailure instanceof OutOfMemoryError ? lookupFailure : null;
        var cause = resourceFailure != null ? resourceFailure : publicationFailure != null
            ? new IllegalStateException("Model texture publication failed", publicationFailure)
            : lookupFailure == null ? new CancellationException("Resource flight was no longer current")
                : new IllegalStateException("Resource current lookup failed", lookupFailure);
        flight.finish(new AcquireResult.Failed(new ResourceFailure(
            resourceFailure != null || lookupFailure == null && publicationFailure == null
            ? ResourceFailure.Kind.TRANSIENT : ResourceFailure.Kind.DETERMINISTIC, cause)));
        if (lookupFailure != null && resourceFailure == null) {
            contractViolationReporter.accept(cause);
        }
    }

    private void rejectCandidate(ModelRenderTargetLoader.ModelCandidate candidate) {
        try {
            candidate.reject(rejectedTargetCloser);
        } catch (Throwable error) {
            contractViolationReporter
                .accept(new IllegalStateException("Failed to dispose rejected model candidate", error));
        }
    }

    private void finishFailure(Flight flight, ResourceFailure failure) {
        if (flight.offline && failure.kind() != ResourceFailure.Kind.TRANSIENT) {
            failure = new ResourceFailure(ResourceFailure.Kind.TRANSIENT, failure.cause());
        }
        synchronized (lock) {
            removeFlight(flight);
        }
        flight.finish(new AcquireResult.Failed(failure));
    }

    private void finishContractViolation(Flight flight, Throwable cause) {
        var violation = new IllegalStateException("Resource loader violated its outcome contract", cause);
        synchronized (lock) {
            removeFlight(flight);
        }
        flight.finish(new AcquireResult.Failed(new ResourceFailure(ResourceFailure.Kind.DETERMINISTIC, violation)));
        contractViolationReporter.accept(violation);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Resource cache is closed");
        }
    }

    private static void reportContractViolation(Throwable error) {
        YesSteveModel.LOGGER.error("Model render target loader contract violation", error);
    }

    /**
     * Produces one asynchronous candidate or classified failure for an admitted Flight.
     * The returned future and its terminal outcome must be non-null. Cancellation is cooperative;
     * a late candidate still transfers to the cache for rejection. The loader must not publish the candidate.
     */
    @FunctionalInterface
    public interface Loader {
        CompletableFuture<ModelRenderTargetLoader.LoadResult> load(BooleanSupplier cancelled,
            ClientResourceScheduler.Dispatch dispatch);
    }

    /**
     * Answers the current qualification of a new acquisition or publication.
     * The callback can run under the cache monitor and must not wait for work that needs that monitor.
     * It observes changing membership and policy; established immutable content guarantees remain trusted.
     */
    @FunctionalInterface
    public interface CurrentLookup {
        boolean isCurrent(ModelFileIdentity identity, RenderTargetKey key, ResourceRequest request);
    }

    private static final class ResourceLeaseImpl implements ResourceLease {
        private final ModelRenderTargetCache cache;
        private final LeaseState state;
        private final Cleaner.Cleanable cleanable;
        private volatile ResourceRequest originalRequest;
        private volatile CurrentLookup currentLookup;

        private ResourceLeaseImpl(ModelRenderTargetCache cache, Flight flight, Interest interest,
            ResourceRequest originalRequest, CurrentLookup currentLookup) {
            this.cache = cache;
            this.originalRequest = originalRequest;
            this.currentLookup = currentLookup;
            state = new LeaseState(cache, flight, interest);
            interest.state = state;
            cleanable = CleanerUtil.ref(this, state, LeaseState::release);
        }

        private ResourceLeaseImpl(ModelRenderTargetCache cache, ReadyEntry ready, ResourceRequest originalRequest,
            CurrentLookup currentLookup) {
            this.cache = cache;
            this.originalRequest = originalRequest;
            this.currentLookup = currentLookup;
            state = new LeaseState(cache, ready);
            cleanable = CleanerUtil.ref(this, state, LeaseState::release);
        }

        @Override
        public ModelResourcePurpose purpose() {
            requireOpen();
            var key = state.ready == null ? state.flight.key : state.ready.key;
            return key.key.purpose();
        }

        @Override
        public AcquireResult poll() {
            requireOpen();
            return readyResult();
        }

        private AcquireResult readyResult() {
            var entry = state.ready;
            return entry != null ? new AcquireResult.Ready(entry.target) : state.flight.result;
        }

        @Override
        public boolean isCurrent(ResourceRequest request) {
            requireOpen();
            var key = state.ready == null ? state.flight.key : state.ready.key;
            return originalRequest.bakeProfile().equals(request.bakeProfile())
                && originalRequest.purpose() == request.purpose()
                && originalRequest.expressionBinding().equals(request.expressionBinding())
                && currentLookup.isCurrent(key.identity, key.key, request);
        }

        @Override
        public void cancelPending() {
            close();
        }

        @Override
        public void close() {
            cleanable.clean();
            originalRequest = null;
            currentLookup = null;
        }

        private void requireOpen() {
            if (state.released.get() || originalRequest == null) {
                throw new IllegalStateException("Resource lease is closed");
            }
        }
    }

    private static final class LeaseState {
        private ModelRenderTargetCache cache;
        private Flight flight;
        private Interest interest;
        private ReadyEntry ready;
        private final AtomicBoolean released = new AtomicBoolean();

        private LeaseState(ModelRenderTargetCache cache, Flight flight, Interest interest) {
            this.cache = cache;
            this.flight = flight;
            this.interest = interest;
        }

        private LeaseState(ModelRenderTargetCache cache, ReadyEntry ready) {
            this.cache = cache;
            this.ready = ready;
        }

        private void attachReady(ReadyEntry entry) {
            ready = entry;
            flight = null;
            interest = null;
        }

        private void release() {
            if (released.compareAndSet(false, true)) {
                var owner = cache;
                if (owner != null) {
                    owner.enqueueRelease(this);
                }
            }
        }

        private void detach() {
            cache = null;
            flight = null;
            interest = null;
            ready = null;
        }
    }

    private static final class Flight {
        private final ResourceKey key;
        private final ResourceRequest request;
        private final CurrentLookup currentLookup;
        private final boolean offline;
        private final boolean cacheOnly;
        private final Object workOwner;
        private final ClientResourceScheduler.Dispatch dispatch;
        private Completion completion;
        private final Set<Interest> interests = ConcurrentHashMap.newKeySet();
        private final Set<AutoCloseable> cancellationActions = Collections.newSetFromMap(new IdentityHashMap<>());
        private final CompletableFuture<AcquireResult> terminal = new CompletableFuture<>();
        private volatile AcquireResult result = PENDING;
        private final AtomicBoolean cancellation = new AtomicBoolean();
        private boolean terminalState;

        private Flight(ResourceKey key, ResourceRequest request, CurrentLookup currentLookup, boolean offline,
            boolean cacheOnly, Object workOwner, ClientResourceScheduler.Dispatch dispatch) {
            this.key = key;
            this.request = request;
            this.currentLookup = currentLookup;
            this.offline = offline;
            this.cacheOnly = cacheOnly;
            this.workOwner = workOwner;
            this.dispatch = dispatch;
        }

        private boolean cancelled() {
            return cancellation.get();
        }

        private Closeable guard(AutoCloseable action) {
            Objects.requireNonNull(action, "action");
            synchronized (this) {
                if (!terminalState) {
                    cancellationActions.add(action);
                    return () -> {
                        synchronized (Flight.this) {
                            cancellationActions.remove(action);
                        }
                    };
                }
            }
            if (cancelled()) {
                close(action);
            }
            return null;
        }

        private void cancel(CancellationException cause) {
            final ArrayList<AutoCloseable> actions;
            synchronized (this) {
                if (terminalState) {
                    return;
                }
                terminalState = true;
                cancellation.set(true);
                actions = new ArrayList<>(cancellationActions);
                cancellationActions.clear();
            }
            actions.forEach(Flight::close);
            var failed = new AcquireResult.Failed(new ResourceFailure(ResourceFailure.Kind.TRANSIENT, cause));
            result = failed;
            terminal.complete(failed);
        }

        private void finish(AcquireResult outcome) {
            synchronized (this) {
                if (terminalState) {
                    return;
                }
                terminalState = true;
                cancellationActions.clear();
                result = outcome;
            }
            terminal.complete(outcome);
        }

        private static void close(AutoCloseable action) {
            try {
                action.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static final class ReadyEntry {
        private final ResourceKey key;
        private final ModelRenderTarget target;
        private int consumers;

        private ReadyEntry(ResourceKey key, ModelRenderTarget target, int consumers) {
            this.key = key;
            this.target = target;
            this.consumers = consumers;
        }
    }

    private record Completion(Flight flight, ModelRenderTargetLoader.LoadResult outcome, Throwable error,
        CandidateGuard guard) {
    }

    private static final class CandidateGuard {
        private final Runnable disposer;
        private final AtomicBoolean available = new AtomicBoolean(true);
        private Closeable registration;

        private CandidateGuard(Runnable disposer) {
            this.disposer = disposer;
        }

        private static CandidateGuard register(Flight flight, Runnable disposer) {
            var guard = new CandidateGuard(disposer);
            var registration = flight.guard(guard::cancel);
            if (registration == null) {
                guard.cancel();
                return null;
            }
            guard.registration = registration;
            return guard;
        }

        private boolean claim() {
            registration.close();
            return available.compareAndSet(true, false);
        }

        private void cancel() {
            if (available.compareAndSet(true, false)) {
                disposer.run();
            }
        }
    }

    private record ResourceKey(ModelFileIdentity identity, RenderTargetKey key, CacheRegion region) {
        private ResourceKey {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(key, "key");
        }
    }

    private static final class Interest {
        private LeaseState state;
    }
}
