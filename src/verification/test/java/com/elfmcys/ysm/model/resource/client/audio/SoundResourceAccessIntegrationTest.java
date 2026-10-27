package com.elfmcys.ysm.model.resource.client.audio;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.views.SoundMedia;
import com.elfmcys.ysm.model.catalog.RawModelImporter;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.resource.client.TargetSoundResources;
import com.elfmcys.ysm.model.resource.client.remote.RemoteChunkFetcher;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelContent;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelStore;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class SoundResourceAccessIntegrationTest {
    @TempDir
    Path temp;

    @Test
    void targetRetainsOnlyValidatedFirstChunksAndRevokesThemOnClose() throws Exception {
        try (var fixture = createFixture("target")) {
            var reads = new java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>();
            var counted = counting(fixture.content, reads);
            var first = fixture.sound.chunkInfos().get(0);
            var sounds = new LinkedHashMap<>(fixture.content.representation().view().getCommon().sounds());
            sounds.put("tone_alias", fixture.sound);

            var resources = TargetSoundResources.prepare(() -> false, fixture.content.representation().identity(),
                sounds, counted.chunks());
            var source = resources.bind(fixture.sound);
            assertEquals(first.size(), resources.retainedBytes());
            assertEquals(1, count(reads, first.type()));
            for (var tail : fixture.sound.chunkInfos().subList(1, fixture.sound.chunkInfos().size())) {
                assertEquals(0, count(reads, tail.type()));
            }
            try (var retained = source.acquireFirstChunk();
                var expected = fixture.content.chunks().readPayload(first, BufferType.NATIVE)) {
                assertArrayEquals(bytes(expected), bytes(retained));
            }

            resources.close();
            assertThrows(AssetLoadException.class, source::acquireFirstChunk);
        }
    }

    @Test
    void verifiedAccessEnforcesOrderWindowAndExactIdentity() throws Exception {
        try (var fixture = createFixture("window")) {
            assertTrue(fixture.sound.chunkInfos().size() >= 3,
                "bundled long audio must exercise more than the first/tail window");
            try (var resources = TargetSoundResources.prepare(() -> false, fixture.content.representation().identity(),
                fixture.content.representation().view().getCommon().sounds(), fixture.content.chunks())) {
                var source = resources.bind(fixture.sound);
                try (var access =
                    VerifiedChunkAccess.create(source, fixture.content, null, Runnable::run, Runnable::run)) {
                    var firstInterest = access.request(0);
                    var first = firstInterest.result().join();
                    firstInterest.cancel();
                    assertThrows(IllegalArgumentException.class, () -> access.request(2));
                    var second = access.request(1).result().join();
                    assertThrows(IllegalStateException.class, () -> access.request(2));
                    assertEquals(source.representation(), first.representation());
                    assertEquals(source.media().mediaIdentity(), first.mediaIdentity());

                    first.close();
                    try (second; var third = access.request(2).result().join()) {
                        assertEquals(2, third.index());
                    }
                }
                resources.close();
                assertThrows(AssetLoadException.class,
                    () -> VerifiedChunkAccess.create(source, fixture.content, null, Runnable::run, Runnable::run));
            }
        }
    }

    @Test
    void restartKeepsTheAcceptedGrantAndOnlyReacquiresTailChunks() throws Exception {
        try (var fixture = createFixture("restart")) {
            var reads = new java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>();
            var counted = counting(fixture.content, reads);
            try (var resources = TargetSoundResources.prepare(() -> false, fixture.content.representation().identity(),
                fixture.content.representation().view().getCommon().sounds(), counted.chunks())) {
                var source = resources.bind(fixture.sound);
                var first = fixture.sound.chunkInfos().get(0);
                try (var original = VerifiedChunkAccess.create(source, counted, null, Runnable::run, Runnable::run)) {
                    assertThrows(IllegalStateException.class, original::restart);
                    for (int index = 0; index < fixture.sound.chunkInfos().size(); index++) {
                        close(original.request(index).result().join());
                    }
                    try (var restarted = original.restart()) {
                        resources.close();
                        assertThrows(IllegalStateException.class, () -> original.request(0));
                        try (var chunk = restarted.request(0).result().join()) {
                            assertEquals(source.representation(), chunk.representation());
                            assertEquals(source.media().mediaIdentity(), chunk.mediaIdentity());
                            assertThrows(IllegalArgumentException.class, () -> restarted.request(0));
                        }
                        for (int index = 1; index < fixture.sound.chunkInfos().size(); index++) {
                            close(restarted.request(index).result().join());
                        }
                    }
                }
                assertEquals(1, count(reads, first.type()));
                for (var tail : fixture.sound.chunkInfos().subList(1, fixture.sound.chunkInfos().size())) {
                    assertEquals(2, count(reads, tail.type()));
                }
            }
        }
    }

    @Test
    void remoteTargetRequestsEveryFirstChunkAndNoTail() throws Exception {
        try (var fixture = createFixture("target-remote");
            var store = new RemoteModelStore(temp.resolve("target-store"))) {
            var remote = commitMetadata(store, fixture.content);
            var requested = new AtomicReference<List<AssetContainerView.ChunkInfo>>();
            var workers = Executors.newSingleThreadExecutor();
            try {
                var target = remote.representation().view().getRenderTargets().stream().findFirst().orElseThrow();
                var texture = target.getTextureNames().stream().findFirst().orElseThrow();
                var retained = remote.loadRenderTarget(() -> false, target.id(), texture,
                    fetchFrom(fixture.content, requested), workers, prepared -> {
                        try (var resources =
                            TargetSoundResources.prepare(() -> false, prepared.representation().identity(),
                                prepared.representation().view().getCommon().sounds(), prepared.chunks())) {
                            return resources.retainedBytes();
                        } catch (IOException failure) {
                            throw new CompletionException(failure);
                        }
                    }).join();
                var first = fixture.sound.chunkInfos().get(0);
                assertEquals(first.size(), retained);
                assertTrue(requested.get().stream().anyMatch(chunk -> chunk.type().equals(first.type())));
                for (var tail : fixture.sound.chunkInfos().subList(1, fixture.sound.chunkInfos().size())) {
                    assertFalse(requested.get().stream().anyMatch(chunk -> chunk.type().equals(tail.type())));
                }
            } finally {
                workers.shutdownNow();
                workers.awaitTermination(10, TimeUnit.SECONDS);
                remote.representation().close();
            }
        }
    }

    @Test
    void sharedRemoteFlightCancelsOnlyAfterItsLastInterest() throws Exception {
        try (var fixture = createFixture("cancel"); var store = new RemoteModelStore(temp.resolve("cancel-store"))) {
            var remote = commitMetadata(store, fixture.content);
            var workers = Executors.newSingleThreadExecutor();
            var accepted = new CompletableFuture<Void>();
            var fetches = new AtomicInteger();
            RemoteChunkFetcher fetcher = (identity, chunks, receiver) -> {
                fetches.incrementAndGet();
                return accepted;
            };
            try (var resources = readyRemote(remote, fixture.content);
                var first = access(resources, remote, fetcher, workers);
                var second = access(resources, remote, fetcher, workers)) {
                close(first.request(0).result().join());
                close(second.request(0).result().join());
                var firstTail = first.request(1);
                var secondTail = second.request(1);
                workers.submit(() -> {
                }).get(10, TimeUnit.SECONDS);
                assertEquals(1, fetches.get());

                firstTail.cancel();
                assertFalse(accepted.isCancelled());
                secondTail.cancel();
                assertTrue(accepted.isCancelled());
            } finally {
                workers.shutdownNow();
                workers.awaitTermination(10, TimeUnit.SECONDS);
                remote.representation().close();
            }
        }
    }

    @Test
    void sharedRemoteFlightPublishesOneVerifiedChunkForLaterCacheHits() throws Exception {
        try (var fixture = createFixture("shared"); var store = new RemoteModelStore(temp.resolve("shared-store"))) {
            var remote = commitMetadata(store, fixture.content);
            var workers = Executors.newSingleThreadExecutor();
            var accepted = new CompletableFuture<Void>();
            var fetches = new AtomicInteger();
            var receiver = new AtomicReference<RemoteChunkFetcher.ChunkReceiver>();
            var requested = new AtomicReference<AssetContainerView.ChunkInfo>();
            RemoteChunkFetcher fetcher = (identity, chunks, sink) -> {
                fetches.incrementAndGet();
                receiver.set(sink);
                requested.set(chunks.get(0));
                return accepted;
            };
            try (var resources = readyRemote(remote, fixture.content);
                var first = access(resources, remote, fetcher, workers);
                var second = access(resources, remote, fetcher, workers)) {
                close(first.request(0).result().join());
                close(second.request(0).result().join());
                var firstTail = first.request(1).result();
                var secondTail = second.request(1).result();
                workers.submit(() -> {
                }).get(10, TimeUnit.SECONDS);
                assertEquals(1, fetches.get());

                try (var stored = fixture.content.chunks().readStoredVerified(requested.get(), BufferType.NATIVE)) {
                    receiver.get().accept(requested.get(), stored);
                }
                accepted.complete(null);
                try (var firstChunk = firstTail.join(); var secondChunk = secondTail.join()) {
                    assertEquals(1, firstChunk.index());
                    assertEquals(1, secondChunk.index());
                }

                try (var cached = access(resources, remote, (identity, chunks, sink) -> {
                    fetches.incrementAndGet();
                    return CompletableFuture.failedFuture(new AssertionError("cache hit fetched remotely"));
                }, workers)) {
                    close(cached.request(0).result().join());
                    close(cached.request(1).result().join());
                }
                assertEquals(1, fetches.get());
            } finally {
                workers.shutdownNow();
                workers.awaitTermination(10, TimeUnit.SECONDS);
                remote.representation().close();
            }
        }
    }

    @Test
    void replacementConnectionDoesNotJoinTheOldAcceptedFlight() throws Exception {
        try (var fixture = createFixture("connection");
            var store = new RemoteModelStore(temp.resolve("connection-store"))) {
            var remote = commitMetadata(store, fixture.content);
            var workers = Executors.newSingleThreadExecutor();
            var oldAccepted = new CompletableFuture<Void>();
            var newAccepted = new CompletableFuture<Void>();
            var oldReceiver = new AtomicReference<RemoteChunkFetcher.ChunkReceiver>();
            var newReceiver = new AtomicReference<RemoteChunkFetcher.ChunkReceiver>();
            var requested = new AtomicReference<AssetContainerView.ChunkInfo>();
            RemoteChunkFetcher oldConnection = (identity, chunks, sink) -> {
                oldReceiver.set(sink);
                requested.set(chunks.get(0));
                return oldAccepted;
            };
            RemoteChunkFetcher newConnection = (identity, chunks, sink) -> {
                newReceiver.set(sink);
                requested.set(chunks.get(0));
                return newAccepted;
            };
            try (var resources = readyRemote(remote, fixture.content);
                var oldAccess = access(resources, remote, oldConnection, workers);
                var newAccess = access(resources, remote, newConnection, workers)) {
                close(oldAccess.request(0).result().join());
                close(newAccess.request(0).result().join());
                var oldTail = oldAccess.request(1).result();
                var newTail = newAccess.request(1).result();
                workers.submit(() -> {
                }).get(10, TimeUnit.SECONDS);
                assertTrue(oldReceiver.get() != null);
                assertTrue(newReceiver.get() != null);

                try (var stored = fixture.content.chunks().readStoredVerified(requested.get(), BufferType.NATIVE)) {
                    oldReceiver.get().accept(requested.get(), stored);
                }
                oldAccepted.complete(null);
                close(oldTail.join());
                assertFalse(newTail.isDone());

                try (var stored = fixture.content.chunks().readStoredVerified(requested.get(), BufferType.NATIVE)) {
                    newReceiver.get().accept(requested.get(), stored);
                }
                newAccepted.complete(null);
                close(newTail.join());
            } finally {
                workers.shutdownNow();
                workers.awaitTermination(10, TimeUnit.SECONDS);
                remote.representation().close();
            }
        }
    }

    @Test
    void residentDefaultSoundSurvivesBackingFileRemoval() throws Exception {
        try (var fixture = createFixture("default")) {
            var encoded = Files.readAllBytes(fixture.container);
            var resident = ManagedContainer.openResidentDefault(encoded,
                new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("audio-default")));
            Files.delete(fixture.container);
            try {
                var sound = resident.representation().view().getCommon().sounds().get("tone");
                sound.validateComplete(() -> false, resident.chunks());
                try (
                    var resources = TargetSoundResources.prepare(() -> false, resident.representation().identity(),
                        resident.representation().view().getCommon().sounds(), resident.chunks());
                    var access = VerifiedChunkAccess.create(resources.bind(sound), resident, null, Runnable::run,
                        Runnable::run)) {
                    for (var index = 0; index < sound.chunkInfos().size(); index++) {
                        close(access.request(index).result().join());
                    }
                }
            } finally {
                resident.representation().close();
            }
        }
    }

    private Fixture createFixture(String name) throws Exception {
        return createFixture(name, "/assets/ysm/builtin/wine_fox/07_jk/sounds/minuet.ogg");
    }

    private Fixture createFixture(String name, String audioResource) throws Exception {
        var source = copyRawFixture(temp.resolve(name + "-raw"), audioResource);
        var importer = new RawModelImporter(DefaultAnimationFilter.keepAll());
        final Path container;
        try (var captured = importer.capture(source)) {
            container =
                importer.convert(captured, Files.createDirectories(temp.resolve(name + "-output"))).stagedContainer();
        }
        var content = ManagedContainer.openDirect(container,
            new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("audio-" + name)));
        var sound = content.representation().view().getCommon().sounds().get("tone");
        return new Fixture(container, content, Objects.requireNonNull(sound));
    }

    private static Path copyRawFixture(Path destination, String audioResource) throws Exception {
        var manifest = SoundResourceAccessIntegrationTest.class.getResource("/assets/ysm/builtin/misc/1_alex/ysm.json");
        var source = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
        try (var paths = Files.walk(source)) {
            for (var path : paths.toList()) {
                var target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        var audio = SoundResourceAccessIntegrationTest.class.getResource(audioResource);
        Files.copy(Path.of(Objects.requireNonNull(audio).toURI()),
            Files.createDirectories(destination.resolve("sounds")).resolve("tone.ogg"),
            StandardCopyOption.REPLACE_EXISTING);
        return destination;
    }

    private static RemoteModelContent commitMetadata(RemoteModelStore store, ManagedContainer content)
        throws Exception {
        try (var prefix = content.representation().metadataPrefix().orElseThrow()) {
            return store.commitMetadataPrefix(content.representation().identity(), prefix);
        }
    }

    private static RemoteChunkFetcher fetchFrom(ManagedContainer source,
        AtomicReference<List<AssetContainerView.ChunkInfo>> requested) {
        return (identity, chunks, receiver) -> {
            requested.set(List.copyOf(chunks));
            try {
                for (var chunk : chunks) {
                    try (var stored = source.chunks().readStoredVerified(chunk, BufferType.NATIVE)) {
                        receiver.accept(chunk, stored);
                    }
                }
                return CompletableFuture.completedFuture(null);
            } catch (IOException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        };
    }

    private static TargetSoundResources readyRemote(RemoteModelContent remote, ManagedContainer source)
        throws IOException {
        return TargetSoundResources.prepare(() -> false, remote.representation().identity(),
            remote.representation().view().getCommon().sounds(), source.chunks());
    }

    private static VerifiedChunkAccess access(TargetSoundResources resources, RemoteModelContent remote,
        RemoteChunkFetcher fetcher, java.util.concurrent.Executor workers) throws AssetLoadException {
        var sound = remote.representation().view().getCommon().sounds().get("tone");
        return VerifiedChunkAccess.create(resources.bind(sound), remote, fetcher, workers, Runnable::run);
    }

    private static ModelContent counting(ModelContent source, java.util.Map<String, AtomicInteger> reads) {
        return new ModelContent() {
            @Override
            public com.elfmcys.ysm.model.domain.ModelRepresentation representation() {
                return source.representation();
            }

            @Override
            public ChunkDataSource chunks() {
                return new ChunkDataSource() {
                    @Override
                    public UniBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType)
                        throws IOException {
                        reads.computeIfAbsent(chunk.type(), ignored -> new AtomicInteger()).incrementAndGet();
                        return source.chunks().readPayload(chunk, bufferType);
                    }

                    @Override
                    public UniBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType)
                        throws IOException {
                        return source.chunks().readStoredVerified(chunk, bufferType);
                    }
                };
            }
        };
    }

    private static int count(java.util.Map<String, AtomicInteger> reads, String type) {
        var value = reads.get(type);
        return value == null ? 0 : value.get();
    }

    private static byte[] bytes(UniBuffer source) {
        var result = new byte[source.size()];
        source.nio().get(result);
        return result;
    }

    private static void close(AutoCloseable closeable) throws Exception {
        closeable.close();
    }

    private record Fixture(Path container, ManagedContainer content, SoundMedia sound) implements AutoCloseable {
        @Override
        public void close() {
            content.representation().close();
        }
    }
}
