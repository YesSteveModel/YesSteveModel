package com.elfmcys.ysm.model.resource.client.remote;

import com.elfmcys.ysm.model.storage.ContentStorageBinding;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.buffer.SharedBuffer;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.container.ChunkDecoding;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.format.schema.model.views.SoundMedia;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** Verified remote metadata with cache-first, on-demand chunk access. */
public final class RemoteModelContent implements ModelContent {
    private final ModelRepresentation representation;
    private final ChunkDataSource chunks;
    private final ChunkDataSource cachedChunks;
    private final RemoteModelStore store;
    private final ContentStorageBinding storageBinding;

    RemoteModelContent(ModelRepresentation representation, ChunkDataSource chunks, ChunkDataSource cachedChunks,
        RemoteModelStore store) {
        this.representation = Objects.requireNonNull(representation, "representation");
        this.chunks = Objects.requireNonNull(chunks, "chunks");
        this.cachedChunks = Objects.requireNonNull(cachedChunks, "cachedChunks");
        this.store = Objects.requireNonNull(store, "store");
        storageBinding = store.storageBinding();
    }

    @Override
    public ContentStorageBinding storageBinding() {
        return storageBinding;
    }

    @Override
    public ModelRepresentation representation() {
        return representation;
    }

    @Override
    public ChunkDataSource chunks() {
        return chunks;
    }

    public <T> CompletableFuture<T> loadRenderTarget(BooleanSupplier cancelled, String targetId, String textureName,
        RemoteChunkFetcher fetcher, Executor workers, Function<ModelContent, T> load) {
        return store.loadRenderTarget(this, targetId, textureName, cancelled, fetcher, workers, load);
    }

    public CompletableFuture<SharedBuffer> requestSoundChunk(SoundMedia sound, int index, RemoteChunkFetcher fetcher,
        Executor workers) {
        Objects.requireNonNull(sound, "sound");
        var chunks = sound.chunkInfos();
        if (index < 0 || index >= chunks.size()) {
            return CompletableFuture.failedFuture(new IndexOutOfBoundsException("Sound chunk index: " + index));
        }
        return store.requestChunk(this, chunks.get(index), fetcher, workers);
    }

    public void publish(AssetContainerView.ChunkInfo chunk, UniBuffer bytes) throws IOException {
        store.publishChunk(representation.modelId(), chunk, bytes);
    }

    /** Opens this exact representation only when every lazy chunk is cached and verified. */
    public Optional<ModelContent> openCached(BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled, "cancelled");
        var asset = representation.view().getFileView().getAssetView();
        var manifest = Objects.requireNonNull(asset.getChunkInfo(ModelFileConstant.MANIFEST_CHUNK_NAME), "manifest");
        var metadataEnd = Math.addExact(manifest.offset(), manifest.size());
        var lazyChunks = asset
            .getChunkTable().values().stream().filter(chunk -> chunk.offset() >= metadataEnd).sorted(Comparator
                .comparingInt(AssetContainerView.ChunkInfo::offset).thenComparing(AssetContainerView.ChunkInfo::type))
            .toList();

