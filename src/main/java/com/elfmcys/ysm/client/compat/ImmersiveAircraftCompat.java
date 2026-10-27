package com.elfmcys.ysm.client.compat;

import com.elfmcys.ysm.client.entity.CustomVehicleEntity;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.core.util.MathUtil;
import com.mojang.math.Axis;
import immersive_aircraft.entity.AircraftEntity;
import net.minecraftforge.fml.loading.LoadingModList;
import org.joml.Vector3f;

import java.util.Optional;

public class ImmersiveAircraftCompat {
    private static final String MOD_ID = "immersive_aircraft";
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
        if (INSTALLED && event.getAnimatableEntity().getEntity() instanceof AircraftEntity planeEntity) {
            Vector3f effect = planeEntity.onGround() ? new Vector3f(0.0f, 0.0f, 0.0f) : planeEntity.getWindEffect();
            var rot = new Vector3f();
            MathUtil.getEulerAnglesZYX(Axis.XP.rotationDegrees(effect.z).rotateZ(MathUtil.degreesToRadians(effect.x))
                .rotateX(-MathUtil.degreesToRadians(planeEntity.getViewXRot(event.getPartialTick())))
                .rotateZ(-MathUtil.degreesToRadians(planeEntity.getRoll(event.getPartialTick()))), rot);
            return Optional.of(rot);
        }
        return Optional.empty();
    }
}
