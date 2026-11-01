package com.elfmcys.ysm.model.resource.client.remote;

import com.elfmcys.ysm.buffer.SharedBuffer;
import com.elfmcys.ysm.buffer.BufferStorage;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.model.storage.ModelStorageInfra;
import com.elfmcys.ysm.model.storage.CacheDirectoryGuard;
import com.elfmcys.ysm.model.storage.CacheRegion;
import com.elfmcys.ysm.model.storage.ContentStorageBinding;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.container.ChunkDecoding;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.natives.Zstd;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Deterministic, index-free cache for verified remote representations. */
public final class RemoteModelStore implements AutoCloseable {
    private static final String METADATA_FILE = "metadata.bin";
    private static final String METADATA_DIRECTORY = "metadata";
    private static final String CHUNK_SUFFIX = ".bin";
    private static final String ZSTD_CHUNK_SUFFIX = "-zst.bin";
    private static final String ZSTD_ENCODING = "zstd";

    private final Path root;
    private final CacheRegion region;
    private final CacheDirectoryGuard guard;
    private final AtomicSharedCache cache;
    private final CacheReadObserver afterCacheRead;
    private final Map<ChunkFlightKey, ChunkFlight> chunkFlights = new HashMap<>();
    private boolean closed;

    public RemoteModelStore(Path root) throws IOException {
        this(root, AtomicSharedCache.AtomicReplacement::run, ignored -> {
        });
    }

    RemoteModelStore(Path root, AtomicMove atomicMove, CacheReadObserver afterCacheRead) throws IOException {
        this(root, atomicMove, afterCacheRead, null);
    }

    private RemoteModelStore(Path root, AtomicMove atomicMove, CacheReadObserver afterCacheRead, CacheRegion region)
        throws IOException {
        var configuredRoot = root.toAbsolutePath().normalize();
        guard = CacheDirectoryGuard.open(configuredRoot);
        this.root = guard.root();
        this.region = region == null ? new CacheRegion(this.root.getParent()) : region;
        this.cache = new AtomicSharedCache(guard, Objects.requireNonNull(atomicMove, "atomicMove")::move);
        this.afterCacheRead = Objects.requireNonNull(afterCacheRead, "afterCacheRead");
        if (!Files.isWritable(this.root)) {
            throw AssetLoadException.access("Remote model store is not writable: " + this.root);
        }
    }

    public static RemoteModelStore create(ModelStorageInfra storage) throws IOException {
        return new RemoteModelStore(storage.remote().root().resolve("remote"), AtomicSharedCache.AtomicReplacement::run,
            ignored -> {
            }, storage.remote());
    }

    ContentStorageBinding storageBinding() {
        return ContentStorageBinding.remote(region);
    }

    public Path root() {
        return root;
    }

    public void publishChunk(Hash256 modelId, AssetContainerView.ChunkInfo chunk, byte[] storedBytes)
        throws IOException {
        publishChunk(modelId, chunk, ArrayBuffer.borrow(storedBytes));
    }

    public void publishChunk(Hash256 modelId, AssetContainerView.ChunkInfo chunk, UniBuffer storedBytes)
        throws IOException {
        requireOpen();
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(chunk, "chunk");
        if (chunk.hash() == null || chunk.hash().length != Hash256.SIZE) {
            throw AssetLoadException.content("Remote chunk has no content hash");
        }
        ChunkDecoding.validateStored(storedBytes, chunk);
        try {
            publishAtomic(chunkPath(modelId, new Hash256(chunk.hash()), chunk.encoding()), storedBytes, temporary -> {
                try (var persisted = read(temporary, BufferType.NATIVE)) {
                    ChunkDecoding.validateStored(persisted, chunk);
                }
                return true;
            });
        } catch (IOException error) {
            throw AssetLoadException.access("Failed to publish remote chunk", error);
        }
    }

    /** Single-file metadata lookup. This method never calls the network fetcher. */
    public synchronized Optional<RemoteModelContent> probeMetadata(ModelFileIdentity identity) throws IOException {
        requireOpen();
        Objects.requireNonNull(identity, "identity");
        var path = metadataPath(identity);
        try {
            try (var metadataPrefix = read(path, BufferType.NATIVE)) {
                return Optional.of(validateMetadata(identity, metadataPrefix));
            }
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        } catch (AssetLoadException invalid) {
            if (invalid.reason() == AssetLoadException.Reason.ACCESS) {
                throw invalid;
            }
            return Optional.empty();
        }
    }

