package com.elfmcys.ysm.format.schema.baked.asset;

import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.model.resource.client.render.BakedAnimationMapper;
import com.elfmcys.ysm.proto.baked.asset.BakedAnimationProto;
import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import com.elfmcys.ysm.util.ResourceDiagnostics;
import com.elfmcys.ysm.format.schema.file.ReadPhaseSet;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Objects;
import java.util.HashSet;

/** Immutable animation index with either resident or independently verified lazy payloads. */
public record BakedAnimationSet(Map<String, List<String>> files, List<ProgramInputDescriptor> descriptors,
    Source source) {
    public BakedAnimationSet(Map<String, List<String>> files, Source source) {
        this(files, List.of(), source);
    }

    public BakedAnimationSet {
        var immutable = new LinkedHashMap<String, List<String>>();
        files.forEach((file, names) -> immutable.put(file, List.copyOf(names)));
        files = Collections.unmodifiableMap(immutable);
        descriptors = List.copyOf(descriptors);
        Objects.requireNonNull(source, "source");
        var locations = new HashSet<com.elfmcys.ysm.expression.inventory.ModelProgramInventory.SemanticLocation>();
        var keys = new HashSet<dev.semaweave.ProgramKey>();
        for (var id = 0; id < descriptors.size(); id++) {
            var descriptor = descriptors.get(id);
            if (descriptor.id() != id || !locations.add(descriptor.location()) || !keys.add(descriptor.key()))
                throw new IllegalArgumentException("Invalid or duplicate program input descriptor: " + descriptor.id());
        }
    }

    public static BakedAnimationSet empty() {
        return new BakedAnimationSet(Map.of(), (file, name) -> null);
    }

    public static BakedAnimationSet resident(Map<String, List<BakedAnimationProto>> files) throws IOException {
        return resident(files, List.of(), Map.of());
    }

    public static BakedAnimationSet resident(Map<String, List<BakedAnimationProto>> files,
        List<ProgramInputDescriptor> descriptors, Map<Integer, ProgramProto> programs) throws IOException {
        var names = new LinkedHashMap<String, List<String>>();
        var payloads = new LinkedHashMap<String, BakedAnimationProto>();
        for (var file : files.entrySet()) {
            names.put(file.getKey(), file.getValue().stream().map(BakedAnimationProto::name).toList());
            for (var animation : file.getValue()) {
                payloads.put(key(file.getKey(), animation.name()), animation);
            }
        }
        var immutable = Map.copyOf(payloads);
        var inputs = Map.copyOf(programs);
        if (descriptors.size() != inputs.size()
            || descriptors.stream().anyMatch(value -> !inputs.containsKey(value.id())))
            throw new IllegalArgumentException("Program input payloads do not match their descriptors");
        return new BakedAnimationSet(names, descriptors, new Source() {
            @Override
            public BakedAnimationProto load(String file, String name) {
                return immutable.get(key(file, name));
            }

            @Override
            public ProgramProto loadProgram(int id) throws IOException {
                var program = inputs.get(id);
                if (program == null)
                    throw new IOException("Program input is missing: " + id);
                return program;
            }
        });
    }

    public ProgramProto loadProgram(int id) throws IOException {
        return source.loadProgram(id);
    }

    public AnimationStore.Index bind(Iterable<String> selectedFiles, RenderTargetExpressionModule module) {
        var index = new LinkedHashMap<String, String>();
        for (var file : selectedFiles) {
            var names = files.get(file);
            if (names == null)
                continue;
            names.forEach(name -> index.put(name, file));
        }
        return AnimationStore.Index.lazy(index.keySet(), new AnimationStore.AnimationLoader() {
            @Override
            public Animation load(String name) throws IOException {
                return bind(source, name);
            }

            @Override
            public AnimationStore.AnimationLoader inReadPhase(ReadPhaseSet phases) throws IOException {
                var local = phases.acquire(source, source::openReadPhase);
                return name -> bind(local, name);
            }

            private Animation bind(Source reader, String name) throws IOException {
                var animation = reader.load(index.get(name), name);
                if (animation == null)
                    return null;
                var bound = BakedAnimationMapper.animation(animation,
                    id -> module.requireFloatProgram(descriptor(id).location()),
                    id -> module.requireVoidProgram(descriptor(id).location()));
                ResourceDiagnostics.track(ResourceDiagnostics.Kind.ANIM, bound, index.get(name) + '/' + name + " bones="
                    + bound.boneAnimations.size() + " ticks=" + bound.animationLength);
                return bound;
            }
        });
    }

    private ProgramInputDescriptor descriptor(int id) {
        if (id < 0 || id >= descriptors.size())
            throw new IllegalArgumentException("Invalid animation input program id: " + id);
        return descriptors.get(id);
    }

    static String key(String file, String name) {
        return file + '\0' + name;
    }

    @FunctionalInterface
    public interface Source {
        BakedAnimationProto load(String file, String name) throws IOException;

        default ProgramProto loadProgram(int id) throws IOException {
            throw new IOException("Program input is unavailable: " + id);
        }

        default ReadSource openReadPhase() throws IOException {
            return new ReadSource() {
                @Override
                public BakedAnimationProto load(String file, String name) throws IOException {
                    return Source.this.load(file, name);
                }

                @Override
                public ProgramProto loadProgram(int id) throws IOException {
                    return Source.this.loadProgram(id);
                }
            };
        }
    }

    public interface ReadSource extends Source, AutoCloseable {
        @Override
        default void close() throws IOException {}
    }
}
