package com.elfmcys.ysm.model.resource.client.data;

import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.model.resource.client.PlayerModelVariant;
import com.elfmcys.ysm.util.FifoHashMap;

import java.util.Objects;

public record PlayerModelData(FifoHashMap<String, PlayerModelVariant> variants, AnimationStore.View animations,
    AnimationStore.View firstPersonAnimations) implements RenderTargetData {
    public PlayerModelData {
        Objects.requireNonNull(variants, "variants");
        Objects.requireNonNull(animations, "animations");
        Objects.requireNonNull(firstPersonAnimations, "firstPersonAnimations");
    }
}
