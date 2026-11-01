package com.elfmcys.ysm.model.resource.client.audio;

import com.elfmcys.ysm.buffer.SharedBuffer;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.media.SupportedAudioProbe;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.file.AssetFileView;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.views.CommonAssetView;
import com.elfmcys.ysm.format.schema.model.views.SoundMedia;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.resource.client.SoundSource;
import com.elfmcys.ysm.model.resource.client.TargetSoundResources;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelHashing;
import com.elfmcys.ysm.natives.sound.OpusDecoder;
import com.elfmcys.ysm.proto.mixel.common.SoundProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.CommonProto;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import javax.sound.sampled.AudioFormat;

/** Repartitions real encoded pages without changing codec packets or their timeline. */
final class AudioPlaybackFixture implements AutoCloseable {
    static final AudioFormat FORMAT = new AudioFormat(48_000, 16, 1, true, false);
    final SoundMedia media;
    final SoundSource source;
    final ModelContent content;
    final List<Integer> reads = new ArrayList<>();
    final List<byte[]> payloads;
    private final ManagedContainer base;
    private final TargetSoundResources resources;
    IOException tailFailure;

    static AudioPlaybackFixture create(Path directory, String resource) throws Exception {
        return new AudioPlaybackFixture(directory, resource);
    }

    private AudioPlaybackFixture(Path directory, String resource) throws Exception {
        byte[] encoded;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream(resource), resource)) {
            encoded = input.readAllBytes();
        }
        var inspection = SupportedAudioProbe.inspect(ByteBuffer.wrap(encoded));
        if (!inspection.playable()) {
            throw new IOException(inspection.diagnostic());
        }
        var pages = new ArrayList<byte[]>();
        for (int offset = 0; offset < encoded.length;) {
            int segments = Byte.toUnsignedInt(encoded[offset + 26]);
            int size = 27 + segments;
            for (int index = 0; index < segments; index++) {
                size += Byte.toUnsignedInt(encoded[offset + 27 + index]);
            }
            pages.add(Arrays.copyOfRange(encoded, offset, offset + size));
            offset += size;
        }
        int firstEnd = Math.min(pages.size(), 3);
        var first = new ByteArrayOutputStream();
        for (int index = 1; index < firstEnd; index++) {
            first.writeBytes(pages.get(index));
        }
        payloads = new ArrayList<>();
        payloads.add(first.toByteArray());
        payloads.addAll(pages.subList(firstEnd, pages.size()));
        var chunks = new LinkedHashMap<Integer, AssetContainerView.ChunkInfo>();
        var descriptor = SoundProto.newBuilder().setName("tone").setEncoding("OGG_OPUS")
            .setChannels(inspection.media().channels()).setSampleRate(48_000).setSamples(inspection.media().frames())
            .setFirstPage(ByteBuffer.wrap(pages.get(0)));
        for (int index = 0; index < payloads.size(); index++) {
            var payload = payloads.get(index);
            chunks.put(index + 1, new AssetContainerView.ChunkInfo("blob-" + (index + 1), "", 0, payload.length, 0, 0,
                0, 0, ModelHashing.blake3(ByteBuffer.wrap(payload)).bytes()));
            descriptor.addChunkIds(index + 1);
        }
        media = new CommonAssetView(CommonProto.newBuilder().addSounds(descriptor.build()).build(),
            new AssetFileView(null) {
                @Override
                public AssetContainerView.ChunkInfo blobChunkInfo(int id) {
                    return chunks.get(id);
                }
            }).sounds().get("tone");
        Files.createDirectories(directory);
        var manifest = Objects.requireNonNull(getClass().getResource("/assets/ysm/builtin/default/ysm.json"));
        Path container;
        try (var input = new Directory(Path.of(manifest.toURI()).getParent())) {
            container = ModelParser.parseBuiltinDefault(input, directory.resolve("base"));
        }
        base = ManagedContainer.openDirect(container,
            new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("audio-fixture")));
        content = new ModelContent() {
            @Override
            public ModelRepresentation representation() {
                return base.representation();
            }

            @Override
            public ChunkDataSource chunks() {
                return new ChunkDataSource() {
                    @Override
                    public SharedBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType type)
                        throws IOException {
                        int index = Integer.parseInt(chunk.type().substring("blob-".length())) - 1;
                        reads.add(index);
                        if (index > 0 && tailFailure != null) {
                            throw tailFailure;
                        }
                        var bytes = payloads.get(index);
                        return SharedBuffer.adopt(type == BufferType.NATIVE
                            ? NativeBuffer.copyOf(ByteBuffer.wrap(bytes)) : ArrayBuffer.copyOf(ByteBuffer.wrap(bytes)));
                    }

                    @Override
                    public SharedBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType type)
                        throws IOException {
                        return readPayload(chunk, type);
                    }
                };
            }
        };
        resources = TargetSoundResources.prepare(() -> false, base.representation().identity(), Map.of("tone", media),
            content.chunks());
        source = resources.bind(media);
        reads.clear();
    }

    VerifiedChunkAccess access(Executor inputWorkers) throws com.elfmcys.ysm.format.AssetLoadException {
        return VerifiedChunkAccess.create(source, content, null, inputWorkers, Runnable::run);
    }

    CachedAudio.Prefix prefix() throws IOException {
        try (var decoder = OpusDecoder.create(media.frames()); var scratch = NativeBuffer.allocate(8192)) {
            var pcm = new ByteArrayOutputStream();
            feed(decoder, media.firstPage(), scratch, pcm);
            feed(decoder, ByteBuffer.wrap(payloads.get(0)), scratch, pcm);
            return new CachedAudio.Prefix(new PcmAudio(pcm.toByteArray(), FORMAT), decoder.checkpoint());
        }
    }

    byte[] reference() throws IOException {
        try (var decoder = OpusDecoder.create(media.frames()); var scratch = NativeBuffer.allocate(8192)) {
            var pcm = new ByteArrayOutputStream();
            feed(decoder, media.firstPage(), scratch, pcm);
            for (var bytes : payloads) {
                feed(decoder, ByteBuffer.wrap(bytes), scratch, pcm);
            }
            decoder.endInput();
            if (drain(decoder, scratch, pcm) != 0) {
                throw new IOException("Reference decode did not reach EOF");
            }
            return pcm.toByteArray();
        }
    }

    private static void feed(OpusDecoder decoder, ByteBuffer bytes, NativeBuffer scratch, ByteArrayOutputStream pcm)
        throws IOException {
        try (var input = NativeBuffer.copyOf(bytes)) {
            var cursor = input.nio();
            while (cursor.hasRemaining()) {
                if (decoder.feed(cursor) == 0 || drain(decoder, scratch, pcm) != OpusDecoder.NEED_INPUT) {
                    throw new IOException("Reference decode rejected an Ogg page");
                }
            }
        }
    }

    private static int drain(OpusDecoder decoder, NativeBuffer scratch, ByteArrayOutputStream pcm) {
        for (;;) {
            int decoded = decoder.decode(scratch.nio());
            if (decoded <= 0) {
                return decoded;
            }
            var bytes = new byte[decoded];
            scratch.nio().get(bytes);
            pcm.writeBytes(bytes);
        }
    }

    static long nativeCount(String name) throws Exception {
        return (long)nativeCall(name);
    }

    static Object nativeCall(String name) throws Exception {
        var method = OpusDecoder.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(null);
    }

    @Override
    public void close() {
        resources.close();
        base.representation().close();
    }
}
