package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity;
import com.elfmcys.ysm.expression.host.ExpressionBinding;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.schema.model.ModelManifestLookup;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogManager;
import com.elfmcys.ysm.model.catalog.client.entry.ClientCatalogEntry;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.LivingEntity;

/** Owns render-target loading, lease retention, builtin initialization and catalog invalidation. */
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
    private final Map<Class<?>, ExpressionBinding> subjectBindings = new IdentityHashMap<>();
    private final Map<Class<?>, ExpressionBinding> defaultSubjectBindings = new IdentityHashMap<>();

    private volatile CompletableFuture<Void> defaultInitialization;
    private volatile ModelRenderTarget defaultRenderTarget;
    private volatile Hash256 defaultModelId;
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

    public ResourceRequest request(Hash256 modelId, String targetId, String textureName) {
        return request(modelId, targetId, textureName, ModelResourcePurpose.FULL, null);
    }

    public ResourceRequest request(Hash256 modelId, String targetId, String textureName, ModelResourcePurpose purpose,
        Class<?> subjectType) {
        boolean defaultModel = modelId.equals(defaultModelId);
        if (defaultModel) {
            subjectType = LivingEntity.class;
        } else if (purpose.isGui())
            subjectType = CustomGuiPlayerEntity.FakePlayer.class;
        else if (subjectType == null && targetId.equals(RenderTargetIds.PLAYER))
            subjectType = AbstractClientPlayer.class;
        return new ResourceRequest(modelId, targetId, textureName, bakeProfile,
            subjectBinding(defaultModel, subjectType), purpose);
    }

    private synchronized ExpressionBinding subjectBinding(boolean defaultModel, Class<?> subjectType) {
        if (subjectType == null)
            return loader.expressionBinding(defaultModel);
        if (subjectType != LivingEntity.class && subjectType != AbstractClientPlayer.class
            && subjectType != CustomGuiPlayerEntity.FakePlayer.class)
            throw new IllegalArgumentException("Unsupported model subject specialization: " + subjectType);
        var bindings = defaultModel ? defaultSubjectBindings : subjectBindings;
        return bindings.computeIfAbsent(subjectType, type -> loader.expressionBinding(defaultModel, type));
    }

    public ResourceRequest defaultRequest(String targetId) {
        return defaultRequest(targetId, ModelResourcePurpose.FULL, null);
    }

    public ResourceRequest defaultRequest(String targetId, ModelResourcePurpose purpose, Class<?> subjectType) {
        var renderTarget = defaultRenderTarget();
        var entry = catalogs.snapshot().find(renderTarget.modelHash()).orElseThrow();
        var resolvedTarget =
            entry.displayRepresentation().view().getRenderTarget(targetId) == null ? RenderTargetIds.PLAYER : targetId;
        return request(renderTarget.modelHash(), resolvedTarget, "", purpose, subjectType);
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
        return getOrStart(request, resolved, context.owner(), context.fetcher(), priority);
    }

    public ReadyAcquisition getOrStartReady(ResourceRequest request) {
        return getOrStartReady(request, WorkPriority.BACKGROUND);
    }

    public ReadyAcquisition getOrStartReady(ResourceRequest request, WorkPriority priority) {
        var context = currentWorkContext();
        if (request.purpose().isGui())
            return new ReadyAcquisition(request, getOrStartCached(request, context, priority),
                () -> getOrStartReadyExact(request, context, priority));
        return getOrStartReadyExact(request, context, priority);
    }

    private ReadyAcquisition getOrStartReadyExact(ResourceRequest request, WorkContext context, WorkPriority priority) {
        var lease = getOrStart(request, context, priority);
        var outcome = lease.poll();
        return new ReadyAcquisition(request, lease, outcome instanceof AcquireResult.Ready
            ? CompletableFuture.completedFuture(outcome) : renderTargets.terminal(lease));
    }

    public Optional<ResourceLease> findReady(ResourceRequest request) {
        requireAvailable();
        if (!permitsAcquisition(request))
            return Optional.empty();
        var resolved = resolve(request);
        return renderTargets.findReady(request, contentStore.exact(resolved.entry().content()), resolved.key(),
            this::isCurrent);
    }

    public CompletableFuture<Optional<ResourceLease>> getOrStartOffline(ResourceRequest request) {
        return getOrStartOffline(request, WorkPriority.BACKGROUND);
    }

    public CompletableFuture<Optional<ResourceLease>> getOrStartOffline(ResourceRequest request,
        WorkPriority priority) {
        var context = currentWorkContext();
        return getOrStartOfflineExact(request, context, priority);
    }

    private synchronized CompletableFuture<Optional<ResourceLease>> getOrStartOfflineExact(ResourceRequest request,
        WorkContext context, WorkPriority priority) {
        requireCurrentWorkContext(context);
        requireAvailable();
        if (!permitsAcquisition(request))
            return CompletableFuture.completedFuture(Optional.empty());
        var resolved = resolve(request);
        var workOwner = context.owner();
        var entry = resolved.entry();
        if (entry.hasLocalSource()) {
            var lease = getOrStart(request, resolved, workOwner, context.fetcher(), priority);
            return CompletableFuture.completedFuture(Optional.of(lease));
        }
        if (!(entry.content() instanceof RemoteModelContent remote)) {
            return CompletableFuture
                .failedFuture(new IllegalStateException("Remote catalog entry has no remote model content"));
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

    public CompletableFuture<Optional<ResourceLease>> getOrStartCached(ResourceRequest request) {
        return getOrStartCached(request, WorkPriority.BACKGROUND);
    }

    public CompletableFuture<Optional<ResourceLease>> getOrStartCached(ResourceRequest request, WorkPriority priority) {
        return getOrStartCached(request, currentWorkContext(), priority);
    }

    private CompletableFuture<Optional<ResourceLease>> getOrStartCached(ResourceRequest request, WorkContext context,
        WorkPriority priority) {
        return getOrStartCachedExact(request, context, priority);
    }

    private synchronized CompletableFuture<Optional<ResourceLease>> getOrStartCachedExact(ResourceRequest request,
        WorkContext context, WorkPriority priority) {
        requireCurrentWorkContext(context);
        requireAvailable();
        if (!permitsAcquisition(request))
            return CompletableFuture.completedFuture(Optional.empty());
        var resolved = resolve(request);
        var entry = resolved.entry();
        var content = contentStore.exact(entry.content());
        var ready = renderTargets.findReady(request, content, resolved.key(), this::isCurrent);
        if (ready.isPresent())
            return CompletableFuture.completedFuture(ready);
        if (!loader.mayHaveCachedArtifacts(content, resolved.key()))
            return CompletableFuture.completedFuture(Optional.empty());
        ModelRenderTargetCache.Loader cachedLoader;
        if (entry.content() instanceof RemoteModelContent remote) {
            cachedLoader = (cancelled, dispatch) -> loadOffline(cancelled, dispatch, resolved, remote, true);
        } else {
            var startedAt = System.nanoTime();
            cachedLoader = (cancelled, dispatch) -> loader
                .loadAsync(cancelled, dispatch, content, resolved.key(), entry.modelHash().equals(defaultModelId), true,
                    resourceFailures(entry.content(), entry))
                .thenApply(outcome -> traceLoad(outcome, startedAt, entry.modelHash(),
                    catalogs.displayPath(entry.modelHash()), resolved.key().targetId(),
                    resolved.key().selectedTexture(), entry.origin().name().toLowerCase(Locale.ROOT)));
        }
        return renderTargets.getOrStartCached(request, content, resolved.key(), cachedLoader, this::isCurrent,
            context.owner(), priority);
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

    public VerifiedChunkAccess resolveSound(SoundSource source) throws AssetLoadException {
        Objects.requireNonNull(source, "source");
        requireAvailable();
        if (!catalogs.snapshot().permitsRuntimeContent(source.representation().modelId())) {
            throw AssetLoadException.access("Current content policy denies new audio playback");
        }
        var entry = catalogs.snapshot().find(source.representation().modelId())
            .orElseThrow(() -> AssetLoadException.access("Model audio is no longer accessible"));
        if (!entry.content().representation().identity().equals(source.representation())) {
            throw AssetLoadException.access("Model audio representation is no longer current");
        }
        var context = currentWorkContext();
        return VerifiedChunkAccess.create(source, contentStore.exact(entry.content()), context.fetcher(), workers,
            hostExecutor);
    }

    private CompletableFuture<ModelRenderTargetLoader.LoadResult> load(BooleanSupplier cancelled, Dispatch dispatch,
        ResolvedResource resolved, RemoteChunkFetcher fetcher) {
        var entry = resolved.entry();
        var key = resolved.key();
        var content = contentStore.exact(entry.content());
        var startedAt = System.nanoTime();
        if (content instanceof RemoteModelContent remote) {
            return remote.loadRenderTargetAsync(cancelled, key.targetId(), key.selectedTexture(), fetcher, dispatch,
                prepared -> loader
                    .loadAsync(cancelled, dispatch, prepared, key, entry.modelHash().equals(defaultModelId),
                        resourceFailures(entry.content(), entry))
                    .thenApply(outcome -> traceLoad(outcome, startedAt, entry.modelHash(),
                        catalogs.displayPath(entry.modelHash()), key.targetId(), key.selectedTexture(),
                        entry.origin().name().toLowerCase(Locale.ROOT))),
                outcome -> {
                    if (outcome instanceof ModelRenderTargetLoader.LoadResult.Ready ready)
                        ready.candidate().close();
                }).handle((outcome, failure) -> {
                    if (failure != null) {
                        var cause = unwrap(failure);
                        if (cause instanceof Error fatal)
                            throw fatal;
                        if (cause instanceof RejectedExecutionException) {
                            cause = AssetLoadException.access("Remote model worker admission failed", cause);
                        } else if (!(cause instanceof AssetLoadException)
                            && !(cause instanceof CancellationException)) {
                            cause = AssetLoadException.content("Failed to prepare remote model activation", cause);
                        }
                        return ModelRenderTargetLoader.failure(cause);
                    }
                    return outcome;
                });
        }
        return loader.loadAsync(cancelled, dispatch, content, key, entry.modelHash().equals(defaultModelId),
            resourceFailures(entry.content(), entry)).thenApply(
                outcome -> traceLoad(outcome, startedAt, entry.modelHash(), catalogs.displayPath(entry.modelHash()),
                    key.targetId(), key.selectedTexture(), entry.origin().name().toLowerCase(Locale.ROOT)));
    }

    private CompletableFuture<ModelRenderTargetLoader.LoadResult> loadOffline(BooleanSupplier cancelled,
        Dispatch dispatch, ResolvedResource resolved, RemoteModelContent remote) {
        return loadOffline(cancelled, dispatch, resolved, remote, false);
    }

    private CompletableFuture<ModelRenderTargetLoader.LoadResult> loadOffline(BooleanSupplier cancelled,
        Dispatch dispatch, ResolvedResource resolved, RemoteModelContent remote, boolean cacheOnly) {
        return CompletableFuture
            .supplyAsync(() -> cacheOnly ? Optional.of(remote.cachedOnlyView()) : remote.openCached(cancelled),
                dispatch)
            .thenCompose(cached -> {
                if (cached.isEmpty()) {
                    var unavailable =
                        cancelled.getAsBoolean() ? new CancellationException("Offline model load was cancelled")
                            : AssetLoadException.access("Remote model is not fully cached");
                    return CompletableFuture.completedFuture(new ModelRenderTargetLoader.LoadResult.Failed(
                        new ResourceFailure(ResourceFailure.Kind.TRANSIENT, unavailable)));
                }
                return loader
                    .loadAsync(cancelled, dispatch, cached.orElseThrow(), resolved.key(),
                        resolved.entry().modelHash().equals(defaultModelId), cacheOnly, ModelResourceFailures.none())
                    .thenApply(
                        outcome -> outcome instanceof ModelRenderTargetLoader.LoadResult.Failed failed
                            ? new ModelRenderTargetLoader.LoadResult.Failed(
                                new ResourceFailure(ResourceFailure.Kind.TRANSIENT, failed.failure().cause()))
                            : outcome);
            });
    }

    private CompletableFuture<Void> initializeDefault(ClientCatalogManager.LocalCatalogState state) {
        var startedAt = System.nanoTime();
        var defaultHandle = state.models().values().stream().filter(this::isDefault).findFirst()
            .orElseThrow(() -> new IllegalStateException("Builtin default model was not found"));
        defaultModelId = defaultHandle.representation().modelId();
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
                    request(handle.representation().modelId(), load.targetId(), "")).selectedTexture()))
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
        var entry = catalogs.snapshot().find(handle.representation().modelId()).orElseThrow();
        var request = request(handle.representation().modelId(), targetId, texture);
        var key = resolveEffectiveKey(handle.representation().view().getManifest(), request);
        var content = contentStore.exact(entry.content());
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
        long startedAt, Hash256 hash, String path, String targetId, String texture, String source) {
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
            catalogs.displayPath(entry.modelHash()), stage, resource, error);
    }

    private ResourceLease getOrStart(ResourceRequest request, ResolvedResource resolved, Object workOwner,
        RemoteChunkFetcher fetcher, WorkPriority priority) {
        var content = contentStore.exact(resolved.entry().content());
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
        if (!subjectBinding(request.modelId().equals(defaultModelId),
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
                request.expressionBinding(), request.purpose());
        }
        var configured = manifest.info().settings().defaultTexture().orElse("");
        if (!configured.isBlank() && containsTexture(target, configured)) {
            return new RenderTargetKey(request.targetId(), configured, request.bakeProfile(),
                request.expressionBinding(), request.purpose());
        }
        for (var texture : target.textures().object2ObjectEntrySet()) {
            return new RenderTargetKey(request.targetId(), texture.getKey(), request.bakeProfile(),
                request.expressionBinding(), request.purpose());
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

    private boolean isDefault(ManagedContainer handle) {
        return handle.location().rootKind() == CatalogRootKind.BUILTIN
            && handle.location().path().value().equals("default");
    }

    private void requireAvailable() {
        var initialization = defaultInitialization;
        if (initialization == null || !initialization.isDone() || initialization.isCompletedExceptionally()) {
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

    /** Linearizes cancellation, terminal disposition and lease ownership transfer. */
    public static final class ReadyAcquisition {
        private final ResourceRequest request;
        private ResourceLease lease;
        private CompletableFuture<Optional<ResourceLease>> probe;
        private ReadyAcquisition delegate;
        private final CompletableFuture<ResourceLease> result = new CompletableFuture<>();
        private boolean ownsLease = true;

        public ReadyAcquisition(ResourceRequest request, ResourceLease lease,
            CompletableFuture<AcquireResult> terminal) {
            this.request = Objects.requireNonNull(request, "request");
            this.lease = Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(terminal, "terminal");
            terminal.whenComplete(this::settle);
        }

        ReadyAcquisition(ResourceRequest request, CompletableFuture<Optional<ResourceLease>> probe,
            Supplier<ReadyAcquisition> fallback) {
            this.request = Objects.requireNonNull(request, "request");
            this.probe = Objects.requireNonNull(probe, "probe");
            probe.whenComplete((ready, failure) -> {
                synchronized (this) {
                    if (!ownsLease) {
                        if (ready != null)
                            ready.ifPresent(ResourceLease::close);
                    } else if (failure != null)
                        settle(null, failure);
                    else if (ready.isPresent()) {
                        lease = ready.get();
                        try {
                            settle(lease.poll(), null);
                        } catch (Throwable rejected) {
                            settle(null, rejected);
                        }
                    } else {
                        try {
                            delegate = fallback.get();
                            delegate.result().whenComplete((next, rejected) -> {
                                synchronized (this) {
                                    if (!ownsLease) {
                                        if (next != null)
                                            next.close();
                                    } else {
                                        lease = next;
                                        try {
                                            settle(next == null ? null : next.poll(), rejected);
                                        } catch (Throwable failed) {
                                            settle(null, failed);
                                        }
                                    }
                                }
                            });
                        } catch (Throwable rejected) {
                            settle(null, rejected);
                        }
                    }
                }
            });
        }

        public CompletionStage<ResourceLease> result() {
            return result.minimalCompletionStage();
        }

        public void cancel() {
            synchronized (this) {
                if (!ownsLease)
                    return;
                ownsLease = false;
            }
            // A delegated completion may hold its own lock while delivering into this owner.
            var failure = cancelLease(new CancellationException("Render-target acquisition was cancelled"));
            result.completeExceptionally(failure);
        }

        private synchronized void settle(AcquireResult outcome, Throwable failure) {
            if (!ownsLease) {
                return;
            }
            ownsLease = false;
            if (failure != null) {
                result.completeExceptionally(closeLease(unwrap(failure)));
                return;
            }
            if (outcome instanceof AcquireResult.Ready) {
                try {
                    if (lease.isCurrent(request)) {
                        if (!result.complete(lease)) {
                            var rejection = closeLease(null);
                            if (rejection != null) {
                                YesSteveModel.LOGGER.warn("Failed to close a rejected render-target lease", rejection);
                            }
                        }
                        return;
                    }
                } catch (Throwable currentFailure) {
                    result.completeExceptionally(closeLease(currentFailure));
                    return;
                }
            }
            var cause = outcome instanceof AcquireResult.Failed failed ? failed.failure().cause()
                : new CancellationException("Model render target was no longer current");
            result.completeExceptionally(closeLease(cause));
        }

        private Throwable cancelLease(Throwable failure) {
            try {
                if (probe != null)
                    probe.cancel(false);
                if (delegate != null)
                    delegate.cancel();
                if (lease != null)
                    lease.cancelPending();
            } catch (Throwable closeFailure) {
                return append(failure, closeFailure);
            }
            return failure;
        }

        private Throwable closeLease(Throwable failure) {
            try {
                if (lease != null)
                    lease.close();
            } catch (Throwable closeFailure) {
                return append(failure, closeFailure);
            }
            return failure;
        }

        private static Throwable append(Throwable failure, Throwable next) {
            if (failure == null) {
                return next;
            }
            if (failure != next) {
                failure.addSuppressed(next);
            }
            return failure;
        }
    }

    private static final class SessionOwner {
        private RemoteChunkFetcher remoteFetcher;
    }
}
