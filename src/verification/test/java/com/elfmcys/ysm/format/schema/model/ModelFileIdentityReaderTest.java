package com.elfmcys.ysm.format.schema.model;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerReader;
import com.elfmcys.ysm.format.container.AssetContainerWriter;
import com.elfmcys.ysm.format.schema.file.AssetFileWriter;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.proto.mixel.common.ImageProto;
import com.elfmcys.ysm.proto.mixel.common.SoundProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.AuthorProto;
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
import com.elfmcys.ysm.proto.mixel.manifest.info.PropertiesProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.util.ProtoUtil;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import us.hebi.quickbuf.UninitializedMessageException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelFileIdentityReaderTest {
    @TempDir
    Path temp;

    @Test
    void writerDerivesExactIdentityPropertyFromManifest() throws Exception {
        var modelId = hash(1);
        var file = writeWithProductionWriter("round-trip.mxc", manifest(modelId));

        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            var identity = ModelFileIdentityReader.read(channel);
            assertEquals(modelId, identity.modelId());
            assertEquals(modelId.toString(),
                AssetContainerReader.read(channel).getSchemaProperty(ModelFileConstant.PROP_MODEL_ID));
            var container = AssetContainerReader.read(channel);
            assertEquals("Yes Steve Model(ysm) vdev", container.getSchemaProperty(ModelFileConstant.PROP_VENDOR));
            assertEquals("(unsigned)", container.getSchemaProperty(ModelFileConstant.PROP_SIGNATURE));
        }
    }

    @Test
    void stableSchemasRejectEitherNewerMajorOrNewerMinorAndIgnorePatch() {
        for (var candidate : new String[] {"3.0.0", "2.5.0", "1.5.0"}) {
            assertFalse(ModelFileIdentityReader.supportsVersion("2.4.0", candidate), candidate);
        }
        for (var candidate : new String[] {"2.4.0", "2.4.99", "2.3.0", "1.4.0", "1.3.0"}) {
            assertTrue(ModelFileIdentityReader.supportsVersion("2.4.0", candidate), candidate);
        }
        assertFalse(ModelFileIdentityReader.supportsVersion("2.4.0-unstable", "2.3.0-unstable"));
        assertFalse(ModelFileIdentityReader.supportsVersion("2.4.0", "2.4.0-dev"));
        assertFalse(ModelFileIdentityReader.supportsVersion("2.4.0", "invalid"));
        assertTrue(ModelFileIdentityReader.supportsVersion("2.4.0-unstable", "2.4.0-unstable"));
    }

    @Test
    void newerSchemasReportTheExportingModFromTheVerifiedProperty() throws Exception {
        for (var version : new String[] {"1.4.0-unstable", "0.5.0-unstable"}) {
            final Path file;
            try (var writer = new VersionedModelWriter(hash(2), manifest(hash(2)), version)) {
                file = write("newer-" + version + ".mxc", writer);
            }
            try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
                var failure =
                    assertThrows(UnsupportedEncodingException.class, () -> ModelFileIdentityReader.read(channel));
                assertTrue(failure.getMessage().contains(version));
                assertTrue(failure.getMessage().contains("Example Mod(example) v9.8.7"));
                assertFalse(failure.getMessage().contains("test-signature"));
            }
        }
    }

    @Test
    void rejectsPreviousUnstableModelSchema() throws Exception {
        var modelId = hash(2);
        var file = writePreviousVersion("previous-version.mxc", modelId, manifest(modelId));

        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            assertThrows(UnsupportedEncodingException.class, () -> ModelFileIdentityReader.read(channel));
        }
    }

    @Test
    void strictBuildRejectsMissingManifestAndWriterRejectsWrongLengthIdentity() throws Exception {
        assertThrows(UninitializedMessageException.class, () -> ManifestProto.newBuilder().build());
        var wrongLength = manifest(ByteBuffer.wrap(new byte[31]));
        try (var writer = new ModelFileWriter()) {
            assertThrows(IOException.class, () -> writer.setManifest(wrongLength));
        }
    }

    @Test
    void rejectsMissingMalformedOrNonLowercaseIdentityProperty() throws Exception {
        var modelId = hash(0xab);
        for (var property : new String[] {null, "0".repeat(63), modelId.toString().toUpperCase()}) {
            var file =
                writeContainer("invalid-" + String.valueOf(property).hashCode() + ".mxc", property, manifest(modelId));
            try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
                assertThrows(IOException.class, () -> ModelFileIdentityReader.read(channel));
            }
        }
    }

    @Test
    void fullViewRejectsPropertyManifestMismatch() throws Exception {
        var file = writeContainer("mismatch.mxc", hash(3).toString(), manifest(hash(4)));

        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            assertThrows(IOException.class, () -> new ModelFileView(channel));
        }
    }

    @Test
    void identityPropertyParticipatesInContainerIdentity() throws Exception {
        var manifest = manifest(hash(6));
        var first = writeContainer("identity-a.mxc", hash(6).toString(), manifest);
        var second = writeContainer("identity-b.mxc", hash(7).toString(), manifest);

        try (var firstChannel = FileChannel.open(first, StandardOpenOption.READ);
            var secondChannel = FileChannel.open(second, StandardOpenOption.READ)) {
            assertNotEquals(AssetContainerReader.read(firstChannel).getContainerId(),
                AssetContainerReader.read(secondChannel).getContainerId());
        }
    }

    @Test
    void identityScanDoesNotReadManifestPayload() throws Exception {
        var modelId = hash(5);
        var file = writeWithProductionWriter("tampered-manifest.mxc", manifest(modelId));
        final int manifestOffset;
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            manifestOffset =
                AssetContainerReader.read(channel).getChunkInfo(ModelFileConstant.MANIFEST_CHUNK_NAME).offset();
        }
        var bytes = Files.readAllBytes(file);
        bytes[manifestOffset] ^= 1;
        Files.write(file, bytes);

        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            assertEquals(modelId, ModelFileIdentityReader.read(channel).modelId());
        }
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            var failure = assertThrows(AssetLoadException.class, () -> new ModelFileView(channel));
            assertEquals(AssetLoadException.Reason.CONTENT, failure.reason());
        }
    }

    @Test
    void preservesAccessFailureProvenance() throws Exception {
        var file = writeWithProductionWriter("access.mxc", manifest(hash(8)));
        try (var channel = new FailingReadChannel(FileChannel.open(file, StandardOpenOption.READ))) {
            var failure = assertThrows(AssetLoadException.class, () -> ModelFileIdentityReader.read(channel));
            assertEquals(AssetLoadException.Reason.ACCESS, failure.reason());
        }
    }

    @Test
    void metadataLayoutRequiresTheManifestToBeFirstDirectAndNonEmpty() throws Exception {
        var modelId = hash(9);
        var valid = writeWithProductionWriter("layout-valid.mxc", manifest(modelId));
        try (var channel = FileChannel.open(valid, StandardOpenOption.READ)) {
            ModelFileView.requireMetadataLayout(AssetContainerReader.read(channel));
        }
        for (var mode : InvalidLayout.values()) {
            final Path invalid;
            try (var writer = new InvalidLayoutWriter(modelId, mode)) {
                invalid = write("layout-" + mode + ".mxc", writer);
            }
            try (var channel = FileChannel.open(invalid, StandardOpenOption.READ)) {
                var asset = AssetContainerReader.read(channel);
                assertThrows(IOException.class, () -> ModelFileView.requireMetadataLayout(asset));
            }
        }
    }

    @Test
    void metadataPrefixIncludesLegalAlignmentPadding() throws Exception {
        var modelId = hash(10);
        var file = writeAlignedModel("aligned-prefix.mxc", modelId, manifest(modelId));
        final int prefixSize;
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            var asset = AssetContainerReader.read(channel);
            var manifest = ModelFileView.requireMetadataLayout(asset);
            assertNotEquals(0, manifest.alignSize());
            prefixSize = Math.addExact(manifest.offset(), manifest.size());
        }
        var fileBytes = Files.readAllBytes(file);
        assertEquals(prefixSize, fileBytes.length);
    }

    @Test
    void productionReopenAcceptsTexturelessProjectileAndVehicle() throws Exception {
        var modelId = hash(11);
        var manifest =
            manifest(modelId).toBuilder().addRenderTargets(renderTarget("player", RenderTargetKind.PLAYER, "main"))
                .addRenderTargets(renderTarget("projectile-1", RenderTargetKind.PROJECTILE, null))
                .addRenderTargets(renderTarget("vehicle-1", RenderTargetKind.VEHICLE, null)).build();
        var file = writeWithProductionWriter("textureless-non-player.mxc", manifest);

        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            var view = new ModelFileView(channel);
            assertEquals(0, view.requireRenderTarget("projectile-1").getTextureNames().size());
            assertEquals(0, view.requireRenderTarget("vehicle-1").getTextureNames().size());
        }
    }

    @Test
    void productionReopenRejectsEmptyTextureKeyForEveryTargetKind() throws Exception {
        var kinds =
            new RenderTargetKind[] {RenderTargetKind.PLAYER, RenderTargetKind.PROJECTILE, RenderTargetKind.VEHICLE,};
        for (int index = 0; index < kinds.length; ++index) {
            var kind = kinds[index];
            var modelId = hash(12 + index);
            var manifest = manifest(modelId).toBuilder();
            if (kind != RenderTargetKind.PLAYER) {
                manifest.addRenderTargets(renderTarget("player", RenderTargetKind.PLAYER, "main"));
            }
            manifest.addRenderTargets(renderTarget(kind == RenderTargetKind.PLAYER ? "player" : "target-1", kind, ""));
            var file = writeWithProductionWriter("empty-texture-key-" + index + ".mxc", manifest.build());

            try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
                assertThrows(IOException.class, () -> new ModelFileView(channel));
            }
        }
    }

    private Path writeWithProductionWriter(String name, ManifestProto manifest) throws Exception {
        try (var writer = new ModelFileWriter()) {
            writer.setManifest(manifest);
            return write(name, writer);
        }
    }

    @Test
    void productionReopenRejectsAudioBlobReusedByAnotherResourceRole() throws Exception {
        var head = java.util.Arrays.copyOf(
            Files.readAllBytes(Path.of("src/main/resources/assets/ysm/builtin/wine_fox/14_momo/sounds/fly_on.ogg")),
            47);
        var sound = SoundProto.newBuilder().setName("tone").setEncoding("OGG_OPUS").setChannels(1).setSampleRate(48_000)
            .setSamples(32_127).setFirstPage(ByteBuffer.wrap(head)).addChunkIds(1).build();
        var image =
            emptyImage().toBuilder().setBlobId(1).setFormat("PNG").setWidth(1).setHeight(1).setFrameCount(1).build();
        for (var role : new String[] {"definition", "strings", "uv", "normal", "specular", "foreground", "background",
            "avatar"}) {
            var manifest = manifest(hash(20)).toBuilder();
            var common = CommonProto.newBuilder().setStringsBlobId(0).addSounds(sound);
            var target = renderTarget("player", RenderTargetKind.PLAYER, "main").toBuilder();
            var texture = PBRTextureSetProto.newBuilder().setUv(emptyImage());
            var info = manifest.build().info().toBuilder();
            var settings = info.build().settings().toBuilder();
            switch (role) {
                case "definition" -> target.setBlobId(1);
                case "strings" -> common.setStringsBlobId(1);
                case "uv" -> texture.setUv(image);
                case "normal" -> texture.setNormal(image);
                case "specular" -> texture.setSpecular(image);
                case "foreground" -> settings.setGuiForeground(image);
                case "background" -> settings.setGuiBackground(image);
                case "avatar" -> info.setMetadata(info.build().metadataUnsafe().toBuilder()
                    .addAuthors(AuthorProto.newBuilder().setName("author").setRole("").setAvatar(image).build())
                    .build());
                default -> throw new AssertionError(role);
            }
            target.putTextures("main", texture.build());
            manifest.addRenderTargets(target.build()).setCommonAssets(common.build())
                .setInfo(info.setSettings(settings.build()).build());
            Path file;
            try (var writer = new ModelFileWriter(); var data = ArrayBuffer.move(new byte[] {1})) {
                assertEquals(1, writer.addBlob(data, 0));
                writer.setManifest(manifest.build());
                file = write("conflicting-audio-" + role + ".mxc", writer);
            }
            try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
                var failure = assertThrows(IOException.class, () -> new ModelFileView(channel));
                assertTrue(failure.getMessage().startsWith("Conflicting roles for blob-1:"), role);
            }
        }
    }

    private Path writeAlignedModel(String name, Hash256 modelId, ManifestProto manifest) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var data = ArrayBuffer.allocate(manifest.getSerializedSize()); var writer = new AssetContainerWriter();
            var channel = Channels.newChannel(output)) {
            manifest.writeTo(ProtoUtil.sink(data));
            writer.setSchema(ModelFileConstant.SCHEMA_ID);
            writer.setSchemaProperty(ModelFileConstant.PROP_VERSION, ModelFileConstant.CURRENT_VERSION.toString());
            writer.setSchemaProperty(ModelFileConstant.PROP_VENDOR, "test");
            writer.setSchemaProperty(ModelFileConstant.PROP_MODEL_ID, modelId.toString());
            writer.addChunk(ModelFileConstant.MANIFEST_CHUNK_NAME, "", 0, 8, 0, data, 0);
            writer.write(channel);
        }
        return Files.write(temp.resolve(name), output.toByteArray());
    }

    private Path writeContainer(String name, String property, ManifestProto manifest) throws Exception {
        try (var writer = new TestModelWriter(property, manifest)) {
            return write(name, writer);
        }
    }

    private Path writePreviousVersion(String name, Hash256 modelId, ManifestProto manifest) throws Exception {
        try (var writer = new VersionedModelWriter(modelId, manifest, "0.3.0-unstable")) {
            return write(name, writer);
        }
    }

    private Path write(String name, AssetFileWriter writer) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var channel = Channels.newChannel(output)) {
            writer.write(channel);
        }
        return Files.write(temp.resolve(name), output.toByteArray());
    }

    private static ManifestProto manifest(Hash256 modelId) {
        return manifest(ByteBuffer.wrap(modelId.bytes()));
    }

    private static ManifestProto manifest(ByteBuffer modelId) {
        return ManifestProto.newBuilder().setCommonAssets(CommonProto.newBuilder().setStringsBlobId(0).build())
            .setInfo(InfoProto.newBuilder()
                .setSettings(SettingsProto.newBuilder().setDefaultTexture("").setPreviewAnimation("")
                    .setDisablePreviewRotation(false).build())
                .setMetadata(MetadataProto.newBuilder().setName("").setTips("")
                    .setLicense(LicenseProto.newBuilder().setType("").setDesc("").build()).build())
                .setProperties(PropertiesProto.newBuilder().setModelId(modelId).setFree(false).setOriginVer("").build())
                .build())
            .build();
    }

    private static RenderTargetProto renderTarget(String targetId, RenderTargetKind kind, String textureKey) {
        var target = RenderTargetProto.newBuilder().setTargetId(targetId).setKind(kind).setBlobId(0)
            .setSettings(ModelSettingsProto.newBuilder().setHeightScale(0).setWidthScale(0).setRenderLayersFirst(false)
                .setForceCulling(false).setGuiNoLighting(false).setMergeMultilineExpr(false).build())
            .setStats(ModelStatsProto.newBuilder().setBones(0).setCubes(0).setFaces(0).build());
        if (textureKey != null) {
            target.putTextures(textureKey, PBRTextureSetProto.newBuilder().setUv(emptyImage()).build());
        }
        return target.build();
    }

    private static ImageProto emptyImage() {
        return ImageProto.newBuilder().setBlobId(0).setFormat("").setWidth(0).setHeight(0).setFrameCount(0).build();
    }

    private static Hash256 hash(int seed) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)seed;
        return new Hash256(bytes);
    }

    private static final class TestModelWriter extends AssetFileWriter {
        private TestModelWriter(String modelIdProperty, ManifestProto manifest) throws IOException {
            setSchemaId(ModelFileConstant.SCHEMA_ID);
            setProperty(ModelFileConstant.PROP_VERSION, ModelFileConstant.CURRENT_VERSION.toString());
            setProperty(ModelFileConstant.PROP_VENDOR, "test");
            if (modelIdProperty != null) {
                setProperty(ModelFileConstant.PROP_MODEL_ID, modelIdProperty);
            }
            addProtoChunk(ModelFileConstant.MANIFEST_CHUNK_NAME, manifest, 0);
        }
    }

    private static final class VersionedModelWriter extends AssetFileWriter {
        private VersionedModelWriter(Hash256 modelId, ManifestProto manifest, String version) throws IOException {
            setSchemaId(ModelFileConstant.SCHEMA_ID);
            setProperty(ModelFileConstant.PROP_VERSION, version);
            setProperty(ModelFileConstant.PROP_VENDOR, "Example Mod(example) v9.8.7");
            setProperty(ModelFileConstant.PROP_SIGNATURE, "test-signature");
            setProperty(ModelFileConstant.PROP_MODEL_ID, modelId.toString());
            addProtoChunk(ModelFileConstant.MANIFEST_CHUNK_NAME, manifest, 0);
        }
    }

    private static final class InvalidLayoutWriter extends AssetFileWriter {
        private InvalidLayoutWriter(Hash256 modelId, InvalidLayout mode) throws IOException {
            setSchemaId(ModelFileConstant.SCHEMA_ID);
            setProperty(ModelFileConstant.PROP_VERSION, ModelFileConstant.CURRENT_VERSION.toString());
            setProperty(ModelFileConstant.PROP_VENDOR, "test");
            setProperty(ModelFileConstant.PROP_MODEL_ID, modelId.toString());
            if (mode == InvalidLayout.BEFORE_MANIFEST) {
                addRawChunk("before-manifest", ArrayBuffer.borrow(new byte[] {1}), 0);
            }
            if (mode == InvalidLayout.EMPTY) {
                addRawChunk(ModelFileConstant.MANIFEST_CHUNK_NAME, ArrayBuffer.borrow(new byte[0]), 0);
            } else {
                addProtoChunk(ModelFileConstant.MANIFEST_CHUNK_NAME, manifest(modelId),
                    mode == InvalidLayout.COMPRESSED ? 1 : 0);
            }
        }
    }

    private enum InvalidLayout {
        BEFORE_MANIFEST, COMPRESSED, EMPTY
    }

    private static final class FailingReadChannel implements SeekableByteChannel {
        private final SeekableByteChannel delegate;

        private FailingReadChannel(SeekableByteChannel delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read(ByteBuffer destination) throws IOException {
            throw new IOException("offline");
        }

        @Override
        public int write(ByteBuffer source) throws IOException {
            return delegate.write(source);
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public SeekableByteChannel position(long position) throws IOException {
            delegate.position(position);
            return this;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
