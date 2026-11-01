package com.elfmcys.ysm.model.resource.client.asset;

import com.elfmcys.ysm.client.demand.ModelResourceDemand;
import com.elfmcys.ysm.client.gui.button.CatalogCardImages;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.ClientCatalogScenarioHarness;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
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
import com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production card image policy and repository; the host consumes uploads without GL. */
class CatalogCardImagesTest {
    private static Path intrinsic;
    private static Path sourceModel;
    private static Path model;

    @TempDir
    Path temp;

    @BeforeAll
    static void prepareModels(@TempDir Path root) throws Exception {
        intrinsic = parse("/assets/ysm/builtin/default/ysm.json", root.resolve("default"), true);
        sourceModel = parse("/assets/ysm/builtin/wine_fox/22_elf/ysm.json", root.resolve("source"), false);
        var content = ManagedContainer.openDirect(sourceModel,
            new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("source")));
        try (var preview = TestPreviews.blank()) {
            model = ModelExporter.export(content, preview, root.resolve("model.mxc"), null);
        } finally {
            content.representation().close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "ready", "failed", "remote-missing", "resident", "builtin-resident"})
    void initialEagerNeverCreatesPreviewDemandOrConsumesAnExistingResult(String sourceState) throws Exception {
        try (var f = new Fixture(temp, sourceState.equals("remote-missing"), sourceState.equals("builtin-resident"));
            var decorations = f.page(); var previews = f.page()) {
            if (sourceState.equals("ready") || sourceState.endsWith("resident"))
                f.completeLocal();
            else if (sourceState.equals("failed"))
                f.local.completeExceptionally(new IllegalStateException("unreadable preview"));
            else if (sourceState.equals("remote-missing"))
                f.probe.complete(Optional.empty());
            if (sourceState.endsWith("resident")) {
                try (var prior = f.page()) {
                    var slot = prior.preview(f.id);
                    prior.advance(ModelResourceDemand.Mode.OFFLINE);
                    assertNotNull(slot.texture());
                }
            }
            var before = f.counts();
            var card = new CatalogCardImages(decorations, f.id, false, false, true);
            card.advancePolicy(true, null);
            decorations.advanceAcquisition(ModelResourceDemand.Mode.ONLINE);
            card.advancePolicy(true, previews);
            prepare(card);
            assertTrue(card.preparations().isEmpty(), "eager must not create even an unacquired preview slot");
            assertNull(card.preview());
            assertFalse(card.previewLoading());
            assertFalse(card.previewAbsent());
            assertNull(card.failure());
            assertArrayEquals(before, f.counts(), "request, acquisition, decode and host work must all stay unchanged");
        }
    }

    @Test
    void eagerSkipDoesNotBecomeAbsenceAndTheNextNonEagerAdvanceCreatesALegitimateRequest() throws Exception {
        try (var f = new Fixture(temp, true); var decorations = f.page(); var previews = f.page()) {
            var card = new CatalogCardImages(decorations, f.id, false, false, true);
            card.advancePolicy(true, null);
            assertFalse(card.previewAbsent());
            assertNull(card.failure());
            assertEquals(0, f.probes);
            card.advancePolicy(false, previews);
            previews.advanceAcquisition(ModelResourceDemand.Mode.OFFLINE);
            assertEquals(1, f.probes);
            assertEquals(0, f.transfers);
            assertTrue(card.previewLoading());
            assertFalse(card.previewAbsent());
            assertNull(card.preview());
            card.advancePolicy(true, null);
            assertFalse(card.previewLoading());
            assertFalse(card.previewAbsent());
            assertTrue(card.preparations().isEmpty());
            assertFalse(f.probe.isCancelled());
        }
    }

    @Test
    void enablingEagerDoesNotCancelAnAcceptedPreviewTransferOrConsumeItsAbsence() throws Exception {
        try (var f = new Fixture(temp, true); var decorations = f.page(); var previews = f.page()) {
            f.probe.complete(Optional.empty());
            var card = new CatalogCardImages(decorations, f.id, false, false, false);
            card.advancePolicy(false, previews);
            previews.advanceAcquisition(ModelResourceDemand.Mode.ONLINE);
            assertEquals(1, f.probes);
            assertEquals(1, f.transfers);
            assertEquals(1, f.members.size());
            assertInstanceOf(RemotePresentationFetcher.Preview.class, f.members.get(0));
            card.advancePolicy(true, null);
            assertFalse(f.transfer.isCancelled());
            assertFalse(f.probe.isCancelled());
            f.receiver.accept(f.members.get(0), new RemotePresentationFetcher.Absent(() -> true));
            f.transfer.complete(null);
            assertFalse(card.previewAbsent());
            assertFalse(card.previewLoading());
            assertNull(card.preview());
            assertNull(card.failure());
            assertEquals(0, f.images.decodes);
            card.advancePolicy(false, previews);
            previews.advanceAcquisition(ModelResourceDemand.Mode.READY);
            assertTrue(card.previewAbsent());
            assertFalse(card.previewLoading());
            assertEquals(1, f.transfers);
            assertEquals(1, f.probes, "restoring non-eager must not repeat an already accepted request");
            assertEquals(0, f.images.host.constructions);
        }
    }

    @Test
    void eagerSuppressesResidentPreviewWhileForegroundAndBackgroundRemainConsumable() throws Exception {
        try (var f = new Fixture(temp, false); var decorations = f.page(); var previews = f.page()) {
            f.completeLocal();
            var card = new CatalogCardImages(decorations, f.id, true, true, true);
            card.advancePolicy(true, null);
            decorations.advanceAcquisition(ModelResourceDemand.Mode.OFFLINE);
            assertEquals(2, card.preparations().size());
            prepare(card);
            assertNotNull(card.background());
            assertNotNull(card.foreground());
            assertNull(card.preview());
            assertEquals(0, f.localRequests);
            assertEquals(2, f.images.decodes);
            assertEquals(2, f.images.host.constructions);
            var background = card.background().id();
            var foreground = card.foreground().id();
            card.advancePolicy(false, previews);
            previews.advanceAcquisition(ModelResourceDemand.Mode.OFFLINE);
            prepare(card);
            assertNotNull(card.preview());
            var preview = card.preview().id();
            assertEquals(1, f.localRequests);
            var before = f.counts();
            card.advancePolicy(true, null);
            assertNull(card.preview());
            assertFalse(card.previewLoading());
            assertFalse(card.previewAbsent());
            assertEquals(2, card.preparations().size());
            assertEquals(background, card.background().id());
            assertEquals(foreground, card.foreground().id());
            card.advancePolicy(false, previews);
            previews.advanceAcquisition(ModelResourceDemand.Mode.READY);
            prepare(card);
            assertEquals(preview, card.preview().id());
            assertArrayEquals(before, f.counts());
        }
    }

    @Test
    void pageCloseStillCancelsTheAcceptedPreviewAfterPolicyChanges() throws Exception {
        try (var f = new Fixture(temp, true); var decorations = f.page()) {
            var previews = f.page();
            f.probe.complete(Optional.empty());
            var card = new CatalogCardImages(decorations, f.id, false, false, false);
            card.advancePolicy(false, previews);
            previews.advanceAcquisition(ModelResourceDemand.Mode.ONLINE);
            card.advancePolicy(true, null);
            assertFalse(f.transfer.isCancelled());
            previews.close();
            assertTrue(f.transfer.isCancelled());
            assertNull(card.preview());
            assertFalse(card.previewAbsent());
            assertFalse(card.previewLoading());
        }
    }

    private static void prepare(CatalogCardImages card) throws Exception {
        for (var image : card.preparations()) {
            var preparation = image.claimPreparation();
            if (preparation == null)
                continue;
            try (preparation) {
                image.publish(preparation.prepare());
            }
        }
    }

    private static Path parse(String resource, Path output, boolean builtin) throws Exception {
        var source = Path.of(CatalogCardImagesTest.class.getResource(resource).toURI()).getParent();
        Files.createDirectories(output);
        try (var vfs = new Directory(source)) {
            return builtin ? ModelParser.parseBuiltinDefault(vfs, output)
                : ModelParser.parse(vfs, output, DefaultAnimationFilter.keepAll());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ClientCatalogScenarioHarness catalogs;
        final RemoteModelStore store;
        final RemoteModelContent remote;
        final Hash256 id;
        final ClientAssetRepository repository;
        final boolean builtin;
        final ResidentImagesTest.Fixture images = new ResidentImagesTest.Fixture();
        final CompletableFuture<ImageSource> local = new CompletableFuture<>();
        final CompletableFuture<Optional<ImageSource>> probe = new CompletableFuture<>();
        final CompletableFuture<Void> transfer = new CompletableFuture<>();
        List<RemotePresentationFetcher.Member> members;
        RemotePresentationFetcher.PresentationReceiver receiver;
        int exactRequests;
        int localRequests;
        int probes;
        int transfers;

        Fixture(Path root, boolean remoteSource) throws Exception {
            this(root, remoteSource, false);
        }

        Fixture(Path root, boolean remoteSource, boolean builtin) throws Exception {
            this.builtin = builtin;
            catalogs = ClientCatalogScenarioHarness.open(root.resolve("catalog"), intrinsic,
                remoteSource || builtin ? List.of() : List.of(model));
            store = new RemoteModelStore(root.resolve("remote"));
            var content = ManagedContainer.openDirect(builtin ? intrinsic : remoteSource ? sourceModel : model,
                new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("source")));
            try {
                id = content.modelId();
                if (remoteSource)
                    assertEquals(PreviewSource.UNSPECIFIED, content.representation().view().getThumbnailPreviewSource(),
                        "remote absence requires a model with an independent preview");
                remote = remoteSource ? store.commitMetadataPrefix(content.representation().identity(),
                    content.representation().metadataPrefix().orElseThrow()) : null;
            } finally {
                content.representation().close();
            }
            if (remoteSource) {
                var record = new CatalogRecord(new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("remote/model")),
                    new CatalogContentBinding(id, remote));
                var publication = new PublicationEntry(remote.representation().identity(), record.entry().path(),
                    CatalogAccess.PUBLIC, true);
                var snapshot = new RemotePublicationSnapshot(List.of(publication), Set.of(), List.of(), Map.of());
                catalogs.manager().beginRemote();
                catalogs.manager().publishSession(
                    new ActivationSnapshot(snapshot, Map.of(id, new ActivationSnapshot.Ready(publication, record))));
            }
            assertTrue(catalogs.manager().contains(id));
            repository = new ClientAssetRepository(catalogs.manager(), () -> (requested, publisher) -> {
                transfers++;
                members = List.copyOf(requested);
                receiver = publisher;
                return transfer;
            }, contentSource -> {
                exactRequests++;
                return contentSource;
            }, null, Runnable::run, contentSource -> {
                localRequests++;
                return local;
            }, contentSource -> {
                probes++;
                return probe;
            }, () -> null);
        }

        void completeLocal() throws Exception {
            if (builtin) {
                local.complete(images.source());
                return;
            }
            ModelContent content = catalogs.manager().snapshot().find(id).orElseThrow().content();
            local.complete(content.modelFile().thumbnailSource(() -> false, content.chunks()));
        }

        PageImages page() {
            return new PageImages(new ClientAssetBatch(repository.openBatch()), images.owner);
        }

        int[] counts() {
            return new int[] {exactRequests, localRequests, probes, transfers, images.decodes,
                images.host.constructions, images.host.registrations, images.host.uploads};
        }

        @Override
        public void close() {
            if (remote != null)
                remote.representation().close();
            store.close();
            catalogs.close();
            images.host.tick();
            images.host.tick();
        }
    }
}
