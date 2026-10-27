package com.elfmcys.ysm.client.controller;

import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.model.resource.client.CommonAsset;
import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMaps;
import it.unimi.dsi.fastutil.objects.ObjectRBTreeSet;

import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public class MultiControllerDiscovery<T extends AnimatableEntity<?>, TModel> implements ControllerDiscovery<T, TModel> {
    private final Predicate<String> controllerNamePattern;
    private final Predicate<String> eventNamePattern;
    private final ResourceAdapter<TModel> resourceAdapter;
    private final BiFunction<String, T, IAnimationController<T>> controllerFunc;

    public MultiControllerDiscovery(String category, String pattern, ResourceAdapter<TModel> resourceAdapter,
        BiFunction<String, T, IAnimationController<T>> controllerFunc) {
        this.controllerNamePattern =
            Pattern.compile(String.format("^%s\\.%s(_.+){0,1}$", category, pattern)).asMatchPredicate();
        this.eventNamePattern =
            Pattern.compile(String.format("^%s_ctrl_%s(_.+){0,1}$", category, pattern)).asMatchPredicate();
        this.resourceAdapter = resourceAdapter;
        this.controllerFunc = controllerFunc;
    }

    @Override
    public ControllerFactory<T> process(TModel model, CommonAsset assets,
        com.elfmcys.ysm.model.resource.client.AnimationStore animations) {
        var names = new ObjectRBTreeSet<String>();
        Object2ReferenceMaps.fastForEach(resourceAdapter.getControllers(model, assets), entry -> {
            if (controllerNamePattern.test(entry.getKey())) {
                names.add(entry.getKey());
            }
        });
        Object2ReferenceMaps.fastForEach(assets.eventHandlers(), entry -> {
            if (eventNamePattern.test(entry.getKey())) {
                var controllerName = entry.getKey().replace("_ctrl_", ".");
                names.add(controllerName);
            }
        });
        return (animatable, consumer) -> {
            for (String name : names) {
                consumer.accept(controllerFunc.apply(name, animatable));
            }
        };
    }
}
