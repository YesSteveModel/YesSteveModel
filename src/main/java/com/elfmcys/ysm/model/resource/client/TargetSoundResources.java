package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.views.SoundMedia;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Owns one preparation or consumer's references to validated first chunks. */
public final class TargetSoundResources implements AutoCloseable {
    private final ModelFileIdentity representation;
    private final Map<String, UniBuffer> firstChunks;
    private final Set<Hash256> validatedMedia;
    private final AtomicBoolean closed = new AtomicBoolean();

    private TargetSoundResources(ModelFileIdentity representation, Map<String, UniBuffer> firstChunks,
        Set<Hash256> validatedMedia) {
        this.representation = representation;
        this.firstChunks = Map.copyOf(firstChunks);
        this.validatedMedia = Set.copyOf(validatedMedia);
    }

    public static TargetSoundResources empty(ModelFileIdentity representation) {
        return new TargetSoundResources(Objects.requireNonNull(representation, "representation"), Map.of(), Set.of());
    }

    public static TargetSoundResources prepare(BooleanSupplier cancelled, ModelFileIdentity representation,
        Map<String, SoundMedia> sounds, ChunkDataSource source) throws IOException {
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(representation, "representation");
        Objects.requireNonNull(sounds, "sounds");
        Objects.requireNonNull(source, "source");
        var firstChunks = source.readPayloads(cancelled,
            sounds.values().stream().map(sound -> sound.chunkInfos().get(0)).toList(), BufferType.NATIVE);
        var validated = new HashSet<Hash256>();
        try {
            for (var sound : sounds.values()) {
                var first = sound.chunkInfos().get(0);
                var retained = firstChunks.get(first.type());
                if (validated.add(sound.mediaIdentity())) {
                    sound.validatePrefix(cancelled, new RetainedFirstChunk(first, retained));
                }
            }
            if (cancelled.getAsBoolean())
                throw new CancellationException("Sound preparation was cancelled");
            return new TargetSoundResources(representation, firstChunks, validated);
        } catch (IOException | RuntimeException | Error failure) {
            firstChunks.values().forEach(UniBuffer::close);
            throw failure;
        }
    }

    public synchronized TargetSoundResources acquire() {
        requireOpen();
        var retained = new LinkedHashMap<String, UniBuffer>();
        try {
            firstChunks.forEach((type, bytes) -> retained.put(type, bytes.acquire()));
            return new TargetSoundResources(representation, retained, validatedMedia);
        } catch (RuntimeException | Error failure) {
            retained.values().forEach(UniBuffer::close);
            throw failure;
        }
    }

    public SoundSource bind(SoundMedia media) {
        Objects.requireNonNull(media, "media");
        requireOpen();
        var first = media.chunkInfos().get(0);
        if (!validatedMedia.contains(media.mediaIdentity()) || !firstChunks.containsKey(first.type())) {
            throw new IllegalArgumentException("Sound first chunk was not prepared for this target: " + media.name());
        }
        return new SoundSource(representation, media, this);
    }

    synchronized UniBuffer acquireFirstChunk(ModelFileIdentity expected, SoundMedia media) throws AssetLoadException {
        requireAvailable(expected);
        var chunk = firstChunks.get(media.chunkInfos().get(0).type());
        if (chunk == null) {
            throw AssetLoadException.content("Ready target no longer owns the sound first chunk");
        }
        return chunk.acquire();
    }

    private void requireAvailable(ModelFileIdentity expected) throws AssetLoadException {
        if (closed.get()) {
            throw AssetLoadException.access("Render target sound resources are no longer active");
        }
        if (!representation.equals(expected)) {
            throw AssetLoadException.content("Render target sound representation mismatch");
        }
    }

    public synchronized int retainedBytes() {
        return closed.get() ? 0 : firstChunks.values().stream().mapToInt(UniBuffer::size).sum();
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        firstChunks.values().forEach(UniBuffer::close);
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Render target sound resources are closed");
        }
    }

    private record RetainedFirstChunk(AssetContainerView.ChunkInfo expected,
        UniBuffer bytes) implements ChunkDataSource {
        @Override
        public UniBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType) throws IOException {
            return readStoredVerified(chunk, bufferType);
        }

        @Override
        public UniBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType)
            throws IOException {
            if (!sameChunk(expected, chunk)) {
                throw AssetLoadException.content("Prefix validation requested an undeclared sound chunk");
            }
            return bufferType == BufferType.NATIVE ? bytes.acquireNative() : bytes.acquireArray();
        }

        private static boolean sameChunk(AssetContainerView.ChunkInfo expected, AssetContainerView.ChunkInfo actual) {
            return TargetSoundResources.sameChunk(expected, actual);
        }
    }

    private static boolean sameChunk(AssetContainerView.ChunkInfo expected, AssetContainerView.ChunkInfo actual) {
        return expected != null && expected.type().equals(actual.type()) && expected.offset() == actual.offset()
            && expected.size() == actual.size() && expected.decodeSize() == actual.decodeSize()
            && expected.encoding().equals(actual.encoding()) && Arrays.equals(expected.hash(), actual.hash());
    }
}
