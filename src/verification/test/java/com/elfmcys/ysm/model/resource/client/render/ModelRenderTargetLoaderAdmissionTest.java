package com.elfmcys.ysm.model.resource.client.render;

import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;

import com.elfmcys.ysm.model.storage.ModelStorageInfra;
import com.elfmcys.ysm.model.storage.ContentStorageBinding;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.expression.aot.ExpressionArtifactStore;
import com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModuleFactory;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.resource.client.ModelResourceFailures;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.ResourceFailure;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.player.AbstractClientPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ModelRenderTargetLoaderAdmissionTest {
    private final ClientResourceScheduler scheduler = new ClientResourceScheduler(2);

    @AfterEach
    void stopWorkers() throws InterruptedException {
        scheduler.shutdown();
        scheduler.awaitTermination();
    }

    @Test
    void rejectedPreparationCompletesWithTransientFailure(@TempDir Path root) throws Exception {
        var content = content();
        try (var representation = content.representation(); var loader = loader(root)) {
            scheduler.shutdown();
            var failed = assertInstanceOf(ModelRenderTargetLoader.LoadResult.Failed.class,
                loader.loadAsync(() -> false, scheduler.dispatch(WorkPriority.LOCAL_PLAYER), content, key(loader), true,
                    ModelResourceFailures.none()).get(10, TimeUnit.SECONDS));
            assertRejected(failed);
        }
    }

    @Test
    void rejectedConsumerAfterPreparationSettlesWithoutHostProgress(@TempDir Path root) throws Exception {
        var content = content();
        var chunks = new ObservedChunks(content.chunks(), true);
        try (var representation = content.representation(); var loader = loader(root)) {
            var result = loader.loadAsync(() -> false, scheduler.dispatch(WorkPriority.BACKGROUND),
                observed(content, chunks), key(loader), true, ModelResourceFailures.none());
            try {
                assertTrue(chunks.entered.await(10, TimeUnit.SECONDS));
                scheduler.shutdown();
            } finally {
                chunks.release.countDown();
            }
            assertRejected(
                assertInstanceOf(ModelRenderTargetLoader.LoadResult.Failed.class, result.get(10, TimeUnit.SECONDS)));
        }
    }

    @Test
    void claimedBackgroundProducerLeavesThePromotedContinuationOnTheLocalWorker(@TempDir Path root) throws Exception {
        var content = content();
        var chunks = new ObservedChunks(content.chunks(), true);
        var localEntered = new CountDownLatch(1);
        var localRelease = new CountDownLatch(1);
        var localWorker = new AtomicReference<Thread>();
        scheduler.dispatch(WorkPriority.LOCAL_PLAYER).execute(() -> {
            localWorker.set(Thread.currentThread());
            localEntered.countDown();
            await(localRelease);
        });
        try (var representation = content.representation(); var loader = loader(root)) {
            assertTrue(localEntered.await(10, TimeUnit.SECONDS));
            var dispatch = scheduler.dispatch(WorkPriority.BACKGROUND);
            var cancelled = new AtomicBoolean();
            var completedOn = new AtomicReference<Thread>();
            var result = loader.loadAsync(cancelled::get, dispatch, observed(content, chunks), key(loader), true,
                ModelResourceFailures.none());
            var observedResult = result.whenComplete((outcome, failure) -> completedOn.set(Thread.currentThread()));
            try {
                assertTrue(chunks.entered.await(10, TimeUnit.SECONDS));
                dispatch.promoteLocalPlayer();
                chunks.release.countDown();
                CompletableFuture.runAsync(() -> {
                }, scheduler.backgroundExecutor()).get(10, TimeUnit.SECONDS);
                assertFalse(result.isDone());
                cancelled.set(true);
                localRelease.countDown();
                var failed = assertInstanceOf(ModelRenderTargetLoader.LoadResult.Failed.class,
                    observedResult.get(10, TimeUnit.SECONDS));
                assertEquals(ResourceFailure.Kind.TRANSIENT, failed.failure().kind());
                assertInstanceOf(java.util.concurrent.CancellationException.class, failed.failure().cause());
                assertNotSame(localWorker.get(), chunks.producerThread.get());
                assertSame(localWorker.get(), completedOn.get());
            } finally {
                chunks.release.countDown();
                localRelease.countDown();
            }
        } finally {
            localRelease.countDown();
        }
    }

    @Test
    void contentFailureFromThePreparationWorkerRemainsDeterministic(@TempDir Path root) throws Exception {
        var content = content();
        var chunks = new ObservedChunks(content.chunks(), false);
        var failure = AssetLoadException.content("Invalid model definition payload");
        chunks.consumerFailure = failure;
        try (var representation = content.representation(); var loader = loader(root)) {
            var failed =
                assertInstanceOf(ModelRenderTargetLoader.LoadResult.Failed.class,
                    loader.loadAsync(() -> false, scheduler.dispatch(WorkPriority.BACKGROUND),
                        observed(content, chunks), key(loader), true, ModelResourceFailures.none())
                        .get(10, TimeUnit.SECONDS));
            assertEquals(1, chunks.reads.get());
            assertEquals(ResourceFailure.Kind.DETERMINISTIC, failed.failure().kind());
            assertSame(failure, failed.failure().cause());
        }
    }

    @Test
    void sourceSwitchKeepsTheOldPreparationInItsCapturedRegion(@TempDir Path root) throws Exception {
        if (!com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread())
            com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        var paths = com.elfmcys.ysm.AssetPaths.discover(root.resolve("game"), root.resolve("config"), root,
            java.util.Map.of("YSM_HOME", root.resolve("global").toString()),
            com.elfmcys.ysm.AssetDirectoryPlatform.Kind.WINDOWS, null);
        assertTrue(paths.globalEnabled(), paths.diagnostics().toString());
        try (var storage = ModelStorageInfra.open(paths, "fixture")) {
            var original = content();
            var oldContent = original.withStorageBinding(ContentStorageBinding.builtin(storage.local()));
            var newContent = original.withStorageBinding(ContentStorageBinding.remote(storage.remote()));
            var defaults = new DefaultAnimationRuntime();
            var empty = com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto.newBuilder().build();
            var main = com.elfmcys.ysm.expression.inventory.ExpressionSet.MAIN;
            var arm = com.elfmcys.ysm.expression.inventory.ExpressionSet.FP_ARM;
            defaults.publishSources(java.util.Map.of("player/main",
                new DefaultAnimationRuntime.Source(original.representation().containerId(), "player", main, empty,
                    null),
                "player/first-person", new DefaultAnimationRuntime.Source(original.representation().containerId(),
                    "player", arm, empty, null)));
            var modules = new RenderTargetExpressionModuleFactory(storage.local().expressions(),
                ExpressionHost.create(HostAvailability.none()), new ExpressionEngineMetrics());
            var chunks = new ObservedChunks(original.chunks(), true);
            try (var representation = original.representation();
                var loader = new ModelRenderTargetLoader(storage, modules, scheduler, defaults)) {
                RenderTargetStateIdentityTest.installMetadataConsumer(loader);
                var oldWork = loader.loadAsync(() -> false, scheduler.dispatch(WorkPriority.BACKGROUND),
                    observed(oldContent, chunks), key(loader), false, ModelResourceFailures.none());
                try {
                    assertTrue(chunks.entered.await(10, TimeUnit.SECONDS));
                    var newResult = loader.loadAsync(() -> false, scheduler.dispatch(WorkPriority.LOCAL_PLAYER),
                        newContent, key(loader), false, ModelResourceFailures.none()).get(30, TimeUnit.SECONDS);
                    if (newResult instanceof ModelRenderTargetLoader.LoadResult.Failed failure)
                        fail("New regional preparation failed", failure.failure().cause());
                    try (var ready =
                        assertInstanceOf(ModelRenderTargetLoader.LoadResult.Ready.class, newResult).candidate()) {
                        storage.remote().expressions().awaitPersistence();
                        assertTrue(artifacts(storage.remote().root(), ".anim.ysm-cache") > 0);
                        assertEquals(0, artifacts(storage.local().root(), ".anim.ysm-cache"));
                        assertEquals(0, artifacts(storage.local().root(), ".geo.ysm-cache"));
                        assertFalse(oldWork.isDone());
                    }
                    chunks.release.countDown();
                    try (var ready = assertInstanceOf(ModelRenderTargetLoader.LoadResult.Ready.class,
                        oldWork.get(30, TimeUnit.SECONDS)).candidate()) {
                        storage.local().expressions().awaitPersistence();
                        assertTrue(artifacts(storage.local().root(), ".anim.ysm-cache") > 0);
                        System.out.println(
                            "source switch animation artifacts: global and local verified; old preparation retained local region");
                    }
                } finally {
                    chunks.release.countDown();
                }
            }
        }
    }

    private static long artifacts(Path root, String suffix) throws IOException {
        if (!java.nio.file.Files.exists(root))
            return 0;
        try (var files = java.nio.file.Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(suffix)).count();
        }
    }

    private static void assertRejected(ModelRenderTargetLoader.LoadResult.Failed failed) {
        assertEquals(ResourceFailure.Kind.TRANSIENT, failed.failure().kind());
        var cause = assertInstanceOf(AssetLoadException.class, failed.failure().cause());
        assertEquals(AssetLoadException.Reason.ACCESS, cause.reason());
        assertInstanceOf(RejectedExecutionException.class, cause.getCause());
    }

    private static ManagedContainer content() throws Exception {
        var root = Path.of(Objects
            .requireNonNull(ModelRenderTargetLoaderAdmissionTest.class.getResource("/assets/ysm/builtin/default"))
            .toURI());
        try (var directory = new Directory(root); var memory = ModelParser.parseBuiltinDefaultMemory(directory);
            var bytes = memory.bytes()) {
            return ManagedContainer.openResidentDefault(bytes,
                new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")));
        }
    }

    private ModelRenderTargetLoader loader(Path root) {
        var cache = track(new AtomicSharedCache(root));
        var modules = new RenderTargetExpressionModuleFactory(track(new ExpressionArtifactStore(root, cache)),
            ExpressionHost.create(HostAvailability.none()), new ExpressionEngineMetrics());
        return new ModelRenderTargetLoader(new BakedModelCache(root, cache), modules, scheduler,
            new DefaultAnimationRuntime());
    }

    private static RenderTargetKey key(ModelRenderTargetLoader loader) {
        return new RenderTargetKey("player", "blue", loader.bakeProfile(),
            loader.expressionBinding(AbstractClientPlayer.class), ModelResourcePurpose.FULL);
    }

    private static ModelContent observed(ModelContent content, ChunkDataSource chunks) {
        return new ModelContent() {
            @Override
            public ContentStorageBinding storageBinding() {
                return content.storageBinding();
            }

            @Override
            public ModelRepresentation representation() {
                return content.representation();
            }

            @Override
            public ChunkDataSource chunks() {
                return chunks;
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static final class ObservedChunks implements ChunkDataSource {
        private final ChunkDataSource delegate;
        private final boolean gated;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicReference<Thread> producerThread = new AtomicReference<>();
        private volatile AssetLoadException consumerFailure;

        private ObservedChunks(ChunkDataSource delegate, boolean gated) {
            this.delegate = delegate;
            this.gated = gated;
        }

        private void beforeRead() throws IOException {
            if (reads.incrementAndGet() == 1) {
                producerThread.set(Thread.currentThread());
                entered.countDown();
                if (gated)
                    await(release);
            }
            var failure = consumerFailure;
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public UniBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType type) throws IOException {
            beforeRead();
            return delegate.readPayload(chunk, type);
        }

        @Override
        public UniBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType type) throws IOException {
            beforeRead();
            return delegate.readStoredVerified(chunk, type);
        }
    }
}
