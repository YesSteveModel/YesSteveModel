package com.elfmcys.ysm.model.resource.client.asset;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.ClientCatalogScenarioHarness;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.HierarchyPath;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelContent;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelStore;
import com.elfmcys.ysm.model.resource.server.ServerResourceRuntime;
import com.elfmcys.ysm.model.session.client.ClientModelSession;
import com.elfmcys.ysm.model.session.client.state.ActivationSnapshot;
import com.elfmcys.ysm.model.session.client.state.PublicationEntry;
import com.elfmcys.ysm.model.session.client.state.RemotePublicationSnapshot;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelExporter;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.model.storage.TestPreviews;
import com.elfmcys.ysm.network.forge.ClientPresentationTransportFixture;
import com.elfmcys.ysm.network.protocol.ProtocolVersion;
import com.elfmcys.ysm.network.session.SessionMode;
import com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource;
import com.elfmcys.ysm.proto.network.PresentationDelivery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ClientRemotePreviewRepositoryTest {
    private static final byte[] BAD_MEDIA = {1, 2, 3};

    @TempDir
    Path temp;

    @Test
    void remoteManifestControlsBadMediaDespiteAnOppositeLocalRepresentation() throws Exception {
        try (var fixture = new Fixture(temp)) {
            assertEquals(fixture.standalone.representation().modelId(), fixture.embedded.representation().modelId());
            for (var embedded : List.of(false, true)) {
                var request = fixture.request(embedded);
                var exact = embedded ? fixture.standalone : fixture.embedded;
                var workers = new QueuedWorkers();
                var repository = fixture.repository(request, ignored -> exact, workers);
                try (var page = repository.openBatch()) {
                    var preview = page.preview(request.content.modelId());
                    var icon = page.presentation(request.content.modelId(),
                        ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR, 0);
                    page.submit();
                    request.transport.preview(0, 0, true, PresentationDelivery.DATA, BAD_MEDIA);
                    request.transport.icon(1, fixture.avatar(request));
                    workers.runNext();
                    try (var image = icon.join().open(); var decoded = image.decodeToBuffer()) {
                        assertTrue(decoded.size() > 0);
                    }
                    if (embedded) {
                        var failure = assertThrows(CompletionException.class, preview::join);
                        assertEquals(AssetLoadException.Reason.CONTENT,
                            assertInstanceOf(AssetLoadException.class, rootCause(failure)).reason());
                        assertFalse(request.session.previewAbsent(request.content.representation().identity()));
                    } else {
                        assertSame(PreviewResult.Absent.INSTANCE, preview.join());
                        assertTrue(request.session.previewAbsent(request.content.representation().identity()));
                    }
                    assertTrue(
                        fixture.previews.load(request.content.representation().identity().containerId()).isEmpty());
                    assertInstanceOf(ActivationSnapshot.Ready.class,
                        request.session.activationSnapshot().orElseThrow().entries().get(request.content.modelId()));
                }
                if (!embedded) {
                    try (var next = repository.openBatch()) {
                        var preview = next.preview(request.content.modelId());
                        next.submit();
                        assertSame(PreviewResult.Absent.INSTANCE, preview.join());
                        assertEquals(1, request.transport.requests());
                    }
                }
            }
        }
    }

    @Test
    void embeddedAbsentIsRejectedBeforeTheSessionRecordsAbsence() throws Exception {
        try (var fixture = new Fixture(temp)) {
            var request = fixture.request(true);
            try (var page = fixture.repository(request, ignored -> fixture.standalone, Runnable::run).openBatch()) {
                var preview = page.preview(request.content.modelId());
                page.submit();
                request.transport.preview(0, 0, true, PresentationDelivery.ABSENT, new byte[0]);
                assertThrows(CompletionException.class, preview::join);
                assertFalse(request.session.previewAbsent(request.content.representation().identity()));
            }
        }
    }

    @Test
    void aTruncatedImageWithAReadableHeaderIsAbsentOnlyAfterFullDecoderRejection() throws Exception {
        try (var fixture = new Fixture(temp)) {
            var truncated = java.util.Arrays.copyOf(previewBytes(), 40);
            try (var image =
                com.elfmcys.ysm.natives.image.Image.probe(com.elfmcys.ysm.buffer.ArrayBuffer.borrow(truncated))) {
                assertEquals(1, image.width());
                assertThrows(java.io.UnsupportedEncodingException.class, image::decodeToBuffer);
            }
            var request = fixture.request(false);
            try (var page = fixture.repository(request, Function.identity(), Runnable::run).openBatch()) {
                var preview = page.preview(request.content.modelId());
                page.submit();
                request.transport.preview(0, 0, true, PresentationDelivery.DATA, truncated);
                assertSame(PreviewResult.Absent.INSTANCE, preview.join());
                assertTrue(request.session.previewAbsent(request.content.representation().identity()));
                assertTrue(fixture.previews.load(request.content.representation().identity().containerId()).isEmpty());
            }
        }
    }

    @Test
    void notificationMemberReplacementAndCloseGateEachPendingResult() throws Exception {
        try (var fixture = new Fixture(temp)) {
            for (var event : List.of("notification", "member", "close")) {
                for (var afterDecode : List.of(false, true)) {
                    var request = fixture.request(false);
                    var workers = new QueuedWorkers();
                    var repository = fixture.repository(request, Function.identity(), workers);
                    try (var page = repository.openBatch()) {
                        var preview = page.preview(request.content.modelId());
                        var blocker = page.preview(request.content.modelId());
                        page.submit();
                        var original =
                            request.session.observePreview(request.content.representation().identity()).orElseThrow();
                        request.transport.preview(0, 0, true, PresentationDelivery.DATA, BAD_MEDIA);
                        request.transport.preview(1, 0, true, PresentationDelivery.DATA, previewBytes());
                        if (afterDecode) {
                            workers.runNext();
                            assertTrue(request.session.previewAbsent(request.content.representation().identity()));
                            assertSame(PreviewResult.Absent.INSTANCE, preview.join());
                        }
                        switch (event) {
                            case "notification" ->
                                request.session.invalidatePreview(request.content.representation().identity());
                            case "member" -> {
                                request.session.publishPublication(
                                    new RemotePublicationSnapshot(List.of(), Set.of(), List.of(), Map.of()));
                                request.session.publishPublication(request.publication);
                            }
                            case "close" -> request.session.close();
                            default -> throw new AssertionError(event);
                        }
                        assertFalse(request.session.previewObservationCurrent(original));
                        while (!workers.tasks.isEmpty()) {
                            workers.runNext();
                        }
                        if (afterDecode)
                            assertSame(PreviewResult.Absent.INSTANCE, preview.join());
                        else
                            assertTrue(preview.isCancelled(), event + " beforeDecode");
                        assertFalse(request.session.previewAbsent(request.content.representation().identity()));
                        assertInstanceOf(PreviewResult.Data.class, blocker.join());
                    }
                }
            }
        }
    }

    @Test
    void oneDecodedImageIsDeliveredWhileAnotherBatchMemberIsPending() throws Exception {
        try (var fixture = new Fixture(temp); var owner = new com.elfmcys.ysm.model.storage.PreviewPixels.Owner()) {
            var request = fixture.request(false);
            var decoding = new ArrayList<CompletableFuture<com.elfmcys.ysm.natives.image.ImageSource>>();
            var repository = new ClientAssetRepository(fixture.catalogs.manager(), request.transport::fetcher,
                Function.identity(), fixture.previews, Runnable::run,
                ignored -> CompletableFuture.failedFuture(new IOException("Unused local preview")),
                ignored -> CompletableFuture.completedFuture(Optional.empty()), () -> request.session,
                (content, bytes) -> {
                    var result = new CompletableFuture<com.elfmcys.ysm.natives.image.ImageSource>();
                    decoding.add(result);
                    return result;
                });
            try (var page = repository.openBatch()) {
                var first = page.preview(request.content.modelId());
                var second = page.preview(request.content.modelId());
                page.submit();
                request.transport.preview(0, 0, true, PresentationDelivery.DATA, previewBytes());
                request.transport.preview(1, 0, true, PresentationDelivery.DATA, previewBytes());
                assertEquals(2, decoding.size());
                try (var pending = TestPreviews.pending()) {
                    decoding.get(0).complete(pending.verify(owner, true));
                    assertTrue(first.isDone(), "A completed image cannot wait for the rest of its pixel cohort");
                    assertFalse(second.isDone());
                    var accepted = (PreviewStore.EncodedPreview)((PreviewResult.Data)first.join()).image();
                    try (var pixels = accepted.takePixels()) {
                        pixels.borrow(bytes -> assertEquals(4, bytes.remaining()));
                    }
                    decoding.get(1).complete(pending.verify(owner, true));
                    assertInstanceOf(PreviewResult.Data.class, second.join());
                }
            }
        }
    }

    @Test
    void incompleteOrOverlappingTransportDoesNotBecomeMediaAbsence() throws Exception {
        try (var fixture = new Fixture(temp)) {
            var overlapping = fixture.request(false);
            var overlappingWorkers = new QueuedWorkers();
            try (var page = fixture.repository(overlapping, Function.identity(), overlappingWorkers).openBatch()) {
                var preview = page.preview(overlapping.content.modelId());
                page.submit();
                overlapping.transport.preview(0, 0, false, PresentationDelivery.DATA, new byte[] {1, 2});
                overlapping.transport.preview(0, 1, true, PresentationDelivery.DATA, new byte[] {3});
                assertThrows(CompletionException.class, preview::join);
                assertTrue(overlappingWorkers.tasks.isEmpty());
                assertFalse(overlapping.session.previewAbsent(overlapping.content.representation().identity()));
            }
            var request = fixture.request(false);
            var workers = new QueuedWorkers();
            try (var page = fixture.repository(request, Function.identity(), workers).openBatch()) {
                var preview = page.preview(request.content.modelId());
                page.submit();
                request.transport.preview(0, 1, true, PresentationDelivery.DATA, BAD_MEDIA);
                assertFalse(preview.isDone());
                assertTrue(workers.tasks.isEmpty());
                page.close();
                assertTrue(preview.isCancelled());
                assertFalse(request.session.previewAbsent(request.content.representation().identity()));
            }
        }
    }

    @Test
    void pageCloseAndWorkerRejectionDoNotCreateAbsence() throws Exception {
        try (var fixture = new Fixture(temp)) {
            var request = fixture.request(false);
            var workers = new QueuedWorkers();
            try (var page = fixture.repository(request, Function.identity(), workers).openBatch()) {
                var preview = page.preview(request.content.modelId());
                page.submit();
                request.transport.preview(0, 0, true, PresentationDelivery.DATA, BAD_MEDIA);
                page.close();
                workers.runNext();
                assertTrue(preview.isCancelled());
                assertFalse(request.session.previewAbsent(request.content.representation().identity()));
            }
            var rejected = fixture.request(false);
            try (var page = fixture.repository(rejected, Function.identity(), command -> {
                throw new java.util.concurrent.RejectedExecutionException("Worker unavailable");
            }).openBatch()) {
                var preview = page.preview(rejected.content.modelId());
                page.submit();
                rejected.transport.preview(0, 0, true, PresentationDelivery.DATA, BAD_MEDIA);
                assertThrows(CompletionException.class, preview::join);
                assertFalse(rejected.session.previewAbsent(rejected.content.representation().identity()));
            }
        }
    }

    @Test
    void remoteValidatedPersistenceInvalidatesServerBytesAndOptionalFailurePreservesThem() throws Exception {
        for (var persist : List.of(true, false)) {
            var owner = new AtomicReference<ServerResourceRuntime>();
            var root = temp.resolve(Boolean.toString(persist));
            try (var fixture = new Fixture(root, id -> owner.get().invalidatePreview(id));
                var runtime = new ServerResourceRuntime(fixture.previews, failure -> fail(failure))) {
                owner.set(runtime);
                var request = fixture.request(false);
                var id = request.content.representation().containerId();
                var directory = Files.createDirectories(root.resolve("previews/previews"));
                Files.write(directory.resolve(id + ".image"), BAD_MEDIA);
                try (var previous = acquire(runtime, id)) {
                    assertArrayEquals(BAD_MEDIA, copy(previous));
                    if (!persist) {
                        Files.move(directory, root.resolve("previous-previews"));
                        Files.writeString(directory, "A file blocks optional persistence");
                    }
                    var expected = previewBytes();
                    try (var page = fixture.repository(request, Function.identity(), Runnable::run).openBatch()) {
                        var preview = page.preview(request.content.modelId());
                        page.submit();
                        request.transport.preview(0, 0, true, PresentationDelivery.DATA, expected);
                        assertInstanceOf(PreviewResult.Data.class, preview.join());
                    }
                    try (var current = acquire(runtime, id)) {
                        assertArrayEquals(persist ? expected : BAD_MEDIA, copy(current));
                        assertArrayEquals(BAD_MEDIA, copy(previous));
                    }
                }
            }
        }
    }

    private static ServerResourceRuntime.BytesLease acquire(ServerResourceRuntime runtime, Hash256 id)
        throws Exception {
        var result = runtime.acquirePreview(id);
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!result.isDone() && System.nanoTime() < deadline) {
            runtime.tick();
            Thread.onSpinWait();
        }
        return result.get(1, TimeUnit.SECONDS).orElseThrow();
    }

    private static byte[] previewBytes() throws IOException {
        try (var preview = TestPreviews.blank()) {
            return preview.bytes();
        }
    }

    private static byte[] copy(ServerResourceRuntime.BytesLease lease) {
        var target = ByteBuffer.allocate(lease.size());
        lease.copyTo(0, target);
        return target.array();
    }

    private static Throwable rootCause(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    private static final class QueuedWorkers implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runNext() {
            tasks.remove().run();
        }
    }

    private record Request(RemoteModelContent content, ClientModelSession session,
        RemotePublicationSnapshot publication, ClientPresentationTransportFixture transport) {
    }

    private static final class Fixture implements AutoCloseable {
        private final ClientCatalogScenarioHarness catalogs;
        private final RemoteModelStore store;
        private final ManagedContainer standalone;
        private final ManagedContainer embedded;
        private final PreviewStore previews;
        private final List<RemoteModelContent> contents = new ArrayList<>();

        private Fixture(Path root) throws Exception {
            this(root, ignored -> {
            });
        }

        private Fixture(Path root, Consumer<Hash256> storedObserver) throws Exception {
            var parsed = parse("/assets/ysm/builtin/misc/1_alex/ysm.json", root.resolve("raw"), false);
            var location = new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("fixture"));
            standalone = ManagedContainer.openDirect(parsed, location);
            assertEquals(PreviewSource.UNSPECIFIED, standalone.modelFile().getThumbnailPreviewSource());
            final Path exported;
            try (var preview = TestPreviews.blank()) {
                exported = ModelExporter.export(standalone, preview, root.resolve("embedded.mxc"), null);
            }
            embedded = ManagedContainer.openDirect(exported, location);
            var intrinsic = parse("/assets/ysm/builtin/default/ysm.json", root.resolve("default"), true);
            catalogs = ClientCatalogScenarioHarness.open(root.resolve("catalog"), intrinsic, List.of());
            catalogs.manager().beginRemote();
            store = new RemoteModelStore(root.resolve("remote"));
            previews = new PreviewStore(root.resolve("previews"), storedObserver);
        }

        private Request request(boolean declaresEmbedded) throws Exception {
            var source = declaresEmbedded ? embedded : standalone;
            final RemoteModelContent content;
            {
                var prefix = source.representation().metadataPrefix().orElseThrow();
                content = store.commitMetadataPrefix(source.representation().identity(), prefix);
            }
            contents.add(content);
            var location = new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("remote/fixture"));
            var record = new CatalogRecord(location, new CatalogContentBinding(content.modelId(), content));
            var entry = new PublicationEntry(content.representation().identity(),
                new HierarchyPath("local:custom/remote/fixture"), CatalogAccess.PUBLIC, true);
            var publication = new RemotePublicationSnapshot(List.of(entry), Set.of(), List.of(), Map.of());
            var activation = new ActivationSnapshot(publication,
                Map.of(content.modelId(), new ActivationSnapshot.Ready(entry, record)));
            catalogs.manager().publishSession(activation);
            var session = new ClientModelSession(SessionMode.AUTO, true, CatalogSnapshot.empty());
            session.onServerHello(ProtocolVersion.CURRENT, false);
            session.publishFull(publication);
            session.publishActivation(activation);
            return new Request(content, session, publication, new ClientPresentationTransportFixture(session));
        }

        private ClientAssetRepository repository(Request request, Function<ModelContent, ModelContent> exact,
            Executor workers) {
            return new ClientAssetRepository(catalogs.manager(), request.transport::fetcher, exact, previews, workers,
                ignored -> CompletableFuture.failedFuture(new IOException("Unused local preview")),
                ignored -> CompletableFuture.completedFuture(Optional.empty()), () -> request.session);
        }

        private byte[] avatar(Request request) throws IOException {
            var view = request.content.representation().view();
            var image = view.getManifest().info().metadataUnsafe().authors().get(0).avatarUnsafe();
            var chunk = view.getFileView().blobChunkInfo(image.blobId());
            var source = view.getThumbnailPreviewSource() == PreviewSource.UNSPECIFIED ? standalone : embedded;
            {
                var bytes = source.chunks().readStoredVerified(chunk, BufferType.ARRAY);
                try (var array = bytes.copyArray()) {
                    return java.util.Arrays.copyOfRange(array.array(), array.arrayOffset(),
                        array.arrayOffset() + array.size());

                }
            }
        }

        @Override
        public void close() {
            contents.forEach(content -> content.representation().close());
            store.close();
            catalogs.close();
            embedded.representation().close();
            standalone.representation().close();
        }

        private static Path parse(String resource, Path output, boolean builtin) throws Exception {
            var source = Path.of(ClientRemotePreviewRepositoryTest.class.getResource(resource).toURI()).getParent();
            Files.createDirectories(output);
            try (var vfs = new Directory(source)) {
                return builtin ? ModelParser.parseBuiltinDefault(vfs, output)
                    : ModelParser.parse(vfs, output, DefaultAnimationFilter.keepAll());
            }
        }
    }
}
