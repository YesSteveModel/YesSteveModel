package com.elfmcys.ysm.client.controller;

import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.model.resource.client.CommonAsset;
import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import com.elfmcys.ysm.geckolib3.core.manager.AnimationData;

public class AnimationControllerCollection<T extends AnimatableEntity<?>, TModel> {
    private final ReferenceArrayList<ControllerDiscovery<T, TModel>> discoveries = new ReferenceArrayList<>();
    private boolean initialized;

    public synchronized void initialize(Runnable initializer) {
        Objects.requireNonNull(initializer, "initializer");
        if (initialized) {
            return;
        }
        initializer.run();
        initialized = true;
    }

    public BiConsumer<T, AnimationData> build(TModel model, CommonAsset assets,
        com.elfmcys.ysm.model.resource.client.AnimationStore source) {
        var factories = new ReferenceArrayList<ControllerFactory<T>>(discoveries.size());
        try (var phase = source.openReadPhase()) {
            for (var discovery : discoveries) {
                factories.add(discovery.process(model, assets, phase.animations()));
            }
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
        return (animatable, animations) -> {
            Consumer<IAnimationController<T>> consumer = animations::addAnimationController;
            for (var factory : factories) {
                factory.create(animatable, consumer);
            }
        };
    }

    public ControllerDiscovery<T, TModel> add(ControllerDiscovery<T, TModel> factory) {
        Objects.requireNonNull(factory, "factory");
        discoveries.add(factory);
        return factory;
    }
}
