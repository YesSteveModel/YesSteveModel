package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.expression.aot.ExpressionArtifactStore;
import com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModuleFactory;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.file.FileChunkDataSource;
import com.elfmcys.ysm.format.schema.model.views.CommonAssetView;
import com.elfmcys.ysm.format.schema.model.views.SoundMedia;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ModelResourceFailures;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.TargetSoundResources;
import com.elfmcys.ysm.model.resource.client.audio.VerifiedChunkAccess;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.natives.sound.OpusDecoder;
import com.elfmcys.ysm.proto.mixel.manifest.asset.CommonProto;
import com.elfmcys.ysm.testutil.DerivedStorageTestScope;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.player.AbstractClientPlayer;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(NativeLibraryExtension.class)
class SoundPreparationIntegrationTest {
    @TempDir
    Path root;

    @BeforeAll
    static void renderOwner() {
        if (!RenderSystem.isOnRenderThread())
            RenderSystem.initRenderThread();
    }

    @Test
    void concurrentTexturesShareAdmissionAndRetireIndependentlyOfPlayback() throws Exception {
        try (var fixture = new Fixture(root)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            fixture.beforeBatch = () -> {
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    throw new AssertionError(failure);
                }
            };
            long decoders = decoderCreates();
            var cancelled = fixture.load("default", ModelResourcePurpose.FULL);
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                var first = fixture.load("default", ModelResourcePurpose.FULL);
                var second = fixture.load("blue", ModelResourcePurpose.FULL);
                assertTrue(cancelled.cancel(false));
                release.countDown();
                try (var a = ready(first); var b = ready(second)) {
                    assertEquals(2, decoderCreates() - decoders);
                    assertReads(fixture, 1);
                    var targetA = a.publish();
                    var prepared = (TargetSoundResources)property(
                        ((ModelRenderTarget.StateIdentity)field(targetA, "stateIdentity")).preparation(), "sounds");
                    long retained = fixture.sounds().values().stream().map(sound -> sound.chunkInfos().get(0))
                        .collect(Collectors.toMap(chunk -> chunk.type(), chunk -> chunk.size(), (left, right) -> left))
                        .values().stream().mapToLong(Integer::longValue).sum();
                    assertEquals(retained, prepared.retainedBytes());
                    var targetB = b.publish();
                    var sourceA = targetA.assets().sounds().get("tone");
                    var sourceB = targetB.assets().sounds().get("tone");
                    try (var access =
                        VerifiedChunkAccess.create(sourceA, fixture.content, null, Runnable::run, Runnable::run)) {
                        a.close();
                        assertThrows(AssetLoadException.class, sourceA::acquireFirstChunk);
                        try (var bytes = sourceB.acquireFirstChunk()) {
                            assertTrue(bytes.size() > 0);
                        }
                        fixture.loader.close();
                        b.close();
                        assertEquals(0, prepared.retainedBytes());
                        assertThrows(IllegalStateException.class, prepared::acquire);
                        for (int index = 0; index < sourceA.media().chunkInfos().size(); index++) {
                            try (var chunk = access.request(index).result().join(); var bytes = chunk.acquireBuffer()) {
                                assertTrue(bytes.size() > 0);
                            }
                        }
                    }
                }
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void sharedChunkStillValidatesDifferentMediaConstraints() throws Exception {
        try (var fixture = new Fixture(root)) {
            var original = fixture.content.representation().view().getManifest().commonAssets().sounds().stream()
                .filter(sound -> sound.name().equals("tone")).findFirst().orElseThrow();
            var alternate = original.toBuilder().setName("alternate").setSamples(original.samples() + 1).build();
            var media = new CommonAssetView(CommonProto.newBuilder().addSounds(alternate).build(),
                fixture.content.representation().view().getFileView()).sounds().get("alternate");
            var tone = fixture.sounds().get("tone");
            long decoders = decoderCreates();
            try (var prepared = TargetSoundResources.prepare(() -> false, fixture.content.representation().identity(),
                Map.of("tone", tone, "alias", tone), fixture.content.chunks())) {
                assertThrows(IllegalArgumentException.class, () -> prepared.bind(media));
            }
            assertEquals(1, decoderCreates() - decoders);
            fixture.reset();
            fixture.expectedReadBytes = tone.chunkInfos().get(0).size() + tone.chunkInfos().get(0).alignSize();
            decoders = decoderCreates();
            try (var prepared = TargetSoundResources.prepare(() -> false, fixture.content.representation().identity(),
                Map.of("tone", tone, "alternate", media), fixture.content.chunks())) {
                assertNotNull(prepared.bind(media));
                assertEquals(tone.chunkInfos().get(0).size(), prepared.retainedBytes());
                assertEquals(2, decoderCreates() - decoders);
                assertReads(fixture, 1);
            }
        }
    }

    @Test
    void badOrUnavailableFirstChunkRejectsReadyAndANewAttemptCanSucceed() throws Exception {
        try (var fixture = new Fixture(root)) {
            var original = Files.readAllBytes(fixture.file);
            for (var sound : fixture.sounds().values()) {
                var bad = original.clone();
                bad[sound.chunkInfos().get(0).offset()] ^= 1;
                Files.write(fixture.file, bad);
                assertInstanceOf(ModelRenderTargetLoader.LoadResult.Failed.class,
                    fixture.load("default", ModelResourcePurpose.FULL).get(30, TimeUnit.SECONDS));
            }
            Files.write(fixture.file, original);
            var unavailable = fixture.file.resolveSibling("parked");
            Files.move(fixture.file, unavailable);
            var rejected = assertInstanceOf(ModelRenderTargetLoader.LoadResult.Failed.class,
                fixture.load("default", ModelResourcePurpose.FULL).get(30, TimeUnit.SECONDS));
            assertEquals(AssetLoadException.Reason.ACCESS, ((AssetLoadException)rejected.failure().cause()).reason());
            Files.move(unavailable, fixture.file);
            fixture.reset();
            try (var candidate = ready(fixture.load("default", ModelResourcePurpose.FULL))) {
                assertNotNull(candidate.publish().assets().sounds().get("tone"));
                assertReads(fixture, 1);
                fixture.reset();
                try (var gui = ready(fixture.load("default", ModelResourcePurpose.GUI))) {
                    assertNotNull(gui.publish().assets().sounds().get("tone"));
                    assertReads(fixture, 1);
                }
            }
        }
    }

    @Test
    void hashValidButInvalidMediaRejectsPreparationAndReleasesTheWholeBatch() throws Exception {
        try (var fixture = new Fixture(root)) {
            var sound = fixture.sounds().get("tone");
            var first = sound.chunkInfos().get(0);
            var encoded = Files.readAllBytes(fixture.file);
            encoded[first.offset()] ^= 1;
            Files.write(fixture.file, encoded);
            var hash = com.elfmcys.ysm.model.storage.ModelHashing
                .blake3(java.nio.ByteBuffer.wrap(encoded, first.offset(), first.size())).bytes();
            var replacement = new com.elfmcys.ysm.format.container.AssetContainerView.ChunkInfo(first.type(),
                first.encoding(), first.offset(), first.size(), first.decodeSize(), first.flags(), first.alignSize(),
                first.alignmentShift(), hash);
            var descriptor = fixture.content.representation().view().getManifest().commonAssets().sounds().stream()
                .filter(value -> value.name().equals("tone")).findFirst().orElseThrow();
            var fileView = fixture.content.representation().view().getFileView();
            var media = new CommonAssetView(CommonProto.newBuilder().addSounds(descriptor).build(),
                new com.elfmcys.ysm.format.schema.file.AssetFileView(null) {
                    @Override
                    public com.elfmcys.ysm.format.container.AssetContainerView.ChunkInfo blobChunkInfo(int id) {
                        var chunk = fileView.blobChunkInfo(id);
                        return chunk.type().equals(first.type()) ? replacement : chunk;
                    }
                }).sounds().get("tone");
            var retained = new java.util.concurrent.atomic.AtomicReference<Map<String, UniBuffer>>();
            var delegate = fixture.content.chunks();
            var captured = new ChunkDataSource() {
                @Override
                public UniBuffer readPayload(com.elfmcys.ysm.format.container.AssetContainerView.ChunkInfo chunk,
                    BufferType type) throws IOException {
                    return delegate.readPayload(chunk, type);
                }

                @Override
                public UniBuffer readStoredVerified(com.elfmcys.ysm.format.container.AssetContainerView.ChunkInfo chunk,
                    BufferType type) throws IOException {
                    return delegate.readStoredVerified(chunk, type);
                }

                @Override
                public Map<String, UniBuffer> readPayloads(java.util.function.BooleanSupplier cancelled,
                    java.util.Collection<com.elfmcys.ysm.format.container.AssetContainerView.ChunkInfo> chunks,
                    BufferType type) throws IOException {
                    var batch = delegate.readPayloads(cancelled, chunks, type);
                    retained.set(batch);
                    return batch;
                }
            };
            var inputs = new java.util.LinkedHashMap<String, SoundMedia>();
            inputs.put("effect", fixture.sounds().get("effect"));
            inputs.put("tone", media);
            assertThrows(IllegalArgumentException.class, () -> TargetSoundResources.prepare(() -> false,
                fixture.content.representation().identity(), inputs, captured));
            assertEquals(2, retained.get().size());
            retained.get().values().forEach(bytes -> assertThrows(IllegalStateException.class, bytes::nio));
        }
    }

    private static void assertReads(Fixture fixture, int batches) {
        assertEquals(batches, fixture.opens.get());
        assertEquals(fixture.expectedReadBytes, fixture.bytes.get());
        assertEquals(0, fixture.tailReads.get());
    }

    private static ModelRenderTargetLoader.ModelCandidate
        ready(java.util.concurrent.CompletableFuture<ModelRenderTargetLoader.LoadResult> result) throws Exception {
        var value = result.get(30, TimeUnit.SECONDS);
        if (value instanceof ModelRenderTargetLoader.LoadResult.Failed failure)
            throw new AssertionError("Preparation failed", failure.failure().cause());
        return ((ModelRenderTargetLoader.LoadResult.Ready)value).candidate();
    }

    private static long decoderCreates() throws Exception {
        var method = OpusDecoder.class.getDeclaredMethod("nCreateCount");
        method.setAccessible(true);
        return (long)method.invoke(null);
    }

    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static Object property(Object object, String name) throws Exception {
        var method = object.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(object);
    }

    private static final class Fixture implements AutoCloseable {
        final Path file;
        final ManagedContainer base;
        final ModelContent content;
        final ClientResourceScheduler scheduler = new ClientResourceScheduler(2);
        final ModelRenderTargetLoader loader;
        final AtomicInteger opens = new AtomicInteger();
        final AtomicLong bytes = new AtomicLong();
        final AtomicInteger tailReads = new AtomicInteger();
        volatile Runnable beforeBatch = () -> {
        };
        long expectedReadBytes;

        Fixture(Path root) throws Exception {
            var source = Path
                .of(SoundPreparationIntegrationTest.class.getResource("/assets/ysm/builtin/default/ysm.json").toURI())
                .getParent();
            var raw = Files.createDirectories(root.resolve("raw"));
            try (var paths = Files.walk(source)) {
                for (var path : paths.toList()) {
                    var destination = raw.resolve(source.relativize(path).toString());
                    if (Files.isDirectory(path))
                        Files.createDirectories(destination);
                    else
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            var sounds = Files.createDirectories(raw.resolve("sounds"));
            Files.copy(
                Path.of(SoundPreparationIntegrationTest.class
                    .getResource("/assets/ysm/builtin/wine_fox/07_jk/sounds/minuet.ogg").toURI()),
                sounds.resolve("tone.ogg"));
            Files.copy(
                Path.of(SoundPreparationIntegrationTest.class
                    .getResource("/assets/ysm/builtin/wine_fox/22_elf/sounds/atk1.ogg").toURI()),
                sounds.resolve("effect.ogg"));
            try (var directory = new Directory(raw)) {
                file = ModelParser.parseBuiltinDefault(directory, Files.createDirectories(root.resolve("model")));
            }
            base = ManagedContainer.openDirect(file,
                new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("sound-preparation")));
            var chunks = new FileChunkDataSource(file, Files.size(file), () -> {
                boolean batch = StackWalker.getInstance().walk(
                    frames -> frames.anyMatch(frame -> frame.getClassName().equals(FileChunkDataSource.class.getName())
                        && (frame.getMethodName().equals("readPayloads")
                            || frame.getMethodName().equals("openReadPhase"))));
                if (batch) {
                    opens.incrementAndGet();
                    beforeBatch.run();
                }
                var channel = FileChannel.open(file);
                return (SeekableByteChannel)Proxy.newProxyInstance(SeekableByteChannel.class.getClassLoader(),
                    new Class<?>[] {SeekableByteChannel.class}, (proxy, method, arguments) -> {
                        try {
                            long position = method.getName().equals("read") ? channel.position() : -1;
                            var result = method.invoke(channel, arguments);
                            if (position >= 0 && (int)result > 0
                                && sounds().values().stream().flatMap(sound -> sound.chunkInfos().stream().skip(1))
                                    .anyMatch(chunk -> position < (long)chunk.offset() + chunk.size()
                                        && position + (int)result > chunk.offset()))
                                tailReads.incrementAndGet();
                            if (batch && method.getName().equals("read") && (int)result > 0) {
                                long end = position + (int)result;
                                bytes.addAndGet(
                                    sounds().values().stream().map(sound -> sound.chunkInfos().get(0)).distinct()
                                        .mapToLong(chunk -> Math.max(0,
                                            Math.min(end, (long)chunk.offset() + chunk.size() + chunk.alignSize())
                                                - Math.max(position, chunk.offset())))
                                        .sum());
                            }
                            return result;
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            });
            content = new ModelContent() {
                @Override
                public ModelRepresentation representation() {
                    return base.representation();
                }

                @Override
                public ChunkDataSource chunks() {
                    return chunks;
                }
            };
            var cacheRoot = root.resolve("cache");
            var cache = DerivedStorageTestScope.track(new AtomicSharedCache(cacheRoot));
            var factory = new RenderTargetExpressionModuleFactory(
                DerivedStorageTestScope.track(new ExpressionArtifactStore(cacheRoot, cache)),
                ExpressionHost.create(HostAvailability.all()), new ExpressionEngineMetrics());
            loader = new ModelRenderTargetLoader(new BakedModelCache(cacheRoot, cache), factory, scheduler,
                new DefaultAnimationRuntime());
            RenderTargetStateIdentityTest.installMetadataConsumer(loader);
            expectedReadBytes = sounds().values().stream().map(sound -> sound.chunkInfos().get(0))
                .mapToLong(chunk -> chunk.size() + chunk.alignSize()).sum();
            reset();
        }

        Map<String, SoundMedia> sounds() {
            return base.representation().view().getCommon().sounds();
        }

        void reset() {
            opens.set(0);
            bytes.set(0);
            tailReads.set(0);
        }

        java.util.concurrent.CompletableFuture<ModelRenderTargetLoader.LoadResult> load(String texture,
            ModelResourcePurpose purpose) {
            var binding = loader.expressionBinding(true, AbstractClientPlayer.class);
            return loader.loadAsync(() -> false, scheduler.dispatch(WorkPriority.BACKGROUND), content,
                new RenderTargetKey("player", texture, loader.bakeProfile(), binding, purpose), true,
                ModelResourceFailures.none());
        }

        @Override
        public void close() throws Exception {
            beforeBatch = () -> {
            };
            loader.close();
            scheduler.shutdown();
            scheduler.awaitTermination();
            base.representation().close();
        }
    }
}
