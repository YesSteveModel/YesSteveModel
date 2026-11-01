package com.elfmcys.ysm.model.service;

import com.elfmcys.ysm.expression.host.MolangExtensionLoader;
import com.elfmcys.ysm.model.resource.client.asset.ClientAssetBatch;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.api.client.audio.AudioBackends;
import com.elfmcys.ysm.client.sound.instance.AudioPlaybackFactory;
import com.elfmcys.ysm.client.sound.instance.MinecraftAudioHost;
import com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModuleFactory;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogSnapshot;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.resource.client.asset.ClientAssetRepository;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogManager;
import com.elfmcys.ysm.model.session.client.RemoteCatalogActivation;
import com.elfmcys.ysm.model.resource.client.ClientModelRenderTargetManager;
import com.elfmcys.ysm.model.resource.client.SoundSource;
import com.elfmcys.ysm.model.resource.client.audio.ClientAudioRuntime;
import com.elfmcys.ysm.model.resource.client.preview.ClientPreviewGenerator;
import com.elfmcys.ysm.model.resource.client.render.ModelRenderTargetLoader;
import com.elfmcys.ysm.model.resource.client.render.DefaultAnimationRuntime;
import com.elfmcys.ysm.client.texture.CustomTexture;
import com.elfmcys.ysm.model.ModelSystem;
import com.elfmcys.ysm.util.ThreadScheduling;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadRole;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadPolicy;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.RenderTargetIds;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelStore;
import com.elfmcys.ysm.model.resource.RuntimeContentStore;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.natives.image.ImageSource;
import com.elfmcys.ysm.natives.NativeRuntime;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Client composition boundary for catalog projection, resource acquisition, and demand progression.
 *
 * <p>Invariant: a published service has completed required preparation and transferred its close
 * responsibility to the process system. Ordinary catalog activation is a separate operation.
 *
 * <p>Execution: live client operations run in the render owner's window. Publication accessors are
 * thread-visible snapshots, not lifetime pins. Process teardown must exclude new client operations.
 *
 * <p>Ownership: child accessors borrow service-owned components. Acquired leases, batches, and
 * playback uses retain their own release obligations; disconnect does not close the process system.
 *
 * <p>Seam: implements [client startup](docs/architecture/runtime/overview.md#启动与进入世界) and [client shutdown](docs/architecture/runtime/overview.md#结束与替换). Resource results follow the
 * [client-resource contract](docs/architecture/model-management/client-resources/overview.md#seam-contract) and do not imply entity installation.
 */
public final class ClientModelService implements AutoCloseable {
    private static volatile ClientModelService INSTANCE;

    private final ModelSystem system;
    private final ClientResourceScheduler scheduler;
    private final ScheduledThreadPoolExecutor notificationTimer;
    private final ClientCatalogManager catalogManager;
    private final ClientAssetRepository assetRepository;
    private final ClientModelRenderTargetManager renderTargetManager;
    private final ClientAudioRuntime audioRuntime;
    private final ClientPreviewGenerator previewOperations;
    private final com.elfmcys.ysm.client.demand.ClientDemandCoordinator demands;
    private final Path gameDirectory;
    private RemoteModelStore remoteStore;
    private final AtomicBoolean closed = new AtomicBoolean();
    private int resourceDiagnosticTicks;

    private ClientModelService(ModelSystem system, ClientResourceScheduler scheduler,
        ScheduledThreadPoolExecutor notificationTimer, ClientCatalogManager catalogManager,
        ClientAssetRepository assetRepository, ClientModelRenderTargetManager renderTargetManager,
        ClientPreviewGenerator previewOperations, Path gameDirectory) {
        this.system = java.util.Objects.requireNonNull(system, "system");
        this.scheduler = scheduler;
        this.notificationTimer = notificationTimer;
        this.catalogManager = catalogManager;
        this.assetRepository = assetRepository;
        this.renderTargetManager = renderTargetManager;
        this.audioRuntime = new ClientAudioRuntime(scheduler.backgroundExecutor(), renderTargetManager::resolveSound,
            new MinecraftAudioHost(), AudioBackends.freeze().orElse(null));
        this.previewOperations = previewOperations;
        this.gameDirectory = gameDirectory;
        this.demands = new com.elfmcys.ysm.client.demand.ClientDemandCoordinator(() -> System.nanoTime() / 1_000_000L,
            new com.elfmcys.ysm.model.resource.client.ClientDemandResources(this),
            new com.elfmcys.ysm.network.forge.ClientDemandSelections(),
            () -> com.elfmcys.ysm.config.ClientConfig.MODEL_SCREEN_EAGER_LOADING.get());
    }

    private ClientModelService(ModelSystem system, ClientResourceScheduler scheduler,
        ScheduledThreadPoolExecutor notificationTimer, ClientCatalogManager catalogManager,
        ClientAssetRepository assetRepository, ClientModelRenderTargetManager renderTargetManager, Path gameDirectory) {
        this(system, scheduler, notificationTimer, catalogManager, assetRepository, renderTargetManager, null,
            gameDirectory);
    }

    private static BootstrapCandidate createCandidate(ModelSystem system, HostAvailability availability) {
        var threadCount = Math.max(2, Math.min(8, NativeRuntime.availableProcessors() / 2));
        var candidateScheduler = new ClientResourceScheduler(threadCount);
        ScheduledThreadPoolExecutor candidateTimer = null;
        ModelRenderTargetLoader candidateLoader = null;
        ClientCatalogManager candidateCatalog = null;
        ClientModelRenderTargetManager candidateTargets = null;
        ClientPreviewGenerator candidatePreviews = null;
        try {
            candidateTimer = new ScheduledThreadPoolExecutor(1, runnable -> {
                var thread = new Thread(
                    ThreadScheduling.entry(ThreadRole.CLIENT_NOTIFICATION_TIMER, ThreadPolicy.BELOW_NORMAL, runnable),
                    "YSM Client Notification Timer");
                thread.setDaemon(true);
                return thread;
            });
            candidateTimer.setRemoveOnCancelPolicy(true);
            candidateTimer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            candidateTimer.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
            var background = candidateScheduler.backgroundExecutor();
            var storage = system.storage();
            var gameDirectory = system.paths().gameDirectory();
            var builtins = system.builtins();
            candidateCatalog = new ClientCatalogManager(system.catalog());
            var defaultAnimations = new DefaultAnimationRuntime(system.builtinContract());
            var expressionModules = new RenderTargetExpressionModuleFactory(storage.local().expressions(),
                MolangExtensionLoader.createHost(availability), new ExpressionEngineMetrics());
            var renderTargetLoader =
                new ModelRenderTargetLoader(storage, expressionModules, candidateScheduler, defaultAnimations);
            candidateLoader = renderTargetLoader;
            var contentStore = new RuntimeContentStore(
                error -> YesSteveModel.LOGGER.warn("Model source instance became corrupted", error));
            candidateTargets =
                new ClientModelRenderTargetManager(candidateCatalog, renderTargetLoader, defaultAnimations,
                    candidateScheduler, candidateTimer, ClientModelService::executeOnRenderThread, contentStore);
            var previewStore = system.previews();
            var previews = new ClientPreviewGenerator(candidateCatalog, candidateTargets, renderTargetLoader,
                previewStore, ClientModelService::executeOnRenderThread, candidateScheduler);
            candidatePreviews = previews;
            var candidateAssets = new ClientAssetRepository(candidateCatalog, contentStore, previewStore, background,
                content -> previews.resolveLocal(content,
                    !com.elfmcys.ysm.config.ClientConfig.MODEL_SCREEN_EAGER_LOADING.get()),
                previews::probeRemote, previews::resolveRaw);
            candidateCatalog.setListener(candidateTargets::catalogChanged);
            var required = candidateTargets.startRequired(candidateCatalog.initialize());
            return new BootstrapCandidate(new ClientModelService(system, candidateScheduler, candidateTimer,
                candidateCatalog, candidateAssets, candidateTargets, candidatePreviews, gameDirectory), required);
        } catch (Throwable error) {
            var failure = unwrap(error);
            if (candidatePreviews != null) {
                try {
                    candidatePreviews.close();
                } catch (Throwable closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (candidateTargets != null) {
                try {
                    candidateTargets.close();
                } catch (Throwable closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (candidateTargets == null && candidateLoader != null) {
                try {
                    candidateLoader.close();
                } catch (Throwable closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (candidateCatalog != null) {
                try {
                    candidateCatalog.close();
                } catch (Throwable closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            try {
                finishExecutors(candidateScheduler, candidateTimer);
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw sneakyThrow(failure);
        }
    }

    /**
     * Completes required preparation before publishing the service; returns the existing service if running.
     *
     * <p>Requires: non-null process system and fixed host availability, in the client setup render-owner
     * window. Repeated calls do not reconfigure the existing service from the supplied arguments.
     *
     * <p>Execution: may block while pumping host work for required preparation. Ordinary catalog activation
     * does not wait for a scan result.
     *
     * <p>Failure: required preparation or ownership rejection closes the private candidate and propagates
     * the original cause. Ordinary catalog failure does not revoke an already published service.
     */
    public static synchronized ClientModelService start(ModelSystem system, HostAvailability availability) {
        java.util.Objects.requireNonNull(system, "system");
        java.util.Objects.requireNonNull(availability, "availability");
        if (INSTANCE != null) {
            return INSTANCE;
        }
        var candidate = publishRequired(() -> createCandidate(system, availability),
            ClientModelService::completeRequired, value -> system.attachClientRuntime(value.service),
            value -> INSTANCE = value.service, () -> system.catalog().activate());
        return candidate.service;
    }

    private static void completeRequired(BootstrapCandidate candidate) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            minecraft.managedBlock(candidate.required::isDone);
        }
        candidate.required.join();
    }

    private static void executeOnRenderThread(Runnable task) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            task.run();
            return;
        }
        // BlockableEventLoop.execute may run inline during startup; tell keeps GPU publication
        // on the render owner while managedBlock drains the same task queue.
        minecraft.tell(task);
    }

    static <T extends AutoCloseable> T publishRequired(Supplier<T> factory, Consumer<T> completeRequired,
        Consumer<T> attach, Consumer<T> publish, Runnable activate) {
        T candidate = null;
        try {
            candidate = factory.get();
            completeRequired.accept(candidate);
            attach.accept(candidate);
        } catch (Throwable error) {
            var failure = unwrap(error);
            if (candidate != null) {
                try {
                    candidate.close();
                } catch (Throwable closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw sneakyThrow(failure);
        }
        publish.accept(candidate);
        activate.run();
        return candidate;
    }

    /**
     * Returns the borrowed published service; the reference does not prevent shutdown.
     *
     * @throws IllegalStateException if no client service is published
     */
    public static ClientModelService instance() {
        var service = INSTANCE;
        if (service == null) {
            throw new IllegalStateException("Client model service is not running");
        }
        return service;
    }

    /** Returns the current publication snapshot; absence does not start or await a service. */
    public static Optional<ClientModelService> current() {
        return Optional.ofNullable(INSTANCE);
    }

    public CompletableFuture<PreviewStore.EncodedPreview> managementPreview(ModelContent content,
        com.elfmcys.ysm.model.resource.client.remote.RemoteChunkFetcher fetcher) {
        if (closed.get() || previewOperations == null)
            return CompletableFuture
                .failedFuture(new java.util.concurrent.CancellationException("Client preview owner is closed"));
        return previewOperations.resolveEncoded(content, fetcher);
    }

    public ClientCatalogSnapshot catalog() {
        return catalogManager.snapshot();
    }

    /**
     * Attempts a catalog reload without awaiting it, then returns the new resource-session identity.
     * The connection owner must retain and use this exact identity for later session operations.
     */
    public Object beginConnection() {
        system.catalog().reload();
        return renderTargetManager.beginSession();
    }

    /**
     * Starts remote projection for the current resource session and returns its activation owner.
     *
     * <p>Requires: the connection owner has selected the current session and live fetch capabilities.
     * This method is not an atomic rollback boundary: fetcher binding and projection selection precede
     * opening the remote store.
     *
     * @throws IOException if the remote store cannot be opened; the connection owner must terminate
     *         the failed activation through its session failure path
     */
    public RemoteCatalogActivation beginRemoteActivation(
        com.elfmcys.ysm.model.resource.client.remote.RemoteMetadataFetcher fetcher,
        com.elfmcys.ysm.model.resource.client.remote.RemoteChunkFetcher chunkFetcher, Object sessionOwner)
        throws IOException {
        renderTargetManager.bindRemoteFetcher(sessionOwner, chunkFetcher);
        var active = catalogManager.beginRemote();
        return new RemoteCatalogActivation(catalogManager::localIndex, active, remoteStore(), fetcher,
            scheduler.backgroundExecutor());
    }

    public void publishSessionActivation(com.elfmcys.ysm.model.session.client.state.ActivationSnapshot activation) {
        catalogManager.publishSession(activation);
    }

    public void failRemoteCatalog() {
        catalogManager.failRemote();
    }

    public boolean contains(Hash256 hash) {
        return catalogManager.contains(hash);
    }

    public Optional<String> findRenderTarget(Hash256 hash,
        com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind kind, ResourceLocation entityType) {
        return catalogManager.findRenderTarget(hash, kind, entityType);
    }

    public Optional<Hash256> resolvePath(String path) {
        return catalogManager.resolvePath(path);
    }

    public String displayPath(Hash256 hash) {
        return catalogManager.displayPath(hash);
    }

    public ModelRenderTarget defaultRenderTarget() {
        return renderTargetManager.defaultRenderTarget();
    }

    public BakeProfile bakeProfile() {
        return renderTargetManager.bakeProfile();
    }

    public ResourceRequest resourceRequest(Hash256 modelId, String textureName) {
        return resourceRequest(modelId, RenderTargetIds.PLAYER, textureName);
    }

    public ResourceRequest resourceRequest(Hash256 modelId, String targetId, String textureName) {
        return renderTargetManager.request(modelId, targetId, textureName);
    }

    public ResourceRequest resourceRequest(Hash256 modelId, String targetId, String textureName,
        com.elfmcys.ysm.model.resource.client.ModelResourcePurpose purpose, Class<?> subjectType) {
        return renderTargetManager.request(modelId, targetId, textureName, purpose, subjectType);
    }

    public ResourceRequest defaultResourceRequest(String targetId) {
        return renderTargetManager.defaultRequest(targetId);
    }

    public ResourceRequest defaultResourceRequest(String targetId,
        com.elfmcys.ysm.model.resource.client.ModelResourcePurpose purpose, Class<?> subjectType) {
        return renderTargetManager.defaultRequest(targetId, purpose, subjectType);
    }

    public ResourceLease getOrStart(ResourceRequest request) {
        return getOrStart(request, WorkPriority.BACKGROUND);
    }

    public ResourceLease getOrStart(ResourceRequest request, WorkPriority priority) {
        return renderTargetManager.getOrStart(request, priority);
    }

    public boolean permitsAcquisition(ResourceRequest request) {
        return renderTargetManager.permitsAcquisition(request);
    }

    public Optional<ResourceLease> findReady(ResourceRequest request) {
        return renderTargetManager.findReady(request);
    }

    public com.elfmcys.ysm.client.demand.ClientDemandCoordinator demands() {
        return demands;
    }

    public CompletableFuture<Optional<ResourceLease>> getOrStartOffline(ResourceRequest request) {
        return getOrStartOffline(request, WorkPriority.BACKGROUND);
    }

    public CompletableFuture<Optional<ResourceLease>> getOrStartOffline(ResourceRequest request,
        WorkPriority priority) {
        return renderTargetManager.getOrStartOffline(request, priority);
    }

    public CompletableFuture<Optional<ResourceLease>> getOrStartCached(ResourceRequest request) {
        return getOrStartCached(request, WorkPriority.BACKGROUND);
    }

    public CompletableFuture<Optional<ResourceLease>> getOrStartCached(ResourceRequest request, WorkPriority priority) {
        return renderTargetManager.getOrStartCached(request, priority);
    }

    public void reportActiveModelFailure(ModelContent version, boolean fallbackAvailable) {
        renderTargetManager.reportActiveFailure(version, fallbackAvailable);
    }

    public void reportActiveModelUse(ResourceRequest request) {
        renderTargetManager.reportActiveUse(request);
    }

    /** Returns an independent asset batch whose submission and close responsibility belong to the caller. */
    public ClientAssetBatch createAssetBatch() {
        return new ClientAssetBatch(assetRepository.openBatch());
    }

    public CustomTexture createTexture(ImageSource source) {
        return new CustomTexture(source);
    }

    public AudioPlaybackFactory createSoundPlayback(SoundSource source) {
        return audioRuntime.createPlayback(source);
    }

    /**
     * Ends current remote use and restores local projection, retaining the process catalog and service.
     *
     * <p>Requires: the connection owner has already matched the exact departing session. This facade
     * resets demand, audio, and catalog projection without independently rejecting a stale identity.
     */
    public void disconnect(Object sessionOwner) {
        demands.reset();
        audioRuntime.stopAll();
        renderTargetManager.disconnect(sessionOwner);
        catalogManager.endRemote();
    }

    /** Advances owned terminal queues and demand on the render owner; callers must exclude teardown. */
    public void tick() {
        system.tickCatalog();
        system.tickServerRuntime();
        renderTargetManager.tick();
        audioRuntime.maintain();
        if (com.elfmcys.ysm.util.ResourceDiagnostics.isEnabled() && ++resourceDiagnosticTicks % 20 == 0) {
            ExpressionEngineMetrics.pollCollectedLoaders();
            com.elfmcys.ysm.util.ResourceDiagnostics.pollCollections();
        }
        if (previewOperations != null) {
            previewOperations.tick();
        }
        com.elfmcys.ysm.network.forge.ClientSessionRuntime.tick();
        demands.tick();
    }

    public com.elfmcys.ysm.model.catalog.CatalogScanProgress catalogProgress() {
        return catalogManager.scanProgress();
    }

    /**
     * Removes service publication, seals domain owners, then drains notification and resource workers.
     *
     * <p>Execution: serialized with startup and other close calls. May block without a timeout; must not
     * run on an owned worker or depend on further host ticks. Interrupt status is restored after draining.
     *
     * <p>Failure: cleanup continues across owners and propagates the first cause with later causes
     * suppressed. A later close waits for this call to leave the monitor but does not retry its cleanup.
     */
    @Override
    public void close() {
        synchronized (ClientModelService.class) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (INSTANCE == this) {
                INSTANCE = null;
            }
            closeResourcesUnchecked();
        }
    }

    private void closeResourcesUnchecked() {
        Throwable failure = null;
        failure = closeOwned(demands, failure);
        failure = closeOwned(audioRuntime, failure);
        failure = closeOwned(previewOperations, failure);
        failure = closeOwned(renderTargetManager, failure);
        failure = closeOwned(catalogManager, failure);
        failure = closeOwned(detachRemoteStore(), failure);
        try {
            finishExecutors(scheduler, notificationTimer);
        } catch (Throwable closeFailure) {
            if (failure == null)
                failure = closeFailure;
            else
                failure.addSuppressed(closeFailure);
        }
        if (failure != null)
            throw sneakyThrow(failure);
    }

    private static Throwable closeOwned(AutoCloseable owner, Throwable failure) {
        if (owner != null) {
            try {
                owner.close();
            } catch (Throwable closeFailure) {
                if (failure == null)
                    return closeFailure;
                failure.addSuppressed(closeFailure);
            }
        }
        return failure;
    }

    private static void finishExecutors(ClientResourceScheduler scheduler, ScheduledThreadPoolExecutor timer) {
        try {
            if (timer != null)
                timer.shutdown();
        } finally {
            scheduler.shutdown();
        }
        var interrupted = false;
        try {
            if (timer != null) {
                while (!timer.isTerminated()) {
                    try {
                        timer.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS);
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
            }
            while (true) {
                try {
                    scheduler.awaitTermination();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    static ModelExportService.Environment createExportEnvironment(net.minecraft.server.MinecraftServer server,
        java.util.function.Supplier<com.elfmcys.ysm.model.catalog.snapshot.ServerCatalog> catalog) {
        var service = instance();
        return new ModelExportService.Environment(server,
            () -> !service.closed.get() && Minecraft.getInstance().getSingleplayerServer() == server, catalog,
            service.previewOperations::export);
    }

    private synchronized RemoteModelStore remoteStore() throws IOException {
        if (closed.get()) {
            throw new IllegalStateException("Client model service is closed");
        }
        if (remoteStore == null) {
            remoteStore = RemoteModelStore.create(system.storage());
        }
        return remoteStore;
    }

    private synchronized RemoteModelStore detachRemoteStore() {
        var store = remoteStore;
        remoteStore = null;
        return store;
    }

    private static Throwable unwrap(Throwable error) {
        var current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static RuntimeException sneakyThrow(Throwable error) {
        ClientModelService.<RuntimeException>throwUnchecked(error);
        throw new AssertionError("unreachable");
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwUnchecked(Throwable error) throws E {
        throw (E)error;
    }

    private record BootstrapCandidate(ClientModelService service,
        CompletableFuture<Void> required) implements AutoCloseable {
        @Override
        public void close() {
            service.close();
        }
    }

}