    /** Validation completes before the atomic replacement that publishes the new prefix. */
    public RemoteModelContent commitMetadataPrefix(ModelFileIdentity identity, UniBuffer metadataPrefix)
        throws IOException {
        requireOpen();
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(metadataPrefix, "metadataPrefix");
        if (metadataPrefix.size() <= 0 || metadataPrefix.size() > AssetContainerConstant.MAX_FILE_SIZE) {
            throw AssetLoadException.content("Remote metadata prefix size is invalid");
        }
        var content = validateMetadata(identity, metadataPrefix);
        try {
            publishAtomic(metadataPath(identity), metadataPrefix, temporary -> {
                try (var persisted = read(temporary, BufferType.NATIVE)) {
                    var verified = validateMetadata(identity, persisted);
                    verified.representation().close();
                }
                return true;
            });
        } catch (IOException | SecurityException access) {
            content.representation().close();
            throw AssetLoadException.access("Failed to commit remote metadata prefix", access);
        }
        return content;
    }

    public RemoteModelContent openContent(ModelFileIdentity identity) throws IOException {
        return probeMetadata(identity).orElseThrow(() -> AssetLoadException.access("Remote metadata is not cached"));
    }

    <T> CompletableFuture<T> loadRenderTarget(RemoteModelContent content, String targetId, String textureName,
        BooleanSupplier cancelled, RemoteChunkFetcher fetcher, Executor workers, Function<ModelContent, T> load) {
        return loadChunks(content, cancelled, fetcher, workers,
            () -> com.elfmcys.ysm.format.schema.model.RenderTargetChunkPlan.chunks(content.representation().view(),
                targetId, textureName),
            load);
    }

    <T> CompletableFuture<T> loadChunks(RemoteModelContent content, BooleanSupplier cancelled,
        RemoteChunkFetcher fetcher, Executor workers, List<AssetContainerView.ChunkInfo> chunks,
        Function<ModelContent, T> load) {
        var immutableChunks = List.copyOf(chunks);
        return loadChunks(content, cancelled, fetcher, workers, () -> immutableChunks, load);
    }

