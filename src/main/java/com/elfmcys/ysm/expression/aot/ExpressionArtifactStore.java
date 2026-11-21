package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.compatibility.AbiVersion;
import com.elfmcys.ysm.model.storage.BakeIndexStore;
import com.elfmcys.ysm.natives.storage.CacheStorageTypes.*;
import com.elfmcys.ysm.expression.host.ExpressionBinding;
import com.elfmcys.ysm.expression.host.ExpressionIdentityHash;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;

import dev.semaweave.compilation.ArtifactCodecs;
import dev.semaweave.compilation.ArtifactIdentityMismatchException;
import dev.semaweave.compilation.ArtifactStructuralException;
import dev.semaweave.compilation.CompiledArtifact;
import dev.semaweave.program.ReadOnlyBytes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Objects;
import com.elfmcys.ysm.model.storage.DerivedStorageWork;

/** Bounded memory and atomic local-disk store for independently published AOT artifacts. */
public final class ExpressionArtifactStore {
    private static final int DEFAULT_MEMORY_ENTRIES = 16;

    private final AtomicSharedCache fileAccess;
    private final ArtifactMaterializer materializer;
    private final DerivedStorageWork work;
    private final BakeIndexStore index;
    private final int maximumMemoryEntries;
    private final LinkedHashMap<String, Cached> memory = new LinkedHashMap<>(16, 0.75f, true);

    public ExpressionArtifactStore(Path cacheRoot, AtomicSharedCache cache) {
        this(cacheRoot, cache, DEFAULT_MEMORY_ENTRIES);
    }

    public ExpressionArtifactStore(Path cacheRoot, AtomicSharedCache cache, int maximumMemoryEntries) {
        this(cacheRoot, cache, cache.bakeIndex(), materializer(cache), maximumMemoryEntries);
    }

    public ExpressionArtifactStore(Path cacheRoot, AtomicSharedCache cache, BakeIndexStore index) {
        this(cacheRoot, cache, index, materializer(cache), DEFAULT_MEMORY_ENTRIES);
    }

    ExpressionArtifactStore(Path cacheRoot, AtomicSharedCache cache, ArtifactMaterializer materializer,
        int maximumMemoryEntries) {
        this(cacheRoot, cache, cache.bakeIndex(), materializer, maximumMemoryEntries);
    }

    private ExpressionArtifactStore(Path cacheRoot, AtomicSharedCache cache, BakeIndexStore index,
        ArtifactMaterializer materializer, int maximumMemoryEntries) {
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        fileAccess = Objects.requireNonNull(cache, "cache");
        this.index = Objects.requireNonNull(index);
        this.materializer = Objects.requireNonNull(materializer, "materializer");
        work = cache.derivedWork();
        if (maximumMemoryEntries <= 0) {
            throw new IllegalArgumentException("expression memory cache limits must be positive");
        }
        this.maximumMemoryEntries = maximumMemoryEntries;
    }

    Result loadOrCompile(ExpressionArtifactLookup lookup, ArtifactProducer producer) throws IOException {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(producer, "producer");
        var cached = memoryGet(lookup.key());
        if (cached != null) {
            var identity = ExpressionArtifactIdentity.create(lookup.expected(), lookup);
            var artifact = cached.identity().fingerprint().equals(identity.fingerprint()) ? cached.artifact() : null;
            if (artifact != null) {
                return new Result(identity, artifact, Source.MEMORY);
            }
            memoryRemove(lookup.key());
        }

        var disk = readValidEnvelope(lookup);
        if (disk != null) {
            memoryPut(lookup.key(), new Cached(disk.identity(), disk.decoded()));
            return new Result(disk.identity(), disk.decoded(), Source.DISK);
        }

        var produced = Objects.requireNonNull(producer.produce(), "compiled artifact");
        requireLookup(produced.identity(), lookup);
        if (!produced.artifact().manifest().identity().equals(produced.identity().expected().identity())) {
            throw AssetLoadException.content("Local expression compiler returned a different identity");
        }
        var local = new Cached(produced.identity(), produced.artifact());
        memoryPut(lookup.key(), local);
        var materialize = materializer;
        work.submit(gate -> {
            try (var staging = fileAccess.reserve(Pool.BAKE)) {
                var encoded = ArtifactCodecs.v1().encode(produced.artifact(), ExpressionIdentityHash.SHA_256);
                requireBoundedArtifact(encoded, produced.identity());
                materialize.materialize(lookup.key(), staging, candidate -> validate(candidate, lookup),
                    candidate -> AotArtifactEnvelope.write(candidate, lookup, produced.identity(), encoded),
                    replacement -> gate.commit(() -> {
                        replacement.run();
                        index.publish(indexKey(lookup), staging.location());
                    }));
            }
        });
        return new Result(produced.identity(), produced.artifact(), Source.COLD);
    }

