package com.elfmcys.ysm.geckolib3.core.controller;

import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import java.util.HashMap;
import java.util.Map;

/**
 * Caches derived animation resolution within one controller's resource window.
 *
 * <p>Invariant: the resource resolution's cacheable flag decides whether success or absence is stable.
 * Source identity change clears all derived entries. Entries contain no mutable playback state.
 *
 * <p>Execution: confined to the owning entity's execution window. Players can share this cache only inside that controller.
 *
 * <p>Seam: implements [resolution and playback lifetimes](docs/architecture/animation/controllers-and-playback.md#解析缓存与播放寿命).
 */
final class ControllerAnimationCache {
    private final AnimatableEntity<?> entity;
    private final Map<String, AnimationStore.Resolution> entries = new HashMap<>();
    private AnimationStore source;

    ControllerAnimationCache(AnimatableEntity<?> entity) {
        this.entity = entity;
    }

    /**
     * Resolves the name against the current source and retains only results that the source declares cacheable.
     *
     * @return a resolution whose null animation means source-reported absence, including a transient fallback failure
     */
    AnimationStore.Resolution resolve(String name) {
        var current = entity.animationResolutionSource();
        if (source != current) {
            clear();
            source = current;
        }
        var result = entries.get(name);
        if (result == null) {
            result = entity.resolveAnimation(name);
            if (result.cacheable())
                entries.put(name, result);
        }
        return result;
    }

    void clear() {
        entries.clear();
        source = null;
    }
}
