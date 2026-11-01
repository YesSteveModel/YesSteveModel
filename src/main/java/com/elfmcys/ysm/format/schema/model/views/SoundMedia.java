package com.elfmcys.ysm.format.schema.model.views;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.media.SupportedAudioProbe;
import com.elfmcys.ysm.format.schema.file.AssetFileView;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.storage.ModelHashing;
import com.elfmcys.ysm.natives.sound.OpusDecoder;
import com.elfmcys.ysm.proto.mixel.common.SoundProto;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Immutable metadata and ordered locators for one selected Opus representation. */
public final class SoundMedia {
    public static final int REGULAR_CHUNK_LIMIT = 57_344;
    private static final int OPUS_HEAD_SIZE = 19;
    private static final int PCM_SCRATCH_SIZE = 8 * 1024;

    private final String name;
    private final int channels;
    private final long frames;
    private final int preSkip;
    private final int outputGain;
    private final byte[] firstPage;
    private final List<AssetContainerView.ChunkInfo> chunks;
    private final List<ChunkLocator> locators;
    private final Hash256 mediaIdentity;

    static SoundMedia create(SoundProto sound, AssetFileView fileView) throws IOException {
        Objects.requireNonNull(sound, "sound");
        Objects.requireNonNull(fileView, "fileView");
        requireName(sound.name());
        if (!sound.encoding().equals("OGG_OPUS")) {
            throw new IOException("Sound is not Opus: " + sound.name());
        }
        int channels = sound.channels();
        if (channels != 1 && channels != 2) {
            throw new IOException("Sound has invalid channel count: " + sound.name());
        }
        if (Integer.toUnsignedLong(sound.sampleRate()) != 48_000) {
            throw new IOException("Sound has invalid sample rate: " + sound.name());
        }
        long frames = sound.samples();
        if (frames <= 0) {
            throw new IOException("Sound has invalid frame count: " + sound.name());
        }

        var firstPage = copy(sound.firstPage());
        var head = parseHead(firstPage, channels, sound.name());
        if (sound.chunkIds().isEmpty()) {
            throw new IOException("Sound has no media chunks: " + sound.name());
        }
        var ids = new HashSet<Integer>();
        var chunks = new ArrayList<AssetContainerView.ChunkInfo>(sound.chunkIds().size());
        var locators = new ArrayList<ChunkLocator>(sound.chunkIds().size());
        for (var index = 0; index < sound.chunkIds().size(); ++index) {
            int id = sound.chunkIds().getInt(index);
            if (id == 0 || !ids.add(id)) {
                throw new IOException("Sound has an invalid chunk sequence: " + sound.name());
            }
            var chunk = fileView.blobChunkInfo(id);
            if (chunk == null) {
                throw new IOException("Sound media chunk is missing: " + sound.name());
            }
            if (!chunk.encoding().isEmpty() || chunk.decodeSize() != 0 || chunk.flags() != 0 || chunk.hash() == null) {
                throw new IOException("Sound media chunk is not direct verified storage: " + sound.name());
            }
            if (index > 0 && chunk.size() > REGULAR_CHUNK_LIMIT) {
                throw new IOException("Sound media chunk exceeds the regular limit: " + sound.name());
            }
            chunks.add(copyChunk(chunk));
            locators.add(new ChunkLocator(chunk.type(), chunk.size(), chunk.hash()));
        }
        return new SoundMedia(sound.name(), channels, frames, head.preSkip(), head.outputGain(), firstPage, chunks,
            locators);
    }

    private SoundMedia(String name, int channels, long frames, int preSkip, int outputGain, byte[] firstPage,
        List<AssetContainerView.ChunkInfo> chunks, List<ChunkLocator> locators) {
        this.name = name;
        this.channels = channels;
        this.frames = frames;
        this.preSkip = preSkip;
        this.outputGain = outputGain;
        this.firstPage = firstPage;
        this.chunks = List.copyOf(chunks);
        this.locators = List.copyOf(locators);
        mediaIdentity = mediaIdentity(firstPage, channels, frames, preSkip, outputGain, locators);
    }

