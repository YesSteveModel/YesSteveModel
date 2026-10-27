package com.elfmcys.ysm.client.controller.collections;

import com.elfmcys.ysm.client.animation.condition.ConditionArmor;
import com.elfmcys.ysm.client.animation.predicate.*;
import com.elfmcys.ysm.client.controller.*;
import com.elfmcys.ysm.client.entity.CustomProjectileEntity;
import com.elfmcys.ysm.model.resource.client.CommonAsset;
import com.elfmcys.ysm.model.resource.client.ProjectileModelResources;
import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData;
import com.elfmcys.ysm.geckolib3.core.controller.HybridAnimationController;
import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import org.apache.commons.lang3.function.TriFunction;

import java.util.function.BiFunction;
import java.util.function.BiConsumer;
import com.elfmcys.ysm.geckolib3.core.manager.AnimationData;

public class ProjectileControllerCollection {
    private static final String CATEGORY = "projectile";
    private static final AnimationControllerCollection<CustomProjectileEntity, ProjectileModelResources> COLLECTION =
        new AnimationControllerCollection<>();

    @SuppressWarnings("rawtypes,unchecked,deprecation")
    private static void init() {
        single("pre_main", null, true,
            (name, entity) -> new HybridAnimationController(entity, name, 0, new EmptyPredicate()));
        single("main", ProjectileMainPredicate.ANIM_LIST, true,
            (name, entity) -> new HybridAnimationController(entity, name, 0.1f, new ProjectileMainPredicate()));
        single("post_main", null, true,
            (name, entity) -> new HybridAnimationController(entity, name, 0, new EmptyPredicate()));

        parallel("parallel", (name, entity, anim) -> new HybridAnimationController(entity, name, 0,
            anim != null ? new ParallelPredicate(anim) : EmptyPredicate.INSTANCE, true));
    }

    public static BiConsumer<CustomProjectileEntity, AnimationData> build(ProjectileModelResources model,
        CommonAsset assets) {
        COLLECTION.initialize(ProjectileControllerCollection::init);
        return COLLECTION.build(model, assets, model.animations());
    }

    private static ControllerDiscovery<CustomProjectileEntity, ProjectileModelResources> simple(String name,
        BiFunction<String, CustomProjectileEntity, IAnimationController<CustomProjectileEntity>> simpleFactory) {
        var controllerName = String.format("%s.%s", CATEGORY, name);
        return COLLECTION.add((model, container, animations) -> (animatable, consumer) -> {
            consumer.accept(simpleFactory.apply(controllerName, animatable));
        });
    }

    private static ControllerDiscovery<CustomProjectileEntity, ProjectileModelResources> single(String name,
        String[] animations, boolean hybrid,
        BiFunction<String, CustomProjectileEntity, IAnimationController<CustomProjectileEntity>> controllerFunc) {
        return COLLECTION
            .add(new SingleControllerDiscovery<>(CATEGORY, name, animations, hybrid, Adapter.INSTANCE, controllerFunc));
    }

    private static ControllerDiscovery<CustomProjectileEntity, ProjectileModelResources> parallel(String name,
        TriFunction<String, CustomProjectileEntity, String,
            IAnimationController<CustomProjectileEntity>> controllerFunc) {
        return COLLECTION
            .add(new ParallelControllerDiscovery<>(CATEGORY, name, false, Adapter.INSTANCE, controllerFunc));
    }

    private static class Adapter implements ResourceAdapter<ProjectileModelResources> {
        static final Adapter INSTANCE = new Adapter();

        @Override
        public Object2ReferenceMap<String, AnimationControllerData> getControllers(ProjectileModelResources model,
            CommonAsset assets) {
            return model.controllers();
        }

        @Override
        public ConditionArmor getArmorCondition(ProjectileModelResources model, CommonAsset assets) {
            return null;
        }
    }
}
