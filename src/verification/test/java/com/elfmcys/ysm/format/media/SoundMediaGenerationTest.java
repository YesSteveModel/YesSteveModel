package com.elfmcys.ysm.format.media;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.format.container.AssetContainerReader;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.natives.sound.AudioNormalizer;
import com.elfmcys.ysm.format.schema.model.ModelFileWriter;
import com.elfmcys.ysm.proto.mixel.manifest.ManifestProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.CommonProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.InfoProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.LicenseProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.MetadataProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.PropertiesProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.util.stream.IntStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class SoundMediaGenerationTest {
    @Test
    void thresholdIsExactAndStrict() {
        assertFalse(SoundMediaGeneration.exceedsTargetRate(35, 192));
        assertTrue(SoundMediaGeneration.exceedsTargetRate(36, 192));
        assertFalse(SoundMediaGeneration.exceedsTargetRate(35, Long.MAX_VALUE));
    }

    @Test
    void equalCompleteContainerCostRetainsR() throws Exception {
        try (var source = source("wine_fox/14_momo/sounds/fly_on.ogg");
            var selected = SoundMediaGeneration.normalize(source, 7, candidate -> 1_000)) {
            assertEquals(AudioNormalizer.Role.RETAINED, selected.candidate().role());
        }
    }

    @Test
    void lowerCompleteContainerCostSelectsT() throws Exception {
        var measured = new AtomicInteger();
        try (var source = source("wine_fox/14_momo/sounds/fly_on.ogg");
            var selected = SoundMediaGeneration.normalize(source, 11, candidate -> {
                measured.incrementAndGet();
                return candidate.role() == AudioNormalizer.Role.TRANSCODED ? 999 : 1_000;
            })) {
            assertEquals(2, measured.get());
            assertEquals(AudioNormalizer.Role.TRANSCODED, selected.candidate().role());
        }
    }

    @Test
    void vorbisRequiresTWithoutRunningOptionalCostComparison() throws Exception {
        var measured = new AtomicInteger();
        try (var source = source("wine_fox/18_wedding/sounds/motor.ogg");
            var selected = SoundMediaGeneration.normalize(source, 13, candidate -> {
                measured.incrementAndGet();
                return 0;
            })) {
            assertEquals(0, measured.get());
            assertEquals(AudioNormalizer.Role.TRANSCODED, selected.candidate().role());
        }
    }

    @Test
    void completeSizerMatchesActualWriterLengthWithSharedBlobIds(@TempDir Path temp) throws Exception {
        var chunks = new ByteBuffer[] {direct(1, 2, 3), direct(4, 5, 6, 7)};
        var manifest = manifest();
        try (var writer = new ModelFileWriter()) {
            assertEquals(1, writer.addProtoBlob(StringDataProto.newBuilder().build(), 16));
            try (var data = NativeBuffer.copyOf(direct(8, 9));
                var image = new Image(Image.Format.PNG, 1, 1, data.acquire())) {
                assertEquals(2, writer.addImageBlob(image));
                for (var id = 3; id <= 9; ++id) {
                    assertEquals(id, writer.addBlob(data, 0));
                }
            }
            assertEquals(10, writer.nextBlobId());
            long measured = writer.serializedLength(manifest, chunks);
            assertEquals(measured, writer.serializedLength(manifest, chunks));
            assertEquals(10, writer.nextBlobId());
            assertArrayEquals(new int[] {10, 11}, writer.addSoundChunks(chunks));
            writer.setManifest(manifest);
            var output = new ByteArrayOutputStream();
            try (var channel = Channels.newChannel(output)) {
                writer.write(channel);
            }
            assertEquals(output.size(), measured);
            var file = Files.write(temp.resolve("shared-blobs.mxc"), output.toByteArray());
            try (var channel = FileChannel.open(file)) {
                var container = AssetContainerReader.read(channel);
                assertEquals(
                    IntStream.rangeClosed(1, 11).mapToObj(id -> "blob-" + id)
                        .collect(java.util.stream.Collectors.toSet()),
                    container.getChunkTable().keySet().stream()
                        .filter(type -> !type.equals("verification") && !type.equals("manifest"))
                        .collect(java.util.stream.Collectors.toSet()));
                var audio = container.getChunkInfo("blob-10");
                assertEquals("", audio.encoding());
                assertEquals(0, audio.decodeSize());
                assertEquals(0, audio.flags());
                assertEquals(chunks[0].remaining(), audio.size());
            }
            try (var data = NativeBuffer.copyOf(direct(10))) {
                assertEquals(12, writer.addBlob(data, 0));
            }
            assertArrayEquals(new int[] {13, 14}, writer.addSoundChunks(chunks));
            assertEquals(15, writer.nextBlobId());
        }
    }

    private static NativeBuffer source(String relative) throws Exception {
        var bytes = Files.readAllBytes(Path.of("src/main/resources/assets/ysm/builtin").resolve(relative));
        return NativeBuffer.copyOf(ByteBuffer.wrap(bytes));
    }

    private static ByteBuffer direct(int... values) {
        var buffer = ByteBuffer.allocateDirect(values.length);
        for (var value : values) {
            buffer.put((byte)value);
        }
        return buffer.flip();
    }

    private static ManifestProto manifest() {
        return ManifestProto.newBuilder().setCommonAssets(CommonProto.newBuilder().setStringsBlobId(0).build())
            .setInfo(InfoProto.newBuilder()
                .setSettings(SettingsProto.newBuilder().setDefaultTexture("").setPreviewAnimation("")
                    .setDisablePreviewRotation(false).build())
                .setMetadata(MetadataProto.newBuilder().setName("").setTips("")
                    .setLicense(LicenseProto.newBuilder().setType("").setDesc("").build()).build())
                .setProperties(PropertiesProto.newBuilder().setModelId(ByteBuffer.wrap(new byte[32])).setFree(false)
                    .setOriginVer("").build())
                .build())
            .build();
    }
}
