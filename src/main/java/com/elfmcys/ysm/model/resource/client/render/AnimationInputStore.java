package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.compatibility.AbiVersion;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.MolangEventCatalog;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.schema.baked.asset.BakedAnimationSet;
import com.elfmcys.ysm.format.schema.baked.asset.BakedAssetView;
import com.elfmcys.ysm.format.schema.baked.asset.BakedAssetWriter;
import com.elfmcys.ysm.format.schema.baked.asset.ProgramInputDescriptor;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.model.storage.BakeIndexStore;
import com.elfmcys.ysm.model.storage.CacheFile;
import com.elfmcys.ysm.model.storage.CacheReadFailure;
import com.elfmcys.ysm.model.storage.DerivedStorageWork;
import com.elfmcys.ysm.model.storage.ModelHashing;
import com.elfmcys.ysm.natives.storage.CacheStorageTypes.*;
import com.elfmcys.ysm.proto.baked.asset.BakedAnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.util.ProtoUtil;
import dev.semaweave.compilation.CompilerOptions;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Region-owned unbound animation inputs with independent memory and optional persistent production. */
public final class AnimationInputStore {
    private static final String UNBOUND = "unbound";
    private static final String PURPOSE = "animation-input-v1";
    private final AtomicSharedCache cache;
    private final BakeIndexStore index;
    private final DerivedStorageWork work;
    private final int maximumMemoryEntries;
    private final LinkedHashMap<String, Cached> memory = new LinkedHashMap<>(16, 0.75f, true);

    public AnimationInputStore(Path cacheRoot, AtomicSharedCache cache) {
        this(cacheRoot, cache, 16);
    }

    public AnimationInputStore(Path cacheRoot, AtomicSharedCache cache, int maximumMemoryEntries) {
        Objects.requireNonNull(cacheRoot);
        this.cache = Objects.requireNonNull(cache);
        this.index = cache.bakeIndex();
        this.work = cache.derivedWork();
        if (maximumMemoryEntries <= 0)
            throw new IllegalArgumentException("Animation input memory limit must be positive");
        this.maximumMemoryEntries = maximumMemoryEntries;
    }

    /** Builds an independently checked expected index, without decoding any Portable Program body. */
    public static Request prepare(Hash256 containerId, String targetId, ExpressionSet set, ModelDataProto definition,
        StringDataProto strings, SettingsProto settings, CompilerOptions options) throws IOException {
        try {
            var prepared = ModelProgramInventory.prepare(targetId, set, definition, strings, settings,
                MolangEventCatalog.production());
            return prepare(containerId, targetId, set, definition, prepared, options);
        } catch (ModelProgramInventory.InventoryException invalid) {
            throw AssetLoadException.content("Invalid animation input index: " + targetId, invalid);
        }
    }

    public static Request prepare(Hash256 containerId, String targetId, ExpressionSet set, ModelDataProto definition,
        ModelProgramInventory.Prepared prepared, CompilerOptions options) throws IOException {
        var identity = AnimationInputIdentity.create(containerId, targetId, set, prepared.sourceFingerprint(), options);
        var descriptors = new ArrayList<ProgramInputDescriptor>();
        var programs = new LinkedHashMap<Integer, ProgramProto>();
        for (var source : prepared.rawPrograms()) {
            var bytes = ProtoUtil.serializeToArray(source.program());
            var id = descriptors.size();
            descriptors.add(new ProgramInputDescriptor(id, source.location().location(), source.location().key(),
                ModelHashing.blake3(bytes), bytes.length));
            programs.put(id, source.program());
        }
        return new Request(identity, definition, List.copyOf(descriptors), Map.copyOf(programs));
    }

    public Result acquire(Request request, boolean cacheOnly) throws IOException {
        return acquire(request, cacheOnly, () -> false);
    }

    /** Each result retains its own input source; eviction only releases this store's reference. */
    public Result acquire(Request request, boolean cacheOnly, BooleanSupplier cancelled) throws IOException {
        requireActive(cancelled);
        var identity = request.identity();
        var cached = memoryGet(identity.key());
        if (cached != null) {
            requireIndex(cached.animations(), request);
            return new Result(identity, cached.animations(), Source.MEMORY);
        }
        var disk = readValid(request);
        if (disk != null) {
            memoryPut(identity, disk);
            return new Result(identity, disk, Source.DISK);
        }
        if (cacheOnly)
            return null;

        var ids = new LinkedHashMap<ModelProgramInventory.SemanticLocation, Integer>();
        request.descriptors().forEach(value -> ids.put(value.location(), value.id()));
        var animations = new LinkedHashMap<String, List<BakedAnimationProto>>();
        for (var file : request.definition().animationFiles().entrySet()) {
            requireActive(cancelled);
            var baked = new LinkedHashMap<String, BakedAnimationProto>();
            for (var animation : file.getValue().animations()) {
                if (animation.name().isBlank())
                    throw AssetLoadException.content("Empty animation name");
                baked.put(animation.name(),
                    BakedAnimationMapper.bake(animation, "animation/" + file.getKey(),
                        location -> ids.get(new ModelProgramInventory.SemanticLocation(identity.targetId(),
                            identity.set(), location, ProgramRole.ANIMATION_SAMPLE, "")),
                        location -> ids.get(new ModelProgramInventory.SemanticLocation(identity.targetId(),
                            identity.set(), location, ProgramRole.ANIMATION_INSTRUCTION, ""))));
            }
            if (!baked.isEmpty())
                animations.put(file.getKey(), List.copyOf(baked.values()));
        }
        var immutableAnimations = Map.copyOf(animations);
        var resident = BakedAnimationSet.resident(immutableAnimations, request.descriptors(), request.programs());
        requireActive(cancelled);
        memoryPut(identity, resident);
        var descriptors = request.descriptors();
        var programs = request.programs();
        // Persistence owns only immutable inputs, never an execution resource or its cancellation.
        work.submit(gate -> {
            try (var staging = cache.reserve(Pool.BAKE); var writer = new BakedAssetWriter()) {
                writer.setData(identity.containerId(), identity.targetId(), identity.fingerprint(), immutableAnimations,
                    descriptors, programs);
                writer.write(staging.file());
                staging.publish(candidate -> validate(candidate, identity, descriptors),
                    replacement -> gate.commit(() -> {
                        replacement.run();
                        index.publish(indexKey(identity), staging.location());
                    }));
            }
        });
        return new Result(identity, resident, Source.COLD);
    }

