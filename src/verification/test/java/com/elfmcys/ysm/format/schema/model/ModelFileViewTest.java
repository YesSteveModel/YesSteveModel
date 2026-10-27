package com.elfmcys.ysm.format.schema.model;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.file.FileChunkDataSource;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.proto.mixel.common.ImageProto;
import com.elfmcys.ysm.proto.mixel.manifest.ManifestProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.CommonProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.PBRTextureSetProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import com.elfmcys.ysm.proto.mixel.manifest.info.InfoProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.LicenseProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.MetadataProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelSettingsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelStatsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource;
import com.elfmcys.ysm.proto.mixel.manifest.info.PropertiesProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelFileViewTest {
    @TempDir
    static Path fixtureTemp;

    private static Path fixture;
    private static ModelFileIdentity fixtureIdentity;

    @BeforeAll
    static void createFixture() throws Exception {
        var manifest = ModelFileViewTest.class.getResource("/assets/ysm/builtin/default/ysm.json");
        var source = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
        try (var vfs = new Directory(source)) {
            fixture = ModelParser.parseBuiltinDefault(vfs, Files.createDirectories(fixtureTemp.resolve("model")));
        }
        try (var channel = FileChannel.open(fixture, StandardOpenOption.READ)) {
            fixtureIdentity = ModelFileIdentityReader.read(channel);
        }
    }

    @Test
    void acceptsCanonicalPlayerRenderTarget() {
        assertDoesNotThrow(() -> ModelFileView.validatePlayerRenderTarget(manifest("player", RenderTargetKind.PLAYER)));
    }

    @Test
    void rejectsTexturelessPlayerButAllowsTexturelessNonPlayerTarget() {
        var player = renderTarget("player", RenderTargetKind.PLAYER, true);
        var texturelessProjectile = renderTarget("projectile-1", RenderTargetKind.PROJECTILE, false);

        assertDoesNotThrow(() -> ModelFileView.validatePlayerRenderTarget(
            manifestBuilder().addRenderTargets(player).addRenderTargets(texturelessProjectile).build()));
        assertThrows(IOException.class, () -> ModelFileView.validatePlayerRenderTarget(
            manifestBuilder().addRenderTargets(renderTarget("player", RenderTargetKind.PLAYER, false)).build()));
    }

    @Test
    void rejectsMissingOrMisidentifiedPlayerRenderTarget() {
        assertThrows(IOException.class, () -> ModelFileView.validatePlayerRenderTarget(manifestBuilder().build()));
        assertThrows(IOException.class,
            () -> ModelFileView.validatePlayerRenderTarget(manifest("player", RenderTargetKind.PROJECTILE)));
        assertThrows(IOException.class,
            () -> ModelFileView.validatePlayerRenderTarget(manifest("legacy-player", RenderTargetKind.PLAYER)));
    }

    @Test
    void validatesThumbnailSourceAgainstChunkPresence() {
        assertDoesNotThrow(() -> ModelFileView.validatePreviewSource("Thumbnail", PreviewSource.UNSPECIFIED, false));
        assertDoesNotThrow(() -> ModelFileView.validatePreviewSource("Thumbnail", PreviewSource.RAW, true));
        assertDoesNotThrow(() -> ModelFileView.validatePreviewSource("Thumbnail", PreviewSource.GENERATED, true));

        assertThrows(IOException.class,
            () -> ModelFileView.validatePreviewSource("Thumbnail", PreviewSource.UNSPECIFIED, true));
        assertThrows(IOException.class,
            () -> ModelFileView.validatePreviewSource("Thumbnail", PreviewSource.RAW, false));
        assertThrows(IOException.class,
            () -> ModelFileView.validatePreviewSource("Thumbnail", PreviewSource.GENERATED, false));
        assertThrows(IOException.class, () -> ModelFileView.validatePreviewSource("Thumbnail", null, false));
    }

    @Test
    void defaultsMissingThumbnailSourceWithoutMutatingInfo() {
        var info = info();

        assertEquals(PreviewSource.UNSPECIFIED, ModelFileView.thumbnailSource(info));
        assertFalse(info.hasThumbnailSource());
    }

    @Test
    void defaultsMissingIconSourceWithoutMutatingInfo() {
        var info = info();

        assertEquals(PreviewSource.UNSPECIFIED, ModelFileView.iconSource(info));
        assertFalse(info.hasIconSource());
    }

    @Test
    void admittedFileReadsRejectLengthRangeAndSameSizeContentChanges(@TempDir Path temp) throws Exception {
        var file = Files.copy(fixture, temp.resolve("source.ysm"));
        var original = Files.readAllBytes(file);
        var content = ManagedContainer.openIndexed(new CatalogIndexEntry(fixtureIdentity,
            new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("fixture")), file));
        var chunk = content.view().getFileView().getAssetView().getChunkTable().values().stream()
            .filter(candidate -> !candidate.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE))
            .max(Comparator.comparingInt(AssetContainerView.ChunkInfo::offset)).orElseThrow();
        try {
            Files.write(file, Arrays.copyOf(original, original.length + 1));
            assertContentFailure(() -> {
                try (var ignored = content.chunks().readStoredVerified(chunk, BufferType.ARRAY)) {
                    // A successful read would retain a buffer that this negative path must close.
                }
            });

            Files.write(file, original);
            var changed = original.clone();
            changed[chunk.offset()] ^= 1;
            Files.write(file, changed);
            assertContentFailure(() -> {
                try (var ignored = content.chunks().readStoredVerified(chunk, BufferType.ARRAY)) {
                    // A successful read would retain a buffer that this negative path must close.
                }
            });

            Files.write(file, original);
            var outOfRange = new AssetContainerView.ChunkInfo(chunk.type(), chunk.encoding(), original.length,
                chunk.size(), chunk.decodeSize(), chunk.flags(), 0, chunk.alignmentShift(), chunk.hash());
            assertContentFailure(() -> {
                try (var ignored =
                    new FileChunkDataSource(file, original.length).readStoredVerified(outOfRange, BufferType.ARRAY)) {
                    // A successful read would retain a buffer that this negative path must close.
                }
            });

            var accessFailure = assertThrows(AssetLoadException.class,
                () -> new FileChunkDataSource(temp.resolve("missing.ysm"), original.length).readStoredVerified(chunk,
                    BufferType.ARRAY));
            assertEquals(AssetLoadException.Reason.ACCESS, accessFailure.reason());
        } finally {
            content.representation().close();
        }
    }

    private static ManifestProto manifest(String targetId, RenderTargetKind kind) {
        return manifestBuilder().addRenderTargets(renderTarget(targetId, kind, true)).build();
    }

    private static ManifestProto.Builder manifestBuilder() {
        return ManifestProto.newBuilder().setCommonAssets(CommonProto.newBuilder().setStringsBlobId(0).build())
            .setInfo(info());
    }

    private static InfoProto info() {
        return InfoProto.newBuilder()
            .setSettings(SettingsProto.newBuilder().setDefaultTexture("").setPreviewAnimation("")
                .setDisablePreviewRotation(false).build())
            .setMetadata(MetadataProto.newBuilder().setName("").setTips("")
                .setLicense(LicenseProto.newBuilder().setType("").setDesc("").build()).build())
            .setProperties(PropertiesProto.newBuilder().setModelId(ByteBuffer.wrap(new byte[32])).setFree(false)
                .setOriginVer("").build())
            .build();
    }

    private static RenderTargetProto renderTarget(String targetId, RenderTargetKind kind, boolean textured) {
        var target = RenderTargetProto.newBuilder().setTargetId(targetId).setKind(kind).setBlobId(0)
            .setSettings(ModelSettingsProto.newBuilder().setHeightScale(0).setWidthScale(0).setRenderLayersFirst(false)
                .setForceCulling(false).setGuiNoLighting(false).setMergeMultilineExpr(false).build())
            .setStats(ModelStatsProto.newBuilder().setBones(0).setCubes(0).setFaces(0).build());
        if (textured) {
            target.putTextures("main", PBRTextureSetProto.newBuilder().setUv(
                ImageProto.newBuilder().setBlobId(0).setFormat("").setWidth(0).setHeight(0).setFrameCount(0).build())
                .build());
        }
        return target.build();
    }

    private static void assertContentFailure(IoOperation operation) {
        var failure = assertThrows(AssetLoadException.class, operation::run);
        assertEquals(AssetLoadException.Reason.CONTENT, failure.reason());
    }

    @FunctionalInterface
    private interface IoOperation {
        void run() throws IOException;
    }
}
