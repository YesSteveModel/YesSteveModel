package com.elfmcys.ysm.model.catalog;

import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.model.ModelFileIdentityReader;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogCandidate;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSource;
import com.elfmcys.ysm.model.catalog.source.ModelSourceResolver;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelScanWarning;
import com.elfmcys.ysm.model.storage.ConvertedObjectStore;
import com.elfmcys.ysm.model.storage.ConvertedSourceIndexStore;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelExporter;
import com.elfmcys.ysm.model.storage.TestPreviews;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogReconcilerTest {
    @TempDir
    static Path fixtureTemp;

    @TempDir
    Path temp;

    private static Path defaultFixture;
    private static Path candidateFixture;
    private static Path candidateWithoutPreviewFixture;

    @BeforeAll
    static void createFixture() throws Exception {
        var defaultManifest = CatalogReconcilerTest.class.getResource("/assets/ysm/builtin/default/ysm.json");
        var defaultSource = Path.of(Objects.requireNonNull(defaultManifest).toURI()).getParent();
        try (var vfs = new Directory(defaultSource)) {
            defaultFixture =
                ModelParser.parseBuiltinDefault(vfs, Files.createDirectories(fixtureTemp.resolve("default")));
        }
        var candidateManifest = CatalogReconcilerTest.class.getResource("/assets/ysm/builtin/misc/1_alex/ysm.json");
        var candidateSource = Path.of(Objects.requireNonNull(candidateManifest).toURI()).getParent();
        try (var vfs = new Directory(candidateSource)) {
            var parsed = ModelParser.parse(vfs, Files.createDirectories(fixtureTemp.resolve("candidate")),
                DefaultAnimationFilter.keepAll());
            candidateWithoutPreviewFixture = parsed;
            var location = new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("candidate"));
            var source = ManagedContainer.openDirect(parsed, location);
            try {
                candidateFixture = ModelExporter.export(source, TestPreviews.blank(),
                    fixtureTemp.resolve("candidate-with-preview.mxc"), null);
            } finally {
                source.representation().close();
            }
        }
    }

    @Test
    void firstFullyValidCandidateWinsForTheSameModelId() throws Exception {
        var root = Files.createDirectories(temp.resolve("custom"));
        Files.copy(candidateFixture, root.resolve("a.mxc"));
        Files.copy(candidateFixture, root.resolve("b.mxc"));

        var candidate = scan(root);
        var modelId = identity(candidateFixture).modelId();

        assertEquals(1, candidate.index().entries().size());
        assertEquals(2, candidate.snapshot().byModelId().size());
        assertTrue(
            Set.of("a.mxc", "b.mxc").contains(candidate.snapshot().byModelId().get(modelId).location().path().value()));
        assertEquals(1, candidate.snapshot().report().errorCount());
    }

    @Test
    void invalidCandidateIsOmittedAndLaterValidCandidateWins() throws Exception {
        var root = Files.createDirectories(temp.resolve("fallback"));
        var broken = Files.copy(candidateFixture, root.resolve("a.mxc"));
        var bytes = Files.readAllBytes(broken);
        bytes[bytes.length - 1] ^= 1;
        Files.write(broken, bytes);
        Files.copy(candidateFixture, root.resolve("b.mxc"));

        var candidate = scan(root);
        var identity = identity(candidateFixture);

        assertEquals(1, candidate.index().findCandidates(identity).size());
        assertEquals("b.mxc", candidate.snapshot().byModelId().get(identity.modelId()).location().path().value());
        assertEquals(1, candidate.snapshot().report().errorCount());
    }

    @Test
    void invalidModelIsOmittedWithoutDiscardingTheCandidate() throws Exception {
        var root = Files.createDirectories(temp.resolve("invalid"));
        Files.write(root.resolve("broken.mxc"), AssetContainerConstant.HEAD);

        var candidate = scan(root);

        assertTrue(candidate.index().entries().isEmpty());
        assertEquals(1, candidate.snapshot().byModelId().size());
        assertEquals(1, candidate.snapshot().report().errorCount());
    }

    @Test
    void rawUnknownAudioWarningSurvivesSuccessfulPublication() throws Exception {
        var root = Files.createDirectories(temp.resolve("raw-warning"));
        var manifest = CatalogReconcilerTest.class.getResource("/assets/ysm/builtin/misc/1_alex/ysm.json");
        var source = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
        var raw = root.resolve("raw-model");
        copyTree(source, raw);
        Files.createDirectories(raw.resolve("sounds"));
        Files.write(raw.resolve("sounds/unknown.mp3"), new byte[] {1, 2, 3});

        var candidate = scan(root);

        assertEquals(0, candidate.snapshot().report().errorCount());
        assertEquals(1, candidate.snapshot().report().warningCount());
        assertEquals(1, candidate.snapshot().report().warnings().size());
        assertEquals(ModelScanWarning.Kind.UNKNOWN_AUDIO, candidate.snapshot().report().warnings().get(0).kind());
    }

    @Test
    void rawInvalidSupportedAudioFailsTheSource() throws Exception {
        var root = Files.createDirectories(temp.resolve("raw-invalid-audio"));
        var manifest = CatalogReconcilerTest.class.getResource("/assets/ysm/builtin/wine_fox/14_momo/ysm.json");
        var source = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
        var raw = root.resolve("raw-model");
        copyTree(source, raw);
        var sound = raw.resolve("sounds/fly_on.ogg");
        var bytes = Files.readAllBytes(sound);
        bytes[bytes.length - 1] ^= 1;
        Files.write(sound, bytes);

        var candidate = scan(root);

        assertTrue(candidate.index().entries().isEmpty());
        assertEquals(1, candidate.snapshot().report().errorCount());
        assertEquals(0, candidate.snapshot().report().warningCount());
    }

    @Test
    void rawWithoutPreviewPublishesAsAnInternalContainer() throws Exception {
        var root = Files.createDirectories(temp.resolve("missing-raw-preview"));
        var manifest = CatalogReconcilerTest.class.getResource("/assets/ysm/builtin/misc/1_alex/ysm.json");
        copyTree(Path.of(Objects.requireNonNull(manifest).toURI()).getParent(), root.resolve("raw-model"));

        var candidate = scan(root);

        assertEquals(0, candidate.snapshot().report().errorCount());
        assertEquals(1, candidate.index().entries().size());
        var entry = candidate.index().entries().get(0);
        var published = candidate.snapshot().byModelId().get(entry.identity().modelId());
        assertEquals("raw-model", published.location().entry());
        assertEquals(com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.UNSPECIFIED,
            published.binding().content().modelFile().getThumbnailPreviewSource());
    }

    @Test
    void directContainerWithoutEmbeddedPreviewIsRejected() throws Exception {
        var root = Files.createDirectories(temp.resolve("missing-preview"));
        Files.copy(candidateWithoutPreviewFixture, root.resolve("missing.mxc"));

        var candidate = scan(root);

        assertTrue(candidate.index().entries().isEmpty());
        assertEquals(1, candidate.snapshot().byModelId().size());
        assertEquals(1, candidate.snapshot().report().errorCount());
    }

    private static void copyTree(Path source, Path target) throws Exception {
        try (var files = Files.walk(source)) {
            for (var path : files.toList()) {
                var destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination);
                }
            }
        }
    }

    private CatalogCandidate scan(Path root) throws Exception {
        var reconciler = reconciler(root);
        try (var catalog = new ReloadableModelCatalog(reconciler, reconciler.materializeBuiltins())) {
            catalog.activate();
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (catalog.busy() && System.nanoTime() < deadline) {
                catalog.tick();
                Thread.sleep(2);
            }
            assertTrue(!catalog.busy(), "Timed out awaiting catalog publication");
            return catalog.currentCandidate();
        }
    }

    private CatalogReconciler reconciler(Path root) throws Exception {
        var cacheRoot = temp.resolve("cache");
        var resolver = new ModelSourceResolver(new RawModelImporter(DefaultAnimationFilter.keepAll()),
            new ConvertedSourceIndexStore(cacheRoot, "test-version"), new ConvertedObjectStore(cacheRoot));
        var intrinsicDefault =
            openIndexed(defaultFixture, new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")));
        return new CatalogReconciler(List.of(new ModelCatalogSource(CatalogRootKind.CUSTOM, root, false)), resolver,
            Set.of(intrinsicDefault.modelId()), intrinsicDefault, CatalogIndexSnapshot.empty());
    }

    private static ManagedContainer openIndexed(Path file, CatalogLocation location) throws Exception {
        var identity = identity(file);
        return ManagedContainer.openIndexed(new CatalogIndexEntry(identity, location, file));
    }

    private static ModelFileIdentity identity(Path file) throws Exception {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return ModelFileIdentityReader.read(channel);
        }
    }
}
