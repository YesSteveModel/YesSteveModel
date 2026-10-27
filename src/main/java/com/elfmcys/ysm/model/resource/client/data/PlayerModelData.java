package com.elfmcys.ysm.model.resource.client.data;

import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.model.resource.client.PlayerModelVariant;
import com.elfmcys.ysm.geckolib3.file.AnimationControllerFile;
import com.elfmcys.ysm.util.FifoHashMap;

import java.util.List;
import java.util.Objects;

public record PlayerModelData(FifoHashMap<String, PlayerModelVariant> variants, AnimationStore animations,
    AnimationStore firstPersonAnimations, List<AnimationControllerFile> animationControllerFiles,
    List<AnimationControllerFile> fpArmAnimationControllerFiles) implements RenderTargetData {
    public PlayerModelData(FifoHashMap<String, PlayerModelVariant> variants, AnimationStore animations,
        AnimationStore firstPersonAnimations, List<AnimationControllerFile> controllers) {
        this(variants, animations, firstPersonAnimations, controllers, controllers);
    }

    public PlayerModelData {
        Objects.requireNonNull(variants, "variants");
        Objects.requireNonNull(animations, "animations");
        Objects.requireNonNull(firstPersonAnimations, "firstPersonAnimations");
        animationControllerFiles = List.copyOf(animationControllerFiles);
        fpArmAnimationControllerFiles = List.copyOf(fpArmAnimationControllerFiles);
    }
}
