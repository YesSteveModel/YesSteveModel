package com.elfmcys.ysm.model.resource.client.asset;

import com.elfmcys.ysm.client.demand.ModelResourceDemand;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.ClientCatalogScenarioHarness;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelContent;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelStore;
import com.elfmcys.ysm.model.resource.client.remote.RemotePresentationFetcher;
import com.elfmcys.ysm.model.session.client.state.ActivationSnapshot;
import com.elfmcys.ysm.model.session.client.state.PublicationEntry;
import com.elfmcys.ysm.model.session.client.state.RemotePublicationSnapshot;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelExporter;
import com.elfmcys.ysm.model.storage.TestPreviews;
import com.elfmcys.ysm.natives.image.ImageSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PageImagesTest {
    private static Path intrinsic;
    private static Path model;
    private static Path localModel;

    @TempDir
    Path temp;

    @BeforeAll
    static void prepareModels(@TempDir Path root) throws Exception {
        intrinsic = parse("/assets/ysm/builtin/default/ysm.json", root.resolve("default"), true);
        model = parse("/assets/ysm/builtin/misc/1_alex/ysm.json", root.resolve("model"), false);
        var content =
            ManagedContainer.openDirect(model, new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("source")));
        try (var preview = TestPreviews.blank()) {
            localModel = ModelExporter.export(content, preview, root.resolve("local.mxc"), null);
        } finally {
            content.representation().close();
        }
    }

    @Test
    void readyAndProjectionReadsDoNotStartAnyImageSupplier() throws Exception {
        try (var fixture = new Fixture(temp, false); var page = fixture.page()) {
            var preview = page.preview(fixture.id);
            var avatar = page.presentation(fixture.id, ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR, 99);
            var cover = page.packCover(fixture.pack);

            for (var image : List.of(preview, avatar, cover)) {
                assertTrue(image.loading());
                assertFalse(image.absent());
                assertNull(image.texture());
                assertNull(image.failure());
            }
            page.advance(ModelResourceDemand.Mode.READY);
            page.advance(ModelResourceDemand.Mode.READY);

            assertEquals(0, fixture.exactCalls);
            assertEquals(0, fixture.localCalls);
            assertEquals(0, fixture.probeCalls);
            assertEquals(0, fixture.transfers);
        }
    }

    @Test
    void localAndCachedAdmissionStartSuppliersOnceWithoutSubmitting() throws Exception {
        for (var mode : List.of(ModelResourceDemand.Mode.OFFLINE, ModelResourceDemand.Mode.CACHED)) {
            try (var fixture = new Fixture(temp.resolve(mode.name()), false); var page = fixture.page()) {
                var preview = page.preview(fixture.id);
                var avatar = page.presentation(fixture.id, ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR, 99);
                var cover = page.packCover(fixture.pack);

                page.advance(mode);
                page.advance(mode);
                page.advance(ModelResourceDemand.Mode.READY);

                assertEquals(2, fixture.exactCalls);
                assertEquals(1, fixture.localCalls);
                assertEquals(0, fixture.transfers);
                assertTrue(preview.loading());
                assertFalse(avatar.loading());
                assertNotNull(avatar.failure());
                assertTrue(cover.loading());
            }
        }
    }

    @Test
    void onlineRegistersEverySlotBeforeSubmittingExactlyOneBatch() throws Exception {
        try (var fixture = new Fixture(temp, true); var page = fixture.page()) {
            var preview = page.preview(fixture.id);
            var avatar = page.presentation(fixture.id, ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR, 99);
            var second = page.preview(fixture.id);
            var cover = page.packCover(fixture.pack);
            fixture.probe.complete(Optional.empty());

            page.advance(ModelResourceDemand.Mode.ONLINE);
            page.advance(ModelResourceDemand.Mode.ONLINE);

            assertEquals(3, fixture.exactCalls);
            assertEquals(2, fixture.probeCalls);
            assertEquals(1, fixture.transfers);
            assertEquals(List.of(0, 2, 3),
                fixture.members.stream().map(RemotePresentationFetcher.Member::slot).toList());
            assertInstanceOf(RemotePresentationFetcher.Preview.class, fixture.members.get(0));
            assertInstanceOf(RemotePresentationFetcher.Preview.class, fixture.members.get(1));
            assertInstanceOf(RemotePresentationFetcher.PackCover.class, fixture.members.get(2));
            assertNotNull(avatar.failure());
            assertTrue(preview.loading());
            assertTrue(second.loading());
            assertTrue(cover.loading());
            assertThrows(IllegalStateException.class, () -> page.preview(fixture.id));
        }
    }

    @Test
    void submittedBatchWaitsForProbeAndStartsMissingTransferWithoutAnotherAdvance() throws Exception {
        try (var fixture = new Fixture(temp, true); var page = fixture.page()) {
            var image = page.preview(fixture.id);
            page.advance(ModelResourceDemand.Mode.OFFLINE);
            assertEquals(1, fixture.probeCalls);
            assertEquals(0, fixture.transfers);

            page.advance(ModelResourceDemand.Mode.ONLINE);
            page.advance(ModelResourceDemand.Mode.ONLINE);
            assertEquals(0, fixture.transfers);
            assertTrue(image.loading());
            fixture.probe.complete(Optional.empty());

            assertEquals(1, fixture.transfers);
            fixture.receiver.accept(fixture.members.get(0), new RemotePresentationFetcher.Absent(() -> true));
            fixture.transfer.complete(null);
            assertTrue(image.loading());
            page.advance(ModelResourceDemand.Mode.READY);

            assertFalse(image.loading());
            assertTrue(image.absent());
            assertNull(image.failure());
            assertNull(image.texture());
            assertEquals(1, fixture.probeCalls);
            assertEquals(1, fixture.transfers);
        }
    }

    @Test
    void repositoryPublishesALocalProbeHitBeforeNetworkAdmission() throws Exception {
        try (var fixture = new Fixture(temp, true); var batch = fixture.repository().openBatch()) {
            var result = batch.preview(fixture.id);
            ImageSource hit = () -> {
                throw new AssertionError("Probe observation must not decode the image");
            };
            fixture.probe.complete(Optional.of(hit));

            assertSame(hit, assertInstanceOf(PreviewResult.Data.class, result.join()).image());
            assertFalse(batch.hasRemoteRequests());
            assertEquals(1, fixture.probeCalls);
            assertEquals(0, fixture.transfers);
            batch.submit();
            assertEquals(0, fixture.transfers);
        }
    }

    @Test
    void closeBeforeAdmissionIsIdempotentAndPreventsEverySupplier() throws Exception {
        try (var fixture = new Fixture(temp, false)) {
            var page = fixture.page();
            var preview = page.preview(fixture.id);
            page.presentation(fixture.id, ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR, 99);
            page.packCover(fixture.pack);

            page.close();
            page.close();
            page.advance(ModelResourceDemand.Mode.ONLINE);

            assertClosed(preview);
            assertEquals(0, fixture.exactCalls);
            assertEquals(0, fixture.localCalls);
            assertEquals(0, fixture.transfers);
            assertThrows(IllegalStateException.class, () -> page.preview(fixture.id));
        }
    }

    @Test
    void closeDuringLocalWorkDoesNotPublishALateResult() throws Exception {
        try (var fixture = new Fixture(temp, false); var page = fixture.page()) {
            var image = page.preview(fixture.id);
            page.advance(ModelResourceDemand.Mode.OFFLINE);
            page.close();
            fixture.local.complete(() -> {
                throw new AssertionError("A closed page must not decode a late image");
            });
            page.advance(ModelResourceDemand.Mode.ONLINE);

            assertClosed(image);
            assertEquals(1, fixture.localCalls);
            assertEquals(0, fixture.transfers);
        }
    }

    @Test
    void closeAfterSubmissionPreventsALateProbeMissFromStartingTransfer() throws Exception {
        try (var fixture = new Fixture(temp, true); var page = fixture.page()) {
            var image = page.preview(fixture.id);
            page.advance(ModelResourceDemand.Mode.ONLINE);
            assertEquals(1, fixture.probeCalls);
            assertEquals(0, fixture.transfers);

            page.close();
            fixture.probe.complete(Optional.empty());
            page.advance(ModelResourceDemand.Mode.ONLINE);

            assertClosed(image);
            assertEquals(0, fixture.transfers);
        }
    }

    @Test
    void closeDuringTransferCancelsOnceAndCannotPublishLateAbsence() throws Exception {
        try (var fixture = new Fixture(temp, true); var page = fixture.page()) {
            var image = page.preview(fixture.id);
            fixture.probe.complete(Optional.empty());
            page.advance(ModelResourceDemand.Mode.ONLINE);
            var cancellations = new AtomicInteger();
            fixture.transfer.whenComplete((ignored, failure) -> {
                if (fixture.transfer.isCancelled())
                    cancellations.incrementAndGet();
            });

            page.close();
            page.close();
            fixture.receiver.accept(fixture.members.get(0), new RemotePresentationFetcher.Absent(() -> true));
            page.advance(ModelResourceDemand.Mode.ONLINE);

            assertEquals(1, cancellations.get());
            assertClosed(image);
            assertEquals(1, fixture.transfers);
        }
    }

    private static void assertClosed(PageImages.Image image) {
        assertFalse(image.loading());
        assertFalse(image.absent());
        assertNull(image.texture());
        assertNull(image.failure());
    }

    private static Path parse(String resource, Path output, boolean builtin) throws Exception {
        var source = Path.of(PageImagesTest.class.getResource(resource).toURI()).getParent();
        Files.createDirectories(output);
        try (var vfs = new Directory(source)) {
            return builtin ? ModelParser.parseBuiltinDefault(vfs, output)
                : ModelParser.parse(vfs, output, DefaultAnimationFilter.keepAll());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final ClientCatalogScenarioHarness catalogs;
        private final RemoteModelStore store;
        private final RemoteModelContent remote;
        private final Hash256 id;
        private final ModelPackDescriptor pack;
        private final CompletableFuture<ImageSource> local = new CompletableFuture<>();
        private final CompletableFuture<Optional<ImageSource>> probe = new CompletableFuture<>();
        private final CompletableFuture<Void> transfer = new CompletableFuture<>();
        private List<RemotePresentationFetcher.Member> members;
        private RemotePresentationFetcher.PresentationReceiver receiver;
        private int exactCalls;
        private int localCalls;
        private int probeCalls;
        private int transfers;

        private Fixture(Path root, boolean useRemote) throws Exception {
            catalogs = ClientCatalogScenarioHarness.open(root.resolve("catalog"), intrinsic,
                useRemote ? List.of() : List.of(localModel));
            store = new RemoteModelStore(root.resolve("remote"));
            var source = ManagedContainer.openDirect(model,
                new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("source")));
            try {
                id = source.modelId();
                if (useRemote) {
                    {
                        var prefix = source.representation().metadataPrefix().orElseThrow();
                        remote = store.commitMetadataPrefix(source.representation().identity(), prefix);
                    }
                } else {
                    remote = null;
                }
            } finally {
                source.representation().close();
            }
            pack =
                new ModelPackDescriptor(CatalogRootKind.CUSTOM, "local:custom/remote", "", "", Map.of(), id, "PNG", 12);
            if (useRemote) {
                var location = new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("remote/model"));
                var record = new CatalogRecord(location, new CatalogContentBinding(id, remote));
                var publication = new PublicationEntry(remote.representation().identity(), record.entry().path(),
                    CatalogAccess.PUBLIC, true);
                var snapshot = new RemotePublicationSnapshot(List.of(publication), Set.of(), List.of(pack), Map.of());
                catalogs.manager().beginRemote();
                catalogs.manager().publishSession(
                    new ActivationSnapshot(snapshot, Map.of(id, new ActivationSnapshot.Ready(publication, record))));
            }
            assertTrue(catalogs.manager().contains(id), "Fixture model must be admitted before creating a batch");
        }

        private ClientAssetRepository repository() {
            return new ClientAssetRepository(catalogs.manager(), () -> (items, publisher) -> {
                transfers++;
                members = List.copyOf(items);
                receiver = publisher;
                return transfer;
            }, content -> {
                exactCalls++;
                return content;
            }, null, Runnable::run, content -> {
                localCalls++;
                return local;
            }, content -> {
                probeCalls++;
                return probe;
            }, () -> null);
        }

        private PageImages page() {
            return new PageImages(new ClientAssetBatch(repository().openBatch()));
        }

        @Override
        public void close() {
            if (remote != null)
                remote.representation().close();
            store.close();
            catalogs.close();
        }
    }
}