        try {
            for (var chunk : lazyChunks) {
                cachedChunks.readStoredVerified(chunk, BufferType.ARRAY);
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("Cached model verification was cancelled");
                }
            }
            return cancelled.getAsBoolean() ? Optional.empty()
                : Optional.of(new CachedModelContent(representation, cachedChunks, storageBinding()));
        } catch (IOException | CancellationException unavailable) {
            return Optional.empty();
        }
    }

    /** A target probe must not require unrelated chunks or acquire network access. */
    public ModelContent cachedOnlyView() {
        return new CachedModelContent(representation, cachedChunks, storageBinding());
    }

    RemoteModelContent withReceived(Map<String, ? extends UniBuffer> received) {
        return new RemoteModelContent(representation, new ReceivedChunkDataSource(chunks, received), cachedChunks,
            store);
    }

    void closeReceived() {
        if (chunks instanceof ReceivedChunkDataSource received) {
            received.close();
        }
    }

    /** Retains preparation bytes independently of the requesting consumer. */
    public RemoteModelContent retainPreparationInputs() {
        if (chunks instanceof ReceivedChunkDataSource received) {
            return new RemoteModelContent(representation, received.retain(), cachedChunks, store);
        }
        return new RemoteModelContent(representation, chunks, cachedChunks, store);
    }

    public void closePreparationInputs() {
        closeReceived();
    }

    public <T> CompletableFuture<T> loadRenderTargetAsync(BooleanSupplier cancelled, String targetId,
        String textureName, RemoteChunkFetcher fetcher, Executor workers,
        Function<ModelContent, CompletableFuture<T>> load, Consumer<T> discard) {
        Objects.requireNonNull(discard, "discard");
        var result = new CompletableFuture<T>();
        var active = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<T>>();
        var outer = loadRenderTarget(cancelled, targetId, textureName, fetcher, workers, prepared -> {
            var retained = ((RemoteModelContent)prepared).retainPreparationInputs();
            try {
                var loading = Objects.requireNonNull(load.apply(retained), "asynchronous load");
                active.set(loading);
                loading.whenComplete((value, failure) -> {
                    try {
                        retained.closePreparationInputs();
                    } finally {
                        // Cancellation can win before an already completed inner load is registered.
                        if (failure != null)
                            result.completeExceptionally(failure);
                        else if (!result.complete(value))
                            discard.accept(value);
                    }
                });
                if (result.isCancelled() || cancelled.getAsBoolean())
                    loading.cancel(false);
                return loading;
            } catch (Throwable failure) {
                retained.closePreparationInputs();
                throw failure;
            }
        });
        outer.whenComplete((ignored, failure) -> {
            if (failure != null)
                result.completeExceptionally(failure);
        });
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                outer.cancel(false);
                var loading = active.get();
                if (loading != null)
                    loading.cancel(false);
            }
        });
        return result;
    }

    private static final class ReceivedChunkDataSource implements ChunkDataSource, AutoCloseable {
        private final ChunkDataSource fallback;
        private Map<String, SharedBuffer> received;

        private ReceivedChunkDataSource(ChunkDataSource fallback, Map<String, ? extends UniBuffer> received) {
            this.fallback = Objects.requireNonNull(fallback, "fallback");
            var retained = new LinkedHashMap<String, SharedBuffer>();
            received.forEach((type, bytes) -> retained.put(type, bytes.share()));
            this.received = Map.copyOf(retained);
        }

        private synchronized ReceivedChunkDataSource retain() {
            return new ReceivedChunkDataSource(fallback, received);
        }

        @Override
        public Map<String, SharedBuffer> readPayloads(BooleanSupplier cancelled,
            java.util.Collection<AssetContainerView.ChunkInfo> chunks, BufferType bufferType) throws IOException {
            try (var retained = retain()) {
                return ChunkDataSource.collectPayloads(cancelled, chunks,
                    chunk -> retained.readPayload(cancelled, chunk, bufferType));
            }
        }

        @Override
        public SharedBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType) throws IOException {
            var bytes = receivedChunk(chunk);
            if (bytes == null) {
                return fallback.readPayload(chunk, bufferType);
            }
            if (ChunkDecoding.isZstd(chunk)) {
                return SharedBuffer.adopt(ChunkDecoding.decodeZstd(bytes, chunk, bufferType));
            }
            return bufferType == BufferType.NATIVE ? bytes.shareNative() : bytes.shareArray();
        }

        @Override
        public SharedBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType)
            throws IOException {
            var bytes = receivedChunk(chunk);
            if (bytes == null) {
                return fallback.readStoredVerified(chunk, bufferType);
            }
            return bufferType == BufferType.NATIVE ? bytes.shareNative() : bytes.shareArray();
        }

        private SharedBuffer receivedChunk(AssetContainerView.ChunkInfo chunk) throws IOException {
            final SharedBuffer bytes;
            synchronized (this) {
                bytes = received.get(chunk.type());
            }
            if (bytes != null) {
                ChunkDecoding.validateStored(bytes, chunk);
            }
            return bytes;
        }

        @Override
        public synchronized void close() {
            received = Map.of();
        }
    }

    private record CachedModelContent(ModelRepresentation representation, ChunkDataSource chunks,
        ContentStorageBinding storageBinding) implements ModelContent {
    }
}
