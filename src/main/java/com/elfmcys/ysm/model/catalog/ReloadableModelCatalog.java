package com.elfmcys.ysm.model.catalog;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelCatalog;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogCandidate;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexSnapshot;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogTransition;
import com.elfmcys.ysm.model.catalog.source.CatalogBuildException;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.CatalogRoot;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSource;
import com.elfmcys.ysm.model.catalog.source.ModelSourceResolver;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.domain.ModelScanError;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.domain.ModelScanWarning;
import com.elfmcys.ysm.model.storage.ConvertedCacheCoordinator;
import com.elfmcys.ysm.model.storage.ConvertedSourceIndex;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelStorageInfra;
import com.elfmcys.ysm.util.ThreadScheduling;
import com.elfmcys.ysm.natives.NativeRuntime;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadPolicy;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadRole;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/** Process catalog authority accepting complete snapshots built by one scan coordinator. */
public final class ReloadableModelCatalog implements AutoCloseable {
    private static final int MAX_INPUTS = 100_000;
    private static final long BATCH_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    /** Unreachable converted root of a composition that owns no converted storage. */
    private static final Path DETACHED_CONVERTED_ROOT =
        Path.of(".").toAbsolutePath().normalize().resolve("never-converted");

    private final CatalogReconciler reconciler;
    private final Path convertedRoot;
    private final Pruner pruner;
    private final Registration registration;
    private final Consumer<CatalogScanProgress> progressObserver;
    private final ModelStorageInfra storage;
    private final ThreadFactory coordinatorThreads;
    private final BiFunction<Long, ThreadPolicy, ExecutorService> workerExecutors;
    private final CopyOnWriteArrayList<Consumer<CatalogTransition>> listeners = new CopyOnWriteArrayList<>();

    private volatile CatalogCandidate current;
    private ActiveScan active;
    private boolean activated;
    private boolean initialScanPending = true;
    private boolean closed;
    private long scanSequence;

    public ReloadableModelCatalog(ModelStorageInfra storage, List<ModelCatalogSource> roots,
        BuiltinModelCatalog builtins) {
        this.storage = Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(builtins, "builtins");
        reconciler = new CatalogReconciler(List.copyOf(roots),
            new ModelSourceResolver(new RawModelImporter(builtins.contract()), storage), builtins.contract(),
            builtins.defaultContent());
        try {
            current = reconciler.materializeBuiltins();
        } catch (CatalogBuildException failure) {
            throw new UncheckedIOException("Failed to materialize the builtin catalog", failure);
        }
        convertedRoot = storage.local().root().resolve("converted");
        pruner = ignored -> ConvertedCacheCoordinator.PruneResult.SKIPPED_ACTIVE_CONSUMER;
        registration = storage::registerConverted;
        progressObserver = ignored -> {};
        coordinatorThreads = Thread::new;
        workerExecutors = ReloadableModelCatalog::newWorkerExecutor;
    }

    ReloadableModelCatalog(CatalogReconciler reconciler, CatalogCandidate initial) {
        this(reconciler, initial, Thread::new);
    }

    ReloadableModelCatalog(CatalogReconciler reconciler, CatalogCandidate initial, ThreadFactory coordinatorThreads) {
        this(reconciler, initial, coordinatorThreads, ReloadableModelCatalog::newWorkerExecutor);
    }

    ReloadableModelCatalog(CatalogReconciler reconciler, CatalogCandidate initial, ThreadFactory coordinatorThreads,
        BiFunction<Long, ThreadPolicy, ExecutorService> workerExecutors) {
        this(reconciler, initial, coordinatorThreads, workerExecutors, () -> {
        });
    }

    ReloadableModelCatalog(CatalogReconciler reconciler, CatalogCandidate initial, ThreadFactory coordinatorThreads,
        BiFunction<Long, ThreadPolicy, ExecutorService> workerExecutors, Registration registration) {
        this(reconciler, initial, coordinatorThreads, workerExecutors, registration, ignored -> {});
    }

