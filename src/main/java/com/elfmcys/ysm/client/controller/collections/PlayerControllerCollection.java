package com.elfmcys.ysm.client.controller.collections;

import com.elfmcys.ysm.client.animation.condition.ConditionArmor;
import com.elfmcys.ysm.client.animation.predicate.*;
import com.elfmcys.ysm.client.compat.carryon.CarryOnCompat;
import com.elfmcys.ysm.client.compat.parcool.ParCoolCompat;
import com.elfmcys.ysm.client.controller.*;
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.model.resource.client.CommonAsset;
import com.elfmcys.ysm.model.resource.client.PlayerModelResources;
import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData;
import com.elfmcys.ysm.geckolib3.core.controller.CodedAnimationController;
import com.elfmcys.ysm.geckolib3.core.controller.HybridAnimationController;
import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import net.minecraft.world.entity.EquipmentSlot;
import org.apache.commons.lang3.function.TriFunction;

import java.util.function.BiFunction;
import java.util.function.BiConsumer;
import com.elfmcys.ysm.geckolib3.core.manager.AnimationData;

public class PlayerControllerCollection {
    private static final AnimationControllerCollection<CustomPlayerEntity, PlayerModelResources> COLLECTION =
        new AnimationControllerCollection<>();
    private static final String CATEGORY = "player";
    public static final String CAP_CONTROLLER = String.format("%s.%s", CATEGORY, "cap");

    @SuppressWarnings("rawtypes,unchecked,deprecation")
    private static void init() {
        parallel("pre_parallel", (name, entity, anim) -> new HybridAnimationController(entity, name, 0,
            anim != null ? new ParallelPredicate(anim) : EmptyPredicate.INSTANCE));

        ParCoolCompat.animationPredicate().ifPresent(func -> simple("parcool", func));
        simple("vehicle", (name, entity) -> new HybridAnimationController(entity, name, 0.1f, new VehiclePredicate()));

        multi("pre_main", (name, entity) -> new HybridAnimationController(entity, name, 0, new EmptyPredicate()));
        simple("main", (name, entity) -> new HybridAnimationController(entity, name, 0.1f, new PlayerMainPredicate()));
        multi("post_main", (name, entity) -> new HybridAnimationController(entity, name, 0, new EmptyPredicate()));

        multi("pre_hold", (name, entity) -> new HybridAnimationController(entity, name, 0, new EmptyPredicate()));
        simple("hold_offhand",
            (name, entity) -> new HybridAnimationController(entity, name, 0.1f, new OffhandPredicate()));
        simple("hold_mainhand",
            (name, entity) -> new HybridAnimationController(entity, name, 0.1f, new MainhandPredicate()));
        multi("post_hold", (name, entity) -> new HybridAnimationController(entity, name, 0, new EmptyPredicate()));

        if (GunFirePredicate.available()) {
            simple("fire", (name, entity) -> new HybridAnimationController(entity, name, 0f, new GunFirePredicate()));
        }

        multi("pre_swing", (name, entity) -> new HybridAnimationController(entity, name, 0, new EmptyPredicate()));
        simple("swing", (name, entity) -> new HybridAnimationController(entity, name, 0, new SwingPredicate()));
        multi("post_swing", (name, entity) -> new HybridAnimationController(entity, name, 0, new EmptyPredicate()));

        multi("pre_use", (name, entity) -> new HybridAnimationController(entity, name, 0, new EmptyPredicate()));
        simple("use", (name, entity) -> new HybridAnimationController(entity, name, 0.1f, new UsePredicate()));
        multi("post_use", (name, entity) -> new HybridAnimationController(entity, name, 0, new EmptyPredicate()));

        simple("passenger",
            (name, entity) -> new HybridAnimationController(entity, name, 0.1f, new PassengerPredicate()));
        CarryOnCompat.animationPredicate().ifPresent(func -> simple("carry_on", func));

        // 下面不需要自定义动画控制器
        simple("cap", (name, entity) -> new CodedAnimationController(entity, name, 0, new CapPredicate()));
        simple("gui_hover", true,
            (name, entity) -> new CodedAnimationController(entity, name, 0, new HoverPredicate()));
        simple("gui_focus", true,
            (name, entity) -> new CodedAnimationController(entity, name, 0, new FocusPredicate()));

        parallel("parallel", (name, entity, anim) -> new HybridAnimationController(entity, name, 0,
            anim != null ? new ParallelPredicate(anim) : EmptyPredicate.INSTANCE, true));

        armor("armor",
            (name, entity, slot) -> new HybridAnimationController(entity, name, 0, new ArmorPredicate(slot)));
    }

    public static BiConsumer<CustomPlayerEntity, AnimationData> build(PlayerModelResources model, CommonAsset assets) {
        COLLECTION.initialize(PlayerControllerCollection::init);
        return COLLECTION.build(model, assets, model.animations());
    }

    private static void simple(String name,
        BiFunction<String, CustomPlayerEntity, IAnimationController<CustomPlayerEntity>> simpleFactory) {
        simple(name, false, simpleFactory);
    }

    private static void simple(String name, boolean guiOnly,
        BiFunction<String, CustomPlayerEntity, IAnimationController<CustomPlayerEntity>> simpleFactory) {
        var controllerName = String.format("%s.%s", CATEGORY, name);
        ControllerDiscovery<CustomPlayerEntity, PlayerModelResources> discovery =
            (model, asset, animations) -> (animatable, consumer) -> {
                consumer.accept(simpleFactory.apply(controllerName, animatable));
            };
        if (guiOnly) {
            discovery = discovery.withCondition(entity -> entity instanceof IPreviewEntity);
        }
        COLLECTION.add(discovery);
    }

    private static void multi(String regex,
        BiFunction<String, CustomPlayerEntity, IAnimationController<CustomPlayerEntity>> controllerFunc) {
        COLLECTION.add(new MultiControllerDiscovery<>(CATEGORY, regex, Adapter.INSTANCE, controllerFunc));
    }

    private static void single(String name, String[] animations, boolean hybrid,
        BiFunction<String, CustomPlayerEntity, IAnimationController<CustomPlayerEntity>> controllerFunc) {
        COLLECTION
            .add(new SingleControllerDiscovery<>(CATEGORY, name, animations, hybrid, Adapter.INSTANCE, controllerFunc));
    }

    private static void parallel(String name,
        TriFunction<String, CustomPlayerEntity, String, IAnimationController<CustomPlayerEntity>> controllerFunc) {
        COLLECTION.add(new ParallelControllerDiscovery<>(CATEGORY, name, true, Adapter.INSTANCE, controllerFunc));
    }

    private static void armor(String name, TriFunction<String, CustomPlayerEntity, EquipmentSlot,
        IAnimationController<CustomPlayerEntity>> controllerFunc) {
        COLLECTION.add(new ArmorControllerDiscovery<>(CATEGORY, name, Adapter.INSTANCE, controllerFunc));
    }

    private static class Adapter implements ResourceAdapter<PlayerModelResources> {
        static final Adapter INSTANCE = new Adapter();

        @Override
        public Object2ReferenceMap<String, AnimationControllerData> getControllers(PlayerModelResources model,
            CommonAsset assets) {
            return model.animationControllers();
        }

        @Override
        public ConditionArmor getArmorCondition(PlayerModelResources model, CommonAsset assets) {
            return model.conditionManager().getArmor();
        }
    }
}
