package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.content.DirectContainerAdmission;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Independently reopens the artifact produced by the real integrated command. */
@EnabledIfEnvironmentVariable(named = "YSM_PREVIEW_HOST_ARTIFACT", matches = ".+")
class PreviewHostArtifactTest {
    @TempDir
    Path temp;

    @Test
    void integratedArtifactPreservesCompleteSourceAndContainsDecodedGeneratedPreview() throws Exception {
        var output = requiredPath("YSM_PREVIEW_HOST_ARTIFACT");
        var sourceDirectory = requiredPath("YSM_PREVIEW_HOST_SOURCE");
        assertTrue(Files.isRegularFile(output), "The real host must produce the artifact before validation");
        final Path converted;
        try (var source = new Directory(sourceDirectory)) {
            converted = ModelParser.parse(source, temp, DefaultAnimationFilter.keepAll());
        }
        var location = new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("preview-host-artifact"));
        var source = ManagedContainer.openDirect(converted, location);
        try (var sourceRepresentation = source.representation()) {
            var artifact = ManagedContainer.openDirect(output, location);
            try (var artifactRepresentation = artifact.representation()) {
                DirectContainerAdmission.requireEmbeddedPreview(artifact);
                assertEquals(source.representation().modelId(), artifact.representation().modelId());
                assertNotEquals(source.representation().containerId(), artifact.representation().containerId());
                assertEquals(source.modelFile().getManifest().renderTargets(),
                    artifact.modelFile().getManifest().renderTargets());
                assertEquals(source.modelFile().getManifest().commonAssets(),
                    artifact.modelFile().getManifest().commonAssets());
                assertEquals(source.modelFile().getManifest().info().metadataUnsafe(),
                    artifact.modelFile().getManifest().info().metadataUnsafe());
                assertEquals(PreviewSource.GENERATED, artifact.modelFile().getThumbnailPreviewSource());
                var artifactView = artifact.modelFile().getFileView().getAssetView();
                for (var chunk : source.modelFile().getFileView().getAssetView().getChunkTable().values()) {
                    // Verification and manifest identities change when the preview is embedded.
                    if (chunk.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE)
                        || chunk.type().equals(ModelFileConstant.MANIFEST_CHUNK_NAME))
                        continue;
                    var copied = artifactView.getChunkInfo(chunk.type());
                    assertNotNull(copied, chunk.type());
                    assertArrayEquals(chunk.hash(), copied.hash(), chunk.type());
                    {
                        var original =
                            source.chunks().readStoredVerified(chunk, com.elfmcys.ysm.buffer.BufferType.ARRAY);
                        var exported =
                            artifact.chunks().readStoredVerified(copied, com.elfmcys.ysm.buffer.BufferType.ARRAY);
                        assertEquals(original.nio(), exported.nio(), chunk.type());
                    }
                }
                try (var image = artifact.modelFile().thumbnailSource(() -> false, artifact.chunks()).open();
                    var pixels = image.decodeToBuffer()) {
                    assertGeneratedPreview(image.width(), image.height(), pixels);
                }
                var received = System.getenv("YSM_PREVIEW_HOST_IMAGE");
                if (received != null && !received.isBlank()) {
                    try (var encoded = PreviewStore.decode(Files.readAllBytes(Path.of(received)));
                        var image = encoded.open(); var pixels = image.decodeToBuffer()) {
                        assertGeneratedPreview(image.width(), image.height(), pixels);
                    }
                }
            }
        }
    }

    private static Path requiredPath(String name) {
        var value = System.getenv(name);
        assertNotNull(value, name + " must name the real host input or output");
        assertFalse(value.isBlank(), name + " must not be blank");
        var path = Path.of(value);
        assertTrue(path.isAbsolute(), name + " must be an absolute path");
        return path;
    }

    private static void assertGeneratedPreview(int width, int height, com.elfmcys.ysm.buffer.UniBuffer pixels) {
        assertEquals(156, width);
        assertEquals(270, height);
        assertEquals(156 * 270 * 4, pixels.size());
    }
}