    ReloadableModelCatalog(CatalogReconciler reconciler, CatalogCandidate initial, ThreadFactory coordinatorThreads,
        BiFunction<Long, ThreadPolicy, ExecutorService> workerExecutors, Registration registration,
        Consumer<CatalogScanProgress> progressObserver) {
        this.storage = null;
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        current = Objects.requireNonNull(initial, "initial");
        convertedRoot = DETACHED_CONVERTED_ROOT;
        pruner = ignored -> ConvertedCacheCoordinator.PruneResult.SKIPPED_ACTIVE_CONSUMER;
        this.coordinatorThreads = Objects.requireNonNull(coordinatorThreads, "coordinatorThreads");
        this.workerExecutors = Objects.requireNonNull(workerExecutors, "workerExecutors");
        this.registration = Objects.requireNonNull(registration, "registration");
        this.progressObserver = Objects.requireNonNull(progressObserver, "progressObserver");
    }

    public CatalogSnapshot current() {
        return current.snapshot();
    }

    public CatalogIndexSnapshot index() {
        return current.index();
    }

    public CatalogCandidate currentCandidate() {
        return current;
    }

    public void activate() {
        final Admission admission;
        synchronized (this) {
            if (closed || activated) {
                return;
            }
            activated = true;
            admission = admit();
        }
        completeStartFailure(admission);
    }

    public CompletableFuture<ReloadResult> reload() {
        final Admission admission;
        synchronized (this) {
            if (closed) {
                return completedFailure("Catalog owner is closed");
            }
            if (!activated) {
                return completedFailure("Catalog scanning has not been activated");
            }
            if (active != null) {
                return CompletableFuture
                    .completedFuture(result(ReloadStatus.BUSY, "Catalog scan is already in progress"));
            }
            admission = admit();
        }
        completeStartFailure(admission);
        return admission.scan.completion;
    }

    private Admission admit() {
        var plan = new ScanPlan(initialScanPending, initialScanPending ? ThreadPolicy.BELOW_NORMAL : ThreadPolicy.LOW);
        var scan = new ActiveScan(++scanSequence, current, plan);
        active = scan;
        try {
            scan.start();
            return new Admission(scan, null);
        } catch (Throwable failure) {
            active = null;
            return new Admission(scan, failure);
        }
    }

    private void completeStartFailure(Admission admission) {
        if (admission.failure != null) {
            diagnose("COORDINATOR_START", admission.failure);
            admission.scan.completion.complete(result(ReloadStatus.FAILED, message(admission.failure)));
        }
    }

