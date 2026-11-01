package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.content.DirectContainerAdmission;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSource;
import com.elfmcys.ysm.model.catalog.source.ModelSourceDiscovery;
import com.elfmcys.ysm.model.catalog.source.ModelSourceKind;
import com.elfmcys.ysm.model.catalog.source.RootInventoryState;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ModelExporterTest {
    @TempDir
    static Path fixtures;
    @TempDir
    Path temp;
    private static Path sourceFile;

    @BeforeAll
    static void parseSource() throws Exception {
        var manifest = ModelExporterTest.class.getResource("/assets/ysm/builtin/misc/1_alex/ysm.json");
        var directory = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
        try (var vfs = new Directory(directory)) {
            sourceFile = ModelParser.parse(vfs, Files.createDirectories(fixtures.resolve("raw")),
                DefaultAnimationFilter.keepAll());
        }
    }

    @Test
    void rawConversionAndReExportPreserveTheOriginBehaviorLevel() throws Exception {
        var manifest = ModelExporterTest.class.getResource("/assets/ysm/builtin/misc/1_alex/ysm.json");
        var directory = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
        for (int originVer : new int[] {0, 18, 19, 32,
            com.elfmcys.ysm.format.schema.model.ModelOriginVersion.CURRENT}) {
            final Path converted;
            try (var vfs = new Directory(directory)) {
                converted = ModelParser
                    .compile(vfs, temp.resolve("origin-" + originVer), DefaultAnimationFilter.keepAll(),
                        com.elfmcys.ysm.format.schema.model.ModelOriginVersion.toModVersion(originVer))
                    .stagedContainer();
            }
            var source = open(converted);
            try {
                assertEquals(originVer, source.modelFile().getMetadata().originVer());
                var output = temp.resolve("export-" + originVer + ".ysm");
                ModelExporter.export(source, TestPreviews.blank(), output, "fixture");
                var exported = open(output);
                try {
                    assertEquals(originVer, exported.modelFile().getMetadata().originVer());
                    assertEquals(source.modelFile().getManifest().info().properties().originVer(),
                        exported.modelFile().getManifest().info().properties().originVer());
                } finally {
                    exported.representation().close();
                }
            } finally {
                source.representation().close();
            }
        }
    }

    @Test
    void generatedPreviewMakesACompleteDirectArtifactAndPreservesModelDeclarations() throws Exception {
        var sourceBytes = Files.readAllBytes(sourceFile);
        var source = open(sourceFile);
        var output = temp.resolve("portable.ysm");
        try {
            ModelExporter.export(source, TestPreviews.blank(), output, "fixture");
            assertArrayEquals(sourceBytes, Files.readAllBytes(sourceFile));
            var exported = open(output);
            try {
                DirectContainerAdmission.requireEmbeddedPreview(exported);
                assertEquals(source.representation().modelId(), exported.representation().modelId());
                assertNotEquals(source.representation().containerId(), exported.representation().containerId());
                assertEquals(source.modelFile().getManifest().commonAssets(),
                    exported.modelFile().getManifest().commonAssets());
                assertEquals(source.modelFile().getManifest().renderTargets(),
                    exported.modelFile().getManifest().renderTargets());
                assertEquals(source.modelFile().getManifest().info().metadataUnsafe(),
                    exported.modelFile().getManifest().info().metadataUnsafe());
                assertEquals(PreviewSource.GENERATED, exported.modelFile().getThumbnailPreviewSource());
                assertEquals("fixture", exported.modelFile().getManifest().info().exportUnsafe().extra());
            } finally {
                exported.representation().close();
            }
        } finally {
            source.representation().close();
        }
    }

    @Test
    void currentContainerWrittenWithExportSuffixIsRediscoveredAsDirectArtifact() throws Exception {
        var source = open(sourceFile);
        var output = temp.resolve("portable.ysm");
        try {
            ModelExporter.export(source, TestPreviews.blank(), output, null);
        } finally {
            source.representation().close();
        }

        var inventory = assertInstanceOf(RootInventoryState.Complete.class,
            ModelSourceDiscovery.inventory(new ModelCatalogSource(CatalogRootKind.CUSTOM, temp, false)));
        var observation = inventory.sources().values().stream()
            .filter(value -> value.absolutePath().equals(output.toAbsolutePath().normalize())).findFirst()
            .orElseThrow();

        assertEquals(ModelSourceKind.DIRECT_CONTAINER, observation.key().sourceKind());
    }

    @Test
    void existingPreviewIsCopiedExactlyAndFailedReplacementKeepsPriorOutput() throws Exception {
        var source = open(sourceFile);
        var output = temp.resolve("stable.ysm");
        try {
            ModelExporter.export(source, TestPreviews.blank(), output, null);
        } finally {
            source.representation().close();
        }
        var stable = Files.readAllBytes(output);
        var exportedSource = open(output);
        try {
            ModelExporter.export(exportedSource, TestPreviews.blank(), temp.resolve("copy.ysm"), null);
            var copied = open(temp.resolve("copy.ysm"));
            try {
                assertStoredChunksEqual(exportedSource, copied);
            } finally {
                copied.representation().close();
            }
        } finally {
            exportedSource.representation().close();
        }

        var corruptFile = Files.copy(sourceFile, temp.resolve("corrupt.mxc"));
        var corrupt = open(corruptFile);
        var bytes = Files.readAllBytes(corruptFile);
        bytes[bytes.length - 1] ^= 1;
        Files.write(corruptFile, bytes);
        try {
            assertThrows(Exception.class, () -> ModelExporter.export(corrupt, TestPreviews.blank(), output, null));
            assertArrayEquals(stable, Files.readAllBytes(output));
        } finally {
            corrupt.representation().close();
        }
    }

    private static ManagedContainer open(Path file) throws Exception {
        return ManagedContainer.openDirect(file, new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("fixture")));
    }

    private static void assertStoredChunksEqual(ManagedContainer expected, ManagedContainer actual) throws Exception {
        var actualView = actual.modelFile().getFileView().getAssetView();
        for (var chunk : expected.modelFile().getFileView().getAssetView().getChunkTable().values()) {
            if (chunk.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE)
                || chunk.type().equals(ModelFileConstant.MANIFEST_CHUNK_NAME))
                continue;
            var copied = actualView.getChunkInfo(chunk.type());
            assertNotNull(copied, chunk.type());
            assertEquals(chunk.encoding(), copied.encoding(), chunk.type());
            assertEquals(chunk.decodeSize(), copied.decodeSize(), chunk.type());
            assertEquals(chunk.flags(), copied.flags(), chunk.type());
            assertEquals(chunk.alignmentShift(), copied.alignmentShift(), chunk.type());
            assertArrayEquals(chunk.hash(), copied.hash(), chunk.type());
            {
                var left = expected.chunks().readStoredVerified(chunk, BufferType.ARRAY);
                var right = actual.chunks().readStoredVerified(copied, BufferType.ARRAY);
                try (var leftBytes = left.copyArray(); var rightBytes = right.copyArray()) {
                    assertArrayEquals(
                        Arrays.copyOfRange(leftBytes.array(), leftBytes.arrayOffset(),
                            leftBytes.arrayOffset() + leftBytes.size()),
                        Arrays.copyOfRange(rightBytes.array(), rightBytes.arrayOffset(),
                            rightBytes.arrayOffset() + rightBytes.size()),
                        chunk.type());

                }
            }
        }
    }
}
