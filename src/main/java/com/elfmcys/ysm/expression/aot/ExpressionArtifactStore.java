package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.expression.host.ExpressionBinding;
import com.elfmcys.ysm.expression.host.ExpressionIdentityHash;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.schema.baked.asset.BakedAnimationSet;
import com.elfmcys.ysm.format.schema.baked.asset.BakedAssetView;
import com.elfmcys.ysm.format.schema.baked.asset.BakedAssetWriter;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.render.BakedAnimationCache;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.proto.baked.asset.BakedAnimationProto;

import dev.semaweave.compilation.ArtifactCodecs;
import dev.semaweave.compilation.ArtifactIdentityMismatchException;
import dev.semaweave.compilation.ArtifactStructuralException;
import dev.semaweave.compilation.CompiledArtifact;
import dev.semaweave.program.ReadOnlyBytes;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.elfmcys.ysm.model.storage.DerivedStorageWork;

/** Bounded memory and atomic local-disk store for complete animation sets and their AOT artifacts. */
public final class ExpressionArtifactStore {
    private static final int DEFAULT_MEMORY_ENTRIES = 16;

    private final Path cacheRoot;
    private final AtomicSharedCache fileAccess;
    private final ArtifactMaterializer materializer;
    private final DerivedStorageWork work;
    private final int maximumMemoryEntries;
    private final LinkedHashMap<String, Cached> memory = new LinkedHashMap<>(16, 0.75f, true);

    public ExpressionArtifactStore(Path cacheRoot, AtomicSharedCache cache) {
        this(cacheRoot, cache, DEFAULT_MEMORY_ENTRIES);
    }

    public ExpressionArtifactStore(Path cacheRoot, AtomicSharedCache cache, int maximumMemoryEntries) {
        this(cacheRoot, cache, materializer(cache), maximumMemoryEntries);
    }

    ExpressionArtifactStore(Path cacheRoot, ArtifactMaterializer materializer, int maximumMemoryEntries) {
        this(cacheRoot, new AtomicSharedCache(cacheRoot), materializer, maximumMemoryEntries);
    }