    public void validatePrefix(BooleanSupplier cancelled, ChunkDataSource source) throws IOException {
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(source, "source");
        try (var decoder = OpusDecoder.create(frames); var scratch = NativeBuffer.allocate(PCM_SCRATCH_SIZE);
            var head = NativeBuffer.copyOf(ByteBuffer.wrap(firstPage))) {
            var first = source.readPayload(cancelled, chunks.get(0), BufferType.NATIVE);
            try {
                feed(decoder, head.nio(), scratch, false, cancelled);
                if (!feed(decoder, first.nio(), scratch, true, cancelled)) {
                    throw content("Sound prefix produced no effective PCM");
                }
            } finally {
                java.lang.ref.Reference.reachabilityFence(first);
            }
        }
    }

    public void validateComplete(BooleanSupplier cancelled, ChunkDataSource source) throws IOException {
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(source, "source");
        try (var decoder = OpusDecoder.create(frames); var scratch = NativeBuffer.allocate(PCM_SCRATCH_SIZE);
            var head = NativeBuffer.copyOf(ByteBuffer.wrap(firstPage))) {
            feed(decoder, head.nio(), scratch, false, cancelled);
            for (var chunk : chunks) {
                if (cancelled.getAsBoolean()) {
                    throw new java.util.concurrent.CancellationException("Sound validation was cancelled");
                }
                var payload = source.readPayload(cancelled, chunk, BufferType.NATIVE);
                try {
                    feed(decoder, payload.nio(), scratch, false, cancelled);
                } finally {
                    java.lang.ref.Reference.reachabilityFence(payload);
                }
            }
            decoder.endInput();
            for (;;) {
                int decoded = decoder.decode(scratch.nio());
                if (decoded > 0) {
                    continue;
                }
                if (decoded == 0) {
                    return;
                }
                throw content("Sound did not reach a verified EOF");
            }
        } catch (IllegalArgumentException failure) {
            throw content("Sound media is invalid", failure);
        }
    }

    private static boolean feed(OpusDecoder decoder, ByteBuffer input, NativeBuffer scratch, boolean stopAfterOutput,
        BooleanSupplier cancelled) throws IOException {
        boolean produced = false;
        while (input.hasRemaining()) {
            if (cancelled.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException("Sound validation was cancelled");
            }
            if (decoder.feed(input) == 0) {
                throw content("Sound decoder made no input progress");
            }
            for (;;) {
                int decoded = decoder.decode(scratch.nio());
                if (decoded > 0) {
                    produced = true;
                    if (stopAfterOutput) {
                        return true;
                    }
                } else if (decoded == OpusDecoder.NEED_INPUT) {
                    break;
                } else {
                    throw content("Sound decoder rejected the media prefix");
                }
            }
        }
        return produced;
    }

    boolean hasSameMedia(SoundMedia other) {
        return channels == other.channels && frames == other.frames && preSkip == other.preSkip
            && outputGain == other.outputGain && Arrays.equals(firstPage, other.firstPage)
            && locators.equals(other.locators);
    }

    public String name() {
        return name;
    }

    public SupportedAudioProbe.Encoding encoding() {
        return SupportedAudioProbe.Encoding.OGG_OPUS;
    }

    public int channels() {
        return channels;
    }

    public long sampleRate() {
        return 48_000;
    }

    public long frames() {
        return frames;
    }

    public int preSkip() {
        return preSkip;
    }

    public int outputGain() {
        return outputGain;
    }

    public ByteBuffer firstPage() {
        return ByteBuffer.wrap(firstPage).asReadOnlyBuffer();
    }

    public List<ChunkLocator> locators() {
        return locators;
    }

    public List<AssetContainerView.ChunkInfo> chunkInfos() {
        return chunks.stream().map(SoundMedia::copyChunk).toList();
    }

    public Hash256 mediaIdentity() {
        return mediaIdentity;
    }

