package com.elfmcys.ysm.model.resource.client.preview;

import com.elfmcys.ysm.model.storage.PreviewStorage;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.gui.PreviewRenderHost;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.RenderTargetIds;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ClientModelRenderTargetManager;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelContent;
import com.elfmcys.ysm.model.resource.client.remote.RemoteChunkFetcher;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.model.service.ModelExportService;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.model.storage.PreviewPixels;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.buffer.SharedBuffer;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.natives.image.ImageEncoder;
import com.elfmcys.ysm.natives.image.ImageSource;
import com.elfmcys.ysm.util.Closeable;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client-tick owner for bounded preview and export operations.
 *
 * <p>
 * Failure: admission after close completes the result with CancellationException; a full submission queue completes it
 * with RejectedExecutionException. Stage failures complete the same result exceptionally.
 *
 * <p>
 * Execution: submitters enqueue work; the client owner drives tick and close. Worker completion publishes stage facts.
 * Tick advances available facts without waiting for unfinished workers. Future callbacks can run inline on the thread
 * that completes or cancels the result, including while the owner monitor is held.
 *
 * <p>
 * Ownership: the operation owns its target lease, retained inputs and intermediate results. The resource owner
 * terminates preparation and rejected target candidates after this operation releases its lease. A successfully
 * delivered EncodedPreview transfers to the consumer, which must close it. Cancellation requests cleanup but does not
 * join workers; the service that owns the executors must drain them during shutdown.
 *
 * <p>
 * Seam: implements [preview operation ownership](docs/architecture/asset-pipeline/overview.md#预览取得与生成).
 */
public final class ClientPreviewGenerator implements Closeable {
    private static final int DEFAULT_MAX_ACTIVE = 16;
    private static final int DEFAULT_PENDING_CAPACITY = 32;
    private static final int DEFAULT_ADMISSIONS_PER_TICK = 4;
    private static final AtomicLong NEXT_OPERATION_ID = new AtomicLong();

    private final TargetSource targetSource;
    private java.util.function.BiFunction<ModelContent, RemoteChunkFetcher, TargetUse> managementTargets;
    private TargetSource exportTargets;
    private final HostRenderer hostRenderer;
    private final Encoder encoder;
    private final Executor hostExecutor;
    private final Executor workers;
    private final PreviewStorage previewStore;
    private final ExportPreparer exportPreparer;
    private final int maxActive;
    private final ArrayBlockingQueue<Submission> submissions = new ArrayBlockingQueue<>(DEFAULT_PENDING_CAPACITY);
    private final Map<Long, Operation> active = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final PreviewPixels.Owner pixelOwner = new PreviewPixels.Owner();

    public ClientPreviewGenerator(ClientModelRenderTargetManager targets, PreviewStorage previewStore,
        Executor hostExecutor, ClientResourceScheduler scheduler) {
        this(content -> new TargetUse(previewRequest(targets, content), targets.acquirePreview(content)),
            PreviewRenderHost::render, ClientPreviewGenerator::encode, hostExecutor, scheduler.backgroundExecutor(),
            previewStore, ModelExportService::prepare, DEFAULT_MAX_ACTIVE);
        exportTargets =
            content -> new TargetUse(previewRequest(targets, content), targets.acquireDetachedPreview(content, null));
        managementTargets = (content, fetcher) -> new TargetUse(previewRequest(targets, content),
            targets.acquireDetachedPreview(content, fetcher));
    }

    private static ResourceRequest previewRequest(ClientModelRenderTargetManager targets, ModelContent content) {
        return targets.request(content.modelId(), RenderTargetIds.PLAYER, "", ModelResourcePurpose.GUI, null);
    }

    ClientPreviewGenerator(TargetSource targetSource, HostRenderer hostRenderer, Encoder encoder, Executor hostExecutor,
        Executor workers, PreviewStorage previewStore, ExportPreparer exportPreparer, int maxActive) {
        this.targetSource = Objects.requireNonNull(targetSource, "targetSource");
        this.hostRenderer = Objects.requireNonNull(hostRenderer, "hostRenderer");
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.hostExecutor = Objects.requireNonNull(hostExecutor, "hostExecutor");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.previewStore = previewStore;
        this.exportPreparer = Objects.requireNonNull(exportPreparer, "exportPreparer");
        if (maxActive <= 0) {
            throw new IllegalArgumentException("Preview operation limits must be positive");
        }
        this.maxActive = maxActive;
    }

    /**
     * @return a preview source, or null when no preview exists and generation is disabled
     */
    public CompletableFuture<ImageSource> resolveLocal(ModelContent content, boolean allowGeneration) {
        Objects.requireNonNull(content, "content");
        var output = new CompletableFuture<ImageSource>();
        return submit(new LocalPreviewSubmission(nextId(), content, allowGeneration, output), output);
    }

    /** Returns an empty result for an absent remote preview; does not generate a replacement. */
    public CompletableFuture<Optional<ImageSource>> probeRemote(ModelContent content) {
        Objects.requireNonNull(content, "content");
        var output = new CompletableFuture<Optional<ImageSource>>();
        return submit(new RemotePreviewSubmission(nextId(), content, output), output);
    }

    public CompletableFuture<PreviewStore.EncodedPreview> resolveEncoded(ModelContent content,
        RemoteChunkFetcher fetcher) {
        Objects.requireNonNull(content, "content");
        var output = new CompletableFuture<PreviewStore.EncodedPreview>();
        return submit(new EncodedPreviewSubmission(nextId(), content, fetcher, output), output);
    }

    public CompletableFuture<ImageSource> resolveRaw(ModelContent content, UniBuffer bytes) {
        var output = new CompletableFuture<ImageSource>();
        try {
            var pending = PreviewStore.pending(bytes);
            var submission = new RawPreviewSubmission(nextId(), content, pending, output);
            submit(submission, output);
            if (output.isDone())
                pending.close();
        } catch (IOException | RuntimeException failure) {
            output.completeExceptionally(failure);
        }
        return output;
    }

    public CompletableFuture<Path> export(ModelExportService.Request request) {
        Objects.requireNonNull(request, "request");
        var output = new CompletableFuture<Path>();
        var submission = new ExportSubmission(nextId(), request, output);
        output.whenComplete((ignored, failure) -> {
            if (output.isCancelled())
                cancelExport(submission);
        });
        return submit(submission, output);
    }

    private synchronized void cancelExport(ExportSubmission submission) {
        submissions.remove(submission);
        var operation = active.get(submission.id());
        if (operation != null) {
            operation.closeSlot();
            finishFailure(operation, new CancellationException("Export environment ended"));
        }
    }

    private static long nextId() {
        return NEXT_OPERATION_ID.incrementAndGet();
    }

    private <T> CompletableFuture<T> submit(Submission submission, CompletableFuture<T> output) {
        if (closed.get()) {
            output.completeExceptionally(new CancellationException("Preview operation owner is closed"));
            return output;
        }
        if (!submissions.offer(submission)) {
            output.completeExceptionally(
                new RejectedExecutionException("Preview operation request capacity is exhausted"));
            return output;
        }
        if (closed.get() && submissions.remove(submission)) {
            output.completeExceptionally(new CancellationException("Preview operation owner is closed"));
        }
        output.whenComplete((value, failure) -> {
            if (output.isCancelled())
                cancelSubmission(submission);
        });
        return output;
    }

    private synchronized void cancelSubmission(Submission submission) {
        if (submissions.remove(submission) && submission instanceof RawPreviewSubmission raw)
            raw.pending().close();
        var operation = active.get(submission.id());
        if (operation != null) {
            finishFailure(operation, new CancellationException("Preview request ended"));
        }
    }

    /** Polls immutable stage facts and performs all operation admission and release. */
    public synchronized void tick() {
        if (closed.get()) {
            return;
        }
        for (var operation : new ArrayList<>(active.values())) {
            if (operation.output().isCancelled()) {
                operation.closeSlot();
                finishFailure(operation, new CancellationException("Preview operation cancelled"));
                continue;
            }
            if (operation.target != null) {
                advanceTarget(operation);
                continue;
            }
            var fact = operation.poll();
            if (fact != null) {
                apply(operation, fact);
            }
        }
        for (var admitted = 0; admitted < DEFAULT_ADMISSIONS_PER_TICK && active.size() < maxActive; admitted++) {
            var submission = submissions.poll();
            if (submission == null) {
                break;
            }
            if (submission.output().isDone()) {
                admitted--;
                continue;
            }
            var operation = new Operation(submission);
            active.put(submission.id(), operation);
            start(operation);
        }
    }

    private void start(Operation operation) {
        if (operation.submission instanceof LocalPreviewSubmission local) {
            startProbe(operation, local.content(), false);
        } else if (operation.submission instanceof RemotePreviewSubmission remote) {
            startProbe(operation, remote.content(), true);
        } else if (operation.submission instanceof EncodedPreviewSubmission encoded) {
            startProbe(operation, encoded.content(), false);
        } else if (operation.submission instanceof RawPreviewSubmission raw) {
            startDecode(operation, raw.pending(), false, false);
        } else if (operation.submission instanceof ExportSubmission export) {
            startExportPreparation(operation, export);
        } else {
            finishFailure(operation, new IllegalStateException("Unsupported preview operation submission"));
        }
    }

    private void startProbe(Operation operation, ModelContent content, boolean remote) {
        var slot = operation.begin();
        dispatchWorker(operation, slot, () -> {
            try {
                return probe(content, remote);
            } catch (Throwable failure) {
                return new ProbeFact(null, false, failure);
            }
        }, FailureCleanup.NONE);
    }

    private ProbeFact probe(ModelContent content, boolean remote) throws IOException {
        try {
            var view = content.modelFile();
            var chunk = view.getFileView().getAssetView().getChunkInfo(ModelFileConstant.THUMB_BUTTON_CHUNK_NAME);
            var source = view.thumbnailSource(() -> false, content.chunks());
            if (source != null) {
                if (chunk == null) {
                    throw new IOException("Model preview chunk is unavailable");
                }
                return new ProbeFact(PreviewStore.pending(source, chunk.size()), false, null);
            }
        } catch (IOException | RuntimeException failure) {
            if (!remote && !(content instanceof RemoteModelContent && failure instanceof AssetLoadException access
                && access.reason() == AssetLoadException.Reason.ACCESS)) {
                throw failure;
            }
        }
        if (previewStore != null) {
            try {
                var store = previewStore.forContent(content);
                var containerId = content.representation().containerId();
                var version = store.observe(containerId);
                var stored = store.readEncoded(containerId);
                if (stored.isPresent()) {
                    var bytes = SharedBuffer.adopt(stored.orElseThrow());
                    return new ProbeFact(PreviewStore.pending(() -> Image.probe(bytes), bytes.size()).version(version),
                        true, null);
                }
            } catch (IOException | RuntimeException invalidCache) {
                // Standalone previews remain optional; a declared embedded image was handled above.
            }
        }
        return new ProbeFact(null, false, null);
    }

    private void startExportPreparation(Operation operation, ExportSubmission export) {
        var slot = operation.begin();
        dispatchWorker(operation, slot, () -> {
            try {
                return new ExportPreparedFact(exportPreparer.prepare(export.request()), null);
            } catch (Throwable failure) {
                return new ExportPreparedFact(null, failure);
            }
        }, FailureCleanup.NONE);
    }

    private void startDecode(Operation operation, PreviewStore.PendingPreview pending, boolean generated,
        boolean standalone) {
        var prepared = operation.export;
        if (operation.submission instanceof ExportSubmission)
            operation.export = null;
        var slot = operation.begin();
        dispatchWorker(operation, slot, () -> {
            PreviewStore.EncodedPreview preview = null;
            var display = operation.submission instanceof LocalPreviewSubmission
                || operation.submission instanceof RemotePreviewSubmission
                || operation.submission instanceof RawPreviewSubmission;
            try (pending) {
                preview = pending.verify(pixelOwner, display);
                if (operation.submission instanceof ExportSubmission) {
                    try (var owned = preview) {
                        return finishExport(prepared, owned);
                    }
                }
                if ((generated && operation.submission instanceof LocalPreviewSubmission
                    || operation.submission instanceof RawPreviewSubmission) && previewStore != null)
                    previewStore.forContent(operation.content())
                        .accept(operation.content().representation().containerId(), preview);
                return new DecodedFact(preview, generated, null);
            } catch (Throwable failure) {
                if (preview != null)
                    preview.close();
                if (prepared != null)
                    failure = prepared.closeOwned(failure);
                if (standalone && failure instanceof AssetLoadException invalid
                    && invalid.reason() == AssetLoadException.Reason.CONTENT)
                    return new DecodedFact(null, false, null);
                return new DecodedFact(null, generated, failure);
            }
        }, failure -> {
            pending.close();
            return prepared == null ? failure : prepared.closeOwned(failure);
        });
    }

    private void startTarget(Operation operation, ModelContent content) {
        try {
            if (operation.submission instanceof ExportSubmission export)
                export.request().requireActive();
            operation.closeSlot();
            operation.target =
                operation.submission instanceof EncodedPreviewSubmission encoded && managementTargets != null
                    ? managementTargets.apply(content, encoded.fetcher())
                    : operation.submission instanceof ExportSubmission && exportTargets != null
                        ? exportTargets.plan(content) : targetSource.plan(content);
        } catch (Throwable failure) {
            finishFailure(operation, failure);
            return;
        }
        advanceTarget(operation);
    }

    private void advanceTarget(Operation operation) {
        try {
            var target = operation.target;
            var outcome = target.lease().poll();
            if (outcome instanceof AcquireResult.Pending)
                return;
            if (outcome instanceof AcquireResult.Ready) {
                startHost(operation, target);
            } else if (outcome instanceof AcquireResult.Failed failed) {
                finishFailure(operation, failed.failure().cause());
            } else if (outcome instanceof AcquireResult.Missing) {
                finishFailure(operation, AssetLoadException.access("Preview target is unavailable"));
            } else {
                finishFailure(operation, new CancellationException("Preview target use ended"));
            }
        } catch (Throwable failure) {
            finishFailure(operation, failure);
        }
    }

    private void startHost(Operation operation, TargetUse input) {
        var slot = operation.begin();
        var pending = new AtomicReference<>(input);
        slot.onClose(() -> {
            var owned = pending.getAndSet(null);
            if (owned != null)
                dispose(owned);
        });
        operation.target = null;
        try {
            hostExecutor.execute(() -> {
                var owned = pending.getAndSet(null);
                if (owned != null)
                    renderPublished(slot, owned);
            });
        } catch (Throwable failure) {
            finishFailure(operation, failure);
        }
    }

    private void renderPublished(FactSlot slot, TargetUse input) {
        NativeImage pixels = null;
        Throwable failure = null;
        try {
            if (!slot.isClosed()) {
                pixels = Objects.requireNonNull(hostRenderer.render(input.request(), input.lease()),
                    "Preview host returned null pixels");
            }
        } catch (Throwable renderingFailure) {
            failure = renderingFailure;
        }
        try {
            input.lease().close();
        } catch (Throwable closeFailure) {
            failure = append(failure, closeFailure);
        }
        if (failure != null) {
            failure = closePixels(pixels, failure);
            pixels = null;
        }
        slot.publish(new PixelsFact(pixels, failure));
    }

    private void startFinish(Operation operation, NativeImage pixels) {
        var slot = operation.begin();
        dispatchWorker(operation, slot, () -> {
            PreviewStore.PendingPreview encoded = null;
            Throwable failure = null;
            try {
                encoded = Objects.requireNonNull(encoder.encode(pixels), "Preview encoder returned null");
            } catch (Throwable encodingFailure) {
                failure = encodingFailure;
            } finally {
                failure = closePixels(pixels, failure);
            }
            return new EncodedFact(encoded, failure);
        }, failure -> closePixels(pixels, failure));
    }

    private ExportFinishedFact finishExport(ModelExportService.PreparedExport prepared,
        PreviewStore.EncodedPreview encoded) {
        Path output = null;
        Throwable failure = null;
        try {
            output = ModelExportService.finish(prepared, encoded);
            if (previewStore != null)
                previewStore.forContent(prepared.content()).accept(prepared.content().representation().containerId(),
                    encoded);
        } catch (Throwable finishFailure) {
            failure = finishFailure;
        } finally {
            failure = prepared.closeOwned(failure);
        }
        return new ExportFinishedFact(output, failure);
    }

    private void dispatchWorker(Operation operation, FactSlot slot, FactSupplier supplier, FailureCleanup cleanup) {
        var request = operation.submission instanceof ExportSubmission export ? export.request() : null;
        if (request != null && !request.beginWorker()) {
            slot.close();
            finishFailure(operation, cleanup.apply(new CancellationException("Export environment ended")));
            return;
        }
        var pending = new AtomicBoolean(true);
        slot.onClose(() -> {
            if (pending.compareAndSet(true, false)) {
                cleanup.apply(new CancellationException("Preview stage was cancelled before execution"));
                if (request != null)
                    request.endWorker();
            }
        });
        try {
            workers.execute(() -> {
                if (!pending.compareAndSet(true, false))
                    return;
                try {
                    Fact fact;
                    try {
                        fact = Objects.requireNonNull(supplier.get(), "Preview worker returned no fact");
                    } catch (Throwable failure) {
                        fact = new FailureFact(failure);
                    }
                    slot.publish(fact);
                } finally {
                    if (request != null)
                        request.endWorker();
                }
            });
        } catch (Throwable failure) {
            if (pending.compareAndSet(true, false)) {
                if (request != null)
                    request.endWorker();
                failure = cleanup.apply(failure);
            }
            finishFailure(operation, failure);
        }
    }

    private void apply(Operation operation, Fact fact) {
        if (operation.output().isCancelled()) {
            dispose(fact);
            finishFailure(operation, new CancellationException("Preview operation was cancelled"));
            return;
        }
        if (fact.failure() != null) {
            dispose(fact);
            finishFailure(operation, fact.failure());
            return;
        }
        if (fact instanceof ProbeFact probe) {
            if (probe.preview() == null)
                applyProbe(operation, null);
            else
                startDecode(operation, probe.preview(), false, probe.standalone());
        } else if (fact instanceof ExportPreparedFact prepared) {
            applyPreparedExport(operation, prepared.prepared());
        } else if (fact instanceof PixelsFact pixels) {
            if (pixels.pixels() == null) {
                finishFailure(operation, new NullPointerException("Preview host completed without pixels"));
            } else {
                startFinish(operation, pixels.pixels());
            }
        } else if (fact instanceof EncodedFact encoded) {
            startDecode(operation, encoded.pending(), true, false);
        } else if (fact instanceof DecodedFact preview) {
            if (preview.generated() || operation.submission instanceof RawPreviewSubmission)
                finishPreviewSuccess(operation, preview.preview());
            else
                applyProbe(operation, preview.preview());
        } else if (fact instanceof ExportFinishedFact export) {
            finishSuccess(operation, export.output());
        } else {
            finishFailure(operation, new IllegalStateException("Unsupported preview operation fact"));
        }
    }

    private void applyProbe(Operation operation, PreviewStore.EncodedPreview preview) {
        if (operation.submission instanceof RemotePreviewSubmission) {
            finishSuccess(operation, Optional.ofNullable(preview).<ImageSource>map(value -> value));
        } else if (preview != null) {
            finishSuccess(operation, preview);
        } else if (operation.submission instanceof LocalPreviewSubmission local && !local.allowGeneration()) {
            finishSuccess(operation, null);
        } else {
            startTarget(operation, operation.content());
        }
    }

    private void applyPreparedExport(Operation operation, ModelExportService.PreparedExport prepared) {
        if (prepared == null) {
            finishFailure(operation, new NullPointerException("Export preparation completed without a result"));
        } else if (prepared.completed() != null) {
            operation.export = prepared;
            finishSuccess(operation, prepared.completed());
        } else {
            operation.export = prepared;
            startTarget(operation, prepared.content());
        }
    }

    private void finishPreviewSuccess(Operation operation, PreviewStore.EncodedPreview preview) {
        if (operation.submission instanceof LocalPreviewSubmission
            || operation.submission instanceof RawPreviewSubmission) {
            finishSuccess(operation, (ImageSource)preview);
        } else {
            finishSuccess(operation, preview);
        }
    }

    @SuppressWarnings("unchecked")
    private void finishSuccess(Operation operation, Object result) {
        var failure = closePrepared(operation, null);
        active.remove(operation.submission.id());
        operation.closeSlot();
        if (failure != null) {
            closeResult(result);
            operation.output().completeExceptionally(failure);
            return;
        }
        if (!((CompletableFuture<Object>)operation.output()).complete(result))
            closeResult(result);
    }

    private static void closeResult(Object result) {
        if (result instanceof PreviewStore.EncodedPreview encoded)
            encoded.close();
        else if (result instanceof Optional<?> optional)
            optional.ifPresent(ClientPreviewGenerator::closeResult);
    }

    private void finishFailure(Operation operation, Throwable failure) {
        failure = unwrap(failure);
        try {
            operation.closeTarget();
        } catch (Throwable closeFailure) {
            failure = append(failure, closeFailure);
        }
        failure = closePrepared(operation, failure);
        active.remove(operation.submission.id());
        operation.closeSlot();
        if (!operation.output().isDone()) {
            operation.output().completeExceptionally(failure);
        }
    }

    private static Throwable closePrepared(Operation operation, Throwable failure) {
        var prepared = operation.export;
        operation.export = null;
        return prepared == null ? failure : prepared.closeOwned(failure);
    }

    /**
     * Seals admission and cancels pending operations. Late facts are disposed by their operation slots. Already
     * delivered results remain the consumer's responsibility. Repeated calls do nothing.
     */
    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Submission submission;
        while ((submission = submissions.poll()) != null) {
            if (submission instanceof RawPreviewSubmission raw)
                raw.pending().close();
            submission.output().completeExceptionally(new CancellationException("Preview operation owner is closed"));
        }
        Throwable failure = null;
        for (var operation : new ArrayList<>(active.values())) {
            try {
                operation.closeSlot();
                operation.closeTarget();
            } catch (Throwable closeFailure) {
                failure = append(failure, closeFailure);
            }
            failure = closePrepared(operation, failure);
            operation.output().cancel(false);
        }
        active.clear();
        pixelOwner.close();
        if (failure != null) {
            YesSteveModel.LOGGER.warn("Failed to close every preview operation resource", failure);
        }
    }

    private static void dispose(Fact fact) {
        Throwable failure = null;
        try {
            if (fact instanceof ExportPreparedFact prepared && prepared.prepared() != null) {
                failure = prepared.prepared().closeOwned(null);
            } else if (fact instanceof ProbeFact probe && probe.preview() != null) {
                probe.preview().close();
            } else if (fact instanceof DecodedFact finished && finished.preview() != null) {
                finished.preview().close();
            } else if (fact instanceof EncodedFact encoded && encoded.pending() != null) {
                encoded.pending().close();
            } else if (fact instanceof PixelsFact pixels) {
                failure = closePixels(pixels.pixels(), null);
            }
        } catch (Throwable disposeFailure) {
            failure = disposeFailure;
        }
        if (failure != null) {
            YesSteveModel.LOGGER.warn("Failed to dispose a late preview operation fact", failure);
        }
    }

    private static void dispose(TargetUse target) {
        try {
            target.lease().close();
        } catch (Throwable failure) {
            YesSteveModel.LOGGER.warn("Failed to close a queued preview target use", failure);
        }
    }

    private static Throwable closePixels(NativeImage pixels, Throwable failure) {
        if (pixels == null) {
            return failure;
        }
        try {
            pixels.close();
        } catch (Throwable closeFailure) {
            return append(failure, closeFailure);
        }
        return failure;
    }

    private static Throwable append(Throwable failure, Throwable next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    private static Throwable unwrap(Throwable failure) {
        var current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static PreviewStore.PendingPreview encode(NativeImage pixels) throws IOException {
        return PreviewStore
            .pending(ImageEncoder.encodeLossy(pixels, PreviewRenderHost.WIDTH, PreviewRenderHost.HEIGHT));
    }

    record TargetUse(ResourceRequest request, ResourceLease lease) {
        TargetUse {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(lease, "lease");
        }
    }

    @FunctionalInterface
    interface TargetSource {
        TargetUse plan(ModelContent content);
    }

    @FunctionalInterface
    interface HostRenderer {
        NativeImage render(ResourceRequest request, ResourceLease lease) throws Exception;
    }

    @FunctionalInterface
    interface Encoder {
        PreviewStore.PendingPreview encode(NativeImage pixels) throws Exception;
    }

    @FunctionalInterface
    interface ExportPreparer {
        ModelExportService.PreparedExport prepare(ModelExportService.Request request) throws Exception;
    }

    @FunctionalInterface
    private interface FactSupplier {
        Fact get();
    }

    @FunctionalInterface
    private interface FailureCleanup {
        FailureCleanup NONE = failure -> failure;

        Throwable apply(Throwable failure);
    }

    private sealed interface Submission permits LocalPreviewSubmission, RemotePreviewSubmission, EncodedPreviewSubmission, RawPreviewSubmission, ExportSubmission {
        long id();

        CompletableFuture<?> output();
    }

    private record LocalPreviewSubmission(long id, ModelContent content, boolean allowGeneration,
        CompletableFuture<ImageSource> output) implements Submission {
    }

    private record RemotePreviewSubmission(long id, ModelContent content,
        CompletableFuture<Optional<ImageSource>> output) implements Submission {
    }

    private record EncodedPreviewSubmission(long id, ModelContent content, RemoteChunkFetcher fetcher,
        CompletableFuture<PreviewStore.EncodedPreview> output) implements Submission {
    }

    private record RawPreviewSubmission(long id, ModelContent content, PreviewStore.PendingPreview pending,
        CompletableFuture<ImageSource> output) implements Submission {
    }

    private record ExportSubmission(long id, ModelExportService.Request request,
        CompletableFuture<Path> output) implements Submission {
    }

    private sealed interface Fact permits ProbeFact, ExportPreparedFact, PixelsFact, EncodedFact, DecodedFact, ExportFinishedFact, FailureFact {
        Throwable failure();
    }

    private record ProbeFact(PreviewStore.PendingPreview preview, boolean standalone,
        Throwable failure) implements Fact {
    }

    private record ExportPreparedFact(ModelExportService.PreparedExport prepared, Throwable failure) implements Fact {
    }

    private record PixelsFact(NativeImage pixels, Throwable failure) implements Fact {
    }

    private record EncodedFact(PreviewStore.PendingPreview pending, Throwable failure) implements Fact {
    }

    private record DecodedFact(PreviewStore.EncodedPreview preview, boolean generated,
        Throwable failure) implements Fact {
    }

    private record ExportFinishedFact(Path output, Throwable failure) implements Fact {
    }

    private record FailureFact(Throwable failure) implements Fact {
        private FailureFact {
            Objects.requireNonNull(failure, "failure");
        }
    }

    private static final class Operation {
        private final Submission submission;
        private FactSlot slot;
        private TargetUse target;
        private ModelExportService.PreparedExport export;

        private Operation(Submission submission) {
            this.submission = submission;
        }

        private FactSlot begin() {
            closeSlot();
            slot = new FactSlot();
            return slot;
        }

        private Fact poll() {
            return slot == null ? null : slot.poll();
        }

        private CompletableFuture<?> output() {
            return submission.output();
        }

        private ModelContent content() {
            if (submission instanceof LocalPreviewSubmission local) {
                return local.content();
            }
            if (submission instanceof RemotePreviewSubmission remote) {
                return remote.content();
            }
            if (submission instanceof EncodedPreviewSubmission encoded)
                return encoded.content();
            if (submission instanceof RawPreviewSubmission raw)
                return raw.content();
            throw new IllegalStateException("Export content has not been prepared");
        }

        private void closeTarget() {
            var owned = target;
            target = null;
            if (owned != null)
                owned.lease().close();
        }

        private void closeSlot() {
            if (slot != null) {
                slot.close();
                slot = null;
            }
        }
    }

    private static final class FactSlot {
        private static final Object WAITING = new Object();
        private static final Object CONSUMED = new Object();
        private static final Object CLOSED = new Object();
        private final AtomicReference<Object> state = new AtomicReference<>(WAITING);
        private final AtomicReference<Runnable> cancellation = new AtomicReference<>();

        private void onClose(Runnable cleanup) {
            cancellation.set(cleanup);
            if (isClosed())
                releasePendingHost();
        }

        private void releasePendingHost() {
            var cleanup = cancellation.getAndSet(null);
            if (cleanup != null)
                cleanup.run();
        }

        private void publish(Fact fact) {
            Objects.requireNonNull(fact, "fact");
            if (!state.compareAndSet(WAITING, fact)) {
                dispose(fact);
            }
        }

        private Fact poll() {
            var value = state.get();
            if (value instanceof Fact fact && state.compareAndSet(value, CONSUMED)) {
                return fact;
            }
            return null;
        }

        private boolean isClosed() {
            return state.get() == CLOSED;
        }

        private void close() {
            var value = state.getAndSet(CLOSED);
            releasePendingHost();
            if (value instanceof Fact fact) {
                dispose(fact);
            }
        }
    }

}
