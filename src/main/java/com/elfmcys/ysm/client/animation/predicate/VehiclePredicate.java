package com.elfmcys.ysm.client.animation.predicate;

import com.elfmcys.ysm.client.animation.condition.ConditionManager;
import com.elfmcys.ysm.client.animation.condition.ConditionalVehicle;
import com.elfmcys.ysm.client.compat.carryon.CarryOnCompat;
import com.elfmcys.ysm.client.compat.swem.SwemCompat;
import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Saddleable;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

import static com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate.playAnimation;

public class VehiclePredicate implements IAnimationPredicate<CustomHumanoidEntity<?>> {
    @Override
    public PlayState test(AnimationEvent<CustomHumanoidEntity<?>> event) {
        PlayState vehicleAnimation = getVehicleAnimation(event);
        return Objects.requireNonNullElse(vehicleAnimation, PlayState.STOP);
    }

    @Nullable
    public PlayState getVehicleAnimation(AnimationEvent<CustomHumanoidEntity<?>> event) {
        LivingEntity entity = event.getAnimatableEntity().getEntity();
        if (entity == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return null;
        }
        Entity vehicle = entity.getVehicle();
        if (vehicle == null || !vehicle.isAlive()) {
            return null;
        }

        String swemAnimation = SwemCompat.getAnimation(entity);
        if (StringUtils.isNoneBlank(swemAnimation)) {
            return playAnimation(event, swemAnimation, LoopType.LOOP);
        }

        ConditionManager conditionManager = event.getAnimatableEntity().getConditionManager();
        ConditionalVehicle vehicleCondition = conditionManager.getVehicle();
        if (vehicleCondition != null) {
            String name = vehicleCondition.doTest(entity);
            if (StringUtils.isNoneBlank(name)) {
                return playAnimation(event, name, LoopType.LOOP);
            }
        }

        // 兼容旧版本的几个动画
        if (vehicle instanceof Pig) {
            return playAnimation(event, "ride_pig", LoopType.LOOP);
        }
        if (vehicle instanceof Saddleable) {
            return playAnimation(event, "ride", LoopType.LOOP);
        }
        if (vehicle instanceof Boat) {
            return playAnimation(event, "boat", LoopType.LOOP);
        }

        // carry on 兼容
        boolean playerIsOnPrincess = entity instanceof Player player && CarryOnCompat.isCarryOnPrincess(player);
        if (playerIsOnPrincess) {
            return playAnimation(event, "carryon:princess", LoopType.LOOP);
        }

        return playAnimation(event, "sit", LoopType.LOOP);
    }
}
