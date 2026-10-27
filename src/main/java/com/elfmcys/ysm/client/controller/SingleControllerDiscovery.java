package com.elfmcys.ysm.client.controller;

import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.model.resource.client.CommonAsset;
import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;

import java.util.function.BiFunction;

public class SingleControllerDiscovery<T extends AnimatableEntity<?>, TModel>
    implements ControllerDiscovery<T, TModel> {
    private final String controllerName;
    private final String scriptName;
    private final String[] animationNames;
    private final boolean hybrid;
    private final ResourceAdapter<TModel> resourceAdapter;
    private final BiFunction<String, T, IAnimationController<T>> controllerFunc;

    public SingleControllerDiscovery(String category, String name, String[] animationNames, boolean hybrid,
        ResourceAdapter<TModel> resourceAdapter, BiFunction<String, T, IAnimationController<T>> controllerFunc) {
        this.controllerName = String.format("%s.%s", category, name);
        this.scriptName = String.format("%s_ctrl_%s", category, name);
        this.animationNames = animationNames;
        this.hybrid = hybrid;
        this.resourceAdapter = resourceAdapter;
        this.controllerFunc = controllerFunc;
    }

    @Override
    public ControllerFactory<T> process(TModel model, CommonAsset assets,
        com.elfmcys.ysm.model.resource.client.AnimationStore animations) {
        var accept = false;
        if (hybrid && resourceAdapter.getControllers(model, assets).containsKey(controllerName)) {
            accept = true;
        } else if (assets.eventHandlers().containsKey(scriptName)) {
            accept = true;
        } else if (animationNames != null) {
            for (String animationName : animationNames) {
                var anim = animations.get(animationName);
                if (anim != null && !anim.isEmpty()) {
                    accept = true;
                    break;
                }
            }
        }
        if (accept) {
            return ((animatable, consumer) -> {
                consumer.accept(controllerFunc.apply(controllerName, animatable));
            });
        } else {
            return ((animatable, consumer) -> {
            });
        }
    }
}
