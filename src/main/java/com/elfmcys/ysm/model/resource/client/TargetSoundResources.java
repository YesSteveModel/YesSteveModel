package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.SharedBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.views.SoundMedia;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;

import java.io.IOException;
import java.util.Arrays;
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
    private Map<String, SharedBuffer> firstChunks;
    private final Set<Hash256> validatedMedia;
    private final AtomicBoolean closed = new AtomicBoolean();

    private TargetSoundResources(ModelFileIdentity representation, Map<String, SharedBuffer> firstChunks,
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
    }

    public synchronized TargetSoundResources acquire() {
        requireOpen();
        return new TargetSoundResources(representation, firstChunks, validatedMedia);
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

    synchronized SharedBuffer acquireFirstChunk(ModelFileIdentity expected, SoundMedia media)
        throws AssetLoadException {
        requireAvailable(expected);
        var chunk = firstChunks.get(media.chunkInfos().get(0).type());
        if (chunk == null) {
            throw AssetLoadException.content("Ready target no longer owns the sound first chunk");
        }
        return chunk;
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
        return closed.get() ? 0 : firstChunks.values().stream().mapToInt(SharedBuffer::size).sum();
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        firstChunks = Map.of();
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Render target sound resources are closed");
        }
    }

    private record RetainedFirstChunk(AssetContainerView.ChunkInfo expected,
        SharedBuffer bytes) implements ChunkDataSource {
        @Override
        public SharedBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType) throws IOException {
            return readStoredVerified(chunk, bufferType);
        }

        @Override
        public SharedBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType)
            throws IOException {
            if (!sameChunk(expected, chunk)) {
                throw AssetLoadException.content("Prefix validation requested an undeclared sound chunk");
            }
            return bufferType == BufferType.NATIVE ? bytes.shareNative() : bytes.shareArray();
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
