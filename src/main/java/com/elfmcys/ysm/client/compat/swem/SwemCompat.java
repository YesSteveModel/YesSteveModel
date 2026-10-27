package com.elfmcys.ysm.client.compat.swem;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

public class SwemCompat {
    private static final String MOD_ID = "swem";
    private static boolean INSTALLED;

    public static void init() {
        INSTALLED = ModList.get().isLoaded(MOD_ID);
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }

    @Nullable
    public static String getAnimation(LivingEntity livingEntity) {
        if (isInstalled()) {
            return SwemCompatInner.getAnimation(livingEntity);
        }
        return null;
    }

}
