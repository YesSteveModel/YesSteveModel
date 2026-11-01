package com.elfmcys.ysm.geckolib3.core.controller;

import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import java.util.HashMap;
import java.util.Map;

/** Derived resolution state, accessed only in the owning entity's execution window. */
final class ControllerAnimationCache {
    private final AnimatableEntity<?> entity;
    private final Map<String, AnimationStore.Resolution> entries = new HashMap<>();
    private AnimationStore source;

    ControllerAnimationCache(AnimatableEntity<?> entity) {
        this.entity = entity;
    }

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
