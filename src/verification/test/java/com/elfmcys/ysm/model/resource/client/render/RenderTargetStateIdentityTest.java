package com.elfmcys.ysm.model.resource.client.render;

import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;

import com.elfmcys.ysm.expression.aot.ExpressionArtifactStore;
import com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModuleFactory;
import com.elfmcys.ysm.expression.host.ExpressionBinding;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.geckolib3.file.AnimationControllerFile;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.model.resource.client.PlayerModelVariant;
import com.elfmcys.ysm.model.resource.client.TargetSoundResources;
import com.elfmcys.ysm.model.resource.client.data.CommonAssetData;
import com.elfmcys.ysm.model.resource.client.data.ModelRenderTargetBuildInput;
import com.elfmcys.ysm.model.resource.client.data.PlayerModelData;
import com.elfmcys.ysm.model.resource.client.ModelResourceFailures;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.elfmcys.ysm.util.FifoHashMap;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.player.AbstractClientPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RenderTargetStateIdentityTest {
    @BeforeAll
    static void establishRenderOwner() {
        if (!RenderSystem.isOnRenderThread())
            RenderSystem.initRenderThread();
    }

    @Test
    void compatibilityRequiresExactContentPreparationAndEveryNonTextureKey() {
        var binding = ExpressionBinding.standalone();
        var profile = new BakeProfile("one");
        var a = new RenderTargetKey("player", "a", profile, binding);
        var b = new RenderTargetKey("player", "b", profile, binding);
        var content = new ModelFileIdentity(hash(1), hash(2));
        var preparation = new Object();
        var state = new ModelRenderTarget.StateIdentity(content, a.stateKey(), preparation);
        assertTrue(state.isCompatible(new ModelRenderTarget.StateIdentity(content, b.stateKey(), preparation)));
        assertFalse(state.isCompatible(new ModelRenderTarget.StateIdentity(content, a.stateKey(), new Object())));
        assertFalse(state.isCompatible(
            new ModelRenderTarget.StateIdentity(new ModelFileIdentity(hash(1), hash(3)), a.stateKey(), preparation)));
        var otherBinding = ExpressionHost.create(HostAvailability.all()).expressionBinding(AbstractClientPlayer.class);
        for (var key : List.of(new RenderTargetKey("vehicle", "a", profile, binding),
            new RenderTargetKey("player", "a", new BakeProfile("two"), binding),
            new RenderTargetKey("player", "a", profile, otherBinding),
            new RenderTargetKey("player", "a", profile, binding, ModelResourcePurpose.GUI))) {
            assertFalse(state.isCompatible(new ModelRenderTarget.StateIdentity(content, key.stateKey(), preparation)));
        }
    }

    @Test
    void coldTextureBuildAndReadyCacheHitSharePreparationUntilEveryTargetReleases(@TempDir Path root) throws Exception {
        var source = Path.of(getClass().getResource("/assets/ysm/builtin/default/ysm.json").toURI()).getParent();
        Path container;
        try (var directory = new Directory(source)) {
            container = ModelParser.parseBuiltinDefault(directory, Files.createDirectories(root.resolve("model")));
        }
        var content = ManagedContainer.openResidentDefault(Files.readAllBytes(container),
            new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")));
        var cacheRoot = root.resolve("cache");
        var sharedCache = track(new AtomicSharedCache(cacheRoot));
        var factory =
            new RenderTargetExpressionModuleFactory(track(new ExpressionArtifactStore(cacheRoot, sharedCache)),
                ExpressionHost.create(HostAvailability.all()), new ExpressionEngineMetrics());
        var scheduler = new ClientResourceScheduler(2);
        var loader = new ModelRenderTargetLoader(new BakedModelCache(cacheRoot, sharedCache), factory, scheduler,
            new DefaultAnimationRuntime());
        installMetadataConsumer(loader);
        var cache = new ModelRenderTargetCache(scheduler, ModelRenderTarget::close, failure -> {
            throw new AssertionError(failure);
        }, prepared -> {
            throw new AssertionError("This resource test replaces image decoding and GPU publication");
        }, () -> 4, () -> 0);
        ModelRenderTarget first = null;
        ModelRenderTarget second = null;
        try {
            var binding = loader.expressionBinding(true, AbstractClientPlayer.class);
            var a = new RenderTargetKey("player", "default", loader.bakeProfile(), binding);
            var b = new RenderTargetKey("player", "blue", loader.bakeProfile(), binding);
            try (var leaseA = acquire(cache, loader, content, a); var leaseB = acquire(cache, loader, content, b)) {
                first = ((AcquireResult.Ready)leaseA.poll()).target();
                second = ((AcquireResult.Ready)leaseB.poll()).target();
                assertNotSame(first, second);
                assertTrue(first.isStateCompatible(second));
                assertSame(first.expressions(), second.expressions());
                assertSame(first.armExpressions(), second.armExpressions());
                try (var hot = cache.findReady(request(content, b), content, b, (id, key, req) -> true).orElseThrow()) {
                    assertSame(second, ((AcquireResult.Ready)hot.poll()).target());
                }
            }
            cache.close();
            first.close();
            loader.close();
            var animations = second.playerResources().animations();
            assertFalse(animations.isEmpty());
            assertNotNull(animations.get(animations.keySet().iterator().next()));
            assertFalse(second.isStateCompatible(first));
        } finally {
            cache.close();
            loader.close();
            if (first != null)
                first.close();
            if (second != null)
                second.close();
            content.representation().close();
            scheduler.shutdown();
            scheduler.awaitTermination();
        }
    }

    /** Preparation stays real; the headless JVM has no NativeImage mixin or GPU context. */
    static void installMetadataConsumer(ModelRenderTargetLoader loader) throws Exception {
        var consumerType = Class.forName(ModelRenderTargetLoader.class.getName() + "$ConsumerStage");
        var consumer = Proxy.newProxyInstance(consumerType.getClassLoader(), new Class<?>[] {consumerType},
            (proxy, method, arguments) -> {
                if (method.getName().equals("build"))
                    return metadataCandidate((ModelRenderTargetLoader.LoadRequest)arguments[1], arguments[2]);
                return switch (method.getName()) {
                    case "toString" -> "MetadataResourceConsumer";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new AssertionError(method);
                };
            });
        var field = ModelRenderTargetLoader.class.getDeclaredField("consumerStage");
        field.setAccessible(true);
        field.set(loader, consumer);
    }

    @SuppressWarnings("unchecked")
    private static ModelRenderTargetLoader.ModelCandidate metadataCandidate(ModelRenderTargetLoader.LoadRequest request,
        Object pin) throws Exception {
        var prepared = property(pin, "value");
        var main = property(prepared, "main");
        var arm = property(prepared, "arm");
        var variants = new FifoHashMap<>(new String[] {request.selectedTexture()}, new PlayerModelVariant[] {
            new PlayerModelVariant(UnrenderedGeometry.create(), arm == null ? null : UnrenderedGeometry.create())});
        var failures = ModelResourceFailures.none();
        var animations = AnimationStore.consumerView((AnimationStore)property(main, "animations"), failures::animation);
        var armAnimations = arm == null ? new AnimationStore()
            : AnimationStore.consumerView((AnimationStore)property(arm, "animations"), failures::animation);
        var mainControllers = (Map<String, AnimationControllerFile>)property(main, "controllers");
        var armControllers = arm == null ? Map.<String, AnimationControllerFile>of()
            : (Map<String, AnimationControllerFile>)property(arm, "controllers");
        var targetData = new PlayerModelData(variants, animations, armAnimations, List.copyOf(mainControllers.values()),
            List.copyOf(armControllers.values()));
        var sounds = ((TargetSoundResources)property(prepared, "sounds")).acquire();
        var bound = new java.util.LinkedHashMap<String, com.elfmcys.ysm.model.resource.client.SoundSource>();
        request.view().getCommon().sounds().forEach((name, media) -> bound.put(name, sounds.bind(media)));
        var assets = new CommonAssetData(bound, sounds, Map.of(), Map.of());
        var stateIdentity =
            new ModelRenderTarget.StateIdentity(request.representation().identity(), request.stateKey(), prepared);
        var input = new ModelRenderTargetBuildInput(request.targetId(), targetData, assets,
            (RenderTargetExpressionModule)property(main, "module"), request.view().getMetadata(),
            arm == null ? null : assets, arm == null ? null : (RenderTargetExpressionModule)property(arm, "module"),
            (AutoCloseable)pin, (Map<String, DefaultAnimationRuntime.Source>)property(prepared, "defaultSources"),
            stateIdentity);
        return ModelRenderTargetLoader.ModelCandidate
            .testing(ModelRenderTargetAssembler.build(request.representation().modelId(), input));
    }

    private static Object property(Object value, String name) throws Exception {
        var method = value.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(value);
    }

    private static final class UnrenderedGeometry extends GeoModel {
        private UnrenderedGeometry() {
            super(null, null, null, null);
        }

        private static UnrenderedGeometry create() throws InstantiationException {
            return (UnrenderedGeometry)UnsafeUtil.getUnsafe().allocateInstance(UnrenderedGeometry.class);
        }

        @Override
        public void close() {}
    }

    private static ResourceLease acquire(ModelRenderTargetCache cache, ModelRenderTargetLoader loader,
        ManagedContainer content, RenderTargetKey key) throws Exception {
        var lease = cache.getOrStart(request(content, key), content, key,
            (cancelled, dispatch) -> loader.loadAsync(cancelled, dispatch, content, key, true,
                ModelResourceFailures.none()),
            (id, target, request) -> true, RenderTargetStateIdentityTest.class, WorkPriority.BACKGROUND);
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (lease.poll() instanceof AcquireResult.Pending && System.nanoTime() < deadline) {
            cache.tick();
            Thread.sleep(1);
        }
        var outcome = lease.poll();
        if (!(outcome instanceof AcquireResult.Ready)) {
            lease.close();
            if (outcome instanceof AcquireResult.Failed failed)
                throw new AssertionError("target preparation failed", failed.failure().cause());
            fail("target was not ready: " + outcome);
        }
        return lease;
    }

    private static ResourceRequest request(ManagedContainer content, RenderTargetKey key) {
        return new ResourceRequest(content.modelId(), key.targetId(), key.selectedTexture(), key.bakeProfile(),
            key.expressionBinding(), key.purpose());
    }

    private static Hash256 hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)marker;
        return new Hash256(bytes);
    }
}
