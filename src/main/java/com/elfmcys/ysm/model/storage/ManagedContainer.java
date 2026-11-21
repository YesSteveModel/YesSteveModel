package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.buffer.NativeStorage;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.container.ByteArraySeekableChannel;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.file.FileChunkDataSource;
import com.elfmcys.ysm.format.schema.file.ResidentChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.format.schema.model.ModelFileIdentityReader;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

public final class ManagedContainer implements ModelContent {
    private final @Nullable Path file;
    private final ChunkDataSource chunks;
    private final ModelRepresentation representation;
    private final CatalogLocation location;

    private final ContentStorageBinding storageBinding;

    private ManagedContainer(@Nullable Path file, CatalogLocation location, ModelRepresentation representation,
        ChunkDataSource chunks) {
        this(file, location, representation, chunks, null);
    }

    private ManagedContainer(@Nullable Path file, CatalogLocation location, ModelRepresentation representation,
        ChunkDataSource chunks, ContentStorageBinding storageBinding) {
        this.storageBinding = storageBinding;
        this.file = file;
        this.location = location;
        this.representation = representation;
        this.chunks = chunks;
    }

    public static ManagedContainer openIndexed(CatalogIndexEntry entry) throws IOException {
        return openIndexed(entry, null);
    }

    public static ManagedContainer openIndexed(CatalogIndexEntry entry, @Nullable ManagedContainer reusable)
        throws IOException {
        var binding = entry.storageBinding();
        return binding == null ? openIndexedProtected(entry, reusable)
            : binding.acquire(() -> openIndexedProtected(entry, reusable));
    }

    private static ManagedContainer openIndexedProtected(CatalogIndexEntry entry, @Nullable ManagedContainer reusable)
        throws IOException {
        var binding = entry.storageBinding();
        if (binding != null)
            binding.requireReadable();
        var cached = binding != null && entry.backingFile().startsWith(binding.region().root());
        var representation = entry.convertedIndex() != null
            ? readConvertedMetadata(entry.convertedIndex(), cached ? binding.region().cache() : null)
            : cached
                ? readCachedMetadata(entry.backingFile(), binding.region().cache(),
                    reusable == null ? null : reusable.representation)
                : readRepresentation(entry.backingFile(), reusable == null ? null : reusable.representation);
        if (!representation.identity().equals(entry.identity())) {
            representation.close();
            throw new IOException("Indexed container identity changed: " + entry.backingFile());
        }
        try {
            return new ManagedContainer(entry.backingFile(), entry.location(), representation,
                new FileChunkDataSource(entry.backingFile(), -1, () -> {
                    CacheRootCoordinator.FileOperation<FileChannel> open =
                        () -> !cached ? FileChannel.open(entry.backingFile(), StandardOpenOption.READ)
                            : binding.region().cache().openRead(entry.backingFile());
                    return binding == null ? open.run() : binding.acquire(open);
                }), binding);
        } catch (RuntimeException | Error failure) {
            representation.close();
            throw failure;
        }
    }

    public static ManagedContainer openDirect(Path file, CatalogLocation location) throws IOException {
        return openDirect(file, location, null);
    }

    public static ManagedContainer openDirect(Path file, CatalogLocation location, @Nullable ManagedContainer reusable)
        throws IOException {
        file = file.toAbsolutePath().normalize();
        var representation = readRepresentation(file, reusable == null ? null : reusable.representation);
        try {
            return new ManagedContainer(file, location, representation, new FileChunkDataSource(file));
        } catch (RuntimeException | Error failure) {
            representation.close();
            throw failure;
        }
    }

    public static ManagedContainer openResidentDefault(byte[] container, CatalogLocation location) throws IOException {
        return openResidentDefault(ArrayBuffer.borrow(Objects.requireNonNull(container, "container")), location);
    }

    public static ManagedContainer openResidentDefault(UniBuffer container, CatalogLocation location)
        throws IOException {
        var array = container.shareArray();
        final ModelRepresentation representation;
        try (var channel = new ByteArraySeekableChannel(array.array(), array.arrayOffset(), array.size())) {
            representation = readRepresentation(channel, null);
        } finally {
            java.lang.ref.Reference.reachabilityFence(array);
        }
        try {
            var source =
                ResidentChunkDataSource.fromContainer(array, representation.view().getFileView().getAssetView());
            return new ManagedContainer(null, location, representation, source);
        } catch (IOException | RuntimeException | Error failure) {
            representation.close();
            throw failure;
        }
    }

    private static ModelRepresentation readRepresentation(Path file, @Nullable ModelRepresentation reusable)
        throws IOException {
        final FileChannel channel;
        try {
            channel = FileChannel.open(file, StandardOpenOption.READ);
        } catch (IOException failure) {
            throw AssetLoadException.access("Failed to open model container: " + file, failure);
        }
        Throwable loadFailure = null;
        ModelRepresentation loaded = null;
        try {
            loaded = readRepresentation(channel, reusable);
            return loaded;
        } catch (IOException | RuntimeException | Error failure) {
            loadFailure = failure;
            throw failure;
        } finally {
            try {
                channel.close();
            } catch (IOException failure) {
                var access = AssetLoadException.access("Failed to close model container: " + file, failure);
                if (loadFailure == null) {
                    if (loaded != null) {
                        loaded.close();
                    }
                    throw access;
                }
                loadFailure.addSuppressed(access);
            }
        }
    }