    public Subscription subscribe(Consumer<CatalogTransition> listener) {
        Objects.requireNonNull(listener, "listener");
        final CatalogCandidate value;
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("Catalog owner is closed");
            }
            listeners.add(listener);
            value = current;
        }
        var empty = CatalogCandidate.empty();
        notifyObserver(listener,
            new CatalogTransition(empty.index(), empty.snapshot(), value.index(), value.snapshot()));
        return new Subscription(listener);
    }

    public void tick() {
        final ActiveScan scan;
        synchronized (this) {
            scan = active;
        }
        if (scan != null) {
            scan.tick();
        }
    }

    public synchronized boolean busy() {
        return !closed && active != null;
    }

    public synchronized CatalogScanProgress scanProgress() {
        return active == null ? null : active.progress;
    }

    private void publish(ActiveScan scan, CatalogCandidate candidate) {
        final CatalogTransition transition;
        synchronized (this) {
            if (closed || active != scan || current == candidate) {
                return;
            }
            var previous = current;
            current = candidate;
            transition =
                new CatalogTransition(previous.index(), previous.snapshot(), candidate.index(), candidate.snapshot());
        }
        listeners.forEach(listener -> notifyObserver(listener, transition));
    }

    private void finish(ActiveScan scan, ReloadStatus status, String message) {
        final ReloadResult outcome;
        synchronized (this) {
            if (closed || active != scan) {
                return;
            }
            active = null;
            outcome = result(status, message);
        }
        scan.completion.complete(outcome);
    }

    private CompletableFuture<ReloadResult> completedFailure(String message) {
        return CompletableFuture.completedFuture(result(ReloadStatus.FAILED, message));
    }

    private ReloadResult result(ReloadStatus status, String message) {
        var snapshot = current.snapshot();
        return new ReloadResult(status, snapshot.byModelId().size(), snapshot.report().errorCount(), message,
            snapshot.report().warnings());
    }

    @Override
    public void close() {
        final ActiveScan scan;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            scan = active;
            if (scan != null) {
                scan.cancelled.set(true);
                scan.finalizeProgress();
            }
            listeners.clear();
        }
        if (scan != null) {
            scan.cancel();
            scan.awaitCoordinator();
        }
        synchronized (this) {
            active = null;
            current = CatalogCandidate.empty();
        }
        if (scan != null) {
            scan.completion.complete(new ReloadResult(ReloadStatus.FAILED, 0, 0, "Catalog owner is closed"));
        }
    }

    private static void notifyObserver(Consumer<CatalogTransition> observer, CatalogTransition transition) {
        try {
            observer.accept(transition);
        } catch (Throwable failure) {
            diagnose("OBSERVER_NOTIFICATION", failure);
        }
    }

    private static void diagnose(String phase, Throwable failure) {
        try {
            YesSteveModel.LOGGER.error("Catalog work failed phase={}", phase, failure);
        } catch (Throwable ignored) {
            // Diagnostics cannot revoke a publication or strand an accepted request.
        }
    }

    private static ExecutorService newWorkerExecutor(long sequence, ThreadPolicy policy) {
        var workerCount = (int)Math.max(2, Math.min(8, NativeRuntime.availableProcessors() * 3L / 4));
        return Executors.newFixedThreadPool(workerCount, runnable -> {
            var thread = new Thread(ThreadScheduling.entry(ThreadRole.CATALOG_WORKER, policy, runnable),
                "YSM Catalog Scan " + sequence);
            thread.setDaemon(true);
            return thread;
        });
    }

    private final class ActiveScan {
        private final CatalogCandidate previous;
        private final ScanPlan plan;
        private final CompletableFuture<ReloadResult> completion = new CompletableFuture<>();
        private ExecutorService executor;
        private Thread coordinator;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Handoff> latest = new AtomicReference<>();
        private final List<ConvertedSourceIndex> convertedIndexes = new ArrayList<>();
        private final Set<Path> retainedConverted = new LinkedHashSet<>();
        private final LinkedBlockingQueue<Object> outcomes = new LinkedBlockingQueue<>();
        private final Map<CatalogLocation, CatalogRecord> effectiveRecords = new LinkedHashMap<>();
        private final Map<CatalogLocation, CatalogIndexEntry> effectiveIndex = new LinkedHashMap<>();
        private final Map<String, ModelPackDescriptor> effectivePacks = new LinkedHashMap<>();
        private final List<ModelScanError> errors = new ArrayList<>();
        private final List<ModelScanWarning> warnings = new ArrayList<>();
        private final List<CatalogLocation> acceptedOrder = new ArrayList<>();
        private final Set<CatalogLocation> acceptedLocations = new HashSet<>();
        private final Set<Hash256> acceptedModelIds = new HashSet<>();

        private CatalogCandidate lastBuilt;
        private long lastBuildNanos;
        private boolean built;
        private boolean dirty;
        private final long sequence;
        private volatile CatalogScanProgress progress;

        private ActiveScan(long sequence, CatalogCandidate previous, ScanPlan plan) {
            this.previous = previous;
            this.plan = plan;
            this.sequence = sequence;
            progress = new CatalogScanProgress(sequence, CatalogScanProgress.Phase.DISCOVERING, 0, -1);
            lastBuilt = previous;
        }

        private void start() {
            coordinator = coordinatorThreads.newThread(
                ThreadScheduling.entry(ThreadRole.CATALOG_COORDINATOR, ThreadPolicy.BELOW_NORMAL, this::run));
            coordinator.setName("YSM Catalog Reload " + sequence);
            coordinator.setDaemon(true);
            coordinator.start();
        }

        private void run() {
            var startedAt = Instant.now();
            var status = ReloadStatus.COMMITTED;
            var detail = "";
            var phase = "CONVERTED_REGISTRATION";
            try {
                progressObserver.accept(progress);
                synchronized (ReloadableModelCatalog.this) {
                    checkEligible();
                }
                previous.snapshot().byModelId().values()
                    .forEach(record -> effectiveRecords.put(record.location(), record));
                previous.index().entries().forEach(entry -> effectiveIndex.put(entry.location(), entry));
                previous.snapshot().packs().forEach(pack -> effectivePacks.put(pack.hierarchy(), pack));
                registration.register();
                synchronized (ReloadableModelCatalog.this) {
                    checkEligible();
                    initialScanPending = false;
                }
                phase = "SCAN_EXECUTION";
                previous.index().entries().stream().map(CatalogIndexEntry::backingFile).forEach(this::retainConverted);
                executor = workerExecutors.apply(sequence, plan.workerPolicy);
                checkCancelled();
                var value = reconciler.discoverIncremental();
                startedAt = value.startedAt();
                var count = Math.addExact(value.sources().size(), value.packs().size());
                if (count > MAX_INPUTS) {
                    throw new IllegalStateException("Catalog scan input capacity exceeded");
                }
                applyDiscovery(value);
                updateProgress(count == 0 ? CatalogScanProgress.Phase.FINALIZING
                    : CatalogScanProgress.Phase.PROCESSING, 0, count);
                for (var source : value.sources()) {
                    checkCancelled();
                    var location = source.key().root().location(source.key().relativePath());
                    var old = effectiveRecords.get(location);
                    var reusable =
                        old != null && old.binding().content() instanceof ManagedContainer content ? content : null;
                    executor.execute(() -> resolve(() -> reconciler.resolveIncremental(source, reusable)));
                }
                for (var pack : value.packs()) {
                    checkCancelled();
                    executor.execute(() -> resolve(() -> reconciler.resolveIncremental(pack)));
                }
                executor.shutdown();
                var received = 0;
                while (received < count) {
                    checkCancelled();
                    buildIfDue(startedAt);
                    var wait = dirty ? remainingNanos() : Long.MAX_VALUE;
                    var outcome = outcomes.poll(wait, TimeUnit.NANOSECONDS);
                    if (outcome != null) {
                        do {
                            applyOutcome(outcome);
                            received++;
                            updateProgress(received == count ? CatalogScanProgress.Phase.FINALIZING
                                : CatalogScanProgress.Phase.PROCESSING, received, count);
                        } while ((outcome = outcomes.poll()) != null);
                    }
                }
                buildIfDue(startedAt);
                awaitWorkers(false);
                checkCancelled();
                try {
                    reconciler.verifyDiscovery(value);
                    var scopes = value.completeRoots().values().stream().map(root -> root.root()).toList();
                    var observedLocations = value.sources().stream()
                        .map(source -> source.key().root().location(source.key().relativePath()))
                        .collect(Collectors.toUnmodifiableSet());
                    var selected = selectRecords(effectiveRecords, acceptedOrder, acceptedLocations);
                    var retainedIndex =
                        effectiveIndex.values().stream().filter(entry -> observedLocations.contains(entry.location()))
                            .filter(entry -> selected.containsKey(entry.modelId())
                                && selected.get(entry.modelId()).location().equals(entry.location()))
                            .toList();
                    reconciler.replaceIncrementalIndex(scopes, convertedIndexes, retainedIndex);
                    dirty |= applyConfirmedDeletions(value);
                    if (plan.initial && (storage == null ? pruner.prune(retainedConverted)
                        : storage.prune(retainedConverted, scopes)) == ConvertedCacheCoordinator.PruneResult.FAILED) {
                        addFailure("CONVERTED_PRUNE_FAILED", new IOException("Converted prune failed"));
                    }
                } catch (Throwable failure) {
                    addFailure("CATALOG_FINALIZATION_FAILED", failure);
                    diagnose("SCAN_FINALIZATION", failure);
                    status = ReloadStatus.FAILED;
                    detail = message(failure);
                }
            } catch (Throwable failure) {
                status = ReloadStatus.FAILED;
                detail = message(failure);
                if (!cancelled.get()) {
                    addFailure(phase, failure);
                    diagnose(phase, failure);
                }
            } finally {
                finalizeProgress();
                awaitWorkers(true);
                disposePendingOutcomes();
            }
            if (!cancelled.get()) {
                try {
                    dirty |= !warnings.equals(lastBuilt.snapshot().report().warnings());
                    if (!built) {
                        dirty |= !errors.equals(previous.snapshot().report().errors());
                    }
                    if (dirty) {
                        TimeUnit.NANOSECONDS.sleep(remainingNanos());
                        checkCancelled();
                        buildProjection(startedAt);
                    }
                } catch (Throwable failure) {
                    status = ReloadStatus.FAILED;
                    detail = message(failure);
                    if (!cancelled.get()) {
                        diagnose("FINAL_HANDOFF", failure);
                    }
                }
                latest.set(new Handoff(lastBuilt, status, detail));
            }
        }

        private void updateProgress(CatalogScanProgress.Phase phase, int processed, int total) {
            final CatalogScanProgress value;
            synchronized (this) {
                value = new CatalogScanProgress(sequence,
                    cancelled.get() ? CatalogScanProgress.Phase.FINALIZING : phase, processed, total);
                progress = value;
            }
            progressObserver.accept(value);
        }

        private synchronized void finalizeProgress() {
            var value = progress;
            progress = new CatalogScanProgress(sequence, CatalogScanProgress.Phase.FINALIZING,
                value.processed(), value.total());
        }

        private void checkEligible() throws InterruptedException {
            checkCancelled();
            if (closed || active != this) {
                throw new InterruptedException("Catalog scan is no longer current");
            }
        }

        private void checkCancelled() throws InterruptedException {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Catalog scan cancelled");
            }
        }

        private void resolve(Supplier<Object> operation) {
            Object outcome;
            try {
                outcome = operation.get();
            } catch (Throwable failure) {
                outcome = failure;
            }
            outcomes.add(outcome);
        }

        private void applyOutcome(Object outcome) throws Throwable {
            if (outcome instanceof CatalogReconciler.ResolvedSource source) {
                try {
                    applySource(source);
                } finally {
                    if (source.content() != null) {
                        source.content().close();
                    }
                }
            } else if (outcome instanceof CatalogReconciler.ResolvedPack pack) {
                applyPack(pack);
            } else if (outcome instanceof Throwable failure) {
                throw failure;
            } else {
                throw new IllegalStateException("Catalog worker returned an unsupported outcome");
            }
        }

        private long remainingNanos() {
            return built ? Math.max(0, BATCH_INTERVAL_NANOS - (System.nanoTime() - lastBuildNanos)) : 0;
        }

        private void buildIfDue(Instant startedAt) {
            if (dirty && remainingNanos() == 0) {
                buildProjection(startedAt);
                latest.set(new Handoff(lastBuilt, null, ""));
            }
        }

        private void addFailure(String code, Throwable failure) {
            errors.add(ModelScanError.infrastructure(CatalogRootKind.CUSTOM, "Catalog infrastructure", code,
                failure instanceof IOException io ? io : new IOException(failure)));
            dirty = true;
        }

        private void tick() {
            var handoff = latest.get();
            if (handoff == null || handoff.status != null && coordinator.isAlive()
                || !latest.compareAndSet(handoff, null)) {
                return;
            }
            publish(this, handoff.candidate);
            if (handoff.status != null) {
                finish(this, handoff.status, handoff.message);
            }
        }

        private void applyDiscovery(CatalogReconciler.ScanDiscovery value) {
            errors.addAll(value.errors());
            dirty = !errors.isEmpty();
        }

        private void applySource(CatalogReconciler.ResolvedSource outcome) throws IOException {
            if (outcome.error() != null) {
                errors.add(outcome.error());
                dirty = true;
                return;
            }
            var entry = outcome.entry();
            var location = entry.location();
            retainConverted(entry.backingFile());
            var path = location.value();
            if (acceptedModelIds.contains(entry.modelId()) || reconciler.conflictsWithBuiltin(entry)) {
                errors.add(ModelScanError.conflict(location.rootKind(), path));
                dirty = true;
                return;
            }
            var previousRecord = previous.snapshot().byModelId().get(entry.modelId());
            if (previousRecord != null && previousRecord.binding().content() instanceof ManagedContainer reusable) {
                outcome.content().reuse(reusable);
            }
            var oldEntry = effectiveIndex.get(location);
            if (!entry.equals(oldEntry)) {
                effectiveIndex.put(location, entry);
                dirty = true;
            }
            outcome.convertedIndex().ifPresent(convertedIndexes::add);
            warnings.addAll(outcome.warnings());
            acceptedModelIds.add(entry.modelId());
            acceptedLocations.add(location);
            var record =
                new CatalogRecord(location, new CatalogContentBinding(entry.modelId(), outcome.content().content()));
            var oldRecord = effectiveRecords.get(location);
            if (!record.equals(oldRecord)) {
                effectiveRecords.put(location, record);
                dirty = true;
            }
            outcome.content().adopt();
            acceptedOrder.add(location);
        }

        private void applyPack(CatalogReconciler.ResolvedPack outcome) {
            if (outcome.error() != null) {
                errors.add(outcome.error());
                dirty = true;
                return;
            }
            outcome.pack().ifPresent(pack -> {
                var key = pack.hierarchy();
                if (!pack.equals(effectivePacks.get(key))) {
                    effectivePacks.put(key, pack);
                    dirty = true;
                }
            });
        }

        private void buildProjection(Instant startedAt) {
            var selected = selectRecords(effectiveRecords, acceptedOrder, acceptedLocations);
            var packs = effectivePacks.values().stream().sorted().toList();
            var report = new ModelScanReport(startedAt, Instant.now(), errors, warnings);
            var selectedIdentities =
                selected.values().stream().collect(Collectors.toUnmodifiableMap(CatalogRecord::location,
                    record -> record.binding().content().representation().identity()));
            var indexEntries = effectiveIndex.values().stream()
                .filter(entry -> entry.identity().equals(selectedIdentities.get(entry.location())))
                .sorted(Comparator.comparing(CatalogIndexEntry::location)).toList();
            var candidate = new CatalogCandidate(new CatalogIndexSnapshot(indexEntries, packs, report),
                new CatalogSnapshot(selected, packs, report));
            lastBuilt = candidate;
            lastBuildNanos = System.nanoTime();
            built = true;
            dirty = false;
            if (plan.initial) {
                candidate.index().entries().stream().map(CatalogIndexEntry::backingFile).forEach(this::retainConverted);
            }
        }

        private void retainConverted(Path path) {
            var checked = path.toAbsolutePath().normalize();
            if (storage == null ? checked.startsWith(convertedRoot) : storage.scopes().stream()
                .anyMatch(scope -> checked.startsWith(scope.identity().root().resolve("objects"))))
                retainedConverted.add(checked);
        }

        private boolean applyConfirmedDeletions(CatalogReconciler.ScanDiscovery value) {
            var observed =
                value.sources().stream().map(source -> source.key().root().location(source.key().relativePath()))
                    .collect(Collectors.toUnmodifiableSet());
            var observedPacks = value.packs().stream().map(pack -> pack.key().location().value())
                .collect(Collectors.toUnmodifiableSet());
            var completeRoots = value.completeRoots().keySet();
            var changed = effectiveRecords.entrySet().removeIf(entry -> completeRoots.contains(entry.getKey().root())
                && !isIntrinsicDefault(entry.getKey()) && !observed.contains(entry.getKey()));
            changed |= effectiveIndex.entrySet()
                .removeIf(entry -> completeRoots.contains(entry.getKey().root()) && !observed.contains(entry.getKey()));
            changed |= effectivePacks.entrySet()
                .removeIf(entry -> completeRoots
                    .contains(com.elfmcys.ysm.model.catalog.source.CatalogLocation.directory(entry.getKey()).root())
                    && !observedPacks.contains(entry.getKey()));
            return changed;
        }

        private void cancel() {
            cancelled.set(true);
            coordinator.interrupt();
        }

        private void awaitCoordinator() {
            var interrupted = false;
            while (coordinator.isAlive()) {
                try {
                    coordinator.join();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                    coordinator.interrupt();
                }
            }
            latest.set(null);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private void awaitWorkers(boolean cancel) {
            if (executor == null) {
                return;
            }
            if (cancel) {
                executor.shutdownNow();
            }
            var interrupted = false;
            while (!executor.isTerminated()) {
                try {
                    executor.awaitTermination(1, TimeUnit.DAYS);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                    executor.shutdownNow();
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private void disposePendingOutcomes() {
            for (Object outcome; (outcome = outcomes.poll()) != null;) {
                if (outcome instanceof CatalogReconciler.ResolvedSource source && source.error() == null) {
                    try {
                        source.content().close();
                    } catch (Throwable failure) {
                        diagnose("UNPUBLISHED_CONTENT_DISPOSAL", failure);
                    }
                }
            }
        }
    }

    private static Map<Hash256, CatalogRecord> selectRecords(Map<CatalogLocation, CatalogRecord> source,
        List<CatalogLocation> acceptedOrder, Set<CatalogLocation> acceptedLocations) {
        var baseline = new ArrayList<>(source.values());
        baseline.sort(Comparator.comparing(CatalogRecord::location));
        var ordered = new ArrayList<CatalogRecord>(baseline.size());
        baseline.stream().filter(record -> record.location().rootKind() == CatalogRootKind.BUILTIN)
            .forEach(ordered::add);
        acceptedOrder.stream().map(source::get).filter(Objects::nonNull)
            .filter(record -> record.location().rootKind() != CatalogRootKind.BUILTIN).forEach(ordered::add);
        baseline.stream().filter(record -> record.location().rootKind() != CatalogRootKind.BUILTIN
            && !acceptedLocations.contains(record.location())).forEach(ordered::add);
        var records = new LinkedHashMap<Hash256, CatalogRecord>();
        for (var record : ordered)
            records.putIfAbsent(record.binding().modelId(), record);
        return records;
    }

    private static boolean isIntrinsicDefault(CatalogLocation location) {
        return location.rootKind() == CatalogRootKind.BUILTIN && location.path().value().equals("default");
    }

    private static String message(Throwable failure) {
        return com.elfmcys.ysm.model.domain.PublicFailure.summary(failure);
    }

    private record Handoff(CatalogCandidate candidate, ReloadStatus status, String message) {
    }

    private record ScanPlan(boolean initial, ThreadPolicy workerPolicy) {
    }

    private record Admission(ActiveScan scan, Throwable failure) {
    }

    @FunctionalInterface
    interface Registration {
        void register() throws IOException;
    }

    @FunctionalInterface
    private interface Pruner {
        ConvertedCacheCoordinator.PruneResult prune(Set<Path> retained);
    }

    public final class Subscription implements AutoCloseable {
        private final Consumer<CatalogTransition> listener;
        private boolean subscriptionClosed;

        private Subscription(Consumer<CatalogTransition> listener) {
            this.listener = listener;
        }

        @Override
        public void close() {
            synchronized (ReloadableModelCatalog.this) {
                if (!subscriptionClosed) {
                    subscriptionClosed = true;
                    listeners.remove(listener);
                }
            }
        }
    }
}