    private static Head parseHead(byte[] page, int declaredChannels, String name) throws IOException {
        if (page.length != 27 + 1 + OPUS_HEAD_SIZE || page[0] != 'O' || page[1] != 'g' || page[2] != 'g'
            || page[3] != 'S' || page[4] != 0 || page[5] != 2 || readLong(page, 6) != 0 || readInt(page, 18) != 0
            || Byte.toUnsignedInt(page[26]) != 1 || Byte.toUnsignedInt(page[27]) != OPUS_HEAD_SIZE) {
            throw new IOException("Sound has an invalid OpusHead page: " + name);
        }
        byte[] signature = {'O', 'p', 'u', 's', 'H', 'e', 'a', 'd'};
        for (var index = 0; index < signature.length; ++index) {
            if (page[28 + index] != signature[index]) {
                throw new IOException("Sound has no OpusHead packet: " + name);
            }
        }
        int channels = Byte.toUnsignedInt(page[37]);
        if (page[36] != 1 || channels != declaredChannels || page[46] != 0 || crc(page) != 0) {
            throw new IOException("Sound OpusHead does not match its descriptor: " + name);
        }
        return new Head(readUnsignedShort(page, 38), readShort(page, 44));
    }

    private static Hash256 mediaIdentity(byte[] firstPage, int channels, long frames, int preSkip, int outputGain,
        List<ChunkLocator> locators) {
        int size = Math.addExact(28 + firstPage.length, Math.multiplyExact(locators.size(), 40));
        var input = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN).putInt(channels).putLong(frames)
            .putInt(preSkip).putInt(outputGain).putInt(firstPage.length).put(firstPage).putInt(locators.size());
        for (var locator : locators) {
            input.putInt(locator.storedSize()).putInt(locator.chunkType().hashCode())
                .put(locator.logicalHash().bytes());
        }
        input.flip();
        return ModelHashing.blake3(input);
    }

    private static byte[] copy(ByteBuffer source) {
        var value = new byte[source.remaining()];
        source.duplicate().get(value);
        return value;
    }

    private static void requireName(String name) throws IOException {
        if (name.isEmpty()) {
            throw new IOException("Sound name is empty");
        }
        try {
            StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(name));
        } catch (CharacterCodingException error) {
            throw new IOException("Sound name is not well-formed UTF-8", error);
        }
    }

    private static AssetContainerView.ChunkInfo copyChunk(AssetContainerView.ChunkInfo chunk) {
        return new AssetContainerView.ChunkInfo(chunk.type(), chunk.encoding(), chunk.offset(), chunk.size(),
            chunk.decodeSize(), chunk.flags(), chunk.alignSize(), chunk.alignmentShift(), chunk.hash().clone());
    }

    private static int readUnsignedShort(byte[] value, int offset) {
        return Byte.toUnsignedInt(value[offset]) | Byte.toUnsignedInt(value[offset + 1]) << 8;
    }

    private static short readShort(byte[] value, int offset) {
        return (short)readUnsignedShort(value, offset);
    }

    private static int readInt(byte[] value, int offset) {
        return ByteBuffer.wrap(value, offset, Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static long readLong(byte[] value, int offset) {
        return ByteBuffer.wrap(value, offset, Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private static int crc(byte[] value) {
        int crc = 0;
        for (var index = 0; index < value.length; ++index) {
            int next = index >= 22 && index < 26 ? 0 : Byte.toUnsignedInt(value[index]);
            crc ^= next << 24;
            for (var bit = 0; bit < 8; ++bit) {
                crc = crc << 1 ^ ((crc & 0x8000_0000) != 0 ? 0x04c1_1db7 : 0);
            }
        }
        return crc ^ readInt(value, 22);
    }

    private static AssetLoadException content(String message) {
        return AssetLoadException.content(message);
    }

    private static AssetLoadException content(String message, Throwable cause) {
        return AssetLoadException.content(message, cause);
    }

    private record Head(int preSkip, int outputGain) {
    }

    public record ChunkLocator(String chunkType, int storedSize, Hash256 logicalHash) {
        public ChunkLocator {
            Objects.requireNonNull(chunkType, "chunkType");
            Objects.requireNonNull(logicalHash, "logicalHash");
        }

        private ChunkLocator(String chunkType, int storedSize, byte[] logicalHash) {
            this(chunkType, storedSize, new Hash256(logicalHash));
        }
    }

}
