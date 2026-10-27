package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.compatibility.AbiVersion;

import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Animation-set cache format identity and default resident binding. */
public final class BakedAnimationCache {
    public static final String CACHE_SUFFIX = ".anim.ysm-cache";
    public static final String CACHE_ABI =
        "animation-" + AbiVersion.CACHE_ANIMATION + "/" + dev.semaweave.EngineAbi.current().packageName();

    private BakedAnimationCache() {}

    static String profileKey() {
        return CACHE_ABI;
    }

    public static AnimationStore bindResident(
        Iterable<? extends java.util.Map.Entry<String, AnimationFileProto>> animationFiles,
        AnimationProtoMapper.ProgramBinder programBinder, BoundAnimationObserver observer) throws IOException {
        var domains = animationDomains(animationFiles);
        var bound = new LinkedHashMap<String, com.elfmcys.ysm.geckolib3.core.builder.Animation>();
        for (var animation : flatten(animationFiles)) {
            final com.elfmcys.ysm.geckolib3.core.builder.Animation value;
            try {
                value = AnimationProtoMapper.animation(animation, domains.get(animation.name()), programBinder);
            } catch (RuntimeException error) {
                throw AssetLoadException.content("Invalid animation: " + animation.name(), error);
            }
            observer.accept(animation, value);
            bound.put(animation.name(), value);
        }
        return AnimationStore.eager(bound);
    }

    @FunctionalInterface
    public interface BoundAnimationObserver {
        void accept(AnimationProto source, com.elfmcys.ysm.geckolib3.core.builder.Animation bound) throws IOException;
    }

    public static ArrayList<AnimationProto>
        flatten(Iterable<? extends java.util.Map.Entry<String, AnimationFileProto>> animationFiles) throws IOException {
        var result = new LinkedHashMap<String, AnimationProto>();
        for (var file : animationFiles) {
            for (var animation : file.getValue().animations()) {
                if (animation.name().isBlank()) {
                    throw AssetLoadException.content("Empty animation name in animation set");
                }
                result.put(animation.name(), animation);
            }
        }
        return new ArrayList<>(result.values());
    }

    private static Map<String, String> animationDomains(
        Iterable<? extends java.util.Map.Entry<String, AnimationFileProto>> animationFiles) throws IOException {
        var result = new LinkedHashMap<String, String>();
        for (var file : animationFiles) {
            for (var animation : file.getValue().animations()) {
                result.put(animation.name(), "animation/" + file.getKey());
            }
        }
        return Map.copyOf(result);
    }

}
