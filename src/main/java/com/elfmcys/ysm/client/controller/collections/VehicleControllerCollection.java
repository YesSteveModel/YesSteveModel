package com.elfmcys.ysm.client.controller.collections;

import com.elfmcys.ysm.client.animation.condition.ConditionArmor;
import com.elfmcys.ysm.client.animation.predicate.*;
import com.elfmcys.ysm.client.controller.*;
import com.elfmcys.ysm.client.entity.CustomVehicleEntity;
import com.elfmcys.ysm.model.resource.client.CommonAsset;
import com.elfmcys.ysm.model.resource.client.VehicleModelResources;
import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData;
import com.elfmcys.ysm.geckolib3.core.controller.HybridAnimationController;
import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import org.apache.commons.lang3.function.TriFunction;

import java.util.function.BiFunction;
import java.util.function.BiConsumer;
import com.elfmcys.ysm.geckolib3.core.manager.AnimationData;

public class VehicleControllerCollection {
    private static final String CATEGORY = "vehicle";
    public static final String NAME_ORIGIN = CATEGORY + ".origin";

    private static final AnimationControllerCollection<CustomVehicleEntity, VehicleModelResources> COLLECTION =
        new AnimationControllerCollection<>();

    @SuppressWarnings("rawtypes,unchecked,deprecation")
    private static void init() {
        parallel("pre_parallel", (name, entity, anim) -> new HybridAnimationController(entity, name, 0,
            anim != null ? new ParallelPredicate(anim) : EmptyPredicate.INSTANCE));

        single("pre_main", null, true,
            (name, entity) -> new HybridAnimationController(entity, name, 0, new EmptyPredicate()));
        single("main", VehicleMainPredicate.ANIM_LIST, true,
            (name, entity) -> new HybridAnimationController(entity, name, 0.1f, new VehicleMainPredicate()));
        single("move", VehicleMovePredicate.ANIM_LIST, true,
            (name, entity) -> new HybridAnimationController(entity, name, 0.1f, new VehicleMovePredicate()));
        simple("origin", (name, entity) -> new VehicleOriginController(entity, name));
        single("ride", VehicleRidePredicate.ANIM_LIST, true,
            (name, entity) -> new HybridAnimationController(entity, name, 0.1f, new VehicleRidePredicate()));
        single("post_main", null, true,
            (name, entity) -> new HybridAnimationController(entity, name, 0, new EmptyPredicate()));

        parallel("parallel", (name, entity, anim) -> new HybridAnimationController(entity, name, 0,
            anim != null ? new ParallelPredicate(anim) : EmptyPredicate.INSTANCE, true));
    }

    public static BiConsumer<CustomVehicleEntity, AnimationData> build(VehicleModelResources model,
        CommonAsset assets) {
        COLLECTION.initialize(VehicleControllerCollection::init);
        return COLLECTION.build(model, assets, model.animations());
    }

    private static ControllerDiscovery<CustomVehicleEntity, VehicleModelResources> simple(String name,
        BiFunction<String, CustomVehicleEntity, IAnimationController<CustomVehicleEntity>> simpleFactory) {
        var controllerName = String.format("%s.%s", CATEGORY, name);
        return COLLECTION.add((model, container, animations) -> (animatable, consumer) -> {
            consumer.accept(simpleFactory.apply(controllerName, animatable));
        });
    }

    private static ControllerDiscovery<CustomVehicleEntity, VehicleModelResources> single(String name,
        String[] animations, boolean hybrid,
        BiFunction<String, CustomVehicleEntity, IAnimationController<CustomVehicleEntity>> controllerFunc) {
        return COLLECTION
            .add(new SingleControllerDiscovery<>(CATEGORY, name, animations, hybrid, Adapter.INSTANCE, controllerFunc));
    }

    private static ControllerDiscovery<CustomVehicleEntity, VehicleModelResources> parallel(String name,
        TriFunction<String, CustomVehicleEntity, String, IAnimationController<CustomVehicleEntity>> controllerFunc) {
        return COLLECTION
            .add(new ParallelControllerDiscovery<>(CATEGORY, name, false, Adapter.INSTANCE, controllerFunc));
    }

    private static class Adapter implements ResourceAdapter<VehicleModelResources> {
        static final Adapter INSTANCE = new Adapter();

        @Override
        public Object2ReferenceMap<String, AnimationControllerData> getControllers(VehicleModelResources model,
            CommonAsset assets) {
            return model.controllers();
        }

        @Override
        public ConditionArmor getArmorCondition(VehicleModelResources model, CommonAsset assets) {
            return null;
        }
    }
}
