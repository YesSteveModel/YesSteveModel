package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.client.controller.collections.ProjectileControllerCollection;
import com.elfmcys.ysm.client.entity.CustomProjectileEntity;
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;

import java.util.function.Consumer;
import java.util.function.BiConsumer;
import com.elfmcys.ysm.geckolib3.core.manager.AnimationData;

public class ProjectileModelResources implements RenderTargetResources {
    private final GeoModel model;
    private final AnimationStore animations;
    private final Object2ReferenceMap<String, AnimationControllerData> controllers;
    private final BiConsumer<CustomProjectileEntity, AnimationData> controllerFactory;

    public ProjectileModelResources(GeoModel model, AnimationStore animations,
        Object2ReferenceMap<String, AnimationControllerData> controllers, CommonAsset assets) {
        this.model = model;
        this.animations = animations;
        this.controllers = controllers;
        this.controllerFactory = ProjectileControllerCollection.build(this, assets);
    }

    public GeoModel model() {
        return model;
    }

    public AnimationStore animations() {
        return animations;
    }

    public Object2ReferenceMap<String, AnimationControllerData> controllers() {
        return controllers;
    }

    public BiConsumer<CustomProjectileEntity, AnimationData> controllerFactory() {
        return controllerFactory;
    }
}
