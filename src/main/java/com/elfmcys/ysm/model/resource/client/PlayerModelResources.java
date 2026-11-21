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
    private final AnimationStore.View animations;
    private final AnimationStore.View fpArmAnimations;
    private final ConditionManager conditionManager;
    private final FPArmConditionManager fpArmConditionManager;
    private final String defaultTextureName;
    private final PlayerModelVariant defaultVariant;
    private final BiConsumer<CustomPlayerEntity, AnimationData> playerControllerFactory;
    private final BiConsumer<CustomFirstPersonArmEntity, AnimationData> fpArmControllerFactory;

    public PlayerModelResources(FifoHashMap<String, PlayerModelVariant> variants, AnimationStore.View animations,
        AnimationStore.View fpArmAnimations, ConditionManager conditionManager,
        FPArmConditionManager fpArmConditionManager, String defaultTextureName) {
        this.variants = variants;
        this.animations = animations;
        this.fpArmAnimations = fpArmAnimations;
        this.conditionManager = conditionManager;
        this.fpArmConditionManager = fpArmConditionManager;
        this.defaultTextureName = defaultTextureName;
        this.defaultVariant = Objects.requireNonNull(variants.get(defaultTextureName), "defaultVariant");
        this.playerControllerFactory = PlayerControllerCollection.build(this);
        this.fpArmControllerFactory = FPArmControllerCollection.build(this);
    }

    public FifoHashMap<String, PlayerModelVariant> variants() {
        return variants;
    }

    public AnimationStore.View animations() {
        return animations;
    }

    public AnimationStore.View fpArmAnimations() {
        return fpArmAnimations;
    }

    public ConditionManager conditionManager() {
        return conditionManager;
    }

    public FPArmConditionManager fpArmConditionManager() {
        return fpArmConditionManager;
    }

    public Object2ReferenceMap<String, AnimationControllerData> animationControllers() {
        return animations.controllers();
    }

    public Object2ReferenceMap<String, AnimationControllerData> fpArmAnimationControllers() {
        return fpArmAnimations.controllers();
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