    private static ModelRepresentation readRepresentation(SeekableByteChannel channel,
        @Nullable ModelRepresentation reusable) throws IOException {
        var preamble = ModelFileIdentityReader.readContainer(channel);
        return readRepresentation(channel, preamble, reusable);
    }

    private static ModelRepresentation readRepresentation(SeekableByteChannel channel, AssetContainerView preamble,
        @Nullable ModelRepresentation reusable) throws IOException {
        if (reusable != null && reusable.hasMetadataPrefix()
            && preamble.sameContents(reusable.view().getFileView().getAssetView())) {
            return reusable.acquire();
        }
        try (var scope = com.elfmcys.ysm.util.ScopeGuard.create(readMetadataPrefix(channel, preamble))) {
            return ModelRepresentation
                .fromMetadataPrefix(com.elfmcys.ysm.buffer.SharedNativeBuffer.adopt(scope.release()), reusable);
        }
    }

    record DirectAdmission(ManagedContainer content, boolean validated) {
    }

    static DirectAdmission openDirect(Path file, CatalogLocation location, @Nullable ManagedContainer reusable,
        DirectContainerIndex index) throws IOException {
        file = file.toAbsolutePath().normalize();
        ModelRepresentation representation = null;
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            var preamble = ModelFileIdentityReader.readContainer(channel);
            var saved = index.find(preamble.getContainerId());
            if (saved.isPresent()) {
                try {
                    representation = ModelRepresentation.fromMetadataPrefix(saved.get(), null);
                    if (!preamble.sameContents(representation.view().getFileView().getAssetView()))
                        throw AssetLoadException.content("Stored direct metadata does not match the container");
                    var content = new ManagedContainer(file, location, representation, new FileChunkDataSource(file));
                    return new DirectAdmission(content, true);
                } catch (IOException invalid) {
                    CacheReadFailure.miss(file, invalid);
                    if (representation != null) {
                        representation.close();
                        representation = null;
                    }
                    com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Stored direct metadata is invalid: {}", file, invalid);
                }
            }
            representation = readRepresentation(channel, preamble, reusable == null ? null : reusable.representation);
            return new DirectAdmission(
                new ManagedContainer(file, location, representation, new FileChunkDataSource(file)), false);
        } catch (IOException | RuntimeException | Error failure) {
            if (representation != null)
                representation.close();
            throw failure;
        }
    }

    static ModelRepresentation readConvertedMetadata(ConvertedSourceIndex entry, @Nullable AtomicSharedCache cache)
        throws IOException {
        var representation = ModelRepresentation.fromMetadataPrefix(entry.metadata(), null);
        try (var channel =
            cache == null ? FileChannel.open(entry.file(), StandardOpenOption.READ) : cache.openRead(entry.file())) {
            var preamble = ModelFileIdentityReader.readContainer(channel);
            if (!representation.identity().equals(entry.identity())
                || !preamble.sameContents(representation.view().getFileView().getAssetView()))
                throw AssetLoadException.content("Converted cache structure or identity changed");
            return representation;
        } catch (IOException | RuntimeException | Error failure) {
            representation.close();
            throw failure;
        }
    }

    static ModelRepresentation readCachedMetadata(Path file, AtomicSharedCache cache,
        @Nullable ModelRepresentation reusable) throws IOException {
        ModelRepresentation loaded = null;
        try (var channel = cache.openRead(file)) {
            loaded = readRepresentation(channel, reusable);
        } catch (IOException | RuntimeException | Error failure) {
            if (loaded != null)
                loaded.close();
            throw failure;
        }
        return loaded;
    }

    public static ModelRepresentation verifyCachedFile(Path file, AtomicSharedCache cache) throws IOException {
        return verifyCachedFile(file, cache, null);
    }

    private static ModelRepresentation verifyCachedFile(Path file, AtomicSharedCache cache,
        @Nullable ModelRepresentation reusable) throws IOException {
        ModelRepresentation loaded = null;
        try (var channel = cache.openRead(file)) {
            loaded = verifyCachedFile(channel, file.toString(), reusable);
        } catch (IOException | RuntimeException | Error failure) {
            if (loaded != null)
                loaded.close();
            throw failure;
        }
        return loaded;
    }

    public static ModelRepresentation verifyCachedFile(CacheFile file) throws IOException {
        file.position(0);
        return verifyCachedFile(file, "cache staging", null);
    }

    private static ModelRepresentation verifyCachedFile(SeekableByteChannel channel, String source,
        @Nullable ModelRepresentation reusable) throws IOException {
        var representation = readRepresentation(channel, reusable);
        try {
            for (var chunk : representation.view().getFileView().getAssetView().getChunkTable().values()) {
                if (!chunk.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE))
                    try (var ignored = com.elfmcys.ysm.format.container.InlineChunkReader.readStoredVerified(channel,
                        chunk, BufferType.ARRAY)) {
                    }
            }
            verifyFileSize(channel, expectedFileSize(representation.view()), source);
            return representation;
        } catch (IOException | RuntimeException | Error failure) {
            representation.close();
            throw failure;
        }
    }

    public static ModelRepresentation verifyFile(Path file) throws IOException {
        return verifyFile(file, null);
    }

    private static ModelRepresentation verifyFile(Path file, @Nullable ModelRepresentation reusable)
        throws IOException {
        file = file.toAbsolutePath().normalize();
        var before = FileStamp.capture(file);
        var representation = readRepresentation(file, reusable);
        try {
            verifyChunks(file, representation);
            var after = FileStamp.capture(file);
            if (!before.equals(after)) {
                throw new IOException("Model file changed while it was being verified: " + file);
            }
            return representation;
        } catch (IOException | RuntimeException | Error failure) {
            representation.close();
            throw failure;
        }
    }

    private static void verifyChunks(Path file, ModelRepresentation representation) throws IOException {
        var source = new FileChunkDataSource(file, expectedFileSize(representation.view()));
        var chunks = representation.view().getFileView().getAssetView().getChunkTable().values().stream()
            .sorted(Comparator.comparingInt(AssetContainerView.ChunkInfo::offset)).toList();
        for (var chunk : chunks) {
            if (chunk.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE)) {
                continue;
            }
            source.readStoredVerified(chunk, BufferType.ARRAY);
        }
    }

    private static NativeStorage readMetadataPrefix(SeekableByteChannel channel, AssetContainerView preamble)
        throws IOException {
        var manifest = ModelFileView.requireMetadataLayout(preamble);
        var size = Math.addExact(manifest.offset(), manifest.size());
        var result = NativeBuffer.allocate(size);
        var target = result.nio();
        try {
            channel.position(0);
            while (target.hasRemaining()) {
                if (channel.read(target) < 0) {
                    throw AssetLoadException.content("Model file changed while reading its metadata prefix");
                }
            }
            return result;
        } catch (AssetLoadException failure) {
            result.close();
            throw failure;
        } catch (IOException failure) {
            result.close();
            throw AssetLoadException.access("Failed to read model metadata prefix", failure);
        } catch (RuntimeException | Error failure) {
            result.close();
            throw failure;
        }
    }

    private static long expectedFileSize(ModelFileView view) {
        return expectedFileSize(view.getFileView().getAssetView());
    }

    private static long expectedFileSize(AssetContainerView preamble) {
        return preamble.getChunkTable().values().stream().mapToLong(chunk -> (long)chunk.offset() + chunk.size()).max()
            .orElseThrow(() -> new IllegalStateException("Model container has no chunks"));
    }

    private static long channelSize(SeekableByteChannel channel) throws IOException {
        try {
            return channel.size();
        } catch (IOException failure) {
            throw AssetLoadException.access("Failed to inspect model container length", failure);
        }
    }

    private static void verifyFileSize(SeekableByteChannel channel, long expected, Object source) throws IOException {
        if (channelSize(channel) != expected) {
            throw AssetLoadException.content("Model file length changed while reading metadata: " + source);
        }
    }

    public Path file() {
        if (file == null) {
            throw new IllegalStateException("Resident model has no file backing");
        }
        return file;
    }

    public ModelFileView view() {
        return representation.view();
    }

    public ChunkDataSource chunks() {
        return chunks;
    }

    public boolean residentOnly() {
        return chunks instanceof ResidentChunkDataSource;
    }

    @Override
    public ModelRepresentation representation() {
        return representation;
    }

    @Override
    public ContentStorageBinding storageBinding() {
        return storageBinding;
    }

    /** Transfers this wrapper's owned representation to a wrapper carrying immutable provenance. */
    public ManagedContainer withStorageBinding(ContentStorageBinding binding) {
        return new ManagedContainer(file, location, representation, chunks, Objects.requireNonNull(binding));
    }

    public CatalogLocation location() {
        return location;
    }

    /** Called only while this container still owns its unpublished representation. */
    public ManagedContainer reuse(@Nullable ManagedContainer previous) throws IOException {
        if (previous == null || previous == this) {
            return this;
        }
        var normalized = representation.reuseView(previous.representation);
        if (Objects.equals(file, previous.file) && location.equals(previous.location)
            && Objects.equals(storageBinding, previous.storageBinding)
            && chunks.getClass() == previous.chunks.getClass() && normalized.sameContents(previous.representation)) {
            if (normalized != representation) {
                normalized.close();
            }
            representation.close();
            return previous;
        }
        if (normalized != representation) {
            var result = new ManagedContainer(file, location, normalized, chunks, storageBinding);
            representation.close();
            return result;
        }
        return this;
    }

    private record FileStamp(Object fileKey, long size, FileTime modified) {
        private static FileStamp capture(Path file) throws IOException {
            try {
                var attributes = Files.readAttributes(file, BasicFileAttributes.class);
                return new FileStamp(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime());
            } catch (IOException | SecurityException failure) {
                throw AssetLoadException.access("Failed to inspect model container: " + file, failure);
            }
        }
    }
}
