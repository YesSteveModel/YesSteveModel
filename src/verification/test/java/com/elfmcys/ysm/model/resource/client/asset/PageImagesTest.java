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
    private static Path richModel;
    private static Path richReplacement;
    private static Hash256 richModelId;

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
        var richSource = parse("/assets/ysm/builtin/wine_fox/22_elf/ysm.json", root.resolve("rich"), false);
        var richContent = ManagedContainer.openDirect(richSource,
            new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("rich")));
        richModelId = richContent.modelId();
        try (var preview = TestPreviews.blank()) {
            richModel = ModelExporter.export(richContent, preview, root.resolve("rich.mxc"), null);
            var raw = java.nio.ByteBuffer.allocate(16 * 16 * 4);
            while (raw.hasRemaining())
                raw.putInt(0x112141FF);
            var rgba = new com.elfmcys.ysm.natives.image.Image(com.elfmcys.ysm.natives.image.Image.Format.RGBA,
                16, 16, com.elfmcys.ysm.buffer.SharedArrayBuffer.copyOf(
                    com.elfmcys.ysm.buffer.ArrayBuffer.borrow(raw.array())));
            try (var pending = com.elfmcys.ysm.model.storage.PreviewStore.pending(rgba);
                 var replacement = pending.verify(null, false)) {
                richReplacement = ModelExporter.export(richContent, replacement, root.resolve("rich-replacement.mxc"), null);
            }
        } finally {
            richContent.representation().close();
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

    @Test
    void pageSlotsShareResidentTextureAndReadyLookupDoesNotRestartTheSupplier() throws Exception {
        try (var fixture = new Fixture(temp, false)) {
            var images = new ResidentImagesTest.Fixture();
            var repository = fixture.repository();
            var first = new PageImages(new ClientAssetBatch(repository.openBatch()), images.owner);
            var second = new PageImages(new ClientAssetBatch(repository.openBatch()), images.owner);
            var a = first.preview(fixture.id);
            var b = second.preview(fixture.id);
            try (var encoded = TestPreviews.blank()) {
                fixture.local.complete(encoded);
                first.advance(ModelResourceDemand.Mode.OFFLINE);
                assertNotNull(a.texture());
                second.advance(ModelResourceDemand.Mode.READY);
                assertNotNull(b.texture());
                assertEquals(a.texture().id(), b.texture().id());
                assertEquals(1, fixture.localCalls);
                assertEquals(1, images.host.constructions);
                first.close();
                images.host.tick();
                images.host.tick();
                assertNotNull(b.texture());
                second.close();
                images.host.tick();
                images.host.tick();
                assertEquals(1, images.host.releases);
            } finally {
                first.close();
                second.close();
            }
        }
    }

    @Test
    void cancellationFailureStillClosesEveryBatchInputAndPageUse() throws Exception {
        try (var fixture = new Fixture(temp, false); var encoded = TestPreviews.blank()) {
            var images = new ResidentImagesTest.Fixture();
            var page = new PageImages(new ClientAssetBatch(fixture.repository().openBatch()), images.owner);
            var first = page.preview(fixture.id);
            var second = page.preview(fixture.id);
            fixture.local.complete(encoded);
            page.advance(ModelResourceDemand.Mode.OFFLINE);
            assertNotNull(first.texture());
            assertNotNull(second.texture());
            var failure = new IllegalStateException("cancel failed");
            fixture.cancellationFailure = failure;
            assertSame(failure, assertThrows(IllegalStateException.class, page::close));
            assertClosed(first);
            assertClosed(second);
            assertThrows(IllegalStateException.class, encoded::bytes);
            images.host.tick();
            images.host.tick();
            assertEquals(1, images.host.releases);
            page.close();
        }
    }

    @Test
    void cpuInputWaitsForAnExplicitClaimAndLatePreparedResultCannotReviveThePage() throws Exception {
        try (var fixture = new Fixture(temp, false)) {
            var images = new ResidentImagesTest.Fixture();
            var page = new PageImages(new ClientAssetBatch(fixture.repository().openBatch()), images.owner);
            var slot = page.preview(fixture.id);
            fixture.local.complete(images.source());
            page.advanceAcquisition(ModelResourceDemand.Mode.OFFLINE);
            assertTrue(slot.cpuReady());
            assertEquals(0, images.reads);
            assertEquals(0, images.decodes);
            try (var preparation = slot.claimPreparation()) {
                var prepared = preparation.prepare();
                page.close();
                slot.publish(prepared);
                assertEquals(0, images.host.registrations);
                assertClosed(slot);
            }
        }
    }

    @Test
    void explicitPublicationFailureStopsTheSlotUntilItsPageEnds() throws Exception {
        for (boolean upload : new boolean[]{false, true}) {
            try (var fixture = new Fixture(temp.resolve("publication-" + upload), false)) {
                var images = new ResidentImagesTest.Fixture();
                images.host.reject = !upload;
                images.host.failUpload = upload;
                try (var page = new PageImages(new ClientAssetBatch(fixture.repository().openBatch()), images.owner)) {
                    var slot = page.preview(fixture.id);
                    fixture.local.complete(images.source());
                    page.advanceAcquisition(ModelResourceDemand.Mode.OFFLINE);
                    try (var preparation = slot.claimPreparation()) {
                        var failure = assertThrows(RuntimeException.class, () -> slot.publish(preparation.prepare()));
                        assertSame(failure, slot.failure());
                    }
                    assertFalse(slot.loading());
                    assertFalse(slot.cpuReady());
                    assertNull(slot.texture());
                    var counts = images.counts();
                    images.host.reject = false;
                    images.host.failUpload = false;
                    for (int frame = 0; frame < 3; frame++) {
                        page.advanceAcquisition(ModelResourceDemand.Mode.ONLINE);
                        page.refreshResidents();
                        assertNull(slot.claimPreparation());
                    }
                    assertArrayEquals(counts, images.counts());
                }
                images.host.tick();
                images.host.tick();
                assertEquals(upload ? 1 : 0, images.host.releases);
            }
        }
    }

    @Test
    void independentVersionAndMemberIncarnationRemainSeparateFromTheContentProof() throws Exception {
        try (var fixture = new Fixture(temp, true)) {
            var store = new com.elfmcys.ysm.model.storage.PreviewStore(temp.resolve("previews"));
            var repository = fixture.repository(store);
            try (var oldBatch = repository.openBatch()) {
                var old = oldBatch.imageRequest(fixture.id, ModelAssetSelector.preview());
                var firstIdentity = old.identity();
                try (var encoded = TestPreviews.blank()) {
                    store.accept(fixture.remote.representation().containerId(), encoded);
                    assertFalse(old.current(null));
                    try (var batch = repository.openBatch()) {
                        var current = batch.imageRequest(fixture.id, ModelAssetSelector.preview());
                        assertNotEquals(firstIdentity, current.identity());
                        assertTrue(current.current(null));
                        assertTrue(old.current(encoded), "Only the operation's qualified replacement can rebase its version");
                        fixture.catalogs.manager().endRemote();
                        assertFalse(old.allowed());
                        assertFalse(current.allowed());
                        fixture.publishRemote();
                        assertFalse(old.allowed());
                        try (var replacement = repository.openBatch()) {
                            assertTrue(replacement.imageRequest(fixture.id, ModelAssetSelector.preview()).allowed());
                        }
                    }
                }
            }
        }
    }

    @Test
    void unpersistedPreviewRemainsUsableWithoutAliasingThePersistentLookupKey() throws Exception {
        try (var fixture = new Fixture(temp, true)) {
            var root = temp.resolve("refused-preview-store");
            var store = new com.elfmcys.ysm.model.storage.PreviewStore(root,
                new com.elfmcys.ysm.model.storage.AtomicSharedCache(root, replacement -> {
                    throw new java.io.IOException("replacement refused");
                }), ignored -> {});
            var repository = fixture.repository(store);
            try (var batch = repository.openBatch(); var encoded = TestPreviews.blank()) {
                var request = batch.imageRequest(fixture.id, ModelAssetSelector.preview());
                store.accept(fixture.remote.representation().containerId(), encoded);
                assertTrue(request.current(encoded));
                assertNotEquals(request.identity(), request.identity(encoded));
                var images = new ResidentImagesTest.Fixture();
                try (var preparation = images.owner.claim(request, encoded)) {
                    images.owner.publish(preparation.prepare());
                }
                assertNull(images.owner.lookup(request));
                var delivered = new ImageRequest(ignored -> request.identity(encoded), request::allowed,
                    ignored -> request.current(encoded), () -> CompletableFuture.completedFuture(new PreviewResult.Data(encoded)));
                try (var use = images.owner.lookup(delivered)) {
                    assertNotNull(use.texture());
                }
            }
        }
    }

    @Test
    void declaredImagesKeepTheirMemberIndexAndDoNotFollowIndependentReplacements() throws Exception {
        try (var fixture = new Fixture(temp, false)) {
            var store = new com.elfmcys.ysm.model.storage.PreviewStore(temp.resolve("previews"));
            var repository = fixture.repository(store);
            try (var batch = repository.openBatch()) {
                var preview = batch.imageRequest(fixture.id, ModelAssetSelector.preview());
                var identity = preview.identity();
                var zero = batch.imageRequest(fixture.id,
                    ModelAssetSelector.presentation(ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR, 0));
                var one = batch.imageRequest(fixture.id,
                    ModelAssetSelector.presentation(ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR, 1));
                assertNotEquals(zero.identity(), one.identity());
                var content = fixture.catalogs.manager().snapshot().find(fixture.id).orElseThrow().content();
                try (var encoded = TestPreviews.blank()) {
                    store.accept(content.representation().containerId(), encoded);
                    assertTrue(preview.current(null));
                    assertEquals(identity, preview.identity());
                }
            }
        }
    }

    @Test
    void notificationAndConnectionReplacementRevokeOnlyTheOriginalObservation() throws Exception {
        try (var fixture = new Fixture(temp, true)) {
            var record = fixture.catalogs.manager().snapshot().catalog().byModelId().get(fixture.id);
            var member = new PublicationEntry(fixture.remote.representation().identity(), record.entry().path(),
                CatalogAccess.PUBLIC, true);
            var publication = new RemotePublicationSnapshot(List.of(member), Set.of(), List.of(fixture.pack), Map.of());
            var activation = new ActivationSnapshot(publication,
                Map.of(fixture.id, new ActivationSnapshot.Ready(member, record)));
            var session = new com.elfmcys.ysm.model.session.client.ClientModelSession(
                com.elfmcys.ysm.network.session.SessionMode.AUTO, true,
                com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot.empty());
            session.onServerHello(com.elfmcys.ysm.network.protocol.ProtocolVersion.CURRENT, false);
            session.publishFull(publication);
            session.publishActivation(activation);
            var currentSession = new java.util.concurrent.atomic.AtomicReference<>(session);
            var repository = fixture.repository(null, currentSession::get);
            try (var firstBatch = repository.openBatch()) {
                var first = firstBatch.imageRequest(fixture.id, ModelAssetSelector.preview());
                assertTrue(first.current(null));
                var declared = firstBatch.imageRequest(fixture.id,
                    ModelAssetSelector.presentation(ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR, 0));
                session.invalidatePreview(member.identity());
                assertTrue(declared.allowed());
                assertTrue(first.allowed());
                assertFalse(first.current(null));
                try (var nextBatch = repository.openBatch()) {
                    var next = nextBatch.imageRequest(fixture.id, ModelAssetSelector.preview());
                    assertTrue(next.current(null));
                    assertNotEquals(first.identity(), next.identity());
                    session.publishPublication(new RemotePublicationSnapshot(List.of(), Set.of(), List.of(), Map.of()));
                    assertFalse(declared.allowed());
                    session.publishPublication(publication);
                    assertFalse(declared.allowed());
                    assertFalse(next.allowed());
                    currentSession.set(new com.elfmcys.ysm.model.session.client.ClientModelSession(
                        com.elfmcys.ysm.network.session.SessionMode.LOCAL, false,
                        com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot.empty()));
                    assertFalse(next.allowed());
                    assertFalse(first.allowed());
                }
            } finally {
                session.close();
            }
        }
    }

    @Test
    void displayImagesRemainAvailableWhenRuntimeContentIsDenied() throws Exception {
        try (var fixture = new Fixture(temp, true); var batch = fixture.repository().openBatch()) {
            var request = batch.imageRequest(fixture.id,
                ModelAssetSelector.presentation(ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR, 0));
            var identity = request.identity();
            var record = fixture.catalogs.manager().snapshot().catalog().byModelId().get(fixture.id);
            var denied = new PublicationEntry(fixture.remote.representation().identity(), record.entry().path(),
                CatalogAccess.PUBLIC, false);
            var publication = new RemotePublicationSnapshot(List.of(denied), Set.of(), List.of(fixture.pack), Map.of());
            fixture.catalogs.manager().publishSession(new ActivationSnapshot(publication,
                Map.of(fixture.id, new ActivationSnapshot.Ready(denied, record))));
            assertTrue(request.allowed());
            assertEquals(identity, request.identity());
            fixture.publishRemote();
            assertTrue(request.allowed());
        }
    }

    @Test
    void realFourImageSourcesHitThroughRepositoryBeforeReadDecodeConstructionAndHostWork() throws Exception {
        try (var catalogs = ClientCatalogScenarioHarness.open(temp.resolve("rich-catalog"), intrinsic, List.of(richModel))) {
            var reads = new AtomicInteger();
            var downloads = new AtomicInteger();
            var exactCalls = new AtomicInteger();
            java.util.function.Function<com.elfmcys.ysm.model.catalog.content.ModelContent,
                com.elfmcys.ysm.model.catalog.content.ModelContent> exact = source -> {
                exactCalls.incrementAndGet();
                return new com.elfmcys.ysm.model.catalog.content.ModelContent() {
                    public com.elfmcys.ysm.model.domain.ModelRepresentation representation() { return source.representation(); }
                    public com.elfmcys.ysm.format.schema.file.ChunkDataSource chunks() {
                        return new com.elfmcys.ysm.format.schema.file.ChunkDataSource() {
                            public com.elfmcys.ysm.buffer.SharedBuffer readPayload(
                                com.elfmcys.ysm.format.container.AssetContainerView.ChunkInfo chunk,
                                com.elfmcys.ysm.buffer.BufferType type) throws java.io.IOException {
                                reads.incrementAndGet();
                                return source.chunks().readPayload(chunk, type);
                            }
                            public com.elfmcys.ysm.buffer.SharedBuffer readStoredVerified(
                                com.elfmcys.ysm.format.container.AssetContainerView.ChunkInfo chunk,
                                com.elfmcys.ysm.buffer.BufferType type) throws java.io.IOException {
                                reads.incrementAndGet();
                                return source.chunks().readStoredVerified(chunk, type);
                            }
                        };
                    }
                };
            };
            var repository = new ClientAssetRepository(catalogs.manager(), () -> (members, receiver) -> {
                downloads.incrementAndGet();
                return CompletableFuture.failedFuture(new AssertionError("Local sources cannot download"));
            }, exact, null, Runnable::run, source -> {
                try {
                    return CompletableFuture.completedFuture(source.modelFile().thumbnailSource(() -> false, source.chunks()));
                } catch (java.io.IOException failure) {
                    return CompletableFuture.failedFuture(failure);
                }
            }, source -> CompletableFuture.completedFuture(Optional.empty()), () -> null);
            var id = richModelId;
            assertTrue(catalogs.manager().contains(id));
            var images = new ResidentImagesTest.Fixture();
            var codec = Boolean.getBoolean("ysm.previewDecodeProbe");
            if (codec) com.elfmcys.ysm.model.storage.PreviewDecodeProbe.reset();
            var first = new PageImages(new ClientAssetBatch(repository.openBatch()), images.owner);
            var second = new PageImages(new ClientAssetBatch(repository.openBatch()), images.owner);
            try (first; second) {
                var old = fourImages(first, id);
                first.advanceAcquisition(ModelResourceDemand.Mode.OFFLINE);
                assertEquals(0, reads.get());
                for (var image : old) {
                    assertTrue(image.cpuReady(), () -> "Image was not ready: " + image.failure());
                    try (var preparation = image.claimPreparation()) {
                        image.publish(preparation.prepare());
                    }
                }
                assertEquals(4, reads.get());
                assertEquals(4, images.decodes);
                if (codec) assertEquals(4, com.elfmcys.ysm.model.storage.PreviewDecodeProbe.count());
                var before = new int[]{reads.get(), downloads.get(), images.decodes, images.host.constructions,
                    images.host.registrations, images.host.uploads, exactCalls.get()};
                var fresh = fourImages(second, id);
                first.close();
                second.advance(ModelResourceDemand.Mode.READY);
                for (var image : fresh) assertNotNull(image.texture());
                assertArrayEquals(before, new int[]{reads.get(), downloads.get(), images.decodes, images.host.constructions,
                    images.host.registrations, images.host.uploads, exactCalls.get()});
                if (codec) assertEquals(4, com.elfmcys.ysm.model.storage.PreviewDecodeProbe.count());
            }
            images.host.tick();
            images.host.tick();
            assertEquals(4, images.host.releases);
        }
    }

    @Test
    void samePathRepresentationReplacementRevokesOldAccessButPreservesProvedImageContent() throws Exception {
        try (var catalogs = ClientCatalogScenarioHarness.open(temp.resolve("replaced-path"), intrinsic, List.of(richModel))) {
            var repository = new ClientAssetRepository(catalogs.manager(), () -> (members, receiver) -> {
                throw new AssertionError("Identity inspection cannot download");
            }, java.util.function.Function.identity(), null, Runnable::run,
                source -> { throw new AssertionError("Identity inspection cannot load"); },
                source -> { throw new AssertionError("Identity inspection cannot probe"); }, () -> null);
            try (var before = repository.openBatch()) {
                var old = before.imageRequest(richModelId,
                    ModelAssetSelector.presentation(ModelAssetSelector.PresentationAsset.GUI_FOREGROUND, 0));
                var oldPreview = before.imageRequest(richModelId, ModelAssetSelector.preview());
                catalogs.addSource(richReplacement, "initial-0.mxc");
                catalogs.reload().join();
                assertEquals(0, catalogs.manager().localState().snapshot().report().errorCount());
                assertFalse(old.allowed());
                assertFalse(oldPreview.allowed());
                try (var after = repository.openBatch()) {
                    var replacement = after.imageRequest(richModelId,
                        ModelAssetSelector.presentation(ModelAssetSelector.PresentationAsset.GUI_FOREGROUND, 0));
                    assertTrue(replacement.allowed());
                    assertEquals(old.identity(), replacement.identity());
                    assertNotEquals(oldPreview.identity(), after.imageRequest(richModelId, ModelAssetSelector.preview()).identity());
                }
            }
        }
    }

    @Test
    void catalogReloadInvalidatesIndependentVersionsWithoutGrantingOrRevokingDeclaredAccess() throws Exception {
        try (var fixture = new Fixture(temp, true); var batch = fixture.repository().openBatch()) {
            var independent = batch.imageRequest(fixture.id, ModelAssetSelector.preview());
            var declared = batch.imageRequest(fixture.id,
                ModelAssetSelector.presentation(ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR, 0));
            var identity = independent.identity();
            fixture.catalogs.reload().join();
            assertFalse(independent.current(null));
            assertTrue(declared.allowed());
            try (var replacement = fixture.repository().openBatch()) {
                assertNotEquals(identity, replacement.imageRequest(fixture.id, ModelAssetSelector.preview()).identity());
            }
        }
    }

    private static List<PageImages.Image> fourImages(PageImages page, Hash256 id) {
        return List.of(page.preview(id),
            page.presentation(id, ModelAssetSelector.PresentationAsset.GUI_FOREGROUND, 0),
            page.presentation(id, ModelAssetSelector.PresentationAsset.GUI_BACKGROUND, 0),
            page.presentation(id, ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR, 0));
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
        private RuntimeException cancellationFailure;
        private final CompletableFuture<ImageSource> local = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                if (cancellationFailure != null)
                    throw cancellationFailure;
                return super.cancel(mayInterruptIfRunning);
            }
        };
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
            return repository(null);
        }

        private void publishRemote() {
            var location = new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("remote/model"));
            var record = new CatalogRecord(location, new CatalogContentBinding(id, remote));
            var publication = new PublicationEntry(remote.representation().identity(), record.entry().path(),
                CatalogAccess.PUBLIC, true);
            var snapshot = new RemotePublicationSnapshot(List.of(publication), Set.of(), List.of(pack), Map.of());
            catalogs.manager().publishSession(
                new ActivationSnapshot(snapshot, Map.of(id, new ActivationSnapshot.Ready(publication, record))));
        }

        private ClientAssetRepository repository(com.elfmcys.ysm.model.storage.PreviewStore previewStore) {
            return repository(previewStore, () -> null);
        }

        private ClientAssetRepository repository(com.elfmcys.ysm.model.storage.PreviewStore previewStore,
            java.util.function.Supplier<com.elfmcys.ysm.model.session.client.ClientModelSession> sessions) {
            return new ClientAssetRepository(catalogs.manager(), () -> (items, publisher) -> {
                transfers++;
                members = List.copyOf(items);
                receiver = publisher;
                return transfer;
            }, content -> {
                exactCalls++;
                return content;
            }, previewStore, Runnable::run, content -> {
                localCalls++;
                return local;
            }, content -> {
                probeCalls++;
                return probe;
            }, sessions);
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
