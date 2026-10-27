package com.elfmcys.ysm.client.controller;

import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.model.resource.client.CommonAsset;

import java.util.function.Predicate;

public interface ControllerDiscovery<T extends AnimatableEntity<?>, TModel> {
    ControllerFactory<T> process(TModel model, CommonAsset assets,
        com.elfmcys.ysm.model.resource.client.AnimationStore animations);

    default ControllerDiscovery<T, TModel> withCondition(Predicate<T> test) {
        return (model, asset, animations) -> {
            var collection = process(model, asset, animations);
            return (animatable, consumer) -> {
                if (test.test(animatable)) {
                    collection.create(animatable, consumer);
                }
            };
        };
    }
}
