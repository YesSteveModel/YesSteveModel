package com.elfmcys.ysm.client.compat.swem;

import com.alaharranhonor.swem.forge.entities.horse.SWEMHorseEntityBase;
import com.google.common.collect.Maps;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Locale;

public class SwemCompatInner {
    private static final EnumMap<SWEMHorseEntityBase.Gait, String> CACHE =
        Maps.newEnumMap(SWEMHorseEntityBase.Gait.class);

    @Nullable
    @SuppressWarnings("all")
    static String getAnimation(LivingEntity livingEntity) {
        if (livingEntity.getVehicle() instanceof SWEMHorseEntityBase horse) {
            SWEMHorseEntityBase.Gait gait = horse.getGait();
            // 判断跳跃
            double jumpHeight = horse.jumpHeight;
            if (jumpHeight > 0) {
                int jumpAnimIndex = Math.min(Mth.ceil(jumpHeight), 5) - 1;
                return "swem:jump_lv" + (jumpAnimIndex + 1);
            }
            if (notMoving(horse)) {
                return "swem:idle";
            }
            return CACHE.computeIfAbsent(gait, g -> "swem:" + g.name().toLowerCase(Locale.ENGLISH));
        }
        return null;
    }

    static boolean notMoving(SWEMHorseEntityBase horse) {
        double x = horse.getX() - horse.xo;
        double z = horse.getZ() - horse.zo;
        return Math.sqrt(x * x + z * z) <= 0d;
    }
}