    private <T> CompletableFuture<T> loadChunks(RemoteModelContent content, BooleanSupplier cancelled,
        RemoteChunkFetcher fetcher, Executor workers, ChunkPlan chunks, Function<ModelContent, T> load) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(workers, "workers");
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(load, "load");
        var probe = CompletableFuture.supplyAsync(() -> {
            try {
                return probeChunks(content, chunks.get(), cancelled, load);
            } catch (IOException | RuntimeException failure) {
                throw new CompletionException(failure);
            }
        }, workers);
        var transfer = new AtomicReference<CompletableFuture<Void>>();
        var transferCancellationRequested = new AtomicBoolean();
        var result = probe.thenCompose(outcome -> {
            if (outcome.missing.isEmpty()) {
                return CompletableFuture.completedFuture(outcome.loaded);
            }
            if (fetcher == null) {
                return CompletableFuture.failedFuture(AssetLoadException.access("Remote chunk fetcher is unavailable"));
            }
            var expected = new LinkedHashMap<String, AssetContainerView.ChunkInfo>();
            outcome.missing.forEach(chunk -> expected.put(chunk.type(), chunk));
            var received = new ConcurrentHashMap<String, SharedBuffer>();
            final CompletableFuture<Void> fetching;
            try {
                fetching = Objects.requireNonNull(
                    fetcher.fetch(content.representation().identity(), outcome.missing,
                        (chunk, bytes) -> receiveChunk(expected, received, chunk, bytes)),
                    "Remote chunk fetcher returned a null future");
            } catch (RuntimeException failure) {
                received.clear();
                return CompletableFuture.failedFuture(failure);
            }
            fetching.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    received.clear();
                }
            });
            transfer.set(fetching);
            if (transferCancellationRequested.get()) {
                fetching.cancel(false);
            }
            Executor continuation = action -> {
                try {
                    workers.execute(action);
                } catch (RejectedExecutionException rejected) {
                    // No worker owns these inputs when the continuation was never accepted.
                    received.clear();
                    throw rejected;
                }
            };
            return fetching.thenApplyAsync(ignored -> {
                try {
                    return persistAndLoad(content, outcome.missing, received, cancelled, load);
                } catch (IOException | RuntimeException failure) {
                    throw new CompletionException(failure);
                }
            }, continuation);
        });
        result.whenComplete((ignored, failure) -> {
            if (!result.isCancelled()) {
                return;
            }
            transferCancellationRequested.set(true);
            probe.cancel(false);
            var fetching = transfer.get();
            if (fetching != null) {
                fetching.cancel(false);
            }
        });
        return result;
    }

    private <T> RenderTargetProbe<T> probeChunks(RemoteModelContent content,
        List<AssetContainerView.ChunkInfo> descriptors, BooleanSupplier cancelled, Function<ModelContent, T> load)
        throws IOException {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Model activation was cancelled");
        }
        var missing = new ArrayList<AssetContainerView.ChunkInfo>();
        for (var chunk : descriptors) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Model activation was cancelled");
            }
            try {
                content.chunks().readStoredVerified(chunk, BufferType.ARRAY);
                // A verified hit remains subject to the load's own exact read validation.
            } catch (IOException unavailable) {
                missing.add(chunk);
            }
        }
        return missing.isEmpty() ? RenderTargetProbe.loaded(load.apply(content))
            : RenderTargetProbe.missing(List.copyOf(missing));
    }

    private static void receiveChunk(Map<String, AssetContainerView.ChunkInfo> expected,
        Map<String, SharedBuffer> received, AssetContainerView.ChunkInfo chunk, UniBuffer bytes) throws IOException {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(bytes, "bytes");
        var descriptor = expected.get(chunk.type());
        if (!sameChunk(descriptor, chunk)) {
            throw AssetLoadException.content("Remote transfer returned an unexpected chunk");
        }
        var shared = bytes.share();
        if (received.putIfAbsent(chunk.type(), shared) != null) {
            throw AssetLoadException.content("Remote transfer returned a chunk more than once: " + chunk.type());
        }
    }

    private <T> T persistAndLoad(RemoteModelContent content, List<AssetContainerView.ChunkInfo> missing,
        Map<String, SharedBuffer> received, BooleanSupplier cancelled, Function<ModelContent, T> load)
        throws IOException {
        try {
            var complete = new LinkedHashMap<String, SharedBuffer>();
            for (var chunk : missing) {
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("Model activation was cancelled");
                }
                var bytes = received.get(chunk.type());
                if (bytes == null) {
                    throw AssetLoadException
                        .content("Remote transfer completed without a required chunk: " + chunk.type());
                }
                ChunkDecoding.validateStored(bytes, chunk);
                complete.put(chunk.type(), bytes);
            }
            var prepared = content.withReceived(complete);
            try {
                for (var chunk : missing) {
                    try {
                        publishChunk(content.representation().modelId(), chunk, complete.get(chunk.type()));
                    } catch (IOException cacheFailure) {
                        YesSteveModel.LOGGER.warn("Failed to persist verified remote model chunk model={} chunk={}",
                            content.representation().modelId(), chunk.type(), cacheFailure);
                    }
                }
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("Model activation was cancelled");
                }
                return load.apply(prepared);
            } finally {
                prepared.closeReceived();
            }
        } finally {
            received.clear();
        }
    }

    private static boolean sameChunk(AssetContainerView.ChunkInfo expected, AssetContainerView.ChunkInfo actual) {
        return expected != null && expected.type().equals(actual.type()) && expected.offset() == actual.offset()
            && expected.size() == actual.size() && expected.decodeSize() == actual.decodeSize()
            && expected.encoding().equals(actual.encoding()) && Arrays.equals(expected.hash(), actual.hash());
    }

    CompletableFuture<SharedBuffer> requestChunk(RemoteModelContent content, AssetContainerView.ChunkInfo chunk,
        RemoteChunkFetcher fetcher, Executor workers) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(workers, "workers");
        var result = new CompletableFuture<SharedBuffer>();
        var flight = new AtomicReference<ChunkFlight>();
        var probe = CompletableFuture.supplyAsync(() -> {
            try {
                return content.chunks().readPayload(chunk, BufferType.NATIVE);
            } catch (IOException unavailable) {
                return null;
            }
        }, workers);
        result.whenComplete((ignored, failure) -> {
            if (!result.isCancelled()) {
                return;
            }
            probe.cancel(false);
            var current = flight.get();
            if (current != null) {
                current.cancel(result);
            }
        });
        probe.whenComplete((cached, failure) -> {
            if (failure != null) {
                result.completeExceptionally(unwrap(failure));
                return;
            }
            if (result.isDone()) {
                return;
            }
            if (cached != null) {
                result.complete(cached);
                return;
            }
            if (fetcher == null) {
                result.completeExceptionally(AssetLoadException.access("Remote chunk fetcher is unavailable"));
                return;
            }
            final ChunkFlight acquired;
            final boolean start;
            try {
                synchronized (this) {
                    requireOpen();
                    var key = new ChunkFlightKey(content.representation().identity(), chunk, fetcher);
                    var existing = chunkFlights.get(key);
                    if (existing != null && existing.isDone()) {
                        chunkFlights.remove(key, existing);
                        existing = null;
                    }
                    if (existing == null) {
                        acquired = new ChunkFlight(key, content, chunk, fetcher, workers);
                        chunkFlights.put(key, acquired);
                        start = true;
                    } else {
                        acquired = existing;
                        start = false;
                    }
                    acquired.add(result);
                    flight.set(acquired);
                }
            } catch (IOException | RuntimeException error) {
                result.completeExceptionally(error);
                return;
            }
            if (result.isCancelled()) {
                acquired.cancel(result);
            }
            if (start) {
                acquired.start();
            }
        });
        return result;
    }

    @Override
    public void close() {
        List<ChunkFlight> active;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            active = List.copyOf(chunkFlights.values());
            chunkFlights.clear();
        }
        var failure = AssetLoadException.access("Remote model store is closed");
        active.forEach(flight -> flight.ownerClosed(failure));
    }

    private synchronized void requireOpen() throws IOException {
        if (closed) {
            throw AssetLoadException.access("Remote model store is closed");
        }
    }

    private RemoteModelContent validateMetadata(ModelFileIdentity identity, UniBuffer metadataPrefix)
        throws IOException {
        try {
            var representation = ModelRepresentation.fromMetadataPrefix(identity, metadataPrefix);
            var asset = representation.view().getFileView().getAssetView();
            if (!identity.containerId().equals(asset.getContainerId())) {
                representation.close();
                throw AssetLoadException.content("Remote metadata container id mismatch");
            }
            var chunks = new StoreChunkDataSource(identity);
            return new RemoteModelContent(representation, chunks, chunks, this);
        } catch (AssetLoadException failure) {
            throw failure;
        } catch (IOException | RuntimeException invalid) {
            throw AssetLoadException.content("Invalid remote metadata prefix", invalid);
        }
    }

    ChunkDataSource chunkSource(ModelFileIdentity identity) {
        return new StoreChunkDataSource(identity);
    }

    Path metadataPath(ModelFileIdentity identity) {
        return ownedPath(metadataRoot(identity).resolve(METADATA_FILE));
    }

    /** A preparation hint only; the card task still verifies the payload before decoding. */
    synchronized boolean hasCachedChunk(Hash256 modelId, AssetContainerView.ChunkInfo chunk) throws IOException {
        requireOpen();
        return chunk.hash() != null && Files.isRegularFile(chunkPath(modelId, new Hash256(chunk.hash()),
            chunk.encoding()), java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    Path chunkPath(Hash256 modelId, Hash256 hash, String encoding) {
        return ownedPath(
            modelRoot(modelId).resolve(hash + (ZSTD_ENCODING.equals(encoding) ? ZSTD_CHUNK_SUFFIX : CHUNK_SUFFIX)));
    }

    /** Verified remote content of one model. */
    private Path modelRoot(Hash256 modelId) {
        return root.resolve(modelId.toString());
    }

    /** Verified remote metadata of one exact container. */
    private Path metadataRoot(ModelFileIdentity identity) {
        return modelRoot(identity.modelId()).resolve(METADATA_DIRECTORY).resolve(identity.containerId().toString());
    }

    private void publishAtomic(Path target, UniBuffer bytes, AtomicSharedCache.CacheValidator validator)
        throws IOException {
        cache.replaceValidated("remote", root.relativize(target).toString(), target, validator, temporary -> {
            try (var channel = temporary) {
                var source = bytes.nio();
                while (source.hasRemaining())
                    channel.write(source);
                channel.force();
            }
        }, replacement -> {
            synchronized (RemoteModelStore.this) {
                requireOpen();
                replacement.run();
            }
        });
    }

    private Path ownedPath(Path candidate) {
        var normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root))
            throw new IllegalArgumentException("Remote cache path escapes its root");
        return normalized;
    }

    private record RenderTargetProbe<T>(T loaded, List<AssetContainerView.ChunkInfo> missing) {
        private static <T> RenderTargetProbe<T> loaded(T value) {
            return new RenderTargetProbe<>(Objects.requireNonNull(value, "loaded"), List.of());
        }

        private static <T> RenderTargetProbe<T> missing(List<AssetContainerView.ChunkInfo> chunks) {
            return new RenderTargetProbe<>(null, List.copyOf(chunks));
        }
    }

    @FunctionalInterface
    private interface ChunkPlan {
        List<AssetContainerView.ChunkInfo> get() throws IOException;
    }

    private final class StoreChunkDataSource implements ChunkDataSource {
        private final ModelFileIdentity identity;

        private StoreChunkDataSource(ModelFileIdentity identity) {
            this.identity = Objects.requireNonNull(identity, "identity");
        }

        @Override
        public SharedBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType) throws IOException {
            return decodePayload(chunk, bufferType);
        }

        @Override
        public SharedBuffer readPayload(BooleanSupplier cancelled, AssetContainerView.ChunkInfo chunk,
            BufferType bufferType) throws IOException {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Remote chunk read was cancelled");
            }
            var result = decodePayload(chunk, bufferType);
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Remote chunk read was cancelled");
            }
            return result;
        }

        private SharedBuffer decodePayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType)
            throws IOException {
            var stored = readStoredVerifiedNow(chunk, bufferType);
            if (!ChunkDecoding.isZstd(chunk)) {
                return stored;
            }
            try {
                return SharedBuffer
                    .adopt(Zstd.decompressAndValidate(stored, chunk.decodeSize(), chunk.hash(), bufferType));
            } catch (RuntimeException invalid) {
                throw AssetLoadException.content("Invalid cached zstd chunk: " + chunk.type(), invalid);
            }
        }

        @Override
        public SharedBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType)
            throws IOException {
            return readStoredVerifiedNow(chunk, bufferType);
        }

        private SharedBuffer readStoredVerifiedNow(AssetContainerView.ChunkInfo chunk, BufferType bufferType)
            throws IOException {
            synchronized (RemoteModelStore.this) {
                requireOpen();
                return readStoredVerifiedLocked(chunk, bufferType);
            }
        }

        private SharedBuffer readStoredVerifiedLocked(AssetContainerView.ChunkInfo chunk, BufferType bufferType)
            throws IOException {
            if (ModelFileConstant.MANIFEST_CHUNK_NAME.equals(chunk.type())) {
                throw AssetLoadException.content("Manifest is included in the metadata prefix");
            }
            if (chunk.hash() == null || chunk.hash().length != Hash256.SIZE) {
                throw AssetLoadException.content("Remote chunk has no content hash");
            }
            var path = chunkPath(identity.modelId(), new Hash256(chunk.hash()), chunk.encoding());
            AssetLoadException cachedFailure;
            try (var scope = com.elfmcys.ysm.util.ScopeGuard.create(read(path, bufferType))) {
                validateCached(scope.get(), chunk);
                return SharedBuffer.adopt(scope.release());
            } catch (NoSuchFileException missing) {
                cachedFailure = AssetLoadException.access("Remote chunk is not cached: " + chunk.type(), missing);
            } catch (AssetLoadException failure) {
                if (failure.reason() == AssetLoadException.Reason.ACCESS) {
                    throw failure;
                }
                cachedFailure = failure;
            }
            throw cachedFailure;
        }

        private void validateCached(UniBuffer stored, AssetContainerView.ChunkInfo chunk) throws IOException {
            if (ChunkDecoding.isZstd(chunk)) {
                try {
                    try (var ignored =
                        Zstd.decompressAndValidate(stored, chunk.decodeSize(), chunk.hash(), BufferType.NATIVE)) {
                        // A cached zstd representation may have a different compressed size.
                    }
                } catch (RuntimeException error) {
                    throw AssetLoadException.content("Invalid cached zstd chunk: " + chunk.type(), error);
                }
            } else {
                ChunkDecoding.validateStored(stored, chunk);
            }
        }
    }

    private BufferStorage read(Path path, BufferType type) throws IOException {
        final FileChannel opened;
        try {
            opened = guard.observe(path, false).open(java.util.Set.of(StandardOpenOption.READ));
        } catch (NoSuchFileException missing) {
            throw missing;
        } catch (IOException | SecurityException error) {
            throw AssetLoadException.access("Failed to open cached remote chunk", error);
        }
        BufferStorage result = null;
        try (var channel = opened) {
            var size = channel.size();
            if (size > AssetContainerConstant.MAX_FILE_SIZE) {
                throw AssetLoadException.content("Cached data is too large: " + size);
            }
            result = UniBuffer.allocate(Math.toIntExact(size), type);
            var target = result.nio();
            while (target.hasRemaining()) {
                if (channel.read(target) < 0) {
                    throw AssetLoadException.content("Cached chunk was truncated while reading");
                }
            }
            if (channel.size() != size)
                throw AssetLoadException.content("Cached chunk length changed while reading");
        } catch (AssetLoadException error) {
            close(result);
            throw error;
        } catch (IOException error) {
            close(result);
            throw AssetLoadException.access("Failed to read cached remote chunk", error);
        } catch (RuntimeException | Error error) {
            close(result);
            throw error;
        }
        try {
            afterCacheRead.observe(path);
            return result;
        } catch (AssetLoadException error) {
            result.close();
            throw error;
        } catch (IOException error) {
            result.close();
            throw AssetLoadException.access("Failed to read cached remote chunk", error);
        } catch (RuntimeException | Error error) {
            result.close();
            throw error;
        }
    }

    private BufferStorage read(com.elfmcys.ysm.model.storage.CacheFile file, BufferType type) throws IOException {
        var size = file.size();
        if (size > AssetContainerConstant.MAX_FILE_SIZE)
            throw AssetLoadException.content("Cached data is too large: " + size);
        var result = UniBuffer.allocate(Math.toIntExact(size), type);
        try {
            file.position(0);
            var target = result.nio();
            while (target.hasRemaining())
                if (file.read(target) < 0)
                    throw AssetLoadException.content("Truncated staged chunk");
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            result.close();
            throw failure;
        }
    }

    private static void close(BufferStorage buffer) {
        if (buffer != null) {
            buffer.close();
        }
    }

    private static Throwable unwrap(Throwable failure) {
        var current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private final class ChunkFlight {
        private final ChunkFlightKey key;
        private final RemoteModelContent content;
        private final AssetContainerView.ChunkInfo chunk;
        private final RemoteChunkFetcher fetcher;
        private final Executor workers;
        private final java.util.Set<CompletableFuture<SharedBuffer>> interests =
            Collections.newSetFromMap(new IdentityHashMap<>());
        private CompletableFuture<Void> transfer;
        private SharedBuffer received;
        private boolean done;

        private ChunkFlight(ChunkFlightKey key, RemoteModelContent content, AssetContainerView.ChunkInfo chunk,
            RemoteChunkFetcher fetcher, Executor workers) {
            this.key = key;
            this.content = content;
            this.chunk = chunk;
            this.fetcher = fetcher;
            this.workers = workers;
        }

        private synchronized void add(CompletableFuture<SharedBuffer> interest) {
            if (!done && !interest.isDone()) {
                interests.add(interest);
            }
        }

        private synchronized boolean isDone() {
            return done;
        }

        private void start() {
            final boolean abandoned;
            synchronized (this) {
                abandoned = done || interests.isEmpty();
                if (abandoned) {
                    done = true;
                }
            }
            if (abandoned) {
                synchronized (RemoteModelStore.this) {
                    chunkFlights.remove(key, this);
                }
                return;
            }
            final CompletableFuture<Void> accepted;
            try {
                accepted = Objects.requireNonNull(
                    fetcher.fetch(content.representation().identity(), List.of(chunk), this::receive),
                    "Remote chunk fetcher returned a null future");
            } catch (RuntimeException failure) {
                finish(null, failure);
                return;
            }
            synchronized (this) {
                transfer = accepted;
                if (interests.isEmpty()) {
                    accepted.cancel(false);
                }
            }
            accepted.whenCompleteAsync(this::completeTransfer, workers);
        }

        private synchronized void receive(AssetContainerView.ChunkInfo actual, UniBuffer bytes) throws IOException {
            if (done || !sameChunk(chunk, actual)) {
                throw AssetLoadException.content("Remote transfer returned an unexpected sound chunk");
            }
            if (received != null) {
                throw AssetLoadException.content("Remote transfer returned a sound chunk more than once");
            }
            received = bytes.share();
        }

        private void completeTransfer(Void ignored, Throwable failure) {
            if (failure != null) {
                finish(null, unwrap(failure));
                return;
            }
            final SharedBuffer stored;
            synchronized (this) {
                stored = received;
                received = null;
            }
            if (stored == null) {
                finish(null, AssetLoadException.content("Remote transfer completed without the sound chunk"));
                return;
            }
            try {
                ChunkDecoding.validateStored(stored, chunk);
                try {
                    publishChunk(content.representation().modelId(), chunk, stored);
                } catch (IOException cacheFailure) {
                    YesSteveModel.LOGGER.warn("Failed to persist verified remote sound chunk model={} chunk={}",
                        content.representation().modelId(), chunk.type(), cacheFailure);
                }
                final SharedBuffer payload;
                if (ChunkDecoding.isZstd(chunk)) {
                    payload = SharedBuffer.adopt(ChunkDecoding.decodeZstd(stored, chunk, BufferType.NATIVE));
                } else {
                    payload = stored.shareNative();
                }
                finish(payload, null);
            } catch (IOException | RuntimeException error) {
                finish(null, error);
            }
        }

        private void cancel(CompletableFuture<SharedBuffer> interest) {
            CompletableFuture<Void> accepted = null;
            synchronized (this) {
                interests.remove(interest);
                if (!done && interests.isEmpty()) {
                    accepted = transfer;
                }
            }
            if (accepted != null) {
                accepted.cancel(false);
            }
            if (accepted != null || interestsEmpty()) {
                synchronized (RemoteModelStore.this) {
                    chunkFlights.remove(key, this);
                }
            }
        }

        private synchronized boolean interestsEmpty() {
            return interests.isEmpty();
        }

        private void ownerClosed(Throwable failure) {
            CompletableFuture<Void> accepted;
            List<CompletableFuture<SharedBuffer>> waiting;
            synchronized (this) {
                if (done) {
                    return;
                }
                done = true;
                accepted = transfer;
                received = null;
                waiting = List.copyOf(interests);
                interests.clear();
            }
            if (accepted != null) {
                accepted.cancel(false);
            }
            waiting.forEach(value -> value.completeExceptionally(failure));
        }

        private void finish(SharedBuffer payload, Throwable failure) {
            List<CompletableFuture<SharedBuffer>> waiting;
            synchronized (this) {
                if (done) {
                    return;
                }
                done = true;
                received = null;
                waiting = List.copyOf(interests);
                interests.clear();
            }
            synchronized (RemoteModelStore.this) {
                chunkFlights.remove(key, this);
            }
            if (failure != null) {
                waiting.forEach(value -> value.completeExceptionally(failure));
                return;
            }
            for (var interest : waiting) {
                interest.complete(payload);
            }
        }
    }

    private static final class ChunkFlightKey {
        private final ModelFileIdentity representation;
        private final String type;
        private final String encoding;
        private final long offset;
        private final int size;
        private final int decodeSize;
        private final Hash256 hash;
        private final RemoteChunkFetcher fetcher;

        private ChunkFlightKey(ModelFileIdentity representation, AssetContainerView.ChunkInfo chunk,
            RemoteChunkFetcher fetcher) {
            this.representation = representation;
            type = chunk.type();
            encoding = chunk.encoding();
            offset = chunk.offset();
            size = chunk.size();
            decodeSize = chunk.decodeSize();
            hash = new Hash256(chunk.hash());
            this.fetcher = fetcher;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof ChunkFlightKey other)) {
                return false;
            }
            return offset == other.offset && size == other.size && decodeSize == other.decodeSize
                && fetcher == other.fetcher && representation.equals(other.representation) && type.equals(other.type)
                && encoding.equals(other.encoding) && hash.equals(other.hash);
        }

        @Override
        public int hashCode() {
            return Objects.hash(representation, type, encoding, offset, size, decodeSize, hash,
                System.identityHashCode(fetcher));
        }
    }

    @FunctionalInterface
    interface AtomicMove {
        void move(AtomicSharedCache.AtomicReplacement replacement) throws IOException;
    }

    @FunctionalInterface
    interface CacheReadObserver {
        void observe(Path path) throws IOException;
    }

}
