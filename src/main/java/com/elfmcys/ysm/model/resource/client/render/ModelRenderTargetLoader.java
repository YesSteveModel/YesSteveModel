package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.model.storage.ModelStorageInfra;
import com.elfmcys.ysm.model.storage.CacheRegion;

import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.ModelResourceFailures;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.client.model.locator.PlayerLocator;
import com.elfmcys.ysm.model.resource.client.PlayerModelVariant;
import com.elfmcys.ysm.model.resource.client.ResourceFailure;
import com.elfmcys.ysm.expression.aot.BooleanProgram;
import com.elfmcys.ysm.expression.aot.EventProgram;
import com.elfmcys.ysm.expression.aot.FloatExpression;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModuleFactory;
import com.elfmcys.ysm.expression.host.ExpressionBinding;
import com.elfmcys.ysm.expression.aot.UserFunctionProgram;
import com.elfmcys.ysm.expression.aot.VoidProgram;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelContent;
import java.util.concurrent.CompletableFuture;
import java.util.Set;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.expression.inventory.MolangEventCatalog;
import com.elfmcys.ysm.model.resource.client.data.CommonAssetData;
import com.elfmcys.ysm.model.resource.client.SoundSource;
import com.elfmcys.ysm.model.resource.client.TargetSoundResources;
import com.elfmcys.ysm.model.resource.client.data.ModelRenderTargetBuildInput;
import com.elfmcys.ysm.model.resource.client.data.PlayerModelData;
import com.elfmcys.ysm.model.resource.client.data.ProjectileModelData;
import com.elfmcys.ysm.model.resource.client.data.RenderTargetData;
import com.elfmcys.ysm.model.resource.client.data.VehicleModelData;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.schema.file.AssetFileConstant;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.file.PBRImageSources;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.format.schema.model.views.RenderTargetView;
import com.elfmcys.ysm.geckolib3.file.AnimationControllerFile;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.catalog.content.DefaultAnimationKey;
import com.elfmcys.ysm.format.schema.model.ModelManifestLookup;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.common.ImageProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.PBRTextureSetProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.util.FifoHashMap;
import com.elfmcys.ysm.util.ResourceTransaction;
import com.elfmcys.ysm.util.ProtoUtil;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import us.hebi.quickbuf.ProtoSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import net.minecraft.client.player.AbstractClientPlayer;

/** Builds the current Java model render target from a model container view. */
public final class ModelRenderTargetLoader implements AutoCloseable {
    private static final int CURRENT_RAW_UV_VERSION = 29;

    private final DefaultBuildStage buildStage;
    private final ConsumerStage consumerStage;
    private final BakeProfile bakeProfile;
    private final RenderTargetExpressionModuleFactory expressionModules;

    public ModelRenderTargetLoader(BakedModelCache bakedModels, RenderTargetExpressionModuleFactory expressionModules,
        ClientResourceScheduler scheduler, DefaultAnimationRuntime defaultAnimations) {
        this.expressionModules = java.util.Objects.requireNonNull(expressionModules, "expressionModules");
        this.buildStage = new DefaultBuildStage(bakedModels, expressionModules, scheduler, defaultAnimations);
        this.consumerStage = buildStage::build;
        this.bakeProfile = new BakeProfile(
            bakedModels.profileKey() + "/" + BakedAnimationCache.profileKey() + "/raw-uv-" + CURRENT_RAW_UV_VERSION);
    }

    public ModelRenderTargetLoader(ModelStorageInfra storage, RenderTargetExpressionModuleFactory expressionModules,
        ClientResourceScheduler scheduler, DefaultAnimationRuntime defaultAnimations) {
        this(new BakedModelCache(storage.local().root().resolve("bake"), storage.local().cache()), expressionModules,
            scheduler, defaultAnimations);
        buildStage.regionBound = true;
    }

    public BakeProfile bakeProfile() {
        return bakeProfile;
    }

    public ExpressionBinding expressionBinding() {
        return expressionModules.expressionBinding();
    }

    public ExpressionBinding expressionBinding(Class<?> subjectType) {
        return expressionBinding(false, subjectType);
    }

    public ExpressionBinding expressionBinding(boolean defaultModel) {
        return expressionModules.expressionBinding(defaultModel);
    }

    public ExpressionBinding expressionBinding(boolean defaultModel, Class<?> subjectType) {
        if (subjectType == AbstractClientPlayer.class)
            return buildStage.playerBinding(defaultModel);
        return subjectType == null ? expressionBinding(defaultModel)
            : expressionModules.expressionBinding(defaultModel, subjectType);
    }

