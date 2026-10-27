package com.elfmcys.ysm.format.schema.baked.asset;

import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.model.resource.client.ModelResourceFailureGate;
import com.elfmcys.ysm.model.resource.client.render.BakedAnimationMapper;
import com.elfmcys.ysm.proto.baked.asset.BakedAnimationProto;
import com.elfmcys.ysm.util.ResourceDiagnostics;
import com.elfmcys.ysm.format.schema.file.ReadPhaseSet;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable animation index with either resident or independently verified lazy payloads. */
public record BakedAnimationSet(Map<String, List<String>> files, Source source) {
    public BakedAnimationSet {
        files = Map.copyOf(files);
    }

    public static BakedAnimationSet empty() {
        return new BakedAnimationSet(Map.of(), (file, name) -> null);
    }

    public static BakedAnimationSet resident(Map<String, List<BakedAnimationProto>> files) throws IOException {
        var names = new LinkedHashMap<String, List<String>>();
        var payloads = new LinkedHashMap<String, BakedAnimationProto>();
        for (var file : files.entrySet()) {
            names.put(file.getKey(), file.getValue().stream().map(BakedAnimationProto::name).toList());
            for (var animation : file.getValue()) {
                payloads.put(key(file.getKey(), animation.name()), animation);
            }
        }
        var immutable = Map.copyOf(payloads);
        return new BakedAnimationSet(names, (file, name) -> immutable.get(key(file, name)));
    }

    public AnimationStore bind(Iterable<String> selectedFiles, RenderTargetExpressionModule module,
        AnimationStore fallback) {
        var index = new LinkedHashMap<String, String>();
        for (var file : selectedFiles) {
            var names = files.get(file);
            if (names == null)
                continue;
            names.forEach(name -> index.put(name, file));
        }
        return AnimationStore.lazyBound(index.keySet(), new AnimationStore.AnimationLoader() {
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
                var bound = BakedAnimationMapper.animation(animation, module);
                ResourceDiagnostics.track(ResourceDiagnostics.Kind.ANIM, bound, index.get(name) + '/' + name + " bones="
                    + bound.boneAnimations.size() + " ticks=" + bound.animationLength);
                return bound;
            }
        }, fallback, ignored -> ModelResourceFailureGate.none());
    }

    static String key(String file, String name) {
        return file + '\0' + name;
    }

    @FunctionalInterface
    public interface Source {
        BakedAnimationProto load(String file, String name) throws IOException;

        default ReadSource openReadPhase() throws IOException {
            return new ReadSource() {
                @Override
                public BakedAnimationProto load(String file, String name) throws IOException {
                    return Source.this.load(file, name);
                }
            };
        }
    }

    public interface ReadSource extends Source, AutoCloseable {
        @Override
        default void close() throws IOException {}
    }
}