    Result loadExisting(ExpressionArtifactLookup lookup) throws IOException {
        var identity = ExpressionArtifactIdentity.create(lookup.expected(), lookup);
        var cached = memoryGet(lookup.key());
        if (cached != null && cached.identity().fingerprint().equals(identity.fingerprint())) {
            var artifact = cached.artifact();
            if (artifact != null)
                return new Result(identity, artifact, Source.MEMORY);
            memoryRemove(lookup.key());
        }
        var disk = readValidEnvelope(lookup);
        if (disk == null)
            return null;
        memoryPut(lookup.key(), new Cached(identity, disk.decoded()));
        return new Result(identity, disk.decoded(), Source.DISK);
    }

    public void awaitPersistence() throws InterruptedException {
        work.awaitIdle();
    }

    public synchronized void clearMemory() {
        memory.clear();
    }

    boolean mayHaveCachedArtifacts(Hash256 containerId, ExpressionBinding binding, ModelResourcePurpose purpose) {
        Objects.requireNonNull(containerId, "containerId");
        Objects.requireNonNull(binding, "binding");
        synchronized (this) {
            for (var cached : memory.values()) {
                if (cached.identity().containerId().equals(containerId)
                    && cached.identity().expressionBinding().equals(binding) && cached.identity().purpose() == purpose)
                    return true;
            }
        }
        try {
            return index.hasPartition(partition(containerId, binding, purpose));
        } catch (IOException unavailable) {
            if (!com.elfmcys.ysm.model.storage.CacheReadFailure.recoverable(unavailable))
                throw new java.io.UncheckedIOException(unavailable);
            return false;
        }
    }

    Path path(ExpressionArtifactIdentity identity) throws IOException {
        var file = index.find(indexKey(identity))
            .orElseThrow(() -> new java.nio.file.NoSuchFileException("Expression artifact index miss"));
        return fileAccess.path(Pool.BAKE, file.location().fileId());
    }

    private static Partition partition(Hash256 container, ExpressionBinding binding, ModelResourcePurpose purpose) {
        return new Partition(BakeKind.ANIMATION, AbiVersion.CACHE_ANIMATION, container, binding.key(),
            purpose.ruleId());
    }

    private static BakeKey indexKey(ExpressionArtifactIdentity identity) {
        return new BakeKey(partition(identity.containerId(), identity.expressionBinding(), identity.purpose()),
            identity.lookupKey(), AbiVersion.CACHE_ANIMATION, "", dev.semaweave.EngineAbi.current().packageName(),
            identity.expected().identity().artifactFingerprint().value().toString(),
            identity.expressionBinding().environment().fingerprint().value().toString());
    }

    private static boolean validate(com.elfmcys.ysm.model.storage.CacheFile file, ExpressionArtifactLookup lookup)
        throws IOException {
        try {
            var envelope = readEnvelope(file, lookup);
            return decode(envelope.artifact(), envelope.identity()) != null;
        } catch (InvalidEnvelopeException invalid) {
            com.elfmcys.ysm.model.storage.CacheReadFailure.miss(lookup.key(), invalid);
            return false;
        }
    }

    private static BakeKey indexKey(ExpressionArtifactLookup lookup) {
        return indexKey(ExpressionArtifactIdentity.create(lookup.expected(), lookup));
    }

