package com.elfmcys.ysm.format.schema.model.views;

import com.elfmcys.ysm.buffer.SharedBuffer;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.schema.file.AssetFileView;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.proto.mixel.common.SoundProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.CommonProto;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class CommonAssetViewTest {
    @Test
    void treatsMissingSoundsAsEmptyWithoutMutatingMessage() throws IOException {
        var common = CommonProto.newBuilder().setStringsBlobId(0).build();

        var view = new CommonAssetView(common, new RecordingAssetFileView(Map.of()));

        assertTrue(common.sounds().isEmpty());
        assertTrue(view.sounds().isEmpty());
    }

    @Test
    void indexesValidatedDescriptorsWithoutReadingContent() throws IOException {
        var common =
            CommonProto.newBuilder().setStringsBlobId(0).addSounds(sound("first", "OGG_OPUS", 2, 48_000, 17, 11))
                .addSounds(sound("second", "OGG_OPUS", 1, 48_000, 23, 22)).build();
        var view = new CommonAssetView(common,
            new RecordingAssetFileView(Map.of(11, chunk(11, 100, "", 0, 0), 22, chunk(22, 200, "", 0, 0))));

        assertEquals("blob-11", view.sounds().get("first").chunkInfos().get(0).type());
        assertEquals("blob-22", view.sounds().get("second").chunkInfos().get(0).type());
        assertEquals(2, view.sounds().size());
    }

    @Test
    void rejectsDuplicateNamesAndConflictingSharedStreams() {
        var files = new RecordingAssetFileView(Map.of(1, chunk(1, 100, "", 0, 0)));
        var duplicateName = CommonProto.newBuilder().addSounds(sound("tone", "OGG_OPUS", 2, 48_000, 10, 1))
            .addSounds(sound("tone", "OGG_OPUS", 2, 48_000, 10, 1)).build();
        var conflictingMedia = CommonProto.newBuilder().addSounds(sound("first", "OGG_OPUS", 2, 48_000, 10, 1))
            .addSounds(sound("second", "OGG_OPUS", 2, 48_000, 11, 1)).build();

        assertThrows(IOException.class, () -> new CommonAssetView(duplicateName, files));
        assertThrows(IOException.class, () -> new CommonAssetView(conflictingMedia, files));
    }

    @Test
    void acceptsSoundAliasesSharingTheSameBlobSequence() throws IOException {
        var common = CommonProto.newBuilder().addSounds(sound("first", "OGG_OPUS", 2, 48_000, 10, 1))
            .addSounds(sound("alias", "OGG_OPUS", 2, 48_000, 10, 1)).build();
        var view = new CommonAssetView(common, new RecordingAssetFileView(Map.of(1, chunk(1, 100, "", 0, 0))));
        assertEquals(view.sounds().get("first").mediaIdentity(), view.sounds().get("alias").mediaIdentity());
    }

    @Test
    void rejectsInvalidDescriptorAndStorageShapes() {
        var direct = new RecordingAssetFileView(Map.of(1, chunk(1, 100, "", 0, 0)));
        var corruptHead = opusHeadPage(2);
        corruptHead[22] ^= 1;
        assertThrows(IOException.class,
            () -> new CommonAssetView(common(sound("", "OGG_OPUS", 2, 48_000, 10, 1)), direct));
        assertThrows(IOException.class,
            () -> new CommonAssetView(common(sound("tone", "opus", 2, 48_000, 10, 1)), direct));
        assertThrows(IOException.class,
            () -> new CommonAssetView(common(sound("tone", "OGG_OPUS", 3, 48_000, 10, 1)), direct));
        assertThrows(IOException.class,
            () -> new CommonAssetView(common(sound("tone", "OGG_VORBIS", 2, 48_000, 10, 1)), direct));
        assertThrows(IOException.class,
            () -> new CommonAssetView(common(sound("tone", "OGG_OPUS", 2, 44_100, 10, 1)), direct));
        assertThrows(IOException.class,
            () -> new CommonAssetView(common(sound("tone", "OGG_OPUS", 2, 48_000, Long.MIN_VALUE, 1)), direct));
        assertThrows(IOException.class, () -> new CommonAssetView(common(sound("tone", "OGG_OPUS", 2, 48_000, 10, 0)),
            new RecordingAssetFileView(Map.of())));
        assertThrows(IOException.class,
            () -> new CommonAssetView(common(sound("tone", "OGG_OPUS", 2, 48_000, 10, 2)), direct));
        assertThrows(IOException.class, () -> new CommonAssetView(common(sound("tone", "OGG_OPUS", 2, 48_000, 10, 1)),
            new RecordingAssetFileView(Map.of(1, chunk(1, 100, "zstd", 200, 1)))));
        assertThrows(IOException.class,
            () -> new CommonAssetView(common(sound("tone", "OGG_OPUS", 2, 48_000, 10, corruptHead, 1)), direct));
    }

    @Test
    void validatesExactContentOnlyAtTheExplicitM2Boundary() throws IOException {
        byte[] encoded = Files.readAllBytes(builtinOpus());
        int firstPageSize = pageSize(encoded, 0);
        byte[] firstPage = java.util.Arrays.copyOf(encoded, firstPageSize);
        byte[] remainder = java.util.Arrays.copyOfRange(encoded, firstPageSize, encoded.length);
        var fileView = new RecordingAssetFileView(Map.of(1, chunk(1, remainder.length, "", 0, 0)));
        var view = new CommonAssetView(common(sound("tone", "OGG_OPUS", 1, 48_000, 32_127, firstPage, 1)), fileView);
        var reads = new AtomicInteger();
        var source = new ChunkDataSource() {
            @Override
            public SharedBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType) {
                reads.incrementAndGet();
                return SharedBuffer.adopt(bufferType == BufferType.NATIVE
                    ? NativeBuffer.copyOf(ByteBuffer.wrap(remainder)) : ArrayBuffer.move(remainder.clone()));
            }

            @Override
            public SharedBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType) {
                throw new AssertionError("stored representation was not requested");
            }
        };

        assertEquals(0, reads.get());
        view.validateSoundContent(() -> false, source);
        assertEquals(1, reads.get());

        var corrupt = remainder.clone();
        corrupt[corrupt.length - 1] ^= 1;
        assertThrows(IOException.class, () -> view.validateSoundContent(() -> false, new ChunkDataSource() {
            @Override
            public SharedBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType) {
                return SharedBuffer.adopt(NativeBuffer.copyOf(ByteBuffer.wrap(corrupt)));
            }

            @Override
            public SharedBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType) {
                throw new AssertionError("stored representation was not requested");
            }
        }));
    }

    private static CommonProto common(SoundProto sound) {
        return CommonProto.newBuilder().addSounds(sound).build();
    }

    private static SoundProto sound(String name, String encoding, int channels, int sampleRate, long frames,
        int chunkId) {
        return sound(name, encoding, channels, sampleRate, frames, opusHeadPage(channels), chunkId);
    }

    private static SoundProto sound(String name, String encoding, int channels, int sampleRate, long frames,
        byte[] firstPage, int chunkId) {
        return SoundProto.newBuilder().setName(name).setEncoding(encoding).setChannels(channels)
            .setSampleRate(sampleRate).setSamples(frames).setFirstPage(ByteBuffer.wrap(firstPage)).addChunkIds(chunkId)
            .build();
    }

    private static AssetContainerView.ChunkInfo chunk(int chunkId, int size, String encoding, int decodeSize,
        int flags) {
        return new AssetContainerView.ChunkInfo("blob-" + Integer.toUnsignedLong(chunkId), encoding, 0, size,
            decodeSize, flags, 0, 0, new byte[32]);
    }

    private static byte[] opusHeadPage(int channels) {
        var page = ByteBuffer.allocate(47).order(ByteOrder.LITTLE_ENDIAN);
        page.put(new byte[] {'O', 'g', 'g', 'S', 0, 2});
        page.putLong(0).putInt(1).putInt(0).putInt(0);
        page.put((byte)1).put((byte)19);
        page.put(new byte[] {'O', 'p', 'u', 's', 'H', 'e', 'a', 'd'});
        page.put((byte)1).put((byte)channels).putShort((short)312).putInt(48_000).putShort((short)0).put((byte)0);
        var bytes = page.array();
        int crc = crc(bytes);
        ByteBuffer.wrap(bytes, 22, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc);
        return bytes;
    }

    private static int pageSize(byte[] bytes, int offset) {
        int segments = Byte.toUnsignedInt(bytes[offset + 26]);
        int size = 27 + segments;
        for (var index = 0; index < segments; ++index) {
            size += Byte.toUnsignedInt(bytes[offset + 27 + index]);
        }
        return size;
    }

    private static int crc(byte[] bytes) {
        int crc = 0;
        for (var value : bytes) {
            crc ^= Byte.toUnsignedInt(value) << 24;
            for (var bit = 0; bit < 8; ++bit) {
                crc = crc << 1 ^ ((crc & 0x8000_0000) != 0 ? 0x04c1_1db7 : 0);
            }
        }
        return crc;
    }

    private static Path builtinOpus() {
        return Path.of("src/main/resources/assets/ysm/builtin/wine_fox/14_momo/" + "sounds/fly_on.ogg").toAbsolutePath()
            .normalize();
    }

    private static final class RecordingAssetFileView extends AssetFileView {
        private final Map<Integer, AssetContainerView.ChunkInfo> chunks;

        private RecordingAssetFileView(Map<Integer, AssetContainerView.ChunkInfo> chunks) {
            super(null);
            this.chunks = chunks;
        }

        @Override
        public AssetContainerView.ChunkInfo blobChunkInfo(int id) {
            return chunks.get(id);
        }
    }
}