    public boolean mayHaveInputs(Hash256 containerId) throws IOException {
        synchronized (this) {
            if (memory.values().stream().anyMatch(value -> value.identity().containerId().equals(containerId))) {
                return true;
            }
        }
        try {
            return index.hasPartition(partition(containerId));
        } catch (IOException unavailable) {
            CacheReadFailure.miss(containerId.toString(), unavailable);
            return false;
        }
    }

    public Path path(AnimationInputIdentity identity) throws IOException {
        var file = index.find(indexKey(identity)).orElseThrow(() -> new NoSuchFileException("Animation input miss"));
        return cache.path(Pool.BAKE, file.location().fileId());
    }

    public static BakeKey indexKey(AnimationInputIdentity identity) {
        return new BakeKey(partition(identity.containerId()), identity.key(), AbiVersion.CACHE_ANIMATION,
            "portable-input-v1", UNBOUND, UNBOUND, UNBOUND);
    }

    private static Partition partition(Hash256 containerId) {
        return new Partition(BakeKind.ANIMATION, AbiVersion.CACHE_ANIMATION, containerId, UNBOUND, PURPOSE);
    }

    public void awaitPersistence() throws InterruptedException {
        work.awaitIdle();
    }

    public synchronized void clearMemory() {
        memory.clear();
    }

    private BakedAnimationSet readValid(Request request) throws IOException {
        try {
            var path = path(request.identity());
            try (var channel = cache.openRead(path)) {
                var view = new BakedAssetView(channel);
                requireIdentity(view, request.identity());
                var animations = view.animationSet(() -> cache.openRead(path));
                requireIndex(animations, request);
                return animations;
            }
        } catch (IOException invalid) {
            CacheReadFailure.miss(request.identity().key(), invalid);
            return null;
        }
    }

    private static void requireIdentity(BakedAssetView view, AnimationInputIdentity identity) throws IOException {
        if (!view.matches(identity.containerId(), identity.targetId(), identity.fingerprint()))
            throw new IOException("Animation input identity mismatch");
    }

    private static void requireIndex(BakedAnimationSet animations, Request request) throws IOException {
        if (!animations.descriptors().equals(request.descriptors()))
            throw new IOException("Animation input program descriptors mismatch");
        var files = new LinkedHashMap<String, List<String>>();
        request.definition().animationFiles().forEach((file, value) -> {
            var names = new LinkedHashMap<String, Boolean>();
            value.animations().forEach(animation -> names.put(animation.name(), true));
            if (!names.isEmpty())
                files.put(file, List.copyOf(names.keySet()));
        });
        if (!animations.files().equals(files))
            throw new IOException("Animation input name index mismatch");
    }

    private static boolean validate(CacheFile file, AnimationInputIdentity identity,
        List<ProgramInputDescriptor> descriptors) throws IOException {
        var view = new BakedAssetView(file);
        requireIdentity(view, identity);
        var animations = view.animationSet(() -> file);
        if (!animations.descriptors().equals(descriptors))
            throw new IOException("Animation input staging index mismatch");
        for (var entry : animations.files().entrySet())
            for (var name : entry.getValue())
                view.readAnimation(file, entry.getKey(), name);
        for (var descriptor : descriptors)
            view.readProgram(file, descriptor.id());
        return true;
    }

    private synchronized Cached memoryGet(String key) {
        return memory.get(key);
    }

    private synchronized void memoryPut(AnimationInputIdentity identity, BakedAnimationSet value) {
        memory.put(identity.key(), new Cached(identity, value));
        while (memory.size() > maximumMemoryEntries)
            memory.remove(memory.keySet().iterator().next());
    }

    private static void requireActive(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean())
            throw new CancellationException("Animation input acquisition cancelled");
    }

    public record Request(AnimationInputIdentity identity, ModelDataProto definition,
        List<ProgramInputDescriptor> descriptors, Map<Integer, ProgramProto> programs) {
        public Request {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(definition);
            descriptors = List.copyOf(descriptors);
            programs = Map.copyOf(programs);
        }
    }

    public record Result(AnimationInputIdentity identity, BakedAnimationSet animations, Source source) {
    }

    public enum Source {
        MEMORY, DISK, COLD
    }

    private record Cached(AnimationInputIdentity identity, BakedAnimationSet animations) {
    }
}
