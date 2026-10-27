package com.elfmcys.ysm.client.compat.immersivemelodies;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;

public class ImmersiveMelodiesCompat {
    private static final String IMMERSIVE_MELODIES = "immersive_melodies";
    private static boolean IS_LOADED = false;

    public static void init() {
        IS_LOADED = ModList.get().isLoaded(IMMERSIVE_MELODIES);
    }

    public static boolean isLoaded() {
        return IS_LOADED;
    }

    public static void updateMelodyProgress(LivingEntity entity, ImmersiveMelodiesCompat.ImmersiveMelodiesData imData) {
        if (IS_LOADED) {
            ImmersiveMelodiesCompatInner.updateMelodyProgress(entity, imData);
        }
    }

    /**
     * 沉浸音乐模组的数据缓存
     */
    public static final class ImmersiveMelodiesData {
        /**
         * 音高，一般是 0-2 之间
         */
        public float pitch = 0f;
        /**
         * 音量，一般是 0-2 之间
         */
        public float volume = 0f;
        /**
         * 电平强度，范围 0-1
         */
        public float current = 0f;
        /**
         * 自上次音符输出后，经过的时间（单位：ms）
         */
        public long delta = 0L;
        /**
         * 自开始演奏后，经过的时间（单位：ms）
         */
        public long time = 0L;
    }
}