    private ValidatedEnvelope readValidEnvelope(ExpressionArtifactLookup lookup) throws IOException {
        try {
            var indexed = index.find(indexKey(lookup));
            if (indexed.isEmpty())
                return null;
            var envelope = readEnvelope(fileAccess.path(Pool.BAKE, indexed.get().location().fileId()), lookup);
            var decoded = decode(envelope.artifact(), envelope.identity());
            return decoded == null ? null : new ValidatedEnvelope(envelope.identity(), decoded);
        } catch (IOException failure) {
            com.elfmcys.ysm.model.storage.CacheReadFailure.miss(lookup.key(), failure);
            return null;
        }
    }

    private static ArtifactMaterializer materializer(AtomicSharedCache cache) {
        Objects.requireNonNull(cache, "cache");
        return (key, staging, validator, writer, gate) -> {
            writer.write(staging.file());
            staging.publish(validator, gate);
        };
    }

    private static CompiledArtifact decode(ReadOnlyBytes bytes, ExpressionArtifactIdentity identity) {
        try {
            return ArtifactCodecs.v1().decodeTrustedLocal(bytes, identity.expected());
        } catch (ArtifactStructuralException | ArtifactIdentityMismatchException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void requireBoundedArtifact(ReadOnlyBytes artifact, ExpressionArtifactIdentity identity)
        throws AssetLoadException {
        var size = artifact.size();
        var maximum = ArtifactCodecs.v1().maximumEncodedBytes(identity.expected());
        if (size == 0 || size > maximum) {
            throw AssetLoadException.content("Expression artifact exceeds its bounded envelope");
        }
    }

    private Envelope readEnvelope(Path path, ExpressionArtifactLookup lookup) throws IOException {
        try (var channel = fileAccess.openRead(path)) {
            return readEnvelope(channel, lookup);
        }
    }

    private static Envelope readEnvelope(java.nio.channels.SeekableByteChannel channel, ExpressionArtifactLookup lookup)
        throws IOException {
        try {
            var identity = ExpressionArtifactIdentity.create(lookup.expected(), lookup);
            return new Envelope(identity, AotArtifactEnvelope.read(channel, lookup, identity));
        } catch (IOException invalid) {
            throw new InvalidEnvelopeException("invalid expression artifact envelope", invalid);
        }
    }

    private static void requireLookup(ExpressionArtifactIdentity identity, ExpressionArtifactLookup lookup) {
        if (!identity.lookupFingerprint().equals(lookup.fingerprint())
            || !identity.equals(ExpressionArtifactIdentity.create(lookup.expected(), lookup))) {
            throw new IllegalArgumentException("compiled expression identity lookup mismatch");
        }
    }

    private synchronized Cached memoryGet(String key) {
        return memory.get(key);
    }

    private synchronized void memoryRemove(String key) {
        memory.remove(key);
    }

    private synchronized void memoryPut(String key, Cached value) {
        memory.put(key, value);
        while (memory.size() > maximumMemoryEntries) {
            var eldest = memory.entrySet().iterator();
            eldest.next();
            eldest.remove();
        }
    }

    @FunctionalInterface
    interface ArtifactProducer {
        Produced produce() throws IOException;
    }

    @FunctionalInterface
    interface ArtifactMaterializer {
        void materialize(String key, AtomicSharedCache.Staging target, AtomicSharedCache.CacheValidator validator,
            AtomicSharedCache.CacheWriter writer, AtomicSharedCache.CommitGate gate) throws IOException;
    }

    public enum Source {
        COLD, MEMORY, DISK
    }

    record Produced(ExpressionArtifactIdentity identity, CompiledArtifact artifact) {
        public Produced {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(artifact, "artifact");
        }
    }

    record Result(ExpressionArtifactIdentity identity, CompiledArtifact artifact, Source source) {
        public Result {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(source, "source");
        }
    }

    private record Cached(ExpressionArtifactIdentity identity, CompiledArtifact artifact) {
    }

    private record Envelope(ExpressionArtifactIdentity identity, ReadOnlyBytes artifact) {
    }

    private record ValidatedEnvelope(ExpressionArtifactIdentity identity, CompiledArtifact decoded) {
    }

    private static final class InvalidEnvelopeException extends IOException {
        private InvalidEnvelopeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

}
