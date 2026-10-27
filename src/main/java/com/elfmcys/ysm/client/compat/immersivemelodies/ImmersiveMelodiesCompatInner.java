package com.elfmcys.ysm.client.compat.immersivemelodies;

import immersive_melodies.client.MelodyProgress;
import immersive_melodies.client.MelodyProgressManager;
import immersive_melodies.client.animation.EntityModelAnimator;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;

public class ImmersiveMelodiesCompatInner {
    static void updateMelodyProgress(LivingEntity entity, ImmersiveMelodiesCompat.ImmersiveMelodiesData imData) {
        Item item = EntityModelAnimator.getInstrument(entity);
        if (item != null) {
            float time = (Minecraft.getInstance().isPaused() ? 0.0F : Minecraft.getInstance().getFrameTime())
                + (float)entity.tickCount;
            MelodyProgress progress = MelodyProgressManager.INSTANCE.getProgress(entity);
            progress.visualTick(time);
            imData.pitch = progress.getCurrentPitch();
            imData.volume = progress.getCurrentVolume();
            imData.current = progress.getCurrent();
            imData.delta = progress.delta();
            imData.time = progress.getTime();
        }
    }
}
