package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.format.schema.model.ModelFileView;
import org.jetbrains.annotations.Nullable;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.format.parser.RawCompileResult;
import com.elfmcys.ysm.format.schema.model.ModelFileIdentityReader;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Exact-path converted-container storage owned by model management. */
public final class ConvertedObjectStore {
    /** Suffix of one stored converted container. */
    static final String CONTAINER_SUFFIX = ".mxc";

    /** Staging directory of the converted store. */
    static final String TEMPORARY_DIRECTORY = ".tmp";

    private final Path convertedRoot;
    private final AtomicSharedCache cache;

    public ConvertedObjectStore(Path gameCacheRoot) {
        this(gameCacheRoot, new AtomicSharedCache(gameCacheRoot));
    }

    ConvertedObjectStore(Path gameCacheRoot, AtomicSharedCache cache) {
        this.convertedRoot = Objects.requireNonNull(gameCacheRoot, "gameCacheRoot").resolve("converted");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    public ConvertedObjectStore(ConvertedScope scope, AtomicSharedCache cache) {
        this.convertedRoot = scope.root();
        this.cache = Objects.requireNonNull(cache);
    }

    public Path temporaryRoot() {
        return convertedRoot().resolve(TEMPORARY_DIRECTORY);
    }

    public TemporaryDirectory createTemporaryDirectory(String prefix) throws IOException {
        var parent = cache.observe(temporaryRoot().resolve(".guard"), true).acquire();
        try {
            var name = prefix + java.util.UUID.randomUUID();
            var directory = parent.createDirectory(name);
            try {
                return new TemporaryDirectory(parent, directory, name, directory.identity());
            } catch (IOException | RuntimeException | Error failure) {
                try {
                    directory.close();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        } catch (IOException | RuntimeException failure) {
            parent.close();
            throw failure;
        }
    }

    public record TemporaryDirectory(com.elfmcys.ysm.util.DirectoryAccess parent,
        com.elfmcys.ysm.util.DirectoryAccess directory, String name, Object identity) implements AutoCloseable {
        public Path path() {
            return directory.label();
        }

        @Override
        public void close() throws IOException {
            try (parent; directory) {
                for (var child : directory.names())
                    directory.delete(child, directory.fileIdentity(child), false);
                parent.delete(name, identity, true);
            }
        }
    }

    private Path convertedRoot() {
        return convertedRoot;
    }

    /** Converted objects of one model, partitioned by model id. */
    private Path modelRoot(Hash256 modelId) {
        return convertedRoot().resolve("objects").resolve(modelId.toString());
    }

    private Path containerPath(Hash256 modelId, Hash256 containerId) {
        return modelRoot(modelId).resolve(containerId + CONTAINER_SUFFIX);
    }

    public Optional<ModelFileIdentity> findIdentity(ConvertedSourceIndex entry) throws IOException {
        return findIdentity(entry, null);
    }

    public Optional<ModelFileIdentity> findIdentity(ConvertedSourceIndex entry, @Nullable String originVer)
        throws IOException {
        Objects.requireNonNull(entry, "entry");
        var expectedModelId = entry.modelId();
        var expectedContainerId = entry.containerId();
        var file = containerPath(expectedModelId, expectedContainerId);
        try {
            final ModelFileIdentity identity;
            try (var channel = cache.openRead(file)) {
                identity = ModelFileIdentityReader.read(channel);
                if (originVer != null) {
                    var view = new ModelFileView(channel);
                    if (!originVer.equals(view.getManifest().info().properties().originVer()))
                        return Optional.empty();
                }
            }
            if (!identity.modelId().equals(expectedModelId) || !identity.containerId().equals(expectedContainerId)) {
                return Optional.empty();
            }
            return Optional.of(identity);
        } catch (java.nio.file.NoSuchFileException absent) {
            return Optional.empty();
        } catch (AssetLoadException accessOrContent) {
            if (accessOrContent.reason() == AssetLoadException.Reason.ACCESS) {
                throw accessOrContent;
            }
            return Optional.empty();
        } catch (FileSystemException | SecurityException infrastructureFailure) {
            throw infrastructureFailure;
        } catch (IOException | RuntimeException invalid) {
            return Optional.empty();
        }
    }

    public ModelFileIdentity commit(RawCompileResult compiled, CatalogLocation location) throws IOException {
        Objects.requireNonNull(compiled, "compiled");
        final ModelFileIdentity stagedIdentity;
        try (var staged = ManagedContainer.verifyCachedFile(compiled.stagedContainer(), cache)) {
            stagedIdentity = staged.identity();
        }
        var actualModelId = stagedIdentity.modelId();
        if (!compiled.modelHash().equals(actualModelId)) {
            throw new ModelHashMismatchException(location, compiled.modelHash(), actualModelId);
        }
        var containerId = stagedIdentity.containerId();
        var target = containerPath(actualModelId, containerId);
        cache.materializeReplacing("converted-container", convertedRoot + "/" + identityKey(stagedIdentity), target,
            path -> fullyMatches(path, stagedIdentity), temporary -> {
                try (var input = cache.openRead(compiled.stagedContainer())) {
                    input.transferTo(0, input.size(), temporary);
                }
                try (var verified = ManagedContainer.verifyCachedFile(temporary)) {
                    if (!verified.identity().equals(stagedIdentity)) {
                        throw new IOException("Converted container identity changed before commit");
                    }
                }
            });
        return stagedIdentity;
    }

    public Path objectPath(Hash256 modelId, Hash256 containerId) {
        return containerPath(modelId, containerId);
    }

    private boolean fullyMatches(CacheFile file, ModelFileIdentity expected) throws IOException {
        try (var verified = ManagedContainer.verifyCachedFile(file)) {
            return verified.identity().equals(expected);
        } catch (AssetLoadException failure) {
            if (failure.reason() == AssetLoadException.Reason.ACCESS) {
                throw failure;
            }
            return false;
        } catch (FileSystemException | SecurityException failure) {
            throw failure;
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    private static String identityKey(ModelFileIdentity identity) {
        return identity.modelId() + ":" + identity.containerId();
    }

}
