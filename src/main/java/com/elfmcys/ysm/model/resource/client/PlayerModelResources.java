package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.client.animation.condition.ConditionManager;
import com.elfmcys.ysm.client.animation.condition.FPArmConditionManager;
import com.elfmcys.ysm.client.controller.collections.FPArmControllerCollection;
import com.elfmcys.ysm.client.controller.collections.PlayerControllerCollection;
import com.elfmcys.ysm.client.entity.CustomFirstPersonArmEntity;
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData;
import com.elfmcys.ysm.util.FifoHashMap;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import com.elfmcys.ysm.geckolib3.core.manager.AnimationData;

public class PlayerModelResources implements RenderTargetResources {
    private final FifoHashMap<String, PlayerModelVariant> variants;
    private final AnimationStore animations;
    private final AnimationStore fpArmAnimations;
    private final ConditionManager conditionManager;
    private final FPArmConditionManager fpArmConditionManager;
    private final Object2ReferenceMap<String, AnimationControllerData> animationControllers;
    private final Object2ReferenceMap<String, AnimationControllerData> fpArmAnimationControllers;
    private final String defaultTextureName;
    private final PlayerModelVariant defaultVariant;
    private final BiConsumer<CustomPlayerEntity, AnimationData> playerControllerFactory;
    private final BiConsumer<CustomFirstPersonArmEntity, AnimationData> fpArmControllerFactory;

    public PlayerModelResources(FifoHashMap<String, PlayerModelVariant> variants, AnimationStore animations,
        AnimationStore fpArmAnimations, ConditionManager conditionManager, FPArmConditionManager fpArmConditionManager,
        Object2ReferenceMap<String, AnimationControllerData> animationControllers, String defaultTextureName,
        CommonAsset assets) {
        this(variants, animations, fpArmAnimations, conditionManager, fpArmConditionManager, animationControllers,
            animationControllers, defaultTextureName, assets, assets);
    }

    public PlayerModelResources(FifoHashMap<String, PlayerModelVariant> variants, AnimationStore animations,
        AnimationStore fpArmAnimations, ConditionManager conditionManager, FPArmConditionManager fpArmConditionManager,
        Object2ReferenceMap<String, AnimationControllerData> animationControllers,
        Object2ReferenceMap<String, AnimationControllerData> fpArmAnimationControllers, String defaultTextureName,
        CommonAsset assets, CommonAsset armAssets) {
        this.variants = variants;
        this.animations = animations;
        this.fpArmAnimations = fpArmAnimations;
        this.conditionManager = conditionManager;
        this.animationControllers = animationControllers;
        this.fpArmAnimationControllers = fpArmAnimationControllers;
        this.fpArmConditionManager = fpArmConditionManager;
        this.defaultTextureName = defaultTextureName;
        this.defaultVariant = Objects.requireNonNull(variants.get(defaultTextureName), "defaultVariant");
        this.playerControllerFactory = PlayerControllerCollection.build(this, assets);
        this.fpArmControllerFactory = FPArmControllerCollection.build(this, armAssets);
    }

    public FifoHashMap<String, PlayerModelVariant> variants() {
        return variants;
    }

    public AnimationStore animations() {
        return animations;
    }

    public AnimationStore fpArmAnimations() {
        return fpArmAnimations;
    }

    public ConditionManager conditionManager() {
        return conditionManager;
    }

    public FPArmConditionManager fpArmConditionManager() {
        return fpArmConditionManager;
    }

    public Object2ReferenceMap<String, AnimationControllerData> animationControllers() {
        return animationControllers;
    }

    public Object2ReferenceMap<String, AnimationControllerData> fpArmAnimationControllers() {
        return fpArmAnimationControllers;
    }

    public String defaultTextureName() {
        return defaultTextureName;
    }

    public PlayerModelVariant defaultVariant() {
        return defaultVariant;
    }

    public BiConsumer<CustomPlayerEntity, AnimationData> playerControllerFactory() {
        return playerControllerFactory;
    }

    public BiConsumer<CustomFirstPersonArmEntity, AnimationData> fpArmControllerFactory() {
        return fpArmControllerFactory;
    }
}
