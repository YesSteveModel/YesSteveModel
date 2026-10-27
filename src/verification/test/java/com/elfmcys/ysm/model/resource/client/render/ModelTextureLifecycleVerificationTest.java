package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.expression.aot.TestExpressionModules;
import com.elfmcys.ysm.format.schema.file.PBRImageSources;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.format.schema.model.views.ModelInfoView;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.CommonAsset;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ModelResourceFailures;
import com.elfmcys.ysm.model.resource.client.RenderTargetResources;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.model.resource.client.TargetSoundResources;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.natives.image.ImageSource;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTextureLifecycleVerificationTest {
    private final ClientResourceScheduler scheduler = new ClientResourceScheduler(2);

    @AfterEach
    void stopScheduler() throws InterruptedException {
        scheduler.shutdown();
        scheduler.awaitTermination();
    }

    private static final BakeProfile PROFILE = new BakeProfile("test");
    private static final Object OWNER = new Object();

    @BeforeAll
    static void establishRenderOwner() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.initRenderThread();
        }
    }

    @Test
    void previousSampleRemainsAuthoritativeUntilDecodeRegistrationAndUploadComplete() throws Exception {
        var events = Collections.synchronizedList(new ArrayList<String>());
        var publisher = new TracingPublisher(id(2), HostFault.NONE, true, events);
        var previousTexture = publisher.install(id(1), "previous");
        var previous = target();
        previous.adoptTexture(id(1),
            new HostTexturePublisher.PublishedTextureBinding(id(1), publisher.manager, List.of(id(1))));
        var fixture = new PreparedFixture(true, events);
        var next = target();
        var candidate = new ModelRenderTargetLoader.ModelCandidate(next, fixture.prepared);
        var worker = Executors.newSingleThreadExecutor();
        var tickWorker = Executors.newSingleThreadExecutor();
        try (var cache = cache(publisher, 4)) {
            var loading = CompletableFuture.supplyAsync(() -> {
                try {
                    fixture.prepared.prepare(() -> false);
                    return (ModelRenderTargetLoader.LoadResult)new ModelRenderTargetLoader.LoadResult.Ready(candidate);
                } catch (IOException failure) {
                    throw new AssertionError(failure);
                }
            }, worker);
            var lease = cache.getOrStart(request(2), content(2), key(), (ignored, dispatch) -> loading, current(), OWNER, WorkPriority.BACKGROUND);

            await(fixture.decodeEntered);
            assertSample(lease, previous, id(1), previousTexture, publisher.manager);

            fixture.decodePermit.countDown();
            loading.get(5, TimeUnit.SECONDS);
            assertSample(lease, previous, id(1), previousTexture, publisher.manager);

            var tick = tickWorker.submit(() -> {
                while (publisher.registrationEntered.getCount() != 0) {
                    cache.tick();
                    Thread.yield();
                }
            });
            await(publisher.registrationEntered);
            assertSample(lease, previous, id(1), previousTexture, publisher.manager);

            publisher.registrationPermit.countDown();
            await(publisher.uploadEntered);
            assertSample(lease, previous, id(1), previousTexture, publisher.manager);

            publisher.uploadPermit.countDown();
            tick.get(5, TimeUnit.SECONDS);
            assertSample(lease, next, id(2), publisher.publishedTexture, publisher.manager);
            assertEquals(
                List.of("decode:uv", "decode:normal", "decode:specular", "registration", "upload", "mapping-installed"),
                events.subList(1, 7));
            fixture.assertAllClosed();
            lease.close();
        } finally {
            worker.shutdownNow();
            tickWorker.shutdownNow();
            next.close();
            previous.close();
        }
    }

    @ParameterizedTest
    @EnumSource(value = HostFault.class, names = {"REGISTRATION", "UPLOAD"})
    void distinctRegistrationAndUploadFaultsRejectWithoutChangingThePreviousSample(HostFault fault) throws Exception {
        var events = Collections.synchronizedList(new ArrayList<String>());
        var publisher = new TracingPublisher(id(11), fault, false, events);
        var previousTexture = publisher.install(id(10), "previous");
        var previous = target();
        previous.adoptTexture(id(10),
            new HostTexturePublisher.PublishedTextureBinding(id(10), publisher.manager, List.of(id(10))));
        var fixture = new PreparedFixture(false, events);
        fixture.prepared.prepare(() -> false);
        var candidate = new ModelRenderTargetLoader.ModelCandidate(target(), fixture.prepared);

        try (var cache = cache(publisher, 4)) {
            var lease = cache.getOrStart(request(11), content(11), key(),
                (ignored, dispatch) -> CompletableFuture.completedFuture(new ModelRenderTargetLoader.LoadResult.Ready(candidate)),
                current(), OWNER, WorkPriority.BACKGROUND);

            cache.tick();

            assertInstanceOf(AcquireResult.Failed.class, lease.poll());
            assertEquals(0, cache.readyCount());
            assertSample(lease, previous, id(10), previousTexture, publisher.manager);
            assertTrue(events.contains("registration"));
            assertEquals(fault == HostFault.UPLOAD, events.contains("upload"));
            assertFalse(events.contains("mapping-installed"));
            fixture.assertAllClosed();
            lease.close();
        } finally {
            previous.close();
        }
    }

    @Test
    void staleAndCancelledPreparedCandidatesHaveOneImageDispositionAndNoHostAttempt() throws Exception {
        var hostAttempts = new AtomicInteger();
        HostTexturePublisher unusedPublisher = ignored -> {
            hostAttempts.incrementAndGet();
            throw new AssertionError("Ineligible candidates cannot reach the host");
        };
        var stale = new PreparedFixture(false, new ArrayList<>());
        stale.prepared.prepare(() -> false);
        var staleTarget = target();
        try (var cache = cache(unusedPublisher, 4)) {
            var lease = cache.getOrStart(request(20), content(20), key(),
                (ignored, dispatch) -> CompletableFuture.completedFuture(new ModelRenderTargetLoader.LoadResult.Ready(
                    new ModelRenderTargetLoader.ModelCandidate(staleTarget, stale.prepared))),
                (content, key, request) -> false, OWNER, WorkPriority.BACKGROUND);
            cache.tick();

            assertInstanceOf(AcquireResult.Failed.class, lease.poll());
            stale.assertAllClosed();
        }

        var cancelled = new PreparedFixture(false, new ArrayList<>());
        cancelled.prepared.prepare(() -> false);
        var cancelledTarget = target();
        try (var cache = cache(unusedPublisher, 4)) {
            var lease = cache.getOrStart(request(21), content(21), key(),
                (ignored, dispatch) -> CompletableFuture.completedFuture(new ModelRenderTargetLoader.LoadResult.Ready(
                    new ModelRenderTargetLoader.ModelCandidate(cancelledTarget, cancelled.prepared))),
                current(), OWNER, WorkPriority.BACKGROUND);
            var terminal = cache.terminal(lease);

            lease.cancelPending();
            cache.tick();

            assertInstanceOf(AcquireResult.Failed.class, terminal.join());
            cancelled.assertAllClosed();
        }
        assertEquals(0, hostAttempts.get());
    }

    @Test
    void logicalReleaseTraversesMappingCloseAndGpuCleanupOnceAfterReload() throws Exception {
        var events = new ArrayList<String>();
        var manager = new TracingTextureManager();
        var textureId = id(30);
        var texture = new LifecycleTexture(manager, textureId, events);
        manager.register(textureId, texture);
        assertSame(texture, manager.getTexture(textureId, null));

        texture.reset(manager, null, textureId, Runnable::run);
        assertEquals(List.of("upload", "upload"), events);
        events.clear();

        var binding = new HostTexturePublisher.PublishedTextureBinding(textureId, manager, List.of(textureId));
        var target = target();
        target.adoptTexture(textureId, () -> {
            events.add("logical-release");
            binding.close();
        });

        target.close();
        target.close();

        assertEquals(List.of("logical-release", "mapping-removed", "texture-close", "gpu-cleanup"), events);
        assertNull(manager.getTexture(textureId, null));
    }

    @Test
    void ordinaryBudgetCountsEveryDispositionAndBootstrapBypassesContinuousWork() throws Exception {
        var failureImages = new PreparedFixture(false, new ArrayList<>());
        failureImages.prepared.prepare(() -> false);
        var publisher = new TracingPublisher(id(40), HostFault.REGISTRATION, false, new ArrayList<>());
        var targets = new ArrayList<ModelRenderTarget>();
        var failureTarget = target();
        try (var cache = cache(publisher, 2)) {
            var failed = cache.getOrStart(request(40), content(40), key(),
                (ignored, dispatch) -> CompletableFuture.completedFuture(new ModelRenderTargetLoader.LoadResult.Ready(
                    new ModelRenderTargetLoader.ModelCandidate(failureTarget, failureImages.prepared))),
                current(), OWNER, WorkPriority.BACKGROUND);

            var cancelledTarget = target();
            targets.add(cancelledTarget);
            var cancelled =
                cache.getOrStart(request(41), content(41), key(), (ignored, dispatch) -> ready(cancelledTarget), current(), OWNER, WorkPriority.BACKGROUND);
            var cancelledTerminal = cache.terminal(cancelled);
            cancelled.cancelPending();

            var ordinary = new ArrayList<ResourceLease>();
            for (var marker = 42; marker < 46; marker++) {
                var target = target();
                targets.add(target);
                ordinary.add(cache.getOrStart(request(marker), content(marker), key(), (ignored, dispatch) -> ready(target),
                    current(), OWNER, WorkPriority.BACKGROUND));
            }

            var bootstrapTarget = target();
            targets.add(bootstrapTarget);
            var bootstrap =
                cache.getOrStartRequired(request(46), content(46), key(),
                    ignored -> new ModelRenderTargetLoader.LoadResult.Ready(
                        ModelRenderTargetLoader.ModelCandidate.testing(bootstrapTarget)),
                    current(), OWNER, Runnable::run);
            assertInstanceOf(AcquireResult.Ready.class, bootstrap.poll());
            assertEquals(1, cache.readyCount());

            cache.tick();

            assertInstanceOf(AcquireResult.Failed.class, failed.poll());
            assertInstanceOf(AcquireResult.Failed.class, cancelledTerminal.join());
            assertEquals(1, cache.readyCount());
            assertInstanceOf(AcquireResult.Pending.class, ordinary.get(0).poll());
            assertInstanceOf(AcquireResult.Pending.class, ordinary.get(1).poll());
            assertInstanceOf(AcquireResult.Pending.class, ordinary.get(2).poll());
            assertInstanceOf(AcquireResult.Pending.class, ordinary.get(3).poll());

            cache.tick();

            assertEquals(3, cache.readyCount());
            assertInstanceOf(AcquireResult.Ready.class, ordinary.get(0).poll());
            assertInstanceOf(AcquireResult.Ready.class, ordinary.get(1).poll());
            assertInstanceOf(AcquireResult.Pending.class, ordinary.get(2).poll());
            assertInstanceOf(AcquireResult.Pending.class, ordinary.get(3).poll());

            cache.tick();

            assertEquals(5, cache.readyCount());
            ordinary.forEach(lease -> assertInstanceOf(AcquireResult.Ready.class, lease.poll()));
            failureImages.assertAllClosed();
        } finally {
            targets.forEach(ModelRenderTarget::close);
        }
    }

    private ModelRenderTargetCache cache(HostTexturePublisher publisher, int limit) {
        return new ModelRenderTargetCache(scheduler, ModelRenderTarget::close, ignored -> {
        }, publisher, () -> limit, () -> 60);
    }

    private static void assertSample(ResourceLease lease, ModelRenderTarget previous, ResourceLocation expectedId,
        AbstractTexture expectedTexture, TextureManager manager) {
        var outcome = lease.poll();
        var target = outcome instanceof AcquireResult.Ready ready ? ready.target() : previous;
        assertEquals(expectedId, target.textureId());
        assertSame(expectedTexture, manager.getTexture(expectedId, null));
    }

    private static CompletableFuture<ModelRenderTargetLoader.LoadResult> ready(ModelRenderTarget target) {
        return CompletableFuture.completedFuture(
            new ModelRenderTargetLoader.LoadResult.Ready(ModelRenderTargetLoader.ModelCandidate.testing(target)));
    }

    private static ModelRenderTargetCache.CurrentLookup current() {
        return (content, key, request) -> true;
    }

    private static ResourceRequest request(int marker) {
        return new ResourceRequest(hash(marker), "player", "default", PROFILE);
    }

    private static RenderTargetKey key() {
        return new RenderTargetKey("player", "default", PROFILE);
    }

    private static ModelContent content(int marker) {
        var modelId = hash(marker);
        var representation = representation(new ModelFileIdentity(modelId, hash(marker + 100)));
        return new ModelContent() {
            @Override
            public Hash256 modelId() {
                return modelId;
            }

            @Override
            public ModelRepresentation representation() {
                return representation;
            }

            @Override
            public ModelFileView modelFile() {
                return null;
            }

            @Override
            public ChunkDataSource chunks() {
                return null;
            }
        };
    }

    private static ModelRepresentation representation(ModelFileIdentity identity) {
        try {
            var value = (ModelRepresentation)UnsafeUtil.getUnsafe().allocateInstance(ModelRepresentation.class);
            var field = ModelRepresentation.class.getDeclaredField("identity");
            UnsafeUtil.getUnsafe().putObject(value, UnsafeUtil.getUnsafe().objectFieldOffset(field), identity);
            return value;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static ModelRenderTarget target() throws Exception {
        return new ModelRenderTarget(hash(100), "test", new RenderTargetResources() {},
            new CommonAsset(Map.of(), TargetSoundResources.empty(new ModelFileIdentity(hash(100), hash(101))),
                new Object2ReferenceOpenHashMap<>(), new Object2ReferenceOpenHashMap<>()),
            com.elfmcys.ysm.expression.aot.TestExpressionModules.create(),
            (ModelInfoView)UnsafeUtil.getUnsafe().allocateInstance(ModelInfoView.class));
    }

    private static Hash256 hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)marker;
        return new Hash256(bytes);
    }

    @SuppressWarnings("removal")
    private static ResourceLocation id(int marker) {
        return new ResourceLocation("ysm", "test/model-" + marker);
    }

    private static void await(CountDownLatch latch) throws Exception {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out at test lifecycle boundary");
    }

    private enum HostFault {
        NONE, REGISTRATION, UPLOAD
    }

    private static final class PreparedFixture {
        private final ImageSource uv = unused();
        private final ImageSource normal = unused();
        private final ImageSource specular = unused();
        private final Map<ImageSource, String> names = new IdentityHashMap<>();
        private final List<NativeImage> images = Collections.synchronizedList(new ArrayList<>());
        private final List<String> events;
        private final AtomicBoolean firstDecode = new AtomicBoolean(true);
        private final CountDownLatch decodeEntered = new CountDownLatch(1);
        private final CountDownLatch decodePermit;
        private final PreparedTextureSet prepared;

        private PreparedFixture(boolean blockDecode, List<String> events) {
            this.events = events;
            decodePermit = new CountDownLatch(blockDecode ? 1 : 0);
            names.put(uv, "uv");
            names.put(normal, "normal");
            names.put(specular, "specular");
            prepared = new PreparedTextureSet(new PBRImageSources(uv, normal, specular), "test/",
                ModelResourceFailures.none(), this::decode);
        }

        private NativeImage decode(ImageSource source) throws IOException {
            var name = names.get(source);
            events.add("decode:" + name);
            if (firstDecode.compareAndSet(true, false)) {
                decodeEntered.countDown();
                waitFor(decodePermit);
            }
            var image = new NativeImage(1, 1, false);
            images.add(image);
            return image;
        }

        private void assertAllClosed() {
            images.forEach(image -> assertThrows(IllegalStateException.class, () -> image.getPixelRGBA(0, 0)));
        }
    }

    private static final class TracingPublisher implements HostTexturePublisher {
        private final ResourceLocation id;
        private final HostFault fault;
        private final List<String> events;
        private final TracingTextureManager manager = new TracingTextureManager();
        private final CountDownLatch registrationEntered = new CountDownLatch(1);
        private final CountDownLatch registrationPermit;
        private final CountDownLatch uploadEntered = new CountDownLatch(1);
        private final CountDownLatch uploadPermit;
        private AbstractTexture publishedTexture;

        private TracingPublisher(ResourceLocation id, HostFault fault, boolean block, List<String> events) {
            this.id = id;
            this.fault = fault;
            this.events = events;
            registrationPermit = new CountDownLatch(block ? 1 : 0);
            uploadPermit = new CountDownLatch(block ? 1 : 0);
        }

        private AbstractTexture install(ResourceLocation textureId, String name) {
            var texture = new InstalledTexture(manager, textureId, events, name);
            manager.register(textureId, texture);
            return texture;
        }

        @Override
        public PublishedTextureBinding publish(PreparedTextureSet prepared) throws Exception {
            var candidateTextures = prepared.transfer();
            var disposed = new AtomicBoolean();
            Runnable dispose = () -> {
                if (disposed.compareAndSet(false, true)) {
                    candidateTextures.closeUnregistered();
                }
            };
            try {
                events.add("registration");
                registrationEntered.countDown();
                waitFor(registrationPermit);
                if (fault == HostFault.REGISTRATION) {
                    throw new IOException("registration failed");
                }
                var texture = new CandidateTexture(manager, id, events, uploadEntered, uploadPermit,
                    fault == HostFault.UPLOAD, dispose);
                manager.register(id, texture);
                if (manager.getTexture(id, null) != texture) {
                    manager.release(id);
                    throw new IOException("upload failed");
                }
                publishedTexture = texture;
                events.add("mapping-installed");
                return new PublishedTextureBinding(id, manager, List.of(id));
            } finally {
                dispose.run();
            }
        }
    }

    private static class InstalledTexture extends AbstractTexture {
        protected final TextureManager manager;
        protected final ResourceLocation id;
        protected final List<String> events;
        private final String name;

        private InstalledTexture(TextureManager manager, ResourceLocation id, List<String> events, String name) {
            this.manager = manager;
            this.id = id;
            this.events = events;
            this.name = name;
        }

        @Override
        public void load(ResourceManager resourceManager) {
            events.add(name + "-upload");
        }

        @Override
        public void close() {
            assertNull(manager.getTexture(id, null));
            events.add("mapping-removed");
            events.add("texture-close");
        }

        @Override
        public void releaseId() {
            events.add("gpu-cleanup");
        }
    }

    private static final class CandidateTexture extends InstalledTexture {
        private final CountDownLatch uploadEntered;
        private final CountDownLatch uploadPermit;
        private final boolean failUpload;
        private final Runnable dispose;

        private CandidateTexture(TextureManager manager, ResourceLocation id, List<String> events,
            CountDownLatch uploadEntered, CountDownLatch uploadPermit, boolean failUpload, Runnable dispose) {
            super(manager, id, events, "candidate");
            this.uploadEntered = uploadEntered;
            this.uploadPermit = uploadPermit;
            this.failUpload = failUpload;
            this.dispose = dispose;
        }

        @Override
        public void load(ResourceManager resourceManager) {
            events.add("upload");
            uploadEntered.countDown();
            try {
                waitForUnchecked(uploadPermit);
                if (failUpload) {
                    throw new IllegalStateException("upload failed");
                }
            } finally {
                dispose.run();
            }
        }
    }

    private static final class LifecycleTexture extends AbstractTexture {
        private final TextureManager manager;
        private final ResourceLocation id;
        private final List<String> events;

        private LifecycleTexture(TextureManager manager, ResourceLocation id, List<String> events) {
            this.manager = manager;
            this.id = id;
            this.events = events;
        }

        @Override
        public void load(ResourceManager resourceManager) {
            events.add("upload");
        }

        @Override
        public void close() {
            assertNull(manager.getTexture(id, null));
            events.add("mapping-removed");
            events.add("texture-close");
        }

        @Override
        public void releaseId() {
            events.add("gpu-cleanup");
        }
    }

    private static final class TracingTextureManager extends TextureManager {
        private final Map<ResourceLocation, AbstractTexture> textures = new HashMap<>();

        private TracingTextureManager() {
            super(null);
        }

        @Override
        public void register(ResourceLocation id, AbstractTexture texture) {
            try {
                texture.load(null);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            textures.put(id, texture);
        }

        @Override
        public AbstractTexture getTexture(ResourceLocation id, AbstractTexture defaultTexture) {
            return textures.getOrDefault(id, defaultTexture);
        }

        @Override
        public void release(ResourceLocation id) {
            var texture = textures.remove(id);
            if (texture != null) {
                texture.close();
                texture.releaseId();
            }
        }
    }

    private static void waitFor(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IOException("Timed out at test lifecycle boundary");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted at test lifecycle boundary", interrupted);
        }
    }

    private static void waitForUnchecked(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out at test lifecycle boundary");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted at test lifecycle boundary", interrupted);
        }
    }

    private static ImageSource unused() {
        return new ImageSource() {
            @Override
            public Image open() {
                throw new AssertionError("Injected decoder owns this test boundary");
            }
        };
    }
}