    private ExpressionArtifactStore(Path cacheRoot, AtomicSharedCache cache, ArtifactMaterializer materializer,
        int maximumMemoryEntries) {
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot");
        fileAccess = Objects.requireNonNull(cache, "cache");
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
                return new Result(identity, artifact, Source.MEMORY, cached.animations());
            }
            memoryRemove(lookup.key());
        }

        var target = path(lookup);
        ValidatedEnvelope disk;
        try {
            disk = readValidEnvelope(target, lookup);
        } catch (IOException unavailable) {
            YesSteveModel.LOGGER.warn("Failed to read optional expression cache key={}; compiling locally",
                lookup.key(), unavailable);
            disk = null;
        }
        if (disk != null) {
            memoryPut(lookup.key(), new Cached(disk.identity(), disk.decoded(), disk.animations()));
            return new Result(disk.identity(), disk.decoded(), Source.DISK, disk.animations());
        }

        var produced = Objects.requireNonNull(producer.produce(), "compiled artifact");
        requireLookup(produced.identity(), lookup);
        if (!produced.artifact().manifest().identity().equals(produced.identity().expected().identity())) {
            throw AssetLoadException.content("Local expression compiler returned a different identity");
        }
        var animations = BakedAnimationSet.resident(produced.animations());
        var local = new Cached(produced.identity(), produced.artifact(), animations);
        memoryPut(lookup.key(), local);
        var materialize = materializer;
        work.submit(gate -> {
            var encoded = ArtifactCodecs.v1().encode(produced.artifact(), ExpressionIdentityHash.SHA_256);
            requireBoundedArtifact(encoded, produced.identity());
            materialize.materialize(lookup.key(), target, candidate -> validate(candidate, lookup),
                candidate -> writeEnvelope(candidate, lookup, produced.identity(), encoded, produced.animations()),
                gate);
        });
        return new Result(produced.identity(), produced.artifact(), Source.COLD, animations);
    }

    Result loadExisting(ExpressionArtifactLookup lookup) throws IOException {
        var identity = ExpressionArtifactIdentity.create(lookup.expected(), lookup);
        var cached = memoryGet(lookup.key());
        if (cached != null && cached.identity().fingerprint().equals(identity.fingerprint())) {
            var artifact = cached.artifact();
            if (artifact != null)
                return new Result(identity, artifact, Source.MEMORY, cached.animations());
            memoryRemove(lookup.key());
        }
        var disk = readValidEnvelope(path(lookup), lookup);
        if (disk == null)
            return null;
        memoryPut(lookup.key(), new Cached(identity, disk.decoded(), disk.animations()));
        return new Result(identity, disk.decoded(), Source.DISK, disk.animations());
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
            fileAccess.observe(bindingDirectory(containerId, binding, purpose).resolve(".guard"), false).verify();
            return true;
        } catch (IOException unavailable) {
            return false;
        }
    }

    Path path(ExpressionArtifactIdentity identity) {
        return pathKey(identity.containerId(), identity.expressionBinding(), identity.purpose(), identity.lookupKey());
    }

    private Path path(ExpressionArtifactLookup lookup) {
        return pathKey(lookup.containerId(), lookup.expressionBinding(), lookup.purpose(), lookup.key());
    }

    private Path pathKey(Hash256 containerId, ExpressionBinding binding, ModelResourcePurpose purpose, String key) {
        return bindingDirectory(containerId, binding, purpose).resolve(key + BakedAnimationCache.CACHE_SUFFIX);
    }

    private Path bindingDirectory(Hash256 containerId, ExpressionBinding binding, ModelResourcePurpose purpose) {
        return cacheRoot.resolve("bake").resolve(BakedAnimationCache.CACHE_ABI).resolve(containerId.toString())
            .resolve(binding.key()).resolve(purpose.ruleId());
    }

    private static boolean validate(com.elfmcys.ysm.model.storage.CacheFile file, ExpressionArtifactLookup lookup)
        throws IOException {
        try {
            var view = new BakedAssetView(file);
            var envelope = readEnvelope(view, file, lookup, () -> file);
            if (decode(envelope.artifact(), envelope.identity()) == null)
                return false;
            for (var source : envelope.animations().files().entrySet()) {
                for (var name : source.getValue()) {
                    if (view.readAnimation(file, source.getKey(), name) == null)
                        return false;
                }
            }
            return true;
        } catch (InvalidEnvelopeException ignored) {
            return false;
        }
    }

    private ValidatedEnvelope readValidEnvelope(Path path, ExpressionArtifactLookup lookup) throws IOException {
        try {
            var envelope = readEnvelope(path, lookup);
            var decoded = decode(envelope.artifact(), envelope.identity());
            return decoded == null ? null
                : new ValidatedEnvelope(envelope.identity(), envelope.artifact(), decoded, envelope.animations());
        } catch (InvalidEnvelopeException | java.nio.file.NoSuchFileException ignored) {
            return null;
        }
    }

    private static ArtifactMaterializer materializer(AtomicSharedCache cache) {
        Objects.requireNonNull(cache, "cache");
        return (key, target, validator, writer, gate) -> cache.replaceValidated("baked-animation", key, target,
            validator, writer, gate);
    }

    private static CompiledArtifact decode(ReadOnlyBytes bytes, ExpressionArtifactIdentity identity) {
        try {
            return ArtifactCodecs.v1().decodeTrustedLocal(bytes, identity.expected());
        } catch (ArtifactStructuralException | ArtifactIdentityMismatchException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void writeEnvelope(com.elfmcys.ysm.model.storage.CacheFile file, ExpressionArtifactLookup lookup,
        ExpressionArtifactIdentity identity, ReadOnlyBytes artifact, Map<String, List<BakedAnimationProto>> animations)
        throws IOException {
        requireBoundedArtifact(artifact, identity);
        try (var writer = new BakedAssetWriter(); var channel = file) {
            writer.setData(lookup.containerId(), lookup.targetId(),
                new Hash256(lookup.fingerprint().value().bytes().toByteArray()), animations, artifact.toByteArray());
            writer.write(channel);
            channel.force();
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
        var observation = fileAccess.observe(path, false);
        try (var channel = observation.open(java.util.Set.of(StandardOpenOption.READ))) {
            return readEnvelope(channel, lookup, () -> observation.open(java.util.Set.of(StandardOpenOption.READ)));
        }
    }

    private static Envelope readEnvelope(java.nio.channels.SeekableByteChannel channel, ExpressionArtifactLookup lookup,
        BakedAssetView.ChannelOpener opener) throws IOException {
        try {
            var view = new BakedAssetView(channel);
            return readEnvelope(view, channel, lookup, opener);
        } catch (IOException | RuntimeException invalid) {
            throw new InvalidEnvelopeException("invalid baked animation set", invalid);
        }
    }

    private static Envelope readEnvelope(BakedAssetView view, java.nio.channels.SeekableByteChannel channel,
        ExpressionArtifactLookup lookup, BakedAssetView.ChannelOpener opener) throws IOException {
        if (!view.matches(lookup.containerId(), lookup.targetId(),
            new Hash256(lookup.fingerprint().value().bytes().toByteArray()))) {
            throw new InvalidEnvelopeException("baked animation set identity mismatch");
        }
        var artifact = view.readExpression(channel, ArtifactCodecs.v1().maximumEncodedBytes(lookup.expected()));
        return new Envelope(ExpressionArtifactIdentity.create(lookup.expected(), lookup),
            ReadOnlyBytes.copyOf(artifact), view.animationSet(opener));
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
        void materialize(String key, Path target, AtomicSharedCache.CacheValidator validator,
            AtomicSharedCache.CacheWriter writer, AtomicSharedCache.CommitGate gate) throws IOException;
    }

    public enum Source {
        COLD, MEMORY, DISK
    }

    record Produced(ExpressionArtifactIdentity identity, CompiledArtifact artifact,
        Map<String, List<BakedAnimationProto>> animations) {
        public Produced {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(animations, "animations");
            var snapshot = new LinkedHashMap<String, List<BakedAnimationProto>>();
            animations.forEach((name, values) -> snapshot.put(name, List.copyOf(values)));
            animations = Map.copyOf(snapshot);
        }
    }

    record Result(ExpressionArtifactIdentity identity, CompiledArtifact artifact, Source source,
        BakedAnimationSet animations) {
        public Result {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(animations, "animations");
            Objects.requireNonNull(source, "source");
        }
    }

    private record Cached(ExpressionArtifactIdentity identity, CompiledArtifact artifact,
        BakedAnimationSet animations) {
    }

    private record Envelope(ExpressionArtifactIdentity identity, ReadOnlyBytes artifact, BakedAnimationSet animations) {
    }

    private record ValidatedEnvelope(ExpressionArtifactIdentity identity, ReadOnlyBytes artifact,
        CompiledArtifact decoded, BakedAnimationSet animations) {
    }

    private static final class InvalidEnvelopeException extends IOException {
        private InvalidEnvelopeException(String message) {
            super(message);
        }

        private InvalidEnvelopeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

}