    boolean mayHaveCachedArtifacts(Hash256 containerId, RenderTargetKey key) {
        if (buildStage.regionBound)
            throw new IllegalStateException("A regional probe requires content provenance");
        return buildStage.prepared
            .containsReady(new PreparationKey(containerId, key.stateKey(), buildStage.bakedModels))
            || expressionModules.mayHaveCachedArtifacts(containerId, key.expressionBinding(), key.purpose());
    }

    public boolean mayHaveCachedArtifacts(ModelContent content, RenderTargetKey key) {
        var containerId = content.representation().containerId();
        var modules = buildStage.modules(content);
        return buildStage.prepared
            .containsReady(new PreparationKey(containerId, key.stateKey(), buildStage.region(content)))
            || modules.mayHaveCachedArtifacts(containerId, key.expressionBinding(), key.purpose());
    }

    public CompletableFuture<LoadResult> loadAsync(BooleanSupplier cancelled, ClientResourceScheduler.Dispatch dispatch,
        ModelContent content, RenderTargetKey key, boolean defaultModel, ModelResourceFailures resourceFailures) {
        return loadAsync(cancelled, dispatch, content, key, defaultModel, false, resourceFailures);
    }

    public CompletableFuture<LoadResult> loadAsync(BooleanSupplier cancelled, ClientResourceScheduler.Dispatch dispatch,
        ModelContent content, RenderTargetKey key, boolean defaultModel, boolean cacheOnly,
        ModelResourceFailures resourceFailures) {
        var representation = content.representation();
        var chunks = content.chunks();
        try {
            requireActive(cancelled);
            var view = representation.view();
            final RenderTargetView target;
            final String selectedTexture;
            try {
                target = view.requireRenderTarget(key.targetId());
                selectedTexture =
                    ModelManifestLookup.chooseTexture(view.getManifest(), key.targetId(), key.selectedTexture());
            } catch (IllegalArgumentException failure) {
                throw AssetLoadException.content("Invalid model render target selection", failure);
            }
            var request = new LoadRequest(representation, view, target, target.textureDescriptor(selectedTexture),
                target.textureSources(cancelled, chunks, selectedTexture), key.targetId(), selectedTexture,
                key.expressionBinding(), key.purpose(), defaultModel, cacheOnly, resourceFailures, key.stateKey(),
                content);
            requireActive(cancelled);
            var interest = buildStage.prepare(request, content, dispatch);
            var result = new CompletableFuture<LoadResult>();
            interest.whenComplete((pin, failure) -> {
                if (failure != null) {
                    completeFailure(result, failure);
                    return;
                }
                try {
                    var queued = dispatch.enqueue(() -> {
                        if (result.isDone()) {
                            pin.close();
                            return;
                        }
                        try {
                            requireActive(cancelled);
                            var candidate = consumerStage.build(() -> result.isCancelled() || cancelled.getAsBoolean(),
                                request, pin);
                            if (!result.complete(new LoadResult.Ready(candidate)))
                                candidate.close();
                        } catch (Throwable rejected) {
                            try {
                                pin.close();
                            } catch (Throwable cleanup) {
                                rejected.addSuppressed(cleanup);
                            }
                            completeFailure(result, rejected);
                        }
                    });
                    result.whenComplete((outcome, rejected) -> {
                        if (result.isCancelled() && queued.withdraw())
                            pin.close();
                    });
                } catch (Throwable rejected) {
                    try {
                        pin.close();
                    } catch (Throwable cleanup) {
                        rejected.addSuppressed(cleanup);
                    }
                    completeFailure(result, rejected);
                }
            });
            result.whenComplete((outcome, failure) -> {
                if (result.isCancelled())
                    interest.cancel(false);
                if (cancelled.getAsBoolean() && outcome instanceof LoadResult.Ready ready)
                    ready.candidate().close();
            });
            return result;
        } catch (AssetLoadException | CancellationException failure) {
            return CompletableFuture.completedFuture(failure(failure));
        } catch (IOException failure) {
            return CompletableFuture
                .completedFuture(failure(AssetLoadException.content("Failed to load model render target", failure)));
        } catch (IllegalArgumentException failure) {
            return CompletableFuture
                .completedFuture(failure(AssetLoadException.content("Failed to build model render target", failure)));
        }
    }

