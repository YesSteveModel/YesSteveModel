package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity;
import com.elfmcys.ysm.expression.host.ExpressionBinding;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.schema.model.ModelManifestLookup;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogManager;
import com.elfmcys.ysm.model.catalog.client.entry.ClientCatalogEntry;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.RenderTargetIds;
import com.elfmcys.ysm.model.resource.RuntimeContentStore;
import com.elfmcys.ysm.model.resource.client.audio.VerifiedChunkAccess;
import com.elfmcys.ysm.model.resource.client.failure.ModelFailureNotificationAggregator;
import com.elfmcys.ysm.model.resource.client.failure.ModelFailureRegistry;
import com.elfmcys.ysm.model.resource.client.remote.RemoteChunkFetcher;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelContent;
import com.elfmcys.ysm.model.resource.client.render.DefaultAnimationRuntime;
import com.elfmcys.ysm.model.resource.client.render.ModelRenderTargetCache;
import com.elfmcys.ysm.model.resource.client.render.ModelRenderTargetLoader;
import com.elfmcys.ysm.model.resource.client.render.RenderTargetKey;
import com.elfmcys.ysm.model.resource.client.render.RenderTargetLeaseOwnership;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler.Dispatch;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.proto.mixel.manifest.ManifestProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetProto;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * Resolves current model uses and owns client render-target preparation across process and session work.
 *
 * <p>
 * <b>Invariant:</b> New acquisition and audio playback use current catalog policy. Exact content identity, expression
 * binding, purpose and storage provenance determine reuse; a matching model ID alone does not establish a reusable
 * target. Content activation does not imply host Ready publication.
 *
 * <p>
 * <b>Ownership:</b> Each successful lease reception transfers an independent interest to its caller. Session retirement
 * cancels that session's pending work; existing Ready leases and independent playback follow their own owners. The
 * required default initialization must finish before ordinary acquisition.
 *
 * <p>
 * <b>Seam:</b> [client target
 * acquisition](docs/architecture/model-management/client-resources/overview.md#seam-contract).
 */
public final class ClientModelRenderTargetManager implements AutoCloseable {
    private final ClientCatalogManager catalogs;
    private final ModelRenderTargetLoader loader;
    private final DefaultAnimationRuntime defaultAnimations;
    private final Executor workers;
    private final Executor requiredExecutor;
    private final Object processOwner = new Object();
    private final Executor hostExecutor;
    private final ModelFailureRegistry failures = new ModelFailureRegistry();
    private final ModelRenderTargetCache renderTargets;
    private final BakeProfile bakeProfile;
    private final ModelFailureNotificationAggregator notifications;
    private final RenderTargetLeaseOwnership leaseOwnership = new RenderTargetLeaseOwnership();
    private final RuntimeContentStore contentStore;
    private final Map<Class<?>, ExpressionBinding> subjectBindings;
    private final ExpressionBinding defaultSubjectBinding;

    private volatile CompletableFuture<Void> defaultInitialization;
    private volatile ModelRenderTarget defaultRenderTarget;
    private SessionOwner sessionOwner;
    private boolean closed;

    public ClientModelRenderTargetManager(ClientCatalogManager catalogs, ModelRenderTargetLoader loader,
        DefaultAnimationRuntime defaultAnimations, ClientResourceScheduler resourceScheduler,
        ScheduledExecutorService notificationTimer, Executor hostExecutor) {
        this(catalogs, loader, defaultAnimations, resourceScheduler, notificationTimer, hostExecutor,
            new RuntimeContentStore(
                error -> YesSteveModel.LOGGER.warn("Model source instance became corrupted", error)));
    }

    public ClientModelRenderTargetManager(ClientCatalogManager catalogs, ModelRenderTargetLoader loader,
        DefaultAnimationRuntime defaultAnimations, ClientResourceScheduler resourceScheduler,
        ScheduledExecutorService notificationTimer, Executor hostExecutor, RuntimeContentStore contentStore) {
        this.catalogs = catalogs;
        this.loader = loader;
        this.bakeProfile = loader.bakeProfile();
        this.defaultAnimations = defaultAnimations;
        this.workers = resourceScheduler.backgroundExecutor();
        this.requiredExecutor = resourceScheduler.dispatch(WorkPriority.LOCAL_PLAYER);
        this.hostExecutor = Objects.requireNonNull(hostExecutor, "hostExecutor");
        this.contentStore = Objects.requireNonNull(contentStore, "contentStore");
        // The loader already owns the frozen declarations; publish every supported specialization with this owner.
        subjectBindings =
            Map.of(LivingEntity.class, loader.expressionBinding(false, LivingEntity.class), AbstractClientPlayer.class,
                loader.expressionBinding(false, AbstractClientPlayer.class), CustomGuiPlayerEntity.FakePlayer.class,
                loader.expressionBinding(false, CustomGuiPlayerEntity.FakePlayer.class));
        defaultSubjectBinding = loader.expressionBinding(true, LivingEntity.class);
        renderTargets = new ModelRenderTargetCache(resourceScheduler);
        notifications = new ModelFailureNotificationAggregator(notificationTimer);
    }

    public synchronized CompletableFuture<Void>
        startRequired(CompletableFuture<ClientCatalogManager.LocalCatalogState> localInitialization) {
        if (closed) {
            throw new CancellationException("Render-target manager was closed");
        }
        if (defaultInitialization != null) {
            throw new IllegalStateException("Builtin initialization has already started");
        }
        var defaultAndCatalog = localInitialization
            .thenComposeAsync(state -> initializeDefault(state).thenApply(ignored -> state), requiredExecutor);
        defaultInitialization = defaultAndCatalog.thenAccept(ignored -> {
        });
        defaultInitialization.whenComplete((ignored, error) -> {
            if (error != null) {
                YesSteveModel.LOGGER.error("Failed to initialize the builtin default model", unwrap(error));
            }
        });
        return defaultInitialization;
    }

    public ModelRenderTarget defaultRenderTarget() {
        if (defaultRenderTarget == null) {
            throw new IllegalStateException("The builtin default model is not ready");
        }
        return defaultRenderTarget;
    }

    public BakeProfile bakeProfile() {
        return bakeProfile;
    }

    public synchronized Object beginSession() {
        if (sessionOwner != null) {
            throw new IllegalStateException("Client model session is already active");
        }
        sessionOwner = new SessionOwner();
        return sessionOwner;
    }

    public synchronized void bindRemoteFetcher(Object owner, RemoteChunkFetcher fetcher) {
        if (sessionOwner != owner) {
            throw new IllegalStateException("Client model session is not current");
        }
        sessionOwner.remoteFetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    public ResourceRequest request(ModelId modelId, String targetId, String textureName) {
        return request(modelId, targetId, textureName, ModelResourcePurpose.FULL, null);
    }

    public ResourceRequest request(ModelId modelId, String targetId, String textureName, ModelResourcePurpose purpose,
        Class<?> subjectType) {
        return request(PublishedModelId.model(modelId), targetId, textureName, purpose, subjectType);
    }

    /** Intrinsic requests always select the resident FULL baseline, including preview callers. */
    public ResourceRequest request(PublishedModelId modelId, String targetId, String textureName,
        ModelResourcePurpose purpose, Class<?> subjectType) {
        boolean defaultModel = modelId.isIntrinsicDefault();
        if (defaultModel) {
            // Intrinsic previews consume the same resident baseline as entity fallback.
            subjectType = LivingEntity.class;
            purpose = ModelResourcePurpose.FULL;
        } else if (purpose.isGui())
            subjectType = CustomGuiPlayerEntity.FakePlayer.class;
        else if (subjectType == null && targetId.equals(RenderTargetIds.PLAYER))
            subjectType = AbstractClientPlayer.class;
        return new ResourceRequest(modelId, targetId, textureName, bakeProfile,
            subjectBinding(defaultModel, subjectType), purpose);
    }

    private ExpressionBinding subjectBinding(boolean defaultModel, Class<?> subjectType) {
        if (subjectType == null)
            return loader.expressionBinding(defaultModel);
        if (subjectType != LivingEntity.class && subjectType != AbstractClientPlayer.class
            && subjectType != CustomGuiPlayerEntity.FakePlayer.class)
            throw new IllegalArgumentException("Unsupported model subject specialization: " + subjectType);
        return defaultModel ? defaultSubjectBinding : subjectBindings.get(subjectType);
    }

    public ResourceRequest defaultRequest(String targetId) {
        return defaultRequest(targetId, ModelResourcePurpose.FULL, null);
    }

    public ResourceRequest defaultRequest(String targetId, ModelResourcePurpose purpose, Class<?> subjectType) {
        defaultRenderTarget();
        var entry = catalogs.snapshot().find(PublishedModelId.INTRINSIC_DEFAULT).orElseThrow();
        var resolvedTarget =
            entry.displayRepresentation().view().getRenderTarget(targetId) == null ? RenderTargetIds.PLAYER : targetId;
        return request(PublishedModelId.INTRINSIC_DEFAULT, resolvedTarget, "", purpose, subjectType);
    }

    public ResourceLease getOrStart(ResourceRequest request) {
        return getOrStart(request, WorkPriority.BACKGROUND);
    }

    public ResourceLease getOrStart(ResourceRequest request, WorkPriority priority) {
        return getOrStart(request, currentWorkContext(), priority);
    }

    private synchronized ResourceLease getOrStart(ResourceRequest request, WorkContext context, WorkPriority priority) {
        requireCurrentWorkContext(context);
        requireAvailable();
        if (!permitsAcquisition(request)) {
            throw new IllegalStateException("Current content policy denies new model use");
        }
        var resolved = resolve(request);
        if (request.modelId().isIntrinsicDefault())
            return requiredReady(request, resolved);
        return getOrStart(request, resolved, context.owner(), context.fetcher(), priority);
    }

    public Optional<ResourceLease> findReady(ResourceRequest request) {
        requireAvailable();
        if (!permitsAcquisition(request))
            return Optional.empty();
        var resolved = resolve(request);
        return renderTargets.findReady(request, runtimeContent(resolved), resolved.key(), this::isCurrent);
    }

    public ResourceLease getOrStartOffline(ResourceRequest request) {
        return getOrStartOffline(request, WorkPriority.BACKGROUND);
    }

    public ResourceLease getOrStartOffline(ResourceRequest request, WorkPriority priority) {
        var context = currentWorkContext();
        return getOrStartOfflineExact(request, context, priority);
    }

    private synchronized ResourceLease getOrStartOfflineExact(ResourceRequest request, WorkContext context,
        WorkPriority priority) {
        requireCurrentWorkContext(context);
        requireAvailable();
        if (!permitsAcquisition(request))
            return ResourceLease.missing(request);
        var resolved = resolve(request);
        if (request.modelId().isIntrinsicDefault())
            return requiredReady(request, resolved);
        var workOwner = context.owner();
        var entry = resolved.entry();
        if (entry.hasLocalSource()) {
            var lease = getOrStart(request, resolved, workOwner, context.fetcher(), priority);
            return lease;
        }
        if (!(entry.content() instanceof RemoteModelContent remote)) {
            throw new IllegalStateException("Remote catalog entry has no remote model content");
        }
        return renderTargets.getOrStartOffline(request, entry.content(), resolved.key(),
            (cancelled, dispatch) -> loadOffline(cancelled, dispatch, resolved, remote), this::isCurrent, workOwner,
            priority);
    }

    public void catalogChanged(ClientCatalogManager.CatalogChange change) {
        change.previous().models().forEach((hash, previous) -> {
            var current = change.current().models().get(hash);
            if (current == null || !previous.content().representation().identity()
                .equals(current.content().representation().identity())) {
                failures.clear(previous.content());
                notifications.recovered(previous.content());
            }
        });
    }

    public ResourceLease getOrStartCached(ResourceRequest request) {
        return getOrStartCached(request, WorkPriority.BACKGROUND);
    }

    public ResourceLease getOrStartCached(ResourceRequest request, WorkPriority priority) {
        return getOrStartCached(request, currentWorkContext(), priority);
    }

    private ResourceLease getOrStartCached(ResourceRequest request, WorkContext context, WorkPriority priority) {
        return getOrStartCachedExact(request, context, priority);
    }

    private synchronized ResourceLease getOrStartCachedExact(ResourceRequest request, WorkContext context,
        WorkPriority priority) {
        requireCurrentWorkContext(context);
        requireAvailable();
        if (!permitsAcquisition(request))
            return ResourceLease.missing(request);
        var resolved = resolve(request);
        var entry = resolved.entry();
        var content = runtimeContent(resolved);
        var ready = renderTargets.findReady(request, content, resolved.key(), this::isCurrent);
        if (ready.isPresent())
            return ready.orElseThrow();
        if (request.modelId().isIntrinsicDefault())
            throw new IllegalStateException("Required intrinsic resource was not retained: " + request);
        if (!loader.mayHaveCachedArtifacts(content, resolved.key()))
            return ResourceLease.missing(request);
        ModelRenderTargetCache.Loader cachedLoader;
        if (entry.content() instanceof RemoteModelContent remote) {
            cachedLoader = (cancelled, dispatch) -> loadOffline(cancelled, dispatch, resolved, remote, true);
        } else {
            var startedAt = System.nanoTime();
            cachedLoader = (cancelled, dispatch) -> {
                var loading = loader.loadAsync(cancelled, dispatch, content, resolved.key(), false, true,
                    resourceFailures(entry.content(), entry));
                loading.thenAccept(outcome -> traceLoad(outcome, startedAt, entry.modelHash(), entry.displayPath(),
                    resolved.key().targetId(), resolved.key().selectedTexture(),
                    entry.origin().name().toLowerCase(Locale.ROOT)));
                return loading;
            };
        }
        return renderTargets.getOrStartCached(request, content, resolved.key(), cachedLoader, this::isCurrent,
            context.owner(), priority);
    }

    /**
     * Acquires a GUI use for the exact published content, or a fixed input use when it is unpublished. The caller owns
     * the returned lease from acceptance; preparation and late candidates remain here.
     */
    public synchronized ResourceLease acquirePreview(ModelContent content) {
        var request = request(content.modelId(), RenderTargetIds.PLAYER, "", ModelResourcePurpose.GUI, null);
        var published = catalogs.snapshot().find(content.modelId()).map(ClientCatalogEntry::content)
            .filter(current -> current.representation().identity().equals(content.representation().identity()));
        return published.isPresent() ? getOrStart(request) : acquireDetachedPreview(content, null);
    }

    /**
     * Acquires fixed, verified input for export or management preview generation. Only exact existing Ready may be
     * reused; new work has no Catalog publication or ordinary session-fetcher authority.
     */
    public synchronized ResourceLease acquireDetachedPreview(ModelContent content, RemoteChunkFetcher fetcher) {
        requireAvailable();
        var request = request(content.modelId(), RenderTargetIds.PLAYER, "", ModelResourcePurpose.GUI, null);
        var published = catalogs.snapshot().find(content.modelId()).map(ClientCatalogEntry::content)
            .filter(current -> current.representation().identity().equals(content.representation().identity()));
        if (published.isPresent() && permitsAcquisition(request)) {
            var ready = findReady(request);
            if (ready.isPresent())
                return ready.orElseThrow();
        }
        var key = resolveEffectiveKey(content.representation().view().getManifest(), request);
        return renderTargets.getOrStartDetached(request, content, key, (cancelled, dispatch) -> loadContent(cancelled,
            dispatch, content, key, fetcher, ModelResourceFailures.none()), processOwner, WorkPriority.BACKGROUND);
    }

    public void disconnect(Object owner) {
        synchronized (this) {
            if (sessionOwner != owner) {
                throw new IllegalStateException("Client model epoch is not current");
            }
            sessionOwner = null;
        }
        renderTargets.clearSession(owner);
    }

    public boolean permitsAcquisition(ResourceRequest request) {
        return catalogs.snapshot().permitsRuntimeContent(request.modelId());
    }

    public void tick() {
        contentStore.tick();
        renderTargets.tick();
    }

    public int loadingCount() {
        return renderTargets.loadingCount();
    }

    public void reportActiveFailure(ModelContent version, boolean fallbackAvailable) {
        notifications.report(version, fallbackAvailable ? ModelFailureNotificationAggregator.Outcome.FALLBACK
            : ModelFailureNotificationAggregator.Outcome.STOPPED);
    }

    public void reportActiveUse(ResourceRequest request) {
        if (request.modelId().isIntrinsicDefault())
            return;
        var resolved = resolve(request);
        var texturePrefix = resolved.key().targetId() + "/" + resolved.key().selectedTexture() + "/";
        var animationPrefix = resolved.key().targetId() + "/";
        if (failures.hasMatching(resolved.entry().content(), ModelFailureRegistry.Stage.TEXTURE,
            resource -> resource.startsWith(texturePrefix))
            || failures.hasMatching(resolved.entry().content(), ModelFailureRegistry.Stage.ANIMATION,
                resource -> resource.startsWith(animationPrefix))) {
            notifications.report(resolved.entry().content(), ModelFailureNotificationAggregator.Outcome.PARTIAL);
        }
    }

    /**
     * Admits a new playback and captures an independent exact-media reading capability. The admitted capability does
     * not depend on the originating target lease staying open.
     *
     * <p>
     * <b>Seam:</b> [playback admission](docs/architecture/model-management/audio/overview.md#接纳与读取).
     *
     * @param source the non-null verified media source with its retained first chunk
     * @return an owned sequential capability bound to this representation and the captured fetcher
     * @throws AssetLoadException if current policy, membership or representation rejects new playback
     * @throws IllegalStateException if required default resources are unavailable
     */
    public VerifiedChunkAccess resolveSound(SoundSource source) throws AssetLoadException {
        Objects.requireNonNull(source, "source");
        requireAvailable();
        if (!catalogs.snapshot().permitsRuntimeContent(source.modelId())) {
            throw AssetLoadException.access("Current content policy denies new audio playback");
        }
        var entry = catalogs.snapshot().find(source.modelId())
            .orElseThrow(() -> AssetLoadException.access("Model audio is no longer accessible"));
        if (!entry.content().representation().identity().equals(source.representation())) {
            throw AssetLoadException.access("Model audio representation is no longer current");
        }
        var context = currentWorkContext();
        return VerifiedChunkAccess.create(source,
            source.modelId().isIntrinsicDefault() ? entry.content() : contentStore.exact(entry.content()),
            source.modelId().isIntrinsicDefault() ? null : context.fetcher(), workers, hostExecutor);
    }

    private CompletableFuture<ModelRenderTargetLoader.LoadResult> load(BooleanSupplier cancelled, Dispatch dispatch,
        ResolvedResource resolved, RemoteChunkFetcher fetcher) {
        var content = runtimeContent(resolved);
        if (resolved.key().purpose().isGui() && loader.mayHaveCachedArtifacts(content, resolved.key())) {
            CompletableFuture<ModelRenderTargetLoader.LoadResult> probe;
            if (resolved.entry().content() instanceof RemoteModelContent remote)
                probe = loadOffline(cancelled, dispatch, resolved, remote, true);
            else
                probe = loader.loadAsync(cancelled, dispatch, content, resolved.key(), false, true,
                    resourceFailures(resolved.entry().content(), resolved.entry()));
            return continueLoad(probe, outcome -> {
                if (outcome instanceof ModelRenderTargetLoader.LoadResult.Ready
                    || ((ModelRenderTargetLoader.LoadResult.Failed)outcome).failure().isMemoryFailure()
                    || cancelled.getAsBoolean())
                    return CompletableFuture.completedFuture(outcome);
                return loadOnline(cancelled, dispatch, resolved, content, fetcher);
            }, ClientModelRenderTargetManager::discardLoad);
        }
        return loadOnline(cancelled, dispatch, resolved, content, fetcher);
    }

    private CompletableFuture<ModelRenderTargetLoader.LoadResult> loadOnline(BooleanSupplier cancelled,
        Dispatch dispatch, ResolvedResource resolved, ModelContent content, RemoteChunkFetcher fetcher) {
        var entry = resolved.entry();
        var key = resolved.key();
        var startedAt = System.nanoTime();
        var loading = loadContent(cancelled, dispatch, content, key, fetcher, resourceFailures(entry.content(), entry));
        loading.thenAccept(outcome -> traceLoad(outcome, startedAt, entry.modelHash(), entry.displayPath(),
            key.targetId(), key.selectedTexture(), entry.origin().name().toLowerCase(Locale.ROOT)));
        return loading;
    }

    private CompletableFuture<ModelRenderTargetLoader.LoadResult> loadContent(BooleanSupplier cancelled,
        Dispatch dispatch, ModelContent content, RenderTargetKey key, RemoteChunkFetcher fetcher,
        ModelResourceFailures resourceFailures) {
        if (!(content instanceof RemoteModelContent remote))
            return loader.loadAsync(cancelled, dispatch, content, key, false, resourceFailures);
        var loading = remote.loadRenderTargetAsync(cancelled, key.targetId(), key.selectedTexture(), fetcher, dispatch,
            prepared -> loader.loadAsync(cancelled, dispatch, prepared, key, false, resourceFailures),
            ClientModelRenderTargetManager::discardLoad);
        var result = new CompletableFuture<ModelRenderTargetLoader.LoadResult>();
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled())
                loading.cancel(false);
        });
        loading.whenComplete((outcome, failure) -> {
            if (failure == null) {
                if (!result.complete(outcome))
                    discardLoad(outcome);
                return;
            }
            var cause = unwrap(failure);
            if (cause instanceof Error) {
                result.completeExceptionally(cause);
                return;
            }
            if (cause instanceof RejectedExecutionException)
                cause = AssetLoadException.access("Remote model worker admission failed", cause);
            else if (!(cause instanceof AssetLoadException) && !(cause instanceof CancellationException))
                cause = AssetLoadException.content("Failed to prepare remote model activation", cause);
            result.complete(ModelRenderTargetLoader.failure(cause));
        });
        return result;
    }

    private static <T> CompletableFuture<ModelRenderTargetLoader.LoadResult> continueLoad(CompletableFuture<T> input,
        java.util.function.Function<T, CompletableFuture<ModelRenderTargetLoader.LoadResult>> next,
        java.util.function.Consumer<T> discard) {
        var result = new CompletableFuture<ModelRenderTargetLoader.LoadResult>();
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled())
                input.cancel(false);
        });
        input.whenComplete((value, failure) -> {
            if (failure != null) {
                result.completeExceptionally(failure);
                return;
            }
            if (result.isCancelled()) {
                discard.accept(value);
                return;
            }
            final CompletableFuture<ModelRenderTargetLoader.LoadResult> loading;
            try {
                loading = next.apply(value);
            } catch (Throwable rejected) {
                discard.accept(value);
                result.completeExceptionally(rejected);
                return;
            }
            result.whenComplete((ignored, error) -> {
                if (result.isCancelled())
                    loading.cancel(false);
            });
            loading.whenComplete((outcome, error) -> {
                if (error != null)
                    result.completeExceptionally(error);
                else if (!result.complete(outcome))
                    discardLoad(outcome);
            });
        });
        return result;
    }

    private static void discardLoad(ModelRenderTargetLoader.LoadResult result) {
        if (result instanceof ModelRenderTargetLoader.LoadResult.Ready ready)
            ready.candidate().close();
    }

    private CompletableFuture<ModelRenderTargetLoader.LoadResult> loadOffline(BooleanSupplier cancelled,
        Dispatch dispatch, ResolvedResource resolved, RemoteModelContent remote) {
        return loadOffline(cancelled, dispatch, resolved, remote, false);
    }

    private CompletableFuture<ModelRenderTargetLoader.LoadResult> loadOffline(BooleanSupplier cancelled,
        Dispatch dispatch, ResolvedResource resolved, RemoteModelContent remote, boolean cacheOnly) {
        final ModelContent.PreparationInputs inputs;
        try {
            inputs = remote.retainPreparationInputs();
        } catch (java.io.IOException failure) {
            return CompletableFuture.completedFuture(ModelRenderTargetLoader
                .failure(AssetLoadException.access("Failed to retain remote model inputs", failure)));
        }
        var retained = (RemoteModelContent)inputs.content();
        var opening = new CompletableFuture<Optional<ModelContent>>();
        var result = continueLoad(opening, cached -> {
            if (cached.isEmpty()) {
                var unavailable =
                    cancelled.getAsBoolean() ? new CancellationException("Offline model load was cancelled")
                        : AssetLoadException.access("Remote model is not fully cached");
                return CompletableFuture.completedFuture(new ModelRenderTargetLoader.LoadResult.Failed(
                    new ResourceFailure(ResourceFailure.Kind.TRANSIENT, unavailable)));
            }
            return loader.loadAsync(cancelled, dispatch, cached.orElseThrow(), resolved.key(), false, cacheOnly,
                ModelResourceFailures.none());
        }, ignored -> {
        });
        try {
            var queued = dispatch.enqueue(() -> {
                try {
                    if (!opening.isDone())
                        opening.complete(
                            cacheOnly ? Optional.of(retained.cachedOnlyView()) : retained.openCached(cancelled));
                } catch (Throwable failure) {
                    opening.completeExceptionally(failure);
                } finally {
                    inputs.close();
                }
            });
            result.whenComplete((ignored, failure) -> {
                if (result.isCancelled() && queued.withdraw())
                    inputs.close();
            });
        } catch (Throwable failure) {
            inputs.close();
            opening.completeExceptionally(failure);
        }
        return result;
    }

    private CompletableFuture<Void> initializeDefault(ClientCatalogManager.LocalCatalogState state) {
        var startedAt = System.nanoTime();
        var defaultHandle = (ManagedContainer)Objects
            .requireNonNull(state.snapshot().intrinsicDefault(), "Required intrinsic default was not found").binding()
            .content();
        var defaultLoads = defaultHandle.view().getRenderTargets().stream().flatMap(target -> target.getTextureNames()
            .stream().map(texture -> loadDefaultTarget(defaultHandle, target.id(), texture))).toList();
        return CompletableFuture.allOf(defaultLoads.toArray(CompletableFuture[]::new)).thenRun(() -> {
            var loaded = defaultLoads.stream().map(CompletableFuture::join).toList();
            var publication = requiredDefaultPublication(defaultHandle, loaded);
            publishRequiredDefault(publication);
            var elapsed = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            YesSteveModel.LOGGER.info("Finished builtin default model initialization targets={} elapsedMs={}",
                loaded.size(), elapsed);
        });
    }

    private RequiredDefaultPublication requiredDefaultPublication(ManagedContainer handle,
        List<DefaultTargetLoad> loaded) {
        var selected = loaded.stream().filter(load -> load.targetId().equals(RenderTargetIds.PLAYER))
            .filter(load -> load.texture()
                .equals(resolveEffectiveKey(handle.representation().view().getManifest(),
                    request(PublishedModelId.INTRINSIC_DEFAULT, load.targetId(), "", ModelResourcePurpose.FULL, null))
                    .selectedTexture()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Builtin default model contains no player render target"));
        var sources = new LinkedHashMap<String, DefaultAnimationRuntime.Source>();
        loaded.forEach(load -> load.renderTarget().defaultSources().forEach((domain, source) -> {
            var previous = sources.putIfAbsent(domain, source);
            if (previous != null && !previous.equals(source))
                throw new IllegalStateException("Conflicting default source domain: " + domain);
        }));
        return new RequiredDefaultPublication(selected.renderTarget(), Map.copyOf(sources));
    }

    private synchronized void publishRequiredDefault(RequiredDefaultPublication publication) {
        if (closed) {
            throw new CancellationException("Render-target manager was closed");
        }
        defaultAnimations.publishSources(publication.sources());
        defaultRenderTarget = publication.selectedTarget();
    }

    private CompletableFuture<DefaultTargetLoad> loadDefaultTarget(ManagedContainer handle, String targetId,
        String texture) {
        return loadRequiredBuiltinTarget(handle, targetId, texture)
            .thenApply(lease -> new DefaultTargetLoad(targetId, texture, readyTarget(lease), lease));
    }

    private CompletableFuture<ResourceLease> loadRequiredBuiltinTarget(ManagedContainer handle, String targetId,
        String texture) {
        var request = request(PublishedModelId.INTRINSIC_DEFAULT, targetId, texture, ModelResourcePurpose.FULL, null);
        var key = resolveEffectiveKey(handle.representation().view().getManifest(), request);
        var content = handle;
        var startedAt = System.nanoTime();
        var lease = renderTargets.getOrStartRequiredAsync(request, content, key,
            (cancelled, dispatch) -> loader
                .loadAsync(cancelled, dispatch, content, key, true, ModelResourceFailures.none())
                .thenApply(outcome -> traceLoad(outcome, startedAt, handle.representation().modelId(),
                    handle.location().value(), targetId, texture, CatalogRootKind.BUILTIN.namespace())),
            this::isCurrent, processOwner, hostExecutor);
        leaseOwnership.addRequired(lease);
        return renderTargets.terminal(lease).thenApply(result -> {
            if (result instanceof AcquireResult.Ready) {
                return lease;
            }
            leaseOwnership.removeRequired(lease);
            if (result instanceof AcquireResult.Failed failed) {
                throw new CompletionException(failed.failure().cause());
            }
            throw new IllegalStateException("Required resource load completed without a terminal result");
        });
    }

    private static ModelRenderTargetLoader.LoadResult traceLoad(ModelRenderTargetLoader.LoadResult result,
        long startedAt, ModelId hash, String path, String targetId, String texture, String source) {
        var elapsed = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        if (result instanceof ModelRenderTargetLoader.LoadResult.Ready) {
            YesSteveModel.LOGGER.debug(
                "Loaded model render target hash={} path={} target={} texture={} source={} elapsedMs={}", hash, path,
                targetId, texture, source, elapsed);
        } else if (result instanceof ModelRenderTargetLoader.LoadResult.Failed failed) {
            var cause = failed.failure().cause();
            if (!(cause instanceof CancellationException)) {
                YesSteveModel.LOGGER.debug(
                    "Failed to load model render target hash={} path={} target={} texture={} source={} elapsedMs={}",
                    hash, path, targetId, texture, source, elapsed, cause);
            }
        }
        return result;
    }

    private record DefaultTargetLoad(String targetId, String texture, ModelRenderTarget renderTarget,
        ResourceLease lease) {
    }

    private record RequiredDefaultPublication(ModelRenderTarget selectedTarget,
        Map<String, DefaultAnimationRuntime.Source> sources) {
    }

    private static Throwable unwrap(Throwable error) {
        var current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private ModelResourceFailures resourceFailures(ModelContent content, ClientCatalogEntry entry) {
        return new ModelResourceFailures() {
            @Override
            public ModelResourceFailureGate texture(String resource) {
                return gate(ModelFailureRegistry.Stage.TEXTURE, resource);
            }

            @Override
            public ModelResourceFailureGate animation(String resource) {
                return gate(ModelFailureRegistry.Stage.ANIMATION, resource);
            }

            private ModelResourceFailureGate gate(ModelFailureRegistry.Stage stage, String resource) {
                return failures.gate(content, stage, resource, error -> resourceFailed(entry, stage, resource, error));
            }
        };
    }

    private void resourceFailed(ClientCatalogEntry entry, ModelFailureRegistry.Stage stage, String resource,
        Throwable error) {
        YesSteveModel.LOGGER.debug("Model lazy resource failed hash={} path={} stage={} resource={}", entry.modelHash(),
            entry.displayPath(), stage, resource, error);
    }

    private ResourceLease getOrStart(ResourceRequest request, ResolvedResource resolved, Object workOwner,
        RemoteChunkFetcher fetcher, WorkPriority priority) {
        var content = runtimeContent(resolved);
        return renderTargets.getOrStart(request, content, resolved.key(),
            (cancelled, dispatch) -> load(cancelled, dispatch, resolved, fetcher), this::isCurrent, workOwner,
            priority);
    }

    private synchronized WorkContext currentWorkContext() {
        return sessionOwner == null ? new WorkContext(processOwner, null)
            : new WorkContext(sessionOwner, sessionOwner.remoteFetcher);
    }

    private void requireCurrentWorkContext(WorkContext context) {
        // Validation and acquisition share this monitor with session retirement.
        if (closed || context.owner() != processOwner && context.owner() != sessionOwner) {
            throw new CancellationException("Render-target work owner is no longer current");
        }
    }

    private ResolvedResource resolve(ResourceRequest request) {
        if (!bakeProfile.equals(request.bakeProfile())) {
            throw new IllegalArgumentException("Unsupported bake profile: " + request.bakeProfile().key());
        }
        if (!subjectBinding(request.modelId().isIntrinsicDefault(),
            request.expressionBinding().bindingPlan().rootSubjectType().orElse(null))
            .equals(request.expressionBinding())) {
            throw new IllegalArgumentException("Resource expressionBinding is not current");
        }
        var entry = catalogs.snapshot().find(request.modelId())
            .orElseThrow(() -> new IllegalArgumentException("Unknown model id: " + request.modelId()));
        return new ResolvedResource(entry,
            resolveEffectiveKey(entry.displayRepresentation().view().getManifest(), request));
    }

    private boolean isCurrent(ModelFileIdentity identity, RenderTargetKey key, ResourceRequest request) {
        if (!bakeProfile.equals(request.bakeProfile())) {
            return false;
        }
        var entry = catalogs.snapshot().find(request.modelId()).orElse(null);
        if (entry == null || !entry.content().representation().identity().equals(identity)) {
            return false;
        }
        try {
            return key.equals(resolveEffectiveKey(entry.displayRepresentation().view().getManifest(), request));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static ModelRenderTarget readyTarget(ResourceLease lease) {
        if (lease.poll() instanceof AcquireResult.Ready ready) {
            return ready.target();
        }
        throw new IllegalStateException("Resource lease is not ready");
    }

    public static RenderTargetKey resolveEffectiveKey(ManifestProto manifest, ResourceRequest request) {
        var target = ModelManifestLookup.target(manifest, request.targetId());
        var requested = request.requestedTexture();
        if (requested != null && !requested.isBlank() && containsTexture(target, requested)) {
            return new RenderTargetKey(request.targetId(), requested, request.bakeProfile(),
                request.expressionBinding(), request.purpose(), request.modelId().role());
        }
        var configured = manifest.info().settings().defaultTexture().orElse("");
        if (!configured.isBlank() && containsTexture(target, configured)) {
            return new RenderTargetKey(request.targetId(), configured, request.bakeProfile(),
                request.expressionBinding(), request.purpose(), request.modelId().role());
        }
        for (var texture : target.textures().object2ObjectEntrySet()) {
            return new RenderTargetKey(request.targetId(), texture.getKey(), request.bakeProfile(),
                request.expressionBinding(), request.purpose(), request.modelId().role());
        }
        throw new IllegalArgumentException("Render target has no texture: " + request.targetId());
    }

    private static boolean containsTexture(RenderTargetProto target, String name) {
        for (var texture : target.textures().object2ObjectEntrySet()) {
            if (texture.getKey().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private ModelContent runtimeContent(ResolvedResource resolved) {
        return resolved.key().role() == PublishedModelId.Role.INTRINSIC_DEFAULT ? resolved.entry().content()
            : contentStore.exact(resolved.entry().content());
    }

    private ResourceLease requiredReady(ResourceRequest request, ResolvedResource resolved) {
        return renderTargets.findReady(request, runtimeContent(resolved), resolved.key(), this::isCurrent)
            .orElseThrow(() -> new IllegalStateException("Required intrinsic resource was not retained: " + request));
    }

    private void requireAvailable() {
        var initialization = defaultInitialization;
        if (closed || initialization == null || !initialization.isDone() || initialization.isCompletedExceptionally()) {
            throw new IllegalStateException("Builtin default resources are not ready");
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            defaultRenderTarget = null;
        }
        Throwable failure = null;
        failure = closeOwned(leaseOwnership, failure);
        failure = closeOwned(defaultAnimations::clear, failure);
        failure = closeOwned(notifications, failure);
        failure = closeOwned(renderTargets, failure);
        failure = closeOwned(loader, failure);
        failures.clear();
        if (failure instanceof RuntimeException error)
            throw error;
        if (failure instanceof Error error)
            throw error;
        if (failure != null)
            throw new IllegalStateException("Failed to close client render-target resources", failure);
    }

    private static Throwable closeOwned(AutoCloseable owner, Throwable failure) {
        try {
            owner.close();
        } catch (Throwable closeFailure) {
            if (failure == null)
                return closeFailure;
            failure.addSuppressed(closeFailure);
        }
        return failure;
    }

    private record ResolvedResource(ClientCatalogEntry entry, RenderTargetKey key) {
    }

    private record WorkContext(Object owner, RemoteChunkFetcher fetcher) {
    }

    private static final class SessionOwner {
        private RemoteChunkFetcher remoteFetcher;
    }
}
