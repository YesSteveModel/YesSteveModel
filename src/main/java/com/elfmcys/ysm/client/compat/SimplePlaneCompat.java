package com.elfmcys.ysm.client.compat;

import com.elfmcys.ysm.client.entity.CustomVehicleEntity;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.core.util.MathUtil;
import com.mojang.math.Axis;
import net.minecraftforge.fml.loading.LoadingModList;
import org.joml.Math;
import org.joml.Vector3f;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.Optional;

public class SimplePlaneCompat {
    private static final String MOD_ID = "simpleplanes";
    private static boolean INSTALLED;

    public static void init() {
        try {
            INSTALLED = LoadingModList.get().getModFileById(MOD_ID) != null;
        } catch (Throwable ignored) {
        }
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }

    public static Optional<Vector3f> getRotation(AnimationEvent<CustomVehicleEntity> event) {
        if (INSTALLED && event.getAnimatableEntity().getEntity() instanceof PlaneEntity planeEntity) {
            var q = xyz.przemyk.simpleplanes.misc.MathUtil.lerpQ(event.getPartialTick(), planeEntity.getQ_Prev(),
                planeEntity.getQ_Client());

            q.premul(Axis.YP.rotation(-MathUtil.degreesToRadians(planeEntity.getViewYRot(event.getPartialTick()))));

            float timeSinceHitWithPartial = (float)planeEntity.getTimeSinceHit() - event.getPartialTick();
            if (timeSinceHitWithPartial > 0.0F) {
                float angle = Math.clamp(timeSinceHitWithPartial / 10.0F, -30, 30);
                timeSinceHitWithPartial = planeEntity.tickCount + event.getPartialTick();
                q.rotateZ(Math.sin(timeSinceHitWithPartial) * angle);
            }

            var rot = new Vector3f();
            MathUtil.getEulerAnglesZYX(q, rot);
            rot.x = -rot.x;
            return Optional.of(rot);
        }
        return Optional.empty();
    }
}
