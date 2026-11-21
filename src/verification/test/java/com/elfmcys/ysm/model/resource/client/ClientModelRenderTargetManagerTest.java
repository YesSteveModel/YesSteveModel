package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.model.domain.Hash256;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.expression.host.ExpressionBinding;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.model.resource.client.render.ModelRenderTargetCache;
import com.elfmcys.ysm.model.resource.client.render.ModelRenderTargetLoader;
import com.elfmcys.ysm.model.resource.client.render.RenderTargetKey;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import org.junit.jupiter.api.AfterEach;
import com.elfmcys.ysm.proto.mixel.common.ImageProto;
import com.elfmcys.ysm.proto.mixel.manifest.ManifestProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.CommonProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.PBRTextureSetProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import com.elfmcys.ysm.proto.mixel.manifest.info.InfoProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.LicenseProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.MetadataProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelSettingsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelStatsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.PropertiesProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.util.UnsafeUtil;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientModelRenderTargetManagerTest {
    private static final BakeProfile PROFILE = new BakeProfile("test");
    private final java.util.List<ClientResourceScheduler> schedulers = new java.util.ArrayList<>();

    @AfterEach
    void closeSchedulers() throws InterruptedException {
        for (var scheduler : schedulers) {
            scheduler.shutdown();
            scheduler.awaitTermination();
        }
    }

    private ClientResourceScheduler scheduler() {
        var scheduler = new ClientResourceScheduler(2);
        schedulers.add(scheduler);
        return scheduler;
    }

    @Test
    void fullAndGuiRequestsKeepTheirExactPurposeAndBinding() throws Exception {
        var manager = contextManager(null);
        var unsafe = UnsafeUtil.getUnsafe();
        var full = ExpressionBinding.standalone(dev.semaweave.compilation.BindingPlan.identity()
            .withRootSubjectType(net.minecraft.client.player.AbstractClientPlayer.class));
        var gui = ExpressionBinding.standalone(dev.semaweave.compilation.BindingPlan.identity()
            .withRootSubjectType(com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity.FakePlayer.class));
        var bindings = new java.util.IdentityHashMap<Class<?>, ExpressionBinding>();
        bindings.put(net.minecraft.client.player.AbstractClientPlayer.class, full);
        bindings.put(com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity.FakePlayer.class, gui);
        unsafe.putObject(manager,
            unsafe.objectFieldOffset(ClientModelRenderTargetManager.class.getDeclaredField("subjectBindings")),
            bindings);
        unsafe.putObject(manager,
            unsafe.objectFieldOffset(ClientModelRenderTargetManager.class.getDeclaredField("bakeProfile")), PROFILE);
        var prepared = manager.request(hash(), "player", "main", ModelResourcePurpose.FULL, null);
        var card = manager.request(hash(), "player", "main", ModelResourcePurpose.GUI, null);
        assertSame(full, prepared.expressionBinding());
        assertSame(gui, card.expressionBinding());
        assertEquals(ModelResourcePurpose.FULL, prepared.purpose());
        assertEquals(ModelResourcePurpose.GUI, card.purpose());
        assertNotEquals(ClientModelRenderTargetManager.resolveEffectiveKey(manifest(null, "player", "main"), prepared),
            ClientModelRenderTargetManager.resolveEffectiveKey(manifest(null, "player", "main"), card));

    }

    @Test
    void intrinsicRequestsShareTheResidentFullPurposeAndBuiltinBindingForEveryTarget() throws Exception {
        var manager = contextManager(null);
        var unsafe = UnsafeUtil.getUnsafe();
        var builtin = ExpressionBinding.standalone(dev.semaweave.compilation.BindingPlan.identity()
            .withRootSubjectType(net.minecraft.world.entity.LivingEntity.class));
        var ordinary = ExpressionBinding.standalone(dev.semaweave.compilation.BindingPlan.identity()
            .withRootSubjectType(net.minecraft.client.player.AbstractClientPlayer.class));
        var preview = ExpressionBinding.standalone(dev.semaweave.compilation.BindingPlan.identity()
            .withRootSubjectType(com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity.FakePlayer.class));
        unsafe.putObject(manager,
            unsafe.objectFieldOffset(ClientModelRenderTargetManager.class.getDeclaredField("defaultSubjectBinding")),
            builtin);
        var ordinaryBindings = new java.util.IdentityHashMap<Class<?>, ExpressionBinding>();
        ordinaryBindings.put(net.minecraft.client.player.AbstractClientPlayer.class, ordinary);
        ordinaryBindings.put(com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity.FakePlayer.class, preview);
        unsafe.putObject(manager,
            unsafe.objectFieldOffset(ClientModelRenderTargetManager.class.getDeclaredField("subjectBindings")),
            ordinaryBindings);
        unsafe.putObject(manager,
            unsafe.objectFieldOffset(ClientModelRenderTargetManager.class.getDeclaredField("bakeProfile")), PROFILE);
        for (var target : List.of("player", "projectile", "vehicle")) {
            for (var purpose : ModelResourcePurpose.values()) {
                var request = manager.request(com.elfmcys.ysm.model.domain.PublishedModelId.INTRINSIC_DEFAULT, target,
                    "main", purpose, com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity.FakePlayer.class);
                assertSame(builtin, request.expressionBinding());
                assertEquals(ModelResourcePurpose.FULL, request.purpose());
            }
        }
        assertSame(ordinary, manager.request(hash(), "player", "main").expressionBinding());
        var ordinaryGui = manager.request(hash(), "player", "main", ModelResourcePurpose.GUI, null);
        assertSame(preview, ordinaryGui.expressionBinding());
        assertEquals(ModelResourcePurpose.GUI, ordinaryGui.purpose());
        var other = ModelId.fromHash(new Hash256(new byte[Hash256.SIZE]));
        assertSame(ordinary, manager.request(other, "player", "main").expressionBinding());
        var mismatched = new ResourceRequest(com.elfmcys.ysm.model.domain.PublishedModelId.INTRINSIC_DEFAULT, "player",
            "main", PROFILE, ordinary, ModelResourcePurpose.FULL);
        var failure = assertThrows(IllegalArgumentException.class,
            () -> invoke(manager, "resolve", new Class<?>[] {ResourceRequest.class}, mismatched));
        assertEquals("Resource expressionBinding is not current", failure.getMessage());
    }

    @Test
    void requestedTextureWinsWhenPresent() {
        var request = request("requested");

        var key = ClientModelRenderTargetManager
            .resolveEffectiveKey(manifest("fallback", "player", "first", "requested", "fallback"), request);

        assertEquals("requested", key.selectedTexture());
        assertEquals("requested", request.requestedTexture());
    }

    @Test
    void missingRequestedTextureFallsThroughToValidDefaultWithoutMutatingRequest() {
        var request = request("server-name");

        var key = ClientModelRenderTargetManager
            .resolveEffectiveKey(manifest("fallback", "player", "first", "fallback"), request);

        assertEquals("fallback", key.selectedTexture());
        assertEquals("server-name", request.requestedTexture());
    }

    @Test
    void invalidDefaultFallsThroughToFirstManifestTexture() {
        var key = ClientModelRenderTargetManager.resolveEffectiveKey(manifest("missing", "player", "first", "second"),
            request("server-name"));

        assertEquals("first", key.selectedTexture());
    }

    @Test
    void unknownAndTexturelessTargetsRemainDeterministicFailures() {
        var unknown = assertThrows(IllegalArgumentException.class, () -> ClientModelRenderTargetManager
            .resolveEffectiveKey(manifest(null, "projectile", "only"), request("server-name")));
        assertEquals("Unknown render target: player", unknown.getMessage());

        var textureless = assertThrows(IllegalArgumentException.class,
            () -> ClientModelRenderTargetManager.resolveEffectiveKey(manifest(null, "projectile"),
                new ResourceRequest(hash(), "projectile", "server-name", PROFILE)));
        assertEquals("Render target has no texture: projectile", textureless.getMessage());
    }

    @Test
    void identicalResolutionConvergesAndChangedRepresentationInvalidatesOldKey() {
        var request = request("server-name");
        var firstRepresentation = manifest(null, "player", "first");
        var nextRepresentation = manifest(null, "player", "next");

        var first = ClientModelRenderTargetManager.resolveEffectiveKey(firstRepresentation, request);

        assertEquals(first, ClientModelRenderTargetManager.resolveEffectiveKey(firstRepresentation, request));
        assertNotEquals(first, ClientModelRenderTargetManager.resolveEffectiveKey(nextRepresentation, request));
        assertEquals("server-name", request.requestedTexture());
    }

    @Test
    void sessionRetirementCannotResumeAnOldGuiRequestUnderTheNextOwner() throws Exception {
        ClientConfig.init().setConfig(CommentedConfig.inMemory());
        for (var fallback : List.of("getOrStartCachedExact", "getOrStartOfflineExact", "getOrStart")) {
            try (var cache = new ModelRenderTargetCache(scheduler())) {
                var manager = contextManager(cache);
                var originalOwner = manager.beginSession();
                var context = invoke(manager, "currentWorkContext", new Class<?>[0]);
                var full = request("");
                var gui = new ResourceRequest(full.modelId(), full.targetId(), full.requestedTexture(), PROFILE,
                    full.expressionBinding(), ModelResourcePurpose.GUI);
                var pending = new CompletableFuture<ModelRenderTargetLoader.LoadResult>();
                var probe = cache.getOrStartCached(gui, content(),
                    new RenderTargetKey("player", "", PROFILE, gui.expressionBinding(), ModelResourcePurpose.GUI),
                    (ignored, dispatch) -> pending, (identity, key, request) -> true, originalOwner,
                    WorkPriority.LOCAL_PLAYER);
                manager.disconnect(originalOwner);
                manager.beginSession();
                cache.tick();

                var failure = assertThrows(CancellationException.class,
                    () -> invoke(manager, fallback,
                        new Class<?>[] {ResourceRequest.class, context.getClass(), WorkPriority.class}, gui, context,
                        WorkPriority.LOCAL_PLAYER),
                    fallback);
                assertEquals("Render-target work owner is no longer current", failure.getMessage());
                assertTrue(pending.isCancelled());
                assertEquals(0, cache.loadingCount());
                probe.close();
            }
        }
    }

    @Test
    void cancelledProbeDisposesItsLateCandidateWithoutStartingFallback() throws Exception {
        var prepared = uncancellableLoad();
        var starts = new AtomicInteger();
        var closes = new AtomicInteger();
        var result = continueLoad(prepared, outcome -> {
            starts.incrementAndGet();
            return CompletableFuture.completedFuture(outcome);
        });
        result.cancel(false);
        prepared.complete(candidate(closes));
        assertEquals(0, starts.get());
        assertEquals(1, closes.get());
    }

    @Test
    void cancelledContinuationDisposesLateCandidateWithoutConsumerProgress() throws Exception {
        var loading = uncancellableLoad();
        var closes = new AtomicInteger();
        var result = continueLoad(
            CompletableFuture.completedFuture(new ModelRenderTargetLoader.LoadResult.Failed(
                new ResourceFailure(ResourceFailure.Kind.TRANSIENT, new java.io.IOException("cache absent")))),
            ignored -> loading);
        result.cancel(false);
        loading.complete(candidate(closes));
        assertEquals(1, closes.get());
    }

    @Test
    void rejectedContinuationKeepsTheSourceResponsibleForItsCandidate() throws Exception {
        var closes = new AtomicInteger();
        var rejected = new IllegalStateException("continuation rejected");
        var result = continueLoad(CompletableFuture.completedFuture(candidate(closes)), ignored -> {
            throw rejected;
        });
        assertSame(rejected, assertThrows(CompletionException.class, result::join).getCause());
        assertEquals(1, closes.get());
    }

    private static CompletableFuture<ModelRenderTargetLoader.LoadResult> uncancellableLoad() {
        return new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean interrupt) {
                return false;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<ModelRenderTargetLoader.LoadResult> continueLoad(
        CompletableFuture<ModelRenderTargetLoader.LoadResult> source, java.util.function.Function<
            ModelRenderTargetLoader.LoadResult, CompletableFuture<ModelRenderTargetLoader.LoadResult>> continuation)
        throws Exception {
        return (CompletableFuture<ModelRenderTargetLoader.LoadResult>)invoke(contextManager(null), "continueLoad",
            new Class<?>[] {CompletableFuture.class, java.util.function.Function.class,
                java.util.function.Consumer.class},
            source, continuation, (java.util.function.Consumer<ModelRenderTargetLoader.LoadResult>)outcome -> {
                if (outcome instanceof ModelRenderTargetLoader.LoadResult.Ready ready)
                    ready.candidate().close();
            });
    }

    private static ModelRenderTargetLoader.LoadResult candidate(AtomicInteger closes) throws Exception {
        var unsafe = UnsafeUtil.getUnsafe();
        var target = (ModelRenderTarget)unsafe.allocateInstance(ModelRenderTarget.class);
        unsafe.putObject(target, unsafe.objectFieldOffset(ModelRenderTarget.class.getDeclaredField("cleanable")),
            (java.lang.ref.Cleaner.Cleanable)closes::incrementAndGet);
        var factory =
            ModelRenderTargetLoader.ModelCandidate.class.getDeclaredMethod("testing", ModelRenderTarget.class);
        factory.setAccessible(true);
        return new ModelRenderTargetLoader.LoadResult.Ready(
            (ModelRenderTargetLoader.ModelCandidate)factory.invoke(null, target));
    }

    private static ClientModelRenderTargetManager contextManager(ModelRenderTargetCache cache) throws Exception {
        // Exercise session retirement without initializing model sources or a graphics host.
        var unsafe = UnsafeUtil.getUnsafe();
        var manager = (ClientModelRenderTargetManager)unsafe.allocateInstance(ClientModelRenderTargetManager.class);
        unsafe.putObject(manager,
            unsafe.objectFieldOffset(ClientModelRenderTargetManager.class.getDeclaredField("renderTargets")), cache);
        unsafe.putObject(manager,
            unsafe.objectFieldOffset(ClientModelRenderTargetManager.class.getDeclaredField("processOwner")),
            new Object());
        return manager;
    }

    private static Object invoke(ClientModelRenderTargetManager manager, String name, Class<?>[] parameters,
        Object... arguments) {
        try {
            var method = ClientModelRenderTargetManager.class.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method.invoke(manager, arguments);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause)
                throw cause;
            if (failure.getCause() instanceof Error cause)
                throw cause;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static ModelContent content() throws Exception {
        var unsafe = UnsafeUtil.getUnsafe();
        var representation = (ModelRepresentation)unsafe.allocateInstance(ModelRepresentation.class);
        unsafe.putObject(representation,
            unsafe.objectFieldOffset(ModelRepresentation.class.getDeclaredField("identity")),
            new ModelFileIdentity(hash(), Hash256.parse(hash().toString().substring(5))));
        return new ModelContent() {
            @Override
            public ModelRepresentation representation() {
                return representation;
            }

            @Override
            public ChunkDataSource chunks() {
                throw new AssertionError("A pending cache probe must not read chunks");
            }
        };
    }

    private static ResourceRequest request(String requestedTexture) {
        return new ResourceRequest(hash(), "player", requestedTexture, PROFILE);
    }

    private static ManifestProto manifest(String defaultTexture, String targetId, String... textures) {
        var target = RenderTargetProto.newBuilder().setTargetId(targetId)
            .setKind(targetId.equals("player") ? RenderTargetKind.PLAYER : RenderTargetKind.PROJECTILE).setBlobId(0)
            .setSettings(ModelSettingsProto.newBuilder().setHeightScale(0).setWidthScale(0).setRenderLayersFirst(false)
                .setForceCulling(false).setGuiNoLighting(false).setMergeMultilineExpr(false).build())
            .setStats(ModelStatsProto.newBuilder().setBones(0).setCubes(0).setFaces(0).build());
        for (var texture : textures) {
            target.putTextures(texture, PBRTextureSetProto.newBuilder().setUv(emptyImage()).build());
        }

        var settings =
            SettingsProto.newBuilder().setDefaultTexture("").setPreviewAnimation("").setDisablePreviewRotation(false);
        if (defaultTexture != null) {
            settings.setDefaultTexture(defaultTexture);
        }
        var info = InfoProto.newBuilder().setSettings(settings.build())
            .setMetadata(MetadataProto.newBuilder().setName("").setTips("")
                .setLicense(LicenseProto.newBuilder().setType("").setDesc("").build()).build())
            .setProperties(PropertiesProto.newBuilder()
                .setModelId("hash:" + java.util.HexFormat.of().formatHex(new byte[Hash256.SIZE])).setFree(false)
                .setOriginVer("").build())
            .build();
        return ManifestProto.newBuilder().addRenderTargets(target.build())
            .setCommonAssets(CommonProto.newBuilder().setStringsBlobId(0).build()).setInfo(info).build();
    }

    private static ImageProto emptyImage() {
        return ImageProto.newBuilder().setBlobId(0).setFormat("").setWidth(0).setHeight(0).setFrameCount(0).build();
    }

    private static ModelId hash() {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = 1;
        return ModelId.fromHash(new Hash256(bytes));
    }

}
