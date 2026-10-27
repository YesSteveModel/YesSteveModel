package com.elfmcys.ysm.client.controller.collections;

import com.elfmcys.ysm.client.animation.condition.ConditionArmor;
import com.elfmcys.ysm.client.animation.predicate.*;
import com.elfmcys.ysm.client.controller.*;
import com.elfmcys.ysm.client.entity.CustomFirstPersonArmEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.model.resource.client.CommonAsset;
import com.elfmcys.ysm.model.resource.client.PlayerModelResources;
import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData;
import com.elfmcys.ysm.geckolib3.core.controller.HybridAnimationController;
import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import net.minecraft.world.entity.EquipmentSlot;
import org.apache.commons.lang3.function.TriFunction;

import java.util.function.BiFunction;
import java.util.function.BiConsumer;
import com.elfmcys.ysm.geckolib3.core.manager.AnimationData;

public class FPArmControllerCollection {
    private static final String CATEGORY = "fp.arm";
    private static final AnimationControllerCollection<CustomFirstPersonArmEntity, PlayerModelResources> COLLECTION =
        new AnimationControllerCollection<>();

    @SuppressWarnings("rawtypes,unchecked,deprecation")
    private static void init() {
        single("misc", null, true,
            (name, entity) -> new HybridAnimationController(entity, name, 0, new EmptyPredicate()));
        parallel("parallel", (name, entity, anim) -> new HybridAnimationController(entity, name, 0,
            anim != null ? new ParallelPredicate(anim) : EmptyPredicate.INSTANCE, true));
        armor("armor",
            (name, entity, slot) -> new HybridAnimationController(entity, name, 0, new FPArmArmorPredicate(slot)));
    }

    public static BiConsumer<CustomFirstPersonArmEntity, AnimationData> build(PlayerModelResources model,
        CommonAsset assets) {
        COLLECTION.initialize(FPArmControllerCollection::init);
        return COLLECTION.build(model, assets, model.fpArmAnimations());
    }

    private static void simple(String name, BiFunction<String, CustomFirstPersonArmEntity,
        IAnimationController<CustomFirstPersonArmEntity>> simpleFactory) {
        simple(name, false, simpleFactory);
    }

    private static void simple(String name, boolean guiOnly, BiFunction<String, CustomFirstPersonArmEntity,
        IAnimationController<CustomFirstPersonArmEntity>> simpleFactory) {
        var controllerName = String.format("%s.%s", CATEGORY, name);
        ControllerDiscovery<CustomFirstPersonArmEntity, PlayerModelResources> discovery =
            (model, asset, animations) -> (animatable, consumer) -> {
                consumer.accept(simpleFactory.apply(controllerName, animatable));
            };
        if (guiOnly) {
            discovery = discovery.withCondition(entity -> entity instanceof IPreviewEntity);
        }
        COLLECTION.add(discovery);
    }

    private static void multi(String regex, BiFunction<String, CustomFirstPersonArmEntity,
        IAnimationController<CustomFirstPersonArmEntity>> controllerFunc) {
        COLLECTION.add(new MultiControllerDiscovery<>(CATEGORY, regex, Adapter.INSTANCE, controllerFunc));
    }

    private static void single(String name, String[] animations, boolean hybrid, BiFunction<String,
        CustomFirstPersonArmEntity, IAnimationController<CustomFirstPersonArmEntity>> controllerFunc) {
        COLLECTION
            .add(new SingleControllerDiscovery<>(CATEGORY, name, animations, hybrid, Adapter.INSTANCE, controllerFunc));
    }

    private static void parallel(String name, TriFunction<String, CustomFirstPersonArmEntity, String,
        IAnimationController<CustomFirstPersonArmEntity>> controllerFunc) {
        COLLECTION.add(new ParallelControllerDiscovery<>(CATEGORY, name, true, Adapter.INSTANCE, controllerFunc));
    }

    private static void armor(String name, TriFunction<String, CustomFirstPersonArmEntity, EquipmentSlot,
        IAnimationController<CustomFirstPersonArmEntity>> controllerFunc) {
        COLLECTION.add(new ArmorControllerDiscovery<>(CATEGORY, name, Adapter.INSTANCE, controllerFunc));
    }

    private static class Adapter implements ResourceAdapter<PlayerModelResources> {
        static final Adapter INSTANCE = new Adapter();

        @Override
        public Object2ReferenceMap<String, AnimationControllerData> getControllers(PlayerModelResources model,
            CommonAsset assets) {
            return model.fpArmAnimationControllers();
        }

        @Override
        public ConditionArmor getArmorCondition(PlayerModelResources model, CommonAsset assets) {
            return model.fpArmConditionManager().getArmor();
        }
    }
}
