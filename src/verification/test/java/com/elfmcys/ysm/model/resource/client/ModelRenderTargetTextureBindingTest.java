package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.format.schema.model.views.ModelInfoView;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.thread.BlockableEventLoop;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ModelRenderTargetTextureBindingTest {
    @BeforeAll
    static void establishRenderOwner() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.initRenderThread();
        }
    }

    @Test
    void targetExposesOnlyAnAdoptedBindingAndReleasesItOnce() throws Exception {
        var target = target();
        var releases = new AtomicInteger();
        @SuppressWarnings("removal")
        var id = new ResourceLocation("ysm", "test/target");

        assertThrows(IllegalStateException.class, target::textureId);
        target.adoptTexture(id, releases::incrementAndGet);
        assertEquals(id, target.textureId());

        target.close();
        target.close();
        assertEquals(1, releases.get());
        assertClosedReferences(target);
    }

    @Test
    void cleanupFailureStillReleasesSoundTextureAndStrongReferences() throws Exception {
        var root = com.elfmcys.ysm.expression.aot.TestExpressionModules
            .store(com.elfmcys.ysm.expression.aot.TestExpressionModules.create(), java.util.Map.of());
        var first = new IllegalStateException("prepared close");
        var soundFailure = new IllegalStateException("sound close");
        var textureFailure = new IllegalStateException("texture close");
        var releases = new AtomicInteger();
        var id = ModelId.fromHash(new Hash256(new byte[Hash256.SIZE]));
        var assets = new CommonAsset(java.util.Map.of(), TargetSoundResources
            .empty(new com.elfmcys.ysm.model.domain.ModelFileIdentity(id, new Hash256(new byte[Hash256.SIZE])))) {
            @Override
            public void close() {
                releases.incrementAndGet();
                throw soundFailure;
            }
        };
        var target = new ModelRenderTarget(id, "test", new RenderTargetResources() {}, assets, root,
            (ModelInfoView)UnsafeUtil.getUnsafe().allocateInstance(ModelInfoView.class), null, null, () -> {
                root.close();
                throw first;
            }, java.util.Map.of());
        target.adoptTexture(new ResourceLocation("ysm", "test/failure"), () -> {
            releases.incrementAndGet();
            throw textureFailure;
        });
        assertSame(first, assertThrows(IllegalStateException.class, target::close));
        assertArrayEquals(new Throwable[] {soundFailure, textureFailure}, first.getSuppressed());
        assertEquals(2, releases.get());
        assertClosedReferences(target);
        target.close();
        assertEquals(2, releases.get());
    }

    @Test
    void backgroundCloseDropsAnimationReferencesBeforeHostDestruction() throws Exception {
        var geometryReleases = new AtomicInteger();
        var textureReleases = new AtomicInteger();
        var target = projectileTarget(geometryReleases);
        var store = target.animationStore();
        var queue = new ConcurrentLinkedQueue<Runnable>();
        target.adoptTexture(new ResourceLocation("ysm", "test/deferred"), textureReleases::incrementAndGet);
        try (var host = installHostQueue(queue); var preparation = store.prepare()) {
            CompletableFuture.runAsync(target::close).get(10, TimeUnit.SECONDS);
            assertClosedReferences(target);
            assertFalse(store.isClosed());
            assertEquals(1, queue.size());
            assertEquals(0, geometryReleases.get());
            assertEquals(0, textureReleases.get());
            queue.remove().run();
            assertEquals(1, geometryReleases.get());
            assertEquals(1, textureReleases.get());
            target.close();
            assertTrue(queue.isEmpty());
        } finally {
            target.close();
            for (Runnable cleanup; (cleanup = queue.poll()) != null;)
                cleanup.run();
        }
        assertTrue(store.isClosed());
    }

    @Test
    void rejectedHostCleanupDropsAnimationReferencesAndPreservesTheFailure() throws Exception {
        var failure = new RejectedExecutionException("host cleanup rejected");
        var queue = new ConcurrentLinkedQueue<Runnable>() {
            @Override
            public boolean add(Runnable task) {
                throw failure;
            }

            @Override
            public boolean offer(Runnable task) {
                throw failure;
            }
        };
        var target = projectileTarget(new AtomicInteger());
        var store = target.animationStore();
        try (var host = installHostQueue(queue)) {
            var rejected =
                assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(target::close).join());
            assertSame(failure, rejected.getCause());
            assertClosedReferences(target);
            assertTrue(store.isClosed());
            target.close();
        }
    }

    private static AutoCloseable installHostQueue(Queue<Runnable> queue) throws Exception {
        var instance = Minecraft.class.getDeclaredField("instance");
        instance.setAccessible(true);
        var previous = instance.get(null);
        var client = (Minecraft)UnsafeUtil.getUnsafe().allocateInstance(Minecraft.class);
        var thread = Minecraft.class.getDeclaredField("gameThread");
        thread.setAccessible(true);
        thread.set(client, Thread.currentThread());
        var pending = BlockableEventLoop.class.getDeclaredField("pendingRunnables");
        pending.setAccessible(true);
        pending.set(client, queue);
        instance.set(null, client);
        return () -> instance.set(null, previous);
    }

    private static ModelRenderTarget projectileTarget(AtomicInteger geometryReleases) throws Exception {
        var geometry = (UnrenderedGeometry)UnsafeUtil.getUnsafe().allocateInstance(UnrenderedGeometry.class);
        geometry.releases = geometryReleases;
        var store = com.elfmcys.ysm.expression.aot.TestExpressionModules
            .store(com.elfmcys.ysm.expression.aot.TestExpressionModules.create(), java.util.Map.of());
        return new ModelRenderTarget(ModelId.fromHash(new Hash256(new byte[Hash256.SIZE])), "test",
            new ProjectileModelResources(geometry, store.view()),
            (CommonAsset)UnsafeUtil.getUnsafe().allocateInstance(CommonAsset.class), store,
            (ModelInfoView)UnsafeUtil.getUnsafe().allocateInstance(ModelInfoView.class));
    }

    private static final class UnrenderedGeometry extends GeoModel {
        private AtomicInteger releases;

        private UnrenderedGeometry() {
            super(null, null, null, null);
        }

        @Override
        public void close() {
            releases.incrementAndGet();
        }
    }

    private static void assertClosedReferences(ModelRenderTarget target) throws Exception {
        assertThrows(IllegalStateException.class, target::animationStore);
        assertThrows(IllegalStateException.class, target::animations);
        assertThrows(IllegalStateException.class, target::assets);
        assertThrows(IllegalStateException.class, target::info);
        assertThrows(IllegalStateException.class, target::textureId);
        assertEquals("test", target.renderTargetId());
        var resources = ModelRenderTarget.class.getDeclaredField("resources");
        resources.setAccessible(true);
        assertNull(resources.get(target));
        var cleanup = ModelRenderTarget.class.getDeclaredField("cleanupState");
        cleanup.setAccessible(true);
        var state = cleanup.get(target);
        for (var name : java.util.List.of("preparedOwner", "targetResources", "assets", "armAssets")) {
            var field = state.getClass().getDeclaredField(name);
            field.setAccessible(true);
            assertNull(field.get(state), name);
        }
        var binding = state.getClass().getDeclaredField("textureBinding");
        binding.setAccessible(true);
        assertNull(((AtomicReference<?>)binding.get(state)).get());
    }

    private static ModelRenderTarget target() throws Exception {
        var bytes = new byte[Hash256.SIZE];
        return new ModelRenderTarget(ModelId.fromHash(new Hash256(bytes)), "test", new RenderTargetResources() {},
            (CommonAsset)UnsafeUtil.getUnsafe().allocateInstance(CommonAsset.class),
            com.elfmcys.ysm.expression.aot.TestExpressionModules
                .store(com.elfmcys.ysm.expression.aot.TestExpressionModules.create(), java.util.Map.of()),
            (ModelInfoView)UnsafeUtil.getUnsafe().allocateInstance(ModelInfoView.class));
    }
}
