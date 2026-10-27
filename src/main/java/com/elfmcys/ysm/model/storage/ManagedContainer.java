package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.buffer.UniBufferIO;
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
        var cached = binding != null && entry.backingFile().startsWith(binding.region().root());
        CacheDirectoryGuard.Observation observation;
        final boolean exists;
        try {
            observation = cached ? binding.region().cache().observe(entry.backingFile(), false) : null;
            exists = RegularFileProbe.exists(observation == null ? entry.backingFile() : observation.target());
        } catch (java.nio.file.NoSuchFileException absent) {
            throw AssetLoadException.content("Indexed model container is missing", absent);
        } catch (IOException | SecurityException failure) {
            throw AssetLoadException.access("Failed to inspect indexed model container: " + entry.backingFile(),
                failure);
        }
        if (!exists)
            throw AssetLoadException.content("Indexed model container is missing: " + entry.backingFile());
        var representation = cached
            ? verifyCachedFile(entry.backingFile(), binding.region().cache(),
                reusable == null ? null : reusable.representation)
            : verifyFile(entry.backingFile(), reusable == null ? null : reusable.representation);
        try {
            if (observation != null)
                observation.verify();
        } catch (IOException failure) {
            representation.close();
            throw failure;
        }
        if (!representation.identity().equals(entry.identity())) {
            representation.close();
            throw new IOException("Indexed container identity changed: " + entry.backingFile());
        }
        try {
            return new ManagedContainer(entry.backingFile(), entry.location(), representation,
                new FileChunkDataSource(entry.backingFile(), expectedFileSize(representation.view()),
                    () -> observation == null ? FileChannel.open(entry.backingFile(), StandardOpenOption.READ)
                        : observation.open(java.util.Set.of(StandardOpenOption.READ))),
                binding);
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
        var representation = verifyFile(file, reusable == null ? null : reusable.representation);
        try {
            return new ManagedContainer(file, location, representation,
                new FileChunkDataSource(file, expectedFileSize(representation.view())));
        } catch (RuntimeException | Error failure) {
            representation.close();
            throw failure;
        }
    }

    public static ManagedContainer openResidentDefault(byte[] container, CatalogLocation location) throws IOException {
        try (var buffer = ArrayBuffer.move(Objects.requireNonNull(container, "container").clone())) {
            return openResidentDefault(buffer, location);
        }
    }

    public static ManagedContainer openResidentDefault(UniBuffer container, CatalogLocation location)
        throws IOException {
        final ModelRepresentation representation;
        try (var array = container.acquireArray();
            var channel = new ByteArraySeekableChannel(array.array(), array.arrayOffset(), array.size())) {
            representation = readRepresentation(channel, "builtin default memory");
        }
        try {
            var source =
                ResidentChunkDataSource.fromContainer(container, representation.view().getFileView().getAssetView());
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
            loaded = readRepresentation(channel, file.toString(), reusable);
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

    private static ModelRepresentation readRepresentation(SeekableByteChannel channel, String source)
        throws IOException {
        return readRepresentation(channel, source, null);
    }

    private static ModelRepresentation readRepresentation(SeekableByteChannel channel, String source,
        @Nullable ModelRepresentation reusable) throws IOException {
        var acceptedSize = channelSize(channel);
        if (reusable != null && reusable.hasMetadataPrefix() && prefixMatches(channel, reusable)) {
            if (expectedFileSize(reusable.view()) != acceptedSize) {
                throw AssetLoadException.content("Model file length does not match its chunk table: " + source);
            }
            verifyFileSize(channel, acceptedSize, source);
            return reusable.acquire();
        }
        var preamble = ModelFileIdentityReader.readContainer(channel);
        if (expectedFileSize(preamble) != acceptedSize) {
            throw AssetLoadException.content("Model file length does not match its chunk table: " + source);
        }
        try (var prefix = readMetadataPrefix(channel, preamble)) {
            verifyFileSize(channel, acceptedSize, source);
            return ModelRepresentation.fromMetadataPrefix(prefix, reusable);
        }
    }

    private static boolean prefixMatches(SeekableByteChannel channel, ModelRepresentation reusable) throws IOException {
        try (var prefix = reusable.metadataPrefix().orElseThrow()) {
            if (channelSize(channel) < prefix.size()) {
                return false;
            }
            channel.position(0);
            var bytes = new byte[Math.min(8192, prefix.size())];
            for (var offset = 0; offset < prefix.size();) {
                var size = Math.min(bytes.length, prefix.size() - offset);
                var target = java.nio.ByteBuffer.wrap(bytes, 0, size);
                while (target.hasRemaining()) {
                    if (channel.read(target) < 0) {
                        throw AssetLoadException.content("Model file changed while comparing metadata");
                    }
                }
                if (!UniBufferIO.equals(prefix, offset, bytes, 0, size)) {
                    return false;
                }
                offset += size;
            }
            return true;
        } catch (AssetLoadException failure) {
            throw failure;
        } catch (IOException failure) {
            throw AssetLoadException.access("Failed to compare model metadata prefix", failure);
        }
    }

    public static ModelRepresentation verifyCachedFile(Path file, AtomicSharedCache cache) throws IOException {
        return verifyCachedFile(file, cache, null);
    }

    private static ModelRepresentation verifyCachedFile(Path file, AtomicSharedCache cache,
        @Nullable ModelRepresentation reusable) throws IOException {
        try (var channel = cache.openRead(file)) {
            return verifyCachedFile(channel, file.toString(), reusable);
        }
    }

    public static ModelRepresentation verifyCachedFile(CacheFile file) throws IOException {
        file.position(0);
        return verifyCachedFile(file, "cache staging", null);
    }

    private static ModelRepresentation verifyCachedFile(SeekableByteChannel channel, String source,
        @Nullable ModelRepresentation reusable) throws IOException {
        var representation = readRepresentation(channel, source, reusable);
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
            try (var ignored = source.readStoredVerified(chunk, BufferType.ARRAY)) {
                // Reading is the validation.
            }
        }
    }

    private static NativeBuffer readMetadataPrefix(SeekableByteChannel channel, AssetContainerView preamble)
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