    private static void completeFailure(CompletableFuture<LoadResult> result, Throwable cause) {
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null)
            cause = cause.getCause();
        if (cause instanceof Error) {
            result.completeExceptionally(cause);
            return;
        }
        if (cause instanceof RejectedExecutionException)
            cause = AssetLoadException.access("Model render target worker admission failed", cause);
        else if (!(cause instanceof AssetLoadException) && !(cause instanceof CancellationException))
            cause = AssetLoadException.content("Failed to prepare model render target", cause);
        result.complete(failure(cause));
    }

    public static LoadResult.Failed failure(Throwable cause) {
        final ResourceFailure.Kind kind;
        if (cause instanceof CancellationException) {
            kind = ResourceFailure.Kind.TRANSIENT;
        } else if (cause instanceof AssetLoadException asset) {
            kind = asset.reason() == AssetLoadException.Reason.CONTENT ? ResourceFailure.Kind.DETERMINISTIC
                : ResourceFailure.Kind.TRANSIENT;
        } else {
            throw new IllegalArgumentException("Unclassified asset failure", cause);
        }
        return new LoadResult.Failed(new ResourceFailure(kind, cause));
    }

    private static StringDataProto readCommonStrings(BooleanSupplier cancelled, ModelFileView view,
        ChunkDataSource chunks) throws IOException {
        var common = view.getManifest().commonAssets();
        if (common.stringsBlobId() > 0) {
            return view.getCommon().readStringData(cancelled, chunks);
        }
        return null;
    }

    private static void requireActive(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Model render target load was cancelled");
        }
    }

    static <T extends AutoCloseable> T publishCandidate(BooleanSupplier cancelled, ResourceTransaction resources,
        T candidate) {
        resources.commit();
        try {
            requireActive(cancelled);
            return candidate;
        } catch (RuntimeException | Error error) {
            try {
                candidate.close();
            } catch (Exception closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    @Override
    public void close() {
        buildStage.prepared.close();
    }

    public sealed interface LoadResult {
        record Ready(ModelCandidate candidate) implements LoadResult {
            public Ready {
                java.util.Objects.requireNonNull(candidate, "candidate");
            }
        }

        record Failed(ResourceFailure failure) implements LoadResult {
            public Failed {
                java.util.Objects.requireNonNull(failure, "failure");
            }
        }
    }

    record LoadRequest(ModelRepresentation representation, ModelFileView view, RenderTargetView target,
        PBRTextureSetProto textureProto, PBRImageSources textureSources, String targetId, String selectedTexture,
        ExpressionBinding expressionBinding, ModelResourcePurpose purpose, boolean defaultModel, boolean cacheOnly,
        ModelResourceFailures resourceFailures, RenderTargetKey.StateKey stateKey, ModelContent content) {
    }

    private record PreparationKey(Hash256 containerId, RenderTargetKey.StateKey stateKey, Object region) {
    }

    record PreparedExpressions(RenderTargetExpressionModule module, ModelDataProto definition) {
    }

    static PreparedExpressions prepareExpressions(RenderTargetExpressionModuleFactory factory,
        BooleanSupplier cancelled, Hash256 containerId, String targetId, ExpressionSet set, ExpressionBinding binding,
        ModelDataProto complete, StringDataProto functions, SettingsProto settings, boolean cacheOnly,
        ModelResourcePurpose purpose, RenderTargetKind kind) throws IOException {
        var config = ProtoUtil.serializeToArray(settings);
        requireActive(cancelled);
        var input = GuiAnimationClosure.select(complete, functions, settings, purpose, kind);
        var module = factory.createSet(cancelled, containerId, targetId, set, binding, input, functions, settings,
            config, cacheOnly, purpose);
        return new PreparedExpressions(module, input);
    }

    @FunctionalInterface
    private interface ConsumerStage {
        ModelCandidate build(BooleanSupplier cancelled, LoadRequest request,
            PreparedTargetCache<PreparationKey, PreparedTarget>.Pin pin) throws IOException;
    }

    private record PreparedSet(RenderTargetExpressionModule module, AnimationStore animations,
        Map<String, AnimationControllerFile> controllers, StringDataProto functions, Set<String> localEvents,
        ExpressionSet set, DefaultAnimationRuntime.Source defaultSource,
        AnimationStore fallback) implements AutoCloseable {
        @Override
        public void close() {
            try {
                animations.close();
            } finally {
                try {
                    if (fallback != null)
                        fallback.close();
                } finally {
                    module.close();
                }
            }
        }
    }

    private record PreparedTarget(ModelDataProto definition, PreparedSet main, PreparedSet arm,
        Map<String, DefaultAnimationRuntime.Source> defaultSources,
        TargetSoundResources sounds) implements AutoCloseable {
        @Override
        public void close() {
            try {
                if (arm != null)
                    arm.close();
            } finally {
                try {
                    main.close();
                } finally {
                    sounds.close();
                }
            }
        }
    }

    private static final class DefaultBuildStage {
        private final BakedModelCache bakedModels;
        private boolean regionBound;
        private final java.util.concurrent.ConcurrentHashMap<CacheRegion, BakedModelCache> regionalModels =
            new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<CacheRegion,
            RenderTargetExpressionModuleFactory> regionalExpressions = new java.util.concurrent.ConcurrentHashMap<>();

        private Object region(ModelContent content) {
            return regionBound
                ? java.util.Objects.requireNonNull(content.storageBinding(), "Storage provenance").region()
                : bakedModels;
        }

        private RenderTargetExpressionModuleFactory modules(ModelContent content) {
            if (!regionBound)
                return expressionModules;
            var region = content.storageBinding().region();
            return regionalExpressions.computeIfAbsent(region,
                value -> expressionModules.withArtifacts(value.expressions()));
        }

        private BakedModelCache models(ModelContent content) {
            if (!regionBound)
                return bakedModels;
            var region = content.storageBinding().region();
            return regionalModels.computeIfAbsent(region,
                value -> new BakedModelCache(value.root().resolve("bake"), value.cache()));
        }

        private final RenderTargetExpressionModuleFactory expressionModules;
        private final DefaultAnimationRuntime defaultAnimations;
        private final PreparedTargetCache<PreparationKey, PreparedTarget> prepared;
        private ExpressionBinding playerBinding;
        private ExpressionBinding defaultPlayerBinding;

        private DefaultBuildStage(BakedModelCache bakedModels, RenderTargetExpressionModuleFactory expressionModules,
            ClientResourceScheduler scheduler, DefaultAnimationRuntime defaultAnimations) {
            this.bakedModels = bakedModels;
            this.expressionModules = expressionModules;
            this.defaultAnimations = defaultAnimations;
            prepared = new PreparedTargetCache<>(scheduler);
        }

        private synchronized ExpressionBinding playerBinding(boolean defaultModel) {
            if (defaultModel) {
                if (defaultPlayerBinding == null)
                    defaultPlayerBinding = expressionModules.expressionBinding(true, AbstractClientPlayer.class);
                return defaultPlayerBinding;
            }
            if (playerBinding == null)
                playerBinding = expressionModules.expressionBinding(AbstractClientPlayer.class);
            return playerBinding;
        }

        private CompletableFuture<PreparedTargetCache<PreparationKey, PreparedTarget>.Pin> prepare(LoadRequest request,
            ModelContent content, ClientResourceScheduler.Dispatch dispatch) {
            var key = new PreparationKey(request.representation().containerId(), request.stateKey(), region(content));
            return prepared.acquire(key, request.cacheOnly(), dispatch, () -> {
                var retained =
                    content instanceof RemoteModelContent remote ? remote.retainPreparationInputs() : content;
                return new PreparedTargetCache.Producer<PreparedTarget>() {
                    @Override
                    public PreparedTarget produce(BooleanSupplier cancelled) throws Exception {
                        try (var resources = new ResourceTransaction()) {
                            final ModelDataProto definition;
                            final StringDataProto strings;
                            final TargetSoundResources sounds;
                            try (var phase = retained.chunks().openReadPhase()) {
                                definition = request.target().readDefinition(cancelled, phase);
                                strings = readCommonStrings(cancelled, request.view(), phase);
                                sounds = resources.own(TargetSoundResources.prepare(cancelled,
                                    request.representation().identity(), request.view().getCommon().sounds(), phase));
                            }
                            var result = prepareNow(cancelled, request, definition, strings, sounds);
                            resources.commit();
                            return result;
                        }
                    }

                    @Override
                    public void close() {
                        if (retained instanceof RemoteModelContent remote)
                            remote.closePreparationInputs();
                    }
                };
            });
        }

        private ModelCandidate build(BooleanSupplier cancelled, LoadRequest request,
            PreparedTargetCache<PreparationKey, PreparedTarget>.Pin pin) throws IOException {
            return buildNow(models(request.content()), cancelled, request.representation(), request.view(),
                request.target(), request.textureProto(), request.textureSources(), request.targetId(),
                request.selectedTexture(), pin.value(), pin, request.defaultModel(), request.cacheOnly(),
                request.resourceFailures(), new ModelRenderTarget.StateIdentity(request.representation().identity(),
                    request.stateKey(), pin.value()));
        }

        private PreparedTarget prepareNow(BooleanSupplier cancelled, LoadRequest request, ModelDataProto definition,
            StringDataProto strings, TargetSoundResources sounds) throws IOException {
            try (var resources = new ResourceTransaction()) {
                var main = resources.own(prepareSet(cancelled, request, definition, strings, ExpressionSet.MAIN));
                var arm = request.target().kind() == com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PLAYER
                    && request.purpose() == ModelResourcePurpose.FULL
                        ? resources.own(prepareSet(cancelled, request, definition, strings, ExpressionSet.FP_ARM))
                        : null;
                var union = new java.util.HashSet<>(main.module().roamingFields());
                if (arm != null)
                    union.addAll(arm.module().roamingFields());
                if (union.size() > com.elfmcys.ysm.network.protocol.ProtocolLimits.MAX_ROAMING_VARIABLES)
                    throw AssetLoadException.content("Combined roaming layout exceeds wire capacity");
                var sources = new LinkedHashMap<String, DefaultAnimationRuntime.Source>();
                if (request.defaultModel()) {
                    sources.put(DefaultAnimationKey.domain(request.target().descriptor(), "main"),
                        main.defaultSource());
                    if (arm != null)
                        sources.put(DefaultAnimationKey.domain(request.target().descriptor(), "fp_arm"),
                            arm.defaultSource());
                }
                return publishCandidate(cancelled, resources,
                    new PreparedTarget(definition, main, arm, Map.copyOf(sources), sounds));
            }
        }

        private PreparedSet prepareSet(BooleanSupplier cancelled, LoadRequest request, ModelDataProto definition,
            StringDataProto strings, ExpressionSet set) throws IOException {
            try (var resources = new ResourceTransaction()) {
                var local = set.select(definition);
                var domain = DefaultAnimationKey.domain(request.target().descriptor(), set.id());
                var defaults = request.defaultModel() ? null : defaultAnimations.source(domain);
                if (!request.defaultModel() && defaults == null)
                    throw AssetLoadException.access("Default animation source is unavailable: " + domain);
                var closure = local.toBuilder();
                var fallbackDefinition = ModelDataProto.newBuilder();
                if (defaults != null)
                    defaults.definition().animationFiles().forEach((file, value) -> {
                        closure.putAnimationFiles(defaults.prefix() + file, value);
                        fallbackDefinition.putAnimationFiles(defaults.prefix() + file, value);
                    });
                var functions = mergeFunctions(strings, defaults);
                var settings = request.view().getMetadata().getSettings();
                var selected = prepareExpressions(modules(request.content()).forDefaultModel(request.defaultModel()),
                    cancelled, request.representation().containerId(), request.targetId(), set,
                    request.expressionBinding(), closure.build(), functions, settings, request.cacheOnly(),
                    request.purpose(), request.target().kind());
                var module = resources.own(selected.module());
                var input = selected.definition();
                if (request.purpose() == ModelResourcePurpose.GUI
                    && request.target().kind() == com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PLAYER) {
                    var selectedLocal = local.toBuilder().clearAnimationFiles();
                    var selectedFallback = fallbackDefinition.clearAnimationFiles();
                    for (var entry : input.animationFiles().entrySet()) {
                        if (local.animationFiles().containsKey(entry.getKey()))
                            selectedLocal.putAnimationFiles(entry.getKey(), entry.getValue());
                        else
                            selectedFallback.putAnimationFiles(entry.getKey(), entry.getValue());
                    }
                    local = selectedLocal.build();
                }
                var binder = programBinder(module);
                AnimationStore fallback = null;
                if (defaults != null) {
                    fallback = resources
                        .own(module.animations(fallbackDefinition.build().animationFiles().keySet(), null).shared());
                }
                var files = local.animationFiles().object2ObjectEntrySet();
                var animations =
                    resources
                        .own(request.defaultModel()
                            ? BakedAnimationCache.bindResident(files, binder,
                                (animation, bound) -> defaultAnimations.requireCurrent(request.target().descriptor(),
                                    set.id(), animation))
                            : module.animations(local.animationFiles().keySet(), fallback));
                animations.shared();
                var controllers = controllerFiles(local, binder);
                var localEvents = new java.util.HashSet<String>();
                if (strings != null)
                    strings.userFunctions().forEach(function -> localEvents.add(function.name()));
                var source =
                    request.defaultModel() ? new DefaultAnimationRuntime.Source(request.representation().containerId(),
                        request.targetId(), set, local, strings) : null;
                var result = new PreparedSet(module, animations, controllers, functions, Set.copyOf(localEvents), set,
                    source, fallback);
                resources.commit();
                return result;
            }
        }

        private static StringDataProto mergeFunctions(StringDataProto local, DefaultAnimationRuntime.Source defaults) {
            if (defaults == null || defaults.strings() == null)
                return local;
            var result = local == null ? StringDataProto.newBuilder() : local.toBuilder();
            var names = new java.util.HashSet<String>();
            for (var function : defaults.strings().userFunctions()) {
                var separator = function.name().indexOf('@');
                if (separator == 0)
                    continue;
                var name = separator < 0 ? function.name() : function.name().substring(0, separator);
                if (names.add(name))
                    result.addUserFunctions(function.toBuilder().setName(defaults.prefix() + name).build());
            }
            return result.build();
        }

        private ModelCandidate buildNow(BakedModelCache bakedModels, BooleanSupplier cancelled,
            ModelRepresentation representation, ModelFileView view, RenderTargetView target,
            PBRTextureSetProto textureProto, PBRImageSources textureSources, String targetId, String selectedTexture,
            PreparedTarget prepared, AutoCloseable preparedOwner, boolean defaultModel, boolean cacheOnly,
            ModelResourceFailures resourceFailures, ModelRenderTarget.StateIdentity stateIdentity) throws IOException {
            try (var resources = new ResourceTransaction()) {
                resources.own(preparedOwner);
                var definition = prepared.definition();
                var soundResources = resources.own(prepared.sounds().acquire());
                var textureResource = targetId + "/" + selectedTexture + "/";
                var textures = resources.own(new PreparedTextureSet(textureSources, textureResource, resourceFailures));
                var textureHash = textureHash(view, textureProto.uv());
                var expressions = prepared.main().module();
                var controllers = prepared.main().controllers();
                final RenderTargetData targetData;
                switch (target.kind()) {
                    case PLAYER -> {
                        var main = resources.own(bakeModel(bakedModels, representation,
                            targetId + "/" + selectedTexture + "/main", textureHash, geoModel(definition, "main"),
                            textures, target, textureProto, defaultModel, cacheOnly));
                        var arm = prepared.arm() == null ? null
                            : resources.own(bakeModel(bakedModels, representation,
                                targetId + "/" + selectedTexture + "/arm", textureHash, geoModel(definition, "arm"),
                                textures, target, textureProto, defaultModel, cacheOnly));
                        var animations = resources.own(AnimationStore.consumerView(prepared.main().animations(),
                            name -> resourceFailures.animation(targetId + "/main/" + name)));
                        var firstPersonAnimations = resources.own(prepared.arm() == null ? new AnimationStore()
                            : AnimationStore.consumerView(prepared.arm().animations(),
                                name -> resourceFailures.animation(targetId + "/fp_arm/" + name)));
                        var variants = new FifoHashMap<>(new String[] {selectedTexture},
                            new PlayerModelVariant[] {new PlayerModelVariant(main, arm)});
                        targetData = new PlayerModelData(variants, animations, firstPersonAnimations,
                            List.copyOf(controllers.values()),
                            prepared.arm() == null ? List.of() : List.copyOf(prepared.arm().controllers().values()));
                    }
                    case PROJECTILE, VEHICLE -> {
                        var baked = resources
                            .own(bakeModel(bakedModels, representation, targetId + "/" + selectedTexture, textureHash,
                                geoModel(definition, "main"), textures, target, textureProto, defaultModel, cacheOnly));
                        var animations = resources.own(AnimationStore.consumerView(prepared.main().animations(),
                            name -> resourceFailures.animation(targetId + "/main/" + name)));
                        var controller = controllers.values().stream().findFirst().orElse(null);
                        targetData =
                            target.kind() == com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PROJECTILE
                                ? new ProjectileModelData(baked, animations, controller)
                                : new VehicleModelData(baked, animations, controller);
                    }
                    default -> throw AssetLoadException.content("Unsupported render target kind: " + target.kind());
                }

                var common = commonAssets(view, prepared.main(), soundResources);
                var armCommon = prepared.arm() == null ? null : commonAssets(view, prepared.arm(), soundResources);
                var data = new ModelRenderTargetBuildInput(targetId, targetData, common, expressions,
                    view.getMetadata(), armCommon, prepared.arm() == null ? null : prepared.arm().module(),
                    preparedOwner, prepared.defaultSources(), stateIdentity);
                textures.prepare(cancelled);
                final ModelRenderTarget targetResult;
                try {
                    targetResult = resources.own(ModelRenderTargetAssembler.build(representation.modelId(), data));
                } catch (java.io.UncheckedIOException error) {
                    throw AssetLoadException.access("Failed to close animation read phase: " + targetId,
                        error.getCause());
                } catch (IllegalArgumentException error) {
                    throw AssetLoadException.content("Invalid assembled model render target: " + targetId, error);
                }
                return publishCandidate(cancelled, resources, new ModelCandidate(targetResult, textures));
            }
        }

        private static CommonAssetData commonAssets(ModelFileView view, PreparedSet prepared,
            TargetSoundResources soundResources) throws IOException {
            var source = prepared.functions();
            var expressions = prepared.module();
            var sounds = new LinkedHashMap<String, SoundSource>();
            for (var entry : view.getCommon().sounds().entrySet()) {
                sounds.put(entry.getKey(), soundResources.bind(entry.getValue()));
            }
            var functions = new Object2ReferenceOpenHashMap<String, UserFunctionProgram>();
            var eventHandlers = new LinkedHashMap<String, List<EventProgram>>();
            var eventOrders = new java.util.HashMap<String, Integer>();
            if (source != null) {
                for (var function : source.userFunctions()) {
                    var name = function.name();
                    var separator = name.indexOf('@');
                    if (separator != 0) {
                        var callable = separator < 0 ? name : name.substring(0, separator);
                        functions.put(callable,
                            userFunction(expressions, "function/" + name, ProgramRole.USER_FUNCTION, "callable"));
                    }
                    if (separator >= 0 && separator + 1 < name.length()) {
                        var event = name.substring(separator + 1).toLowerCase(java.util.Locale.ROOT);
                        var order = eventOrders.merge(event, 1, Integer::sum) - 1;
                        var classification = MolangEventCatalog.production().classify(event, order);
                        if (classification instanceof MolangEventCatalog.Classification.Declared declared) {
                            var role = declared.route().role();
                            if (prepared.set() == ExpressionSet.FP_ARM && role != ProgramRole.EVENT_DEFER
                                && role != ProgramRole.EVENT_CODED_CONTROLLER)
                                continue;
                            if (!prepared.localEvents().contains(name) && role != ProgramRole.EVENT_DEFER
                                && role != ProgramRole.EVENT_CODED_CONTROLLER)
                                continue;
                            eventHandlers.computeIfAbsent(event, ignored -> new ArrayList<>()).add(eventProgram(
                                expressions, "function/" + name, declared.route().role(), event + ':' + order));
                        }
                    }
                }
            }
            var immutableEvents = new LinkedHashMap<String, List<EventProgram>>();
            eventHandlers.forEach((name, handlers) -> immutableEvents.put(name, List.copyOf(handlers)));
            return new CommonAssetData(Map.copyOf(sounds), soundResources, functions, Map.copyOf(immutableEvents));
        }

        private static Map<String, AnimationControllerFile> controllerFiles(ModelDataProto source,
            AnimationProtoMapper.ProgramBinder programBinder) throws IOException {
            var result = new LinkedHashMap<String, AnimationControllerFile>();
            if (!source.animationControllers().isEmpty()) {
                for (var entry : source.animationControllers().object2ObjectEntrySet()) {
                    try {
                        result.put(entry.getKey(), AnimationProtoMapper.controllerFile(entry.getValue(),
                            "controller/" + entry.getKey(), programBinder));
                    } catch (RuntimeException error) {
                        throw AssetLoadException.content("Invalid animation controller: " + entry.getKey(), error);
                    }
                }
            }
            return result;
        }

        private GeoModel bakeModel(BakedModelCache bakedModels, ModelRepresentation representation, String resourceName,
            byte[] textureHash, GeoModelProto geo, BakedModelCache.TexturePixelsSupplier texturePixels,
            RenderTargetView target, PBRTextureSetProto textureProto, boolean defaultModel, boolean cacheOnly)
            throws IOException {
            if (defaultModel) {
                return BakedModelCache.bakeResident(geo, resourceName, texturePixels, CURRENT_RAW_UV_VERSION,
                    target.descriptor().settings().forceCulling(), false, hasPbr(textureProto), PlayerLocator.get());
            }
            if (cacheOnly) {
                return bakedModels.loadExisting(representation.containerId(), resourceName, textureHash,
                    PlayerLocator.get());
            }
            return bakedModels.loadOrBake(representation.containerId(), resourceName, textureHash, geo, texturePixels,
                CURRENT_RAW_UV_VERSION, target.descriptor().settings().forceCulling(), false, hasPbr(textureProto),
                PlayerLocator.get());
        }

        private static AnimationProtoMapper.ProgramBinder programBinder(RenderTargetExpressionModule expressions) {
            return new AnimationProtoMapper.ProgramBinder() {
                @Override
                public FloatExpression.Program bindFloat(String location, ProgramRole role, String discriminator) {
                    return floatProgram(expressions, location, role, discriminator);
                }

                @Override
                public BooleanProgram bindBoolean(String location, ProgramRole role, String discriminator) {
                    return booleanProgram(expressions, location, role, discriminator);
                }

                @Override
                public VoidProgram bindVoid(String location, ProgramRole role, String discriminator) {
                    return voidProgram(expressions, location, role, discriminator);
                }
            };
        }

        private static ModelProgramInventory.SemanticLocation semantic(RenderTargetExpressionModule expressions,
            String location, ProgramRole role, String discriminator) {
            return new ModelProgramInventory.SemanticLocation(expressions.identity().targetId(),
                expressions.identity().set(), location, role, discriminator);
        }

        private static FloatExpression.Program floatProgram(RenderTargetExpressionModule expressions, String location,
            ProgramRole role, String discriminator) {
            return expressions.requireFloatProgram(semantic(expressions, location, role, discriminator));
        }

        private static BooleanProgram booleanProgram(RenderTargetExpressionModule expressions, String location,
            ProgramRole role, String discriminator) {
            return expressions.requireBooleanProgram(semantic(expressions, location, role, discriminator));
        }

        private static VoidProgram voidProgram(RenderTargetExpressionModule expressions, String location,
            ProgramRole role, String discriminator) {
            return expressions.requireVoidProgram(semantic(expressions, location, role, discriminator));
        }

        private static UserFunctionProgram userFunction(RenderTargetExpressionModule expressions, String location,
            ProgramRole role, String discriminator) {
            return expressions.requireUserFunctionProgram(semantic(expressions, location, role, discriminator));
        }

        private static EventProgram eventProgram(RenderTargetExpressionModule expressions, String location,
            ProgramRole role, String discriminator) {
            return expressions.requireEventProgram(semantic(expressions, location, role, discriminator));
        }
    }

    static GeoModelProto geoModel(ModelDataProto source, String name) throws IOException {
        for (var entry : source.geoModels().object2ObjectEntrySet()) {
            if (entry.getKey().equals(name)) {
                try {
                    return GeoModelProto.parseFrom(ProtoSource.newInstance(entry.getValue()));
                } catch (IOException error) {
                    throw AssetLoadException.content("Invalid geo model named " + name, error);
                }
            }
        }
        throw AssetLoadException.content("Model data contains no geo model named " + name);
    }

    private static byte[] textureHash(ModelFileView view, ImageProto image) throws IOException {
        var chunk =
            view.getFileView().getAssetView().getChunkInfo(AssetFileConstant.BLOB_CHUNK_PREFIX + image.blobId());
        if (chunk == null || chunk.hash() == null || chunk.hash().length != Hash256.SIZE) {
            throw AssetLoadException.content("Texture chunk contains no content hash");
        }
        return chunk.hash();
    }

    private static boolean hasPbr(PBRTextureSetProto texture) {
        return texture.hasNormal() || texture.hasSpecular();
    }

    public static final class ModelCandidate implements AutoCloseable {
        private final ModelRenderTarget target;
        private final PreparedTextureSet textures;
        private final AtomicBoolean closed = new AtomicBoolean();
        private boolean published;

        ModelCandidate(ModelRenderTarget target, PreparedTextureSet textures) {
            this.target = java.util.Objects.requireNonNull(target, "target");
            this.textures = textures;
        }

        static ModelCandidate testing(ModelRenderTarget target) {
            return new ModelCandidate(target, null);
        }

        public ModelRenderTarget publish() throws Exception {
            return publish(HostTexturePublisher.production());
        }

        ModelRenderTarget publish(HostTexturePublisher publisher) throws Exception {
            if (closed.get() || published) {
                throw new IllegalStateException("Model candidate is no longer publishable");
            }
            if (textures == null) {
                published = true;
                return target;
            }
            var binding = publisher.publish(textures);
            try {
                target.adoptTexture(binding.base(), binding);
            } catch (Throwable error) {
                binding.close();
                throw error;
            }
            published = true;
            return target;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (textures != null) {
                textures.close();
            }
            target.close();
        }

        void reject(java.util.function.Consumer<ModelRenderTarget> rejectedTargetCloser) {
            if (textures == null) {
                if (closed.compareAndSet(false, true)) {
                    rejectedTargetCloser.accept(target);
                }
            } else {
                close();
            }
        }
    }
}
